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
    private val a: HomeActions
) : FrameLayout(ctx) {

    init {
        setBackgroundColor(c.bg)
        val aurora = AuroraView(ctx, c)
        aurora.alpha = 0f
        aurora.animate().alpha(1f).setDuration(1200L).start()
        addView(aurora, LayoutParams(MATCH, MATCH))
        val grid = GridBackground(ctx, c)
        grid.alpha = 0f
        grid.animate().alpha(0.6f).setDuration(900L).start()
        addView(grid, LayoutParams(MATCH, MATCH))

        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ctx.dp(20), ctx.dp(28), ctx.dp(20), ctx.dp(36))

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

        // headline: two lines, the second shimmers
        val line1 = makeText(ctx, if (isPrivate) "Nothing here will" else "Browse,", 46f, c.ink, Fonts.serif)
        line1.setLineSpacing(0f, 1.0f)
        val l1 = LinearLayout.LayoutParams(WRAP, WRAP)
        l1.topMargin = ctx.dp(26)
        col.addView(line1, l1)
        val line2 = ShimmerText(ctx, c.accent, if (c.dark) 0xFFFFD9B8.toInt() else 0xFFE9A07A.toInt())
        line2.text = if (isPrivate) "follow you." else "quietly."
        line2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 46f)
        line2.typeface = Fonts.serifItalic
        line2.includeFontPadding = false
        col.addView(line2, LinearLayout.LayoutParams(WRAP, WRAP))

        val subText = if (isPrivate) "No history is saved for this tab, and its data is wiped when the last private tab closes."
        else when (workspace.kind) {
            Workspace.RESEARCH -> "Research workspace. Tabs, notes and highlights for this topic stay together, on this device."
            Workspace.TEMPORARY -> "Temporary session. Everything here is erased when you leave."
            else -> "Fast, private, and free of clutter."
        }
        val sub = makeText(ctx, subText, 16f, c.muted, Fonts.sans)
        sub.setLineSpacing(0f, 1.2f)
        val sublp = LinearLayout.LayoutParams(MATCH, WRAP)
        sublp.topMargin = ctx.dp(14)
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
            rlp.topMargin = ctx.dp(28)
            col.addView(row, rlp)

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

            // quick actions
            val acts = LinearLayout(ctx)
            acts.orientation = LinearLayout.VERTICAL
            val r1 = LinearLayout(ctx)
            r1.orientation = LinearLayout.HORIZONTAL
            r1.addView(action(ctx, Icon.NOTE, "Research", "Notes and highlights") { a.onResearch() }, half(true))
            r1.addView(action(ctx, Icon.DOWNLOAD, "Downloads", "Every file in one place") { a.onDownloads() }, half(false))
            acts.addView(r1, LinearLayout.LayoutParams(MATCH, WRAP))
            val r2 = LinearLayout(ctx)
            r2.orientation = LinearLayout.HORIZONTAL
            r2.addView(action(ctx, Icon.PRIVATE, "Private tab", "Leaves no trace") { a.onNewPrivate() }, half(true))
            r2.addView(action(ctx, Icon.COMMAND, "Command bar", "Tabs, pages, settings") { a.onCommand() }, half(false))
            val r2lp = LinearLayout.LayoutParams(MATCH, WRAP)
            r2lp.topMargin = ctx.dp(12)
            acts.addView(r2, r2lp)
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
            col.addView(bm, bmlp)
        }

        val foot = makeTag(ctx, "Facet v$version", c.muted)
        foot.alpha = 0.7f
        val footLp = LinearLayout.LayoutParams(WRAP, WRAP)
        footLp.topMargin = ctx.dp(28)
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
        val bg = if (strong) shape(c.accent, ctx.dpf(22f)) else shape(withAlpha(c.surface, 0xE6), ctx.dpf(22f), c.line, ctx.dp(1))
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

    private fun action(ctx: Context, icon: Icon, title: String, sub: String, onClick: () -> Unit): View {
        val card = BentoCard(ctx, c)
        card.pressable(card.background, withAlpha(c.ink, 0x14), ctx.dpf(18f))
        card.setOnClickListener { onClick() }
        card.pressScale(0.96f)
        card.addView(IconView(ctx, icon, c.accent, 24), LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)))
        val t = makeText(ctx, title, 17f, c.ink, Fonts.sansMedium)
        val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
        tlp.topMargin = ctx.dp(14)
        card.addView(t, tlp)
        val s = makeText(ctx, sub, 12.5f, c.muted, Fonts.sans)
        val slp = LinearLayout.LayoutParams(WRAP, WRAP)
        slp.topMargin = ctx.dp(3)
        card.addView(s, slp)
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
