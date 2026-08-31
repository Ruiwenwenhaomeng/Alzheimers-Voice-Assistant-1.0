$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ProjectRoot = $PSScriptRoot
$BackendDir = Join-Path $ProjectRoot "backend"
$ScreeningDir = Join-Path $ProjectRoot "screening-service"
$AudioRoot = Join-Path $ProjectRoot "data\audio"
$PdfRoot = Join-Path $ProjectRoot "data\pdf"
$AdminAudioRoot = Join-Path $ProjectRoot "data\admin_audio"
$ScreeningArtifactRoot = Join-Path $ProjectRoot "data\screening-artifacts"
$RuntimeRoot = Join-Path $ProjectRoot "data\runtime"
$EnvFile = Join-Path $ProjectRoot ".env"
$AppUrl = "http://localhost:8080/"
$DefaultMockPython = "D:\Professional software\anaconda3\envs\mock\python.exe"
$QdrantVersion = "1.18.2"
$QdrantWindowsSha256 = "b2b262cba6f78cf4fa794ae78d73a8f70a221c93c76c75ac8fd6fe95d809b142"

function Get-DotEnvValue {
    param(
        [string] $Name
    )
    if (-not (Test-Path -LiteralPath $EnvFile)) {
        return ""
    }
    $line = Get-Content -LiteralPath $EnvFile |
        Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } |
        Select-Object -First 1
    if ($null -eq $line) {
        return ""
    }
    $value = ($line -replace "^\s*$([regex]::Escape($Name))\s*=", "").Trim()
    return $value.Trim('"').Trim("'")
}

function Read-SecretAsPlainText {
    param(
        [string] $Prompt
    )
    $secure = Read-Host $Prompt -AsSecureString
    if ($secure.Length -eq 0) {
        return ""
    }
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

function Test-PortOpen {
    param(
        [int] $Port
    )
    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return $null -ne $connection
}

function Stop-ManagedScreeningWorker {
    param(
        [int] $ProcessId
    )
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if ($null -eq $processInfo -or $processInfo.CommandLine -notmatch "app\.workers\.(combined|pipeline)") {
        return $false
    }

    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match "app\.workers\.(combined|pipeline)" })
    foreach ($child in $children) {
        Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 300
    return $null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
}

function Stop-ManagedPythonModuleOnPort {
    param(
        [int] $Port,
        [string] $ModulePattern
    )
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $listener) {
        return $true
    }
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction SilentlyContinue
    if ($null -eq $processInfo -or $processInfo.CommandLine -notmatch $ModulePattern) {
        return $false
    }
    $parent = Get-CimInstance Win32_Process -Filter "ProcessId=$($processInfo.ParentProcessId)" -ErrorAction SilentlyContinue
    Stop-Process -Id $processInfo.ProcessId -Force -ErrorAction SilentlyContinue
    if ($null -ne $parent -and $parent.CommandLine -match $ModulePattern) {
        Stop-Process -Id $parent.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Milliseconds 300
    return -not (Test-PortOpen -Port $Port)
}

function Resolve-ProjectPath {
    param(
        [string] $PathValue
    )
    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return $PathValue
    }
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return $PathValue
    }
    return Join-Path $ProjectRoot $PathValue
}

function Get-JavaVersionText {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = (& java -version 2>&1) -join "`n"
    $ErrorActionPreference = $previousPreference
    return $output
}

function Set-EnvValueFromDotEnv {
    param(
        [string] $Name,
        [switch] $Force
    )
    if ($Force -or [string]::IsNullOrWhiteSpace((Get-Item -Path "Env:$Name" -ErrorAction SilentlyContinue).Value)) {
        $value = Get-DotEnvValue $Name
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            Set-Item -Path "Env:$Name" -Value $value
        }
    }
}

function Resolve-PythonExecutable {
    if (-not [string]::IsNullOrWhiteSpace($env:PYTHON_EXE) -and (Test-Path -LiteralPath $env:PYTHON_EXE)) {
        return $env:PYTHON_EXE
    }

    if (Test-Path -LiteralPath $DefaultMockPython) {
        return $DefaultMockPython
    }

    return ""
}

function Resolve-PythonForVenv {
    $candidates = @(
        "D:\Professional software\anaconda3\python.exe",
        "D:\Professional software\Python\python.exe"
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    $command = Get-Command python -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }
    return ""
}

function Confirm-ScreeningPythonBootstrap {
    Write-Host "Mock Python environment was not found: $DefaultMockPython" -ForegroundColor Yellow
    Write-Host "Create/configure a project Python environment from screening-service\requirements.txt? [Y/N]"
    while ($true) {
        $answer = (Read-Host "Choose Y or N").Trim()
        if ($answer -match '^(Y|y)$') {
            return $true
        }
        if ($answer -match '^(N|n)$') {
            return $false
        }
        Write-Host "Please enter Y to continue, or N to exit." -ForegroundColor Yellow
    }
}

function Ensure-ScreeningPython {
    param(
        [string] $BasePython
    )

    $venvDir = Join-Path $ScreeningDir ".venv"
    $venvPython = Join-Path $venvDir "Scripts\python.exe"
    $requirements = Join-Path $ScreeningDir "requirements.txt"
    $stampFile = Join-Path $venvDir ".requirements.sha256"

    if (-not (Test-Path -LiteralPath $venvPython)) {
        Write-Host "Creating Python virtual environment for screening service..."
        & $BasePython -m venv $venvDir
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to create Python virtual environment."
        }
    }

    $requirementsHash = (Get-FileHash -LiteralPath $requirements -Algorithm SHA256).Hash
    $installedHash = if (Test-Path -LiteralPath $stampFile) {
        (Get-Content -LiteralPath $stampFile -Raw).Trim()
    } else {
        ""
    }

    if ($installedHash -ne $requirementsHash) {
        Write-Host "Installing Python screening dependencies..."
        & $venvPython -m pip install -r $requirements
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to install Python screening dependencies."
        }
        Set-Content -LiteralPath $stampFile -Value $requirementsHash -Encoding ASCII
    }

    return $venvPython
}

