# Build the luo mic application icon (multi-size ICO).
# Rounded green tile + white microphone, matching the PC accent colour #0E9F6E.
# NOTE: ASCII-only on purpose - Windows PowerShell 5.1 reads BOM-less files as
# ANSI, where non-ASCII bytes can swallow the trailing newline.
Add-Type -AssemblyName System.Drawing

$code = @'
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;

public static class IconGen
{
    static GraphicsPath RoundRect(RectangleF r, float rad)
    {
        float d = rad * 2f;
        var p = new GraphicsPath();
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    public static Bitmap Render(int S)
    {
        var bmp = new Bitmap(S, S, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            g.Clear(Color.Transparent);

            // Rounded tile with a green gradient
            var body = new RectangleF(S * 0.015f, S * 0.015f, S * 0.97f, S * 0.97f);
            using (var gp = RoundRect(body, S * 0.21f))
            using (var br = new LinearGradientBrush(body,
                        Color.FromArgb(0x17, 0xB9, 0x80), Color.FromArgb(0x0A, 0x7A, 0x54), 60f))
            {
                g.FillPath(br, gp);
            }

            // Microphone capsule head
            var head = new RectangleF(S * 0.335f, S * 0.165f, S * 0.33f, S * 0.40f);
            using (var gp = RoundRect(head, S * 0.165f))
            using (var br = new LinearGradientBrush(head, Color.White, Color.FromArgb(0xE4, 0xF6, 0xEE), 90f))
            {
                g.FillPath(br, gp);
            }

            float w = Math.Max(1.6f, S * 0.075f);

            // Pickup cradle (arc opening upwards)
            using (var pen = new Pen(Color.White, w))
            {
                pen.StartCap = LineCap.Round;
                pen.EndCap = LineCap.Round;
                g.DrawArc(pen, S * 0.225f, S * 0.375f, S * 0.55f, S * 0.38f, 0f, 180f);
            }

            // Stem
            using (var pen = new Pen(Color.White, w))
            {
                pen.StartCap = LineCap.Round;
                pen.EndCap = LineCap.Round;
                g.DrawLine(pen, S * 0.50f, S * 0.755f, S * 0.50f, S * 0.845f);
            }

            // Base
            using (var pen = new Pen(Color.White, w))
            {
                pen.StartCap = LineCap.Round;
                pen.EndCap = LineCap.Round;
                g.DrawLine(pen, S * 0.345f, S * 0.848f, S * 0.655f, S * 0.848f);
            }
        }
        return bmp;
    }

    public static byte[] PngBytes(Bitmap bmp)
    {
        using (var ms = new MemoryStream())
        {
            bmp.Save(ms, ImageFormat.Png);
            return ms.ToArray();
        }
    }

    public static void WriteIco(string path, int[] sizes)
    {
        var pngs = new List<byte[]>();
        foreach (int s in sizes)
        {
            using (var b = Render(s)) { pngs.Add(PngBytes(b)); }
        }
        using (var fs = new FileStream(path, FileMode.Create, FileAccess.Write))
        using (var w = new BinaryWriter(fs))
        {
            w.Write((ushort)0);
            w.Write((ushort)1);
            w.Write((ushort)sizes.Length);
            int offset = 6 + 16 * sizes.Length;
            for (int i = 0; i < sizes.Length; i++)
            {
                int s = sizes[i];
                w.Write((byte)(s >= 256 ? 0 : s));
                w.Write((byte)(s >= 256 ? 0 : s));
                w.Write((byte)0);
                w.Write((byte)0);
                w.Write((ushort)1);
                w.Write((ushort)32);
                w.Write((uint)pngs[i].Length);
                w.Write((uint)offset);
                offset += pngs[i].Length;
            }
            foreach (var png in pngs) { w.Write(png); }
        }
    }

    public static void WritePng(string path, int size)
    {
        using (var b = Render(size)) { b.Save(path, ImageFormat.Png); }
    }
}
'@

Add-Type -TypeDefinition $code -ReferencedAssemblies System.Drawing

$outDir = $PSScriptRoot
$ico = Join-Path $outDir 'luo-mic.ico'
[IconGen]::WriteIco($ico, @(16, 24, 32, 48, 64, 128, 256))
[IconGen]::WritePng((Join-Path $outDir 'luo-mic-256.png'), 256)
[IconGen]::WritePng((Join-Path $outDir 'luo-mic-64.png'), 64)

Write-Output ("ICO: " + $ico + "  " + [math]::Round((Get-Item $ico).Length / 1KB, 1) + " KB")
Write-Output ("PNG: " + (Join-Path $outDir 'luo-mic-256.png'))
