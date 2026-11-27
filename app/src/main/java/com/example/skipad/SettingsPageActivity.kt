package com.example.skipad

import android.os.Bundle
import android.os.Build
import android.view.View
import android.content.res.Configuration
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/** 二级设置页沿用首页的原生组件、主题及系统栏样式。 */
internal abstract class SettingsPageActivity : AppCompatActivity() {
    protected lateinit var ui: AppUi
    protected lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(ConfigManager.get(this).themeMode)
        super.onCreate(savedInstanceState)
        ui = AppUi(this)
        window.statusBarColor = ui.background
        window.navigationBarColor = ui.background
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        window.decorView.systemUiVisibility = if (night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
    }

    protected fun page(title: String) {
        val shell = ui.column().apply { setBackgroundColor(ui.background); setPadding(ui.dp(20), ui.dp(12), ui.dp(20), 0) }
        val header = ui.row()
        header.addView(ui.backButton { finish() })
        header.addView(ui.label(title, 20f, medium = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        shell.addView(header)
        shell.addView(ui.space(16))
        body = ui.column().apply { setPadding(0, 0, 0, ui.dp(16)) }
        shell.addView(ScrollView(this).apply { addView(body); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(shell)
    }
}
