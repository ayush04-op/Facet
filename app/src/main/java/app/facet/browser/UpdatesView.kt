package app.facet.browser

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

/** Thin rounded progress bar. */
private class Bar(ctx: Context, private val track: Int, private val fill: Int) : View(ctx) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        p.color = track
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2, h / 2, p)
        p.color = fill
        canvas.drawRoundRect(RectF(0f, 0f, width * fraction, h), h / 2, h / 2, p)
    }
}

/** Software update page: update state, this build, device status and update source. */
class UpdatesView(
    private val ctx: Context,
    private val c: Colors,
    private val prefs: Prefs,
    private val version: String,
    private val onBack: () -> Unit,
    private val onEdit: (title: String, hint: String, initial: String, secret: Boolean, save: (String) -> Unit) -> Unit
) : LinearLayout(ctx) {

    private enum class S { CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, INSTALLING, ERROR }

    private val kit = RowKit(ctx, c)
    private val body = LinearLayout(ctx)
    private var state = S.CHECKING
    private var release: Release? = null
    private var error = ""
    private var apk: File? = null
    @Volatile private var cancelled = false
    private var bar: Bar? = null
    private var progressText: android.widget.TextView? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(c.bg)
        isClickable = true
        addView(kit.header("Software update", onBack), LayoutParams(MATCH, WRAP))
        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        body.orientation = VERTICAL
        body.setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(32))
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, 0, 1f))
        render()
        check()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelled = true
        Updater.installListener = null
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && (state == S.READY || state == S.AVAILABLE)) render()
    }

    // ------------------------------------------------------------ actions

    private fun check() {
        state = S.CHECKING
        render()
        val repo = prefs.updateRepo
        val token = prefs.updateToken
        Thread {
            var rel: Release? = null
            var err: String? = null
            try { rel = Updater.latest(repo, token) } catch (e: Exception) { err = e.message ?: "Could not check for updates" }
            post {
                if (!isAttachedToWindow) return@post
                if (err != null) {
                    error = err; state = S.ERROR
                } else {
                    prefs.lastUpdateCheck = System.currentTimeMillis()
                    release = rel
                    if (rel != null && Updater.isNewer(rel.tag, version)) {
                        prefs.availableUpdate = rel.tag; state = S.AVAILABLE
                    } else {
                        prefs.availableUpdate = ""; state = S.UP_TO_DATE
                    }
                }
                render()
            }
        }.start()
    }

    private fun download() {
        val r = release ?: return
        cancelled = false
        state = S.DOWNLOADING
        render()
        val token = prefs.updateToken
        Thread {
            var f: File? = null
            var err: String? = null
            try {
                f = Updater.download(ctx, r, token, { cancelled }) { done, total ->
                    post { updateProgress(done, total) }
                }
            } catch (e: Exception) { err = e.message ?: "Download failed" }
            post {
                if (!isAttachedToWindow) return@post
                if (err == "Cancelled") { state = S.AVAILABLE }
                else if (err != null) { error = err; state = S.ERROR }
                else { apk = f; state = S.READY }
                render()
            }
        }.start()
    }

    private fun install() {
        val f = apk ?: return
        if (!Updater.canInstall(ctx)) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            )
            return
        }
        state = S.INSTALLING
        render()
        Updater.installListener = { ok, msg ->
            post {
                if (ok) { state = S.UP_TO_DATE } else { error = msg; state = S.ERROR }
                render()
            }
        }
        try {
            Updater.install(ctx, f)
        } catch (e: Exception) {
            error = e.message ?: "Could not start the installer"
            state = S.ERROR
            render()
        }
    }

    private fun updateProgress(done: Long, total: Long) {
        if (state != S.DOWNLOADING) return
        bar?.fraction = if (total > 0) done.toFloat() / total else 0f
        progressText?.text = DeviceInfo.bytes(done) + if (total > 0) " of " + DeviceInfo.bytes(total) else ""
    }

    // ------------------------------------------------------------ drawing

    private fun ago(t: Long): String {
        if (t == 0L) return "never"
        val s = (System.currentTimeMillis() - t) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }

    private fun hero() {
        val card = BentoCard(ctx, c)
        bar = null
        progressText = null
        val r = release
        val (title, sub) = when (state) {
            S.CHECKING -> Pair("Checking for updates", "Contacting GitHub Releases for ${prefs.updateRepo}")
            S.UP_TO_DATE -> Pair("Facet is up to date", "Version $version · Last checked ${ago(prefs.lastUpdateCheck)}")
            S.AVAILABLE -> Pair(
                "${r?.tag ?: "A new version"} is ready",
                (if (r != null && r.size > 0) DeviceInfo.bytes(r.size) + " · " else "") + "You have version $version"
            )
            S.DOWNLOADING -> Pair("Downloading ${r?.tag ?: "update"}", "Keep Facet open until this finishes")
            S.READY -> Pair("Ready to install", "Downloaded and checked. Android will ask you to confirm.")
            S.INSTALLING -> Pair("Waiting for the installer", "Confirm the installation in the Android prompt.")
            S.ERROR -> Pair("Couldn't update", error)
        }
        card.addView(makeTag(ctx, "STATUS", c.accent))
        val t = makeText(ctx, title, 28f, c.ink, Fonts.serif)
        val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
        tlp.topMargin = ctx.dp(10)
        card.addView(t, tlp)
        val s = makeText(ctx, sub, 14f, c.muted, Fonts.sans)
        s.setLineSpacing(0f, 1.2f)
        val slp = LinearLayout.LayoutParams(WRAP, WRAP)
        slp.topMargin = ctx.dp(8)
        card.addView(s, slp)

        if (state == S.DOWNLOADING) {
            val b = Bar(ctx, c.line, c.accent)
            bar = b
            val blp = LinearLayout.LayoutParams(MATCH, ctx.dp(6))
            blp.topMargin = ctx.dp(18)
            card.addView(b, blp)
            val pt = makeText(ctx, "Starting", 13f, c.muted, Fonts.mono)
            progressText = pt
            val plp = LinearLayout.LayoutParams(WRAP, WRAP)
            plp.topMargin = ctx.dp(8)
            card.addView(pt, plp)
        }

        val action: Pair<String, () -> Unit>? = when (state) {
            S.UP_TO_DATE, S.ERROR -> Pair(if (state == S.ERROR) "Try again" else "Check again") { check() }
            S.AVAILABLE -> Pair("Download and install") { download() }
            S.DOWNLOADING -> Pair("Cancel") { cancelled = true }
            S.READY -> Pair(if (Updater.canInstall(ctx)) "Install" else "Allow installs, then return") { install() }
            else -> null
        }
        if (action != null) {
            val btn = kit.button(action.first, state != S.DOWNLOADING && state != S.ERROR && state != S.UP_TO_DATE, action.second)
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = ctx.dp(20)
            card.addView(btn, lp)
        }
        body.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun render() {
        body.removeAllViews()
        hero()

        val r = release
        if (r != null && state != S.UP_TO_DATE && r.notes.isNotBlank()) {
            body.addView(kit.section("What's new"))
            val g = kit.group()
            val t = makeText(ctx, r.notes.trim().take(1200), 14f, c.ink, Fonts.sans)
            t.setLineSpacing(0f, 1.2f)
            t.setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            g.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
            body.addView(g, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        body.addView(kit.section("This build"))
        val build = kit.group()
        for (l in DeviceInfo.build(ctx)) kit.add(build, kit.info(l.label, l.value))
        body.addView(build, LinearLayout.LayoutParams(MATCH, WRAP))

        body.addView(kit.section("Device status"))
        val dev = kit.group()
        val ready = DeviceInfo.readiness(ctx)
        kit.add(dev, kit.info("Update readiness", ready.second))
        for (l in DeviceInfo.device(ctx)) kit.add(dev, kit.info(l.label, l.value))
        body.addView(dev, LinearLayout.LayoutParams(MATCH, WRAP))

        body.addView(kit.section("Update source"))
        val src = kit.group()
        kit.add(src, kit.value(Icon.LINK, "Repository", prefs.updateRepo) {
            onEdit("Update repository", "owner/name", prefs.updateRepo, false) { prefs.updateRepo = it; check() }
        })
        kit.add(src, kit.value(Icon.LOCK, "Access token", if (prefs.updateToken.isEmpty()) "Not set" else "Set") {
            onEdit("GitHub access token", "Only needed for private repositories", prefs.updateToken, true) {
                prefs.updateToken = it; check()
            }
        })
        kit.add(src, kit.toggle(Icon.REFRESH, "Check automatically", "Looks for a new release when Facet starts, at most twice a day.", prefs.autoCheckUpdates) {
            prefs.autoCheckUpdates = it
        })
        body.addView(src, LinearLayout.LayoutParams(MATCH, WRAP))
        body.addView(kit.note("Last checked ${ago(prefs.lastUpdateCheck)}. The token stays on this device and is only sent to api.github.com."))
        if (r != null && r.published.isNotEmpty()) {
            body.addView(kit.note("Latest release: ${r.tag}" + if (r.assetName.isNotEmpty()) " (${r.assetName})" else ""))
        }
    }
}
