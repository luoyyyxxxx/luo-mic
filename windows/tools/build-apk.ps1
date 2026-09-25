# =====================================================================
#  luo mic - 安卓端一键打包 APK（Windows PowerShell）
#
#  由 build-apk.bat 调用，也可以单独运行：
#      powershell -ExecutionPolicy Bypass -File build-apk.ps1
#      powershell -ExecutionPolicy Bypass -File build-apk.ps1 -Clean     # 先清理再编译
#      powershell -ExecutionPolicy Bypass -File build-apk.ps1 -SdkDir D:\Android\Sdk
#
#  第一次运行会下载：便携 JDK 17（如果需要）+ Android 命令行工具 + SDK 组件
#  之后每次编译只要 1 分钟左右。
# =====================================================================
[CmdletBinding()]
param(
    [string]$SdkDir,        # 指定已装好的 Android SDK 目录
    [switch]$Clean,         # 编译前先 clean
    [switch]$NoDesktopCopy  # 不复制 APK 到桌面
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Say($msg, $color = 'Gray') { Write-Host $msg -ForegroundColor $color }
function Head($msg) { Write-Host ''; Write-Host "=== $msg ===" -ForegroundColor Cyan }
function Fail($msg) {
    Write-Host ''
    Write-Host "[失败] $msg" -ForegroundColor Red
    exit 1
}
function Step($msg) { Write-Host "  -> $msg" -ForegroundColor Cyan }

# ---------------------------------------------------------------- 路径

$ToolsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$WinDir   = Split-Path -Parent $ToolsDir
$RootDir  = Split-Path -Parent $WinDir
$AndDir   = Join-Path $RootDir 'android'
$DataDir  = Join-Path $env:LOCALAPPDATA 'luo-mic'          # 所有下载都放这里
$JdkCache = Join-Path $DataDir 'jdk'
$SdkCache = Join-Path $DataDir 'android-sdk'
$CmdlineZip = Join-Path $DataDir 'cmdline-tools.zip'
$GradleCache = Join-Path $env:USERPROFILE '.gradle\luomic-gradle'

$JdkUrl  = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse'
$CmdUrl  = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
$GradleVer = '8.7'
# 官方源在国内经常只有几十 KB/s；腾讯/阿里镜像通常满速，所以镜像优先
$GradleUrls = @(
    "https://mirrors.cloud.tencent.com/gradle/gradle-$GradleVer-bin.zip",
    "https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-$GradleVer-bin.zip",
    "https://services.gradle.org/distributions/gradle-$GradleVer-bin.zip"
)

Say '====================================================' Cyan
Say '  luo mic  -  安卓端一键打包 APK' Cyan
Say '====================================================' Cyan

if (-not (Test-Path $AndDir)) { Fail "找不到安卓工程目录：$AndDir" }

# 路径里如果有中文/空格，Android 的 aapt2 可能报错，提前提醒
if ($RootDir -match '[^\x00-\x7F]') {
    Say ''
    Say "  [提醒] 你的项目路径包含中文或特殊字符：" Yellow
    Say "         $RootDir" Yellow
    Say '         安卓编译工具对中文路径比较敏感，如果编译失败，' Yellow
    Say '         请把整个 luo-mic 文件夹移动到 C:\luo-mic 之类的纯英文路径再试。' Yellow
}

# ---------------------------------------------------------------- 通用工具

function Get-Text([string]$url, [string]$out) {
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing
}

function Find-Jdk {
    if (Test-Jdk $env:JAVA_HOME) { return $env:JAVA_HOME }
    $cmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($cmd) {
        # NOTE: do not use $home here - $HOME is a read-only automatic variable
        # in Windows PowerShell 5.1 and assigning to it aborts the script.
        $jdkFromPath = Split-Path -Parent (Split-Path -Parent $cmd.Source)
        if ((Test-Jdk $jdkFromPath) -and (Get-JdkMajor $jdkFromPath) -ge 17) { return $jdkFromPath }
    }
    $roots = @(
        "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Microsoft\jdk", "$env:ProgramFiles\Amazon Corretto",
        "$env:ProgramFiles\Zulu", "$env:ProgramFiles\BellSoft",
        "${env:ProgramFiles(x86)}\Java", "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
    )
    foreach ($r in $roots) {
        if (-not (Test-Path $r)) { continue }
        $found = Get-ChildItem -Path $r -Directory -ErrorAction SilentlyContinue |
                 Where-Object { (Test-Jdk $_.FullName) -and (Get-JdkMajor $_.FullName) -ge 17 } |
                 Sort-Object Name -Descending | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    if (Test-Path $JdkCache) {
        $found = Get-ChildItem -Path $JdkCache -Directory -ErrorAction SilentlyContinue |
                 Where-Object { (Test-Jdk $_.FullName) -and (Get-JdkMajor $_.FullName) -ge 17 } |
                 Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

function Test-Jdk([string]$candidate) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { return $false }
    return (Test-Path (Join-Path $candidate 'bin\javac.exe'))
}

function Get-JdkMajor([string]$jdkDir) {
    try {
        $out = & (Join-Path $jdkDir 'bin\javac.exe') -version 2>&1 | Out-String
        if ($out -match 'javac\s+(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}

function Download-Jdk17 {
    Step '下载便携版 OpenJDK 17（约 180MB，只下一次）'
    if (-not (Test-Path $JdkCache)) { New-Item -ItemType Directory -Path $JdkCache -Force | Out-Null }
    $zip = Join-Path $JdkCache 'jdk17.zip'
    try { Get-Text $JdkUrl $zip }
    catch { Fail "JDK 下载失败：$($_.Exception.Message)`n可手动安装 JDK 17（https://adoptium.net/）后重试。" }
    Step '解压 JDK'
    $tmp = Join-Path $JdkCache 'tmp'
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $inner = Get-ChildItem -Path $tmp -Directory | Select-Object -First 1
    if (-not $inner) { Fail 'JDK 解压结果异常。' }
    $target = Join-Path $JdkCache $inner.Name
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Move-Item $inner.FullName $target
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    Remove-Item -Force $zip -ErrorAction SilentlyContinue
    return $target
}

# ---------------------------------------------------------------- 1. Java

Head '1/5  检查 Java（Gradle 8.7 需要 JDK 17 及以上）'
$JdkHome = Find-Jdk
if (-not $JdkHome) {
    Say '  没有找到 JDK 17+，将自动下载一个便携版（不会影响你已装的 Java）' Yellow
    $JdkHome = Download-Jdk17
}
$env:JAVA_HOME = $JdkHome
$env:PATH = (Join-Path $JdkHome 'bin') + ';' + $env:PATH
Say "  Java: $JdkHome（$(Get-JdkMajor $JdkHome)）" Green

# ---------------------------------------------------------------- 2. Android SDK

Head '2/5  检查 Android SDK'

function Find-Sdk {
    $cands = @()
    if ($SdkDir) { $cands += $SdkDir }
    if ($env:ANDROID_HOME) { $cands += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $cands += $env:ANDROID_SDK_ROOT }
    $cands += (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    $cands += $SdkCache
    foreach ($c in $cands) {
        if (-not $c) { continue }
        if (Test-Path (Join-Path $c 'platforms')) { return $c }
    }
    return $null
}

function Install-Sdk {
    param([string]$Target)
    Step "安装 Android SDK 到 $Target"
    if (-not (Test-Path $DataDir)) { New-Item -ItemType Directory -Path $DataDir -Force | Out-Null }

    $sdkManager = Join-Path $Target 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (-not (Test-Path $sdkManager)) {
        Step '下载 Android 命令行工具（约 130MB）'
        try { Get-Text $CmdUrl $CmdlineZip }
        catch { Fail "命令行工具下载失败：$($_.Exception.Message)`n检查网络后重试。" }

        $tmp = Join-Path $DataDir 'cmdline-tmp'
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
        Expand-Archive -Path $CmdlineZip -DestinationPath $tmp -Force
        Remove-Item -Force $CmdlineZip -ErrorAction SilentlyContinue

        $latest = Join-Path $Target 'cmdline-tools\latest'
        if (Test-Path $latest) { Remove-Item -Recurse -Force $latest }
        New-Item -ItemType Directory -Path (Split-Path -Parent $latest) -Force | Out-Null
        $inner = Join-Path $tmp 'cmdline-tools'
        if (-not (Test-Path $inner)) { Fail '命令行工具解压结果异常。' }
        Move-Item $inner $latest
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }

    $sdkManager = Join-Path $Target 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (-not (Test-Path $sdkManager)) { Fail '没有找到 sdkmanager.bat。' }

    Step '接受 Android SDK 许可协议'
    $yes = ("y`r`n" * 30)
    $yes | & cmd /c "`"$sdkManager`" --sdk_root=`"$Target`" --licenses" 2>&1 | Out-Null

    Step '下载 SDK 组件（platform-tools / build-tools 34 / platform 34，约 250MB）'
    $pkgs = @('platform-tools', 'build-tools;34.0.0', 'platforms;android-34')
    & cmd /c "`"$sdkManager`" --sdk_root=`"$Target`" $($pkgs -join ' ')" 2>&1 |
        ForEach-Object { if ($_ -match '(\d+)%') { Write-Host "`r    进度 $($Matches[1])%" -NoNewline -ForegroundColor DarkGray } }
    Write-Host ''
    if (-not (Test-Path (Join-Path $Target 'platforms\android-34'))) {
        Fail "SDK 组件安装不完整。可以手动执行：`n  `"$sdkManager`" --sdk_root=`"$Target`" `"platforms;android-34`" `"build-tools;34.0.0`" `"platform-tools`""
    }
}

$Sdk = Find-Sdk
if (-not $Sdk) {
    Say '  没有找到 Android SDK，将自动下载安装（约 400MB，只做一次）' Yellow
    Install-Sdk -Target $SdkCache
    $Sdk = $SdkCache
} else {
    Say "  使用已有 SDK: $Sdk" Green
    # 补齐可能缺少的组件
    $need = @()
    if (-not (Test-Path (Join-Path $Sdk 'platforms\android-34'))) { $need += 'platforms;android-34' }
    if (-not (Test-Path (Join-Path $Sdk 'build-tools\34.0.0'))) { $need += 'build-tools;34.0.0' }
    if ($need.Count -gt 0) { Install-Sdk -Target $Sdk }
}
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
Say "  SDK: $Sdk" Green

# 写 local.properties，告诉 Gradle SDK 在哪（路径里的反斜杠要转义）
$localProps = Join-Path $AndDir 'local.properties'
$sdkForProps = $Sdk -replace '\\', '\\'
"# 由 build-apk.ps1 自动生成，请勿提交到 git`nsdk.dir=$sdkForProps`n" |
    Set-Content -Path $localProps -Encoding ASCII
Say "  已写入 $localProps"

# ---------------------------------------------------------------- 3. Gradle

Head '3/5  准备 Gradle'
$GradleBin = Join-Path $GradleCache "gradle-$GradleVer\bin\gradle.bat"
if (-not (Test-Path $GradleBin)) {
    Step "下载 Gradle $GradleVer（约 130MB，只下一次）"
    if (-not (Test-Path $GradleCache)) { New-Item -ItemType Directory -Path $GradleCache -Force | Out-Null }
    $gzip = Join-Path $GradleCache "gradle-$GradleVer-bin.zip"
    $ok = $false
    foreach ($u in $GradleUrls) {
        try {
            Step "  从 $u 下载"
            Get-Text $u $gzip
            $ok = $true
            break
        } catch {
            Say "  该地址失败，换下一个 ..." Yellow
        }
    }
    if (-not $ok) { Fail 'Gradle 下载失败，请检查网络（或挂代理）后重试。' }

    Step '解压 Gradle'
    $tmp = Join-Path $GradleCache 'tmp'
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path $gzip -DestinationPath $tmp -Force
    $inner = Join-Path $tmp "gradle-$GradleVer"
    if (-not (Test-Path $inner)) { Fail 'Gradle 解压结果异常。' }
    $target = Join-Path $GradleCache "gradle-$GradleVer"
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Move-Item $inner $target
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    Remove-Item -Force $gzip -ErrorAction SilentlyContinue
}
Say "  Gradle: $GradleBin" Green

# ---------------------------------------------------------------- 4. 编译

Head '4/5  编译 APK（第一次会比较久，要下载依赖）'
Push-Location $AndDir
try {
    if ($Clean) {
        Step 'clean'
        & cmd /c "`"$GradleBin`" -p `"$AndDir`" clean --console=plain" 2>&1 | Out-Null
    }
    $gradleArgs = @('assembleDebug', '--console=plain', '--no-daemon')
    & cmd /c "`"$GradleBin`" -p `"$AndDir`" $($gradleArgs -join ' ')" 2>&1 |
        ForEach-Object {
            if ($_ -match '^\s*> Task') { Write-Host "  $_" -ForegroundColor DarkGray }
            elseif ($_ -match 'BUILD SUCCESSFUL') { Write-Host "  $_" -ForegroundColor Green }
            elseif ($_ -match 'FAILURE|error:|ERROR') { Write-Host "  $_" -ForegroundColor Red }
        }
    $rc = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($rc -ne 0) {
    Fail @"
编译失败。常见原因与处理：
  1) 路径含中文 —— 把 luo-mic 文件夹移到 C:\luo-mic 再试；
  2) 网络问题导致依赖下载失败 —— 重跑本脚本，或在 android\settings.gradle.kts 里
     打开阿里云镜像的两行注释（maven("https://maven.aliyun.com/repository/...")）；
  3) JDK 版本过低 —— 删掉 $JdkCache 后重跑。
详细日志：android\app\build\ 下，或加 -Clean 参数重跑看完整输出。
"@
}
Say '  编译成功' Green

# ---------------------------------------------------------------- 5. 交付 APK

Head '5/5  取出 APK'
$apk = Get-ChildItem -Path (Join-Path $AndDir 'app\build\outputs\apk') -Recurse -Filter *.apk -ErrorAction SilentlyContinue |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { Fail "编译成功但没找到 APK，请检查 $AndDir\app\build\outputs\apk" }

$sizeMb = [math]::Round($apk.Length / 1MB, 2)
Say "  APK: $($apk.FullName)（$sizeMb MB）" Green

if (-not $NoDesktopCopy) {
    $desktop = [Environment]::GetFolderPath('Desktop')
    if ($desktop -and (Test-Path $desktop)) {
        $dest = Join-Path $desktop 'luo-mic.apk'
        Copy-Item $apk.FullName $dest -Force
        Say "  已复制到桌面：$dest" Green
    }
}

Say ''
Say '  接下来（手机上）：' White
Say '    1. 把这个 luo-mic.apk 传到手机（数据线 / 微信文件传输助手 / QQ 都行）'
Say '    2. 在手机上点开它安装；系统提示“未知来源应用”时选择“允许”'
Say '    3. 打开 luo mic，授予麦克风权限，点「开始」'
Say ''
Say '  手机连上电脑后如果没声音，电脑端窗口里选对「播放到」设备即可。' DarkGray
exit 0
