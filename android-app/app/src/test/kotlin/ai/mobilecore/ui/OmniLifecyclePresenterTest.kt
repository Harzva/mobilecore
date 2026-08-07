package ai.mobilecore.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniLifecyclePresenterTest {
    @Test
    fun `status parser keeps live resource and verification truth`() {
        val payload = statusPayload(
            phase = "idle",
            resourcesSufficient = false,
            availableMemory = 1_280_000_000L,
            availableStorage = 3_270_000_000L,
        )

        val snapshot = OmniLifecyclePresenter.parseStatus(payload.toString())
        val model = OmniLifecyclePresenter.present(snapshot)

        assertTrue(snapshot.serviceReachable)
        assertEquals(OmniLifecycleStage.BLOCKED, model.stage)
        assertFalse(model.memoryReady)
        assertFalse(model.storageReady)
        assertEquals(listOf(OmniLifecycleAction.REFRESH), model.actions.map { it.action })
    }

    @Test
    fun `verified pair is install complete but not loaded`() {
        val payload = statusPayload(
            phase = "installed",
            resourcesSufficient = true,
            pairVerified = true,
            loaded = false,
        )

        val model = OmniLifecyclePresenter.present(OmniLifecyclePresenter.parseStatus(payload.toString()))

        assertEquals(OmniLifecycleStage.INSTALLED, model.stage)
        assertEquals("已校验，尚未加载", model.statusLabel)
        assertEquals(OmniLifecycleAction.LOAD, model.actions.first().action)
    }

    @Test
    fun `download state offers cancellation without claiming progress percent`() {
        val payload = statusPayload(
            phase = "downloading",
            resourcesSufficient = true,
        )

        val model = OmniLifecyclePresenter.present(OmniLifecyclePresenter.parseStatus(payload.toString()))

        assertEquals(OmniLifecycleStage.INSTALLING, model.stage)
        assertTrue(model.isBusy)
        assertEquals(listOf(OmniLifecycleAction.CANCEL), model.actions.map { it.action })
    }

    @Test
    fun `api failure remains typed and does not erase current preflight`() {
        val current = OmniLifecyclePresenter.parseStatus(
            statusPayload(phase = "idle", resourcesSufficient = true).toString(),
        )
        val failed = OmniLifecyclePresenter.withApiFailure(
            current,
            JSONObject().put(
                "error",
                JSONObject().put("code", "wifi_required").put("message", "wifi required"),
            ).toString(),
        )

        val model = OmniLifecyclePresenter.present(failed)

        assertEquals("wifi_required", failed.failureCode)
        assertEquals(4_716_704_800L, failed.requiredMemoryBytes)
        assertEquals(OmniLifecycleStage.BLOCKED, model.stage)
    }

    @Test
    fun `wifi only policy blocks install until wifi is present`() {
        val payload = statusPayload(phase = "idle", resourcesSufficient = true).apply {
            put("wifi_connected", false)
        }

        val model = OmniLifecyclePresenter.present(OmniLifecyclePresenter.parseStatus(payload.toString()))

        assertEquals(OmniLifecycleStage.BLOCKED, model.stage)
        assertTrue(model.statusDetail.contains("Wi-Fi"))
        assertEquals(listOf(OmniLifecycleAction.REFRESH), model.actions.map { it.action })
    }

    @Test
    fun `artifact failure is not hidden by simultaneous resource pressure`() {
        val payload = statusPayload(
            phase = "failed",
            resourcesSufficient = false,
            availableMemory = 1_000_000_000L,
        ).apply {
            put("failure", JSONObject().put("code", "checksum_mismatch"))
        }

        val model = OmniLifecyclePresenter.present(OmniLifecyclePresenter.parseStatus(payload.toString()))

        assertEquals(OmniLifecycleStage.FAILED, model.stage)
        assertTrue(model.statusDetail.contains("摘要不匹配"))
    }

    private fun statusPayload(
        phase: String,
        resourcesSufficient: Boolean,
        availableMemory: Long = 8_000_000_000L,
        availableStorage: Long = 10_000_000_000L,
        pairVerified: Boolean = false,
        loaded: Boolean = false,
    ): JSONObject = JSONObject().apply {
        put("phase", phase)
        put("pair_verified", pairVerified)
        put("loaded", loaded)
        put("wifi_connected", true)
        put("revision", "75f1b73b657a50f5092502799457ccb4a4a1f9df")
        put("license", JSONObject().apply {
            put("id", "qwen-research")
            put("review_status", "source_declared_not_legal_reviewed")
        })
        put("artifacts", JSONObject().apply {
            put("main", JSONObject().put("installed", pairVerified).put("verified", pairVerified))
            put("mmproj", JSONObject().put("installed", pairVerified).put("verified", pairVerified))
        })
        put("preflight", JSONObject().apply {
            put("required_memory_bytes", 4_716_704_800L)
            put("available_memory_bytes", availableMemory)
            put("required_storage_bytes", 4_179_833_888L)
            put("available_storage_bytes", availableStorage)
            put("resources_sufficient", resourcesSufficient)
        })
    }
}
