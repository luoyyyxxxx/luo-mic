# =====================================================================
#  luo mic - PC side builder & launcher (Windows PowerShell)
#
#  由 start-luo-mic.bat 调用，也可以单独运行：
#      powershell -ExecutionPolicy Bypass -File pc-build.ps1
#      powershell -ExecutionPolicy Bypass -File pc-build.ps1 -SelfTest 8
#      powershell -ExecutionPolicy Bypass -File pc-build.ps1 -NoRun      # 只编译
# =====================================================================
[CmdletBinding()]
param(
    [switch]$NoRun,          # 编译后不自动启动界面
    [int]$SelfTest = 0,      # 编译后跑 N 秒自检（不需要手机、不需要声卡）
    [switch]$Force           # 忽略缓存，强制重新编译
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ── 强制启用 TLS 1.2 ────────────────────────────────────────────────
# Windows PowerShell 5.1 默认只启用 TLS 1.0，而现在的下载服务器
# （api.adoptium.net / dl.google.com / 各镜像站）都强制要求 TLS 1.2+，
# 不设置的话所有 Invoke-WebRequest 都会失败（报"基础连接已关闭"之类）。
try {
    [Net.ServicePointManager]::SecurityProtocol = `
        [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls11
} catch {
    try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }
}


function Say($msg, $color = 'Gray') { Write-Host $msg -ForegroundColor $color }
function Head($msg) { Write-Host ''; Write-Host "=== $msg ===" -ForegroundColor Cyan }
function Fail($msg) {
    Write-Host ''
    Write-Host "[失败] $msg" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- 路径

$ToolsDir = Split-Path -Parent $MyInvocation.MyCommand.Path      # windows\tools
$WinDir   = Split-Path -Parent $ToolsDir                          # windows
$RootDir  = Split-Path -Parent $WinDir                            # 仓库根
$SrcDir   = Join-Path $WinDir 'java\src'
$TestDir  = Join-Path $RootDir 'tools\java'
$BuildDir = Join-Path $WinDir 'build'
$ClassDir = Join-Path $BuildDir 'classes'
$TestClassDir = Join-Path $BuildDir 'test-classes'
$JarPath  = Join-Path $BuildDir 'luo-mic.jar'
$JdkCache = Join-Path $env:LOCALAPPDATA 'luo-mic\jdk'
$JdkUrl   = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse'
$JdkZip   = Join-Path $JdkCache 'jdk17.zip'
$JdkHome  = $null

Say '====================================================' Cyan
Say '  luo mic  -  电脑端一键编译 / 启动' Cyan
Say '====================================================' Cyan

if (-not (Test-Path $SrcDir)) { Fail "找不到源码目录：$SrcDir`n请确认 luo-mic 文件夹结构没有被移动。" }

# ---------------------------------------------------------------- 1. 找 Java

function Test-Jdk([string]$candidate) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { return $false }
    $javac = Join-Path $candidate 'bin\javac.exe'
    if (-not (Test-Path $javac)) { return $false }
    $jar = Join-Path $candidate 'bin\jar.exe'
    return (Test-Path $jar)
}

function Get-JdkMajor([string]$home) {
    $javac = Join-Path $home 'bin\javac.exe'
    try {
        $out = & $javac -version 2>&1 | Out-String
        if ($out -match 'javac\s+(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}

Head '1/3  检查 Java'

# 1) JAVA_HOME
if (Test-Jdk $env:JAVA_HOME) { $JdkHome = $env:JAVA_HOME }
# 2) PATH 里的 javac
if (-not $JdkHome) {
    $cmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($cmd) {
        $home = Split-Path -Parent (Split-Path -Parent $cmd.Source)
        if (Test-Jdk $home) { $JdkHome = $home }
    }
}
# 3) 常见安装位置
if (-not $JdkHome) {
    $roots = @(
        "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Microsoft\jdk", "$env:ProgramFiles\Amazon Corretto",
        "$env:ProgramFiles\Zulu", "$env:ProgramFiles\BellSoft",
        "${env:ProgramFiles(x86)}\Java", "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
    )
    foreach ($r in $roots) {
        if (-not (Test-Path $r)) { continue }
        $found = Get-ChildItem -Path $r -Directory -ErrorAction SilentlyContinue |
                 Where-Object { Test-Jdk $_.FullName } |
                 Sort-Object Name -Descending | Select-Object -First 1
        if ($found) { $JdkHome = $found.FullName; break }
    }
}
# 4) 之前自动下载的 JDK
if (-not $JdkHome -and (Test-Path $JdkCache)) {
    $found = Get-ChildItem -Path $JdkCache -Directory -ErrorAction SilentlyContinue |
             Where-Object { Test-Jdk $_.FullName } | Select-Object -First 1
    if ($found) { $JdkHome = $found.FullName }
}

$major = if ($JdkHome) { Get-JdkMajor $JdkHome } else { 0 }
if ($JdkHome -and $major -ge 17) {
    Say "  找到 Java $major ：$JdkHome" Green
} else {
    if ($JdkHome) {
        Say "  找到的 Java 版本是 $major，低于 17，需要重新下载一个（不会动你现有的 Java）。" Yellow
    } else {
        Say '  这台电脑没找到 JDK，将自动下载一个便携版 JDK 17（约 180MB，只下一次）。' Yellow
    }
    Say '  正在下载 OpenJDK 17 ...' Cyan
    if (-not (Test-Path $JdkCache)) { New-Item -ItemType Directory -Path $JdkCache -Force | Out-Null }
    try {
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $JdkUrl -OutFile $JdkZip -UseBasicParsing
    } catch {
        Fail "JDK 下载失败（网络问题）。`n手动方案：到 https://adoptium.net/ 下载 JDK 17 安装后重新双击本脚本。`n错误信息：$($_.Exception.Message)"
    }
    Say '  正在解压 ...' Cyan
    $tmp = Join-Path $JdkCache 'tmp'
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path $JdkZip -DestinationPath $tmp -Force
    $inner = Get-ChildItem -Path $tmp -Directory | Select-Object -First 1
    if (-not $inner) { Fail 'JDK 解压结果异常。' }
    $target = Join-Path $JdkCache $inner.Name
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Move-Item $inner.FullName $target
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    Remove-Item -Force $JdkZip -ErrorAction SilentlyContinue
    $JdkHome = $target
    $major = Get-JdkMajor $JdkHome
    Say "  JDK 已就绪：$JdkHome（Java $major）" Green
}

$Javac = Join-Path $JdkHome 'bin\javac.exe'
$Jar   = Join-Path $JdkHome 'bin\jar.exe'
$JavaW = Join-Path $JdkHome 'bin\javaw.exe'
$Java  = Join-Path $JdkHome 'bin\java.exe'

# ---------------------------------------------------------------- 2. 编译

Head '2/3  编译'

$sources = Get-ChildItem -Path $SrcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if (-not $sources) { Fail "在 $SrcDir 下没有找到 .java 文件。" }
Say "  共 $($sources.Count) 个源文件"

if ($Force -and (Test-Path $BuildDir)) { Remove-Item -Recurse -Force $BuildDir }
if (-not (Test-Path $ClassDir)) { New-Item -ItemType Directory -Path $ClassDir -Force | Out-Null }

$listFile = Join-Path $BuildDir 'sources.txt'
$sources | Set-Content -Path $listFile -Encoding UTF8

$javacOut = & $Javac -encoding UTF-8 -nowarn -d $ClassDir "@$listFile" 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Host $javacOut -ForegroundColor Red
    Fail "编译失败。请把上面的红色信息发给我。`n（如果你的 Java 版本低于 17，先删掉 $JdkCache 再重新运行本脚本。）"
}
Say '  编译通过' Green

& $Jar --create --file $JarPath --main-class com.luomic.pc.Main -C $ClassDir . | Out-Null
if ($LASTEXITCODE -ne 0) { Fail '打包 jar 失败。' }
$size = [math]::Round((Get-Item $JarPath).Length / 1KB, 0)
Say "  已生成：$JarPath（$size KB）" Green

# 可选：编译自检工具
$haveTests = $false
if (Test-Path $TestDir) {
    if (-not (Test-Path $TestClassDir)) { New-Item -ItemType Directory -Path $TestClassDir -Force | Out-Null }
    $testSources = Get-ChildItem -Path $TestDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    if ($testSources) {
        $tlist = Join-Path $BuildDir 'test-sources.txt'
        $testSources | Set-Content -Path $tlist -Encoding UTF8
        & $Javac -encoding UTF-8 -nowarn -cp $ClassDir -d $TestClassDir "@$tlist" 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { $haveTests = $true }
    }
}

# ---------------------------------------------------------------- 3. 启动

Head '3/3  启动'

if ($SelfTest -gt 0) {
    if (-not $haveTests) { Fail '自检工具没有编译成功。' }
    Say "  运行 $SelfTest 秒自检（不接手机、不需要声卡）..." Cyan
    $cp = "$ClassDir;$TestClassDir"
    & $Java -cp $cp com.luomic.test.SelfTest $SelfTest
    exit $LASTEXITCODE
}

if ($NoRun) {
    Say "  已按要求只编译。启动命令：" Cyan
    Say "     `"$JavaW`" -Dfile.encoding=UTF-8 -jar `"$JarPath`""
    exit 0
}

Say '  正在打开 luo mic 窗口 ...' Green
Start-Process -FilePath $JavaW -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', $JarPath) -WorkingDirectory $WinDir
Start-Sleep -Milliseconds 800
Say ''
Say '  下一步：' White
Say '    1. 在弹出的窗口里点右下角「放行防火墙」，UAC 弹窗选「是」'
Say '    2. 「播放到」选择你的耳机 / 音箱（想让微信等软件把手机当麦克风时，选 CABLE Input）'
Say '    3. 点「启动服务」，状态变成“等待手机连接…”'
Say '    4. 手机打开 luo mic，点「开始」'
Say ''
Say '  自检命令（排查问题用）：' DarkGray
Say "    powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`" -SelfTest 8" DarkGray
exit 0
