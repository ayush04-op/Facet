package app.facet.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebResponse
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var runtime: GeckoRuntime
    private lateinit var c: Colors
    private lateinit var version: String

    private val tabs = ArrayList<BrowserTab>()
    private var current: BrowserTab? = null
    private var nextId = 1L

    private lateinit var geckoView: GeckoView
    private lateinit var rootFrame: FrameLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var bottomBar: LinearLayout
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

    private var fullscreen = false
    private var pendingAndroidPerm: GeckoSession.PermissionDelegate.Callback? = null
    private var pendingActivity: GeckoResult<Intent>? = null
    private var pendingFile: Pair<GeckoSession.PromptDelegate.FilePrompt, GeckoResult<GeckoSession.PromptDelegate.PromptResponse>>? = null

    private val pendingBlocked = AtomicInteger(0)
    private val pendingSecure = AtomicInteger(0)

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        prefs = Prefs(this)
        version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
        autoCheckUpdates()
        runtime = FacetApp.runtime(this)
        // Passkeys: GeckoView hands system sign-in screens (Credential Manager) to the activity.
        runtime.activityDelegate = GeckoRuntime.ActivityDelegate { pi ->
            val res = GeckoResult<Intent>()
            pendingActivity = res
            try {
                startIntentSenderForResult(pi.intentSender, REQ_GECKO, null, 0, 0, 0)
            } catch (e: Exception) {
                pendingActivity = null
                res.completeExceptionally(e)
            }
            res
        }
        geckoView = GeckoView(this)

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
        current?.session?.setActive(true)
    }

    override fun onPause() {
        super.onPause()
        current?.session?.setActive(false)
        flushStats()
        saveSession()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Dark/light switched by the system: rebuild the chrome, keep the pages.
        if (prefs.theme == 0 && resolveColors().dark != c.dark) rebuildUi()
    }

    override fun onDestroy() {
        geckoView.releaseSession()
        for (t in tabs) {
            t.session?.close()
            t.session = null
        }
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (fullscreen) {
            current?.session?.exitFullScreen()
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
        if (!t.showsNewTab && t.canGoBack) {
            t.session?.goBack()
            return
        }
        if (!t.showsNewTab && t.closeOnBack && tabs.size > 1) {
            closeTab(t)
            return
        }
        if (!t.showsNewTab) {
            // No history left: go back to the new tab page.
            disposeSession(t)
            t.showsNewTab = true
            t.url = ""
            t.title = ""
            t.progress = 0
            t.canGoBack = false
            t.canGoForward = false
            showCurrent()
            return
        }
        if (tabs.size > 1 && t.isPrivate) {
            closeTab(t)
            return
        }
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            val cb = pendingAndroidPerm
            pendingAndroidPerm = null
            if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) cb?.grant()
            else cb?.reject()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_GECKO) {
            val pa = pendingActivity
            pendingActivity = null
            if (resultCode == RESULT_OK && data != null) pa?.complete(data)
            else pa?.completeExceptionally(RuntimeException("Cancelled"))
            return
        }
        if (requestCode == REQ_FILE) {
            val pf = pendingFile
            pendingFile = null
            if (pf != null) {
                val prompt = pf.first
                val res = pf.second
                val uris = ArrayList<Uri>()
                if (resultCode == RESULT_OK && data != null) {
                    val clip = data.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                    } else {
                        val one = data.data
                        if (one != null) uris.add(one)
                    }
                }
                if (uris.isEmpty()) {
                    res.complete(prompt.dismiss())
                } else if (uris.size == 1) {
                    res.complete(prompt.confirm(this, uris[0]))
                } else {
                    res.complete(prompt.confirm(this, uris.toTypedArray()))
                }
            }
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

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
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

        bottomBar = buildBottomBar()
        column.addView(bottomBar, LinearLayout.LayoutParams(MATCH, WRAP))
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
        box.pressScale(0.84f)
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
        val label = tabs.size.toString()
        if (tabsIcon.badge != label) {
            val first = tabsIcon.badge == null
            tabsIcon.badge = label
            if (!first) {
                tabsIcon.animate().scaleX(1.3f).scaleY(1.3f).setDuration(110L).withEndAction {
                    tabsIcon.animate().scaleX(1f).scaleY(1f).setDuration(240L)
                        .setInterpolator(OvershootInterpolator(3f)).start()
                }.start()
            }
        }
        backIcon.alpha = if (!t.showsNewTab || tabs.size > 1) 1f else 0.35f
        forwardIcon.alpha = if (!t.showsNewTab && t.canGoForward) 1f else 0.35f
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
            val secure = t.secure || u.startsWith("https://")
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

    // ---------------------------------------------------------------- tabs

    private fun urlOf(t: BrowserTab): String = t.url

    private fun newTab(url: String?, isPrivate: Boolean, select: Boolean = true): BrowserTab {
        val t = BrowserTab(nextId++, isPrivate)
        if (url != null) {
            t.showsNewTab = false
            t.pendingUrl = url
            t.url = url
        }
        tabs.add(t)
        if (select) selectTab(t)
        return t
    }

    private fun selectTab(t: BrowserTab) {
        current?.session?.setActive(false)
        current = t
        showCurrent()
    }

    private fun sessionSettings(t: BrowserTab): GeckoSessionSettings {
        return GeckoSessionSettings.Builder()
            .usePrivateMode(t.isPrivate)
            .userAgentMode(
                if (t.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            .viewportMode(
                if (t.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            )
            .build()
    }

    private fun attachDelegates(t: BrowserTab, s: GeckoSession) {
        val d = TabDelegate(t)
        s.navigationDelegate = d
        s.progressDelegate = d
        s.contentDelegate = d
        s.promptDelegate = d
        s.permissionDelegate = d
        s.contentBlockingDelegate = d
    }

    private fun ensureSession(t: BrowserTab): GeckoSession {
        var s = t.session
        if (s == null) {
            s = GeckoSession(sessionSettings(t))
            attachDelegates(t, s)
            t.session = s
        }
        if (!s.isOpen) s.open(runtime)
        return s
    }

    private fun recover(t: BrowserTab) {
        val url = t.url
        geckoView.post {
            disposeSession(t)
            if (url.isNotEmpty() && !t.showsNewTab) {
                ensureSession(t).loadUri(url)
                toast("The page stopped working and was reloaded")
            }
            if (t === current) showCurrent()
        }
    }

    private fun disposeSession(t: BrowserTab) {
        val s = t.session ?: return
        if (geckoView.session === s) geckoView.releaseSession()
        s.close()
        t.session = null
    }

    private fun showCurrent() {
        val t = current ?: return
        contentHost.removeAllViews()
        geckoView.releaseSession()
        (geckoView.parent as? ViewGroup)?.removeView(geckoView)
        if (t.showsNewTab) {
            val v = NewTabView(
                this, c, t.isPrivate,
                prefs.trackersBlocked + pendingBlocked.get(),
                prefs.httpsUpgraded + pendingSecure.get(),
                version, prefs.bookmarks(),
                { u -> navigate(u) },
                { b -> confirmRemoveBookmark(b) }
            )
            contentHost.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            val s = ensureSession(t)
            contentHost.addView(geckoView, FrameLayout.LayoutParams(MATCH, MATCH))
            geckoView.setSession(s)
            s.setActive(true)
            val pending = t.pendingUrl
            if (pending != null) {
                t.pendingUrl = null
                s.loadUri(pending)
            }
        }
        refreshChrome()
    }

    private fun closeTab(t: BrowserTab) {
        val idx = tabs.indexOf(t)
        if (idx < 0) return
        tabs.removeAt(idx)
        disposeSession(t)
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

    private fun loadInto(t: BrowserTab, url: String) {
        t.url = url
        t.title = ""
        t.progress = 0
        if (t.showsNewTab) {
            t.showsNewTab = false
            t.pendingUrl = url
            showCurrent()
        } else {
            t.session?.loadUri(url)
            refreshChrome()
        }
    }

    private fun goBack() {
        val t = current ?: return
        if (!t.showsNewTab && t.canGoBack) t.session?.goBack() else onBackPressed()
    }

    private fun goForward() {
        val t = current ?: return
        if (t.canGoForward) t.session?.goForward()
    }

    private fun onReloadClicked() {
        val t = current ?: return
        val s = t.session ?: return
        if (t.progress in 1..99) {
            s.stop()
        } else {
            s.reload()
            reloadIcon.animate().rotationBy(360f).setDuration(480L)
                .setInterpolator(DecelerateInterpolator()).start()
        }
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

    // ---------------------------------------------------------------- engine callbacks

    /** Receives everything a tab's GeckoSession reports and asks the UI to do. */
    private inner class TabDelegate(private val tab: BrowserTab) :
        GeckoSession.NavigationDelegate,
        GeckoSession.ProgressDelegate,
        GeckoSession.ContentDelegate,
        GeckoSession.PromptDelegate,
        GeckoSession.PermissionDelegate,
        ContentBlocking.Delegate {

        // ----- navigation

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean
        ) {
            if (url != null) tab.url = url
            if (tab === current) refreshChrome()
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            tab.canGoBack = canGoBack
            if (tab === current) refreshChrome()
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            tab.canGoForward = canGoForward
            if (tab === current) refreshChrome()
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny>? {
            val uri = Uri.parse(request.uri)
            when (uri.scheme) {
                "mailto", "tel", "sms", "geo" -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e: ActivityNotFoundException) {
                        // no app for it
                    }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                "intent", "market" -> return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            return null
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            val nt = BrowserTab(nextId++, tab.isPrivate)
            nt.showsNewTab = false
            nt.closeOnBack = true
            nt.url = uri
            val ns = GeckoSession(sessionSettings(nt))
            attachDelegates(nt, ns)
            nt.session = ns
            tabs.add(nt)
            // The engine opens the new session right after this returns; select the tab afterwards.
            geckoView.post { selectTab(nt) }
            return GeckoResult.fromValue(ns)
        }

        // ----- progress

        override fun onPageStart(session: GeckoSession, url: String) {
            tab.url = url
            tab.title = ""
            tab.progress = 5
            if (tab === current) refreshChrome()
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            tab.progress = 100
            if (success && tab.secure && !tab.isPrivate) pendingSecure.incrementAndGet()
            if (tab === current) refreshChrome()
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            tab.progress = progress
            if (tab === current) refreshChrome()
        }

        override fun onSecurityChange(
            session: GeckoSession,
            securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
        ) {
            tab.secure = securityInfo.isSecure
            if (tab === current) refreshChrome()
        }

        // ----- content

        override fun onTitleChange(session: GeckoSession, title: String?) {
            tab.title = title ?: ""
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            setFullscreen(fullScreen)
        }

        override fun onCloseRequest(session: GeckoSession) {
            closeTab(tab)
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            startDownload(response.uri, response.headers["content-disposition"], response.headers["content-type"])
        }

        override fun onContextMenu(
            session: GeckoSession,
            screenX: Int,
            screenY: Int,
            element: GeckoSession.ContentDelegate.ContextElement
        ) {
            val link = element.linkUri
            val image = element.srcUri
            val items = ArrayList<SheetItem>()
            if (link != null) {
                items.add(SheetItem(Icon.PLUS, "Open in new tab") { newTab(link, tab.isPrivate, false); toast("Opened in a new tab") })
                items.add(SheetItem(Icon.PRIVATE, "Open in private tab") { newTab(link, true, false); toast("Opened in a private tab") })
                items.add(SheetItem(Icon.LINK, "Copy link") { copyText(link) })
                items.add(SheetItem(Icon.SHARE, "Share link") { share(link) })
            }
            if (image != null && element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE) {
                items.add(SheetItem(Icon.LINK, "Copy image link") { copyText(image) })
                items.add(SheetItem(Icon.PLUS, "Open image in new tab") { newTab(image, tab.isPrivate, false) })
            }
            if (items.isEmpty()) return
            showSheet(Urls.host(link ?: image ?: ""), items)
        }

        // ----- permissions (asked per request; nothing is granted silently)

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: GeckoSession.PermissionDelegate.Callback
        ) {
            val needed = permissions?.filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }?.toTypedArray()
            if (needed == null || needed.isEmpty()) {
                callback.grant()
                return
            }
            pendingAndroidPerm = callback
            requestPermissions(needed, REQ_PERM)
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission
        ): GeckoResult<Int>? {
            if (perm.permission != GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
            val res = GeckoResult<Int>()
            val items = ArrayList<SheetItem>()
            items.add(SheetItem(Icon.CHECK, "Allow") {
                res.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            })
            items.add(SheetItem(null, "Block") {
                res.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            })
            showSheet("${Urls.host(perm.uri ?: "")} wants to know your location", items) {
                res.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
            return res
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback
        ) {
            val cam = video?.firstOrNull()
            val mic = audio?.firstOrNull()
            if (cam == null && mic == null) {
                callback.reject()
                return
            }
            val what = when {
                cam != null && mic != null -> "camera and microphone"
                cam != null -> "camera"
                else -> "microphone"
            }
            val items = ArrayList<SheetItem>()
            items.add(SheetItem(Icon.CHECK, "Allow") { callback.grant(cam, mic) })
            items.add(SheetItem(null, "Block") { callback.reject() })
            showSheet("${Urls.host(uri)} wants to use your $what", items) { callback.reject() }
        }

        // ----- engine crashes: reload the page instead of leaving a blank tab

        override fun onCrash(session: GeckoSession) { recover(tab) }
        override fun onKill(session: GeckoSession) { recover(tab) }

        // ----- tracker stats

        override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
            if (event.isBlocking) pendingBlocked.incrementAndGet()
        }

        // ----- prompts (alerts, confirms, <select> lists, file pickers) shown as Facet sheets

        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            val res = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            val items = ArrayList<SheetItem>()
            items.add(SheetItem(null, "OK") { res.complete(prompt.dismiss()) })
            showSheet(prompt.message ?: prompt.title ?: "", items) { res.complete(prompt.dismiss()) }
            return res
        }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ButtonPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            val res = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            val items = ArrayList<SheetItem>()
            items.add(SheetItem(null, "OK") {
                res.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE))
            })
            items.add(SheetItem(null, "Cancel") {
                res.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE))
            })
            showSheet(prompt.message ?: prompt.title ?: "", items) { res.complete(prompt.dismiss()) }
            return res
        }

        override fun onChoicePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ChoicePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            val res = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            val items = ArrayList<SheetItem>()
            val multiple = prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE
            addChoices(prompt.choices, items) { id ->
                if (multiple) {
                    res.complete(prompt.confirm(arrayOf(id)))
                } else {
                    res.complete(prompt.confirm(id))
                }
            }
            showSheet(prompt.title ?: "", items) { res.complete(prompt.dismiss()) }
            return res
        }

        override fun onFilePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            val res = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            pendingFile = Pair(prompt, res)
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            val mimes = prompt.mimeTypes
            if (mimes != null && mimes.isNotEmpty()) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent.createChooser(i, "Choose file"), REQ_FILE)
            } catch (e: ActivityNotFoundException) {
                pendingFile = null
                res.complete(prompt.dismiss())
            }
            return res
        }

        override fun onPopupPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.PopupPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
        }

        override fun onWebAuthnRelatedOriginPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.WebAuthnRelatedOriginPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            val res = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            val what = if (prompt.isCreate) "Create a passkey" else "Use a passkey"
            val items = ArrayList<SheetItem>()
            items.add(SheetItem(Icon.LOCK, "Allow") { res.complete(prompt.confirm(AllowOrDeny.ALLOW)) })
            items.add(SheetItem(null, "Don't allow") { res.complete(prompt.confirm(AllowOrDeny.DENY)) })
            showSheet("$what for ${prompt.rpId} on ${Urls.host(prompt.origin ?: "")}?", items) {
                res.complete(prompt.confirm(AllowOrDeny.DENY))
            }
            return res
        }

        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
        }
    }

    private fun addChoices(
        choices: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>,
        out: ArrayList<SheetItem>,
        onPick: (String) -> Unit
    ) {
        for (choice in choices) {
            val sub = choice.items
            if (sub != null) {
                addChoices(sub, out, onPick)
                continue
            }
            if (choice.disabled || choice.separator) continue
            val id = choice.id
            out.add(SheetItem(null, choice.label ?: "") { onPick(id) })
        }
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        bottomBar.visibility = if (on) View.GONE else View.VISIBLE
        progressLine.visibility = if (on) View.GONE else View.VISIBLE
        if (on) hideSystemBars() else applySystemBars()
    }

    private fun startDownload(url: String, contentDisposition: String?, mime: String?) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("This download type isn't supported yet")
            return
        }
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
            if (mime != null) req.setMimeType(mime)
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

    // ---------------------------------------------------------------- overlays

    private fun showOverlay(v: View) {
        overlays.add(v)
        rootFrame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        if (v !is SheetView) {
            v.alpha = 0f
            v.translationY = dpf(30f)
            v.animate().alpha(1f).translationY(0f).setDuration(260L)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    private fun closeTopOverlay() {
        if (overlays.isEmpty()) return
        val v = overlays.removeAt(overlays.size - 1)
        if (v === tabSwitcher) tabSwitcher = null
        if (v === settingsView) settingsView = null
        if (v is SheetView) {
            rootFrame.removeView(v)
            v.cancel()
            return
        }
        v.animate().alpha(0f).translationY(dpf(30f)).setDuration(190L)
            .withEndAction { rootFrame.removeView(v) }.start()
    }

    private fun closeAllOverlays() {
        while (overlays.isNotEmpty()) closeTopOverlay()
    }

    private fun showSheet(title: String?, items: List<SheetItem>, onCancel: (() -> Unit)? = null) {
        val holder = arrayOfNulls<SheetView>(1)
        val sheet = SheetView(this, c, title, items) {
            val me = holder[0]
            if (me != null && overlays.contains(me)) {
                overlays.remove(me)
                rootFrame.removeView(me)
            }
        }
        sheet.onCancel = onCancel
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
            { FacetApp.applyPrefs(runtime, prefs) },
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
                items.add(SheetItem(null, "Match system", prefs.theme == 0) { applyThemeMode(0) })
                items.add(SheetItem(null, "Paper (light)", prefs.theme == 1) { applyThemeMode(1) })
                items.add(SheetItem(null, "Paper Dark", prefs.theme == 2) { applyThemeMode(2) })
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
            },
            { showUpdates() }
        )
        holder[0] = sv
        settingsView = sv
        showOverlay(sv)
    }

    private fun showUpdates() {
        val uv = UpdatesView(
            this, c, prefs, version,
            { closeTopOverlay(); settingsView?.rebuild() },
            { title, hint, initial, secret, save -> showInput(title, hint, initial, secret, save) }
        )
        showOverlay(uv)
    }

    private fun showInput(title: String, hint: String, initial: String, secret: Boolean, save: (String) -> Unit) {
        val holder = arrayOfNulls<InputSheetView>(1)
        val v = InputSheetView(this, c, title, hint, initial, secret, save) {
            val me = holder[0]
            if (me != null && overlays.contains(me)) {
                overlays.remove(me)
                rootFrame.removeView(me)
            }
        }
        holder[0] = v
        overlays.add(v)
        rootFrame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Quiet background check at most twice a day; only records that a newer release exists. */
    private fun autoCheckUpdates() {
        if (!prefs.autoCheckUpdates) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < 12L * 3600 * 1000) return
        val repo = prefs.updateRepo
        val token = prefs.updateToken
        Thread {
            try {
                val r = Updater.latest(repo, token)
                prefs.lastUpdateCheck = System.currentTimeMillis()
                prefs.availableUpdate = if (r != null && Updater.isNewer(r.tag, version)) r.tag else ""
            } catch (e: Exception) {
                // stay quiet: the updates page shows errors when the user asks
            }
        }.start()
    }

    private fun applyThemeMode(mode: Int) {
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
            list.add(Bookmark(if (t.title.isBlank()) Urls.host(url) else t.title, url))
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
        val s = t.session ?: return
        s.settings.setUserAgentMode(
            if (t.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        )
        s.settings.setViewportMode(
            if (t.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        )
        s.reload()
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
        runtime.storageController.clearData(StorageController.ClearFlags.ALL)
        toast("Browsing data cleared")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- persistence

    private fun flushStats() {
        val b = pendingBlocked.getAndSet(0)
        if (b > 0) prefs.trackersBlocked = prefs.trackersBlocked + b
        val u = pendingSecure.getAndSet(0)
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
        private const val REQ_GECKO = 1002
        private const val REQ_PERM = 1003
    }
}
