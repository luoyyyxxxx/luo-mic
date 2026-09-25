@echo off
rem  luo mic - uninstall the VB-CABLE virtual audio driver
rem  ASCII-only on purpose: see the note in firewall.bat
chcp 65001 >nul
title luo mic - uninstall VB-CABLE
set "PKG="
for %%F in ("%~dp0VBCABLE_Setup*.exe" "%~dp0vbcable*.exe" "%~dp0VBCABLE*.exe") do if exist "%%~F" set "PKG=%%~F"
if "%PKG%"=="" (
    echo [luo mic] VB-CABLE installer not found, cannot uninstall automatically.
    echo Remove "VB-Audio Virtual Cable" manually from
    echo Device Manager - Sound, video and game controllers.
    pause
    exit /b 1
)
net session >nul 2>&1
if errorlevel 1 (
    echo [luo mic] Administrator rights required, elevating...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
"%PKG%" -u -h
echo Uninstalled. Please REBOOT. You can also remove the VB-CABLE entry
echo from Settings - Apps.
pause
