@echo off
chcp 65001 >nul
title luo mic - 防火墙放行
net session >nul 2>&1
if errorlevel 1 (
    echo 需要管理员权限，正在提权...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
echo ============================================
echo  luo mic 防火墙放行
echo ============================================
netsh advfirewall firewall delete rule name="luo mic UDP 47777" >nul 2>&1
netsh advfirewall firewall delete rule name="luo mic TCP 47778-47779" >nul 2>&1
netsh advfirewall firewall add rule name="luo mic UDP 47777" dir=in action=allow protocol=UDP localport=47777
netsh advfirewall firewall add rule name="luo mic TCP 47778-47779" dir=in action=allow protocol=TCP localport=47778-47779
echo.
echo 已放行：UDP 47777（设备发现）、TCP 47778-47779（控制与音频）
echo.
pause
