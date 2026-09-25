@echo off
rem ===================================================================
rem  luo mic - build the Windows (PC) side with plain JDK command line
rem  ASCII-only on purpose: see the note in firewall.bat
rem ===================================================================
chcp 65001 >nul
title luo mic - build PC side
setlocal enabledelayedexpansion
cd /d "%~dp0.."

where javac >nul 2>&1
if errorlevel 1 (
    echo [ERROR] javac not found. Install JDK 17 or newer and add its bin to PATH.
    echo         https://adoptium.net/
    pause
    exit /b 1
)

if exist build rmdir /s /q build
mkdir build\classes

echo Compiling...
dir /s /b java\src\*.java > build\sources.txt
javac -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 (
    echo [ERROR] compile failed.
    pause
    exit /b 1
)

echo Packaging luo-mic.jar...
jar --create --file build\luo-mic.jar --main-class com.luomic.pc.Main -C build\classes .
if errorlevel 1 (
    echo [ERROR] jar failed.
    pause
    exit /b 1
)

rem Also build the fake-phone simulator (optional, used by the self test)
if exist "..\tools\java" (
    mkdir build\test-classes 2>nul
    dir /s /b "..\tools\java\*.java" > build\test-sources.txt
    javac -encoding UTF-8 -cp build\classes -d build\test-classes @build\test-sources.txt 2>nul
)

echo.
echo ============================================
echo  Built: %~dp0..\build\luo-mic.jar
echo.
echo  Run  : java -jar build\luo-mic.jar
echo  Check: java -cp "build\classes;build\test-classes" com.luomic.test.SelfTest 8
echo ============================================
pause
