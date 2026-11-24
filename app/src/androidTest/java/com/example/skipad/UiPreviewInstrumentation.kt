package com.example.skipad

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

/** 仅测试包：捕获 Activity 自身视图，排除系统悬浮窗，不修改手机全局主题。 */
class UiPreviewInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val output = Bundle()
        val captured = mutableListOf<String>()
        val config = ConfigManager.get(targetContext)
        val originalTheme = config.themeMode
        try {
            val directory = requireNotNull(targetContext.getExternalFilesDir("ui-preview"))
            check(directory.exists() || directory.mkdirs())
            fun capture(activity: Activity, name: String) {
                waitForIdleSync()
                SystemClock.sleep(220L)
                var captureError: Exception? = null
                runOnMainSync { try {
                    val root = activity.window.decorView
                    check(root.width > 0 && root.height > 0)
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    val file = File(directory, "$name.png")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    captured.add(file.absolutePath)
                } catch (e: Exception) { captureError = e } }
                captureError?.let { throw it }
            }
            fun scrollIn(view: View): ScrollView? {
                if (view is ScrollView) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) {
                    scrollIn(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            for ((theme, mode) in listOf("light" to AppCompatDelegate.MODE_NIGHT_NO,
                "dark" to AppCompatDelegate.MODE_NIGHT_YES)) {
                config.themeMode = mode
                val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                for ((page, tag) in listOf("home" to "nav_home", "appearance" to "nav_appearance", "settings" to "nav_settings")) {
                    runOnMainSync { activity.window.decorView.findViewWithTag<View>(tag).performClick() }
                    capture(activity, "ui-$theme-$page")
                }
                runOnMainSync { scrollIn(activity.window.decorView)?.fullScroll(View.FOCUS_DOWN) }
                capture(activity, "ui-$theme-releases")
                for ((name, activityClass) in listOf("rules" to RulesActivity::class.java, "history" to RecognitionHistoryActivity::class.java)) {
                    val details = startActivitySync(Intent(targetContext, activityClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    capture(details, "ui-$theme-$name")
                    runOnMainSync { details.finish() }
                }
            }
            output.putString("stream", captured.joinToString("\n"))
            config.themeMode = originalTheme
            runOnMainSync { AppCompatDelegate.setDefaultNightMode(originalTheme) }
            finish(Activity.RESULT_OK, output)
        } catch (e: Exception) {
            config.themeMode = originalTheme
            runOnMainSync { AppCompatDelegate.setDefaultNightMode(originalTheme) }
            output.putString("stream", "UI preview failed: ${e.javaClass.simpleName}: ${e.message}")
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
