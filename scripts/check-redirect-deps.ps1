<#
.SYNOPSIS
    redirect 依赖红线检查：Servlet 栈、数据库驱动、公共 Servlet 模块都不许进依赖树。

.DESCRIPTION
    "redirect 无 DB 依赖"是架构约束，不能靠自觉。这个脚本就是守门人：
    只要有人往 redirect 里加了 spring-boot-starter-web / mybatis / mysql-connector，
    依赖树里就会出现对应条目，这里立刻失败。

    为什么不用 grep 搜源码：依赖是传递进来的，import 可能出现在第三方 jar 里，
    只有 dependency:tree 能看到最终解析结果。

    为什么用 :artifact: 而不是裸字符串匹配：spring-boot-starter-web 是
    spring-boot-starter-webflux 的前缀，裸匹配会把 WebFlux 自己判成违规。

.PARAMETER Offline
    离线解析本地仓库（本地开发快；CI 上不要加）。

.NOTES
    Windows 默认执行策略可能禁止运行 .ps1，按需这样调用：
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-redirect-deps.ps1 -Offline

.EXAMPLE
    ./scripts/check-redirect-deps.ps1 -Offline
#>
param([switch]$Offline)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$module = 'shortlink-redirect'
$report = Join-Path $root "$module\target\dependency-tree.txt"

$forbidden = @(
    @{ Token = ':spring-boot-starter-web:';  Why = 'Servlet 栈：会同时引入 Tomcat 与 MVC，和 WebFlux 冲突' },
    @{ Token = ':spring-webmvc:';            Why = 'Servlet 栈：MVC 的 DispatcherServlet' },
    @{ Token = ':tomcat-';                   Why = 'Servlet 容器：两个 Web 栈并存会导致启动失败' },
    @{ Token = ':mybatis';                   Why = '持久层：redirect 不允许连 DB' },
    @{ Token = ':mysql-connector';           Why = 'JDBC 驱动：redirect 不允许连 DB' },
    @{ Token = ':spring-boot-starter-jdbc:'; Why = 'JDBC 自动配置：说明有 DB 依赖' },
    @{ Token = ':HikariCP:';                 Why = '连接池：说明有 DB 依赖' },
    @{ Token = ':shortlink-common-web:';     Why = '公共 Servlet 模块：它带 starter-web' }
)

Write-Host "==> 解析 $module 的依赖树 ..."
$mvnArgs = @('-B', '-q', '-pl', $module, '-am', 'dependency:tree',
             '-DoutputFile=target/dependency-tree.txt')
if ($Offline) {
    $mvnArgs = @('-o') + $mvnArgs
}

& mvn @mvnArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host "依赖解析失败（mvn 退出码 $LASTEXITCODE），无法判定，按失败处理" -ForegroundColor Red
    exit 2
}
if (-not (Test-Path $report)) {
    Write-Host "没有生成依赖树文件：$report" -ForegroundColor Red
    exit 2
}

$lines = Get-Content $report
$violations = New-Object System.Collections.Generic.List[string]
foreach ($line in $lines) {
    foreach ($rule in $forbidden) {
        if ($line.Contains($rule.Token)) {
            $violations.Add(("  " + $line.Trim() + [Environment]::NewLine + "    原因：" + $rule.Why))
        }
    }
}

if ($violations.Count -gt 0) {
    Write-Host "FAIL: redirect 的依赖树里出现了红线依赖" -ForegroundColor Red
    $violations | ForEach-Object { Write-Host $_ }
    Write-Host ""
    Write-Host "这条约束写在 shortlink-redirect/pom.xml 的注释里，也写在设计文档 3.2。"
    Write-Host "一旦破掉，redirect 就不再是'可以无脑扩副本的纯读服务'。"
    exit 1
}

Write-Host ("PASS: {0} 依赖树干净（{1} 行，无 Servlet / DB 依赖）" -f $module, $lines.Count) -ForegroundColor Green
exit 0
