# 抖音短剧广告跳过助手

一个 Android 悬浮窗工具：在抖音短剧播放时，**手动点击**或**自动检测**跳过广告。

## 功能

- **悬浮窗一键跳过**：抖音播放界面显示悬浮按钮，单击即跳过当前广告（含倒计时中的广告）
- **自动跳过**：开启后自动识别广告界面并跳过，约 0.8 秒响应，经双阶段确认防误触
  - 支持**倒计时广告**（识别「X 秒后可继续看下一集」等文案）
  - 支持**无倒计时的直播间/商品引流广告**（识别「免广告」「查看详情」等广告页独有文案）
- **广告检测间隔可调**：在 App 内设置前台检测/广告识别的轮询频率（200~2000ms，默认 500ms），越小响应越快、越耗电
- **跳过统计**：首页展示今日 / 本周已跳过次数（跨天、跨周自动归零）
- **悬浮窗自定义**：颜色、透明度、大小、形状、文字、图片内容均可配置
- **悬浮窗交互**：
  - 单击跳过广告；长按收起 / 恢复
  - 拖到屏幕边缘停留 2 秒 → 自动收起成小长条
  - 收起态横向滑动或向屏幕内侧拖动 → 恢复正常大小
  - 位置自动记忆，重启后恢复到上次位置
- **多应用支持**：抖音、抖音极速版

## 原理

### 跳过：MediaSession skipToNext

抖音短剧播放器实现了 `MediaSession.Callback.onSkipToNext()` 接口。调用 `MediaController.transportControls.skipToNext()` 时，播放器切到下一集，**无论当前是否在广告界面**——这就是车载蓝牙「下一曲」键能跳过抖音广告的原因。

### 广告检测：无障碍节点文字

通过无障碍服务读取当前界面的文字节点，匹配广告页独有文案来判定是否为广告：

| 广告类型 | 识别文案 |
|----------|----------|
| 可直接上滑 | 「上滑继续观看短剧」 |
| 倒计时中 | 「X 秒后可继续看下一集」、「秒后可继续上滑看剧」、「倒计时结束后可滑动」 |
| 直播间 / 商品引流（无倒计时） | 「免广告」、「查看详情」 |
| 兜底 | 短文本「广告」 |

> 检测到广告后采用**双阶段确认**：首次命中只记时间，约 300ms 后二次检测仍命中才真正跳过，避免界面文字瞬态闪现造成误触。

> ⚠️ 抖音 MediaSession 在正片和广告时状态完全相同（metadata=null, state=NONE），**无法仅通过 MediaSession 区分正片和广告**，因此必须依赖无障碍读取界面文字。若广告文字画在视频画面（SurfaceView）上，无障碍读不到，则该类广告暂无法自动识别。

## 技术栈

- **语言**：Kotlin
- **构建**：Gradle (Kotlin DSL)，JDK 17
- **minSdk**：26（Android 8.0）

### 核心组件

| 组件 | 作用 |
|------|------|
| `FloatingButtonService` | 前台服务，管理悬浮窗显示、拖动、贴边收起、位置记忆、自动跳过调度 |
| `FloatingView` / `ShapePainter` | 悬浮窗绘制（形状、图片、文字） |
| `MediaControlService` | `NotificationListenerService`，获取 MediaSession，执行 skipToNext |
| `SkipAdAccessibilityService` | `AccessibilityService`，前台应用检测、广告节点文字检测 |
| `ConfigManager` | SharedPreferences 配置管理（样式、App 列表、自动跳过、检测间隔、统计、位置记忆） |
| `MainActivity` | 主界面：权限引导、样式设置、自动跳过开关、检测间隔、统计展示 |
| `ColorPickerDialog` | HSV 颜色选择对话框 |
| `AdDataLogger` | 本地数据采集（**已停用**，仅供调试，正式版不再后台写文件） |

### 关键技术点

