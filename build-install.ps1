#Requires -Version 5.1
<#
  Build WCX (standard flavor) and optionally overwrite-install it to the
  connected device.

  Usage:
    .\build-install.ps1              # release APK, then ask whether to install
    .\build-install.ps1 -Debug       # debug APK, then ask whether to install
    .\build-install.ps1 -NoInstall   # build only, skip adb (no prompt)

  Behavior:
    - "Install to the connected device?" is asked on every run: press Enter to
      install, or answer n to keep the APK only. Passing -NoInstall skips both
      the prompt and the install step.
    - Everything shown on the console is mirrored to buildlog.txt (the file is
      overwritten on every run), including gradlew's raw output.
    - The script never calls `exit`, so a dot-sourced run
      (". .\build-install.ps1") does not kill the hosting PowerShell session
      and a double-clicked run does not flash away. It prints the result and
      waits for Enter when it finishes.

  Notes:
    - Keep this file ASCII-only. Windows PowerShell 5.1 decodes BOM-less files
      with the ANSI code page, so non-ASCII text gets garbled there.
    - ABI splits are on, so a build produces one APK per ABI
      (app-standard-<abi>-<variant>.apk) instead of a single
      app-standard-<variant>.apk. The install step picks the APK matching the
      device's "getprop ro.product.cpu.abi", falling back to the first one if
      nothing matches.
    - AGP only creates lintVital* for release variants, so the lint skip is
      added to the release build only. Excluding a task that does not exist
      makes Gradle 9 abort with "Selection failed".
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

$logFile = Join-Path $root 'buildlog.txt'
$script:failed = $false
$script:transcriptStarted = $false

# --- 0. logging ---------------------------------------------------------------
# Mirror every stream (including gradlew's raw output) into buildlog.txt, so a
# run can still be inspected after the console window is gone.
try {
    Start-Transcript -Path $logFile -Force | Out-Null
    $script:transcriptStarted = $true
    Write-Host "=== build-install start $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ===" -ForegroundColor Cyan
} catch {
    Write-Host "[warn] cannot write $logFile - $($_.Exception.Message)" -ForegroundColor Yellow
}

# Aborts the current run without calling `exit`, so a dot-sourced invocation
# does not terminate the hosting PowerShell session.
function Fail([string]$msg) {
    throw [System.Exception]::new($msg)
}

try {

    # --- 1. signing credentials (optional) -----------------------------------
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

    # --- 2. Android SDK location ---------------------------------------------
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

    # --- 3. build ------------------------------------------------------------
    $variant = if ($Debug) { 'Debug' } else { 'Release' }
    $task = ":app:assembleStandard$variant"
    Write-Host "=== $task ===" -ForegroundColor Cyan

    $gradleArgs = @($task)
    if (-not $Debug) {
        $gradleArgs += @('-x', "lintVitalStandard$variant", '-x', "lintVitalReportStandard$variant")
    }
    $gradleArgs += '--console=plain'

    # "2>&1 | Out-Host" keeps gradle's raw output flowing through PowerShell so
    # the transcript captures it; $LASTEXITCODE still reflects gradlew.bat.
    & (Join-Path $root 'gradlew.bat') @gradleArgs 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { Fail "gradle $task failed" }

    # --- 4. locate the apk(s) ------------------------------------------------
    # ABI splits are on, so there is usually no single app-standard-<variant>.apk.
    $apkDir = Join-Path $root "app\build\outputs\apk\standard\$($variant.ToLower())"
    if (-not (Test-Path $apkDir)) { Fail "apk output directory not found: $apkDir" }

    $apks = @(Get-ChildItem -Path $apkDir -Filter '*.apk' -File | Sort-Object Name)
    if ($apks.Count -eq 0) { Fail "no apk produced in $apkDir" }

    foreach ($a in $apks) {
        Write-Host ("=== built: {0} ({1} MB) ===" -f $a.FullName, [math]::Round($a.Length / 1MB, 2)) -ForegroundColor Green
    }

    # --- 5. ask whether to install -------------------------------------------
    # Asked on every run; -NoInstall skips both the prompt and the install.
    $installNow = $false
    if ($NoInstall) {
        Write-Host '=== install skipped (-NoInstall) ===' -ForegroundColor Yellow
    } else {
        $answer = 'y'
        try {
            $answer = Read-Host 'Install to the connected device now? [Y/n]'
        } catch {
            Write-Host '[warn] no interactive input available, assuming yes' -ForegroundColor Yellow
        }
        $installNow = -not ("$answer".Trim() -match '^(n|no)$')
        if (-not $installNow) {
            Write-Host '=== install skipped ===' -ForegroundColor Yellow
        }
    }

    if ($installNow) {

        # --- 6. locate adb ---------------------------------------------------
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

        # --- 7. pick the apk built for the device abi ------------------------
        $deviceAbi = "$(& $adb shell getprop ro.product.cpu.abi 2>$null | Select-Object -First 1)".Trim()
        $apk = $apks | Where-Object { $deviceAbi -and $_.Name -like "*$deviceAbi*" } | Select-Object -First 1
        if ($apk) {
            Write-Host "[info] device abi=$deviceAbi -> $($apk.Name)" -ForegroundColor DarkGray
        } else {
            $apk = $apks[0]
            Write-Host "[warn] no apk matches device abi '$deviceAbi' - falling back to $($apk.Name)" -ForegroundColor Yellow
        }

        # --- 8. install ------------------------------------------------------
        $sizeMb = [math]::Round($apk.Length / 1MB, 2)
        Write-Host "=== installing $($apk.FullName) ($sizeMb MB) ===" -ForegroundColor Cyan

        $remote = '/data/local/tmp/wcx-install.apk'
        & $adb push $apk.FullName $remote 2>&1 | Out-Host
        if ($LASTEXITCODE -ne 0) { Fail 'adb push failed' }

        $result = & $adb shell "pm install -r $remote" 2>&1
        $result | Write-Host
        if ($LASTEXITCODE -ne 0 -or ("$result" -notmatch 'Success')) {
            Fail 'pm install failed (signature mismatch with the installed build? uninstall is intentionally not automated)'
        }

        & $adb shell "rm -f $remote" 2>&1 | Out-Host

        Write-Host '=== installed. Restart WeChat to apply hooks. ===' -ForegroundColor Green
    }

} catch {
    $script:failed = $true
    Write-Host ''
    Write-Host "[FAIL] $($_.Exception.Message)" -ForegroundColor Red
}

# --- 9. wrap up --------------------------------------------------------------
Write-Host ''
if ($script:failed) {
    Write-Host "=== build-install FAILED - log: $logFile ===" -ForegroundColor Red
    $global:LASTEXITCODE = 1
} else {
    Write-Host "=== build-install done - log: $logFile ===" -ForegroundColor Green
    $global:LASTEXITCODE = 0
}

if ($script:transcriptStarted) {
    try { Stop-Transcript | Out-Null } catch { }
}

# Never `exit`, so the hosting session survives a dot-sourced run and the
# console window stays open on a double-clicked one. Skip the pause when stdin
# is redirected (CI / scheduled runs) instead of blocking on a prompt nobody
# can answer.
if (-not [Console]::IsInputRedirected) {
    try { Read-Host 'Press Enter to exit' | Out-Null } catch { }
}
