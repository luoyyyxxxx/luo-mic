@echo off
rem ===================================================================
rem  luo mic - one click APK builder (Windows)
rem  Double click this file. It will:
rem    1. find your Java (JDK 17+)
rem    2. download the Android SDK tools (first run only, about 400MB)
rem    3. build the APK, then copy it to your Desktop
rem  First run takes about 10-30 minutes (downloads). Later runs: about 1 minute.
rem ===================================================================
chcp 65001 >nul
setlocal
set "PS=powershell"
where pwsh >nul 2>&1 && set "PS=pwsh"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1" %*
echo.
echo Done. Press any key to exit this console...
pause >nul
endlocal
