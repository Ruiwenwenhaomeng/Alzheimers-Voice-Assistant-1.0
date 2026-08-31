$ErrorActionPreference = "Stop"

$projectRoot = "C:\Users\Administrator\IdeaProjects\alz-backendalz-backend\alzheimers-voice-assistant"
$erlangHome = "E:\zhangyu\Erlang OTP"
$rabbitSbin = "E:\zhangyu\rabbitmq_server-4.3.5\sbin"
$rabbitBase = "C:\Users\Administrator\AppData\Roaming\RabbitMQ"
$dotEnvPath = Join-Path $projectRoot ".env"
$resultFile = Join-Path $projectRoot "data\runtime\rabbitmq-stage2-result.txt"
$configPath = Join-Path $rabbitBase "rabbitmq.conf"
$configBackup = $configPath + ".stage2-backup"
$definitionsPath = Join-Path $rabbitBase "stage2-definitions.json"

$env:ERLANG_HOME = $erlangHome
$env:HOME = $env:USERPROFILE
$env:APPDATA = "C:\Users\Administrator\AppData\Roaming"
$env:RABBITMQ_BASE = $rabbitBase
$env:RABBITMQ_NODENAME = "rabbit@WINSTATION-2"
$env:Path = (Join-Path $erlangHome "bin") + ";" + $env:Path

$plugins = Join-Path $rabbitSbin "rabbitmq-plugins.bat"
$configChanged = $false

function Write-Result([string[]] $Lines) {
    $Lines | Set-Content -LiteralPath $resultFile -Encoding UTF8
}

function Set-DotEnvValue([string] $Name, [string] $Value) {
    $content = if (Test-Path -LiteralPath $dotEnvPath) {
        [System.IO.File]::ReadAllText($dotEnvPath, [System.Text.Encoding]::UTF8)
    } else { "" }
    $line = "$Name=$Value"
    $pattern = "(?m)^" + [regex]::Escape($Name) + "=.*$"
    if ([regex]::IsMatch($content, $pattern)) {
        $content = [regex]::Replace($content, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($match) $line })
    } else {
        if ($content.Length -gt 0 -and -not $content.EndsWith("`n")) { $content += "`r`n" }
        $content += $line + "`r`n"
    }
    [System.IO.File]::WriteAllText($dotEnvPath, $content, [System.Text.UTF8Encoding]::new($false))
}

function Get-DotEnvValue([string] $Name) {
    if (-not (Test-Path -LiteralPath $dotEnvPath)) { return "" }
    $content = [System.IO.File]::ReadAllText($dotEnvPath, [System.Text.Encoding]::UTF8)
    $pattern = "(?m)^" + [regex]::Escape($Name) + "=(.*)$"
    $match = [regex]::Match($content, $pattern)
    if (-not $match.Success) { return "" }
    return $match.Groups[1].Value.Trim()
}

function Test-LocalPort([int] $Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(5000)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch { return $false } finally { $client.Dispose() }
}

function Test-LoopbackOnlyListener([int] $Port) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0) { return $false }
    foreach ($listener in $listeners) {
        if ($listener.LocalAddress -notin @("127.0.0.1", "::1")) { return $false }
    }
    return $true
}

trap {
    if ($configChanged -and (Test-Path -LiteralPath $configBackup)) {
        if ((Get-Item -LiteralPath $configBackup).Length -eq 0) {
            Remove-Item -LiteralPath $configPath -Force -ErrorAction SilentlyContinue
        } else {
            Copy-Item -LiteralPath $configBackup -Destination $configPath -Force
        }
        Restart-Service RabbitMQ -ErrorAction SilentlyContinue
    }
    Write-Result @("stage2=error", "message=$($_.Exception.Message)")
    exit 1
}

if (-not (Test-Path -LiteralPath $plugins)) { throw "RabbitMQ plugins CLI is missing: $plugins" }

$rabbitUsername = Get-DotEnvValue "RABBITMQ_USERNAME"
$rabbitPassword = Get-DotEnvValue "RABBITMQ_PASSWORD"
if ($rabbitUsername -ne "alz_app" -or [string]::IsNullOrWhiteSpace($rabbitPassword)) {
    throw "Existing alz_app credentials were not found in the project .env"
}

$definitions = [ordered]@{
    users = @()
    vhosts = @([ordered]@{ name = "/alz" })
    permissions = @([ordered]@{
        user = "alz_app"
        vhost = "/alz"
        configure = ".*"
        write = ".*"
        read = ".*"
    })
    topic_permissions = @()
    parameters = @()
    global_parameters = @()
    policies = @()
    queues = @()
    exchanges = @()
    bindings = @()
}
$definitionsJson = $definitions | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($definitionsPath, $definitionsJson, [System.Text.UTF8Encoding]::new($false))
$definitionsConfigPath = $definitionsPath.Replace("\", "/")

$pluginOutput = @(& $plugins enable --offline rabbitmq_management 2>&1)
if ($LASTEXITCODE -ne 0) { throw "Offline plugin enable failed: $($pluginOutput -join ' ')" }

if (Test-Path -LiteralPath $configPath) {
    Copy-Item -LiteralPath $configPath -Destination $configBackup -Force
} else {
    "" | Set-Content -LiteralPath $configBackup -Encoding ASCII
}
@(
    "listeners.tcp.default = 127.0.0.1:5672",
    "management.tcp.ip = 127.0.0.1",
    "management.tcp.port = 15672",
    "definitions.import_backend = local_filesystem",
    "definitions.local.path = $definitionsConfigPath"
) | Set-Content -LiteralPath $configPath -Encoding ASCII
$configChanged = $true

Restart-Service RabbitMQ
$service = Get-Service RabbitMQ
$service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds(60))
Start-Sleep -Seconds 5

if (-not (Test-LocalPort 5672)) { throw "AMQP port 5672 is not listening on loopback" }
if (-not (Test-LocalPort 15672)) { throw "Management port 15672 is not listening on loopback" }
if (-not (Test-LoopbackOnlyListener 5672)) { throw "AMQP port 5672 is listening outside loopback" }
if (-not (Test-LoopbackOnlyListener 15672)) { throw "Management port 15672 is listening outside loopback" }
$configChanged = $false

Set-DotEnvValue "RABBITMQ_HOST" "127.0.0.1"
Set-DotEnvValue "RABBITMQ_PORT" "5672"
Set-DotEnvValue "RABBITMQ_VHOST" "/alz"
Set-DotEnvValue "RABBITMQ_USERNAME" "alz_app"

$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${rabbitUsername}:${rabbitPassword}"))
$headers = @{ Authorization = "Basic $basic" }
$visibleVhosts = @(Invoke-RestMethod -Uri "http://127.0.0.1:15672/api/vhosts" -Headers $headers -TimeoutSec 10)
if (-not ($visibleVhosts | Where-Object { $_.name -eq "/alz" })) {
    throw "/alz is not visible to alz_app"
}

Write-Result @(
    "stage2=complete", "service=running", "amqp_loopback=ok", "management_loopback=ok",
    "plugin=ok", "management_auth=ok", "vhost=ok", "user=ok", "permissions=ok", "dotenv=updated"
)
