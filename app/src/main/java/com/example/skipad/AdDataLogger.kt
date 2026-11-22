package com.example.skipad

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 广告数据记录器
 *
 * 功能：记录用户跳过广告时的 MediaSession 特征信息，保存到本地文件，
 * 供后续分析广告与正片的状态差异，优化自动跳过检测逻辑。
 *
 * 记录内容：
 * - 时间戳
 * - 事件类型（click_skip: 用户点击跳过 / periodic: 周期性采样）
 * - 前台应用包名
 * - 所有目标 App 的 MediaSession 详细状态（metadata、playbackState 等）
 *
 * 存储位置：/sdcard/Android/data/com.example.skipad/files/ad_data.jsonl
 * 格式：JSON Lines（每行一个 JSON 对象）
 */
object AdDataLogger {

    private const val TAG = "SkipAd_DataLogger"
    private const val LOG_FILE_NAME = "ad_data.jsonl"

    // 单线程写入器：log() 可能在主线程被调用（悬浮窗 1 秒轮询），
    // 磁盘 I/O 放到子线程执行，避免阻塞主线程导致 ANR
    private val writeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 获取日志文件路径
     */
    fun getLogFile(context: Context): File {
        // 保存到 App 外部存储目录，无需额外权限
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, LOG_FILE_NAME)
    }

    /**
     * 记录一条数据
     * @param context 上下文
     * @param eventType 事件类型：click_skip（用户点击跳过）、periodic（周期性采样）
     */
    fun log(context: Context, eventType: String) {
        try {
            val json = JSONObject()
            json.put("timestamp", System.currentTimeMillis())
            json.put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date()))
            json.put("event", eventType)

            // 前台应用包名
            json.put("foreground", SkipAdAccessibilityService.getForegroundPackage() ?: "unknown")

            // 采集所有目标 App 的 MediaSession 状态
            val sessionsArray = org.json.JSONArray()
            try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val controllers = msm.getActiveSessions(ComponentName(context, MediaControlService::class.java))
                val config = ConfigManager.get(context)

                for (controller in controllers) {
                    val sessionJson = JSONObject()
                    val pkg = controller.packageName
                    sessionJson.put("package", pkg)
                    sessionJson.put("enabled", config.isAppEnabled(pkg))

                    // metadata
                    val metadata = controller.metadata
                    if (metadata != null) {
                        val metaJson = JSONObject()
                        metaJson.put("title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
                        metaJson.put("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
                        metaJson.put("album", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
                        metaJson.put("duration", metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
                        sessionJson.put("metadata", metaJson)
                    } else {
                        sessionJson.put("metadata", JSONObject.NULL)
                    }

                    // playbackState
                    val state = controller.playbackState
                    if (state != null) {
                        val stateJson = JSONObject()
                        stateJson.put("state", state.state)
                        stateJson.put("position", state.position)
                        stateJson.put("speed", state.playbackSpeed)
                        stateJson.put("buffered", state.bufferedPosition)
                        sessionJson.put("playbackState", stateJson)
                    } else {
                        sessionJson.put("playbackState", JSONObject.NULL)
                    }

                    // extras（可能包含广告相关信息）
                    val extras = controller.extras
                    if (extras != null && !extras.isEmpty) {
                        val extrasJson = JSONObject()
                        for (key in extras.keySet()) {
                            extrasJson.put(key, extras.get(key)?.toString() ?: "")
                        }
                        sessionJson.put("extras", extrasJson)
                    }

                    sessionsArray.put(sessionJson)
                }
            } catch (e: Exception) {
                json.put("session_error", e.message)
            }
            json.put("sessions", sessionsArray)

            // 追加写入文件（放到子线程，避免主线程磁盘 I/O 阻塞）
            val file = getLogFile(context)
            val line = json.toString() + "\n"
            writeExecutor.execute {
                try {
                    file.appendText(line)
                    // 限制文件大小，超过 10MB 则清空重写（避免占满存储）
                    if (file.length() > 10 * 1024 * 1024) {
                        file.writeText(line)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "写入日志文件失败: ${e.message}")
                }
            }

            if (eventType == "click_skip") {
                Log.d(TAG, "记录跳过广告数据: ${json.toString().take(200)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "记录数据失败: ${e.message}")
        }
    }

    /**
     * 读取所有日志内容（用于分析）
     */
    fun readAllLogs(context: Context): String {
        return try {
            getLogFile(context).readText()
        } catch (e: Exception) {
            "读取失败: ${e.message}"
        }
    }
}
