package app.facet.browser

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Builds the grouped-card rows used by the Settings and Software update pages. */
class RowKit(private val ctx: Context, private val c: Colors) {

    fun section(name: String): TextView {
        val t = makeTag(ctx, name, c.accent)
        t.setPadding(ctx.dp(6), ctx.dp(24), 0, ctx.dp(8))
        return t
    }

    fun group(): LinearLayout {
        val g = LinearLayout(ctx)
        g.orientation = LinearLayout.VERTICAL
        g.background = shape(c.surface, ctx.dpf(18f), c.line, ctx.dp(1))
        g.clipToOutline = true
        return g
    }

    /** Adds a row to a group, with a hairline divider above it unless it is the first. */
    fun add(g: LinearLayout, row: View) {
        if (g.childCount > 0) {
            val d = View(ctx)
            d.setBackgroundColor(c.line)
            val lp = LinearLayout.LayoutParams(MATCH, ctx.dp(1))
            lp.leftMargin = ctx.dp(16)
            g.addView(d, lp)
        }
        g.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun base(icon: Icon?): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
        if (icon != null) {
            val chip = FrameLayout(ctx)
            chip.background = shape(withAlpha(c.accent, 0x22), ctx.dpf(11f))
            chip.addView(IconView(ctx, icon, c.accent, 20), FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER))
            val lp = LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36))
            lp.rightMargin = ctx.dp(14)
            row.addView(chip, lp)
        }
        return row
    }

    private fun texts(title: String, sub: String?): LinearLayout {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(makeText(ctx, title, 16f, c.ink, Fonts.sansMedium))
        if (!sub.isNullOrEmpty()) {
            val s = makeText(ctx, sub, 13f, c.muted, Fonts.sans)
            s.setLineSpacing(0f, 1.15f)
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.topMargin = ctx.dp(3)
            col.addView(s, lp)
        }
        return col
    }

    fun toggle(icon: Icon?, title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = base(icon)
        val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp.rightMargin = ctx.dp(14)
        row.addView(texts(title, sub), lp)
        val sw = SwitchView(ctx, c, checked)
        sw.onChange = onChange
        row.addView(sw, LinearLayout.LayoutParams(ctx.dp(46), ctx.dp(28)))
        row.pressable(null, withAlpha(c.ink, 0x14), 0f)
        row.setOnClickListener { sw.performClick() }
        return row
    }

    fun value(icon: Icon?, title: String, value: String, sub: String? = null, onClick: () -> Unit): View {
        val row = base(icon)
        row.addView(texts(title, sub), LinearLayout.LayoutParams(0, WRAP, 1f))
        val v = makeText(ctx, value, 14f, c.muted, Fonts.sans)
        v.maxLines = 1
        v.ellipsize = TextUtils.TruncateAt.END
        v.maxWidth = ctx.dp(170)
        val vlp = LinearLayout.LayoutParams(WRAP, WRAP)
        vlp.leftMargin = ctx.dp(10)
        row.addView(v, vlp)
        val chev = IconView(ctx, Icon.FORWARD, c.muted, 16)
        val clp = LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16))
        clp.leftMargin = ctx.dp(4)
        row.addView(chev, clp)
        row.pressable(null, withAlpha(c.ink, 0x14), 0f)
        row.setOnClickListener { onClick() }
        return row
    }

    fun info(label: String, value: String): View {
        val row = base(null)
        row.setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
        row.addView(makeText(ctx, label, 15f, c.muted, Fonts.sans), LinearLayout.LayoutParams(0, WRAP, 1f))
        val v = makeText(ctx, value, 15f, c.ink, Fonts.sansMedium)
        v.gravity = Gravity.END
        v.maxWidth = ctx.dp(220)
        row.addView(v, LinearLayout.LayoutParams(WRAP, WRAP))
        return row
    }

    fun note(text: String): TextView {
        val t = makeText(ctx, text, 13f, c.muted, Fonts.sans)
        t.setLineSpacing(0f, 1.2f)
        t.setPadding(ctx.dp(6), ctx.dp(12), ctx.dp(6), 0)
        return t
    }

    fun header(title: String, onBack: () -> Unit): LinearLayout {
        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(ctx.dp(8), ctx.dp(12), ctx.dp(20), ctx.dp(4))
        val back = FrameLayout(ctx)
        back.pressable(null, withAlpha(c.ink, 0x22), ctx.dpf(24f))
        back.addView(IconView(ctx, Icon.BACK, c.ink, 24), FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
        back.setOnClickListener { onBack() }
        header.addView(back, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
        val t = makeText(ctx, title, 32f, c.ink, Fonts.serif)
        val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
        tlp.leftMargin = ctx.dp(6)
        header.addView(t, tlp)
        return header
    }

    fun button(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        val b = makeText(ctx, text, 15f, if (primary) c.bg else c.ink, Fonts.sansMedium)
        b.gravity = Gravity.CENTER
        b.setPadding(ctx.dp(20), ctx.dp(13), ctx.dp(20), ctx.dp(13))
        val bg = if (primary) shape(c.accent, ctx.dpf(24f)) else shape(0, ctx.dpf(24f), c.line, ctx.dp(1))
        b.pressable(bg, withAlpha(c.ink, 0x22), ctx.dpf(24f))
        b.setOnClickListener { onClick() }
        b.pressScale(0.96f)
        return b
    }
}
