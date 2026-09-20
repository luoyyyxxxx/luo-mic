@echo off
chcp 65001 >nul
title luo mic
cd /d "%~dp0.."
if exist build\luo-mic.jar (
    start "" javaw -Dfile.encoding=UTF-8 -jar build\luo-mic.jar
) else (
    echo 还没有编译，正在调用 build-windows.bat ...
    call tools\build-windows.bat
)
