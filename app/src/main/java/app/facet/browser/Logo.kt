package app.facet.browser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/** The Facet gem, as nine flat facets on a 108 unit grid (the same shapes as the launcher icon). */
object Gem {
    class Facet(val pts: FloatArray, val color: Int)

    val facets = listOf(
        Facet(floatArrayOf(24f, 44f, 40f, 30f, 39f, 44f), 0xFFF4B08A.toInt()),
        Facet(floatArrayOf(40f, 30f, 54f, 44f, 39f, 44f), 0xFFFFD9B8.toInt()),
        Facet(floatArrayOf(40f, 30f, 68f, 30f, 54f, 44f), 0xFFFFEFDD.toInt()),
        Facet(floatArrayOf(68f, 30f, 69f, 44f, 54f, 44f), 0xFFF0A070.toInt()),
        Facet(floatArrayOf(68f, 30f, 84f, 44f, 69f, 44f), 0xFFD9774A.toInt()),
        Facet(floatArrayOf(24f, 44f, 39f, 44f, 54f, 86f), 0xFFC8623A.toInt()),
        Facet(floatArrayOf(39f, 44f, 54f, 44f, 54f, 86f), 0xFFE0804F.toInt()),
        Facet(floatArrayOf(54f, 44f, 69f, 44f, 54f, 86f), 0xFFB5532F.toInt()),
        Facet(floatArrayOf(69f, 44f, 84f, 44f, 54f, 86f), 0xFF8F3E20.toInt())
    )
}

/**
 * The logo, animated: the facets fly in from outside and snap together with a spring, then a band of light
 * sweeps across the gem now and then and the whole thing floats gently.
 */
class LogoView(context: Context, private val withGlow: Boolean = true) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spring = SpringInterpolator(0.28f, 0.55f)
    private var build = 0f      // 0..1 assembly progress
    private var sweep = -1f     // light band position, -1 = off
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
            // the band crosses during the first 40% of each cycle
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
        val s = Math.min(width, height) / 108f
        if (s <= 0f) return
        val ox = (width - 108f * s) / 2f
        val oy = (height - 108f * s) / 2f + (Math.sin(floatT * 2.0 * Math.PI) * 1.6 * s).toFloat()
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        if (withGlow) {
            glow.shader = android.graphics.RadialGradient(54f, 56f, 46f, 0x55E58A5F, 0x00E58A5F, Shader.TileMode.CLAMP)
            canvas.drawCircle(54f, 56f, 46f, glow)
        }
        val n = Gem.facets.size
        val clip = Path()
        for ((i, f) in Gem.facets.withIndex()) {
            val local = ((build * (n + 3) - i) / 3f).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val e = spring.getInterpolation(local)
            // direction away from the gem's centre
            var cx = 0f; var cy = 0f
            for (k in 0 until 3) { cx += f.pts[k * 2] / 3f; cy += f.pts[k * 2 + 1] / 3f }
            val dx = (cx - 54f) * 1.4f + (if (cx < 54f) -26f else 26f)
            val dy = (cy - 56f) * 1.4f - 20f
            val tx = dx * (1f - e)
            val ty = dy * (1f - e)
            path.reset()
            path.moveTo(f.pts[0] + tx, f.pts[1] + ty)
            path.lineTo(f.pts[2] + tx, f.pts[3] + ty)
            path.lineTo(f.pts[4] + tx, f.pts[5] + ty)
            path.close()
            paint.color = withAlpha(f.color, (255 * local.coerceAtMost(1f)).toInt())
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
            if (e >= 0.98f) {
                clip.moveTo(f.pts[0], f.pts[1]); clip.lineTo(f.pts[2], f.pts[3]); clip.lineTo(f.pts[4], f.pts[5]); clip.close()
            }
        }
        // sweeping light, clipped to the gem
        if (build >= 1f && sweep > -0.9f) {
            canvas.save()
            canvas.clipPath(clip)
            val x = 24f + 60f * sweep
            paint.shader = LinearGradient(x - 10f, 30f, x + 10f, 60f, intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 20f, 108f, 90f, paint)
            paint.shader = null
            canvas.restore()
        }
        canvas.restore()
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
