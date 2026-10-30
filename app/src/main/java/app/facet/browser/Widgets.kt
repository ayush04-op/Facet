package app.facet.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

enum class Icon {
    BACK, FORWARD, PLUS, CLOSE, MENU, TABS, LOCK, SEARCH, SHIELD, STAR,
    SETTINGS, SHARE, REFRESH, GLOBE, TRASH, CHECK, DESKTOP, PRIVATE, LINK
}

/** All icons are drawn in code on a 24x24 grid, so there are no image resources to go wrong. */
class IconView(context: Context, icon: Icon, tint: Int, private val sizeDp: Int = 24) : View(context) {
    var icon: Icon = icon
        set(v) { field = v; invalidate() }
    var tint: Int = tint
        set(v) { field = v; invalidate() }
    var badge: String? = null
        set(v) { field = v; invalidate() }
    var filled: Boolean = false
        set(v) { field = v; invalidate() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.8f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 9f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val px = context.dp(sizeDp)
        setMeasuredDimension(resolveSize(px, widthMeasureSpec), resolveSize(px, heightMeasureSpec))
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        c.drawLine(x1, y1, x2, y2, stroke)
    }

    private fun poly(vararg pts: Float): Path {
        val p = Path()
        var i = 0
        while (i < pts.size) {
            if (i == 0) p.moveTo(pts[i], pts[i + 1]) else p.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        return p
    }

    override fun onDraw(canvas: Canvas) {
        val s = width / 24f
        stroke.color = tint
        fill.color = tint
        canvas.save()
        canvas.scale(s, s)
        when (icon) {
            Icon.BACK -> canvas.drawPath(poly(15f, 5f, 8f, 12f, 15f, 19f), stroke)
            Icon.FORWARD -> canvas.drawPath(poly(9f, 5f, 16f, 12f, 9f, 19f), stroke)
            Icon.PLUS -> { line(canvas, 12f, 5f, 12f, 19f); line(canvas, 5f, 12f, 19f, 12f) }
            Icon.CLOSE -> { line(canvas, 6f, 6f, 18f, 18f); line(canvas, 18f, 6f, 6f, 18f) }
            Icon.MENU -> {
                canvas.drawCircle(12f, 5.5f, 1.7f, fill)
                canvas.drawCircle(12f, 12f, 1.7f, fill)
                canvas.drawCircle(12f, 18.5f, 1.7f, fill)
            }
            Icon.TABS -> {
                canvas.drawRoundRect(RectF(4f, 5f, 20f, 19f), 3.5f, 3.5f, stroke)
                val b = badge
                if (b != null) {
                    textPaint.color = tint
                    textPaint.typeface = Fonts.sansBold
                    canvas.drawText(b, 12f, 15.2f, textPaint)
                }
            }
            Icon.LOCK -> {
                canvas.drawRoundRect(RectF(6f, 11f, 18f, 20f), 2.5f, 2.5f, stroke)
                val p = Path()
                p.moveTo(8.5f, 11f)
                p.lineTo(8.5f, 8.5f)
                p.cubicTo(8.5f, 4.5f, 15.5f, 4.5f, 15.5f, 8.5f)
                p.lineTo(15.5f, 11f)
                canvas.drawPath(p, stroke)
            }
            Icon.SEARCH -> {
                canvas.drawCircle(11f, 11f, 6f, stroke)
                line(canvas, 15.5f, 15.5f, 20f, 20f)
            }
            Icon.SHIELD -> {
                val p = Path()
                p.moveTo(12f, 3f)
                p.lineTo(19f, 6f)
                p.lineTo(19f, 12f)
                p.cubicTo(19f, 16.5f, 16f, 19.5f, 12f, 21f)
                p.cubicTo(8f, 19.5f, 5f, 16.5f, 5f, 12f)
                p.lineTo(5f, 6f)
                p.close()
                canvas.drawPath(p, stroke)
            }
            Icon.STAR -> {
                val p = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 8.6f else 3.7f
                    val a = Math.toRadians((-90 + i * 36).toDouble())
                    val x = (12 + r * Math.cos(a)).toFloat()
                    val y = (12.6 + r * Math.sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                canvas.drawPath(p, if (filled) fill else stroke)
            }
            Icon.SETTINGS -> {
                line(canvas, 4f, 8f, 20f, 8f)
                line(canvas, 4f, 16f, 20f, 16f)
                fill.color = 0
                canvas.drawCircle(9f, 8f, 2.4f, stroke)
                canvas.drawCircle(15f, 16f, 2.4f, stroke)
            }
            Icon.SHARE -> {
                line(canvas, 12f, 15f, 12f, 4f)
                canvas.drawPath(poly(8f, 8f, 12f, 4f, 16f, 8f), stroke)
                canvas.drawPath(poly(6f, 12f, 6f, 19f, 18f, 19f, 18f, 12f), stroke)
            }
            Icon.REFRESH -> {
                val p = Path()
                p.arcTo(RectF(5f, 5f, 19f, 19f), -50f, 290f, true)
                canvas.drawPath(p, stroke)
                canvas.drawPath(poly(17.2f, 2.8f, 17.2f, 7.6f, 12.4f, 7.6f), stroke)
            }
            Icon.GLOBE -> {
                canvas.drawCircle(12f, 12f, 8f, stroke)
                canvas.drawOval(RectF(8.5f, 4f, 15.5f, 20f), stroke)
                line(canvas, 4f, 12f, 20f, 12f)
            }
            Icon.TRASH -> {
                line(canvas, 5f, 7f, 19f, 7f)
                canvas.drawPath(poly(7f, 7f, 8f, 20f, 16f, 20f, 17f, 7f), stroke)
                canvas.drawPath(poly(10f, 7f, 10f, 4f, 14f, 4f, 14f, 7f), stroke)
            }
            Icon.CHECK -> canvas.drawPath(poly(5f, 12.5f, 10f, 17.5f, 19f, 7f), stroke)
            Icon.DESKTOP -> {
                canvas.drawRoundRect(RectF(3f, 5f, 21f, 16f), 2f, 2f, stroke)
                line(canvas, 9f, 20f, 15f, 20f)
                line(canvas, 12f, 16f, 12f, 20f)
            }
            Icon.PRIVATE -> {
                line(canvas, 3.5f, 10f, 20.5f, 10f)
                canvas.drawPath(poly(6.5f, 10f, 8.5f, 4.5f, 15.5f, 4.5f, 17.5f, 10f), stroke)
                canvas.drawCircle(8f, 16f, 2.8f, stroke)
                canvas.drawCircle(16f, 16f, 2.8f, stroke)
                line(canvas, 10.8f, 15.5f, 13.2f, 15.5f)
            }
            Icon.LINK -> {
                canvas.drawRoundRect(RectF(3.5f, 9f, 11.5f, 15f), 3f, 3f, stroke)
                canvas.drawRoundRect(RectF(12.5f, 9f, 20.5f, 15f), 3f, 3f, stroke)
                line(canvas, 9f, 12f, 15f, 12f)
            }
        }
        canvas.restore()
    }
}

/** Pill-shaped on/off switch in the Paper style. */
class SwitchView(context: Context, private val c: Colors, checked: Boolean) : View(context) {
    var checked: Boolean = checked
        set(v) { field = v; invalidate() }
    var onChange: ((Boolean) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        setOnClickListener {
            checked = !checked
            onChange?.invoke(checked)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(46), context.dp(28))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = if (checked) c.accent else c.line
        canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, paint)
        val pad = context.dpf(3f)
        val r = h / 2 - pad
        val cx = if (checked) w - pad - r else pad + r
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, h / 2, r, paint)
    }
}