function Wait-HttpReady {
    param(
        [string] $Uri,
        [int] $TimeoutSeconds,
        [string] $ServiceName,
        [System.Diagnostics.Process] $Process = $null
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $nextProgress = [DateTime]::UtcNow.AddSeconds(10)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($null -ne $Process) {
            try {
                if ($Process.HasExited) {
                    return $false
                }
            } catch {
            }
        }
        try {
            $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                return $true
            }
        } catch {
        }
        if ([DateTime]::UtcNow -ge $nextProgress) {
            Write-Host "Waiting for $ServiceName readiness..."
            $nextProgress = [DateTime]::UtcNow.AddSeconds(10)
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Test-PythonEmbeddingModules {
    param([string] $PythonExe)
    if ([string]::IsNullOrWhiteSpace($PythonExe) -or -not (Test-Path -LiteralPath $PythonExe)) {
        return $false
    }
    $previousPreference = $ErrorActionPreference
    $exitCode = 1
    try {
        # A broken Python package can write a traceback to stderr. Treat that as
        # a failed probe so Ensure-EmbeddingPython can create an isolated venv.
        $ErrorActionPreference = "Continue"
        & $PythonExe -c "import flask, sentence_transformers, torch" *> $null
        $exitCode = $LASTEXITCODE
    } catch {
        $exitCode = 1
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    return $exitCode -eq 0
}

function Invoke-PythonGpuProbe {
    param(
        [string] $PythonExe,
        [ValidateSet("whisper", "embedding")]
        [string] $Component,
        [int] $DeviceIndex,
        [string] $ComputeType = "float16"
    )
    $probeArguments = @("-m", "app.gpu_runtime", $Component, "--device-index", "$DeviceIndex")
    if ($Component -eq "whisper") {
        $probeArguments += @("--compute-type", $ComputeType)
    }
    $previousPreference = $ErrorActionPreference
    $exitCode = 1
    try {
        $ErrorActionPreference = "Continue"
        Push-Location $ScreeningDir
        $probeOutput = & $PythonExe @probeArguments 2>&1
        $exitCode = $LASTEXITCODE
        foreach ($line in @($probeOutput)) {
            if ($exitCode -eq 0) {
                Write-Host $line -ForegroundColor DarkGray
            } else {
                Write-Host $line -ForegroundColor Yellow
            }
        }
    } catch {
        Write-Host "GPU probe failed: $($_.Exception.Message)" -ForegroundColor Yellow
        $exitCode = 1
    } finally {
        Pop-Location -ErrorAction SilentlyContinue
        $ErrorActionPreference = $previousPreference
    }
    return $exitCode -eq 0
}

function Update-PythonPackageInstaller {
    param([string] $PythonExe)
    Write-Host "Updating pip and wheel before installing GPU dependencies..."
    $process = Start-Process -FilePath $PythonExe `
        -ArgumentList "-m", "pip", "install", "--disable-pip-version-check", "--upgrade", "pip", "wheel", "--index-url", "https://pypi.org/simple" `
        -NoNewWindow `
        -Wait `
        -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Failed to update pip and wheel before GPU dependency installation."
    }
}

function Ensure-ScreeningCudaRuntime {
    param([string] $PythonExe)
    $requirements = Join-Path $ScreeningDir "gpu-requirements.txt"
    Update-PythonPackageInstaller $PythonExe
    Write-Host "Installing/updating CUDA 12 cuBLAS and cuDNN 9 for faster-whisper..."
    $process = Start-Process -FilePath $PythonExe `
        -ArgumentList "-m", "pip", "install", "--disable-pip-version-check", "--upgrade", "-r", "`"$requirements`"" `
        -NoNewWindow `
        -Wait `
        -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Failed to install CUDA runtime packages for faster-whisper."
    }
}

function Ensure-EmbeddingCudaTorch {
    param(
        [string] $PythonExe,
        [int] $DeviceIndex
    )
    if (Invoke-PythonGpuProbe $PythonExe "embedding" $DeviceIndex) {
        return
    }
    Update-PythonPackageInstaller $PythonExe
    $version = $env:RAG_EMBEDDING_TORCH_VERSION
    $indexUrl = $env:RAG_EMBEDDING_TORCH_INDEX_URL
    Write-Host "Installing CUDA PyTorch $version for the RAG embedding service..."
    $process = Start-Process -FilePath $PythonExe `
        -ArgumentList "-m", "pip", "install", "--disable-pip-version-check", "--upgrade", "--force-reinstall", "torch==$version", "--index-url", $indexUrl `
        -NoNewWindow `
        -Wait `
        -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Failed to install CUDA PyTorch from $indexUrl."
    }
    if (-not (Invoke-PythonGpuProbe $PythonExe "embedding" $DeviceIndex)) {
        throw "CUDA PyTorch was installed, but its CUDA/cuDNN execution probe failed."
    }
}

function Get-ScreeningWorkerCount {
    $configured = $env:SCREENING_TRANSCRIPTION_WORKERS
    if ([string]::IsNullOrWhiteSpace($configured)) {
        $configured = $env:SCREENING_WORKER_CONCURRENCY
    }
    $count = 1
    if (-not [string]::IsNullOrWhiteSpace($configured)) {
        $parsed = 0
        if ([int]::TryParse($configured, [ref] $parsed)) {
            $count = [Math]::Max(1, [Math]::Min(4, $parsed))
        }
    }
    return $count
}

function Get-ScreeningStageWorkerCount {
    param(
        [string] $Name,
        [int] $Default = 1
    )
    $value = (Get-Item -Path "Env:$Name" -ErrorAction SilentlyContinue).Value
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $Default
    }
    $parsed = 0
    if (-not [int]::TryParse($value, [ref] $parsed)) {
        throw "$Name must be a positive integer."
    }
    return [Math]::Max(1, [Math]::Min(8, $parsed))
}

function Ensure-EmbeddingPython {
    param([string] $BasePython)

    $venvDir = Join-Path $ScreeningDir ".venv-embedding"
    $venvPython = Join-Path $venvDir "Scripts\python.exe"
    $requirements = Join-Path $ScreeningDir "embedding-requirements.txt"
    $stampFile = Join-Path $venvDir ".requirements.sha256"
    $requirementsHash = (Get-FileHash -LiteralPath $requirements -Algorithm SHA256).Hash
    $installedHash = if (Test-Path -LiteralPath $stampFile) {
        (Get-Content -LiteralPath $stampFile -Raw).Trim()
    } else {
        ""
    }
    if ($installedHash -eq $requirementsHash -and (Test-PythonEmbeddingModules $venvPython)) {
        return $venvPython
    }
    if ($env:RAG_EMBEDDING_DEVICE -ne "cuda" -and (Test-PythonEmbeddingModules $BasePython)) {
        return $BasePython
    }
    if ([string]::IsNullOrWhiteSpace($BasePython) -or -not (Test-Path -LiteralPath $BasePython)) {
        throw "No Python executable is available for the embedding service."
    }

    if (-not (Test-Path -LiteralPath $venvPython)) {
        Write-Host "Creating the RAG embedding Python environment..."
        $venvProcess = Start-Process -FilePath $BasePython `
            -ArgumentList "-m venv `"$venvDir`"" `
            -NoNewWindow `
            -Wait `
            -PassThru
        if ($venvProcess.ExitCode -ne 0) {
            throw "Failed to create the RAG embedding Python environment."
        }
    }

    $venvReady = Test-PythonEmbeddingModules $venvPython
    if ($installedHash -ne $requirementsHash -or -not $venvReady) {
        Write-Host "Installing RAG embedding dependencies. The first installation may take several minutes..."
        # Start-Process lets pip write progress/notices directly to the console.
        # PowerShell therefore does not wrap harmless stderr notices as errors,
        # and this function's return pipeline still contains only python.exe.
        $pipProcess = Start-Process -FilePath $venvPython `
            -ArgumentList "-m pip install --disable-pip-version-check -r `"$requirements`"" `
            -NoNewWindow `
            -Wait `
            -PassThru
        if ($pipProcess.ExitCode -ne 0) {
            throw "Failed to install RAG embedding dependencies."
        }
        Set-Content -LiteralPath $stampFile -Value $requirementsHash -Encoding ASCII
        $venvReady = Test-PythonEmbeddingModules $venvPython
    }
    if (-not $venvReady) {
        throw "The RAG embedding environment is incomplete after installation."
    }
    return $venvPython
}

