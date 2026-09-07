package ai.mobilecore.playground

import ai.mobilecore.runtime.RuntimeModel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaygroundArtifactInstallerTest {
    private val directory = Files.createTempDirectory("mobilecore-playground-installer").toFile()
    private val payload = "a-pinned-gguf-payload".encodeToByteArray()
    private val spec = spec(payload)
    private val enoughStorage = PlaygroundInstallEnvironmentProbe {
        PlaygroundInstallEnvironment(spec.totalBytes + PlaygroundArtifactInstaller.DEFAULT_STORAGE_SAFETY_BYTES)
    }

    @After
    fun tearDown() {
        PlaygroundArtifactTrustRegistry.clearForTests()
        directory.deleteRecursively()
    }

    @Test
    fun `resumes part verifies atomically installs and uninstalls`() {
        val artifact = spec.primaryArtifact
        val split = 7
        val part = File(directory, ".${artifact.name}.part")
        directory.mkdirs()
        part.writeBytes(payload.copyOfRange(0, split))
        var observedResume = -1L
        var finalExistedDuringTransfer = true
        val installer = installer(
            transport = PlaygroundArtifactTransport { _, destination, resumeOffset, cancelled, onBytes ->
                observedResume = resumeOffset
                finalExistedDuringTransfer = File(directory, artifact.name).exists()
                assertFalse(cancelled())
                destination.appendBytes(payload.copyOfRange(resumeOffset.toInt(), payload.size))
                onBytes(payload.size.toLong())
            }
        )

        val handle = installer.install()

        assertTrue(handle.await(5_000L))
        assertEquals(split.toLong(), observedResume)
        assertFalse(finalExistedDuringTransfer)
        assertEquals(PlaygroundInstallPhase.INSTALLED, installer.snapshot().phase)
        assertTrue(installer.snapshot().verified)
        assertEquals(100, installer.snapshot().progressPercent)
        assertArrayEquals(payload, requireNotNull(installer.primaryModelFile()).readBytes())
        assertFalse(part.exists())

        val refused = installer.uninstall(activeModel = true)
        assertEquals(PlaygroundInstallFailureCode.ACTIVE_MODEL, refused.failure?.code)
        assertTrue(File(directory, artifact.name).exists())

        val removed = installer.uninstall(activeModel = false)
        assertEquals(PlaygroundInstallPhase.UNINSTALLED, removed.phase)
        assertNull(installer.primaryModelFile())
        assertFalse(File(directory, artifact.name).exists())
    }

    @Test
    fun `network failure keeps bounded part and retry resumes it`() {
        val artifact = spec.primaryArtifact
        val firstChunk = payload.copyOfRange(0, 6)
        var attempts = 0
        var retriedFrom = -1L
        val transport = PlaygroundArtifactTransport { _, destination, resumeOffset, _, onBytes ->
            attempts += 1
            if (attempts == 1) {
                destination.appendBytes(firstChunk)
                onBytes(firstChunk.size.toLong())
                throw IOException("transient")
            }
            retriedFrom = resumeOffset
            destination.appendBytes(payload.copyOfRange(resumeOffset.toInt(), payload.size))
            onBytes(payload.size.toLong())
        }
        val installer = installer(transport)

        assertTrue(installer.install().await(5_000L))
        assertEquals(PlaygroundInstallFailureCode.DOWNLOAD_FAILED, installer.snapshot().failure?.code)
        assertEquals(firstChunk.size.toLong(), File(directory, ".${artifact.name}.part").length())

        assertTrue(installer.install().await(5_000L))
        assertEquals(firstChunk.size.toLong(), retriedFrom)
        assertEquals(PlaygroundInstallPhase.INSTALLED, installer.snapshot().phase)
        assertArrayEquals(payload, requireNotNull(installer.primaryModelFile()).readBytes())
    }

    @Test
    fun `cancel is typed and removes temporary bytes`() {
        val entered = CountDownLatch(1)
        val installer = installer(
            PlaygroundArtifactTransport { _, destination, _, cancelled, onBytes ->
                destination.appendBytes(payload.copyOfRange(0, 4))
                onBytes(4L)
                entered.countDown()
                while (!cancelled()) Thread.yield()
                throw IOException("cancelled")
            }
        )

        val handle = installer.install()
        assertTrue(entered.await(2L, TimeUnit.SECONDS))
        handle.cancel()

        assertTrue(handle.await(5_000L))
        assertEquals(PlaygroundInstallPhase.CANCELLED, installer.snapshot().phase)
        assertEquals(PlaygroundInstallFailureCode.CANCELLED, installer.snapshot().failure?.code)
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `wrong byte count is typed and temporary file is deleted`() {
        val installer = installer(
            CopyingTransport(payload + byteArrayOf(0x01))
        )

        assertTrue(installer.install().await(5_000L))

        assertEquals(PlaygroundInstallPhase.VERIFICATION_FAILED, installer.snapshot().phase)
        assertEquals(PlaygroundInstallFailureCode.SIZE_MISMATCH, installer.snapshot().failure?.code)
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `wrong digest is typed and temporary file is deleted`() {
        val corrupt = payload.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val installer = installer(CopyingTransport(corrupt))

        assertTrue(installer.install().await(5_000L))

        assertEquals(PlaygroundInstallPhase.VERIFICATION_FAILED, installer.snapshot().phase)
        assertEquals(PlaygroundInstallFailureCode.CHECKSUM_MISMATCH, installer.snapshot().failure?.code)
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `same filename with wrong identity is never overwritten`() {
        val existing = File(directory, spec.primaryArtifact.name)
        directory.mkdirs()
        existing.writeBytes(ByteArray(payload.size) { 0x55 })
        var transportCalled = false
        val installer = installer(
            PlaygroundArtifactTransport { _, _, _, _, _ -> transportCalled = true }
        )

        assertEquals(PlaygroundInstallPhase.SOURCE_MISMATCH, installer.snapshot().phase)
        assertTrue(installer.managesModelPath(existing.absolutePath))
        assertFalse(installer.managesModelPath(File(directory, "other.gguf").absolutePath))
        assertNull(installer.primaryModelFile())
        assertTrue(installer.install().await(5_000L))
        assertEquals(PlaygroundInstallFailureCode.SOURCE_MISMATCH, installer.snapshot().failure?.code)
        assertFalse(transportCalled)
        assertArrayEquals(ByteArray(payload.size) { 0x55 }, existing.readBytes())
    }

    @Test
    fun `storage preflight fails before transport`() {
        var transportCalled = false
        val installer = PlaygroundArtifactInstaller(
            installDirectory = directory,
            environmentProbe = PlaygroundInstallEnvironmentProbe { PlaygroundInstallEnvironment(1L) },
            spec = spec,
            transport = PlaygroundArtifactTransport { _, _, _, _, _ -> transportCalled = true },
        )

        assertTrue(installer.install().await(5_000L))

        assertEquals(PlaygroundInstallFailureCode.INSUFFICIENT_STORAGE, installer.snapshot().failure?.code)
        assertFalse(transportCalled)
    }

    @Test
    fun `catalog contract rejects cross repository and traversal sources`() {
        val catalog = PlaygroundCatalogParser.parse(
            File("src/main/assets/mobile-model-playground/catalog-v1.json").readText()
        )
        val entry = catalog.entries.first { it.id == "qwen3-0.6b-q4-k-m" }
        assertNotNull(PlaygroundInstallSpec.fromCatalogEntry(entry))

        val crossRepository = entry.copy(
            artifacts = entry.artifacts.map { artifact ->
                artifact.copy(sourceUrl = artifact.sourceUrl?.replace("/harzva/", "/attacker/"))
            }
        )
        val traversal = entry.copy(
            artifacts = entry.artifacts.map { artifact -> artifact.copy(name = "../model.gguf") }
        )

        assertFails { PlaygroundInstallSpec.fromCatalogEntry(crossRepository) }
        assertFails { PlaygroundInstallSpec.fromCatalogEntry(traversal) }
    }

    @Test
    fun `install contract rejects source link only scope even with tampered eligibility flags`() {
        val catalog = PlaygroundCatalogParser.parse(
            File("src/main/assets/mobile-model-playground/catalog-v1.json").readText()
        )
        val entry = catalog.entries.first { it.id == "qwen3-0.6b-q4-k-m" }
        val sourceLinkOnly = entry.copy(
            source = entry.source.copy(licenseReview = "cleared", licenseReviewScope = "source_link_only"),
            distribution = entry.distribution.copy(downloadable = true, installTransport = "https_direct"),
        )

        assertFails { PlaygroundInstallSpec.fromCatalogEntry(sourceLinkOnly) }
    }

    @Test
    fun `install contract accepts legacy and canonical review scopes`() {
        val catalog = PlaygroundCatalogParser.parse(
            File("src/main/assets/mobile-model-playground/catalog-v1.json").readText()
        )
        val entry = catalog.entries.first { it.id == "qwen3-0.6b-q4-k-m" }
        val legacy = entry.copy(source = entry.source.copy(licenseReviewScope = null))
        val canonical = entry.copy(
            source = entry.source.copy(licenseReviewScope = "canonical_upstream_reference_and_direct_download"),
        )

        assertNotNull(PlaygroundInstallSpec.fromCatalogEntry(legacy))
        assertNotNull(PlaygroundInstallSpec.fromCatalogEntry(canonical))
    }

    @Test
    fun `verified install has explicit loading loaded and load failure states`() {
        val installer = installer(CopyingTransport(payload))
        assertTrue(installer.install().await(5_000L))

        assertEquals(PlaygroundInstallPhase.LOADING, installer.markLoading().phase)
        assertEquals(PlaygroundInstallPhase.LOADED, installer.markLoaded().phase)
        assertEquals(PlaygroundInstallFailureCode.MODEL_LOAD_FAILED, installer.markLoadFailed().failure?.code)
    }

    @Test
    fun `persisted stat metadata requires a fresh startup SHA verification`() {
        val installed = installer(CopyingTransport(payload))
        assertTrue(installed.install().await(5_000L))
        assertEquals(PlaygroundInstallPhase.INSTALLED, installed.snapshot().phase)
        PlaygroundArtifactTrustRegistry.clearForTests()

        val reopened = installer(CopyingTransport(payload))

        assertEquals(PlaygroundInstallPhase.VERIFYING, reopened.snapshot().phase)
        assertTrue(reopened.needsStartupVerification())
        assertTrue(reopened.beginStartupVerification())
        assertFalse(reopened.beginStartupVerification())
        assertFalse(reopened.snapshot().verified)
        assertNull(reopened.primaryModelFile())
        assertTrue(reopened.verifyInstalled().await(5_000L))
        assertEquals(PlaygroundInstallPhase.INSTALLED, reopened.snapshot().phase)
        assertTrue(reopened.snapshot().verified)
        assertFalse(reopened.beginStartupVerification())
    }

    @Test
    fun `new installer in same process reuses only current-process digest proof`() {
        val first = installer(CopyingTransport(payload))
        assertTrue(first.install().await(5_000L))

        val recreated = installer(CopyingTransport(payload))

        assertEquals(PlaygroundInstallPhase.INSTALLED, recreated.snapshot().phase)
        assertTrue(recreated.snapshot().verified)
        assertNotNull(recreated.primaryModelFile())
        assertFalse(recreated.needsStartupVerification())
    }

    @Test
    fun `reopened install API rehashes persisted artifacts without transport`() {
        val first = installer(CopyingTransport(payload))
        assertTrue(first.install().await(5_000L))
        PlaygroundArtifactTrustRegistry.clearForTests()
        var transportCalled = false
        val reopened = installer(
            PlaygroundArtifactTransport { _, _, _, _, _ -> transportCalled = true }
        )

        assertTrue(reopened.install().await(5_000L))

        assertFalse(transportCalled)
        assertEquals(PlaygroundInstallPhase.INSTALLED, reopened.snapshot().phase)
        assertTrue(reopened.snapshot().verified)
    }

    @Test
    fun `snapshot immediately demotes loaded state when stat proof changes`() {
        val installer = installer(CopyingTransport(payload))
        assertTrue(installer.install().await(5_000L))
        assertEquals(PlaygroundInstallPhase.LOADED, installer.markLoaded().phase)
        val installedFile = requireNotNull(installer.primaryModelFile())
        assertTrue(installedFile.setLastModified(installedFile.lastModified() + 5_000L))

        val snapshot = installer.snapshot()

        assertFalse(snapshot.verified)
        assertEquals(PlaygroundInstallPhase.SOURCE_MISMATCH, snapshot.phase)
        assertEquals(PlaygroundInstallFailureCode.SOURCE_MISMATCH, snapshot.failure?.code)
        assertNull(installer.primaryModelFile())
    }

    @Test
    fun `full verification runs on worker and cancellation is typed`() {
        val installed = installer(CopyingTransport(payload))
        assertTrue(installed.install().await(5_000L))
        PlaygroundArtifactTrustRegistry.clearForTests()
        val enteredDigest = CountDownLatch(1)
        var digestThreadName: String? = null
        val reopened = installer(
            transport = CopyingTransport(payload),
            artifactDigest = { _, cancelled ->
                digestThreadName = Thread.currentThread().name
                enteredDigest.countDown()
                while (!cancelled()) Thread.yield()
                throw IOException("cancelled")
            },
        )

        val callerThread = Thread.currentThread().name
        val handle = reopened.verifyInstalled()
        assertTrue(enteredDigest.await(2L, TimeUnit.SECONDS))
        assertEquals(PlaygroundInstallPhase.VERIFYING, reopened.snapshot().phase)
        handle.cancel()

        assertTrue(handle.await(5_000L))
        assertFalse(callerThread == digestThreadName)
        assertEquals(PlaygroundInstallPhase.CANCELLED, reopened.snapshot().phase)
        assertEquals(PlaygroundInstallFailureCode.CANCELLED, reopened.snapshot().failure?.code)
        assertTrue(File(directory, spec.primaryArtifact.name).isFile)
        assertFalse(reopened.snapshot().verified)
    }

    @Test
    fun `verification IO failure is terminal typed and claim can recover`() {
        val installed = installer(CopyingTransport(payload))
        assertTrue(installed.install().await(5_000L))
        PlaygroundArtifactTrustRegistry.clearForTests()
        val attempts = AtomicInteger(0)
        val reopened = installer(
            transport = CopyingTransport(payload),
            artifactDigest = { file, cancelled ->
                if (attempts.getAndIncrement() == 0) throw IOException("read failed")
                playgroundSha256(file, cancelled)
            },
        )

        assertTrue(reopened.verifyInstalled().await(5_000L))
        assertEquals(PlaygroundInstallPhase.VERIFICATION_FAILED, reopened.snapshot().phase)
        assertEquals(
            PlaygroundInstallFailureCode.VERIFICATION_IO_FAILED,
            reopened.snapshot().failure?.code,
        )

        assertTrue(reopened.verifyInstalled().await(5_000L))
        assertEquals(PlaygroundInstallPhase.INSTALLED, reopened.snapshot().phase)
        assertTrue(reopened.snapshot().verified)
    }

    @Test
    fun `process bootstrap rehashes persisted model before health trust is available`() {
        val installed = installer(CopyingTransport(payload))
        assertTrue(installed.install().await(5_000L))
        PlaygroundArtifactTrustRegistry.clearForTests()
        val reopened = installer(CopyingTransport(payload))
        assertNull(reopened.primaryModelFile())

        val bootstrap = PlaygroundTrustBootstrap { listOf(reopened) }.start()
        bootstrap.join(5_000L)

        assertFalse(bootstrap.isAlive)
        assertEquals(PlaygroundInstallPhase.INSTALLED, reopened.snapshot().phase)
        assertTrue(reopened.snapshot().verified)
        assertNotNull(reopened.primaryModelFile())
    }

    @Test
    fun `concurrent startup verification can be claimed only once`() {
        val first = installer(CopyingTransport(payload))
        assertTrue(first.install().await(5_000L))
        PlaygroundArtifactTrustRegistry.clearForTests()
        val reopened = installer(CopyingTransport(payload))
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val threads = List(8) {
            Thread {
                ready.countDown()
                start.await()
                results += reopened.beginStartupVerification()
            }.apply { start() }
        }
        assertTrue(ready.await(2L, TimeUnit.SECONDS))

        start.countDown()
        threads.forEach { it.join(2_000L) }

        assertEquals(1, results.count { it })
        assertEquals(7, results.count { !it })
    }

    @Test
    fun `health resolver exposes only current-process proof matching built-in catalog`() {
        val installer = installer(CopyingTransport(payload))
        assertTrue(installer.install().await(5_000L))
        val baseCatalog = PlaygroundCatalogParser.parse(
            File("src/main/assets/mobile-model-playground/catalog-v1.json").readText()
        )
        val baseEntry = baseCatalog.entries.first { it.id == "qwen3-0.6b-q4-k-m" }
        val artifact = spec.primaryArtifact
        val catalogEntry = baseEntry.copy(
            id = spec.id,
            source = baseEntry.source.copy(
                repository = spec.repositoryUrl,
                revision = spec.revision,
            ),
            artifacts = listOf(
                baseEntry.artifacts.first().copy(
                    name = artifact.name,
                    sizeBytes = artifact.byteSize,
                    sha256 = artifact.sha256,
                    sourceUrl = artifact.sourceUrl,
                )
            ),
            distribution = baseEntry.distribution.copy(
                repositoryUrl = spec.repositoryUrl,
                revision = spec.revision,
            ),
        )
        val resolver = PlaygroundArtifactHealthResolver(
            catalogProvider = { baseCatalog.copy(entries = listOf(catalogEntry)) },
            modelDirectories = { listOf(directory) },
        )
        val file = requireNotNull(installer.primaryModelFile())
        val model = RuntimeModel(
            id = file.nameWithoutExtension,
            path = file.absolutePath,
            format = "gguf",
            backend = "llama.cpp",
            quantization = "Q4_K_M",
            contextLength = 2048,
            sizeBytes = file.length(),
            loaded = true,
        )

        val health = requireNotNull(resolver.resolve(model))

        assertTrue(health.verified)
        assertEquals(artifact.sha256, health.expectedSha256)
        assertEquals(artifact.byteSize, health.expectedBytes)
        assertNull(resolver.resolve(model.copy(sizeBytes = artifact.byteSize + 1L)))
        PlaygroundArtifactTrustRegistry.clearForTests()
        assertNull(resolver.resolve(model))
    }

    @Test
    fun `redirect policy permits only controlled HTTPS Hugging Face artifact hosts`() {
        val pinned = URI("https://huggingface.co/harzva/model/resolve/$REVISION/model.gguf")

        assertTrue(PlaygroundRedirectPolicy.isAllowed(pinned, URI("https://cas-bridge.xethub.hf.co/blob?id=1")))
        assertTrue(PlaygroundRedirectPolicy.isAllowed(pinned, URI("https://cdn-lfs-us-1.hf.co/blob?id=1")))
        assertTrue(PlaygroundRedirectPolicy.isAllowed(pinned, URI("https://cdn-lfs.huggingface.co/blob?id=1")))
        assertFalse(PlaygroundRedirectPolicy.isAllowed(pinned, URI("https://example.com/blob")))
        assertFalse(PlaygroundRedirectPolicy.isAllowed(pinned, URI("http://cas-bridge.xethub.hf.co/blob")))
        assertFalse(PlaygroundRedirectPolicy.isAllowed(pinned, URI("https://cas-bridge.xethub.hf.co:444/blob")))
        assertFalse(PlaygroundRedirectPolicy.isAllowed(pinned, URI("https://user@cas-bridge.xethub.hf.co/blob")))
        assertFalse(PlaygroundRedirectPolicy.isAllowed(pinned, URI("https://cas-bridge.xethub.hf.co/blob#fragment")))
        assertFalse(PlaygroundRedirectPolicy.isAllowed(pinned, URI("https://127.0.0.1/blob")))
        assertFalse(PlaygroundRedirectPolicy.isAllowed(pinned, URI("https://huggingface.co.example.com/blob")))
    }

    private fun installer(
        transport: PlaygroundArtifactTransport,
        artifactDigest: (File, () -> Boolean) -> String = ::playgroundSha256,
    ): PlaygroundArtifactInstaller {
        return PlaygroundArtifactInstaller(
            installDirectory = directory,
            environmentProbe = enoughStorage,
            spec = spec,
            transport = transport,
            storageSafetyBytes = 0L,
            artifactDigest = artifactDigest,
        )
    }

    private class CopyingTransport(private val bytes: ByteArray) : PlaygroundArtifactTransport {
        override fun download(
            artifact: PlaygroundInstallArtifact,
            destinationPart: File,
            resumeOffset: Long,
            cancelled: () -> Boolean,
            onBytes: (Long) -> Unit,
        ) {
            check(!cancelled())
            destinationPart.appendBytes(bytes.copyOfRange(resumeOffset.toInt().coerceAtMost(bytes.size), bytes.size))
            onBytes(destinationPart.length())
        }
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    private companion object {
        private const val REVISION = "0123456789abcdef0123456789abcdef01234567"

        fun spec(bytes: ByteArray): PlaygroundInstallSpec {
            val name = "model-q4_k_m.gguf"
            return PlaygroundInstallSpec(
                id = "test-model",
                revision = REVISION,
                repositoryUrl = "https://huggingface.co/test/model",
                artifacts = listOf(
                    PlaygroundInstallArtifact(
                        name = name,
                        role = "mobile_runtime",
                        byteSize = bytes.size.toLong(),
                        sha256 = MessageDigest.getInstance("SHA-256")
                            .digest(bytes)
                            .joinToString("") { "%02x".format(it) },
                        sourceUrl = "https://huggingface.co/test/model/resolve/$REVISION/$name?download=true",
                    )
                ),
            )
        }
    }
}
