package app.facet.browser

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

class TabSwitcherView(
    private val ctx: Context,
    private val c: Colors,
    private val tabs: () -> List<BrowserTab>,
    private val current: () -> BrowserTab?,
    private val onSelect: (BrowserTab) -> Unit,
    private val onCloseTab: (BrowserTab) -> Unit,
    private val onNew: () -> Unit,
    private val onNewPrivate: () -> Unit,
    private val onDone: () -> Unit
) : LinearLayout(ctx) {

    private val gridHost = LinearLayout(ctx)
    private val countTag = makeTag(ctx, "", c.muted)

    init {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(c.bg)
        isClickable = true

        // Header
        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(8))
        val title = makeText(ctx, "Tabs", 36f, c.ink, Fonts.serif)
        header.addView(title, LinearLayout.LayoutParams(WRAP, WRAP))
        val spacer = View(ctx)
        header.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(countTag, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        // Grid
        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        gridHost.orientation = LinearLayout.VERTICAL
        gridHost.setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(16))
        scroll.addView(gridHost, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Footer buttons
        val footer = LinearLayout(ctx)
        footer.orientation = LinearLayout.HORIZONTAL
        footer.gravity = Gravity.CENTER_VERTICAL
        footer.setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(14))
        footer.addView(button("New tab", Icon.PLUS, true, onNew), weighted(1f))
        footer.addView(button("Private", Icon.PRIVATE, false, onNewPrivate), weighted(1f))
        footer.addView(button("Done", Icon.CHECK, false, onDone), weighted(1f))
        addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))

        rebuild()
    }

    private fun weighted(w: Float): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, ctx.dp(52), w)
        lp.leftMargin = ctx.dp(5)
        lp.rightMargin = ctx.dp(5)
        return lp
    }

    private fun button(label: String, icon: Icon, primary: Boolean, action: () -> Unit): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val fg = if (primary) 0xFFFFFFFF.toInt() else c.ink
        val bg = if (primary) shape(c.accent, ctx.dpf(26f)) else shape(c.surface, ctx.dpf(26f), c.line, ctx.dp(1))
        row.pressable(bg, withAlpha(fg, 0x33), ctx.dpf(26f))
        row.addView(IconView(ctx, icon, fg, 18), LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18)))
        val t = makeText(ctx, label, 15f, fg, Fonts.sansMedium)
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.leftMargin = ctx.dp(8)
        row.addView(t, lp)
        row.setOnClickListener { action() }
        return row
    }

    fun rebuild() {
        gridHost.removeAllViews()
        val list = tabs()
        countTag.text = (if (list.size == 1) "1 TAB" else "${list.size} TABS")
        var i = 0
        while (i < list.size) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            val first = card(list[i])
            val lp1 = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp1.setMargins(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
            row.addView(first, lp1)
            val lp2 = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp2.setMargins(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
            if (i + 1 < list.size) {
                row.addView(card(list[i + 1]), lp2)
            } else {
                row.addView(View(ctx), lp2)
            }
            gridHost.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            i += 2
        }
    }

    private fun card(tab: BrowserTab): View {
        val isCurrent = tab === current()
        val bgColor = if (tab.isPrivate) {
            if (c.dark) 0xFF2B221B.toInt() else 0xFF2A221C.toInt()
        } else c.surface
        val fg = if (tab.isPrivate) 0xFFF3EBDA.toInt() else c.ink
        val sub = if (tab.isPrivate) 0xFFB9AB98.toInt() else c.muted

        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(8), ctx.dp(14))
        val stroke = if (isCurrent) c.accent else c.line
        val strokeW = if (isCurrent) ctx.dp(2) else ctx.dp(1)
        card.pressable(shape(bgColor, ctx.dpf(18f), stroke, strokeW), withAlpha(fg, 0x22), ctx.dpf(18f))
        card.minimumHeight = ctx.dp(136)
        card.setOnClickListener { onSelect(tab) }

        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val label = if (tab.title.isNotBlank()) tab.title else if (tab.showsNewTab) "New tab" else Urls.host(tab.url)
        val letter = makeText(ctx, label.take(1).uppercase(), 26f, if (tab.isPrivate) 0xFFE58A5F.toInt() else c.accent, Fonts.serif)
        top.addView(letter, LinearLayout.LayoutParams(0, WRAP, 1f))
        val close = FrameLayout(ctx)
        close.pressable(null, withAlpha(fg, 0x22), ctx.dpf(18f))
        close.addView(IconView(ctx, Icon.CLOSE, sub, 16), FrameLayout.LayoutParams(ctx.dp(16), ctx.dp(16), Gravity.CENTER))
        close.setOnClickListener {
            onCloseTab(tab)
            rebuild()
        }
        top.addView(close, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)))
        card.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        val t = makeText(ctx, label, 15f, fg, Fonts.sansMedium)
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        val tlp = LinearLayout.LayoutParams(MATCH, WRAP)
        tlp.topMargin = ctx.dp(10)
        card.addView(t, tlp)

        val hostText = when {
            tab.isPrivate && tab.showsNewTab -> "Private"
            tab.showsNewTab -> "Facet"
            else -> Urls.host(tab.url) + if (tab.isPrivate) "  ·  private" else ""
        }
        val h = makeText(ctx, hostText, 12f, sub, Fonts.sans)
        h.maxLines = 1
        h.ellipsize = TextUtils.TruncateAt.END
        val hlp = LinearLayout.LayoutParams(MATCH, WRAP)
        hlp.topMargin = ctx.dp(4)
        card.addView(h, hlp)
        return card
    }
}
