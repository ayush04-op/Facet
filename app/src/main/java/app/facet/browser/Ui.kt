package app.facet.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.annotation.SuppressLint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

fun shape(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable {
    val d = GradientDrawable()
    d.shape = GradientDrawable.RECTANGLE
    d.cornerRadius = radius
    d.setColor(color)
    if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor)
    return d
}

fun View.pressable(bg: Drawable?, ripple: Int, radius: Float) {
    val mask = GradientDrawable()
    mask.shape = GradientDrawable.RECTANGLE
    mask.cornerRadius = radius
    mask.setColor(Color.WHITE)
    background = RippleDrawable(ColorStateList.valueOf(ripple), bg, mask)
    isClickable = true
}

fun makeText(ctx: Context, s: CharSequence, sp: Float, color: Int, face: Typeface): TextView {
    val t = TextView(ctx)
    t.text = s
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    t.setTextColor(color)
    t.typeface = face
    t.includeFontPadding = false
    return t
}

/** Small all-caps monospace label, the "technical drawing" accent used throughout. */
fun makeTag(ctx: Context, s: String, color: Int): TextView {
    val t = makeText(ctx, s.uppercase(), 10.5f, color, Fonts.mono)
    t.letterSpacing = 0.14f
    return t
}

/** Applies a different typeface to part of a string (used for the italic accent word). */
class FaceSpan(private val face: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(p: TextPaint) { p.typeface = face }
    override fun updateMeasureState(p: TextPaint) { p.typeface = face }
}

/** Squashes a view slightly while it is pressed and springs it back on release. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressScale(down: Float = 0.92f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(down).scaleY(down).setDuration(90L)
                    .setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(240L)
                    .setInterpolator(OvershootInterpolator(2.4f)).start()
        }
        false
    }
}
