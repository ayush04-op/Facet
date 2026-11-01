package app.facet.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import java.io.File

// ---------------------------------------------------------------- motion

/**
 * A damped spring as an interpolator: runs from 0 to 1, overshoots and settles. This is the "jelly" motion
 * Android 17 uses for pop-up menus. [bounce] 0.0 is critically damped (no overshoot), around 0.25 is lively.
 */
class SpringInterpolator(private val bounce: Float = 0.3f, private val response: Float = 0.45f) : android.view.animation.Interpolator {
    private val damping = (1f - bounce).coerceIn(0.05f, 1f)
    private val omega = (2.0 * Math.PI / response).toFloat()

    override fun getInterpolation(t: Float): Float {
        if (t >= 1f) return 1f
        val time = t * response * 2.2f
        return if (damping >= 1f) {
            (1.0 - (1.0 + omega * time) * Math.exp((-omega * time).toDouble())).toFloat()
        } else {
            val wd = omega * Math.sqrt((1.0 - damping * damping).toDouble()).toFloat()
            val decay = Math.exp((-damping * omega * time).toDouble())
            (1.0 - decay * (Math.cos((wd * time).toDouble()) + (damping * omega / wd) * Math.sin((wd * time).toDouble()))).toFloat()
        }
    }
}

// ---------------------------------------------------------------- squircle

