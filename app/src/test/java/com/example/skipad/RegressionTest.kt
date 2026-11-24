package com.example.skipad

import android.app.Service
import android.app.KeyguardManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import android.accessibilityservice.AccessibilityService
import java.io.File

@Implements(AccessibilityService::class)
class RootWindowShadow {
    @Implementation
    fun getWindows(): List<android.view.accessibility.AccessibilityWindowInfo> = windowList
    @Implementation
    fun getRootInActiveWindow(): AccessibilityNodeInfo? {
        onRead?.invoke()
        return root
    }

    companion object {
        @Volatile var windowList: List<android.view.accessibility.AccessibilityWindowInfo> = emptyList()
        @Volatile var root: AccessibilityNodeInfo? = null
        @Volatile var onRead: (() -> Unit)? = null
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [RootWindowShadow::class, QueryNodeShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RegressionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val target = "com.ss.android.ugc.aweme"

    @Before fun reset() {
        ConfigManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(false)
    }

    @After fun clearRoot() {
        stopLeakedFloatingService()
        RootWindowShadow.onRead = null
        RootWindowShadow.root = null
        SkipAdAccessibilityService::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun playerRoot(text: String, pkg: String = target, visible: Boolean = true): AccessibilityNodeInfo {
        fun node(label: String, bounds: Rect, shown: Boolean = true) = AccessibilityNodeInfo.obtain().apply {
            this.text = label
            packageName = pkg
            isVisibleToUser = shown
            setBoundsInScreen(bounds)
        }
        return node("", Rect(0, 0, 1080, 1920)).apply {
            shadowOf(this).addChild(node("第12集", Rect(10, 100, 100, 130)))
            shadowOf(this).addChild(node("选集", Rect(100, 100, 160, 130)))
            shadowOf(this).addChild(node(text, Rect(10, 1500, 100, 1530), visible))
        }
    }

    private fun connectReader(text: String = "广告") {
        RootWindowShadow.root = playerRoot(text)
        val svc = Robolectric.buildService(SkipAdAccessibilityService::class.java).create().get()
        svc.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(svc)
    }

    private fun mediaController(pkg: String): MediaController {
        val session = MediaSession(context, pkg)
        val controller = session.controller
        shadowOf(controller).setPackageName(pkg)
        shadowOf(context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).addController(controller)
        return controller
    }

    private fun detects(text: String, pkg: String = target, visible: Boolean = true): Boolean {
        RootWindowShadow.root = playerRoot(text, pkg, visible)
        val svc = Robolectric.buildService(SkipAdAccessibilityService::class.java).create().get()
        assertSame("测试必须注入真实根窗口", RootWindowShadow.root, svc.rootInActiveWindow)
        val method = svc.javaClass.getDeclaredMethod("detectAd").apply { isAccessible = true }
        return method.invoke(svc) as Boolean
    }

    @Test fun membershipAndCommentTextMustNotSkipEpisodes() {
        for (text in listOf("免广告", "无广告", "去广告", "不是广告", "这个视频没有广告")) {
            assertFalse(text, detects(text))
        }
    }

    @Test fun numberedAdBadgeIsRecognized() { assertTrue(detects("广告1/3")) }
    @Test fun countdownIsRecognized() { assertTrue(detects("5秒后可继续看下一集")) }
    @Test fun otherApplicationsAndHiddenNodesAreIgnored() {
        assertFalse(detects("广告", "com.other.app"))
        assertFalse(detects("广告", visible = false))
    }

    @Test @Config(sdk = [24]) fun notificationWorksOnAndroid7() {
        val svc = floatingServiceOnTestLooper().get()
        val method = svc.javaClass.getDeclaredMethod("createNotification").apply { isAccessible = true }
        assertNotNull(method.invoke(svc))
    }

    @Test fun pictureAndTextHonorTransparency() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val file = File(context.filesDir, "regression.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ConfigManager.get(context).apply {
            contentMode = ConfigManager.CONTENT_IMAGE
            imagePath = file.absolutePath
            buttonAlpha = 64
            buttonText = ""
            buttonShape = ConfigManager.SHAPE_SQUARE
        }
        val view = FloatingView(context)
        view.layout(0, 0, 100, 100)
        val result = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(result))
        assertEquals(64, Color.alpha(result.getPixel(50, 50)))
    }

    @Test fun drawingReusesImageUntilExplicitConfigRefresh() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val file = File(context.filesDir, "cached-drag-image.png")
        fun save(color: Int, modified: Long) {
            source.eraseColor(color)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.setLastModified(modified)
        }
        save(Color.RED, 1000L)
        ConfigManager.get(context).apply {
            contentMode = ConfigManager.CONTENT_IMAGE; imagePath = file.absolutePath
            buttonAlpha = 255; buttonText = ""; buttonShape = ConfigManager.SHAPE_SQUARE
        }
        val view = FloatingView(context).apply { layout(0, 0, 100, 100) }
        fun pixel(): Int {
            val result = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(result))
            return result.getPixel(50, 50)
        }
        assertEquals(Color.RED, pixel())
        save(Color.BLUE, 2000L)
        assertEquals("拖动重绘不应反复访问图片文件", Color.RED, pixel())
        view.updateConfig()
        assertEquals(Color.BLUE, pixel())
    }

    @Test fun startWithoutOverlayPermissionStopsInsteadOfCrashing() {
        ConfigManager.get(context).apply { autoSkip = false; floatingEnabled = true }
        ShadowSettings.setCanDrawOverlays(false)
        val svc = floatingServiceOnTestLooper().get()
        val mode = svc.onStartCommand(Intent(context, svc.javaClass).setAction(FloatingButtonService.ACTION_START), 0, 1)
        assertEquals(Service.START_NOT_STICKY, mode)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun savedNegativeVerticalPositionIsRestored() {
        ShadowSettings.setCanDrawOverlays(true)
        ConfigManager.get(context).apply { buttonX = 80; buttonY = -240 }
        val controller = floatingServiceOnTestLooper()
        val svc = controller.get()
        svc.javaClass.getDeclaredMethod("showFloatingButton").apply { isAccessible = true }.invoke(svc)
        val lp = svc.javaClass.getDeclaredField("layoutParams").apply { isAccessible = true }.get(svc)
            as android.view.WindowManager.LayoutParams
        assertEquals(-240, lp.y)
        controller.destroy()
    }

    @Test fun explicitStopPersistsAcrossActivityRestarts() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        activity.javaClass.getDeclaredMethod("stopFloatingButton").apply { isAccessible = true }.invoke(activity)
        assertFalse(ConfigManager.get(context).floatingEnabled)
        controller.destroy()
    }

    @Test fun mediaRequestOnlyControlsOneSessionInTheForegroundApp() {
        connectReader()
        val background = mediaController("com.ss.android.ugc.aweme.lite")
        val first = mediaController(target)
        val duplicate = mediaController(target)
        val result = MediaControlService.skipDouyinAd(context, target)
        assertEquals(MediaControlService.Companion.SkipResult.SENT, result)
        assertEquals(PlaybackState.ACTION_SKIP_TO_NEXT, shadowOf(first.transportControls).lastPerformedAction)
        assertNotEquals(PlaybackState.ACTION_SKIP_TO_NEXT, shadowOf(background.transportControls).lastPerformedAction)
        assertNotEquals(PlaybackState.ACTION_SKIP_TO_NEXT, shadowOf(duplicate.transportControls).lastPerformedAction)
    }

    @Test fun disabledOrBackgroundTargetCannotBeControlled() {
        connectReader()
        mediaController(target)
        assertEquals(MediaControlService.Companion.SkipResult.FAILED,
            MediaControlService.skipDouyinAd(context, "com.ss.android.ugc.aweme.lite"))
        ConfigManager.get(context).setEnabledApps(emptySet())
        assertEquals(MediaControlService.Companion.SkipResult.FAILED,
            MediaControlService.skipDouyinAd(context, target))
    }

    @Test fun sendingCommandDoesNotCountUntilAdDisappearsStably() {
        connectReader()
        mediaController(target)
        val controller = floatingServiceOnTestLooper()
        val svc = controller.get()
        ShadowSettings.setCanDrawOverlays(true)
        svc.onStartCommand(Intent(context, svc.javaClass).setAction(FloatingButtonService.ACTION_START), 0, 1)
        val trigger = svc.javaClass.getDeclaredMethod("triggerSkip", Boolean::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
        val verify = svc.javaClass.getDeclaredMethod("verifyPendingSkip").apply { isAccessible = true }
        trigger.invoke(svc, false, target)
        assertEquals(0, ConfigManager.get(context).getTodaySkipCount())
        verify.invoke(svc)
        assertEquals(0, ConfigManager.get(context).getTodaySkipCount())
        RootWindowShadow.root = playerRoot("正在播放第2集")
        verify.invoke(svc)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        verify.invoke(svc)
        verify.invoke(svc)
        assertEquals(1, ConfigManager.get(context).getTodaySkipCount())
        controller.destroy()
    }

    @Test fun lostWindowDoesNotCountAsSuccessfulSkip() {
        connectReader()
        mediaController(target)
        val controller = floatingServiceOnTestLooper()
        val svc = controller.get()
        ShadowSettings.setCanDrawOverlays(true)
        svc.onStartCommand(Intent(context, svc.javaClass).setAction(FloatingButtonService.ACTION_START), 0, 1)
        svc.javaClass.getDeclaredMethod("triggerSkip", Boolean::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }.invoke(svc, false, target)
        RootWindowShadow.root = null
        val verify = svc.javaClass.getDeclaredMethod("verifyPendingSkip").apply { isAccessible = true }
        verify.invoke(svc)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        verify.invoke(svc)
        assertEquals(0, ConfigManager.get(context).getTodaySkipCount())
        controller.destroy()
    }

    @Test @LooperMode(LooperMode.Mode.LEGACY)
    fun repeatedStartDoesNotMultiplyScheduledWork() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = floatingServiceOnTestLooper()
        val svc = controller.get()
        val intent = Intent(context, svc.javaClass).setAction(FloatingButtonService.ACTION_START)
        svc.onStartCommand(intent, 0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        val before = shadowOf(Looper.getMainLooper()).scheduler.size()
        repeat(4) { svc.onStartCommand(intent, 0, it + 2) }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertEquals(before, shadowOf(Looper.getMainLooper()).scheduler.size())
        controller.destroy()
    }
}
