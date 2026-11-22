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
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
    private var waitingForNotificationPermission = false
    private var waitingForOverlayPermission = false
    private var waitingForUsageStatsPermission = false
    private var waitingForAccessibilityPermission = false

    // 自动跳过复选框（成员变量，便于从设置返回后刷新状态）
    private var autoSkipCheckBox: CheckBox? = null
    // 跳过统计展示（成员变量，便于 onResume 时刷新最新数字）
    private var statsView: TextView? = null
    // 权限状态展示（成员变量，从系统设置返回后需立即刷新，否则一直显示旧的 ❌）
    private var permStatusView: TextView? = null
    // 自动跳过复选框的监听器（成员变量，便于刷新时重新设置）
    private var autoSkipListener: android.widget.CompoundButton.OnCheckedChangeListener? = null
    // 标记：用户想开启自动跳过，但无障碍服务未开，等待用户开启后自动勾选
    private var pendingAutoSkip = false

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
        super.onCreate(savedInstanceState)
        config = ConfigManager.get(this)

        // 注册选图 / 裁剪的 Activity Result launcher（必须在 onCreate 完成注册，buildUI 会用到）
        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { launchCrop(it) }
        }
        cropLauncher = registerForActivityResult(CropImageContract()) { result ->
            handleCropResult(result)
        }

        buildUI()
    }

    override fun onResume() {
        super.onResume()
        // 从抖音自动跳过后返回本页，刷新统计数字（今日/本周可能已增加）
        refreshStatsView()
        // 从系统设置返回，刷新权限状态（否则无障碍已开却仍显示 ❌）
        refreshPermissionStatus()
        checkAndProceed()
        // 从无障碍设置返回后，如果用户之前想开启自动跳过且无障碍已开，自动勾选
        if (pendingAutoSkip && isAccessibilityServiceEnabled()) {
            pendingAutoSkip = false
            config.autoSkip = true
            autoSkipCheckBox?.let { cb ->
                cb.setOnCheckedChangeListener(null)
                cb.isChecked = true
                cb.setOnCheckedChangeListener(autoSkipListener)
            }
            Toast.makeText(this, "无障碍服务已开启，自动跳过已启用", Toast.LENGTH_SHORT).show()
        }
        // 刷新复选框状态
        autoSkipCheckBox?.let { cb ->
            cb.setOnCheckedChangeListener(null)
            cb.isChecked = config.autoSkip
            cb.setOnCheckedChangeListener(autoSkipListener)
        }
    }

    /**
     * 权限检测与流程推进（通知使用权 → 悬浮窗 → 使用情况访问）
     * 注意：
     * - 「使用情况访问」用于检测当前前台应用，决定悬浮窗在哪些界面显示，必须开启。
     * - 无障碍服务不在这里强制引导，只有用户开启「自动跳过」时才检查。
     */
    private fun checkAndProceed() {
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
            !canDrawOverlays() -> {
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
            !hasUsageStatsPermission() -> {
                waitingForNotificationPermission = false
                waitingForOverlayPermission = false
                if (!waitingForUsageStatsPermission) {
                    waitingForUsageStatsPermission = true
                    showPermissionDialog(
                        "需要开启「使用情况访问」",
                        "需要读取前台应用信息，才能让悬浮窗只在本应用和抖音中显示、在其他界面自动隐藏。\n\n请在接下来的页面中，找到「抖音跳广告助手」并开启开关。",
                        "去开启"
                    ) { openUsageAccessSettings() }
                }
            }
            else -> {
                waitingForNotificationPermission = false
                waitingForOverlayPermission = false
                waitingForUsageStatsPermission = false
                // 所有权限就绪后，若悬浮窗服务未运行则自动启动（打开 App 默认显示悬浮窗）
                if (!isFloatingServiceRunning()) {
                    startFloatingButton()
                }
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.contains(packageName) == true
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
        return enabled?.contains(packageName) == true
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
        val scroll = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // === 标题 ===
        layout.addView(makeTitle("抖音短剧跳广告助手"))

        // === 跳过统计（今日 / 本周）===
        layout.addView(makeSectionTitle("跳过统计"))
        statsView = TextView(this).apply {
            text = buildStatsText()
            textSize = 15f
            setPadding(0, 8, 0, 16)
        }
        layout.addView(statsView)

        // === 权限状态 ===
        layout.addView(makeSectionTitle("权限状态"))
        permStatusView = TextView(this).apply {
            text = buildPermissionStatus()
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        layout.addView(permStatusView)

        // 「去开启无障碍」按钮：自动跳过依赖无障碍，ColorOS 容易偷偷关掉，
        // 给个直达入口，避免用户在设置里翻半天找不到。
        layout.addView(android.widget.Button(this).apply {
            text = "去开启无障碍服务"
            setOnClickListener {
                try {
                    // 直接跳到系统无障碍设置页
                    startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "无法打开设置，请手动前往：设置→无障碍", Toast.LENGTH_LONG).show()
                }
            }
        })

        // === 悬浮窗样式 ===
        layout.addView(makeSectionTitle("悬浮窗样式"))

        // 颜色选择
        layout.addView(makeLabel("按钮颜色"))
        layout.addView(createColorPicker())

        // 透明度
        layout.addView(makeLabel("透明度: ${config.buttonAlpha * 100 / 255}%"))
        layout.addView(createSeekBar(config.buttonAlpha, 255) { value ->
            config.buttonAlpha = value
            refreshFloatingButton()
        })

        // 大小
        layout.addView(makeLabel("按钮大小: ${config.buttonSize}dp"))
        layout.addView(createSeekBar(config.buttonSize, 120, 36) { value ->
            config.buttonSize = value
            refreshFloatingButton()
        })

        // 形状
        layout.addView(makeLabel("形状"))
        layout.addView(createShapeSelector())

        // 内容模式（文字 / 图片）
        layout.addView(makeLabel("内容"))
        layout.addView(createContentModeSelector())

        // 图片区（图片模式下选图、预览、清除）
        layout.addView(createImageSection())

        // 文字（图片模式下作为叠加文字，留空则纯图片）
        layout.addView(makeLabel("按钮文字（图片模式可留空）"))
        layout.addView(createTextEditor())

        // 文字大小
        layout.addView(makeLabel("文字大小: ${config.textSize.toInt()}sp"))
        layout.addView(createSeekBar(config.textSize.toInt(), 24, 8) { value ->
            config.textSize = value.toFloat()
            refreshFloatingButton()
        })

        // === 支持的 App ===
        layout.addView(makeSectionTitle("支持的 App"))
        layout.addView(createAppSelector())

        // === 自动跳过 ===
        layout.addView(makeSectionTitle("自动跳过"))
        val autoSkipCb = CheckBox(this).apply {
            text = "开启后，广告出现时自动跳过（需开启无障碍服务）"
            isChecked = config.autoSkip
            textSize = 13f
        }
        autoSkipCheckBox = autoSkipCb
        // 防止取消勾选时递归触发监听器
        var autoSkipResetting = false
        autoSkipListener = android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            if (autoSkipResetting) return@OnCheckedChangeListener
            if (checked && !isAccessibilityServiceEnabled()) {
                // 开启自动跳过时需要无障碍服务，未开启则引导并取消勾选
                config.autoSkip = false
                pendingAutoSkip = true  // 标记：等待用户开启无障碍后自动勾选
                autoSkipResetting = true
                autoSkipCb.isChecked = false
                autoSkipResetting = false
                showPermissionDialog(
                    "需要开启「无障碍服务」",
                    "自动跳过功能需要无障碍服务来检测广告界面。\n\n请在接下来的页面中，找到「抖音跳广告助手」并开启开关。",
                    "去开启"
                ) { openAccessibilitySettings() }
            } else {
                pendingAutoSkip = false
                config.autoSkip = checked
                Toast.makeText(this@MainActivity, if (checked) "自动跳过已开启" else "自动跳过已关闭", Toast.LENGTH_SHORT).show()
            }
        }
        autoSkipCb.setOnCheckedChangeListener(autoSkipListener)
        layout.addView(autoSkipCb)

        // 广告检测间隔：用户可调节前台检测 / 广告识别的轮询频率，值越小响应越快但越耗电。
        // 范围 200~2000ms，默认 500ms；FloatingButtonService 每轮轮询都读取此值，改完即时生效。
        val intervalLabel = makeLabel("广告检测间隔: ${config.detectIntervalMs}ms")
        layout.addView(intervalLabel)
        layout.addView(createSeekBar(config.detectIntervalMs, 2000, 200) { value ->
            config.detectIntervalMs = value
            intervalLabel.text = "广告检测间隔: ${value}ms"   // 拖动时实时刷新显示
        })

        // === 控制按钮 ===
        layout.addView(makeSectionTitle("控制"))
        layout.addView(makeButton("启动悬浮按钮") { startFloatingButton() })
        layout.addView(makeButton("停止悬浮按钮") { stopFloatingButton() })

        scroll.addView(layout)
        setContentView(scroll)
    }

    // ===== UI 构造辅助方法 =====

    private fun makeTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 22f; setPadding(0, 0, 0, 16)
    }

    private fun makeSectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 16f; setPadding(0, 24, 0, 8)
        setTextColor(0xFF333333.toInt())
    }

    private fun makeLabel(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setPadding(0, 12, 0, 4)
    }

    private fun makeButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; setOnClickListener { onClick() }
    }

    /**
     * 创建颜色选择器（预设色块 + 选色盘）
     */
    private fun createColorPicker(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        for (color in presetColors) {
            val btn = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(80, 80).apply { marginEnd = 12 }
                setBackgroundColor(color)
                setOnClickListener {
                    config.buttonColor = color
                    refreshFloatingButton()
                }
            }
            container.addView(btn)
        }
        // 选色盘按钮
        val customBtn = Button(this).apply {
            text = "选色盘"
            textSize = 11f
            setOnClickListener {
                ColorPickerDialog(this@MainActivity, config.buttonColor) { color ->
                    config.buttonColor = color
                    refreshFloatingButton()
                    Toast.makeText(this@MainActivity, "颜色已更新", Toast.LENGTH_SHORT).show()
                }.show()
            }
        }
        container.addView(customBtn)
        return container
    }

    /**
     * 创建滑块
     */
    private fun createSeekBar(value: Int, max: Int, min: Int = 0, onChanged: (Int) -> Unit): SeekBar {
        return SeekBar(this).apply {
            this.max = max - min
            progress = value - min
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
            color = 0xFF2196F3.toInt()   // 蓝色选中边框
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
        val container = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            setText(config.buttonText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = Button(this).apply {
            text = "保存"
            setOnClickListener {
                // 允许保存空文字：图片模式下清空文字即显示纯图片背景，文字模式下清空则为纯色块无文字
                // （FloatingView.onDraw 已判断 text.isNotEmpty() 才绘制文字，空文字不会导致悬浮窗空白）
                config.buttonText = input.text.toString()
                refreshFloatingButton()
                Toast.makeText(this@MainActivity, "文字已更新", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(input)
        container.addView(btn)
        return container
    }

    // ===== 内容模式（文字 / 图片） =====

    /**
     * 创建内容模式选择器：文字 / 图片两个互斥按钮，当前模式高亮
     */
    private fun createContentModeSelector(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val textBtn = Button(this).apply {
            text = "文字"
            textSize = 12f
            setOnClickListener { setContentMode(ConfigManager.CONTENT_TEXT) }
        }
        val imageBtn = Button(this).apply {
            text = "图片"
            textSize = 12f
            setOnClickListener { setContentMode(ConfigManager.CONTENT_IMAGE) }
        }
        textModeBtn = textBtn
        imageModeBtn = imageBtn
        container.addView(textBtn)
        container.addView(imageBtn)
        updateContentModeButtons()
        return container
    }

    /** 切换内容模式并刷新悬浮窗 */
    private fun setContentMode(mode: Int) {
        config.contentMode = mode
        updateContentModeButtons()
        refreshFloatingButton()
    }

    /** 刷新内容模式按钮的高亮状态（选中项蓝底白字，未选中灰底黑字） */
    private fun updateContentModeButtons() {
        val isImage = config.contentMode == ConfigManager.CONTENT_IMAGE
        textModeBtn?.apply {
            setBackgroundColor(if (!isImage) 0xFF2196F3.toInt() else 0xFFDDDDDD.toInt())
            setTextColor(if (!isImage) Color.WHITE else Color.BLACK)
        }
        imageModeBtn?.apply {
            setBackgroundColor(if (isImage) 0xFF2196F3.toInt() else 0xFFDDDDDD.toInt())
            setTextColor(if (isImage) Color.WHITE else Color.BLACK)
        }
    }

    // ===== 图片选择与裁剪 =====

    /**
     * 创建图片区：选择图片按钮 + 清除按钮 + 预览缩略图
     */
    private fun createImageSection(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pickBtn = Button(this).apply {
            text = "选择图片"
            textSize = 12f
            setOnClickListener { pickImageLauncher.launch("image/*") }
        }
        val clearBtn = Button(this).apply {
            text = "清除图片"
            textSize = 12f
            setOnClickListener {
                config.imagePath = null
                updateImagePreview()
                refreshFloatingButton()
                Toast.makeText(this@MainActivity, "已清除图片", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(pickBtn)
        row.addView(clearBtn)
        container.addView(row)

        // 预览缩略图（CENTER_CROP 居中裁剪显示）
        val preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(240, 240).apply { topMargin = 8 }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        imagePreview = preview
        container.addView(preview)
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
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val enabled = config.getEnabledApps()
        for ((name, pkg) in config.allSupportedApps) {
            val cb = CheckBox(this).apply {
                text = "$name ($pkg)"
                isChecked = enabled.contains(pkg)
                setOnCheckedChangeListener { _, isChecked ->
                    val current = config.getEnabledApps().toMutableSet()
                    if (isChecked) current.add(pkg) else current.remove(pkg)
                    config.setEnabledApps(current)
                }
            }
            container.addView(cb)
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
        val intent = Intent(this, FloatingButtonService::class.java).apply {
            action = FloatingButtonService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "悬浮按钮已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingButton() {
        startService(Intent(this, FloatingButtonService::class.java).apply {
            action = FloatingButtonService.ACTION_STOP
        })
        Toast.makeText(this, "悬浮按钮已停止", Toast.LENGTH_SHORT).show()
    }

    private fun refreshFloatingButton() {
        // 修 Bug7：服务未启动时发 REFRESH 会空跑（Service 端 refreshButtonStyle 因 floatingView==null 直接 return），
        // 用户改了样式却没反应也无提示。这里先判断服务是否运行，未运行则提示先启动。
        if (!isFloatingServiceRunning()) {
            Toast.makeText(this, "请先启动悬浮按钮", Toast.LENGTH_SHORT).show()
            return
        }
        startService(Intent(this, FloatingButtonService::class.java).apply {
            action = FloatingButtonService.ACTION_REFRESH
        })
    }

    /** 检测悬浮按钮服务是否正在运行 */
    private fun isFloatingServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in am.getRunningServices(Int.MAX_VALUE)) {
            if (FloatingButtonService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun buildPermissionStatus(): String {
        val n = if (isNotificationListenerEnabled()) "✓ 已开启" else "✗ 未开启"
        val o = if (canDrawOverlays()) "✓ 已开启" else "✗ 未开启"
        val u = if (hasUsageStatsPermission()) "✓ 已开启" else "✗ 未开启"
        val a = if (isAccessibilityServiceEnabled()) "✓ 已开启" else "✗ 未开启"
        return "通知使用权: $n\n悬浮窗权限: $o\n使用情况访问: $u\n无障碍服务: $a（仅自动跳过需要）"
    }
}
