# =====================================================================
#  luo mic - build a single-file Windows exe (no Java required on the
#  target machine) and lay out a GitHub release folder.
#
#  Pipeline:
#    1. javac  -> rebuild the PC-side classes from source
#    2. jar    -> single application jar
#    3. jpackage (type=app-image) -> bundle a trimmed Java runtime
#    4. zip    -> compress that app-image into an embedded payload
#    5. csc    -> compile the self-extracting launcher with the payload
#                 embedded as a resource, plus icon and version metadata
#
#  Requirements: a JDK 17+ that ships jpackage/jlink (e.g. Adoptium) and
#  the .NET Framework C# compiler that is present on every Windows.
#
#  ASCII-only on purpose: Windows PowerShell 5.1 decodes BOM-less files as
#  ANSI, where non-ASCII bytes can swallow the trailing newline and corrupt
#  the script.
# =====================================================================
[CmdletBinding()]
param(
    [string]$Jdk       = '',
    [string]$Version   = '1.0.0',
    [string]$RepoRoot  = '',
    [string]$OutDir    = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Say($m, $c = 'Gray') { Write-Host $m -ForegroundColor $c }
function Fail($m) { Write-Host ''; Write-Host "[FAIL] $m" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- paths

if (-not $RepoRoot) { $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }
if (-not $OutDir)   { $OutDir   = Join-Path $RepoRoot 'dist' }

$WinDir = Join-Path $RepoRoot 'windows'
$SrcDir = Join-Path $WinDir 'java\src'
$Build  = Join-Path $WinDir 'build'

if (-not (Test-Path $SrcDir)) { Fail "source not found: $SrcDir" }

# ---------------------------------------------------------------- locate a JDK 17+ with jpackage

function Get-Major([string]$dir) {
    $javac = Join-Path $dir 'bin\javac.exe'
    if (-not (Test-Path $javac)) { return 0 }
    try {
        $o = & $javac -version 2>&1 | Out-String
        if ($o -match 'javac\s+(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}

function Find-Jdk {
    $c = New-Object System.Collections.ArrayList
    if ($Jdk) { [void]$c.Add($Jdk) }
    if ($env:JAVA_HOME) { [void]$c.Add($env:JAVA_HOME) }
    $cmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($cmd) { [void]$c.Add((Split-Path -Parent (Split-Path -Parent $cmd.Source))) }
    $roots = @(
        "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Microsoft\jdk", "$env:ProgramFiles\Amazon Corretto",
        "$env:ProgramFiles\Zulu", "$env:ProgramFiles\BellSoft",
        "${env:ProgramFiles(x86)}\Java", "$env:LOCALAPPDATA\Programs\Eclipse Adoptium",
        (Join-Path $env:LOCALAPPDATA 'luo-mic\jdk')
    )
    foreach ($r in $roots) {
        if (-not (Test-Path $r)) { continue }
        foreach ($d in (Get-ChildItem -Path $r -Directory -ErrorAction SilentlyContinue)) {
            [void]$c.Add($d.FullName)
        }
    }
    foreach ($x in $c) {
        if ($x -and (Get-Major $x) -ge 17 -and (Test-Path (Join-Path $x 'bin\jpackage.exe'))) {
            return $x
        }
    }
    return $null
}

$JdkHome = Find-Jdk
if (-not $JdkHome) {
    Fail 'no JDK 17+ with jpackage found. Install one from https://adoptium.net/ (full JDK, not JRE).'
}
Say ("JDK : $JdkHome (Java " + (Get-Major $JdkHome) + ")") Green

$Javac    = Join-Path $JdkHome 'bin\javac.exe'
$JarTool  = Join-Path $JdkHome 'bin\jar.exe'
$JPackage = Join-Path $JdkHome 'bin\jpackage.exe'

$Csc = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $Csc)) { $Csc = 'C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe' }
if (-not (Test-Path $Csc)) { Fail 'csc.exe (.NET Framework compiler) not found.' }

$Assets = $PSScriptRoot
$Icon   = Join-Path $Assets 'luo-mic.ico'

$Work = Join-Path $OutDir '_build'
if (Test-Path $Work) { Remove-Item $Work -Recurse -Force }
New-Item -ItemType Directory -Path $Work -Force | Out-Null

# ---------------------------------------------------------------- 1. compile

Say ''
Say '=== 1/5  compile the PC side ==='
$Classes = Join-Path $Work 'classes'
New-Item -ItemType Directory -Path $Classes -Force | Out-Null
$sources = Get-ChildItem -Path $SrcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if (-not $sources) { Fail "no .java under $SrcDir" }
# Pass sources directly - javac reads @argfiles using the platform charset, which
# breaks on non-ASCII paths (MalformedInputException).
& $Javac -encoding UTF-8 -nowarn -d $Classes $sources 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { Fail 'javac failed.' }
Say ("  compiled " + $sources.Count + " source files") Green

# ---------------------------------------------------------------- 2. jar

Say ''
Say '=== 2/5  package the application jar ==='
$Input = Join-Path $Work 'input'
New-Item -ItemType Directory -Path $Input -Force | Out-Null
$AppJar = Join-Path $Input 'luo-mic.jar'
& $JarTool --create --file $AppJar --main-class com.luomic.pc.Main -C $Classes . 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'jar failed.' }
Say ("  " + [math]::Round((Get-Item $AppJar).Length / 1KB, 1) + " KB") Green

# ---------------------------------------------------------------- 3. jpackage

Say ''
Say '=== 3/5  bundle a trimmed Java runtime (jpackage app-image) ==='
$ConsoleProps = Join-Path $Work 'console-launcher.properties'
Set-Content -LiteralPath $ConsoleProps -Encoding ASCII -Value @(
    'main-jar=luo-mic.jar',
    'main-class=com.luomic.pc.Main',
    'win-console=true'
)
$AppImageRoot = Join-Path $Work 'app-image'
$jpArgs = @(
    '--type', 'app-image',
    '--name', 'luo mic',
    '--app-version', $Version,
    '--input', $Input,
    '--main-jar', 'luo-mic.jar',
    '--main-class', 'com.luomic.pc.Main',
    '--add-modules', 'java.base,java.desktop,java.logging,java.prefs,java.xml,jdk.unsupported',
    '--dest', $AppImageRoot,
    '--vendor', 'luoyyyxxxx',
    '--description', 'luo mic - use your Android phone as a Windows microphone',
    '--add-launcher', "luo-mic-console=$ConsoleProps"
)
if (Test-Path $Icon) { $jpArgs += @('--icon', $Icon) }
& $JPackage @jpArgs 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { Fail 'jpackage failed.' }
$AppImage = Join-Path $AppImageRoot 'luo mic'
if (-not (Test-Path $AppImage)) { Fail "app-image missing: $AppImage" }
$imgSize = (Get-ChildItem $AppImage -Recurse -File | Measure-Object -Property Length -Sum).Sum
Say ("  app-image " + [math]::Round($imgSize / 1MB, 1) + " MB") Green

# ---------------------------------------------------------------- 4. payload zip

Say ''
Say '=== 4/5  compress the payload ==='
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Payload = Join-Path $Work 'payload.zip'
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $AppImage, $Payload, [System.IO.Compression.CompressionLevel]::Optimal, $false)
Say ("  payload " + [math]::Round((Get-Item $Payload).Length / 1MB, 1) + " MB") Green

# ---------------------------------------------------------------- 5. launcher

Say ''
Say '=== 5/5  build the single-file launcher ==='
$ExeName = "luo-mic-$Version-win64.exe"
$Exe = Join-Path $Work $ExeName
$cscArgs = @(
    '/nologo', '/target:exe', '/platform:x64', '/optimize+',
    "/out:$Exe",
    "/resource:$Payload,payload",
    '/reference:System.IO.Compression.dll,System.IO.Compression.FileSystem.dll'
)
if (Test-Path $Icon) { $cscArgs += "/win32icon:$Icon" }
$cscArgs += @(
    (Join-Path $Assets 'AssemblyInfo.cs'),
    (Join-Path $Assets 'Launcher.cs')
)
& $Csc @cscArgs 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Exe)) { Fail 'csc failed.' }
Say ("  " + $ExeName + "  " + [math]::Round((Get-Item $Exe).Length / 1MB, 1) + " MB") Green

