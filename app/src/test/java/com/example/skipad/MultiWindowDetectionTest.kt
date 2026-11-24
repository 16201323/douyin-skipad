package com.example.skipad

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.graphics.Region
import android.os.PowerManager
import android.app.KeyguardManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [RootWindowShadow::class, QueryNodeShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MultiWindowDetectionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val target = "com.ss.android.ugc.aweme"
    private val map = "com.autonavi.minimap"
    private lateinit var screen: Rect

    @Before fun setup() {
        ConfigManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE).edit().clear().commit()
        RootWindowShadow.windowList = emptyList(); RootWindowShadow.root = null
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        val metrics = context.resources.displayMetrics
        screen = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        val service = Robolectric.buildService(SkipAdAccessibilityService::class.java).create().get()
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
    }
    @After fun cleanup() {
        RootWindowShadow.windowList = emptyList(); RootWindowShadow.root = null
        SkipAdAccessibilityService::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    private fun node(pkg: String, text: String, bounds: Rect) = AccessibilityNodeInfo.obtain().apply {
        packageName = pkg; this.text = text; isVisibleToUser = true; setBoundsInScreen(bounds)
    }
    private fun player(bounds: Rect) = node(target, "", bounds).also { root ->
        fun child(text: String, y: Float) = node(target, text, Rect(bounds.left + 2, bounds.top + (bounds.height() * y).toInt(),
            bounds.right - 2, bounds.top + (bounds.height() * y).toInt() + 10))
        shadowOf(root).addChild(child("第12集", .05f))
        shadowOf(root).addChild(child("选集", .1f))
        shadowOf(root).addChild(child("上滑继续看短剧", .8f))
    }
    private fun window(id: Int, root: AccessibilityNodeInfo, bounds: Rect, layer: Int, focus: Boolean = false) =
        AccessibilityWindowInfo.obtain().also { info ->
            shadowOf(info).apply { setId(id); setType(AccessibilityWindowInfo.TYPE_APPLICATION); setRoot(root)
                setBoundsInScreen(bounds); setLayer(layer); setFocused(focus); setActive(focus) }
            shadowOf(root).setAccessibilityWindowInfo(info)
        }
    @Test fun focusedMapSmallWindowDoesNotHideFullscreenAdEvidence() {
        val root = player(screen)
        val main = window(10, root, screen, 1)
        val smallBounds = Rect(0, screen.height() / 2, screen.width() / 2, screen.bottom)
        val mapRoot = node(map, "", smallBounds)
        val small = window(20, mapRoot, smallBounds, 2, true)
        RootWindowShadow.root = mapRoot; RootWindowShadow.windowList = listOf(small, main)
        assertEquals(target, ForegroundAppResolver.current(context))
        val evidence = SkipAdAccessibilityService.observeEvidence(target)
        assertTrue("小窗抢焦点后仍应识别抖音主窗口", evidence?.isAd == true)
        assertEquals(10, evidence!!.windowId)
    }
    @Test fun unfocusedDouyinSmallWindowIsRecognizedOverAnotherMainApp() {
        val smallBounds = Rect(screen.width() / 2, screen.height() / 2, screen.right, screen.bottom)
        val root = player(smallBounds)
        val small = window(10, root, smallBounds, 2, false)
        val mapRoot = node(map, "", screen)
        val main = window(20, mapRoot, screen, 1, true)
        RootWindowShadow.root = mapRoot; RootWindowShadow.windowList = listOf(small, main)
        assertEquals(target, ForegroundAppResolver.current(context))
        assertTrue(SkipAdAccessibilityService.observeEvidence(target)?.isAd == true)
    }
    @Test fun aCompletelyCoveredWindowCannotBeControlledAndNoOldSelectionIsReused() {
        val root = player(screen)
        val main = window(10, root, screen, 1)
        val mapRoot = node(map, "", screen)
        val cover = window(20, mapRoot, screen, 2, true)
        RootWindowShadow.root = root; RootWindowShadow.windowList = listOf(main)
        assertEquals(target, ForegroundAppResolver.current(context))
        RootWindowShadow.root = mapRoot; RootWindowShadow.windowList = listOf(cover, main)
        assertNull(ForegroundAppResolver.current(context))
        assertNull(SkipAdAccessibilityService.observeEvidence(target))
    }
    @Test fun emptyOrDisabledOrLockedWindowDoesNotFallBackToCachedPlayer() {
        val root = player(screen)
        RootWindowShadow.root = root; RootWindowShadow.windowList = listOf(window(10, root, screen, 1))
        assertEquals(target, ForegroundAppResolver.current(context))
        ConfigManager.get(context).setEnabledApps(emptySet())
        assertNull(ForegroundAppResolver.current(context))
        assertNull(SkipAdAccessibilityService.observeEvidence(target))
        ConfigManager.get(context).setEnabledApps(setOf(target))
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertNull(ForegroundAppResolver.current(context))
        RootWindowShadow.windowList = emptyList(); RootWindowShadow.root = null
        assertNull(SkipAdAccessibilityService.observeEvidence(target))
    }
    @Test fun skippedClickCannotHitAnAdControlCoveredByTheMap() {
        val root = player(screen)
        val buttonBounds = Rect(5, screen.height() * 3 / 4, screen.width() / 3, screen.height() * 3 / 4 + 15)
        val button = node(target, "跳过广告", buttonBounds).apply { isClickable = true; isEnabled = true }
        shadowOf(button).setOnPerformActionListener { _, _ -> true }
        shadowOf(root).addChild(button)
        val main = window(10, root, screen, 1)
        RootWindowShadow.root = root; RootWindowShadow.windowList = listOf(main)
        assertTrue(SkipAdAccessibilityService.trySkipByClick(target))
        val before = shadowOf(button).performedActions.size
        val bounds = Rect(0, screen.height() / 2, screen.width() / 2, screen.bottom)
        val mapRoot = node(map, "", bounds)
        RootWindowShadow.root = mapRoot
        RootWindowShadow.windowList = listOf(window(20, mapRoot, bounds, 2, true), main)
        assertFalse(SkipAdAccessibilityService.trySkipByClick(target))
        assertEquals(before, shadowOf(button).performedActions.size)
    }
    @Test fun smallPlayerReturningToFullscreenKeepsTheSameWindowScope() {
        val smallBounds = Rect(0, screen.height() / 2, screen.width() / 2, screen.bottom)
        val root = player(smallBounds)
        val mapRoot = node(map, "", screen)
        RootWindowShadow.root = mapRoot
        RootWindowShadow.windowList = listOf(window(10, root, smallBounds, 2), window(20, mapRoot, screen, 1, true))
        val smallScope = SkipAdAccessibilityService.observeEvidence(target)?.scope
        assertNotNull(smallScope)
        val fullRoot = player(screen)
        RootWindowShadow.root = fullRoot; RootWindowShadow.windowList = listOf(window(10, fullRoot, screen, 1, true))
        assertEquals(smallScope, SkipAdAccessibilityService.observeEvidence(target)?.scope)
    }
    @Test fun accessibilityConfigurationRequestsInteractiveWindows() {
        val parser = context.resources.getXml(R.xml.accessibility_service_config)
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) parser.next()
        val flags = parser.getAttributeIntValue("http://schemas.android.com/apk/res/android", "accessibilityFlags", 0)
        assertTrue("必须开启多窗口读取能力", flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
        parser.close()
    }
    @Test fun separateHigherWindowsThatTogetherCoverThePlayerPreventControl() {
        val root = player(screen)
        val main = window(10, root, screen, 1)
        val leftBounds = Rect(0, 0, screen.width() / 2, screen.bottom)
        val rightBounds = Rect(screen.width() / 2, 0, screen.right, screen.bottom)
        val leftRoot = node(map, "", leftBounds)
        val rightRoot = node("com.other.app", "", rightBounds)
        RootWindowShadow.root = root
        RootWindowShadow.windowList = listOf(window(30, rightRoot, rightBounds, 3), window(20, leftRoot, leftBounds, 2), main)
        assertNull(ForegroundAppResolver.current(context))
        assertNull(SkipAdAccessibilityService.observeEvidence(target))
    }
    @Test fun aMinimizedOrOffscreenPlayerIsNotTreatedAsVisible() {
        val outside = Rect(screen.right + 10, 0, screen.right + 100, screen.bottom)
        val root = player(outside)
        RootWindowShadow.root = root; RootWindowShadow.windowList = listOf(window(10, root, outside, 1))
        assertNull(ForegroundAppResolver.current(context))
        assertNull(SkipAdAccessibilityService.observeEvidence(target))
    }
    @Test fun mediaCommandForAnUnfocusedSmallPlayerDoesNotControlTheOtherApp() {
        val bounds = Rect(screen.width() / 2, screen.height() / 2, screen.right, screen.bottom)
        val root = player(bounds)
        val mapRoot = node(map, "", screen)
        RootWindowShadow.root = mapRoot
        RootWindowShadow.windowList = listOf(window(10, root, bounds, 2), window(20, mapRoot, screen, 1, true))
        val manager = context.getSystemService(android.media.session.MediaSessionManager::class.java)
        val mapSession = android.media.session.MediaSession(context, "navigation")
        val playerSession = android.media.session.MediaSession(context, "drama")
        val navigation = mapSession.controller
        val drama = playerSession.controller
        shadowOf(navigation).setPackageName(map); shadowOf(drama).setPackageName(target)
        shadowOf(manager).addController(navigation); shadowOf(manager).addController(drama)
        try {
            assertEquals(MediaControlService.Companion.SkipResult.SENT, MediaControlService.skipDouyinAd(context, target))
            assertEquals(android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT, shadowOf(drama.transportControls).lastPerformedAction)
            assertNotEquals(android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT, shadowOf(navigation.transportControls).lastPerformedAction)
        } finally { mapSession.release(); playerSession.release() }
    }
    @Test fun aReplacementWindowWithTheSamePackageCannotReuseTheOldEvidenceScope() {
        val first = player(screen)
        RootWindowShadow.root = first; RootWindowShadow.windowList = listOf(window(10, first, screen, 1))
        assertTrue(SkipAdAccessibilityService.matchesVisibleWindow(target, 10))
        val replacement = player(screen)
        RootWindowShadow.root = replacement; RootWindowShadow.windowList = listOf(window(11, replacement, screen, 2, true))
        assertFalse(SkipAdAccessibilityService.matchesVisibleWindow(target, 10))
        assertTrue(SkipAdAccessibilityService.matchesVisibleWindow(target, 11))
    }

    private fun region(info: AccessibilityWindowInfo, value: Region): AccessibilityWindowInfo = info.also {
        ReflectionHelpers.callInstanceMethod<Void>(it, "setRegionInScreen",
            ReflectionHelpers.ClassParameter.from(Region::class.java, value))
    }

    @Test @Config(sdk = [35]) fun transparentSystemDecorationDoesNotHideAnUnfocusedPlayer() {
        val bounds = Rect(screen.width() / 3, screen.height() / 3, screen.right, screen.bottom)
        val root = player(bounds)
        val small = region(window(10, root, bounds, 2), Region(bounds))
        val otherRoot = node(map, "", screen)
        val main = region(window(20, otherRoot, screen, 1, true), Region(screen))
        // ColorOS reports the same bounding rectangle for its hollow resize border and the app.
        val border = Region(bounds).apply {
            op(Rect(bounds.left + 3, bounds.top + 3, bounds.right - 3, bounds.bottom - 3), Region.Op.DIFFERENCE)
        }
        val decor = region(window(30, node("android", "", bounds), bounds, 3), border)
        shadowOf(decor).setType(AccessibilityWindowInfo.TYPE_SYSTEM)
        RootWindowShadow.root = otherRoot; RootWindowShadow.windowList = listOf(decor, small, main)
        assertEquals(target, ForegroundAppResolver.current(context))
        assertTrue(SkipAdAccessibilityService.observeEvidence(target)?.isAd == true)
        val button = node(target, "跳过广告", Rect(bounds.left + 10, bounds.top + 40, bounds.right - 10, bounds.top + 60))
            .apply { isClickable = true; isEnabled = true }
        shadowOf(button).setOnPerformActionListener { _, _ -> true }; shadowOf(root).addChild(button)
        assertTrue("透明中心内的按钮允许点击", SkipAdAccessibilityService.trySkipByClick(target))
        border.op(Rect(bounds.left, bounds.top, bounds.right, bounds.top + 80), Region.Op.UNION)
        region(decor, border)
        val before = shadowOf(button).performedActions.size
        assertFalse("装饰层实际区域内的按钮仍禁止点击", SkipAdAccessibilityService.trySkipByClick(target))
        assertEquals(before, shadowOf(button).performedActions.size)
    }

    @Test @Config(sdk = [35]) fun irregularTargetRegionFullyCoveredCannotBeControlled() {
        val root = player(screen)
        val targetRegion = Region(Rect(0, 0, screen.width() / 3, screen.bottom)).apply {
            op(Rect(screen.width() * 2 / 3, 0, screen.right, screen.bottom), Region.Op.UNION)
        }
        val main = region(window(10, root, screen, 1), targetRegion)
        val cover = region(window(20, node(map, "", screen), screen, 2, true), Region(targetRegion))
        RootWindowShadow.root = root; RootWindowShadow.windowList = listOf(cover, main)
        assertNull(ForegroundAppResolver.current(context))
    }
}
