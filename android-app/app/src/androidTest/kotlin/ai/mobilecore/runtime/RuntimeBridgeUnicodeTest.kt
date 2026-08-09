package ai.mobilecore.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeBridgeUnicodeTest {
    @Test
    fun jniBoundaryRoundTripsChineseEmojiAndSupplementaryCodePoints() {
        assumeTrue("native runtime library is required", RuntimeBridge.isLibraryReady())
        val expected = "中文输入与输出 👋🏽 🚀 𠮷野家 café"

        val actual = RuntimeBridge.roundTripUtf8ForTest(expected)

        assertEquals(expected, actual)
        assertFalse(actual.contains('\uFFFD'))
        assertTrue(actual.codePoints().anyMatch { it > 0xFFFF })
    }
}
