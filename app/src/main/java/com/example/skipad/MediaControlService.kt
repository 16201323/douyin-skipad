package com.example.skipad

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * 媒体控制服务（核心）
 *
 * 继承 NotificationListenerService 的目的：
 *   MediaSessionManager.getActiveSessions(ComponentName) 要求传入已启用的
 *   NotificationListenerService 组件名，才能获取系统中所有活跃的 MediaSession。
 *   这是普通应用无需 root 就能控制其他应用媒体播放的唯一官方途径。
 *
 * 功能：
 * 1. 跳过广告：调用 MediaController.skipToNext()，等价于发送 KEYCODE_MEDIA_NEXT
 * 2. 多 App 支持：根据 ConfigManager 配置过滤目标 App
 * 3. 信号采集：记录 MediaSession 的 metadata/状态变化，用于后续自动跳过研究
 */
class MediaControlService : NotificationListenerService() {

    companion object {
        private const val TAG = "SkipAd_MediaCtrl"

        enum class SkipResult { SENT, CLICKED, FAILED }

        /** 请求已提交不等于跳过成功；调用者观察页面后再累计统计。 */
        fun skipDouyinAd(context: Context, targetPackage: String, canProceed: () -> Boolean = { true }): SkipResult {
            val config = ConfigManager.get(context)
            if (!canProceed() || !config.isAppEnabled(targetPackage) ||
                ForegroundAppResolver.current(context) != targetPackage) return SkipResult.FAILED
            try {
                val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val component = ComponentName(context, MediaControlService::class.java)
                val controller = manager.getActiveSessions(component)
                    .filter { it.packageName == targetPackage }
                    .sortedByDescending { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
                    .firstOrNull()
                if (controller != null) {
                    if (!canProceed()) return SkipResult.FAILED
                    controller.transportControls.skipToNext()
                    Log.d(TAG, "已向 $targetPackage 的一个会话发送切集指令，等待页面确认")
                    return SkipResult.SENT
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送媒体指令失败", e)
            }
            return if (canProceed() && SkipAdAccessibilityService.trySkipByClick(targetPackage, canProceed)) {
                SkipResult.CLICKED
            } else SkipResult.FAILED
        }

        /**
         * 信号采集：记录当前所有活跃 MediaSession 的详细信息
         * 用于研究广告期间的特征信号（metadata、播放状态、位置等）
         */
        fun dumpMediaSessionInfo(context: Context): String {
            val sb = StringBuilder()
            try {
                val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                    as MediaSessionManager
                val componentName = ComponentName(context, MediaControlService::class.java)
                val controllers = sessionManager.getActiveSessions(componentName)

                sb.append("=== MediaSession 信号采集 ===\n")
                sb.append("时间: ${System.currentTimeMillis()}\n")
                sb.append("活跃会话数: ${controllers.size}\n\n")

                for (controller in controllers) {
                    sb.append("--- 会话 ---\n")
                    sb.append("包名: ${controller.packageName}\n")

                    // 元数据
                    val metadata = controller.metadata
                    if (metadata != null) {
                        sb.append("标题: ${metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)}\n")
                        sb.append("艺术家: ${metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)}\n")
                        sb.append("时长: ${metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)}\n")
                    } else {
                        sb.append("元数据: null\n")
                    }

                    // 播放状态
                    val state = controller.playbackState
                    if (state != null) {
                        sb.append("播放状态: ${state.state}\n")
                        sb.append("位置: ${state.position}\n")
                        sb.append("播放速度: ${state.playbackSpeed}\n")
                    } else {
                        sb.append("播放状态: null\n")
                    }
                    sb.append("\n")
                }
            } catch (e: Exception) {
                sb.append("采集失败: ${e.message}")
            }
            return sb.toString()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "通知监听服务已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "通知监听服务已断开")
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {}
}
