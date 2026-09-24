# =====================================================================
#  luo mic · Windows 音频输出模块（WASAPI）
#
#  设计说明：C# 代码里用到 Windows COM（MMDeviceEnumerator / IAudioClient），
#  只能在 Windows 上编译。所以单独放在这个文件，
#  由 luomic-native.ps1 在 Windows 上加载；
#  网络协议部分（luomic-protocol.ps1）与它解耦，可以在任何系统上测试。
# =====================================================================

$script:LuoMicAudioCode = @'
using System;
using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using System.Threading;

namespace LuoMic
{
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    internal class MMDeviceEnumeratorComObject { }

    internal enum EDataFlow { eRender = 0, eCapture = 1, eAll = 2 }
    internal enum ERole { eConsole = 0, eMultimedia = 1, eCommunications = 2 }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(EDataFlow dataFlow, int stateMask, out IMMDeviceCollection devices);
        int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice endpoint);
        int GetDevice(string id, out IMMDevice device);
        int RegisterEndpointNotificationCallback(IntPtr client);
        int UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDeviceCollection
    {
        int GetCount(out int count);
        int Item(int index, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDevice
    {
        int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        int OpenPropertyStore(int access, out IntPtr properties);
        int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetState(out int state);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioClient
    {
        int Initialize(int shareMode, int flags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
        int GetBufferSize(out int frames);
        int GetStreamLatency(out long latency);
        int GetCurrentPadding(out int padding);
        int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closestMatch);
        int GetMixFormat(out IntPtr format);
        int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        int Start();
        int Stop();
        int Reset();
        int SetEventHandle(IntPtr handle);
        int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    }

    [ComImport, Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioRenderClient
    {
        int GetBuffer(int frames, out IntPtr buffer);
        int ReleaseBuffer(int frames, int flags);
    }

    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    public struct WaveFormatEx
    {
        public ushort wFormatTag;
        public ushort nChannels;
        public uint nSamplesPerSec;
        public uint nAvgBytesPerSec;
        public ushort nBlockAlign;
        public ushort wBitsPerSample;
        public ushort cbSize;
    }

    /// <summary>极简 WASAPI 播放器：把 16bit 单声道 PCM 播到指定设备。</summary>
    public class WasapiPlayer : IDisposable
    {
        private IAudioClient client;
        private IAudioRenderClient render;
        private IntPtr formatPtr = IntPtr.Zero;
        private WaveFormatEx deviceFormat;
        private bool isFloat;
        private int channels;
        private volatile bool running;
        private System.Threading.Thread thread;
        private readonly ConcurrentQueue<byte[]> queue = new ConcurrentQueue<byte[]>();
        private long underruns;
        private long played;
        private float levelDb = -120f;
        private long lastFrameTicks;

        public string DeviceName = "";
        public int SourceRate = 48000;
        public int SourceChannels = 1;

        public long Underruns { get { return System.Threading.Interlocked.Read(ref underruns); } }
        public long Played { get { return System.Threading.Interlocked.Read(ref played); } }
        public float LevelDb { get { return levelDb; } }
        public bool HasRecentAudio { get { return (DateTime.UtcNow.Ticks - System.Threading.Interlocked.Read(ref lastFrameTicks)) < TimeSpan.TicksPerSecond * 2; } }

        public static System.Collections.Generic.List<string> ListRenderDevices()
        {
            var list = new System.Collections.Generic.List<string>();
            IMMDeviceEnumerator en = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
            IMMDeviceCollection col;
            if (en.EnumAudioEndpoints(EDataFlow.eRender, 1, out col) != 0) return list;
            int count;
            col.GetCount(out count);
            for (int i = 0; i < count; i++)
            {
                IMMDevice dev;
                if (col.Item(i, out dev) != 0) continue;
                string name = GetFriendlyName(dev);
                string id;
                dev.GetId(out id);
                list.Add(name + "||" + id);
            }
            return list;
        }

        internal static string GetFriendlyName(IMMDevice dev)
        {
            try
            {
                IntPtr store;
                if (dev.OpenPropertyStore(0, out store) != 0) return "(未知设备)";
                var pkey = new PROPERTYKEY();
                pkey.fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0");
                pkey.pid = 14; // PKEY_Device_FriendlyName
                IntPtr pv;
                int hr = PSGetValue(store, ref pkey, out pv);
                if (hr != 0 || pv == IntPtr.Zero) return "(未知设备)";
                string s = Marshal.PtrToStringUni(pv);
                PropVariantClear(pv);
                Marshal.Release(store);
                return s ?? "(未知设备)";
            }
            catch { return "(未知设备)"; }
        }

        [StructLayout(LayoutKind.Sequential, Pack = 4)]
        internal struct PROPERTYKEY { public Guid fmtid; public int pid; }

        [DllImport("ole32.dll")]
        internal static extern int PropVariantClear(IntPtr pv);

        // IPropertyStore::GetValue 通过 vtable 调用
        [DllImport("propsys.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
        internal static extern void PSGetPropertyKeyFromName(string name, out PROPERTYKEY pkey);

        internal static int PSGetValue(IntPtr store, ref PROPERTYKEY key, out IntPtr value)
        {
            // IPropertyStore vtable: 0..2 IUnknown, 3 GetCount, 4 GetAt, 5 GetValue
            IntPtr vtbl = Marshal.ReadIntPtr(store);
            IntPtr fn = Marshal.ReadIntPtr(vtbl, IntPtr.Size * 5);
            var del = (GetValueDelegate)Marshal.GetDelegateForFunctionPointer(fn, typeof(GetValueDelegate));
            return del(store, ref key, out value);
        }

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        internal delegate int GetValueDelegate(IntPtr thisPtr, ref PROPERTYKEY key, out IntPtr value);

        /// <summary>打开设备并开始播放。</summary>
        public void Start(string deviceIdOrNull, int sourceRate, int sourceChannels)
        {
            SourceRate = sourceRate;
            SourceChannels = sourceChannels;

            IMMDeviceEnumerator en = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
            IMMDevice dev;
            if (!string.IsNullOrEmpty(deviceIdOrNull))
            {
                if (en.GetDevice(deviceIdOrNull, out dev) != 0) throw new Exception("找不到指定的音频设备");
            }
            else
            {
                if (en.GetDefaultAudioEndpoint(EDataFlow.eRender, ERole.eMultimedia, out dev) != 0)
                    throw new Exception("找不到默认音频输出设备");
            }
            DeviceName = GetFriendlyName(dev);

            Guid iid = typeof(IAudioClient).GUID;
            object o;
            if (dev.Activate(ref iid, 1 /*CLSCTX_ALL*/, IntPtr.Zero, out o) != 0) throw new Exception("无法激活音频设备");
            client = (IAudioClient)o;

            IntPtr fmt;
            if (client.GetMixFormat(out fmt) != 0) throw new Exception("无法读取设备格式");
            formatPtr = fmt;
            deviceFormat = (WaveFormatEx)Marshal.PtrToStructure(fmt, typeof(WaveFormatEx));
            isFloat = deviceFormat.wFormatTag == 3;   // WAVE_FORMAT_IEEE_FLOAT
            channels = deviceFormat.nChannels;
            if ((int)deviceFormat.wBitsPerSample != 16 && (int)deviceFormat.wBitsPerSample != 32)
                throw new Exception("设备位深不支持：" + (int)deviceFormat.wBitsPerSample);

            long dur = 2000000; // 200ms 缓冲（100ns 单位）
            int hr = client.Initialize(0 /*SHARED*/, 0, dur, 0, fmt, IntPtr.Zero);
            if (hr != 0) throw new Exception("音频初始化失败（HRESULT 0x" + hr.ToString("X8") + "）");

            Guid rid = typeof(IAudioRenderClient).GUID;
            object ro;
            if (client.GetService(ref rid, out ro) != 0) throw new Exception("无法获取播放接口");
            render = (IAudioRenderClient)ro;

            client.Start();
            running = true;
            thread = new System.Threading.Thread(Loop);
            thread.IsBackground = true;
            thread.Start();
        }

        public void Push(byte[] pcm)
        {
            Interlocked.Exchange(ref lastFrameTicks, DateTime.UtcNow.Ticks);
            // 峰值电平
            int peak = 0;
            for (int i = 0; i + 1 < pcm.Length; i += 2)
            {
                short v = (short)(pcm[i] | (pcm[i + 1] << 8));
                int a = v < 0 ? -v : v;
                if (a > peak) peak = a;
            }
            levelDb = peak <= 0 ? -120f : (float)Math.Max(-120.0, 20.0 * Math.Log10(peak / 32768.0));
            queue.Enqueue(pcm);
            // 队列过长说明消费不过来（延迟堆积），丢掉最旧的
            while (queue.Count > 25) { byte[] drop; queue.TryDequeue(out drop); }
        }

        // 预缓冲：队列里至少攒够这么多块才开始播放，用来吸收网络抖动。
        // 每块 20ms，5 块 ≈ 100ms —— 局域网实测 RTT 约 10ms，这个值足够安全。
        private const int PREBUFFER_CHUNKS = 5;

        private void Loop()
        {
            int blockFrames = (int)deviceFormat.nSamplesPerSec * 20 / 1000; // 每次写 20ms
            int bytesPerSampleLocal = (int)deviceFormat.wBitsPerSample / 8;
            byte[] silence = new byte[blockFrames * channels * bytesPerSampleLocal];
            bool primed = false;
            long starveStart = 0;

            while (running)
            {
                // ① 预缓冲：数据不够就先写静音等着（避免一有数据就 drain 造成断续）
                if (!primed)
                {
                    if (queue.Count < PREBUFFER_CHUNKS)
                    {
                        WriteSilence(blockFrames, silence);
                        System.Threading.Thread.Sleep(FRAME_SLEEP);
                        continue;
                    }
                    primed = true;
                }

                int padding;
                if (client.GetCurrentPadding(out padding) != 0) break;
                int frames = blockFrames - padding;
                if (frames <= 0) { System.Threading.Thread.Sleep(FRAME_SLEEP); continue; }

                IntPtr buf;
                if (render.GetBuffer(frames, out buf) != 0) break;

                byte[] pcm;
                if (!queue.TryDequeue(out pcm))
                {
                    // ② 队列空了：先看是不是"瞬时抖动"。
                    //    抖动容忍窗口内继续补静音但不退出预缓冲状态；
                    //    超过 300ms 还没数据，才退回预缓冲状态重新攒。
                    if (starveStart == 0) starveStart = DateTime.UtcNow.Ticks;
                    if ((DateTime.UtcNow.Ticks - starveStart) > TimeSpan.TicksPerMillisecond * 300)
                    {
                        primed = false;
                        starveStart = 0;
                    }
                    WriteSilence(frames, silence);
                    Interlocked.Increment(ref underruns);
                    continue;
                }
                starveStart = 0;

                WriteConverted(buf, frames, pcm);
                render.ReleaseBuffer(frames, 0);
                Interlocked.Increment(ref played);
            }
        }

        /// <summary>写静音并释放缓冲（保持 WASAPI 时钟连续，避免爆音）。</summary>
        private void WriteSilence(int frames, byte[] silence)
        {
            IntPtr buf;
            if (render == null) return;
            if (render.GetBuffer(frames, out buf) != 0) return;
            int bytesPerSampleLocal = (int)deviceFormat.wBitsPerSample / 8;
            int need = frames * channels * bytesPerSampleLocal;
            Marshal.Copy(silence, 0, buf, Math.Min(silence.Length, need));
            render.ReleaseBuffer(frames, 0);
        }

        private const int FRAME_SLEEP = 5;

        // 跨块保持的小数相位：不保持的话每次从 0 重算，长时间播放会累积漂移
        private double resamplePhase = 0.0;
        private byte[] convertBuf = null;
        private int lastSrcRate = 0;

        /// <summary>
        /// 把 16bit 单声道 PCM 转成设备要求的格式写进缓冲。
        /// 48k→44.1k 这类采样率不一致时用**线性插值**（不是最近邻），
        /// 否则会有明显混叠噪声；并用跨块保持的小数相位避免累积漂移。
        /// </summary>
        private void WriteConverted(IntPtr buf, int frames, byte[] pcm)
        {
            int bytesPerSampleLocal = (int)deviceFormat.wBitsPerSample / 8;
            int outBytes = frames * channels * bytesPerSampleLocal;
            if (convertBuf == null || convertBuf.Length < outBytes)
            {
                convertBuf = new byte[outBytes + 4096];
            }
            byte[] outBuf = convertBuf;

            int srcSamples = pcm.Length / 2;
            int srcRate = SourceRate > 0 ? SourceRate : (int)deviceFormat.nSamplesPerSec;
            if (srcRate <= 0) srcRate = 48000;
            int devRate = (int)deviceFormat.nSamplesPerSec;

            // 采样率变了就重置相位
            if (srcRate != lastSrcRate) { resamplePhase = 0.0; lastSrcRate = srcRate; }

            // step = 每输出一个样本要走多少个源样本
            double step = (double)srcRate / devRate;
            // phase 记的是"上一个输出样本落在源缓冲里的位置"的小数部分。
            // 本次从 -step 开始推进，第一个输出样本就正好落在 phase 处。
            double phase = resamplePhase;
            double pos = phase - step;

            for (int i = 0; i < frames; i++)
            {
                int i0 = (int)pos;
                double frac = pos - i0;
                short s0 = SampleAt(pcm, srcSamples, i0);
                short s1 = SampleAt(pcm, srcSamples, i0 + 1);
                short s = (short)(s0 + (s1 - s0) * frac);

                for (int c = 0; c < channels; c++)
                {
                    int off = (i * channels + c) * bytesPerSampleLocal;
                    if (isFloat)
                    {
                        float f = s / 32768f;
                        byte[] fb = BitConverter.GetBytes(f);
                        Buffer.BlockCopy(fb, 0, outBuf, off, 4);
                    }
                    else
                    {
                        outBuf[off] = (byte)(s & 0xFF);
                        outBuf[off + 1] = (byte)((s >> 8) & 0xFF);
                    }
                }
                pos += step;
            }
            // 保存相位（取小数部分）供下一块接着用 —— 这是"不累积漂移"的关键。
            // 每块开头用 phase - step 起步，正好抵消这里多推进的那一步。
            resamplePhase = pos - Math.Floor(pos);
            if (resamplePhase < 0) resamplePhase = 0;

            Marshal.Copy(outBuf, 0, buf, Math.Min(outBuf.Length, outBytes));
        }

        /// <summary>安全取样本（越界返回 0）。</summary>
        private static short SampleAt(byte[] pcm, int count, int index)
        {
            if (index < 0 || index >= count) return 0;
            return (short)(pcm[index * 2] | (pcm[index * 2 + 1] << 8));
        }

        public void Stop()
        {
            running = false;
            try { if (thread != null) thread.Join(300); } catch { }
            try { if (client != null) client.Stop(); } catch { }
            thread = null;
        }

        public void Dispose() { Stop(); }
    }
}
'@

function Initialize-LuoMicAudio {
    <#
      编译 C# 音频组件。成功返回 $true；失败返回 $false（网络功能不受影响）。

      坑：Add-Type 的 -ReferencedAssemblies 会**覆盖**默认程序集列表，
      只给 'System.Core' 会导致 System.Collections.Concurrent / System.Runtime.InteropServices
      都找不到（实测报 CS0234）。所以这里显式给出完整依赖列表，
      并在缺失时自动回退到不指定引用的默认编译。
    #>
    $candidates = @(
        @('System.dll', 'System.Core.dll'),
        @('System.Core'),
        $null
    )
    foreach ($refs in $candidates) {
        try {
            if ($refs) {
                Add-Type -TypeDefinition $script:LuoMicAudioCode -Language CSharp `
                         -ReferencedAssemblies $refs -ErrorAction Stop
            } else {
                Add-Type -TypeDefinition $script:LuoMicAudioCode -Language CSharp -ErrorAction Stop
            }
            return $true
        } catch {
            $lastError = $_.Exception.Message
        }
    }
    Write-Host "音频组件编译失败：$lastError" -ForegroundColor Red
    Write-Host "（网络功能不受影响；想出声请用 Java 版 luo-mic.jar，或安装 .NET Framework 4.x）" -ForegroundColor Yellow
    return $false
}

function Get-LuoMicRenderDevices {
    <#
      返回 @( @{Name=...; Id=...} )。没有音频组件、或系统不支持 COM 时返回空数组。
      注意：必须 try/catch —— 非 Windows 上调用会抛 "COM is not supported"，
      不接住会直接终止整个程序（实测踩过）。
    #>
    $out = @()
    if (-not ('LuoMic.WasapiPlayer' -as [type])) { return $out }
    try {
        foreach ($d in [LuoMic.WasapiPlayer]::ListRenderDevices()) {
            $parts = $d.Split('||')
            if ($parts.Length -ge 2) {
                $out += @{ Name = $parts[0]; Id = $parts[1] }
            }
        }
    } catch {
        Write-Host ("  枚举音频设备失败：{0}" -f $_.Exception.Message) -ForegroundColor DarkGray
    }
    return $out
}

function New-LuoMicPlayer {
    <# 创建播放器；没有音频组件或系统不支持时返回 $null（程序仍可跑，只是不出声）。 #>
    if (-not ('LuoMic.WasapiPlayer' -as [type])) { return $null }
    try {
        return New-Object LuoMic.WasapiPlayer
    } catch {
        Write-Host ("  创建播放器失败：{0}" -f $_.Exception.Message) -ForegroundColor DarkGray
        return $null
    }
}
