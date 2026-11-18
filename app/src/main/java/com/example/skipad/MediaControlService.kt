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

        /**
         * 跳过广告（切到下一集 / 点击跳过按钮）
         *
         * 跳过策略（两种方式都尝试，任一成功即返回成功）：
         * 1. MediaSession.skipToNext()：对抖音等实现了 MediaSession 回调的 App 有效
         * 2. 无障碍服务点击"跳过"按钮：抖音广告界面可能有"跳过"按钮
         *
         * @return 是否成功跳过
         */
        fun skipDouyinAd(context: Context): Boolean {
            // ---- 策略 1：MediaSession skipToNext ----
            var mediaSkipped = false
            try {
                val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                    as MediaSessionManager
                val componentName = ComponentName(context, MediaControlService::class.java)
                val controllers: List<MediaController> = sessionManager.getActiveSessions(componentName)

                Log.d(TAG, "当前活跃 MediaSession 数量: ${controllers.size}")

                for (controller in controllers) {
                    val pkg = controller.packageName
                    val config = ConfigManager.get(context)

                    // 只控制用户启用的 App
                    if (config.isAppEnabled(pkg)) {
                        Log.d(TAG, "  -> 命中目标 App: $pkg，调用 skipToNext()")
                        controller.transportControls.skipToNext()
                        mediaSkipped = true
                    } else {
                        Log.d(TAG, "  跳过（未启用）: $pkg")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaSession 跳过失败: ${e.message}", e)
            }

            // ---- 策略 2：无障碍服务点击"跳过"按钮 ----
            // 抖音广告界面可能有"跳过"按钮（倒计时结束后出现）
            Log.d(TAG, "尝试无障碍点击跳过按钮")
            val a11ySkipped = SkipAdAccessibilityService.trySkipByClick()
            if (a11ySkipped) {
                Log.d(TAG, "无障碍点击跳过成功")
            }

            // 任一方式成功即返回成功
            val success = mediaSkipped || a11ySkipped
            if (!success) {
                Log.w(TAG, "所有跳过方式均未成功")
            }
            return success
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
