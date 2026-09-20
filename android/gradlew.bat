@echo off
@rem luo mic — 安卓端构建脚本（Windows）
@rem
@rem 用法：
@rem     gradlew.bat assembleDebug     编译 debug APK
@rem     gradlew.bat installDebug      编译并安装到已连接的手机
@rem     gradlew.bat assembleRelease   编译 release APK
@rem
@rem 说明：仓库不附带二进制文件 gradle-wrapper.jar，本脚本会在首次运行时
@rem       自动下载官方的 Gradle 8.7 发行包并缓存到
@rem       %USERPROFILE%\.gradle\luomic-gradle\，之后直接复用，无需联网。

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
    echo [luo mic] 首次运行：准备 Gradle %GRADLE_VER% ^(只需一次，约 130MB^)
    where java >nul 2>&1
    if errorlevel 1 (
        echo [错误] 找不到 java。请安装 JDK 17 或更高版本并加入 PATH。
        echo        下载地址：https://adoptium.net/
        exit /b 1
    )
    if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
      "$ErrorActionPreference='Stop';" ^
      "$ver='%GRADLE_VER%'; $cache='%CACHE_ROOT%';" ^
      "$zip=Join-Path $cache ('gradle-' + $ver + '-bin.zip');" ^
      "if (-not (Test-Path $zip)) {" ^
      "  Write-Host ('  正在下载 gradle-' + $ver + '-bin.zip ...');" ^
      "  $urls=@('https://mirrors.cloud.tencent.com/gradle/gradle-' + $ver + '-bin.zip'," ^
      "          'https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-' + $ver + '-bin.zip'," ^
      "          'https://services.gradle.org/distributions/gradle-' + $ver + '-bin.zip');" ^
      "  $ok=$false;" ^
      "  foreach ($u in $urls) { try { Invoke-WebRequest -Uri $u -OutFile $zip -UseBasicParsing; $ok=$true; break } catch { Write-Host ('  镜像失败，换下一个: ' + $u) } }" ^
      "  if (-not $ok) { throw '所有下载地址都失败，请检查网络' }" ^
      "}" ^
      "Write-Host '  正在解压 ...';" ^
      "$tmp=Join-Path $cache 'tmp'; if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp };" ^
      "Expand-Archive -Path $zip -DestinationPath $tmp -Force;" ^
      "$src=Join-Path $tmp ('gradle-' + $ver);" ^
      "if (-not (Test-Path $src)) { throw '解压结果不符合预期' };" ^
      "if (Test-Path '%GRADLE_HOME%') { Remove-Item -Recurse -Force '%GRADLE_HOME%' };" ^
      "Move-Item $src '%GRADLE_HOME%';" ^
      "Remove-Item -Recurse -Force $tmp;" ^
      "Write-Host '  Gradle 已就绪。'"
    if errorlevel 1 (
        echo [错误] 准备 Gradle 失败。
        echo        手动方案：自行安装 Gradle 8.7 后执行  gradle assembleDebug
        exit /b 1
    )
)

call "%GRADLE_BIN%" -p "%APP_HOME%" %*
set RC=%ERRORLEVEL%
endlocal & exit /b %RC%
