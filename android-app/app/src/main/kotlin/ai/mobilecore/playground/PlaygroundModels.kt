package ai.mobilecore.playground

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

enum class PlaygroundArtifactOrigin(
    val wireValue: String,
    val displayLabel: String,
) {
    HARZVA("converted_by_harzva", "Harzva 转换"),
    THIRD_PARTY("third_party_conversion", "第三方转换"),
    UPSTREAM("upstream_native", "上游官方"),
    RECIPE("recipe_only", "仅配方");

    companion object {
        fun fromWireValue(value: String): PlaygroundArtifactOrigin =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("unsupported artifact origin: $value")
    }
}

data class PlaygroundSource(
    val repository: String,
    val revision: String,
    val upstreamPublisher: String,
    val conversionPublisher: String,
    val license: String,
    val licenseReview: String,
)

data class PlaygroundArtifact(
    val name: String,
    val role: String,
    val format: String,
    val sizeBytes: Long,
    val sha256: String,
    val distribution: String,
    val sourceUrl: String?,
)

data class PlaygroundCapabilities(
    val inputs: List<String>,
    val outputs: List<String>,
    val backends: List<String>,
    val unsupported: List<String>,
)

data class PlaygroundVerifiedCapabilities(
    val inputs: List<String>,
    val outputs: List<String>,
    val backends: List<String>,
    val status: String,
    val scopes: List<String>,
)

data class PlaygroundDistribution(
    val mode: String,
    val mirrorEligible: Boolean,
    val publishable: Boolean,
    val published: Boolean,
    val downloadable: Boolean,
    val repositoryUrl: String?,
    val revision: String?,
    val installTransport: String?,
    val publicationState: String?,
)

data class PlaygroundCatalogEntry(
    val id: String,
    val displayName: String,
    val origin: PlaygroundArtifactOrigin,
    val state: String,
    val validationLabel: String,
    val summary: String,
    val parameterLabel: String,
    val quantizationLabel: String,
    val featured: Boolean,
    val source: PlaygroundSource,
    val artifacts: List<PlaygroundArtifact>,
    val declaredCapabilities: PlaygroundCapabilities,
    val verifiedCapabilities: PlaygroundVerifiedCapabilities,
    val distribution: PlaygroundDistribution,
) {
    val requiredArtifactNames: Set<String>
        get() = artifacts
            .filter { it.role == "mobile_runtime" || it.role == "projector" }
            .mapTo(linkedSetOf()) { it.name }
}

data class PlaygroundCatalog(
    val id: String,
    val sourceRepository: String,
    val registryDigestSha256: String,
    val entries: List<PlaygroundCatalogEntry>,
)

class PlaygroundCatalogRepository(private val context: Context) {
    fun load(): PlaygroundCatalog = context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
        PlaygroundCatalogParser.parse(reader.readText())
    }

    companion object {
        const val ASSET_PATH = "mobile-model-playground/catalog-v1.json"
    }
}

object PlaygroundCatalogParser {
    private val idPattern = Regex("^[a-z0-9][a-z0-9._-]+$")
    private val revisionPattern = Regex("^[0-9a-f]{40}$")
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val allowedStates = setOf(
        "DISCOVERED",
        "PROVENANCE_LOCKED",
        "LICENSE_CLEARED",
        "REFERENCE_VALIDATED",
        "CONVERTED",
        "HOST_VALIDATED",
        "EMULATOR_CONTRACT_CHECKED",
        "DEVICE_VALIDATED",
        "QUALITY_VALIDATED",
        "PERFORMANCE_VALIDATED",
        "RECIPE_ONLY",
        "BLOCKED_LICENSE",
        "BLOCKED_DEVICE",
        "UNSUPPORTED_ARCHITECTURE",
        "FAILED_QUALITY",
        "FAILED_RESOURCE_BUDGET",
    )
    private val allowedLicenseReviews = setOf("cleared", "pending", "blocked")
    private val allowedDistributions = setOf("external", "gitcode_lfs", "not_distributed")
    private val allowedVerifiedStatuses = setOf("pass", "quality_failed", "unverified")
    private val allowedDistributionModes = setOf(
        "upstream_link",
        "planned_gitcode_model_repo",
        "gitcode_model_repo",
        "byte_mirror",
        "recipe_only",
    )
    private val allowedInstallTransports = setOf("https_direct", "git_lfs_batch")
    private val allowedPublicationStates = setOf("PUBLISHED", "POST_PUBLISH_VERIFIED")
    private val downloadableStates = setOf(
        "EMULATOR_CONTRACT_CHECKED",
        "DEVICE_VALIDATED",
        "QUALITY_VALIDATED",
        "PERFORMANCE_VALIDATED",
    )

