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
        // two soft ribbons of light, so the glass has shapes to bend at its edges (as Prism's aurora does)
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
class GlassSource(val blurred: Bitmap, val dark: Boolean, val sharp: Bitmap? = null, val dimAlpha: Int = 0) {
    var origin: View? = null
    val views = ArrayList<View>()
    fun invalidateAll() { for (v in views) v.invalidate() }
}

/**
 * The liquid glass shader (Android 13+). It treats the pane as a slab of glass with rounded edges: inside, the
 * wallpaper is seen slightly magnified and frosted; towards the rim the surface curves away, so the backdrop is
 * bent inwards (with a touch of colour fringing), and the rim catches light from the top left.
 */
object LiquidGlass {
    const val SKSL = """
uniform shader sharp;
uniform shader frost;
uniform float2 origin;
uniform float2 size;
uniform float radius;
uniform float band;
uniform float bend;
uniform float frostAmt;
uniform float dimAmt;
uniform float isDark;
uniform float px;

float sdBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 xy) {
    float2 hb = size * 0.5;
    float2 c = origin + hb;
    float2 p = xy - c;
    float r = min(radius, min(hb.x, hb.y));
    float d = sdBox(p, hb, r);
    float aa = clamp(0.5 - d, 0.0, 1.0);
    if (aa <= 0.0) { return half4(0.0, 0.0, 0.0, 0.0); }
    float e = 0.75;
    float2 g = float2(sdBox(p + float2(e, 0.0), hb, r) - sdBox(p - float2(e, 0.0), hb, r),
                      sdBox(p + float2(0.0, e), hb, r) - sdBox(p - float2(0.0, e), hb, r));
    float gl = max(length(g), 0.0001);
    float2 n = g / gl;
    float x = clamp(1.0 + d / band, 0.0, 1.0);
    float lens = 1.0 - sqrt(max(1.0 - x * x, 0.0));
    float2 q = c + p * 0.985 - n * (bend * lens);
    float ca = px * 1.4 * lens;
    float3 sh = float3(float(sharp.eval(q + n * ca).r), float(sharp.eval(q).g), float(sharp.eval(q - n * ca).b));
    sh = sh * (1.0 - dimAmt);
    float3 fr = float3(frost.eval(q).rgb);
    float3 col = mix(sh, fr, frostAmt * (1.0 - 0.75 * lens));
    float tAmt = isDark > 0.5 ? 0.16 : 0.24;
    float3 tintc = isDark > 0.5 ? float3(0.0, 0.0, 0.0) : float3(1.0, 1.0, 1.0);
    col = mix(col, tintc, tAmt);
    float sheen = clamp(1.0 - (p.y + hb.y) / (size.y * 0.6), 0.0, 1.0);
    float sheenAmt = isDark > 0.5 ? 0.07 : 0.15;
    col = col + float3(1.0, 1.0, 1.0) * (sheen * sheen * sheenAmt);
    float rim = smoothstep(-2.2 * px, 0.0, d);
    float lit = dot(n, normalize(float2(-0.55, -0.85)));
    float rimAmt = rim * (0.6 * max(lit, 0.0) + 0.2 * max(-lit, 0.0)) * (isDark > 0.5 ? 0.75 : 1.0);
    col = col + float3(1.0, 1.0, 1.0) * (rimAmt + lens * 0.08 * max(lit, 0.0));
    col = clamp(col, 0.0, 1.0);
    return half4(half3(col * aa), half(aa));
}
"""
    @Volatile private var broken = false

    /** A fresh shader, or null where the platform can't run it (older Android, or a driver that rejects it). */
    fun create(): android.graphics.RuntimeShader? {
        if (broken || android.os.Build.VERSION.SDK_INT < 33) return null
        return try {
            android.graphics.RuntimeShader(SKSL)
        } catch (e: Throwable) {
            android.util.Log.w("Facet", "liquid glass shader rejected", e)
            broken = true
            null
        }
    }
}

