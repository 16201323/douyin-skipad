package com.example.skipad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.util.Log
import android.view.View
import java.io.File

/**
 * 悬浮窗自定义 View
 *
 * 负责绘制悬浮窗内容，支持两种模式（由 ConfigManager.contentMode 决定）：
 * 1. 文字模式（默认）：纯色形状背景 + 居中文字
 * 2. 图片模式：按形状裁剪显示图片（CENTER_CROP 居中裁剪填满）+ 可选叠加文字
 *
 * 形状跟随 ConfigManager.buttonShape（圆形 / 圆角矩形 / 方形）。
 * 每次 onDraw 都读取最新配置，外部只需调用 updateConfig() 触发重绘即可刷新样式。
 * 触摸事件（点击/拖动/长按）由 FloatingButtonService 通过 setOnTouchListener 设置，本 View 不处理。
 */
class FloatingView(context: Context) : View(context) {

    private val tag = "SkipAd_FloatingView"
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val collapsedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val drawingBounds = RectF()
    private var bitmapShader: BitmapShader? = null
    private var shaderBitmap: Bitmap? = null
    private var shaderWidth = -1f
    private var shaderHeight = -1f
    private var pathShape = -1
    private var pathWidth = -1f
    private var pathHeight = -1f
    private var shapePath: Path? = null

    // 拖动绘制只读缓存。图片覆盖同一路径时，配置刷新负责检查修改时间并使缓存失效。
    private var cachedBitmap: Bitmap? = null
    private var cachedPath: String? = null
    private var cachedLastModified = 0L

    // 收起态标记：true 时绘制半透明圆角小长方形（贴边隐藏用），不绘制文字/图片
    private var collapsed = false

    /**
     * 通知配置已变更，触发重绘（外部刷新样式时调用）
     */
    fun updateConfig() {
        val path = ConfigManager.get(context).imagePath
        if (path != cachedPath || (path != null && File(path).lastModified() != cachedLastModified)) {
            cachedPath = null
            cachedBitmap = null
            bitmapShader = null
            shaderBitmap = null
        }
        invalidate()
    }

    /**
     * 设置收起态：true 画小长方形，false 恢复正常按钮样式
     */
    fun setCollapsed(collapsed: Boolean) {
        this.collapsed = collapsed
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val config = ConfigManager.get(context)
        val shape = config.buttonShape
        val density = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        drawingBounds.set(0f, 0f, w, h)

        // 收起态：直接画半透明圆角小长方形，不绘制文字/图片（贴边隐藏用）
        if (collapsed) {
            val paint = collapsedPaint.apply {
                color = config.buttonColor
                alpha = 153   // 0.6 透明度，提示这是收起状态
            }
            canvas.drawRoundRect(drawingBounds, 3f * density, 3f * density, paint)
            return
        }

        // 1. 绘制底层背景：图片模式用 BitmapShader，否则纯色
        val bgPaint = backgroundPaint
        bgPaint.shader = null
        if (config.contentMode == ConfigManager.CONTENT_IMAGE && !config.imagePath.isNullOrEmpty()) {
            val bmp = loadBitmap(config.imagePath!!)
            if (bmp != null) {
                if (shaderBitmap !== bmp || bitmapShader == null) {
                    shaderBitmap = bmp
                    bitmapShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    shaderWidth = -1f
                }
                val shader = bitmapShader!!
                if (shaderWidth != w || shaderHeight != h) {
                    shader.setLocalMatrix(buildCenterCropMatrix(bmp, w, h))
                    shaderWidth = w
                    shaderHeight = h
                }
                bgPaint.shader = shader
                bgPaint.alpha = config.buttonAlpha
            } else {
                // 图片加载失败：回退为纯色背景，避免悬浮窗空白
                bgPaint.color = config.buttonColor
                bgPaint.alpha = config.buttonAlpha
            }
        } else {
            bgPaint.color = config.buttonColor
            bgPaint.alpha = config.buttonAlpha
        }
        drawShape(canvas, bgPaint, shape, w, h, density)

        // 2. 绘制上层文字：文字非空时叠加在背景/图片之上（文字清空即纯图片）
        val text = config.buttonText
        if (text.isNotEmpty()) {
            textPaint.apply {
                color = Color.BLACK
                alpha = config.buttonAlpha
                // config.textSize 单位为 sp，Canvas 的 textSize 单位为 px，乘以 density 转换
                textSize = config.textSize * density
                textAlign = Paint.Align.CENTER
            }
            val baseline = h / 2 - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(text, w / 2, baseline, textPaint)
        }
    }

    /**
     * 按当前形状绘制背景区域。
     * 纯色模式下 paint 为实色；图片模式下 paint 携带 BitmapShader，
     * 因此这里画什么轮廓，图片就被裁剪成什么形状——新增形状对两种模式自动生效。
     * 爱心/五角星/云朵/三角形的 Path 由 ShapePainter 统一构建（与设置页预览共用，保证一致）。
     */
    private fun drawShape(canvas: Canvas, paint: Paint, shape: Int, w: Float, h: Float, density: Float) {
        when (shape) {
            ConfigManager.SHAPE_CIRCLE -> {
                val radius = minOf(w, h) / 2
                canvas.drawCircle(w / 2, h / 2, radius, paint)
            }
            ConfigManager.SHAPE_ROUND_RECT -> {
                val rect = drawingBounds
                val radius = 16 * density
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            else -> {
                // 爱心/五角星/云朵/三角形：用 ShapePainter 构建 Path 绘制；
                // SHAPE_SQUARE（buildPath 返回 null）：方形无圆角
                if (pathShape != shape || pathWidth != w || pathHeight != h) {
                    shapePath = ShapePainter.buildPath(shape, w, h)
                    pathShape = shape
                    pathWidth = w
                    pathHeight = h
                }
                val path = shapePath
                if (path != null) {
                    canvas.drawPath(path, paint)
                } else {
                    canvas.drawRect(0f, 0f, w, h, paint)
                }
            }
        }
    }

    /**
     * 计算 CENTER_CROP 变换矩阵：
     * 等比缩放使图片填满 View（取较大缩放比），居中平移，超出部分被形状裁掉
     */
    private fun buildCenterCropMatrix(bitmap: Bitmap, viewW: Float, viewH: Float): Matrix {
        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()
        val scale = maxOf(viewW / bmpW, viewH / bmpH)
        val dx = (viewW - bmpW * scale) / 2f
        val dy = (viewH - bmpH * scale) / 2f
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
    }

    /**
     * 加载图片（带缓存 + 降采样，防止大图 OOM）
     * 目标尺寸为 View 当前大小，先读边界计算 inSampleSize 降采样，再解码
     */
    private fun loadBitmap(path: String): Bitmap? {
        if (path == cachedPath) return cachedBitmap
        val file = File(path)
        val lastModified = file.lastModified()
        try {
            val targetW = width.coerceAtLeast(1)
            val targetH = height.coerceAtLeast(1)
            // 第一遍只解码边界，不加载像素
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            // 计算降采样比例：使解码后的图不超过目标尺寸的 2 倍（留余量给 CENTER_CROP 缩放）
            var sample = 1
            while (bounds.outWidth / sample > targetW * 2 || bounds.outHeight / sample > targetH * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(path, opts)
            cachedBitmap = bmp
            cachedPath = path
            cachedLastModified = lastModified
            return bmp
        } catch (e: Exception) {
            Log.e(tag, "加载悬浮窗图片失败: ${e.message}")
            cachedBitmap = null
            cachedPath = null
            cachedLastModified = 0L
            return null
        }
    }
}
