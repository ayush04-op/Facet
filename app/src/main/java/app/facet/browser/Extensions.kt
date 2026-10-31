package app.facet.browser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What the extension layer needs from the activity. */
class ExtHooks(
    val newTab: (url: String?, active: Boolean) -> GeckoSession?,
    val openUrl: (String) -> Unit,
    val showPopup: (title: String, session: GeckoSession) -> Unit,
    val closePopup: () -> Unit,
    val prompt: (title: String, lines: List<String>, okLabel: String, decide: (Boolean) -> Unit) -> Unit,
    val toast: (String) -> Unit
)

class AmoItem(val name: String, val summary: String, val users: Long, val url: String)

/**
 * Web extensions through the engine's WebExtension support. Extensions written for Chrome (Manifest V2 and V3,
 * chrome.* APIs) mostly run unchanged; Facet repackages Chrome's .crx format so they can be installed.
 * Nothing here talks to a server except when the person asks to browse or install.
 */
class ExtensionManager(
    private val ctx: Context,
    private val rt: GeckoRuntime,
    private val hooks: ExtHooks
) {
    private val ui = Handler(Looper.getMainLooper())
    private val controller get() = rt.webExtensionController

    /** Installed, non-built-in extensions as of the last refresh. */
    var installed: List<WebExtension> = emptyList()
        private set

    private val actions = HashMap<String, WebExtension.Action>()
    private val icons = HashMap<String, Bitmap>()
    var onChange: (() -> Unit)? = null
    var pickFile: (() -> Unit)? = null
    private var popupFor: String? = null

    fun start() {
        controller.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                val res = GeckoResult<WebExtension.PermissionPromptResponse>()
                val name = extension.metaData.name ?: "this extension"
                // Never allowed in private tabs, and never sent usage data, unless the person changes it later.
                hooks.prompt("Add $name", describe(permissions, origins), "Add extension") { ok ->
                    res.complete(WebExtension.PermissionPromptResponse(ok, false, false))
                }
                return res
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>
            ): GeckoResult<AllowOrDeny>? = ask(extension, "Update ${extension.metaData.name ?: "extension"}", "Update", newPermissions, newOrigins)

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<AllowOrDeny>? = ask(extension, "${extension.metaData.name ?: "An extension"} asks for more access", "Allow", permissions, origins)
        })
        refresh { }
    }

    private fun ask(ext: WebExtension, title: String, ok: String, perms: Array<String>, origins: Array<String>): GeckoResult<AllowOrDeny> {
        val res = GeckoResult<AllowOrDeny>()
        hooks.prompt(title, describe(perms, origins), ok) { yes -> res.complete(if (yes) AllowOrDeny.ALLOW else AllowOrDeny.DENY) }
        return res
    }

    // ------------------------------------------------------------ listing

    fun refresh(done: () -> Unit) {
        controller.list().accept({ list ->
            val mine = (list ?: emptyList()).filter { !it.isBuiltIn }
            installed = mine.sortedBy { (it.metaData.name ?: "").lowercase() }
            for (e in mine) register(e)
            onChange?.invoke()
            done()
        }, { done() })
    }

    /** Hooks one extension up to Facet's toolbar actions, popups and tabs. */
    private fun register(ext: WebExtension) {
        ext.setActionDelegate(object : WebExtension.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
                val base = actions[extension.id]
                val merged = if (session != null && base != null) {
                    try { action.withDefault(base) } catch (e: Exception) { action }
                } else action
                if (session == null) actions[extension.id] = action
                loadIcon(extension, merged)
                onChange?.invoke()
            }

            override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? {
                if (popupFor == extension.id) {
                    popupFor = null
                    hooks.closePopup()
                    return null
                }
                return openPopup(extension)
            }

            override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? =
                openPopup(extension)
        })
        ext.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onNewTab(source: WebExtension, createDetails: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? {
                val s = hooks.newTab(createDetails.url, createDetails.active != false) ?: return null
                return GeckoResult.fromValue(s)
            }

            override fun onOpenOptionsPage(source: WebExtension) {
                source.metaData.optionsPageUrl?.let { hooks.openUrl(it) }
            }
        })
        loadIcon(ext, null)
    }

    private fun openPopup(ext: WebExtension): GeckoResult<GeckoSession> {
        val s = GeckoSession()
        popupFor = ext.id
        hooks.showPopup(ext.metaData.name ?: "Extension", s)
        return GeckoResult.fromValue(s)
    }

    fun popupClosed() { popupFor = null }

    private fun loadIcon(ext: WebExtension, action: WebExtension.Action?) {
        val img = action?.icon ?: ext.metaData.icon
        try {
            img.getBitmap(96).accept({ bmp -> if (bmp != null) { icons[ext.id] = bmp; onChange?.invoke() } }, { })
        } catch (e: Exception) {
            // no icon; the row falls back to a letter
        }
    }

    fun iconOf(ext: WebExtension): Bitmap? = icons[ext.id]
    fun actionOf(ext: WebExtension): WebExtension.Action? = actions[ext.id]
    fun badgeOf(ext: WebExtension): String? = actions[ext.id]?.badgeText?.takeIf { it.isNotEmpty() }

    fun click(ext: WebExtension): Boolean {
        val a = actions[ext.id] ?: return false
        if (a.enabled == false) return false
        a.click()
        return true
    }

    // ------------------------------------------------------------ changes

    fun setEnabled(ext: WebExtension, on: Boolean, done: () -> Unit) {
        val r = if (on) controller.enable(ext, WebExtensionController.EnableSource.USER)
        else controller.disable(ext, WebExtensionController.EnableSource.USER)
        r.accept({ refresh(done) }, { hooks.toast("Couldn't change that"); done() })
    }

    fun setPrivateAllowed(ext: WebExtension, on: Boolean, done: () -> Unit) {
        controller.setAllowedInPrivateBrowsing(ext, on).accept({ refresh(done) }, { hooks.toast("Couldn't change that"); done() })
    }

    fun remove(ext: WebExtension, done: () -> Unit) {
        controller.uninstall(ext).accept({
            actions.remove(ext.id)
            icons.remove(ext.id)
            refresh(done)
        }, { hooks.toast("Couldn't remove it"); done() })
    }

    // ------------------------------------------------------------ installing

    private fun installUri(uri: String, method: String, done: () -> Unit) {
        controller.install(uri, method).accept({ ext ->
            if (ext != null) {
                register(ext)
                hooks.toast("Added ${ext.metaData.name ?: "extension"}")
            }
            refresh(done)
        }, { e ->
            val msg = e?.message.orEmpty()
            hooks.toast(
                if (msg.contains("cancel", true)) "Cancelled"
                else "Couldn't add it. The engine only accepts extensions that Mozilla has signed or that are valid for it. ($msg)"
            )
            done()
        })
    }

    /** From Mozilla's add-on site: signed, reviewed, and built for Firefox, so they run as they are. */
    fun installFromAmo(item: AmoItem, done: () -> Unit) {
        hooks.toast("Adding ${item.name}")
        installUri(item.url, WebExtensionController.INSTALLATION_METHOD_MANAGER, done)
    }

    /** From a file the person picked: .xpi, .zip or Chrome's .crx. */
    fun installFromFile(uri: Uri, done: () -> Unit) {
        Thread {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Couldn't read the file")
                val hint = "file" + java.lang.Long.toString(bytes.size.toLong() * 31 + bytes.take(64).sumOf { it.toInt() }, 36)
                val prepared = prepare(bytes, hint)
                val f = File(ctx.cacheDir, "ext").also { it.mkdirs() }.let { File(it, "install_${System.currentTimeMillis()}.xpi") }
                f.writeBytes(prepared)
                ui.post { installUri("file://" + f.absolutePath, WebExtensionController.INSTALLATION_METHOD_FROM_FILE, done) }
            } catch (e: Exception) {
                ui.post { hooks.toast("Couldn't read that file: ${e.message}"); done() }
            }
        }.start()
    }

    /** Experimental: fetches a Chrome Web Store extension by its link or id and repackages it. */
    fun installFromChromeStore(input: String, done: () -> Unit) {
        val id = Regex("[a-p]{32}").find(input.lowercase())?.value
        if (id == null) {
            hooks.toast("That doesn't look like a Chrome Web Store link or extension id")
            done()
            return
        }
        hooks.toast("Downloading…")
        Thread {
            try {
                val url = "https://clients2.google.com/service/update2/crx?response=redirect&prodversion=130.0.0.0" +
                    "&acceptformat=crx2,crx3&x=" + URLEncoder.encode("id=$id&uc", "UTF-8")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) throw IllegalStateException("The store answered ${conn.responseCode}")
                val bytes = conn.inputStream.use { it.readBytes() }
                val prepared = prepare(bytes, id)
                val f = File(ctx.cacheDir, "ext").also { it.mkdirs() }.let { File(it, "$id.xpi") }
                f.writeBytes(prepared)
                ui.post { installUri("file://" + f.absolutePath, WebExtensionController.INSTALLATION_METHOD_FROM_FILE, done) }
            } catch (e: Exception) {
                ui.post { hooks.toast("Couldn't download it: ${e.message}"); done() }
            }
        }.start()
    }

    /** Strips Chrome's .crx header and fills in what the engine needs that Chrome doesn't. Signed .xpi files pass through untouched. */
    private fun prepare(input: ByteArray, idHint: String): ByteArray {
        var zip = input
        if (input.size > 16 && input[0] == 'C'.code.toByte() && input[1] == 'r'.code.toByte() && input[2] == '2'.code.toByte() && input[3] == '4'.code.toByte()) {
            fun le(o: Int) = (input[o].toInt() and 0xff) or ((input[o + 1].toInt() and 0xff) shl 8) or
                ((input[o + 2].toInt() and 0xff) shl 16) or ((input[o + 3].toInt() and 0xff) shl 24)
            val version = le(4)
            val start = if (version == 2) 16 + le(8) + le(12) else 12 + le(8)
            zip = input.copyOfRange(start, input.size)
        }
        return normalize(zip, idHint)
    }

    private fun normalize(zip: ByteArray, idHint: String): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory) entries[e.name] = zin.readBytes()
            }
        }
        if (entries.keys.any { it.equals("META-INF/mozilla.rsa", true) }) return zip
        val raw = entries["manifest.json"] ?: return zip
        val m = JSONObject(String(raw, Charsets.UTF_8).removePrefix("﻿"))
        val bss = m.optJSONObject("browser_specific_settings") ?: JSONObject().also { m.put("browser_specific_settings", it) }
        val gecko = bss.optJSONObject("gecko") ?: JSONObject().also { bss.put("gecko", it) }
        if (gecko.optString("id").isEmpty()) gecko.put("id", "$idHint@chrome.facet")
        // Chrome service workers become the background script the engine understands.
        val bg = m.optJSONObject("background")
        if (bg != null && bg.has("service_worker") && !bg.has("scripts")) {
            bg.put("scripts", JSONArray().put(bg.getString("service_worker")))
        }
        m.remove("incognito")
        entries["manifest.json"] = m.toString().toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zo ->
            for ((name, data) in entries) {
                zo.putNextEntry(ZipEntry(name))
                zo.write(data)
                zo.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ Mozilla add-on search

    fun search(query: String, cb: (List<AmoItem>?, String?) -> Unit) {
        Thread {
            try {
                val q = URLEncoder.encode(query, "UTF-8")
                val url = "https://addons.mozilla.org/api/v5/addons/search/?app=android&type=extension&sort=users&page_size=25&lang=en-US" +
                    (if (query.isNotEmpty()) "&q=$q" else "")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                if (conn.responseCode !in 200..299) throw IllegalStateException("Mozilla's site answered ${conn.responseCode}")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONObject(body).getJSONArray("results")
                val out = ArrayList<AmoItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val file = o.optJSONObject("current_version")?.optJSONObject("file")?.optString("url").orEmpty()
                    if (file.isEmpty()) continue
                    out.add(AmoItem(text(o.opt("name")), text(o.opt("summary")), o.optLong("average_daily_users"), file))
                }
                ui.post { cb(out, null) }
            } catch (e: Exception) {
                ui.post { cb(null, e.message ?: "No connection") }
            }
        }.start()
    }

    private fun text(v: Any?): String = when (v) {
        is String -> v
        is JSONObject -> v.keys().asSequence().firstOrNull()?.let { v.optString(it) } ?: ""
        else -> ""
    }

    // ------------------------------------------------------------ wording for permission prompts

    fun describe(permissions: Array<String>, origins: Array<String>): List<String> {
        val out = ArrayList<String>()
        val broad = origins.any { it == "<all_urls>" || it == "*://*/*" || it == "http://*/*" || it == "https://*/*" }
        if (broad) out.add("Read and change your data on every website you visit")
        else for (o in origins.take(6)) out.add("Read and change your data on $o")
        for (p in permissions) {
            val line = when (p) {
                "tabs" -> "See the addresses and titles of your open tabs"
                "history" -> "Read and change your browsing history"
                "bookmarks" -> "Read and change your bookmarks"
                "cookies" -> "Read and change cookies"
                "webRequest", "webRequestBlocking" -> "See and block the requests that pages make"
                "declarativeNetRequest", "declarativeNetRequestWithHostAccess", "declarativeNetRequestFeedback" -> "Block or change requests that pages make"
                "downloads" -> "Start and manage downloads"
                "clipboardRead" -> "Read what you copied"
                "clipboardWrite" -> "Put text on your clipboard"
                "geolocation" -> "Know your location"
                "nativeMessaging" -> "Talk to other apps on your device"
                "notifications" -> "Show notifications"
                "management" -> "Manage other extensions"
                "privacy" -> "Change privacy settings"
                "proxy" -> "Control how Facet connects to the internet"
                "scripting" -> "Run code in pages"
                "webNavigation" -> "See how you move between pages"
                "storage", "unlimitedStorage", "alarms", "activeTab", "contextMenus", "menus", "idle" -> null
                else -> null
            }
            if (line != null) out.add(line)
        }
        if (out.isEmpty()) out.add("Nothing beyond what any extension gets")
        return out
    }
}

