package com.example.skipad

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class RecognitionHistoryActivity : SettingsPageActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var filter = LogCategory.ALL
    private var visibleLimit = 50
    private var shownRevision = -1L
    private lateinit var list: LinearLayout
    private lateinit var count: TextView
    private val buttons = mutableMapOf<LogCategory, android.widget.Button>()
    private val expanded = mutableSetOf<String>()
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1500L) }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        filter = runCatching { LogCategory.valueOf(state?.getString("filter").orEmpty()) }.getOrDefault(LogCategory.ALL)
        page("运行日志")
        body.addView(ui.label("本机保留最近 ${RecognitionHistory.LIMIT} 条状态变化，不记录截图或页面全文。未发现证据不等于确认没有广告。", 12f, ui.secondary))
        body.addView(ui.space(14))
        val filters = ui.row()
        for (value in LogCategory.values()) {
            val button = ui.button(value.label, value == filter) { filter = value; visibleLimit = 50; (body.parent as? ScrollView)?.scrollTo(0, 0); refresh(true) }
                .apply { tag = "log_filter_${value.name.lowercase(Locale.ROOT)}" }
            buttons[value] = button
            filters.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { if (value != LogCategory.ALL) marginStart = ui.dp(6) })
        }
        body.addView(filters)
        val actions = ui.row()
        actions.addView(ui.textButton("刷新") { refresh(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(ui.textButton("复制") {
            val entries = DiagnosticReport.select(RecognitionHistory.load(this), filter)
            val text = DiagnosticReport.export(this, entries, filter)
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("运行日志", text))
            Toast.makeText(this, "已复制当前分类的日志与诊断状态", Toast.LENGTH_SHORT).show()
        }.apply { tag = "copy_logs" }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(6) })
        actions.addView(ui.textButton("清空") {
            AlertDialog.Builder(this).setTitle("清空全部运行日志？").setMessage("将删除所有分类的本机日志，不影响识别规则和设置。")
                .setPositiveButton("清空") { _, _ -> RecognitionHistory.clear(this); expanded.clear(); refresh(true) }
                .setNegativeButton("取消", null).show()
        }.apply { tag = "clear_logs" }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(6) })
        body.addView(ui.space(8)); body.addView(actions)
        count = ui.label("", 12f, ui.secondary).apply { tag = "log_count" }
        body.addView(ui.space(12)); body.addView(count); body.addView(ui.space(12))
        list = ui.column(); body.addView(list)
        refresh(true)
    }
    override fun onResume() { super.onResume(); handler.removeCallbacks(tick); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onSaveInstanceState(state: Bundle) { state.putString("filter", filter.name); super.onSaveInstanceState(state) }
    private fun refresh(force: Boolean = false) {
        if (!::list.isInitialized || (!force && shownRevision == RecognitionHistory.revision)) return
        val revision = RecognitionHistory.revision
        val scroll = body.parent as? ScrollView
        val oldScroll = scroll?.scrollY ?: 0
        val oldHeight = list.height
        val entries = DiagnosticReport.select(RecognitionHistory.load(this), filter)
        count.text = getString(R.string.runtime_log_count, entries.size, if (filter == LogCategory.ALL) "" else filter.label)
        buttons.forEach { (value, button) -> ui.styleButton(button, value == filter); button.isSelected = value == filter }
        list.removeAllViews()
        if (entries.isEmpty()) list.addView(ui.label(if (filter == LogCategory.ALL) "暂无日志。自动识别运行后会记录状态变化。" else "暂无${filter.label}日志。", 14f, ui.secondary))
        for (entry in entries.take(visibleLimit)) {
            val category = DiagnosticReport.category(entry)
            val color = when (category) { LogCategory.PROBLEM -> ui.warning; LogCategory.SUCCESS -> ui.accent; else -> ui.secondary }
            val card = ui.card()
            val header = ui.row()
            header.addView(ui.label(entry.kind, 14f, color, true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(ui.label(SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.time)), 10f, ui.secondary))
            card.addView(header); card.addView(ui.space(8))
            card.addView(ui.label(entry.message, 13f).apply { setTextIsSelectable(true) })
            if (entry.ruleName.isNotEmpty()) { card.addView(ui.space(6)); card.addView(ui.label("规则 · ${entry.ruleName}", 11f, ui.secondary)) }
            val key = "${entry.time}:${entry.kind}:${entry.message}"
            val detail = ui.label(listOf(entry.packageName, entry.details).filter { it.isNotBlank() }.joinToString("\n"), 11f, ui.secondary)
                .apply { setTextIsSelectable(true); visibility = if (key in expanded) View.VISIBLE else View.GONE }
            if (detail.text.isNotEmpty()) {
                val toggle = ui.label(if (key in expanded) "收起详情 ▴" else "诊断详情 ▾", 12f, ui.accent).apply {
                    minimumHeight = ui.dp(48); gravity = android.view.Gravity.CENTER_VERTICAL
                    setOnClickListener {
                        if (!expanded.add(key)) expanded.remove(key)
                        detail.visibility = if (key in expanded) View.VISIBLE else View.GONE
                        text = if (key in expanded) "收起详情 ▴" else "诊断详情 ▾"
                    }
                }
                card.addView(toggle); card.addView(detail)
            }
            list.addView(card)
        }
        if (entries.size > visibleLimit) list.addView(ui.button("加载更多（还有 ${entries.size - visibleLimit} 条）") { visibleLimit += 50; refresh(true) })
        shownRevision = revision
        if (!force && oldScroll > 0) list.post { scroll?.scrollTo(0, (oldScroll + list.height - oldHeight).coerceAtLeast(0)) }
    }
}
