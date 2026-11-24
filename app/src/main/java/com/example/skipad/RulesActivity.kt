package com.example.skipad

import android.os.Bundle
import android.text.InputFilter
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import java.util.UUID

internal class RulesActivity : SettingsPageActivity() {
    private val store get() = ConfigManager.get(this).adRules

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("识别规则")
        render()
    }

    private fun render() {
        body.removeAllViews()
        body.addView(ui.label("保存后立即生效，仍会校验目标应用、短剧播放器与二次确认。", 13f, ui.secondary))
        body.addView(ui.space(14))
        val controls = ui.row()
        controls.addView(ui.button("添加规则", true) { editRule(null) }.apply { tag = "add_rule" }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(ui.button("恢复默认") {
            AlertDialog.Builder(this).setTitle("恢复默认规则？")
                .setMessage("会替换当前规则，包括你新增和修改的内容。")
                .setPositiveButton("恢复") { _, _ -> store.reset(); changed("已恢复默认规则"); render() }
                .setNegativeButton("取消", null).show()
        }.apply { tag = "reset_rules" }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
        body.addView(controls)
        body.addView(ui.space(12))
        val rules = store.snapshot().rules
        body.addView(ui.label("${rules.size} / ${AdRuleStore.MAX_RULES} 条 · 关闭的规则不参与识别", 12f, ui.secondary))
        body.addView(ui.space(12))
        if (rules.isEmpty()) body.addView(ui.label("暂无规则。添加规则或恢复默认后才能自动识别。", 14f))
        for (rule in rules) {
            val card = ui.card()
            val header = ui.row()
            header.addView(ui.label(rule.name, 16f, medium = true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(SwitchCompat(this).apply {
                tag = "rule_enabled_${rule.id}"; contentDescription = "启用${rule.name}"
                minimumHeight = ui.dp(48); isChecked = rule.enabled
                setOnCheckedChangeListener { _, enabled ->
                    store.save(store.snapshot().rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
                    changed("${if (enabled) "启用" else "关闭"}规则：${rule.name}")
                }
            })
            card.addView(header)
            card.addView(ui.space(6))
            card.addView(ui.label(rule.pattern, 14f).apply { setTextIsSelectable(true) })
            card.addView(ui.space(8))
            card.addView(ui.label("${rule.mode.label} · ${rule.evidence.label}", 12f, ui.secondary))
            card.addView(ui.space(14))
            val actions = ui.row()
            actions.addView(ui.button("编辑") { editRule(rule) }.apply { tag = "edit_rule_${rule.id}" }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(ui.button("删除") {
                AlertDialog.Builder(this).setTitle("删除${rule.name}？").setPositiveButton("删除") { _, _ ->
                    store.save(store.snapshot().rules.filterNot { it.id == rule.id })
                    changed("已删除规则：${rule.name}"); render()
                }.setNegativeButton("取消", null).show()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
            card.addView(actions)
            body.addView(card)
        }
    }

    private fun changed(message: String) {
        RecognitionHistory.record(this, "规则变更", message)
        FloatingButtonService.onRulesChanged()
    }

    private fun editRule(existing: AdRule?) {
        if (existing == null && store.snapshot().rules.size >= AdRuleStore.MAX_RULES) {
            Toast.makeText(this, "最多保存 ${AdRuleStore.MAX_RULES} 条规则", Toast.LENGTH_SHORT).show(); return
        }
        val form = ui.column().apply { setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0) }
        fun input(hint: String, value: String, max: Int, fieldTag: String) = EditText(this).apply {
            this.hint = hint; setText(value); setTextColor(ui.text); setHintTextColor(ui.secondary)
            filters = arrayOf(InputFilter.LengthFilter(max)); tag = fieldTag
            form.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val name = input("规则名称", existing?.name.orEmpty(), 32, "rule_name")
        val pattern = input("匹配内容", existing?.pattern.orEmpty(), 60, "rule_pattern")
        form.addView(ui.label("匹配方式", 12f, ui.secondary))
        val modes = RuleMode.values()
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@RulesActivity, android.R.layout.simple_spinner_dropdown_item, modes.map { it.label })
            setSelection(modes.indexOf(existing?.mode ?: RuleMode.EXACT)); tag = "rule_mode"
        }
        form.addView(mode, LinearLayout.LayoutParams(-1, ui.dp(48)))
        form.addView(ui.label("页面条件", 12f, ui.secondary))
        val evidenceTypes = RuleEvidence.values()
        val evidence = Spinner(this).apply {
            adapter = ArrayAdapter(this@RulesActivity, android.R.layout.simple_spinner_dropdown_item, evidenceTypes.map { it.label })
            setSelection(evidenceTypes.indexOf(existing?.evidence ?: RuleEvidence.PROMPT)); tag = "rule_evidence"
        }
        form.addView(evidence, LinearLayout.LayoutParams(-1, ui.dp(48)))
        form.addView(ui.label("优先使用完整提示。包含匹配更宽泛；倒计时用 {秒} 代表数字。直播引流还需同页的退出短剧、底部剧名及左下直播标记。", 12f, ui.secondary))
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "添加规则" else "编辑规则")
            .setView(android.widget.ScrollView(this).apply { addView(form) })
            .setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val keyword = pattern.text.toString().trim()
                val rule = AdRule(existing?.id ?: UUID.randomUUID().toString(), name.text.toString().trim().ifEmpty { keyword.take(32) },
                    keyword, modes[mode.selectedItemPosition], evidenceTypes[evidence.selectedItemPosition], existing?.enabled ?: true)
                val error = rule.validationError()
                if (error != null) { pattern.error = error; return@setOnClickListener }
                runCatching {
                    val current = store.snapshot().rules
                    store.save(if (current.any { it.id == rule.id }) current.map { if (it.id == rule.id) rule else it } else current + rule)
                }
                    .onSuccess { changed("已保存规则：${rule.name}"); dialog.dismiss(); render() }
                    .onFailure { pattern.error = it.message }
            }
        }
        dialog.show()
    }
}
