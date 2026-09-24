# 安卓端：编译、安装、上手

## 1. 环境要求

| 项目 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17（Android Gradle Plugin 8.x 要求） | <https://adoptium.net/> |
| Android SDK | Platform 34 + Build-Tools 34 | Android Studio 自带，或命令行 `sdkmanager` |
| Gradle | 无需手动装 | 仓库自带 **自举脚本** `gradlew` / `gradlew.bat`，首次运行自动下载 Gradle 8.7 到 `~/.gradle/luomic-gradle/` |
| 手机 | Android 7.0（API 24）及以上 | 需要麦克风与 Wi-Fi |

> 为什么没有 `gradle-wrapper.jar`？仓库不存放二进制文件，且官方 wrapper jar 需要与发行包配套。
> 因此 `gradlew` / `gradlew.bat` 改为自举：首次运行从官方（含备用镜像）下载官方 Gradle 发行包并解压缓存，
> 之后完全离线复用。**用 Android Studio 打开本工程也可以**，Studio 会用它自带的 Gradle。

本工程**不依赖任何第三方库**（只用 Android SDK 自带 API），所以第一次构建只需要联网下载 Gradle 与 Android Gradle Plugin。

## 2. 编译

```bat
cd android

:: 首次需要指定 SDK 位置（两种方式任选其一）
::   方式 A：设置环境变量
set ANDROID_HOME=C:\Users\你的用户名\AppData\Local\Android\Sdk
::   方式 B：在 android\ 下新建 local.properties，内容为
::        sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk

gradlew.bat assembleDebug
```

产物：`android\app\build\outputs\apk\debug\luo-mic-debug.apk`

国内网络慢的话，把 `android/settings.gradle.kts` 里两处阿里云镜像的注释打开即可：

```kotlin
// maven("https://maven.aliyun.com/repository/gradle-plugin")
// maven("https://maven.aliyun.com/repository/public")
```

### 直接装到已连接的手机

```bat
gradlew.bat installDebug
:: 或
adb install -r app\build\outputs\apk\debug\luo-mic-debug.apk
```

### 发布版（可选）

在 `android/` 下新建 `keystore.properties`：

```properties
storeFile=luomic.jks
storePassword=你的密码
keyAlias=luomic
keyPassword=你的密码
```

然后 `gradlew.bat assembleRelease`，产物文件名同样是 `luo-mic-release.apk`。
没有这个文件时，release 也会用 debug 签名，方便你自己装机测试。

## 3. 手机端权限

App 会申请：

- **麦克风**（必需）：采集音频
- **通知**（Android 13+，可选）：显示前台服务状态
- 前台服务 / 唤醒锁 / Wi-Fi 锁：锁屏后台继续传输（清单里已声明，不需要用户确认）

部分国产 ROM 还需要手动设置，否则息屏一段时间后被系统杀进程：

- **设置 → 应用 → luo mic → 省电策略 → 无限制 / 允许后台活动**
- **设置 → 应用 → luo mic → 自启动 → 打开**
- 最近任务里给 luo mic **加锁**（防止一键清理）

## 4. 使用流程

1. 手机与电脑连同一个 Wi-Fi（或手机开热点让电脑连）。
2. 电脑端先“启动服务”。
3. 手机打开 luo mic → **开始**：
   - 状态显示 `正在扫描电脑…` → `正在连接…` → `正在传输`
   - 出现电平条跳动、右上角显示 `768 kbps · 50 fps`
4. 连接成功后地址写入本地（SharedPreferences）：
   - 下次打开 App 会**先直连上次的电脑**（超时 3 秒）
   - 直连失败会自动回到扫描模式；连续失败 3 次会丢弃旧地址重新扫描
   - 电脑换了 IP、手机换了 Wi-Fi 都能自己找回来
5. 设置里可以：
   - 切换 **48 kHz / 16 kHz**（重新“开始”后生效）
   - 开关 **推流**（关掉后仍保持连接，但不会打开手机麦克风）

## 5. 代码结构速览

| 文件 | 职责 |
| --- | --- |
| `MainActivity.java` | 界面：开始/停止、状态卡片、电平条、设置对话框、权限申请 |
| `MicService.java` | 前台服务：完整连接状态机（扫描→连接→握手→推流→断线退避重连）、音频发送、通知栏、唤醒锁 |
| `MicCapture.java` | `AudioRecord` 采集，`VOICE_RECOGNITION` 音源（原声、不降噪），按 20ms 帧读出，附带 dBFS 电平 |
| `Discovery.java` | 绑 47777 端口：被动监听电脑广播 + 每 2 秒主动探测；同时向 `255.255.255.255` 和各网段定向广播地址发送 |
| `Proto.java` | 协议常量、极简 JSON 读写、Base64 |

## 6. 常见问题

| 现象 | 处理 |
| --- | --- |
| 一直“正在扫描电脑…” | 见根目录 README 排障表（同网段 / 防火墙 / AP 隔离） |
| 连接后没声音、电脑日志正常 | 电脑端“播放到”设备是否选对；系统音量；是否选了 CABLE Input 而没做侦听 |
| 息屏一会儿就断 | 按第 3 节设置省电策略与自启动；App 已用前台服务 + `WifiLock(HIGH_PERF)` + `PARTIAL_WAKE_LOCK` |
| 提示“麦克风启动失败” | 有其它 App 正在占用麦克风（通话、录音机、语音助手），关掉后重试 |
| 电脑端显示“心跳超时” | 网络抖动或电脑端被杀；手机端会自动退避重连（1s→10s） |
| 手机端连错电脑 | 设置里切一下“推流”再切回来，或点“重新扫描” |

## 7. 调试

```bat
:: 看 App 日志（TAG 前缀 luo-mic/）
adb logcat -s luo-mic/service luo-mic/discovery luo-mic/capture
```

日志会打印：发现到的电脑、每次连接/重连、握手参数（采样率/帧长）、断线原因与退避重试。
