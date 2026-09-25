@echo off
rem ===================================================================
rem  luo mic - allow firewall ports (UDP 47777, TCP 47778-47779)
rem  NOTE: this file is intentionally ASCII-only. cmd.exe reads .bat files
rem  using the OEM/ANSI code page, so non-ASCII text here can corrupt the
rem  parsed line (it can even swallow the trailing newline) and break the
rem  script. Keep all Chinese/Unicode text in the .ps1 files instead.
rem ===================================================================
chcp 65001 >nul
title luo mic - firewall
net session >nul 2>&1
if errorlevel 1 (
    echo [luo mic] Administrator rights required, elevating...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
echo ============================================
echo  luo mic firewall
echo ============================================
netsh advfirewall firewall delete rule name="luo mic UDP 47777" >nul 2>&1
netsh advfirewall firewall delete rule name="luo mic TCP 47778-47779" >nul 2>&1
netsh advfirewall firewall add rule name="luo mic UDP 47777" dir=in action=allow protocol=UDP localport=47777
netsh advfirewall firewall add rule name="luo mic TCP 47778-47779" dir=in action=allow protocol=TCP localport=47778-47779
echo.
echo Allowed: UDP 47777 (discovery), TCP 47778-47779 (control + audio)
echo.
pause
