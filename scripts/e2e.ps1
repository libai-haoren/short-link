<#
.SYNOPSIS
    ShortLink 端到端联调：起容器 → 建链 → 列表 → 改链立即生效 → 封禁立即生效 → 缓存重建。

.DESCRIPTION
    V2 之后，"改链/封禁立即生效"不再用"全新短码"规避 L1，而是真正断言失效通道生效。
    默认会先 mvn package 再 docker compose up --build，跑完 down -v 清理。
    依赖：JDK 17+、Maven、Docker Desktop、curl.exe（Windows 10+ 自带）。
    兼容 Windows PowerShell 5.1 与 PowerShell 7。

.EXAMPLE
    powershell -File scripts/e2e.ps1              # 全流程
    powershell -File scripts/e2e.ps1 -SkipBuild
    powershell -File scripts/e2e.ps1 -SkipCompose # 服务已在跑，只跑断言
    powershell -File scripts/e2e.ps1 -KeepRunning
#>
param(
    [switch]$SkipBuild,
    [switch]$SkipCompose,
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

function Update-ShortLink {
    param([string]$Gid, [string]$LongUrl, [string]$Title, $ExpireTime = $null)
    $payload = @{ longUrl = $LongUrl; title = $Title; maxClick = 0 }
    if ($null -ne $ExpireTime) { $payload['expireTime'] = $ExpireTime }
    $body = $payload | ConvertTo-Json -Compress
    return Invoke-RestMethod -Uri "$adminBase/api/v1/links/$Gid" -Method Put `
        -ContentType 'application/json; charset=utf-8' -Body $body
}

function Get-RedisHashField {
    param([string]$Code, [string]$Field)
    $out = & docker compose -f $composeFile exec -T redis redis-cli HGET "sl:link:$Code" $Field 2>$null
    return (($out | Out-String).Trim())
}

# ---------------------------------------------------------------------
Write-Host "`n=== 1/13 构建并启动 ===" -ForegroundColor Cyan
if ($SkipCompose) {
    Write-Host "  -SkipCompose：跳过构建与容器，直接测试已在运行的服务" -ForegroundColor DarkGray
} else {
    if (-not $SkipBuild) {
        Push-Location $repo
        try {
            & mvn -q -DskipTests package
            if ($LASTEXITCODE -ne 0) { throw 'mvn package 失败，先修编译错误' }
        } finally { Pop-Location }
    }

    & docker compose -f $composeFile up -d --build
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up 失败' }
}

Write-Host "`n=== 2/13 等待服务就绪 ===" -ForegroundColor Cyan
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
Write-Host "`n=== 3/13 核心链路 ===" -ForegroundColor Cyan
$unique = [guid]::NewGuid().ToString('N').Substring(0, 8)
$longUrl = "https://example.com/e2e/${unique}?a=1"

$created = New-ShortLink -LongUrl $longUrl
Assert-True '创建接口返回 code=0' ($created.code -eq 0) "实际 $($created.code)"
$code = $created.data.shortCode
$gid = $created.data.gid
Assert-True '短码长度为 7' ($code.Length -eq 7) "实际 '$code'"
Assert-True 'shortUrl 拼接正确' ($created.data.shortUrl.EndsWith("/$code")) "实际 $($created.data.shortUrl)"

$r = Get-RedirectResult "$redirectBase/$code"
Assert-True '跳转返回 302' ($r.Status -eq 302) "实际 $($r.Status)"
Assert-True 'Location 指向原始长链' ($r.Location -eq $longUrl) "实际 '$($r.Location)'"

$detail = Invoke-RestMethod -Uri "$adminBase/api/v1/links/$gid"
Assert-True '详情接口可查' ($detail.data.shortCode -eq $code)

# ---------------------------------------------------------------------
Write-Host "`n=== 4/13 列表与改链（立即生效）===" -ForegroundColor Cyan

$list = Invoke-RestMethod -Uri "$adminBase/api/v1/links?size=5"
# @() 强制成数组：PS 5.1 的 ConvertFrom-Json 对单元素数组会返回单个对象
$items = @($list.data.items)
Assert-True '列表返回数据' ($items.Count -gt 0) "实际 $($items.Count) 条"
Assert-True '列表返回分页字段' ($list.data.PSObject.Properties.Name -contains 'nextCursor') "缺少 nextCursor"
Assert-True '列表按创建时间倒序' ($items[0].createTime -ge $items[-1].createTime)

# 关键：$code 刚才已经被访问过，一定在 redirect 的 L1 里。
# V2 之前这里必须换新短码才能验证通过；现在直接改同一个码，能测出失效通道是否真的生效。
$newUrl = "https://example.com/new/$unique"
$updated = Update-ShortLink -Gid $gid -LongUrl $newUrl -Title 'updated'
Assert-True '修改后 longUrl 已变' ($updated.data.longUrl -eq $newUrl) "实际 $($updated.data.longUrl)"
Assert-True '修改后 title 已变' ($updated.data.title -eq 'updated') "实际 '$($updated.data.title)'"

$r4 = Get-RedirectResult "$redirectBase/$code"
Assert-True '改链后同一个短码立即跳新地址' ($r4.Location -eq $newUrl) "实际 '$($r4.Location)'（若仍是旧地址说明失效通道没生效）"
Assert-True 'Redis 里的 longUrl 同步更新' ((Get-RedisHashField -Code $code -Field 'longUrl') -eq $newUrl)

# ---------------------------------------------------------------------
Write-Host "`n=== 5/13 错误分支 ===" -ForegroundColor Cyan

$miss = Get-RedirectResult "$redirectBase/zzzzzzz"
Assert-True '不存在的短码返回 404' ($miss.Status -eq 404) "实际 $($miss.Status)"

$bad = Get-RedirectResult "$redirectBase/abc"
Assert-True '格式非法返回 404' ($bad.Status -eq 404) "实际 $($bad.Status)"

# 封禁同样用"已经访问过"的短码，才能验证失效生效
$banned = New-ShortLink -LongUrl "https://example.com/ban/$unique"
$banCode = $banned.data.shortCode
$banGid = $banned.data.gid
$before = Get-RedirectResult "$redirectBase/$banCode"
Assert-True '封禁前可正常跳转' ($before.Status -eq 302) "实际 $($before.Status)"

$banResp = Invoke-RestMethod -Uri "$adminBase/api/v1/links/$banGid/disable" -Method Post
Assert-True '封禁接口返回 status=0' ($banResp.data.status -eq 0) "实际 $($banResp.data.status)"
$afterBan = Get-RedirectResult "$redirectBase/$banCode"
Assert-True '封禁后同一个短码立即 403' ($afterBan.Status -eq 403) "实际 $($afterBan.Status)（若仍 302 说明失效通道没生效）"

$enableResp = Invoke-RestMethod -Uri "$adminBase/api/v1/links/$banGid/enable" -Method Post
Assert-True '解封接口返回 status=1' ($enableResp.data.status -eq 1) "实际 $($enableResp.data.status)"

# 过期：改成过去的时间，等价于立即过期（410，与停用的 403 语义不同）
$past = (Get-Date).AddDays(-1).ToString('yyyy-MM-ddTHH:mm:ss')
$null = Update-ShortLink -Gid $gid -LongUrl $newUrl -Title 'expired' -ExpireTime $past
$r6 = Get-RedirectResult "$redirectBase/$code"
Assert-True '过期短链返回 410' ($r6.Status -eq 410) "实际 $($r6.Status)"

Assert-True '错误页是 HTML' ((Get-HttpBody "$redirectBase/zzzzzzz") -match '<!doctype html>')

# ---------------------------------------------------------------------
Write-Host "`n=== 6/13 缓存重建（Redis 全丢后的恢复）===" -ForegroundColor Cyan

# 这条链接建好但从未访问过，所以不在 L1 里，FLUSHALL 后必然是 404
$orphan = New-ShortLink -LongUrl "https://example.com/orphan/$unique"
$orphanCode = $orphan.data.shortCode
$orphanLong = $orphan.data.longUrl

& docker compose -f $composeFile exec -T redis redis-cli FLUSHALL | Out-Null
Start-Sleep -Milliseconds 500

$afterFlush = Get-RedirectResult "$redirectBase/$orphanCode"
Assert-True 'FLUSHALL 后未缓存短码返回 404' ($afterFlush.Status -eq 404) "实际 $($afterFlush.Status)"

# 版本号兜底：$banCode 刚才被访问过、在 L1 里；FLUSHALL 会把版本号一起清掉，
# 轮询（1s）应当发现变化并清空 L1
Start-Sleep -Seconds 2
$healed = Get-RedirectResult "$redirectBase/$banCode"
Assert-True '版本号兜底：FLUSHALL 后 L1 被清空（变 404）' ($healed.Status -eq 404) "实际 $($healed.Status)"

$rebuild = Invoke-RestMethod -Uri "$adminBase/api/v1/admin/cache/rebuild?batchSize=500" -Method Post
Assert-True '重建接口扫描到数据' ($rebuild.data.scanned -gt 0) "实际 $($rebuild.data.scanned)"
Assert-True '重建无写入失败' ($rebuild.data.failed -eq 0) "failed=$($rebuild.data.failed)"

$afterRebuild = Get-RedirectResult "$redirectBase/$orphanCode"
Assert-True '重建后跳转恢复 302' ($afterRebuild.Status -eq 302) "实际 $($afterRebuild.Status)"
Assert-True '重建后 Location 正确' ($afterRebuild.Location -eq $orphanLong) "实际 '$($afterRebuild.Location)'"

# ---------------------------------------------------------------------
# ---------------------------------------------------------------------
Write-Host "`n=== 7/13 网关鉴权 ===" -ForegroundColor Cyan
$gatewayBase = 'http://127.0.0.1:8080'
$statsBase = 'http://127.0.0.1:8085'
$jobBase = 'http://127.0.0.1:8086'

$noToken = & curl.exe -s -o NUL -w '%{http_code}' "$gatewayBase/api/v1/links" 2>$null
Assert-True '无 token 访问被拒绝（401）' ("$noToken" -eq '401') "实际 $noToken"

$badToken = & curl.exe -s -o NUL -w '%{http_code}' -H 'Authorization: Bearer fake.token.value' "$gatewayBase/api/v1/links" 2>$null
Assert-True '伪造 token 被拒绝（401）' ("$badToken" -eq '401') "实际 $badToken"

$login = Invoke-RestMethod -Uri "$gatewayBase/api/v1/auth/login" -Method Post `
    -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}'
$token = $login.data.token
Assert-True '登录拿到 token' ($token.Length -gt 20) "实际 '$token'"

$authed = & curl.exe -s -o NUL -w '%{http_code}' -H "Authorization: Bearer $token" "$gatewayBase/api/v1/links?size=1" 2>$null
Assert-True '带 token 访问通过（200）' ("$authed" -eq '200') "实际 $authed"

# 用 curl 而不是 Invoke-RestMethod：后者遇到 4xx 会抛异常，直接把脚本打断
$meJson = (& curl.exe -s -H "Authorization: Bearer $token" "$gatewayBase/api/v1/auth/me" 2>$null | Out-String)
Assert-True '网关透传 X-User-Id' ($meJson -match '"userId"\s*:\s*"1"') "实际 $meJson"

# 安全用例：客户端自己塞 X-User-Id 必须被网关剥掉，否则就是越权
$spoofJson = (& curl.exe -s -H "Authorization: Bearer $token" -H 'X-User-Id: 999' "$gatewayBase/api/v1/auth/me" 2>$null | Out-String)
Assert-True '客户端伪造的 X-User-Id 被剥离' ($spoofJson -match '"userId"\s*:\s*"1"' -and $spoofJson -notmatch '999') "实际 $spoofJson"

# ---------------------------------------------------------------------
Write-Host "`n=== 8/13 IP 黑名单（网关 + redirect 双端生效）===" -ForegroundColor Cyan

# 容器里看到的客户端 IP 不是 127.0.0.1，而是 Docker 网桥地址（如 172.20.0.1）。
# 所以先问网关"它看到的来源是谁"，再按这个 IP 拉黑——写死 127.0.0.1 永远测不出效果。
$meForIp = (& curl.exe -s -H "Authorization: Bearer $token" "$gatewayBase/api/v1/auth/me" 2>$null | Out-String)
$clientIp = if ($meForIp -match '"clientIp"\s*:\s*"([^"]+)"') { $Matches[1] } else { '' }
Assert-True '能拿到网关视角的客户端 IP' ($clientIp.Length -gt 0) "实际 '$clientIp'"

# 用 Invoke-RestMethod 而不是 curl.exe：PS 5.1 调原生程序时会把 JSON 里的双引号转义坏，
# 后端收到非法 JSON，表现为"接口 200 但数据没写进去"这种极难查的问题。
$banBody = @{ ip = $clientIp } | ConvertTo-Json -Compress
Invoke-RestMethod -Uri "$adminBase/api/v1/blacklist/ip" -Method Post `
    -ContentType 'application/json' -Body $banBody | Out-Null
# 黑名单是每 2s 拉一次全量快照，不是每请求查 Redis
Start-Sleep -Seconds 3

$gwBlocked = & curl.exe -s -o NUL -w '%{http_code}' -H "Authorization: Bearer $token" "$gatewayBase/api/v1/links?size=1" 2>$null
Assert-True '拉黑后网关返回 403' ("$gwBlocked" -eq '403') "实际 $gwBlocked"

$rdBlocked = & curl.exe -s -o NUL -w '%{http_code}' "$redirectBase/zzzzzzz" 2>$null
Assert-True '拉黑后 redirect 返回 403' ("$rdBlocked" -eq '403') "实际 $rdBlocked"

Invoke-RestMethod -Uri "$adminBase/api/v1/blacklist/ip/$clientIp" -Method Delete | Out-Null
Start-Sleep -Seconds 3

$gwRestored = & curl.exe -s -o NUL -w '%{http_code}' -H "Authorization: Bearer $token" "$gatewayBase/api/v1/links?size=1" 2>$null
Assert-True '解除后网关恢复（不再 403）' ("$gwRestored" -ne '403') "实际 $gwRestored"

# ---------------------------------------------------------------------
Write-Host "`n=== 9/13 限流（网关固定窗口 + redirect 单码热点保护）===" -ForegroundColor Cyan

# 用 HttpClient 而不是 curl.exe：curl 每次起一个进程（~50ms），
# 700 次要 35 秒，会跨过分钟窗口把计数劈成两半，网关限流永远打不满。
Add-Type -AssemblyName System.Net.Http
$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromSeconds(5)

function Get-StatusFast([string]$url) {
    try { return [int]$http.GetAsync($url).GetAwaiter().GetResult().StatusCode }
    catch { return 0 }
}

$hit429 = $false
for ($attempt = 1; $attempt -le 3 -and -not $hit429; $attempt++) {
    & docker compose -f $composeFile exec -T redis redis-cli EVAL "local ks=redis.call('keys','sl:rate:*'); for i=1,#ks do redis.call('del',ks[i]) end; return #ks" 0 | Out-Null
    for ($i = 0; $i -lt 700; $i++) {
        if ((Get-StatusFast "$gatewayBase/api/v1/links") -eq 429) { $hit429 = $true; break }
    }
}
$http.Dispose()
Assert-True '网关超过阈值后返回 429' $hit429 '尝试 3 轮仍未触发'

# redirect 的单码令牌桶：容量 500、补充 500/s。
# 串行请求只有 ~600 req/s，扣掉补充几乎不消耗令牌，必须并发突发才打得穿。
$chars = (48..57) + (65..90) + (97..122)
$randCode = -join ($chars | Get-Random -Count 7 | ForEach-Object { [char]$_ })

# .NET Framework 默认每主机只允许 2 个并发连接，不放开的话请求会被串行化，
# 打不出"突发"，令牌桶永远补得回来。
[System.Net.ServicePointManager]::DefaultConnectionLimit = 1000
$httpRd = New-Object System.Net.Http.HttpClient
$httpRd.Timeout = [TimeSpan]::FromSeconds(15)
$tasks = New-Object 'System.Collections.Generic.List[System.Threading.Tasks.Task[System.Net.Http.HttpResponseMessage]]'
for ($i = 0; $i -lt 1200; $i++) {
    $tasks.Add($httpRd.GetAsync("$redirectBase/$randCode"))
}
[System.Threading.Tasks.Task]::WaitAll($tasks.ToArray())

$rejected = 0
foreach ($t in $tasks) {
    if ($t.IsFaulted) { continue }
    if ([int]$t.Result.StatusCode -eq 429) { $rejected++ }
}
$httpRd.Dispose()
Assert-True 'redirect 单码突发超过容量后触发 429' ($rejected -gt 0) "1200 并发中 429 数量=$rejected"
# ---------------------------------------------------------------------
Write-Host "`n=== 10/13 幂等 / 批量 / 配额 / 审计 ===" -ForegroundColor Cyan

# 幂等：同一个 Idempotency-Key 提交两次，必须拿到同一个短码
$idemKey = [guid]::NewGuid().ToString()
$idemBody = @{ longUrl = "https://example.com/idem/$unique" } | ConvertTo-Json -Compress
$idem1 = Invoke-RestMethod -Uri "$adminBase/api/v1/links" -Method Post -ContentType 'application/json' `
    -Headers @{ 'Idempotency-Key' = $idemKey } -Body $idemBody
$idem2 = Invoke-RestMethod -Uri "$adminBase/api/v1/links" -Method Post -ContentType 'application/json' `
    -Headers @{ 'Idempotency-Key' = $idemKey } -Body $idemBody
Assert-True '幂等：两次提交返回同一个短码' ($idem1.data.shortCode -eq $idem2.data.shortCode) "第一次=$($idem1.data.shortCode) 第二次=$($idem2.data.shortCode)"

# 批量：一条合法 + 一条 javascript: 非法，应 1 成功 1 失败并给出原因
$batchBody = @{ items = @(@{ longUrl = "https://example.com/b1/$unique" }, @{ longUrl = "javascript:alert(1)" }) } | ConvertTo-Json -Compress -Depth 5
$batch = Invoke-RestMethod -Uri "$adminBase/api/v1/links/batch" -Method Post -ContentType 'application/json' -Body $batchBody
Assert-True '批量：1 条成功' ($batch.data.success -eq 1) "success=$($batch.data.success)"
Assert-True '批量：1 条失败且带原因' ($batch.data.failed -eq 1 -and $batch.data.failures.Count -ge 1) "failed=$($batch.data.failed)"

# maxClick：只有配了上限的短链才走 Redis 配额计数，第 3 次应 403
$limitBody = @{ longUrl = "https://example.com/limit/$unique"; maxClick = 2 } | ConvertTo-Json -Compress
$limited = Invoke-RestMethod -Uri "$adminBase/api/v1/links" -Method Post -ContentType 'application/json' -Body $limitBody
$limitCode = $limited.data.shortCode
$limitStatuses = @()
for ($i = 0; $i -lt 3; $i++) {
    $limitStatuses += (& curl.exe -s -o NUL -w '%{http_code}' "$redirectBase/$limitCode" 2>$null)
}
Assert-True 'maxClick：前 2 次 302、第 3 次 403' (($limitStatuses[0] -eq '302') -and ($limitStatuses[1] -eq '302') -and ($limitStatuses[2] -eq '403')) "实际 $($limitStatuses -join ',')"

# 审计：以上写操作都该在 t_operate_log 里留下痕迹
# 用 MYSQL_PWD 传密码：mysql 客户端在命令行上用 -p 会往 stderr 打一句告警，
# 而脚本开了 ErrorActionPreference=Stop，原生程序的 stderr 会被当成错误直接中断。
$auditRaw = & docker compose -f $composeFile exec -T -e MYSQL_PWD=shortlink mysql `
    mysql -uroot -N -e "SELECT COUNT(*) FROM shortlink.t_operate_log WHERE action IN ('CREATE','UPDATE','DISABLE','ENABLE','BATCH_CREATE')" 2>$null
$auditText = ($auditRaw | Out-String).Trim()
$auditValue = 0
if ($auditText -match '(\d+)') { $auditValue = [int]$Matches[1] }
Assert-True '操作审计有记录' ($auditValue -gt 0) "实际 '$auditText'"

# 审计要记真实操作人：通过网关创建（带 token），网关注入 X-User-Id
# 第 9 段把限流配额打满了，先清计数
& docker compose -f $composeFile exec -T redis redis-cli EVAL "local ks=redis.call('keys','sl:rate:*'); for i=1,#ks do redis.call('del',ks[i]) end; return #ks" 0 | Out-Null
$gwBody = @{ longUrl = "https://example.com/audit/$unique" } | ConvertTo-Json -Compress
$gwCreated = Invoke-RestMethod -Uri "$gatewayBase/api/v1/links" -Method Post -ContentType 'application/json' `
    -Headers @{ Authorization = "Bearer $token" } -Body $gwBody
$gwGid = $gwCreated.data.gid

$creator = & docker compose -f $composeFile exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot -N -e "SELECT creator_id FROM shortlink.t_short_link WHERE gid='$gwGid'" 2>$null
$creatorText = (($creator | Out-String).Trim() -split "`n")[-1].Trim()
Assert-True '短链记录了真实创建人（网关透传 X-User-Id）' ($creatorText -eq '1') "实际 '$creatorText'"

$op = & docker compose -f $composeFile exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot -N -e "SELECT operator_id FROM shortlink.t_operate_log WHERE biz_id='$gwGid' AND action='CREATE'" 2>$null
$opText = (($op | Out-String).Trim() -split "`n")[-1].Trim()
Assert-True '审计记录了真实操作人' ($opText -eq '1') "实际 '$opText'"

# ---------------------------------------------------------------------
Write-Host "`n=== 11/13 统计查询（日聚合 + 实时 + 网关路由）===" -ForegroundColor Cyan

# 聚合任务（T+1）还没实现，这里直接往统计库写一行昨天的数据来验证查询链路
$yesterday = (Get-Date).AddDays(-1).ToString('yyyy-MM-dd')
$today = (Get-Date).ToString('yyyy-MM-dd')
& docker compose -f $composeFile exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot -e "DELETE FROM shortlink_stats.t_stats_link_daily WHERE short_code='$code'" 2>$null
& docker compose -f $composeFile exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot -e "INSERT INTO shortlink_stats.t_stats_link_daily (short_code, gid, stat_date, pv, uv) VALUES ('$code', '', '$yesterday', 100, 40)" 2>$null
$inserted = & docker compose -f $composeFile exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot -N -e "SELECT COUNT(*) FROM shortlink_stats.t_stats_link_daily WHERE short_code='$code'" 2>$null
$insertedText = (($inserted | Out-String).Trim() -split "`n")[-1].Trim()
Assert-True '能往统计库写入日聚合数据' ($insertedText -eq '1') "实际 '$insertedText'"

# 今天的实时值：PV 用 String，UV 用 HLL
& docker compose -f $composeFile exec -T redis redis-cli SET "sl:stats:pv:${code}:$today" 7 | Out-Null
& docker compose -f $composeFile exec -T redis redis-cli PFADD "sl:stats:uv:${code}:$today" u1 u2 u3 | Out-Null

$trend = Invoke-RestMethod -Uri "$statsBase/api/v1/stats/links/${code}?from=$yesterday&to=$today"
Assert-True '趋势返回 2 个点' ($trend.data.points.Count -eq 2) "实际 $($trend.data.points.Count)"
Assert-True '昨天的 PV 来自聚合表' ($trend.data.points[0].pv -eq 100) "实际 $($trend.data.points[0].pv)"
Assert-True '今天的 PV 来自 Redis 实时' ($trend.data.points[1].pv -eq 7) "实际 $($trend.data.points[1].pv)"
Assert-True '区间总 PV 正确' ($trend.data.totalPv -eq 107) "实际 $($trend.data.totalPv)"
Assert-True '今天的 source 标记为 REALTIME' ($trend.data.points[1].source -eq 'REALTIME') "实际 $($trend.data.points[1].source)"

$rt = Invoke-RestMethod -Uri "$statsBase/api/v1/stats/links/$code/realtime"
Assert-True '实时接口返回 PV=7' ($rt.data.pv -eq 7) "实际 $($rt.data.pv)"
Assert-True '实时 UV 由 HLL 估算为 3' ($rt.data.uv -eq 3) "实际 $($rt.data.uv)"

# 超区间与非法参数要被挡住，而不是返回空数据
$badRange = & curl.exe -s -o NUL -w '%{http_code}' "$statsBase/api/v1/stats/links/${code}?from=2026-01-01&to=2026-12-31" 2>$null
Assert-True '超 90 天区间被拒绝（4xx）' ("$badRange" -match '^4') "实际 $badRange"

# 网关路由：带 token 应能访问到 stats-api
# 第 9 段把网关的 IP 配额打满了，先清计数再验证路由（否则拿到的是 429 不是 200）
& docker compose -f $composeFile exec -T redis redis-cli EVAL "local ks=redis.call('keys','sl:rate:*'); for i=1,#ks do redis.call('del',ks[i]) end; return #ks" 0 | Out-Null

$gwStats = & curl.exe -s -o NUL -w '%{http_code}' -H "Authorization: Bearer $token" "$gatewayBase/api/v1/stats/links/$code/realtime" 2>$null
Assert-True '网关能把 /api/v1/stats 路由到 stats-api' ("$gwStats" -eq '200') "实际 $gwStats"

# ---------------------------------------------------------------------
Write-Host "`n=== 12/13 定时任务（过期清理）===" -ForegroundColor Cyan

# 直接插一条 100 天前就过期的短链：API 不允许创建过去的过期时间，只能走 SQL
$oldChars = (48..57) + (65..90) + (97..122)
$expiredCode = -join ($oldChars | Get-Random -Count 7 | ForEach-Object { [char]$_ })
$expiredGid = "01JOB" + (-join ($oldChars | Get-Random -Count 21 | ForEach-Object { [char]$_ }))  # gid 是 CHAR(26)
$seedSql = "INSERT INTO shortlink.t_short_link (gid, short_code, long_url, long_url_hash, domain_id, group_id, title, creator_id, status, expire_time, max_click, click_count, version, create_time, update_time, del_flag) VALUES ('$expiredGid', '$expiredCode', 'https://example.com/expired', MD5('https://example.com/expired'), 1, 0, 'expired', 0, 1, NOW() - INTERVAL 100 DAY, 0, 0, 0, NOW(), NOW(), 0)"
& docker compose -f $composeFile exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot -e $seedSql 2>$null
$seeded = & docker compose -f $composeFile exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot -N -e "SELECT COUNT(*) FROM shortlink.t_short_link WHERE short_code='$expiredCode'" 2>$null
Assert-True '造出一条过期短链' ((($seeded | Out-String).Trim() -split "`n")[-1].Trim() -eq '1')

$jobResp = Invoke-RestMethod -Uri "$jobBase/api/v1/admin/jobs/expired-cleanup" -Method Post
Assert-True '任务执行成功' ($jobResp.data.executed -eq $true) "实际 $($jobResp.data.executed) / $($jobResp.data.reason)"
Assert-True '至少清理 1 条' ($jobResp.data.result.scanned -ge 1) "实际 $($jobResp.data.result.scanned)"

$flag = & docker compose -f $composeFile exec -T -e MYSQL_PWD=shortlink mysql mysql -uroot -N -e "SELECT del_flag FROM shortlink.t_short_link WHERE short_code='$expiredCode'" 2>$null
Assert-True '过期短链被软删（del_flag=1）' ((($flag | Out-String).Trim() -split "`n")[-1].Trim() -eq '1')

& docker compose -f $composeFile exec -T redis redis-cli DEL "sl:link:$expiredCode" | Out-Null
$detailAfter = & curl.exe -s -o NUL -w '%{http_code}' "$adminBase/api/v1/links/$expiredGid" 2>$null
Assert-True '清理后详情接口返回 404' ("$detailAfter" -eq '404') "实际 $detailAfter"

# ---------------------------------------------------------------------
Write-Host "`n=== 13/13 可观测性 ===" -ForegroundColor Cyan
$metrics = Get-HttpBody "$redirectBase/actuator/prometheus"
Assert-True '暴露 redirect 结果计数' ($metrics -match 'shortlink_redirect_total')
Assert-True '暴露延迟指标' ($metrics -match 'shortlink_redirect_latency')
Assert-True '暴露缓存失效计数' ($metrics -match 'shortlink_cache_evict_total')

# ---------------------------------------------------------------------
Write-Host "`n=== 结果 ===" -ForegroundColor Cyan
$color = if ($script:failed -eq 0) { 'Green' } else { 'Red' }
Write-Host ("  通过 " + $script:passed + " / 失败 " + $script:failed) -ForegroundColor $color

if ($SkipCompose) {
    Write-Host "`n  -SkipCompose：不动容器，请自行停止服务" -ForegroundColor DarkGray
} elseif (-not $KeepRunning) {
    Write-Host "`n清理容器与数据卷..." -ForegroundColor DarkGray
    & docker compose -f $composeFile down -v | Out-Null
} else {
    Write-Host "`n容器保留中，手动清理：docker compose -f $composeFile down -v" -ForegroundColor DarkGray
}

if ($script:failed -gt 0) { exit 1 } else { exit 0 }
