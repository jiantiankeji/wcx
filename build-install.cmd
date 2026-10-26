@echo off
rem ===========================================================================
rem  Double-click wrapper for build-install.ps1.
rem
rem  Why this exists: on this machine ".ps1" has no OPEN association - only a
rem  "Run with PowerShell 7" verb whose command is
rem      pwsh.exe -Command "$host.UI.RawUI.WindowTitle = 'PowerShell 7 (x64)'; & '%1'"
rem  That has no -NoExit, so whenever the script ends early (bad argument, policy
rem  block, adb/install failure) the console closes before the error can be read:
rem  the window "flashes and disappears" and nothing lands in buildlog.txt either,
rem  because that file is only opened at line 73 of the script.
rem
rem  This wrapper starts PowerShell with -NoExit, so the window is kept no matter
rem  how the run ends, and it falls back to Windows PowerShell when pwsh is absent.
rem  The script only needs PowerShell 5.1, so both hosts work.
rem
rem  Arguments are forwarded verbatim, e.g.:
rem      build-install.cmd -NoInstall
rem      build-install.cmd -Debug
rem      build-install.cmd -AllowDebugSigning
rem ===========================================================================
setlocal

set "SCRIPT=%~dp0build-install.ps1"
set "PS="

if not exist "%SCRIPT%" (
    echo [build-install.cmd] build-install.ps1 not found next to this file:
    echo [build-install.cmd] %SCRIPT%
    pause
    exit /b 1
)

rem Prefer PowerShell 7; fall back to Windows PowerShell 5.1.
where pwsh.exe >nul 2>nul && set "PS=pwsh.exe"
if not defined PS (
    where powershell.exe >nul 2>nul && set "PS=powershell.exe"
)
if not defined PS (
    echo [build-install.cmd] neither pwsh.exe nor powershell.exe was found in PATH.
    pause
    exit /b 1
)

echo [build-install.cmd] host: %PS%
echo [build-install.cmd] the console stays open after the run - type "exit" to close it.
echo.

"%PS%" -NoProfile -NoExit -ExecutionPolicy Bypass -File "%SCRIPT%" %*

rem Reached only after the interactive session is left with "exit".
endlocal
