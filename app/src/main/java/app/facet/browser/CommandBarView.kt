package app.facet.browser

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

class Command(
    val kind: String,
    val label: String,
    val sub: String,
    val icon: Icon,
    val action: () -> Unit
)

/** One box that searches open tabs, bookmarks, history, settings and commands. */
class CommandBarView(
    private val ctx: Context,
    private val c: Colors,
    private val all: () -> List<Command>,
    private val fallback: (String) -> List<Command>,
    private val onClose: () -> Unit
) : FrameLayout(ctx) {

    private val field = EditText(ctx)
    private val results = LinearLayout(ctx)
    private var shown: List<Command> = emptyList()

    init {
        setBackgroundColor(Color.argb(150, 0, 0, 0))
        isClickable = true
        setOnClickListener { close() }

        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.background = shape(c.bg, ctx.dpf(24f), c.line, ctx.dp(1))
        card.isClickable = true
        card.setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))

        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(ctx.dp(14), 0, ctx.dp(10), 0)
        bar.addView(IconView(ctx, Icon.SEARCH, c.accent, 22), LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)))
        field.hint = "Search tabs, bookmarks, history, settings"
        field.setHintTextColor(c.muted)
        field.setTextColor(c.ink)
        field.typeface = Fonts.sans
        field.textSize = 17f
        field.background = null
        field.isSingleLine = true
        field.imeOptions = EditorInfo.IME_ACTION_GO
        field.setPadding(ctx.dp(14), 0, 0, 0)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, a: Int, b: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, a: Int) {}
            override fun afterTextChanged(s: Editable?) { render() }
        })
        field.setOnEditorActionListener { _, _, _ -> runFirst(); true }
        field.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_UP) { runFirst(); true } else false
        }
        bar.addView(field, LinearLayout.LayoutParams(0, MATCH, 1f))
        card.addView(bar, LinearLayout.LayoutParams(MATCH, ctx.dp(56)))

        val line = android.view.View(ctx)
        line.setBackgroundColor(c.line)
        card.addView(line, LinearLayout.LayoutParams(MATCH, ctx.dp(1)))

        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        results.orientation = LinearLayout.VERTICAL
        scroll.addView(results, FrameLayout.LayoutParams(MATCH, WRAP))
        card.addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP))

        val lp = LayoutParams(MATCH, WRAP, Gravity.TOP)
        lp.setMargins(ctx.dp(12), ctx.dp(56), ctx.dp(12), 0)
        addView(card, lp)
        render()

        field.postDelayed({
            field.requestFocus()
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, 150L)
    }

    private fun close() {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
        onClose()
    }

    private fun score(cmd: Command, tokens: List<String>): Int {
        val hay = (cmd.label + " " + cmd.sub).lowercase()
        if (!tokens.all { hay.contains(it) }) return -1
        var s = 0
        val label = cmd.label.lowercase()
        if (label.startsWith(tokens[0])) s += 30
        if (label.contains(tokens[0])) s += 15
        s += when (cmd.kind) { "Tab" -> 12; "Command" -> 10; "Setting" -> 8; "Bookmark" -> 6; else -> 2 }
        return s
    }

    private fun render() {
        val q = field.text.toString().trim().lowercase()
        val tokens = q.split(" ").filter { it.isNotEmpty() }
        val list: List<Command> = if (tokens.isEmpty()) {
            all().filter { it.kind == "Tab" || it.kind == "Command" }.take(10)
        } else {
            val scored = all().map { Pair(it, score(it, tokens)) }.filter { it.second >= 0 }
            scored.sortedByDescending { it.second }.map { it.first }.take(14) + fallback(field.text.toString().trim())
        }
        shown = list
        results.removeAllViews()
        for (cmd in list) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
            row.pressable(null, withAlpha(c.ink, 0x14), ctx.dpf(14f))
            row.addView(IconView(ctx, cmd.icon, c.muted, 22), LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)))
            val texts = LinearLayout(ctx)
            texts.orientation = LinearLayout.VERTICAL
            val t1 = makeText(ctx, cmd.label, 16f, c.ink, Fonts.sansMedium)
            t1.maxLines = 1
            t1.ellipsize = TextUtils.TruncateAt.END
            texts.addView(t1)
            if (cmd.sub.isNotEmpty()) {
                val t2 = makeText(ctx, cmd.sub, 12.5f, c.muted, Fonts.sans)
                t2.maxLines = 1
                t2.ellipsize = TextUtils.TruncateAt.MIDDLE
                texts.addView(t2)
            }
            val tlp = LinearLayout.LayoutParams(0, WRAP, 1f)
            tlp.leftMargin = ctx.dp(14)
            row.addView(texts, tlp)
            row.addView(makeTag(ctx, cmd.kind, c.muted))
            row.setOnClickListener { run(cmd) }
            results.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        if (list.isEmpty()) {
            val t = hint(ctx, c, "No matches.")
            t.setPadding(ctx.dp(18), ctx.dp(16), ctx.dp(18), ctx.dp(16))
            results.addView(t)
        }
    }

    private fun runFirst() {
        shown.firstOrNull()?.let { run(it) }
    }

    private fun run(cmd: Command) {
        close()
        cmd.action()
    }
}
