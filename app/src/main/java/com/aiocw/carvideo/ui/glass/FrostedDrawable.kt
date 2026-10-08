package com.aiocw.carvideo.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 磨砂玻璃面板 Drawable：
 * 圆角裁剪的模糊壁纸底 + 纵向半透明渐变 + 顶部高光 + 细描边（iOS 毛玻璃质感）。
 * 叠色由调用方按昼夜模式注入（见 colors.xml / values-night/colors.xml 的 frost_*）。
 */
class FrostedDrawable(
    private val frost: Bitmap,
    private val radiusPx: Float,
    private val fillTop: Int,
    private val fillBottom: Int,
    private val strokeColor: Int,
    private val highlightColor: Int,
) : Drawable() {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = radiusPx / 16f + 1f
        color = strokeColor
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = radiusPx / 16f + 1f
        color = highlightColor
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        // 上亮下暗的玻璃渐变（随边界重建 shader）
        fillPaint.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            fillTop, fillBottom,
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val rect = RectF(b)
        val path = Path().apply { addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW) }

        val save = canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(frost, null, b, bitmapPaint)   // 模糊壁纸（拉伸即磨砂）
        canvas.drawRect(b, fillPaint)                     // 玻璃渐变
        canvas.restoreToCount(save)

        canvas.drawRoundRect(rect, radiusPx, radiusPx, strokePaint)
        // 顶部高光（上边线内侧亮线）
        canvas.drawLine(
            b.left + radiusPx, b.top + strokePaint.strokeWidth,
            b.right - radiusPx, b.top + strokePaint.strokeWidth,
            highlightPaint
        )
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
