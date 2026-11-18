package com.example.skipad

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置管理类
 * 负责存储和读取悬浮按钮的样式配置、支持的 App 列表、自动跳过开关等。
 * 使用 SharedPreferences 持久化，应用重启后配置不丢失。
 */
class ConfigManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE)

    companion object {
        // 配置键名
        private const val KEY_BUTTON_COLOR = "button_color"
        private const val KEY_BUTTON_ALPHA = "button_alpha"       // 透明度 0-255
        private const val KEY_BUTTON_SIZE = "button_size"         // 按钮大小（sp 单位）
        private const val KEY_BUTTON_TEXT = "button_text"
        private const val KEY_BUTTON_SHAPE = "button_shape"       // 0=圆形 1=圆角矩形 2=方形
        private const val KEY_TEXT_SIZE = "text_size"             // 文字大小
        private const val KEY_ENABLED_APPS = "enabled_apps"       // 启用的 App 包名（逗号分隔）
        private const val KEY_AUTO_SKIP = "auto_skip"             // 自动跳过开关

        // 形状常量
        const val SHAPE_CIRCLE = 0
        const val SHAPE_ROUND_RECT = 1
        const val SHAPE_SQUARE = 2

        // 默认值：粉色圆形、有文字
        private const val DEFAULT_COLOR = 0xFFE91E63.toInt()  // 粉红
        private const val DEFAULT_ALPHA = 230                 // 约 90% 不透明
        private const val DEFAULT_SIZE = 56                   // 按钮宽高 dp
        private const val DEFAULT_TEXT = "跳过"               // 默认有文字
        private const val DEFAULT_SHAPE = SHAPE_ROUND_RECT    // 圆角矩形
        private const val DEFAULT_TEXT_SIZE = 14f

        @Volatile
        private var instance: ConfigManager? = null

        fun get(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // ===== 颜色 =====
    var buttonColor: Int
        get() = prefs.getInt(KEY_BUTTON_COLOR, DEFAULT_COLOR)
        set(value) = prefs.edit().putInt(KEY_BUTTON_COLOR, value).apply()

    // ===== 透明度（0-255） =====
    var buttonAlpha: Int
        get() = prefs.getInt(KEY_BUTTON_ALPHA, DEFAULT_ALPHA)
        set(value) = prefs.edit().putInt(KEY_BUTTON_ALPHA, value.coerceIn(0, 255)).apply()

    // ===== 按钮大小（dp） =====
    var buttonSize: Int
        get() = prefs.getInt(KEY_BUTTON_SIZE, DEFAULT_SIZE)
        set(value) = prefs.edit().putInt(KEY_BUTTON_SIZE, value.coerceIn(36, 120)).apply()

    // ===== 按钮文字 =====
    var buttonText: String
        get() = prefs.getString(KEY_BUTTON_TEXT, DEFAULT_TEXT) ?: DEFAULT_TEXT
        set(value) = prefs.edit().putString(KEY_BUTTON_TEXT, value).apply()

    // ===== 形状 =====
    var buttonShape: Int
        get() = prefs.getInt(KEY_BUTTON_SHAPE, DEFAULT_SHAPE)
        set(value) = prefs.edit().putInt(KEY_BUTTON_SHAPE, value).apply()

    // ===== 文字大小 =====
    var textSize: Float
        get() = prefs.getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE)
        set(value) = prefs.edit().putFloat(KEY_TEXT_SIZE, value).apply()

    // ===== 自动跳过开关 =====
    var autoSkip: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SKIP, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SKIP, value).apply()

    // ===== 支持的 App 包名列表 =====
    // 所有已知短剧 App 的包名
    val allSupportedApps: List<Pair<String, String>> = listOf(
        "抖音" to "com.ss.android.ugc.aweme",
        "抖音极速版" to "com.ss.android.ugc.aweme.lite"
    )

    /**
     * 获取已启用的 App 包名列表
     */
    fun getEnabledApps(): Set<String> {
        val saved = prefs.getString(KEY_ENABLED_APPS, null)
        return if (saved.isNullOrEmpty()) {
            // 默认启用所有 App
            allSupportedApps.map { it.second }.toSet()
        } else {
            saved.split(",").toSet()
        }
    }

    /**
     * 保存启用的 App 包名列表
     */
    fun setEnabledApps(apps: Set<String>) {
        prefs.edit().putString(KEY_ENABLED_APPS, apps.joinToString(",")).apply()
    }

    /**
     * 判断某个包名是否在启用列表中
     */
    fun isAppEnabled(packageName: String): Boolean {
        return getEnabledApps().contains(packageName)
    }
}