1. **NotificationListenerService**：必须启用才能通过 `MediaSessionManager.getActiveSessions()` 获取所有活跃 MediaController。
2. **悬浮窗权限**：`SYSTEM_ALERT_WINDOW`，Android 6.0+ 需手动授权。
3. **前台应用检测**：Android 10+ `ActivityManager.getRunningAppProcesses()` 受限，改用 `AccessibilityService` 监听 `TYPE_WINDOW_STATE_CHANGED` 事件。
4. **悬浮窗只在目标 App 显示**：双条件——有目标 App 的 MediaSession 且前台是目标 App。

## 使用说明

### 1. 开启必需权限

首次启动会引导开启：

1. **通知使用权**（NotificationListener）—— 获取 MediaSession，执行跳过
2. **悬浮窗权限**（SYSTEM_ALERT_WINDOW）—— 显示悬浮按钮
3. **无障碍服务**（AccessibilityService）—— 前台检测 + 广告检测（仅自动跳过需要）

> 权限状态在首页实时刷新；若无障碍显示 ✗，点「去开启无障碍服务」直达系统设置页开启后返回即可。

### 2. 重要：防止无障碍被系统杀掉（OPPO / ColorOS 等国产 ROM 必做）

国产 ROM 会在后台强杀进程（ColorOS 的 `o-stop`），并**自动撤销已开启的无障碍服务**，导致自动跳过失效、权限状态反复打叉。请务必设置：

1. **应用信息 → 电池 / 耗电管理**：允许后台活动，关闭所有省电限制、应用速冻
2. **应用信息 → 自启动**：打开
3. **多任务界面**：下拉本 App 卡片锁住（出现 🔒）
4. **不要使用「一键清理 / 加速」**：会触发强杀并撤销无障碍

设置生效后，进程稳定、无障碍不再掉线，自动跳过才能持续工作。

### 3. 配置与启动

1. 勾选支持的应用（抖音 / 抖音极速版）
2. （可选）自定义悬浮窗样式：颜色、透明度、大小、形状、文字 / 图片
3. （可选）开启「自动跳过」，并调节「广告检测间隔」（默认 500ms）
4. 点击「启动悬浮按钮」

### 4. 看剧

打开抖音刷短剧：

- **手动**：遇到广告单击悬浮窗跳过
- **自动**：开启自动跳过后，广告出现会自动跳过（约 0.8 秒），可在首页「跳过统计」查看今日 / 本周跳过次数

### 悬浮窗操作

| 操作 | 效果 |
|------|------|
| 单击 | 跳过广告（正常态）/ 恢复显示（收起态） |
| 长按（>0.6 秒） | 收起 / 恢复切换 |
| 拖动 | 移动位置；拖到屏幕边缘停留 2 秒自动收起 |
| 收起态横向滑动 | 恢复正常大小，可继续拖动 |

## 已知限制

- **红果免费漫剧无法跳过**：其播放器未实现 `onSkipToNext()` 回调，MediaSession 方式无效
- **画在视频画面上的广告文字**：无障碍读不到 SurfaceView 内的字幕，这类广告无法自动识别（需 OCR 等方案，暂未支持）
- **自动跳过依赖无障碍**：无障碍被系统杀掉则自动跳过失效，见上文「防止无障碍被系统杀掉」

## 项目结构

```
douyin-skipad/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/example/skipad/
│   │   ├── MainActivity.kt                # 主界面
│   │   ├── FloatingButtonService.kt       # 悬浮窗服务
│   │   ├── FloatingView.kt                # 悬浮窗 View
│   │   ├── ShapePainter.kt                # 形状绘制
│   │   ├── MediaControlService.kt         # MediaSession 控制
│   │   ├── SkipAdAccessibilityService.kt  # 无障碍服务
│   │   ├── ConfigManager.kt               # 配置管理
│   │   ├── AdDataLogger.kt                # 数据采集（已停用）
│   │   └── ColorPickerDialog.kt           # 颜色选择
│   └── res/
├── build.gradle.kts
└── settings.gradle.kts
```

## 构建

需 JDK 17 与 Android SDK：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# Debug 包
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/douyin-skipad-debug.apk

# Release 包
./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/douyin-skipad-release.apk

# 安装到设备
adb install -r app/build/outputs/apk/debug/douyin-skipad-debug.apk
```

## License

个人项目，仅供学习研究使用。
