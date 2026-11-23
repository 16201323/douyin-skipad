package com.example.skipad

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UiRedesignTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        ConfigManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigManager.get(context).floatingEnabled = false
    }

    @After fun cleanup() {
        stopLeakedFloatingService()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test fun navigationCentersLabelsBelowTheirIcons() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val root = controller.get().window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1920)
        for (tag in listOf("nav_home", "nav_appearance", "nav_settings")) {
            val item = root.findViewWithTag<LinearLayout>(tag)
            val icon = item.getChildAt(0)
            val label = item.getChildAt(1) as TextView
            assertTrue(label.top >= icon.bottom)
            val textCenter = label.left + label.totalPaddingLeft +
                (label.layout.getLineLeft(0) + label.layout.getLineRight(0)) / 2f
            assertEquals("$tag 的图标与文字应共用中心线", (icon.left + icon.right) / 2f, textCenter, 1f)
        }
        controller.destroy()
    }

    @Test fun lightThemeOptionSurvivesColdActivityCreation() = assertThemeOption(1, false)

    @Test fun darkThemeOptionSurvivesColdActivityCreation() = assertThemeOption(2, true)

    @Test @Config(qualifiers = "notnight") fun systemThemeFollowsLightSystem() = assertThemeOption(-1, false)

    @Test @Config(qualifiers = "night") fun systemThemeFollowsDarkSystem() = assertThemeOption(-1, true)

    private fun assertThemeOption(mode: Int, night: Boolean) {
            val first = Robolectric.buildActivity(MainActivity::class.java).create()
            val root = first.get().window.decorView
            root.findViewWithTag<View>("nav_settings").performClick()
            val option = root.findViewWithTag<View>("theme_$mode")
            assertNotNull("设置应提供跟随系统、浅色、深色三个选项", option)
            option.performClick()
            assertEquals(mode, AppCompatDelegate.getDefaultNightMode())
            first.destroy()
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            ConfigManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
            val next = Robolectric.buildActivity(MainActivity::class.java).create()
            assertEquals(mode, AppCompatDelegate.getDefaultNightMode())
            val actualNight = next.get().resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            assertEquals("恢复模式 $mode", night, actualNight)
            assertFalse(ConfigManager.get(context).floatingEnabled)
            next.destroy()
    }

    @Test fun navigationOpensAppearanceAndSettingsWithoutChangingSavedStyle() {
        val config = ConfigManager.get(context).apply { buttonSize = 84; buttonText = "继续"; buttonColor = 0xFF339977.toInt() }
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val root = controller.get().window.decorView
        val appearance = root.findViewWithTag<View>("nav_appearance")
        assertNotNull("应提供独立悬浮窗导航", appearance)
        appearance.performClick()
        assertNotNull(root.findViewWithTag<View>("floating_preview"))
        root.findViewWithTag<View>("nav_settings").performClick()
        assertNotNull(root.findViewWithTag<View>("permission_accessibility"))
        root.findViewWithTag<View>("nav_home").performClick()
        assertNotNull(root.findViewWithTag<View>("service_toggle"))
        assertEquals(84, config.buttonSize)
        assertEquals("继续", config.buttonText)
        assertFalse(config.floatingEnabled)
        controller.destroy()
    }

    @Test fun selectedPageSurvivesActivityRecreation() {
        val first = Robolectric.buildActivity(MainActivity::class.java).create()
        first.get().window.decorView.findViewWithTag<View>("nav_settings")?.performClick()
        val state = Bundle()
        first.saveInstanceState(state).destroy()
        val second = Robolectric.buildActivity(MainActivity::class.java).create(state)
        assertTrue(second.get().window.decorView.findViewWithTag<View>("nav_settings")?.isSelected == true)
        second.destroy()
    }

    @Test @Config(qualifiers = "notnight") fun lightThemeHasLightBackgroundAndReadableText() {
        assertTheme(light = true)
    }

    @Test @Config(qualifiers = "night") fun darkThemeHasDarkBackgroundAndReadableText() {
        assertTheme(light = false)
    }

    private fun assertTheme(light: Boolean) {
        val resources = context.resources
        val bgId = resources.getIdentifier("ui_background", "color", context.packageName)
        val textId = resources.getIdentifier("ui_text", "color", context.packageName)
        assertTrue("两种模式都应提供完整主题色", bgId != 0 && textId != 0)
        val background = context.getColor(bgId)
        val foreground = context.getColor(textId)
        val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(background)
        assertEquals(light, luminance > 0.5)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(foreground, background) >= 4.5)
        val secondary = context.getColor(R.color.ui_secondary)
        for (surface in listOf(R.color.ui_surface, R.color.ui_surface_alt, R.color.ui_hero)) {
            assertTrue("说明文字在卡片上应清晰可读",
                androidx.core.graphics.ColorUtils.calculateContrast(secondary, context.getColor(surface)) >= 4.5)
        }
    }
}