function Test-DeepSeekApiKey {
    param(
        [string] $BaseUrl,
        [string] $ApiKey,
        [string] $Model
    )
    try {
        $response = Invoke-RestMethod `
            -Uri ($BaseUrl.TrimEnd('/') + "/models") `
            -Headers @{ Authorization = "Bearer $ApiKey" } `
            -TimeoutSec 10
        return $null -ne ($response.data | Where-Object { $_.id -eq $Model } | Select-Object -First 1)
    } catch {
        return $false
    }
}

function Test-HuggingFaceEndpoint {
    param(
        [string] $Endpoint,
        [string] $Model
    )
    if ([string]::IsNullOrWhiteSpace($Endpoint) -or [string]::IsNullOrWhiteSpace($Model)) {
        return $false
    }
    $probeUri = $Endpoint.TrimEnd('/') + "/" + $Model.Trim('/') + "/resolve/main/config.json"
    try {
        $response = Invoke-WebRequest -Uri $probeUri -Method Head -UseBasicParsing -TimeoutSec 15
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
    } catch {
        return $false
    }
}

function Start-QdrantForRag {
    param([string] $BaseUrl)

    $readyUri = $BaseUrl.TrimEnd('/') + "/readyz"
    try {
        $uri = [Uri] $BaseUrl
    } catch {
        throw "QDRANT_BASE_URL is invalid: $BaseUrl"
    }
    if ($uri.Host -notin @("127.0.0.1", "localhost")) {
        if (-not (Wait-HttpReady $readyUri 15 "remote Qdrant")) {
            throw "Remote Qdrant is not ready at $BaseUrl"
        }
        return
    }
    if (Wait-HttpReady $readyUri 2 "Qdrant") {
        return
    }

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    $dockerReady = $false
    if ($null -ne $docker) {
        & $docker.Source info *> $null
        $dockerReady = $LASTEXITCODE -eq 0
    }
    if ($dockerReady) {
        Write-Host "Starting Qdrant $QdrantVersion with Docker Compose..."
        & $docker.Source compose --project-directory $ProjectRoot `
            -f (Join-Path $ProjectRoot "compose.yaml") up -d qdrant
        if ($LASTEXITCODE -ne 0) {
            throw "Docker Compose failed to start Qdrant."
        }
    } else {
        # Normalize architecture names and use Windows environment fallbacks so
        # this probe also works consistently in Windows PowerShell 5.1/.NET hosts.
        $isWindows = $env:OS -eq "Windows_NT"
        try {
            $isWindows = $isWindows -or
                [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
                    [System.Runtime.InteropServices.OSPlatform]::Windows
                )
        } catch {
        }
        $architectureSignals = @()
        try {
            $architectureSignals += [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
        } catch {
        }
        $architectureSignals += @($env:PROCESSOR_ARCHITECTURE, $env:PROCESSOR_ARCHITEW6432)
        $architectureSignals = @($architectureSignals |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            ForEach-Object { $_.Trim().ToUpperInvariant() } |
            Select-Object -Unique)
        $isX64 = [Environment]::Is64BitOperatingSystem -and
            $null -ne ($architectureSignals | Where-Object { $_ -in @("X64", "AMD64", "X86_64") } | Select-Object -First 1)
        if (-not $isWindows -or -not $isX64) {
            $architectureText = if ($architectureSignals.Count -gt 0) {
                $architectureSignals -join ", "
            } else {
                "unknown"
            }
            throw "Automatic native Qdrant bootstrap currently supports Windows x64 only. Detected OS='$([Environment]::OSVersion.Platform)', architecture='$architectureText', 64-bit OS='$([Environment]::Is64BitOperatingSystem)'."
        }
        $configuredExe = Get-DotEnvValue "QDRANT_EXE"
        $qdrantExe = if (-not [string]::IsNullOrWhiteSpace($env:QDRANT_EXE)) {
            $env:QDRANT_EXE
        } elseif (-not [string]::IsNullOrWhiteSpace($configuredExe)) {
            Resolve-ProjectPath $configuredExe
        } else {
            ""
        }
        $qdrantDir = Join-Path $RuntimeRoot "qdrant\v$QdrantVersion"
        $bundledQdrantExe = Join-Path $qdrantDir "qdrant.exe"
        if ([string]::IsNullOrWhiteSpace($qdrantExe) -and (Test-Path -LiteralPath $bundledQdrantExe)) {
            $qdrantExe = $bundledQdrantExe
        }
        if ([string]::IsNullOrWhiteSpace($qdrantExe) -or -not (Test-Path -LiteralPath $qdrantExe)) {
            New-Item -ItemType Directory -Path $qdrantDir -Force | Out-Null
            $archive = Join-Path $qdrantDir "qdrant-x86_64-pc-windows-msvc.zip"
            $downloadUrl = "https://github.com/qdrant/qdrant/releases/download/v$QdrantVersion/qdrant-x86_64-pc-windows-msvc.zip"
            Write-Host "Downloading official Qdrant $QdrantVersion for Windows x64 (about 28 MB)..."
            Invoke-WebRequest -Uri $downloadUrl -OutFile $archive -UseBasicParsing
            $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($actualHash -ne $QdrantWindowsSha256) {
                throw "Qdrant archive SHA-256 verification failed."
            }
            Expand-Archive -LiteralPath $archive -DestinationPath $qdrantDir -Force
            $qdrantExe = Get-ChildItem -LiteralPath $qdrantDir -Filter "qdrant.exe" -Recurse |
                Select-Object -First 1 -ExpandProperty FullName
        }
        if ([string]::IsNullOrWhiteSpace($qdrantExe) -or -not (Test-Path -LiteralPath $qdrantExe)) {
            throw "qdrant.exe was not found after bootstrap."
        }

        $logsDir = Join-Path $RuntimeRoot "logs"
        $storageDir = Join-Path $RuntimeRoot "qdrant-storage"
        New-Item -ItemType Directory -Path $logsDir -Force | Out-Null
        New-Item -ItemType Directory -Path $storageDir -Force | Out-Null
        $httpPort = if ($uri.IsDefaultPort) { 6333 } else { $uri.Port }
        $env:QDRANT__SERVICE__HTTP_PORT = "$httpPort"
        $env:QDRANT__SERVICE__GRPC_PORT = "$($httpPort + 1)"
        $env:QDRANT__STORAGE__STORAGE_PATH = $storageDir
        Write-Host "Starting native Qdrant $QdrantVersion on port $httpPort..."
        Start-Process -FilePath $qdrantExe `
            -WorkingDirectory $qdrantDir `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logsDir "qdrant.out.log") `
            -RedirectStandardError (Join-Path $logsDir "qdrant.err.log") | Out-Null
    }

    if (-not (Wait-HttpReady $readyUri 90 "Qdrant")) {
        throw "Qdrant did not become ready at $readyUri"
    }
}

if (Test-PortOpen -Port 8080) {
    Write-Host "Port 8080 is already in use by an existing backend." -ForegroundColor Yellow
    Write-Host "Stop the existing backend with Ctrl+C, then run start.ps1 again so the new RAG environment is applied." -ForegroundColor Yellow
    exit 1
}

if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
    $keyFromEnvFile = Get-DotEnvValue "DEEPSEEK_API_KEY"
    if (-not [string]::IsNullOrWhiteSpace($keyFromEnvFile)) {
        $env:DEEPSEEK_API_KEY = $keyFromEnvFile
    }
}

if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
    Write-Host "DeepSeek API key was not found in this session or .env." -ForegroundColor Yellow
    Write-Host "Press Enter to use local RAG only, or paste a key to enable DeepSeek for this run."
    $keyFromPrompt = Read-SecretAsPlainText "DeepSeek API key"
    if (-not [string]::IsNullOrWhiteSpace($keyFromPrompt)) {
        $env:DEEPSEEK_API_KEY = $keyFromPrompt.Trim()
    }
}

if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
    $env:DEEPSEEK_ENABLED = "false"
    Write-Host "DeepSeek disabled. The assistant will use local RAG answers." -ForegroundColor Yellow
} else {
    $env:DEEPSEEK_ENABLED = "true"
    Write-Host "DeepSeek enabled for this run. The key is kept in process memory only."
}

if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_MODEL)) {
    $modelFromEnvFile = Get-DotEnvValue "DEEPSEEK_MODEL"
    $env:DEEPSEEK_MODEL = if ([string]::IsNullOrWhiteSpace($modelFromEnvFile)) { "deepseek-v4-flash" } else { $modelFromEnvFile }
}

Set-EnvValueFromDotEnv "DB_URL"
Set-EnvValueFromDotEnv "DB_USERNAME"
Set-EnvValueFromDotEnv "DB_PASSWORD"
Set-EnvValueFromDotEnv "REDIS_HOST"
Set-EnvValueFromDotEnv "REDIS_PORT"
Set-EnvValueFromDotEnv "JWT_SECRET"
Set-EnvValueFromDotEnv "CORS_ALLOWED_ORIGINS"
Set-EnvValueFromDotEnv "DEEPSEEK_BASE_URL"
Set-EnvValueFromDotEnv "DEEPSEEK_ANTHROPIC_BASE_URL"
Set-EnvValueFromDotEnv "DEEPSEEK_MAX_TOKENS"
Set-EnvValueFromDotEnv "DEEPSEEK_CONNECT_TIMEOUT_MS"
Set-EnvValueFromDotEnv "DEEPSEEK_READ_TIMEOUT_MS"
Set-EnvValueFromDotEnv "DEEPSEEK_WEB_SEARCH_ENABLED"
Set-EnvValueFromDotEnv "DEEPSEEK_WEB_SEARCH_MAX_USES"
Set-EnvValueFromDotEnv "RAG_TOP_K"
Set-EnvValueFromDotEnv "RAG_LOCAL_MIN_SCORE"
Set-EnvValueFromDotEnv "RAG_STREAM_REQUEST_TIMEOUT"
Set-EnvValueFromDotEnv "RAG_VECTOR_ENABLED"
Set-EnvValueFromDotEnv "QDRANT_BASE_URL"
Set-EnvValueFromDotEnv "QDRANT_API_KEY"
Set-EnvValueFromDotEnv "QDRANT_EXE"
Set-EnvValueFromDotEnv "QDRANT_COLLECTION"
Set-EnvValueFromDotEnv "RAG_VECTOR_SCORE_THRESHOLD"
Set-EnvValueFromDotEnv "RAG_VECTOR_BOOTSTRAP"
Set-EnvValueFromDotEnv "RAG_VECTOR_WARMUP_ENABLED"
Set-EnvValueFromDotEnv "RAG_VECTOR_PAYLOAD_INDEX_ENABLED"
Set-EnvValueFromDotEnv "RAG_EMBEDDING_ENABLED"
Set-EnvValueFromDotEnv "RAG_EMBEDDING_BASE_URL"
Set-EnvValueFromDotEnv "RAG_EMBEDDING_API_KEY"
Set-EnvValueFromDotEnv "RAG_EMBEDDING_MODEL"
Set-EnvValueFromDotEnv "RAG_EMBEDDING_DEVICE" -Force
Set-EnvValueFromDotEnv "RAG_EMBEDDING_DEVICE_INDEX" -Force
Set-EnvValueFromDotEnv "RAG_EMBEDDING_TORCH_VERSION" -Force
Set-EnvValueFromDotEnv "RAG_EMBEDDING_TORCH_INDEX_URL" -Force
Set-EnvValueFromDotEnv "RAG_EMBEDDING_BATCH_SIZE"
Set-EnvValueFromDotEnv "RAG_EMBEDDING_PORT"
Set-EnvValueFromDotEnv "RAG_EMBEDDING_STARTUP_TIMEOUT_SECONDS"
Set-EnvValueFromDotEnv "RAG_EMBEDDING_CONNECT_TIMEOUT_MS"
Set-EnvValueFromDotEnv "RAG_EMBEDDING_READ_TIMEOUT_MS"
Set-EnvValueFromDotEnv "SCREENING_ENGINE_FACTORY"
Set-EnvValueFromDotEnv "SCREENING_REQUIRE_AI"
Set-EnvValueFromDotEnv "PYTHON_EXE"
Set-EnvValueFromDotEnv "PYTHON_AUTO_VENV"
Set-EnvValueFromDotEnv "PYTHON_DIAGNOSIS_URL"
Set-EnvValueFromDotEnv "PYTHON_CONNECT_TIMEOUT_MS"
Set-EnvValueFromDotEnv "PYTHON_READ_TIMEOUT_MS"
Set-EnvValueFromDotEnv "HF_ENDPOINT"
Set-EnvValueFromDotEnv "WHISPER_MODEL_SIZE"
Set-EnvValueFromDotEnv "WHISPER_DEVICE" -Force
Set-EnvValueFromDotEnv "WHISPER_DEVICE_INDEX" -Force
Set-EnvValueFromDotEnv "WHISPER_COMPUTE_TYPE" -Force
Set-EnvValueFromDotEnv "CUDA_DLL_PATHS" -Force
Set-EnvValueFromDotEnv "GPU_STARTUP_FAILURE_POLICY" -Force
Set-EnvValueFromDotEnv "DEEPSEEK_SCREENING_MODEL"
Set-EnvValueFromDotEnv "DEEPSEEK_SCREENING_MAX_TOKENS"
Set-EnvValueFromDotEnv "AUDIO_STORAGE_DIR"
Set-EnvValueFromDotEnv "PDF_STORAGE_DIR"
Set-EnvValueFromDotEnv "ADMIN_AUDIO_STORAGE_DIR"
Set-EnvValueFromDotEnv "SCREENING_ARTIFACT_DIR"
Set-EnvValueFromDotEnv "SCREENING_ASYNC_ENABLED"
Set-EnvValueFromDotEnv "RABBITMQ_HOST"
Set-EnvValueFromDotEnv "RABBITMQ_PORT"
Set-EnvValueFromDotEnv "RABBITMQ_VHOST"
Set-EnvValueFromDotEnv "RABBITMQ_USERNAME"
Set-EnvValueFromDotEnv "RABBITMQ_PASSWORD"
Set-EnvValueFromDotEnv "RABBIT_PREFETCH"
Set-EnvValueFromDotEnv "SCREENING_RESULT_CONCURRENCY"
Set-EnvValueFromDotEnv "SCREENING_STATUS_CONCURRENCY"
Set-EnvValueFromDotEnv "PDF_WORKER_CONCURRENCY"
Set-EnvValueFromDotEnv "SCREENING_LISTENER_QUEUE_CAPACITY"
Set-EnvValueFromDotEnv "SCREENING_USER_MAX_ACTIVE"
Set-EnvValueFromDotEnv "SCREENING_CANCEL_TIMEOUT_MS"
Set-EnvValueFromDotEnv "SCREENING_CANCEL_RECONCILE_DELAY_MS"
Set-EnvValueFromDotEnv "SCREENING_OUTBOX_DELAY_MS"
Set-EnvValueFromDotEnv "SCREENING_OUTBOX_BATCH_SIZE"
Set-EnvValueFromDotEnv "SCREENING_PUBLISH_CONFIRM_TIMEOUT_MS"
Set-EnvValueFromDotEnv "SCREENING_WORKER_CONCURRENCY"
Set-EnvValueFromDotEnv "SCREENING_TRANSCRIPTION_WORKERS"
Set-EnvValueFromDotEnv "SCREENING_FEATURE_WORKERS"
Set-EnvValueFromDotEnv "SCREENING_LLM_WORKERS"
Set-EnvValueFromDotEnv "SCREENING_WORKER_MAX_RETRIES"
Set-EnvValueFromDotEnv "SCREENING_RETRY_DELAY_MS"
if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_BASE_URL)) {
    $env:DEEPSEEK_BASE_URL = "https://api.deepseek.com"
}
if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_ANTHROPIC_BASE_URL)) {
    $env:DEEPSEEK_ANTHROPIC_BASE_URL = "https://api.deepseek.com/anthropic"
}
if ([string]::IsNullOrWhiteSpace($env:RAG_LOCAL_MIN_SCORE)) {
    $env:RAG_LOCAL_MIN_SCORE = "16"
}
if ([string]::IsNullOrWhiteSpace($env:QDRANT_BASE_URL)) {
    $env:QDRANT_BASE_URL = "http://127.0.0.1:6333"
}
if ([string]::IsNullOrWhiteSpace($env:RAG_EMBEDDING_BASE_URL)) {
    $env:RAG_EMBEDDING_BASE_URL = "http://127.0.0.1:7997/v1"
}
if ([string]::IsNullOrWhiteSpace($env:RAG_EMBEDDING_PORT)) {
    $env:RAG_EMBEDDING_PORT = "7997"
}
if ([string]::IsNullOrWhiteSpace($env:RAG_EMBEDDING_MODEL)) {
    $env:RAG_EMBEDDING_MODEL = "BAAI/bge-m3"
}
if ([string]::IsNullOrWhiteSpace($env:RAG_EMBEDDING_DEVICE)) {
    $env:RAG_EMBEDDING_DEVICE = "cpu"
}
if ([string]::IsNullOrWhiteSpace($env:RAG_EMBEDDING_DEVICE_INDEX)) {
    $env:RAG_EMBEDDING_DEVICE_INDEX = "0"
}
if ([string]::IsNullOrWhiteSpace($env:RAG_EMBEDDING_TORCH_VERSION)) {
    $env:RAG_EMBEDDING_TORCH_VERSION = "2.8.0"
}
if ([string]::IsNullOrWhiteSpace($env:RAG_EMBEDDING_TORCH_INDEX_URL)) {
    $env:RAG_EMBEDDING_TORCH_INDEX_URL = "https://download.pytorch.org/whl/cu128"
}
if ([string]::IsNullOrWhiteSpace($env:RAG_EMBEDDING_BATCH_SIZE)) {
    $env:RAG_EMBEDDING_BATCH_SIZE = "16"
}
if ([string]::IsNullOrWhiteSpace($env:RAG_EMBEDDING_STARTUP_TIMEOUT_SECONDS)) {
    $env:RAG_EMBEDDING_STARTUP_TIMEOUT_SECONDS = "3600"
}
if ($env:DEEPSEEK_ENABLED -eq "true") {
    $env:DEEPSEEK_WEB_SEARCH_ENABLED = "true"
    $env:RAG_VECTOR_ENABLED = "true"
    $env:RAG_EMBEDDING_ENABLED = "true"
    $env:RAG_VECTOR_BOOTSTRAP = "true"
    $env:RAG_VECTOR_WARMUP_ENABLED = "true"
    if (-not (Test-DeepSeekApiKey $env:DEEPSEEK_BASE_URL $env:DEEPSEEK_API_KEY $env:DEEPSEEK_MODEL)) {
        Write-Host "DeepSeek API key validation failed or model '$env:DEEPSEEK_MODEL' is unavailable." -ForegroundColor Yellow
        Write-Host "Check the key, account status and network, then rerun start.ps1." -ForegroundColor Yellow
        exit 1
    }
    Write-Host "Full RAG mode enabled: vector retrieval, embedding, Qdrant bootstrap and web search."
}
if ([string]::IsNullOrWhiteSpace($env:SCREENING_ENGINE_FACTORY)) {
    $env:SCREENING_ENGINE_FACTORY = "app.ad_diagnose_engine:create_engine"
}
if ([string]::IsNullOrWhiteSpace($env:WHISPER_DEVICE)) {
    $env:WHISPER_DEVICE = "cpu"
}
if ([string]::IsNullOrWhiteSpace($env:WHISPER_DEVICE_INDEX)) {
    $env:WHISPER_DEVICE_INDEX = "0"
}
if ([string]::IsNullOrWhiteSpace($env:WHISPER_COMPUTE_TYPE)) {
    $env:WHISPER_COMPUTE_TYPE = if ($env:WHISPER_DEVICE -eq "cuda") { "float16" } else { "int8" }
}
if ([string]::IsNullOrWhiteSpace($env:GPU_STARTUP_FAILURE_POLICY)) {
    $env:GPU_STARTUP_FAILURE_POLICY = "fail"
}
if ($env:GPU_STARTUP_FAILURE_POLICY -notin @("fail", "cpu")) {
    Write-Host "GPU_STARTUP_FAILURE_POLICY must be 'fail' or 'cpu'." -ForegroundColor Red
    exit 1
}
if ($env:DEEPSEEK_ENABLED -eq "true" -and
        $env:SCREENING_ENGINE_FACTORY -eq "app.ad_diagnose_engine:create_engine") {
    # A key entered for this run must also reach the async screening workers.
    # Override stale values inherited from an older PowerShell session.
    $env:SCREENING_REQUIRE_AI = "true"
}
if ($env:SCREENING_REQUIRE_AI -eq "true" -and [string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
    Write-Host "SCREENING_REQUIRE_AI=true requires DEEPSEEK_API_KEY." -ForegroundColor Red
    Write-Host "Set the key in the current PowerShell session or .env, then rerun start.ps1." -ForegroundColor Yellow
    Write-Host "Startup stopped to avoid silently producing quality-only screening reports." -ForegroundColor Yellow
    exit 1
}
Write-Host "Screening engine mode: $($env:SCREENING_ENGINE_FACTORY), require AI: $($env:SCREENING_REQUIRE_AI)"
if ([string]::IsNullOrWhiteSpace($env:HF_ENDPOINT)) {
    $env:HF_ENDPOINT = "https://huggingface.co"
}
$env:HF_HUB_DISABLE_SYMLINKS_WARNING = "1"
if ($env:RAG_EMBEDDING_ENABLED -eq "true" -and
        -not (Test-HuggingFaceEndpoint $env:HF_ENDPOINT $env:RAG_EMBEDDING_MODEL)) {
    $officialHuggingFaceEndpoint = "https://huggingface.co"
    if ($env:HF_ENDPOINT.TrimEnd('/') -ne $officialHuggingFaceEndpoint -and
            (Test-HuggingFaceEndpoint $officialHuggingFaceEndpoint $env:RAG_EMBEDDING_MODEL)) {
        Write-Host "Configured HF_ENDPOINT is unavailable; using https://huggingface.co for this run." -ForegroundColor Yellow
        $env:HF_ENDPOINT = $officialHuggingFaceEndpoint
    } else {
        Write-Host "Hugging Face endpoint preflight failed. A cached model may still work; otherwise check embedding.err.log." -ForegroundColor Yellow
    }
}
if ([string]::IsNullOrWhiteSpace($env:PYTHON_DIAGNOSIS_URL)) {
    $env:PYTHON_DIAGNOSIS_URL = "http://127.0.0.1:5000/api/diagnosis"
}
if ([string]::IsNullOrWhiteSpace($env:AUDIO_STORAGE_DIR)) {
    $env:AUDIO_STORAGE_DIR = $AudioRoot
}
if ([string]::IsNullOrWhiteSpace($env:PDF_STORAGE_DIR)) {
    $env:PDF_STORAGE_DIR = $PdfRoot
}
if ([string]::IsNullOrWhiteSpace($env:ADMIN_AUDIO_STORAGE_DIR)) {
    $env:ADMIN_AUDIO_STORAGE_DIR = $AdminAudioRoot
}
if ([string]::IsNullOrWhiteSpace($env:SCREENING_ARTIFACT_DIR)) {
    $env:SCREENING_ARTIFACT_DIR = $ScreeningArtifactRoot
}
if ([string]::IsNullOrWhiteSpace($env:REDIS_PORT)) {
    $env:REDIS_PORT = "6379"
}
if ([string]::IsNullOrWhiteSpace($env:RABBITMQ_HOST)) {
    $env:RABBITMQ_HOST = "localhost"
}
if ([string]::IsNullOrWhiteSpace($env:RABBITMQ_PORT)) {
    $env:RABBITMQ_PORT = "5672"
}
if ([string]::IsNullOrWhiteSpace($env:RABBITMQ_VHOST)) {
    $env:RABBITMQ_VHOST = "/alz"
}
if ([string]::IsNullOrWhiteSpace($env:RABBITMQ_USERNAME)) {
    $env:RABBITMQ_USERNAME = "alz_app"
}
if ([string]::IsNullOrWhiteSpace($env:RABBITMQ_PASSWORD)) {
    $env:RABBITMQ_PASSWORD = "alz_dev_password"
}
$env:AUDIO_STORAGE_DIR = Resolve-ProjectPath $env:AUDIO_STORAGE_DIR
$env:PDF_STORAGE_DIR = Resolve-ProjectPath $env:PDF_STORAGE_DIR
$env:ADMIN_AUDIO_STORAGE_DIR = Resolve-ProjectPath $env:ADMIN_AUDIO_STORAGE_DIR
$env:SCREENING_ARTIFACT_DIR = Resolve-ProjectPath $env:SCREENING_ARTIFACT_DIR

$DefaultJavaHome = "D:\Professional software\jdk17"
if (Test-Path -LiteralPath $DefaultJavaHome) {
    $env:JAVA_HOME = $DefaultJavaHome
}
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $env:Path = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:Path
}

$javaVersion = Get-JavaVersionText
if ($javaVersion -notmatch 'version "17\.') {
    Write-Host "Java 17 is required, but current java -version is:" -ForegroundColor Yellow
    Write-Host $javaVersion -ForegroundColor Yellow
    exit 1
}

New-Item -ItemType Directory -Path $env:AUDIO_STORAGE_DIR -Force | Out-Null
New-Item -ItemType Directory -Path $env:PDF_STORAGE_DIR -Force | Out-Null
New-Item -ItemType Directory -Path $env:ADMIN_AUDIO_STORAGE_DIR -Force | Out-Null
New-Item -ItemType Directory -Path $env:SCREENING_ARTIFACT_DIR -Force | Out-Null

$redisPort = 6379
if (-not [int]::TryParse($env:REDIS_PORT, [ref] $redisPort)) {
    Write-Host "REDIS_PORT must be a number, but got '$env:REDIS_PORT'." -ForegroundColor Yellow
    exit 1
}

if (-not (Test-PortOpen -Port $redisPort)) {
    $redisServer = "C:\Redis\redis-server.exe"
    $redisConf = "C:\Redis\redis.windows.conf"
    if ($redisPort -eq 6379 -and (Test-Path -LiteralPath $redisServer)) {
        Write-Host "Starting Redis on port 6379..."
        Start-Process -FilePath $redisServer -ArgumentList "`"$redisConf`"" -WorkingDirectory "C:\Redis" -WindowStyle Hidden
        Start-Sleep -Seconds 2
    } else {
        Write-Host "Redis is not listening on port $redisPort. Start Redis manually if backend requires it." -ForegroundColor Yellow
    }
}

