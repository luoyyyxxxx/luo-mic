// luo mic - single-file self-extracting launcher.
//
// The whole application (jpackage app-image with a trimmed Java runtime) is
// embedded as the "payload" resource. On first run it is extracted to
// %LOCALAPPDATA%\luo-mic\app\<version> and then launched from there, so the
// application keeps a stable path (the autostart registry entry and the
// firewall rules point at a real, permanent location).
//
// ASCII-only on purpose: the toolchain on this machine mis-handles BOM-less
// UTF-8 scripts, and this file must stay readable by csc on any locale.
using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text;

internal static class Launcher
{
    // build-exe.ps1 substitutes the real version into this token before compiling,
    // so a new version never reuses an older version's extracted directory.
    private const string AppVersion = "@APPVERSION@";
    private const string AppExe = "luo mic.exe";
    private const string ConsoleExe = "luo-mic-console.exe";

    private static int Main(string[] args)
    {
        Console.OutputEncoding = Encoding.UTF8;
        try
        {
            string dir = AppDir();
            string exe = Path.Combine(dir, AppExe);
            string consoleExe = Path.Combine(dir, ConsoleExe);

            if (!File.Exists(exe))
            {
                Extract(dir);
            }

            // The console launcher is used when the caller asks for something
            // that writes to stdout; otherwise the normal GUI launcher.
            string target = exe;
            if (File.Exists(consoleExe) && WantsConsole(args))
            {
                target = consoleExe;
            }

            var psi = new ProcessStartInfo(target)
            {
                UseShellExecute = false,
                WorkingDirectory = dir,
                Arguments = BuildArguments(args)
            };
            using (Process p = Process.Start(psi))
            {
                p.WaitForExit();
                return p.ExitCode;
            }
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("luo mic launcher error: " + ex.Message);
            return 1;
        }
    }

    /// <summary>Flags whose output only makes sense on a console.</summary>
    private static bool WantsConsole(string[] args)
    {
        foreach (string a in args)
        {
            if (a == null) { continue; }
            if (a.Equals("--list-devices", StringComparison.OrdinalIgnoreCase) ||
                a.Equals("--help", StringComparison.OrdinalIgnoreCase) ||
                a.Equals("-h", StringComparison.OrdinalIgnoreCase) ||
                a.Equals("--install", StringComparison.OrdinalIgnoreCase) ||
                a.Equals("--uninstall", StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
        }
        return false;
    }

    private static string AppDir()
    {
        string baseDir = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(Path.Combine(Path.Combine(baseDir, "luo-mic"), "app"), AppVersion);
    }

    private static void Extract(string dir)
    {
        Console.WriteLine("luo mic: first run, unpacking the bundled runtime...");
        if (Directory.Exists(dir))
        {
            try { Directory.Delete(dir, true); }
            catch { }
        }
        Directory.CreateDirectory(dir);

        Assembly asm = Assembly.GetExecutingAssembly();
        using (Stream s = asm.GetManifestResourceStream("payload"))
        {
            if (s == null)
            {
                throw new InvalidOperationException("embedded payload is missing");
            }
            using (var za = new ZipArchive(s, ZipArchiveMode.Read))
            {
                foreach (ZipArchiveEntry e in za.Entries)
                {
                    string dest = Path.Combine(dir, e.FullName.Replace('/', Path.DirectorySeparatorChar));
                    if (e.FullName.EndsWith("/"))
                    {
                        Directory.CreateDirectory(dest);
                        continue;
                    }
                    string parent = Path.GetDirectoryName(dest);
                    if (!string.IsNullOrEmpty(parent))
                    {
                        Directory.CreateDirectory(parent);
                    }
                    e.ExtractToFile(dest, true);
                }
            }
        }
        Console.WriteLine("luo mic: unpacked to " + dir);
    }

    /// <summary>Rebuild a Windows command line from the argument vector.</summary>
    private static string BuildArguments(string[] args)
    {
        if (args == null || args.Length == 0)
        {
            return string.Empty;
        }
        var sb = new StringBuilder();
        for (int i = 0; i < args.Length; i++)
        {
            if (i > 0) { sb.Append(' '); }
            sb.Append(Quote(args[i]));
        }
        return sb.ToString();
    }

    private static string Quote(string a)
    {
        if (a == null) { return "\"\""; }
        if (a.Length > 0 && a.IndexOfAny(new char[] { ' ', '\t', '"' }) < 0)
        {
            return a;
        }
        var sb = new StringBuilder();
        sb.Append('"');
        int backslashes = 0;
        foreach (char c in a)
        {
            if (c == '\\')
            {
                backslashes++;
            }
            else if (c == '"')
            {
                sb.Append('\\', backslashes * 2 + 1);
                sb.Append('"');
                backslashes = 0;
            }
            else
            {
                sb.Append('\\', backslashes);
                backslashes = 0;
                sb.Append(c);
            }
        }
        sb.Append('\\', backslashes * 2);
        sb.Append('"');
        return sb.ToString();
    }
}
