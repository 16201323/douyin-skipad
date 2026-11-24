package com.example.skipad

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal class AppUpdateActivity : SettingsPageActivity() {
    private val engine get() = AppUpdates.engine(this)
    private lateinit var status: TextView
    private lateinit var version: TextView
    private lateinit var checkedAt: TextView
    private lateinit var sourceInfo: TextView
    private lateinit var notes: TextView
    private lateinit var progress: ProgressBar
    private lateinit var check: Button
    private lateinit var download: Button
    private lateinit var install: Button
    private lateinit var cancel: Button
    private var busy = false
    private var resumed = false
    private var installAfterPermission = false
    private val refreshing = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1500L) }
    }
    private val sourcePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!canInstall()) {
            installAfterPermission = false
            Toast.makeText(this, "尚未允许安装更新，可稍后重试", Toast.LENGTH_SHORT).show()
        } else if (resumed && installAfterPermission) {
            installAfterPermission = false; requestInstall()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installAfterPermission = savedInstanceState?.getBoolean("install_after_permission") ?: false
        page("软件更新")
        buildPage()
        show(engine.view())
    }

    private fun buildPage() {
        val current = ui.card()
        current.addView(ui.label("当前版本 ${engine.currentVersion}", 18f, medium = true))
        current.addView(ui.space(10))
        status = ui.label("正在读取更新状态…", 14f)
        current.addView(status)
        version = ui.label("", 12f, ui.secondary)
        current.addView(ui.space(8)); current.addView(version)
        sourceInfo = ui.label("", 11f, ui.secondary)
        current.addView(ui.space(8)); current.addView(sourceInfo)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        current.addView(progress, LinearLayout.LayoutParams(-1, ui.dp(12)).apply { topMargin = ui.dp(14) })
        checkedAt = ui.label("", 11f, ui.secondary)
        current.addView(ui.space(10)); current.addView(checkedAt)
        current.addView(ui.space(18))
        check = ui.button("检查更新", true) {
            if (!busy) {
                busy = true; status.text = "正在检查新版本…"; check.isEnabled = false
                AppUpdates.check(this, true) { state -> runOnUiThread { if (!isDestroyed) { busy = false; show(state) } } }
            }
        }.apply { tag = "check_update" }
        current.addView(check, LinearLayout.LayoutParams(-1, -2))
        val actions = ui.row()
        download = ui.button("下载更新") { chooseDownloadNetwork() }.apply { tag = "download_update" }
        install = ui.button("安装更新", true) { requestInstall() }.apply { tag = "install_update" }
        actions.addView(download, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(install, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        current.addView(ui.space(10)); current.addView(actions)
        cancel = ui.button("取消本次下载") { work { engine.cancel() } }.apply { tag = "cancel_update" }
        current.addView(ui.space(10)); current.addView(cancel, LinearLayout.LayoutParams(-1, -2))
        body.addView(current)

        val preferences = ui.card()
        val automatic = SwitchCompat(this).apply {
            text = "自动检查新版本"; setTextColor(ui.text); textSize = 16f
            minimumHeight = ui.dp(48); isChecked = engine.store.automatic; tag = "automatic_update"
            setOnCheckedChangeListener { _, enabled ->
                engine.store.automatic = enabled
                AppUpdateScheduler.onAppOpened(this@AppUpdateActivity)
            }
        }
        preferences.addView(automatic)
        preferences.addView(ui.space(5))
        preferences.addView(ui.label("联网时定期检查，按下面选择的网络下载。下载完成后通知安装；系统省电或后台限制可能延迟检查。", 12f, ui.secondary))
        preferences.addView(ui.space(18)); preferences.addView(ui.divider()); preferences.addView(ui.space(16))
        preferences.addView(ui.label("更新包下载方式", 16f, medium = true))
        val networks = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        UpdateNetwork.values().forEach { network ->
            networks.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = network.label; textSize = 14f; setTextColor(ui.text)
                minHeight = ui.dp(48); tag = "update_network_${network.name}"; isChecked = network == engine.store.network
                setOnCheckedChangeListener { _, selected -> if (selected) {
                    // 选择立即持久化；即使检查正在联网也不会丢失用户的新网络偏好。
                    if (engine.store.network != network) engine.store.cancelledKey = ""
                    engine.store.network = network
                    AppUpdates.execute {
                        val state = engine.applyNetworkPolicy()
                        runOnUiThread { if (!isDestroyed && !busy) show(state) }
                    }
                } }
            }, RadioGroup.LayoutParams(-1, -2))
        }
        preferences.addView(networks)
        preferences.addView(ui.space(8))
        preferences.addView(ui.label("默认仅 Wi-Fi，不使用计费网络或漫游。下载中切换方式会重新开始；手动下载可选择本次网络。", 12f, ui.secondary))
        body.addView(preferences)

        val changes = ui.card()
        changes.addView(ui.label("更新说明", 16f, medium = true)); changes.addView(ui.space(10))
        notes = ui.label("检查后显示版本说明。", 13f, ui.secondary).apply { setTextIsSelectable(true) }
        changes.addView(notes)
        changes.addView(ui.space(16))
        changes.addView(ui.label("历史版本与下载", 15f, medium = true))
        changes.addView(ui.space(6))
        changes.addView(ui.label("打开 GitHub Release 列表，自行选择版本并下载 APK。", 12f, ui.secondary))
        changes.addView(ui.space(10))
        val releasesUrl = UpdateRelease.RELEASES_URL
        changes.addView(ui.label(releasesUrl, 12f, ui.accent).apply {
            tag = "release_list_url"; setTextIsSelectable(true)
        })
        changes.addView(ui.space(12))
        val downloads = ui.row()
        downloads.addView(ui.button("打开历史版本") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releasesUrl))) }
            catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "未找到浏览器，请复制地址后打开", Toast.LENGTH_SHORT).show()
            }
        }.apply { tag = "open_releases" }, LinearLayout.LayoutParams(0, -2, 1f))
        downloads.addView(ui.button("复制地址") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("历史版本与下载", releasesUrl))
            Toast.makeText(this, "已复制历史版本地址", Toast.LENGTH_SHORT).show()
        }.apply { tag = "copy_releases" }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        changes.addView(downloads)
        body.addView(changes)
    }

    private fun show(state: UpdateView) {
        status.text = state.message
        sourceInfo.text = state.release?.let {
            if (it.staticOrigin.isNotEmpty() && !engine.store.downloadFallback) "下载来源：更新站点 · ${Uri.parse(it.url).host}"
            else "下载来源：GitHub 备用"
        } ?: if (BuildConfig.STATIC_UPDATE_ORIGIN.isNotBlank()) "更新来源：更新站点优先，GitHub 备用"
            else "更新来源：GitHub（静态更新站点尚未配置）"
        version.text = state.release?.let { "最新版本 ${it.version} · ${String.format(Locale.getDefault(), "%.2f MB", it.bytes / 1048576.0)}" }.orEmpty()
        val last = engine.store.lastCheck
        checkedAt.text = if (last > 0) "最近检查：" + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(last)) else "尚未检查"
        val active = state.phase == UpdatePhase.QUEUED || state.phase == UpdatePhase.DOWNLOADING
        progress.visibility = if (active) View.VISIBLE else View.GONE
        progress.isIndeterminate = state.phase == UpdatePhase.QUEUED
        progress.progress = if (state.total > 0) (100L * state.downloaded / state.total).coerceIn(0, 100).toInt() else 0
        check.isEnabled = !busy && state.phase != UpdatePhase.CHECKING
        download.visibility = if (state.release != null && !active && state.phase != UpdatePhase.READY) View.VISIBLE else View.GONE
        install.visibility = if (state.phase == UpdatePhase.READY) View.VISIBLE else View.GONE
        cancel.visibility = if (active) View.VISIBLE else View.GONE
        notes.text = state.release?.notes?.ifBlank { "该版本暂无说明。" } ?: "检查后显示版本说明。"
    }

    private fun chooseDownloadNetwork() {
        val metered = getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        if (!metered && engine.store.network == UpdateNetwork.WIFI) { work { engine.startDownload(true) }; return }
        AlertDialog.Builder(this).setTitle("本次更新下载网络")
            .setItems(arrayOf("仅使用 Wi-Fi", "允许使用当前网络（可能消耗流量）")) { _, index ->
                work { engine.startDownload(index == 0) }
            }.setNegativeButton("取消", null).show()
    }

    private fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun requestInstall() {
        if (busy) return
        if (!canInstall()) {
            installAfterPermission = true
            sourcePermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        busy = true
        AppUpdates.execute {
            val result = runCatching { engine.fileForInstall() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                result.onSuccess { file ->
                    if (resumed) try {
                        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("更新安装包", uri) })
                    } catch (_: Exception) { Toast.makeText(this, "无法打开系统安装器，请重试", Toast.LENGTH_LONG).show() }
                }.onFailure { Toast.makeText(this, it.message ?: "安装包验证失败", Toast.LENGTH_LONG).show(); show(engine.view()) }
            }
        }
    }

    private fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        AppUpdates.execute {
            try {
                val state = engine.refresh()
                runOnUiThread { if (!isDestroyed && resumed && !busy) show(state) }
            } finally { refreshing.set(false) }
        }
    }

    private fun work(action: () -> UpdateView) {
        if (busy) return
        busy = true
        AppUpdates.execute {
            val state = runCatching(action).getOrElse { engine.store.state(UpdatePhase.ERROR, it.message ?: "操作失败，请重试"); engine.view() }
            runOnUiThread { if (!isDestroyed) { busy = false; show(state) } }
        }
    }
    override fun onResume() {
        super.onResume(); resumed = true
        handler.post(tick)
        AppUpdateScheduler.onAppOpened(this)
        if (installAfterPermission && canInstall()) { installAfterPermission = false; requestInstall() }
    }
    override fun onPause() { resumed = false; handler.removeCallbacks(tick); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("install_after_permission", installAfterPermission); super.onSaveInstanceState(outState) }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
