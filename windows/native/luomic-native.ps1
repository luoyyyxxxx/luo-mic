# =====================================================================
#  luo mic · Windows 原生电脑端（PowerShell 版，不需要 Java）
#
#  为什么有这个版本：
#    Java 版依赖 JavaSound，个别 Windows 声卡驱动下枚举不到设备或播不出声。
#    这个版本直接用 Windows 自己的音频 API（WASAPI），对 VB-CABLE 这类
#    虚拟声卡支持最可靠，而且不用装 Java。
#
#  用法：
#    双击 start-luo-mic-native.bat
#    或命令行：
#      powershell -ExecutionPolicy Bypass -File luomic-native.ps1
#      powershell -ExecutionPolicy Bypass -File luomic-native.ps1 -ListDevices
#      powershell -ExecutionPolicy Bypass -File luomic-native.ps1 -Device "CABLE"
#      powershell -ExecutionPolicy Bypass -File luomic-native.ps1 -NoAutoStart
#
#  文件说明：
#    luomic-protocol.ps1  网络协议（可在任何系统上测试）
#    luomic-audio.ps1     Windows 音频输出（WASAPI，仅 Windows 可编译）
#    本文件               入口：选设备 + 启动服务
# =====================================================================
[CmdletBinding()]
param(
    [string]$Device,          # 输出设备名（支持部分匹配，如 "CABLE"）；省略则自动优先选虚拟声卡
    [switch]$ListDevices,     # 只列出设备后退出
    [switch]$NoAutoStart,     # 手机连上后不自动开始推流
    [switch]$DebugMode        # 打印协议级调试信息
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


$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here 'luomic-protocol.ps1')
. (Join-Path $here 'luomic-audio.ps1')

Write-Host '==============================================' -ForegroundColor Cyan
Write-Host '  luo mic · Windows 原生版（不需要 Java）' -ForegroundColor Cyan
Write-Host '==============================================' -ForegroundColor Cyan

# ---------------- 音频组件 ----------------
$audioOk = Initialize-LuoMicAudio

# ---------------- 选设备 ----------------
$devices = @()
if ($audioOk) { $devices = Get-LuoMicRenderDevices }

function Show-Devices {
    Write-Host ''
    Write-Host '可用的播放设备：' -ForegroundColor Cyan
    $i = 0
    foreach ($d in $devices) {
        $mark = if ($d.Name -match 'cable|virtual|vb-audio|voicemeeter') { '   <-- 虚拟声卡（当麦克风用就选它）' } else { '' }
        Write-Host ("  [{0}] {1}{2}" -f $i, $d.Name, $mark)
        $i++
    }
    if ($i -eq 0) { Write-Host '  （没有枚举到设备，可能是音频组件没编译成功）' -ForegroundColor Yellow }
    Write-Host ''
}

if ($ListDevices) { Show-Devices; exit 0 }

$chosen = $null
if ($Device) {
    foreach ($d in $devices) {
        if ($d.Name -like "*$Device*") { $chosen = $d; break }
    }
    if (-not $chosen) { Write-Host "  未找到匹配 '$Device' 的设备，改用默认设备" -ForegroundColor Yellow }
}
if (-not $chosen) {
    # 没指定就优先虚拟声卡（这样就是"手机当麦克风"的效果）
    foreach ($d in $devices) {
        if ($d.Name -match 'cable input|virtual cable|vb-audio') { $chosen = $d; break }
    }
}

if ($chosen) {
    Write-Host ("  输出设备：{0}" -f $chosen.Name) -ForegroundColor Green
    if ($chosen.Name -match 'cable|virtual|vb-audio') {
        Write-Host '  （已选虚拟声卡 —— 请在微信/QQ/Discord 里把麦克风选成 CABLE Output）' -ForegroundColor Green
    }
} else {
    Write-Host '  输出设备：系统默认' -ForegroundColor Green
}
if (-not $audioOk) {
    Write-Host '  ⚠ 音频组件不可用，程序仍会接收手机音频但不会出声' -ForegroundColor Yellow
}

# ---------------- 播放器工厂 ----------------
$playerFactory = $null
if ($audioOk) {
    $playerFactory = {
        param($dev)
        $p = New-LuoMicPlayer
        if ($p -and $dev) { $p.Start($dev.Id, 48000, 1) }
        elseif ($p)       { $p.Start($null, 48000, 1) }
        return $p
    }.GetNewClosure()
}

Write-Host ''
Write-Host '  现在用手机打开 luo mic 并点「开始」...' -ForegroundColor White
Write-Host '  （Windows 防火墙若弹窗，请勾选"允许访问"）' -ForegroundColor DarkGray

# ---------------- 启动 ----------------
Start-LuoMicProtocol -PlayerFactory $playerFactory -DeviceInfo $chosen `
    -AutoStart:(-not $NoAutoStart) -DebugMode:$DebugMode

Write-Host '已退出'
