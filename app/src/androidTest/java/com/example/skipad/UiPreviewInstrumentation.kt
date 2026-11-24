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
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 仅测试包：捕获 Activity 自身视图，排除系统悬浮窗，不修改手机全局主题。 */
class UiPreviewInstrumentation : Instrumentation() {
    private var verifyUpdate = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        verifyUpdate = arguments?.getString("verify_update") == "true"
        start()
    }

    override fun onStart() {
        if (verifyUpdate) { verifyRealUpdate(); return }
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
                for ((name, activityClass) in listOf("rules" to RulesActivity::class.java, "history" to RecognitionHistoryActivity::class.java,
                    "updates" to AppUpdateActivity::class.java)) {
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

    /** 真机检查真实 GitHub 元数据、DownloadManager 和包校验；不改变网络偏好，不拉起安装器。 */
    private fun verifyRealUpdate() {
        val output = Bundle()
        val engine = AppUpdates.engine(targetContext)
        fun runOnUpdateThread(action: () -> UpdateView): UpdateView {
            val done = CountDownLatch(1)
            var result: UpdateView? = null
            var error: Exception? = null
            AppUpdates.execute { try { result = action() } catch (e: Exception) { error = e } finally { done.countDown() } }
            check(done.await(45, TimeUnit.SECONDS)) { "检查更新超时" }
            error?.let { throw it }
            return requireNotNull(result)
        }
        try {
            var state = runOnUpdateThread { engine.check(true) }
            val deadline = SystemClock.uptimeMillis() + 45_000
            while (state.phase in listOf(UpdatePhase.QUEUED, UpdatePhase.DOWNLOADING) && SystemClock.uptimeMillis() < deadline) {
                sendStatus(1, Bundle().apply { putString("stream", "update ${state.phase} ${state.downloaded}/${state.total}") })
                SystemClock.sleep(2000)
                state = runOnUpdateThread { engine.refresh() }
            }
            output.putString("installed", engine.currentVersion)
            output.putString("network", engine.store.network.name)
            output.putString("phase", state.phase.name)
            output.putString("message", state.message)
            output.putString("release", state.release?.version?.toString())
            if (state.phase == UpdatePhase.READY) {
                val file = engine.fileForInstall()
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                output.putString("file", file.absolutePath)
                output.putString("sha256", hash)
                output.putLong("bytes", file.length())
            }
            finish(Activity.RESULT_OK, output)
        } catch (e: Exception) {
            output.putString("stream", "Update verification failed: ${e.javaClass.simpleName}: ${e.message}")
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
