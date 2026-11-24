package com.example.skipad

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.session.MediaSession
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
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
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowTransportControls
import java.time.Duration

@Implements(MediaController.TransportControls::class)
class CountingTransportShadow : ShadowTransportControls() {
    @Implementation override fun skipToNext() {
        super.skipToNext()
        count++
    }
    companion object { var count = 0 }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [RootWindowShadow::class, QueryNodeShadow::class, CountingTransportShadow::class])
class EventDetectionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val pkg = "com.ss.android.ugc.aweme"
    private lateinit var reader: SkipAdAccessibilityService

    private fun node(text: String = "", id: String? = null, bounds: Rect = Rect(10, 600, 100, 630)) =
        AccessibilityNodeInfo.obtain().apply {
            this.text = text
            packageName = pkg
            isVisibleToUser = true
            viewIdResourceName = id
            setBoundsInScreen(bounds)
        }

    private fun player(ad: String? = null, excluded: Boolean = false): AccessibilityNodeInfo {
        val root = node(bounds = Rect(0, 0, 1080, 1920))
        shadowOf(root).addChild(node("第12集"))
        shadowOf(root).addChild(node("选集"))
        if (ad != null) {
            if (excluded) {
                val panel = node(id = "$pkg:id/comment_list")
                shadowOf(panel).addChild(node(ad))
                shadowOf(root).addChild(panel)
            } else shadowOf(root).addChild(node(ad))
        }
        return root
    }

    @Before fun setup() {
        CountingTransportShadow.count = 0
        ConfigManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(false)
        ShadowSettings.setCanDrawOverlays(true)
        RootWindowShadow.root = player()
        reader = Robolectric.buildService(SkipAdAccessibilityService::class.java).create().get()
        reader.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(reader)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
    }

    @After fun cleanup() {
        stopLeakedFloatingService()
        RootWindowShadow.onRead = null
        RootWindowShadow.root = null
        SkipAdAccessibilityService::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun detected(): Boolean = reader.javaClass.getDeclaredMethod("detectAd")
        .apply { isAccessible = true }.invoke(reader) as Boolean

    private fun contentEvent() {
        reader.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = pkg
        })
    }

    private fun startAutomaticService(): org.robolectric.android.controller.ServiceController<FloatingButtonService> {
        ConfigManager.get(context).apply { autoSkip = true; detectIntervalMs = 2000 }
        val media = MediaSession(context, "test").controller
        shadowOf(media).setPackageName(pkg)
        shadowOf(context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).addController(media)
        val service = floatingServiceOnTestLooper()
        service.get().onStartCommand(Intent(context, FloatingButtonService::class.java)
            .setAction(FloatingButtonService.ACTION_START), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        return service
    }

    @Test fun isolatedAdTextOutsideShortDramaPlayerIsNotEnough() {
        RootWindowShadow.root = node("广告")
        assertFalse(detected())
    }

    @Test fun commentAdWordInsidePlayerDoesNotTriggerSkip() {
        RootWindowShadow.root = player("广告", excluded = true)
        assertFalse(detected())
    }

    @Test fun largeCommentPanelCannotBeUsedToConfirmPlayerRestoration() {
        val root = player()
        val panel = node(id = "$pkg:id/comment_list", bounds = Rect(0, 800, 1080, 1920))
        shadowOf(panel).addChild(node("广告"))
        shadowOf(root).addChild(panel)
        assertFalse(AdDetector.inspectFocused(root, pkg)!!.inPlayer)
    }

    @Test fun realCountdownRemainsRecognizableAlongsideMembershipEntry() {
        RootWindowShadow.root = player("5秒后可继续看下一集").apply { shadowOf(this).addChild(node("免广告")) }
        assertTrue(AdDetector.inspectFocused(RootWindowShadow.root!!, pkg).toString(), detected())
    }

    @Test fun sampledPhoneLayoutWithEpisodeDescriptionAndDramaTitleIsPlayer() {
        // 真机布局：没有“选集”，集数拼在介绍中，独立的“短剧 · 剧名”节点提供另一锚点。
        val root = node(bounds = Rect(0, 0, 1236, 2772))
        shadowOf(root).addChild(node("第36集 | 奇幻玄幻 | 这是用于回归测试的一段较长短剧介绍文案 #新剧来袭 #... 展开",
            "$pkg:id/desc"))
        shadowOf(root).addChild(node("短剧 · 示例剧名", "$pkg:id/cq-"))
        shadowOf(root).addChild(node("5秒后可继续看下一集"))
        RootWindowShadow.root = root
        assertTrue(detected())
        val observation = AdDetector.inspect(root, pkg)
        assertTrue(observation!!.inPlayer)
    }

    @Test fun sampledNormalVideoWithoutSelectionButtonIsRecognizedAsPlayer() {
        val root = node(bounds = Rect(0, 0, 1236, 2772))
        shadowOf(root).addChild(node("第36集 | 奇幻玄幻 | 这是用于回归测试的一段较长短剧介绍文案 #新剧来袭 #... 展开"))
        shadowOf(root).addChild(node("短剧 · 示例剧名"))
        val observation = AdDetector.inspect(root, pkg)!!
        assertTrue(observation.inPlayer)
        assertEquals(AdConfidence.NONE, observation.confidence)
    }

    @Test fun dramaTitleAloneDoesNotTurnOrdinaryAdTextIntoPlayer() {
        val root = node(bounds = Rect(0, 0, 1080, 1920))
        shadowOf(root).addChild(node("短剧 · 示例剧名"))
        shadowOf(root).addChild(node("广告"))
        RootWindowShadow.root = root
        assertFalse(detected())
    }

    @Test fun sampledAdCaptionNeedsExitDramaControlAndVerifiedCaptionId() {
        val root = node(bounds = Rect(0, 0, 1236, 2772))
        shadowOf(root).addChild(node("退出短剧", "$pkg:id/b4=", Rect(189, 185, 413, 262)))
        shadowOf(root).addChild(node("这是用于回归测试的商品介绍... 展开广告", "$pkg:id/desc", Rect(42, 2318, 914, 2475)))
        shadowOf(root).addChild(node("免广告", bounds = Rect(895, 200, 1021, 249)))
        assertTrue(AdDetector.inspect(root, pkg)!!.isAd)
        // 相同后缀在普通视频介绍中，缺少已验证的退出短剧结构，不能自动跳过。
        val ordinary = node(bounds = Rect(0, 0, 1236, 2772))
        shadowOf(ordinary).addChild(node("这个视频不是广告", "$pkg:id/desc", Rect(42, 2318, 914, 2475)))
        assertFalse(AdDetector.inspect(ordinary, pkg)!!.isAd)
    }

    @Test fun strongEvidenceCanSkipBeforeSlowFallbackPoll() {
        ConfigManager.get(context).apply { autoSkip = true; detectIntervalMs = 2000 }
        val media = MediaSession(context, "test").controller
        shadowOf(media).setPackageName(pkg)
        shadowOf(context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).addController(media)
        val service = floatingServiceOnTestLooper()
        service.get().onStartCommand(Intent(context, FloatingButtonService::class.java)
            .setAction(FloatingButtonService.ACTION_START), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        RootWindowShadow.root = player("5秒后可继续看下一集")
        contentEvent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(119))
        assertNotEquals(PlaybackState.ACTION_SKIP_TO_NEXT, shadowOf(media.transportControls).lastPerformedAction)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(281))
        assertEquals(PlaybackState.ACTION_SKIP_TO_NEXT, shadowOf(media.transportControls).lastPerformedAction)
        service.destroy()
    }

    @Test fun eventStormDoesNotKeepPostponingConfirmation() {
        ConfigManager.get(context).apply { autoSkip = true; detectIntervalMs = 2000 }
        val media = MediaSession(context, "test").controller
        shadowOf(media).setPackageName(pkg)
        shadowOf(context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).addController(media)
        val service = floatingServiceOnTestLooper()
        service.get().onStartCommand(Intent(context, FloatingButtonService::class.java)
            .setAction(FloatingButtonService.ACTION_START), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        RootWindowShadow.root = player("5秒后可继续看下一集")
        repeat(20) {
            contentEvent()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        }
        assertEquals(PlaybackState.ACTION_SKIP_TO_NEXT, shadowOf(media.transportControls).lastPerformedAction)
        service.destroy()
    }

    @Test fun countdownChangesAndBriefWindowLossCannotSkipTwice() {
        val service = startAutomaticService()
        RootWindowShadow.root = player("5秒后可继续看下一集")
        contentEvent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(1, CountingTransportShadow.count)
        RootWindowShadow.root = null
        contentEvent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        RootWindowShadow.root = player("4秒后可继续看下一集")
        contentEvent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        assertEquals(1, CountingTransportShadow.count)
        service.destroy()
    }

    @Test fun restoredPlayerAllowsNextAdBeforeOldFiveSecondLimit() {
        val service = startAutomaticService()
        RootWindowShadow.root = player("5秒后可继续看下一集")
        contentEvent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(1, CountingTransportShadow.count)
        RootWindowShadow.root = player()
        contentEvent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
        assertEquals(1, ConfigManager.get(context).getTodaySkipCount())
        RootWindowShadow.root = player("8秒后可继续看下一集")
        contentEvent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(2, CountingTransportShadow.count)
        service.destroy()
    }

    @Test fun switchingAppsDuringConfirmationCancelsRequest() {
        val service = startAutomaticService()
        RootWindowShadow.root = player("5秒后可继续看下一集")
        contentEvent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        RootWindowShadow.root = node("其他应用").apply { packageName = "com.other.app" }
        reader.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.other.app"
        })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(0, CountingTransportShadow.count)
        service.destroy()
    }

    @Test fun disablingAutomaticSkipDuringConfirmationCancelsRequest() {
        val service = startAutomaticService()
        RootWindowShadow.root = player("5秒后可继续看下一集")
        contentEvent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        ConfigManager.get(context).autoSkip = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(0, CountingTransportShadow.count)
        service.destroy()
    }

    @Test fun mediumEvidenceWaitsLongerThanStrongEvidence() {
        val service = startAutomaticService()
        RootWindowShadow.root = player("广告")
        contentEvent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(0, CountingTransportShadow.count)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(1, CountingTransportShadow.count)
        service.destroy()
    }

    @Test fun pausingWhileWindowReadReturnsCannotSendStaleSkip() {
        val service = startAutomaticService()
        RootWindowShadow.root = player("5秒后可继续看下一集")
        contentEvent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        RootWindowShadow.onRead = {
            RootWindowShadow.onRead = null
            service.get().onStartCommand(Intent(context, FloatingButtonService::class.java)
                .setAction(FloatingButtonService.ACTION_PAUSE_AUTO), 0, 2)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertFalse(ConfigManager.get(context).autoSkip)
        assertEquals(0, CountingTransportShadow.count)
        service.destroy()
    }
}
