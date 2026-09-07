package ai.mobilecore.playground

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
import kotlin.math.max

enum class PlaygroundInstallPhase {
    NOT_DOWNLOADED,
    PREFLIGHT,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    LOADING,
    LOADED,
    VERIFICATION_FAILED,
    SOURCE_MISMATCH,
    FAILED,
    CANCELLED,
    UNINSTALLED,
}

enum class PlaygroundInstallFailureCode(val wireValue: String) {
    MANIFEST_INVALID("manifest_invalid"),
    SOURCE_MISMATCH("source_mismatch"),
    INSUFFICIENT_STORAGE("insufficient_storage"),
    DOWNLOAD_FAILED("download_failed"),
    SIZE_MISMATCH("size_mismatch"),
    CHECKSUM_MISMATCH("checksum_mismatch"),
    VERIFICATION_IO_FAILED("verification_io_failed"),
    ATOMIC_INSTALL_FAILED("atomic_install_failed"),
    ARTIFACT_MISSING("artifact_missing"),
    MODEL_LOAD_FAILED("model_load_failed"),
    ACTIVE_MODEL("active_model"),
    UNINSTALL_FAILED("uninstall_failed"),
    CANCELLED("cancelled"),
    INSTALL_IN_PROGRESS("install_in_progress"),
}

data class PlaygroundInstallFailure(
    val code: PlaygroundInstallFailureCode,
    val message: String,
    val artifactName: String? = null,
)

data class PlaygroundInstallArtifact(
    val name: String,
    val role: String,
    val byteSize: Long,
    val sha256: String,
    val sourceUrl: String,
)

internal data class PlaygroundTrustedArtifactIdentity(
    val modelId: String,
    val revision: String,
    val artifactName: String,
    val sha256: String,
    val byteSize: Long,
    val canonicalPath: String,
    val lastModified: Long,
)

/**
 * Process-local proof that this exact file completed a full digest pass in the current process.
 * It deliberately contains no URL, token, media, prompt, or user content.
 */
internal object PlaygroundArtifactTrustRegistry {
    private val identities = java.util.concurrent.ConcurrentHashMap<String, PlaygroundTrustedArtifactIdentity>()

    fun record(
        spec: PlaygroundInstallSpec,
        artifact: PlaygroundInstallArtifact,
        file: File,
    ) {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (!canonical.isFile || canonical.length() != artifact.byteSize) return
        identities[canonical.absolutePath] = PlaygroundTrustedArtifactIdentity(
            modelId = spec.id,
            revision = spec.revision,
            artifactName = artifact.name,
            sha256 = artifact.sha256,
            byteSize = artifact.byteSize,
            canonicalPath = canonical.absolutePath,
            lastModified = canonical.lastModified(),
        )
    }

    fun resolve(file: File): PlaygroundTrustedArtifactIdentity? {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val identity = identities[canonical.absolutePath] ?: return null
        val unchanged = canonical.isFile &&
            canonical.length() == identity.byteSize &&
            canonical.lastModified() == identity.lastModified
        if (!unchanged) {
            identities.remove(canonical.absolutePath, identity)
            return null
        }
        return identity
    }

    fun matches(
        spec: PlaygroundInstallSpec,
        artifact: PlaygroundInstallArtifact,
        file: File,
    ): Boolean {
        val identity = resolve(file) ?: return false
        return identity.modelId == spec.id &&
            identity.revision == spec.revision &&
            identity.artifactName == artifact.name &&
            identity.sha256 == artifact.sha256 &&
            identity.byteSize == artifact.byteSize
    }

    fun revoke(file: File) {
        runCatching { file.canonicalFile.absolutePath }.getOrNull()?.let(identities::remove)
    }

    internal fun clearForTests() {
        identities.clear()
    }
}

