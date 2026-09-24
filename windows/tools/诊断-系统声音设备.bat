@echo off
rem 用 Windows 自带命令列出所有声音设备（含被禁用的），排查驱动问题用
chcp 65001 >nul
echo ===== 所有声音设备（含未启用）=====
powershell -NoProfile -Command "Get-PnpDevice -Class 'AudioEndpoint','Media' -ErrorAction SilentlyContinue | Select-Object Status,FriendlyName | Format-Table -AutoSize | Out-String -Width 200"
echo ===== 当前默认播放/录音设备 =====
powershell -NoProfile -Command "Get-CimInstance Win32_SoundDevice | Select-Object Name,Status | Format-Table -AutoSize | Out-String -Width 200"
echo.
pause >nul
