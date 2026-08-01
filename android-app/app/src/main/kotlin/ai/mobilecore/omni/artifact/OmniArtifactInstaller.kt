package ai.mobilecore.omni.artifact

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

fun interface OmniInstallEnvironmentProbe {
    fun probe(): OmniInstallEnvironment
}

fun interface OmniInstallStateListener {
    fun onState(snapshot: OmniInstallSnapshot)
}

fun interface OmniArtifactTransport {
    @Throws(IOException::class)
    fun download(
        artifact: OmniArtifactSpec,
        destinationPart: File,
        cancelled: () -> Boolean,
        onBytes: (Long) -> Unit
    )
}

class HttpOmniArtifactTransport : OmniArtifactTransport {
    override fun download(
        artifact: OmniArtifactSpec,
        destinationPart: File,
        cancelled: () -> Boolean,
        onBytes: (Long) -> Unit
    ) {
        val source = URI(artifact.sourceUrl)
        require(source.scheme == "https" && source.host == "huggingface.co") {
            "Only the revision-pinned Hugging Face manifest source is allowed"
        }

        destinationPart.parentFile?.mkdirs()
        val connection = source.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "MobileCore-OmniArtifactInstaller/1")

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IOException("Artifact source returned HTTP $responseCode")
            val declaredLength = connection.contentLengthLong
            if (declaredLength > 0L && declaredLength != artifact.byteSize) {
                throw IOException("Artifact source length does not match the pinned manifest")
            }

            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(destinationPart, false)).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var written = 0L
                    while (true) {
                        if (cancelled()) throw OmniCancelledIOException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > artifact.byteSize) {
                            throw IOException("Artifact exceeds its pinned byte size")
                        }
                        output.write(buffer, 0, count)
                        onBytes(written)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private class OmniCancelledIOException : IOException("cancelled")
}

/**
 * Callable install lifecycle for the exact main-GGUF + mmproj pair.
 *
 * The state listener receives metadata only. Paths, URLs, content, and downloaded bytes are never
 * logged or persisted as evidence. SHA-256 is calculated only after a download or when
 * [verifyInstalledPair] is explicitly requested. [snapshot] uses a stat-bound verification record.
 */