Write-Host "Static frontend is served by the Spring Boot backend at $AppUrl"

$screeningGpuPython = ""
if ($env:WHISPER_DEVICE -eq "cuda") {
    try {
        $screeningGpuPython = Resolve-PythonExecutable
        if ([string]::IsNullOrWhiteSpace($screeningGpuPython) -or -not (Test-Path -LiteralPath $screeningGpuPython)) {
            throw "A Python environment is required for the Whisper CUDA startup probe."
        }
        if ($env:PYTHON_AUTO_VENV -eq "true") {
            $screeningGpuPython = Ensure-ScreeningPython $screeningGpuPython
        }
        $baseDeviceIndex = 0
        if (-not [int]::TryParse($env:WHISPER_DEVICE_INDEX, [ref] $baseDeviceIndex) -or $baseDeviceIndex -lt 0) {
            throw "WHISPER_DEVICE_INDEX must be a non-negative integer."
        }
        $gpuWorkerCount = Get-ScreeningWorkerCount
        $whisperGpuReady = $true
        for ($probeOffset = 0; $probeOffset -lt $gpuWorkerCount; $probeOffset++) {
            if (-not (Invoke-PythonGpuProbe $screeningGpuPython "whisper" ($baseDeviceIndex + $probeOffset) $env:WHISPER_COMPUTE_TYPE)) {
                $whisperGpuReady = $false
                break
            }
        }
        if (-not $whisperGpuReady) {
            Ensure-ScreeningCudaRuntime $screeningGpuPython
            $whisperGpuReady = $true
            for ($probeOffset = 0; $probeOffset -lt $gpuWorkerCount; $probeOffset++) {
                if (-not (Invoke-PythonGpuProbe $screeningGpuPython "whisper" ($baseDeviceIndex + $probeOffset) $env:WHISPER_COMPUTE_TYPE)) {
                    $whisperGpuReady = $false
                    break
                }
            }
        }
        if (-not $whisperGpuReady) {
            throw "Whisper CUDA/cuBLAS/cuDNN startup probe failed."
        }
        Write-Host "Whisper GPU preflight passed for $gpuWorkerCount worker(s), starting at GPU $baseDeviceIndex."
    } catch {
        if ($env:GPU_STARTUP_FAILURE_POLICY -eq "cpu") {
            Write-Host "Whisper GPU startup failed: $($_.Exception.Message)" -ForegroundColor Yellow
            Write-Host "Explicitly falling back to CPU because GPU_STARTUP_FAILURE_POLICY=cpu." -ForegroundColor Yellow
            $env:WHISPER_DEVICE = "cpu"
            $env:WHISPER_DEVICE_INDEX = "0"
            $env:WHISPER_COMPUTE_TYPE = "int8"
            $screeningGpuPython = ""
        } else {
            Write-Host "Whisper GPU startup failed: $($_.Exception.Message)" -ForegroundColor Red
            Write-Host "Startup stopped because GPU_STARTUP_FAILURE_POLICY=fail." -ForegroundColor Red
            exit 1
        }
    }
}

