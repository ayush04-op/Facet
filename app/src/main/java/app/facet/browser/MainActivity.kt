package app.facet.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.provider.Settings
import android.graphics.RenderEffect
import android.graphics.Shader
import android.animation.ValueAnimator
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
import org.json.JSONObject
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

private val PAGE_EASE = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

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
    // Edge to edge, like Chrome: the status bar stays visible and the app draws behind both system bars,
    // padding its own content by these insets.
    private var insTop = 0
    private var insNav = 0
    private var insIme = 0
    private var barFloating = false
    private var homeGlass: GlassSource? = null
    private lateinit var bottomStack: LinearLayout
    private lateinit var addrPill: LinearLayout
    private lateinit var navLeft: LinearLayout
    private lateinit var navRight: LinearLayout
    private val overlayBase = HashMap<View, IntArray>()
    private var pendingAndroidPerm: GeckoSession.PermissionDelegate.Callback? = null
    private var pendingActivity: GeckoResult<Intent>? = null
    private var pendingFile: Pair<GeckoSession.PromptDelegate.FilePrompt, GeckoResult<GeckoSession.PromptDelegate.PromptResponse>>? = null

    private lateinit var workspaces: WorkspaceStore
    private lateinit var workspace: Workspace
    private lateinit var sitePerms: SitePermStore
    private lateinit var history: HistoryStore
    private lateinit var ai: AiConfig
    private lateinit var extMgr: ExtensionManager
    private var onboardView: OnboardingView? = null
    private val onboardState by lazy { OnboardState(workspaces.current().name, HomeConfig(prefs).name) }
    private var popupView: View? = null
    private var popupGecko: GeckoView? = null
    private var popupSession: GeckoSession? = null
    private var promptView: View? = null
    private var promptCb: ((Boolean) -> Unit)? = null
    private var crashedLastTime = false
    private val readerTabs = HashSet<Long>()
    private val aiPending = HashMap<Long, String>()

    private val pendingBlocked = AtomicInteger(0)
    private val pendingSecure = AtomicInteger(0)

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) {
            val wlp = window.attributes
            wlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = wlp
        }
        Fonts.init(this)
        prefs = Prefs(this)
        crashedLastTime = prefs.runningFlag
        workspaces = WorkspaceStore(prefs)
        sitePerms = SitePermStore(prefs)
        history = HistoryStore(prefs)
        ai = AiConfig(prefs)
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

        // Temporary workspaces never outlive the run they were made in.
        for (w in workspaces.all()) {
            if (w.kind == Workspace.TEMPORARY) wipeWorkspace(w)
        }
        workspace = workspaces.current()

        extMgr = ExtensionManager(this, runtime, extHooks)
        extMgr.pickFile = { pickExtensionFile() }
        extMgr.start()
        FacetExt.listener = extListener
        FacetExt.install(runtime) {
            for (t in tabs) t.session?.let { FacetExt.attach(it, t.id) }
        }

        buildUi()
        if (prefs.restoreTabs) restoreSession()
        handleIntent(intent)
        if (tabs.isEmpty()) newTab(null, false)
        if (!prefs.getBool("onboarded", false)) showOnboarding(0)
    }

    private fun showOnboarding(step: Int) {
        val v = OnboardingView(this, c, prefs, onboardState, step, OnboardHooks(
            { s -> window.decorView.post { rebuildUi(); showOnboarding(s) } },
            { finishOnboarding() }
        ))
        onboardView = v
        v.setInsets(insTop, insNav)
        rootFrame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun finishOnboarding() {
        onboardView?.let { rootFrame.removeView(it) }
        onboardView = null
        val name = onboardState.profile.ifBlank { "Personal" }
        workspaces.rename("personal", name)
        for (x in onboardState.extras) {
            if (x.equals(name, true) || workspaces.all().any { it.name.equals(x, true) }) continue
            workspaces.add(x, if (x == "Research") Workspace.RESEARCH else Workspace.NORMAL)
        }
        workspace = workspaces.current()
        prefs.putBool("onboarded", true)
        showCurrent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        prefs.runningFlag = true
        current?.session?.setActive(true)
    }

    override fun onPause() {
        super.onPause()
        current?.session?.setActive(false)
        flushStats()
        saveSession()
        prefs.runningFlag = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_K -> { showCommandBar(); return true }
                KeyEvent.KEYCODE_T -> { newTab(null, false); return true }
                KeyEvent.KEYCODE_L -> { addressEdit.requestFocus(); return true }
                KeyEvent.KEYCODE_W -> { current?.let { closeTab(it) }; return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Dark/light switched by the system: rebuild the chrome, keep the pages.
        if (prefs.theme == 0 && resolveColors().dark != c.dark) rebuildUi()
    }

    override fun onDestroy() {
        geckoView.releaseSession()
        FacetExt.listener = null
        for (t in tabs) {
            t.session?.close()
            t.session = null
        }
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        onboardView?.let {
            if (!it.handleBack()) moveTaskToBack(true)
            return
        }
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
        if (requestCode == REQ_WALL) {
            val u = data?.data
            if (resultCode == RESULT_OK && u != null) {
                if (Wallpapers.saveCustom(this, u)) {
                    val cfg = HomeConfig(prefs)
                    cfg.wallpaper = 9
                    cfg.customStamp = System.currentTimeMillis()
                    HomeLook.forget()
                    if (current?.showsNewTab == true) showCurrent()
                    toast("Wallpaper set")
                } else toast("Couldn't use that picture")
            }
            return
        }
        if (requestCode == REQ_EXT) {
            val u = data?.data
            if (resultCode == RESULT_OK && u != null) extMgr.installFromFile(u) { }
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
            2, 3 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        return Palette.make(dark, HomeConfig(prefs).accent, prefs.theme == 3)
    }

    private fun highContrastText(): Boolean = try {
        Settings.Secure.getInt(contentResolver, "high_text_contrast_enabled", 0) == 1
    } catch (e: Exception) {
        false
    }

    /** Immersive mode hides both system bars. Off by default: Facet sits edge to edge like Chrome instead. */
    private fun fullUi(): Boolean = prefs.getBool("immersive_ui", false)

    private fun gestureNav(): Boolean = try {
        Settings.Secure.getInt(contentResolver, "navigation_mode", 0) == 2
    } catch (e: Exception) {
        false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (fullscreen) hideSystemBars(true) else if (fullUi()) hideSystemBars(gestureNav())
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        window.setBackgroundDrawable(ColorDrawable(c.bg))
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val ic = window.insetsController
            if (ic != null) {
                val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                ic.setSystemBarsAppearance(if (c.dark) 0 else light, light)
                if (!fullUi()) ic.show(WindowInsets.Type.systemBars())
            }
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (!c.dark) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }
        if (fullUi()) hideSystemBars(gestureNav())
    }

    private fun hideSystemBars(hideNav: Boolean) {
        try { hideSystemBarsUnsafe(hideNav) } catch (e: Exception) { android.util.Log.w("Facet", "hide bars", e) }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBarsUnsafe(hideNav: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val ic = window.insetsController ?: return
            ic.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            var types = WindowInsets.Type.statusBars()
            if (hideNav) types = types or WindowInsets.Type.navigationBars()
            ic.hide(types)
        } else {
            var f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            if (hideNav) f = f or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            window.decorView.systemUiVisibility = f
        }
    }

    @Suppress("DEPRECATION")
    private fun readInsets(ins: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val sys = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insTop = sys.top
            insNav = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
            insIme = ins.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            insTop = ins.systemWindowInsetTop
            insNav = ins.stableInsetBottom
            val all = ins.systemWindowInsetBottom
            insIme = if (all > insNav + dp(40)) all else 0
        }
        if (fullscreen) { insTop = 0; insNav = 0 }
    }

    /** Lays the bottom bar, the page and any open overlays out around the system bars and the keyboard. */
    private fun applyInsets() {
        if (!::bottomStack.isInitialized) return
        val kb = insIme > 0
        val lp = bottomStack.layoutParams as FrameLayout.LayoutParams
        if (barFloating) {
            lp.leftMargin = dp(12)
            lp.rightMargin = dp(12)
            lp.bottomMargin = (if (kb) insIme else insNav) + dp(6)
            bottomBar.setPadding(0, 0, 0, 0)
        } else {
            lp.leftMargin = 0
            lp.rightMargin = 0
            lp.bottomMargin = if (kb) insIme else 0
            bottomBar.setPadding(0, 0, 0, if (kb) 0 else insNav)
        }
        bottomStack.layoutParams = lp
        bottomStack.post { padContent() }
        for ((v, base) in overlayBase) padOverlay(v, base)
        onboardView?.setInsets(insTop, if (kb) insIme else insNav)
        bottomBar.invalidate()
        if (::addrPill.isInitialized) { addrPill.invalidate(); navLeft.invalidate(); navRight.invalidate() }
    }

    private fun barSpace(): Int {
        if (!::bottomStack.isInitialized || bottomStack.visibility != View.VISIBLE) return if (insIme > 0) insIme else insNav
        val lp = bottomStack.layoutParams as FrameLayout.LayoutParams
        return bottomStack.height + lp.bottomMargin
    }

    private fun padContent() {
        val home = contentHost.getChildAt(0) as? NewTabView
        if (home != null) {
            contentHost.setPadding(0, 0, 0, 0)
            home.setInsets(insTop, barSpace() + dp(6))
        } else if (fullscreen) {
            contentHost.setPadding(0, 0, 0, 0)
        } else {
            contentHost.setPadding(0, insTop, 0, barSpace())
        }
    }

    private fun padOverlay(v: View, base: IntArray) {
        when (v) {
            is MenuPopupView -> {}
            is SheetView -> v.setBottomInset(insNav)
            else -> v.setPadding(base[0], base[1] + insTop, base[2], base[3] + Math.max(insNav, insIme))
        }
    }

    /** The glass backdrop for whatever is on screen: the home wallpaper, or the plain page colour. */
    private fun currentBackdrop(): GlassSource {
        val g = homeGlass
        if (g != null && current?.showsNewTab == true) return g
        return GlassSource.plain(c.bg, c.dark)
    }

    /** Captures the page once (GeckoView can render it to a bitmap) so a menu or sheet can refract it. */
    private fun withBackdrop(then: (GlassSource) -> Unit) {
        val t = current
        if (t == null || t.showsNewTab || fullscreen || geckoView.parent == null) {
            val src = currentBackdrop()
            Backdrop.set(src, false)
            then(src)
            return
        }
        var done = false
        val finish = { src: GlassSource ->
            if (!done) {
                done = true
                Backdrop.set(src, true)
                then(src)
            }
        }
        val fallback = Runnable { finish(GlassSource.plain(c.bg, c.dark)) }
        rootFrame.postDelayed(fallback, 350L)
        try {
            geckoView.capturePixels().accept({ bmp ->
                rootFrame.post {
                    rootFrame.removeCallbacks(fallback)
                    finish(if (bmp != null) snapshotSource(bmp) else GlassSource.plain(c.bg, c.dark))
                }
            }, { _ -> rootFrame.post { rootFrame.removeCallbacks(fallback); finish(GlassSource.plain(c.bg, c.dark)) } })
        } catch (e: Throwable) {
            rootFrame.removeCallbacks(fallback)
            finish(GlassSource.plain(c.bg, c.dark))
        }
    }

    private fun snapshotSource(bmp: android.graphics.Bitmap): GlassSource {
        return try {
            val sw = Math.max(1, bmp.width / 2)
            val sh = Math.max(1, bmp.height / 2)
            val sharp = android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
            val src = GlassSource(Wallpapers.blurred(sharp, 1), c.dark, sharp, 0)
            src.origin = geckoView
            src
        } catch (e: Throwable) {
            GlassSource.plain(c.bg, c.dark)
        }
    }

    /**
     * The toolbar floats on the Liquid Glass layer: the address capsule and two grouped button capsules, inset
     * from the screen edges. Over the home page they refract the wallpaper; over web pages, the page colour.
     */
    private fun styleBar() {
        if (!::bottomStack.isInitialized) return
        barFloating = true
        val src = currentBackdrop()
        val d = resources.displayMetrics.density
        bottomBar.background = null
        addrPill.background = GlassDrawable(src, addrPill, dpf(26f), d)
        navLeft.background = GlassDrawable(src, navLeft, dpf(26f), d)
        navRight.background = GlassDrawable(src, navRight, dpf(26f), d)
        applyInsets()
    }

    private fun buildUi() {
        c = resolveColors()
        GlassPrefs.reduceTransparency = prefs.getBool("reduce_transparency", false) || highContrastText()

        rootFrame = FrameLayout(this)
        rootFrame.setBackgroundColor(c.bg)

        contentHost = FrameLayout(this)
        contentHost.isFocusableInTouchMode = true
        contentHost.setBackgroundColor(c.bg)
        rootFrame.addView(contentHost, FrameLayout.LayoutParams(MATCH, MATCH))

        bottomStack = LinearLayout(this)
        bottomStack.orientation = LinearLayout.VERTICAL
        progressLine = ProgressLine(this, c)
        bottomStack.addView(progressLine, LinearLayout.LayoutParams(MATCH, dp(2)))
        bottomBar = buildBottomBar()
        bottomStack.addView(bottomBar, LinearLayout.LayoutParams(MATCH, WRAP))
        rootFrame.addView(bottomStack, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        bottomStack.addOnLayoutChangeListener { _, _, top, _, bottom, _, oTop, _, oBottom -> if (bottom - top != oBottom - oTop || top != oTop) padContent() }
        rootFrame.setOnApplyWindowInsetsListener { _, ins ->
            readInsets(ins)
            applyInsets()
            ins
        }
        setContentView(rootFrame)
        // the window's insets controller only exists once the decor view is installed
        try { applySystemBars() } catch (e: Exception) { android.util.Log.w("Facet", "system bars", e) }
        contentHost.requestFocus()
    }

    private fun rebuildUi() {
        contentHost.removeAllViews()
        overlays.clear()
        overlayBase.clear()
        contentHost.translationX = 0f
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

        // Address pill
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = shape(c.glass, dpf(26f), c.glassEdge, dp(1))
        pill.setPadding(dp(16), 0, dp(4), 0)
        addrPill = pill

        lockIcon = IconView(this, Icon.SEARCH, c.muted, 18)
        lockIcon.setOnClickListener { showSiteInfo() }
        pill.addView(lockIcon, LinearLayout.LayoutParams(dp(18), dp(18)))

        addressEdit = EditText(this)
        addressEdit.background = null
        addressEdit.setPadding(0, 0, 0, 0)
        addressEdit.typeface = Fonts.sans
        addressEdit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        addressEdit.setTextColor(c.ink)
        addressEdit.setHintTextColor(c.muted)
        addressEdit.hint = "Search or enter address"
        addressEdit.contentDescription = "Address"
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
        pillLp.setMargins(0, dp(6), 0, dp(8))
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

        // Toolbar items grouped by what they do, each group on its own glass capsule (Liquid Glass guidance):
        // history on the left, tabs and the menu on the right.
        navLeft = LinearLayout(this)
        navLeft.orientation = LinearLayout.HORIZONTAL
        navLeft.gravity = Gravity.CENTER_VERTICAL
        navLeft.setPadding(dp(4), 0, dp(4), 0)
        for (b in listOf(back.first, fwd.first)) navLeft.addView(b, LinearLayout.LayoutParams(dp(52), dp(48)))
        navRight = LinearLayout(this)
        navRight.orientation = LinearLayout.HORIZONTAL
        navRight.gravity = Gravity.CENTER_VERTICAL
        navRight.setPadding(dp(4), 0, dp(4), 0)
        for (b in listOf(add.first, tabsBtn.first, menu.first)) navRight.addView(b, LinearLayout.LayoutParams(dp(52), dp(48)))
        back.first.contentDescription = "Back"
        fwd.first.contentDescription = "Forward"
        add.first.contentDescription = "New tab"
        tabsBtn.first.contentDescription = "Tabs"
        menu.first.contentDescription = "Menu"
        nav.addView(navLeft, LinearLayout.LayoutParams(WRAP, dp(52)))
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        nav.addView(navRight, LinearLayout.LayoutParams(WRAP, dp(52)))
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
        current?.session?.let { runtime.webExtensionController.setTabActive(it, false); it.setActive(false) }
        current = t
        showCurrent()
        saveSession()
    }

    private fun sessionSettings(t: BrowserTab): GeckoSessionSettings {
        val b = GeckoSessionSettings.Builder()
        if (!t.isPrivate) {
            // Each workspace gets its own cookies and storage; Personal uses the engine's normal storage.
            workspaces.contextFor(workspace)?.let { b.contextId(it) }
        }
        return b
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
        FacetExt.attach(s, t.id)
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
        FacetExt.forget(t.id)
        readerTabs.remove(t.id)
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
            val cfgHome = HomeConfig(prefs)
            val v = NewTabView(
                this, c, t.isPrivate,
                prefs.trackersBlocked + pendingBlocked.get(),
                prefs.httpsUpgraded + pendingSecure.get(),
                version, workspace, prefs.bookmarks(), sitePerms.reminders().size,
                HomeActions(
                    { u -> navigate(u) },
                    { b -> confirmRemoveBookmark(b) },
                    { host.openWorkspaces() },
                    { showCommandBar() },
                    { host.openResearch() },
                    { host.openDownloads() },
                    { showOverlay(permissionRemindersPage(host)) },
                    { newTab(null, true) }
                ),
                cfgHome, prefs,
                if (t.isPrivate) null else HomeLook.build(this, cfgHome, c.dark, c.amoled).also { homeGlass = it.glass }
            )
            if (t.isPrivate) homeGlass = null
            contentHost.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            val s = ensureSession(t)
            contentHost.addView(geckoView, FrameLayout.LayoutParams(MATCH, MATCH))
            geckoView.setSession(s)
            s.setActive(true)
            runtime.webExtensionController.setTabActive(s, true)
            val pending = t.pendingUrl
            if (pending != null) {
                t.pendingUrl = null
                s.loadUri(pending)
            }
        }
        Backdrop.set(currentBackdrop(), false)
        styleBar()
        refreshChrome()
    }

    private fun closeTab(t: BrowserTab) {
        val idx = tabs.indexOf(t)
        if (idx < 0) return
        tabs.removeAt(idx)
        disposeSession(t)
        saveSession()
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
            tab.blockedHosts.clear()
            if (tab === current) refreshChrome()
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            tab.progress = 100
            if (success && tab.secure && !tab.isPrivate) pendingSecure.incrementAndGet()
            if (success && !tab.isPrivate && !tab.showsNewTab && tab.url.startsWith("http")) history.add(tab.url, tab.title)
            saveSession()
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
            val allow = GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            val deny = GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            if (perm.permission != GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
                return GeckoResult.fromValue(deny)
            }
            val host = Urls.host(perm.uri ?: "")
            if (!tab.isPrivate) {
                val saved = sitePerms.find(host, "location")
                if (saved != null) {
                    if (saved.allowed) sitePerms.touch(host, "location")
                    return GeckoResult.fromValue(if (saved.allowed) allow else deny)
                }
            }
            val res = GeckoResult<Int>()
            val items = ArrayList<SheetItem>()
            items.add(SheetItem(Icon.CHECK, "Allow") {
                if (!tab.isPrivate) sitePerms.set(host, "location", true)
                res.complete(allow)
            })
            items.add(SheetItem(null, "Allow this time") { res.complete(allow) })
            items.add(SheetItem(null, "Block") {
                if (!tab.isPrivate) sitePerms.set(host, "location", false)
                res.complete(deny)
            })
            showSheet("$host wants to know your location", items) { res.complete(deny) }
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
            val host = Urls.host(uri)
            val types = ArrayList<String>()
            if (cam != null) types.add("camera")
            if (mic != null) types.add("microphone")
            if (!tab.isPrivate) {
                val saved = types.map { sitePerms.find(host, it) }
                if (saved.any { it != null && !it.allowed }) {
                    callback.reject()
                    return
                }
                if (saved.all { it != null && it.allowed }) {
                    types.forEach { sitePerms.touch(host, it) }
                    callback.grant(cam, mic)
                    return
                }
            }
            val what = when {
                cam != null && mic != null -> "camera and microphone"
                cam != null -> "camera"
                else -> "microphone"
            }
            val items = ArrayList<SheetItem>()
            items.add(SheetItem(Icon.CHECK, "Allow") {
                if (!tab.isPrivate) types.forEach { sitePerms.set(host, it, true) }
                callback.grant(cam, mic)
            })
            items.add(SheetItem(null, "Allow this time") { callback.grant(cam, mic) })
            items.add(SheetItem(null, "Block") {
                if (!tab.isPrivate) types.forEach { sitePerms.set(host, it, false) }
                callback.reject()
            })
            showSheet("$host wants to use your $what", items) { callback.reject() }
        }

        // ----- engine crashes: reload the page instead of leaving a blank tab

        override fun onCrash(session: GeckoSession) { recover(tab) }
        override fun onKill(session: GeckoSession) { recover(tab) }

        // ----- tracker stats

        override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
            if (event.isBlocking) {
                pendingBlocked.incrementAndGet()
                val h = Urls.host(event.uri ?: "")
                if (h.isNotEmpty()) tab.blockedHosts[h] = (tab.blockedHosts[h] ?: 0) + 1
            }
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
        bottomStack.visibility = if (on) View.GONE else View.VISIBLE
        if (on) hideSystemBars(true) else applySystemBars()
        rootFrame.requestApplyInsets()
        padContent()
    }

    private fun startDownload(url: String, contentDisposition: String?, mime: String?) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("This download type isn't supported yet")
            return
        }
        try {
            val guess = URLUtil.guessFileName(url, contentDisposition, mime)
            val name = Downloads.enqueue(this, url, guess, mime, prefs.getBool("dl_wifi_only", false))
            toast("Downloading $name")
        } catch (e: Exception) {
            toast("Couldn't start the download")
        }
    }

    // ---------------------------------------------------------------- overlays

    private var blurAnim: ValueAnimator? = null
    private var blurNow = 0f

    /** Frosts the home page behind menus and sheets (Android 12+). Web pages are drawn by the engine and stay as they are. */
    private fun updateBackdrop() {
        if (Build.VERSION.SDK_INT < 31) return
        val want = overlays.isNotEmpty() && current?.showsNewTab == true && HomeConfig(prefs).glass
        val target = if (want) 26f else 0f
        if (target == blurNow) return
        blurAnim?.cancel()
        val a = ValueAnimator.ofFloat(blurNow, target)
        a.duration = 240L
        a.addUpdateListener {
            blurNow = it.animatedValue as Float
            try {
                contentHost.setRenderEffect(if (blurNow < 0.5f) null else RenderEffect.createBlurEffect(blurNow, blurNow, Shader.TileMode.CLAMP))
            } catch (e: Exception) {
                a.cancel()
            }
        }
        a.start()
        blurAnim = a
    }

    private fun showOverlay(v: View) {
        overlays.add(v)
        updateBackdrop()
        overlayBase[v] = intArrayOf(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom)
        padOverlay(v, overlayBase[v]!!)
        val under = pageUnder()
        rootFrame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        if (isPage(v)) {
            // pages push in from the right; what was underneath drifts a little to the left
            val w = pageWidth()
            v.translationX = w
            v.animate().translationX(0f).setDuration(340L).setInterpolator(PAGE_EASE).start()
            for (u in under) u.animate().translationX(-w * 0.22f).setDuration(340L).setInterpolator(PAGE_EASE).start()
        } else if (v !is SheetView && v !is MenuPopupView) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(200L).start()
        }
    }

    /** Full-screen pages (settings and the like), as opposed to sheets, menus and pop-ups. */
    private fun isPage(v: View): Boolean =
        v !is SheetView && v !is MenuPopupView && v !is InputSheetView && v !is CommandBarView

    private fun pageWidth(): Float = (if (rootFrame.width > 0) rootFrame.width else resources.displayMetrics.widthPixels).toFloat()

    /** The views a new page slides over: the page below it, or the browser itself. */
    private fun pageUnder(): List<View> {
        for (i in overlays.size - 2 downTo 0) {
            val o = overlays[i]
            if (isPage(o)) return listOf(o)
        }
        return listOf(contentHost, bottomStack)
    }

    private fun closeTopOverlay() {
        if (overlays.isEmpty()) return
        val v = overlays.removeAt(overlays.size - 1)
        overlayBase.remove(v)
        updateBackdrop()
        if (v === tabSwitcher) tabSwitcher = null
        if (v === settingsView) settingsView = null
        if (v === popupView) { popupView = null; popupGecko?.releaseSession(); popupGecko = null; popupSession?.close(); popupSession = null; extMgr.popupClosed() }
        if (v === promptView) { promptView = null; val cb = promptCb; promptCb = null; cb?.invoke(false) }
        if (v is SheetView) {
            rootFrame.removeView(v)
            v.cancel()
            return
        }
        if (isPage(v)) {
            // back: the page slides out to the right and the one underneath comes back into place
            val w = pageWidth()
            val under = ArrayList<View>()
            var found = false
            for (i in overlays.size - 1 downTo 0) if (isPage(overlays[i])) { under.add(overlays[i]); found = true; break }
            if (!found) { under.add(contentHost); under.add(bottomStack) }
            for (u in under) u.animate().translationX(0f).setDuration(300L).setInterpolator(PAGE_EASE).start()
            v.animate().translationX(w).setDuration(300L).setInterpolator(PAGE_EASE)
                .withEndAction { rootFrame.removeView(v) }.start()
            return
        }
        v.animate().alpha(0f).setDuration(160L)
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
                overlayBase.remove(me)
                rootFrame.removeView(me)
                updateBackdrop()
            }
        }
        sheet.onCancel = onCancel
        holder[0] = sheet
        showOverlay(sheet)
    }

    private fun showTabSwitcher() {
        blurAddress()
        val v = TabSwitcherView(
            this, c, workspace.name,
            { tabs }, { current },
            { prefs.tabListMode },
            { prefs.tabListMode = if (prefs.tabListMode == 1) 0 else 1 },
            { t -> closeAllOverlays(); selectTab(t) },
            { t -> closeTab(t); refreshChrome() },
            { t -> groupSheet(t) },
            { closeAllOverlays(); newTab(null, false) },
            { closeAllOverlays(); newTab(null, true) },
            { host.openWorkspaces() },
            { closeAllOverlays() }
        )
        tabSwitcher = v
        showOverlay(v)
    }

    private fun groupSheet(t: BrowserTab) {
        val items = ArrayList<SheetItem>()
        val names = tabs.mapNotNull { it.group }.distinct()
        for (n in names) {
            if (n != t.group) items.add(SheetItem(Icon.FOLDER, "Move to $n") { t.group = n; saveSession(); tabSwitcher?.rebuild(false) })
        }
        items.add(SheetItem(Icon.PLUS, "New group") {
            showInput("Group name", "For example Trip", "", false) { name ->
                if (name.isNotBlank()) { t.group = name.trim(); saveSession(); tabSwitcher?.rebuild(false) }
            }
        })
        if (t.group != null) items.add(SheetItem(null, "Remove from group") { t.group = null; saveSession(); tabSwitcher?.rebuild(false) })
        items.add(SheetItem(Icon.TRASH, "Close tab", false, true) { closeTab(t); refreshChrome(); tabSwitcher?.rebuild(false) })
        showSheet(if (t.title.isNotBlank()) t.title else Urls.host(t.url), items)
    }

    private fun showMenu() {
        val t = current ?: return
        blurAddress()
        val onPage = !t.showsNewTab
        val url = urlOf(t)
        val items = ArrayList<SheetItem>()
        items.add(SheetItem(Icon.PLUS, "New tab") { newTab(null, false) })
        items.add(SheetItem(Icon.PRIVATE, "New private tab") { newTab(null, true) })
        items.add(SheetItem(Icon.COMMAND, "Search everything") { showCommandBar() })
        if (onPage) {
            val saved = prefs.bookmarks().any { it.url == url }
            items.add(SheetItem(Icon.STAR, if (saved) "Remove bookmark" else "Add bookmark") { toggleBookmark(t) })
            items.add(SheetItem(Icon.READER, "Reader view", readerTabs.contains(t.id)) { toggleReader(t) })
            if (!t.isPrivate) {
                items.add(SheetItem(Icon.PEN, "Highlight selection") { highlightSelection(t) })
                items.add(SheetItem(Icon.NOTE, "Save to ${if (workspace.kind == Workspace.RESEARCH) workspace.name else "research"}") {
                    ResearchStore(prefs, workspace.id).savePage(url, t.title)
                    toast("Saved")
                })
                if (ai.ready) {
                    items.add(SheetItem(Icon.SPARK, "Summarize with AI") { askAi(t, null) })
                    items.add(SheetItem(Icon.SPARK, "Ask AI about this page") {
                        showInput("Ask about this page", "Your question", "", false) { q -> if (q.isNotBlank()) askAi(t, q.trim()) }
                    })
                }
            }
            items.add(SheetItem(Icon.SHARE, "Share") { share(url) })
            items.add(SheetItem(Icon.LINK, "Copy link") { copyText(url) })
            items.add(SheetItem(Icon.DESKTOP, "Desktop site", t.desktop) { toggleDesktop(t) })
        }
        items.add(SheetItem(Icon.FOLDER, "Profile: ${workspace.name}") { host.openWorkspaces() })
        items.add(SheetItem(Icon.DOWNLOAD, "Downloads") { host.openDownloads() })
        items.add(SheetItem(Icon.GRID, "Extensions") { showExtensionsSheet() })
        items.add(SheetItem(Icon.SETTINGS, "Settings") { showSettings() })
        withBackdrop { showMenuPopup(items) }
    }

    private fun showMenuPopup(items: List<SheetItem>) {
        val holder = arrayOfNulls<MenuPopupView>(1)
        val v = MenuPopupView(this, c, items, barSpace()) {
            val me = holder[0]
            if (me != null && overlays.contains(me)) {
                overlays.remove(me)
                overlayBase.remove(me)
                rootFrame.removeView(me)
                updateBackdrop()
            }
        }
        holder[0] = v
        showOverlay(v)
    }

    private fun toggleReader(t: BrowserTab) {
        val on = !readerTabs.contains(t.id)
        val m = JSONObject().put("cmd", "reader").put("on", on)
            .put("size", prefs.getInt("reader_size", 19))
            .put("width", prefs.getInt("reader_width", 1))
            .put("theme", prefs.getInt("reader_theme", 0))
        if (FacetExt.send(t.id, m)) {
            if (on) readerTabs.add(t.id) else readerTabs.remove(t.id)
        } else {
            toast("Reader view isn't ready on this page yet. Try again in a moment.")
        }
    }

    private fun highlightSelection(t: BrowserTab) {
        if (!FacetExt.send(t.id, JSONObject().put("cmd", "highlightSelection"))) toast("Highlighting isn't ready on this page yet")
    }

    private fun askAi(t: BrowserTab, question: String?) {
        aiPending[t.id] = question ?: ""
        if (!FacetExt.send(t.id, JSONObject().put("cmd", "extractText"))) {
            aiPending.remove(t.id)
            toast("The page isn't ready yet. Try again in a moment.")
        }
    }

    private val extListener = object : FacetExt.Listener {
        private fun tabFor(id: Long) = tabs.firstOrNull { it.id == id }

        override fun onHello(tabId: Long, url: String, title: String) {
            runOnUiThread {
                val t = tabFor(tabId) ?: return@runOnUiThread
                if (t.isPrivate) return@runOnUiThread
                val list = ResearchStore(prefs, workspace.id).highlightsFor(url)
                if (list.isNotEmpty()) {
                    FacetExt.send(tabId, JSONObject().put("cmd", "highlights").put("list", JSONArray(list)))
                }
            }
        }

        override fun onHighlight(tabId: Long, url: String, title: String, text: String) {
            runOnUiThread {
                val t = tabFor(tabId) ?: return@runOnUiThread
                if (t.isPrivate || text.isBlank()) return@runOnUiThread
                ResearchStore(prefs, workspace.id).addHighlight(url, title, text)
                toast("Highlight saved")
            }
        }

        override fun onText(tabId: Long, url: String, title: String, text: String) {
            runOnUiThread {
                val q = aiPending.remove(tabId) ?: return@runOnUiThread
                val t = tabFor(tabId)
                if (t == null || t.isPrivate || !ai.ready) return@runOnUiThread
                val view = AiAnswerView(this@MainActivity, c, if (q.isEmpty()) "Summary" else "Answer", { copyText(it) }, { closeTopOverlay() })
                showOverlay(view)
                val system = "You help a person read a web page. Answer only from the page text you are given. Be concise and plain."
                val user = if (q.isEmpty()) "Summarize this page in a few short paragraphs or bullet points.\n\nTitle: $title\n\n$text"
                else "Question: $q\n\nTitle: $title\n\n$text"
                Thread {
                    val out = try { ai.ask(system, user) } catch (e: Exception) { "Couldn't get an answer: ${e.message}" }
                    runOnUiThread { view.show(out) }
                }.start()
            }
        }

        override fun onReaderSettings(size: Int, width: Int, theme: Int) {
            prefs.putInt("reader_size", size)
            prefs.putInt("reader_width", width)
            prefs.putInt("reader_theme", theme)
        }

        override fun onToast(text: String) { runOnUiThread { toast(text) } }

        override fun onReaderClosed(tabId: Long) { runOnUiThread { readerTabs.remove(tabId) } }
    }

    // ---------------------------------------------------------------- command bar

    private fun showCommandBar() {
        blurAddress()
        val v = CommandBarView(this, c, { buildCommands() }, { q ->
            val out = ArrayList<Command>()
            if (q.isNotEmpty()) {
                val looksLikeUrl = !q.contains(" ") && q.contains(".")
                if (looksLikeUrl) out.add(Command("Go", "Open $q", "", Icon.GLOBE) { navigate(q) })
                out.add(Command("Search", "Search the web for \"$q\"", "", Icon.SEARCH) { navigate(q) })
            }
            out
        }, { closeTopOverlay() })
        showOverlay(v)
    }

    private fun buildCommands(): List<Command> {
        val out = ArrayList<Command>()
        for (t in tabs) {
            val label = if (t.title.isNotBlank()) t.title else if (t.showsNewTab) "New tab" else Urls.host(t.url)
            out.add(Command("Tab", label, if (t.showsNewTab) "" else Urls.host(t.url), if (t.isPrivate) Icon.PRIVATE else Icon.TABS) { selectTab(t) })
        }
        out.add(Command("Command", "New tab", "", Icon.PLUS) { newTab(null, false) })
        out.add(Command("Command", "New private tab", "", Icon.PRIVATE) { newTab(null, true) })
        out.add(Command("Command", "Reader view", "Toggle on this page", Icon.READER) { current?.let { if (!it.showsNewTab) toggleReader(it) } })
        out.add(Command("Command", "Downloads", "", Icon.DOWNLOAD) { host.openDownloads() })
        out.add(Command("Setting", "Extensions", "Add and manage", Icon.COMMAND) { host.openExtensions() })
        for (e in extMgr.installed) if (extMgr.actionOf(e) != null) out.add(Command("Command", e.metaData.name ?: e.id, "Extension", Icon.COMMAND) { extMgr.click(e) })
        out.add(Command("Command", "Profiles", workspace.name, Icon.FOLDER) { host.openWorkspaces() })
        out.add(Command("Command", "Research", "Notes and highlights", Icon.NOTE) { host.openResearch() })
        out.add(Command("Command", "Clear browsing data", "", Icon.TRASH) { host.clearData() })
        out.add(Command("Setting", "Settings", "", Icon.SETTINGS) { showSettings() })
        out.add(Command("Setting", "Privacy and security", "Trackers, cookies, HTTPS", Icon.SHIELD) { showOverlay(privacyPage(host)) })
        out.add(Command("Setting", "Site permissions", "Camera, microphone, location", Icon.LOCK) { host.openSitePermissions() })
        out.add(Command("Setting", "Tabs and groups", "", Icon.TABS) { showOverlay(tabsPage(host)) })
        out.add(Command("Setting", "Search engine", "", Icon.SEARCH) { showOverlay(enginePage(host)) })
        out.add(Command("Setting", "Appearance", "Theme", Icon.PHONE) { showOverlay(appearancePage(host)) })
        out.add(Command("Setting", "AI assistant", "Optional", Icon.SPARK) { host.openAiSettings() })
        out.add(Command("Setting", "Software update", "", Icon.REFRESH) { host.openUpdates() })
        for (b in prefs.bookmarks()) out.add(Command("Bookmark", if (b.title.isNotBlank()) b.title else Urls.host(b.url), Urls.host(b.url), Icon.STAR) { navigate(b.url) })
        if (!current!!.isPrivate) {
            for (e in history.all().take(200)) out.add(Command("History", if (e.title.isNotBlank()) e.title else Urls.host(e.url), Urls.host(e.url), Icon.GLOBE) { navigate(e.url) })
        }
        return out
    }

    private fun pickWallpaperImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_WALL)
        } catch (e: ActivityNotFoundException) {
            toast("No file picker found")
        }
    }

    private fun pickExtensionFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_EXT)
        } catch (e: ActivityNotFoundException) {
            toast("No file picker found")
        }
    }

    private fun showExtensionsSheet() {
        val items = ArrayList<SheetItem>()
        for (e in extMgr.installed) {
            if (!e.metaData.enabled || extMgr.actionOf(e) == null) continue
            items.add(SheetItem(null, e.metaData.name ?: e.id) { extMgr.click(e) })
        }
        items.add(SheetItem(Icon.SETTINGS, "Manage extensions") { host.openExtensions() })
        showSheet("Extensions", items)
    }

    private val extHooks = ExtHooks(
        { url, active ->
            val nt = BrowserTab(nextId++, false)
            nt.showsNewTab = false
            nt.closeOnBack = true
            nt.url = url ?: ""
            val ns = GeckoSession(sessionSettings(nt))
            attachDelegates(nt, ns)
            nt.session = ns
            tabs.add(nt)
            if (active) geckoView.post { selectTab(nt) }
            ns
        },
        { u -> closeAllOverlays(); newTab(u, false) },
        { title, session -> showExtPopup(title, session) },
        { closeExtPopup() },
        { title, lines, ok, decide -> showExtPrompt(title, lines, ok, decide) },
        { msg -> toast(msg) }
    )

    private fun closeExtPopup() {
        val v = popupView ?: return
        overlays.remove(v)
        popupView = null
        popupGecko?.releaseSession()
        popupGecko = null
        popupSession?.close()
        popupSession = null
        rootFrame.removeView(v)
        extMgr.popupClosed()
    }

    private fun showExtPopup(title: String, session: GeckoSession) {
        closeExtPopup()
        val frame = FrameLayout(this)
        frame.setBackgroundColor(android.graphics.Color.argb(140, 0, 0, 0))
        frame.isClickable = true
        frame.setOnClickListener { closeTopOverlay() }
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = shape(c.bg, dpf(24f), c.line, dp(1))
        card.isClickable = true
        val head = makeText(this, title, 16f, c.ink, Fonts.sansMedium)
        head.setPadding(dp(18), dp(14), dp(18), dp(10))
        card.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        val gv = GeckoView(this)
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) { closeTopOverlay() }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                closeAllOverlays()
                newTab(uri, false)
                return null
            }
        }
        if (!session.isOpen) session.open(runtime)
        gv.setSession(session)
        card.addView(gv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val lp = FrameLayout.LayoutParams(MATCH, dp(520), Gravity.CENTER)
        lp.setMargins(dp(14), dp(40), dp(14), dp(40))
        frame.addView(card, lp)
        popupView = frame
        popupGecko = gv
        popupSession = session
        showOverlay(frame)
    }

    private fun showExtPrompt(title: String, lines: List<String>, okLabel: String, decide: (Boolean) -> Unit) {
        val done = booleanArrayOf(false)
        val answer: (Boolean) -> Unit = { yes ->
            if (!done[0]) {
                done[0] = true
                promptView = null
                promptCb = null
                decide(yes)
            }
        }
        val holder = arrayOfNulls<View>(1)
        val view = PageView(this, c, title, { closeTopOverlay() }) { b ->
            b.section("It will be able to")
            for (l in lines) b.info(l, "")
            b.section("Decide")
            b.row(okLabel, "Private tabs stay off limits unless you allow it later") {
                answer(true)
                holder[0]?.let { v -> overlays.remove(v); rootFrame.removeView(v) }
            }
            b.row("Cancel", null) { closeTopOverlay() }
        }
        holder[0] = view
        promptView = view
        promptCb = answer
        showOverlay(view)
    }

    private fun showSiteInfo() {
        val t = current ?: return
        if (t.showsNewTab) return
        blurAddress()
        showOverlay(siteInfoPage(host, Urls.host(t.url), t.secure || t.url.startsWith("https://"), t.blockedHosts))
    }

    // ---------------------------------------------------------------- settings host + workspaces

    private val host = object : SettingsHost {
        override val context: Context get() = this@MainActivity
        override val prefs: Prefs get() = this@MainActivity.prefs
        override val colors: Colors get() = c
        override val version: String get() = this@MainActivity.version
        override fun push(v: View) = showOverlay(v)
        override fun back() = closeTopOverlay()
        override fun toast(msg: String) = this@MainActivity.toast(msg)
        override fun sheet(title: String?, items: List<SheetItem>, onCancel: (() -> Unit)?) = showSheet(title, items, onCancel)
        override fun input(title: String, hint: String, initial: String, secret: Boolean, save: (String) -> Unit) =
            showInput(title, hint, initial, secret, save)
        override fun applyEngineSettings() { FacetApp.applyPrefs(runtime, prefs) }
        override fun applyTheme(mode: Int) = applyThemeMode(mode)
        override fun clearData() {
            val items = ArrayList<SheetItem>()
            items.add(SheetItem(Icon.TRASH, "Clear cookies, cache and site data", false, true) { clearBrowsingData() })
            items.add(SheetItem(null, "Cancel") { })
            showSheet("Clear browsing data?", items)
        }
        override fun blockedCount(): Long = pendingBlocked.get().toLong()
        override fun openUpdates() = showUpdates()
        override fun openDownloads() {
            showOverlay(DownloadsView(this@MainActivity, c, prefs, { this@MainActivity.toast(it) }, { closeTopOverlay() }))
        }
        override fun openWorkspaces() { showOverlay(workspacesPage(this)) }
        override fun openSitePermissions() { showOverlay(sitePermissionsPage(this)) }
        override fun openResearch() {
            val store = ResearchStore(prefs, workspace.id)
            showOverlay(ResearchView(
                this@MainActivity, c, workspace.name, store,
                current?.let { !it.showsNewTab && !it.isPrivate } ?: false,
                { current?.let { store.savePage(it.url, it.title); this@MainActivity.toast("Saved") } },
                { u -> closeAllOverlays(); newTab(u, false) },
                { md -> share(md) },
                { closeTopOverlay() }
            ))
        }
        override fun openAiSettings() { showOverlay(aiPage(this)) }
        override fun openExtensions() { showOverlay(extensionsPage(this)) }
        override fun applySystemUi() { applySystemBars(); rootFrame.requestApplyInsets() }
        override fun openHome() { showOverlay(homePage(this)) }
        override fun homeChanged() { if (current?.showsNewTab == true) showCurrent() }
        override fun pickWallpaper() { pickWallpaperImage() }
        override fun openUrl(url: String) { closeAllOverlays(); newTab(url, false) }
        override val extensions: ExtensionManager get() = extMgr
        override fun switchWorkspace(id: String) = this@MainActivity.switchWorkspace(id)
        override fun createWorkspace(kind: Int) {
            if (kind == Workspace.TEMPORARY) {
                val w = workspaces.add("Temporary", kind)
                this@MainActivity.switchWorkspace(w.id)
            } else {
                showInput("Name", if (kind == Workspace.RESEARCH) "Topic, for example Solar panels" else "For example Work", "", false) { name ->
                    val w = workspaces.add(name, kind)
                    this@MainActivity.switchWorkspace(w.id)
                }
            }
        }
        override fun deleteWorkspace(id: String) {
            val w = workspaces.all().firstOrNull { it.id == id } ?: return
            if (id == prefs.workspaceId) this@MainActivity.switchWorkspace("personal", keepLeaving = true)
            wipeWorkspace(w)
            this@MainActivity.toast("Deleted ${w.name}")
            closeTopOverlay()
            showOverlay(workspacesPage(this))
        }
        override fun renameWorkspace(id: String) {
            val w = workspaces.all().firstOrNull { it.id == id } ?: return
            showInput("Rename", "Name", w.name, false) { name ->
                workspaces.rename(id, name)
                workspace = workspaces.current()
                closeTopOverlay()
                showOverlay(workspacesPage(this))
            }
        }
        override fun openBlockedReport() {
            val t = current ?: return
            showOverlay(blockedReportPage(this, Urls.host(t.url), t.blockedHosts))
        }
        override fun reopen(page: () -> View) { closeTopOverlay(); showOverlay(page()) }
    }

    /** Removes a workspace and everything the engine stored for it. */
    private fun wipeWorkspace(w: Workspace) {
        workspaces.contextFor(w)?.let {
            try { runtime.storageController.clearDataForSessionContext(it) } catch (e: Exception) { }
        }
        workspaces.remove(w.id)
        if (prefs.workspaceId == w.id) prefs.workspaceId = "personal"
    }

    private fun switchWorkspace(id: String, keepLeaving: Boolean = false) {
        if (id == prefs.workspaceId) { closeAllOverlays(); return }
        saveSession()
        val leaving = workspace
        for (t in tabs.filter { !it.isPrivate }) disposeSession(t)
        tabs.removeAll { !it.isPrivate }
        current = null
        if (leaving.kind == Workspace.TEMPORARY && !keepLeaving) wipeWorkspace(leaving)
        prefs.workspaceId = id
        workspace = workspaces.current()
        closeAllOverlays()
        restoreSession()
        if (tabs.none { !it.isPrivate }) newTab(null, false)
        if (current == null) selectTab(tabs.last()) else showCurrent()
        toast("Profile: ${workspace.name}")
    }

    private fun showSettings() {
        val sv = SettingsView(this, host)
        settingsView = sv
        showOverlay(sv)
    }

    private fun showUpdates() {
        val uv = UpdatesView(
            this, c, prefs, version,
            { closeTopOverlay(); settingsView?.refresh() },
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
                overlayBase.remove(me)
                rootFrame.removeView(me)
                updateBackdrop()
            }
        }
        holder[0] = v
        overlays.add(v)
        overlayBase[v] = intArrayOf(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom)
        padOverlay(v, overlayBase[v]!!)
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
            val o = JSONObject()
            o.put("u", if (t.showsNewTab) "" else urlOf(t))
            t.group?.let { o.put("g", it) }
            arr.put(o)
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
                val item = arr.opt(i)
                val u: String
                var g: String? = null
                if (item is JSONObject) {
                    u = item.optString("u", "")
                    g = item.optString("g", "").ifEmpty { null }
                } else {
                    u = arr.optString(i, "")
                }
                val t = newTab(if (u.isEmpty()) null else u, false, false)
                t.group = g
                if (i == sel) toSelect = t
            }
            val pick = toSelect ?: tabs.lastOrNull()
            if (pick != null) selectTab(pick)
            if (crashedLastTime) {
                crashedLastTime = false
                toast("Facet stopped unexpectedly. Your tabs are back.")
            }
        } catch (e: Exception) {
            // bad session data: start fresh
        }
    }

    companion object {
        private const val REQ_FILE = 1001
        private const val REQ_GECKO = 1002
        private const val REQ_PERM = 1003
        private const val REQ_EXT = 1004
        private const val REQ_WALL = 1005
    }
}