class OmniArtifactInstaller(
    private val installDirectory: File,
    private val environmentProbe: OmniInstallEnvironmentProbe,
    private val manifest: OmniArtifactManifest = Qwen25Omni3bArtifacts.manifest,
    private val transport: OmniArtifactTransport = HttpOmniArtifactTransport(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val cancelled = AtomicBoolean(false)
    private val verificationStore = OmniVerificationStore(
        File(installDirectory, ".${manifest.id}.verification.properties")
    )
    private val lock = Any()

    @Volatile
    private var phase: OmniInstallPhase = OmniInstallPhase.IDLE

    @Volatile
    private var lastPreflight: OmniPreflightResult? = null

    @Volatile
    private var lastFailure: OmniArtifactFailure? = null

    @Volatile
    private var activeWorker: Thread? = null

    fun install(
        request: OmniInstallRequest,
        listener: OmniInstallStateListener = OmniInstallStateListener { }
    ): OmniInstallHandle {
        synchronized(lock) {
            if (activeWorker?.isAlive == true) {
                val failure = OmniArtifactFailure(
                    OmniArtifactFailureCode.INSTALL_IN_PROGRESS,
                    "An artifact install is already in progress"
                )
                return OmniInstallHandle(false, failure, null) { cancel() }
            }
            cancelled.set(false)
            val worker = Thread(
                { runInstall(request, listener) },
                "mobilecore-omni-artifact-install"
            ).apply { isDaemon = true }
            activeWorker = worker
            worker.start()
            return OmniInstallHandle(true, null, worker) { cancel() }
        }
    }

    fun cancel() {
        cancelled.set(true)
    }

    fun uninstall(): OmniInstallSnapshot {
        cancel()
        val worker = synchronized(lock) { activeWorker }
        worker?.join(35_000L)
        if (worker?.isAlive == true) {
            lastFailure = OmniArtifactFailure(
                OmniArtifactFailureCode.INSTALL_IN_PROGRESS,
                "Cancellation has not completed; uninstall did not remove active files"
            )
            phase = OmniInstallPhase.FAILED
            return snapshot()
        }
        synchronized(lock) {
            artifactFiles(includePartFiles = true).forEach { file ->
                if (file.exists() && !file.delete()) {
                    lastFailure = OmniArtifactFailure(
                        OmniArtifactFailureCode.DOWNLOAD_FAILED,
                        "Unable to remove an app-private model artifact"
                    )
                    phase = OmniInstallPhase.FAILED
                    return snapshot()
                }
            }
            verificationStore.clear()
            lastFailure = null
            lastPreflight = null
            phase = OmniInstallPhase.UNINSTALLED
            return snapshot()
        }
    }

    fun verifyInstalledPair(): OmniInstallSnapshot {
        synchronized(lock) {
            for (artifact in manifest.artifacts) {
                val file = File(installDirectory, artifact.fileName)
                if (!file.isFile) {
                    lastFailure = OmniArtifactFailure(
                        OmniArtifactFailureCode.ARTIFACT_MISSING,
                        "A required artifact is missing",
                        artifact.role
                    )
                    phase = OmniInstallPhase.FAILED
                    return snapshot()
                }
                if (file.length() != artifact.byteSize || sha256(file) != artifact.sha256) {
                    verificationStore.remove(artifact.role)
                    lastFailure = OmniArtifactFailure(
                        OmniArtifactFailureCode.CHECKSUM_MISMATCH,
                        "A required artifact failed verification",
                        artifact.role
                    )
                    phase = OmniInstallPhase.FAILED
                    return snapshot()
                }
                verificationStore.record(artifact, file, clock())
            }
            lastFailure = null
            phase = OmniInstallPhase.INSTALLED
            return snapshot()
        }
    }

    fun loadVerifiedPair(loader: OmniVerifiedPairLoader): OmniLoadPairResult {
        val current = snapshot()
        if (!current.pairVerified) {
            val missingRole = when {
                !current.main.installed -> OmniArtifactRole.MAIN
                !current.mmproj.installed -> OmniArtifactRole.MMPROJ
                !current.main.verified -> OmniArtifactRole.MAIN
                else -> OmniArtifactRole.MMPROJ
            }
            val code = if (
                (missingRole == OmniArtifactRole.MAIN && !current.main.installed) ||
                (missingRole == OmniArtifactRole.MMPROJ && !current.mmproj.installed)
            ) {
                OmniArtifactFailureCode.ARTIFACT_MISSING
            } else {
                OmniArtifactFailureCode.CHECKSUM_MISMATCH
            }
            return OmniLoadPairResult.Failed(
                OmniArtifactFailure(code, "The verified artifact pair is not available", missingRole)
            )
        }
        val main = manifest.artifact(OmniArtifactRole.MAIN)!!
        val mmproj = manifest.artifact(OmniArtifactRole.MMPROJ)!!
        return if (loader.load(
                File(installDirectory, main.fileName).absolutePath,
                File(installDirectory, mmproj.fileName).absolutePath
            )
        ) {
            OmniLoadPairResult.Loaded
        } else {
            OmniLoadPairResult.Failed(
                OmniArtifactFailure(
                    OmniArtifactFailureCode.MODEL_LOAD_FAILED,
                    "The runtime rejected the verified artifact pair"
                )
            )
        }
    }

    fun snapshot(): OmniInstallSnapshot {
        val main = manifest.artifact(OmniArtifactRole.MAIN)!!
        val mmproj = manifest.artifact(OmniArtifactRole.MMPROJ)!!
        return OmniInstallSnapshot(
            modelId = manifest.id,
            revision = main.revision,
            phase = phase,
            main = verificationSnapshot(main),
            mmproj = verificationSnapshot(mmproj),
            lastPreflight = lastPreflight,
            failure = lastFailure
        )
    }

    private fun runInstall(request: OmniInstallRequest, listener: OmniInstallStateListener) {
        try {
            installDirectory.mkdirs()
            phase = OmniInstallPhase.PREFLIGHT
            lastFailure = null
            lastPreflight = OmniInstallPreflight(manifest).evaluate(request, environmentProbe.probe())
            listener.onState(snapshot())
            val preflightFailure = lastPreflight?.failure
            if (preflightFailure != null) {
                fail(preflightFailure, listener)
                return
            }

            manifest.artifacts.forEach { artifact ->
                if (cancelled.get()) throw InstallCancelled()
                val finalFile = File(installDirectory, artifact.fileName)
                if (verificationStore.isVerified(artifact, finalFile)) return@forEach
                val partFile = File(installDirectory, "${artifact.fileName}.part")
                if (partFile.exists()) partFile.delete()

                phase = OmniInstallPhase.DOWNLOADING
                listener.onState(snapshot())
                try {
                    transport.download(artifact, partFile, cancelled::get) { }
                    if (cancelled.get()) throw InstallCancelled()
                    phase = OmniInstallPhase.VERIFYING
                    listener.onState(snapshot())
                    if (partFile.length() != artifact.byteSize || sha256(partFile) != artifact.sha256) {
                        throw InstallFailure(
                            OmniArtifactFailure(
                                OmniArtifactFailureCode.CHECKSUM_MISMATCH,
                                "Downloaded artifact failed verification",
                                artifact.role
                            )
                        )
                    }
                    moveVerifiedPart(partFile, finalFile)
                    verificationStore.record(artifact, finalFile, clock())
                } finally {
                    if (partFile.exists()) partFile.delete()
                }
            }

            lastFailure = null
            phase = OmniInstallPhase.INSTALLED
            listener.onState(snapshot())
        } catch (_: InstallCancelled) {
            cancelledState(listener)
        } catch (error: InstallFailure) {
            fail(error.failure, listener)
        } catch (error: IOException) {
            if (cancelled.get()) {
                cancelledState(listener)
            } else {
                fail(
                    OmniArtifactFailure(
                        OmniArtifactFailureCode.DOWNLOAD_FAILED,
                        "Artifact download did not complete"
                    ),
                    listener
                )
            }
        } catch (_: RuntimeException) {
            fail(
                OmniArtifactFailure(
                    OmniArtifactFailureCode.DOWNLOAD_FAILED,
                    "Artifact installation did not complete"
                ),
                listener
            )
        } finally {
            synchronized(lock) {
                if (Thread.currentThread() == activeWorker) activeWorker = null
            }
        }
    }

    private fun verificationSnapshot(artifact: OmniArtifactSpec): OmniArtifactVerification {
        val file = File(installDirectory, artifact.fileName)
        return OmniArtifactVerification(
            expectedSha256 = artifact.sha256,
            expectedBytes = artifact.byteSize,
            installed = file.isFile,
            verified = verificationStore.isVerified(artifact, file),
            verifiedAtEpochMs = verificationStore.verifiedAt(artifact, file)
        )
    }

    private fun artifactFiles(includePartFiles: Boolean): List<File> {
        return manifest.artifacts.flatMap { artifact ->
            buildList {
                add(File(installDirectory, artifact.fileName))
                if (includePartFiles) add(File(installDirectory, "${artifact.fileName}.part"))
            }
        }
    }

    private fun moveVerifiedPart(partFile: File, finalFile: File) {
        try {
            Files.move(
                partFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun cancelledState(listener: OmniInstallStateListener) {
        artifactFiles(includePartFiles = true).filter { it.name.endsWith(".part") }.forEach { it.delete() }
        lastFailure = OmniArtifactFailure(OmniArtifactFailureCode.CANCELLED, "Artifact install was cancelled")
        phase = OmniInstallPhase.CANCELLED
        listener.onState(snapshot())
    }

    private fun fail(failure: OmniArtifactFailure, listener: OmniInstallStateListener) {
        lastFailure = failure
        phase = OmniInstallPhase.FAILED
        listener.onState(snapshot())
    }

    private class InstallCancelled : Exception()
    private class InstallFailure(val failure: OmniArtifactFailure) : Exception()
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private class OmniVerificationStore(private val file: File) {
    private val properties = Properties()

    init {
        if (file.isFile) FileInputStream(file).use(properties::load)
    }

    @Synchronized
    fun isVerified(artifact: OmniArtifactSpec, artifactFile: File): Boolean {
        val prefix = artifact.role.name.lowercase()
        return artifactFile.isFile &&
            artifactFile.length() == artifact.byteSize &&
            properties.getProperty("$prefix.revision") == artifact.revision &&
            properties.getProperty("$prefix.sha256") == artifact.sha256 &&
            properties.getProperty("$prefix.bytes") == artifact.byteSize.toString() &&
            properties.getProperty("$prefix.last_modified") == artifactFile.lastModified().toString()
    }

    @Synchronized
    fun verifiedAt(artifact: OmniArtifactSpec, artifactFile: File): Long? {
        if (!isVerified(artifact, artifactFile)) return null
        return properties.getProperty("${artifact.role.name.lowercase()}.verified_at")?.toLongOrNull()
    }

    @Synchronized
    fun record(artifact: OmniArtifactSpec, artifactFile: File, verifiedAt: Long) {
        val prefix = artifact.role.name.lowercase()
        properties.setProperty("$prefix.revision", artifact.revision)
        properties.setProperty("$prefix.sha256", artifact.sha256)
        properties.setProperty("$prefix.bytes", artifact.byteSize.toString())
        properties.setProperty("$prefix.last_modified", artifactFile.lastModified().toString())
        properties.setProperty("$prefix.verified_at", verifiedAt.toString())
        persist()
    }

    @Synchronized
    fun remove(role: OmniArtifactRole) {
        val prefix = "${role.name.lowercase()}."
        properties.keys.map { it.toString() }.filter { it.startsWith(prefix) }.forEach(properties::remove)
        persist()
    }

    @Synchronized
    fun clear() {
        properties.clear()
        if (file.exists()) file.delete()
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        FileOutputStream(file, false).use { properties.store(it, "MobileCore artifact verification metadata") }
    }
}
