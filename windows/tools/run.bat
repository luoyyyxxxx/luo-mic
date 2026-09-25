@echo off
rem  luo mic - build (if needed) and run the PC side
rem  ASCII-only on purpose: see the note in firewall.bat
chcp 65001 >nul
title luo mic
cd /d "%~dp0.."
if exist build\luo-mic.jar (
    start "" javaw -Dfile.encoding=UTF-8 -jar build\luo-mic.jar
) else (
    echo Not built yet, calling build-windows.bat ...
    call tools\build-windows.bat
)
