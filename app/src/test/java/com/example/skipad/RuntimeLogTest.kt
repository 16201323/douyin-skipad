package com.example.skipad

import android.content.Context
import android.content.ClipboardManager
import android.view.View
import android.widget.TextView
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuntimeLogTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        ConfigManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        RecognitionHistory.clear(context)
    }

    @Test fun evidenceDetailsArePersistedWithoutPageText() {
        val observation = AdObservation("com.ss.android.ugc.aweme", 42, false, AdConfidence.NONE,
            "播放器上下文不足 episode=true", rulesVersion = "test")
        RecognitionHistory.record(context, "识别结果", "播放器上下文不足", observation)
        val raw = JSONArray(context.getSharedPreferences("recognition_history", Context.MODE_PRIVATE).getString("entries", "[]"))
        assertTrue(raw.getJSONObject(0).optString("details").contains("42"))
        assertTrue(RecognitionHistory.LIMIT >= 300)
    }

    @Test fun logFiltersAndCopyContainActionableDiagnostics() {
        RecognitionHistory.record(context, "识别结果", "播放器内未发现广告")
        RecognitionHistory.record(context, "发送失败", "未找到播放会话")
        RecognitionHistory.record(context, "已确认跳过", "广告稳定消失")
        val controller = Robolectric.buildActivity(RecognitionHistoryActivity::class.java).create()
        val root = controller.get().window.decorView
        assertNotNull(root.findViewWithTag<View>("log_filter_problem"))
        root.findViewWithTag<View>("log_filter_problem").performClick()
        assertEquals("1 条问题日志", root.findViewWithTag<TextView>("log_count").text.toString())
        root.findViewWithTag<View>("copy_logs").performClick()
        val copied = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString()
        assertTrue(copied.contains("助手版本"))
        assertTrue(copied.contains("Android"))
        assertTrue(copied.contains("发送失败"))
        assertFalse(copied.contains("广告稳定消失"))
        root.findViewWithTag<View>("log_filter_success").performClick()
        assertEquals("1 条成功日志", root.findViewWithTag<TextView>("log_count").text.toString())
        controller.destroy()
    }

    @Test fun visibleLogPageRefreshesWhenNewEventsArrive() {
        val controller = Robolectric.buildActivity(RecognitionHistoryActivity::class.java).create().start().resume()
        RecognitionHistory.record(context, "检测异常", "测试异常")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertEquals("1 条日志", controller.get().window.decorView.findViewWithTag<TextView>("log_count").text.toString())
        controller.pause().stop().destroy()
    }
    @Test fun oldRecordsRemainReadableAndNormalContentIsNotAProblem() {
        context.getSharedPreferences("recognition_history", Context.MODE_PRIVATE).edit().putString("entries",
            """[{"time":1,"kind":"识别结果","message":"播放器内未发现广告"}]""").commit()
        val entry = RecognitionHistory.load(context).single()
        assertEquals("", entry.details)
        assertTrue(DiagnosticReport.select(listOf(entry), LogCategory.PROBLEM).isEmpty())
        RecognitionHistory.record(context, "已发送请求", "等待画面确认")
        assertTrue(DiagnosticReport.select(RecognitionHistory.load(context), LogCategory.SUCCESS).isEmpty())
    }
    @Test fun clearingRequiresConfirmationAndDeduplicationDoesNotInvalidatePage() {
        RecognitionHistory.record(context, "发送失败", "没有可用会话", now = 100L)
        val revision = RecognitionHistory.revision
        RecognitionHistory.record(context, "发送失败", "没有可用会话", now = 101L)
        assertEquals(revision, RecognitionHistory.revision)
        val controller = Robolectric.buildActivity(RecognitionHistoryActivity::class.java).create()
        controller.get().window.decorView.findViewWithTag<View>("clear_logs").performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals(1, RecognitionHistory.load(context).size)
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        assertEquals(1, RecognitionHistory.load(context).size)
        controller.destroy()
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun renderRuntimeLogInBothThemes() {
        val directory = java.io.File("../build/ui-1.5.5").apply { mkdirs() }
        RecognitionHistory.record(context, "识别结果", "播放器内未发现广告")
        RecognitionHistory.record(context, "确认中止", "窗口发生变化，本次未计为成功")
        RecognitionHistory.record(context, "已确认跳过", "同一播放器内广告稳定消失")
        for (mode in listOf(1, 2)) {
            ConfigManager.get(context).themeMode = mode
            val controller = Robolectric.buildActivity(RecognitionHistoryActivity::class.java).create()
            val view = controller.get().window.decorView
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, 2400)
            val bitmap = android.graphics.Bitmap.createBitmap(1080, 2400, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File(directory, "$mode-logs.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle(); controller.destroy()
        }
    }
    @Test fun separateSuccessfulRequestsAreNotLostToStatusDeduplication() {
        RecognitionHistory.record(context, "已确认跳过", "广告稳定消失", now = 100L)
        RecognitionHistory.record(context, "已确认跳过", "广告稳定消失", now = 2000L)
        assertEquals(2, DiagnosticReport.select(RecognitionHistory.load(context), LogCategory.SUCCESS).size)
    }
}
