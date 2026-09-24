@echo off
rem 用 Windows 原生方式列出所有播放设备（含虚拟声卡），排查"听不到声音"用
chcp 65001 >nul
setlocal
set "PS=powershell"
where pwsh >nul 2>&1 && set "PS=pwsh"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\native\luomic-native.ps1" -ListDevices
echo.
pause >nul
endlocal
