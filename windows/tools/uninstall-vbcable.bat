@echo off
chcp 65001 >nul
title luo mic - 卸载 VB-CABLE
set "PKG="
for %%F in ("%~dp0VBCABLE_Setup*.exe" "%~dp0vbcable*.exe" "%~dp0VBCABLE*.exe") do if exist "%%~F" set "PKG=%%~F"
if "%PKG%"=="" (
    echo 没有找到 VB-CABLE 安装包，无法自动卸载。
    echo 可以到“设备管理器 - 声音、视频和游戏控制器”里手动卸载 VB-Audio Virtual Cable。
    pause
    exit /b 1
)
net session >nul 2>&1
if errorlevel 1 (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
"%PKG%" -u -h
echo 已执行卸载，请重启电脑。你可以在“设置 - 应用”里删除 VB-CABLE 的条目。
pause
