package app.facet.browser

import android.view.View
import java.text.NumberFormat
import java.text.DateFormat
import java.util.Date

private fun page(h: SettingsHost, title: String, build: (PageBuilder) -> Unit): PageView =
    PageView(h.context, h.colors, title, { h.back() }, build)

fun privacyPage(h: SettingsHost): View {
    val p = h.prefs
    val nf = NumberFormat.getIntegerInstance()
    lateinit var view: PageView
    view = page(h, "Privacy and security") { b ->
        b.section("Tracker and cookie protection")
        b.choice("Standard", "Blocks known trackers and cross-site tracking cookies. Fewest broken sites.", p.protectionLevel == 0) {
            p.protectionLevel = 0; h.applyEngineSettings(); view.refresh()
        }
        b.choice("Strict", "Also blocks fingerprinters and cryptominers, and partitions every cookie by site. Some sites may need Standard.", p.protectionLevel == 1) {
            p.protectionLevel = 1; h.applyEngineSettings(); view.refresh()
        }
        b.choice("Custom", "Choose the switches yourself.", p.protectionLevel == 2) {
            p.protectionLevel = 2; h.applyEngineSettings(); view.refresh()
        }
        if (p.protectionLevel == 2) {
            b.toggle("Block ads and trackers", "Strict tracking protection.", p.blockTrackers) { p.blockTrackers = it; h.applyEngineSettings() }
            b.toggle("Isolate third-party cookies", "Cookies from other sites stay locked to the site that set them.", p.blockThirdPartyCookies) {
                p.blockThirdPartyCookies = it; h.applyEngineSettings()
            }
        }
        b.row("What was blocked on this page", "Trackers by site, for the page you are on") { h.openBlockedReport() }

        b.section("Connections")
        b.toggle("HTTPS-only mode", "Only opens secure pages, and warns before using an insecure one.", p.httpsUpgrade) { p.httpsUpgrade = it; h.applyEngineSettings() }
        b.toggle("Safe Browsing", "Warns about dangerous sites. Checks hash prefixes with Google.", p.safeBrowsing) { p.safeBrowsing = it; h.applyEngineSettings() }
        b.toggle("JavaScript", "Turning this off blocks most trackers but breaks many sites.", p.javascript) { p.javascript = it; h.applyEngineSettings() }

        b.section("Activity")
        b.info("Trackers blocked", nf.format(p.trackersBlocked + h.blockedCount()))
        b.info("Secure page loads", nf.format(p.httpsUpgraded))

        b.section("Data")
        b.row("Clear browsing data", "Cookies, cache, site data") { h.clearData() }
        b.row("Clear history", "${HistoryStore(p).all().size} pages") {
            HistoryStore(p).clear(); h.toast("History cleared"); view.refresh()
        }
    }
    return view
}

fun tabsPage(h: SettingsHost): View {
    val p = h.prefs
    lateinit var view: PageView
    view = page(h, "Tabs and groups") { b ->
        b.section("Tab view")
        b.choice("Grid", "Large cards, two across.", p.tabListMode == 0) { p.tabListMode = 0; view.refresh() }
        b.choice("Vertical list", "Compact rows grouped by tab group, easy to scan with many tabs.", p.tabListMode == 1) { p.tabListMode = 1; view.refresh() }
        b.section("Sessions")
        b.toggle("Restore tabs on launch", "Reopen the tabs you had open, including after a crash. Private tabs are never saved.", p.restoreTabs) { p.restoreTabs = it }
        b.note("Press and hold a tab in the tab view to put it in a group. Each workspace keeps its own tabs and groups.")
    }
    return view
}

fun enginePage(h: SettingsHost): View {
    val p = h.prefs
    lateinit var view: PageView
    view = page(h, "Search engine") { b ->
        for (e in Urls.engines) {
            b.choice(e.name, null, e.id == p.searchEngine) { p.searchEngine = e.id; view.refresh() }
        }
    }
    return view
}

