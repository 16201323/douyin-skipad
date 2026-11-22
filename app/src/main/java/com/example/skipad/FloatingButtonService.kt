package com.example.skipad

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
    private var floatingView: FloatingView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isStealth = false           // 是否隐身（收起）状态
    private var normalX = 0                 // 收起前的 x 坐标（恢复位置用，修 Bug1）
    private var normalWidth = 0             // 收起前的宽度（恢复尺寸用）
    private var normalHeight = 0            // 收起前的高度（恢复尺寸用）
    private var isVisible = true            // 悬浮窗是否可见
    private var screenWidth = 0

    private val handler = Handler(Looper.getMainLooper())
    // 前台检测间隔改由 ConfigManager.detectIntervalMs 读取（用户在 App 可调），
    // 不再硬编码，见 foregroundChecker.run() 末尾。

    // 自动跳过节流：上次跳过时间，避免同一次广告被反复跳过
    private var lastSkipTime = 0L
    private val skipThrottleMs = 2000L      // 2秒内不重复跳过

    // 自动跳过「双阶段确认」：首次检测到广告时记下时间，下一轮仍检测到才真正跳过，
    // 避免界面瞬态文本闪现导致的误触（经验：单次命中立即执行误触率高）。
    private var adFirstDetectedAt = 0L
    private val adConfirmDelayMs = 300L     // 首次命中后至少间隔 300ms 且仍命中才跳过（原来 600ms，广告倒计时文案稳定持续，缩短以加快响应）

    // 无障碍未连接警告的节流时间（避免每秒刷屏）
    private var lastA11yWarnTime = 0L
    private val a11yWarnIntervalMs = 5000L

    // 贴边收起：拖到屏幕边缘并持续 2 秒不动 → 自动收起成小长方形
    private var edgeHoldRunnable: Runnable? = null
    private val edgeHoldMs = 2000L          // 贴边持续多久触发收起
    private val edgeThresholdDp = 14        // 距离屏幕边缘多少 dp 以内算「贴边」

    // MediaSession 变化监听器
    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener {
        Log.d(TAG, "MediaSession 列表变化，重新检测")
        checkShouldShow()
    }

    // 定时检测前台应用
    private val foregroundChecker = object : Runnable {
        override fun run() {
            // 每轮都读取最新配置，用户在 App 里调节「广告检测间隔」后即时生效
            val config = ConfigManager.get(this@FloatingButtonService)
            // 整个循环体包 try/catch：任何子步骤异常都不能中断 postDelayed，
            // 否则轮询会永久停止，悬浮窗再也不会自动显隐（修 Bug3）
            try {
                checkShouldShow()
                // 自动跳过广告：如果开启了自动跳过，检测到广告则自动跳过
                if (config.autoSkip && isVisible) {
                    tryAutoSkip()
                }
            } catch (e: Exception) {
                Log.e(TAG, "前台检测循环异常: ${e.message}")
            }

            // 无论是否异常，都继续下一轮轮询；间隔取用户设置的「广告检测间隔」
            handler.postDelayed(this, config.detectIntervalMs.toLong())
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
                // 启动时强制显示悬浮窗，给用户明确的「启动成功」视觉反馈。
                // 原因：如果紧接着就走 checkShouldShow，此时抖音 MediaSession 可能尚未就绪
                // （通知监听服务刚绑定、或抖音未播放），会导致 View 刚创建就被设为 GONE，
                // 用户以为没启动成功。先强制显示 2 秒，再进入正常的显示/隐藏判断。
                showFloatingButton()
                floatingView?.visibility = View.VISIBLE
                isVisible = true
                handler.postDelayed({
                    checkShouldShow()           // 宽限期后开始正常检测
                    handler.post(foregroundChecker)  // 启动定时前台检测
                }, 2000L)
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
        // 引用存在且仍挂在窗口上 → 无需重建，直接复用
        if (floatingView?.isAttachedToWindow == true) return
        // 残留引用：View 已被系统回收或从窗口移除（权限变化、系统回收等），
        // 但 floatingView 引用还在，旧逻辑会直接 return 导致永远不重建。
        // 这里清空引用，走下面的重建流程。
        floatingView = null
        val button = FloatingView(this)

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
            // 位置记忆：若之前拖动并保存过位置（buttonX>=0），启动时恢复；否则用默认右侧位置。
            val config = ConfigManager.get(this@FloatingButtonService)
            x = if (config.buttonX >= 0) config.buttonX else (60 * density).toInt()
            y = if (config.buttonY >= 0) config.buttonY else 0
        }

        setupButtonEvents(button)
        floatingView = button
        windowManager.addView(floatingView, layoutParams)
        Log.d(TAG, "悬浮按钮已显示")

        // 重建后恢复收起态：若之前处于收起态（小长方形），View 被系统移除重建会丢失该状态，
        // 这里重新应用收起布局，保证重建前后形态一致。
        if (isStealth) enterStealth()
    }

    /**
     * 设置按钮的点击、拖动、长按事件
     */
    private fun setupButtonEvents(button: View) {
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
                    // 按下时取消可能正在等待的贴边收起计时（重新开始判定）
                    cancelEdgeHold()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initialTouchX - event.rawX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true

                    if (isStealth) {
                        // 收起态小长方形：横向滑动 / 向屏幕内侧拖动 → 恢复正常按钮。
                        // 水平位移超过阈值即恢复；恢复后把初始参照对齐到恢复后的位置，
                        // 后续拖动就能平滑地把按钮从边缘带到手指位置（曲面屏更顺手）。
                        if (Math.abs(dx) > 30) {
                            restoreFromStealth()
                            initialX = layoutParams!!.x
                            initialY = layoutParams!!.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                        }
                        true
                    } else if (isDragging) {
                        // 正常态拖动：更新位置
                        layoutParams!!.x = initialX + dx
                        layoutParams!!.y = initialY + dy
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        // 拖到屏幕边缘并保持 → 启动 2 秒计时自动收起；离开边缘则取消。
                        if (isViewAtEdge()) scheduleEdgeHold() else cancelEdgeHold()
                        true
                    } else {
                        true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // 抬手：取消贴边收起计时（若计时已触发，isStealth 已变为 true）
                    cancelEdgeHold()
                    val pressDuration = System.currentTimeMillis() - downTime
                    // 拖动结束（正常态）：记忆当前位置，下次启动恢复
                    if (isDragging && !isStealth) saveButtonPosition()
                    if (!isDragging) {
                        if (pressDuration > 600) {
                            // 长按：收起/恢复切换
                            if (isStealth) restoreFromStealth() else enterStealth()
                        } else {
                            // 短按：收起态恢复，正常态跳过广告
                            if (isStealth) restoreFromStealth() else performSkip()
                        }
                    }
                    true
                }
                else -> {
                    // CANCEL 等事件也清理贴边计时，避免泄漏
                    cancelEdgeHold()
                    false
                }
            }
        }
    }

    /** 判断悬浮按钮是否已贴近屏幕左/右边缘（用于触发贴边 2 秒自动收起） */
    private fun isViewAtEdge(): Boolean {
        val lp = layoutParams ?: return false
        val density = resources.displayMetrics.density
        val threshold = (edgeThresholdDp * density).toInt()
        // gravity = END：lp.x 是相对右边缘的偏移，x<=threshold 表示贴近屏幕右边缘；
        // view 左边 = screenWidth - lp.x - lp.width，<=threshold 表示贴近屏幕左边缘。
        val atRightEdge = lp.x <= threshold
        val atLeftEdge = (screenWidth - lp.x - lp.width) <= threshold
        return atRightEdge || atLeftEdge
    }

    /** 启动贴边 2 秒计时，到点自动收起（已在计时中则不重复启动） */
    private fun scheduleEdgeHold() {
        if (edgeHoldRunnable != null) return
        edgeHoldRunnable = Runnable {
            edgeHoldRunnable = null
            if (!isStealth) enterStealth()
        }
        handler.postDelayed(edgeHoldRunnable!!, edgeHoldMs)
    }

    /** 取消贴边收起计时 */
    private fun cancelEdgeHold() {
        edgeHoldRunnable?.let { handler.removeCallbacks(it) }
        edgeHoldRunnable = null
    }

    /** 记忆当前悬浮窗位置到 SharedPreferences（供下次启动恢复） */
    private fun saveButtonPosition() {
        val lp = layoutParams ?: return
        ConfigManager.get(this).apply {
            buttonX = lp.x
            buttonY = lp.y
        }
        Log.d(TAG, "已记忆悬浮窗位置: x=${lp.x}, y=${lp.y}")
    }

    /**
     * 手动点击触发的跳过（弹 Toast 提示）
     */
    private fun performSkip() {
        triggerSkip(isAuto = false)
    }

    /**
     * 统一的跳过入口：手动点击与自动检测都走这里，保证行为与统计一致。
     * @param isAuto true=自动检测触发（不弹 Toast，避免打扰）；false=手动点击
     */
    private fun triggerSkip(isAuto: Boolean) {
        val success = MediaControlService.skipDouyinAd(this)
        lastSkipTime = System.currentTimeMillis()
        if (success) {
            // 跳过成功：累加今日/本周统计，给用户成就感
            ConfigManager.get(this).incrementSkipCount()
            if (!isAuto) {
                Toast.makeText(this, "已跳过", Toast.LENGTH_SHORT).show()
            }
            Log.d(TAG, "${if (isAuto) "自动" else "手动"}跳过成功")
        } else {
            Log.d(TAG, "${if (isAuto) "自动" else "手动"}跳过失败")
        }
    }

    /**
     * 自动跳过广告：
     * 1) 前台门控：只在已启用的目标 App（抖音/抖音极速版）前台时才尝试，避免在其他应用误操作；
     * 2) 双阶段确认：首次检测到广告只记录时间，下一轮仍检测到且间隔 >= 600ms 才真正跳过，
     *    减少界面瞬态文本闪现导致的误触（经验：单次命中立即执行误触率高）；
     * 3) 节流：2 秒内不重复跳过。
     * 用户反馈：即使倒计时中点击悬浮窗也能跳过，因此检测到广告即可跳过，无需等倒计时结束。
     */
    private fun tryAutoSkip() {
        val now = System.currentTimeMillis()
        // 节流：避免短时间内重复跳过
        if (now - lastSkipTime < skipThrottleMs) return

        // 无障碍连接检查：自动跳过依赖无障碍读屏，未连接则无法检测广告。
        // 每 5 秒警告一次，便于从日志直接看出根因（而不是静默失败）。
        if (!SkipAdAccessibilityService.isRunning()) {
            if (now - lastA11yWarnTime > a11yWarnIntervalMs) {
                lastA11yWarnTime = now
                Log.w(TAG, "自动跳过未执行：无障碍服务未连接，请在系统设置中开启本应用的无障碍权限")
            }
            adFirstDetectedAt = 0L
            return
        }

        val config = ConfigManager.get(this)
        // 前台门控：只在目标 App 前台时才跳过，其他应用一律不动作
        val fgPkg = SkipAdAccessibilityService.getForegroundPackage()
        if (fgPkg == null || !config.isAppEnabled(fgPkg)) {
            adFirstDetectedAt = 0L   // 不在目标 App，重置确认计时
            return
        }

        // 通过无障碍服务检测广告界面（文案已修正为抖音实际显示的文字）
        val isAd = SkipAdAccessibilityService.isAdShowing()
        if (!isAd) {
            if (adFirstDetectedAt != 0L) Log.d(TAG, "广告信号消失，重置确认计时")
            adFirstDetectedAt = 0L
            return
        }

        // 双阶段确认：首次命中只记录，不跳
        if (adFirstDetectedAt == 0L) {
            adFirstDetectedAt = now
            Log.d(TAG, "首次检测到广告，等待二次确认（${adConfirmDelayMs}ms）前台=$fgPkg")
            return
        }
        // 间隔不足则继续等
        if (now - adFirstDetectedAt < adConfirmDelayMs) return

        // 二次确认仍为广告 → 执行跳过
        Log.d(TAG, "二次确认仍为广告，执行自动跳过（前台=$fgPkg）")
        adFirstDetectedAt = 0L
        triggerSkip(isAuto = true)
    }

    /**
     * 进入收起状态：变成小长方形贴边显示（不再藏 2/3 + 变透明）
     */
    private fun enterStealth() {
        val button = floatingView ?: return
        val lp = layoutParams ?: return
        val density = resources.displayMetrics.density

        // 保存当前位置和尺寸，恢复时还原（修 Bug1：旧逻辑恢复时不还原，导致按钮停在边缘/尺寸错乱）
        normalX = lp.x
        normalWidth = lp.width
        normalHeight = lp.height

        // 收起态小长方形尺寸：竖条，宽 10dp、高 55dp（加大以便曲面屏边缘好点中恢复）
        val rectWidth = (10 * density).toInt()
        val rectHeight = (55 * density).toInt()

        // 判断按钮在屏幕左半还是右半，决定贴哪边（跟随当前位置）
        val centerX = screenWidth / 2
        val currentCenterX = screenWidth - lp.x - lp.width / 2

        lp.width = rectWidth
        lp.height = rectHeight

        if (currentCenterX > centerX) {
            // 靠右 → 贴右边缘（gravity 含 END，x=0 即右边缘贴屏幕右边）
            lp.x = 0
        } else {
            // 靠左 → 贴左边缘（END gravity 下 x = 屏宽 - 条宽，使左边缘贴屏幕左边）
            lp.x = screenWidth - rectWidth
        }
        // 垂直方向由 gravity CENTER_VERTICAL 保证居中，y 保持不变

        // 切换为收起态绘制：半透明圆角小长方形（透明度由 onDraw 内部控制）
        button.setCollapsed(true)
        button.alpha = 1.0f

        isStealth = true
        windowManager.updateViewLayout(button, lp)
        Toast.makeText(this, "已收起，点击恢复", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "进入收起状态(小长方形)")
    }

    /**
     * 从收起状态恢复：还原位置、尺寸和正常绘制
     */
    private fun restoreFromStealth() {
        val button = floatingView ?: return
        val lp = layoutParams ?: return

        // 恢复收起前的位置和尺寸
        lp.x = normalX
        lp.width = normalWidth
        lp.height = normalHeight
        button.alpha = 1.0f
        // 恢复正常按钮绘制（不再画小长方形）
        button.setCollapsed(false)

        isStealth = false
        windowManager.updateViewLayout(button, lp)
        Log.d(TAG, "恢复正常状态")
    }

    /**
     * 刷新按钮样式（配置变更后调用）
     */
    private fun refreshButtonStyle() {
        val button = floatingView ?: return
        val config = ConfigManager.get(this)
        val density = resources.displayMetrics.density

        // 触发 FloatingView 重绘：内部 onDraw 会读取最新配置（颜色/形状/文字/内容模式/图片）
        button.updateConfig()

        // 更新固定宽高（尺寸变化时需要更新 LayoutParams）
        val sizePx = (config.buttonSize * density).toInt()
        layoutParams?.width = sizePx
        layoutParams?.height = sizePx
        // 配置变更视为退出收起态，恢复正常绘制（否则尺寸变了但还画小长方形）
        button.setCollapsed(false)
        layoutParams?.let { windowManager.updateViewLayout(button, it) }

        isStealth = false
        Log.d(TAG, "按钮样式已刷新")
    }

    /**
     * 维持悬浮窗永久显示。
     *
     * 用户决定：悬浮窗一经启动即永久显示，不再随前台应用切换而隐藏。
     * 本方法在每秒轮询中被调用，负责校验 View 的真实挂载状态，
     * 若被系统意外移除则立即重建，保证悬浮窗始终可见。
     */
    private fun checkShouldShow() {
        // 用户决定：悬浮窗启动后永久显示，不再随前台应用切换而隐藏。
        // 因此 shouldShow 恒为 true，不做任何前台检测与隐藏判断。
        // 同时每次轮询都校验 View 的真实挂载状态：若被系统意外移除（isAttachedToWindow=false），
        // 立即重建，保证悬浮窗始终可见（解决部分 ROM 切换前台时回收 overlay 导致消失的问题）。
        val isAttached = floatingView?.isAttachedToWindow == true
        if (!isAttached) {
            Log.d(TAG, "永久显示: View未挂载(引用=${floatingView != null})，执行重建")
            showFloatingButton()
        }
        // 确保 View 可见（即使之前被设为 GONE 也恢复）
        if (floatingView?.visibility != View.VISIBLE) {
            floatingView?.visibility = View.VISIBLE
        }
        isVisible = true
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
     * 检查是否已授予「使用情况访问」权限
     * PACKAGE_USAGE_STATS 是特殊权限，需用户在系统设置手动开启，无法运行时动态申请。
     * 用 AppOpsManager 检查授权状态（API 23+，本项目 minSdk 24 满足）。
     */
    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.e(TAG, "检查使用情况访问权限失败: ${e.message}")
            false
        }
    }

    /**
     * 通过 UsageStatsManager 获取当前前台应用包名
     *
     * 原理：queryUsageStats 返回近期各 app 的使用统计，取 lastTimeUsed（最后一次被使用/切前台时间）
     * 最大的那个，即为当前或最近的前台应用。
     * 相比无障碍服务的 rootInActiveWindow，此方式不受窗口焦点影响，跨进程切换更可靠，
     * 且不依赖常驻无障碍服务。
     */
    private fun getForegroundPackageByUsageStats(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            // 查最近 1 小时的统计（时间范围太短可能因系统聚合延迟返回空数据）
            val stats: List<UsageStats> = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 3600_000L, now
            )
            if (stats.isEmpty()) return null
            var recent: UsageStats? = null
            for (s in stats) {
                if (recent == null || s.lastTimeUsed > recent.lastTimeUsed) {
                    recent = s
                }
            }
            recent?.packageName
        } catch (e: Exception) {
            Log.e(TAG, "UsageStats 取前台包名失败: ${e.message}")
            null
        }
    }

    /**
     * 判断当前前台应用的类别，决定悬浮窗显示策略
     *
     * 返回值：
     *   1 = 本应用自身        → 无条件显示
     *   2 = 系统UI（通知栏等） → 保持当前显示状态（不主动显隐）
     *   3 = 已启用的目标 App  → 需有活跃 MediaSession 才显示
     *   0 = 取不到 / 其他 App  → 隐藏
     *
     * 数据源优先级：
     *   1. UsageStatsManager（已授权「使用情况访问」时，最可靠，不依赖无障碍）
     *   2. 无障碍服务记录的前台包名（回退，双保险；用户若仍开着无障碍可补充）
     *
     * 关键修复：旧逻辑在取不到前台时默认返回 true（倾向显示），导致悬浮窗在桌面/其他 app
     * 乱飘；现改为默认隐藏（返回 0），只在明确识别为本 app / 系统UI / 目标 app 时才显示。
     */
    private fun getForegroundCategory(config: ConfigManager): Int {
        // 优先用 UsageStatsManager（不依赖无障碍服务）
        var pkg: String? = null
        if (hasUsageStatsPermission()) {
            pkg = getForegroundPackageByUsageStats()
        }
        // 回退：无障碍服务记录的前台包名（双保险）
        if (pkg.isNullOrEmpty()) {
            pkg = SkipAdAccessibilityService.getForegroundPackage()
        }

        Log.d(TAG, "前台检测: 包名=$pkg, UsageStats授权=${hasUsageStatsPermission()}")

        if (pkg.isNullOrEmpty()) {
            // 两个数据源都取不到 → 无法判断，保守隐藏
            return 0
        }
        if (pkg == packageName) return 1                          // 本 app
        if (pkg in systemUiPackages) return 2                      // 系统 UI
        return if (config.isAppEnabled(pkg)) 3 else 0              // 目标 app / 其他
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
