<#
.SYNOPSIS
    Starts the GEO Tree development backend (MongoDB + FastAPI) with Docker Compose.

.DESCRIPTION
    1. Checks that the Docker CLI exists and the Docker engine is reachable.
    2. Runs `docker compose up -d --build` from the repository root.
    3. Waits for http://localhost:<port>/api/v1/health.
    4. Prints the local address and likely LAN addresses for a physical phone.

    It never deletes Docker volumes and never changes Windows Firewall settings.
    Compatible with Windows PowerShell 5.1.
#>
[CmdletBinding()]
param(
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot

function Write-Step([string]$text) { Write-Host "  > $text" -ForegroundColor DarkGray }
function Stop-WithError([string]$text) {
    Write-Host ''
    Write-Host "ERROR: $text" -ForegroundColor Red
    exit 1
}

# The API host port can be overridden in .env (GEO_TREE_API_HOST_PORT); default 8000.
$port = 8000
$envFile = Join-Path $repoRoot '.env'
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^\s*GEO_TREE_API_HOST_PORT\s*=\s*(\d+)\s*$') { $port = [int]$Matches[1] }
    }
}

Write-Host ''
Write-Host 'GEO Tree - local backend' -ForegroundColor Green
Write-Host "Repository: $repoRoot"
Write-Host ''

# 1. Docker CLI
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Stop-WithError 'Docker CLI not found. Install Docker Desktop (https://www.docker.com/products/docker-desktop/) and run this again.'
}
Write-Step 'Docker CLI found'

# 2. Docker engine. cmd /c keeps native stderr out of PowerShell's error stream.
cmd /c 'docker info >nul 2>&1'
if ($LASTEXITCODE -ne 0) {
    $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (Test-Path -LiteralPath $desktop) {
        Stop-WithError ("Docker Desktop is installed but its engine is not running.`n" +
            "       Start Docker Desktop, wait until it shows 'Engine running', then run run.bat again.")
    }
    Stop-WithError 'The Docker engine is not reachable. Start Docker and run this again.'
}
Write-Step 'Docker engine is running'

# 3. Start services. Named volumes (geo_tree_mongodb_data, geo_tree_uploads) are reused, never removed.
Write-Step 'docker compose up -d --build'
cmd /c 'docker compose up -d --build'
if ($LASTEXITCODE -ne 0) {
    Stop-WithError 'docker compose up failed. See the output above.'
}

# 4. Wait for the health endpoint.
$healthUrl = "http://localhost:$port/api/v1/health"
Write-Step "Waiting for $healthUrl (up to $TimeoutSeconds s)"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$health = $null
while ((Get-Date) -lt $deadline) {
    try {
        $response = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3
        if ($response.service -eq 'geo-tree-api' -and $response.status -eq 'ok') { $health = $response; break }
    } catch {
        # Not ready yet.
    }
    Start-Sleep -Seconds 2
}
if ($null -eq $health) {
    Stop-WithError ("The backend did not become healthy within $TimeoutSeconds s.`n" +
        "       Check: docker compose ps   and   docker compose logs backend")
}

# 5. Likely LAN addresses for a phone on the same Wi-Fi / hotspot.
$lan = @()
try {
    $lan = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object {
            $_.IPAddress -notlike '127.*' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.AddressState -eq 'Preferred' -and
            $_.InterfaceAlias -notmatch 'vEthernet|WSL|Loopback|Docker|VirtualBox|VMware|Hyper-V|Bluetooth'
        } |
        Sort-Object InterfaceMetric |
        ForEach-Object { [pscustomobject]@{ Address = $_.IPAddress; Interface = $_.InterfaceAlias } }
} catch {
    $lan = [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
        Where-Object { $_.AddressFamily -eq 'InterNetwork' -and $_.ToString() -notlike '127.*' -and $_.ToString() -notlike '169.254.*' } |
        ForEach-Object { [pscustomobject]@{ Address = $_.ToString(); Interface = '' } }
}

Write-Host ''
Write-Host 'GEO Tree Backend Ready' -ForegroundColor Green
Write-Host "  API $($health.version), database $($health.database)"
Write-Host ''
Write-Host 'Local:'
Write-Host "  http://localhost:$port"
Write-Host ''
Write-Host 'Android emulator:'
Write-Host "  http://10.0.2.2:$port"
Write-Host ''
Write-Host 'Physical phone (same Wi-Fi/hotspot) - candidate LAN addresses:'
if ($lan.Count -eq 0) {
    Write-Host '  none found (is this computer connected to Wi-Fi or Ethernet?)' -ForegroundColor Yellow
} else {
    foreach ($entry in $lan) {
        $label = if ($entry.Interface) { "   ($($entry.Interface))" } else { '' }
        Write-Host "  http://$($entry.Address):$port$label"
    }
}
Write-Host ''
Write-Host 'Enter one of these in GEO Tree > Settings > Change Server, then Test & Save.'
Write-Host 'If the phone cannot connect, Windows Firewall may be blocking inbound TCP' $port '- this script does not change firewall settings.' -ForegroundColor DarkGray
Write-Host 'Containers use restart: unless-stopped, so they come back when Docker Desktop starts.' -ForegroundColor DarkGray
Write-Host 'Stop without deleting data: docker compose stop   (never use: docker compose down -v)' -ForegroundColor DarkGray
exit 0