data class PlaygroundInstallSpec(
    val id: String,
    val revision: String,
    val repositoryUrl: String,
    val artifacts: List<PlaygroundInstallArtifact>,
) {
    val totalBytes: Long
        get() = artifacts.sumOf { it.byteSize }

    val primaryArtifact: PlaygroundInstallArtifact
        get() = artifacts.single { it.role == "mobile_runtime" }

    companion object {
        private val safeId = Regex("^[a-z0-9][a-z0-9._-]+$")
        private val safeName = Regex("^[A-Za-z0-9][A-Za-z0-9._-]+$")
        private val revisionPattern = Regex("^[0-9a-f]{40}$")
        private val shaPattern = Regex("^[0-9a-f]{64}$")

        /**
         * Converts a signed-off catalog row into the smaller immutable install contract.
         * The installer repeats these checks rather than trusting UI/parser call order.
         */
        fun fromCatalogEntry(entry: PlaygroundCatalogEntry): PlaygroundInstallSpec {
            require(safeId.matches(entry.id)) { "unsafe model id" }
            require(entry.distribution.downloadable) { "catalog entry is not downloadable" }
            require(entry.distribution.installTransport == "https_direct") {
                "catalog entry is not a direct HTTPS distribution"
            }
            require(entry.source.licenseReview == "cleared") { "model license is not cleared" }
            require(entry.source.licenseReviewScope != "source_link_only") {
                "source-link-only license review does not permit installation"
            }

            val repositoryUrl = entry.distribution.repositoryUrl ?: entry.source.repository
            val revision = entry.distribution.revision ?: entry.source.revision
            require(revisionPattern.matches(revision)) { "distribution revision is not immutable" }
            val repository = strictHuggingFaceUri(repositoryUrl)
            val repositoryPath = repository.path.trimEnd('/')
            require(repositoryPath.split('/').filter(String::isNotBlank).size == 2) {
                "Hugging Face repository path must identify owner and repository"
            }

            val installArtifacts = entry.artifacts
                .filter { it.role == "mobile_runtime" || it.role == "projector" }
                .map { artifact ->
                    require(safeName.matches(artifact.name) && artifact.name != "." && artifact.name != "..") {
                        "unsafe artifact filename"
                    }
                    require(artifact.sizeBytes > 0L) { "artifact byte size must be positive" }
                    require(shaPattern.matches(artifact.sha256)) { "artifact SHA-256 is invalid" }
                    require(artifact.format.startsWith("GGUF", ignoreCase = true)) {
                        "only GGUF runtime artifacts are installable"
                    }
                    val sourceUrl = requireNotNull(artifact.sourceUrl) { "artifact source is missing" }
                    val source = strictHuggingFaceUri(sourceUrl, allowDownloadQuery = true)
                    require(source.path == "$repositoryPath/resolve/$revision/${artifact.name}") {
                        "artifact source does not match its pinned repository, revision, and filename"
                    }
                    PlaygroundInstallArtifact(
                        name = artifact.name,
                        role = artifact.role,
                        byteSize = artifact.sizeBytes,
                        sha256 = artifact.sha256,
                        sourceUrl = sourceUrl,
                    )
                }
            require(installArtifacts.isNotEmpty()) { "catalog entry has no runtime artifacts" }
            require(installArtifacts.count { it.role == "mobile_runtime" } == 1) {
                "catalog entry must contain exactly one primary runtime artifact"
            }
            require(installArtifacts.map { it.name }.toSet().size == installArtifacts.size) {
                "catalog entry contains duplicate artifact filenames"
            }
            return PlaygroundInstallSpec(
                id = entry.id,
                revision = revision,
                repositoryUrl = repositoryUrl,
                artifacts = installArtifacts,
            )
        }

        internal fun strictHuggingFaceUri(value: String, allowDownloadQuery: Boolean = false): URI {
            val uri = URI(value)
            require(
                uri.scheme == "https" &&
                    uri.host == "huggingface.co" &&
                    uri.userInfo == null &&
                    (uri.port == -1 || uri.port == 443) &&
                    uri.fragment == null &&
                    (uri.rawQuery == null || allowDownloadQuery && uri.rawQuery == "download=true"),
            ) { "source must be a credential-free pinned Hugging Face HTTPS URL" }
            return uri
        }
    }
}

data class PlaygroundInstallEnvironment(val availableStorageBytes: Long)

fun interface PlaygroundInstallEnvironmentProbe {
    fun probe(): PlaygroundInstallEnvironment
}

