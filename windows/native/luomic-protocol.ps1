# =====================================================================
#  luo mic · 协议实现（与 Java 版、安卓端完全一致的局域网协议）
#
#  这个文件只负责网络，不碰任何 Windows 专有 API，
#  所以它可以在任何系统上被测试（见 tools/test-protocol.ps1）。
#  音频输出通过 -PlayerFactory 注入，Windows 上传入 WASAPI 播放器。
#
#  协议见 docs/PROTOCOL.md：
#    UDP 47777  DISCOVER 广播 / PROBE 探测 / HERE 应答
#    TCP 47778  行分隔 JSON：HELLO / START / STOP / PING / PONG / STAT / BYE
#    TCP 47779  4 字节大端长度前缀 + 16bit 小端单声道 PCM
# =====================================================================

$script:LuoMicProtocol = @{
    DiscoveryPort = 47777
    ControlPort   = 47778
    AudioPort     = 47779
    Magic         = 'LUOMIC/1'
    Rate          = 48000
    FrameMs       = 20
    ClientTimeout = 12
    MaxFrameBytes = 8192
}

function Write-LuoMicLog {
    param([string]$Message, [string]$Color = 'Gray')
    Write-Host $Message -ForegroundColor $Color
}

function Start-LuoMicProtocol {
    <#
    .SYNOPSIS
        启动 luo mic 电脑端服务（发现 + 控制 + 音频）。
    .PARAMETER PlayerFactory
        函数，接收设备对象，返回一个播放器对象。播放器需要提供：
            Push([byte[]] pcm)  收音频
            Stop()              停止
            LevelDb / Played / DeviceName（可选，用于显示）
        传 $null 表示不出声（只跑协议）。
    .PARAMETER DeviceInfo
        要传给 PlayerFactory 的设备信息（Windows 上是 @{Id=..;Name=..}）。
    .PARAMETER AutoStart
        手机连上后是否自动发 START（默认 $true）。
    .PARAMETER MaxSeconds
        跑多少秒后自动退出（0 = 一直跑）。
    #>
    [CmdletBinding()]
    param(
        [scriptblock]$PlayerFactory = $null,
        $DeviceInfo = $null,
        [bool]$AutoStart = $true,
        [int]$MaxSeconds = 0,
        [string]$BindAddress = '0.0.0.0',
        [switch]$DebugMode
    )

    $P = $script:LuoMicProtocol
    $deadline = if ($MaxSeconds -gt 0) { (Get-Date).AddSeconds($MaxSeconds) } else { $null }

    # ---------------- 监听 ----------------
    $udp = New-Object System.Net.Sockets.UdpClient
    $udp.Client.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket,
        [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
    $udp.Client.Bind((New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Parse($BindAddress), $P.DiscoveryPort)))
    $udp.Client.ReceiveTimeout = 500

    $ctrlListener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Parse($BindAddress), $P.ControlPort)
    $ctrlListener.Start()
    $audioListener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Parse($BindAddress), $P.AudioPort)
    $audioListener.Start()

    Write-LuoMicLog ("  监听中：UDP {0} / TCP {1} / TCP {2}" -f $P.DiscoveryPort, $P.ControlPort, $P.AudioPort) Green

    $hostName = if ($env:COMPUTERNAME) { "$env:COMPUTERNAME 的电脑" } else { "$env:HOSTNAME 的电脑" }
    $b64Name = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($hostName)).TrimEnd('=')

    $lastAnnounce = (Get-Date).AddSeconds(-10)
    $player = $null
    $phoneName = ''
    $sessions = 0

    try {
        while ($true) {
            if ($deadline -and (Get-Date) -gt $deadline) { break }

            # 1) 周期广播（让手机"被动"就能发现电脑）
            if (((Get-Date) - $lastAnnounce).TotalSeconds -ge 2) {
                $lastAnnounce = Get-Date
                $msg = [Text.Encoding]::ASCII.GetBytes("$($P.Magic) DISCOVER`n")
                try { $udp.Send($msg, $msg.Length, '255.255.255.255', $P.DiscoveryPort) | Out-Null } catch { }
            }

            # 2) 回应手机的主动探测
            if ($udp.Available -gt 0) {
                try {
                    $remote = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
                    $data = $udp.Receive([ref]$remote)
                    $text = [Text.Encoding]::ASCII.GetString($data).Trim()
                    if ($text -eq "$($P.Magic) PROBE") {
                        $reply = [Text.Encoding]::ASCII.GetBytes(
                            "$($P.Magic) HERE $b64Name $($P.ControlPort) $($P.AudioPort)`n")
                        $udp.Send($reply, $reply.Length, $remote.Address.ToString(), $P.DiscoveryPort) | Out-Null
                        Write-LuoMicLog ("  收到 {0} 的探测，已回应" -f $remote.Address) DarkGray
                    }
                } catch { }
            }

            # 3) 手机连上控制端口
            if (-not $ctrlListener.Pending()) { Start-Sleep -Milliseconds 20; continue }

            $ctrl = $ctrlListener.AcceptTcpClient()
            $ctrl.NoDelay = $true
            $sessions++
            Write-LuoMicLog ''
            Write-LuoMicLog ("  手机接入：{0}" -f $ctrl.Client.RemoteEndPoint) Green

            $stream = $ctrl.GetStream()
            $writer = New-Object System.IO.StreamWriter($stream, (New-Object Text.UTF8Encoding($false)))
            $writer.AutoFlush = $true
            $reader = New-Object System.IO.StreamReader($stream, [Text.Encoding]::UTF8)
            $stream.ReadTimeout = 500

            # HELLO（手机据此决定采样参数）
            $writer.WriteLine(([ordered]@{ t='HELLO'; v=1; name=$hostName
                audio_port=$P.AudioPort; codec='pcm_s16le'; rate=$P.Rate; channels=1
                frame_ms=$P.FrameMs } | ConvertTo-Json -Compress))

            # 打开音频输出
            if ($PlayerFactory) {
                try {
                    $player = & $PlayerFactory $DeviceInfo
                    if ($player) { Write-LuoMicLog ("  音频输出已打开：{0}" -f $player.DeviceName) Green }
                } catch {
                    Write-LuoMicLog ("  音频设备打开失败：{0}" -f $_.Exception.Message) Red
                    $player = $null
                }
            }
            if ($AutoStart) { $writer.WriteLine('{"t":"START"}') }

            # 等音频通道
            $wait = (Get-Date).AddSeconds(10)
            while (-not $audioListener.Pending() -and (Get-Date) -lt $wait) { Start-Sleep -Milliseconds 50 }
            if (-not $audioListener.Pending()) {
                Write-LuoMicLog '  手机没有建立音频通道（超时 10 秒）' Yellow
                try { $ctrl.Close() } catch { }
                if ($player) { try { $player.Stop() } catch { } }
                continue
            }

            $audio = $audioListener.AcceptTcpClient()
            $audio.NoDelay = $true
            $aStream = $audio.GetStream()
            $aStream.ReadTimeout = 1000

            # 32 字节握手
            $hs = New-Object byte[] 32
            $got = 0
            $chunks = @()
            try {
                $aStream.ReadTimeout = 2000
                while ($got -lt 32) {
                    $n = $aStream.Read($hs, $got, 32 - $got)
                    if ($DebugMode) {
                        $slice = ($hs[($got)..([Math]::Max($got, $got + $n - 1))] | ForEach-Object { $_.ToString('X2') }) -join ' '
                        Write-LuoMicLog ("  [调试] read 返回 {0} 字节（累计 {1}）：{2}" -f $n, ($got + $n), $slice) DarkGray
                    }
                    if ($n -le 0) { break }
                    $got += $n
                }
            } catch {
                if ($DebugMode) { Write-LuoMicLog ("  [调试] 握手读取异常：{0}" -f $_.Exception.Message) DarkGray }
            }
            # ⚠️ PowerShell 坑：byte 类型的 -shl 会被截断成 0，必须先转 [int]
            #    （[byte]0xBB -shl 8 得到 0，[int][byte]0xBB -shl 8 才是 47872）
            $hsRate = ([int]$hs[12] -shl 24) -bor ([int]$hs[13] -shl 16) -bor ([int]$hs[14] -shl 8) -bor [int]$hs[15]
            Write-LuoMicLog ("  音频通道已建立（握手 {0} 字节，手机采样率 {1}Hz）" -f $got, $hsRate) Green
            if ($DebugMode) {
                Write-LuoMicLog ("  [调试] 握手原始字节: {0}" -f (($hs | ForEach-Object { $_.ToString('X2') }) -join ' ')) DarkGray
            }
            Write-LuoMicLog '  正在传输…（手机端应显示"正在传输"）' White

            # ---------------- 音视频/心跳循环 ----------------
            $lastRecv = Get-Date
            $lastPing = Get-Date
            $lastStat = Get-Date
            $bytes = 0
            $lastBytes = 0
            $frames = 0
            $hdr = New-Object byte[] 4

            while ($true) {
                if ($deadline -and (Get-Date) -gt $deadline) { break }

                # 读一帧音频
                try {
                    $got = 0
                    while ($got -lt 4) {
                        $n = $aStream.Read($hdr, $got, 4 - $got)
                        if ($n -le 0) { throw 'peer closed' }
                        $got += $n
                    }
                    $len = ([int]$hdr[0] -shl 24) -bor ([int]$hdr[1] -shl 16) -bor ([int]$hdr[2] -shl 8) -bor [int]$hdr[3]
                    if ($len -le 0 -or $len -gt $P.MaxFrameBytes) {
                        if ($DebugMode) {
                            Write-LuoMicLog ("  [调试] 帧头字节: {0} -> 长度 {1}" -f (($hdr | ForEach-Object { $_.ToString('X2') }) -join ' '), $len) DarkGray
                        }
                        throw "帧长度非法：$len"
                    }
                    $pcm = New-Object byte[] $len
                    $got = 0
                    while ($got -lt $len) {
                        $n = $aStream.Read($pcm, $got, $len - $got)
                        if ($n -le 0) { throw 'peer closed' }
                        $got += $n
                    }
                    if ($player) { $player.Push($pcm) }
                    $bytes += $len
                    $frames++
                    $lastRecv = Get-Date
                } catch [System.IO.IOException] {
                    # 读超时属正常，继续
                } catch {
                    Write-LuoMicLog ("  音频通道中断：{0}" -f $_.Exception.Message) Yellow
                    break
                }

                # 读控制报文
                try {
                    while ($stream.DataAvailable) {
                        $line = $reader.ReadLine()
                        if ($null -eq $line) { break }
                        $obj = $line | ConvertFrom-Json
                        switch ($obj.t) {
                            'HELLO' {
                                if ($obj.device) { $phoneName = $obj.device }
                                Write-LuoMicLog ("  手机已连接：{0}" -f $phoneName) Green
                            }
                            'BYE' { Write-LuoMicLog '  手机主动断开' Yellow; throw 'bye' }
                            default { }
                        }
                        $lastRecv = Get-Date
                    }
                } catch [System.Management.Automation.RuntimeException] {
                    break
                } catch { }

                # 心跳
                if (((Get-Date) - $lastPing).TotalSeconds -ge 2) {
                    $lastPing = Get-Date
                    try {
                        $ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                        $writer.WriteLine(([ordered]@{ t='PING'; ts=$ts } | ConvertTo-Json -Compress))
                    } catch { Write-LuoMicLog '  控制通道断开' Yellow; break }
                }

                # 心跳超时
                if (((Get-Date) - $lastRecv).TotalSeconds -gt $P.ClientTimeout) {
                    Write-LuoMicLog '  与手机心跳超时，断开' Yellow
                    break
                }

                # 每秒状态
                if (((Get-Date) - $lastStat).TotalSeconds -ge 1) {
                    $secs = ((Get-Date) - $lastStat).TotalSeconds
                    $kbps = [int](($bytes - $lastBytes) * 8 / 1000 / $secs)
                    $lastStat = Get-Date
                    $lastBytes = $bytes
                    $db = -120
                    $played = 0
                    if ($player) {
                        if ($player.PSObject.Properties.Name -contains 'LevelDb') { $db = [int]$player.LevelDb }
                        if ($player.PSObject.Properties.Name -contains 'Played') { $played = $player.Played }
                    }
                    Write-Host ("`r  接收中 | {0} | {1} kbps | {2} 帧 | 播放 {3} | 电平 {4} dB   " -f `
                        $phoneName, $kbps, $frames, $played, $db) -NoNewline -ForegroundColor Gray
                }
            }

            Write-Host ''
            if ($player) { try { $player.Stop() } catch { } ; $player = $null }
            try { $audio.Close() } catch { }
            try { $ctrl.Close() } catch { }
            if ($deadline -and (Get-Date) -gt $deadline) { break }
            Write-LuoMicLog '  本次会话结束，等待手机重新连接…' Yellow
        }
    } finally {
        try { $udp.Close() } catch { }
        try { $ctrlListener.Stop() } catch { }
        try { $audioListener.Stop() } catch { }
        if ($player) { try { $player.Stop() } catch { } }
    }
    return $sessions
}
