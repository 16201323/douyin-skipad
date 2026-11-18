package com.example.skipad

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast

/**
 * 悬浮按钮服务
 *
 * 交互逻辑（参考苹果小白点）：
 * - 正常状态：
 *   - 点击 → 跳过广告
 *   - 长按 → 进入隐身状态
 *   - 拖动 → 移动位置
 * - 隐身状态：
 *   - 按钮靠边，只露 1/3 在屏幕外边缘
 *   - 颜色变浅（透明度降低）
 *   - 点击 → 恢复正常状态
 *
 * 显示逻辑：
 * - 通过 MediaSessionManager.OnActiveSessionsChangedListener 监听活跃 MediaSession
 * - 当已启用的短剧 App 有活跃 MediaSession（正在播放视频）时显示悬浮窗
 * - 其他情况隐藏悬浮窗
 */
class FloatingButtonService : Service() {

    companion object {
        private const val TAG = "SkipAd_Floating"
        private const val CHANNEL_ID = "skip_ad_floating_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.example.skipad.START_FLOATING"
        const val ACTION_STOP = "com.example.skipad.STOP_FLOATING"
        const val ACTION_REFRESH = "com.example.skipad.REFRESH_FLOATING"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var activityManager: ActivityManager
    private var floatingView: Button? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isStealth = false           // 是否隐身状态
    private var isVisible = true            // 悬浮窗是否可见
    private var screenWidth = 0

    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval = 1000L       // 前台检测间隔 1 秒

    // 自动跳过节流：上次跳过时间，避免同一次广告被反复跳过
    private var lastSkipTime = 0L
    private val skipThrottleMs = 2000L      // 2秒内不重复跳过

    // 记录进度条连续消失的次数
    // 广告时进度条持续消失（3秒以上），切集/缓冲时只短暂消失（<1秒）
    private var noMetadataCount = 0

    // MediaSession 变化监听器
    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener {
        Log.d(TAG, "MediaSession 列表变化，重新检测")
        checkShouldShow()
    }

    // 定时检测前台应用
    private val foregroundChecker = object : Runnable {
        override fun run() {
            checkShouldShow()
            // 自动跳过广告：如果开启了自动跳过，检测到广告则自动跳过
            val config = ConfigManager.get(this@FloatingButtonService)
            if (config.autoSkip && isVisible) {
                tryAutoSkip()
            }

            // 周期性采样 MediaSession 状态（用于数据分析，每秒记录一次）
            // 不依赖前台检测，只要有目标 App 的 MediaSession 就记录
            try {
                val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
                val sessions = msm.getActiveSessions(android.content.ComponentName(this@FloatingButtonService, MediaControlService::class.java))
                val hasTargetSession = sessions.any { config.isAppEnabled(it.packageName) }
                if (hasTargetSession) {
                    AdDataLogger.log(this@FloatingButtonService, "periodic")
                }
            } catch (e: Exception) {
                // 忽略
            }

            handler.postDelayed(this, checkInterval)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        screenWidth = resources.displayMetrics.widthPixels

        // 注册 MediaSession 变化监听（需要通知使用权）
        try {
            val componentName = ComponentName(this, MediaControlService::class.java)
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
            Log.d(TAG, "MediaSession 监听器已注册")
        } catch (e: Exception) {
            Log.e(TAG, "注册 MediaSession 监听器失败: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                refreshButtonStyle()
                return START_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification())
                showFloatingButton()
                checkShouldShow()           // 启动时立即检测一次
                handler.post(foregroundChecker)  // 启动定时前台检测
            }
        }
        return START_STICKY
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "跳广告悬浮按钮", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示跳广告悬浮按钮"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("抖音跳广告助手")
            .setContentText("悬浮按钮已运行")
            .setSmallIcon(android.R.drawable.ic_media_next)
            .build()
    }

    /**
     * 创建并显示悬浮按钮
     */
    private fun showFloatingButton() {
        if (floatingView != null) return
        val button = createStyledButton()

        val density = resources.displayMetrics.density
        val sizePx = (ConfigManager.get(this).buttonSize * density).toInt()

        layoutParams = WindowManager.LayoutParams(
            sizePx,  // 固定宽度
            sizePx,  // 固定高度（圆形时宽高相等）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = (60 * density).toInt()
            y = 0
        }

        setupButtonEvents(button)
        floatingView = button
        windowManager.addView(floatingView, layoutParams)
        Log.d(TAG, "悬浮按钮已显示")
    }

    /**
     * 根据配置创建带样式的按钮
     */
    private fun createStyledButton(): Button {
        val config = ConfigManager.get(this)
        val density = resources.displayMetrics.density

        return Button(this).apply {
            text = config.buttonText
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, config.textSize)
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER

            // 设置背景形状和颜色
            val drawable = GradientDrawable().apply {
                setColor(config.buttonColor)
                when (config.buttonShape) {
                    ConfigManager.SHAPE_CIRCLE -> shape = GradientDrawable.OVAL
                    ConfigManager.SHAPE_ROUND_RECT -> cornerRadius = 16 * density
                    ConfigManager.SHAPE_SQUARE -> cornerRadius = 0f
                }
                alpha = config.buttonAlpha
            }
            background = drawable
        }
    }