data class PlaygroundInstallSnapshot(
    val modelId: String,
    val phase: PlaygroundInstallPhase,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val expectedArtifactCount: Int,
    val verifiedArtifactNames: Set<String>,
    val installedArtifactNames: Set<String>,
    val failure: PlaygroundInstallFailure? = null,
) {
    val progressPercent: Int
        get() = if (totalBytes <= 0L) 0 else {
            ((downloadedBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
        }

    val verified: Boolean
        get() = expectedArtifactCount > 0 && verifiedArtifactNames.size == expectedArtifactCount
}

fun interface PlaygroundInstallStateListener {
    fun onState(snapshot: PlaygroundInstallSnapshot)
}

fun interface PlaygroundArtifactTransport {
    /** Appends bytes beginning at [resumeOffset] and reports the absolute artifact byte count. */
    @Throws(IOException::class)
    fun download(
        artifact: PlaygroundInstallArtifact,
        destinationPart: File,
        resumeOffset: Long,
        cancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
    )
}

/**
 * Direct HTTPS transport for revision-pinned Hugging Face files.
 *
 * Redirects are handled manually. HTTPS downgrade, credentials, non-default ports, and redirects
 * outside the small Hugging Face artifact-host allowlist are rejected before response bytes are
 * read. Exact byte-count and SHA-256 verification remain mandatory after the transport completes.
 */
class HttpPlaygroundArtifactTransport : PlaygroundArtifactTransport {
    override fun download(
        artifact: PlaygroundInstallArtifact,
        destinationPart: File,
        resumeOffset: Long,
        cancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
    ) {
        require(resumeOffset >= 0L && destinationPart.length() == resumeOffset) {
            "resume offset does not match the partial file"
        }
        val pinnedSource = PlaygroundInstallSpec.strictHuggingFaceUri(
            artifact.sourceUrl,
            allowDownloadQuery = true,
        )
        var requestUri = pinnedSource
        var redirects = 0
        while (true) {
            if (cancelled()) throw PlaygroundCancelledIOException()
            val connection = requestUri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "MobileCore-PlaygroundInstaller/1")
            if (resumeOffset > 0L) connection.setRequestProperty("Range", "bytes=$resumeOffset-")

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
                    throw PlaygroundSourceMismatchIOException()
                }
                val redirected = requestUri.resolve(location)
                if (!PlaygroundRedirectPolicy.isAllowed(pinnedSource, redirected)) {
                    throw PlaygroundSourceMismatchIOException()
                }
                requestUri = redirected
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
                    onBytes = onBytes,
                )
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun streamResponse(
        connection: HttpURLConnection,
        artifact: PlaygroundInstallArtifact,
        destinationPart: File,
        requestedOffset: Long,
        responseCode: Int,
        cancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
    ) {
        if (responseCode == 416) throw PlaygroundSizeMismatchIOException()
        if (responseCode !in 200..299) throw IOException("artifact source rejected the request")
        val append = requestedOffset > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
        val effectiveOffset = if (append) requestedOffset else 0L
        if (append) {
            val range = parseContentRange(connection.getHeaderField("Content-Range"))
            if (range == null || range.first != requestedOffset || range.third != artifact.byteSize) {
                throw PlaygroundSizeMismatchIOException()
            }
        }
        val expectedResponseBytes = artifact.byteSize - effectiveOffset
        val responseBytes = connection.contentLengthLong
        if (responseBytes >= 0L && responseBytes != expectedResponseBytes) {
            throw PlaygroundSizeMismatchIOException()
        }

        destinationPart.parentFile?.mkdirs()
        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(destinationPart, append).use { fileOutput ->
                BufferedOutputStream(fileOutput, DOWNLOAD_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var written = effectiveOffset
                    while (true) {
                        if (cancelled()) throw PlaygroundCancelledIOException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > artifact.byteSize) throw PlaygroundSizeMismatchIOException()
                        output.write(buffer, 0, count)
                        onBytes(written)
                    }
                    output.flush()
                    fileOutput.fd.sync()
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

    private data class ContentRange(val first: Long, val second: Long, val third: Long)

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 4
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}

/** Strict allowlist for Hugging Face's documented model origin and artifact delivery hosts. */
internal object PlaygroundRedirectPolicy {
    private val hfCdnHost = Regex("^cdn-lfs(?:-[a-z0-9-]+)?\\.hf\\.co$")

    fun isAllowed(pinnedSource: URI, target: URI): Boolean {
        val host = target.host?.lowercase() ?: return false
        val trustedArtifactHost = host == pinnedSource.host ||
            host.endsWith(".huggingface.co") ||
            host == "cas-bridge.xethub.hf.co" ||
            hfCdnHost.matches(host)
        return target.scheme == "https" &&
            trustedArtifactHost &&
            target.userInfo == null &&
            (target.port == -1 || target.port == 443) &&
            target.fragment == null
    }
}

class PlaygroundInstallHandle internal constructor(
    val started: Boolean,
    val startFailure: PlaygroundInstallFailure?,
    private val worker: Thread?,
    private val cancelAction: () -> Unit,
) {
    fun cancel() = cancelAction()

    fun await(timeoutMs: Long): Boolean {
        val running = worker ?: return true
        running.join(timeoutMs)
        return !running.isAlive
    }
}

/**
 * Verifies and atomically installs a catalog model into one app-private directory.
 *
 * A transient network failure keeps a bounded `.part` file for the next Range request. Explicit
 * cancellation, size mismatch, and checksum mismatch delete the temporary bytes. Existing files
 * without matching stat-bound verification metadata are never overwritten and surface as
 * [PlaygroundInstallPhase.SOURCE_MISMATCH].
 */
class PlaygroundArtifactInstaller(
    private val installDirectory: File,
    private val environmentProbe: PlaygroundInstallEnvironmentProbe,
    val spec: PlaygroundInstallSpec,
    private val transport: PlaygroundArtifactTransport = HttpPlaygroundArtifactTransport(),
    private val storageSafetyBytes: Long = DEFAULT_STORAGE_SAFETY_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val artifactDigest: (File, () -> Boolean) -> String = ::sha256,
) {
    private val cancelled = AtomicBoolean(false)
    private val startupVerificationClaimed = AtomicBoolean(false)
    private val lock = Any()
    private val verificationStore = PlaygroundVerificationStore(
        File(installDirectory, ".playground-${spec.id}.verified.properties")
    )
    private val sessionVerifiedArtifactNames = Collections.synchronizedSet(
        spec.artifacts
            .filter { PlaygroundArtifactTrustRegistry.matches(spec, it, finalFile(it)) }
            .mapTo(linkedSetOf()) { it.name }
    )

    @Volatile
    private var phase = initialPhase()

    @Volatile
    private var failure: PlaygroundInstallFailure? = initialFailure()

    @Volatile
    private var activeWorker: Thread? = null

    @Volatile
    private var activeArtifactBytes: Long = 0L

    @Volatile
    private var lastProgressNotificationMs: Long = 0L

    fun install(
        listener: PlaygroundInstallStateListener = PlaygroundInstallStateListener { },
    ): PlaygroundInstallHandle = startWorker(
        threadName = "mobilecore-playground-install-${spec.id}",
        listener = listener,
        operation = ::runInstall,
    )

    private fun startWorker(
        threadName: String,
        listener: PlaygroundInstallStateListener,
        operation: (PlaygroundInstallStateListener) -> Unit,
        initialPhase: PlaygroundInstallPhase? = null,
    ): PlaygroundInstallHandle {
        synchronized(lock) {
            if (activeWorker?.isAlive == true) {
                val currentFailure = PlaygroundInstallFailure(
                    PlaygroundInstallFailureCode.INSTALL_IN_PROGRESS,
                    "A Playground install is already running",
                )
                return PlaygroundInstallHandle(false, currentFailure, null, ::cancel)
            }
            cancelled.set(false)
            initialPhase?.let {
                phase = it
                failure = null
            }
            val worker = Thread(
                {
                    try {
                        operation(listener)
                    } finally {
                        synchronized(lock) {
                            if (Thread.currentThread() == activeWorker) activeWorker = null
                        }
                        startupVerificationClaimed.set(false)
                    }
                },
                threadName,
            ).apply { isDaemon = true }
            activeWorker = worker
            worker.start()
            return PlaygroundInstallHandle(true, null, worker, ::cancel)
        }
    }

    fun cancel() {
        cancelled.set(true)
        activeWorker?.interrupt()
    }

    fun snapshot(): PlaygroundInstallSnapshot = synchronized(lock) {
        val verifiedNames = spec.artifacts
            .filter {
                val file = finalFile(it)
                val verifiedInSession = it.name in sessionVerifiedArtifactNames
                val persisted = runCatching { verificationStore.isVerified(it, file) }
                    .getOrDefault(false)
                val trusted = PlaygroundArtifactTrustRegistry.matches(spec, it, file)
                if (verifiedInSession && (!persisted || !trusted)) {
                    sessionVerifiedArtifactNames.remove(it.name)
                    PlaygroundArtifactTrustRegistry.revoke(file)
                }
                verifiedInSession && persisted && trusted
            }
            .mapTo(linkedSetOf()) { it.name }
        val installedNames = spec.artifacts
            .filter { finalFile(it).isFile }
            .mapTo(linkedSetOf()) { it.name }
        val completedBytes = spec.artifacts.sumOf { artifact ->
            when {
                artifact.name in verifiedNames -> artifact.byteSize
                phase == PlaygroundInstallPhase.DOWNLOADING -> partialFile(artifact).length()
                    .coerceIn(0L, artifact.byteSize)
                else -> 0L
            }
        }
        reconcileTrustedPhase(verifiedNames)
        PlaygroundInstallSnapshot(
            modelId = spec.id,
            phase = phase,
            downloadedBytes = max(completedBytes, activeArtifactBytes).coerceAtMost(spec.totalBytes),
            totalBytes = spec.totalBytes,
            expectedArtifactCount = spec.artifacts.size,
            verifiedArtifactNames = verifiedNames,
            installedArtifactNames = installedNames,
            failure = failure,
        )
    }

    /** Demotes trusted-looking phases as soon as their stat-bound proof is no longer valid. */
    private fun reconcileTrustedPhase(verifiedNames: Set<String>) {
        if (phase !in TRUSTED_PHASES || verifiedNames.size == spec.artifacts.size) return

        val missing = spec.artifacts.firstOrNull { !finalFile(it).isFile }
        val wrongSize = spec.artifacts.firstOrNull {
            val file = finalFile(it)
            file.isFile && file.length() != it.byteSize
        }
        val persistedProofStillValid = spec.artifacts.all {
            runCatching { verificationStore.isVerified(it, finalFile(it)) }.getOrDefault(false)
        }
        when {
            missing != null -> {
                phase = PlaygroundInstallPhase.FAILED
                failure = PlaygroundInstallFailure(
                    PlaygroundInstallFailureCode.ARTIFACT_MISSING,
                    "A required installed artifact is missing",
                    missing.name,
                )
            }
            wrongSize != null -> {
                phase = PlaygroundInstallPhase.VERIFICATION_FAILED
                failure = PlaygroundInstallFailure(
                    PlaygroundInstallFailureCode.SIZE_MISMATCH,
                    "An installed artifact has the wrong byte count",
                    wrongSize.name,
                )
            }
            persistedProofStillValid -> {
                // A fresh process has only stat-bound persisted metadata. A worker must perform a
                // full digest before this entry may become INSTALLED/LOADED again.
                phase = PlaygroundInstallPhase.VERIFYING
                failure = null
            }
            else -> {
                phase = PlaygroundInstallPhase.SOURCE_MISMATCH
                failure = PlaygroundInstallFailure(
                    PlaygroundInstallFailureCode.SOURCE_MISMATCH,
                    "An installed artifact no longer matches its verification proof",
                )
            }
        }
    }

    fun needsStartupVerification(): Boolean =
        phase == PlaygroundInstallPhase.VERIFYING && allPersistedArtifactsVerified()

    /** Allows only one full startup digest pass for this process-local installer instance. */
    fun beginStartupVerification(): Boolean =
        needsStartupVerification() && startupVerificationClaimed.compareAndSet(false, true)

    internal fun releaseStartupVerificationClaim() {
        startupVerificationClaimed.set(false)
    }

    /**
     * Starts a full byte-for-byte verification on a dedicated worker. No public API performs a
     * multi-gigabyte digest on its caller thread, so UI and service entry points cannot ANR.
     */
    fun verifyInstalled(
        listener: PlaygroundInstallStateListener = PlaygroundInstallStateListener { },
    ): PlaygroundInstallHandle = startWorker(
        threadName = "mobilecore-playground-verify-${spec.id}",
        listener = listener,
        operation = ::runVerification,
        initialPhase = PlaygroundInstallPhase.VERIFYING,
    )

    private fun runVerification(listener: PlaygroundInstallStateListener) {
        try {
            prepareForFullVerification()
            notify(listener, force = true)
            verifyInstalledOnWorker()
            failure = null
            phase = PlaygroundInstallPhase.INSTALLED
            notify(listener, force = true)
        } catch (_: InstallCancelled) {
            cancelledState(listener)
        } catch (error: InstallFailure) {
            fail(error.failure, listener, phaseForFailure(error.failure.code))
        } catch (_: IOException) {
            if (isCancelled()) {
                cancelledState(listener)
            } else {
                fail(
                    PlaygroundInstallFailure(
                        PlaygroundInstallFailureCode.VERIFICATION_IO_FAILED,
                        "An installed artifact could not be read or its verification proof could not be committed",
                    ),
                    listener,
                    PlaygroundInstallPhase.VERIFICATION_FAILED,
                )
            }
        } catch (_: RuntimeException) {
            fail(
                PlaygroundInstallFailure(
                    PlaygroundInstallFailureCode.VERIFICATION_IO_FAILED,
                    "The installed artifact verification did not complete",
                ),
                listener,
                PlaygroundInstallPhase.VERIFICATION_FAILED,
            )
        }
    }

    private fun prepareForFullVerification() {
        phase = PlaygroundInstallPhase.VERIFYING
        failure = null
        sessionVerifiedArtifactNames.clear()
        spec.artifacts.forEach { PlaygroundArtifactTrustRegistry.revoke(finalFile(it)) }
    }

    @Throws(IOException::class, InstallCancelled::class, InstallFailure::class)
    private fun verifyInstalledOnWorker() {
        for (artifact in spec.artifacts) {
            throwIfCancelled()
            val file = finalFile(artifact)
            if (!file.isFile) {
                PlaygroundArtifactTrustRegistry.revoke(file)
                throw InstallFailure(
                    PlaygroundInstallFailure(
                        PlaygroundInstallFailureCode.ARTIFACT_MISSING,
                        "A required installed artifact is missing",
                        artifact.name,
                    )
                )
            }
            verifyArtifactOnWorker(artifact, file)
        }
    }

    @Throws(IOException::class, InstallCancelled::class, InstallFailure::class)
    private fun verifyArtifactOnWorker(artifact: PlaygroundInstallArtifact, file: File) {
        val beforeLength = file.length()
        val beforeModified = file.lastModified()
        if (beforeLength != artifact.byteSize) {
            verificationStore.remove(artifact)
            PlaygroundArtifactTrustRegistry.revoke(file)
            throw InstallFailure(
                PlaygroundInstallFailure(
                    PlaygroundInstallFailureCode.SIZE_MISMATCH,
                    "An installed artifact has the wrong byte count",
                    artifact.name,
                )
            )
        }
        if (artifactDigest(file, ::isCancelled) != artifact.sha256) {
            verificationStore.remove(artifact)
            PlaygroundArtifactTrustRegistry.revoke(file)
            throw InstallFailure(
                PlaygroundInstallFailure(
                    PlaygroundInstallFailureCode.CHECKSUM_MISMATCH,
                    "An installed artifact failed SHA-256 verification",
                    artifact.name,
                )
            )
        }
        throwIfCancelled()
        if (!file.isFile || file.length() != beforeLength || file.lastModified() != beforeModified) {
            verificationStore.remove(artifact)
            PlaygroundArtifactTrustRegistry.revoke(file)
            throw InstallFailure(
                PlaygroundInstallFailure(
                    PlaygroundInstallFailureCode.SOURCE_MISMATCH,
                    "An installed artifact changed during verification",
                    artifact.name,
                )
            )
        }
        verificationStore.record(artifact, file, clock())
        sessionVerifiedArtifactNames.add(artifact.name)
        PlaygroundArtifactTrustRegistry.record(spec, artifact, file)
    }

    fun markLoading(): PlaygroundInstallSnapshot = synchronized(lock) {
        if (!allArtifactsVerified()) {
            return@synchronized failSnapshot(
                PlaygroundInstallFailureCode.ARTIFACT_MISSING,
                "The verified model is not installed",
            )
        }
        failure = null
        phase = PlaygroundInstallPhase.LOADING
        snapshot()
    }

    fun markLoaded(): PlaygroundInstallSnapshot = synchronized(lock) {
        if (!allArtifactsVerified()) {
            return@synchronized failSnapshot(
                PlaygroundInstallFailureCode.ARTIFACT_MISSING,
                "The loaded model no longer matches the installed artifacts",
            )
        }
        failure = null
        phase = PlaygroundInstallPhase.LOADED
        snapshot()
    }

    fun markLoadFailed(): PlaygroundInstallSnapshot = synchronized(lock) {
        failSnapshot(
            PlaygroundInstallFailureCode.MODEL_LOAD_FAILED,
            "The local runtime could not load the verified model",
        )
    }

    fun primaryModelFile(): File? {
        val artifact = spec.primaryArtifact
        val file = finalFile(artifact)
        return file.takeIf {
            allArtifactsVerified() &&
                PlaygroundArtifactTrustRegistry.matches(spec, artifact, it)
        }
    }

    /** Expected app-private primary path, exposed only for active/pending unload protection. */
    fun managedPrimaryModelFile(): File = finalFile(spec.primaryArtifact)

    fun managesModelPath(path: String): Boolean {
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val managed = runCatching { managedPrimaryModelFile().canonicalFile }.getOrNull() ?: return false
        return candidate == managed
    }

    /** Exact canonical membership for either the main GGUF or a managed projector artifact. */
    fun managesArtifactPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        return spec.artifacts.any { artifact ->
            runCatching { finalFile(artifact).canonicalFile == candidate }.getOrDefault(false)
        }
    }

    fun hasManagedProjector(): Boolean = spec.artifacts.any { it.role == "projector" }

    fun markUnloaded(): PlaygroundInstallSnapshot = synchronized(lock) {
        if (!allArtifactsVerified()) return@synchronized snapshot()
        failure = null
        phase = PlaygroundInstallPhase.INSTALLED
        snapshot()
    }

    fun uninstall(activeModel: Boolean = false): PlaygroundInstallSnapshot {
        if (activeModel) {
            synchronized(lock) {
                return failSnapshot(
                    PlaygroundInstallFailureCode.ACTIVE_MODEL,
                    "Unload the active model before removing its files",
                )
            }
        }
        cancel()
        val worker = activeWorker
        worker?.join(CANCEL_JOIN_TIMEOUT_MS)
        synchronized(lock) {
            if (worker?.isAlive == true) {
                return failSnapshot(
                    PlaygroundInstallFailureCode.INSTALL_IN_PROGRESS,
                    "The active transfer has not stopped; no installed file was removed",
                )
            }
            managedFiles().forEach { file ->
                PlaygroundArtifactTrustRegistry.revoke(file)
                if (file.exists() && !file.delete()) {
                    return failSnapshot(
                        PlaygroundInstallFailureCode.UNINSTALL_FAILED,
                        "An app-private model artifact could not be removed",
                    )
                }
            }
            verificationStore.clear()
            sessionVerifiedArtifactNames.clear()
            failure = null
            activeArtifactBytes = 0L
            phase = PlaygroundInstallPhase.UNINSTALLED
            return snapshot()
        }
    }

    private fun runInstall(listener: PlaygroundInstallStateListener) {
        try {
            installDirectory.mkdirs()
            failure = null
            activeArtifactBytes = verifiedByteCount()

            if (allPersistedArtifactsVerified() && !allArtifactsVerified()) {
                runVerification(listener)
                return
            }

            existingSourceMismatch()?.let { artifact ->
                fail(
                    PlaygroundInstallFailure(
                        PlaygroundInstallFailureCode.SOURCE_MISMATCH,
                        "An app-private file has the catalog filename but not its verified identity",
                        artifact.name,
                    ),
                    listener,
                    PlaygroundInstallPhase.SOURCE_MISMATCH,
                )
                return
            }

            phase = PlaygroundInstallPhase.PREFLIGHT
            notify(listener, force = true)
            val requiredBytes = remainingDownloadBytes() + storageSafetyBytes.coerceAtLeast(0L)
            if (environmentProbe.probe().availableStorageBytes < requiredBytes) {
                fail(
                    PlaygroundInstallFailure(
                        PlaygroundInstallFailureCode.INSUFFICIENT_STORAGE,
                        "Available app-private storage is below the download and safety requirement",
                    ),
                    listener,
                )
                return
            }

            for (artifact in spec.artifacts) {
                if (cancelled.get()) throw InstallCancelled()
                val final = finalFile(artifact)
                if (verificationStore.isVerified(artifact, final)) {
                    if (!PlaygroundArtifactTrustRegistry.matches(spec, artifact, final)) {
                        phase = PlaygroundInstallPhase.VERIFYING
                        notify(listener, force = true)
                        verifyArtifactOnWorker(artifact, final)
                    } else {
                        sessionVerifiedArtifactNames.add(artifact.name)
                    }
                    continue
                }

                val part = partialFile(artifact)
                if (part.length() > artifact.byteSize) {
                    part.delete()
                    throw InstallFailure(
                        PlaygroundInstallFailure(
                            PlaygroundInstallFailureCode.SIZE_MISMATCH,
                            "The partial artifact exceeds its pinned byte count",
                            artifact.name,
                        )
                    )
                }
                phase = PlaygroundInstallPhase.DOWNLOADING
                activeArtifactBytes = verifiedByteCount() + part.length()
                notify(listener, force = true)
                if (part.length() < artifact.byteSize) {
                    val resumeOffset = part.length()
                    transport.download(
                        artifact = artifact,
                        destinationPart = part,
                        resumeOffset = resumeOffset,
                        cancelled = cancelled::get,
                    ) { artifactBytes ->
                        activeArtifactBytes = verifiedByteCount() + artifactBytes
                        notify(listener, force = false)
                    }
                }
                if (cancelled.get()) throw InstallCancelled()
                phase = PlaygroundInstallPhase.VERIFYING
                notify(listener, force = true)
                if (part.length() != artifact.byteSize) {
                    part.delete()
                    throw InstallFailure(
                        PlaygroundInstallFailure(
                            PlaygroundInstallFailureCode.SIZE_MISMATCH,
                            "The downloaded artifact does not match its pinned byte count",
                            artifact.name,
                        )
                    )
                }
                if (artifactDigest(part, ::isCancelled) != artifact.sha256) {
                    part.delete()
                    throw InstallFailure(
                        PlaygroundInstallFailure(
                            PlaygroundInstallFailureCode.CHECKSUM_MISMATCH,
                            "The downloaded artifact failed SHA-256 verification",
                            artifact.name,
                        )
                    )
                }
                if (cancelled.get()) throw InstallCancelled()
                try {
                    Files.move(
                        part.toPath(),
                        final.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    throw InstallFailure(
                        PlaygroundInstallFailure(
                            PlaygroundInstallFailureCode.ATOMIC_INSTALL_FAILED,
                            "This storage location does not support an atomic artifact install",
                            artifact.name,
                        )
                    )
                } catch (_: IOException) {
                    throw InstallFailure(
                        PlaygroundInstallFailure(
                            PlaygroundInstallFailureCode.ATOMIC_INSTALL_FAILED,
                            "The verified artifact could not be atomically installed",
                            artifact.name,
                        )
                    )
                }
                try {
                    verificationStore.record(artifact, final, clock())
                } catch (_: IOException) {
                    PlaygroundArtifactTrustRegistry.revoke(final)
                    throw InstallFailure(
                        PlaygroundInstallFailure(
                            PlaygroundInstallFailureCode.VERIFICATION_IO_FAILED,
                            "The artifact was installed but its verification proof could not be committed",
                            artifact.name,
                        )
                    )
                }
                sessionVerifiedArtifactNames.add(artifact.name)
                PlaygroundArtifactTrustRegistry.record(spec, artifact, final)
                activeArtifactBytes = verifiedByteCount()
            }
            failure = null
            phase = PlaygroundInstallPhase.INSTALLED
            notify(listener, force = true)
        } catch (_: InstallCancelled) {
            cancelledState(listener)
        } catch (error: InstallFailure) {
            fail(error.failure, listener, phaseForFailure(error.failure.code))
        } catch (_: PlaygroundSourceMismatchIOException) {
            cleanupPartialFiles()
            fail(
                PlaygroundInstallFailure(
                    PlaygroundInstallFailureCode.SOURCE_MISMATCH,
                    "The artifact response left its pinned HTTPS origin",
                ),
                listener,
                PlaygroundInstallPhase.SOURCE_MISMATCH,
            )
        } catch (_: PlaygroundSizeMismatchIOException) {
            cleanupPartialFiles()
            fail(
                PlaygroundInstallFailure(
                    PlaygroundInstallFailureCode.SIZE_MISMATCH,
                    "The artifact response byte range did not match the pinned manifest",
                ),
                listener,
                PlaygroundInstallPhase.VERIFICATION_FAILED,
            )
        } catch (_: IOException) {
            if (isCancelled()) {
                cancelledState(listener)
            } else if (phase == PlaygroundInstallPhase.VERIFYING) {
                fail(
                    PlaygroundInstallFailure(
                        PlaygroundInstallFailureCode.VERIFICATION_IO_FAILED,
                        "Artifact verification could not read or commit its proof",
                    ),
                    listener,
                    PlaygroundInstallPhase.VERIFICATION_FAILED,
                )
            } else {
                discardInvalidPartialFiles()
                fail(
                    PlaygroundInstallFailure(
                        PlaygroundInstallFailureCode.DOWNLOAD_FAILED,
                        "The transfer stopped; a bounded partial file is available for retry",
                    ),
                    listener,
                )
            }
        } catch (_: RuntimeException) {
            discardInvalidPartialFiles()
            fail(
                PlaygroundInstallFailure(
                    if (phase == PlaygroundInstallPhase.VERIFYING) {
                        PlaygroundInstallFailureCode.VERIFICATION_IO_FAILED
                    } else {
                        PlaygroundInstallFailureCode.DOWNLOAD_FAILED
                    },
                    if (phase == PlaygroundInstallPhase.VERIFYING) {
                        "Artifact verification did not complete"
                    } else {
                        "The model installation did not complete"
                    },
                ),
                listener,
                if (phase == PlaygroundInstallPhase.VERIFYING) {
                    PlaygroundInstallPhase.VERIFICATION_FAILED
                } else {
                    PlaygroundInstallPhase.FAILED
                },
            )
        }
    }

    private fun initialPhase(): PlaygroundInstallPhase = when {
        allArtifactsVerified() -> PlaygroundInstallPhase.INSTALLED
        allPersistedArtifactsVerified() -> PlaygroundInstallPhase.VERIFYING
        existingSourceMismatch() != null -> PlaygroundInstallPhase.SOURCE_MISMATCH
        else -> PlaygroundInstallPhase.NOT_DOWNLOADED
    }

    private fun initialFailure(): PlaygroundInstallFailure? {
        val mismatch = existingSourceMismatch() ?: return null
        return PlaygroundInstallFailure(
            PlaygroundInstallFailureCode.SOURCE_MISMATCH,
            "An existing file has the expected name but no matching verified identity",
            mismatch.name,
        )
    }

    private fun existingSourceMismatch(): PlaygroundInstallArtifact? {
        return spec.artifacts.firstOrNull { artifact ->
            val file = finalFile(artifact)
            file.exists() && !verificationStore.isVerified(artifact, file)
        }
    }

    private fun allArtifactsVerified(): Boolean = spec.artifacts.all { artifact ->
        artifact.name in sessionVerifiedArtifactNames &&
            runCatching { verificationStore.isVerified(artifact, finalFile(artifact)) }.getOrDefault(false) &&
            PlaygroundArtifactTrustRegistry.matches(spec, artifact, finalFile(artifact))
    }

    private fun allPersistedArtifactsVerified(): Boolean = spec.artifacts.all { artifact ->
        runCatching { verificationStore.isVerified(artifact, finalFile(artifact)) }.getOrDefault(false)
    }

    private fun verifiedByteCount(): Long = spec.artifacts.sumOf { artifact ->
        if (verificationStore.isVerified(artifact, finalFile(artifact))) artifact.byteSize else 0L
    }

    private fun remainingDownloadBytes(): Long = spec.artifacts.sumOf { artifact ->
        if (verificationStore.isVerified(artifact, finalFile(artifact))) {
            0L
        } else {
            artifact.byteSize - partialFile(artifact).length().coerceIn(0L, artifact.byteSize)
        }
    }

    private fun managedFiles(): List<File> = buildList {
        spec.artifacts.forEach { artifact ->
            add(finalFile(artifact))
            add(partialFile(artifact))
        }
    }

    private fun finalFile(artifact: PlaygroundInstallArtifact) = File(installDirectory, artifact.name)

    private fun partialFile(artifact: PlaygroundInstallArtifact) = File(installDirectory, ".${artifact.name}.part")

    private fun cleanupPartialFiles() {
        spec.artifacts.forEach { partialFile(it).delete() }
    }

    private fun discardInvalidPartialFiles() {
        spec.artifacts.forEach { artifact ->
            val part = partialFile(artifact)
            if (part.length() > artifact.byteSize) part.delete()
        }
    }

    private fun cancelledState(listener: PlaygroundInstallStateListener) {
        cleanupPartialFiles()
        activeArtifactBytes = verifiedByteCount()
        failure = PlaygroundInstallFailure(
            PlaygroundInstallFailureCode.CANCELLED,
            "The model operation was cancelled; temporary bytes were removed and installed files were left unchanged",
        )
        phase = PlaygroundInstallPhase.CANCELLED
        notify(listener, force = true)
    }

    private fun fail(
        nextFailure: PlaygroundInstallFailure,
        listener: PlaygroundInstallStateListener,
        nextPhase: PlaygroundInstallPhase = PlaygroundInstallPhase.FAILED,
    ) {
        failure = nextFailure
        phase = nextPhase
        notify(listener, force = true)
    }

    private fun failSnapshot(
        code: PlaygroundInstallFailureCode,
        message: String,
        artifact: PlaygroundInstallArtifact? = null,
    ): PlaygroundInstallSnapshot {
        failure = PlaygroundInstallFailure(code, message, artifact?.name)
        phase = when (code) {
            PlaygroundInstallFailureCode.CHECKSUM_MISMATCH,
            PlaygroundInstallFailureCode.SIZE_MISMATCH,
            -> PlaygroundInstallPhase.VERIFICATION_FAILED
            PlaygroundInstallFailureCode.SOURCE_MISMATCH -> PlaygroundInstallPhase.SOURCE_MISMATCH
            else -> PlaygroundInstallPhase.FAILED
        }
        return snapshot()
    }

    private fun phaseForFailure(code: PlaygroundInstallFailureCode): PlaygroundInstallPhase = when (code) {
        PlaygroundInstallFailureCode.CHECKSUM_MISMATCH,
        PlaygroundInstallFailureCode.SIZE_MISMATCH,
        PlaygroundInstallFailureCode.VERIFICATION_IO_FAILED,
        -> PlaygroundInstallPhase.VERIFICATION_FAILED
        PlaygroundInstallFailureCode.SOURCE_MISMATCH -> PlaygroundInstallPhase.SOURCE_MISMATCH
        PlaygroundInstallFailureCode.CANCELLED -> PlaygroundInstallPhase.CANCELLED
        else -> PlaygroundInstallPhase.FAILED
    }

    private fun isCancelled(): Boolean = cancelled.get() || Thread.currentThread().isInterrupted

    @Throws(InstallCancelled::class)
    private fun throwIfCancelled() {
        if (isCancelled()) throw InstallCancelled()
    }

    private fun notify(listener: PlaygroundInstallStateListener, force: Boolean) {
        val now = clock()
        if (force || now - lastProgressNotificationMs >= PROGRESS_NOTIFICATION_INTERVAL_MS) {
            lastProgressNotificationMs = now
            listener.onState(snapshot())
        }
    }

    private class InstallCancelled : Exception()
    private class InstallFailure(val failure: PlaygroundInstallFailure) : Exception()

    companion object {
        const val DEFAULT_STORAGE_SAFETY_BYTES = 64L * 1024L * 1024L
        private const val PROGRESS_NOTIFICATION_INTERVAL_MS = 200L
        private const val CANCEL_JOIN_TIMEOUT_MS = 35_000L
        private val TRUSTED_PHASES = setOf(
            PlaygroundInstallPhase.INSTALLED,
            PlaygroundInstallPhase.LOADING,
            PlaygroundInstallPhase.LOADED,
        )
    }
}

private class PlaygroundVerificationStore(private val file: File) {
    private val properties = Properties()

    init {
        if (file.isFile) runCatching { FileInputStream(file).use(properties::load) }
    }

    @Synchronized
    fun isVerified(artifact: PlaygroundInstallArtifact, artifactFile: File): Boolean {
        val prefix = key(artifact)
        return artifactFile.isFile &&
            artifactFile.length() == artifact.byteSize &&
            properties.getProperty("$prefix.sha256") == artifact.sha256 &&
            properties.getProperty("$prefix.bytes") == artifact.byteSize.toString() &&
            properties.getProperty("$prefix.last_modified") == artifactFile.lastModified().toString()
    }

    @Synchronized
    fun record(artifact: PlaygroundInstallArtifact, artifactFile: File, verifiedAtMs: Long) {
        val prefix = key(artifact)
        properties.setProperty("$prefix.sha256", artifact.sha256)
        properties.setProperty("$prefix.bytes", artifact.byteSize.toString())
        properties.setProperty("$prefix.last_modified", artifactFile.lastModified().toString())
        properties.setProperty("$prefix.verified_at", verifiedAtMs.toString())
        persist()
    }

    @Synchronized
    fun remove(artifact: PlaygroundInstallArtifact) {
        val prefix = "${key(artifact)}."
        properties.keys.map { it.toString() }.filter { it.startsWith(prefix) }.forEach(properties::remove)
        persist()
    }

    @Synchronized
    fun clear() {
        properties.clear()
        if (file.exists()) file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    private fun key(artifact: PlaygroundInstallArtifact): String =
        MessageDigest.getInstance("SHA-256")
            .digest(artifact.name.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }

    private fun persist() {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary, false).use { output ->
            properties.store(output, "MobileCore Playground artifact verification metadata")
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            temporary.delete()
            throw IOException("verification metadata cannot be committed atomically")
        }
    }
}

internal fun playgroundSha256(
    file: File,
    cancelled: () -> Boolean = { false },
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw PlaygroundCancelledIOException()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256(file: File, cancelled: () -> Boolean): String = playgroundSha256(file, cancelled)

private class PlaygroundCancelledIOException : IOException("cancelled")

private class PlaygroundSourceMismatchIOException : IOException("source mismatch")

private class PlaygroundSizeMismatchIOException : IOException("size mismatch")
