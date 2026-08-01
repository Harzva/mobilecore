package ai.mobilecore.ui

enum class TuiMaThemeMode(val preferenceValue: String, val displayName: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    fun next(): TuiMaThemeMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromPreference(value: String?): TuiMaThemeMode = entries.firstOrNull {
            it.preferenceValue == value
        } ?: SYSTEM
    }
}

/** Shared visual tokens for the native TuiMa UI. */
object TuiMaTheme {
    private var dark = false

    fun configure(mode: TuiMaThemeMode, systemDark: Boolean) {
        dark = resolveDark(mode, systemDark)
    }

    fun resolveDark(mode: TuiMaThemeMode, systemDark: Boolean): Boolean = when (mode) {
        TuiMaThemeMode.SYSTEM -> systemDark
        TuiMaThemeMode.LIGHT -> false
        TuiMaThemeMode.DARK -> true
    }

    val isDark: Boolean get() = dark
    val background: Int get() = if (dark) 0xFF07111F.toInt() else TuiMaShareTheme.background
    val surface: Int get() = if (dark) 0xFF0F1D30.toInt() else TuiMaShareTheme.surface
    val deepInk: Int get() = if (dark) 0xFFF1F7FF.toInt() else TuiMaShareTheme.deepInk
    val ink: Int get() = if (dark) 0xFFD2DDED.toInt() else TuiMaShareTheme.ink
    val muted: Int get() = if (dark) 0xFF9CACBF.toInt() else TuiMaShareTheme.muted
    val stroke: Int get() = if (dark) 0xFF223754.toInt() else TuiMaShareTheme.stroke
    val mint: Int get() = if (dark) 0xFF7EE6C1.toInt() else TuiMaShareTheme.mint
    val mintDark: Int get() = if (dark) 0xFF55D6B4.toInt() else TuiMaShareTheme.mintDark
    val mintPale: Int get() = if (dark) 0xFF11352E.toInt() else TuiMaShareTheme.mintPale
    val sky: Int get() = if (dark) 0xFF51D5EA.toInt() else TuiMaShareTheme.sky
    val blue: Int get() = if (dark) 0xFF8AA4FF.toInt() else TuiMaShareTheme.blue
    val lavender: Int get() = if (dark) 0xFFC3B0FF.toInt() else TuiMaShareTheme.lavender
    val mintWash: Int get() = if (dark) 0xFF0F2A28.toInt() else TuiMaShareTheme.mintWash
    val blueWash: Int get() = if (dark) 0xFF10263B.toInt() else TuiMaShareTheme.blueWash
    val lavenderWash: Int get() = if (dark) 0xFF211D3B.toInt() else TuiMaShareTheme.lavenderWash

    const val compactHeaderHeightDp = 62
    const val minimumTouchTargetDp = 48
    const val cardRadiusDp = 16f
}

/** Stable export palette so shared cards look identical in light and dark app themes. */
object TuiMaShareTheme {
    const val background = 0xFFFBFDFF.toInt()
    const val surface = 0xFFFFFFFF.toInt()
    const val deepInk = 0xFF0B2B68.toInt()
    const val ink = 0xFF41516D.toInt()
    const val muted = 0xFF7A89A2.toInt()
    const val stroke = 0xFFE5EEF7.toInt()
    const val mint = 0xFF7EE6C1.toInt()
    const val mintDark = 0xFF24AA8A.toInt()
    const val mintPale = 0xFFEFFFF9.toInt()
    const val sky = 0xFF43D1E8.toInt()
    const val blue = 0xFF6B8CFF.toInt()
    const val lavender = 0xFFB69CFF.toInt()
    const val mintWash = 0xFFF0FFF9.toInt()
    const val blueWash = 0xFFEEF7FF.toInt()
    const val lavenderWash = 0xFFF4F1FF.toInt()
}
