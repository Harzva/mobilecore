package ai.mobilecore.playground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class PlaygroundRuntimeTruthResolverTest {
    @Test
    fun `same public id in two roots is ambiguous without verified digest`() {
        val first = Files.createTempDirectory("runtime-a").resolve("same.gguf").toFile()
        val second = Files.createTempDirectory("runtime-b").resolve("same.gguf").toFile()
        val candidates = listOf(
            PlaygroundRuntimeCandidate("same", first.absolutePath),
            PlaygroundRuntimeCandidate("same", second.absolutePath),
        )

        assertNull(PlaygroundRuntimeTruthResolver.resolve(true, "same", null, candidates))
    }

    @Test
    fun `verified digest uniquely locates managed candidate without exposing path in health`() {
        val first = Files.createTempDirectory("runtime-a").resolve("same.gguf").toFile()
        val second = Files.createTempDirectory("runtime-b").resolve("same.gguf").toFile()
        val expectedDigest = "a".repeat(64)
        val candidates = listOf(
            PlaygroundRuntimeCandidate("same", first.absolutePath, expectedDigest),
            PlaygroundRuntimeCandidate("same", second.absolutePath, "b".repeat(64)),
        )

        assertEquals(
            first.canonicalPath,
            PlaygroundRuntimeTruthResolver.resolve(true, "same", expectedDigest, candidates),
        )
    }

    @Test
    fun `stale cached identity cannot resolve when runtime says unloaded`() {
        val stale = Files.createTempDirectory("runtime-stale").resolve("old.gguf").toFile()

        assertNull(
            PlaygroundRuntimeTruthResolver.resolve(
                modelLoaded = false,
                activeModelId = "old",
                verifiedMainDigest = "a".repeat(64),
                candidates = listOf(PlaygroundRuntimeCandidate("old", stale.absolutePath, "a".repeat(64))),
            ),
        )
    }
}