/**
 * Liquid glass pane. On Android 13+ it runs [LiquidGlass] over the sharp wallpaper (refraction at the edges,
 * frost in the middle); on older versions it falls back to the frosted wallpaper with a sheen and rim. Either
 * way it samples the wallpaper at the pane's own position, so panes stay see-through while the page scrolls.
 */
class GlassDrawable(private val src: GlassSource, private val view: View, private val radius: Float, private val density: Float) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density * 1.2f }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val frostShader = BitmapShader(src.blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val sharpShader = src.sharp?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    private val liquid = if (sharpShader != null) LiquidGlass.create() else null
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val m2 = Matrix()
    private val rect = RectF()
    private val loc = IntArray(2)
    private val oloc = IntArray(2)

    init { src.views.add(view) }

    /** Maps a bitmap that covers the origin view (centre-crop) into this pane's coordinates. */
    private fun place(matrix: Matrix, bmp: Bitmap, o: View, dx: Float, dy: Float) {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val s = Math.max(o.width / bw, o.height / bh)
        matrix.reset()
        matrix.setScale(s, s)
        matrix.postTranslate((o.width - bw * s) / 2f - dx, (o.height - bh * s) / 2f - dy)
    }

    override fun draw(canvas: Canvas) {
        val o = src.origin
        rect.set(bounds)
        if (rect.isEmpty) return
        var dx = 0f
        var dy = 0f
        if (o != null && o.width > 0) {
            view.getLocationInWindow(loc)
            o.getLocationInWindow(oloc)
            dx = (loc[0] - oloc[0]).toFloat()
            dy = (loc[1] - oloc[1]).toFloat()
        }
        val rs = liquid
        if (rs != null && o != null && o.width > 0 && android.os.Build.VERSION.SDK_INT >= 33) {
            place(m, src.blurred, o, dx, dy)
            frostShader.setLocalMatrix(m)
            place(m2, src.sharp!!, o, dx, dy)
            sharpShader!!.setLocalMatrix(m2)
            try {
                rs.setInputShader("sharp", sharpShader)
                rs.setInputShader("frost", frostShader)
                rs.setFloatUniform("origin", rect.left, rect.top)
                rs.setFloatUniform("size", rect.width(), rect.height())
                rs.setFloatUniform("radius", radius)
                rs.setFloatUniform("band", Math.min(density * 20f, Math.min(rect.width(), rect.height()) * 0.4f))
                rs.setFloatUniform("bend", density * 11f)
                rs.setFloatUniform("frostAmt", 0.72f)
                rs.setFloatUniform("dimAmt", src.dimAlpha / 255f)
                rs.setFloatUniform("isDark", if (src.dark) 1f else 0f)
                rs.setFloatUniform("px", density)
                glassPaint.shader = rs
                canvas.drawRect(rect, glassPaint)
                return
            } catch (e: Throwable) {
                android.util.Log.w("Facet", "liquid glass draw", e)
            }
        }
        if (o != null && o.width > 0) {
            place(m, src.blurred, o, dx, dy)
            frostShader.setLocalMatrix(m)
            fill.shader = frostShader
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        tint.color = if (src.dark) 0x29000000 else 0x55FFFFFF
        canvas.drawRoundRect(rect, radius, radius, tint)
        edge.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.65f,
            if (src.dark) 0x38FFFFFF else 0x99FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, radius, radius, edge)
        // a bright rim that fades across the pane, plus a faint inner rim, as in Prism's glass panels
        rim.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(if (src.dark) 0xB3FFFFFF.toInt() else 0xFFFFFFFF.toInt(), if (src.dark) 0x1FFFFFFF else 0x33FFFFFF,
                if (src.dark) 0x66FFFFFF else 0xB3FFFFFF.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val half = rim.strokeWidth / 2f
        canvas.drawRoundRect(RectF(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half), radius, radius, rim)
        inner.color = if (src.dark) 0x14FFFFFF else 0x22000000
        val i = density * 1.6f
        canvas.drawRoundRect(RectF(rect.left + i, rect.top + i, rect.right - i, rect.bottom - i), radius - i, radius - i, inner)
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
