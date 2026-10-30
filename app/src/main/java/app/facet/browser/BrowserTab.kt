package app.facet.browser

import android.webkit.WebView

class BrowserTab(val id: Long, val isPrivate: Boolean) {
    var webView: WebView? = null
    var title: String = ""
    var url: String = ""
    var progress: Int = 0
    var desktop: Boolean = false

    /** True while the tab is showing Facet's new tab page instead of a website. */
    var showsNewTab: Boolean = true

    /** URL to load the first time this tab's WebView is created (restored sessions). */
    var pendingUrl: String? = null

    /** Opened by a page (target=_blank): Back closes it once there is no history left. */
    var closeOnBack: Boolean = false

    /** Set when we rewrote http to https, so a failure can fall back to the original. */
    var upgradedFrom: String? = null
    var skipUpgradeHost: String? = null
}
