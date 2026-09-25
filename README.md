# luo mic

> 把**安卓手机的麦克风**通过 **同一个局域网** 接入 **Windows 电脑**：手机当无线麦克风用。
> 电脑端自动扫描局域网、手机连过一次以后**自动重连**，锁屏也不断。

```
┌─────────────┐        同一局域网（Wi-Fi / 热点 / 网线）        ┌──────────────────┐
│  Android    │  UDP 47777  ← 电脑每 2 秒广播“我在这里”         │  Windows 电脑端   │
│  luo mic    │  UDP 47777  → 手机探测，电脑立刻应答            │  （服务端）        │
│  （客户端）  │  TCP 47778  ⇄ 控制：HELLO/START/STOP/PING/STAT  │  luo-mic.jar     │
│             │  TCP 47779  → 音频：长度前缀 + 16bit PCM        │  或 Python 版     │
└─────────────┘                                               └──────────────────┘
                                                                       ↓
                                                        扬声器 / 耳机 / 虚拟声卡
                                                        （虚拟声卡 → 微信、QQ、游戏、
                                                          Discord、直播软件都能选它）
```

---

## ⬇️ 直接下载（推荐，不用装 Java）

到 [Releases](../../releases) 下载 **`luo-mic-1.0.0-win64.exe`**（约 21 MB）：