fun appearancePage(h: SettingsHost): View {
    val p = h.prefs
    return page(h, "Appearance") { b ->
        b.section("Theme")
        b.choice("Match system", null, p.theme == 0) { h.applyTheme(0) }
        b.choice("Paper (light)", null, p.theme == 1) { h.applyTheme(1) }
        b.choice("Paper Dark", null, p.theme == 2) { h.applyTheme(2) }
        b.note("Facet always uses Prism Serif for headings and Prism Sans for text.")
    }
}

fun aboutPage(h: SettingsHost): View {
    return page(h, "About Facet") { b ->
        b.info("Version", h.version)
        b.info("Engine", DeviceInfo.engineVersion(h.context))
        b.info("License", "MIT")
        b.note("Facet collects nothing: no accounts, no analytics, no telemetry. Powered by Mozilla's GeckoView engine, bundled inside the app.")
        b.note("Made by Ayush, written with Claude, an AI model from Anthropic.")
    }
}

// ---------------------------------------------------------------- site permissions

fun sitePermissionsPage(h: SettingsHost): View {
    val store = SitePermStore(h.prefs)
    lateinit var view: PageView
    view = page(h, "Site permissions") { b ->
        val reminders = store.reminders()
        if (reminders.isNotEmpty()) {
            b.section("Worth a look")
            val sites = reminders.map { it.host }.distinct()
            b.row(
                "${sites.size} ${if (sites.size == 1) "site has" else "sites have"} had access for over a month",
                sites.take(3).joinToString(", ") + if (sites.size > 3) " and more" else ""
            ) { h.push(permissionRemindersPage(h)) }
        }
        b.section("By permission")
        for (t in SitePerm.types) {
            val list = store.all().filter { it.type == t }
            val allowed = list.count { it.allowed }
            val sub = if (t == SitePerm.NOTIFICATIONS) "Not available yet, always blocked"
            else if (list.isEmpty()) "Ask every time" else "$allowed allowed, ${list.size - allowed} blocked"
            b.row(SitePerm.title(t), sub) { h.push(permissionTypePage(h, t)) }
        }
        val hosts = store.hosts()
        b.section("All sites")
        if (hosts.isEmpty()) b.note("No site has been given or refused a permission yet. Facet asks each time until you choose to remember an answer.")
        for (host in hosts) {
            val n = store.all().count { it.host == host }
            b.row(host, "$n ${if (n == 1) "setting" else "settings"}") { h.push(sitePermissionPage(h, host)) }
        }
        if (hosts.isNotEmpty()) {
            b.section("Reset")
            b.row("Reset all site permissions", "Facet will ask again on every site") {
                h.sheet("Reset all site permissions?", listOf(
                    SheetItem(Icon.TRASH, "Reset everything", false, true) { store.clearAll(); view.refresh() },
                    SheetItem(null, "Cancel") { }
                ))
            }
        }
    }
    return view
}

fun permissionTypePage(h: SettingsHost, type: String): View {
    val store = SitePermStore(h.prefs)
    lateinit var view: PageView
    view = page(h, SitePerm.title(type)) { b ->
        val list = store.all().filter { it.type == type }.sortedBy { it.host }
        if (type == SitePerm.NOTIFICATIONS) b.note("Facet doesn't deliver web notifications yet, so every request is declined.")
        if (list.isEmpty()) b.note("No site has a saved answer. Facet asks every time.")
        b.section("Allowed")
        for (p in list.filter { it.allowed }) {
            b.row(p.host, "Allowed ${ago(p.granted)} · last used ${ago(p.lastUsed)}") {
                h.sheet(p.host, listOf(
                    SheetItem(Icon.CLOSE, "Block", false, false) { store.set(p.host, type, false); view.refresh() },
                    SheetItem(Icon.TRASH, "Forget this site (ask again)", false, true) { store.remove(p.host, type); view.refresh() },
                    SheetItem(null, "Cancel") { }
                ))
            }
        }
        b.section("Blocked")
        for (p in list.filter { !it.allowed }) {
            b.row(p.host, "Blocked ${ago(p.granted)}") {
                h.sheet(p.host, listOf(
                    SheetItem(Icon.CHECK, "Allow", false, false) { store.set(p.host, type, true); view.refresh() },
                    SheetItem(Icon.TRASH, "Forget this site (ask again)", false, true) { store.remove(p.host, type); view.refresh() },
                    SheetItem(null, "Cancel") { }
                ))
            }
        }
    }
    return view
}

