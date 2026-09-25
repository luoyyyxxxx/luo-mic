# ============================================================
#  luo mic —— 安卓构建诊断脚本
#  双击运行，它会把出错原因直接显示出来，并把完整日志存到桌面
# ============================================================
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Log  = Join-Path ([Environment]::GetFolderPath('Desktop')) 'luo-mic-构建日志.txt'

function Say($m, $c='Gray') { Write-Host $m -ForegroundColor $c }
$out = New-Object System.Collections.Generic.List[string]
function Rec($m) { $out.Add($m); Write-Host $m }

Say '========================================' Cyan
Say '  luo mic 安卓构建诊断' Cyan
Say '========================================' Cyan

# ---- 1. Java ----
Rec ''
Rec '【1】Java'
$javac = Get-Command javac.exe -ErrorAction SilentlyContinue
if ($javac) {
    $v = & javac.exe -version 2>&1 | Out-String
    Rec "  javac: $($javac.Source)"
    Rec "  版本 : $($v.Trim())"
    if ($v -match 'javac\s+(\d+)') {
        $maj = [int]$Matches[1]
        if ($maj -lt 17) { Rec "  ✘ 版本过低！Android 构建需要 JDK 17 及以上（当前 $maj）" }
        else { Rec "  ✔ 版本满足要求" }
    }
} else {
    Rec '  ✘ 没有找到 javac —— 说明只装了 JRE，没有装 JDK'
    Rec '     解决：到 https://adoptium.net/ 下载 JDK 17 安装（选 .msi，安装时勾选 Set JAVA_HOME）'
}
Rec "  JAVA_HOME = $env:JAVA_HOME"

# ---- 2. 项目位置 ----
Rec ''
Rec '【2】项目路径'
$proj = Join-Path $Root 'luo-mic'
if (-not (Test-Path $proj)) { $proj = $Root }
Rec "  项目目录: $proj"
if ($proj -match '[^\x00-\x7F]') {
    Rec '  ⚠ 路径含中文/特殊字符 —— Android 编译工具(aapt2)对中文路径敏感，'
    Rec '     请把 luo-mic 文件夹移动到 C:\luo-mic 这类纯英文路径再试'
} else { Rec '  ✔ 路径是纯英文，没问题' }

# ---- 3. Android SDK ----
Rec ''
Rec '【3】Android SDK'
$sdkCands = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")
$sdk = $null
foreach ($c in $sdkCands) { if ($c -and (Test-Path (Join-Path $c 'platforms'))) { $sdk = $c; break } }
if ($sdk) {
    Rec "  ✔ 找到 SDK: $sdk"
    Rec "  platform 34: $(if (Test-Path (Join-Path $sdk 'platforms\android-34')) { '有' } else { '✘ 缺失' })"
    Rec "  build-tools: $(if (Test-Path (Join-Path $sdk 'build-tools')) { (Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Select-Object -First 3 | ForEach-Object Name) -join ', ' } else { '✘ 缺失' })"
} else {
    Rec '  ✘ 没有找到 Android SDK'
    Rec '     解决：双击 windows\tools\build-apk.bat，它会自动下载安装 SDK'
}

# ---- 4. 真的编译一次，抓完整报错 ----
Rec ''
Rec '【4】实际编译（抓真实报错）'
$bat = Join-Path $proj 'android\gradlew.bat'
if (Test-Path $bat) {
    Rec "  执行: $bat assembleDebug"
    Push-Location (Join-Path $proj 'android')
    $buildOut = & cmd /c "`"$bat`" assembleDebug --console=plain --no-daemon 2>&1" | Out-String
    Pop-Location
    $out.Add('')
    $out.Add('===== 编译输出（完整）=====')
    $out.Add($buildOut)
    # 只把关键行显示在屏幕上
    Rec '  ---- 关键报错行 ----'
    $lines = $buildOut -split "`r?`n"
    $key = $lines | Where-Object { $_ -match 'FAILURE|error:|What went wrong|Caused by|BUILD|Unsupported|not found|✘' } | Select-Object -First 15
    if ($key) { $key | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow } }
    else { Rec '    （没抓到明显报错，请把桌面上的日志发给我）' }
    if ($buildOut -match 'BUILD SUCCESSFUL') { Rec '  ✔ 其实编译成功了！APK 在 android\app\build\outputs\apk\debug\' }
} else {
    Rec "  ✘ 找不到 $bat"
    Rec '     说明 android 文件夹不完整，请重新从 U 盘拷贝/重新 clone 仓库'
}

# ---- 5. 写出日志 ----
try {
    $out -join "`r`n" | Set-Content -Path $Log -Encoding UTF8
    Rec ''
    Rec "【5】完整日志已保存到：$Log"
    Rec '     把这个文件发给我，我就能准确判断问题'
} catch { Rec "  写日志失败: $_" }

Say ''
Say '按任意键关闭…'
$null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
