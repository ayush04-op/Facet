package app.facet.browser

class Colors(
    val dark: Boolean,
    val bg: Int,
    val surface: Int,
    val ink: Int,
    val muted: Int,
    val line: Int,
    val accent: Int,
    val glass: Int,
    val glassEdge: Int
)

/** Paper palette, taken from Prism Client's Paper / Paper Dark themes. */
object Palette {
    val accents = listOf(
        AccentChoice("Terracotta", 0xFFB5532F.toInt(), 0xFFD4693F.toInt()),
        AccentChoice("Ocean", 0xFF1F6F8B.toInt(), 0xFF4FA3C2.toInt()),
        AccentChoice("Forest", 0xFF3C7A4E.toInt(), 0xFF6DB584.toInt()),
        AccentChoice("Violet", 0xFF6B4FBF.toInt(), 0xFF9C84E8.toInt()),
        AccentChoice("Rose", 0xFFB23A5E.toInt(), 0xFFE0708F.toInt()),
        AccentChoice("Amber", 0xFFB27A12.toInt(), 0xFFE0A93C.toInt())
    )

    val light = Colors(
        dark = false,
        bg = 0xFFFBF6EA.toInt(),
        surface = 0xFFF3EBDA.toInt(),
        ink = 0xFF1E1915.toInt(),
        muted = 0xFF7A6E62.toInt(),
        line = 0xFFE2D6BF.toInt(),
        accent = 0xFFB5532F.toInt(),
        glass = 0xE6FFFCF4.toInt(),
        glassEdge = 0x99FFFFFF.toInt()
    )
    val dark = Colors(
        dark = true,
        bg = 0xFF15110E.toInt(),
        surface = 0xFF211B16.toInt(),
        ink = 0xFFF3EBDA.toInt(),
        muted = 0xFFA09282.toInt(),
        line = 0xFF3A3028.toInt(),
        accent = 0xFFD4693F.toInt(),
        glass = 0xE61F1914.toInt(),
        glassEdge = 0x33FFFFFF.toInt()
    )
}

class AccentChoice(val name: String, val light: Int, val dark: Int)

fun Palette.make(dark: Boolean, accent: Int): Colors {
    val base = if (dark) Palette.dark else Palette.light
    val a = Palette.accents.getOrNull(accent) ?: return base
    if (accent == 0) return base
    return Colors(base.dark, base.bg, base.surface, base.ink, base.muted, base.line, if (dark) a.dark else a.light, base.glass, base.glassEdge)
}
