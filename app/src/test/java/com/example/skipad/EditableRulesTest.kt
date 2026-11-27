package com.example.skipad

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.os.Looper
import android.widget.TextView
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AlertDialog
import org.robolectric.shadows.ShadowDialog
import android.content.ClipboardManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.view.accessibility.AccessibilityNodeInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [QueryNodeShadow::class])
class EditableRulesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val pkg = "com.ss.android.ugc.aweme"

    @Before fun reset() {
        ConfigManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE).edit().clear().commit()
        RecognitionHistory.clear(context)
    }

    private fun node(text: String, bounds: Rect, id: String? = null) = AccessibilityNodeInfo.obtain().apply {
        this.text = text; packageName = pkg; isVisibleToUser = true
        viewIdResourceName = id?.let { "$pkg:id/$it" }; setBoundsInScreen(bounds)
    }

    private fun liveRoot(label: String, exit: Boolean = true, badge: Boolean = true, comment: Boolean = false): AccessibilityNodeInfo {
        val root = node("", Rect(0, 0, 1240, 2772))
        if (exit) shadowOf(root).addChild(node("退出短剧", Rect(180, 180, 420, 270), "b4="))
        shadowOf(root).addChild(node("短剧 · 示例剧名 · 120集全 免费", Rect(35, 2630, 1010, 2750), "cq-"))
        if (badge) shadowOf(root).addChild(node("直播中", Rect(40, 2280, 245, 2360)))
        val prompt = node(label, Rect(320, 1850, 920, 2010))
        if (comment) {
            val panel = node("", Rect(0, 1000, 1240, 2700), "comment_list")
            shadowOf(panel).addChild(prompt); shadowOf(root).addChild(panel)
        } else shadowOf(root).addChild(prompt)
        return root
    }

    @Test fun liveEntryAndEndedStatesAreRecognizedOnlyWithCombinedContext() {
        for (text in listOf("点击进入直播间", "直播已结束")) {
            assertTrue("短剧内直播页面应被识别: $text", AdDetector.inspectFocused(liveRoot(text), pkg)!!.isAd)
            assertFalse(AdDetector.inspectFocused(liveRoot(text, exit = false), pkg)!!.isAd)
            assertFalse(AdDetector.inspectFocused(liveRoot(text, badge = false), pkg)!!.isAd)
            assertFalse(AdDetector.inspectFocused(liveRoot(text, comment = true), pkg)!!.isAd)
        }
    }

    @Test fun settingsOffersRulesHistoryAndCopyableReleaseList() {
        ConfigManager.get(context).autoSkip = false
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val root = controller.get().window.decorView
        root.findViewWithTag<View>("nav_settings").performClick()
        assertNotNull("规则管理入口", root.findViewWithTag<View>("manage_rules"))
        assertNotNull("识别记录入口", root.findViewWithTag<View>("recognition_history"))
        assertNull(root.findViewWithTag<View>("open_releases"))
        root.findViewWithTag<View>("app_update").performClick()
        assertEquals(AppUpdateActivity::class.java.name, shadowOf(controller.get()).nextStartedActivity.component!!.className)
        controller.destroy()
    }

    @Test fun customEditsAffectFocusedQueriesAndPersistWithoutRestart() {
        val store = ConfigManager.get(context).adRules
        val rule = AdRule("custom_test", "自定义提示", "新的推广入口")
        val root = liveRoot("新的推广入口")
        assertFalse(AdDetector.inspectFocused(root, pkg, store.snapshot())!!.isAd)
        store.save(listOf(rule))
        val first = store.snapshot()
        assertTrue(AdDetector.inspectFocused(root, pkg, first)!!.isAd)
        store.save(listOf(rule.copy(enabled = false)))
        assertFalse(AdDetector.inspectFocused(root, pkg, store.snapshot())!!.isAd)
        store.save(listOf(rule.copy(pattern = "推广入口", mode = RuleMode.CONTAINS)))
        val next = AdRuleStore(context).snapshot()
        assertTrue(AdDetector.inspectFocused(root, pkg, next)!!.isAd)
        assertNotEquals(first.version, next.version)
        store.save(emptyList())
        assertTrue(AdRuleStore(context).snapshot().rules.isEmpty())
        store.reset()
        assertEquals(AdRuleSet.defaultRules, store.snapshot().rules)
    }

    @Test fun regexCharactersAreLiteralAndInvalidRulesAreRejected() {
        val rule = AdRule("literal", "字面内容", "推广.*入口", RuleMode.EXACT)
        val rules = AdRuleSet.create(listOf(rule))
        assertTrue(rules.matching("推广内容入口").isEmpty())
        assertEquals(rule, rules.matching("推广.*入口").single())
        assertNotNull(rule.copy(pattern = " ").validationError())
        assertNotNull(rule.copy(mode = RuleMode.COUNTDOWN).validationError())
        context.getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE).edit()
            .putString("ad_rules_v1", "broken json").commit()
        assertEquals(AdRuleSet.defaultRules, AdRuleStore(context).snapshot().rules)
    }

    @Test fun changedRuleSnapshotNeedsNewConfirmation() {
        val gate = AdSkipGate()
        val first = AdObservation(pkg, 1, true, AdConfidence.MEDIUM, "test", "old", "旧规则", "v1")
        val second = first.copy(ruleId = "new", ruleName = "新规则", rulesVersion = "v2")
        assertFalse(gate.evaluate(first, 0).send)
        assertFalse("规则变更不能沿用旧候选等待时间", gate.evaluate(second, 400).send)
        assertTrue(gate.evaluate(second, 700).send)
    }

    @Test fun ruleEditorSavesAndUpdatesThenDisablesWithoutStartingService() {
        ConfigManager.get(context).autoSkip = false
        val controller = Robolectric.buildActivity(RulesActivity::class.java).create()
        controller.get().window.decorView.findViewWithTag<View>("add_rule").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        var dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.window!!.decorView.findViewWithTag<EditText>("rule_name").setText("新的提示")
        dialog.window!!.decorView.findViewWithTag<EditText>("rule_pattern").setText("点击继续追剧")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val rule = ConfigManager.get(context).adRules.snapshot().rules.last()
        assertEquals("点击继续追剧", rule.pattern)
        controller.get().window.decorView.findViewWithTag<View>("edit_rule_${rule.id}").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.window!!.decorView.findViewWithTag<EditText>("rule_pattern").setText("继续追剧")
        dialog.window!!.decorView.findViewWithTag<Spinner>("rule_mode").setSelection(1)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        controller.get().window.decorView.findViewWithTag<SwitchCompat>("rule_enabled_${rule.id}").isChecked = false
        val saved = AdRuleStore(context).snapshot().rules.single { it.id == rule.id }
        assertEquals(RuleMode.CONTAINS, saved.mode)
        assertEquals("继续追剧", saved.pattern)
        assertFalse(saved.enabled)
        assertFalse(ConfigManager.get(context).autoSkip)
        assertFalse(FloatingButtonService.isRunning())
        assertTrue(RecognitionHistory.load(context).any { it.kind == "规则变更" })
        controller.destroy()
    }

    @Test fun historyIsBoundedPersistentAndClearable() {
        repeat(120) { index -> RecognitionHistory.record(context, "测试状态", "记录$index", now = index.toLong()) }
        val entries = RecognitionHistory.load(context)
        assertEquals(100, entries.size)
        assertEquals("记录20", entries.first().message)
        assertEquals("记录119", entries.last().message)
        RecognitionHistory.record(context, "测试状态", "记录119", now = 120L)
        assertEquals(100, RecognitionHistory.load(context).size)
        RecognitionHistory.clear(context)
        assertTrue(RecognitionHistory.load(context).isEmpty())
    }

    @Test fun longCaptionKeepsTheExistingVerifiedAdSuffixBehavior() {
        val root = liveRoot("正常播放")
        shadowOf(root).addChild(node("产品介绍".repeat(200) + "广告", Rect(40, 2350, 1100, 2520), "desc"))
        assertTrue(AdDetector.inspectFocused(root, pkg)!!.isAd)
    }
    @Test fun renamedPlayerIdsWithAnExistingDescriptionStillExposeLiveContext() {
        val root = node("", Rect(0, 0, 1240, 2772))
        shadowOf(root).addChild(node("退出短剧", Rect(189, 185, 413, 262), "b5i"))
        shadowOf(root).addChild(node("短剧 · 示例剧名", Rect(161, 2658, 575, 2720), "crf"))
        shadowOf(root).addChild(node("直播中", Rect(40, 2280, 245, 2360)))
        shadowOf(root).addChild(node("点击进入直播间", Rect(320, 1850, 920, 2010)))
        shadowOf(root).addChild(node("普通商品介绍", Rect(42, 2249, 914, 2406), "desc"))
        assertTrue(AdDetector.inspectFocused(root, pkg)?.isAd == true)
    }
    @Test fun renamedExitAnchorRecognizesCaptionAdsAndNormalContentSeparately() {
        for ((text, expected) in listOf("商品介绍 展开广告" to true, "普通剧情介绍" to false, "这个视频不是广告" to false)) {
            val root = node("", Rect(0, 0, 1240, 2772))
            shadowOf(root).addChild(node("退出短剧", Rect(189, 185, 413, 262), "b5i"))
            shadowOf(root).addChild(node("短剧 · 测试剧名", Rect(161, 2658, 575, 2720), "crf"))
            shadowOf(root).addChild(node(text, Rect(42, 2249, 914, 2406), "desc"))
            val observation = AdDetector.inspectFocused(root, pkg)
            assertTrue("改版后的正常播放器也应能确认上下文", observation?.inPlayer == true)
            assertEquals(text, expected, observation!!.isAd)
        }
    }
    @Test fun exitWordsInBodyOrCommentsDoNotCreateAPlayerAnchor() {
        val root = node("", Rect(0, 0, 1240, 2772))
        shadowOf(root).addChild(node("退出短剧", Rect(100, 1400, 500, 1500), "random"))
        shadowOf(root).addChild(node("商品介绍 展开广告", Rect(42, 2249, 914, 2406), "desc"))
        assertFalse(AdDetector.inspectFocused(root, pkg)!!.isAd)
    }
}
