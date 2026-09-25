@echo off
rem  luo mic - install the VB-CABLE virtual audio driver
rem  ASCII-only on purpose: see the note in firewall.bat
chcp 65001 >nul
title luo mic - install VB-CABLE
setlocal
set "PKG="
for %%F in ("%~dp0VBCABLE_Setup*.exe" "%~dp0vbcable*.exe" "%~dp0VBCABLE*.exe") do if exist "%%~F" set "PKG=%%~F"
if "%PKG%"=="" (
    echo [luo mic] VB-CABLE installer not found in:
    echo     %~dp0
    echo.
    echo Download it from https://vb-audio.com/Cable/ , unzip, and put
    echo VBCABLE_Setup_x64.exe into that folder, then run this again.
    echo.
    pause
    exit /b 1
)
echo Found installer: %PKG%
net session >nul 2>&1
if errorlevel 1 (
    echo [luo mic] Administrator rights required, elevating...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
echo Installing VB-CABLE silently...
"%PKG%" -i -h
echo.
echo Done. REBOOT Windows, then set "Play to" to CABLE Input in luo mic.
pause
