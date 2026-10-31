package app.facet.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class DlItem(
    val id: Long, val name: String, val url: String, val status: Int,
    val done: Long, val total: Long, val mime: String, val localUri: String?
) {
    val running: Boolean get() = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED
    val finished: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
    val category: String get() = Downloads.categoryOf(name, mime)
}

/** One place for every kind of file: documents, media, apps and archives, using Android's own download service. */
object Downloads {
    val categories = listOf("All", "Documents", "Images", "Video", "Audio", "Apps", "Archives", "Other")

    fun categoryOf(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "odt", "ods", "csv", "rtf", "md") -> "Documents"
            ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "avif") || mime.startsWith("image/") -> "Images"
            ext in setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp") || mime.startsWith("video/") -> "Video"
            ext in setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac") || mime.startsWith("audio/") -> "Audio"
            ext in setOf("apk", "xapk", "apks") -> "Apps"
            ext in setOf("zip", "rar", "7z", "tar", "gz", "xz", "bz2") -> "Archives"
            else -> "Other"
        }
    }

    fun enqueue(ctx: Context, url: String, suggestedName: String?, mime: String?, wifiOnly: Boolean): String {
        require(url.startsWith("http://") || url.startsWith("https://")) { "Only web links can be downloaded" }
        val name = suggestedName ?: URLUtil.guessFileName(url, null, mime)
        val req = DownloadManager.Request(Uri.parse(url))
        if (!mime.isNullOrEmpty()) req.setMimeType(mime)
        req.setTitle(name)
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        if (wifiOnly) req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        return name
    }

    fun list(ctx: Context): List<DlItem> {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val out = ArrayList<DlItem>()
        val cur = dm.query(DownloadManager.Query())
        try {
            val iId = cur.getColumnIndex(DownloadManager.COLUMN_ID)
            val iTitle = cur.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val iUri = cur.getColumnIndex(DownloadManager.COLUMN_URI)
            val iStatus = cur.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val iDone = cur.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val iTotal = cur.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val iMime = cur.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
            val iLocal = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            while (cur.moveToNext()) {
                out.add(DlItem(
                    cur.getLong(iId), cur.getString(iTitle) ?: "download", cur.getString(iUri) ?: "",
                    cur.getInt(iStatus), cur.getLong(iDone), cur.getLong(iTotal),
                    cur.getString(iMime) ?: "", cur.getString(iLocal)
                ))
            }
        } finally {
            cur.close()
        }
        return out
    }

    fun remove(ctx: Context, id: Long) {
        (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
    }

    fun open(ctx: Context, d: DlItem) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(d.id) ?: return
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, dm.getMimeTypeForDownloadedFile(d.id) ?: "*/*")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: ActivityNotFoundException) { /* no app can open it */ }
    }

    fun share(ctx: Context, d: DlItem) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(d.id) ?: return
        val i = Intent(Intent.ACTION_SEND)
        i.type = dm.getMimeTypeForDownloadedFile(d.id) ?: "*/*"
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Share file"))
    }

    fun bytes(n: Long): String {
        if (n <= 0) return ""
        val mb = n / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024.0)
        else if (mb >= 1) String.format(Locale.US, "%.1f MB", mb)
        else String.format(Locale.US, "%.0f KB", n / 1024.0)
    }
}

