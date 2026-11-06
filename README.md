# HyperMaterialUnleashed

HyperOS 3 的 LSPosed 模块，做两件互不依赖的事：

1. **高级材质分应用解锁** —— 不开启系统「高级材质」总开关，只让 LSPosed 作用域内勾选的应用（默认系统桌面 `com.miui.home`）获得高级材质。系统属性、`Settings` 数据库、全局开关一律不修改。
2. **把「长按手势提示线 → 超级小爱识屏」暴露成一条 shell 命令** —— 其它 App 发一条广播就能触发，等价于用户长按了一次屏幕底部的小白条。

基于 [libxposed/api](https://github.com/libxposed/api) 现代 API 102，纯后台、无界面、无启动图标。

---

## 安装

| 项目 | 要求 |
| --- | --- |
| 框架 | 支持 libxposed API 102 的 LSPosed（2.1.0+）；旧版 1.x 不加载本模块 |
| 作用域 | 勾选 **系统桌面 `com.miui.home`**（`staticScope=false`，可自行增删） |
| 生效 | 启用模块后**必须重启手机**，换 APK 后同样要重启 |
| 识屏前提 | 系统设置 → 桌面 / 导航方式 → **「长按手势提示线」设为「超级小爱识屏」** |

> 高级材质功能不需要任何额外设置；识屏广播建议在系统里把长按手势提示线指向「超级小爱识屏」，这样默认命令触发的就是识屏。

---

## Shell 命令：触发超级小爱识屏

模块在**系统桌面进程**内注册了一个可跨应用调用的广播接收器。任何 App 只要能执行 `am broadcast`，就能触发一次「长按手势提示线」。

### 主命令（推荐，在手机上由其它 App 执行）

```sh
am broadcast -a com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS
```

这条不带任何限制，一定会被桌面进程里动态注册的接收器收到。

### 可选：限定只投递给桌面

```sh
am broadcast -a com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS -p com.miui.home
```

接收器由 `com.miui.home` 进程注册，`-p com.miui.home` 与它自身包名一致，正常情况下会匹配；**若加上 `-p` 后没有反应，去掉 `-p` 即可**。

### 从电脑 adb 触发 / 调试

```sh
adb shell am broadcast -a com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS -p com.miui.home
```

### 强制走「超级小爱识屏」（不依赖系统里长按手势提示线的设置）

```sh
am broadcast -a com.hyperos.hypermaterial.action.XIAOAI_SCREEN_RECOGNIZE -p com.miui.home
```

等价于给主命令加 `--es target screen_recognize`：

```sh
am broadcast -a com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS --es target screen_recognize -p com.miui.home
```

此模式会从系统配置里取出 `VoiceAssistantScreenRecognizer` 项，让桌面自己构造 Intent，绕过「长按手势提示线」当前的设置。

### 带上来源标记（仅写进日志，方便排查）

```sh
am broadcast -a com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS --es from Tasker -p com.miui.home
```

### 在各类工具里怎么用

| 工具 | 配置 |
| --- | --- |
| Tasker | 「运行 Shell」动作填上面的命令；或用「发送 Intent」：Action = `com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS`，Target = **Broadcast Receiver**，Package 可填 `com.miui.home`（可选，不填更保险） |
| MacroDroid | 动作 → 应用程序 → 「Shell 脚本」，填命令 |
| Termux / 任意终端 | 直接执行 `am broadcast ...`（Termux 需先 `pkg install termux-am` 或直接用 `/system/bin/am`） |
| 自己写的 App | `Runtime.getRuntime().exec("am broadcast -a com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS -p com.miui.home")`，或直接 `sendBroadcast(new Intent(...).setPackage("com.miui.home"))` |
| 快捷方式 / 磁贴类 App | 选择「发送广播」，填入 action 与包名 `com.miui.home` |

**不需要 root**：`am broadcast` 由普通应用 UID 即可发送，接收器已显式导出（Android 13+ 使用 `RECEIVER_EXPORTED`）。

### Action 与参数一览

| 项 | 值 |
| --- | --- |
| 主 action（推荐） | `com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS` |
| 别名 action（强制识屏） | `com.hyperos.hypermaterial.action.XIAOAI_SCREEN_RECOGNIZE` |
| 目标包名 | `com.miui.home`（接收器所在进程） |
| `-p com.miui.home` | 可选，限定只投递给桌面；若不生效请去掉 |
| `--es from <字符串>` | 可选，来源标记，写入 `HM-Unleash` 日志 |
| `--es target screen_recognize` | 可选，等价于强制识屏 action |
| 权限 | 无，任何应用均可发送 |

### 原理与边界

反编译 `com.miui.home` 3.0.3 后确认的长按链路：

```
NavStubGestureEventManager.onLongPress()
  → handleLongPressEvent() → doAction(2)
  → NavShortcutConfigManager.INSTANCE.findGestureTargetItem(2)   # 读系统设置
  → launchService() → buildIntent()
  → new Intent("android.intent.action.ASSIST")
      .setClassName("com.miui.voiceassist", "com.xiaomi.voiceassistant.VoiceService")
  → startForegroundService(intent)
```

模块**不硬编码这些 Intent 参数**，收到广播后直接反射调用桌面的 `handleLongPressEvent()`，因此会自动跟随系统设置与不同 ROM 版本的配置变化。只有该入口不可用时才降级为直接拉起 `VoiceService`。

- 广播接收器在桌面进程内，桌面被强制停止 / 冻结后需重新打开桌面或重启手机。
- 所有反射都延迟到应用就绪之后执行，不会在启动早期触发宿主类的 `<clinit>`（早期曾因此导致桌面无限闪退）。
- 识屏是否真正弹出，取决于系统「超级小爱」版本与识屏开关；模块只负责触发，不接管识屏界面。

---

## 高级材质解锁原理（简述）

桌面并不走 miuix 的 `HyperMaterialUtils`，而是自己的 `BlurUtilities.IS_BACKGROUND_BLUR_ENABLED`，它由 `Settings.Secure.background_blur_enable` 决定。模块做了三层放行：

1. 进程内拦截 `Settings.Secure/System/Global` 对 `background_blur_enable` 等 key 的读取，返回 1 —— 系统数据库不变，所以「总开关」保持关闭。
2. 放行桌面 `BlurUtilities.isBlurSupported()` / `MiuixMaterialBlurUtilities.isSupportHyperMaterialBlur()` 等门控。
3. 静态字段注入延后到应用就绪后，避免早期触发类初始化。

「分应用生效」= 在 LSPosed 作用域里勾选哪些应用。

---

## 日志排查

LSPosed 日志筛 `HM-Unleash`：

| 期望看到 | 含义 |
| --- | --- |
| `★ 识屏广播接收器已注册（exported ...）` | 入口已就位（应用就绪后才有） |
| `★ 收到识屏广播 action=...` | 命令已送达桌面进程 |
| `★ 已调用 handleLongPressEvent()` | 等价长按已触发 |
| `★ 强制识屏：已启动 ...` / `★ 识屏兜底：已直接启动 ...` | 走了降级路径 |
| `★ 全局开关 ...` / `★ 桌面命中 ...` | 高级材质放行链路 |

如果只有「模块载入」而没有「广播接收器已注册」，说明应用还没就绪或模块没注入桌面主进程；重启手机再试。

---

## 构建

```sh
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

仓库已配置 GitHub Actions：push 到 `main` 自动构建 release APK 并上传 artifact；推送 `v*` 标签时自动附加到 Release。

---

## 版本

| 版本 | 变更 |
| --- | --- |
| 2.1.0 | 新增识屏广播触发入口，补齐 README 与 shell 命令说明 |
| 2.0.0 | 精简为桌面专用：移除设置应用 Hook、版本卡片补丁与启动图标 |
| 1.3.0 | 修复静态字段注入过早触发 `<clinit>` 导致的桌面无限闪退 |
| 1.2.0 | 新增桌面 `BlurUtilities` 门控放行与全局开关拦截 |
| 1.0.0 | 首版，基于 libxposed 现代 API 102 |
