$ErrorActionPreference = "Stop"

# application.yml now owns REMOTE_UPLOAD_ENABLED / REMOTE_UPLOAD_URL / PUBLIC_BASE_URL.
# Clear stale session-level overrides so the YAML values really take effect.
@(
  "REMOTE_UPLOAD_ENABLED",
  "REMOTE_UPLOAD_URL",
  "PUBLIC_BASE_URL"
) | ForEach-Object {
  if (Test-Path "Env:$_") {
    Remove-Item "Env:$_"
  }
}

# Only prompt for upload credentials if your Nginx upload endpoint is protected.
if (-not $env:REMOTE_UPLOAD_USER) {
  $env:REMOTE_UPLOAD_USER = Read-Host "Enter HTTP upload username, or press Enter if upload endpoint has no auth"
}

if ($env:REMOTE_UPLOAD_USER -and -not $env:REMOTE_UPLOAD_PASSWORD) {
  $securePassword = Read-Host "Enter HTTP upload password" -AsSecureString
  $env:REMOTE_UPLOAD_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
  )
}

.\start-java17.ps1
