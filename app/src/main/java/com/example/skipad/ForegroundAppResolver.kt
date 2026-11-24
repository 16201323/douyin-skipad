package com.example.skipad

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager

/** 操作前实时确定可见目标窗口；无障碍不可用时仅用前台事件，不猜测媒体会话所属应用。 */
internal object ForegroundAppResolver {
    fun current(context: Context): String? {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!power.isInteractive || keyguard.isKeyguardLocked) return null
        if (SkipAdAccessibilityService.isRunning()) {
            return SkipAdAccessibilityService.getVisibleTargetPackage()
        }
        return try {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = manager.queryEvents(now - 86_400_000L, now)
            val event = UsageEvents.Event()
            var foreground: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> foreground = event.packageName
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (foreground == event.packageName) foreground = null
                    }
                }
            }
            foreground
        } catch (_: Exception) {
            null
        }
    }
}
