package app.facet.browser

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.NumberFormat

class SettingsView(
    private val ctx: Context,
    private val c: Colors,
    private val prefs: Prefs,
    private val version: String,
    private val pendingBlocked: () -> Long,
    private val onBack: () -> Unit,
    private val onToggled: () -> Unit,
    private val onPickEngine: () -> Unit,
    private val onPickTheme: () -> Unit,
    private val onClearData: () -> Unit
) : LinearLayout(ctx) {

    private val body = LinearLayout(ctx)

    init {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(c.bg)
        isClickable = true

        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(ctx.dp(8), ctx.dp(12), ctx.dp(20), ctx.dp(4))
        val back = FrameLayout(ctx)
        back.pressable(null, withAlpha(c.ink, 0x22), ctx.dpf(24f))
        back.addView(IconView(ctx, Icon.BACK, c.ink, 24), FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
        back.setOnClickListener { onBack() }
        header.addView(back, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
        val title = makeText(ctx, "Settings", 32f, c.ink, Fonts.serif)
        val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
        tlp.leftMargin = ctx.dp(6)
        header.addView(title, tlp)
        addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(32))
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        rebuild()
    }

    fun rebuild() {
        body.removeAllViews()
        val nf = NumberFormat.getIntegerInstance()

        section("Privacy")
        toggle("Block ads and trackers", "Strict tracking protection: ads, analytics, social trackers, fingerprinters and cryptominers.", prefs.blockTrackers) {
            prefs.blockTrackers = it; onToggled()
        }
        toggle("Isolate third-party cookies", "Cookies from other sites stay locked to the site that set them.", prefs.blockThirdPartyCookies) {
            prefs.blockThirdPartyCookies = it; onToggled()
        }
        toggle("HTTPS-only mode", "Only opens secure pages, and warns before using an insecure one.", prefs.httpsUpgrade) {
            prefs.httpsUpgrade = it; onToggled()
        }
        toggle("Safe Browsing", "Warns about dangerous sites. Checks hash prefixes with Google.", prefs.safeBrowsing) {
            prefs.safeBrowsing = it; onToggled()
        }
        toggle("JavaScript", "Turning this off blocks most trackers but breaks many sites.", prefs.javascript) {
            prefs.javascript = it; onToggled()
        }

        section("Browsing")
        value("Search engine", Urls.engine(prefs.searchEngine).name) { onPickEngine() }
        toggle("Restore tabs on launch", "Reopen the tabs you had open. Private tabs are never saved.", prefs.restoreTabs) {
            prefs.restoreTabs = it; onToggled()
        }

        section("Appearance")
        val themeName = when (prefs.theme) { 1 -> "Paper (light)"; 2 -> "Paper Dark"; else -> "Match system" }
        value("Theme", themeName) { onPickTheme() }

        section("Data")
        value("Clear browsing data", "Cookies, cache, site data") { onClearData() }

        section("Stats")
        val blocked = prefs.trackersBlocked + pendingBlocked()
        stat("Trackers blocked", nf.format(blocked))
        stat("Secure page loads", nf.format(prefs.httpsUpgraded))

        section("About")
        stat("Version", version)
        val note = makeText(
            ctx,
            "Facet collects nothing: no accounts, no analytics, no telemetry. Powered by Mozilla's GeckoView engine, bundled inside the app.",
            13f, c.muted, Fonts.sans
        )
        note.setLineSpacing(0f, 1.2f)
        val nlp = LinearLayout.LayoutParams(MATCH, WRAP)
        nlp.topMargin = ctx.dp(10)
        body.addView(note, nlp)
    }

    private fun section(name: String) {
        val t = makeTag(ctx, name, c.accent)
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.topMargin = ctx.dp(26)
        lp.bottomMargin = ctx.dp(6)
        body.addView(t, lp)
    }

    private fun rowBase(): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dp(2), ctx.dp(12), ctx.dp(2), ctx.dp(12))
        return row
    }

    private fun divider() {
        val d = View(ctx)
        d.setBackgroundColor(c.line)
        body.addView(d, LinearLayout.LayoutParams(MATCH, ctx.dp(1)))
    }

    private fun toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = rowBase()
        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(makeText(ctx, title, 16f, c.ink, Fonts.sansMedium))
        val sub = makeText(ctx, subtitle, 13f, c.muted, Fonts.sans)
        sub.setLineSpacing(0f, 1.15f)
        val slp = LinearLayout.LayoutParams(WRAP, WRAP)
        slp.topMargin = ctx.dp(4)
        texts.addView(sub, slp)
        val tlp = LinearLayout.LayoutParams(0, WRAP, 1f)
        tlp.rightMargin = ctx.dp(16)
        row.addView(texts, tlp)
        val sw = SwitchView(ctx, c, checked)
        sw.onChange = onChange
        row.addView(sw, LinearLayout.LayoutParams(ctx.dp(46), ctx.dp(28)))
        row.setOnClickListener { sw.performClick() }
        body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        divider()
    }

    private fun value(title: String, current: String, onClick: () -> Unit) {
        val row = rowBase()
        row.pressable(null, withAlpha(c.ink, 0x14), ctx.dpf(10f))
        row.setPadding(ctx.dp(2), ctx.dp(14), ctx.dp(2), ctx.dp(14))
        row.setOnClickListener { onClick() }
        row.addView(makeText(ctx, title, 16f, c.ink, Fonts.sansMedium), LinearLayout.LayoutParams(0, WRAP, 1f))
        val v = makeText(ctx, current, 14f, c.muted, Fonts.sans)
        v.maxLines = 1
        v.ellipsize = TextUtils.TruncateAt.END
        row.addView(v, LinearLayout.LayoutParams(WRAP, WRAP))
        body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        divider()
    }

    private fun stat(title: String, value: String) {
        val row = rowBase()
        row.addView(makeText(ctx, title, 16f, c.ink, Fonts.sans), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(makeText(ctx, value, 18f, c.ink, Fonts.serifText), LinearLayout.LayoutParams(WRAP, WRAP))
        body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        divider()
    }
}
