package app.facet.browser

import android.content.Context
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
    private val onClearData: () -> Unit,
    private val onOpenUpdates: () -> Unit
) : LinearLayout(ctx) {

    private val kit = RowKit(ctx, c)
    private val body = LinearLayout(ctx)

    init {
        orientation = VERTICAL
        setBackgroundColor(c.bg)
        isClickable = true
        addView(kit.header("Settings", onBack), LayoutParams(MATCH, WRAP))
        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        body.orientation = VERTICAL
        body.setPadding(ctx.dp(20), ctx.dp(4), ctx.dp(20), ctx.dp(32))
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, 0, 1f))
        rebuild()
    }

    private fun put(g: LinearLayout) {
        body.addView(g, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    fun rebuild() {
        body.removeAllViews()
        val nf = NumberFormat.getIntegerInstance()

        body.addView(kit.section("Software"))
        val sw = kit.group()
        val avail = prefs.availableUpdate
        kit.add(sw, kit.value(Icon.DOWNLOAD, "Software update",
            if (avail.isNotEmpty()) "$avail available" else "Version $version",
            "Updates, this build and device status") { onOpenUpdates() })
        put(sw)

        body.addView(kit.section("Privacy and security"))
        val priv = kit.group()
        kit.add(priv, kit.toggle(Icon.SHIELD, "Block ads and trackers", "Ads, analytics, social trackers, fingerprinters and cryptominers.", prefs.blockTrackers) {
            prefs.blockTrackers = it; onToggled()
        })
        kit.add(priv, kit.toggle(Icon.PRIVATE, "Isolate third-party cookies", "Cookies from other sites stay locked to the site that set them.", prefs.blockThirdPartyCookies) {
            prefs.blockThirdPartyCookies = it; onToggled()
        })
        kit.add(priv, kit.toggle(Icon.LOCK, "HTTPS-only mode", "Only opens secure pages, and warns before using an insecure one.", prefs.httpsUpgrade) {
            prefs.httpsUpgrade = it; onToggled()
        })
        kit.add(priv, kit.toggle(Icon.INFO, "Safe Browsing", "Warns about dangerous sites. Checks hash prefixes with Google.", prefs.safeBrowsing) {
            prefs.safeBrowsing = it; onToggled()
        })
        kit.add(priv, kit.toggle(Icon.LINK, "JavaScript", "Turning this off blocks most trackers but breaks many sites.", prefs.javascript) {
            prefs.javascript = it; onToggled()
        })
        kit.add(priv, kit.info("Passkeys", "Built in (Android 14+)"))
        put(priv)

        body.addView(kit.section("Browsing"))
        val br = kit.group()
        kit.add(br, kit.value(Icon.SEARCH, "Search engine", Urls.engine(prefs.searchEngine).name) { onPickEngine() })
        kit.add(br, kit.toggle(Icon.TABS, "Restore tabs on launch", "Private tabs are never saved.", prefs.restoreTabs) {
            prefs.restoreTabs = it; onToggled()
        })
        put(br)

        body.addView(kit.section("Appearance"))
        val ap = kit.group()
        val themeName = when (prefs.theme) { 1 -> "Paper (light)"; 2 -> "Paper Dark"; else -> "Match system" }
        kit.add(ap, kit.value(Icon.PHONE, "Theme", themeName) { onPickTheme() })
        put(ap)

        body.addView(kit.section("Data"))
        val data = kit.group()
        kit.add(data, kit.value(Icon.TRASH, "Clear browsing data", "Cookies, cache, site data") { onClearData() })
        put(data)

        body.addView(kit.section("Activity"))
        val st = kit.group()
        kit.add(st, kit.info("Trackers blocked", nf.format(prefs.trackersBlocked + pendingBlocked())))
        kit.add(st, kit.info("Secure page loads", nf.format(prefs.httpsUpgraded)))
        put(st)

        body.addView(kit.section("About"))
        val ab = kit.group()
        kit.add(ab, kit.info("Version", version))
        kit.add(ab, kit.info("Engine", DeviceInfo.engineVersion(ctx)))
        kit.add(ab, kit.info("License", "MIT"))
        put(ab)
        body.addView(kit.note(
            "Facet collects nothing: no accounts, no analytics, no telemetry. Powered by Mozilla's GeckoView engine, bundled inside the app."
        ))
        body.addView(kit.note("Made by Ayush, written with Claude, an AI model from Anthropic."))
    }
}
