@echo off
rem  List every sound device via built-in Windows commands - for driver issues
rem  ASCII-only on purpose: see the note in firewall.bat
chcp 65001 >nul
echo ===== All sound devices (including disabled) =====
powershell -NoProfile -Command "Get-PnpDevice -Class 'AudioEndpoint','Media' -ErrorAction SilentlyContinue | Select-Object Status,FriendlyName | Format-Table -AutoSize | Out-String -Width 200"
echo ===== Current default playback / recording devices =====
powershell -NoProfile -Command "Get-CimInstance Win32_SoundDevice | Select-Object Name,Status | Format-Table -AutoSize | Out-String -Width 200"
echo.
pause >nul
