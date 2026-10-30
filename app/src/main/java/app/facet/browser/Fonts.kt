package app.facet.browser

import android.content.Context
import android.graphics.Typeface

/**
 * Prism Sans (body text) and Prism Serif (headings), loaded from assets/fonts.
 * If a file is missing the system font is used, so the app never crashes over a font.
 */
object Fonts {
    var sans: Typeface = Typeface.SANS_SERIF
    var sansMedium: Typeface = Typeface.SANS_SERIF
    var sansBold: Typeface = Typeface.DEFAULT_BOLD
    var serif: Typeface = Typeface.SERIF
    var serifItalic: Typeface = Typeface.SERIF
    var serifMedium: Typeface = Typeface.SERIF
    var serifText: Typeface = Typeface.SERIF
    var mono: Typeface = Typeface.MONOSPACE

    fun init(ctx: Context) {
        sans = load(ctx, "AnthropicSans-Text-Regular-Static.otf", Typeface.SANS_SERIF)
        sansMedium = load(ctx, "AnthropicSans-Text-Medium-Static.otf", Typeface.SANS_SERIF)
        sansBold = load(ctx, "AnthropicSans-Text-Semibold-Static.otf", Typeface.DEFAULT_BOLD)
        serif = load(ctx, "AnthropicSerif-Display-Regular-Static.otf", Typeface.SERIF)
        serifItalic = load(ctx, "AnthropicSerif-Display-RegularItalic-Static.otf", Typeface.SERIF)
        serifMedium = load(ctx, "AnthropicSerif-Display-Medium-Static.otf", Typeface.SERIF)
        serifText = load(ctx, "AnthropicSerif-Text-Medium-Static.otf", Typeface.SERIF)
    }

    private fun load(ctx: Context, name: String, fallback: Typeface): Typeface {
        return try {
            Typeface.createFromAsset(ctx.assets, "fonts/$name")
        } catch (e: Exception) {
            fallback
        }
    }
}
