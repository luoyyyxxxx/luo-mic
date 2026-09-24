@echo off
rem ===================================================================
rem  luo mic - Windows native launcher (NO Java required)
rem
rem  Double click this file. Uses Windows' own audio API (WASAPI)
rem  so it works with VB-CABLE and other virtual sound cards reliably.
rem
rem  Options:
rem    start-luo-mic-native.bat -ListDevices
rem    start-luo-mic-native.bat -Device "CABLE"
rem ===================================================================
chcp 65001 >nul
setlocal
set "PS=powershell"
where pwsh >nul 2>&1 && set "PS=pwsh"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\native\luomic-native.ps1" %*
echo.
echo Done. Press any key to close...
pause >nul
endlocal
