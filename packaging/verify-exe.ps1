# Verify a freshly built single-file exe end to end.
# ASCII-only.
param(
    [string]$Exe = '',
    [string]$Version = '1.0.0'
)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if (-not $Exe) { $Exe = Join-Path (Split-Path -Parent $PSScriptRoot) 'dist\release\luo-mic-1.0.0-win64.exe' }
if (-not (Test-Path -LiteralPath $Exe)) { Write-Output "exe not found: $Exe"; exit 1 }
Write-Output ("exe   : " + $Exe)
Write-Output ("size  : " + [math]::Round((Get-Item -LiteralPath $Exe).Length / 1MB, 2) + " MB")

# fresh copy on an ASCII path, and force a fresh extraction
$t = Join-Path $env:USERPROFILE 'luo-mic-verify'
if (Test-Path -LiteralPath $t) { Remove-Item -LiteralPath $t -Recurse -Force }
New-Item -ItemType Directory -Path $t -Force | Out-Null
$copy = Join-Path $t 'luo-mic.exe'
Copy-Item -LiteralPath $Exe -Destination $copy -Force

$appDir = Join-Path $env:LOCALAPPDATA ("luo-mic\app\" + $Version)
if (Test-Path -LiteralPath $appDir) { Remove-Item -LiteralPath $appDir -Recurse -Force }

# no Java at all
$env:JAVA_HOME = $null
$env:PATH = 'C:\Windows\System32;C:\Windows;C:\Windows\System32\Wbem'

Write-Output ("java on PATH        : " + [bool](Get-Command java -ErrorAction SilentlyContinue))
Write-Output ("extracted before    : " + (Test-Path -LiteralPath $appDir))

Write-Output ''
Write-Output '--- run 1: --list-devices (fresh extraction) ---'
$sw = [System.Diagnostics.Stopwatch]::StartNew()
& $copy --list-devices 2>&1 | Out-String
$rc1 = $LASTEXITCODE
$sw.Stop()
Write-Output ("exit " + $rc1 + "   " + [math]::Round($sw.Elapsed.TotalSeconds, 1) + "s")
Write-Output ("extracted after     : " + (Test-Path -LiteralPath $appDir))

Write-Output ''
Write-Output '--- run 2: --list-devices (already extracted) ---'
$sw2 = [System.Diagnostics.Stopwatch]::StartNew()
& $copy --list-devices 2>&1 | Out-Null
$rc2 = $LASTEXITCODE
$sw2.Stop()
Write-Output ("exit " + $rc2 + "   " + [math]::Round($sw2.Elapsed.TotalSeconds, 1) + "s")

Write-Output ''
if ((Test-Path -LiteralPath $appDir) -and $rc1 -eq 0 -and $rc2 -eq 0) {
    Write-Output 'RESULT: PASS'
} else {
    Write-Output 'RESULT: FAIL'
}
