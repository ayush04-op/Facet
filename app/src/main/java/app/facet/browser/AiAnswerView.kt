package app.facet.browser

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Full-screen answer from the optional AI assistant. Shows a waiting state first, then the text. */
class AiAnswerView(
    ctx: Context,
    private val c: Colors,
    title: String,
    private val onCopy: (String) -> Unit,
    onBack: () -> Unit
) : LinearLayout(ctx) {

    private val body: TextView
    private val spinner = ProgressBar(ctx)
    private var text = ""

    init {
        orientation = VERTICAL
        setBackgroundColor(c.bg)
        isClickable = true
        val copy = FrameLayout(ctx)
        copy.pressable(null, withAlpha(c.ink, 0x22), ctx.dpf(24f))
        copy.addView(IconView(ctx, Icon.LINK, c.ink, 22), FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.CENTER))
        copy.setOnClickListener { if (text.isNotEmpty()) onCopy(text) }
        addView(pageHeader(ctx, c, title, onBack, copy), LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(ctx)
        col.orientation = VERTICAL
        col.setPadding(ctx.dp(22), ctx.dp(10), ctx.dp(22), ctx.dp(40))
        body = makeText(ctx, "", 17f, c.ink, Fonts.serifText)
        body.setLineSpacing(0f, 1.25f)
        body.setTextIsSelectable(true)
        col.addView(spinner, LayoutParams(ctx.dp(32), ctx.dp(32)))
        col.addView(body, LayoutParams(MATCH, WRAP))
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, 0, 1f))
    }

    fun show(answer: String) {
        text = answer
        spinner.visibility = View.GONE
        body.text = answer
    }
}
