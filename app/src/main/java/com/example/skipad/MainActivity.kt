package com.example.skipad

import android.content.Intent
import android.graphics.Color
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 主 Activity：权限引导 + 悬浮窗设置 + 控制
 *
 * 功能分区：
 * 1. 权限状态提示（通知使用权、悬浮窗权限）
 * 2. 悬浮窗样式设置（颜色、透明度、形状、大小、文字）
 * 3. 支持的 App 选择
 * 4. 启动/停止悬浮按钮
 * 5. 信号采集（用于自动跳过研究）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var config: ConfigManager
    private var waitingForNotificationPermission = false
    private var waitingForOverlayPermission = false
    private var waitingForAccessibilityPermission = false

    // 自动跳过复选框（成员变量，便于从设置返回后刷新状态）
    private var autoSkipCheckBox: CheckBox? = null
    // 自动跳过复选框的监听器（成员变量，便于刷新时重新设置）
    private var autoSkipListener: android.widget.CompoundButton.OnCheckedChangeListener? = null
    // 标记：用户想开启自动跳过，但无障碍服务未开，等待用户开启后自动勾选
    private var pendingAutoSkip = false

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
        buildUI()
    }

    override fun onResume() {
        super.onResume()
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
     * 权限检测与流程推进（通知使用权 → 悬浮窗）
     * 注意：无障碍服务不在这里强制引导，只有用户开启「自动跳过」时才检查
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
            else -> {
                waitingForNotificationPermission = false
                waitingForOverlayPermission = false
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
     * 检测无障碍服务是否已启用
     * 通过 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 检查
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(packageName) == true
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

        // === 权限状态 ===
        layout.addView(makeSectionTitle("权限状态"))
        val permStatus = TextView(this).apply {
            text = buildPermissionStatus()
            textSize = 13f
            setPadding(0, 8, 0, 16)
        }
        layout.addView(permStatus)

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

        // 文字
        layout.addView(makeLabel("按钮文字"))
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

        // === 控制按钮 ===
        layout.addView(makeSectionTitle("控制"))
        layout.addView(makeButton("启动悬浮按钮") { startFloatingButton() })
        layout.addView(makeButton("停止悬浮按钮") { stopFloatingButton() })

        // === 工具区 ===
        layout.addView(makeSectionTitle("数据采集（自动跳过研究）"))
        val dataHint = TextView(this).apply {
            text = "正在自动记录你跳过广告时的 MediaSession 特征数据，用于分析广告与正片的差异。\n数据文件路径：${AdDataLogger.getLogFile(this@MainActivity).absolutePath}"
            textSize = 12f
            setPadding(0, 8, 0, 8)
            setTextColor(0xFF666666.toInt())
        }
        layout.addView(dataHint)
        layout.addView(makeButton("查看采集的数据") {
            val logs = AdDataLogger.readAllLogs(this@MainActivity)
            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("采集的数据")
                .setMessage(logs.take(5000) + if (logs.length > 5000) "\n...(截断，完整数据在文件中)" else "")
                .setPositiveButton("确定", null)
                .create()
            dialog.show()
        })
        layout.addView(makeButton("清空采集数据") {
            AdDataLogger.getLogFile(this).delete()
            Toast.makeText(this, "已清空采集数据", Toast.LENGTH_SHORT).show()
        })
        layout.addView(makeButton("采集 MediaSession 信号") { dumpSignal() })
        layout.addView(makeButton("Dump 无障碍节点（调试）") {
            val info = SkipAdAccessibilityService.dumpAllNodes()
            // 保存到文件
            val file = java.io.File(getExternalFilesDir(null), "a11y_nodes.txt")
            file.writeText(info)
            android.app.AlertDialog.Builder(this)
                .setTitle("无障碍节点信息")
                .setMessage(info.take(3000) + if (info.length > 3000) "\n...(已截断，完整数据保存在 ${file.absolutePath})" else "")
                .setPositiveButton("确定", null)
                .show()
        })

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
        val container = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val shapes = listOf("圆形" to ConfigManager.SHAPE_CIRCLE, "圆角矩形" to ConfigManager.SHAPE_ROUND_RECT, "方形" to ConfigManager.SHAPE_SQUARE)
        for ((name, value) in shapes) {
            val btn = Button(this).apply {
                text = name
                textSize = 12f
                setOnClickListener {
                    config.buttonShape = value
                    refreshFloatingButton()
                }
            }
            container.addView(btn)
        }
        return container
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
                config.buttonText = input.text.toString().ifEmpty { "跳过" }
                refreshFloatingButton()
                Toast.makeText(this@MainActivity, "文字已更新", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(input)
        container.addView(btn)
        return container
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
        startService(Intent(this, FloatingButtonService::class.java).apply {
            action = FloatingButtonService.ACTION_REFRESH
        })
    }

    /**
     * 采集 MediaSession 信号并显示（用于研究自动跳过）
     */
    private fun dumpSignal() {
        val info = MediaControlService.dumpMediaSessionInfo(this)
        AlertDialog.Builder(this)
            .setTitle("MediaSession 信号采集")
            .setMessage(info)
            .setPositiveButton("复制到剪贴板") { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("signal", info))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun buildPermissionStatus(): String {
        val n = if (isNotificationListenerEnabled()) "✓ 已开启" else "✗ 未开启"
        val o = if (canDrawOverlays()) "✓ 已开启" else "✗ 未开启"
        val a = if (isAccessibilityServiceEnabled()) "✓ 已开启" else "✗ 未开启"
        return "通知使用权: $n\n悬浮窗权限: $o\n无障碍服务: $a"
    }
}
