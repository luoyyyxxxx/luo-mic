@echo off
rem  List every audio playback device (incl. virtual cables) - for "no sound"
rem  ASCII-only on purpose: see the note in firewall.bat
chcp 65001 >nul
setlocal
set "PS=powershell"
where pwsh >nul 2>&1 && set "PS=pwsh"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\native\luomic-native.ps1" -ListDevices
echo.
pause >nul
endlocal
