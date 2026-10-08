package com.aiocw.carvideo.ui.glass

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.View
import androidx.core.view.doOnLayout

/**
 * 毛玻璃（Glassmorphism）基础设施：
 *
 * 1. [install] 生成 iOS 风格彩色壁纸（线性渐变 + 柔光色斑），设为窗口背景；
 * 2. [frost] 在面板完成布局后，按面板在屏幕上的实际位置裁剪壁纸 → 降采样模糊 →
 *    作为该面板的磨砂底，再叠加半透明渐变/高光/描边（[FrostedDrawable]）。
 *
 * 目标机 Android 11 无 RenderEffect（API 31+），此处用"低分辨率放大"实现快速磨砂，
 * 效果接近 iOS 毛玻璃且零额外依赖。
 */
object GlassBackground {

    private var wallpaper: Bitmap? = null

    /** 安装壁纸 + 半透明系统栏。setContentView 之后调用。 */
    fun install(activity: Activity) {
        val bmp = wallpaper(activity)
        activity.window.setBackgroundDrawable(BitmapDrawable(activity.resources, bmp))
        activity.window.statusBarColor = Color.parseColor("#66000000")
        activity.window.navigationBarColor = Color.parseColor("#66000000")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 深色壁纸 → 浅色状态栏图标
            activity.window.decorView.systemUiVisibility = 0
        }
    }

    /** 给单个面板施加毛玻璃效果（面板需在布局后调用，内部自动等待布局）。 */
    fun frost(activity: Activity, view: View, radiusDp: Float = 22f) {
        val bmp = wallpaper(activity)
        val density = activity.resources.displayMetrics.density
        val radiusPx = radiusDp * density
        view.doOnLayout { v ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val w = v.width
            val h = v.height
            if (w <= 0 || h <= 0) return@doOnLayout
            // 裁剪面板背后的壁纸区域（越界钳制）
            val x = loc[0].coerceIn(0, (bmp.width - 1).coerceAtLeast(0))
            val y = loc[1].coerceIn(0, (bmp.height - 1).coerceAtLeast(0))
            val cw = w.coerceAtMost(bmp.width - x)
            val ch = h.coerceAtMost(bmp.height - y)
            val crop = Bitmap.createBitmap(bmp, x, y, cw.coerceAtLeast(1), ch.coerceAtLeast(1))
            // 降采样后再放大 = 磨砂模糊（绘制时由 FrostedDrawable 拉伸回原尺寸）
            val frost = Bitmap.createScaledBitmap(
                crop,
                (cw / 10).coerceAtLeast(1),
                (ch / 10).coerceAtLeast(1),
                true
            )
            v.background = FrostedDrawable(frost, radiusPx)
        }
    }

    /** 生成（并缓存）壁纸位图，尺寸与屏幕一致。 */
    fun wallpaper(activity: Activity): Bitmap {
        wallpaper?.let { return it }
        val dm = activity.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(720)
        val h = dm.heightPixels.coerceAtLeast(1280)
        val bmp = generate(w, h)
        wallpaper = bmp
        return bmp
    }

    /** 低分辨率绘制渐变 + 柔光色斑，再双线性放大 → 天然柔和。 */
    private fun generate(w: Int, h: Int): Bitmap {
        val sw = 256
        val sh = (sw.toFloat() * h / w).toInt().coerceAtLeast(1)
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(small)

        // 底色：深空蓝纵向渐变
        canvas.drawRect(
            0f, 0f, sw.toFloat(), sh.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, sh.toFloat(),
                    intArrayOf(
                        Color.parseColor("#0A0E1F"),
                        Color.parseColor("#111B3E"),
                        Color.parseColor("#0B1226"),
                        Color.parseColor("#0A0E1F"),
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
        )

        // 柔光色斑（iOS 壁纸质感）
        blob(canvas, sw * 0.18f, sh * 0.12f, sw * 0.85f, "#4B3FD1", 0x88)
        blob(canvas, sw * 0.92f, sh * 0.30f, sw * 0.80f, "#1D7FA8", 0x77)
        blob(canvas, sw * 0.12f, sh * 0.72f, sw * 0.90f, "#7A3BD8", 0x66)
        blob(canvas, sw * 0.85f, sh * 0.92f, sw * 0.85f, "#2B5CC7", 0x77)
        blob(canvas, sw * 0.50f, sh * 0.52f, sw * 0.70f, "#16244F", 0x99)

        return Bitmap.createScaledBitmap(small, w, h, true)
    }

    private fun blob(canvas: Canvas, cx: Float, cy: Float, r: Float, color: String, alpha: Int) {
        val c = Color.parseColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, r,
                Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c)),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, r, paint)
    }
}