/** A smooth superellipse (the continuous-corner "squircle" Android 17 pop-ups use), filled and outlined. */
class SquircleDrawable(private val fill: Int, private val stroke: Int, private val strokeWidth: Float, private val radius: Float, private val n: Float = 3.4f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onBoundsChange(b: android.graphics.Rect) {
        path.reset()
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val r = Math.min(radius, Math.min(w, h) / 2f)
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val steps = 20
        // each corner is a quarter of a superellipse, so the curve eases into the straight edges
        fun corner(cx: Float, cy: Float, which: Int) {
            for (i in 0..steps) {
                val a = (Math.PI / 2.0) * i / steps
                val u = r * Math.pow(Math.cos(a), 2.0 / n).toFloat()
                val v = r * Math.pow(Math.sin(a), 2.0 / n).toFloat()
                when (which) {
                    0 -> path.lineTo(cx + v, cy - u)   // top right: from the top edge round to the right edge
                    1 -> path.lineTo(cx + u, cy + v)   // bottom right
                    2 -> path.lineTo(cx - v, cy + u)   // bottom left
                    else -> path.lineTo(cx - u, cy - v) // top left
                }
            }
        }
        path.moveTo(l + r, t)
        corner(l + w - r, t + r, 0)
        corner(l + w - r, t + h - r, 1)
        corner(l + r, t + h - r, 2)
        corner(l + r, t + r, 3)
        path.close()
    }

    override fun draw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawPath(path, paint)
        if (strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = stroke
            canvas.drawPath(path, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// ---------------------------------------------------------------- wallpaper

/** Generated wallpapers, so Facet ships no images. */
object Wallpapers {
    val names = listOf("Paper glow", "Dusk", "Ocean", "Forest", "Graphite")

    fun preset(id: Int, dark: Boolean, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // base colour, then three soft blobs
        val (base, blobs) = when (id) {
            1 -> if (dark) Pair(0xFF16101F.toInt(), intArrayOf(0xFF7A3FBF.toInt(), 0xFFE0703C.toInt(), 0xFF3A2A7A.toInt()))
                 else Pair(0xFFF6EEF8.toInt(), intArrayOf(0xFFC8A2E8.toInt(), 0xFFF2B27A.toInt(), 0xFFB7A8E8.toInt()))
            2 -> if (dark) Pair(0xFF0B1620.toInt(), intArrayOf(0xFF1F7A8C.toInt(), 0xFF2B5BA8.toInt(), 0xFF10424F.toInt()))
                 else Pair(0xFFEAF4F6.toInt(), intArrayOf(0xFF8CCBD6.toInt(), 0xFF8FB1E8.toInt(), 0xFFBFE3E0.toInt()))
            3 -> if (dark) Pair(0xFF0E1710.toInt(), intArrayOf(0xFF2F7D4A.toInt(), 0xFF8A9A2F.toInt(), 0xFF1B4D32.toInt()))
                 else Pair(0xFFEFF5E8.toInt(), intArrayOf(0xFFA6D3A0.toInt(), 0xFFD8DE9A.toInt(), 0xFF9CCFB5.toInt()))
            4 -> if (dark) Pair(0xFF121212.toInt(), intArrayOf(0xFF4A4A4A.toInt(), 0xFF2A2A2A.toInt(), 0xFF5A5A5A.toInt()))
                 else Pair(0xFFF1F1EF.toInt(), intArrayOf(0xFFCFCFCB.toInt(), 0xFFE2E2DE.toInt(), 0xFFB9B9B5.toInt()))
            else -> if (dark) Pair(0xFF15110E.toInt(), intArrayOf(0xFFB5532F.toInt(), 0xFFB8862F.toInt(), 0xFF5E7C8C.toInt()))
                    else Pair(0xFFFBF6EA.toInt(), intArrayOf(0xFFE9A07A.toInt(), 0xFFF0CB8A.toInt(), 0xFFA9C7D2.toInt()))
        }
        cv.drawColor(base)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val spots = arrayOf(floatArrayOf(0.2f, 0.18f, 0.95f), floatArrayOf(0.88f, 0.5f, 0.8f), floatArrayOf(0.3f, 0.88f, 0.9f))
        for (i in 0..2) {
            val r = w * spots[i][2]
            p.shader = RadialGradient(w * spots[i][0], h * spots[i][1], r, withAlpha(blobs[i], if (dark) 0xB0 else 0xA0), withAlpha(blobs[i], 0), Shader.TileMode.CLAMP)
            cv.drawCircle(w * spots[i][0], h * spots[i][1], r, p)
        }
        // two soft ribbons of light across the gradient
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.maskFilter = android.graphics.BlurMaskFilter(w * 0.05f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        val ribbons = arrayOf(floatArrayOf(-0.1f, 0.34f, 0.5f, 0.12f, 1.1f, 0.42f, 0.16f), floatArrayOf(-0.1f, 0.78f, 0.45f, 0.6f, 1.1f, 0.7f, 0.11f))
        for ((i, rb) in ribbons.withIndex()) {
            val path = Path()
            path.moveTo(w * rb[0], h * rb[1])
            path.quadTo(w * rb[2], h * rb[3], w * rb[4], h * rb[5])
            p.strokeWidth = w * rb[6]
            p.color = withAlpha(blobs[i], if (dark) 0x8C else 0x99)
            cv.drawPath(path, p)
            p.strokeWidth = w * rb[6] * 0.25f
            p.color = withAlpha(0xFFFFFF, if (dark) 0x26 else 0x66)
            cv.drawPath(path, p)
        }
        return bmp
    }

    /** Copies a picked photo into private storage at a sensible size. */
    fun saveCustom(ctx: Context, uri: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outHeight / sample > 2400 || bounds.outWidth / sample > 2400) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
            File(ctx.filesDir, "wallpaper.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun custom(ctx: Context): Bitmap? {
        val f = File(ctx.filesDir, "wallpaper.jpg")
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Exception) { null }
    }
}

/** The wallpaper layer: sharp image, optional dim, always laid out at full screen height so it never rescales. */
class WallpaperView(ctx: Context, private val sharp: Bitmap, private val dimAlpha: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val m = Matrix()

    override fun onDraw(canvas: Canvas) {
        val bw = sharp.width.toFloat()
        val bh = sharp.height.toFloat()
        val s = Math.max(width / bw, height / bh)
        m.setScale(s, s)
        m.postTranslate((width - bw * s) / 2f, (height - bh * s) / 2f)
        canvas.drawBitmap(sharp, m, paint)
        if (dimAlpha > 0) canvas.drawColor(withAlpha(0x000000, dimAlpha))
    }
}
