package app.facet.browser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Ambient motion switch. On the Lite glass tier the home page holds still, so a screen nobody touches costs no
 * frames at all (live glass only re-renders when what's behind it actually changes).
 */
object Ambient {
    @Volatile var still = false
}

/** Soft colour blobs drifting slowly behind the page. Pure drawing, no images. */
class AuroraView(context: Context, private val c: Colors) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private var anim: ValueAnimator? = null
    private val blobs = arrayOf(
        floatArrayOf(0.25f, 0.18f, 0.85f, 0.35f, 0.9f, c.accent.toFloat()),
        floatArrayOf(0.8f, 0.42f, 0.55f, 0.28f, 1.3f, 0f),
        floatArrayOf(0.35f, 0.8f, 0.7f, 0.3f, 0.7f, 0f)
    )
    private val colors = intArrayOf(c.accent, if (c.dark) 0xFFB8862F.toInt() else 0xFFE0A15B.toInt(), if (c.dark) 0xFF5E7C8C.toInt() else 0xFF8FB3C0.toInt())

    private var lastFrame = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Ambient.still) return
        val a = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat())
        a.duration = 26000L
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        // the drift is very slow, so 15 redraws a second look the same as 60 and cost a quarter
        a.addUpdateListener {
            t = it.animatedValue as Float
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastFrame >= 66L) { lastFrame = now; invalidate() }
        }
        a.start()
        anim = a
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        for (i in blobs.indices) {
            val b = blobs[i]
            val phase = t * b[4] + i * 2.1f
            val cx = w * (b[0] + 0.12f * Math.cos(phase.toDouble()).toFloat())
            val cy = h * 0.5f * (b[1] + 0.14f * Math.sin((phase * 0.8f).toDouble()).toFloat())
            val r = w * b[2] * 0.85f
            val alpha = if (c.dark) 0x38 else 0x30
            paint.shader = RadialGradient(cx, cy, r, withAlpha(colors[i], alpha), withAlpha(colors[i], 0), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
}

/** Text whose colour carries a slow highlight sweep, used for the accent word in headings. */
class ShimmerText(context: Context, private val base: Int, private val glow: Int) : TextView(context) {
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var anim: ValueAnimator? = null
    private var shift = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val span = (w.coerceAtLeast(1)).toFloat()
        shader = LinearGradient(0f, 0f, span, 0f, intArrayOf(base, base, glow, base, base), floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f), Shader.TileMode.CLAMP)
        paint.shader = shader
    }

    private val again = Runnable { sweep() }

    /** One highlight sweep, then a rest before the next, so the text isn't redrawn all the time. */
    private fun sweep() {
        if (!isAttachedToWindow || Ambient.still) return
        val a = ValueAnimator.ofFloat(-1f, 1.6f)
        a.duration = 3600L
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { shift = it.animatedValue as Float; invalidate() }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { postDelayed(again, 7000L) }
        })
        a.start()
        anim = a
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        shift = 2f
        postDelayed(again, 900L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(again)
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val s = shader
        if (s != null) {
            matrix.reset()
            matrix.setTranslate(shift * width, 0f)
            s.setLocalMatrix(matrix)
            paint.shader = s
        }
        super.onDraw(canvas)
    }
}

/** A shield with two rings that swell and fade, like a quiet heartbeat. */
class PulseShield(context: Context, private val c: Colors) : FrameLayout(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1.5f)
    }
    private var phase = 0f
    private var anim: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        addView(IconView(context, Icon.SHIELD, c.accent, 26), LayoutParams(context.dp(26), context.dp(26), android.view.Gravity.CENTER))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 2600L
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { phase = it.animatedValue as Float; invalidate() }
        a.start()
        anim = a
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val max = minOf(width, height) / 2f
        for (k in 0..1) {
            val p = (phase + k * 0.5f) % 1f
            ring.color = withAlpha(c.accent, ((1f - p) * 0x90).toInt())
            canvas.drawCircle(cx, cy, max * (0.45f + 0.55f * p), ring)
        }
    }
}
