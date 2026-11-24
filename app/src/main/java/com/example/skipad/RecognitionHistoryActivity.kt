package com.example.skipad

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class RecognitionHistoryActivity : SettingsPageActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("识别记录")
        render()
    }

    private fun render() {
        body.removeAllViews()
        body.addView(ui.label("仅本机保存最近 ${RecognitionHistory.LIMIT} 条简短状态，不记录截图或页面全文。", 13f, ui.secondary))
        body.addView(ui.space(14))
        val actions = ui.row()
        actions.addView(ui.button("刷新") { render() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(ui.button("复制") {
            val text = RecognitionHistory.load(this).asReversed().joinToString("\n\n", transform = ::format)
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("识别记录", text))
            Toast.makeText(this, "已复制识别记录", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        actions.addView(ui.button("清空") {
            AlertDialog.Builder(this).setTitle("清空识别记录？").setPositiveButton("清空") { _, _ ->
                RecognitionHistory.clear(this); render()
            }.setNegativeButton("取消", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        body.addView(actions)
        body.addView(ui.space(16))
        val entries = RecognitionHistory.load(this).asReversed()
        if (entries.isEmpty()) body.addView(ui.label("暂无记录。自动识别运行后，这里会展示状态和处理结果。", 14f))
        for (entry in entries) {
            val card = ui.card()
            card.addView(ui.label(entry.kind, 15f, ui.accent, true))
            card.addView(ui.space(8))
            card.addView(ui.label(format(entry), 12f, ui.secondary).apply { setTextIsSelectable(true) })
            body.addView(card)
        }
    }

    private fun format(entry: RecognitionEntry): String = buildString {
        append(SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.time)))
        append(" · ").append(entry.kind).append('\n').append(entry.message)
        if (entry.ruleName.isNotEmpty()) append("\n规则：").append(entry.ruleName)
        if (entry.packageName.isNotEmpty()) append("\n应用：").append(entry.packageName)
    }
}
