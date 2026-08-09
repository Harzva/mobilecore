package ai.mobilecore.omni.artifact

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OmniArtifactInstallerTest {
    private val testDirectory = Files.createTempDirectory("mobilecore-omni-installer").toFile()
    private val mainBytes = "main-model".encodeToByteArray()
    private val mmprojBytes = "multimodal-projector".encodeToByteArray()
    private val manifest = testManifest(mainBytes, mmprojBytes)
    private val request = OmniInstallRequest(
        explicitConsent = true,
        acceptedLicenseId = manifest.licenseId,
        wifiOnly = true
    )
    private val environmentProbe = OmniInstallEnvironmentProbe {
        OmniInstallEnvironment(
            availableMemoryBytes = manifest.minimumAvailableMemoryBytes,
            availableStorageBytes = manifest.requiredStorageBytes,
            wifiConnected = true
        )
    }

    @After
    fun tearDown() {
        testDirectory.deleteRecursively()
    }

    @Test
    fun installsVerifiesLoadsAndUninstallsThePair() {
        val payloads = mapOf(
            OmniArtifactRole.MAIN to mainBytes,
            OmniArtifactRole.MMPROJ to mmprojBytes
        )
        val installer = OmniArtifactInstaller(
            installDirectory = testDirectory,
            environmentProbe = environmentProbe,
            manifest = manifest,
            transport = CopyingTransport(payloads),
            clock = { 42L }
        )

        val handle = installer.install(request)

        assertTrue(handle.started)
        assertTrue(handle.await(5_000L))
        assertTrue(installer.snapshot().pairVerified)
        assertEquals(42L, installer.snapshot().main.verifiedAtEpochMs)
        assertTrue(installer.loadVerifiedPair { mainPath, mmprojPath ->
            File(mainPath).readBytes().contentEquals(mainBytes) &&
                File(mmprojPath).readBytes().contentEquals(mmprojBytes)
        } is OmniLoadPairResult.Loaded)

        val uninstalled = installer.uninstall()
        assertEquals(OmniInstallPhase.UNINSTALLED, uninstalled.phase)
        assertFalse(uninstalled.main.installed)
        assertFalse(uninstalled.mmproj.installed)
    }

    @Test
    fun rejectsChecksumMismatchAndDeletesPartFile() {
        val installer = OmniArtifactInstaller(
            installDirectory = testDirectory,
            environmentProbe = environmentProbe,
            manifest = manifest,
            transport = CopyingTransport(
                mapOf(
                    OmniArtifactRole.MAIN to "corrupt!!!".encodeToByteArray(),
                    OmniArtifactRole.MMPROJ to mmprojBytes
                )
            )
        )

        val handle = installer.install(request)

        assertTrue(handle.await(5_000L))
        assertEquals(OmniArtifactFailureCode.CHECKSUM_MISMATCH, installer.snapshot().failure?.code)
        assertTrue(testDirectory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun cancellationIsTypedAndCleansTemporaryFile() {
        val enteredDownload = CountDownLatch(1)
        val transport = OmniArtifactTransport { _, destination, cancelled, _ ->
            destination.writeText("partial")
            enteredDownload.countDown()
            while (!cancelled()) Thread.sleep(1L)
            throw IOException("cancelled")
        }
        val installer = OmniArtifactInstaller(
            installDirectory = testDirectory,
            environmentProbe = environmentProbe,
            manifest = manifest,
            transport = transport
        )

        val handle = installer.install(request)
        assertTrue(enteredDownload.await(2L, TimeUnit.SECONDS))
        handle.cancel()

        assertTrue(handle.await(5_000L))
        assertEquals(OmniArtifactFailureCode.CANCELLED, installer.snapshot().failure?.code)
        assertTrue(testDirectory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun snapshotDoesNotRehashUnchangedArtifacts() {
        val installer = OmniArtifactInstaller(
            installDirectory = testDirectory,
            environmentProbe = environmentProbe,
            manifest = manifest,
            transport = CopyingTransport(
                mapOf(
                    OmniArtifactRole.MAIN to mainBytes,
                    OmniArtifactRole.MMPROJ to mmprojBytes
                )
            )
        )
        val handle = installer.install(request)
        assertTrue(handle.await(5_000L))

        val main = File(testDirectory, manifest.artifact(OmniArtifactRole.MAIN)!!.fileName)
        main.setLastModified(main.lastModified() + 1L)

        assertFalse(installer.snapshot().main.verified)
        assertTrue(installer.snapshot().main.installed)
    }

    @Test
    fun `new process cannot trust persisted pair until asynchronous full digest bootstrap`() {
        val first = OmniArtifactInstaller(
            installDirectory = testDirectory,
            environmentProbe = environmentProbe,
            manifest = manifest,
            transport = CopyingTransport(
                mapOf(
                    OmniArtifactRole.MAIN to mainBytes,
                    OmniArtifactRole.MMPROJ to mmprojBytes,
                ),
            ),
        )
        assertTrue(first.install(request).await(5_000L))
        assertTrue(first.snapshot().pairVerified)

        val restarted = OmniArtifactInstaller(
            installDirectory = testDirectory,
            environmentProbe = environmentProbe,
            manifest = manifest,
        )

        assertFalse(restarted.snapshot().pairVerified)
        assertTrue(restarted.loadVerifiedPair { _, _ -> true } is OmniLoadPairResult.Failed)
        val bootstrap = restarted.startStartupVerification()
        assertTrue(bootstrap.started)
        assertTrue(bootstrap.await(5_000L))
        assertTrue(restarted.snapshot().pairVerified)
    }

    @Test
    fun `startup digest verification is cancellable and never restores partial trust`() {
        val first = OmniArtifactInstaller(
            installDirectory = testDirectory,
            environmentProbe = environmentProbe,
            manifest = manifest,
            transport = CopyingTransport(
                mapOf(
                    OmniArtifactRole.MAIN to mainBytes,
                    OmniArtifactRole.MMPROJ to mmprojBytes,
                ),
            ),
        )
        assertTrue(first.install(request).await(5_000L))
        val digestEntered = CountDownLatch(1)
        val restarted = OmniArtifactInstaller(
            installDirectory = testDirectory,
            environmentProbe = environmentProbe,
            manifest = manifest,
            artifactDigest = { _, cancelled ->
                digestEntered.countDown()
                while (!cancelled()) Thread.sleep(1L)
                throw IOException("cancelled")
            },
        )

        val bootstrap = restarted.startStartupVerification()
        assertTrue(digestEntered.await(2L, TimeUnit.SECONDS))
        bootstrap.cancel()

        assertTrue(bootstrap.await(5_000L))
        assertEquals(OmniArtifactFailureCode.CANCELLED, restarted.snapshot().failure?.code)
        assertFalse(restarted.snapshot().pairVerified)
    }

    @Test
    fun `initial source is exact credential-free revision-pinned Hugging Face HTTPS`() {
        val artifact = manifest.artifact(OmniArtifactRole.MAIN)!!

        assertNotNull(OmniArtifactSourcePolicy.pinnedSourceOrNull(artifact))
        assertTrue(OmniArtifactSourcePolicy.matchesManifest(manifest, artifact))
        assertNull(
            OmniArtifactSourcePolicy.pinnedSourceOrNull(
                artifact.copy(sourceUrl = artifact.sourceUrl.replace("https://", "http://"))
            )
        )
        assertNull(
            OmniArtifactSourcePolicy.pinnedSourceOrNull(
                artifact.copy(sourceUrl = artifact.sourceUrl.replace("huggingface.co", "huggingface.co:444"))
            )
        )
        assertNull(
            OmniArtifactSourcePolicy.pinnedSourceOrNull(
                artifact.copy(sourceUrl = artifact.sourceUrl.replace("huggingface.co", "user@huggingface.co"))
            )
        )
        assertNull(OmniArtifactSourcePolicy.pinnedSourceOrNull(artifact.copy(sourceUrl = "${artifact.sourceUrl}#fragment")))
        assertNull(
            OmniArtifactSourcePolicy.pinnedSourceOrNull(
                artifact.copy(sourceUrl = artifact.sourceUrl.replace("huggingface.co", "127.0.0.1"))
            )
        )
        assertNull(
            OmniArtifactSourcePolicy.pinnedSourceOrNull(
                artifact.copy(sourceUrl = artifact.sourceUrl.replace("huggingface.co", "example.com"))
            )
        )
        assertNull(
            OmniArtifactSourcePolicy.pinnedSourceOrNull(
                artifact.copy(sourceUrl = artifact.sourceUrl.replace(artifact.fileName, "other.gguf"))
            )
        )
        assertFalse(
            OmniArtifactSourcePolicy.matchesManifest(
                manifest.copy(sourceRepository = "https://huggingface.co/other/repo"),
                artifact
            )
        )
    }

    @Test
    fun `redirect policy allows only controlled HTTPS Hugging Face Xet and CDN hosts`() {
        val artifact = manifest.artifact(OmniArtifactRole.MAIN)!!
        val pinned = requireNotNull(OmniArtifactSourcePolicy.pinnedSourceOrNull(artifact))

        assertTrue(OmniArtifactSourcePolicy.isAllowedRedirect(pinned, URI("https://cas-bridge.xethub.hf.co/blob?id=1")))
        assertTrue(OmniArtifactSourcePolicy.isAllowedRedirect(pinned, URI("https://cdn-lfs-us-1.hf.co/blob?id=1")))
        assertTrue(OmniArtifactSourcePolicy.isAllowedRedirect(pinned, URI("https://cdn-lfs.huggingface.co/blob?id=1")))
        assertFalse(OmniArtifactSourcePolicy.isAllowedRedirect(pinned, URI("http://cas-bridge.xethub.hf.co/blob")))
        assertFalse(OmniArtifactSourcePolicy.isAllowedRedirect(pinned, URI("https://cas-bridge.xethub.hf.co:444/blob")))
        assertFalse(OmniArtifactSourcePolicy.isAllowedRedirect(pinned, URI("https://user@cas-bridge.xethub.hf.co/blob")))
        assertFalse(OmniArtifactSourcePolicy.isAllowedRedirect(pinned, URI("https://cas-bridge.xethub.hf.co/blob#fragment")))
        assertFalse(OmniArtifactSourcePolicy.isAllowedRedirect(pinned, URI("https://127.0.0.1/blob")))
        assertFalse(OmniArtifactSourcePolicy.isAllowedRedirect(pinned, URI("https://example.com/blob")))
        assertFalse(OmniArtifactSourcePolicy.isAllowedRedirect(pinned, URI("https://huggingface.co.example.com/blob")))
    }

    @Test
    fun `manifest repository mismatch is a typed source mismatch before download`() {
        var downloadCalls = 0
        val mismatchedManifest = manifest.copy(sourceRepository = "https://huggingface.co/other/repo")
        val installer = OmniArtifactInstaller(
            installDirectory = testDirectory,
            environmentProbe = environmentProbe,
            manifest = mismatchedManifest,
            transport = OmniArtifactTransport { _, _, _, _ -> downloadCalls++ }
        )

        val handle = installer.install(request)

        assertTrue(handle.await(5_000L))
        assertEquals(0, downloadCalls)
        assertEquals(OmniArtifactFailureCode.SOURCE_MISMATCH, installer.snapshot().failure?.code)
    }

    @Test
    fun `existing mismatched final file is never overwritten`() {
        val artifact = manifest.artifact(OmniArtifactRole.MAIN)!!
        val finalFile = File(testDirectory, artifact.fileName)
        val existingBytes = "foreign-final".encodeToByteArray()
        finalFile.writeBytes(existingBytes)
        var downloadCalls = 0
        val installer = OmniArtifactInstaller(
            installDirectory = testDirectory,
            environmentProbe = environmentProbe,
            manifest = manifest,
            transport = OmniArtifactTransport { _, _, _, _ -> downloadCalls++ }
        )

        val handle = installer.install(request)

        assertTrue(handle.await(5_000L))
        assertEquals(0, downloadCalls)
        assertEquals(OmniArtifactFailureCode.SOURCE_MISMATCH, installer.snapshot().failure?.code)
        assertTrue(finalFile.readBytes().contentEquals(existingBytes))
    }

    @Test
    fun `atomic move unavailable fails closed without a final artifact`() {
        val installer = OmniArtifactInstaller(
            installDirectory = testDirectory,
            environmentProbe = environmentProbe,
            manifest = manifest,
            transport = CopyingTransport(
                mapOf(
                    OmniArtifactRole.MAIN to mainBytes,
                    OmniArtifactRole.MMPROJ to mmprojBytes
                )
            ),
            atomicMove = { source, target ->
                throw AtomicMoveNotSupportedException(source.path, target.path, "unsupported")
            }
        )

        val handle = installer.install(request)

        assertTrue(handle.await(5_000L))
        assertEquals(OmniArtifactFailureCode.ATOMIC_INSTALL_FAILED, installer.snapshot().failure?.code)
        assertFalse(File(testDirectory, manifest.artifact(OmniArtifactRole.MAIN)!!.fileName).exists())
        assertFalse(installer.snapshot().pairVerified)
    }

    @Test
    fun `new installer failures have stable wire values`() {
        assertEquals("source_mismatch", OmniArtifactFailureCode.SOURCE_MISMATCH.wireValue)
        assertEquals("atomic_install_failed", OmniArtifactFailureCode.ATOMIC_INSTALL_FAILED.wireValue)
    }

    private class CopyingTransport(
        private val payloads: Map<OmniArtifactRole, ByteArray>
    ) : OmniArtifactTransport {
        override fun download(
            artifact: OmniArtifactSpec,
            destinationPart: File,
            cancelled: () -> Boolean,
            onBytes: (Long) -> Unit
        ) {
            check(!cancelled())
            val bytes = payloads.getValue(artifact.role)
            destinationPart.writeBytes(bytes)
            onBytes(bytes.size.toLong())
        }
    }

    companion object {
        private const val REVISION = "0123456789abcdef0123456789abcdef01234567"

        private fun testManifest(main: ByteArray, mmproj: ByteArray): OmniArtifactManifest {
            return OmniArtifactManifest(
                schemaVersion = 1,
                id = "test-omni-pair",
                displayName = "test",
                sourceRepository = "https://huggingface.co/test/repo",
                originalModel = "https://huggingface.co/test/original",
                conversionPublisher = "test",
                licenseId = "test-license",
                licenseReviewStatus = OmniLicenseReviewStatus.SOURCE_DECLARED_NOT_LEGAL_REVIEWED,
                quantization = "test",
                runtime = "test",
                backend = "test",
                minimumAvailableMemoryBytes = 10L,
                storageSafetyBytes = 5L,
                artifacts = listOf(
                    artifact(OmniArtifactRole.MAIN, "main.gguf", main),
                    artifact(OmniArtifactRole.MMPROJ, "mmproj.gguf", mmproj)
                )
            )
        }

        private fun artifact(role: OmniArtifactRole, name: String, bytes: ByteArray): OmniArtifactSpec {
            return OmniArtifactSpec(
                role = role,
                fileName = name,
                revision = REVISION,
                byteSize = bytes.size.toLong(),
                sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) },
                sourceUrl = "https://huggingface.co/test/repo/resolve/$REVISION/$name"
            )
        }
    }
}