- **自带 Java 运行时**，目标电脑不用装任何东西，双击即用
- 首次运行自动解压内置运行时到 `%LOCALAPPDATA%\luo-mic\`（约 1 秒）
- 手机端装同页的 `luo-mic.apk`

```
luo-mic-1.0.0-win64.exe --list-devices    :: 列出播放设备（排查"没声音"）
luo-mic-1.0.0-win64.exe --install         :: 放行防火墙 + 开机自启
luo-mic-1.0.0-win64.exe --uninstall       :: 撤销设置
```

> 没有购买商业代码签名证书，Windows 可能提示「已保护你的电脑」——
> 点「更多信息」→「仍要运行」。可用 `SHA256SUMS.txt` 校验文件。

想自己改代码重新打包 exe，见 [`build/`](build/)（需要 JDK 17+）。

---

## 🚀 完全不会用命令行？照这个做（两次双击）

### 第 1 步：电脑端（双击一次）

打开文件夹 `luo-mic\windows\tools\`，**双击 `start-luo-mic.bat`**。

脚本会自动完成：找 Java → 编译 → 弹出 luo mic 窗口（第一次约 10 秒）。

窗口出来后：

1. 点右下角 **「放行防火墙」**，弹出蓝色 UAC 窗口点 **「是」**（只需一次）
2. 「播放到」选择你的**耳机 / 音箱**
3. 点 **「启动服务」**，状态变成 `等待手机连接…`

> 没装 Java 也能用：脚本会自己下载一个便携版 JDK 17，不影响你已有的 Java。

### 第 2 步：手机端 APK（双击一次）

打开同一个文件夹，**双击 `build-apk.bat`**，然后等（第一次要下载，10~30 分钟；以后每次只要 1 分钟）：

- 自动找/装 Java
- 自动下载 Android 命令行工具 + SDK 组件（约 400MB）
- 自动下载 Gradle（国内走腾讯云镜像，实测 10MB/s）
- 编译完成后**把 `luo-mic.apk` 直接放到你的桌面**

### 第 3 步：装到手机

1. 把桌面上的 `luo-mic.apk` 传到手机（**数据线**、**微信文件传输助手**、**QQ** 都行）
2. 手机上点开它安装，提示「未知来源应用」时选 **允许**
3. 打开 luo mic → 授予**麦克风**权限 → 点 **「开始」**

几秒后：电脑窗口显示 `已连接，正在传输`，手机显示 `正在传输` 并且有一条跳动的电平条 —— **搞定了**。

> 连不上？先看下面的「排障」表；想验证是不是网络问题，可以在 `windows\tools` 里运行
> `powershell -ExecutionPolicy Bypass -File pc-build.ps1 -SelfTest 8` 一键自检（不需要手机、不需要声卡）。

### 第 4 步（可选）：让微信 / QQ / 游戏把手机当麦克风

1. 到 <https://vb-audio.com/Cable/> 下载 VB-CABLE，解压后把 `VBCABLE_Setup_x64.exe` 放进 `luo-mic\windows\tools\`
2. 双击 `install-vbcable.bat`（管理员确认）→ **重启电脑**
3. luo mic 窗口里「播放到」改成 **CABLE Input (VB-Audio Virtual Cable)**
4. 在微信/QQ/Discord/游戏里，把**麦克风**设成 **CABLE Output (VB-Audio Virtual Cable)**

---

## 目录结构

```
luo-mic/
├── README.md                    ← 你正在看的文件
├── docs/
│   ├── PROTOCOL.md              通信协议（三端唯一权威定义）
│   └── BUILD-ANDROID.md         安卓端编译与安装说明（进阶）
├── android/                     安卓端（Java，零第三方依赖）
│   ├── gradlew / gradlew.bat    自举构建脚本：首次运行自动下载 Gradle
│   └── app/src/main/java/com/luomic/app/
│       ├── MainActivity.java    界面：一键开始/停止、状态、电平条、设置
│       ├── MicService.java      前台服务：连接状态机、自动重连、音频推流
│       ├── MicCapture.java      麦克风采集（AudioRecord，按帧读 PCM）
│       ├── Discovery.java       UDP 广播发现（主动探测 + 被动监听）
│       └── Proto.java           协议常量与工具
├── windows/
│   ├── java/                    电脑端 Java 版（推荐，免安装依赖）
│   │   └── src/com/luomic/pc/
│   │       ├── Main.java        入口（含 --install / --list-devices 等）
│   │       ├── MainWindow.java  Swing 界面
│   │       ├── Server.java      发现广播 + 控制/音频监听 + 会话管理
│   │       ├── AudioPlayer.java 音频输出（抖动缓冲、设备枚举）
│   │       ├── Win.java         防火墙 / 开机自启 / VB-CABLE 集成
│   │       └── Proto.java       协议常量与工具
│   ├── python/                  电脑端 Python 版（需要 pip install sounddevice）
│   │   ├── luomic.py            协议与服务器核心
│   │   ├── luomic_gui.py        tkinter 界面（python luomic_gui.py）
│   │   └── phone_sim.py         假手机模拟器（没手机时自检用）
│   └── tools/                   ★ Windows 一键脚本都在这
│       ├── start-luo-mic.bat    ★ 双击：编译并打开电脑端
│       ├── build-apk.bat        ★ 双击：编译 APK 并复制到桌面
│       ├── firewall.bat         一键放行防火墙（管理员）
│       ├── install-vbcable.bat  一键安装虚拟声卡（管理员，可选）
│       ├── build-windows.bat    只用 JDK 命令行编译（进阶）
│       └── run.bat              编译并运行（进阶）
├── packaging/                   ★ 打包成「单文件免 Java exe」
│   ├── build-exe.ps1            一条命令走完 javac→jar→jpackage→csc
│   ├── Launcher.cs              自解压启动器（把运行时嵌进 exe）
│   ├── AssemblyInfo.cs          exe 的版本 / 公司等元数据
│   ├── make-icon.ps1            生成多尺寸图标 luo-mic.ico
│   ├── luo-mic.ico              应用图标
│   └── verify-exe.ps1           验证 exe（无 Java 环境、全新解压）
└── tools/java/                  测试工具
    └── com/luomic/test/
        ├── SelfTest.java        一键自检（无需手机、无需声卡）
        ├── PhoneSim.java        假手机模拟器（Java 版）
        └── HeadlessServer.java  无界面服务端（排查用）
```

---

## 打包成单文件 exe（发布用）

目标电脑**不需要装 Java**：exe 里嵌了一个精简过的 Java 运行时（21 MB）。

```powershell
# 需要 JDK 17+（带 jpackage/jlink），会自动寻找
powershell -ExecutionPolicy Bypass -File packaging\build-exe.ps1

