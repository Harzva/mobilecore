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
import java.util.Collections
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
        destinationPart.parentFile?.mkdirs()
        val resumeOffset = destinationPart.length()
        if (resumeOffset < 0L || resumeOffset > artifact.byteSize) {
            throw OmniSizeMismatchIOException()
        }
        val pinnedSource = OmniArtifactSourcePolicy.requirePinnedSource(artifact)
        var requestUri = pinnedSource
        var redirects = 0
        while (true) {
            if (cancelled()) throw OmniCancelledIOException()
            val connection = requestUri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "MobileCore-OmniArtifactInstaller/1")
            if (resumeOffset > 0L) {
                connection.setRequestProperty("Range", "bytes=$resumeOffset-")
            }

            val responseCode = try {
                connection.responseCode
            } catch (error: IOException) {
                connection.disconnect()
                throw error
            }
            if (responseCode in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank() || redirects++ >= MAX_REDIRECTS) {
                    throw OmniSourceMismatchIOException()
                }
                val target = runCatching { requestUri.resolve(location) }.getOrNull()
                    ?: throw OmniSourceMismatchIOException()
                if (!OmniArtifactSourcePolicy.isAllowedRedirect(pinnedSource, target)) {
                    throw OmniSourceMismatchIOException()
                }
                requestUri = target
                continue
            }

            try {
                streamResponse(
                    connection = connection,
                    artifact = artifact,
                    destinationPart = destinationPart,
                    requestedOffset = resumeOffset,
                    responseCode = responseCode,
                    cancelled = cancelled,
                    onBytes = onBytes
                )
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun streamResponse(
        connection: HttpURLConnection,
        artifact: OmniArtifactSpec,
        destinationPart: File,
        requestedOffset: Long,
        responseCode: Int,
        cancelled: () -> Boolean,
        onBytes: (Long) -> Unit
    ) {
        if (responseCode == 416) throw OmniSizeMismatchIOException()
        if (responseCode !in 200..299) throw IOException("Artifact source rejected the request")

        val append = requestedOffset > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
        val effectiveOffset = if (append) requestedOffset else 0L
        if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
            val range = parseContentRange(connection.getHeaderField("Content-Range"))
            if (
                range == null ||
                range.start != effectiveOffset ||
                range.end != artifact.byteSize - 1L ||
                range.total != artifact.byteSize
            ) {
                throw OmniSizeMismatchIOException()
            }
        }

        val expectedResponseBytes = artifact.byteSize - effectiveOffset
        val responseBytes = connection.contentLengthLong
        if (responseBytes >= 0L && responseBytes != expectedResponseBytes) {
            throw OmniSizeMismatchIOException()
        }

        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(destinationPart, append).use { fileOutput ->
                BufferedOutputStream(fileOutput, DOWNLOAD_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var written = effectiveOffset
                    while (true) {
                        if (cancelled()) throw OmniCancelledIOException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > artifact.byteSize) throw OmniSizeMismatchIOException()
                        output.write(buffer, 0, count)
                        onBytes(written)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                    if (written != artifact.byteSize) throw OmniSizeMismatchIOException()
                }
            }
        }
    }

    private fun parseContentRange(value: String?): ContentRange? {
        val match = value?.let(CONTENT_RANGE::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start < 0L || end < start || total <= end) return null
        return ContentRange(start, end, total)
    }

    private data class ContentRange(val start: Long, val end: Long, val total: Long)

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
        const val MAX_REDIRECTS = 4
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    }
}

/** Network source policy for the legacy Omni pair installer. */
internal object OmniArtifactSourcePolicy {
    private val safePathSegment = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    private val hfCdnHost = Regex("^cdn-lfs(?:-[a-z0-9-]+)?\\.hf\\.co$")

    fun pinnedSourceOrNull(artifact: OmniArtifactSpec): URI? {
        val uri = runCatching { URI(artifact.sourceUrl) }.getOrNull() ?: return null
        val segments = uri.path?.split('/')?.filter(String::isNotBlank) ?: return null
        val valid = uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() == "huggingface.co" &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            uri.fragment == null &&
            (uri.rawQuery == null || uri.rawQuery == "download=true") &&
            uri.rawPath == uri.path &&
            safePathSegment.matches(artifact.fileName) &&
            artifact.revision.matches(Regex("^[0-9a-f]{40}$")) &&
            segments.size == 5 &&
            safePathSegment.matches(segments[0]) &&
            safePathSegment.matches(segments[1]) &&
            segments[2] == "resolve" &&
            segments[3] == artifact.revision &&
            segments[4] == artifact.fileName
        return uri.takeIf { valid }
    }