class DownloadsView(
    private val ctx: Context,
    private val c: Colors,
    private val prefs: Prefs,
    private val toast: (String) -> Unit,
    private val onBack: () -> Unit
) : LinearLayout(ctx) {

    private val list = LinearLayout(ctx)
    private val chips = LinearLayout(ctx)
    private var filter = "All"
    private var polling = false
    private val field = EditText(ctx)

    private val tick = object : Runnable {
        override fun run() {
            if (!polling) return
            render()
            postDelayed(this, 1000L)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(c.bg)
        isClickable = true
        addView(pageHeader(ctx, c, "Downloads", onBack, null), LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(ctx)
        col.orientation = VERTICAL
        col.setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(40))
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, 0, 1f))

        // add by link
        val add = BentoCard(ctx, c)
        add.addView(makeTag(ctx, "Add by link", c.accent))
        field.hint = "Paste one or more links, one per line"
        field.setHintTextColor(c.muted)
        field.setTextColor(c.ink)
        field.typeface = Fonts.sans
        field.textSize = 15f
        field.minLines = 2
        field.maxLines = 5
        field.gravity = Gravity.TOP
        field.background = shape(c.bg, ctx.dpf(14f), c.line, ctx.dp(1))
        field.setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        val flp = LayoutParams(MATCH, WRAP)
        flp.topMargin = ctx.dp(12)
        add.addView(field, flp)
        val go = pill("Download", true) { startFromField() }
        val glp = LayoutParams(MATCH, ctx.dp(48))
        glp.topMargin = ctx.dp(12)
        add.addView(go, glp)
        val wifi = SettingsRow.toggle(ctx, c, "Wi-Fi only", "Wait for Wi-Fi instead of using mobile data.", prefs.getBool("dl_wifi_only", false)) {
            prefs.putBool("dl_wifi_only", it)
        }
        val wlp = LayoutParams(MATCH, WRAP)
        wlp.topMargin = ctx.dp(8)
        add.addView(wifi, wlp)
        col.addView(add, LayoutParams(MATCH, WRAP))

        // filters
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        chips.orientation = HORIZONTAL
        hs.addView(chips, FrameLayout.LayoutParams(WRAP, WRAP))
        val hlp = LayoutParams(MATCH, WRAP)
        hlp.topMargin = ctx.dp(16)
        col.addView(hs, hlp)

        val llp = LayoutParams(MATCH, WRAP)
        llp.topMargin = ctx.dp(10)
        list.orientation = VERTICAL
        col.addView(list, llp)
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        polling = true
        postDelayed(tick, 1000L)
    }

    override fun onDetachedFromWindow() {
        polling = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun pill(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        val fg = if (primary) 0xFFFFFFFF.toInt() else c.ink
        val t = makeText(ctx, text, 15f, fg, Fonts.sansMedium)
        t.gravity = Gravity.CENTER
        t.pressable(
            if (primary) shape(c.accent, ctx.dpf(24f)) else shape(c.surface, ctx.dpf(24f), c.line, ctx.dp(1)),
            withAlpha(fg, 0x33), ctx.dpf(24f)
        )
        t.setOnClickListener { onClick() }
        t.pressScale(0.96f)
        return t
    }

    private fun startFromField() {
        val links = field.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (links.isEmpty()) { toast("Paste a link first"); return }
        var started = 0
        for (l in links) {
            try {
                Downloads.enqueue(ctx, l, null, null, prefs.getBool("dl_wifi_only", false))
                started++
            } catch (e: Exception) {
                toast("Skipped: $l")
            }
        }
        if (started > 0) { toast(if (started == 1) "Download started" else "$started downloads started"); field.setText("") }
        render()
    }

    private fun render() {
        val all = Downloads.list(ctx).sortedByDescending { it.id }

        chips.removeAllViews()
        for (cat in Downloads.categories) {
            val n = if (cat == "All") all.size else all.count { it.category == cat }
            if (cat != "All" && n == 0 && cat != filter) continue
            val on = cat == filter
            val t = makeText(ctx, "$cat $n", 14f, if (on) 0xFFFFFFFF.toInt() else c.ink, Fonts.sansMedium)
            t.setPadding(ctx.dp(16), ctx.dp(9), ctx.dp(16), ctx.dp(9))
            t.pressable(
                if (on) shape(c.accent, ctx.dpf(20f)) else shape(c.surface, ctx.dpf(20f), c.line, ctx.dp(1)),
                withAlpha(c.ink, 0x22), ctx.dpf(20f)
            )
            t.setOnClickListener { filter = cat; render() }
            val lp = LayoutParams(WRAP, WRAP)
            lp.rightMargin = ctx.dp(8)
            chips.addView(t, lp)
        }

        list.removeAllViews()
        val shown = if (filter == "All") all else all.filter { it.category == filter }
        if (shown.isEmpty()) {
            val t = hint(ctx, c, "Nothing here yet. Downloads you start from any page appear here too.")
            t.setPadding(ctx.dp(6), ctx.dp(16), ctx.dp(6), 0)
            list.addView(t)
            return
        }
        for (d in shown) list.addView(row(d), LayoutParams(MATCH, WRAP).also { it.topMargin = ctx.dp(8) })
    }

    private fun row(d: DlItem): View {
        val card = LinearLayout(ctx)
        card.orientation = VERTICAL
        card.setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(8), ctx.dp(14))
        card.pressable(shape(c.surface, ctx.dpf(18f), c.line, ctx.dp(1)), withAlpha(c.ink, 0x14), ctx.dpf(18f))
        if (d.finished) card.setOnClickListener { Downloads.open(ctx, d) }

        val top = LinearLayout(ctx)
        top.orientation = HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val texts = LinearLayout(ctx)
        texts.orientation = VERTICAL
        val name = makeText(ctx, d.name, 16f, c.ink, Fonts.sansMedium)
        name.maxLines = 2
        name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        texts.addView(name)
        val state = when {
            d.finished -> "Done"
            d.status == DownloadManager.STATUS_FAILED -> "Failed"
            d.status == DownloadManager.STATUS_PAUSED -> "Waiting for network"
            d.status == DownloadManager.STATUS_PENDING -> "Starting"
            else -> if (d.total > 0) "${(100 * d.done / d.total)}%" else "Downloading"
        }
        val size = if (d.total > 0) Downloads.bytes(d.total) else Downloads.bytes(d.done)
        val meta = makeText(ctx, listOf(d.category, size, state).filter { it.isNotEmpty() }.joinToString("  ·  "), 13f, c.muted, Fonts.sans)
        val mlp = LayoutParams(WRAP, WRAP)
        mlp.topMargin = ctx.dp(4)
        texts.addView(meta, mlp)
        top.addView(texts, LayoutParams(0, WRAP, 1f))

        if (d.finished) top.addView(iconAction(Icon.SHARE) { Downloads.share(ctx, d) })
        if (d.status == DownloadManager.STATUS_FAILED) top.addView(iconAction(Icon.REFRESH) {
            try { Downloads.enqueue(ctx, d.url, d.name, d.mime, prefs.getBool("dl_wifi_only", false)); Downloads.remove(ctx, d.id) } catch (e: Exception) { toast("Couldn't retry") }
            render()
        })
        top.addView(iconAction(if (d.running) Icon.CLOSE else Icon.TRASH) { Downloads.remove(ctx, d.id); render() })
        card.addView(top, LayoutParams(MATCH, WRAP))

        if (d.running && d.total > 0) {
            val bar = ProgressLine(ctx, c)
            bar.progress = (100 * d.done / d.total).toInt()
            val blp = LayoutParams(MATCH, ctx.dp(3))
            blp.topMargin = ctx.dp(10)
            blp.rightMargin = ctx.dp(8)
            card.addView(bar, blp)
        }
        return card
    }

    private fun iconAction(icon: Icon, onClick: () -> Unit): View {
        val f = FrameLayout(ctx)
        f.pressable(null, withAlpha(c.ink, 0x22), ctx.dpf(20f))
        f.addView(IconView(ctx, icon, c.muted, 20), FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER))
        f.setOnClickListener { onClick() }
        return f.also { it.layoutParams = LayoutParams(ctx.dp(40), ctx.dp(40)) }
    }
}

object SettingsRow {
    /** A standalone toggle row for use inside cards. */
    fun toggle(ctx: Context, c: Colors, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(makeText(ctx, title, 15f, c.ink, Fonts.sansMedium))
        val s = makeText(ctx, sub, 13f, c.muted, Fonts.sans)
        val slp = LinearLayout.LayoutParams(WRAP, WRAP)
        slp.topMargin = ctx.dp(2)
        col.addView(s, slp)
        val clp = LinearLayout.LayoutParams(0, WRAP, 1f)
        clp.rightMargin = ctx.dp(12)
        r.addView(col, clp)
        val sw = SwitchView(ctx, c, checked)
        sw.onChange = onChange
        r.addView(sw, LinearLayout.LayoutParams(ctx.dp(46), ctx.dp(28)))
        r.setOnClickListener { sw.performClick() }
        return r
    }
}
