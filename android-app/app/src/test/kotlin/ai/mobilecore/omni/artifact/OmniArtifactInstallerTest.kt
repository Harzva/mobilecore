package ai.mobilecore.omni.artifact

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
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