/** Faint drafting grid with registration crosses, the Nuance-inspired backdrop. */
class GridBackground(context: Context, private val c: Colors) : View(context) {
    private val linePaint = Paint().apply {
        color = withAlpha(c.line, 0x80)
        strokeWidth = context.dpf(1f)
    }
    private val crossPaint = Paint().apply {
        color = withAlpha(c.accent, 0x70)
        strokeWidth = context.dpf(1.2f)
    }

    override fun onDraw(canvas: Canvas) {
        val step = context.dpf(40f)
        var x = step / 2
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
            x += step
        }
        var y = step / 2
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
            y += step
        }
        val arm = context.dpf(4f)
        x = step / 2
        while (x < width) {
            y = step / 2
            // crosses only on every 3rd intersection so the grid stays calm
            val ix = ((x - step / 2) / step).toInt()
            while (y < height) {
                val iy = ((y - step / 2) / step).toInt()
                if (ix % 3 == 0 && iy % 3 == 0) {
                    canvas.drawLine(x - arm, y, x + arm, y, crossPaint)
                    canvas.drawLine(x, y - arm, x, y + arm, crossPaint)
                }
                y += step
            }
            x += step
        }
    }
}

/** Thin accent line under the page that fills as it loads. */
class ProgressLine(context: Context, private val c: Colors) : View(context) {
    var progress: Int = 0
        set(v) { field = v; invalidate() }

    private val paint = Paint().apply { color = c.accent }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0 || progress >= 100) return
        canvas.drawRect(0f, 0f, width * progress / 100f, height.toFloat(), paint)
    }
}

/** A bento card with bounding-box corner ticks. */
class BentoCard(context: Context, private val c: Colors) : LinearLayout(context) {
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c.accent
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1.4f)
        strokeCap = Paint.Cap.SQUARE
    }

    init {
        orientation = LinearLayout.VERTICAL
        background = shape(c.surface, context.dpf(18f), c.line, context.dp(1))
        val p = context.dp(16)
        setPadding(p, p, p, p)
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val i = context.dpf(8f)
        val s = context.dpf(7f)
        val w = width.toFloat()
        val h = height.toFloat()
        val p = Path()
        p.moveTo(i, i + s); p.lineTo(i, i); p.lineTo(i + s, i)
        p.moveTo(w - i - s, i); p.lineTo(w - i, i); p.lineTo(w - i, i + s)
        p.moveTo(i, h - i - s); p.lineTo(i, h - i); p.lineTo(i + s, h - i)
        p.moveTo(w - i - s, h - i); p.lineTo(w - i, h - i); p.lineTo(w - i, h - i - s)
        canvas.drawPath(p, tick)
    }
}
