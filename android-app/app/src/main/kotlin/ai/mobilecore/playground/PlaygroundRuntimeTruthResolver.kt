package ai.mobilecore.playground

import java.io.File

internal data class PlaygroundRuntimeCandidate(
    val publicModelId: String,
    val path: String,
    val trustedSha256: String? = null,
)

/** Resolves health's path-free identity only when it identifies exactly one app-private file. */
internal object PlaygroundRuntimeTruthResolver {
    fun resolve(
        modelLoaded: Boolean,
        activeModelId: String?,
        verifiedMainDigest: String?,
        candidates: List<PlaygroundRuntimeCandidate>,
    ): String? {
        if (!modelLoaded) return null
        val normalizedId = activeModelId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val matchingId = candidates.filter { it.publicModelId.equals(normalizedId, ignoreCase = true) }
        val matching = if (verifiedMainDigest != null) {
            matchingId.filter { it.trustedSha256.equals(verifiedMainDigest, ignoreCase = true) }
        } else {
            matchingId
        }
        val canonical = matching.mapNotNull { candidate ->
            runCatching { File(candidate.path).canonicalFile.absolutePath }.getOrNull()
        }.distinct()
        return canonical.singleOrNull()
    }
}
