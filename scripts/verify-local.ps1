<#
.SYNOPSIS
  Local verification gate: static checks, builds, unit/integration tests, contract + Compose validation,
  runtime health checks, telemetry checks and Playwright E2E. Prints a PASS/FAIL summary and exits 1 if any
  required check fails. Read-only towards Docker volumes and .env.
.PARAMETER SkipRuntime
  Only run checks that need no running stack (static, builds, unit tests, config validation).
.PARAMETER SkipE2E
  Skip Playwright (runtime health checks still run).
.EXAMPLE
  pnpm verify
  powershell -ExecutionPolicy Bypass -File scripts/verify-local.ps1 -SkipRuntime
#>
[CmdletBinding()]
param([switch]$SkipRuntime, [switch]$SkipE2E)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$env:NX_DAEMON = 'false'
$results = New-Object System.Collections.Generic.List[object]

function Add-Result([string]$name, [bool]$ok, [string]$note = '') {
  $results.Add([pscustomobject]@{ Check = $name; Result = $(if ($ok) { 'PASS' } else { 'FAIL' }); Note = $note })
  $color = if ($ok) { 'Green' } else { 'Red' }
  Write-Host ("[{0}] {1} {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $note) -ForegroundColor $color
}

# Runs a command line through cmd so native stderr does not become a PowerShell error (Windows PowerShell 5.1).
function Invoke-Check([string]$name, [string]$commandLine) {
  Write-Host "`n>>> $commandLine" -ForegroundColor Cyan
  cmd /c "$commandLine 2>&1" | Out-Host
  Add-Result $name ($LASTEXITCODE -eq 0) "(exit $LASTEXITCODE)"
}

function Get-Http([string]$url) {
  try { Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5 } catch { $null }
}

function Test-Http([string]$name, [string]$url, [string]$mustContain = '') {
  # Retries for ~30s: some services (e.g. Loki's ingester) report not-ready for a short window after a restart.
  $ok = $false
  for ($i = 0; $i -lt 6 -and -not $ok; $i++) {
    if ($i -gt 0) { Start-Sleep -Seconds 5 }
    $res = Get-Http $url
    # PS 5.1 returns vendor content types (e.g. actuator's application/vnd.spring-boot...) as bytes.
    $body = if ($res -and $res.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($res.Content) } elseif ($res) { $res.Content } else { '' }
    $ok = [bool]($res -and $res.StatusCode -eq 200 -and ($mustContain -eq '' -or $body -match [regex]::Escape($mustContain)))
  }
  Add-Result $name $ok $url
}

# --- Static, build and unit-test gates ------------------------------------------------------------
Invoke-Check 'pnpm install --frozen-lockfile' 'pnpm install --frozen-lockfile'
Invoke-Check 'nx show projects' 'pnpm nx show projects'
Invoke-Check 'lint' 'pnpm nx run-many -t lint --output-style=static'
Invoke-Check 'typecheck' 'pnpm nx run-many -t typecheck --output-style=static'
Invoke-Check 'unit tests (frontend + Maven incl. Testcontainers)' 'pnpm nx run-many -t test --output-style=static'
Invoke-Check 'production builds (Next.js, Vite, Maven package)' 'pnpm nx run-many -t build --output-style=static'
Invoke-Check 'OpenAPI contract lint' 'pnpm nx run contracts:validate --output-style=static'
Invoke-Check 'docker compose config (base)' 'docker compose config -q'
Invoke-Check 'docker compose config (base + apps)' 'docker compose -f docker-compose.yml -f docker-compose.apps.yml config -q'

# --- Repository security checks -------------------------------------------------------------------
cmd /c 'git check-ignore -q .env' | Out-Null
Add-Result '.env is git-ignored' ($LASTEXITCODE -eq 0)
$leaks = @()
if (Test-Path .env) {
  $secrets = Get-Content .env | Where-Object { $_ -match '^[A-Z0-9_]*(PASSWORD|SECRET|KEY)[A-Z0-9_]*=(.{16,})$' } | ForEach-Object { ($_ -split '=', 2)[1] }
  $scan = @(Get-ChildItem -Recurse -File -Path dist, apps\chatdashboardapp\.next\static -ErrorAction SilentlyContinue)
  $tracked = @(cmd /c 'git ls-files --cached --others --exclude-standard' | Where-Object { Test-Path $_ -PathType Leaf } | ForEach-Object { Get-Item $_ })
  foreach ($f in ($scan + $tracked)) {
    $text = [System.IO.File]::ReadAllText($f.FullName)
    foreach ($s in $secrets) { if ($text.Contains($s)) { $leaks += $f.FullName } }
  }
}
Add-Result 'no local secret values in bundles or committable files' ($leaks.Count -eq 0) (($leaks | Select-Object -Unique) -join ', ')

# --- Runtime checks (stack must be running: Mode A or Mode B) -------------------------------------
if (-not $SkipRuntime) {
  Invoke-Check 'Flyway validate' 'pnpm nx run flyway-migration:validate --output-style=static'
  Test-Http 'backend /actuator/health' 'http://localhost:8080/actuator/health' '"UP"'
  Test-Http 'backend /api/v1/status (all UP)' 'http://localhost:8080/api/v1/status' '"status":"UP","timestamp"'
  Test-Http 'backend /api/v1/bootstrap (Flyway record)' 'http://localhost:8080/api/v1/bootstrap' 'local-poc'
  Test-Http 'Centrifugo /health' 'http://localhost:8000/health'
  Test-Http 'Prometheus ready' 'http://localhost:9090/-/ready'
  Test-Http 'Grafana health' 'http://localhost:3001/api/health' '"database"'
  Test-Http 'Loki ready' 'http://localhost:3100/ready' 'ready'
  Test-Http 'Tempo ready' 'http://localhost:3200/ready' 'ready'
  Test-Http 'OTel Collector health' 'http://localhost:13133/' 'Server available'
  Test-Http 'dashboard page' 'http://localhost:3000/' 'Chat Platform'
  Test-Http 'widget demo page' 'http://localhost:5173/' 'chat-platform-widget'

  $q = Get-Http 'http://localhost:9090/api/v1/query?query=max(up%7Bjob%3D%22modulithplatformservice%22%7D)'
  Add-Result 'Prometheus scrapes backend (max up == 1)' ([bool]($q -and $q.Content -match '"value":\[[^,]+,"1"\]'))

  # Trace + log correlation: one request, find its trace in Tempo and its log line in Loki.
  $req = Get-Http 'http://localhost:8080/api/v1/bootstrap'
  $traceId = if ($req) { $req.Headers['X-Trace-Id'] } else { '' }
  $traceOk = $false; $logOk = $false
  if ($traceId) {
    for ($i = 0; $i -lt 12 -and -not ($traceOk -and $logOk); $i++) {
      Start-Sleep -Seconds 5
      if (-not $traceOk) { $traceOk = [bool](Get-Http "http://localhost:3200/api/v2/traces/$traceId") }
      if (-not $logOk) {
        $lq = [uri]::EscapeDataString("{service_name=`"modulithplatformservice`"} | trace_id=`"$traceId`"")
        $l = Get-Http "http://localhost:3100/loki/api/v1/query_range?query=$lq&since=10m"
        $logOk = [bool]($l -and $l.Content -match 'Bootstrap record requested')
      }
    }
  }
  Add-Result 'trace reaches Tempo (OTLP via Collector)' $traceOk "trace $traceId"
  Add-Result 'log with same trace_id reaches Loki (OTLP via Collector)' $logOk

  if (-not $SkipE2E) {
    Invoke-Check 'Playwright E2E + live contract tests' 'pnpm nx run platform-tests:e2e --output-style=static'
  }
}

# --- Summary --------------------------------------------------------------------------------------
Write-Host "`n================ SUMMARY ================"
$results | Format-Table -AutoSize | Out-Host
$failed = @($results | Where-Object { $_.Result -eq 'FAIL' })
if ($failed.Count -gt 0) {
  Write-Host "VERIFY FAILED: $($failed.Count) of $($results.Count) checks failed" -ForegroundColor Red
  exit 1
}
Write-Host "VERIFY PASSED: $($results.Count) checks" -ForegroundColor Green
exit 0
