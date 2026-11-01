package app.facet.browser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The logo, animated: the four pieces (sage arc, terracotta arc, sand swirl, pen tip) swing in from outside and
 * settle with a spring, then a band of light sweeps across now and then and the whole mark floats gently.
 */
class LogoView(context: Context, private val withGlow: Boolean = true) : View(context) {
    private class Piece(val bmp: Bitmap, val dx: Float, val dy: Float, val rot: Float, val from: Float, val scale: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spring = SpringInterpolator(0.28f, 0.55f)
    private fun load(id: Int): Bitmap = BitmapFactory.decodeResource(resources, id)
    // dx/dy are the offsets (in logo widths) the piece starts from, rot the angle it starts at, from the delay
    private val pieces = listOf(
        Piece(load(R.drawable.logo_teal), -0.55f, -0.25f, -70f, 0.00f, 0.7f),
        Piece(load(R.drawable.logo_red), 0.55f, -0.30f, 70f, 0.10f, 0.7f),
        Piece(load(R.drawable.logo_swirl), 0.0f, 0.55f, 40f, 0.22f, 0.8f),
        Piece(load(R.drawable.logo_tip), 0.25f, -0.6f, 160f, 0.45f, 0.2f)
    )
    private var build = 0f
    private var sweep = -1f
    private var floatT = 0f
    private var buildAnim: ValueAnimator? = null
    private var loop: ValueAnimator? = null

    /** Plays the assembly from the start. */
    fun replay() {
        buildAnim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 1500L
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { build = it.animatedValue as Float; invalidate() }
        a.start()
        buildAnim = a
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        replay()
        val l = ValueAnimator.ofFloat(0f, 1f)
        l.duration = 4200L
        l.repeatCount = ValueAnimator.INFINITE
        l.interpolator = LinearInterpolator()
        l.addUpdateListener {
            val t = it.animatedValue as Float
            floatT = t
            sweep = if (t < 0.4f) -0.3f + 1.6f * (t / 0.4f) else -1f
            invalidate()
        }
        l.start()
        loop = l
    }

    override fun onDetachedFromWindow() {
        buildAnim?.cancel()
        loop?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val size = Math.min(width, height) * 0.84f
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f + (Math.sin(floatT * 2.0 * Math.PI) * 0.018 * size).toFloat()
        if (withGlow) {
            glow.shader = RadialGradient(cx, cy, size * 0.62f, 0x44D4693F, 0x00D4693F, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, size * 0.62f, glow)
        }
        val sweeping = build >= 1f && sweep > -0.9f
        val layer = if (sweeping) canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null) else -1
        for (p in pieces) {
            val local = ((build - p.from) / 0.55f).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val e = spring.getInterpolation(local)
            val inv = 1f - e
            val sc = p.scale + (1f - p.scale) * e
            canvas.save()
            canvas.translate(cx + p.dx * size * inv, cy + p.dy * size * inv)
            canvas.rotate(p.rot * inv)
            canvas.scale(sc * size / p.bmp.width, sc * size / p.bmp.height)
            canvas.translate(-p.bmp.width / 2f, -p.bmp.height / 2f)
            paint.alpha = (255 * Math.min(1f, local * 2.5f)).toInt()
            canvas.drawBitmap(p.bmp, 0f, 0f, paint)
            canvas.restore()
        }
        if (sweeping) {
            // a band of light, drawn only where the logo already has pixels
            val x = cx - size / 2f + size * sweep
            sweepPaint.shader = LinearGradient(x - size * 0.12f, cy - size * 0.3f, x + size * 0.12f, cy + size * 0.3f,
                intArrayOf(0x00FFFFFF, 0x88FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            sweepPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            canvas.drawRect(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f, sweepPaint)
            sweepPaint.xfermode = null
            canvas.restoreToCount(layer)
        }
    }
}

/** Faint facets drifting and turning in the background of the welcome screens. */
class FacetField(context: Context, private val tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private class P(val x: Float, val y: Float, val size: Float, val speed: Float, val phase: Float, val spin: Float, val alpha: Int)
    private val rnd = java.util.Random(7)
    private val items = List(16) {
        P(rnd.nextFloat(), rnd.nextFloat(), 14f + rnd.nextFloat() * 38f, 0.3f + rnd.nextFloat() * 0.9f, rnd.nextFloat() * 6.28f, (rnd.nextFloat() - 0.5f) * 2f, 14 + rnd.nextInt(26))
    }
    private var t = 0f
    private var anim: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val a = ValueAnimator.ofFloat(0f, 6.2831855f)
        a.duration = 30000L
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { t = it.animatedValue as Float; invalidate() }
        a.start()
        anim = a
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        for (p in items) {
            val px = w * p.x + Math.sin((t * p.speed + p.phase).toDouble()).toFloat() * 26f * d
            val py = (h * p.y - t * p.speed * 40f * d + h * 4f) % (h + 120f * d) - 60f * d
            val r = p.size * d
            canvas.save()
            canvas.translate(px, py)
            canvas.rotate(t * 57.3f * p.spin * 0.25f + p.phase * 10f)
            path.reset()
            // a small diamond with a bright left half
            path.moveTo(0f, -r); path.lineTo(r * 0.7f, 0f); path.lineTo(0f, r); path.lineTo(-r * 0.7f, 0f); path.close()
            paint.color = withAlpha(tint, p.alpha)
            canvas.drawPath(path, paint)
            path.reset()
            path.moveTo(0f, -r); path.lineTo(0f, r); path.lineTo(-r * 0.7f, 0f); path.close()
            paint.color = withAlpha(0xFFFFFF, p.alpha / 2)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }
}
