package app.facet.browser

import android.app.Application
import android.content.Context
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/** Owns the one GeckoRuntime (Mozilla's engine) that every tab shares. */
class FacetApp : Application() {

    companion object {
        private var runtime: GeckoRuntime? = null

        fun runtime(ctx: Context): GeckoRuntime {
            val existing = runtime
            if (existing != null) return existing
            val prefs = Prefs(ctx)
            val settings = GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false)
                .remoteDebuggingEnabled(false)
                .consoleOutput(false)
                .build()
            val created = GeckoRuntime.create(ctx.applicationContext, settings)
            runtime = created
            applyPrefs(created, prefs)
            return created
        }

        /** Pushes Facet's privacy toggles into the engine. Safe to call at any time. */
        fun applyPrefs(rt: GeckoRuntime, prefs: Prefs) {
            val s = rt.settings
            s.setJavaScriptEnabled(prefs.javascript)
            s.setAllowInsecureConnections(
                if (prefs.httpsUpgrade) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL
            )
            val cb = s.contentBlocking
            cb.setEnhancedTrackingProtectionLevel(
                if (prefs.blockTrackers) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.NONE
            )
            cb.setCookieBehavior(
                if (prefs.blockThirdPartyCookies) ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
                else ContentBlocking.CookieBehavior.ACCEPT_ALL
            )
            cb.setSafeBrowsing(
                if (prefs.safeBrowsing) ContentBlocking.SafeBrowsing.DEFAULT else ContentBlocking.SafeBrowsing.NONE
            )
        }
    }
}
