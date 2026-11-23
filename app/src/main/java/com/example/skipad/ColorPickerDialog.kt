package com.example.skipad

import androidx.appcompat.app.AppCompatDialog
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 颜色选择对话框（HSV 色盘）
 *
 * 包含三个滑块：
 * 1. 色相 Hue (0-360)
 * 2. 饱和度 Saturation (0-100)
 * 3. 明度 Value (0-100)
 * 底部实时预览当前颜色
 */
class ColorPickerDialog(
    context: Context,
    private val initialColor: Int,
    private val onColorSelected: (Int) -> Unit
) : AppCompatDialog(context) {

    private val ui = AppUi(context)

    // 当前 HSV 值
    private var hue = 0f
    private var saturation = 1f
    private var value = 1f

    private lateinit var previewView: View
    private lateinit var hueBar: View
    private lateinit var satBar: View
    private lateinit var valBar: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 从初始颜色解析 HSV
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24))
            background = ui.rounded(ui.surface, 24)
        }

        layout.addView(ui.label("自定义颜色", 21f, medium = true))
        layout.addView(ui.space(8))
        layout.addView(ui.label("调整下方滑块，找到喜欢的颜色。", 12f, ui.secondary))
        layout.addView(ui.space(20))
        // 预览色块
        previewView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ui.dp(72)
            ).apply { bottomMargin = ui.dp(16) }
            background = ui.rounded(initialColor, 16)
        }
        layout.addView(previewView)

        // 色相滑块
        layout.addView(makeLabel("色相"))
        hueBar = createHueBar()
        layout.addView(hueBar)
        layout.addView(createHueSeekBar())

        // 饱和度滑块
        layout.addView(makeLabel("饱和度"))
        satBar = createSatBar()
        layout.addView(satBar)
        layout.addView(createSatSeekBar())

        // 明度滑块
        layout.addView(makeLabel("明度"))
        valBar = createValBar()
        layout.addView(valBar)
        layout.addView(createValSeekBar())

        // 确认按钮
        val btn = ui.button("应用颜色", true) {
            onColorSelected(currentColor())
            dismiss()
        }
        layout.addView(ui.space(16))
        layout.addView(btn, LinearLayout.LayoutParams(-1, -2))

        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        setContentView(layout)
        updateBars()
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout((context.resources.displayMetrics.widthPixels * 0.90f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun makeLabel(text: String) = ui.label(text, 12f, ui.secondary).apply {
        setPadding(0, ui.dp(12), 0, ui.dp(6))
    }

    /** 当前颜色（带原 alpha） */
    private fun currentColor(): Int {
        val alpha = Color.alpha(initialColor)
        val rgb = Color.HSVToColor(floatArrayOf(hue, saturation, value))
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    }

    private fun updatePreview() {
        previewView.background = ui.rounded(currentColor(), 16)
        updateBars()
    }

    /** 色相条（彩虹渐变） */
    private fun createHueBar(): View {
        val colors = IntArray(360) { i ->
            Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f))
        }
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(6)
            ).apply { bottomMargin = 8 }
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors)
        }
    }

    private fun createHueSeekBar(): SeekBar {
        return SeekBar(context).apply {
            minimumHeight = ui.dp(48)
            thumbTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            progressTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            max = 360
            progress = hue.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { hue = progress.toFloat(); updatePreview() }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }

    /** 饱和度条（灰到当前色相饱和色） */
    private fun createSatBar(): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(6)
            ).apply { bottomMargin = 8 }
        }
    }

    private fun createSatSeekBar(): SeekBar {
        return SeekBar(context).apply {
            minimumHeight = ui.dp(48)
            thumbTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            progressTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            max = 100
            progress = (saturation * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { saturation = progress / 100f; updatePreview() }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }

    /** 明度条（黑到当前色相） */
    private fun createValBar(): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(6)
            ).apply { bottomMargin = 8 }
        }
    }

    private fun createValSeekBar(): SeekBar {
        return SeekBar(context).apply {
            minimumHeight = ui.dp(48)
            thumbTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            progressTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            max = 100
            progress = (value * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { value = progress / 100f; updatePreview() }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }

    /** 更新饱和度和明度条的渐变色 */
    private fun updateBars() {
        // 饱和度条：左端 = 当前明度下的灰色（hue,0,value），右端 = 当前明度下的满饱和色（hue,1,value）
        // 右端必须带当前 value，旧代码用满明度 pureColor(hue,1,1)，当 value<1 时色条右端与预览色块不一致
        val satColors = intArrayOf(
            Color.HSVToColor(floatArrayOf(hue, 0f, value)),
            Color.HSVToColor(floatArrayOf(hue, 1f, value))
        )
        satBar.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, satColors)
        // 明度条：左端 = 黑（value=0），右端 = 当前饱和度下满明度（hue,saturation,1），滑块位置对应当前 value
        val valColors = intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hue, saturation, 1f)))
        valBar.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, valColors)
    }
}
