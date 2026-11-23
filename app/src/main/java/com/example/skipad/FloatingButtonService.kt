package com.example.skipad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.ViewConfiguration
import java.util.concurrent.atomic.AtomicLong
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
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
 * - 收起状态：
 *   - 按钮靠边显示为半透明小长条
 *   - 点击 → 恢复正常状态
 *
 * 显示逻辑：
 * - 用户启动后常驻显示，主动停止后保持停止。
 * - 广告识别、请求发送、页面确认分开处理；操作始终限定在当前目标应用。
 */
class FloatingButtonService : Service() {

    companion object {
        private const val TAG = "SkipAd_Floating"
        private const val CHANNEL_ID = "skip_ad_floating_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.example.skipad.START_FLOATING"
        const val ACTION_STOP = "com.example.skipad.STOP_FLOATING"
        const val ACTION_REFRESH = "com.example.skipad.REFRESH_FLOATING"
        @Volatile private var runningInstance: FloatingButtonService? = null

        internal fun isRunning(): Boolean = runningInstance?.pollingStarted == true

        internal fun onAccessibilityChange(pkg: String?, windowChange: Boolean) {
            val service = runningInstance ?: return
            if (!service.pollingStarted) return
            val config = ConfigManager.get(service)
            if (!config.autoSkip && service.pendingSkip == null) return
            if (!windowChange && (pkg == null || !config.isAppEnabled(pkg))) return
            val now = SystemClock.uptimeMillis()
            val delay = if (windowChange) 0L else maxOf(30L, service.lastDetectionAt + 80L - now)
            service.scheduleDetection(delay, "event")
        }
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: FloatingView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isStealth = false           // 是否隐身（收起）状态
    private var normalX = 0                 // 收起前的 x 坐标（恢复位置用，修 Bug1）
    private var normalWidth = 0             // 收起前的宽度（恢复尺寸用）
    private var normalHeight = 0            // 收起前的高度（恢复尺寸用）
    @Volatile private var isVisible = true            // 悬浮窗是否可见
    private var screenWidth = 0

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var detectionThread: HandlerThread
    private lateinit var detectionHandler: Handler
    @Volatile private var pollingStarted = false
    @Volatile private var interactionActive = false
    private val interactionGeneration = AtomicLong(0L)
    private var dragFramePending = false
    private val dragPositionFrame = Runnable {
        if (dragFramePending) {
            dragFramePending = false
            applyDragPosition()
        }
    }
    private val adGate = AdSkipGate()
    private data class PendingSkip(
        val target: String,
        val scope: String,
        val startedAt: Long,
        val automatic: Boolean,
        var fallbackTried: Boolean,
        var clearSince: Long? = null
    )
    @Volatile private var pendingSkip: PendingSkip? = null
    private var scheduledAt: Long? = null
    private var scheduledCause = "poll"
    @Volatile private var lastDetectionAt = 0L
    private var lastEvidenceSummary: String? = null
    private val detectionTask = Runnable {
        scheduledAt = null
        runDetection(scheduledCause)
    }

    /** 检测状态和跨进程查询串行在专用线程；主线程只处理窗口与触摸。 */
    private fun scheduleDetection(delayMs: Long, cause: String) {
        if (!pollingStarted) return
        if (Looper.myLooper() != detectionHandler.looper) {
            detectionHandler.post { scheduleDetection(delayMs, cause) }
            return
        }
        if (interactionActive) {
            adGate.cancelCandidate()
            pendingSkip?.clearSince = null
            return
        }
        val due = SystemClock.uptimeMillis() + delayMs.coerceAtLeast(0L)
        if (scheduledAt?.let { it <= due } == true) return
        detectionHandler.removeCallbacks(detectionTask)
        scheduledAt = due
        scheduledCause = cause
        detectionHandler.postAtTime(detectionTask, due)
    }

    private fun runDetection(cause: String) {
        if (!pollingStarted) return
        if (Looper.myLooper() != detectionHandler.looper) {
            scheduleDetection(0L, cause)
            return
        }
        if (interactionActive) {
            adGate.cancelCandidate()
            pendingSkip?.clearSince = null
            return
        }
        lastDetectionAt = SystemClock.uptimeMillis()
        if (!Settings.canDrawOverlays(this)) {
            pollingStarted = false
            stopSelf()
            return
        }
        try {
            val config = ConfigManager.get(this)
            val pending = pendingSkip
            if (pending != null) {
                if (pending.automatic && !config.autoSkip) {
                    pendingSkip = null
                    adGate.cancelCandidate()
                } else verifyPendingSkip()
            } else if (config.autoSkip && isVisible) {
                tryAutoSkip(cause)
            } else adGate.cancelCandidate()
            if (pendingSkip != null) scheduleDetection(100L, "verify")
        } catch (e: Exception) {
            adGate.cancelCandidate()
            pendingSkip = null
            Log.e(TAG, "广告检测失败，不执行跳过", e)
        }
    }

    // 贴边收起：拖到屏幕边缘并持续 2 秒不动 → 自动收起成小长方形
    private var edgeHoldRunnable: Runnable? = null
    private val edgeHoldMs = 2000L          // 贴边持续多久触发收起
    private val edgeThresholdDp = 14        // 距离屏幕边缘多少 dp 以内算「贴边」

    // 定时检测前台应用
    private val foregroundChecker = object : Runnable {
        override fun run() {
            if (!pollingStarted) return
            val config = ConfigManager.get(this@FloatingButtonService)
            try {
                checkShouldShow()
                runDetection("poll")
            } catch (e: Exception) {
                Log.e(TAG, "悬浮窗保活检测异常", e)
            }

            // 无论是否异常，都继续下一轮轮询；间隔取用户设置的「广告检测间隔」
            if (pollingStarted) handler.postDelayed(this, config.detectIntervalMs.toLong())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        screenWidth = resources.displayMetrics.widthPixels
        detectionThread = HandlerThread("SkipAdDetection", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        detectionHandler = Handler(detectionThread.looper)
        runningInstance = this

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = ConfigManager.get(this)
        if (intent?.action == ACTION_STOP) {
            config.floatingEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH) {
            if (floatingView?.isAttachedToWindow == true) refreshButtonStyle()
            else stopSelf()
            return if (pollingStarted) START_STICKY else START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this) || (intent == null && !config.floatingEnabled)) {
            Log.w(TAG, "未启动悬浮窗：缺少权限或用户已停止")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            if (!showFloatingButton()) {
                stopSelf()
                return START_NOT_STICKY
            }
            config.floatingEnabled = true
            if (!pollingStarted) {
                pollingStarted = true
                handler.post(foregroundChecker)
                Log.d(TAG, "启动唯一检测轮询")
                Log.i(TAG, "AdPipeline started auto=${config.autoSkip} fallback_ms=${config.detectIntervalMs}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动悬浮窗失败", e)
            stopSelf()
            return START_NOT_STICKY
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("抖音跳广告助手")
            .setContentText("悬浮按钮已运行")
            .setSmallIcon(android.R.drawable.ic_media_next)
            .build()
    }

    /**
     * 创建并显示悬浮按钮
     */
    private fun showFloatingButton(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        // 引用存在且仍挂在窗口上 → 无需重建，直接复用
        if (floatingView?.isAttachedToWindow == true) return true
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
            // 是否保存与坐标正负无关；上半屏的 y 为负数。
            val config = ConfigManager.get(this@FloatingButtonService)
            x = if (config.hasSavedPosition) config.buttonX else (60 * density).toInt()
            y = if (config.hasSavedPosition) config.buttonY else 0
        }

        setupButtonEvents(button)
        floatingView = button
        try {
            windowManager.addView(button, layoutParams)
        } catch (e: Exception) {
            floatingView = null
            Log.e(TAG, "无法创建悬浮窗", e)
            return false
        }
        Log.d(TAG, "悬浮按钮已显示")

        // 重建后恢复收起态：若之前处于收起态（小长方形），View 被系统移除重建会丢失该状态，
        // 这里重新应用收起布局，保证重建前后形态一致。
        if (isStealth) enterStealth()
        return true
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
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    interactionActive = true
                    interactionGeneration.incrementAndGet()
                    detectionHandler.post {
                        adGate.cancelCandidate()
                        pendingSkip?.clearSince = null
                    }
                    initialX = layoutParams!!.x
                    initialY = layoutParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    downTime = event.eventTime
                    cancelEdgeHold()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initialTouchX - event.rawX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) isDragging = true
                    if (isStealth) {
                        if (kotlin.math.abs(dx) > 30) {
                            restoreFromStealth()
                            initialX = layoutParams!!.x
                            initialY = layoutParams!!.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                        }
                    } else if (isDragging) {
                        layoutParams!!.x = initialX + dx
                        layoutParams!!.y = initialY + dy
                        if (!dragFramePending) {
                            dragFramePending = true
                            button.postOnAnimation(dragPositionFrame)
                        }
                        if (isViewAtEdge()) scheduleEdgeHold() else cancelEdgeHold()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelEdgeHold()
                    if (isDragging && !isStealth) {
                        val finalX = initialX + (initialTouchX - event.rawX).toInt()
                        val finalY = initialY + (event.rawY - initialTouchY).toInt()
                        if (layoutParams!!.x != finalX || layoutParams!!.y != finalY) {
                            layoutParams!!.x = finalX
                            layoutParams!!.y = finalY
                            dragFramePending = true
                        }
                    }
                    flushDragPosition()
                    if (isDragging && !isStealth) saveButtonPosition()
                    val pressDuration = event.eventTime - downTime
                    finishInteraction()
                    if (!isDragging) {
                        if (pressDuration > 600) {
                            if (isStealth) restoreFromStealth() else enterStealth()
                        } else {
                            if (isStealth) restoreFromStealth() else performSkip()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelEdgeHold()
                    flushDragPosition()
                    if (isDragging && !isStealth) saveButtonPosition()
                    finishInteraction()
                    true
                }
                else -> true
            }
        }
    }

    private fun applyDragPosition() {
        val view = floatingView ?: return
        val params = layoutParams ?: return
        if (!view.isAttachedToWindow) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "悬浮窗移动失败", e)
        }
    }

