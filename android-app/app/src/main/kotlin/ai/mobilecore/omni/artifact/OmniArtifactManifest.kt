package ai.mobilecore.omni.artifact

enum class OmniArtifactRole {
    MAIN,
    MMPROJ
}

enum class OmniLicenseReviewStatus {
    SOURCE_DECLARED_NOT_LEGAL_REVIEWED
}

data class OmniArtifactSpec(
    val role: OmniArtifactRole,
    val fileName: String,
    val revision: String,
    val byteSize: Long,
    val sha256: String,
    val sourceUrl: String
)

data class OmniArtifactManifest(
    val schemaVersion: Int,
    val id: String,
    val displayName: String,
    val sourceRepository: String,
    val originalModel: String,
    val conversionPublisher: String,
    val licenseId: String,
    val licenseReviewStatus: OmniLicenseReviewStatus,
    val quantization: String,
    val runtime: String,
    val backend: String,
    val minimumAvailableMemoryBytes: Long,
    val storageSafetyBytes: Long,
    val artifacts: List<OmniArtifactSpec>
) {
    val totalArtifactBytes: Long
        get() = artifacts.sumOf { it.byteSize }

    val requiredStorageBytes: Long
        get() = totalArtifactBytes + storageSafetyBytes

    fun artifact(role: OmniArtifactRole): OmniArtifactSpec? = artifacts.singleOrNull { it.role == role }

    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()
        if (schemaVersion != 1) errors += "unsupported_schema_version"
        if (artifacts.size != 2) errors += "artifact_pair_required"
        OmniArtifactRole.entries.forEach { role ->
            if (artifacts.count { it.role == role } != 1) errors += "${role.name.lowercase()}_artifact_required"
        }
        artifacts.forEach { artifact ->
            if (artifact.byteSize <= 0) errors += "${artifact.role.name.lowercase()}_size_invalid"
            if (!artifact.sha256.matches(Regex("[0-9a-f]{64}"))) {
                errors += "${artifact.role.name.lowercase()}_sha256_invalid"
            }
            if (!artifact.revision.matches(Regex("[0-9a-f]{40}"))) {
                errors += "${artifact.role.name.lowercase()}_revision_invalid"
            }
            if (!artifact.sourceUrl.startsWith("https://huggingface.co/")) {
                errors += "${artifact.role.name.lowercase()}_source_not_allowed"
            }
            if (!artifact.sourceUrl.contains("/${artifact.revision}/")) {
                errors += "${artifact.role.name.lowercase()}_source_not_revision_pinned"
            }
        }
        return errors.distinct()
    }
}

/**
 * Reproducible metadata for the ggml-org conversion. This is not a Qwen-published GGUF.
 * Metadata was captured from the Hugging Face repository revision and LFS/Xet pointers.
 */
object Qwen25Omni3bArtifacts {
    const val REPOSITORY = "ggml-org/Qwen2.5-Omni-3B-GGUF"
    const val REVISION = "75f1b73b657a50f5092502799457ccb4a4a1f9df"
    const val LLAMA_CPP_REVISION = "063d9c156e816ae3cf62db01f429a07a099afe97"

    val manifest = OmniArtifactManifest(
        schemaVersion = 1,
        id = "qwen2.5-omni-3b-ggml-org-q4km-q8-mmproj",
        displayName = "Qwen2.5-Omni-3B (ggml-org GGUF conversion)",
        sourceRepository = "https://huggingface.co/$REPOSITORY",
        originalModel = "https://huggingface.co/Qwen/Qwen2.5-Omni-3B",
        conversionPublisher = "ggml-org",
        licenseId = "qwen-research",
        licenseReviewStatus = OmniLicenseReviewStatus.SOURCE_DECLARED_NOT_LEGAL_REVIEWED,
        quantization = "main=Q4_K_M;mmproj=Q8_0",
        runtime = "llama.cpp/libmtmd@$LLAMA_CPP_REVISION",
        backend = "llama.cpp/libmtmd",
        // Provisional conservative gate: both mapped artifacts plus 1 GiB runtime headroom.
        // Physical-device profiling must replace this estimate before product default enablement.
        minimumAvailableMemoryBytes = 4_716_704_800L,
        storageSafetyBytes = 536_870_912L,
        artifacts = listOf(
            OmniArtifactSpec(
                role = OmniArtifactRole.MAIN,
                fileName = "Qwen2.5-Omni-3B-Q4_K_M.gguf",
                revision = REVISION,
                byteSize = 2_104_931_648L,
                sha256 = "4b0bd358c1e9ec55dd3055ef6d71c958c821533d85916a10cfa89c4552a86e29",
                sourceUrl = "https://huggingface.co/$REPOSITORY/resolve/$REVISION/Qwen2.5-Omni-3B-Q4_K_M.gguf"
            ),
            OmniArtifactSpec(
                role = OmniArtifactRole.MMPROJ,
                fileName = "mmproj-Qwen2.5-Omni-3B-Q8_0.gguf",
                revision = REVISION,
                byteSize = 1_538_031_328L,
                sha256 = "4e6c816cd33f7298d07cb780c136a396631e50e62f6501660271f8c6e302e565",
                sourceUrl = "https://huggingface.co/$REPOSITORY/resolve/$REVISION/mmproj-Qwen2.5-Omni-3B-Q8_0.gguf"
            )
        )
    )
}
