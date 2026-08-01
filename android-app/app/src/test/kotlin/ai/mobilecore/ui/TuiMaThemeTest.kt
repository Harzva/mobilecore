package ai.mobilecore.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuiMaThemeTest {
    @Test
    fun `theme mode resolves system light and dark`() {
        assertFalse(TuiMaTheme.resolveDark(TuiMaThemeMode.SYSTEM, systemDark = false))
        assertTrue(TuiMaTheme.resolveDark(TuiMaThemeMode.SYSTEM, systemDark = true))
        assertFalse(TuiMaTheme.resolveDark(TuiMaThemeMode.LIGHT, systemDark = true))
        assertTrue(TuiMaTheme.resolveDark(TuiMaThemeMode.DARK, systemDark = false))
    }

    @Test
    fun `unknown preference falls back to system`() {
        assertTrue(TuiMaThemeMode.fromPreference("unknown") == TuiMaThemeMode.SYSTEM)
        assertTrue(TuiMaThemeMode.DARK.next() == TuiMaThemeMode.SYSTEM)
    }
}
