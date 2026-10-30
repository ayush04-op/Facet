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
