<#
.SYNOPSIS
    ShortLink 端到端联调：起容器 → 建链 → 跳转 → 断言各类错误分支。

.DESCRIPTION
    默认会先 mvn package 再 docker compose up --build，跑完 down -v 清理。
    依赖：JDK 17+、Maven、Docker Desktop、curl.exe（Windows 10+ 自带）。
    兼容 Windows PowerShell 5.1 与 PowerShell 7。

.EXAMPLE
    powershell -File scripts/e2e.ps1          # 全流程
    powershell -File scripts/e2e.ps1 -SkipBuild
    powershell -File scripts/e2e.ps1 -KeepRunning
#>
param(
    [switch]$SkipBuild,
    [switch]$KeepRunning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo         = Split-Path -Parent $PSScriptRoot
$composeFile  = Join-Path $repo 'deploy/docker-compose.yml'
$adminBase    = 'http://127.0.0.1:8082'
$redirectBase = 'http://127.0.0.1:8081'

$script:passed = 0
$script:failed = 0

function Assert-True {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) {
        $script:passed++
        Write-Host "  [PASS] $Name" -ForegroundColor Green
    } else {
        $script:failed++
        Write-Host "  [FAIL] $Name $Detail" -ForegroundColor Red
    }
}

function Wait-Ready {
    param([string]$Name, [scriptblock]$Probe, [int]$TimeoutSec = 180)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            if ((& $Probe) -eq $true) {
                Write-Host "  $Name 已就绪" -ForegroundColor DarkGray
                return $true
            }
        } catch { }
        Start-Sleep -Seconds 2
    }
    Write-Host "  $Name 在 ${TimeoutSec}s 内未就绪" -ForegroundColor Red
    return $false
}

# 用 curl.exe 而不是 Invoke-WebRequest：需要精确拿到 302 与 Location，
# 且不能被自动跟随。这也让脚本不依赖 PowerShell 7 的 -SkipHttpErrorCheck。
function Get-RedirectResult {
    param([string]$Url)
    $raw = & curl.exe -s -o NUL -D - --max-redirs 0 $Url 2>$null
    $text = ($raw | Out-String)
    $status = 0
    if ($text -match 'HTTP/\d(?:\.\d)?\s+(\d{3})') { $status = [int]$Matches[1] }
    $location = ''
    if ($text -match '(?im)^Location:\s*(.+?)\r?$') { $location = $Matches[1].Trim() }
    return @{ Status = $status; Location = $location }
}

function Get-HttpBody {
    param([string]$Url)
    return ((& curl.exe -s $Url 2>$null) | Out-String)
}

function New-ShortLink {
    param([string]$LongUrl, [string]$Title = 'e2e')
    $body = @{ longUrl = $LongUrl; title = $Title } | ConvertTo-Json -Compress
    return Invoke-RestMethod -Uri "$adminBase/api/v1/links" -Method Post `
        -ContentType 'application/json; charset=utf-8' -Body $body
}

function Set-RedisHashField {
    param([string]$Code, [string]$Field, [string]$Value)
    & docker compose -f $composeFile exec -T redis redis-cli HSET "sl:link:$Code" $Field $Value | Out-Null
}

# ---------------------------------------------------------------------
Write-Host "`n=== 1/5 构建并启动 ===" -ForegroundColor Cyan
if (-not $SkipBuild) {
    Push-Location $repo
    try {
        & mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'mvn package 失败，先修编译错误' }
    } finally { Pop-Location }
}

& docker compose -f $composeFile up -d --build
if ($LASTEXITCODE -ne 0) { throw 'docker compose up 失败' }

Write-Host "`n=== 2/5 等待服务就绪 ===" -ForegroundColor Cyan
$adminOk = Wait-Ready 'admin' {
    $status = & curl.exe -s -o NUL -w '%{http_code}' "$adminBase/actuator/health" 2>$null
    if ("$status" -ne '200') { return $false }
    return ((& curl.exe -s "$adminBase/actuator/health" 2>$null | Out-String) -match '"status":"UP"')
}
$redirectOk = Wait-Ready 'redirect' {
    return ((& curl.exe -s "$redirectBase/ping" 2>$null | Out-String).Trim() -eq 'pong')
}
if (-not ($adminOk -and $redirectOk)) {
    Write-Host "`n服务未就绪，输出最后 40 行日志：" -ForegroundColor Yellow
    & docker compose -f $composeFile logs --tail 40
    exit 1
}

