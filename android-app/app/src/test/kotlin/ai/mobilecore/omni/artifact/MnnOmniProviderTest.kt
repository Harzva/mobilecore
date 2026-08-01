package ai.mobilecore.omni.artifact

import ai.mobilecore.omni.mnn.UnavailableMnnOmniProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class MnnOmniProviderTest {
    @Test
    fun p1ProviderDoesNotAdvertiseUnintegratedSpeechOutput() {
        val status = UnavailableMnnOmniProvider().probe()

        assertEquals("mnn_p1_not_integrated", status.reason)
        assertFalse(status.runtimeIntegrated)
        assertFalse(status.modelLoaded)
        assertFalse(status.audioOutput)
    }
}
