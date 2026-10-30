package app.facet.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var blocklist: Blocklist
    private lateinit var c: Colors
    private lateinit var version: String

    private val tabs = ArrayList<BrowserTab>()
    private var current: BrowserTab? = null
    private var nextId = 1L

    private lateinit var rootFrame: FrameLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var progressLine: ProgressLine
    private lateinit var lockIcon: IconView
    private lateinit var addressEdit: EditText
    private lateinit var reloadIcon: IconView
    private lateinit var backIcon: IconView
    private lateinit var forwardIcon: IconView
    private lateinit var tabsIcon: IconView

    private val overlays = ArrayList<View>()
    private var tabSwitcher: TabSwitcherView? = null
    private var settingsView: SettingsView? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val pendingBlocked = AtomicInteger(0)
    private val pendingUpgraded = AtomicInteger(0)

    private val emptyBytes = ByteArray(0)

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        prefs = Prefs(this)
        blocklist = Blocklist.load(this)
        version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
        CookieManager.getInstance().setAcceptCookie(true)

        buildUi()
        if (prefs.restoreTabs) restoreSession()
        handleIntent(intent)
        if (tabs.isEmpty()) newTab(null, false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        current?.webView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        for (t in tabs) t.webView?.onPause()
        flushStats()
        saveSession()
        CookieManager.getInstance().flush()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Dark/light switched by the system: rebuild the chrome, keep the pages.
        if (prefs.theme == 0 && resolveColors().dark != c.dark) rebuildUi()
    }

    override fun onDestroy() {
        for (t in tabs) {
            t.webView?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
            t.webView = null
        }
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            hideCustomView()
            return
        }
        if (overlays.isNotEmpty()) {
            closeTopOverlay()
            return
        }
        if (addressEdit.hasFocus()) {
            blurAddress()
            return
        }
        val t = current
        if (t == null) {
            moveTaskToBack(true)
            return
        }
        val wv = t.webView
        if (!t.showsNewTab && wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        if (!t.showsNewTab && t.closeOnBack && tabs.size > 1) {
            closeTab(t)
            return
        }
        if (!t.showsNewTab) {
            // No history left: go back to the new tab page.
            t.showsNewTab = true
            t.url = ""
            t.title = ""
            t.progress = 0
            wv?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
            t.webView = null
            showCurrent()
            return
        }
        if (tabs.size > 1 && t.isPrivate) {
            closeTab(t)
            return
        }
        moveTaskToBack(true)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            val cb = fileCallback
            fileCallback = null
            var result: Array<Uri>? = null
            if (resultCode == RESULT_OK) {
                result = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            }
            cb?.onReceiveValue(result)
        }
    }

    // ---------------------------------------------------------------- theme + chrome

    private fun resolveColors(): Colors {
        val dark = when (prefs.theme) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        return if (dark) Palette.dark else Palette.light
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        window.statusBarColor = c.bg
        window.navigationBarColor = c.bg
        window.setBackgroundDrawable(ColorDrawable(c.bg))
        var flags = View.SYSTEM_UI_FLAG_VISIBLE
        if (!c.dark) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun buildUi() {
        c = resolveColors()
        applySystemBars()

        rootFrame = FrameLayout(this)
        rootFrame.setBackgroundColor(c.bg)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        contentHost = FrameLayout(this)
        contentHost.isFocusableInTouchMode = true
        column.addView(contentHost, LinearLayout.LayoutParams(MATCH, 0, 1f))

        progressLine = ProgressLine(this, c)
        column.addView(progressLine, LinearLayout.LayoutParams(MATCH, dp(2)))

        column.addView(buildBottomBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        rootFrame.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(rootFrame)
        contentHost.requestFocus()
    }

    private fun rebuildUi() {
        contentHost.removeAllViews()
        overlays.clear()
        tabSwitcher = null
        settingsView = null
        buildUi()
        showCurrent()
    }

    private fun iconButton(icon: Icon, boxDp: Int, sizeDp: Int, onClick: () -> Unit): Pair<FrameLayout, IconView> {
        val box = FrameLayout(this)
        val iv = IconView(this, icon, c.ink, sizeDp)
        box.addView(iv, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER))
        box.pressable(null, withAlpha(c.ink, 0x22), dpf(boxDp / 2f))
        box.setOnClickListener { onClick() }
        return Pair(box, iv)
    }

    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.setBackgroundColor(c.bg)

        // Address pill
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = shape(c.glass, dpf(26f), c.glassEdge, dp(1))
        pill.setPadding(dp(16), 0, dp(4), 0)
        pill.elevation = dpf(2f)

        lockIcon = IconView(this, Icon.SEARCH, c.muted, 18)
        pill.addView(lockIcon, LinearLayout.LayoutParams(dp(18), dp(18)))

        addressEdit = EditText(this)
        addressEdit.background = null
        addressEdit.setPadding(0, 0, 0, 0)
        addressEdit.typeface = Fonts.sans
        addressEdit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        addressEdit.setTextColor(c.ink)
        addressEdit.setHintTextColor(c.muted)
        addressEdit.hint = "Search or enter address"
        addressEdit.isSingleLine = true
        addressEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        addressEdit.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        addressEdit.setSelectAllOnFocus(true)
        addressEdit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                addressEdit.setText(current?.let { urlOf(it) } ?: "")
                addressEdit.selectAll()
                showKeyboard()
            } else {
                updateAddress()
            }
        }
        addressEdit.setOnEditorActionListener { v, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                navigate(v.text.toString())
                true
            } else {
                false
            }
        }
        val elp = LinearLayout.LayoutParams(0, MATCH, 1f)
        elp.leftMargin = dp(12)
        pill.addView(addressEdit, elp)

        val reload = iconButton(Icon.REFRESH, 44, 20) { onReloadClicked() }
        reloadIcon = reload.second
        pill.addView(reload.first, LinearLayout.LayoutParams(dp(44), dp(44)))

        val pillLp = LinearLayout.LayoutParams(MATCH, dp(52))
        pillLp.setMargins(dp(12), dp(8), dp(12), dp(2))
        bar.addView(pill, pillLp)

        // Navigation row
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL

        val back = iconButton(Icon.BACK, 48, 24) { goBack() }
        backIcon = back.second
        val fwd = iconButton(Icon.FORWARD, 48, 24) { goForward() }
        forwardIcon = fwd.second
        val add = iconButton(Icon.PLUS, 48, 24) { newTab(null, false) }
        val tabsBtn = iconButton(Icon.TABS, 48, 26) { showTabSwitcher() }
        tabsIcon = tabsBtn.second
        val menu = iconButton(Icon.MENU, 48, 24) { showMenu() }

        for (b in listOf(back.first, fwd.first, add.first, tabsBtn.first, menu.first)) {
            nav.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        bar.addView(nav, LinearLayout.LayoutParams(MATCH, dp(52)))
        return bar
    }

    private fun refreshChrome() {
        val t = current ?: return
        updateAddress()
        progressLine.progress = t.progress
        tabsIcon.badge = tabs.size.toString()
        val wv = t.webView
        backIcon.alpha = if (!t.showsNewTab || tabs.size > 1) 1f else 0.35f
        forwardIcon.alpha = if (!t.showsNewTab && wv?.canGoForward() == true) 1f else 0.35f
        val loading = t.progress in 1..99
        reloadIcon.icon = if (loading) Icon.CLOSE else Icon.REFRESH
        reloadIcon.alpha = if (t.showsNewTab) 0.35f else 1f
    }

    private fun updateAddress() {
        val t = current ?: return
        if (addressEdit.hasFocus()) return
        if (t.showsNewTab) {
            addressEdit.setText("")
            lockIcon.icon = if (t.isPrivate) Icon.PRIVATE else Icon.SEARCH
            lockIcon.tint = c.muted
        } else {
            val u = urlOf(t)
            addressEdit.setText(Urls.host(u))
            val secure = u.startsWith("https://")
            lockIcon.icon = if (secure) Icon.LOCK else Icon.GLOBE
            lockIcon.tint = if (secure) c.muted else c.accent
        }
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(addressEdit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun blurAddress() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(addressEdit.windowToken, 0)
        contentHost.requestFocus()
        updateAddress()
    }

    private fun focusAddress() {
        addressEdit.requestFocus()
    }

    // ---------------------------------------------------------------- tabs

    private fun urlOf(t: BrowserTab): String = t.webView?.url ?: t.pendingUrl ?: t.url

    private fun newTab(url: String?, isPrivate: Boolean, select: Boolean = true): BrowserTab {
        val t = BrowserTab(nextId++, isPrivate)
        if (url != null) {
            val target = maybeUpgrade(t, url)
            t.showsNewTab = false
            t.pendingUrl = target
            t.url = target
        }
        tabs.add(t)
        if (select) selectTab(t)
        return t
    }

    private fun selectTab(t: BrowserTab) {
        current?.webView?.onPause()
        current = t
        showCurrent()
    }

    private fun showCurrent() {
        val t = current ?: return
        contentHost.removeAllViews()
        if (t.showsNewTab) {
            val v = NewTabView(
                this, c, t.isPrivate,
                prefs.trackersBlocked + pendingBlocked.get(),
                prefs.httpsUpgraded + pendingUpgraded.get(),
                version, prefs.bookmarks(),
                { focusAddress() },
                { u -> navigate(u) },
                { b -> confirmRemoveBookmark(b) }
            )
            contentHost.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            var wv = t.webView
            if (wv == null) {
                wv = createWebView(t)
                t.webView = wv
            }
            contentHost.addView(wv, FrameLayout.LayoutParams(MATCH, MATCH))
            val pending = t.pendingUrl
            if (pending != null) {
                t.pendingUrl = null
                wv.loadUrl(pending)
            }
            wv.onResume()
        }
        refreshChrome()
    }

    private fun closeTab(t: BrowserTab) {
        val idx = tabs.indexOf(t)
        if (idx < 0) return
        tabs.removeAt(idx)
        t.webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        t.webView = null
        if (t.isPrivate && tabs.none { it.isPrivate }) {
            // Last private tab gone: wipe the shared HTTP cache.
            current?.webView?.clearCache(true)
        }
        if (tabs.isEmpty()) {
            current = null
            newTab(null, false)
            return
        }
        if (t === current) {
            current = null
            selectTab(tabs[minOf(idx, tabs.size - 1)])
        } else {
            refreshChrome()
        }
    }

    // ---------------------------------------------------------------- navigation

    private fun navigate(input: String) {
        val t = current ?: return
        val url = Urls.resolve(input, Urls.engine(prefs.searchEngine))
        if (url.isEmpty()) return
        blurAddress()
        loadInto(t, url)
    }

    private fun loadInto(t: BrowserTab, rawUrl: String) {
        val target = maybeUpgrade(t, rawUrl)
        t.url = target
        t.title = ""
        t.progress = 0
        if (t.showsNewTab) {
            t.showsNewTab = false
            t.pendingUrl = target
            showCurrent()
        } else {
            t.webView?.loadUrl(target)
            refreshChrome()
        }
    }

    private fun maybeUpgrade(t: BrowserTab, url: String): String {
        t.upgradedFrom = null
        if (!prefs.httpsUpgrade) return url
        if (!url.startsWith("http://")) return url
        val host = try { Uri.parse(url).host } catch (e: Exception) { null }
        if (Urls.isLocal(host) || host == t.skipUpgradeHost) return url
        t.upgradedFrom = url
        pendingUpgraded.incrementAndGet()
        return "https://" + url.substring(7)
    }

    private fun goBack() {
        val t = current ?: return
        val wv = t.webView
        if (!t.showsNewTab && wv != null && wv.canGoBack()) wv.goBack() else onBackPressed()
    }

    private fun goForward() {
        val wv = current?.webView ?: return
        if (wv.canGoForward()) wv.goForward()
    }

    private fun onReloadClicked() {
        val t = current ?: return
        val wv = t.webView ?: return
        if (t.progress in 1..99) wv.stopLoading() else wv.reload()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_VIEW) {
            val data = i.data ?: return
            val s = data.toString()
            if (s.startsWith("http://") || s.startsWith("https://")) {
                newTab(s, false)
            }
        }
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(t: BrowserTab): WebView {
        val wv = WebView(this)
        wv.setBackgroundColor(c.bg)
        val s = wv.settings
        s.javaScriptEnabled = prefs.javascript
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.mediaPlaybackRequiresUserGesture = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSafeBrowsingEnabled(prefs.safeBrowsing)
        if (t.isPrivate) s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.userAgentString = userAgentFor(wv, t.desktop)

        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !prefs.blockThirdPartyCookies)

        wv.webViewClient = FacetClient(t)
        wv.webChromeClient = FacetChromeClient(t)
        wv.setDownloadListener(DownloadListener { url, ua, cd, mime, _ -> startDownload(url, ua, cd, mime) })
        wv.setOnLongClickListener { onWebLongPress(wv, t) }
        return wv
    }

    private fun userAgentFor(wv: WebView, desktop: Boolean): String {
        val base = wv.settings.userAgentString
        val chrome = Regex("Chrome/[\\d.]+").find(base)?.value ?: "Chrome/120.0.0.0"
        if (desktop) {
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        }
        // Drop the WebView markers so sites treat Facet like a normal mobile Chrome.
        return base.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
    }

    private fun applySettingsToAll() {
        for (t in tabs) {
            val wv = t.webView ?: continue
            wv.settings.javaScriptEnabled = prefs.javascript
            wv.settings.setSafeBrowsingEnabled(prefs.safeBrowsing)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !prefs.blockThirdPartyCookies)
        }
    }

    private inner class FacetClient(private val tab: BrowserTab) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme ?: return false
            when (scheme) {
                "http" -> {
                    if (request.isForMainFrame && prefs.httpsUpgrade) {
                        val raw = uri.toString()
                        val up = maybeUpgrade(tab, raw)
                        if (up != raw) {
                            view.loadUrl(up)
                            return true
                        }
                    }
                    return false
                }
                "https", "about", "data", "blob", "javascript" -> return false
                "mailto", "tel", "sms", "geo" -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e: ActivityNotFoundException) {
                        // no app for it
                    }
                    return true
                }
                else -> return true // ignore every other scheme (intent:, market:, ...)
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (prefs.blockTrackers && !request.isForMainFrame) {
                val host = request.url.host
                if (host != null && blocklist.isBlocked(host)) {
                    pendingBlocked.incrementAndGet()
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(emptyBytes))
                }
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (url != null) tab.url = url
            tab.title = ""
            if (tab === current) refreshChrome()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (url != null) tab.url = url
            tab.title = view.title ?: ""
            tab.progress = 100
            if (tab === current) refreshChrome()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url != null) tab.url = url
            if (tab === current) refreshChrome()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val original = tab.upgradedFrom ?: return
            // The https upgrade failed: fall back to the page the user actually asked for.
            tab.upgradedFrom = null
            tab.skipUpgradeHost = try { Uri.parse(original).host } catch (e: Exception) { null }
            view.loadUrl(original)
        }
    }

    private inner class FacetChromeClient(private val tab: BrowserTab) : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
            if (tab === current) refreshChrome()
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title ?: ""
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false // block pop-ups
            val nt = BrowserTab(nextId++, tab.isPrivate)
            nt.showsNewTab = false
            nt.closeOnBack = true
            val wv = createWebView(nt)
            nt.webView = wv
            tabs.add(nt)
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = wv
            resultMsg.sendToTarget()
            selectTab(nt)
            return true
        }

        override fun onCloseWindow(window: WebView) {
            closeTab(tab)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                startActivityForResult(fileChooserParams.createIntent(), REQ_FILE)
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                filePathCallback.onReceiveValue(null)
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny() // camera and microphone are never granted silently
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            view.setBackgroundColor(Color.BLACK)
            rootFrame.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
            hideSystemBars()
        }

        override fun onHideCustomView() {
            hideCustomView()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun hideCustomView() {
        val v = customView ?: return
        rootFrame.removeView(v)
        customCallback?.onCustomViewHidden()
        customView = null
        customCallback = null
        applySystemBars()
    }

    private fun startDownload(url: String, ua: String?, contentDisposition: String?, mime: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast("This download type isn't supported yet")
            return
        }
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
            if (mime != null) req.setMimeType(mime)
            if (ua != null) req.addRequestHeader("User-Agent", ua)
            val cookie = CookieManager.getInstance().getCookie(url)
            if (cookie != null) req.addRequestHeader("Cookie", cookie)
            req.setTitle(name)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            toast("Downloading $name")
        } catch (e: Exception) {
            toast("Couldn't start the download")
        }
    }

    private fun onWebLongPress(wv: WebView, t: BrowserTab): Boolean {
        val r = wv.hitTestResult
        val type = r.type
        val extra = r.extra
        if (extra == null) return false
        val isLink = type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        if (!isLink) return false
        val items = ArrayList<SheetItem>()
        items.add(SheetItem(Icon.PLUS, "Open in new tab") { newTab(extra, t.isPrivate, false); toast("Opened in a new tab") })
        items.add(SheetItem(Icon.PRIVATE, "Open in private tab") { newTab(extra, true, false); toast("Opened in a private tab") })
        items.add(SheetItem(Icon.LINK, "Copy link") { copyText(extra) })
        items.add(SheetItem(Icon.SHARE, "Share link") { share(extra) })
        showSheet(Urls.host(extra), items)
        return true
    }

    // ---------------------------------------------------------------- overlays

    private fun showOverlay(v: View) {
        overlays.add(v)
        rootFrame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun closeTopOverlay() {
        if (overlays.isEmpty()) return
        val v = overlays.removeAt(overlays.size - 1)
        rootFrame.removeView(v)
        if (v === tabSwitcher) tabSwitcher = null
        if (v === settingsView) settingsView = null
    }

    private fun closeAllOverlays() {
        while (overlays.isNotEmpty()) closeTopOverlay()
    }

    private fun showSheet(title: String?, items: List<SheetItem>) {
        val holder = arrayOfNulls<SheetView>(1)
        val sheet = SheetView(this, c, title, items) {
            val me = holder[0]
            if (me != null && overlays.contains(me)) {
                overlays.remove(me)
                rootFrame.removeView(me)
            }
        }
        holder[0] = sheet
        showOverlay(sheet)
    }

    private fun showTabSwitcher() {
        blurAddress()
        val v = TabSwitcherView(
            this, c,
            { tabs }, { current },
            { t -> closeAllOverlays(); selectTab(t) },
            { t -> closeTab(t); refreshChrome() },
            { closeAllOverlays(); newTab(null, false) },
            { closeAllOverlays(); newTab(null, true) },
            { closeAllOverlays() }
        )
        tabSwitcher = v
        showOverlay(v)
    }

    private fun showMenu() {
        val t = current ?: return
        blurAddress()
        val onPage = !t.showsNewTab
        val url = urlOf(t)
        val items = ArrayList<SheetItem>()
        items.add(SheetItem(Icon.PLUS, "New tab") { newTab(null, false) })
        items.add(SheetItem(Icon.PRIVATE, "New private tab") { newTab(null, true) })
        if (onPage) {
            val saved = prefs.bookmarks().any { it.url == url }
            items.add(SheetItem(Icon.STAR, if (saved) "Remove bookmark" else "Add bookmark") { toggleBookmark(t) })
            items.add(SheetItem(Icon.SHARE, "Share") { share(url) })
            items.add(SheetItem(Icon.LINK, "Copy link") { copyText(url) })
            items.add(SheetItem(Icon.DESKTOP, "Desktop site", t.desktop) { toggleDesktop(t) })
        }
        items.add(SheetItem(Icon.SETTINGS, "Settings") { showSettings() })
        showSheet(null, items)
    }

    private fun showSettings() {
        val holder = arrayOfNulls<SettingsView>(1)
        val sv = SettingsView(
            this, c, prefs, version,
            { pendingBlocked.get().toLong() },
            { closeTopOverlay() },
            { applySettingsToAll() },
            {
                val items = ArrayList<SheetItem>()
                for (e in Urls.engines) {
                    items.add(SheetItem(null, e.name, e.id == prefs.searchEngine) {
                        prefs.searchEngine = e.id
                        holder[0]?.rebuild()
                    })
                }
                showSheet("Search engine", items)
            },
            {
                val items = ArrayList<SheetItem>()
                items.add(SheetItem(null, "Match system", prefs.theme == 0) { setTheme(0) })
                items.add(SheetItem(null, "Paper (light)", prefs.theme == 1) { setTheme(1) })
                items.add(SheetItem(null, "Paper Dark", prefs.theme == 2) { setTheme(2) })
                showSheet("Theme", items)
            },
            {
                val items = ArrayList<SheetItem>()
                items.add(SheetItem(Icon.TRASH, "Clear cookies, cache and site data", false, true) {
                    clearBrowsingData()
                    holder[0]?.rebuild()
                })
                items.add(SheetItem(null, "Cancel") { })
                showSheet("Clear browsing data?", items)
            }
        )
        holder[0] = sv
        settingsView = sv
        showOverlay(sv)
    }

    private fun setTheme(mode: Int) {
        prefs.theme = mode
        rebuildUi()
        showSettings()
    }

    // ---------------------------------------------------------------- actions

    private fun toggleBookmark(t: BrowserTab) {
        val url = urlOf(t)
        val list = prefs.bookmarks()
        val existing = list.firstOrNull { it.url == url }
        if (existing != null) {
            list.remove(existing)
            toast("Bookmark removed")
        } else {
            val title = t.webView?.title ?: t.title
            list.add(Bookmark(if (title.isNullOrBlank()) Urls.host(url) else title, url))
            toast("Bookmarked")
        }
        prefs.saveBookmarks(list)
    }

    private fun confirmRemoveBookmark(b: Bookmark) {
        val items = ArrayList<SheetItem>()
        items.add(SheetItem(Icon.TRASH, "Remove bookmark", false, true) {
            val list = prefs.bookmarks()
            list.removeAll { it.url == b.url }
            prefs.saveBookmarks(list)
            showCurrent()
        })
        items.add(SheetItem(null, "Cancel") { })
        showSheet(if (b.title.isNotBlank()) b.title else Urls.host(b.url), items)
    }

    private fun toggleDesktop(t: BrowserTab) {
        t.desktop = !t.desktop
        val wv = t.webView ?: return
        wv.settings.userAgentString = userAgentFor(wv, t.desktop)
        wv.reload()
    }

    private fun share(url: String) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(i, "Share link"))
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Facet", text))
        toast("Copied")
    }

    private fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        for (t in tabs) {
            t.webView?.clearCache(true)
            t.webView?.clearHistory()
        }
        toast("Browsing data cleared")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- persistence

    private fun flushStats() {
        val b = pendingBlocked.getAndSet(0)
        if (b > 0) prefs.trackersBlocked = prefs.trackersBlocked + b
        val u = pendingUpgraded.getAndSet(0)
        if (u > 0) prefs.httpsUpgraded = prefs.httpsUpgraded + u
    }

    private fun saveSession() {
        val arr = JSONArray()
        var selected = 0
        for (t in tabs) {
            if (t.isPrivate) continue
            if (t === current) selected = arr.length()
            arr.put(if (t.showsNewTab) "" else urlOf(t))
        }
        prefs.sessionUrls = arr.toString()
        prefs.sessionSelected = selected
    }

    private fun restoreSession() {
        try {
            val arr = JSONArray(prefs.sessionUrls)
            if (arr.length() == 0) return
            val sel = prefs.sessionSelected
            var toSelect: BrowserTab? = null
            for (i in 0 until arr.length()) {
                val u = arr.optString(i, "")
                val t = newTab(if (u.isEmpty()) null else u, false, false)
                if (i == sel) toSelect = t
            }
            val pick = toSelect ?: tabs.lastOrNull()
            if (pick != null) selectTab(pick)
        } catch (e: Exception) {
            // bad session data: start fresh
        }
    }

    companion object {
        private const val REQ_FILE = 1001
    }
}
