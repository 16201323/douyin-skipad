# 抖音短剧广告跳过助手

一个 Android 悬浮窗工具，用于在抖音短剧播放时自动跳过广告。

## 功能

- **悬浮窗一键跳过**：在抖音播放界面显示悬浮按钮，点击即跳过当前广告
- **自动跳过（实验性）**：开启后自动检测广告界面并跳过
- **悬浮窗自定义**：颜色、透明度、大小、形状、文字均可配置
- **隐身模式**：长按悬浮窗进入隐身（缩到屏幕边缘），点击恢复
- **多应用支持**：抖音、抖音极速版

## 原理

### MediaSession skipToNext

抖音短剧播放器实现了 `MediaSession.Callback.onSkipToNext()` 接口。当调用 `MediaController.transportControls.skipToNext()` 时，播放器会切到下一集，**无论当前是否在广告界面**。

这就是为什么车载蓝牙的「下一曲」按键（`KEYCODE_MEDIA_NEXT` / keyevent 87）能跳过抖音广告——它走的就是 MediaSession 通道。

### 广告检测（实验性）

抖音短剧广告界面有以下可检测的特征：

| 广告类型 | 提示文字 |
|----------|----------|
| 无必看时长 | 底部「上滑继续看短剧」 |
| 有必看时长 | 「X秒后可继续上滑看剧」、「倒计时结束后可滑动」 |

通过 Android 无障碍服务检测这些文字节点，发现即调用 `skipToNext()`。

> ⚠️ 由于抖音 MediaSession 在正片和广告时状态完全相同（metadata=null, state=NONE），**无法仅通过 MediaSession 区分正片和广告**。这是自动跳过功能的主要难点。

## 技术栈

- **语言**：Kotlin
- **IDE**：Android Studio
- **构建**：Gradle (Kotlin DSL)
- **minSdk**：26（Android 8.0）

### 核心组件

| 组件 | 作用 |
|------|------|
| `FloatingButtonService` | 前台服务，管理悬浮窗显示、移动、点击、隐身 |
| `MediaControlService` | `NotificationListenerService`，获取 MediaSession，执行 skipToNext |
| `SkipAdAccessibilityService` | `AccessibilityService`，前台检测、广告节点检测 |
| `ConfigManager` | SharedPreferences 配置管理 |
| `AdDataLogger` | 本地数据采集，记录 MediaSession 特征用于分析 |
| `ColorPickerDialog` | HSV 颜色选择对话框 |

### 关键技术点

#### 1. NotificationListenerService

必须启用才能通过 `MediaSessionManager.getActiveSessions()` 获取所有活跃的 MediaController。

```kotlin
val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
val controllers = msm.getActiveSessions(ComponentName(this, MediaControlService::class.java))
controllers.forEach { it.transportControls.skipToNext() }
```

#### 2. 悬浮窗权限

`SYSTEM_ALERT_WINDOW` 权限，Android 6.0+ 需要用户手动授权。

#### 3. 前台应用检测

- **Android 10+**：`ActivityManager.getRunningAppProcesses()` 受限，只能返回自身进程
- **方案**：使用 `AccessibilityService` 监听 `TYPE_WINDOW_STATE_CHANGED` 事件

#### 4. 悬浮窗只在目标 App 显示

双条件判断：
1. 有目标 App 的 MediaSession（`hasTargetMediaSession`）
2. 前台是目标 App（`isForegroundTargetApp`，通过无障碍服务检测）

#### 5. MediaSession 状态不稳定

实测发现抖音播放器在以下场景的 MediaSession 状态：

| 场景 | metadata | state |
|------|----------|-------|
| 正片播放 | null | NONE (1) |
| 广告播放 | null | NONE (1) |
| 切集缓冲 | 有值 | PLAYING (2) |

**正片和广告的 MediaSession 状态完全一致**，无法作为自动跳过的判断依据。

## 使用说明

### 必需权限

首次启动会引导开启以下权限：

1. **通知使用权**（NotificationListener）：用于获取 MediaSession
2. **悬浮窗权限**（SYSTEM_ALERT_WINDOW）：显示悬浮按钮
3. **无障碍服务**（AccessibilityService）：前台检测 + 广告检测

### 操作流程

1. 打开 App，按引导开启权限
2. 勾选支持的应用（抖音 / 抖音极速版）
3. 点击「启动悬浮按钮」
4. 打开抖音看短剧
5. 遇到广告时：
   - **手动**：点击悬浮窗跳过
   - **自动**：开启「自动跳过」开关

### 悬浮窗操作

- **单击**：跳过广告
- **长按**：隐身模式（缩到屏幕边缘）
- **隐身时单击/长按**：恢复显示
- **拖动**：移动位置

### 数据分析模式

开启悬浮窗后，App 每秒自动采集抖音 MediaSession 状态，用于研究自动跳过方案。

- 数据文件：`/sdcard/Android/data/com.example.skipad/files/ad_data.jsonl`
- 格式：JSON Lines
- 内容：时间戳、事件类型（click_skip/periodic）、前台包名、MediaSession 完整状态

## 已知限制

- **红果免费漫剧无法跳过**：红果的播放器未实现 `onSkipToNext()` 回调，MediaSession 方式无效；其广告有独立 SDK 控制，无障碍服务也难以绕过
- **自动跳过不稳定**：抖音 MediaSession 不区分正片和广告，只能通过无障碍检测 UI 文字，存在误判可能
- **OPPO 等国产 ROM 限制**：部分 ROM 对 `ActivityManager` API 有限制，需要依赖无障碍服务

## 项目结构

```
douyin-skipad/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/example/skipad/
│   │   ├── MainActivity.kt              # 主界面
│   │   ├── FloatingButtonService.kt     # 悬浮窗服务
│   │   ├── MediaControlService.kt       # MediaSession 控制
│   │   ├── SkipAdAccessibilityService.kt # 无障碍服务
│   │   ├── ConfigManager.kt             # 配置管理
│   │   ├── AdDataLogger.kt              # 数据采集
│   │   └── ColorPickerDialog.kt          # 颜色选择
│   └── res/
├── build.gradle.kts
└── settings.gradle.kts
```

## 构建

```bash
# 设置环境变量
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# 构建
./gradlew assembleDebug

# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License

个人项目，仅供学习研究使用。