# ---------------------------------------------------------------------
Write-Host "`n=== 3/5 核心链路 ===" -ForegroundColor Cyan
$unique = [guid]::NewGuid().ToString('N').Substring(0, 8)
$longUrl = "https://example.com/e2e/$unique?a=1"

$created = New-ShortLink -LongUrl $longUrl
Assert-True '创建接口返回 code=0' ($created.code -eq 0) "实际 $($created.code)"
$code = $created.data.shortCode
Assert-True '短码长度为 7' ($code.Length -eq 7) "实际 '$code'"
Assert-True 'shortUrl 拼接正确' ($created.data.shortUrl.EndsWith("/$code")) "实际 $($created.data.shortUrl)"

$r = Get-RedirectResult "$redirectBase/$code"
Assert-True '跳转返回 302' ($r.Status -eq 302) "实际 $($r.Status)"
Assert-True 'Location 指向原始长链' ($r.Location -eq $longUrl) "实际 '$($r.Location)'"

$detail = Invoke-RestMethod -Uri "$adminBase/api/v1/links/$($created.data.gid)"
Assert-True '详情接口可查' ($detail.data.shortCode -eq $code)

# ---------------------------------------------------------------------
Write-Host "`n=== 4/5 错误分支 ===" -ForegroundColor Cyan

$miss = Get-RedirectResult "$redirectBase/zzzzzzz"
Assert-True '不存在的短码返回 404' ($miss.Status -eq 404) "实际 $($miss.Status)"

$bad = Get-RedirectResult "$redirectBase/abc"
Assert-True '格式非法返回 404' ($bad.Status -eq 404) "实际 $($bad.Status)"

# 停用/过期都用全新短码，避免被 L1 缓存挡住（当前没有 MQ 失效）
$disabled = New-ShortLink -LongUrl "https://example.com/disabled/$unique"
Set-RedisHashField -Code $disabled.data.shortCode -Field 'status' -Value '0'
$r2 = Get-RedirectResult "$redirectBase/$($disabled.data.shortCode)"
Assert-True '停用短链返回 403' ($r2.Status -eq 403) "实际 $($r2.Status)"

$expired = New-ShortLink -LongUrl "https://example.com/expired/$unique"
Set-RedisHashField -Code $expired.data.shortCode -Field 'expireTime' -Value '1'
$r3 = Get-RedirectResult "$redirectBase/$($expired.data.shortCode)"
Assert-True '过期短链返回 410' ($r3.Status -eq 410) "实际 $($r3.Status)"

Assert-True '错误页是 HTML' ((Get-HttpBody "$redirectBase/zzzzzzz") -match '<!doctype html>')

# ---------------------------------------------------------------------
Write-Host "`n=== 5/5 可观测性 ===" -ForegroundColor Cyan
$metrics = Get-HttpBody "$redirectBase/actuator/prometheus"
Assert-True '暴露 redirect 结果计数' ($metrics -match 'shortlink_redirect_total')
Assert-True '暴露延迟指标' ($metrics -match 'shortlink_redirect_latency')

# ---------------------------------------------------------------------
Write-Host "`n=== 结果 ===" -ForegroundColor Cyan
$color = if ($script:failed -eq 0) { 'Green' } else { 'Red' }
Write-Host ("  通过 " + $script:passed + " / 失败 " + $script:failed) -ForegroundColor $color

if (-not $KeepRunning) {
    Write-Host "`n清理容器与数据卷..." -ForegroundColor DarkGray
    & docker compose -f $composeFile down -v | Out-Null
} else {
    Write-Host "`n容器保留中，手动清理：docker compose -f $composeFile down -v" -ForegroundColor DarkGray
}

if ($script:failed -gt 0) { exit 1 } else { exit 0 }