    fun matchesManifest(manifest: OmniArtifactManifest, artifact: OmniArtifactSpec): Boolean {
        val source = pinnedSourceOrNull(artifact) ?: return false
        val repository = runCatching { URI(manifest.sourceRepository) }.getOrNull() ?: return false
        val repositorySegments = repository.path?.split('/')?.filter(String::isNotBlank) ?: return false
        val repositoryIsPinned = repository.scheme.equals("https", ignoreCase = true) &&
            repository.host?.lowercase() == "huggingface.co" &&
            repository.userInfo == null &&
            (repository.port == -1 || repository.port == 443) &&
            repository.rawQuery == null &&
            repository.fragment == null &&
            repository.rawPath == repository.path &&
            repositorySegments.size == 2 &&
            repositorySegments.all(safePathSegment::matches)
        if (!repositoryIsPinned) return false
        val expectedPath = "${repository.path.trimEnd('/')}/resolve/${artifact.revision}/${artifact.fileName}"
        return source.path == expectedPath
    }

    @Throws(OmniSourceMismatchIOException::class)
    fun requirePinnedSource(artifact: OmniArtifactSpec): URI {
        return pinnedSourceOrNull(artifact) ?: throw OmniSourceMismatchIOException()
    }

    fun isAllowedRedirect(pinnedSource: URI, target: URI): Boolean {
        val host = target.host?.lowercase() ?: return false
        val trustedHost = host == pinnedSource.host?.lowercase() ||
            host.endsWith(".huggingface.co") ||
            host == "cas-bridge.xethub.hf.co" ||
            hfCdnHost.matches(host)
        return target.scheme.equals("https", ignoreCase = true) &&
            trustedHost &&
            target.userInfo == null &&
            (target.port == -1 || target.port == 443) &&
            target.fragment == null
    }
}

