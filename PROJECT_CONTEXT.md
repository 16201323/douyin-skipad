# 抖音跳广告助手 - 项目上下文

> 本文档记录项目的完整上下文，包括技术原理、踩坑经历、已知限制和待解决问题。
> 在正式开发环境中使用，避免重复踩坑。

## 一、核心原理

### 为什么车载蓝牙能跳过抖音广告？

抖音短剧播放器实现了 `MediaSession.Callback` 接口：

| 回调 | 正片 | 广告 |
|------|------|------|
| `onSkipToNext()` | ✅ 切下一集 | ✅ **切下一集（跳过广告）** |
| `onPlay()` | ✅ 播放 | ❌ 广告时拦截 |
| `onPause()` | ✅ 暂停 | ✅ 暂停 |

车载蓝牙的「下一曲」按键 → 系统发送 `KEYCODE_MEDIA_NEXT` (keyevent 87) → 调用 `transportControls.skipToNext()` → **无论正片还是广告都切下一集**。

这是项目的根本机制。红果等 App 不实现 `onSkipToNext()`，所以无法跳过。

### 验证命令

```bash
# 手动验证跳过广告
adb shell input keyevent 87

# 查看抖音 MediaSession 详细状态
adb shell dumpsys media_session | grep -A 50 "com.ss.android.ugc.aweme"
```

## 二、MediaSession 状态实测数据（关键发现）

### 正片 vs 广告：MediaSession 完全相同！

| 场景 | metadata | state | position | speed |
|------|----------|-------|----------|-------|
| 正片播放 | **null** | NONE(1) | 0 | 1 |
| 广告播放 | **null** | NONE(1) | 0 | 1 |
| 切集缓冲 | **有值** | PLAYING(2) | 递增 | 1 |

### 数据来源

用户操作时每秒采样 435 条 periodic 数据 + 10 条 click_skip 数据：
- 有 metadata：仅 6 次（全在切集缓冲瞬间）
- 无 metadata：429 次（正片+广告）
- state=1(NONE)：429 次（正片+广告）
- state=2(PLAYING)：6 次（仅切集缓冲）

**结论：MediaSession 无法区分正片和广告，所有基于 MediaSession 的自动跳过方案都不可行。**

## 三、已尝试失败的自动跳过方案

| 方案 | 检测方式 | 结果 | 原因 |
|------|----------|------|------|
| 无障碍检测"广告"文字 | `findAccessibilityNodeInfosByText("广告")` | ❌ 无效 | 抖音广告标识画在视频上（OpenGL渲染），无障碍看不到 |
| metadata 从有到无 | 状态变化检测 | ❌ 误跳 | 正片切集/缓冲时 metadata 也会变 null |
| 进度条持续消失3秒 | `metadata==null` 持续计数 | ❌ 误跳 | 正片播放时 metadata 也持续为 null |
| 剩余时间 < 1.5秒 | position vs duration | ❌ 无效 | 正片 position 也不更新（始终为0） |

## 四、当前可行方案

### 4.1 手动跳过（稳定）

用户点击悬浮窗 → 调用 `skipToNext()` → 跳过广告。

### 4.2 自动跳过（实验性）

**无障碍检测广告 UI 提示文字**：

抖音短剧广告（Draw流广告）的提示文字：
- 无必看时长：底部「上滑继续看短剧」
- 有必看时长：「X秒后可继续上滑看剧」、「倒计时结束后可滑动」

但抖音把这些文字也画在视频上（OpenGL），无障碍能否检测到待验证。

## 五、悬浮窗只在目标 App 显示

### 问题

Android 11+ 限制 `ActivityManager.getRunningAppProcesses()`，只能返回自身进程。

### 解决方案

双条件判断：
1. **有目标 App 的 MediaSession**（`MediaSessionManager.getActiveSessions()` 检测）
2. **前台是目标 App**（`AccessibilityService` 监听 `TYPE_WINDOW_STATE_CHANGED`）

### 特殊情况处理

| 情况 | 处理 |
|------|------|
| 系统 UI 包名（`com.android.systemui`） | 不触发隐藏 |
| 设置页（`com.android.settings`） | 不触发隐藏 |
| 悬浮窗自身获取焦点 | 不触发隐藏 |