// ---------------------------------------------------------------- pages

private fun page(h: SettingsHost, title: String, build: (PageBuilder) -> Unit): PageView =
    PageView(h.context, h.colors, title, { h.back() }, build)

fun extensionsPage(h: SettingsHost): View {
    val mgr = h.extensions
    lateinit var view: PageView
    view = page(h, "Extensions") { b ->
        b.section("Add")
        b.row("Browse add-ons", "Mozilla's add-on site. Reviewed and signed, and they work in Facet.") { h.push(browseAddonsPage(h, "")) }
        b.row("Add from a file", ".xpi, .zip or Chrome .crx") { mgr.pickFile?.invoke() }
        b.row("Add from the Chrome Web Store", "Paste a link or an extension id. Experimental.") {
            h.input("Chrome Web Store", "Link or extension id", "", false) { text -> mgr.installFromChromeStore(text) { view.refresh() } }
        }
        b.section("Installed")
        val list = mgr.installed
        if (list.isEmpty()) b.note("No extensions yet.")
        for (e in list) {
            val badge = mgr.badgeOf(e)
            val state = if (e.metaData.enabled) "" else "Off · "
            b.row((e.metaData.name ?: e.id) + (if (badge != null) "  ($badge)" else ""), state + (e.metaData.description ?: "Version ${e.metaData.version}")) {
                h.push(extensionPage(h, e.id))
            }
        }
        b.note("Extensions can see the pages you visit. Add only ones you trust. They are switched off in private tabs unless you allow them one by one. Extensions written for Chrome work when the engine accepts them; the engine only installs extensions that are signed for it, so some Chrome Web Store ones will be refused.")
    }
    mgr.onChange = { view.post { view.refresh() } }
    mgr.refresh { view.refresh() }
    return view
}

