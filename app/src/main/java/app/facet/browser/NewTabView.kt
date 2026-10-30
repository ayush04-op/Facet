package app.facet.browser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.NumberFormat

class NewTabView(
    ctx: Context,
    private val c: Colors,
    isPrivate: Boolean,
    blocked: Long,
    upgraded: Long,
    version: String,
    bookmarks: List<Bookmark>,
    private val onOpen: (String) -> Unit,
    private val onBookmarkLong: (Bookmark) -> Unit
) : FrameLayout(ctx) {

    init {
        setBackgroundColor(c.bg)
        val grid = GridBackground(ctx, c)
        grid.alpha = 0f
        grid.animate().alpha(1f).setDuration(700L).start()
        addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))

        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ctx.dp(20), ctx.dp(32), ctx.dp(20), ctx.dp(32))

        // Tag line
        val tagRow = LinearLayout(ctx)
        tagRow.orientation = LinearLayout.HORIZONTAL
        tagRow.gravity = Gravity.CENTER_VERTICAL
        val dot = View(ctx)
        dot.background = shape(c.accent, ctx.dpf(2f))
        tagRow.addView(dot, LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)))
        val tag = makeTag(ctx, if (isPrivate) "Facet / Private tab" else "Facet / New tab", c.muted)
        val tagLp = LinearLayout.LayoutParams(WRAP, WRAP)
        tagLp.leftMargin = ctx.dp(10)
        tagRow.addView(tag, tagLp)
        col.addView(tagRow, LinearLayout.LayoutParams(WRAP, WRAP))

        // Hero heading: serif, with one italic accent word
        val heroText = if (isPrivate) "Nothing here will follow you." else "A quieter way to browse."
        val accentWord = if (isPrivate) "follow" else "quieter"
        val sp = SpannableString(heroText)
        val start = heroText.indexOf(accentWord)
        if (start >= 0) {
            sp.setSpan(FaceSpan(Fonts.serifItalic), start, start + accentWord.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(c.accent), start, start + accentWord.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val hero = makeText(ctx, sp, 40f, c.ink, Fonts.serif)
        hero.setLineSpacing(0f, 1.05f)
        val heroLp = LinearLayout.LayoutParams(MATCH, WRAP)
        heroLp.topMargin = ctx.dp(22)
        col.addView(hero, heroLp)

        val subText = if (isPrivate) {
            "No history is saved for this tab, and its cache is wiped when the last private tab closes."
        } else {
            "Fast, private, and free of clutter."
        }
        val sub = makeText(ctx, subText, 16f, c.muted, Fonts.sans)
        sub.setLineSpacing(0f, 1.2f)
        val subLp = LinearLayout.LayoutParams(MATCH, WRAP)
        subLp.topMargin = ctx.dp(12)
        col.addView(sub, subLp)

        if (!isPrivate) {
            // Row of two stat cards
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            val gap = ctx.dp(12)
            val a = statCard(ctx, "Trackers blocked", blocked, "since install")
            val b = statCard(ctx, "Upgraded to HTTPS", upgraded, "page loads")
            val la = LinearLayout.LayoutParams(0, WRAP, 1f)
            la.rightMargin = gap / 2
            val lb = LinearLayout.LayoutParams(0, WRAP, 1f)
            lb.leftMargin = gap / 2
            row.addView(a, la)
            row.addView(b, lb)
            val rowLp = LinearLayout.LayoutParams(MATCH, WRAP)
            rowLp.topMargin = ctx.dp(30)
            col.addView(row, rowLp)

            // Bookmarks card
            val bm = BentoCard(ctx, c)
            bm.addView(makeTag(ctx, "Bookmarks", c.muted))
            if (bookmarks.isEmpty()) {
                val empty = makeText(ctx, "Bookmark a page from the menu and it will live here.", 15f, c.muted, Fonts.sans)
                empty.setLineSpacing(0f, 1.2f)
                val elp = LinearLayout.LayoutParams(MATCH, WRAP)
                elp.topMargin = ctx.dp(10)
                bm.addView(empty, elp)
            } else {
                val shown = if (bookmarks.size > 8) bookmarks.subList(0, 8) else bookmarks
                for (b2 in shown) {
                    val r = bookmarkRow(ctx, b2)
                    val rlp = LinearLayout.LayoutParams(MATCH, ctx.dp(52))
                    rlp.topMargin = ctx.dp(6)
                    bm.addView(r, rlp)
                }
            }
            val bmLp = LinearLayout.LayoutParams(MATCH, WRAP)
            bmLp.topMargin = ctx.dp(12)
            col.addView(bm, bmLp)

            // Principles card
            val pr = BentoCard(ctx, c)
            pr.addView(makeTag(ctx, "Principles", c.muted))
            val line = makeText(ctx, "No accounts. No analytics. No tracking.", 22f, c.ink, Fonts.serif)
            line.setLineSpacing(0f, 1.05f)
            val llp = LinearLayout.LayoutParams(MATCH, WRAP)
            llp.topMargin = ctx.dp(10)
            pr.addView(line, llp)
            val prLp = LinearLayout.LayoutParams(MATCH, WRAP)
            prLp.topMargin = ctx.dp(12)
            col.addView(pr, prLp)
        }

        val foot = makeTag(ctx, "Facet v$version", c.muted)
        foot.alpha = 0.7f
        val footLp = LinearLayout.LayoutParams(WRAP, WRAP)
        footLp.topMargin = ctx.dp(28)
        col.addView(foot, footLp)

        for (i in 0 until col.childCount) {
            val v = col.getChildAt(i)
            v.alpha = 0f
            v.translationY = ctx.dpf(26f)
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(70L * i + 60L).setDuration(480L)
                .setInterpolator(DecelerateInterpolator(2.2f)).start()
        }
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun statCard(ctx: Context, label: String, value: Long, caption: String): BentoCard {
        val card = BentoCard(ctx, c)
        card.addView(makeTag(ctx, label, c.muted))
        val num = makeText(ctx, "0", 38f, c.ink, Fonts.serif)
        countUp(num, value)
        val nlp = LinearLayout.LayoutParams(WRAP, WRAP)
        nlp.topMargin = ctx.dp(14)
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
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 900L
        a.startDelay = 380L
        a.interpolator = DecelerateInterpolator(2f)
        a.addUpdateListener { tv.text = nf.format((target * (it.animatedValue as Float)).toLong()) }
        a.start()
    }

    private fun bookmarkRow(ctx: Context, b: Bookmark): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.pressable(null, withAlpha(c.ink, 0x1A), ctx.dpf(12f))
        row.setOnClickListener { onOpen(b.url) }
        row.pressScale(0.96f)
        row.setOnLongClickListener { onBookmarkLong(b); true }

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