## 六、关键技术点

### 6.1 NotificationListenerService

- 必须启用才能调用 `getActiveSessions()`
- 在 Manifest 中声明 + 引导用户开启
- 需要绑定 MediaSessionManager

### 6.2 MediaSessionManager.getActiveSessions()

```kotlin
val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
val controllers = msm.getActiveSessions(ComponentName(this, MediaControlService::class.java))
```

- 返回所有活跃的 MediaController
- 可读取 metadata、playbackState、extras
- 可调用 transportControls（skipToNext、play、pause）

### 6.3 悬浮窗权限

- `SYSTEM_ALERT_WINDOW`
- Android 6.0+ 需要 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` 引导用户授权
- 使用 `TYPE_APPLICATION_OVERLAY`（Android 8.0+）

### 6.4 前台服务

- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- 需要 `startForeground(notificationId, notification)`

### 6.5 抖音 MediaSession 不更新的问题

抖音播放器可能使用自定义播放器，不通过 MediaSession 上报实时状态。只有切集时才短暂更新 metadata。这是 Android 媒体框架的限制，App 可以选择不实现实时更新。

## 七、红果免费漫剧

| 功能 | 抖音 | 红果 |
|------|------|------|
| MediaSession | ✅ 有 | ✅ 有 |
| onSkipToNext() | ✅ 实现 | ❌ **未实现** |
| 广告跳过方式 | MediaSession | 独立广告 SDK |
| 手动跳过 | ✅ 有效 | ❌ **无效** |
| 无障碍上滑 | 不需要 | 部分有效（倒计时结束后） |

**红果放弃处理**——它不实现 skipToNext，广告由独立 SDK 控制，无法绕过。

## 八、数据采集

### 目的

分析 MediaSession 特征，寻找自动跳过信号。

### 数据格式

```jsonl
{"timestamp":1731900000000,"time":"2026-11-18 02:07:10.259","event":"click_skip","foreground":"unknown","sessions":[{"package":"com.ss.android.ugc.aweme","metadata":null,"playbackState":{"state":1,"position":0,"speed":1}}]}
```

### 存储位置

```
/sdcard/Android/data/com.example.skipad/files/ad_data.jsonl
```

### 事件类型

- `click_skip`：用户点击悬浮窗跳过
- `periodic`：每秒周期性采样

### 分析结论

**MediaSession 正片和广告状态完全一致，无法作为自动跳过信号。**

## 九、文件清单

```
app/src/main/java/com/example/skipad/
├── MainActivity.kt                    # 主界面（权限引导 + 设置 + 工具）
├── FloatingButtonService.kt           # 悬浮窗前台服务
├── MediaControlService.kt             # NotificationListenerService + MediaSession 控制
├── SkipAdAccessibilityService.kt      # AccessibilityService（前台检测 + 广告检测 + dump）
├── ConfigManager.kt                   # SharedPreferences 配置管理
├── AdDataLogger.kt                    # JSON Lines 数据采集
└── ColorPickerDialog.kt              # HSV 颜色选择器
```

## 十、待解决问题

### P0：自动跳过

当前无障碍检测「上滑继续看短剧」等提示文字，需要验证这些文字是否是无障碍节点。

**下一步**：在广告界面 dump 无障碍节点，确认抖音是否把广告提示文字暴露给无障碍服务。

### P1：MediaSession 实时状态

如果能让抖音播放器实时更新 MediaSession（metadata/playbackState），就能精确区分正片和广告。

可能方案：
- `Xposed/LSPosed hook` 抖音播放器的 MediaSession 相关方法
- 反编译抖音 APK，找到 MediaSession 更新逻辑

### P2：截图识别（备选方案）

用 `MediaProjection` 截屏 + OCR 识别视频画面上的"广告"标识。但需要 `MediaProjection` 权限（每次要弹窗确认），且 OCR 性能有问题。

### P3：定时跳过

用户手动跳过一次后，记录跳过时间，每隔 N 秒自动跳过一次。粗粒度但不需要检测。

## 十一、构建命令

```bash
# Windows PowerShell
$env:JAVA_HOME = "D:\code\...\jdk-17"
$env:ANDROID_HOME = "D:\code\...\android-sdk"
.\gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
