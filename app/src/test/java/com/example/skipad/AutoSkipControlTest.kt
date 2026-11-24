package com.example.skipad

import android.content.Context
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutoSkipControlTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        ConfigManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", null)
        Settings.Secure.putString(context.contentResolver, "enabled_accessibility_services", null)
        ShadowSettings.setCanDrawOverlays(false)
        SkipAdAccessibilityService::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    @After fun cleanup() {
        ShadowDialog.getLatestDialog()?.dismiss()
        SkipAdAccessibilityService::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    private fun refresh(activity: MainActivity) {
        activity.javaClass.getDeclaredMethod("refreshRuntimeUi").apply { isAccessible = true }.invoke(activity)
    }
    private fun notificationAccess() {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            "com.example.skipad/com.example.skipad.MediaControlService")
    }
    private fun permissionTitle() = ShadowDialog.getLatestDialog()
        .findViewById<TextView>(androidx.appcompat.R.id.alertTitle).text.toString()
    @Test fun homeOffersAnExplicitPersistentAutomaticSwitchIndependentOfOverlay() {
        val config = ConfigManager.get(context).apply { autoSkip = false; floatingEnabled = false }
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        val toggle = activity.get().window.decorView.findViewWithTag<CompoundButton>("auto_skip_toggle")
        assertNotNull("首页必须提供自动跳过开关", toggle)
        assertFalse(toggle.isChecked)
        toggle.isChecked = true
        assertTrue(config.autoSkip)
        assertFalse(config.floatingEnabled)
        assertNull("缺少必需授权时不启动自动服务", shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertTrue(permissionTitle().contains("通知使用权"))
        toggle.isChecked = false
        assertFalse(config.autoSkip)
        activity.destroy()
        val reopened = Robolectric.buildActivity(MainActivity::class.java).create()
        assertFalse(reopened.get().window.decorView.findViewWithTag<CompoundButton>("auto_skip_toggle").isChecked)
        reopened.destroy()
    }
    @Test fun enabledButDisconnectedAccessibilityIsNotReportedAsRunning() {
        notificationAccess()
        Settings.Secure.putString(context.contentResolver, "enabled_accessibility_services",
            "com.example.skipad/com.example.skipad.SkipAdAccessibilityService")
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        val root = activity.get().window.decorView
        assertEquals("无障碍服务未连接", root.findViewWithTag<TextView>("runtime_status_title").text.toString())
        root.findViewWithTag<View>("check_permissions").performClick()
        assertEquals("已授权 · 待连接", root.findViewWithTag<TextView>("permission_accessibility_state").text.toString())
        activity.destroy()
    }
    @Test fun missingAccessibilityPromptsOnlyForTheRequiredAuthorization() {
        notificationAccess()
        ConfigManager.get(context).autoSkip = false
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        activity.get().window.decorView.findViewWithTag<CompoundButton>("auto_skip_toggle").isChecked = true
        assertTrue(permissionTitle().contains("无障碍服务"))
        assertFalse(ConfigManager.get(context).floatingEnabled)
        activity.destroy()
    }
    @Test fun permissionCheckDoesNotTurnOnPausedAutomaticSkipping() {
        ConfigManager.get(context).autoSkip = false
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        val root = activity.get().window.decorView
        root.findViewWithTag<View>("check_permissions").performClick()
        assertNotNull(root.findViewWithTag<View>("permission_notification"))
        assertNotNull(root.findViewWithTag<View>("permission_accessibility"))
        assertFalse(ConfigManager.get(context).autoSkip)
        activity.destroy()
    }
    @Test fun externalPauseRefreshesTheSwitchWithoutStartingAService() {
        notificationAccess()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        val toggle = activity.get().window.decorView.findViewWithTag<CompoundButton>("auto_skip_toggle")
        assertNotNull(toggle)
        ConfigManager.get(context).autoSkip = false
        refresh(activity.get())
        assertFalse(toggle.isChecked)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        activity.destroy()
    }
    @Test fun controlsShareOneCardAndSettingsOpenPermissionDetails() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        val root = activity.get().window.decorView
        val automatic = root.findViewWithTag<View>("auto_skip_toggle")
        val floating = root.findViewWithTag<View>("floating_toggle")
        assertSame(automatic.parent.parent, floating.parent.parent)
        root.findViewWithTag<View>("nav_settings").performClick()
        assertNull(root.findViewWithTag<View>("permission_notification"))
        assertNull(root.findViewWithTag<View>("open_releases"))
        root.findViewWithTag<View>("permissions_details").performClick()
        assertNotNull(root.findViewWithTag<View>("permission_notification"))
        activity.destroy()
    }
    @Test fun freshInstallDefaultsAreReadyForIndependentAutomaticSkipping() {
        val config = ConfigManager.get(context)
        assertTrue(config.autoSkip)
        assertFalse(config.floatingEnabled)
        assertEquals(-1, config.themeMode)
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun renderConsolidatedPagesForReview() {
        val folder = java.io.File("../build/ui-1.5.3").apply { mkdirs() }
        for (mode in listOf(1, 2)) {
            ConfigManager.get(context).themeMode = mode
            val controller = Robolectric.buildActivity(MainActivity::class.java).create()
            val activity = controller.get()
            fun capture(name: String) {
                val view = activity.window.decorView
                view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 1080, 2400)
                val bitmap = android.graphics.Bitmap.createBitmap(1080, 2400, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                java.io.File(folder, "$mode-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            capture("home")
            activity.window.decorView.findViewWithTag<View>("nav_settings").performClick()
            capture("settings")
            activity.window.decorView.findViewWithTag<View>("permissions_details").performClick()
            capture("permissions")
            activity.onBackPressed()
            assertNotNull(activity.window.decorView.findViewWithTag<View>("permissions_details"))
            controller.destroy()
        }
    }
}
