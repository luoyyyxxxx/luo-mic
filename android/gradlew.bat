@echo off
rem  luo mic - Android build script (Windows)
rem
rem  Usage:
rem      gradlew.bat assembleDebug     build the debug APK
rem      gradlew.bat installDebug      build and install onto a connected phone
rem      gradlew.bat assembleRelease   build the release APK
rem
rem  This repo does not ship a binary gradle-wrapper.jar. On first run the
rem  official Gradle 8.7 distribution is downloaded and cached to
rem  %USERPROFILE%\.gradle\luomic-gradle\ , then reused offline.
rem
rem  ASCII-only on purpose: cmd.exe reads .bat files using the OEM/ANSI code
rem  page, and non-ASCII text at the end of a line can swallow the newline and
rem  corrupt the parsed script.
rem =====================================================================

setlocal enabledelayedexpansion
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.\
set APP_HOME=%DIRNAME%
set GRADLE_VER=8.7
set CACHE_ROOT=%USERPROFILE%\.gradle\luomic-gradle
set GRADLE_HOME=%CACHE_ROOT%\gradle-%GRADLE_VER%
set GRADLE_BIN=%GRADLE_HOME%\bin\gradle.bat
set JAVA_EXE=java

if not exist "%GRADLE_BIN%" (
    echo [luo mic] First run: preparing Gradle %GRADLE_VER% (one time only, about 130MB)
    where java >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] java not found. Install JDK 17 or newer and add it to PATH.
        echo         https://adoptium.net/
        exit /b 1
    )
    if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
      "$ErrorActionPreference='Stop';" ^
      "$ver='%GRADLE_VER%'; $cache='%CACHE_ROOT%';" ^
      "$zip=Join-Path $cache ('gradle-' + $ver + '-bin.zip');" ^
      "if (-not (Test-Path $zip)) {" ^
      "  Write-Host ('  Downloading gradle-' + $ver + '-bin.zip ...');" ^
      "  $urls=@('https://mirrors.cloud.tencent.com/gradle/gradle-' + $ver + '-bin.zip'," ^
      "          'https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-' + $ver + '-bin.zip'," ^
      "          'https://services.gradle.org/distributions/gradle-' + $ver + '-bin.zip');" ^
      "  $ok=$false;" ^
      "  foreach ($u in $urls) { try { Invoke-WebRequest -Uri $u -OutFile $zip -UseBasicParsing; $ok=$true; break } catch { Write-Host ('  mirror failed, trying next: ' + $u) } }" ^
      "  if (-not $ok) { throw 'all download mirrors failed, check your network' }" ^
      "}" ^
      "Write-Host '  Extracting ...';" ^
      "$tmp=Join-Path $cache 'tmp'; if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp };" ^
      "Expand-Archive -Path $zip -DestinationPath $tmp -Force;" ^
      "$src=Join-Path $tmp ('gradle-' + $ver);" ^
      "if (-not (Test-Path $src)) { throw 'unexpected archive layout' };" ^
      "if (Test-Path '%GRADLE_HOME%') { Remove-Item -Recurse -Force '%GRADLE_HOME%' };" ^
      "Move-Item $src '%GRADLE_HOME%';" ^
      "Remove-Item -Recurse -Force $tmp;" ^
      "Write-Host '  Gradle is ready.'"
    if errorlevel 1 (
        echo [ERROR] Failed to prepare Gradle.
        echo         Manual fix: install Gradle 8.7 yourself, then run  gradle assembleDebug
        exit /b 1
    )
)

call "%GRADLE_BIN%" -p "%APP_HOME%" %*
set RC=%ERRORLEVEL%
endlocal & exit /b %RC%