    /**
     * 设置按钮的点击、拖动、长按事件
     */
    private fun setupButtonEvents(button: Button) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var downTime = 0L

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams!!.x
                    initialY = layoutParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    downTime = System.currentTimeMillis()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initialTouchX - event.rawX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    if (isDragging) {
                        layoutParams!!.x = initialX + dx
                        layoutParams!!.y = initialY + dy
                        windowManager.updateViewLayout(floatingView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val pressDuration = System.currentTimeMillis() - downTime
                    if (!isDragging) {
                        if (pressDuration > 600) {
                            // 长按
                            if (isStealth) {
                                restoreFromStealth()  // 隐身状态长按 → 恢复
                            } else {
                                enterStealth()        // 正常状态长按 → 隐身
                            }
                        } else {
                            // 短按
                            if (isStealth) {
                                restoreFromStealth()  // 隐身状态点击 → 恢复
                            } else {
                                performSkip()         // 正常状态点击 → 跳过广告
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 执行跳过广告
     */
    private fun performSkip() {
        // 记录用户点击跳过时的 MediaSession 特征（用于数据分析）
        AdDataLogger.log(this, "click_skip")

        val success = MediaControlService.skipDouyinAd(this)
        if (success) {
            Toast.makeText(this, "已跳过", Toast.LENGTH_SHORT).show()
        }
        Log.d(TAG, "点击跳过，结果: $success")
    }

    /**
     * 自动跳过广告
     * 抖音广告标识是画在视频画面上的，无障碍服务看不到文字，因此改用 MediaSession 信号：
     * 当播放进度 position 接近总时长 duration（剩余 < 1.5秒）时，说明正片即将结束、广告即将开始，
     * 此时调用 skipToNext() 直接切到下一集，跳过广告。
     * 带节流：2秒内不重复跳过
     */
    private fun tryAutoSkip() {
        // 节流：避免短时间内重复跳过
        val now = System.currentTimeMillis()
        if (now - lastSkipTime < skipThrottleMs) {
            return
        }

        // 通过无障碍服务检测广告界面
        // 抖音短剧广告会显示"上滑继续看短剧"、"X秒后可继续上滑看剧"等提示
        val isAd = SkipAdAccessibilityService.isAdShowing()
        if (!isAd) return

        Log.d(TAG, "检测到广告界面，执行自动跳过")
        val success = MediaControlService.skipDouyinAd(this)
        lastSkipTime = now
        if (success) {
            Log.d(TAG, "自动跳过成功")
        }
    }

    /**
     * 检测抖音是否正在播放广告
     * 逻辑：
     * - 正片播放时有进度条（metadata 非空）
     * - 广告播放时进度条消失（metadata 为 null）
     * - 正片切集/缓冲时进度条也会短暂消失，但持续时间短（<1秒）
     * - 广告时进度条持续消失（3秒以上）
     *
     * 因此：进度条连续消失 3 次（3秒）以上 → 判定为广告
     */
    private fun isEpisodeEnding(): Boolean {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            val sessions = msm.getActiveSessions(android.content.ComponentName(this, MediaControlService::class.java))
            for (controller in sessions) {
                val config = ConfigManager.get(this)
                if (!config.isAppEnabled(controller.packageName)) continue

                val metadata = controller.metadata

                // 进度条消失
                if (metadata == null) {
                    noMetadataCount++
                    Log.d(TAG, "isEpisodeEnding: ${controller.packageName} 进度条消失 count=$noMetadataCount")
                    // 连续消失 3 次（3秒）→ 判定为广告
                    if (noMetadataCount >= 3) {
                        Log.d(TAG, "广告信号: 进度条连续消失 ${noMetadataCount} 次，判定为广告")
                        noMetadataCount = 0
                        return true
                    }
                    continue
                }

                // 有进度条，重置计数
                if (noMetadataCount > 0) {
                    Log.d(TAG, "isEpisodeEnding: ${controller.packageName} 进度条恢复，重置计数")
                }
                noMetadataCount = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "检测广告信号失败: ${e.message}")
        }
        return false
    }

    /**
     * 进入隐身状态：靠边藏起 2/3，颜色变浅
     */
    private fun enterStealth() {
        val button = floatingView ?: return
        val lp = layoutParams ?: return
        val config = ConfigManager.get(this)
        val density = resources.displayMetrics.density
        val sizePx = (config.buttonSize * density).toInt()

        // 判断按钮在屏幕左半还是右半，决定靠边方向
        val centerX = screenWidth / 2
        val currentCenterX = screenWidth - lp.x - sizePx / 2

        if (currentCenterX > centerX) {
            // 靠右 → 藏到右边，只露 1/3
            lp.x = -(sizePx / 3)
        } else {
            // 靠左 → 藏到左边，只露 1/3
            lp.x = screenWidth - sizePx / 3
        }

        // 颜色变浅（透明度降低到原来的 40%）
        val drawable = button.background as GradientDrawable
        drawable.alpha = (config.buttonAlpha * 0.4f).toInt().coerceIn(20, 255)
        button.alpha = 0.6f

        isStealth = true
        windowManager.updateViewLayout(button, lp)
        Toast.makeText(this, "已隐身，点击恢复", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "进入隐身状态")
    }

    /**
     * 从隐身状态恢复
     */
    private fun restoreFromStealth() {
        val button = floatingView ?: return
        val config = ConfigManager.get(this)

        // 恢复透明度
        val drawable = button.background as GradientDrawable
        drawable.alpha = config.buttonAlpha
        button.alpha = 1.0f

        isStealth = false
        windowManager.updateViewLayout(button, layoutParams)
        Log.d(TAG, "恢复正常状态")
    }

    /**
     * 刷新按钮样式（配置变更后调用）
     */
    private fun refreshButtonStyle() {
        val button = floatingView ?: return
        val config = ConfigManager.get(this)
        val density = resources.displayMetrics.density

        button.text = config.buttonText
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.textSize)

        val drawable = GradientDrawable().apply {
            setColor(config.buttonColor)
            when (config.buttonShape) {
                ConfigManager.SHAPE_CIRCLE -> shape = GradientDrawable.OVAL
                ConfigManager.SHAPE_ROUND_RECT -> cornerRadius = 16 * density
                ConfigManager.SHAPE_SQUARE -> cornerRadius = 0f
            }
            alpha = config.buttonAlpha
        }
        button.background = drawable

        // 更新固定宽高
        val sizePx = (config.buttonSize * density).toInt()
        layoutParams?.width = sizePx
        layoutParams?.height = sizePx
        windowManager.updateViewLayout(button, layoutParams)

        isStealth = false
        Log.d(TAG, "按钮样式已刷新")
    }

    /**
     * 综合判断是否应该显示悬浮窗
     * 条件（同时满足）：
     * 1. 有目标 App 的活跃 MediaSession（正在播放）
     * 2. 前台应用是目标 App（避免后台时仍显示）
     */
    private fun checkShouldShow() {
        val config = ConfigManager.get(this)

        // 条件 1：检查是否有目标 App 的活跃 MediaSession
        val hasTargetSession = hasTargetMediaSession(config)

        // 条件 2：检查前台应用是否是目标 App
        val foregroundIsTarget = isForegroundTargetApp(config)

        val shouldShow = hasTargetSession && foregroundIsTarget

        Log.d(TAG, "检测结果: session=$hasTargetSession, 前台=$foregroundIsTarget, shouldShow=$shouldShow, isVisible=$isVisible")

        if (shouldShow && !isVisible) {
            if (floatingView == null) {
                showFloatingButton()
            } else {
                floatingView?.visibility = View.VISIBLE
            }
            isVisible = true
            Log.d(TAG, "显示悬浮窗（目标App前台且有活跃会话）")
        } else if (!shouldShow && isVisible) {
            floatingView?.visibility = View.GONE
            isVisible = false
            Log.d(TAG, "隐藏悬浮窗（session=$hasTargetSession, 前台=$foregroundIsTarget）")
        }
    }

    /**
     * 检查是否有目标 App 的活跃 MediaSession
     */
    private fun hasTargetMediaSession(config: ConfigManager): Boolean {
        return try {
            val componentName = ComponentName(this, MediaControlService::class.java)
            val controllers: List<MediaController> = mediaSessionManager.getActiveSessions(componentName)
            for (controller in controllers) {
                if (config.isAppEnabled(controller.packageName)) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "检查活跃会话失败: ${e.message}")
            false
        }
    }

    /**
     * 检查前台应用是否是目标 App
     *
     * 检测策略（按优先级）：
     * 1. 无障碍服务记录的前台包名（最可靠，Android 11+ 必备）
     *    - 如果明确检测到前台是目标 App → true
     *    - 如果明确检测到前台不是目标 App → false
     * 2. 无障碍服务未启用或尚未检测到前台 → 返回 true（倾向于显示）
     *    此时由 hasTargetMediaSession 条件控制是否显示悬浮窗
     *
     * 注意：Android 11+ 上 ActivityManager.getRunningAppProcesses() 被限制，
     * 只能返回自身进程，因此不作为主要检测手段。
     */
    /**
     * 系统UI包名列表（不影响悬浮窗显示）
     * 这些包名只是短暂出现（如下拉通知、手势导航、设置页），
     * 不应导致悬浮窗被隐藏。
     */
    private val systemUiPackages = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.android.incallui",
        "com.android.dialer",
        "com.android.systemui.recents"
    )

    /**
     * 检查前台应用是否是目标 App
     *
     * 检测策略：
     * 1. 无障碍服务记录的前台包名
     *    - 如果是目标 App → true
     *    - 如果是系统UI（通知栏/导航/设置等）→ true（不隐藏悬浮窗，系统UI只是短暂出现）
     *    - 如果是其他非目标App → false
     * 2. 无障碍服务未启用或尚未检测到前台 → true（倾向于显示）
     */
    private fun isForegroundTargetApp(config: ConfigManager): Boolean {
        val a11yPkg = SkipAdAccessibilityService.getForegroundPackage()
        if (!a11yPkg.isNullOrEmpty()) {
            // 本应用自身：显示悬浮窗（方便用户在设置页测试）
            if (a11yPkg == packageName) {
                return true
            }
            // 系统UI包名：忽略，保持悬浮窗显示
            if (a11yPkg in systemUiPackages) {
                Log.d(TAG, "无障碍前台检测: $a11yPkg（系统UI，保持显示）")
                return true
            }
            val isTarget = config.isAppEnabled(a11yPkg)
            Log.d(TAG, "无障碍前台检测: $a11yPkg, 是目标=$isTarget")
            return isTarget
        }

        Log.d(TAG, "无障碍服务未检测到前台，倾向于显示悬浮窗")
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // 注销 MediaSession 监听器
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (e: Exception) {
            Log.e(TAG, "注销监听器失败: ${e.message}")
        }
        floatingView?.let {
            windowManager.removeView(it)
            floatingView = null
        }
    }
}
