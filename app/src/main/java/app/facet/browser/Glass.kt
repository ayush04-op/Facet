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
class SquircleDrawable(private val fill: Int, private val stroke: Int, private val strokeWidth: Float, private val n: Float = 5f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onBoundsChange(b: android.graphics.Rect) {
        path.reset()
        val a = b.width() / 2f
        val h = b.height() / 2f
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val steps = 96
        for (i in 0..steps) {
            val t = (2.0 * Math.PI * i / steps)
            val c = Math.cos(t)
            val s = Math.sin(t)
            val x = cx + a * Math.signum(c).toFloat() * Math.pow(Math.abs(c), 2.0 / n).toFloat()
            val y = cy + h * Math.signum(s).toFloat() * Math.pow(Math.abs(s), 2.0 / n).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
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

    /** Small, heavily softened copy: frosted-glass cards sample from this one. */
    fun blurred(src: Bitmap, strength: Int): Bitmap {
        val targetW = when (strength) { 0 -> 200; 2 -> 70; else -> 120 }
        val sw = targetW
        val sh = (src.height.toFloat() * sw / src.width).toInt().coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true).copy(Bitmap.Config.ARGB_8888, true)
        val radius = when (strength) { 0 -> 3; 2 -> 8; else -> 5 }
        repeat(3) { boxBlur(small, radius) }
        return small
    }

    private fun boxBlur(b: Bitmap, r: Int) {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        val div = 2 * r + 1
        for (y in 0 until h) {
            var ra = 0; var ga = 0; var ba = 0
            for (i in -r..r) {
                val c = px[y * w + i.coerceIn(0, w - 1)]
                ra += (c shr 16) and 0xff; ga += (c shr 8) and 0xff; ba += c and 0xff
            }
            for (x in 0 until w) {
                tmp[y * w + x] = (0xff shl 24) or ((ra / div) shl 16) or ((ga / div) shl 8) or (ba / div)
                val out = px[y * w + (x - r).coerceIn(0, w - 1)]
                val inn = px[y * w + (x + r + 1).coerceIn(0, w - 1)]
                ra += ((inn shr 16) and 0xff) - ((out shr 16) and 0xff)
                ga += ((inn shr 8) and 0xff) - ((out shr 8) and 0xff)
                ba += (inn and 0xff) - (out and 0xff)
            }
        }
        for (x in 0 until w) {
            var ra = 0; var ga = 0; var ba = 0
            for (i in -r..r) {
                val c = tmp[i.coerceIn(0, h - 1) * w + x]
                ra += (c shr 16) and 0xff; ga += (c shr 8) and 0xff; ba += c and 0xff
            }
            for (y in 0 until h) {
                px[y * w + x] = (0xff shl 24) or ((ra / div) shl 16) or ((ga / div) shl 8) or (ba / div)
                val out = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                val inn = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                ra += ((inn shr 16) and 0xff) - ((out shr 16) and 0xff)
                ga += ((inn shr 8) and 0xff) - ((out shr 8) and 0xff)
                ba += (inn and 0xff) - (out and 0xff)
            }
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }
}

/** Where the glass samples from: the blurred wallpaper and the layer it is laid out against. */
class GlassSource(val blurred: Bitmap, val dark: Boolean) {
    var origin: View? = null
    val views = ArrayList<View>()
    fun invalidateAll() { for (v in views) v.invalidate() }
}

/**
 * Frosted "liquid glass": the blurred wallpaper seen through a rounded pane, a light tint, a bright top edge
 * and a thin rim. Because it samples the wallpaper at the pane's own position, panes stay see-through while
 * the page scrolls.
 */
class GlassDrawable(private val src: GlassSource, private val view: View, private val radius: Float, private val density: Float) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val shader = BitmapShader(src.blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val m = Matrix()
    private val rect = RectF()
    private val loc = IntArray(2)
    private val oloc = IntArray(2)

    init { src.views.add(view) }

    override fun draw(canvas: Canvas) {
        val o = src.origin
        rect.set(bounds)
        if (o != null && o.width > 0) {
            view.getLocationInWindow(loc)
            o.getLocationInWindow(oloc)
            val dx = (loc[0] - oloc[0]).toFloat()
            val dy = (loc[1] - oloc[1]).toFloat()
            val bw = src.blurred.width.toFloat()
            val bh = src.blurred.height.toFloat()
            val s = Math.max(o.width / bw, o.height / bh)
            m.reset()
            m.setScale(s, s)
            m.postTranslate((o.width - bw * s) / 2f - dx, (o.height - bh * s) / 2f - dy)
            shader.setLocalMatrix(m)
            fill.shader = shader
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        tint.color = if (src.dark) 0x33000000 else 0x4DFFFFFF
        canvas.drawRoundRect(rect, radius, radius, tint)
        // bright top edge, the "liquid" highlight
        edge.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.55f,
            if (src.dark) 0x30FFFFFF else 0x99FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, radius, radius, edge)
        rim.color = if (src.dark) 0x40FFFFFF else 0xCCFFFFFF.toInt()
        val half = rim.strokeWidth / 2f
        canvas.drawRoundRect(RectF(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half), radius, radius, rim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
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
