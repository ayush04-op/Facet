package app.facet.browser

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

class SheetItem(
    val icon: Icon?,
    val label: String,
    val checked: Boolean = false,
    val destructive: Boolean = false,
    val action: () -> Unit
)

/** Bottom sheet: dimmed scrim plus a rounded card of tappable rows. */
class SheetView(
    ctx: Context,
    c: Colors,
    title: String?,
    items: List<SheetItem>,
    onDismiss: () -> Unit
) : FrameLayout(ctx) {

    init {
        isClickable = true
        val scrim = View(ctx)
        scrim.setBackgroundColor(0x66000000)
        scrim.setOnClickListener { onDismiss() }
        addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        val r = ctx.dpf(24f)
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(c.bg)
        bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        card.background = bg
        card.isClickable = true
        card.setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(8), ctx.dp(12))

        val grab = View(ctx)
        grab.background = shape(c.line, ctx.dpf(2f))
        val glp = LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(4))
        glp.gravity = Gravity.CENTER_HORIZONTAL
        glp.bottomMargin = ctx.dp(8)
        card.addView(grab, glp)

        if (title != null) {
            val t = makeText(ctx, title, 22f, c.ink, Fonts.serif)
            t.setPadding(ctx.dp(14), ctx.dp(6), ctx.dp(14), ctx.dp(10))
            card.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        for (item in items) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            row.pressable(null, withAlpha(c.ink, 0x1A), ctx.dpf(14f))
            val color = if (item.destructive) c.accent else c.ink
            if (item.icon != null) {
                val ic = IconView(ctx, item.icon, color, 22)
                val ilp = LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22))
                ilp.rightMargin = ctx.dp(16)
                row.addView(ic, ilp)
            }
            val label = makeText(ctx, item.label, 16f, color, Fonts.sans)
            row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (item.checked) {
                row.addView(IconView(ctx, Icon.CHECK, c.accent, 20), LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20)))
            }
            row.setOnClickListener {
                onDismiss()
                item.action()
            }
            card.addView(row, LinearLayout.LayoutParams(MATCH, ctx.dp(52)))
        }

        val lp = FrameLayout.LayoutParams(MATCH, WRAP)
        lp.gravity = Gravity.BOTTOM
        addView(card, lp)
    }
}