    fun parse(text: String): PlaygroundCatalog {
        val root = JSONObject(text)
        require(root.getInt("schema_version") == 1) { "unsupported catalog schema" }
        require(root.getString("catalog_id") == "mobile-model-playground-v1") { "unexpected catalog id" }
        val sourceRepository = requireHttps(root.getString("source_repository"), "source_repository")
        val digest = root.getString("registry_digest_sha256")
        require(sha256Pattern.matches(digest)) { "invalid registry digest" }

        val entriesJson = root.getJSONArray("entries")
        require(entriesJson.length() > 0) { "catalog is empty" }
        val entries = buildList {
            for (index in 0 until entriesJson.length()) {
                add(parseEntry(entriesJson.getJSONObject(index)))
            }
        }
        require(entries.map { it.id }.toSet().size == entries.size) { "duplicate catalog entry id" }
        return PlaygroundCatalog(
            id = root.getString("catalog_id"),
            sourceRepository = sourceRepository,
            registryDigestSha256 = digest,
            entries = entries,
        )
    }

    private fun parseEntry(value: JSONObject): PlaygroundCatalogEntry {
        val id = value.getString("id")
        require(idPattern.matches(id)) { "invalid model id" }
        val origin = PlaygroundArtifactOrigin.fromWireValue(value.getString("artifact_origin"))
        require(value.getString("origin_label") == origin.displayLabel) { "origin label does not match provenance" }
        val state = value.getString("state")
        require(state in allowedStates) { "unsupported model state" }

        val sourceJson = value.getJSONObject("source")
        val licenseReview = sourceJson.getString("license_review")
        require(licenseReview in allowedLicenseReviews) { "unsupported license review state" }
        val revision = sourceJson.getString("revision")
        require(revisionPattern.matches(revision)) { "source revision must be an immutable commit" }
        val source = PlaygroundSource(
            repository = requireHttps(sourceJson.getString("repository"), "source.repository"),
            revision = revision,
            upstreamPublisher = sourceJson.getString("upstream_publisher").also { require(it.isNotBlank()) },
            conversionPublisher = sourceJson.getString("conversion_publisher").also { require(it.isNotBlank()) },
            license = sourceJson.getString("license").also { require(it.isNotBlank()) },
            licenseReview = licenseReview,
        )

        val artifactsJson = value.getJSONArray("artifacts")
        require(artifactsJson.length() > 0) { "model has no distributable artifacts" }
        val artifacts = buildList {
            for (index in 0 until artifactsJson.length()) {
                val artifact = artifactsJson.getJSONObject(index)
                val name = artifact.getString("name")
                require(name.isNotBlank() && '/' !in name && '\\' !in name) { "unsafe artifact name" }
                val size = artifact.getLong("size_bytes")
                require(size > 0L) { "artifact size must be positive" }
                val sha256 = artifact.getString("sha256")
                require(sha256Pattern.matches(sha256)) { "invalid artifact digest" }
                val distribution = artifact.getString("distribution")
                require(distribution in allowedDistributions) { "unsupported artifact distribution" }
                val sourceUrl = artifact.optString("source_url").takeIf { it.isNotBlank() }
                    ?.let { requireHttps(it, "artifact.source_url") }
                add(
                    PlaygroundArtifact(
                        name = name,
                        role = artifact.getString("role"),
                        format = artifact.getString("format"),
                        sizeBytes = size,
                        sha256 = sha256,
                        distribution = distribution,
                        sourceUrl = sourceUrl,
                    )
                )
            }
        }
        require(artifacts.map { it.name }.toSet().size == artifacts.size) { "duplicate artifact name" }

        val declaredCapabilitiesJson = value.getJSONObject("declared_capabilities")
        val verifiedCapabilitiesJson = value.getJSONObject("verified_capabilities")
        val verifiedStatus = verifiedCapabilitiesJson.getString("status")
        require(verifiedStatus in allowedVerifiedStatuses) { "unsupported verified capability state" }
        val distributionJson = value.getJSONObject("distribution")
        val distributionMode = distributionJson.getString("mode")
        require(distributionMode in allowedDistributionModes) { "unsupported catalog distribution mode" }
        val published = distributionJson.getBoolean("published")
        val downloadable = distributionJson.getBoolean("downloadable")
        val repositoryUrl = distributionJson.optString("repository_url").takeIf { it.isNotBlank() }
            ?.let { requireHttps(it, "distribution.repository_url") }
        val publishedRevision = distributionJson.optString("revision").takeIf { it.isNotBlank() }
            ?.also { require(revisionPattern.matches(it)) { "distribution revision must be an immutable commit" } }
        val installTransport = distributionJson.optString("install_transport").takeIf { it.isNotBlank() }
            ?.also { require(it in allowedInstallTransports) { "unsupported install transport" } }
        val publicationState = distributionJson.optString("publication_state").takeIf { it.isNotBlank() }
            ?.also { require(it in allowedPublicationStates) { "unsupported publication state" } }
        require(!downloadable || published) { "downloadable artifact must be published" }
        require(!downloadable || licenseReview == "cleared") { "downloadable artifact requires cleared license" }
        require(!downloadable || verifiedStatus == "pass") { "downloadable artifact requires verified capability" }
        require(!downloadable || state in downloadableStates) { "downloadable artifact requires a positive validation state" }
        require(!downloadable || installTransport == "https_direct") {
            "downloadable artifact requires a supported direct install transport"
        }
        require(!downloadable || artifacts.all { it.sourceUrl != null }) {
            "downloadable entry must pin every artifact source URL"
        }
        if (distributionMode == "gitcode_model_repo") {
            require(published) { "GitCode model repository must be published" }
            require(repositoryUrl != null) { "GitCode model repository URL is required" }
            require(publishedRevision != null) { "GitCode model repository revision is required" }
            require(installTransport != null) { "GitCode model install transport is required" }
            require(publicationState == "PUBLISHED" || publicationState == "POST_PUBLISH_VERIFIED") {
                "published GitCode model requires a published publication state"
            }
        }
        return PlaygroundCatalogEntry(
            id = id,
            displayName = value.getString("display_name").also { require(it.isNotBlank()) },
            origin = origin,
            state = state,
            validationLabel = value.getString("validation_label").also { require(it.isNotBlank()) },
            summary = value.getString("summary").also { require(it.isNotBlank()) },
            parameterLabel = value.optString("parameter_label"),
            quantizationLabel = value.optString("quantization_label"),
            featured = value.optBoolean("featured", false),
            source = source,
            artifacts = artifacts,
            declaredCapabilities = PlaygroundCapabilities(
                inputs = declaredCapabilitiesJson.getJSONArray("inputs").strings(),
                outputs = declaredCapabilitiesJson.getJSONArray("outputs").strings(),
                backends = declaredCapabilitiesJson.getJSONArray("backends").strings(),
                unsupported = declaredCapabilitiesJson.getJSONArray("unsupported").strings(),
            ),
            verifiedCapabilities = PlaygroundVerifiedCapabilities(
                inputs = verifiedCapabilitiesJson.getJSONArray("inputs").strings(),
                outputs = verifiedCapabilitiesJson.getJSONArray("outputs").strings(),
                backends = verifiedCapabilitiesJson.getJSONArray("backends").strings(),
                status = verifiedStatus,
                scopes = verifiedCapabilitiesJson.getJSONArray("scopes").strings(),
            ),
            distribution = PlaygroundDistribution(
                mode = distributionMode,
                mirrorEligible = distributionJson.getBoolean("mirror_eligible"),
                publishable = distributionJson.getBoolean("publishable"),
                published = published,
                downloadable = downloadable,
                repositoryUrl = repositoryUrl,
                revision = publishedRevision,
                installTransport = installTransport,
                publicationState = publicationState,
            ),
        )
    }

    private fun JSONArray.strings(): List<String> = buildList {
        for (index in 0 until length()) {
            add(getString(index))
        }
    }

    private fun requireHttps(value: String, field: String): String {
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "$field must be an HTTPS URL without credentials"
        }
        return value
    }
}
