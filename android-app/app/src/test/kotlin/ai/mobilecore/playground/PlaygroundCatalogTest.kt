package ai.mobilecore.playground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(6, catalog.entries.size)

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
        assertFalse(harzva.distribution.downloadable)
        assertEquals("gitcode_model_repo", harzva.distribution.mode)
        assertEquals("git_lfs_batch", harzva.distribution.installTransport)
        assertEquals("POST_PUBLISH_VERIFIED", harzva.distribution.publicationState)
        assertEquals("f985baad4b0637d500d38d755a92dc95dfa9c1ab", harzva.distribution.revision)
        assertEquals(
            "https://gitcode.com/harzva/mobilecore-qwen3-0.6b-gguf",
            harzva.distribution.repositoryUrl,
        )
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

    @Test(expected = IllegalArgumentException::class)
    fun `published GitCode model requires pinned repository metadata`() {
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
                entry.getJSONObject("distribution").put("downloadable", true)
                entry.getJSONArray("artifacts").getJSONObject(0).put(
                    "source_url",
                    "https://gitcode.com/harzva/mobilecore-qwen3-0.6b-gguf/raw/model.gguf",
                )
            }
        }
        PlaygroundCatalogParser.parse(root.toString())
    }
}