    private fun flushDragPosition() {
        if (!dragFramePending) return
        floatingView?.removeCallbacks(dragPositionFrame)
        dragFramePending = false
        applyDragPosition()
    }

    private fun finishInteraction() {
        interactionGeneration.incrementAndGet()
        interactionActive = false
        scheduleDetection(100L, "touch-end")
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
    private fun triggerSkip(isAuto: Boolean, target: String? = null) {
        if (!pollingStarted) return
        if (Looper.myLooper() != detectionHandler.looper) {
            detectionHandler.post { triggerSkip(isAuto, target) }
            return
        }
        submitSkip(isAuto, target ?: ForegroundAppResolver.current(this), null)
    }

    private fun showSkipMessage(message: String) {
        handler.post {
            if (pollingStarted) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun submitSkip(isAuto: Boolean, target: String?, expected: AdObservation?) {
        if (!pollingStarted || interactionActive || pendingSkip != null) return
        val generation = interactionGeneration.get()
        if (target == null || !ConfigManager.get(this).isAppEnabled(target)) {
            if (!isAuto) showSkipMessage("请先打开已勾选的抖音应用")
            return
        }
        // 不保留旧节点；发送前从当前窗口重新取得证据与作用域。
        val actual = SkipAdAccessibilityService.observeEvidence(target)
        if (isAuto && (actual?.isAd != true || actual.scope != expected?.scope ||
            actual.confidence != expected.confidence)) {
            adGate.cancelCandidate()
            return
        }
        if (!pollingStarted || interactionActive || generation != interactionGeneration.get()) {
            adGate.cancelCandidate()
            return
        }
        val now = SystemClock.uptimeMillis()
        val result = MediaControlService.skipDouyinAd(this, target) {
            pollingStarted && !interactionActive && generation == interactionGeneration.get()
        }
        if (result == MediaControlService.Companion.SkipResult.FAILED) {
            adGate.markFailed(now)
            if (!isAuto) showSkipMessage("未找到可用的播放会话或跳过按钮")
            return
        }
        if (actual?.isAd == true) {
            adGate.markSent(actual, now)
            pendingSkip = PendingSkip(target, actual.scope, now, isAuto,
                result == MediaControlService.Companion.SkipResult.CLICKED)
            scheduleDetection(100L, "verify")
        }
        Log.i(TAG, "AdPipeline request t=$now automatic=$isAuto confidence=${actual?.confidence}")
        if (!isAuto) showSkipMessage("已发送跳过请求")
    }

    /** 只在同一播放器内持续观测到广告消失才计数，未知页面不解除广告去重锁。 */
    private fun verifyPendingSkip() {
        val pending = pendingSkip ?: return
        val generation = interactionGeneration.get()
        val now = SystemClock.uptimeMillis()
        if (!ConfigManager.get(this).isAppEnabled(pending.target) ||
            ForegroundAppResolver.current(this) != pending.target) {
            pendingSkip = null
            adGate.cancelCandidate()
            return
        }
        val observation = SkipAdAccessibilityService.observeEvidence(pending.target)
        if (observation == null || !observation.inPlayer || observation.scope != pending.scope) {
            pendingSkip = null
            adGate.cancelCandidate()
            return
        }
        if (!pollingStarted || interactionActive || generation != interactionGeneration.get()) {
            adGate.cancelCandidate()
            pending.clearSince = null
            return
        }
        val decision = adGate.evaluate(observation, now)
        decision.recheckMs?.let { scheduleDetection(it, "rearm") }
        if (observation.confidence == AdConfidence.NONE) {
            val since = pending.clearSince ?: now.also { pending.clearSince = it }
            if (now - since >= 800L) {
                ConfigManager.get(this).incrementSkipCount()
                Log.i(TAG, "AdPipeline confirmed t=$now request_to_confirm_ms=${now - pending.startedAt}")
                pendingSkip = null
                return
            }
        } else {
            pending.clearSince = null
            if (observation.isAd && !pending.fallbackTried && now - pending.startedAt >= 2000L) {
                pending.fallbackTried = true
                SkipAdAccessibilityService.trySkipByClick(pending.target) {
                    pollingStarted && !interactionActive && generation == interactionGeneration.get()
                }
            }
        }
        if (now - pending.startedAt >= 6000L) {
            Log.i(TAG, "AdPipeline unconfirmed t=$now")
            pendingSkip = null
        }
    }

    private fun tryAutoSkip(cause: String) {
        val generation = interactionGeneration.get()
        val target = ForegroundAppResolver.current(this)
        if (!pollingStarted || interactionActive || generation != interactionGeneration.get()) {
            adGate.cancelCandidate()
            return
        }
        if (!SkipAdAccessibilityService.isRunning() || target == null ||
            !ConfigManager.get(this).isAppEnabled(target)) {
            adGate.cancelCandidate()
            return
        }
        val start = SystemClock.uptimeMillis()
        val observation = SkipAdAccessibilityService.observeEvidence(target)
        val now = SystemClock.uptimeMillis()
        if (!pollingStarted || interactionActive || generation != interactionGeneration.get()) {
            adGate.cancelCandidate()
            return
        }
        val summary = "${observation?.scope} ${observation?.confidence} ${observation?.reason}"
        if (summary != lastEvidenceSummary) {
            lastEvidenceSummary = summary
            Log.i(TAG, "AdPipeline observation t=$now scan_ms=${now - start} $summary")
        }
        val decision = adGate.evaluate(observation, now)
        if (decision.newCandidate) {
            Log.i(TAG, "AdPipeline candidate t=$now cause=$cause scan_ms=${now - start} " +
                "confidence=${observation?.confidence} reason=${observation?.reason}")
        }
        decision.recheckMs?.let { scheduleDetection(it, "confirm") }
        if (decision.send) submitSkip(true, target, observation)
    }

    /**
     * 进入收起状态：变成小长方形贴边显示（不再藏 2/3 + 变透明）
     */
    private fun enterStealth() {
        flushDragPosition()
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
        if (!Settings.canDrawOverlays(this)) {
            pollingStarted = false
            handler.removeCallbacks(foregroundChecker)
            stopSelf()
            return
        }
        // 用户决定：悬浮窗启动后永久显示，不再随前台应用切换而隐藏。
        // 因此 shouldShow 恒为 true，不做任何前台检测与隐藏判断。
        // 同时每次轮询都校验 View 的真实挂载状态：若被系统意外移除（isAttachedToWindow=false），
        // 立即重建，保证悬浮窗始终可见（解决部分 ROM 切换前台时回收 overlay 导致消失的问题）。
        val isAttached = floatingView?.isAttachedToWindow == true
        if (!isAttached) {
            Log.d(TAG, "永久显示: View未挂载(引用=${floatingView != null})，执行重建")
            if (!showFloatingButton()) {
                pollingStarted = false
                stopSelf()
                return
            }
        }
        // 确保 View 可见（即使之前被设为 GONE 也恢复）
        if (floatingView?.visibility != View.VISIBLE) {
            floatingView?.visibility = View.VISIBLE
        }
        isVisible = true
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingStarted = false
        if (runningInstance === this) runningInstance = null
        interactionActive = false
        interactionGeneration.incrementAndGet()
        floatingView?.removeCallbacks(dragPositionFrame)
        dragFramePending = false
        handler.removeCallbacksAndMessages(null)
        detectionHandler.removeCallbacksAndMessages(null)
        detectionHandler.post {
            pendingSkip = null
            adGate.cancelCandidate()
            scheduledAt = null
        }
        detectionThread.quitSafely()
        floatingView?.let {
            if (it.isAttachedToWindow) runCatching { windowManager.removeView(it) }
            floatingView = null
        }
    }
}