private class OmniSourceMismatchIOException : IOException("artifact source mismatch")
private class OmniSizeMismatchIOException : IOException("artifact byte range mismatch")
private class OmniCancelledIOException : IOException("cancelled")

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
    private val clock: () -> Long = System::currentTimeMillis,
    private val atomicMove: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    },
    private val artifactDigest: (File, () -> Boolean) -> String = ::sha256,
) {
    private val cancelled = AtomicBoolean(false)
    private val verificationStore = OmniVerificationStore(
        File(installDirectory, ".${manifest.id}.verification.properties")
    )
    private val lock = Any()
    private val sessionVerifiedRoles = Collections.synchronizedSet(mutableSetOf<OmniArtifactRole>())

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
        activeWorker?.interrupt()
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
            sessionVerifiedRoles.clear()
            lastFailure = null
            lastPreflight = null
            phase = OmniInstallPhase.UNINSTALLED
            return snapshot()
        }
    }

    fun verifyInstalledPair(): OmniInstallSnapshot {
        val handle = startVerification(requirePersistedProof = false)
        if (handle.started) handle.await(Long.MAX_VALUE)
        return snapshot()
    }

    /**
     * Restores trust after process start only through a fresh full digest pass. Persisted metadata
     * is a bootstrap hint, never sufficient by itself to report `verified=true` or load a pair.
     */
    fun startStartupVerification(
        listener: OmniInstallStateListener = OmniInstallStateListener { },
    ): OmniInstallHandle = startVerification(requirePersistedProof = true, listener = listener)

    private fun startVerification(
        requirePersistedProof: Boolean,
        listener: OmniInstallStateListener = OmniInstallStateListener { },
    ): OmniInstallHandle {
        synchronized(lock) {
            if (activeWorker?.isAlive == true) {
                return OmniInstallHandle(
                    false,
                    OmniArtifactFailure(
                        OmniArtifactFailureCode.INSTALL_IN_PROGRESS,
                        "An artifact install or verification is already in progress",
                    ),
                    null,
                    ::cancel,
                )
            }
            if (requirePersistedProof && !manifest.artifacts.all { artifact ->
                    verificationStore.isVerified(artifact, File(installDirectory, artifact.fileName))
                }
            ) {
                return OmniInstallHandle(false, null, null, ::cancel)
            }
            cancelled.set(false)
            sessionVerifiedRoles.clear()
            lastFailure = null
            phase = OmniInstallPhase.VERIFYING
            val worker = Thread(
                {
                    try {
                        runFullVerification(listener)
                    } finally {
                        synchronized(lock) {
                            if (Thread.currentThread() == activeWorker) activeWorker = null
                        }
                    }
                },
                "mobilecore-omni-artifact-verify",
            ).apply { isDaemon = true }
            activeWorker = worker
            worker.start()
            return OmniInstallHandle(true, null, worker, ::cancel)
        }
    }

    private fun runFullVerification(listener: OmniInstallStateListener) {
        try {
            listener.onState(snapshot())
            manifest.artifacts.forEach { artifact ->
                throwIfCancelled()
                verifyArtifactOnWorker(artifact, File(installDirectory, artifact.fileName))
            }
            lastFailure = null
            phase = OmniInstallPhase.INSTALLED
            listener.onState(snapshot())
        } catch (_: InstallCancelled) {
            cancelledState(listener)
        } catch (error: InstallFailure) {
            fail(error.failure, listener)
        } catch (_: IOException) {
            if (isCancelled()) {
                cancelledState(listener)
            } else {
                fail(
                    OmniArtifactFailure(
                        OmniArtifactFailureCode.DOWNLOAD_FAILED,
                        "Installed artifact verification could not complete",
                    ),
                    listener,
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cancelledState(listener)
        } catch (_: RuntimeException) {
            if (isCancelled()) {
                cancelledState(listener)
            } else {
                fail(
                    OmniArtifactFailure(
                        OmniArtifactFailureCode.DOWNLOAD_FAILED,
                        "Installed artifact verification could not complete",
                    ),
                    listener,
                )
            }
        }
    }

    private fun verifyArtifactOnWorker(
        artifact: OmniArtifactSpec,
        file: File,
        mismatchCode: OmniArtifactFailureCode = OmniArtifactFailureCode.CHECKSUM_MISMATCH,
    ) {
        if (!file.isFile) {
            verificationStore.remove(artifact.role)
            sessionVerifiedRoles.remove(artifact.role)
            throw InstallFailure(
                OmniArtifactFailure(
                    OmniArtifactFailureCode.ARTIFACT_MISSING,
                    "A required artifact is missing",
                    artifact.role,
                ),
            )
        }
        val beforeLength = file.length()
        val beforeModified = file.lastModified()
        if (beforeLength != artifact.byteSize || artifactDigest(file, ::isCancelled) != artifact.sha256) {
            verificationStore.remove(artifact.role)
            sessionVerifiedRoles.remove(artifact.role)
            throw InstallFailure(
                OmniArtifactFailure(
                    mismatchCode,
                    if (mismatchCode == OmniArtifactFailureCode.SOURCE_MISMATCH) {
                        "An existing artifact does not match the pinned manifest identity"
                    } else {
                        "A required artifact failed verification"
                    },
                    artifact.role,
                ),
            )
        }
        throwIfCancelled()
        if (!file.isFile || file.length() != beforeLength || file.lastModified() != beforeModified) {
            verificationStore.remove(artifact.role)
            sessionVerifiedRoles.remove(artifact.role)
            throw InstallFailure(
                OmniArtifactFailure(
                    OmniArtifactFailureCode.SOURCE_MISMATCH,
                    "An installed artifact changed during verification",
                    artifact.role,
                ),
            )
        }
        verificationStore.record(artifact, file, clock())
        sessionVerifiedRoles.add(artifact.role)
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

            val mismatchedSource = manifest.artifacts.firstOrNull { artifact ->
                !OmniArtifactSourcePolicy.matchesManifest(manifest, artifact)
            }
            if (mismatchedSource != null) {
                fail(
                    OmniArtifactFailure(
                        OmniArtifactFailureCode.SOURCE_MISMATCH,
                        "An artifact source does not match the pinned repository, revision, and filename",
                        mismatchedSource.role
                    ),
                    listener
                )
                return
            }

            manifest.artifacts.forEach { artifact ->
                if (cancelled.get()) throw InstallCancelled()
                val finalFile = File(installDirectory, artifact.fileName)
                if (verificationStore.isVerified(artifact, finalFile)) {
                    if (artifact.role !in sessionVerifiedRoles) {
                        phase = OmniInstallPhase.VERIFYING
                        listener.onState(snapshot())
                        verifyArtifactOnWorker(artifact, finalFile)
                    }
                    return@forEach
                }
                if (finalFile.exists()) {
                    phase = OmniInstallPhase.VERIFYING
                    listener.onState(snapshot())
                    verifyArtifactOnWorker(
                        artifact,
                        finalFile,
                        OmniArtifactFailureCode.SOURCE_MISMATCH,
                    )
                    return@forEach
                }
                val partFile = File(installDirectory, "${artifact.fileName}.part")
                if (partFile.exists() && (!partFile.isFile || partFile.length() > artifact.byteSize)) {
                    partFile.delete()
                    throw InstallFailure(
                        OmniArtifactFailure(
                            OmniArtifactFailureCode.CHECKSUM_MISMATCH,
                            "A temporary artifact exceeds its pinned identity",
                            artifact.role
                        )
                    )
                }

                phase = OmniInstallPhase.DOWNLOADING
                listener.onState(snapshot())
                if (partFile.length() < artifact.byteSize) {
                    transport.download(artifact, partFile, cancelled::get) { }
                }
                if (cancelled.get()) throw InstallCancelled()
                phase = OmniInstallPhase.VERIFYING
                listener.onState(snapshot())
                if (
                    partFile.length() != artifact.byteSize ||
                    artifactDigest(partFile, ::isCancelled) != artifact.sha256
                ) {
                    partFile.delete()
                    throw InstallFailure(
                        OmniArtifactFailure(
                            OmniArtifactFailureCode.CHECKSUM_MISMATCH,
                            "Downloaded artifact failed verification",
                            artifact.role
                        )
                    )
                }
                moveVerifiedPart(partFile, finalFile, artifact)
                verificationStore.record(artifact, finalFile, clock())
                sessionVerifiedRoles.add(artifact.role)
            }

            lastFailure = null
            phase = OmniInstallPhase.INSTALLED
            listener.onState(snapshot())
        } catch (_: InstallCancelled) {
            cancelledState(listener)
        } catch (error: InstallFailure) {
            fail(error.failure, listener)
        } catch (_: OmniSourceMismatchIOException) {
            cleanupPartFiles()
            fail(
                OmniArtifactFailure(
                    OmniArtifactFailureCode.SOURCE_MISMATCH,
                    "The artifact response left its pinned HTTPS source"
                ),
                listener
            )
        } catch (_: OmniSizeMismatchIOException) {
            cleanupPartFiles()
            fail(
                OmniArtifactFailure(
                    OmniArtifactFailureCode.CHECKSUM_MISMATCH,
                    "The artifact response byte range did not match the pinned manifest"
                ),
                listener
            )
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
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cancelledState(listener)
        } catch (_: RuntimeException) {
            if (isCancelled()) {
                cancelledState(listener)
            } else {
                fail(
                    OmniArtifactFailure(
                        OmniArtifactFailureCode.DOWNLOAD_FAILED,
                        "Artifact installation did not complete"
                    ),
                    listener
                )
            }
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
            verified = artifact.role in sessionVerifiedRoles && verificationStore.isVerified(artifact, file),
            verifiedAtEpochMs = verificationStore.verifiedAt(artifact, file)
                .takeIf { artifact.role in sessionVerifiedRoles },
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

    private fun moveVerifiedPart(
        partFile: File,
        finalFile: File,
        artifact: OmniArtifactSpec
    ) {
        if (finalFile.exists()) {
            throw InstallFailure(
                OmniArtifactFailure(
                    OmniArtifactFailureCode.SOURCE_MISMATCH,
                    "An existing artifact appeared before atomic installation",
                    artifact.role
                )
            )
        }
        try {
            atomicMove(partFile, finalFile)
        } catch (_: AtomicMoveNotSupportedException) {
            throw InstallFailure(
                OmniArtifactFailure(
                    OmniArtifactFailureCode.ATOMIC_INSTALL_FAILED,
                    "This storage location does not support atomic artifact installation",
                    artifact.role
                )
            )
        } catch (_: IOException) {
            throw InstallFailure(
                OmniArtifactFailure(
                    OmniArtifactFailureCode.ATOMIC_INSTALL_FAILED,
                    "The verified artifact could not be atomically installed",
                    artifact.role
                )
            )
        }
        if (!finalFile.isFile || partFile.exists()) {
            throw InstallFailure(
                OmniArtifactFailure(
                    OmniArtifactFailureCode.ATOMIC_INSTALL_FAILED,
                    "Atomic artifact installation did not produce the expected final file",
                    artifact.role
                )
            )
        }
    }

    private fun cleanupPartFiles() {
        artifactFiles(includePartFiles = true)
            .filter { it.name.endsWith(".part") }
            .forEach { it.delete() }
    }

    private fun isCancelled(): Boolean = cancelled.get() || Thread.currentThread().isInterrupted

    private fun throwIfCancelled() {
        if (isCancelled()) throw InstallCancelled()
    }

    private fun cancelledState(listener: OmniInstallStateListener) {
        cleanupPartFiles()
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

internal fun sha256(file: File, cancelled: () -> Boolean = { false }): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw OmniCancelledIOException()
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