fun sitePermissionPage(h: SettingsHost, host: String): View {
    val store = SitePermStore(h.prefs)
    lateinit var view: PageView
    view = page(h, host) { b ->
        b.section("Permissions")
        for (t in SitePerm.types) {
            val p = store.find(host, t)
            val state = when {
                p == null -> "Ask every time"
                p.allowed -> "Allowed ${ago(p.granted)}"
                else -> "Blocked"
            }
            b.row(SitePerm.title(t), state) {
                h.sheet("${SitePerm.title(t)} for $host", listOf(
                    SheetItem(Icon.CHECK, "Allow", p?.allowed == true) { store.set(host, t, true); view.refresh() },
                    SheetItem(Icon.CLOSE, "Block", p != null && !p.allowed) { store.set(host, t, false); view.refresh() },
                    SheetItem(null, "Ask every time", p == null) { store.remove(host, t); view.refresh() }
                ))
            }
        }
        b.section("Reset")
        b.row("Reset all for this site", null) { store.clearHost(host); h.toast("Reset"); h.back() }
    }
    return view
}

fun permissionRemindersPage(h: SettingsHost): View {
    val store = SitePermStore(h.prefs)
    lateinit var view: PageView
    view = page(h, "Permission reminders") { b ->
        val list = store.reminders().sortedBy { it.granted }
        if (list.isEmpty()) b.note("Nothing to review. Every site with access to your camera, microphone or location was allowed recently.")
        b.note("These sites were allowed to use a sensitive feature at least 30 days ago and still can.")
        for (p in list) {
            b.row("${p.host}: ${SitePerm.title(p.type)}", "Allowed ${ago(p.granted)} · last used ${ago(p.lastUsed)}") {
                h.sheet("${p.host}: ${SitePerm.title(p.type)}", listOf(
                    SheetItem(Icon.CLOSE, "Revoke access", false, true) { store.remove(p.host, p.type); view.refresh() },
                    SheetItem(Icon.CHECK, "Keep access", false) { store.set(p.host, p.type, true); view.refresh() },
                    SheetItem(null, "Cancel") { }
                ))
            }
        }
    }
    return view
}

fun ago(t: Long): String {
    if (t == 0L) return "never"
    val days = (System.currentTimeMillis() - t) / (24L * 3600 * 1000)
    return when {
        days <= 0 -> "today"
        days == 1L -> "yesterday"
        days < 60 -> "$days days ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
    }
}

// ---------------------------------------------------------------- AI assistant

fun aiPage(h: SettingsHost): View {
    val ai = AiConfig(h.prefs)
    lateinit var view: PageView
    view = page(h, "AI assistant") { b ->
        b.toggle("Use an AI assistant", "Optional and off by default. Adds Summarize and Ask to the page menu.", ai.enabled) { ai.enabled = it; view.refresh() }
        if (ai.enabled) {
            b.section("Provider")
            b.choice("Anthropic (Claude)", null, ai.provider == 0) { ai.provider = 0; view.refresh() }
            b.choice("OpenAI-compatible", "Any service that offers /chat/completions", ai.provider == 1) { ai.provider = 1; view.refresh() }
            b.row("Server address", ai.baseUrl) { h.input("Server address", "https://", ai.baseUrl, false) { ai.baseUrl = it; view.refresh() } }
            b.row("Model", ai.model.ifEmpty { "Not set" }) { h.input("Model name", "model id", ai.model, false) { ai.model = it; view.refresh() } }
            b.row("API key", if (ai.apiKey.isEmpty()) "Not set" else "Saved, encrypted on this device") {
                h.input("API key", "Paste your key", "", true) { ai.apiKey = it; view.refresh() }
            }
        }
        b.note("When you tap Summarize or Ask, the text of the page you are on and your question are sent to the provider you chose, using your own key. Facet sends nothing on its own, and never from private tabs.")
    }
    return view
}
