luo mic · Windows 工具目录
==========================

★ 最常用的两个（双击就行）
--------------------------------------------------------------
start-luo-mic.bat     【第 1 步】编译并打开电脑端窗口
                      自动找 Java（没有就自动下载便携版 JDK 17）
                      首次约 10 秒；以后秒开
                      进阶参数：
                        powershell -ExecutionPolicy Bypass -File pc-build.ps1 -SelfTest 8
                            自检 8 秒（不需要手机、不需要声卡）
                        powershell -ExecutionPolicy Bypass -File pc-build.ps1 -NoRun
                            只编译，不启动
                        powershell -ExecutionPolicy Bypass -File pc-build.ps1 -Force
                            强制重新编译

build-apk.bat        【第 2 步】编译安卓 APK，完成后自动复制到桌面
                      首次 10~30 分钟（要下载 Android SDK 约 400MB + Gradle）
                      以后每次约 1 分钟
                      进阶参数：
                        ... -File build-apk.ps1 -Clean          先清理再编译
                        ... -File build-apk.ps1 -SdkDir D:\Android\Sdk   用已有 SDK
                        ... -File build-apk.ps1 -NoDesktopCopy  不复制到桌面

其他脚本
--------------------------------------------------------------
firewall.bat            放行防火墙端口（UDP 47777 / TCP 47778-47779），需要管理员权限
firewall-remove.bat     删除上面的防火墙规则
install-vbcable.bat     安装 VB-CABLE 虚拟声卡（把手机变成电脑麦克风），需要管理员权限
uninstall-vbcable.bat   卸载 VB-CABLE
build-windows.bat       只用 JDK 命令行编译出 windows\build\luo-mic.jar（进阶）
run.bat                 编译并运行（进阶）

关于虚拟声卡
--------------------------------------------------------------
“让电脑里的软件把手机当麦克风”依赖一块虚拟声卡，推荐免费的 VB-CABLE：
    官网：https://vb-audio.com/Cable/
本仓库不附带该驱动（它是第三方软件）。下载解压后，把 VBCABLE_Setup_x64.exe
放到本目录，然后双击 install-vbcable.bat 即可静默安装（装完需要重启电脑）。

装好后：luo mic 的“播放到”选 CABLE Input；
        微信/QQ/Discord/游戏里的麦克风选 CABLE Output。

没有虚拟声卡也能用：直接把手机声音从电脑音箱/耳机放出来。

出问题了怎么办
--------------------------------------------------------------
1. 电脑端窗口里能看到全部日志，先看那里写了什么；
2. 在命令行跑一次自检（不依赖手机与声卡）：
     powershell -ExecutionPolicy Bypass -File pc-build.ps1 -SelfTest 8
   出现“结论：通过”说明电脑端本身没问题，问题在网络/防火墙/手机端；
3. 想确认是不是网络问题，用假手机模拟器：
     cd ..\python
     python phone_sim.py --host 你的电脑IP --seconds 10
4. 安卓端编译失败：先把整个 luo-mic 文件夹移到 C:\luo-mic（纯英文路径）再试。
