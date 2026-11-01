package app.facet.browser

import android.animation.ValueAnimator
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.NumberFormat

class HomeActions(
    val onOpen: (String) -> Unit,
    val onBookmarkLong: (Bookmark) -> Unit,
    val onWorkspace: () -> Unit,
    val onCommand: () -> Unit,
    val onResearch: () -> Unit,
    val onDownloads: () -> Unit,
    val onReminders: () -> Unit,
    val onNewPrivate: () -> Unit
)

/** The page shown in an empty tab: drifting colour, a shimmering headline and a bento of quick actions. */
class NewTabView(
    ctx: Context,
    private val c: Colors,
    isPrivate: Boolean,
    blocked: Long,
    upgraded: Long,
    version: String,
    workspace: Workspace,
    bookmarks: List<Bookmark>,
    reminderCount: Int,
    private val a: HomeActions,
    private val cfg: HomeConfig,
    private val prefs: Prefs,
    private val look: HomeLook.Look?
) : FrameLayout(ctx) {

    private val col = LinearLayout(ctx)
    // a Paper strip behind the status bar, so scrolled content never runs into the clock and icons
    private val statusScrim = View(ctx).apply { setBackgroundColor(withAlpha(c.bg, 0xF2)) }

    /** Keeps the content clear of the status bar at the top and the toolbar below. */
    fun setInsets(top: Int, bottom: Int) {
        val t = context.dp(14) + top
        val b = context.dp(10) + bottom
        if (col.paddingTop != t || col.paddingBottom != b) col.setPadding(col.paddingLeft, t, col.paddingRight, b)
        if (statusScrim.layoutParams != null && statusScrim.layoutParams.height != top) {
            statusScrim.layoutParams = statusScrim.layoutParams.also { it.height = top }
        }
    }

    init {
        setBackgroundColor(c.bg)
        // The background is laid out at full screen height and anchored to the top, so opening the keyboard
        // (which shrinks this page) never rescales or shifts it.
        val bgHost = FrameLayout(ctx)
        val screenH = screenHeightPx(ctx)
        if (look != null) bgHost.addView(look.wallpaper, LayoutParams(MATCH, screenH))
        if (look == null || cfg.aurora) {
            val aurora = AuroraView(ctx, c)
            aurora.alpha = 0f
            aurora.animate().alpha(if (look != null) 0.6f else 1f).setDuration(1200L).start()
            bgHost.addView(aurora, LayoutParams(MATCH, screenH))
        }
        if (look == null) {
            val grid = GridBackground(ctx, c)
            grid.alpha = 0f
            grid.animate().alpha(0.6f).setDuration(900L).start()
            bgHost.addView(grid, LayoutParams(MATCH, screenH))
        }
        addView(bgHost, LayoutParams(MATCH, MATCH))

        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(20), ctx.dp(10))

        // chips: workspace and command bar
        val chips = LinearLayout(ctx)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.gravity = Gravity.CENTER_VERTICAL
        val wsLabel = if (isPrivate) "Private" else workspace.name
        chips.addView(chip(ctx, Icon.FOLDER, wsLabel, true) { a.onWorkspace() })
        val cmd = chip(ctx, Icon.COMMAND, "Search everything", false) { a.onCommand() }
        val clp = LinearLayout.LayoutParams(WRAP, WRAP)
        clp.leftMargin = ctx.dp(8)
        chips.addView(cmd, clp)
        col.addView(chips, LinearLayout.LayoutParams(WRAP, WRAP))

        // clock, date and weather
        if (cfg.clock && !isPrivate) col.addView(clockBlock(ctx), LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = ctx.dp(18) })

        // headline: two lines, the second shimmers (one compact line when the clock is shown)
        val compact = cfg.clock && !isPrivate
        val greeting = if (!isPrivate && cfg.name.isNotEmpty()) greetingFor(cfg.name) else null
        val line1 = makeText(ctx, if (isPrivate) "Nothing here will" else greeting ?: "Browse,", if (compact) 30f else 42f, c.ink, Fonts.serif)
        line1.setLineSpacing(0f, 1.0f)
        val l1 = LinearLayout.LayoutParams(WRAP, WRAP)
        l1.topMargin = ctx.dp(if (compact) 14 else 16)
        col.addView(line1, l1)
        if (!(compact && greeting != null)) {
            val line2 = ShimmerText(ctx, c.accent, if (c.dark) 0xFFFFD9B8.toInt() else 0xFFE9A07A.toInt())
            line2.text = if (isPrivate) "follow you." else "quietly."
            line2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 30f else 42f)
            line2.typeface = Fonts.serifItalic
            line2.includeFontPadding = false
            col.addView(line2, LinearLayout.LayoutParams(WRAP, WRAP))
        }

        val subText = if (isPrivate) "No history is saved for this tab, and its data is wiped when the last private tab closes."
        else when (workspace.kind) {
            Workspace.RESEARCH -> "Research profile. Tabs, notes and highlights for this topic stay together, on this device."
            Workspace.TEMPORARY -> "Temporary session. Everything here is erased when you leave."
            else -> "Fast, private, and free of clutter."
        }
        val sub = makeText(ctx, subText, 16f, c.muted, Fonts.sans)
        sub.setLineSpacing(0f, 1.2f)
        val sublp = LinearLayout.LayoutParams(MATCH, WRAP)
        sublp.topMargin = ctx.dp(10)
        col.addView(sub, sublp)

        if (!isPrivate) {
            // protection bento
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            val protect = statCard(ctx, "Trackers blocked", blocked, "and counting", true)
            val secure = statCard(ctx, "Secure page loads", upgraded, "over HTTPS", false)
            val la = LinearLayout.LayoutParams(0, WRAP, 1f)
            la.rightMargin = ctx.dp(6)
            val lb = LinearLayout.LayoutParams(0, WRAP, 1f)
            lb.leftMargin = ctx.dp(6)
            row.addView(protect, la)
            row.addView(secure, lb)
            val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
            rlp.topMargin = ctx.dp(18)
            if (cfg.stats) col.addView(row, rlp)

            if (reminderCount > 0) {
                val rem = BentoCard(ctx, c)
                rem.addView(makeTag(ctx, "Permission reminder", c.accent))
                val t = makeText(
                    ctx,
                    if (reminderCount == 1) "1 site can still use your camera, microphone or location." else "$reminderCount sites can still use your camera, microphone or location.",
                    18f, c.ink, Fonts.serif
                )
                t.setLineSpacing(0f, 1.1f)
                val tlp = LinearLayout.LayoutParams(MATCH, WRAP)
                tlp.topMargin = ctx.dp(10)
                rem.addView(t, tlp)
                val go = makeText(ctx, "Review", 14f, c.accent, Fonts.sansMedium)
                val glp = LinearLayout.LayoutParams(WRAP, WRAP)
                glp.topMargin = ctx.dp(10)
                rem.addView(go, glp)
                rem.pressable(rem.background, withAlpha(c.ink, 0x14), ctx.dpf(18f))
                rem.setOnClickListener { a.onReminders() }
                val remlp = LinearLayout.LayoutParams(MATCH, WRAP)
                remlp.topMargin = ctx.dp(12)
                col.addView(rem, remlp)
            }

            // quick actions: four compact tiles in one row
            val acts = LinearLayout(ctx)
            acts.orientation = LinearLayout.HORIZONTAL
            val tiles = listOf(
                Triple(Icon.NOTE, "Research") { a.onResearch() },
                Triple(Icon.DOWNLOAD, "Downloads") { a.onDownloads() },
                Triple(Icon.PRIVATE, "Private tab") { a.onNewPrivate() },
                Triple(Icon.COMMAND, "Command bar") { a.onCommand() }
            )
            for ((i, tile) in tiles.withIndex()) {
                val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
                if (i > 0) lp.leftMargin = ctx.dp(8)
                acts.addView(actionTile(ctx, tile.first, tile.second, tile.third), lp)
            }
            val alp = LinearLayout.LayoutParams(MATCH, WRAP)
            alp.topMargin = ctx.dp(12)
            col.addView(acts, alp)

            // bookmarks
            val bm = BentoCard(ctx, c)
            bm.addView(makeTag(ctx, "Bookmarks · ${workspace.name}", c.muted))
            if (bookmarks.isEmpty()) {
                val empty = makeText(ctx, "Bookmark a page from the menu and it will live here.", 15f, c.muted, Fonts.sans)
                empty.setLineSpacing(0f, 1.2f)
                val elp = LinearLayout.LayoutParams(MATCH, WRAP)
                elp.topMargin = ctx.dp(10)
                bm.addView(empty, elp)
            } else {
                for (b2 in bookmarks.take(8)) {
                    val r = bookmarkRow(ctx, b2)
                    val rlp = LinearLayout.LayoutParams(MATCH, ctx.dp(52))
                    rlp.topMargin = ctx.dp(6)
                    bm.addView(r, rlp)
                }
            }
            val bmlp = LinearLayout.LayoutParams(MATCH, WRAP)
            bmlp.topMargin = ctx.dp(12)
            if (cfg.bookmarks) col.addView(bm, bmlp)
        }

        val foot = makeTag(ctx, "Facet v$version", c.muted)
        foot.alpha = 0.7f
        val footLp = LinearLayout.LayoutParams(WRAP, WRAP)
        footLp.topMargin = ctx.dp(12)
        col.addView(foot, footLp)

        for (i in 0 until col.childCount) {
            val v = col.getChildAt(i)
            v.alpha = 0f
            v.translationY = ctx.dpf(30f)
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(80L * i + 80L).setDuration(560L)
                .setInterpolator(DecelerateInterpolator(2.4f)).start()
        }
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, MATCH))
        addView(statusScrim, LayoutParams(MATCH, 0, Gravity.TOP))
    }

    private fun greetingFor(name: String): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val part = when { hour < 5 -> "Good night"; hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }
        return "$part, $name"
    }

    private fun clockBlock(ctx: Context): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val time = android.widget.TextClock(ctx)
        when (cfg.hourFormat) {
            1 -> { time.format12Hour = "h:mm"; time.format24Hour = "h:mm" }
            2 -> { time.format12Hour = "HH:mm"; time.format24Hour = "HH:mm" }
            else -> { time.format12Hour = "h:mm"; time.format24Hour = "HH:mm" }
        }
        time.setTextColor(c.ink)
        time.typeface = Fonts.serif
        time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 64f)
        time.includeFontPadding = false
        box.addView(time, LinearLayout.LayoutParams(WRAP, WRAP))
        if (cfg.date) {
            val date = android.widget.TextClock(ctx)
            date.format12Hour = "EEEE, d MMMM"
            date.format24Hour = "EEEE, d MMMM"
            date.setTextColor(c.muted)
            date.typeface = Fonts.sans
            date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            date.includeFontPadding = false
            val dlp = LinearLayout.LayoutParams(WRAP, WRAP)
            dlp.topMargin = ctx.dp(4)
            box.addView(date, dlp)
        }
        if (cfg.weather) {
            val w = makeText(ctx, "", 15f, c.muted, Fonts.sans)
            val wlp = LinearLayout.LayoutParams(WRAP, WRAP)
            wlp.topMargin = ctx.dp(6)
            box.addView(w, wlp)
            fun show(i: WeatherInfo?) {
                w.text = if (i == null) "" else "${i.temp}°${if (i.fahrenheit) "F" else "C"} · ${i.words} · ${i.place}"
            }
            show(Weather.cached(prefs))
            Weather.refresh(prefs, cfg) { show(it) }
        }
        return box
    }

    private fun half(left: Boolean): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
        if (left) lp.rightMargin = context.dp(6) else lp.leftMargin = context.dp(6)
        return lp
    }

    private fun chip(ctx: Context, icon: Icon, label: String, strong: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(14), ctx.dp(8))
        val fg = if (strong) 0xFFFFFFFF.toInt() else c.ink
        val bg: android.graphics.drawable.Drawable = if (strong) shape(c.accent, ctx.dpf(22f))
        else shape(withAlpha(c.surface, 0xE6), ctx.dpf(22f), c.line, ctx.dp(1))
        row.pressable(bg, withAlpha(fg, 0x33), ctx.dpf(22f))
        row.addView(IconView(ctx, icon, fg, 17), LinearLayout.LayoutParams(ctx.dp(17), ctx.dp(17)))
        val t = makeText(ctx, label, 14f, fg, Fonts.sansMedium)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        t.maxWidth = ctx.dp(160)
        val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
        tlp.leftMargin = ctx.dp(8)
        row.addView(t, tlp)
        row.setOnClickListener { onClick() }
        row.pressScale(0.95f)
        return row
    }

    /** A small square-ish tile: icon above a short label, sized to sit four in a row. */
    private fun actionTile(ctx: Context, icon: Icon, title: String, onClick: () -> Unit): View {
        val card = BentoCard(ctx, c)
        card.setPadding(ctx.dp(6), ctx.dp(14), ctx.dp(6), ctx.dp(12))
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.pressable(card.background, withAlpha(c.ink, 0x14), ctx.dpf(18f))
        card.setOnClickListener { onClick() }
        card.pressScale(0.94f)
        card.contentDescription = title
        card.addView(IconView(ctx, icon, c.accent, 24), LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)))
        val t = makeText(ctx, title, 13f, c.ink, Fonts.sansMedium)
        t.gravity = Gravity.CENTER
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        val tlp = LinearLayout.LayoutParams(MATCH, WRAP)
        tlp.topMargin = ctx.dp(8)
        card.addView(t, tlp)
        return card
    }

    private fun statCard(ctx: Context, label: String, value: Long, caption: String, pulse: Boolean): BentoCard {
        val card = BentoCard(ctx, c)
        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(makeTag(ctx, label, c.muted), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (pulse) top.addView(PulseShield(ctx, c), LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)))
        card.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        val num = makeText(ctx, "0", 38f, c.ink, Fonts.serif)
        countUp(num, value)
        val nlp = LinearLayout.LayoutParams(WRAP, WRAP)
        nlp.topMargin = ctx.dp(10)
        card.addView(num, nlp)
        val cap = makeText(ctx, caption, 13f, c.muted, Fonts.sans)
        val clp = LinearLayout.LayoutParams(WRAP, WRAP)
        clp.topMargin = ctx.dp(4)
        card.addView(cap, clp)
        return card
    }

    private fun countUp(tv: TextView, target: Long) {
        if (target <= 0L) return
        val nf = NumberFormat.getIntegerInstance()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 1100L
        anim.startDelay = 500L
        anim.interpolator = DecelerateInterpolator(2f)
        anim.addUpdateListener { tv.text = nf.format((target * (it.animatedValue as Float)).toLong()) }
        anim.start()
    }

    private fun bookmarkRow(ctx: Context, b: Bookmark): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.pressable(null, withAlpha(c.ink, 0x1A), ctx.dpf(12f))
        row.setOnClickListener { a.onOpen(b.url) }
        row.pressScale(0.96f)
        row.setOnLongClickListener { a.onBookmarkLong(b); true }

        val label = if (b.title.isNotBlank()) b.title else Urls.host(b.url)
        val letter = makeText(ctx, label.take(1).uppercase(), 16f, c.accent, Fonts.serifText)
        letter.gravity = Gravity.CENTER
        letter.background = shape(withAlpha(c.accent, 0x24), ctx.dpf(18f))
        val llp = LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36))
        llp.leftMargin = ctx.dp(4)
        row.addView(letter, llp)

        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        val t1 = makeText(ctx, label, 15f, c.ink, Fonts.sansMedium)
        t1.maxLines = 1
        t1.ellipsize = TextUtils.TruncateAt.END
        val t2 = makeText(ctx, Urls.host(b.url), 12f, c.muted, Fonts.sans)
        t2.maxLines = 1
        t2.ellipsize = TextUtils.TruncateAt.END
        texts.addView(t1)
        val t2lp = LinearLayout.LayoutParams(WRAP, WRAP)
        t2lp.topMargin = ctx.dp(3)
        texts.addView(t2, t2lp)
        val tlp = LinearLayout.LayoutParams(0, WRAP, 1f)
        tlp.leftMargin = ctx.dp(12)
        row.addView(texts, tlp)
        return row
    }
}
