package ai.mobilecore.playground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

class PlaygroundCatalogTest {
    private fun assetText(): String =
        File("src/main/assets/mobile-model-playground/catalog-v1.json").readText()

    @Test
    fun `bundled catalog preserves publishers and provenance`() {
        val catalog = PlaygroundCatalogParser.parse(assetText())
        assertTrue("The bundled Playground must expose at least 20 distinct models", catalog.entries.size >= 20)
        assertEquals(catalog.entries.size, catalog.entries.map { it.id }.toSet().size)

        val qwenOfficial = catalog.entries.first { it.id == "qwen2.5-0.5b-instruct-q4-k-m-official" }
        assertEquals(PlaygroundArtifactOrigin.UPSTREAM, qwenOfficial.origin)
        assertEquals("Qwen", qwenOfficial.source.upstreamPublisher)
        assertEquals("Qwen", qwenOfficial.source.conversionPublisher)

        val qwenVlm = catalog.entries.first { it.id == "qwen3.5-0.8b-q4-k-m-bartowski" }
        assertEquals(PlaygroundArtifactOrigin.THIRD_PARTY, qwenVlm.origin)
        assertEquals("Qwen", qwenVlm.source.upstreamPublisher)
        assertEquals("bartowski", qwenVlm.source.conversionPublisher)
        assertEquals("FAILED_QUALITY", qwenVlm.state)
        assertEquals("quality_failed", qwenVlm.verifiedCapabilities.status)

        val gemma = catalog.entries.first { it.id == "gemma3-1b-it-q4-k-m-unsloth" }
        assertFalse(gemma.distribution.mirrorEligible)
        assertEquals("blocked", gemma.source.licenseReview)

        val harzva = catalog.entries.first { it.id == "qwen3-0.6b-q4-k-m" }
        assertEquals("Qwen", harzva.source.upstreamPublisher)
        assertEquals("Harzva", harzva.source.conversionPublisher)
        assertTrue(harzva.distribution.published)
        assertTrue(harzva.distribution.downloadable)
        assertEquals("huggingface_model_repo", harzva.distribution.mode)
        assertEquals("https_direct", harzva.distribution.installTransport)
        assertEquals("POST_PUBLISH_VERIFIED", harzva.distribution.publicationState)
        assertEquals("00b8b574c0cba5df1aa04971f179a7d29d828910", harzva.distribution.revision)
        assertEquals(
            "https://huggingface.co/harzva/mobilecore-qwen3-0.6b-gguf",
            harzva.distribution.repositoryUrl,
        )
        assertTrue(harzva.artifacts.single().sourceUrl!!.contains(harzva.distribution.revision!!))
    }

