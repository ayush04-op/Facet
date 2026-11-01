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
import android.widget.TextView

private fun page(h: SettingsHost, title: String, build: (PageBuilder) -> Unit): PageView =
    PageView(h.context, h.colors, title, { h.back() }, build)

fun workspacesPage(h: SettingsHost): View {
    lateinit var view: PageView
    view = page(h, "Profiles") { b ->
        val store = WorkspaceStore(h.prefs)
        val cur = store.current()
        b.section("Your profiles")
        for (w in store.all()) {
            val sub = w.label + if (w.id == cur.id) " · open now" else ""
            b.row(w.name, sub) {
                val items = ArrayList<SheetItem>()
                if (w.id != cur.id) items.add(SheetItem(Icon.CHECK, "Switch to ${w.name}") { h.switchWorkspace(w.id) })
                if (w.id != "personal") {
                    items.add(SheetItem(Icon.SETTINGS, "Rename") { h.renameWorkspace(w.id) })
                    items.add(SheetItem(Icon.TRASH, "Delete with its tabs and bookmarks", false, true) { h.deleteWorkspace(w.id) })
                }
                items.add(SheetItem(null, "Cancel") { })
                h.sheet(w.name, items)
            }
        }
        b.section("Add")
        b.row("New profile", "Its own tabs, bookmarks, cookies and logins. For example Work.") { h.createWorkspace(Workspace.NORMAL) }
        b.row("New research profile", "Keeps a topic's tabs, notes and page highlights together.") { h.createWorkspace(Workspace.RESEARCH) }
        b.row("Temporary session", "Clears its tabs, cookies and site data as soon as you leave it.") { h.createWorkspace(Workspace.TEMPORARY) }
        b.note("Switching profile saves the tabs you have open and opens the ones from the other profile. Profiles never share cookies or logins.")
    }
    return view
}

