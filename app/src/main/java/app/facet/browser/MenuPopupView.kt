package app.facet.browser

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout

/** The overflow menu: a squircle pop-up that springs out of its button, with a highlighted selection. */
class MenuPopupView(
    ctx: Context,
    private val c: Colors,
    items: List<SheetItem>,
    bottomInset: Int,
    onDismiss: () -> Unit
) : FrameLayout(ctx) {

    private var leaving = false
    private val scrim = View(ctx)
    private val card = LinearLayout(ctx)

    fun cancel() {}

    private fun animateOut(end: () -> Unit) {
        if (leaving) return
        leaving = true
        scrim.animate().alpha(0f).setDuration(140L).start()
        card.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(150L)
            .setInterpolator(AccelerateInterpolator()).withEndAction { end() }.start()
    }

    init {
        isClickable = true
        scrim.setBackgroundColor(0x40000000)
        scrim.alpha = 0f
        scrim.setOnClickListener { animateOut { onDismiss() } }
        addView(scrim, LayoutParams(MATCH, MATCH))

        card.orientation = LinearLayout.VERTICAL
        card.background = SquircleDrawable(withAlpha(c.bg, 0xEE), withAlpha(c.ink, if (c.dark) 0x26 else 0x1A), ctx.dpf(1f), ctx.dpf(30f))
        card.elevation = ctx.dpf(10f)
        card.isClickable = true
        card.setPadding(ctx.dp(10), ctx.dp(12), ctx.dp(10), ctx.dp(12))

        val rows = ArrayList<View>()
        for (item in items) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            row.minimumHeight = ctx.dp(52)
            val highlight = if (item.checked) withAlpha(c.accent, 0x26) else 0
            row.pressable(if (highlight != 0) shape(highlight, ctx.dpf(20f)) else null, withAlpha(c.ink, 0x1A), ctx.dpf(20f))
            val color = if (item.destructive) c.accent else c.ink
            if (item.icon != null) {
                val ilp = LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22))
                ilp.rightMargin = ctx.dp(14)
                row.addView(IconView(ctx, item.icon, color, 22), ilp)
            }
            row.addView(makeText(ctx, item.label, 16f, color, Fonts.sans), LinearLayout.LayoutParams(0, WRAP, 1f))
            if (item.checked) {
                val clp = LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20))
                clp.leftMargin = ctx.dp(12)
                row.addView(IconView(ctx, Icon.CHECK, c.accent, 20), clp)
            }
            row.setOnClickListener {
                animateOut {
                    onDismiss()
                    item.action()
                }
            }
            card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            rows.add(row)
        }

        val maxH = ctx.resources.displayMetrics.heightPixels - bottomInset - ctx.dp(96)
        val scroll = android.widget.ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
        val lp = LayoutParams(ctx.dp(300), WRAP, Gravity.BOTTOM or Gravity.END)
        lp.setMargins(0, 0, ctx.dp(10), bottomInset)
        // keep very long menus inside the screen
        scroll.post {
            if (scroll.height > maxH) scroll.layoutParams = scroll.layoutParams.also { it.height = maxH }
        }
        addView(scroll, lp)

        // Entrance: grows out of the menu button with a springy settle, rows follow one after another.
        scroll.visibility = View.INVISIBLE
        scroll.post {
            scroll.pivotX = scroll.width.toFloat() - ctx.dpf(24f)
            scroll.pivotY = scroll.height.toFloat()
            scroll.scaleX = 0.55f
            scroll.scaleY = 0.55f
            scroll.alpha = 0f
            scroll.visibility = View.VISIBLE
            scroll.animate().scaleX(1f).scaleY(1f).setDuration(520L).setInterpolator(SpringInterpolator(0.32f, 0.5f)).start()
            scroll.animate().alpha(1f).setDuration(120L).start()
            scrim.animate().alpha(1f).setDuration(200L).start()
            for ((i, r) in rows.withIndex()) {
                r.alpha = 0f
                r.translationY = ctx.dpf(14f)
                r.animate().alpha(1f).translationY(0f).setStartDelay(30L + 22L * i).setDuration(420L)
                    .setInterpolator(SpringInterpolator(0.25f, 0.45f)).start()
            }
        }
    }
}
