package com.example.skipad

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface UpdateSource { fun latest(): UpdateRelease }

/** 所有联网、下载查询及文件校验由更新线程调用，不占用广告检测线程。 */
internal class AppUpdateEngine(val context: Context, private val source: UpdateSource = UpdateSources.default(),
    staticOrigin: String = BuildConfig.STATIC_UPDATE_ORIGIN,
    private val archiveCheck: ((File, UpdateRelease) -> String?)? = null) {
    val store = UpdateStore(context, staticOrigin)
    private val downloads get() = context.getSystemService(DownloadManager::class.java)
    val currentVersion: String get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    fun view(): UpdateView = UpdateView(store.phase, store.message, store.release)

    fun check(force: Boolean = false, now: Long = System.currentTimeMillis()): UpdateView {
        if (!force && (!store.automatic || (store.lastCheck > 0 && now - store.lastCheck in 0 until CHECK_INTERVAL))) return refresh()
        store.lastCheck = now
        store.state(UpdatePhase.CHECKING, "正在检查新版本…")
        return try {
            val release = source.latest()
            val installedVersion = ReleaseVersion.parse(currentVersion)
            require(installedVersion != null) { "当前版本格式不支持自动比较" }
            if (release.version <= installedVersion) {
                discardDownload()
                store.release = null
                store.state(UpdatePhase.LATEST, "当前已是最新版本")
            } else {
                if (store.release?.key != release.key) {
                    discardDownload()
                    store.release = release
                    store.state(UpdatePhase.AVAILABLE, "发现新版本 ${release.version}")
                }
                if (store.downloadId < 0 && store.phase != UpdatePhase.READY) {
                    store.state(UpdatePhase.AVAILABLE, "发现新版本 ${release.version}")
                    if ((force || store.automatic) && store.network != UpdateNetwork.MANUAL && store.cancelledKey != release.key) {
                        startDownload(store.network == UpdateNetwork.WIFI)
                    } else notifyUpdate(release, ready = false)
                }
            }
            refresh()
        } catch (e: Exception) {
            val message = when (e) {
                is java.io.IOException -> "无法连接更新来源，请检查网络后重试"
                is org.json.JSONException -> "版本信息不完整，请稍后重试或查看历史版本"
                else -> e.message?.take(180) ?: "检查失败，请检查网络后重试"
            }
            store.state(UpdatePhase.ERROR, message)
            view()
        }
    }

    fun downloadFile(release: UpdateRelease): File = File(
        requireNotNull(context.getExternalFilesDir("updates")) { "下载目录暂不可用" }, release.fileName)

    fun startDownload(wifiOnly: Boolean, useFallback: Boolean = false): UpdateView {
        val release = store.release ?: return view()
        if (store.downloadId >= 0 || store.phase == UpdatePhase.READY) return refresh()
        return try {
            require(release.version > requireNotNull(ReleaseVersion.parse(currentVersion))) { "该版本不高于当前版本" }
            val file = downloadFile(release)
            if (file.exists()) require(file.delete()) { "旧安装包无法清理，请稍后重试" }
            val request = DownloadManager.Request(Uri.parse(if (useFallback) requireNotNull(release.fallbackUrl) else release.url))
                .setTitle("抖音跳广告助手 ${release.version}")
                .setDescription(if (wifiOnly) "更新包下载 · 仅 Wi-Fi" else "更新包下载 · 允许移动网络")
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedNetworkTypes(if (wifiOnly) DownloadManager.Request.NETWORK_WIFI
                    else DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                .setAllowedOverMetered(!wifiOnly)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, "updates", release.fileName)
            store.downloadId = downloads.enqueue(request)
            store.downloadWifiOnly = wifiOnly
            store.downloadFallback = useFallback
            store.cancelledKey = ""
            store.state(UpdatePhase.QUEUED, if (wifiOnly) "等待 Wi-Fi 或开始下载" else "已加入下载，允许使用移动网络")
            refresh()
        } catch (e: Exception) {
            store.state(UpdatePhase.ERROR, e.message?.take(180) ?: "无法开始下载，请重试")
            view()
        }
    }

    fun changeNetwork(network: UpdateNetwork): UpdateView {
        val changed = store.network != network
        store.network = network
        if (changed) store.cancelledKey = ""
        return applyNetworkPolicy()
    }

    fun applyNetworkPolicy(): UpdateView {
        val network = store.network
        val incompatible = network == UpdateNetwork.MANUAL || store.downloadWifiOnly != (network == UpdateNetwork.WIFI)
        if (incompatible && store.downloadId >= 0 && store.phase != UpdatePhase.READY) {
            discardDownload()
            store.state(UpdatePhase.AVAILABLE, "下载网络已修改")
        }
        if (store.release != null && store.phase != UpdatePhase.READY && network != UpdateNetwork.MANUAL &&
            store.cancelledKey != store.release?.key) {
            return startDownload(network == UpdateNetwork.WIFI)
        }
        return refresh()
    }

    fun cancel(): UpdateView {
        store.cancelledKey = store.release?.key.orEmpty()
        discardDownload()
        store.state(UpdatePhase.AVAILABLE, "已取消本次下载，可手动重新下载")
        return view()
    }

    private fun discardDownload() {
        if (store.downloadId >= 0) runCatching { downloads.remove(store.downloadId) }
        store.downloadId = -1
        store.downloadFallback = false
        store.release?.let { runCatching { downloadFile(it).delete() } }
        context.getSystemService(NotificationManager::class.java).cancel(UPDATE_NOTIFICATION)
    }

    fun refresh(): UpdateView {
        val release = store.release ?: return view()
        val current = ReleaseVersion.parse(currentVersion)
        if (current != null && release.version <= current) {
            discardDownload(); store.release = null
            store.state(UpdatePhase.LATEST, "当前已是最新版本")
            return view()
        }
        if (store.phase == UpdatePhase.READY) {
            if (runCatching { downloadFile(release).isFile }.getOrDefault(false)) return view()
            store.downloadId = -1
            store.state(UpdatePhase.AVAILABLE, "已下载文件被清理，请重新下载")
            return view()
        }
        if (store.downloadId < 0) return view()
        return try {
            downloads.query(DownloadManager.Query().setFilterById(store.downloadId)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) {
                    store.downloadId = -1; store.state(UpdatePhase.AVAILABLE, "下载任务已丢失，请重新下载")
                    return view()
                }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val completed = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: release.bytes
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val error = validate(release)
                        if (error == null) {
                            store.state(UpdatePhase.READY, "更新包已下载并校验，点击安装更新")
                            notifyUpdate(release, ready = true)
                        } else {
                            discardDownload(); store.state(UpdatePhase.ERROR, error)
                        }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        val canRetry = reason !in listOf(DownloadManager.ERROR_INSUFFICIENT_SPACE, DownloadManager.ERROR_DEVICE_NOT_FOUND,
                            DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_FILE_ALREADY_EXISTS)
                        if (release.fallbackUrl != null && !store.downloadFallback && canRetry) {
                            val wifiOnly = store.downloadWifiOnly
                            discardDownload()
                            return startDownload(wifiOnly, useFallback = true)
                        }
                        discardDownload(); store.state(UpdatePhase.ERROR, if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE)
                            "手机存储空间不足，请清理后重新下载" else "下载来源连接失败，请稍后重新下载")
                    }
                    DownloadManager.STATUS_RUNNING -> store.state(UpdatePhase.DOWNLOADING, "正在下载更新包")
                    DownloadManager.STATUS_PAUSED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        store.state(UpdatePhase.QUEUED, when (reason) {
                            DownloadManager.PAUSED_WAITING_TO_RETRY -> "下载连接暂时失败，系统正在等待重试"
                            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "等待可用的 Wi-Fi 网络"
                            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "等待网络恢复后继续下载"
                            else -> "下载已暂停，等待系统继续"
                        })
                    }
                    else -> store.state(UpdatePhase.QUEUED, "等待网络、Wi-Fi 或系统继续下载")
                }
                UpdateView(store.phase, store.message, release, completed, total)
            }
        } catch (e: Exception) {
            discardDownload()
            store.state(UpdatePhase.ERROR, e.message?.take(180) ?: "读取下载状态失败，请重试")
            view()
        }
    }

    private fun packageData(info: PackageInfo): UpdatePackage {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return UpdatePackage(info.packageName, info.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(info),
            signatures.orEmpty().map { hash(it.toByteArray()) }.toSet())
    }

    private fun validate(release: UpdateRelease): String? {
        val file = downloadFile(release)
        if (!file.isFile || file.length() != release.bytes) return "安装包大小不完整，请重新下载"
        archiveCheck?.let { return it(file, release) }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val installed = packageData(manager.getPackageInfo(context.packageName, flags))
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)?.let(::packageData)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return UpdateVerifier.error(release, installed, archive, file.length(), hex(digest.digest()))
    }

    fun fileForInstall(): File {
        val release = requireNotNull(store.release) { "请先下载更新包" }
        require(store.phase == UpdatePhase.READY) { "更新包尚未完成校验" }
        val error = validate(release)
        if (error != null) { discardDownload(); store.state(UpdatePhase.ERROR, error); throw IllegalStateException(error) }
        return downloadFile(release)
    }

    private fun notifyUpdate(release: UpdateRelease, ready: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
        val key = "${release.key}:$ready"
        if (prefs.getString("notified", "") == key) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "skip_ad_updates"
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel, "版本更新", NotificationManager.IMPORTANCE_LOW))
        manager.notify(UPDATE_NOTIFICATION, NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_skip).setContentTitle(if (ready) "新版本已下载 ${release.version}" else "发现新版本 ${release.version}")
            .setContentText(if (ready) "点击查看并安装更新" else "点击查看更新说明与下载选项")
            .setContentIntent(PendingIntent.getActivity(context, 4, Intent(context, AppUpdateActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).setOnlyAlertOnce(true).build())
        prefs.edit().putString("notified", key).apply()
    }

    companion object {
        const val CHECK_INTERVAL = 6L * 60 * 60 * 1000
        private const val UPDATE_NOTIFICATION = 2
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

internal object AppUpdates {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "SkipAdUpdates").apply { isDaemon = true } }
    private val checking = AtomicBoolean(false)
    private var cached: AppUpdateEngine? = null
    @Synchronized fun engine(context: Context): AppUpdateEngine {
        val app = context.applicationContext
        return cached?.takeIf { it.context === app } ?: AppUpdateEngine(app).also { cached = it }
    }
    fun execute(action: () -> Unit) { executor.execute(action) }
    fun check(context: Context, force: Boolean, finished: (UpdateView) -> Unit) {
        if (!checking.compareAndSet(false, true)) { finished(engine(context).view()); return }
        execute {
            try { finished(engine(context).check(force)) } finally { checking.set(false) }
        }
    }
}