    @Test
    fun `expanded source catalog does not grant installation or device evidence`() {
        val catalog = PlaygroundCatalogParser.parse(assetText())
        val liquid = catalog.entries.first { it.id == "lfm2-350m-q4-k-m-official" }
        assertEquals("pending", liquid.source.licenseReview)
        assertFalse(liquid.distribution.downloadable)
        assertFalse(liquid.distribution.mirrorEligible)

        val smolVlm = catalog.entries.first { it.id == "smolvlm-256m-instruct-q8-0-ggml-org" }
        assertEquals(2, smolVlm.artifacts.size)
        assertTrue(smolVlm.declaredCapabilities.inputs.contains("image"))
        assertFalse(smolVlm.verifiedCapabilities.scopes.contains("physical_device"))

        catalog.entries.filter { it.state == "PROVENANCE_LOCKED" }.forEach {
            assertFalse(it.distribution.downloadable)
            assertEquals("unverified", it.verifiedCapabilities.status)
            assertTrue(it.verifiedCapabilities.inputs.isEmpty())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `origin label cannot rewrite third party attribution`() {
        val tampered = assetText().replaceFirst(
            "\"origin_label\": \"第三方转换\"",
            "\"origin_label\": \"Harzva 转换\"",
        )
        PlaygroundCatalogParser.parse(tampered)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `source URL must be safe HTTPS`() {
        val tampered = assetText().replaceFirst(
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            "file:///data/local/tmp/model.gguf",
        )
        PlaygroundCatalogParser.parse(tampered)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Hugging Face URL must reject non default HTTPS port`() {
        val tampered = assetText().replace(
            "https://huggingface.co/harzva/mobilecore-qwen3-0.6b-gguf",
            "https://huggingface.co:444/harzva/mobilecore-qwen3-0.6b-gguf",
        )
        PlaygroundCatalogParser.parse(tampered)
    }

    @Test
    fun `catalog digest and artifact digests are complete`() {
        val catalog = PlaygroundCatalogParser.parse(assetText())
        assertTrue(catalog.registryDigestSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(catalog.entries.flatMap { it.artifacts }.all { it.sha256.length == 64 && it.sizeBytes > 0 })
    }

    @Test
    fun `declared omni capability stays separate from verified capability`() {
        val omni = PlaygroundCatalogParser.parse(assetText()).entries
            .first { it.id == "qwen2.5-omni-3b-q4-k-m-ggml-org" }
        assertEquals(listOf("text", "image", "audio"), omni.declaredCapabilities.inputs)
        assertTrue(omni.verifiedCapabilities.inputs.isEmpty())
        assertEquals("unverified", omni.verifiedCapabilities.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `license blocked entry cannot become downloadable`() {
        val root = JSONObject(assetText())
        val entries = root.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (entry.getString("id") == "gemma3-1b-it-q4-k-m-unsloth") {
                entry.getJSONObject("distribution").put("downloadable", true)
            }
        }
        PlaygroundCatalogParser.parse(root.toString())
    }

    @Test
    fun `legacy catalog without license review scope remains valid`() {
        val root = JSONObject(assetText())
        val entries = root.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            entries.getJSONObject(index).getJSONObject("source").remove("license_review_scope")
        }

        val catalog = PlaygroundCatalogParser.parse(root.toString())

        catalog.entries.forEach { assertNull(it.source.licenseReviewScope) }
        assertTrue(catalog.entries.first { it.id == "qwen3-0.6b-q4-k-m" }.distribution.downloadable)
    }

    @Test
    fun `canonical review scope preserves existing download eligibility`() {
        val root = JSONObject(assetText())
        val entry = root.getJSONArray("entries").getJSONObject(0)
        entry.getJSONObject("source").put(
            "license_review_scope",
            "canonical_upstream_reference_and_direct_download",
        )

        val parsed = PlaygroundCatalogParser.parse(root.toString()).entries.first()

        assertEquals("canonical_upstream_reference_and_direct_download", parsed.source.licenseReviewScope)
        assertTrue(parsed.distribution.downloadable)
    }

    @Test
    fun `source link only scope remains valid when downloads are disabled`() {
        val root = JSONObject(assetText())
        val entry = root.getJSONArray("entries").getJSONObject(0)
        entry.getJSONObject("source").put("license_review_scope", "source_link_only")
        entry.getJSONObject("distribution").put("downloadable", false)

        val parsed = PlaygroundCatalogParser.parse(root.toString()).entries.first()

        assertEquals("source_link_only", parsed.source.licenseReviewScope)
        assertFalse(parsed.distribution.downloadable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `source link only review cannot become downloadable even with cleared license`() {
        val root = JSONObject(assetText())
        val entry = root.getJSONArray("entries").getJSONObject(0)
        entry.getJSONObject("source")
            .put("license_review", "cleared")
            .put("license_review_scope", "source_link_only")
        entry.getJSONObject("distribution").put("downloadable", true)

        PlaygroundCatalogParser.parse(root.toString())
    }

    @Test
    fun `present license review scope requires a supported non null string`() {
        listOf("", "direct_download", 1, true, JSONObject.NULL).forEach { invalidScope ->
            val root = JSONObject(assetText())
            root.getJSONArray("entries").getJSONObject(0).getJSONObject("source")
                .put("license_review_scope", invalidScope)

            val failure = runCatching { PlaygroundCatalogParser.parse(root.toString()) }.exceptionOrNull()

            assertTrue("Unsupported review scope must be rejected", failure is IllegalArgumentException)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `source rejects unknown fields including misspelled review scope`() {
        val root = JSONObject(assetText())
        root.getJSONArray("entries").getJSONObject(0).getJSONObject("source")
            .put("license_review_scopes", "source_link_only")

        PlaygroundCatalogParser.parse(root.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `published Hugging Face model requires pinned repository metadata`() {
        val root = JSONObject(assetText())
        val entries = root.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (entry.getString("id") == "qwen3-0.6b-q4-k-m") {
                entry.getJSONObject("distribution").remove("revision")
            }
        }
        PlaygroundCatalogParser.parse(root.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Git LFS entry cannot claim direct download support`() {
        val root = JSONObject(assetText())
        val entries = root.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (entry.getString("id") == "qwen3-0.6b-q4-k-m") {
                entry.getJSONObject("distribution")
                    .put("mode", "gitcode_model_repo")
                    .put("install_transport", "git_lfs_batch")
                entry.getJSONArray("artifacts").getJSONObject(0).put(
                    "source_url",
                    "https://gitcode.com/harzva/mobilecore-qwen3-0.6b-gguf/raw/model.gguf",
                )
            }
        }
        PlaygroundCatalogParser.parse(root.toString())
    }
}