/** Notes, saved pages and highlights for the current research workspace. */
class ResearchView(
    private val ctx: Context,
    private val c: Colors,
    private val topic: String,
    private val store: ResearchStore,
    private val canSaveCurrent: Boolean,
    private val onSaveCurrent: () -> Unit,
    private val onOpen: (String) -> Unit,
    private val onExport: (String) -> Unit,
    private val onBack: () -> Unit
) : LinearLayout(ctx) {

    private val pagesHost = LinearLayout(ctx)

    init {
        orientation = VERTICAL
        setBackgroundColor(c.bg)
        isClickable = true
        addView(pageHeader(ctx, c, "Research", onBack, null), LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(ctx)
        col.orientation = VERTICAL
        col.setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(40))
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, 0, 1f))

        val topicText = makeText(ctx, topic, 26f, c.ink, Fonts.serif)
        topicText.setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
        col.addView(topicText)
        val sub = hint(ctx, c, "Everything here stays on this device. Nothing is sent to a cloud service.")
        sub.setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(14))
        col.addView(sub)

        // notes
        val notes = BentoCard(ctx, c)
        notes.addView(makeTag(ctx, "Notes", c.accent))
        val edit = EditText(ctx)
        edit.setText(store.notes)
        edit.hint = "Write down what you're finding out"
        edit.setHintTextColor(c.muted)
        edit.setTextColor(c.ink)
        edit.typeface = Fonts.sans
        edit.textSize = 16f
        edit.minLines = 5
        edit.gravity = Gravity.TOP
        edit.background = null
        edit.setPadding(0, ctx.dp(10), 0, 0)
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, a: Int, b: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, a: Int) {}
            override fun afterTextChanged(s: Editable?) { store.notes = s.toString() }
        })
        notes.addView(edit, LayoutParams(MATCH, WRAP))
        col.addView(notes, LayoutParams(MATCH, WRAP))

        // actions
        val actions = LinearLayout(ctx)
        actions.orientation = HORIZONTAL
        val save = pill("Save this page", canSaveCurrent) { onSaveCurrent(); renderPages() }
        val export = pill("Export notes", false) { onExport(store.markdown(topic)) }
        val slp = LayoutParams(0, ctx.dp(48), 1f)
        slp.rightMargin = ctx.dp(5)
        val elp = LayoutParams(0, ctx.dp(48), 1f)
        elp.leftMargin = ctx.dp(5)
        actions.addView(save, slp)
        actions.addView(export, elp)
        val alp = LayoutParams(MATCH, WRAP)
        alp.topMargin = ctx.dp(12)
        col.addView(actions, alp)

        val tag = makeTag(ctx, "Saved pages and highlights", c.accent)
        tag.setPadding(ctx.dp(8), ctx.dp(26), 0, ctx.dp(8))
        col.addView(tag)
        pagesHost.orientation = VERTICAL
        col.addView(pagesHost, LayoutParams(MATCH, WRAP))
        renderPages()
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

    private fun renderPages() {
        pagesHost.removeAllViews()
        val pages = store.pages()
        if (pages.isEmpty()) {
            pagesHost.addView(hint(ctx, c, "Nothing saved yet. Open a page, then choose Save to research in the menu. Highlight text from the menu to keep quotes here."))
            return
        }
        for (p in pages) {
            val card = LinearLayout(ctx)
            card.orientation = VERTICAL
            card.setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(12), ctx.dp(14))
            card.background = shape(c.surface, ctx.dpf(18f), c.line, ctx.dp(1))

            val top = LinearLayout(ctx)
            top.orientation = HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            val texts = LinearLayout(ctx)
            texts.orientation = VERTICAL
            val title = makeText(ctx, if (p.title.isBlank()) Urls.host(p.url) else p.title, 16f, c.ink, Fonts.sansMedium)
            title.maxLines = 2
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            texts.addView(title)
            val host = makeText(ctx, Urls.host(p.url), 13f, c.muted, Fonts.sans)
            texts.addView(host)
            texts.setOnClickListener { onOpen(p.url) }
            texts.isClickable = true
            top.addView(texts, LayoutParams(0, WRAP, 1f))
            val del = FrameLayout(ctx)
            del.pressable(null, withAlpha(c.ink, 0x22), ctx.dpf(20f))
            del.addView(IconView(ctx, Icon.TRASH, c.muted, 18), FrameLayout.LayoutParams(ctx.dp(18), ctx.dp(18), Gravity.CENTER))
            del.setOnClickListener { store.removePage(p.url); renderPages() }
            top.addView(del, LayoutParams(ctx.dp(40), ctx.dp(40)))
            card.addView(top, LayoutParams(MATCH, WRAP))

            for (hl in p.highlights) {
                val q = makeText(ctx, hl, 14f, c.ink, Fonts.serifText)
                q.setLineSpacing(0f, 1.2f)
                q.setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(4), ctx.dp(6))
                q.setBackgroundColor(withAlpha(c.accent, 0x1F))
                q.setOnLongClickListener { store.removeHighlight(p.url, hl); renderPages(); true }
                val qlp = LayoutParams(MATCH, WRAP)
                qlp.topMargin = ctx.dp(8)
                card.addView(q, qlp)
            }
            if (p.highlights.isNotEmpty()) {
                val tip = makeText(ctx, "Press and hold a highlight to remove it", 11f, c.muted, Fonts.sans)
                val tlp = LayoutParams(WRAP, WRAP)
                tlp.topMargin = ctx.dp(6)
                card.addView(tip, tlp)
            }
            val clp = LayoutParams(MATCH, WRAP)
            clp.bottomMargin = ctx.dp(8)
            pagesHost.addView(card, clp)
        }
    }
}

/** Lists the trackers blocked on the current page, by site. */
fun blockedReportPage(h: SettingsHost, pageHost: String, blocked: Map<String, Int>): View {
    return page(h, "Blocked on this page") { b ->
        val total = blocked.values.sum()
        b.info("Page", pageHost.ifEmpty { "No page open" })
        b.info("Blocked requests", total.toString())
        b.info("Protection", when (h.prefs.protectionLevel) { 0 -> "Standard"; 1 -> "Strict"; else -> "Custom" })
        b.section("By site")
        if (blocked.isEmpty()) b.note("Nothing was blocked on this page so far.")
        for ((host, n) in blocked.entries.sortedByDescending { it.value }) b.info(host, n.toString())
        b.note("Change how strict protection is under Settings, Privacy and security.")
    }
}

/** Shown when the lock in the address bar is tapped. */
fun siteInfoPage(h: SettingsHost, pageHost: String, secure: Boolean, blocked: Map<String, Int>): View {
    return page(h, pageHost.ifEmpty { "This page" }) { b ->
        b.section("Connection")
        b.info("Encryption", if (secure) "Secure (HTTPS)" else "Not secure")
        b.section("Protection")
        b.info("Trackers blocked here", blocked.values.sum().toString())
        b.row("See what was blocked", "By site") { h.push(blockedReportPage(h, pageHost, blocked)) }
        b.row("Protection level", when (h.prefs.protectionLevel) { 0 -> "Standard"; 1 -> "Strict"; else -> "Custom" }) { h.push(privacyPage(h)) }
        if (pageHost.isNotEmpty()) {
            b.section("This site")
            b.row("Site permissions", "Camera, microphone, location") { h.push(sitePermissionPage(h, pageHost)) }
        }
    }
}
