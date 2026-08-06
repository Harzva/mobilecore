package ai.mobilecore.omni.artifact

enum class OmniArtifactFailureCode(val wireValue: String) {
    UNSUPPORTED_MODALITY("unsupported_modality"),
    ARTIFACT_MISSING("artifact_missing"),
    CHECKSUM_MISMATCH("checksum_mismatch"),
    INSUFFICIENT_MEMORY("insufficient_memory"),
    INSUFFICIENT_STORAGE("insufficient_storage"),
    MODEL_LOAD_FAILED("model_load_failed"),
    PROJECTOR_INCOMPATIBLE("projector_incompatible"),
    PROJECTOR_LOAD_FAILED("projector_load_failed"),
    MEDIA_TOO_LARGE("media_too_large"),
    CANCELLED("cancelled"),
    EXPLICIT_CONSENT_REQUIRED("explicit_consent_required"),
    LICENSE_ACCEPTANCE_REQUIRED("license_acceptance_required"),
    WIFI_REQUIRED("wifi_required"),
    MANIFEST_INVALID("manifest_invalid"),
    DOWNLOAD_FAILED("download_failed"),
    INSTALL_IN_PROGRESS("install_in_progress")
}

data class OmniArtifactFailure(
    val code: OmniArtifactFailureCode,
    val message: String,
    val artifactRole: OmniArtifactRole? = null
)

data class OmniInstallRequest(
    val explicitConsent: Boolean,
    val acceptedLicenseId: String?,
    val wifiOnly: Boolean = true
)

data class OmniInstallEnvironment(
    val availableMemoryBytes: Long,
    val availableStorageBytes: Long,
    val wifiConnected: Boolean
)

data class OmniPreflightResult(
    val passed: Boolean,
    val requiredMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val requiredStorageBytes: Long,
    val availableStorageBytes: Long,
    val wifiOnly: Boolean,
    val wifiConnected: Boolean,
    val failure: OmniArtifactFailure? = null
)

class OmniInstallPreflight(private val manifest: OmniArtifactManifest) {
    fun evaluate(request: OmniInstallRequest, environment: OmniInstallEnvironment): OmniPreflightResult {
        val failure = when {
            manifest.validationErrors().isNotEmpty() -> OmniArtifactFailure(
                OmniArtifactFailureCode.MANIFEST_INVALID,
                "Artifact manifest is not installable"
            )
            !request.explicitConsent -> OmniArtifactFailure(
                OmniArtifactFailureCode.EXPLICIT_CONSENT_REQUIRED,
                "Explicit download consent is required"
            )
            request.acceptedLicenseId != manifest.licenseId -> OmniArtifactFailure(
                OmniArtifactFailureCode.LICENSE_ACCEPTANCE_REQUIRED,
                "The source-declared license must be shown and accepted"
            )
            request.wifiOnly && !environment.wifiConnected -> OmniArtifactFailure(
                OmniArtifactFailureCode.WIFI_REQUIRED,
                "A Wi-Fi connection is required by this install request"
            )
            environment.availableStorageBytes < manifest.requiredStorageBytes -> OmniArtifactFailure(
                OmniArtifactFailureCode.INSUFFICIENT_STORAGE,
                "Not enough app-private storage for the verified artifact pair"
            )
            environment.availableMemoryBytes < manifest.minimumAvailableMemoryBytes -> OmniArtifactFailure(
                OmniArtifactFailureCode.INSUFFICIENT_MEMORY,
                "Not enough currently available memory for the provisional load gate"
            )
            else -> null
        }
        return OmniPreflightResult(
            passed = failure == null,
            requiredMemoryBytes = manifest.minimumAvailableMemoryBytes,
            availableMemoryBytes = environment.availableMemoryBytes,
            requiredStorageBytes = manifest.requiredStorageBytes,
            availableStorageBytes = environment.availableStorageBytes,
            wifiOnly = request.wifiOnly,
            wifiConnected = environment.wifiConnected,
            failure = failure
        )
    }
}

enum class OmniInstallPhase {
    IDLE,
    PREFLIGHT,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    FAILED,
    CANCELLED,
    UNINSTALLED
}

data class OmniArtifactVerification(
    val expectedSha256: String,
    val expectedBytes: Long,
    val installed: Boolean,
    val verified: Boolean,
    val verifiedAtEpochMs: Long?
)

data class OmniInstallSnapshot(
    val modelId: String,
    val revision: String,
    val phase: OmniInstallPhase,
    val main: OmniArtifactVerification,
    val mmproj: OmniArtifactVerification,
    val lastPreflight: OmniPreflightResult?,
    val failure: OmniArtifactFailure? = null
) {
    val pairVerified: Boolean
        get() = main.verified && mmproj.verified
}

class OmniInstallHandle internal constructor(
    val started: Boolean,
    val startFailure: OmniArtifactFailure?,
    private val worker: Thread?,
    private val cancelAction: () -> Unit
) {
    fun cancel() = cancelAction()

    fun await(timeoutMs: Long): Boolean {
        val runningWorker = worker ?: return true
        runningWorker.join(timeoutMs)
        return !runningWorker.isAlive
    }
}

sealed class OmniLoadPairResult {
    data object Loaded : OmniLoadPairResult()
    data class Failed(val failure: OmniArtifactFailure) : OmniLoadPairResult()
}

fun interface OmniVerifiedPairLoader {
    fun load(mainPath: String, mmprojPath: String): Boolean
}
