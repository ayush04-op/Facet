package app.facet.browser

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Bottom card with a single text field, shown over a dimmed page. */
class InputSheetView(
    ctx: Context,
    c: Colors,
    title: String,
    hint: String,
    initial: String,
    secret: Boolean,
    onSave: (String) -> Unit,
    private val onClose: () -> Unit
) : FrameLayout(ctx) {

    init {
        setBackgroundColor(Color.argb(120, 0, 0, 0))
        isClickable = true
        setOnClickListener { onClose() }

        val kit = RowKit(ctx, c)
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.background = shape(c.bg, ctx.dpf(24f), c.line, ctx.dp(1))
        card.setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(20))
        card.isClickable = true

        card.addView(makeText(ctx, title, 22f, c.ink, Fonts.serif))

        val field = EditText(ctx)
        field.setText(initial)
        field.setSelection(initial.length)
        field.hint = hint
        field.setHintTextColor(c.muted)
        field.setTextColor(c.ink)
        field.typeface = Fonts.sans
        field.textSize = 16f
        field.isSingleLine = true
        field.inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        field.background = shape(c.surface, ctx.dpf(14f), c.line, ctx.dp(1))
        field.setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        val flp = LinearLayout.LayoutParams(MATCH, WRAP)
        flp.topMargin = ctx.dp(16)
        card.addView(field, flp)

        val buttons = LinearLayout(ctx)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.END
        buttons.addView(kit.button("Cancel", false) { hide(field); onClose() })
        val slp = LinearLayout.LayoutParams(WRAP, WRAP)
        slp.leftMargin = ctx.dp(10)
        buttons.addView(kit.button("Save", true) { onSave(field.text.toString()); hide(field); onClose() }, slp)
        val blp = LinearLayout.LayoutParams(MATCH, WRAP)
        blp.topMargin = ctx.dp(16)
        card.addView(buttons, blp)

        val clp = LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        clp.setMargins(ctx.dp(12), 0, ctx.dp(12), ctx.dp(12))
        addView(card, clp)

        field.postDelayed({
            field.requestFocus()
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, 250L)
    }

    private fun hide(f: EditText) {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(f.windowToken, 0)
    }
}
