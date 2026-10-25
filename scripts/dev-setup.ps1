<#
.SYNOPSIS
  Idempotent first-time setup: checks prerequisites, creates .env (only if absent) with random dev-only secrets,
  and installs JS dependencies from the lockfile. Never overwrites .env, never touches Docker volumes.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/dev-setup.ps1
#>
[CmdletBinding()]
param([switch]$SkipInstall)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$problems = @()

function Get-Major([string]$text) { if ($text -match '(\d+)') { [int]$Matches[1] } else { 0 } }

# --- Prerequisites -----------------------------------------------------------
$node = (& node --version) 2>$null
if (-not $node) { $problems += 'Node.js not found (need >= 24 LTS)' }
elseif ((Get-Major $node) -lt 24) { $problems += "Node.js $node found; need >= 24 LTS" }
else { Write-Host "OK   node $node" }

$pnpm = (& pnpm --version) 2>$null
if (-not $pnpm) { $problems += 'pnpm not found. Run: corepack enable  (version is pinned by package.json "packageManager")' }
else { Write-Host "OK   pnpm $pnpm" }

# cmd /c merges stderr (where java -version prints) without PowerShell 5.1 turning it into an error record.
$javaVersion = (cmd /c 'java -version 2>&1') | Out-String
if ($javaVersion -match 'version "(\d+)') {
  if ([int]$Matches[1] -lt 25) { $problems += "Java $($Matches[1]) on PATH; need JDK 25+ (set JAVA_HOME to a JDK 25 install)" }
  else { Write-Host "OK   java $($Matches[1]) (JAVA_HOME=$env:JAVA_HOME)" }
} else { $problems += 'Java not found; install JDK 25 and set JAVA_HOME' }

$dockerOk = $false
try { & docker info --format '{{.ServerVersion}}' *> $null; $dockerOk = ($LASTEXITCODE -eq 0) } catch { }
if ($dockerOk) { Write-Host "OK   docker $(& docker --version)" } else { $problems += 'Docker daemon not reachable (start Docker Desktop)' }

# --- .env --------------------------------------------------------------------
function New-Secret([int]$bytes = 24) {
  $buf = New-Object byte[] $bytes
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buf)
  # URL/shell safe: hex only.
  -join ($buf | ForEach-Object { $_.ToString('x2') })
}

$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  Write-Host 'OK   .env already exists - left unchanged'
} else {
  $lines = Get-Content (Join-Path $root '.env.example') | ForEach-Object {
    if ($_ -match '^([A-Z0-9_]+)=__generate__$') { "$($Matches[1])=$(New-Secret)" } else { $_ }
  }
  # UTF-8 without BOM so Docker Compose and Spring Boot parse the first key correctly.
  [System.IO.File]::WriteAllLines($envFile, [string[]]$lines, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host 'OK   .env created with random local-only secrets (values not printed)'
}

# --- Dependencies ------------------------------------------------------------
if (-not $SkipInstall -and $pnpm) {
  & pnpm install --frozen-lockfile
  if ($LASTEXITCODE -ne 0) { $problems += 'pnpm install --frozen-lockfile failed' }
  & pnpm exec playwright install chromium
  if ($LASTEXITCODE -ne 0) { $problems += 'playwright browser install failed' }
}

if ($problems.Count -gt 0) {
  Write-Host ''
  $problems | ForEach-Object { Write-Host "FAIL $_" -ForegroundColor Red }
  exit 1
}
Write-Host "`nSetup complete. Next: docker compose up -d --wait, then see docs/setup/local-development.md"