if ((Test-PortOpen -Port 5000) -and $env:WHISPER_DEVICE -eq "cuda") {
    $expectedScreeningDevice = "cuda"
    $expectedScreeningIndex = $env:WHISPER_DEVICE_INDEX
    $restartScreeningApi = $true
    try {
        $existingScreeningHealth = Invoke-RestMethod -Uri "http://127.0.0.1:5000/health" -TimeoutSec 5
        $restartScreeningApi = $existingScreeningHealth.whisper_device -ne $expectedScreeningDevice -or
            "$($existingScreeningHealth.whisper_device_index)" -ne "$expectedScreeningIndex" -or
            $existingScreeningHealth.whisper_compute_type -ne $env:WHISPER_COMPUTE_TYPE
    } catch {
        $restartScreeningApi = $true
    }
    if ($restartScreeningApi) {
        Write-Host "Restarting the managed Python screening API to apply GPU configuration..."
        if (-not (Stop-ManagedPythonModuleOnPort 5000 "app\.main")) {
            Write-Host "Port 5000 is owned by an unmanaged process; stop it manually and rerun start.ps1." -ForegroundColor Red
            exit 1
        }
    }
}

if (-not (Test-PortOpen -Port 5000)) {
    $pythonExe = if (-not [string]::IsNullOrWhiteSpace($screeningGpuPython)) {
        $screeningGpuPython
    } else {
        Resolve-PythonExecutable
    }
    if ([string]::IsNullOrWhiteSpace($pythonExe) -or -not (Test-Path -LiteralPath $pythonExe)) {
        if (-not (Confirm-ScreeningPythonBootstrap)) {
            Write-Host "Python screening environment is required. Exiting startup." -ForegroundColor Yellow
            exit 1
        }

        $basePython = Resolve-PythonForVenv
        if ([string]::IsNullOrWhiteSpace($basePython) -or -not (Test-Path -LiteralPath $basePython)) {
            Write-Host "No base Python executable was found to create screening-service\.venv. Exiting startup." -ForegroundColor Yellow
            exit 1
        }

        try {
            $pythonExe = Ensure-ScreeningPython $basePython
        } catch {
            Write-Host $_ -ForegroundColor Yellow
            Write-Host "Failed to configure the Python screening environment. Exiting startup." -ForegroundColor Yellow
            exit 1
        }
    } elseif ($env:PYTHON_AUTO_VENV -eq "true") {
        try {
            $pythonExe = Ensure-ScreeningPython $pythonExe
        } catch {
            Write-Host $_ -ForegroundColor Yellow
            Write-Host "Failed to configure the Python screening environment. Exiting startup." -ForegroundColor Yellow
            exit 1
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($pythonExe) -and (Test-Path -LiteralPath $pythonExe)) {
        Write-Host "Starting Python screening service on port 5000 with $pythonExe..."
        $env:AUDIO_ROOT = $env:AUDIO_STORAGE_DIR
        $screeningLogsDir = Join-Path $RuntimeRoot "logs"
        New-Item -ItemType Directory -Path $screeningLogsDir -Force | Out-Null
        Start-Process -FilePath $pythonExe `
            -ArgumentList "-m", "app.main" `
            -WorkingDirectory $ScreeningDir `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $screeningLogsDir "screening-api.out.log") `
            -RedirectStandardError (Join-Path $screeningLogsDir "screening-api.err.log")
        Start-Sleep -Seconds 2
    } else {
        Write-Host "Python screening environment is unavailable. Exiting startup." -ForegroundColor Yellow
        exit 1
    }
}

if ($env:SCREENING_REQUIRE_AI -eq "true") {
    try {
        $screeningHealth = Invoke-RestMethod -Uri "http://127.0.0.1:5000/health" -TimeoutSec 5
        $screeningAiReady = $screeningHealth.ai_ready -eq $true -or
            $screeningHealth.engine -eq "DeepSeekVoiceDiagnosisEngine"
        if (-not $screeningAiReady) {
            Write-Host "Python screening service is running without the DeepSeek AI engine." -ForegroundColor Red
            Write-Host "Stop the stale port 5000 service and rerun start.ps1." -ForegroundColor Yellow
            exit 1
        }
    } catch {
        Write-Host "Python screening AI health check failed: $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}

if ($env:RAG_VECTOR_ENABLED -eq "true") {
    if ($env:RAG_EMBEDDING_ENABLED -ne "true") {
        Write-Host "RAG_VECTOR_ENABLED=true requires RAG_EMBEDDING_ENABLED=true." -ForegroundColor Yellow
        exit 1
    }
    try {
        Start-QdrantForRag $env:QDRANT_BASE_URL
    } catch {
        Write-Host $_ -ForegroundColor Yellow
        Write-Host "Qdrant startup failed; the backend will not start in misleading fallback mode." -ForegroundColor Yellow
        exit 1
    }
}

if ($env:RAG_EMBEDDING_ENABLED -eq "true") {
    $embeddingPort = 7997
    $embeddingProcess = $null
    $parsedEmbeddingPort = 0
    if ([int]::TryParse($env:RAG_EMBEDDING_PORT, [ref] $parsedEmbeddingPort) -and $parsedEmbeddingPort -gt 0) {
        $embeddingPort = $parsedEmbeddingPort
    }
    try {
        $embeddingPython = Ensure-EmbeddingPython (Resolve-PythonExecutable)
        $embeddingDeviceIndex = 0
        if (-not [int]::TryParse($env:RAG_EMBEDDING_DEVICE_INDEX, [ref] $embeddingDeviceIndex) -or $embeddingDeviceIndex -lt 0) {
            throw "RAG_EMBEDDING_DEVICE_INDEX must be a non-negative integer."
        }
        if ($env:RAG_EMBEDDING_DEVICE -eq "cuda") {
            Ensure-EmbeddingCudaTorch $embeddingPython $embeddingDeviceIndex
        }
    } catch {
        if ($env:RAG_EMBEDDING_DEVICE -eq "cuda" -and $env:GPU_STARTUP_FAILURE_POLICY -eq "cpu") {
            Write-Host "Embedding GPU startup failed: $($_.Exception.Message)" -ForegroundColor Yellow
            Write-Host "Explicitly falling back to CPU because GPU_STARTUP_FAILURE_POLICY=cpu." -ForegroundColor Yellow
            $env:RAG_EMBEDDING_DEVICE = "cpu"
            $env:RAG_EMBEDDING_DEVICE_INDEX = "0"
            try {
                $embeddingPython = Ensure-EmbeddingPython (Resolve-PythonExecutable)
            } catch {
                Write-Host $_ -ForegroundColor Red
                exit 1
            }
        } else {
            Write-Host $_ -ForegroundColor Red
            Write-Host "Embedding environment setup failed; startup stopped explicitly." -ForegroundColor Red
            exit 1
        }
    }

    $expectedEmbeddingDevice = if ($env:RAG_EMBEDDING_DEVICE -eq "cuda") {
        "cuda:$($env:RAG_EMBEDDING_DEVICE_INDEX)"
    } else {
        $env:RAG_EMBEDDING_DEVICE
    }
    if (Test-PortOpen -Port $embeddingPort) {
        $restartEmbedding = $true
        try {
            $existingEmbeddingHealth = Invoke-RestMethod -Uri "http://127.0.0.1:$embeddingPort/health" -TimeoutSec 5
            $restartEmbedding = $existingEmbeddingHealth.device -ne $expectedEmbeddingDevice
        } catch {
            $restartEmbedding = $true
        }
        if ($restartEmbedding) {
            Write-Host "Restarting the managed embedding service to apply device $expectedEmbeddingDevice..."
            if (-not (Stop-ManagedPythonModuleOnPort $embeddingPort "app\.embedding_main")) {
                Write-Host "Embedding port $embeddingPort is owned by an unmanaged process; stop it manually and rerun start.ps1." -ForegroundColor Red
                exit 1
            }
        }
    }
    if (-not (Test-PortOpen -Port $embeddingPort)) {
        $logsDir = Join-Path $RuntimeRoot "logs"
        New-Item -ItemType Directory -Path $logsDir -Force | Out-Null
        Write-Host "Starting RAG embedding service on $expectedEmbeddingDevice, port $embeddingPort. First model download/load may take several minutes..."
        $embeddingProcess = Start-Process -FilePath $embeddingPython `
            -ArgumentList "-m", "app.embedding_main" `
            -WorkingDirectory $ScreeningDir `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $logsDir "embedding.out.log") `
            -RedirectStandardError (Join-Path $logsDir "embedding.err.log") `
            -PassThru
    }
    $embeddingHealth = ([Uri] $env:RAG_EMBEDDING_BASE_URL)
    $embeddingHealthUri = "$($embeddingHealth.Scheme)://$($embeddingHealth.Host):$($embeddingHealth.Port)/health"
    $embeddingStartupTimeout = 3600
    $parsedEmbeddingStartupTimeout = 0
    if ([int]::TryParse($env:RAG_EMBEDDING_STARTUP_TIMEOUT_SECONDS, [ref] $parsedEmbeddingStartupTimeout)) {
        $embeddingStartupTimeout = [Math]::Max(60, $parsedEmbeddingStartupTimeout)
    }
    if (-not (Wait-HttpReady $embeddingHealthUri $embeddingStartupTimeout "RAG embedding service" $embeddingProcess)) {
        Write-Host "Embedding service did not become ready. See data\runtime\logs\embedding.err.log." -ForegroundColor Yellow
        exit 1
    }
    Write-Host "RAG dependencies are ready: Qdrant and embedding service."
}

if ($env:SCREENING_ASYNC_ENABLED -eq "true") {
    $rabbitPort = 5672
    if (-not [int]::TryParse($env:RABBITMQ_PORT, [ref] $rabbitPort)) {
        Write-Host "RABBITMQ_PORT must be a number, but got '$env:RABBITMQ_PORT'." -ForegroundColor Yellow
        exit 1
    }
    if (-not (Test-PortOpen -Port $rabbitPort)) {
        Write-Host "RabbitMQ is not listening on port $rabbitPort." -ForegroundColor Yellow
        Write-Host "Run 'docker compose up -d rabbitmq' or start RabbitMQ, then retry." -ForegroundColor Yellow
        exit 1
    }

    $workerPython = if (-not [string]::IsNullOrWhiteSpace($screeningGpuPython)) {
        $screeningGpuPython
    } else {
        Resolve-PythonExecutable
    }
    if ([string]::IsNullOrWhiteSpace($workerPython) -or -not (Test-Path -LiteralPath $workerPython)) {
        Write-Host "Python screening worker environment is unavailable." -ForegroundColor Yellow
        exit 1
    }
    & $workerPython -c "import pika" 2>$null
    if ($LASTEXITCODE -ne 0 -and $env:PYTHON_AUTO_VENV -eq "true") {
        try {
            $workerPython = Ensure-ScreeningPython $workerPython
        } catch {
            Write-Host $_ -ForegroundColor Yellow
            Write-Host "Failed to configure the Python screening worker environment." -ForegroundColor Yellow
            exit 1
        }
        & $workerPython -c "import pika" 2>$null
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Python module 'pika' is required by the RabbitMQ screening worker." -ForegroundColor Yellow
        Write-Host "Install screening-service\requirements.txt or set PYTHON_AUTO_VENV=true, then retry." -ForegroundColor Yellow
        exit 1
    }
    $workerCount = Get-ScreeningWorkerCount
    $featureWorkerCount = Get-ScreeningStageWorkerCount -Name "SCREENING_FEATURE_WORKERS" -Default 2
    $llmWorkerCount = Get-ScreeningStageWorkerCount -Name "SCREENING_LLM_WORKERS" -Default 2
    $env:AUDIO_ROOT = $env:AUDIO_STORAGE_DIR
    $baseWorkerDeviceIndex = 0
    [void] [int]::TryParse($env:WHISPER_DEVICE_INDEX, [ref] $baseWorkerDeviceIndex)
    $workerLogsDir = Join-Path $RuntimeRoot "logs"
    New-Item -ItemType Directory -Path $workerLogsDir -Force | Out-Null

    # Combined workers consume the same entry queue and must not coexist with the staged pipeline.
    $managedPidFiles = @(
        Get-ChildItem -LiteralPath $env:SCREENING_ARTIFACT_DIR -Filter "worker-*.pid" -File -ErrorAction SilentlyContinue
        Get-ChildItem -LiteralPath $env:SCREENING_ARTIFACT_DIR -Filter "pipeline-*.pid" -File -ErrorAction SilentlyContinue
    )
    foreach ($managedPidFile in $managedPidFiles) {
        $savedPid = 0
        if ([int]::TryParse((Get-Content -LiteralPath $managedPidFile.FullName -Raw).Trim(), [ref] $savedPid) -and
                $null -ne (Get-Process -Id $savedPid -ErrorAction SilentlyContinue)) {
            Write-Host "Stopping previous screening worker PID $savedPid before pipeline startup..."
            if (-not (Stop-ManagedScreeningWorker $savedPid)) {
                throw "Unable to stop managed screening worker PID $savedPid."
            }
        }
        Remove-Item -LiteralPath $managedPidFile.FullName -Force -ErrorAction SilentlyContinue
    }

    $pipelineStages = @(
        [pscustomobject]@{ Name = "transcription"; Count = $workerCount; UsesGpu = $true },
        [pscustomobject]@{ Name = "features"; Count = $featureWorkerCount; UsesGpu = $false },
        [pscustomobject]@{ Name = "llm"; Count = $llmWorkerCount; UsesGpu = $false }
    )
    foreach ($stage in $pipelineStages) {
        for ($workerIndex = 1; $workerIndex -le $stage.Count; $workerIndex++) {
            $workerDeviceIndex = if ($stage.UsesGpu -and $env:WHISPER_DEVICE -eq "cuda") {
                $baseWorkerDeviceIndex + $workerIndex - 1
            } else {
                0
            }
            $deviceLabel = if ($stage.UsesGpu) { "$($env:WHISPER_DEVICE):$workerDeviceIndex" } else { "CPU/network" }
            Write-Host "Starting $($stage.Name) worker $workerIndex/$($stage.Count) on $deviceLabel..."
            $previousWorkerDeviceIndex = $env:WHISPER_DEVICE_INDEX
            $previousWorkerId = $env:SCREENING_WORKER_ID
            try {
                if ($stage.UsesGpu) {
                    $env:WHISPER_DEVICE_INDEX = "$workerDeviceIndex"
                }
                $env:SCREENING_WORKER_ID = "$($stage.Name)-$workerIndex"
                $workerProcess = Start-Process -FilePath $workerPython `
                    -ArgumentList "-m", "app.workers.pipeline", $stage.Name `
                    -WorkingDirectory $ScreeningDir `
                    -WindowStyle Hidden `
                    -RedirectStandardOutput (Join-Path $workerLogsDir "screening-$($stage.Name)-worker-$workerIndex.out.log") `
                    -RedirectStandardError (Join-Path $workerLogsDir "screening-$($stage.Name)-worker-$workerIndex.err.log") `
                    -PassThru
            } finally {
                $env:WHISPER_DEVICE_INDEX = $previousWorkerDeviceIndex
                if ($null -eq $previousWorkerId) {
                    Remove-Item Env:SCREENING_WORKER_ID -ErrorAction SilentlyContinue
                } else {
                    $env:SCREENING_WORKER_ID = $previousWorkerId
                }
            }
            $pidFile = Join-Path $env:SCREENING_ARTIFACT_DIR "pipeline-$($stage.Name)-$workerIndex.pid"
            Set-Content -LiteralPath $pidFile -Value $workerProcess.Id -Encoding ASCII
        }
    }
}

if (-not (Test-PortOpen -Port 8000)) {
    Write-Host "Optional /detect service is not listening on localhost:8000." -ForegroundColor Yellow
    Write-Host "The migrated user/admin detect buttons require an external service that exposes POST /detect." -ForegroundColor Yellow
}

Start-Job -ScriptBlock {
    param([string] $ReadinessUrl, [string] $Url)
    for ($i = 0; $i -lt 180; $i++) {
        try {
            $response = Invoke-WebRequest -Uri $ReadinessUrl -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                Start-Process $Url
                break
            }
        } catch {
        }
        Start-Sleep -Seconds 2
    }
} -ArgumentList "http://127.0.0.1:8080/actuator/health/readiness", $AppUrl | Out-Null

Write-Host "Starting backend and static frontend. The browser will open after RAG warm-up and application readiness complete."
Set-Location -LiteralPath $BackendDir
$mavenCmd = "D:\Professional software\IntelliJ IDEA 2024.3.2\plugins\maven\lib\maven3\bin\mvn.cmd"
if (Test-Path -LiteralPath $mavenCmd) {
    & $mavenCmd spring-boot:run
} else {
    & .\mvnw.cmd spring-boot:run
}
