@echo off
chcp 65001 >nul
title luo mic - 编译电脑端
setlocal enabledelayedexpansion
cd /d "%~dp0.."

where javac >nul 2>&1
if errorlevel 1 (
    echo [错误] 没有找到 javac。请先安装 JDK 17 或更高版本，并把 bin 目录加入 PATH。
    echo         下载地址：https://adoptium.net/
    pause
    exit /b 1
)

if exist build rmdir /s /q build
mkdir build\classes

echo 正在编译...
dir /s /b java\src\*.java > build\sources.txt
javac -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 (
    echo [错误] 编译失败。
    pause
    exit /b 1
)

echo 正在打包 luo-mic.jar...
jar --create --file build\luo-mic.jar --main-class com.luomic.pc.Main -C build\classes .
if errorlevel 1 (
    echo [错误] 打包失败。
    pause
    exit /b 1
)

rem 顺带编译假手机模拟器（可选，用于没手机时自检）
if exist "..\tools\java" (
    mkdir build\test-classes 2>nul
    dir /s /b "..\tools\java\*.java" > build\test-sources.txt
    javac -encoding UTF-8 -cp build\classes -d build\test-classes @build\test-sources.txt 2>nul
)

echo.
echo ============================================
echo  编译完成：%~dp0..\build\luo-mic.jar
echo.
echo  运行：java -jar build\luo-mic.jar
echo  自检：java -cp "build\classes;build\test-classes" com.luomic.test.SelfTest 8
echo ============================================
pause
