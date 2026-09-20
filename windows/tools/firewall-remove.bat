@echo off
chcp 65001 >nul
title luo mic - 删除防火墙规则
net session >nul 2>&1
if errorlevel 1 (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
netsh advfirewall firewall delete rule name="luo mic UDP 47777"
netsh advfirewall firewall delete rule name="luo mic TCP 47778-47779"
echo 已删除 luo mic 的防火墙规则。
pause
