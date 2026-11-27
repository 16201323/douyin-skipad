package com.example.skipad

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class LogCategory(val label: String) { ALL("全部"), PROBLEM("问题"), SUCCESS("成功") }

internal object DiagnosticReport {
    fun category(entry: RecognitionEntry): LogCategory = when {
        entry.kind == "已确认跳过" -> LogCategory.SUCCESS
        entry.kind in setOf("发送失败", "未确认跳过", "读取失败", "检测异常", "确认中止", "复核取消") -> LogCategory.PROBLEM
        entry.kind == "识别结果" && (entry.message.contains("上下文不足") || entry.message.contains("孤立角标") || entry.message.contains("无法读取") || entry.message.contains("不可读")) -> LogCategory.PROBLEM
        entry.kind == "识别状态" && entry.message.contains("无障碍尚未连接") -> LogCategory.PROBLEM
        entry.kind == "服务连接" && (entry.message.contains("断开") || entry.message.contains("中断")) -> LogCategory.PROBLEM
        else -> LogCategory.ALL
    }
    fun select(entries: List<RecognitionEntry>, filter: LogCategory) = entries.asReversed().filter { filter == LogCategory.ALL || category(it) == filter }
    fun format(entry: RecognitionEntry): String = buildString {
        append(SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.time)))
        append(" · ").append(entry.kind).append('\n').append(entry.message)
        if (entry.ruleName.isNotEmpty()) append("\n规则：").append(entry.ruleName)
        if (entry.packageName.isNotEmpty()) append("\n应用：").append(entry.packageName)
        if (entry.details.isNotEmpty()) append('\n').append(entry.details)
    }
    fun export(context: Context, entries: List<RecognitionEntry>, filter: LogCategory): String = buildString {
        val config = ConfigManager.get(context)
        fun version(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: "未安装或不可读取"
        append("运行诊断 · 当前状态\n助手版本：${version(context.packageName)}\n")
        append("Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n")
        append("抖音版本：${version("com.ss.android.ugc.aweme")}\n极速版版本：${version("com.ss.android.ugc.aweme.lite")}\n")
        append("自动跳过：${if (config.autoSkip) "开启" else "关闭"}；悬浮窗：${if (config.floatingEnabled) "开启" else "关闭"}\n")
        append("无障碍连接：${if (SkipAdAccessibilityService.isRunning()) "已连接" else "未连接"}；后台服务：${if (FloatingButtonService.isRunning()) "运行中" else "未运行"}\n")
        val listeners = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val granted = listeners.split(':').mapNotNull(android.content.ComponentName::unflattenFromString)
            .contains(android.content.ComponentName(context, MediaControlService::class.java))
        append("通知使用权：${if (granted) "已授权" else "未授权"}\n筛选：${filter.label} · ${entries.size}条\n")
        append("以上为复制时状态；下方为历史事件。未发现证据不等于确认没有广告。\n\n")
        append(entries.joinToString("\n\n", transform = ::format))
    }
}
