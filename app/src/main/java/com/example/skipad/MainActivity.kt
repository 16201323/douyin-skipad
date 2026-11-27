package com.example.skipad

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.CompoundButton
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import androidx.appcompat.widget.SwitchCompat
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import java.io.File
import java.io.FileOutputStream

/**
 * 主 Activity：权限引导 + 悬浮窗设置 + 控制
 *
 * 功能分区：
 * 1. 权限状态提示（通知使用权、悬浮窗权限）
 * 2. 悬浮窗样式设置（颜色、透明度、形状、大小、文字）
 * 3. 支持的 App 选择
 * 4. 自动跳过开关与检测间隔设置
 * 5. 启动/停止悬浮按钮
 */
class MainActivity : AppCompatActivity() {

    private lateinit var config: ConfigManager
    private lateinit var ui: AppUi
    private lateinit var pageHost: FrameLayout
    private lateinit var navigation: LinearLayout
    private var selectedPage = 0
    private lateinit var toolbar: LinearLayout
    private var draftButtonText: String? = null
    private var floatingPreview: FloatingView? = null
    private var imageOptions: View? = null
    private var statusTitle: TextView? = null
    private var statusDetail: TextView? = null
    private var statusBadge: TextView? = null
    private var automaticToggle: SwitchCompat? = null
    private var automaticToggleListener: CompoundButton.OnCheckedChangeListener? = null
    private var todayCountView: TextView? = null
    private var weekCountView: TextView? = null
    private var readinessText: TextView? = null
    private val permissionRefreshers = mutableListOf<() -> Unit>()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() {
            refreshRuntimeUi()
            uiHandler.postDelayed(this, 1500L)
        }
    }
    private var waitingForNotificationPermission = false
    private var waitingForOverlayPermission = false
    private var waitingForUsageStatsPermission = false
    private var waitingForAccessibilityPermission = false

    // 独立的悬浮窗显示开关，从设置返回后同步授权与显示意愿。
    private var floatingToggle: CompoundButton? = null
    // 跳过统计展示（成员变量，便于 onResume 时刷新最新数字）
    private var statsView: TextView? = null
    // 权限状态展示（成员变量，从系统设置返回后需立即刷新，否则一直显示旧的 ❌）
    private var permStatusView: TextView? = null
    // 显示悬浮窗开关的监听器，与自动跳过分开保存。
    private var floatingToggleListener: CompoundButton.OnCheckedChangeListener? = null
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ===== 图片模式相关 =====
    // 选图 / 裁剪的 Activity Result 启动器（在 onCreate 注册）
    private lateinit var pickImageLauncher: ActivityResultLauncher<String>
    private lateinit var cropLauncher: ActivityResultLauncher<CropImageContractOptions>
    // 内容模式按钮（用于高亮当前选中项）
    private var textModeBtn: Button? = null
    private var imageModeBtn: Button? = null
    // 图片预览缩略图
    private var imagePreview: ImageView? = null
    // 形状选择器的所有预览按钮（用于刷新选中高亮）
    private val shapePreviews = mutableListOf<ShapePreviewView>()

    // 预设颜色
    private val presetColors = listOf(
        0xFFFFFFFF.toInt(),  // 白（默认）
        0xFFE91E63.toInt(),  // 粉红
        0xFFF44336.toInt(),  // 红
        0xFFFF9800.toInt(),  // 橙
        0xFFFFEB3B.toInt(),  // 黄
        0xFF4CAF50.toInt(),  // 绿
        0xFF2196F3.toInt(),  // 蓝
        0xFF9C27B0.toInt(),  // 紫
        0xFF000000.toInt()   // 黑
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        config = ConfigManager.get(this)
        AppCompatDelegate.setDefaultNightMode(config.themeMode)
        super.onCreate(savedInstanceState)
        consumeNotificationAction(intent)
        ui = AppUi(this)
        selectedPage = savedInstanceState?.getInt("selected_page", 0) ?: 0
        draftButtonText = savedInstanceState?.getString("draft_button_text")

        // 注册选图 / 裁剪的 Activity Result launcher（必须在 onCreate 完成注册，buildUI 会用到）
        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { launchCrop(it) }
        }
        cropLauncher = registerForActivityResult(CropImageContract()) { result ->
            handleCropResult(result)
        }

        buildUI()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationAction(intent)
        checkAndProceed()
        refreshRuntimeUi()
    }

    private fun consumeNotificationAction(intent: Intent) {
        if (intent.action == FloatingButtonService.ACTION_SHOW_FLOATING) {
            config.floatingEnabled = true
            intent.action = null
        }
    }

    override fun onResume() {
        super.onResume()
        AppUpdateScheduler.onAppOpened(this)
        // 从抖音自动跳过后返回本页，刷新统计数字（今日/本周可能已增加）
        refreshStatsView()
        // 从系统设置返回，刷新权限状态（否则无障碍已开却仍显示 ❌）
        refreshPermissionStatus()
        // 授权齐全时打开 App 即启动；主动暂停保留，不因返回首页重新打开。
        checkAndProceed()
        // 刷新复选框状态
        floatingToggle?.let { cb ->
            cb.setOnCheckedChangeListener(null)
            cb.isChecked = config.floatingEnabled
            cb.setOnCheckedChangeListener(floatingToggleListener)
        }
        refreshRuntimeUi()
        uiHandler.removeCallbacks(statusTick)
        uiHandler.postDelayed(statusTick, 1500L)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(statusTick)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_page", selectedPage)
        draftButtonText?.let { outState.putString("draft_button_text", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * 按运行意愿引导授权：自动跳过需要通知使用权和无障碍；
     * 显示手动按钮才需要悬浮窗权限，无障碍不可用时手动操作另需使用情况访问。
     */
    private fun checkAndProceed() {
        if (!config.autoSkip && !config.floatingEnabled) return
        when {
            !isNotificationListenerEnabled() -> {
                if (!waitingForNotificationPermission) {
                    waitingForNotificationPermission = true
                    showPermissionDialog(
                        "需要开启「通知使用权」",
                        "本功能需要获取短剧 App 的播放控制权来跳过广告。\n\n请在接下来的页面中，找到「抖音跳广告助手」并开启开关。",
                        "去开启"
                    ) { openNotificationListenerSettings() }
                }
            }
            config.autoSkip && !isAccessibilityServiceEnabled() && !SkipAdAccessibilityService.isRunning() -> {
                waitingForNotificationPermission = false
                if (!waitingForAccessibilityPermission) {
                    waitingForAccessibilityPermission = true
                    showPermissionDialog("需要开启「无障碍服务」",
                        "自动跳过需要读取目标应用中的广告提示，不需要显示悬浮按钮。", "去开启") { openAccessibilitySettings() }
                }
            }
            config.floatingEnabled && !canDrawOverlays() -> {
                // 悬浮按钮授权不阻塞已具备权限的自动跳过。
                if (config.autoSkip) syncWorker()
                waitingForNotificationPermission = false
                if (!waitingForOverlayPermission) {
                    waitingForOverlayPermission = true
                    showPermissionDialog(
                        "需要开启「悬浮窗权限」",
                        "需要在屏幕上显示一个「跳过」按钮。\n\n请在接下来的页面中允许本应用显示在其他应用上层。",
                        "去开启"
                    ) { openOverlaySettings() }
                }
            }
            !config.autoSkip && !hasUsageStatsPermission() && !SkipAdAccessibilityService.isRunning() -> {
                waitingForNotificationPermission = false
                waitingForOverlayPermission = false
                if (!waitingForUsageStatsPermission) {
                    waitingForUsageStatsPermission = true
                    showPermissionDialog(
                        "需要开启「使用情况访问」",
                        "手动跳过时需要确认当前正在使用的应用，避免控制后台播放器。若已启用无障碍服务则不需要此权限。\n\n请在接下来的页面中，找到「抖音跳广告助手」并开启开关。",
                        "去开启"
                    ) { openUsageAccessSettings() }
                }
            }
            else -> {
                waitingForNotificationPermission = false
                waitingForOverlayPermission = false
                waitingForUsageStatsPermission = false
                waitingForAccessibilityPermission = false
                // 仅恢复用户期望的运行状态，主动停止后不自动重启。
                syncWorker()
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val component = android.content.ComponentName(this, MediaControlService::class.java)
        return enabled?.split(':')?.any { android.content.ComponentName.unflattenFromString(it) == component } == true
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
    }

    /**
     * 检测是否已授予「使用情况访问」权限
     * PACKAGE_USAGE_STATS 是特殊权限，需用户在系统设置手动开启，用 AppOpsManager 检查状态
     */
    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 打开系统「使用情况访问」设置页面，引导用户授权
     */
    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            // 部分机型该设置页 Intent 可能不同，回退到应用详情页
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        }
    }

    /**
     * 检测无障碍服务是否已启用
     * 通过 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 检查
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val component = android.content.ComponentName(this, SkipAdAccessibilityService::class.java)
        return enabled?.split(':')?.any { android.content.ComponentName.unflattenFromString(it) == component } == true
    }

    /**
     * 构建统计文案：今日 / 本周已跳过条数
     */
    private fun buildStatsText(): String {
        val today = config.getTodaySkipCount()
        val week = config.getWeekSkipCount()
        return "今日已跳过 $today 条   ·   本周已跳过 $week 条"
    }

    /** 刷新统计展示（onResume 时调用，反映自动跳过的最新累计） */
    private fun refreshStatsView() {
        statsView?.text = buildStatsText()
    }

    /** 刷新权限状态展示（从系统设置返回 App 时调用，解决 ❌ 不更新的问题） */
    private fun refreshPermissionStatus() {
        permStatusView?.text = buildPermissionStatus()
    }

    /**
     * 构建完整界面
     */
    private fun buildUI() {
        window.statusBarColor = ui.background
        window.navigationBarColor = ui.surface
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        var flags = window.decorView.systemUiVisibility
        flags = if (night) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) flags = if (night) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = flags
        val shell = ui.column().apply { setBackgroundColor(ui.background) }
        toolbar = ui.row().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }
        shell.addView(toolbar)
        pageHost = FrameLayout(this)
        shell.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        navigation = ui.row().apply {
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
            setBackgroundColor(ui.surface)
        }
        shell.addView(navigation, LinearLayout.LayoutParams(-1, -2))
        setContentView(shell)
        showPage(selectedPage)
    }

    private fun showPage(index: Int) {
        selectedPage = index.coerceIn(0, 3)
        floatingToggle = null
        floatingPreview = null
        imagePreview = null
        imageOptions = null
        statusTitle = null; statusDetail = null; statusBadge = null; automaticToggle = null; automaticToggleListener = null
        todayCountView = null; weekCountView = null; readinessText = null
        permissionRefreshers.clear()
        pageHost.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val content = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(12)) }
        when (selectedPage) {
            0 -> buildHome(content)
            1 -> buildAppearance(content)
            2 -> buildSettings(content)
            else -> buildPermissionDetails(content)
        }
        scroll.addView(content)
        pageHost.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        updateToolbar()
        buildNavigation()
        navigation.visibility = if (selectedPage == 3) View.GONE else View.VISIBLE
        refreshRuntimeUi()
    }

    private fun updateToolbar() {
        toolbar.removeAllViews()
        if (selectedPage == 3) {
            toolbar.addView(ui.backButton { showPage(2) }.apply { tag = "permissions_back" })
            toolbar.addView(ui.label("权限与连接", 18f, medium = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        } else {
            toolbar.addView(ui.icon(R.drawable.ic_skip, size = 22))
            toolbar.addView(ui.label("抖音跳广告助手", 15f, medium = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
            toolbar.addView(ui.badge("本地工具"))
        }
    }

    private fun buildNavigation() {
        navigation.removeAllViews()
        val names = listOf("首页", "悬浮窗", "设置")
        val tags = listOf("nav_home", "nav_appearance", "nav_settings")
        val icons = listOf(R.drawable.ic_home, R.drawable.ic_floating, R.drawable.ic_settings)
        for (index in names.indices) {
            val active = index == selectedPage
            val item = ui.column().apply {
                gravity = Gravity.CENTER
                tag = tags[index]
                contentDescription = names[index]
                isSelected = active
                isClickable = true
                isFocusable = true
                minimumHeight = ui.dp(58)
                if (active) background = ui.rounded(ui.accentSoft, 18)
                setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7))
                setOnClickListener { if (selectedPage != index) showPage(index) }
            }
            item.addView(ui.icon(icons[index], if (active) ui.accent else ui.secondary, 22))
            item.addView(ui.label(names[index], 11f, if (active) ui.accent else ui.secondary, active).apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(5), 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            navigation.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = ui.dp(4); marginEnd = ui.dp(4)
            })
        }
    }

    private fun heading(parent: LinearLayout, title: String, description: String) {
        parent.addView(ui.label(title, 22f, medium = true))
        parent.addView(ui.space(8))
        parent.addView(ui.label(description, 13f, ui.secondary))
        parent.addView(ui.space(16))
    }

    private fun cardTitle(card: LinearLayout, title: String, description: String? = null) {
        card.addView(ui.label(title, 16f, medium = true))
        if (description != null) {
            card.addView(ui.space(6))
            card.addView(ui.label(description, 12f, ui.secondary))
        }
        card.addView(ui.space(16))
    }

    private fun buildHome(parent: LinearLayout) {
        heading(parent, "运行概览", "进入抖音，自动识别支持的广告。")
        val hero = ui.card(ui.hero)
        val top = ui.row()
        statusBadge = ui.badge("尚未启动", false)
        top.addView(statusBadge)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        hero.addView(top)
        hero.addView(ui.space(10))
        statusTitle = ui.label("准备好再出发", 19f, medium = true).apply { tag = "runtime_status_title" }
        statusDetail = ui.label("自动识别广告，无需显示悬浮按钮。", 13f, ui.secondary)
        hero.addView(statusTitle)
        hero.addView(ui.space(9))
        hero.addView(statusDetail)
        hero.addView(ui.space(4))
        val automaticRow = ui.row().apply { tag = "service_toggle" }
        val automaticWords = ui.column()
        automaticWords.addView(ui.label("自动跳过", 17f, medium = true))
        automaticWords.addView(ui.space(5))
        automaticWords.addView(ui.label("识别广告后自动尝试跳过", 12f, ui.secondary))
        automaticRow.addView(automaticWords, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(12) })
        automaticToggle = SwitchCompat(this).apply {
            tag = "auto_skip_toggle"; contentDescription = "自动跳过"
            minimumHeight = ui.dp(48); isChecked = config.autoSkip
        }
        automaticToggleListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            config.autoSkip = checked
            if (checked) checkAndProceed() else syncWorker()
            refreshRuntimeUi()
            uiHandler.postDelayed({ refreshRuntimeUi() }, 300L)
        }
        automaticToggle!!.setOnCheckedChangeListener(automaticToggleListener)
        automaticRow.addView(automaticToggle)
        hero.addView(ui.space(8))
        hero.addView(ui.button("检查权限") { showPage(3) }.apply { tag = "check_permissions" }, LinearLayout.LayoutParams(-1, -2))
        parent.addView(hero)

        val stats = ui.card()
        val statsTitle = ui.row()
        statsTitle.addView(ui.label("已确认跳过", 14f, medium = true), LinearLayout.LayoutParams(0, -2, 1f))
        statsTitle.addView(ui.label("仅统计确认成功", 11f, ui.secondary))
        stats.addView(statsTitle)
        stats.addView(ui.space(18))
        val values = ui.row()
        fun metric(title: String): Pair<LinearLayout, TextView> {
            val column = ui.column()
            val value = ui.label("0", 28f, medium = true)
            column.addView(value)
            column.addView(ui.space(5))
            column.addView(ui.label(title, 12f, ui.secondary))
            return column to value
        }
        val today = metric("今日 · 条"); val week = metric("本周 · 条")
        todayCountView = today.second; weekCountView = week.second
        values.addView(today.first, LinearLayout.LayoutParams(0, -2, 1f))
        values.addView(View(this).apply { setBackgroundColor(ui.line) },
            LinearLayout.LayoutParams(ui.dp(1), ui.dp(48)).apply { marginEnd = ui.dp(24) })
        values.addView(week.first, LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(values)

        val automatic = ui.card()
        automatic.addView(automaticRow)
        automatic.addView(ui.divider())
        val row = ui.row()
        val words = ui.column()
        words.addView(ui.label("显示悬浮窗", 17f, medium = true))
        words.addView(ui.space(5))
        words.addView(ui.label("可选手动按钮，独立控制", 12f, ui.secondary))
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(12) })
        val toggle = SwitchCompat(this).apply {
            isChecked = config.floatingEnabled
            contentDescription = "显示悬浮窗"
            tag = "floating_toggle"
            minimumHeight = ui.dp(48)
            buttonTintList = ColorStateList.valueOf(ui.accent)
        }
        floatingToggle = toggle
        floatingToggleListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            if (checked) startFloatingButton() else stopFloatingButton()
            refreshRuntimeUi()
        }
        toggle.setOnCheckedChangeListener(floatingToggleListener)
        row.addView(toggle)
        automatic.addView(row)
        parent.addView(automatic)
        parent.addView(stats)

        parent.addView(ui.label("支持大窗口及可操作小窗；失焦时也可识别。", 11f, ui.secondary).apply { gravity = Gravity.CENTER })
    }

    private fun buildAppearance(parent: LinearLayout) {
        heading(parent, "你的悬浮窗。", "把常用的按钮，调成喜欢的样子。")
        val previewCard = ui.card()
        cardTitle(previewCard, "实时预览", "调整下方选项，即可查看实际样式。")
        val stage = FrameLayout(this).apply { background = ui.rounded(ui.surfaceAlt, 18) }
        floatingPreview = FloatingView(this).apply { tag = "floating_preview"; contentDescription = "悬浮窗样式预览" }
        val size = ui.dp(config.buttonSize)
        stage.addView(floatingPreview, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        previewCard.addView(stage, LinearLayout.LayoutParams(-1, ui.dp(164)))
        previewCard.addView(ui.space(10))
        previewCard.addView(ui.label("仅样式预览，不会触发跳过", 11f, ui.secondary).apply { gravity = Gravity.CENTER })
        parent.addView(previewCard)

        val content = ui.card()
        cardTitle(content, "显示内容")
        content.addView(createContentModeSelector())
        content.addView(ui.space(14))
        content.addView(ui.label("按钮文字", 12f, ui.secondary))
        content.addView(ui.space(7))
        content.addView(createTextEditor())
        imageOptions = createImageSection().apply { visibility = if (config.contentMode == ConfigManager.CONTENT_IMAGE) View.VISIBLE else View.GONE }
        content.addView(imageOptions)
        parent.addView(content)

        val shape = ui.card()
        cardTitle(shape, "形状与颜色")
        shape.addView(createShapeSelector())
        shape.addView(ui.space(18))
        shape.addView(createColorPicker())
        parent.addView(shape)

        val dimensions = ui.card()
        cardTitle(dimensions, "尺寸与质感")
        addSlider(dimensions, "按钮大小", config.buttonSize, 36, 120, { "${it} dp" }) {
            config.buttonSize = it; refreshFloatingButton()
        }
        addSlider(dimensions, "不透明度", config.buttonAlpha, 0, 255, { "${it * 100 / 255}%" }) {
            config.buttonAlpha = it; refreshFloatingButton()
        }
        addSlider(dimensions, "文字大小", config.textSize.toInt(), 8, 24, { "${it} sp" }) {
            config.textSize = it.toFloat(); refreshFloatingButton()
        }
        parent.addView(dimensions)
    }

    private fun buildSettings(parent: LinearLayout) {
        heading(parent, "设置", "应用、权限与运行偏好")
        val appearance = ui.card()
        cardTitle(appearance, "界面主题", "选择后立即生效，下次打开会保留。")
        val choices = ui.row()
        val themeButtons = mutableListOf<Pair<Int, Button>>()
        for ((mode, title) in listOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to "跟随系统",
            AppCompatDelegate.MODE_NIGHT_NO to "浅色",
            AppCompatDelegate.MODE_NIGHT_YES to "深色")) {
            val button = ui.button(title, config.themeMode == mode) {
                config.themeMode = mode
                themeButtons.forEach { (value, view) ->
                    view.isSelected = value == mode
                    ui.styleButton(view, value == mode)
                    view.textSize = 12f
                    view.setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10))
                }
                AppCompatDelegate.setDefaultNightMode(mode)
            }.apply {
                tag = "theme_$mode"
                isSelected = config.themeMode == mode
                textSize = 12f
                setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10))
            }
            themeButtons.add(mode to button)
            choices.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (mode != AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) marginStart = ui.dp(6)
            })
        }
        appearance.addView(choices)
        parent.addView(appearance)
        val apps = ui.card()
        cardTitle(apps, "目标应用", "只对你选择的应用发送跳过请求。")
        apps.addView(createAppSelector())
        parent.addView(apps)

        val recognition = ui.card()
        cardTitle(recognition, "识别与诊断")
        recognition.addView(ui.navigationRow("识别规则", "维护广告提示关键词") {
            startActivity(Intent(this, RulesActivity::class.java))
        }.apply { tag = "manage_rules" }, LinearLayout.LayoutParams(-1, -2))
        recognition.addView(ui.divider())
        recognition.addView(ui.navigationRow("运行日志", "查看识别状态、跳过结果及问题详情") {
            startActivity(Intent(this, RecognitionHistoryActivity::class.java))
        }.apply { tag = "recognition_history" }, LinearLayout.LayoutParams(-1, -2))
        parent.addView(recognition)

        val permissions = ui.card()
        val permissionEntry = ui.navigationRow("权限与连接", "检查授权状态") { showPage(3) }.apply { tag = "permissions_details" }
        val permissionSummary = permissionEntry.findViewWithTag<TextView>("navigation_summary")
        permissionRefreshers.add {
            permissionSummary.text = if (!isNotificationListenerEnabled()) "待开启通知使用权"
                else if (!isAccessibilityServiceEnabled()) "待开启无障碍服务"
                else if (!SkipAdAccessibilityService.isRunning()) "已授权 · 等待服务连接"
                else "必需授权已就绪 · 无障碍已连接"
        }
        permissions.addView(permissionEntry)
        parent.addView(permissions)

        val detection = ui.card()
        cardTitle(detection, "补充检测", "页面变化时优先检测，此间隔用于补漏。")
        addSlider(detection, "检查间隔", config.detectIntervalMs, 200, 2000, { "${it} ms" }) { config.detectIntervalMs = it }
        detection.addView(ui.label("间隔越小，补漏越及时，也会增加耗电。", 12f, ui.secondary))
        parent.addView(detection)

        val about = ui.card()
        cardTitle(about, "关于")
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        about.addView(ui.label("抖音跳广告助手  ·  $version", 12f, ui.secondary))
        about.addView(ui.space(12))
        about.addView(ui.navigationRow("软件更新", "检查新版与历史版本") { startActivity(Intent(this, AppUpdateActivity::class.java)) }
            .apply { tag = "app_update" }, LinearLayout.LayoutParams(-1, -2))
        about.addView(ui.space(8))
        about.addView(ui.label("识别存在覆盖限制，未自动跳过时可手动点击悬浮窗。", 12f, ui.secondary))
        parent.addView(about)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectedPage == 3) showPage(2) else super.onBackPressed()
    }

    private fun buildPermissionDetails(parent: LinearLayout) {
        val permissions = ui.card()
        cardTitle(permissions, "授权状态", "必需权限就绪并连接后，自动识别即可运行。")
        permissionRow(permissions, "通知使用权", "自动跳过必需 · 获取播放器控制会话", "notification",
            { isNotificationListenerEnabled() }, { openNotificationListenerSettings() })
        permissionRow(permissions, "悬浮窗", "可选 · 仅显示悬浮按钮时需要", "overlay",
            { canDrawOverlays() }, { openOverlaySettings() }, { if (config.floatingEnabled) "未开启" else "未开启 · 可选" })
        permissionRow(permissions, "无障碍服务", "自动跳过必需 · 识别界面中的广告提示", "accessibility",
            { SkipAdAccessibilityService.isRunning() }, { openAccessibilitySettings() },
            { if (isAccessibilityServiceEnabled()) "已授权 · 待连接" else "未开启" })
        permissionRow(permissions, "使用情况访问", "可选 · 未启用无障碍时供手动跳过判断前台", "usage",
            { hasUsageStatsPermission() }, { openUsageAccessSettings() }, { "未开启 · 可选" })
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.addView(ui.divider())
            permissionRow(permissions, "通知显示", "可选 · 显示运行状态和版本更新通知", "notifications",
                { checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED },
                { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) },
                { "已关闭 · 可选" })
        }
        parent.addView(permissions)

    }

    private fun permissionRow(parent: LinearLayout, title: String, description: String, key: String,
        enabled: () -> Boolean, open: () -> Unit, inactive: () -> String = { "未开启" }) {
        val row = ui.row().apply {
            tag = "permission_$key"
            minimumHeight = ui.dp(74)
            isClickable = true; isFocusable = true
            setOnClickListener { open() }
            contentDescription = "$title，点击管理"
        }
        val labels = ui.column()
        labels.addView(ui.label(title, 14f, medium = true))
        labels.addView(ui.space(5))
        labels.addView(ui.label(description, 11f, ui.secondary))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(12) })
        val state = ui.label("", 11f, medium = true).apply { tag = "permission_${key}_state" }
        row.addView(state)
        row.addView(ui.label("  ›", 22f, ui.secondary))
        permissionRefreshers.add {
            val active = enabled()
            state.text = if (active) "已开启" else inactive()
            state.setTextColor(if (active) ui.accent else ui.warning)
        }
        parent.addView(row)
        if (key != "usage") parent.addView(ui.divider())
    }

    private fun addSlider(parent: LinearLayout, title: String, value: Int, min: Int, max: Int,
        format: (Int) -> String, changed: (Int) -> Unit) {
        val line = ui.row()
        line.addView(ui.label(title, 13f), LinearLayout.LayoutParams(0, -2, 1f))
        val valueView = ui.label(format(value), 12f, ui.accent, true)
        line.addView(valueView)
        parent.addView(line)
        parent.addView(createSeekBar(value, max, min) {
            valueView.text = format(it)
            changed(it)
        }.apply { contentDescription = title }, LinearLayout.LayoutParams(-1, ui.dp(48)))
        parent.addView(ui.space(10))
    }

    private fun refreshRuntimeUi() {
        if (!::ui.isInitialized) return
        val running = isFloatingServiceRunning()
        val notificationReady = isNotificationListenerEnabled()
        val accessibilityReady = SkipAdAccessibilityService.isRunning()
        val basicReady = notificationReady && accessibilityReady
        val automaticRunning = running && config.autoSkip && basicReady
        statusBadge?.text = if (automaticRunning) "●  运行中" else if (!config.autoSkip) "○  已关闭" else "○  待就绪"
        statusBadge?.setTextColor(if (automaticRunning) ui.accent else ui.secondary)
        statusTitle?.text = when {
            !config.autoSkip -> "自动跳过已关闭"
            !notificationReady -> "等待通知使用权"
            !accessibilityReady && isAccessibilityServiceEnabled() -> "无障碍服务未连接"
            !accessibilityReady -> "等待无障碍授权"
            automaticRunning -> "自动跳过运行中"
            else -> "等待权限与服务就绪"
        }
        statusDetail?.text = when {
            !config.autoSkip -> "开启后自动识别广告；关闭界面也会记住开关状态。"
            !notificationReady -> "点击检查权限，开启通知使用权后才能控制播放器。"
            !accessibilityReady && isAccessibilityServiceEnabled() -> "已获得无障碍授权，但服务尚未连接。请检查系统中的服务状态。"
            !accessibilityReady -> "点击检查权限，开启无障碍服务后才能识别广告。"
            automaticRunning -> "无需悬浮按钮，进入已选择的应用即可使用。"
            else -> "后台服务尚未运行，请重试或检查系统后台限制。"
        }
        automaticToggle?.let { toggle ->
            if (toggle.isChecked != config.autoSkip) {
                toggle.setOnCheckedChangeListener(null)
                toggle.isChecked = config.autoSkip
                toggle.setOnCheckedChangeListener(automaticToggleListener)
            }
        }
        todayCountView?.text = config.getTodaySkipCount().toString()
        weekCountView?.text = config.getWeekSkipCount().toString()
        readinessText?.text = when {
            !basicReady -> "自动跳过需要通知使用权与已连接的无障碍服务。"
            !config.autoSkip -> "必需权限已就绪；自动跳过开关当前关闭。"
            config.floatingEnabled && !canDrawOverlays() -> "自动跳过已就绪；手动按钮还需要悬浮窗权限。"
            else -> "自动跳过已就绪，无需授予悬浮窗权限。"
        }
        permissionRefreshers.forEach { it() }
    }

    /**
     * 创建颜色选择器（预设色块 + 选色盘）
     */
    private fun createColorPicker(): View {
        val column = ui.column()
        val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val colors = ui.row()
        val swatches = mutableListOf<Pair<Int, FrameLayout>>()
        fun markSelection() {
            swatches.forEach { (color, frame) ->
                frame.background = ui.rounded(if (color == config.buttonColor) ui.accentSoft else ui.surface, 14)
                frame.isSelected = color == config.buttonColor
            }
        }
        for (color in presetColors) {
            val frame = FrameLayout(this).apply {
                minimumWidth = ui.dp(48); minimumHeight = ui.dp(48)
                contentDescription = "按钮颜色 #${Integer.toHexString(color).takeLast(6)}"
                isFocusable = true
                setOnClickListener {
                    config.buttonColor = color
                    markSelection()
                    refreshFloatingButton()
                }
            }
            val dot = View(this).apply { background = ui.rounded(color, 30, true) }
            frame.addView(dot, FrameLayout.LayoutParams(ui.dp(28), ui.dp(28), Gravity.CENTER))
            colors.addView(frame, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginEnd = ui.dp(3) })
            swatches.add(color to frame)
        }
        markSelection()
        scroll.addView(colors)
        column.addView(scroll)
        column.addView(ui.space(12))
        column.addView(ui.button("自定义颜色") {
            ColorPickerDialog(this, config.buttonColor) { color ->
                config.buttonColor = color
                markSelection()
                refreshFloatingButton()
            }.show()
        }, LinearLayout.LayoutParams(-1, -2))
        return column
    }

    /**
     * 创建滑块
     */
    private fun createSeekBar(value: Int, max: Int, min: Int = 0, onChanged: (Int) -> Unit): SeekBar {
        return SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            thumbTintList = ColorStateList.valueOf(ui.accent)
            progressTintList = ColorStateList.valueOf(ui.accent)
            progressBackgroundTintList = ColorStateList.valueOf(ui.line)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChanged(progress + min)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }

    /**
     * 创建形状选择器
     */
    private fun createShapeSelector(): View {
        shapePreviews.clear()
        // 横向滚动容器：形状较多时一行放不下，避免预览被裁切
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // 形状列表：直接画出对应图形作为预览（不再显示文字），与真实悬浮窗一致
        val shapes = listOf(
            ConfigManager.SHAPE_CIRCLE,
            ConfigManager.SHAPE_ROUND_RECT,
            ConfigManager.SHAPE_SQUARE,
            ConfigManager.SHAPE_HEART,
            ConfigManager.SHAPE_STAR,
            ConfigManager.SHAPE_CLOUD,
            ConfigManager.SHAPE_TRIANGLE
        )
        for (value in shapes) {
            val preview = ShapePreviewView(this, value).apply {
                isSelectedShape = (config.buttonShape == value)
                contentDescription = listOf("圆形", "圆角方形", "方形", "爱心", "星形", "云朵", "三角形")[value]
                isFocusable = true
            }
            shapePreviews.add(preview)
            container.addView(preview)
        }
        scroll.addView(container)
        return scroll
    }

    /**
     * 形状预览按钮：直接绘制对应形状的缩略图，选中时画蓝色边框。
     * 用当前按钮颜色/透明度绘制，保证预览和真实悬浮窗一致；
     * 卡通形状复用 ShapePainter 构建 Path，与 FloatingView 完全同源。
     */
    private inner class ShapePreviewView(context: Context, private val shape: Int) : View(context) {
        var isSelectedShape = false

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ui.accent
        }

        init {
            val dp = resources.displayMetrics.density
            val size = (52 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            }
            borderPaint.strokeWidth = 3 * dp
            setOnClickListener {
                config.buttonShape = shape
                refreshFloatingButton()
                // 刷新所有预览的选中高亮
                for (p in shapePreviews) {
                    p.isSelectedShape = (p.shape == shape)
                    p.invalidate()
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val dp = resources.displayMetrics.density
            val pad = 7 * dp
            val w = width - 2 * pad
            val h = height - 2 * pad
            // 用当前按钮颜色/透明度画形状，让预览和真实悬浮窗一致
            fillPaint.color = config.buttonColor
            fillPaint.alpha = config.buttonAlpha
            canvas.save()
            canvas.translate(pad, pad)
            when (shape) {
                ConfigManager.SHAPE_CIRCLE ->
                    canvas.drawCircle(w / 2, h / 2, minOf(w, h) / 2, fillPaint)
                ConfigManager.SHAPE_ROUND_RECT ->
                    canvas.drawRoundRect(RectF(0f, 0f, w, h), 12 * dp, 12 * dp, fillPaint)
                ConfigManager.SHAPE_SQUARE ->
                    canvas.drawRect(0f, 0f, w, h, fillPaint)
                else ->
                    ShapePainter.buildPath(shape, w, h)?.let { canvas.drawPath(it, fillPaint) }
            }
            canvas.restore()
            // 选中态：画圆角边框提示当前选中项
            if (isSelectedShape) {
                canvas.drawRoundRect(
                    RectF(2f, 2f, width - 2f, height - 2f),
                    8 * dp, 8 * dp, borderPaint
                )
            }
        }
    }

    /**
     * 创建文字编辑器
     */
    private fun createTextEditor(): View {
        val container = ui.row()
        val input = EditText(this).apply {
            setText(draftButtonText ?: config.buttonText)
            hint = "留空则不显示文字"
            textSize = 14f
            setTextColor(ui.text)
            setHintTextColor(ui.secondary)
            setSingleLine(true)
            background = ui.rounded(ui.surfaceAlt, 14)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            minimumHeight = ui.dp(50)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draftButtonText = s?.toString() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        container.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
        container.addView(ui.button("应用") {
            config.buttonText = input.text.toString()
            draftButtonText = config.buttonText
            refreshFloatingButton()
            input.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
        })
        return container
    }

    // ===== 内容模式（文字 / 图片） =====

    /**
     * 创建内容模式选择器：文字 / 图片两个互斥按钮，当前模式高亮
     */
    private fun createContentModeSelector(): View {
        val container = ui.row()
        textModeBtn = ui.button("文字") { setContentMode(ConfigManager.CONTENT_TEXT) }
        imageModeBtn = ui.button("图片") { setContentMode(ConfigManager.CONTENT_IMAGE) }
        container.addView(textModeBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
        container.addView(imageModeBtn, LinearLayout.LayoutParams(0, -2, 1f))
        updateContentModeButtons()
        return container
    }

    private fun setContentMode(mode: Int) {
        config.contentMode = mode
        updateContentModeButtons()
        imageOptions?.visibility = if (mode == ConfigManager.CONTENT_IMAGE) View.VISIBLE else View.GONE
        refreshFloatingButton()
    }

    private fun updateContentModeButtons() {
        val isImage = config.contentMode == ConfigManager.CONTENT_IMAGE
        textModeBtn?.let { ui.styleButton(it, !isImage); it.isSelected = !isImage }
        imageModeBtn?.let { ui.styleButton(it, isImage); it.isSelected = isImage }
    }

    // ===== 图片选择与裁剪 =====

    /**
     * 创建图片区：选择图片按钮 + 清除按钮 + 预览缩略图
     */
    private fun createImageSection(): View {
        val container = ui.column()
        container.addView(ui.space(14))
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = ui.rounded(ui.surfaceAlt, 16)
            clipToOutline = true
            contentDescription = "当前图片"
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
        }
        imagePreview = preview
        container.addView(preview, LinearLayout.LayoutParams(-1, ui.dp(120)))
        container.addView(ui.space(10))
        val row = ui.row()
        row.addView(ui.button("选择并裁剪") { pickImageLauncher.launch("image/*") },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
        row.addView(ui.button("清除") {
            config.imagePath = null
            updateImagePreview()
            refreshFloatingButton()
        })
        container.addView(row)
        updateImagePreview()
        return container
    }

    /** 刷新图片预览缩略图 */
    private fun updateImagePreview() {
        val path = config.imagePath
        if (path.isNullOrEmpty() || !File(path).exists()) {
            imagePreview?.setImageDrawable(null)
            return
        }
        val bmp = loadPreviewBitmap(path, 240)
        if (bmp != null) imagePreview?.setImageBitmap(bmp)
    }

    /** 降采样加载预览图（防止大图 OOM） */
    private fun loadPreviewBitmap(path: String, targetSize: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > targetSize * 2 || bounds.outHeight / sample > targetSize * 2) {
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        }
    }

    /** 启动裁剪界面（自由框选，不固定宽高比） */
    private fun launchCrop(sourceUri: Uri) {
        val options = CropImageOptions(
            fixAspectRatio = false,                           // 自由框选，不固定宽高比
            guidelines = CropImageView.Guidelines.ON,         // 显示裁剪辅助线
            outputCompressFormat = Bitmap.CompressFormat.PNG,
            outputCompressQuality = 90
        )
        cropLauncher.launch(CropImageContractOptions(sourceUri, options))
    }

    /** 处理裁剪结果：保存到私有目录并更新配置 */
    private fun handleCropResult(result: CropImageView.CropResult) {
        if (!result.isSuccessful) {
            result.error?.let { Toast.makeText(this, "裁剪失败: ${it.message}", Toast.LENGTH_SHORT).show() }
            return
        }
        // 优先取 Bitmap，取不到再取库输出的文件路径
        val bitmap = result.getBitmap(this)
        val filePath = result.getUriFilePath(this)
        when {
            bitmap != null -> saveCroppedImage(bitmap)
            filePath != null -> {
                config.imagePath = filePath
                config.contentMode = ConfigManager.CONTENT_IMAGE
                updateContentModeButtons()
                imageOptions?.visibility = View.VISIBLE
                updateImagePreview()
                refreshFloatingButton()
                Toast.makeText(this, "图片已设置", Toast.LENGTH_SHORT).show()
            }
            else -> Toast.makeText(this, "裁剪结果为空", Toast.LENGTH_SHORT).show()
        }
    }

    /** 将裁剪后的 Bitmap 保存到 App 私有目录 */
    private fun saveCroppedImage(bitmap: Bitmap) {
        try {
            val file = File(filesDir, "cropped_image.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            config.imagePath = file.absolutePath
            config.contentMode = ConfigManager.CONTENT_IMAGE
            updateContentModeButtons()
            imageOptions?.visibility = View.VISIBLE
            updateImagePreview()
            refreshFloatingButton()
            Toast.makeText(this, "图片已设置", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 创建 App 选择器
     */
    private fun createAppSelector(): View {
        val container = ui.column()
        for ((index, app) in config.allSupportedApps.withIndex()) {
            val (name, pkg) = app
            val row = ui.row().apply { minimumHeight = ui.dp(62) }
            val icon = ui.label(if (index == 0) "抖" else "速", 16f, ui.accent, true).apply {
                gravity = Gravity.CENTER; background = ui.rounded(ui.accentSoft, 13)
            }
            row.addView(icon, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(12) })
            row.addView(ui.label(name, 14f, medium = true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(SwitchCompat(this).apply {
                isChecked = config.isAppEnabled(pkg)
                contentDescription = "启用$name"
                minimumHeight = ui.dp(48)
                setOnCheckedChangeListener { _, checked ->
                    val current = config.getEnabledApps().toMutableSet()
                    if (checked) current.add(pkg) else current.remove(pkg)
                    config.setEnabledApps(current)
                }
            })
            container.addView(row)
            if (index < config.allSupportedApps.lastIndex) container.addView(ui.divider())
        }
        return container
    }

    // ===== 权限相关 =====

    private fun showPermissionDialog(title: String, message: String, positiveText: String, onPositive: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(message).setCancelable(false)
            .setPositiveButton(positiveText) { _, _ -> onPositive() }
            .setNegativeButton("稍后再说") { _, _ ->
                waitingForNotificationPermission = false
                waitingForOverlayPermission = false
                waitingForUsageStatsPermission = false
                waitingForAccessibilityPermission = false
            }
            .show()
    }

    private fun openNotificationListenerSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    /**
     * 打开系统无障碍设置页面
     */
    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            // 部分机型无障碍设置页面 Intent 可能不同，回退到应用详情页
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        }
    }

    // ===== 服务控制 =====

    private fun startFloatingButton() {
        config.floatingEnabled = true
        checkAndProceed()
    }

    private fun syncWorker() {
        if (!isNotificationListenerEnabled()) return
        if (!config.autoSkip && !config.floatingEnabled && !isFloatingServiceRunning()) return
        val intent = Intent(this, FloatingButtonService::class.java).apply {
            action = FloatingButtonService.ACTION_REFRESH
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val prefs = getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("notification_prompted", false)) {
                    prefs.edit().putBoolean("notification_prompted", true).apply()
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "无法启动跳过服务：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopFloatingButton() {
        config.floatingEnabled = false
        syncWorker()
    }

    private fun refreshFloatingButton() {
        floatingPreview?.let { preview ->
            val size = ui.dp(config.buttonSize)
            preview.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            preview.updateConfig()
        }
        shapePreviews.forEach { it.invalidate() }
        if (!isFloatingServiceRunning()) return
        startService(Intent(this, FloatingButtonService::class.java).apply {
            action = FloatingButtonService.ACTION_REFRESH
        })
    }

    private fun isFloatingServiceRunning(): Boolean = FloatingButtonService.isRunning()

    private fun buildPermissionStatus(): String {
        val n = if (isNotificationListenerEnabled()) "✓ 已开启" else "✗ 未开启"
        val o = if (canDrawOverlays()) "✓ 已开启" else "✗ 未开启"
        val u = if (hasUsageStatsPermission()) "✓ 已开启" else "✗ 未开启"
        val a = if (isAccessibilityServiceEnabled()) "✓ 已开启" else "✗ 未开启"
        return "通知使用权: $n\n悬浮窗权限: $o\n使用情况访问: $u\n无障碍服务: $a（仅自动跳过需要）"
    }
}
