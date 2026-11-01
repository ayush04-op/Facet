package app.facet.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class OnboardState(var profile: String, var user: String, val extras: MutableSet<String> = HashSet())

class OnboardHooks(
    /** Rebuilds the app chrome with new colours and shows the onboarding again at the given step. */
    val retheme: (Int) -> Unit,
    val finish: () -> Unit
)

/** Light, dark or split preview of the Paper themes. */
private class ThemePreview(ctx: Context, private val mode: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawSide(cv: Canvas, c: Colors, left: Float, right: Float) {
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        cv.save()
        cv.clipRect(left, 0f, right, h)
        p.color = c.bg
        cv.drawRect(0f, 0f, width.toFloat(), h, p)
        p.color = c.surface
        cv.drawRoundRect(10f * d, 14f * d, width - 10f * d, 44f * d, 8f * d, 8f * d, p)
        p.color = c.accent
        cv.drawRoundRect(10f * d, 56f * d, 46f * d, 64f * d, 4f * d, 4f * d, p)
        p.color = c.ink
        cv.drawRoundRect(10f * d, 74f * d, width - 24f * d, 80f * d, 3f * d, 3f * d, p)
        p.color = c.muted
        cv.drawRoundRect(10f * d, 88f * d, width - 40f * d, 94f * d, 3f * d, 3f * d, p)
        p.color = c.surface
        cv.drawRoundRect(10f * d, h - 34f * d, width - 10f * d, h - 12f * d, 11f * d, 11f * d, p)
        cv.restore()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        when (mode) {
            1 -> drawSide(canvas, Palette.light, 0f, w)
            2 -> drawSide(canvas, Palette.dark, 0f, w)
            else -> { drawSide(canvas, Palette.light, 0f, w / 2f); drawSide(canvas, Palette.dark, w / 2f, w) }
        }
    }
}

/**
 * First-run experience: an animated welcome, then a few quick choices (profile, search engine, theme, look).
 * Every choice is saved as it is made, and all of it can be changed later in Settings.
 */
