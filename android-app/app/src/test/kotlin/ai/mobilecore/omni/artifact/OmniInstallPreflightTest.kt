package ai.mobilecore.omni.artifact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniInstallPreflightTest {
    private val manifest = Qwen25Omni3bArtifacts.manifest
    private val acceptedRequest = OmniInstallRequest(
        explicitConsent = true,
        acceptedLicenseId = "qwen-research",
        wifiOnly = true
    )

    @Test
    fun consentAndLicenseAcceptanceAreMandatory() {
        val result = evaluate(acceptedRequest.copy(explicitConsent = false))

        assertFalse(result.passed)
        assertEquals(OmniArtifactFailureCode.EXPLICIT_CONSENT_REQUIRED, result.failure?.code)
    }

    @Test
    fun wifiOnlyRequestRejectsCellularEnvironment() {
        val result = evaluate(acceptedRequest, wifiConnected = false)

        assertFalse(result.passed)
        assertEquals(OmniArtifactFailureCode.WIFI_REQUIRED, result.failure?.code)
    }

    @Test
    fun memoryAndStorageHaveSeparateTypedFailures() {
        val memoryResult = evaluate(
            acceptedRequest,
            memory = manifest.minimumAvailableMemoryBytes - 1
        )
        val storageResult = evaluate(
            acceptedRequest,
            storage = manifest.requiredStorageBytes - 1
        )

        assertEquals(OmniArtifactFailureCode.INSUFFICIENT_MEMORY, memoryResult.failure?.code)
        assertEquals(OmniArtifactFailureCode.INSUFFICIENT_STORAGE, storageResult.failure?.code)
    }

    @Test
    fun completeAcceptedPreflightPasses() {
        val result = evaluate(acceptedRequest)

        assertTrue(result.passed)
        assertEquals(manifest.requiredStorageBytes, result.requiredStorageBytes)
        assertEquals(manifest.minimumAvailableMemoryBytes, result.requiredMemoryBytes)
    }

    private fun evaluate(
        request: OmniInstallRequest,
        memory: Long = manifest.minimumAvailableMemoryBytes,
        storage: Long = manifest.requiredStorageBytes,
        wifiConnected: Boolean = true
    ): OmniPreflightResult {
        return OmniInstallPreflight(manifest).evaluate(
            request,
            OmniInstallEnvironment(
                availableMemoryBytes = memory,
                availableStorageBytes = storage,
                wifiConnected = wifiConnected
            )
        )
    }
}
