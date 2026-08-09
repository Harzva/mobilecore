package ai.mobilecore.gallery.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture

class GalleryRuntimeLifecycleTest {
    @Before
    fun setUp() = GalleryRuntimeReleaseBarrier.resetForTest()

    @After
    fun tearDown() = GalleryRuntimeReleaseBarrier.resetForTest()

    @Test
    fun `replacement runtime sees incomplete process release barrier`() {
        val future = CompletableFuture<Unit>()

        GalleryRuntimeReleaseBarrier.register(future)

        assertSame(future, GalleryRuntimeReleaseBarrier.pending().single())
        future.complete(Unit)
        GalleryRuntimeReleaseBarrier.clear(future)
        assertTrue(GalleryRuntimeReleaseBarrier.pending().isEmpty())
    }

    @Test
    fun `completed release is never returned as a pending barrier`() {
        val future = CompletableFuture.completedFuture(Unit)

        GalleryRuntimeReleaseBarrier.register(future)

        assertTrue(GalleryRuntimeReleaseBarrier.pending().isEmpty())
    }

    @Test
    fun `failed native close remains fail closed for the process`() {
        val failed = CompletableFuture<Unit>()
        failed.completeExceptionally(IllegalStateException("native close failed"))
        GalleryRuntimeReleaseBarrier.register(failed)

        GalleryRuntimeReleaseBarrier.clear(failed)
        GalleryRuntimeReleaseBarrier.register(CompletableFuture.completedFuture(Unit))

        assertSame(failed, GalleryRuntimeReleaseBarrier.pending().single())
    }

    @Test
    fun `concurrent activity releases are all preserved`() {
        val first = CompletableFuture<Unit>()
        val second = CompletableFuture<Unit>()

        GalleryRuntimeReleaseBarrier.register(first)
        GalleryRuntimeReleaseBarrier.register(second)

        assertEquals(listOf(first, second), GalleryRuntimeReleaseBarrier.pending())
    }

    @Test
    fun `environment classifier separates sdk emulator from physical pixel`() {
        assertEquals(
            GalleryEvidenceEnvironment.ANDROID_EMULATOR,
            GalleryEvidenceEnvironmentClassifier.classify(
                fingerprint = "google/sdk_gphone64_arm64/emu64a:16/BP2A/test-keys",
                model = "sdk_gphone64_arm64",
                manufacturer = "Google",
                brand = "google",
                device = "emu64a",
                product = "sdk_gphone64_arm64",
                hardware = "ranchu",
            ),
        )
        assertEquals(
            GalleryEvidenceEnvironment.ANDROID_PHYSICAL,
            GalleryEvidenceEnvironmentClassifier.classify(
                fingerprint = "google/komodo/komodo:16/BP2A/release-keys",
                model = "Pixel 9 Pro XL",
                manufacturer = "Google",
                brand = "google",
                device = "komodo",
                product = "komodo",
                hardware = "tensor",
            ),
        )
    }
}
