# HyperMaterialUnleashed

> **正式版 v3.0.0** · 基于 [libxposed/api](https://github.com/libxposed/api) 现代 API 102

不开启系统「高级材质」总开关，仅在 LSPosed 作用域勾选的应用内（默认系统桌面 `com.miui.home`）放行高级材质；并把「长按手势提示线 → 超级小爱识屏」暴露为 `am broadcast` 广播入口，供其它 App 调用。无界面、无启动图标，系统属性与设置数据库均不修改。

---

## 一、Shell 命令（可直接复制）

模块在**系统桌面进程**内注册了一个已导出的广播接收器。任何 App 只要能执行 `am broadcast`，就等价于长按了一次屏幕底部的小白条。

**主命令 —— 触发「长按手势提示线」（跟随系统设置，推荐）**

```sh
am broadcast -a com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS
```

**强制识屏 —— 不管系统里长按手势提示线指向什么，都走「超级小爱识屏」**

```sh
am broadcast -a com.hyperos.hypermaterial.action.XIAOAI_SCREEN_RECOGNIZE
```

**从电脑 adb 触发 / 调试**

```sh
adb shell am broadcast -a com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS
```

> 主命令刻意**不带 `-p`**：动态注册的接收器加 `-p com.miui.home` 在部分 ROM 上反而不匹配。确需限定时可以加，不生效就去掉。

### Action 与参数

| 项 | 值 |
| --- | --- |
| 主 action | `com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS` |
| 强制识屏 action | `com.hyperos.hypermaterial.action.XIAOAI_SCREEN_RECOGNIZE` |
| 接收器所在进程 | `com.miui.home`（系统桌面主进程） |
| `--es target screen_recognize` | 可选，等价于强制识屏 action |
| `--es from <字符串>` | 可选，来源标记，写入 `HM-Unleash` 日志 |
| `-p com.miui.home` | 可选，不限定更保险 |
| 权限 / root | 均不需要，任何应用都能发；Android 13+ 已显式 `RECEIVER_EXPORTED` |

### 在各处怎么用

| 场景 | 写法 |
| --- | --- |
| Tasker「运行 Shell」 | 直接填上面的主命令 |
| Tasker「发送 Intent」 | Action 填主 action，Target = **Broadcast Receiver**，Package 留空 |
| MacroDroid | 动作 → 应用程序 → Shell 脚本，填命令 |
| Termux / 任意终端 | `am broadcast -a ...`（Termux 可先 `pkg install termux-am`） |
| 自写 App（shell） | `Runtime.getRuntime().exec("am broadcast -a com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS")` |
| 自写 App（原生） | `sendBroadcast(new Intent("com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS"))` |
| 快捷方式 / 磁贴类 App | 选「发送广播」，填 action 即可 |

**前提**：系统设置 → 桌面 / 导航方式 → 「长按手势提示线」建议设为「超级小爱识屏」，这样主命令触发的就是识屏；用强制识屏命令则不受此项影响。识屏是否真正弹出取决于本机「超级小爱」版本与开关，模块只负责触发，不接管识屏界面。

---

## 二、安装

| 项目 | 要求 |
| --- | --- |
| 框架 | 支持 libxposed API 102 的 LSPosed；旧版 1.x 不加载本模块 |
| 作用域 | 勾选 **系统桌面 `com.miui.home`**（`staticScope=false`，可自行增删） |
| 生效 | 启用模块 / 更换 APK 后**必须重启手机** |
| 高级材质 | 无需任何额外设置，保持系统「高级材质」总开关关闭即可 |

---

## 三、原理（简述）

**高级材质**：桌面不走 miuix 的 `HyperMaterialUtils`，而是自己的 `BlurUtilities.IS_BACKGROUND_BLUR_ENABLED`，由 `Settings.Secure.background_blur_enable` 决定。模块三层放行：

1. 进程内拦截 `Settings.Secure/System/Global` 对该 key 的读取，返回 1 —— 数据库不变，总开关保持关闭；
2. 放行 `BlurUtilities.isBlurSupported()` / `MiuixMaterialBlurUtilities.isSupportHyperMaterialBlur()` / `shouldApplyBlur()`；
3. 应用就绪后延迟注入静态字段，免疫 ART 内联（早期触发宿主 `<clinit>` 曾导致桌面闪退）。

「分应用生效」= 在 LSPosed 作用域里勾选哪些应用。

**识屏**：反编译 `com.miui.home` 3.0.3 得到的长按链路是 `NavStubGestureEventManager.handleLongPressEvent()` → `doAction(2)` → `NavShortcutConfigManager` → `launchService()/buildIntent()` → `android.intent.action.ASSIST` / `VoiceService`。模块不硬编码这些 Intent 参数，收到广播后直接反射调用桌面的 `handleLongPressEvent()`，自动跟随系统设置与 ROM 版本；该入口不可用时才降级为直接拉起 `VoiceService`。所有反射都延迟到广播到达时执行，不会在启动早期触发宿主 `<clinit>`。

- 桌面被强制停止 / 冻结后，需重新打开桌面或重启手机，接收器才会重新注册。

---

## 四、日志排查

LSPosed 日志筛 `HM-Unleash`：

| 期望看到 | 含义 |
| --- | --- |
| `★ 识屏广播接收器已注册（exported ...）` | 入口已就位（应用就绪后才有） |
| `★ 收到识屏广播 action=...` | 命令已送达桌面进程 |
| `★ 已调用 handleLongPressEvent()` | 等价长按已触发 |
| `★ 强制识屏：已启动 ...` / `★ 识屏兜底：已直接启动 ...` | 走了降级路径 |
| `★ 全局开关 ...` / `★ 桌面命中 ...` | 高级材质放行链路命中 |

如果只有「模块载入」而没有「广播接收器已注册」，说明模块没注入桌面主进程；重启手机再试。

---

## 五、构建

```sh
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

仓库已配置 GitHub Actions：push 到 `main` 或 `v*` 标签自动构建 release APK 并上传构建产物；Release 附件（带版本号的 APK 与源码包）在发版时手动上传。

---

## 六、版本

| 版本 | 变更 |
| --- | --- |
| **3.0.0 正式版** | 清理从未命中的 Hook 与猜测性代码（3 个猜测 key、无参 boolean 兜底 Hook、整类 deoptimize、静态字段全量扫描、classloader 猜测兜底）；shell 命令写入模块说明与 README |
| 2.1.0 | 新增长按手势提示线识屏广播触发入口 |
| 2.0.0 | 精简为桌面专用：移除设置应用 Hook、版本卡片补丁与启动图标 |
| 1.3.0 | 修复静态字段注入过早触发 `<clinit>` 导致的桌面无限闪退 |
| 1.2.0 | 新增桌面 `BlurUtilities` 门控放行与全局开关拦截 |
| 1.0.0 | 首版，基于 libxposed 现代 API 102 |
