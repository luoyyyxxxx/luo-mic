@echo off
rem  luo mic - remove firewall rules
rem  ASCII-only on purpose: see the note in firewall.bat
chcp 65001 >nul
title luo mic - firewall remove
net session >nul 2>&1
if errorlevel 1 (
    echo [luo mic] Administrator rights required, elevating...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
netsh advfirewall firewall delete rule name="luo mic UDP 47777"
netsh advfirewall firewall delete rule name="luo mic TCP 47778-47779"
echo Removed luo mic firewall rules.
pause
