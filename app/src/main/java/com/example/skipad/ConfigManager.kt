package com.example.skipad

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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
        private const val KEY_BUTTON_SHAPE = "button_shape"       // 0=圆形 1=圆角矩形 2=方形 3=爱心 4=五角星 5=云朵 6=三角形
        private const val KEY_TEXT_SIZE = "text_size"             // 文字大小
        private const val KEY_ENABLED_APPS = "enabled_apps"       // 启用的 App 包名（逗号分隔）
        private const val KEY_AUTO_SKIP = "auto_skip"             // 自动跳过开关
        private const val KEY_DETECT_INTERVAL = "detect_interval" // 广告检测轮询间隔（毫秒）
        private const val KEY_CONTENT_MODE = "content_mode"       // 内容模式：0=文字 1=图片
        private const val KEY_IMAGE_PATH = "image_path"           // 裁剪后图片的私有目录绝对路径
        private const val KEY_BUTTON_X = "button_x"               // 记忆的悬浮窗 x 坐标（-1 表示未设置，用默认位置）
        private const val KEY_BUTTON_Y = "button_y"               // 记忆的悬浮窗 y 坐标（-1 表示未设置）
        private const val KEY_STATS_DATE = "stats_date"           // 当前计数所属日期 yyyy-MM-dd（跨天归零用）
        private const val KEY_STATS_TODAY = "stats_today"         // 今日跳过次数
        private const val KEY_STATS_WEEK = "stats_week"           // 本周跳过次数
        private const val KEY_STATS_WEEK_KEY = "stats_week_key"   // 本周标识 yyyy-ww（跨周归零用）

        // 形状常量
        const val SHAPE_CIRCLE = 0
        const val SHAPE_ROUND_RECT = 1
        const val SHAPE_SQUARE = 2
        const val SHAPE_HEART = 3       // 爱心
        const val SHAPE_STAR = 4        // 五角星
        const val SHAPE_CLOUD = 5       // 云朵
        const val SHAPE_TRIANGLE = 6    // 三角形

        // 内容模式常量
        const val CONTENT_TEXT = 0
        const val CONTENT_IMAGE = 1

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
    // AppCompat 夜间模式：-1 跟随系统，1 浅色，2 深色。
    var themeMode: Int
        get() = prefs.getInt("theme_mode", -1).takeIf { it in listOf(-1, 1, 2) } ?: -1
        set(value) = prefs.edit().putInt("theme_mode", value.takeIf { it in listOf(-1, 1, 2) } ?: -1).apply()

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

    // ===== 内容模式（文字 / 图片） =====
    var contentMode: Int
        get() = prefs.getInt(KEY_CONTENT_MODE, CONTENT_TEXT)
        set(value) = prefs.edit().putInt(KEY_CONTENT_MODE, value).apply()

    // 裁剪后图片的私有目录路径（图片模式下使用）
    var imagePath: String?
        get() = prefs.getString(KEY_IMAGE_PATH, null)
        set(value) = prefs.edit().putString(KEY_IMAGE_PATH, value).apply()

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

    // 主动停止后不因 Activity.onResume 或系统重建再次启动。
    var floatingEnabled: Boolean
        get() = prefs.getBoolean("floating_enabled", true)
        set(value) = prefs.edit().putBoolean("floating_enabled", value).apply()

    val hasSavedPosition: Boolean
        get() = prefs.contains(KEY_BUTTON_X) && prefs.contains(KEY_BUTTON_Y)

    // ===== 广告检测轮询间隔（毫秒）=====
    // 前台应用检测与广告识别的轮询周期：值越小响应越快，但越耗电。
    // 范围 200~2000ms，默认 500ms（实测约 0.8 秒完成首次检测→跳过）。
    var detectIntervalMs: Int
        get() = prefs.getInt(KEY_DETECT_INTERVAL, 500)
        set(value) = prefs.edit().putInt(KEY_DETECT_INTERVAL, value.coerceIn(200, 2000)).apply()

    // ===== 悬浮窗位置记忆 =====
    // 保存拖动后的位置（x/y 为 WindowManager gravity=END|CENTER_VERTICAL 坐标系下的偏移量）。
    // -1 表示从未保存过，showFloatingButton 会回退到默认位置。
    var buttonX: Int
        get() = prefs.getInt(KEY_BUTTON_X, -1)
        set(value) = prefs.edit().putInt(KEY_BUTTON_X, value).apply()

    var buttonY: Int
        get() = prefs.getInt(KEY_BUTTON_Y, -1)
        set(value) = prefs.edit().putInt(KEY_BUTTON_Y, value).apply()

    // ===== 跳过统计 =====
    /** 今日已跳过次数（跨天自动归零） */
    fun getTodaySkipCount(): Int {
        rolloverIfNeeded()
        return prefs.getInt(KEY_STATS_TODAY, 0)
    }

    /** 本周已跳过次数（跨周自动归零） */
    fun getWeekSkipCount(): Int {
        rolloverIfNeeded()
        return prefs.getInt(KEY_STATS_WEEK, 0)
    }

    /** 记录一次成功跳过（内部先处理跨天/跨周归零，再累加今日和本周计数） */
    fun incrementSkipCount() {
        rolloverIfNeeded()
        prefs.edit()
            .putInt(KEY_STATS_TODAY, prefs.getInt(KEY_STATS_TODAY, 0) + 1)
            .putInt(KEY_STATS_WEEK, prefs.getInt(KEY_STATS_WEEK, 0) + 1)
            .apply()
    }

    /**
     * 跨天/跨周归零检查：
     * 每次读/写统计前调用，若当前日期与记录日期不一致，则今日计数清零；
     * 若当前周标识与记录的不一致，本周计数也清零，并更新记录的日期/周标识。
     */
    private fun rolloverIfNeeded() {
        val cal = Calendar.getInstance()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
        val weekKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.WEEK_OF_YEAR)}"
        val savedDate = prefs.getString(KEY_STATS_DATE, "")
        if (savedDate != today) {
            val edit = prefs.edit()
                .putInt(KEY_STATS_TODAY, 0)
                .putString(KEY_STATS_DATE, today)
            // 日期变化时顺带判断周是否也变化，跨周则本周计数归零
            if (prefs.getString(KEY_STATS_WEEK_KEY, "") != weekKey) {
                edit.putInt(KEY_STATS_WEEK, 0).putString(KEY_STATS_WEEK_KEY, weekKey)
            }
            edit.apply()
        }
    }

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
        // 区分「从未设置」与「已设置为空」：
        // - 从未设置（键不存在）→ 默认启用所有 App
        // - 已设置为空字符串 → 用户主动取消了全部勾选，应返回空集
        //   （旧逻辑用 isNullOrEmpty 会把空集误判为未设置，导致无法取消全部 App）
        if (!prefs.contains(KEY_ENABLED_APPS)) {
            return allSupportedApps.map { it.second }.toSet()
        }
        val saved = prefs.getString(KEY_ENABLED_APPS, "") ?: ""
        if (saved.isEmpty()) return emptySet()
        return saved.split(",").toSet()
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
