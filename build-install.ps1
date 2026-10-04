#Requires -Version 5.1
<#
  Build + overwrite-install WCXLC (standard flavor) to the connected device.

  Usage:
    .\build-install.ps1              # release APK, then install
    .\build-install.ps1 -Debug       # debug APK, then install
    .\build-install.ps1 -NoInstall   # build only, skip adb

  Notes:
    - Keep this file ASCII-only. Windows PowerShell 5.1 decodes BOM-less files
      with the ANSI code page, so non-ASCII text gets garbled there.
    - Signing credentials are read from local-creds.env (same variables as
      fastbuild.bat). If absent, Gradle falls back to the debug keystore, which
      is fine for local installs but cannot overwrite a release-signed app.
    - No -PdisableMinify: measured, APK grows 28MB -> 90MB and adb install
      becomes flaky, so minify stays on.
    - Install is push + "pm install -r" (plain "adb install" fails
      intermittently here). No uninstall / pm clear: WeChat data must never
      be wiped, and overwrite install keeps module prefs intact.
#>

# No [CmdletBinding()] here on purpose: it would inject the common parameter
# -Debug and collide with the switch below ("parameter Debug defined multiple times").
param(
    [switch]$Debug,
    [switch]$NoInstall
)

$root = $PSScriptRoot
Set-Location $root

function Fail([string]$msg) {
    Write-Host "[FAIL] $msg" -ForegroundColor Red
    exit 1
}

# --- 1. signing credentials (optional) ---------------------------------------
$credsFile = Join-Path $root 'local-creds.env'
if (Test-Path $credsFile) {
    foreach ($line in Get-Content $credsFile) {
        if ($line -match '^\s*#' -or $line -notmatch '^\s*(WEKIT_[A-Z_]+)\s*=\s*(.+?)\s*$') { continue }
        $name = $Matches[1]
        $value = $Matches[2]
        if (-not [Environment]::GetEnvironmentVariable($name)) {
            [Environment]::SetEnvironmentVariable($name, $value)
        }
    }
}
if (-not $env:WEKIT_KEYSTORE_FILE) {
    Write-Host '[warn] local-creds.env not found - Gradle will sign with the debug keystore fallback' -ForegroundColor Yellow
}

# --- 2. Android SDK location --------------------------------------------------
# Gradle resolves the SDK from ANDROID_HOME -> ANDROID_SDK_ROOT -> local.properties
# (sdk.dir). Without any of them the build dies with "SDK location not found", so
# probe the usual Windows install paths and export ANDROID_HOME for this process.
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT -and
    -not (Test-Path (Join-Path $root 'local.properties'))) {
    $sdk = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk'),
        'C:\Android\Sdk',
        'D:\Android\Sdk'
    ) | Where-Object { Test-Path (Join-Path $_ 'platform-tools') } | Select-Object -First 1
    if (-not $sdk) {
        Fail 'Android SDK not found. Set ANDROID_HOME, or put sdk.dir=<path> in local.properties.'
    }
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    Write-Host "[info] ANDROID_HOME=$sdk" -ForegroundColor DarkGray
}

# --- 3. build -----------------------------------------------------------------
$variant = if ($Debug) { 'Debug' } else { 'Release' }
$task = ":app:assembleStandard$variant"
Write-Host "=== $task ===" -ForegroundColor Cyan
& (Join-Path $root 'gradlew.bat') $task `
    -x "lintVitalStandard$variant" `
    -x "lintVitalReportStandard$variant" `
    --console=plain
if ($LASTEXITCODE -ne 0) { Fail "gradle $task failed" }

$apk = Join-Path $root "app\build\outputs\apk\standard\$($variant.ToLower())\app-standard-$($variant.ToLower()).apk"
if (-not (Test-Path $apk)) { Fail "apk not found: $apk" }

if ($NoInstall) {
    Write-Host "=== built: $apk (install skipped) ===" -ForegroundColor Green
    exit 0
}

# --- 4. locate adb ------------------------------------------------------------
$adb = if ($env:ADB) { $env:ADB } else { 'adb' }
if (-not (Get-Command $adb -ErrorAction SilentlyContinue)) {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
        (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk\platform-tools\adb.exe'),
        'C:\platform-tools\adb.exe',
        'D:\platform-tools\adb.exe'
    )
    $adb = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $adb) { Fail 'adb not found. Add it to PATH or set the ADB environment variable.' }
}

$devices = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device$' })
if ($devices.Count -eq 0) { Fail 'no device connected (check: adb devices)' }
if ($devices.Count -gt 1) { Write-Host "[warn] $($devices.Count) devices connected, installing to all of them" -ForegroundColor Yellow }

# --- 5. install ---------------------------------------------------------------
$sizeMb = [math]::Round((Get-Item $apk).Length / 1MB, 2)
Write-Host "=== installing $apk ($sizeMb MB) ===" -ForegroundColor Cyan

$remote = '/data/local/tmp/wcx-install.apk'
& $adb push $apk $remote
if ($LASTEXITCODE -ne 0) { Fail 'adb push failed' }

$result = & $adb shell "pm install -r $remote" 2>&1
$result | Write-Host
if ($LASTEXITCODE -ne 0 -or ("$result" -notmatch 'Success')) {
    Fail 'pm install failed (signature mismatch with the installed build? uninstall is intentionally not automated)'
}

& $adb shell "rm -f $remote"

Write-Host '=== installed. Restart WeChat to apply hooks. ===' -ForegroundColor Green