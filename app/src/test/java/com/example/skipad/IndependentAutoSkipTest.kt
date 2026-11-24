package com.example.skipad

import android.content.Context
import android.content.Intent
import android.app.Notification
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [RootWindowShadow::class, QueryNodeShadow::class])
class IndependentAutoSkipTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var controller: ServiceController<FloatingButtonService>? = null
    private var reads = 0

    @Before fun setup() {
        ConfigManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(false)
        RootWindowShadow.root = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.ss.android.ugc.aweme"; isVisibleToUser = true
        }
        RootWindowShadow.onRead = { reads++ }
        val reader = Robolectric.buildService(SkipAdAccessibilityService::class.java).create().get()
        reader.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(reader)
    }

    @After fun cleanup() {
        controller?.destroy()
        RootWindowShadow.root = null; RootWindowShadow.onRead = null
        SkipAdAccessibilityService::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun start(): FloatingButtonService {
        controller = floatingServiceOnTestLooper()
        return controller!!.get().also {
            it.onStartCommand(Intent(context, it.javaClass).setAction(FloatingButtonService.ACTION_START), 0, 1)
        }
    }

    private fun overlay(service: FloatingButtonService): Any? = service.javaClass.getDeclaredField("floatingView")
        .apply { isAccessible = true }.get(service)

    @Test fun freshInstallEnablesAutomaticSkippingWithoutOverlay() {
        assertTrue(ConfigManager.get(context).autoSkip)
        assertFalse(ConfigManager.get(context).floatingEnabled)
    }

    @Test fun oldExplicitAutomaticPauseSurvivesUpgrade() {
        context.getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE).edit()
            .putBoolean("auto_skip", false).putBoolean("floating_enabled", true).commit()
        assertFalse(ConfigManager.get(context).autoSkip)
        assertFalse("旧版服务开关不等于新版显示按钮的意愿", ConfigManager.get(context).floatingEnabled)
    }

    @Test fun automaticDetectionWorksWithoutOverlayPermissionOrWindow() {
        ConfigManager.get(context).apply { autoSkip = true; floatingEnabled = false }
        val service = start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue(FloatingButtonService.isRunning())
        assertNull(overlay(service))
        assertTrue("不显示悬浮窗时仍应读取目标窗口", reads > 0)
    }

    @Test fun overlayCanBeTurnedOffWithoutStoppingAutomaticDetection() {
        val config = ConfigManager.get(context).apply { autoSkip = true; floatingEnabled = true }
        ShadowSettings.setCanDrawOverlays(true)
        val service = start()
        assertNotNull(overlay(service))
        config.floatingEnabled = false
        service.onStartCommand(Intent(context, service.javaClass).setAction(FloatingButtonService.ACTION_REFRESH), 0, 2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertNull(overlay(service))
        assertTrue(config.autoSkip)
        assertTrue(FloatingButtonService.isRunning())
        assertTrue(reads > 0)
    }

    @Test fun notificationPauseStopsQueriesAndResumeRestartsThemWithoutOverlay() {
        ConfigManager.get(context).apply { autoSkip = true; floatingEnabled = false }
        val service = start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        service.onStartCommand(Intent(context, service.javaClass).setAction("com.example.skipad.PAUSE_AUTO"), 0, 2)
        val before = reads
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(ConfigManager.get(context).autoSkip)
        assertEquals(before, reads)
        val notification = service.javaClass.getDeclaredMethod("createNotification")
            .apply { isAccessible = true }.invoke(service) as Notification
        val controls = requireNotNull(notification.bigContentView).apply(context, null)
        val autoId = context.resources.getIdentifier("notification_auto", "id", context.packageName)
        assertEquals("恢复识别", controls.findViewById<TextView>(autoId).text.toString())
        assertNotNull(notification.contentIntent)
        controls.findViewById<TextView>(autoId).performClick()
        val resume = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(FloatingButtonService.ACTION_RESUME_AUTO, resume.action)
        service.onStartCommand(resume, 0, 3)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue(ConfigManager.get(context).autoSkip)
        assertTrue(reads > before)
        assertNull(overlay(service))
    }

    @Test fun revokingOverlayPermissionKeepsAutomaticServiceAlive() {
        ConfigManager.get(context).apply { autoSkip = true; floatingEnabled = true }
        ShadowSettings.setCanDrawOverlays(true)
        val service = start()
        ShadowSettings.setCanDrawOverlays(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue(FloatingButtonService.isRunning())
        assertNull(overlay(service))
        assertTrue(reads > 0)
    }

    @Test fun openingActivityStartsAutomaticServiceWithoutOverlayPermission() {
        ConfigManager.get(context).apply { autoSkip = true; floatingEnabled = false }
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            "com.example.skipad/com.example.skipad.MediaControlService")
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertFalse(ConfigManager.get(context).floatingEnabled)
        activity.pause().stop().destroy()
    }

    @Test fun explicitPauseDoesNotRestartWhenActivityResumes() {
        ConfigManager.get(context).apply { autoSkip = false; floatingEnabled = false }
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertFalse(ConfigManager.get(context).autoSkip)
        activity.pause().stop().destroy()
    }

    @Test fun notificationOffersIndependentFloatingButtonAndPreservesAutomaticPause() {
        val config = ConfigManager.get(context).apply { autoSkip = false; floatingEnabled = false }
        ShadowSettings.setCanDrawOverlays(true)
        val service = start()
        service.onStartCommand(Intent(context, service.javaClass).setAction("com.example.skipad.SHOW_FLOATING"), 0, 2)
        assertTrue(config.floatingEnabled)
        assertFalse(config.autoSkip)
        assertNotNull(overlay(service))
        val notification = service.javaClass.getDeclaredMethod("createNotification")
            .apply { isAccessible = true }.invoke(service) as Notification
        assertNotNull(notification.contentView)
        val views = requireNotNull(notification.bigContentView).apply(context, null)
        val id = context.resources.getIdentifier("notification_floating", "id", context.packageName)
        assertEquals("隐藏悬浮窗", views.findViewById<TextView>(id).text.toString())
        views.findViewById<TextView>(id).performClick()
        val hide = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(FloatingButtonService.ACTION_HIDE_FLOATING, hide.action)
        service.onStartCommand(hide, 0, 3)
        assertFalse(config.floatingEnabled)
        assertFalse(config.autoSkip)
        assertNull(overlay(service))
    }

    @Test fun notificationCanOpenPermissionGuidanceWhenOverlayIsNotGranted() {
        val service = start()
        val notification = service.javaClass.getDeclaredMethod("createNotification")
            .apply { isAccessible = true }.invoke(service) as Notification
        val views = requireNotNull(notification.bigContentView).apply(context, null)
        val id = context.resources.getIdentifier("notification_floating", "id", context.packageName)
        views.findViewById<TextView>(id).performClick()
        val opened = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(MainActivity::class.java.name, opened.component?.className)
        assertEquals("com.example.skipad.SHOW_FLOATING", opened.action)
    }
}
