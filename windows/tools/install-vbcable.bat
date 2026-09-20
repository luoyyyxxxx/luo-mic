@echo off
chcp 65001 >nul
title luo mic - 安装 VB-CABLE 虚拟声卡
setlocal
set "PKG="
for %%F in ("%~dp0VBCABLE_Setup*.exe" "%~dp0vbcable*.exe" "%~dp0VBCABLE*.exe") do if exist "%%~F" set "PKG=%%~F"
if "%PKG%"=="" (
    echo [提示] 在 %~dp0 下没有找到 VB-CABLE 安装包。
    echo.
    echo 请到官网下载后放到本目录（文件名以 VBCABLE 开头）：
    echo     https://vb-audio.com/Cable/
    echo.
    echo 下载的是压缩包，解压后把 VBCABLE_Setup_x64.exe 放进 %~dp0 再运行本脚本。
    echo.
    pause
    exit /b 1
)
echo 找到安装包：%PKG%
net session >nul 2>&1
if errorlevel 1 (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
echo 正在静默安装 VB-CABLE...
"%PKG%" -i -h
echo.
echo 安装完成，请【重启电脑】，然后在 luo mic 里把“播放到”选择 CABLE Input。
pause