class OnboardingView(
    private val ctx: Context,
    private val c: Colors,
    private val prefs: Prefs,
    private val state: OnboardState,
    startStep: Int,
    private val hooks: OnboardHooks
) : FrameLayout(ctx) {

    private val cfg = HomeConfig(prefs)
    private val handler = Handler(Looper.getMainLooper())
    private var step = startStep
    private val stage = FrameLayout(ctx)
    private val dots = LinearLayout(ctx)
    private val primary = TextView(ctx)
    private val back = TextView(ctx)
    private val skip = TextView(ctx)
    private var rotator: Runnable? = null
    private var profileField: EditText? = null
    private var userField: EditText? = null

    init {
        isClickable = true
        setBackgroundColor(c.bg)
        val dm = ctx.resources.displayMetrics
        val bg = FrameLayout(ctx)
        val wp = WallpaperView(ctx, Wallpapers.preset(if (cfg.wallpaper == 9) 0 else cfg.wallpaper, c.dark, dm.widthPixels / 2, dm.heightPixels / 2), 0)
        bg.addView(wp, LayoutParams(MATCH, dm.heightPixels))
        val aurora = AuroraView(ctx, c)
        aurora.alpha = 0.8f
        bg.addView(aurora, LayoutParams(MATCH, dm.heightPixels))
        bg.addView(FacetField(ctx, c.accent), LayoutParams(MATCH, dm.heightPixels))
        addView(bg, LayoutParams(MATCH, MATCH))

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(22), ctx.dp(18))

        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER_VERTICAL
        top.addView(dots, LinearLayout.LayoutParams(0, WRAP, 1f))
        skip.text = "Skip"
        skip.setTextColor(c.muted)
        skip.typeface = Fonts.sansMedium
        skip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        skip.setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
        skip.pressable(null, withAlpha(c.ink, 0x22), ctx.dpf(18f))
        skip.setOnClickListener { saveFields(); end() }
        top.addView(skip, LinearLayout.LayoutParams(WRAP, WRAP))
        col.addView(top, LinearLayout.LayoutParams(MATCH, ctx.dp(44)))

        col.addView(stage, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bottom = LinearLayout(ctx)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        back.text = "Back"
        back.setTextColor(c.ink)
        back.typeface = Fonts.sansMedium
        back.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        back.gravity = Gravity.CENTER
        back.pressable(shape(withAlpha(c.surface, 0xCC), ctx.dpf(28f), c.line, ctx.dp(1)), withAlpha(c.ink, 0x22), ctx.dpf(28f))
        back.setOnClickListener { goBack() }
        val blp = LinearLayout.LayoutParams(ctx.dp(96), ctx.dp(56))
        blp.rightMargin = ctx.dp(10)
        bottom.addView(back, blp)
        primary.setTextColor(0xFFFFFFFF.toInt())
        primary.typeface = Fonts.sansMedium
        primary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
        primary.gravity = Gravity.CENTER
        primary.pressable(shape(c.accent, ctx.dpf(28f)), withAlpha(0xFFFFFF, 0x44), ctx.dpf(28f))
        primary.pressScale(0.97f)
        primary.setOnClickListener { next() }
        bottom.addView(primary, LinearLayout.LayoutParams(0, ctx.dp(56), 1f))
        col.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(col, LayoutParams(MATCH, MATCH))

        enter(step, true, false)
    }

    // ---------------------------------------------------------------- flow

    /** Back gesture or button. Returns false when there is nothing earlier to go to. */
    fun handleBack(): Boolean {
        if (step == 0) return false
        goBack()
        return true
    }

    private fun goBack() {
        saveFields()
        if (step > 0) enter(step - 1, false, true)
    }

    private fun next() {
        saveFields()
        if (step >= 5) end() else enter(step + 1, true, true)
    }

    private fun saveFields() {
        profileField?.let { state.profile = it.text.toString().trim() }
        userField?.let { state.user = it.text.toString().trim() }
        if (state.user.isNotEmpty()) cfg.name = state.user
    }

    private fun end() {
        stopRotator()
        animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(380L).withEndAction { hooks.finish() }.start()
    }

    private fun stopRotator() {
        rotator?.let { handler.removeCallbacks(it) }
        rotator = null
    }

    override fun onDetachedFromWindow() {
        stopRotator()
        super.onDetachedFromWindow()
    }

    private fun enter(newStep: Int, forward: Boolean, animated: Boolean) {
        stopRotator()
        profileField = null
        userField = null
        val old = if (stage.childCount > 0) stage.getChildAt(0) else null
        step = newStep
        val v = buildStep(newStep)
        stage.addView(v, LayoutParams(MATCH, MATCH))
        if (animated) {
            val dist = ctx.dpf(36f) * (if (forward) 1f else -1f)
            v.alpha = 0f
            v.translationX = dist
            v.animate().alpha(1f).translationX(0f).setDuration(480L).setInterpolator(SpringInterpolator(0.15f, 0.45f)).start()
            old?.animate()?.alpha(0f)?.translationX(-dist)?.setDuration(200L)?.withEndAction { stage.removeView(old) }?.start()
        } else if (old != null) {
            stage.removeView(old)
        }
        refreshChrome()
    }

    private fun refreshChrome() {
        dots.removeAllViews()
        if (step in 1..4) {
            for (i in 1..4) {
                val on = i == step
                val d = View(ctx)
                d.background = shape(if (on) c.accent else withAlpha(c.ink, 0x33), ctx.dpf(4f))
                val lp = LinearLayout.LayoutParams(ctx.dp(if (on) 22 else 8), ctx.dp(8))
                lp.rightMargin = ctx.dp(6)
                dots.addView(d, lp)
            }
        }
        back.visibility = if (step == 0) View.GONE else View.VISIBLE
        skip.visibility = if (step in 0..4) View.VISIBLE else View.INVISIBLE
        primary.text = when (step) { 0 -> "Get started"; 5 -> "Start browsing"; else -> "Continue" }
    }

    // ---------------------------------------------------------------- steps

    private fun buildStep(i: Int): View = when (i) {
        0 -> intro()
        1 -> profileStep()
        2 -> searchStep()
        3 -> themeStep()
        4 -> lookStep()
        else -> doneStep()
    }

    private fun reveal(v: View, delay: Long, rise: Float = 24f) {
        v.alpha = 0f
        v.translationY = ctx.dpf(rise)
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(700L)
            .setInterpolator(DecelerateInterpolator(2.2f)).start()
    }

    private fun intro(): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL

        box.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 0.7f))
        val logo = LogoView(ctx)
        box.addView(logo, LinearLayout.LayoutParams(ctx.dp(190), ctx.dp(190)))

        val hello = makeTag(ctx, "Welcome to", c.muted)
        hello.letterSpacing = 0.3f
        val hlp = LinearLayout.LayoutParams(WRAP, WRAP)
        hlp.topMargin = ctx.dp(18)
        box.addView(hello, hlp)
        reveal(hello, 1100L, 10f)

        val name = ShimmerText(ctx, c.accent, if (c.dark) 0xFFFFD9B8.toInt() else 0xFFE9A07A.toInt())
        name.text = "Facet."
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 68f)
        name.typeface = Fonts.serifItalic
        name.includeFontPadding = false
        box.addView(name, LinearLayout.LayoutParams(WRAP, WRAP))
        reveal(name, 1350L, 30f)

        val line = makeText(ctx, "", 18f, c.muted, Fonts.sans)
        line.gravity = Gravity.CENTER
        val llp = LinearLayout.LayoutParams(WRAP, WRAP)
        llp.topMargin = ctx.dp(14)
        box.addView(line, llp)
        val phrases = listOf("Private by default.", "Fast and free of clutter.", "Made to feel like yours.", "Nothing leaves your device.")
        var idx = 0
        val tick = object : Runnable {
            override fun run() {
                line.animate().alpha(0f).translationY(-ctx.dpf(8f)).setDuration(220L).withEndAction {
                    line.text = phrases[idx % phrases.size]
                    idx++
                    line.translationY = ctx.dpf(8f)
                    line.animate().alpha(1f).translationY(0f).setDuration(380L).setInterpolator(DecelerateInterpolator()).start()
                }.start()
                handler.postDelayed(this, 2300L)
            }
        }
        rotator = tick
        handler.postDelayed(tick, 1700L)

        box.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        return box
    }

    private fun title(text: String, sub: String): LinearLayout {
        val t = LinearLayout(ctx)
        t.orientation = LinearLayout.VERTICAL
        val h = makeText(ctx, text, 34f, c.ink, Fonts.serif)
        t.addView(h)
        val s = makeText(ctx, sub, 16f, c.muted, Fonts.sans)
        s.setLineSpacing(0f, 1.2f)
        val slp = LinearLayout.LayoutParams(MATCH, WRAP)
        slp.topMargin = ctx.dp(8)
        t.addView(s, slp)
        reveal(t, 60L, 16f)
        return t
    }

    private fun scrolled(content: View): View {
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        return sv
    }

    private fun card(): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        l.background = shape(withAlpha(c.surface, 0xD0), ctx.dpf(22f), c.line, ctx.dp(1))
        return l
    }

    private fun field(initial: String, hint: String): EditText {
        val e = EditText(ctx)
        e.setText(initial)
        e.hint = hint
        e.setTextColor(c.ink)
        e.setHintTextColor(c.muted)
        e.typeface = Fonts.sans
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
        e.isSingleLine = true
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        e.background = shape(withAlpha(c.surface, 0xE0), ctx.dpf(18f), c.line, ctx.dp(1))
        e.setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
        return e
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit): TextView {
        val t = makeText(ctx, label, 14f, if (on) 0xFFFFFFFF.toInt() else c.ink, Fonts.sansMedium)
        t.setPadding(ctx.dp(14), ctx.dp(9), ctx.dp(14), ctx.dp(9))
        t.pressable(if (on) shape(c.accent, ctx.dpf(20f)) else shape(withAlpha(c.surface, 0xD0), ctx.dpf(20f), c.line, ctx.dp(1)), withAlpha(c.ink, 0x22), ctx.dpf(20f))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun label(text: String): TextView {
        val t = makeTag(ctx, text, c.muted)
        t.setPadding(ctx.dp(4), ctx.dp(22), 0, ctx.dp(8))
        return t
    }

    private fun profileStep(): View {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(title("Name your profile", "A profile keeps its own tabs, bookmarks, cookies and logins. You can add more whenever you like."))

        col.addView(label("PROFILE NAME"))
        val f = field(state.profile, "Personal")
        profileField = f
        col.addView(f, LinearLayout.LayoutParams(MATCH, ctx.dp(58)))
        reveal(f, 160L)

        val chips = LinearLayout(ctx)
        chips.orientation = LinearLayout.HORIZONTAL
        for (n in listOf("Personal", "Work", "Study", "Home")) {
            val ch = chip(n, false) { f.setText(n); f.setSelection(n.length) }
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = ctx.dp(8)
            chips.addView(ch, lp)
        }
        val clp = LinearLayout.LayoutParams(MATCH, WRAP)
        clp.topMargin = ctx.dp(12)
        col.addView(chips, clp)
        reveal(chips, 230L)

        col.addView(label("WHAT SHOULD WE CALL YOU? (OPTIONAL)"))
        val u = field(state.user, "Your name")
        userField = u
        col.addView(u, LinearLayout.LayoutParams(MATCH, ctx.dp(58)))
        reveal(u, 300L)
        val note = makeText(ctx, "Used for a greeting on the home page. It stays on this device.", 13.5f, c.muted, Fonts.sans)
        val nlp = LinearLayout.LayoutParams(MATCH, WRAP)
        nlp.topMargin = ctx.dp(8)
        nlp.leftMargin = ctx.dp(4)
        col.addView(note, nlp)

        col.addView(label("ALSO SET UP"))
        val extra = LinearLayout(ctx)
        extra.orientation = LinearLayout.HORIZONTAL
        for (n in listOf("Work", "Study", "Research")) {
            lateinit var ch: TextView
            fun paint() {
                val on = state.extras.contains(n)
                ch.setTextColor(if (on) 0xFFFFFFFF.toInt() else c.ink)
                ch.pressable(if (on) shape(c.accent, ctx.dpf(20f)) else shape(withAlpha(c.surface, 0xD0), ctx.dpf(20f), c.line, ctx.dp(1)), withAlpha(c.ink, 0x22), ctx.dpf(20f))
            }
            ch = chip(if (state.extras.contains(n)) "$n ✓" else n, state.extras.contains(n)) {
                if (!state.extras.remove(n)) state.extras.add(n)
                ch.text = if (state.extras.contains(n)) "$n ✓" else n
                paint()
            }
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = ctx.dp(8)
            extra.addView(ch, lp)
        }
        col.addView(extra)
        reveal(extra, 380L)
        return scrolled(col)
    }

    private fun searchStep(): View {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(title("Pick a search engine", "This is what the address bar uses. Change it any time in Settings."))
        val list = card()
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = ctx.dp(22)
        col.addView(list, lp)
        fun fill() {
            list.removeAllViews()
            for (e in Urls.engines) {
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(ctx.dp(20), 0, ctx.dp(18), 0)
                row.pressable(null, withAlpha(c.ink, 0x14), ctx.dpf(22f))
                val sel = e.id == prefs.searchEngine
                val letter = makeText(ctx, e.name.take(1), 16f, c.accent, Fonts.serifText)
                letter.gravity = Gravity.CENTER
                letter.background = shape(withAlpha(c.accent, 0x24), ctx.dpf(18f))
                row.addView(letter, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)))
                val t = makeText(ctx, e.name, 18f, c.ink, Fonts.sans)
                val tlp = LinearLayout.LayoutParams(0, WRAP, 1f)
                tlp.leftMargin = ctx.dp(14)
                row.addView(t, tlp)
                if (sel) row.addView(IconView(ctx, Icon.CHECK, c.accent, 24), LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)))
                row.setOnClickListener { prefs.searchEngine = e.id; fill() }
                list.addView(row, LinearLayout.LayoutParams(MATCH, ctx.dp(62)))
            }
        }
        fill()
        reveal(list, 200L)
        return scrolled(col)
    }

    private fun themeStep(): View {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(title("Choose your look", "Paper is Facet's warm, quiet theme. Pick light, dark, or follow your phone."))
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        val names = listOf("Match phone", "Paper", "Paper Dark")
        for (mode in 0..2) {
            val cell = LinearLayout(ctx)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER_HORIZONTAL
            val sel = prefs.theme == mode
            val frame = FrameLayout(ctx)
            frame.background = shape(0, ctx.dpf(20f), if (sel) c.accent else c.line, if (sel) ctx.dp(3) else ctx.dp(1))
            frame.setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
            val pv = ThemePreview(ctx, mode)
            pv.outlineProvider = ViewOutlineProvider.BACKGROUND
            pv.background = shape(0xFF000000.toInt(), ctx.dpf(16f))
            pv.clipToOutline = true
            frame.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))
            cell.addView(frame, LinearLayout.LayoutParams(MATCH, ctx.dp(210)))
            val nm = makeText(ctx, names[mode], 15f, if (sel) c.accent else c.ink, Fonts.sansMedium)
            val nlp = LinearLayout.LayoutParams(WRAP, WRAP)
            nlp.topMargin = ctx.dp(10)
            cell.addView(nm, nlp)
            cell.setOnClickListener {
                if (prefs.theme != mode) {
                    prefs.theme = mode
                    hooks.retheme(3)
                }
            }
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.leftMargin = ctx.dp(5)
            lp.rightMargin = ctx.dp(5)
            row.addView(cell, lp)
        }
        val rlp = LinearLayout.LayoutParams(MATCH, WRAP)
        rlp.topMargin = ctx.dp(26)
        col.addView(row, rlp)
        reveal(row, 200L)
        return scrolled(col)
    }

    private fun lookStep(): View {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(title("Make it yours", "Accent colour, wallpaper and glass. All of it can change later under Home page."))

        col.addView(label("ACCENT"))
        val sw = LinearLayout(ctx)
        sw.orientation = LinearLayout.HORIZONTAL
        for ((i, a) in Palette.accents.withIndex()) {
            val sel = cfg.accent == i
            val dot = FrameLayout(ctx)
            dot.background = shape(if (c.dark) a.dark else a.light, ctx.dpf(22f), if (sel) c.ink else 0, if (sel) ctx.dp(3) else 0)
            dot.setOnClickListener { if (cfg.accent != i) { cfg.accent = i; hooks.retheme(4) } }
            dot.pressScale(0.9f)
            val lp = LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44))
            lp.rightMargin = ctx.dp(10)
            sw.addView(dot, lp)
        }
        col.addView(sw)
        reveal(sw, 150L)

        col.addView(label("WALLPAPER"))
        val wp = LinearLayout(ctx)
        wp.orientation = LinearLayout.HORIZONTAL
        for (i in Wallpapers.names.indices) {
            val sel = cfg.wallpaper == i
            val frame = FrameLayout(ctx)
            frame.background = shape(0, ctx.dpf(16f), if (sel) c.accent else c.line, if (sel) ctx.dp(3) else ctx.dp(1))
            frame.setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            val iv = ImageView(ctx)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(Wallpapers.preset(i, c.dark, 54, 96))
            iv.outlineProvider = ViewOutlineProvider.BACKGROUND
            iv.background = shape(0xFF000000.toInt(), ctx.dpf(13f))
            iv.clipToOutline = true
            frame.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
            frame.setOnClickListener { if (cfg.wallpaper != i) { cfg.wallpaper = i; HomeLook.forget(); hooks.retheme(4) } }
            val lp = LinearLayout.LayoutParams(0, ctx.dp(96), 1f)
            lp.rightMargin = ctx.dp(8)
            wp.addView(frame, lp)
        }
        col.addView(wp)
        reveal(wp, 230L)

        col.addView(label("EXTRAS"))
        val box = card()
        fun toggleRow(title: String, sub: String, on: Boolean, change: (Boolean) -> Unit) {
            val r = LinearLayout(ctx)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(18), ctx.dp(14))
            val t = LinearLayout(ctx)
            t.orientation = LinearLayout.VERTICAL
            t.addView(makeText(ctx, title, 17f, c.ink, Fonts.sans))
            val s = makeText(ctx, sub, 13.5f, c.muted, Fonts.sans)
            val slp = LinearLayout.LayoutParams(WRAP, WRAP)
            slp.topMargin = ctx.dp(3)
            t.addView(s, slp)
            r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            val sv = SwitchView(ctx, c, on)
            sv.onChange = change
            r.addView(sv, LinearLayout.LayoutParams(ctx.dp(46), ctx.dp(28)))
            r.setOnClickListener { sv.performClick() }
            box.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        toggleRow("Liquid glass", "Frosted panels over your wallpaper", cfg.glass) { cfg.glass = it; HomeLook.forget() }
        toggleRow("Clock on the home page", "Time and date, big and calm", cfg.clock) { cfg.clock = it }
        col.addView(box)
        reveal(box, 300L)
        return scrolled(col)
    }

    private fun doneStep(): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 0.5f))
        box.addView(LogoView(ctx), LinearLayout.LayoutParams(ctx.dp(130), ctx.dp(130)))
        val h = makeText(ctx, "You're all set.", 36f, c.ink, Fonts.serif)
        val hlp = LinearLayout.LayoutParams(WRAP, WRAP)
        hlp.topMargin = ctx.dp(16)
        box.addView(h, hlp)
        reveal(h, 500L)

        val themeName = when (prefs.theme) { 1 -> "Paper"; 2 -> "Paper Dark"; else -> "Match phone" }
        val profile = state.profile.ifEmpty { "Personal" }
        val summary = card()
        summary.setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(8))
        fun line(k: String, v: String) {
            val r = LinearLayout(ctx)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.addView(makeText(ctx, k, 15f, c.muted, Fonts.sans), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(makeText(ctx, v, 16f, c.ink, Fonts.sansMedium))
            summary.addView(r, LinearLayout.LayoutParams(MATCH, ctx.dp(46)))
        }
        line("Profile", profile + if (state.extras.isNotEmpty()) " + ${state.extras.size} more" else "")
        line("Search", Urls.engine(prefs.searchEngine).name)
        line("Theme", themeName)
        line("Wallpaper", if (cfg.wallpaper == 9) "Your photo" else Wallpapers.names[cfg.wallpaper.coerceIn(0, Wallpapers.names.size - 1)])
        val slp = LinearLayout.LayoutParams(MATCH, WRAP)
        slp.topMargin = ctx.dp(26)
        box.addView(summary, slp)
        reveal(summary, 650L)
        val tip = makeText(ctx, "Everything here lives in Settings if you change your mind.", 14f, c.muted, Fonts.sans)
        tip.gravity = Gravity.CENTER
        val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
        tlp.topMargin = ctx.dp(16)
        box.addView(tip, tlp)
        reveal(tip, 800L)
        box.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        return box
    }
}