fun extensionPage(h: SettingsHost, id: String): View {
    val mgr = h.extensions
    lateinit var view: PageView
    view = page(h, mgr.installed.firstOrNull { it.id == id }?.metaData?.name ?: "Extension") { b ->
        val e = mgr.installed.firstOrNull { it.id == id }
        if (e == null) {
            b.note("This extension was removed.")
            return@page
        }
        val m = e.metaData
        if (mgr.actionOf(e) != null) {
            b.row("Open", "Its toolbar button") { if (mgr.click(e)) h.back() else h.toast("It has nothing to open on this page") }
        }
        if (m.optionsPageUrl != null) b.row("Options", "Open its settings page") { h.openUrl(m.optionsPageUrl!!) }
        b.toggle("Enabled", null, m.enabled) { on -> mgr.setEnabled(e, on) { view.refresh() } }
        b.toggle("Allow in private tabs", "Off by default, because extensions can see what you do", m.allowedInPrivateBrowsing) { on ->
            mgr.setPrivateAllowed(e, on) { view.refresh() }
        }
        b.section("About")
        b.info("Version", m.version)
        m.creatorName?.let { b.info("By", it) }
        m.homepageUrl?.let { u -> b.row("Website", u) { h.openUrl(u) } }
        m.description?.let { b.note(it) }
        b.section("Can")
        for (line in mgr.describe(m.requiredPermissions, m.requiredOrigins)) b.info(line, "")
        b.section("Remove")
        b.row("Remove extension", "Deletes it and its data") {
            h.sheet("Remove ${m.name ?: "extension"}?", listOf(
                SheetItem(Icon.TRASH, "Remove", false, true) { mgr.remove(e) { h.back() } },
                SheetItem(null, "Cancel") { }
            ))
        }
    }
    return view
}

fun browseAddonsPage(h: SettingsHost, query: String): View {
    val mgr = h.extensions
    var results: List<AmoItem>? = null
    var error: String? = null
    lateinit var view: PageView
    view = page(h, if (query.isEmpty()) "Popular add-ons" else "Results for $query") { b ->
        b.row("Search add-ons", "By name or topic") {
            h.input("Search add-ons", "ad blocker, dark mode, passwords", query, false) { q -> h.reopen { browseAddonsPage(h, q.trim()) } }
        }
        b.section(if (query.isEmpty()) "Most used" else "Matches")
        val r = results
        if (r == null && error == null) b.note("Loading from Mozilla's add-on site…")
        error?.let { b.note("Couldn't load the list: $it") }
        if (r != null && r.isEmpty()) b.note("Nothing found.")
        for (item in r ?: emptyList()) {
            val users = java.text.NumberFormat.getIntegerInstance().format(item.users)
            b.row(item.name, "$users users · ${item.summary}") { mgr.installFromAmo(item) { } }
        }
        b.note("The list comes from addons.mozilla.org when you open this page. Nothing is sent to anyone else.")
    }
    mgr.search(query) { res, err -> results = res; error = err; view.refresh() }
    return view
}