# ---------------------------------------------------------------- release folder

Say ''
Say '=== laying out the release folder ==='
$Release = Join-Path $OutDir 'release'
if (Test-Path $Release) { Remove-Item $Release -Recurse -Force }
New-Item -ItemType Directory -Path $Release -Force | Out-Null
Copy-Item -LiteralPath $Exe -Destination $Release -Force

$hash = (Get-FileHash -LiteralPath $Exe -Algorithm SHA256).Hash.ToLower()
Set-Content -LiteralPath (Join-Path $Release ($ExeName + '.sha256')) -Encoding ASCII -Value "$hash  $ExeName"

# keep the sources needed to reproduce this build next to the binary
$tools = Join-Path $Release 'build'
New-Item -ItemType Directory -Path $tools -Force | Out-Null
foreach ($f in @('build-exe.ps1', 'Launcher.cs', 'AssemblyInfo.cs', 'make-icon.ps1', 'luo-mic.ico')) {
    $p = Join-Path $Assets $f
    if (Test-Path $p) { Copy-Item -LiteralPath $p -Destination $tools -Force }
}

Say ''
Say '============================================' Cyan
Say ("  exe    : " + (Join-Path $Release $ExeName)) Cyan
Say ("  size   : " + [math]::Round((Get-Item $Exe).Length / 1MB, 2) + " MB") Cyan
Say ("  sha256 : " + $hash) Cyan
Say ("  release: " + $Release) Cyan
Say '============================================' Cyan
