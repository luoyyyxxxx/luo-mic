# =====================================================================
#  Set the Windows default playback / recording device.
#
#  Uses the documented-in-practice IPolicyConfig COM interface
#  (works on Windows 7 .. 11). Needed so that games such as Counter-Strike
#  pick up the VB-CABLE virtual microphone without any in-game setup.
#
#  ASCII-only: Windows PowerShell 5.1 reads BOM-less files as ANSI, and
#  non-ASCII bytes at end of line can swallow the newline.
#
#  Usage:
#     .\set-default-audio.ps1 -List
#     .\set-default-audio.ps1 -Render "CABLE Input"
#     .\set-default-audio.ps1 -Capture "CABLE Output"
#     .\set-default-audio.ps1 -Capture "CABLE Output" -Render "Realtek"
# =====================================================================
[CmdletBinding()]
param(
    [string]$Render = '',
    [string]$Capture = '',
    [switch]$List
)

$ErrorActionPreference = 'Stop'

$code = @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace LuomicAudio
{
    internal enum EDataFlow { eRender = 0, eCapture = 1, eAll = 2 }
    internal enum ERole { eConsole = 0, eMultimedia = 1, eCommunications = 2 }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    internal class MMDeviceEnumerator { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(EDataFlow flow, int mask, out IMMDeviceCollection col);
        int GetDefaultAudioEndpoint(EDataFlow flow, ERole role, out IMMDevice dev);
        int GetDevice(string id, out IMMDevice dev);
        int RegisterEndpointNotificationCallback(IntPtr cb);
        int UnregisterEndpointNotificationCallback(IntPtr cb);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDeviceCollection
    {
        int GetCount(out int count);
        int Item(int index, out IMMDevice dev);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDevice
    {
        int Activate(ref Guid iid, int ctx, IntPtr params_, out IntPtr iface);
        int OpenPropertyStore(int access, out IntPtr props);
        int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetState(out int state);
    }

    // IPropertyStore - read PKEY_Device_FriendlyName
    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    internal struct PROPERTYKEY { public Guid fmtid; public int pid; }

    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    internal struct PROPVARIANT
    {
        public ushort vt; public ushort r1; public uint r2; public IntPtr p;
    }

    [ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IPropertyStore
    {
        int GetCount(out int count);
        int GetAt(int index, out PROPERTYKEY key);
        int GetValue(ref PROPERTYKEY key, out PROPVARIANT value);
        int SetValue(ref PROPERTYKEY key, ref PROPVARIANT value);
        int Commit();
    }

    // IPolicyConfig - set the default endpoint for a role
    [ComImport, Guid("f8679f50-850a-41cf-9c72-430f290290c8"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IPolicyConfig
    {
        int GetMixFormat(string id, out IntPtr fmt);
        int GetDeviceFormat(string id, bool def, out IntPtr fmt);
        int ResetDeviceFormat(string id);
        int SetDeviceFormat(string id, IntPtr end, IntPtr mix);
        int GetProcessingPeriod(string id, bool def, out long a, out long b);
        int SetProcessingPeriod(string id, ref long p);
        int GetShareMode(string id, out IntPtr mode);
        int SetShareMode(string id, IntPtr mode);
        int GetPropertyValue(string id, ref PROPERTYKEY key, out PROPVARIANT v);
        int SetPropertyValue(string id, ref PROPERTYKEY key, ref PROPVARIANT v);
        int SetDefaultEndpoint(string id, ERole role);
        int SetEndpointVisibility(string id, bool visible);
    }

    [ComImport, Guid("870af99c-171d-4f9e-af0d-e63df40c2bc9")]
    internal class CPolicyConfigClient { }

    public class Endpoint
    {
        public string Id;
        public string Name;
        public bool IsCapture;
    }

    public static class AudioDefaults
    {
        static IMMDeviceEnumerator Enumerator()
        {
            return (IMMDeviceEnumerator)(new MMDeviceEnumerator());
        }

        static string FriendlyName(IMMDevice dev)
        {
            string name = "(unknown)";
            IntPtr store = IntPtr.Zero;
            try
            {
                if (dev.OpenPropertyStore(0, out store) != 0 || store == IntPtr.Zero)
                    return name;
                var ps = (IPropertyStore)Marshal.GetObjectForIUnknown(store);
                var key = new PROPERTYKEY
                {
                    fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"),
                    pid = 14
                };
                PROPVARIANT pv;
                if (ps.GetValue(ref key, out pv) == 0 && pv.p != IntPtr.Zero)
                {
                    name = Marshal.PtrToStringUni(pv.p) ?? name;
                }
                // release the PROPVARIANT through the policy object is not needed here;
                // the property store frees it on release
                Marshal.Release(store);
            }
            catch { }
            return name;
        }

        public static List<Endpoint> List(bool capture)
        {
            var list = new List<Endpoint>();
            var en = Enumerator();
            IMMDeviceCollection col;
            var flow = capture ? EDataFlow.eCapture : EDataFlow.eRender;
            if (en.EnumAudioEndpoints(flow, 1, out col) != 0) return list;
            int count;
            col.GetCount(out count);
            for (int i = 0; i < count; i++)
            {
                IMMDevice dev;
                if (col.Item(i, out dev) != 0) continue;
                string id;
                dev.GetId(out id);
                list.Add(new Endpoint { Id = id, Name = FriendlyName(dev), IsCapture = capture });
            }
            return list;
        }

        public static string FindId(string namePart, bool capture)
        {
            foreach (var e in List(capture))
            {
                if (e.Name.IndexOf(namePart, StringComparison.OrdinalIgnoreCase) >= 0)
                    return e.Id;
            }
            return null;
        }

        public static bool SetDefault(string id)
        {
            var pc = (IPolicyConfig)(new CPolicyConfigClient());
            bool ok = true;
            foreach (ERole r in new[] { ERole.eConsole, ERole.eMultimedia, ERole.eCommunications })
            {
                if (pc.SetDefaultEndpoint(id, r) != 0) ok = false;
            }
            Marshal.ReleaseComObject(pc);
            return ok;
        }

        public static string DefaultName(bool capture)
        {
            var en = Enumerator();
            IMMDevice dev;
            var flow = capture ? EDataFlow.eCapture : EDataFlow.eRender;
            if (en.GetDefaultAudioEndpoint(flow, ERole.eConsole, out dev) != 0) return "(none)";
            return FriendlyName(dev);
        }
    }
}
'@

Add-Type -TypeDefinition $code -ErrorAction Stop

if ($List) {
    Write-Output "=== render devices ==="
    foreach ($e in [LuomicAudio.AudioDefaults]::List($false)) {
        Write-Output ("  " + $e.Name)
    }
    Write-Output "=== capture devices ==="
    foreach ($e in [LuomicAudio.AudioDefaults]::List($true)) {
        Write-Output ("  " + $e.Name)
    }
    Write-Output ""
    Write-Output ("render default : " + [LuomicAudio.AudioDefaults]::DefaultName($false))
    Write-Output ("capture default: " + [LuomicAudio.AudioDefaults]::DefaultName($true))
    return
}

if ($Render) {
    $id = [LuomicAudio.AudioDefaults]::FindId($Render, $false)
    if (-not $id) { Write-Output ("[FAIL] render device not found: " + $Render); exit 1 }
    $ok = [LuomicAudio.AudioDefaults]::SetDefault($id)
    Write-Output ("render default  -> " + $Render + "   ok=" + $ok)
}

if ($Capture) {
    $id = [LuomicAudio.AudioDefaults]::FindId($Capture, $true)
    if (-not $id) { Write-Output ("[FAIL] capture device not found: " + $Capture); exit 1 }
    $ok = [LuomicAudio.AudioDefaults]::SetDefault($id)
    Write-Output ("capture default -> " + $Capture + "   ok=" + $ok)
}

Write-Output ""
Write-Output ("render default now : " + [LuomicAudio.AudioDefaults]::DefaultName($false))
Write-Output ("capture default now: " + [LuomicAudio.AudioDefaults]::DefaultName($true))
