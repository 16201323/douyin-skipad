package com.example.skipad

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [RootWindowShadow::class, QueryNodeShadow::class])
class DragResponsivenessTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var controller: ServiceController<FloatingButtonService>? = null
    private val releaseRead = CountDownLatch(1)

    @Before fun setup() {
        ConfigManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigManager.get(context).apply { floatingEnabled = true; autoSkip = false }
        ShadowSettings.setCanDrawOverlays(true)
        RootWindowShadow.onRead = null
        RootWindowShadow.root = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.ss.android.ugc.aweme"
            isVisibleToUser = true
            setBoundsInScreen(Rect(0, 0, 1080, 1920))
        }
        val reader = Robolectric.buildService(SkipAdAccessibilityService::class.java).create().get()
        reader.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(reader)
    }

    @After fun cleanup() {
        releaseRead.countDown()
        RootWindowShadow.onRead = null
        controller?.destroy()
        RootWindowShadow.root = null
        SkipAdAccessibilityService::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun start(): FloatingButtonService {
        controller = Robolectric.buildService(FloatingButtonService::class.java).create()
        val service = controller!!.get()
        service.onStartCommand(Intent(context, service.javaClass).setAction(FloatingButtonService.ACTION_START), 0, 1)
        return service
    }

    private fun field(service: FloatingButtonService, name: String): Any =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)!!

    private fun touch(view: View, action: Int, x: Float, y: Float): Boolean {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        return try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private class RecordingWindows(private val delegate: WindowManager) : WindowManager by delegate {
        var updates = 0
        var x = 0
        var y = 0
        override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
            updates++
            val lp = params as WindowManager.LayoutParams
            x = lp.x; y = lp.y
            delegate.updateViewLayout(view, params)
        }
    }

    @Test fun dragConsumesDownSoTheWholeGestureBelongsToTheButton() {
        val service = start()
        val button = field(service, "floatingView") as View
        assertTrue(touch(button, MotionEvent.ACTION_DOWN, 200f, 200f))
        touch(button, MotionEvent.ACTION_CANCEL, 200f, 200f)
    }

    @Test fun moveBurstUsesOneFrameUpdateAndReleasePreservesLastPosition() {
        val service = start()
        val button = field(service, "floatingView") as View
        val windows = RecordingWindows(field(service, "windowManager") as WindowManager)
        service.javaClass.getDeclaredField("windowManager").apply { isAccessible = true }.set(service, windows)
        val lp = field(service, "layoutParams") as WindowManager.LayoutParams
        val startX = lp.x; val startY = lp.y
        touch(button, MotionEvent.ACTION_DOWN, 200f, 200f)
        repeat(20) { touch(button, MotionEvent.ACTION_MOVE, 200f + (it + 1) * 2, 200f + (it + 1) * 3) }
        assertEquals("同一帧内的 MOVE 不应直接重复调用窗口服务", 0, windows.updates)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(17))
        assertEquals(1, windows.updates)
        touch(button, MotionEvent.ACTION_MOVE, 260f, 275f)
        touch(button, MotionEvent.ACTION_UP, 260f, 275f)
        assertEquals(startX - 60, windows.x)
        assertEquals(startY + 75, windows.y)
        assertEquals(windows.x, ConfigManager.get(context).buttonX)
        assertEquals(windows.y, ConfigManager.get(context).buttonY)
    }

    @Test fun accessibilityQueriesRunOutsideUiThread() {
        ConfigManager.get(context).autoSkip = true
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).setIsInteractive(true)
        shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).setKeyguardLocked(false)
        val queryEntered = CountDownLatch(1)
        val threads = CopyOnWriteArrayList<Thread>()
        RootWindowShadow.onRead = {
            threads.add(Thread.currentThread())
            queryEntered.countDown()
        }
        start()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(queryEntered.await(2, TimeUnit.SECONDS))
        assertTrue("跨进程节点读取不能占用 UI 主线程", threads.none { it === Looper.getMainLooper().thread })
    }

    @Test fun releaseUsesUpCoordinateWhenItDiffersFromLastMove() {
        val service = start()
        val button = field(service, "floatingView") as View
        val lp = field(service, "layoutParams") as WindowManager.LayoutParams
        val x = lp.x; val y = lp.y
        touch(button, MotionEvent.ACTION_DOWN, 200f, 200f)
        touch(button, MotionEvent.ACTION_MOVE, 250f, 240f)
        touch(button, MotionEvent.ACTION_UP, 280f, 295f)
        assertEquals(x - 80, ConfigManager.get(context).buttonX)
        assertEquals(y + 95, ConfigManager.get(context).buttonY)
    }

    @Test fun slowBackgroundReadCannotBlockDragOrStartMoreReadsWhilePressed() {
        ConfigManager.get(context).autoSkip = true
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).setIsInteractive(true)
        shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).setKeyguardLocked(false)
        val entered = CountDownLatch(1)
        val reads = AtomicInteger()
        RootWindowShadow.onRead = {
            reads.incrementAndGet()
            entered.countDown()
            releaseRead.await(3, TimeUnit.SECONDS)
        }
        val service = start()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val button = field(service, "floatingView") as View
        val windows = RecordingWindows(field(service, "windowManager") as WindowManager)
        service.javaClass.getDeclaredField("windowManager").apply { isAccessible = true }.set(service, windows)
        touch(button, MotionEvent.ACTION_DOWN, 200f, 200f)
        touch(button, MotionEvent.ACTION_MOVE, 250f, 240f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(17))
        assertEquals("即使节点查询未返回，主线程仍应完成窗口移动", 1, windows.updates)
        releaseRead.countDown()
        val worker = field(service, "detectionHandler") as Handler
        fun drainWorker() {
            val drained = CountDownLatch(1)
            worker.post { drained.countDown() }
            assertTrue(drained.await(2, TimeUnit.SECONDS))
        }
        drainWorker()
        val before = reads.get()
        repeat(20) { FloatingButtonService.onAccessibilityChange("com.ss.android.ugc.aweme", false) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        drainWorker()
        assertEquals("按住拖动期间不应发起新的读屏查询", before, reads.get())
        touch(button, MotionEvent.ACTION_CANCEL, 250f, 240f)
    }
}
