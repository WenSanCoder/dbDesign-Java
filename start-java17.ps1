$ErrorActionPreference = "Stop"

$serverPort = 8080
$mavenRepo = "D:\develop\apache-maven-3.9.11\mvn_repo"

function Stop-ListenerOnPort {
  param(
    [Parameter(Mandatory = $true)]
    [int] $Port
  )

  $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
  if (-not $connections) {
    Write-Host "Port $Port is free."
    return
  }

  $processIds = $connections | Select-Object -ExpandProperty OwningProcess -Unique
  foreach ($processId in $processIds) {
    if ($processId -eq $PID) {
      throw "Port $Port is used by the current PowerShell process. Refusing to stop itself."
    }

    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process) {
      Write-Host "Stopping process on port ${Port}: $($process.ProcessName) (PID $processId)"
      Stop-Process -Id $processId -Force
    }
  }

  Start-Sleep -Seconds 1
  $remaining = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
  if ($remaining) {
    $remainingPids = ($remaining | Select-Object -ExpandProperty OwningProcess -Unique) -join ", "
    throw "Port $Port is still occupied by PID(s): $remainingPids"
  }

  Write-Host "Port $Port has been released."
}

function Resolve-JavaHome {
  $candidates = @(
    $env:JAVA_HOME,
    "C:\Users\20979\.jdks\ms-17.0.18",
    "C:\Users\20979\.jdks\ms-21.0.10",
    "C:\Users\20979\.jdks\openjdk-25"
  ) | Where-Object { $_ }

  foreach ($candidate in $candidates) {
    $javaExe = Join-Path $candidate "bin\java.exe"
    if (Test-Path -LiteralPath $javaExe) {
      return $candidate
    }
  }

  throw "No valid JDK found. Please install JDK 17 or point JAVA_HOME to a valid JDK directory."
}

Stop-ListenerOnPort -Port $serverPort

$env:JAVA_HOME = Resolve-JavaHome
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:SERVER_PORT = "$serverPort"

if (-not $env:DB_URL) {
  $env:DB_URL = "jdbc:opengauss://x6.sjcmc.cn:34003/postgres?user=sht&password=@Sht20051229"
}

if (-not $env:REDIS_HOST) {
  $env:REDIS_HOST = "127.0.0.1"
}
if (-not $env:REDIS_PORT) {
  $env:REDIS_PORT = "6380"
}
if (-not $env:REDIS_DATABASE) {
  $env:REDIS_DATABASE = "0"
}

Write-Host "Using JAVA_HOME=$env:JAVA_HOME"
Write-Host "Redis SSH tunnel and password are loaded from application.yml."
Write-Host "Using local Redis tunnel endpoint $env:REDIS_HOST`:$env:REDIS_PORT/$env:REDIS_DATABASE"
java -version

mvn -Dmaven.repo.local="$mavenRepo" spring-boot:run
