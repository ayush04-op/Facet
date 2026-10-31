package app.facet.browser

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** The settings front page: a search box and a flat list of sections, like a system settings app. */
class SettingsView(
    private val ctx: Context,
    private val host: SettingsHost
) : LinearLayout(ctx) {

    private class Entry(val title: String, val sub: String?, val keywords: String, val open: () -> Unit)

    private val c = host.colors
    private val prefs = host.prefs
    private val body = LinearLayout(ctx)
    private val search = EditText(ctx)

    init {
        orientation = VERTICAL
        setBackgroundColor(c.bg)
        isClickable = true

        val help = FrameLayout(ctx)
        help.pressable(null, withAlpha(c.ink, 0x22), ctx.dpf(24f))
        help.addView(IconView(ctx, Icon.INFO, c.ink, 24), FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
        help.setOnClickListener { host.push(aboutPage(host)) }
        addView(pageHeader(ctx, c, "Settings", { host.back() }, help), LayoutParams(MATCH, WRAP))

        val pill = LinearLayout(ctx)
        pill.orientation = HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = shape(c.surface, ctx.dpf(30f))
        pill.setPadding(ctx.dp(22), 0, ctx.dp(18), 0)
        pill.addView(IconView(ctx, Icon.SEARCH, c.muted, 22), LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)))
        search.hint = "Search settings"
        search.setHintTextColor(c.muted)
        search.setTextColor(c.ink)
        search.typeface = Fonts.sans
        search.textSize = 17f
        search.background = null
        search.isSingleLine = true
        search.setPadding(ctx.dp(16), 0, 0, 0)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { rebuild() }
        })
        pill.addView(search, LinearLayout.LayoutParams(0, MATCH, 1f))
        val plp = LayoutParams(MATCH, ctx.dp(58))
        plp.setMargins(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(6))
        addView(pill, plp)

        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        body.orientation = VERTICAL
        body.setPadding(ctx.dp(14), ctx.dp(4), ctx.dp(14), ctx.dp(40))
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, 0, 1f))
        rebuild()
    }

    private fun entries(): List<Entry> {
        val ws = WorkspaceStore(prefs).current()
        val reminders = SitePermStore(prefs).reminders().size
        val avail = prefs.availableUpdate
        val themeName = when (prefs.theme) { 1 -> "Paper (light)"; 2 -> "Paper Dark"; else -> "Match system" }
        return listOf(
            Entry("Software update", if (avail.isNotEmpty()) "$avail is available" else "Version ${host.version}", "update upgrade version device status") { host.openUpdates() },
            Entry("Workspaces", "${ws.name} · personal, work, research and temporary sessions", "profiles workspace profile temporary session separate") { host.openWorkspaces() },
            Entry("Privacy and security", "Tracker blocking, cookies, HTTPS-only", "tracker cookie https safe browsing javascript strict protection clear data history") { host.push(privacyPage(host)) },
            Entry("Site permissions", if (reminders > 0) "$reminders ${if (reminders == 1) "site has" else "sites have"} had access for a while" else "Camera, microphone, location", "camera microphone location notifications permission revoke reminder") { host.openSitePermissions() },
            Entry("Home page", "Wallpaper, clock, weather, glass", "home wallpaper clock weather glass blur accent colour customise background greeting") { host.openHome() },
            Entry("Tabs and groups", if (prefs.tabListMode == 1) "Vertical list" else "Grid", "tabs groups vertical list grid restore session crash") { host.push(tabsPage(host)) },
            Entry("Downloads", "Download manager and Wi-Fi only", "download file wifi manager") { host.openDownloads() },
            Entry("Extensions", "${host.extensions.installed.size} installed · Firefox and Chrome extensions", "extensions add-ons addons chrome firefox ublock crx xpi") { host.openExtensions() },
            Entry("Search engine", Urls.engine(prefs.searchEngine).name, "search engine duckduckgo google brave startpage ecosia") { host.push(enginePage(host)) },
            Entry("Appearance", themeName, "theme dark light paper font appearance") { host.push(appearancePage(host)) },
            Entry("Research", "Notes, saved pages and highlights", "research notes highlights saved pages topic export") { host.openResearch() },
            Entry("AI assistant", if (AiConfig(prefs).enabled) "On" else "Off", "ai assistant summarize claude openai api key") { host.openAiSettings() },
            Entry("About Facet", "Version ${host.version}", "about version license credits claude engine") { host.push(aboutPage(host)) }
        )
    }

    private fun rebuild() {
        body.removeAllViews()
        val b = PageBuilder(ctx, c, body)
        val q = search.text.toString().trim().lowercase()
        val all = entries()
        if (q.isNotEmpty()) {
            val hits = all.filter { (it.title + " " + it.keywords).lowercase().contains(q) }
            if (hits.isEmpty()) b.note("Nothing matches \"$q\".")
            for (e in hits) b.row(e.title, e.sub) { e.open() }
            b.flush()
            return
        }
        for (e in all.take(2)) b.row(e.title, e.sub) { e.open() }
        b.section("Privacy")
        for (e in all.subList(2, 4)) b.row(e.title, e.sub) { e.open() }
        b.section("Advanced")
        for (e in all.drop(4)) b.row(e.title, e.sub) { e.open() }
        b.flush()
    }

    /** Called when a sub-page closes, so the summaries under each row are up to date. */
    fun refresh() = rebuild()
}