# 产物：dist\release\luo-mic-1.0.0-win64.exe
```

流水线：`javac` 编译 → `jar` 打包 → `jpackage` 生成含精简运行时的
app-image → 压缩为内嵌载荷 → `csc` 编译自解压启动器。

首次运行解压到 `%LOCALAPPDATA%\luo-mic\app\<版本>\`，之后直接复用，
所以开机自启与防火墙规则指向的是稳定路径。

验证（会临时清掉 JAVA_HOME/PATH，确认真的不依赖系统 Java）：

```powershell
powershell -ExecutionPolicy Bypass -File packaging\verify-exe.ps1
```

---

## 手动构建（会用命令行的人看这里）

**电脑端 Java 版**

```bat
cd windows\tools
build-windows.bat                 :: 需要 JDK 17+，生成 windows\build\luo-mic.jar
java -jar ..\build\luo-mic.jar
```

**电脑端 Python 版**

```bat
pip install sounddevice
cd windows\python
python luomic_gui.py
```

**安卓端**

```bat
cd android
gradlew.bat assembleDebug
:: 产物 app\build\outputs\apk\debug\luo-mic-debug.apk
```

> `gradlew.bat` / `gradlew` 是自举脚本：仓库不放二进制 wrapper jar，
> 首次运行会从腾讯云镜像（失败自动换阿里云、官方源）下载 Gradle 8.7 到 `~/.gradle/luomic-gradle/`，之后离线复用。
> 用 Android Studio 打开 `android/` 目录也可以，Studio 会用自带的 Gradle。

---


## 🥇 推荐：Windows 原生版（不需要 Java，对虚拟声卡支持最好）

`windows\native\` 是**用 Windows 自己的音频 API（WASAPI）写的电脑端**。
如果你的 Windows 上装了 Java 版却听不到声音、或者双击 jar 报错，**直接用它**。

```bat
双击 windows\tools\start-luo-mic-native.bat
```

它会自动：
- 列出所有播放设备，**优先选中 VB-CABLE 虚拟声卡**（这样就是"手机当麦克风"的效果）
- 监听局域网等待手机连接，连上后自动开始接收
- 实时显示码率、帧数、电平

其它用法：

```bat
start-luo-mic-native.bat -ListDevices        :: 只列出音频设备
start-luo-mic-native.bat -Device "CABLE"     :: 指定输出设备（支持部分匹配）
start-luo-mic-native.bat -NoAutoStart        :: 手机连上后不自动推流
windows\tools\诊断-音频设备.bat              :: 排查"没声音"
windows\tools\诊断-系统声音设备.bat          :: 用 Windows 自带命令看设备状态
```

> 协议与 Java 版、安卓端完全一致（见 `docs/PROTOCOL.md`），可以混用。
> 网络部分（`luomic-protocol.ps1`）已做过端到端验证：假手机推流 400 帧 / 50fps / 768kbps 全部正确接收。

---

## ⚡ 最快上手：已经编译好的程序

`windows/build/luo-mic.jar` 是**已经编译好并验证过**的电脑端程序（Java 8 字节码，
Java 8/11/17/21 都能直接运行）。只要电脑装了 Java：

```bat
双击 windows\build\luo-mic.jar
```

没装 Java 就双击 `windows\tools\start-luo-mic.bat`，它会自动下载便携版 JDK 再启动。

---

## 🎤 让它像 WO Mic 一样出现在麦克风列表里

**先说清原理**：Windows 只允许内核级驱动注册"麦克风"设备。WO Mic 开箱即用是因为它自带驱动；
本项目用免费的 **VB-CABLE** 驱动达到同样效果 —— 装完后微信/QQ/Discord 的麦克风列表里
会多出 `CABLE Output`，选它，对方听到的就是你手机麦克风的声音。

程序里已经把这套流程做成**一键**：

1. 运行 luo mic → 点左下角 **「安装虚拟声卡」** → 它会自动从 vb-audio.com 下载驱动包并安装（弹 UAC 点"是"）
2. **重启电脑**（驱动需要）
3. 「播放到」选 **CABLE Input (VB-Audio Virtual Cable)**
4. 微信/QQ/Discord 里的麦克风选 **CABLE Output (VB-Audio Virtual Cable)**

详细图文步骤 + 常见问题见 `docs/虚拟麦克风设置.md`。

> 不想装驱动也能用：直接选扬声器/耳机，手机变成电脑的无线扩音器（只是任何软件都拿不到麦克风输入，
> 这是 Windows 的限制，不是实现问题）。

---

## 变成“电脑麦克风”（在微信 / QQ / 游戏 / 直播里用）

手机音频是一路普通的播放设备，要让**其它软件把手机当麦克风**，需要一块虚拟声卡把播放端接到录制端：

1. 下载免费的 **VB-CABLE**：<https://vb-audio.com/Cable/>
2. 把 `VBCABLE_Setup_x64.exe` 放到 `windows\tools\`，双击 `install-vbcable.bat`（或直接用界面上的“安装虚拟声卡”按钮）
3. **重启电脑**（驱动要重启才生效）
4. luo mic 里“播放到”选 **CABLE Input (VB-Audio Virtual Cable)**
5. 在微信/QQ/Discord/游戏里，把**麦克风**设成 **CABLE Output (VB-Audio Virtual Cable)**

> 想自己同时听到声音：Windows「声音设置 → 录制 → CABLE Output → 属性 → 侦听 → 勾选“侦听此设备”」。
>
> 不想装驱动也完全能用：直接选扬声器/耳机，手机就变成电脑的无线扩音器。

### 以后每次怎么用？

- 电脑端：双击 `windows\tools\start-luo-mic.bat`（或已经生成的 `windows\build\luo-mic.jar`），点「启动服务」。
  也可以在窗口里勾上「开机自动启动」（走 HKCU\Run，不需要管理员）。
- 手机端：打开 App 点「开始」。它会自己连上电脑 —— **不用重新配对**。

---

## 参数与调优

| 项目 | 默认 | 说明 |
| --- | --- | --- |
| 采样率 | 48 kHz | 手机设置里可切 16 kHz：码率从 768 kbps 降到 256 kbps，弱网更稳 |
| 帧长 | 20 ms | 越小延迟越低、抗抖动越差 |
| 电脑缓冲 | ~60–120 ms | 队列空补静音、积压超 240ms 丢最旧帧，避免延迟越滚越大 |
| 端到端延迟 | 约 1 帧长 + 缓冲 | 实测局域网内 **10~15 ms**（控制通道心跳 RTT 显示在电脑界面） |

网络要求：普通家用 Wi-Fi（2.4G/5G 都行）即可，768 kbps 带宽占用很低。
如果出现断续：把路由器的“AP 隔离/客户端隔离”关掉（该功能会阻止手机与电脑互访），或改用 5GHz 频段。

## 排障

| 现象 | 处理 |
| --- | --- |
| 电脑显示“等待手机连接”，手机一直“正在扫描电脑…” | ①两台设备是否同一网段（手机看 Wi-Fi 是否走了流量）；②电脑防火墙是否放行（点界面“放行防火墙”）；③路由器是否开了 AP/客户端隔离 |
| 手机显示连接失败、电脑日志有连接记录 | 电脑端音频设备被占用（关掉占用独占模式的播放软件）；或“暂停接收”被选中 |
| 有连接、有码率，但没声音 | 电脑端“播放到”选错设备；系统音量/静音；选了 CABLE Input 时要从目标软件或“侦听”里听 |
| 声音断续、卡顿 | 手机设置里切 16 kHz；靠近路由器；关掉手机的省电模式（部分机型会限制 Wi-Fi 休眠） |
| 端口被占用（启动失败） | 已有另一个 luo mic 在跑；或 `netstat -ano \| findstr 4777` 查出占用进程 |
| 想确认是不是网络问题 | 电脑端保留服务运行，另开命令行跑 `python windows\python\phone_sim.py --host <电脑IP> --seconds 10`（假手机模拟器） |

## 自检（没有手机 / 没有声卡也能验证）

```bat
:: Java：服务端 + 假手机模拟器，全程自检
java -cp "build\classes;build\test-classes" com.luomic.test.SelfTest 8

:: 列出所有能用的播放设备（确认虚拟声卡是否被识别）
java -jar build\luo-mic.jar --list-devices
python windows\python\luomic_gui.py --list-devices
```

本仓库交付前已完成的验证：

- Windows 端 Java（含测试工具）编译 0 错误
- 安卓端 Java 全部源码编译 0 错误
- Java 服务端 ↔ Java 假手机：**400 帧 / 50 fps / 768 kbps / 0 丢帧 / 0 补静音**
- Java 服务端 ↔ Python 假手机：**400 帧通过**
- Python 服务端 ↔ Python 假手机：**400 帧通过**，实测延迟 10~15 ms

## 协议

三端共用 `docs/PROTOCOL.md`：UDP 47777 发现（电脑 2 秒周期广播 + 应答探测）、
TCP 47778 控制（行分隔 JSON）、TCP 47779 音频（4 字节大端长度前缀 + PCM）。
改动任何一端都必须同步另外两端。
