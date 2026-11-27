package com.example.skipad

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton

/** 原生界面使用的统一间距、颜色和组件；主题色由 values / values-night 自动切换。 */
internal class AppUi(private val context: Context) {
    val background = context.getColor(R.color.ui_background)
    val surface = context.getColor(R.color.ui_surface)
    val surfaceAlt = context.getColor(R.color.ui_surface_alt)
    val text = context.getColor(R.color.ui_text)
    val secondary = context.getColor(R.color.ui_secondary)
    val line = context.getColor(R.color.ui_line)
    val accent = context.getColor(R.color.ui_accent)
    val onAccent = context.getColor(R.color.ui_on_accent)
    val accentSoft = context.getColor(R.color.ui_accent_soft)
    val hero = context.getColor(R.color.ui_hero)
    val warning = context.getColor(R.color.ui_warning)
    val warningSoft = context.getColor(R.color.ui_warning_soft)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radius: Int = 18, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(1), line)
    }

    fun label(value: String, size: Float = 14f, color: Int = text, medium: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun card(color: Int = surface) = column().apply {
        background = rounded(color)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }

    fun space(height: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    fun divider() = View(context).apply { setBackgroundColor(line); layoutParams = LinearLayout.LayoutParams(-1, dp(1)) }

    fun styleButton(button: android.widget.Button, primary: Boolean) {
        button.backgroundTintList = null
        button.background = RippleDrawable(ColorStateList.valueOf(accentSoft),
            rounded(if (primary) accent else surfaceAlt, 16), null)
        button.setTextColor(if (primary) onAccent else text)
        button.isAllCaps = false
        button.textSize = 14f
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.minimumHeight = dp(48)
        button.minHeight = dp(48)
        button.minimumWidth = 0
        button.minWidth = 0
        button.setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    fun button(value: String, primary: Boolean = false, action: () -> Unit) = AppCompatButton(context).apply {
        text = value
        styleButton(this, primary)
        setOnClickListener { action() }
    }

    fun backButton(action: () -> Unit) = button("‹", action = action).apply {
        contentDescription = "返回"
        textSize = 28f
        background = RippleDrawable(ColorStateList.valueOf(accentSoft), rounded(this@AppUi.background, 12), null)
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    fun textButton(title: String, action: () -> Unit) = button(title, action = action).apply {
        background = RippleDrawable(ColorStateList.valueOf(accentSoft), rounded(android.graphics.Color.TRANSPARENT, 12), null)
        setTextColor(accent)
    }

    fun navigationRow(title: String, description: String? = null, action: () -> Unit) = row().apply {
        minimumHeight = dp(52)
        setPadding(0, dp(10), 0, dp(10))
        isClickable = true; isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(accentSoft), rounded(surface, 10), null)
        val words = column()
        words.addView(label(title, 14f, medium = true))
        if (description != null) { words.addView(space(5)); words.addView(label(description, 11f, secondary).apply { tag = "navigation_summary" }) }
        addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        addView(label("›", 22f, secondary))
        setOnClickListener { action() }
    }

    fun icon(resource: Int, color: Int = accent, size: Int = 24) = ImageView(context).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun badge(value: String, active: Boolean = true) = label(value, 11f,
        if (active) accent else secondary, true).apply {
        background = rounded(if (active) accentSoft else surfaceAlt, 10)
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }
}
