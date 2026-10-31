package app.facet.browser

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

class TabSwitcherView(
    private val ctx: Context,
    private val c: Colors,
    private val workspaceName: String,
    private val tabs: () -> List<BrowserTab>,
    private val current: () -> BrowserTab?,
    private val listMode: () -> Int,
    private val onToggleMode: () -> Unit,
    private val onSelect: (BrowserTab) -> Unit,
    private val onCloseTab: (BrowserTab) -> Unit,
    private val onLongPress: (BrowserTab) -> Unit,
    private val onNew: () -> Unit,
    private val onNewPrivate: () -> Unit,
    private val onWorkspace: () -> Unit,
    private val onDone: () -> Unit
) : LinearLayout(ctx) {

    private val collapsed = HashSet<String>()
    private val gridHost = LinearLayout(ctx)
    private val countTag = makeTag(ctx, "", c.muted)
    private lateinit var modeIcon: IconView

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
        val toggle = FrameLayout(ctx)
        toggle.pressable(null, withAlpha(c.ink, 0x22), ctx.dpf(20f))
        modeIcon = IconView(ctx, if (listMode() == 1) Icon.GRID else Icon.LIST, c.ink, 22)
        toggle.addView(modeIcon, FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.CENTER))
        toggle.setOnClickListener {
            onToggleMode()
            modeIcon.icon = if (listMode() == 1) Icon.GRID else Icon.LIST
            rebuild()
        }
        val tlp = LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40))
        tlp.leftMargin = ctx.dp(6)
        header.addView(toggle, tlp)
        addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val wsChip = LinearLayout(ctx)
        wsChip.orientation = LinearLayout.HORIZONTAL
        wsChip.gravity = Gravity.CENTER_VERTICAL
        wsChip.setPadding(ctx.dp(12), ctx.dp(7), ctx.dp(14), ctx.dp(7))
        wsChip.pressable(shape(c.surface, ctx.dpf(20f), c.line, ctx.dp(1)), withAlpha(c.ink, 0x22), ctx.dpf(20f))
        wsChip.addView(IconView(ctx, Icon.FOLDER, c.accent, 16), LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)))
        val wsText = makeText(ctx, workspaceName, 14f, c.ink, Fonts.sansMedium)
        val wlp = LinearLayout.LayoutParams(WRAP, WRAP)
        wlp.leftMargin = ctx.dp(8)
        wsChip.addView(wsText, wlp)
        wsChip.setOnClickListener { onWorkspace() }
        val wclp = LinearLayout.LayoutParams(WRAP, WRAP)
        wclp.leftMargin = ctx.dp(20)
        addView(wsChip, wclp)

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
        row.pressScale(0.94f)
        return row
    }

    private fun sections(list: List<BrowserTab>): List<Pair<String?, List<BrowserTab>>> {
        val out = ArrayList<Pair<String?, List<BrowserTab>>>()
        val ungrouped = list.filter { it.group == null }
        val names = list.mapNotNull { it.group }.distinct()
        if (ungrouped.isNotEmpty()) out.add(Pair(null, ungrouped))
        for (n in names) out.add(Pair(n, list.filter { it.group == n }))
        return out
    }

    fun rebuild(animate: Boolean = true) {
        gridHost.removeAllViews()
        val list = tabs()
        countTag.text = (if (list.size == 1) "1 TAB" else "${list.size} TABS")
        val secs = sections(list)
        val showHeaders = secs.any { it.first != null }
        var shown = 0
        for ((name, group) in secs) {
            if (showHeaders) gridHost.addView(groupHeader(name, group.size))
            if (name != null && collapsed.contains(name)) continue
            if (listMode() == 1) {
                for (t in group) {
                    val row = listRow(t)
                    if (animate) enter(row, shown++)
                    val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                    lp.setMargins(ctx.dp(5), ctx.dp(4), ctx.dp(5), ctx.dp(4))
                    gridHost.addView(row, lp)
                }
            } else {
                var i = 0
                while (i < group.size) {
                    val row = LinearLayout(ctx)
                    row.orientation = LinearLayout.HORIZONTAL
                    val first = card(group[i])
                    if (animate) enter(first, shown++)
                    val lp1 = LinearLayout.LayoutParams(0, WRAP, 1f)
                    lp1.setMargins(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
                    row.addView(first, lp1)
                    val lp2 = LinearLayout.LayoutParams(0, WRAP, 1f)
                    lp2.setMargins(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
                    if (i + 1 < group.size) {
                        val second = card(group[i + 1])
                        if (animate) enter(second, shown++)
                        row.addView(second, lp2)
                    } else {
                        row.addView(View(ctx), lp2)
                    }
                    gridHost.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
                    i += 2
                }
            }
        }
    }

    private fun groupHeader(name: String?, count: Int): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dp(8), ctx.dp(12), ctx.dp(8), ctx.dp(6))
        val key = name ?: ""
        val isCollapsed = name != null && collapsed.contains(name)
        if (name != null) {
            val dot = View(ctx)
            dot.background = shape(c.accent, ctx.dpf(5f))
            row.addView(dot, LinearLayout.LayoutParams(ctx.dp(10), ctx.dp(10)))
        }
        val label = makeText(ctx, (name ?: "Ungrouped"), 15f, c.ink, Fonts.serif)
        val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp.leftMargin = if (name != null) ctx.dp(10) else 0
        row.addView(label, lp)
        row.addView(makeTag(ctx, if (isCollapsed) "$count hidden" else "$count", c.muted))
        if (name != null) {
            row.setOnClickListener {
                if (!collapsed.remove(key)) collapsed.add(key)
                rebuild(false)
            }
            row.pressable(null, withAlpha(c.ink, 0x14), ctx.dpf(12f))
        }
        return row
    }

    private fun listRow(tab: BrowserTab): View {
        val isCurrent = tab === current()
        val fg = if (tab.isPrivate) 0xFFF3EBDA.toInt() else c.ink
        val sub = if (tab.isPrivate) 0xFFB9AB98.toInt() else c.muted
        val bg = if (tab.isPrivate) 0xFF2A221C.toInt() else c.surface
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(6), ctx.dp(8))
        row.pressable(shape(bg, ctx.dpf(16f), if (isCurrent) c.accent else c.line, if (isCurrent) ctx.dp(2) else ctx.dp(1)), withAlpha(fg, 0x22), ctx.dpf(16f))
        row.setOnClickListener { onSelect(tab) }
        row.setOnLongClickListener { onLongPress(tab); true }

        val label = if (tab.title.isNotBlank()) tab.title else if (tab.showsNewTab) "New tab" else Urls.host(tab.url)
        val letter = makeText(ctx, label.take(1).uppercase(), 16f, if (tab.isPrivate) 0xFFE58A5F.toInt() else c.accent, Fonts.serifText)
        letter.gravity = Gravity.CENTER
        letter.background = shape(withAlpha(c.accent, 0x24), ctx.dpf(16f))
        row.addView(letter, LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32)))

        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        val t1 = makeText(ctx, label, 15f, fg, Fonts.sansMedium)
        t1.maxLines = 1
        t1.ellipsize = TextUtils.TruncateAt.END
        val t2 = makeText(ctx, if (tab.showsNewTab) "Facet" else Urls.host(tab.url), 12f, sub, Fonts.sans)
        t2.maxLines = 1
        t2.ellipsize = TextUtils.TruncateAt.END
        texts.addView(t1)
        texts.addView(t2)
        val tlp = LinearLayout.LayoutParams(0, WRAP, 1f)
        tlp.leftMargin = ctx.dp(12)
        row.addView(texts, tlp)

        val close = FrameLayout(ctx)
        close.pressable(null, withAlpha(fg, 0x22), ctx.dpf(18f))
        close.addView(IconView(ctx, Icon.CLOSE, sub, 16), FrameLayout.LayoutParams(ctx.dp(16), ctx.dp(16), Gravity.CENTER))
        close.setOnClickListener {
            row.animate().alpha(0f).translationX(ctx.dpf(40f)).setDuration(170L).withEndAction {
                onCloseTab(tab)
                rebuild(false)
            }.start()
        }
        row.addView(close, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)))
        return row
    }

    private fun enter(v: View, i: Int) {
        v.alpha = 0f
        v.scaleX = 0.92f
        v.scaleY = 0.92f
        v.translationY = ctx.dpf(16f)
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(40L * i + 40L).setDuration(320L)
            .setInterpolator(DecelerateInterpolator(2f)).start()
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
        card.setOnLongClickListener { onLongPress(tab); true }
        card.pressScale(0.96f)

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
            close.isEnabled = false
            card.animate().alpha(0f).scaleX(0.88f).scaleY(0.88f).setDuration(170L)
                .withEndAction {
                    onCloseTab(tab)
                    rebuild(false)
                }.start()
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
