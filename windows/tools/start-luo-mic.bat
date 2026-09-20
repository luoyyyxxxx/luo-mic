@echo off
rem ===================================================================
rem  luo mic - one click launcher (Windows)
rem  Double click this file. It will:
rem    1. find your Java (JDK)
rem    2. compile luo mic PC side
rem    3. open the luo mic window
rem  First run takes about 10 seconds. Later runs are instant.
rem ===================================================================
chcp 65001 >nul
setlocal
set "PS=powershell"
where pwsh >nul 2>&1 && set "PS=pwsh"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0pc-build.ps1" %*
echo.
echo Window closed. Press any key to exit this console...
pause >nul
endlocal
