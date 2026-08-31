$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $ProjectRoot "backend"
$ScreeningDir = Join-Path $ProjectRoot "screening-service"
$AudioRoot = Join-Path $ProjectRoot "data\audio"

if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
    Write-Host "DEEPSEEK_API_KEY is not set in this PowerShell session." -ForegroundColor Yellow
    Write-Host "Set it first, then run this script again:" -ForegroundColor Yellow
    Write-Host '$env:DEEPSEEK_API_KEY="your-api-key"' -ForegroundColor Yellow
    exit 1
}

$env:DEEPSEEK_ENABLED = "true"
if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_MODEL)) {
    $env:DEEPSEEK_MODEL = "deepseek-v4-flash"
}

$DefaultJavaHome = "D:\Professional software\jdk17"
if (Test-Path -LiteralPath $DefaultJavaHome) {
    $env:JAVA_HOME = $DefaultJavaHome
}
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $env:Path = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:Path
}

$PreviousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$JavaVersionOutput = (& java -version 2>&1) -join "`n"
$ErrorActionPreference = $PreviousErrorActionPreference
if ($JavaVersionOutput -notmatch 'version "17\.') {
    Write-Host "Java 17 is required, but current java -version is:" -ForegroundColor Yellow
    Write-Host $JavaVersionOutput -ForegroundColor Yellow
    Write-Host "Please install JDK 17 or update JAVA_HOME in this script." -ForegroundColor Yellow
    exit 1
}

New-Item -ItemType Directory -Path $AudioRoot -Force | Out-Null

function Test-PortOpen {
    param([int] $Port)
    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return $null -ne $connection
}

if (-not (Test-PortOpen -Port 6379)) {
    $RedisServer = "C:\Redis\redis-server.exe"
    $RedisConf = "C:\Redis\redis.windows.conf"
    if (Test-Path -LiteralPath $RedisServer) {
        Write-Host "Starting Redis on port 6379..."
        Start-Process -FilePath $RedisServer -ArgumentList "`"$RedisConf`"" -WorkingDirectory "C:\Redis" -WindowStyle Hidden
        Start-Sleep -Seconds 2
    } else {
        Write-Host "Redis was not found at C:\Redis. Start Redis manually if backend requires it." -ForegroundColor Yellow
    }
}

if (-not (Test-PortOpen -Port 5000)) {
    $PythonExe = "D:\Professional software\anaconda3\python.exe"
    if (Test-Path -LiteralPath $PythonExe) {
        Write-Host "Starting Python screening service on port 5000..."
        $env:AUDIO_ROOT = $AudioRoot
        Start-Process -FilePath $PythonExe `
            -ArgumentList "-m", "app.main" `
            -WorkingDirectory $ScreeningDir `
            -WindowStyle Hidden
        Start-Sleep -Seconds 2
    } else {
        Write-Host "Python was not found at the configured path. Voice screening analysis may be unavailable." -ForegroundColor Yellow
    }
}

if (Test-PortOpen -Port 8080) {
    Write-Host "Port 8080 is already in use. Stop the old backend first, then rerun this script." -ForegroundColor Yellow
    exit 1
}

Write-Host "Starting backend with DeepSeek + RAG enabled..."
Write-Host "Open http://localhost:8080/ after Spring Boot reports it has started."
Set-Location -LiteralPath $BackendDir
$MavenCmd = "D:\Professional software\IntelliJ IDEA 2024.3.2\plugins\maven\lib\maven3\bin\mvn.cmd"
if (Test-Path -LiteralPath $MavenCmd) {
    & $MavenCmd spring-boot:run
} else {
    & .\mvnw.cmd spring-boot:run
}
