package ai.mobilecore.playground

import ai.mobilecore.runtime.ArtifactHealth
import ai.mobilecore.runtime.ModelManager
import ai.mobilecore.runtime.RuntimeModel
import android.content.Context
import java.io.File

/** Maps current-process digest proof back to the immutable catalog before `/health` exposes it. */
internal class PlaygroundArtifactHealthResolver(
    private val catalogProvider: () -> PlaygroundCatalog,
    private val modelDirectories: () -> List<File>,
) {
    constructor(context: Context, modelManager: ModelManager) : this(
        catalogProvider = { PlaygroundCatalogRepository(context.applicationContext).load() },
        modelDirectories = modelManager::modelDirectories,
    )

    private val catalog by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { catalogProvider() }

    fun resolve(model: RuntimeModel): ArtifactHealth? {
        val file = runCatching { File(model.path).canonicalFile }.getOrNull() ?: return null
        val roots = modelDirectories().mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        if (roots.none { file.parentFile == it }) return null

        val identity = PlaygroundArtifactTrustRegistry.resolve(file) ?: return null
        val entry = runCatching { catalog.entries.singleOrNull { it.id == identity.modelId } }.getOrNull()
            ?: return null
        val spec = runCatching { PlaygroundInstallSpec.fromCatalogEntry(entry) }.getOrNull() ?: return null
        val artifact = spec.artifacts.singleOrNull {
            it.role == "mobile_runtime" && it.name == identity.artifactName
        } ?: return null
        val matchesCatalog = spec.revision == identity.revision &&
            artifact.sha256 == identity.sha256 &&
            artifact.byteSize == identity.byteSize &&
            artifact.name == file.name &&
            model.sizeBytes == artifact.byteSize
        if (!matchesCatalog) return null

        return ArtifactHealth(
            fileName = artifact.name,
            expectedSha256 = artifact.sha256,
            expectedBytes = artifact.byteSize,
            present = true,
            verified = true,
        )
    }
}
