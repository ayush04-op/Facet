package app.facet.browser

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Everything a settings page needs from the activity. */
interface SettingsHost {
    val context: Context
    val prefs: Prefs
    val colors: Colors
    val version: String
    fun push(v: View)
    fun back()
    fun toast(msg: String)
    fun sheet(title: String?, items: List<SheetItem>, onCancel: (() -> Unit)? = null)
    fun input(title: String, hint: String, initial: String, secret: Boolean, save: (String) -> Unit)
    fun applyEngineSettings()
    fun applyTheme(mode: Int)
    fun clearData()
    fun blockedCount(): Long
    fun openUpdates()
    fun openDownloads()
    fun openWorkspaces()
    fun openSitePermissions()
    fun openResearch()
    fun openAiSettings()
    fun openExtensions()
    fun openHome()
    fun applySystemUi()
    fun homeChanged()
    fun pickWallpaper()
    fun openUrl(url: String)
    val extensions: ExtensionManager
    fun switchWorkspace(id: String)
    fun createWorkspace(kind: Int)
    fun deleteWorkspace(id: String)
    fun renameWorkspace(id: String)
    fun openBlockedReport()
    fun reopen(page: () -> View)
}

/**
 * Chrome-style settings rows: each row is its own rounded surface, with large corners at the top and
 * bottom of a group and small ones between rows.
 */
class PageBuilder(private val ctx: Context, private val c: Colors, private val body: LinearLayout) {
    private val pending = ArrayList<View>()

    private fun rowBackground(top: Boolean, bottom: Boolean): GradientDrawable {
        val big = ctx.dpf(24f)
        val small = ctx.dpf(6f)
        val d = GradientDrawable()
        d.setColor(c.surface)
        val t = if (top) big else small
        val b = if (bottom) big else small
        d.cornerRadii = floatArrayOf(t, t, t, t, b, b, b, b)
        return d
    }

    fun flush() {
        for ((i, v) in pending.withIndex()) {
            val top = i == 0
            val bottom = i == pending.size - 1
            val bg = rowBackground(top, bottom)
            val ripple = withAlpha(c.ink, 0x14)
            if (v.hasOnClickListeners()) {
                v.pressable(bg, ripple, ctx.dpf(24f))
            } else {
                v.background = bg
            }
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = if (top) 0 else ctx.dp(3)
            body.addView(v, lp)
        }
        pending.clear()
    }

    fun section(name: String) {
        flush()
        val t = makeText(ctx, name, 15f, c.muted, Fonts.sansMedium)
        t.setPadding(ctx.dp(22), ctx.dp(26), 0, ctx.dp(10))
        body.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun textColumn(title: String, sub: String?): LinearLayout {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(makeText(ctx, title, 17f, c.ink, Fonts.sans))
        if (!sub.isNullOrEmpty()) {
            val s = makeText(ctx, sub, 14f, c.muted, Fonts.sans)
            s.setLineSpacing(0f, 1.15f)
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.topMargin = ctx.dp(4)
            col.addView(s, lp)
        }
        return col
    }

    private fun base(): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(20), ctx.dp(18))
        row.minimumHeight = ctx.dp(64)
        return row
    }

    /** A tappable row with a title and an optional second line. */
    fun row(title: String, sub: String? = null, onClick: () -> Unit) {
        val r = base()
        r.addView(textColumn(title, sub), LinearLayout.LayoutParams(0, WRAP, 1f))
        r.setOnClickListener { onClick() }
        pending.add(r)
    }

    fun toggle(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        val r = base()
        val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp.rightMargin = ctx.dp(16)
        r.addView(textColumn(title, sub), lp)
        val sw = SwitchView(ctx, c, checked)
        sw.onChange = onChange
        r.addView(sw, LinearLayout.LayoutParams(ctx.dp(46), ctx.dp(28)))
        r.setOnClickListener { sw.performClick() }
        pending.add(r)
    }

    fun info(title: String, value: String) {
        val r = base()
        r.addView(makeText(ctx, title, 16f, c.muted, Fonts.sans), LinearLayout.LayoutParams(0, WRAP, 1f))
        val v = makeText(ctx, value, 16f, c.ink, Fonts.sansMedium)
        v.gravity = Gravity.END
        v.maxLines = 2
        v.ellipsize = TextUtils.TruncateAt.END
        v.maxWidth = ctx.dp(220)
        r.addView(v, LinearLayout.LayoutParams(WRAP, WRAP))
        pending.add(r)
    }

    /** Radio-style choice row. */
    fun choice(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
        val r = base()
        r.addView(textColumn(title, sub), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (selected) r.addView(IconView(ctx, Icon.CHECK, c.accent, 22), LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)))
        r.setOnClickListener { onClick() }
        pending.add(r)
    }

    fun note(text: String) {
        flush()
        val t = makeText(ctx, text, 14f, c.muted, Fonts.sans)
        t.setLineSpacing(0f, 1.2f)
        t.setPadding(ctx.dp(22), ctx.dp(12), ctx.dp(22), 0)
        body.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    fun custom(v: View, lp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(MATCH, WRAP)) {
        flush()
        body.addView(v, lp)
    }
}

/** A scrolling page with a back arrow and a serif title. */
class PageView(
    private val ctx: Context,
    private val c: Colors,
    title: String,
    onBack: () -> Unit,
    private val build: (PageBuilder) -> Unit
) : LinearLayout(ctx) {

    private val body = LinearLayout(ctx)

    init {
        orientation = VERTICAL
        setBackgroundColor(c.bg)
        isClickable = true
        addView(pageHeader(ctx, c, title, onBack, null), LayoutParams(MATCH, WRAP))
        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        body.orientation = VERTICAL
        body.setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(40))
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, 0, 1f))
        refresh()
    }

    fun refresh() {
        body.removeAllViews()
        val b = PageBuilder(ctx, c, body)
        build(b)
        b.flush()
    }
}

fun pageHeader(ctx: Context, c: Colors, title: String, onBack: () -> Unit, trailing: View?): LinearLayout {
    val header = LinearLayout(ctx)
    header.orientation = LinearLayout.HORIZONTAL
    header.gravity = Gravity.CENTER_VERTICAL
    header.setPadding(ctx.dp(8), ctx.dp(12), ctx.dp(14), ctx.dp(4))
    val back = FrameLayout(ctx)
    back.pressable(null, withAlpha(c.ink, 0x22), ctx.dpf(24f))
    back.addView(IconView(ctx, Icon.BACK, c.ink, 24), FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
    back.setOnClickListener { onBack() }
    header.addView(back, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
    val t = makeText(ctx, title, 30f, c.ink, Fonts.serif)
    val tlp = LinearLayout.LayoutParams(0, WRAP, 1f)
    tlp.leftMargin = ctx.dp(6)
    t.maxLines = 1
    t.ellipsize = TextUtils.TruncateAt.END
    header.addView(t, tlp)
    if (trailing != null) header.addView(trailing, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
    return header
}

fun hint(ctx: Context, c: Colors, s: String): TextView {
    val t = makeText(ctx, s, 14f, c.muted, Fonts.sans)
    t.setLineSpacing(0f, 1.2f)
    return t
}
