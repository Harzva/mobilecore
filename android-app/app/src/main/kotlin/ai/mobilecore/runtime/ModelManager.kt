package ai.mobilecore.runtime

import android.content.Context
import java.io.File

class ModelManager internal constructor(
    private val backend: RuntimeBackend,
    private val internalModelDir: File,
    private val externalModelDir: File?,
) {
    constructor(backend: RuntimeBackend, context: Context) : this(
        backend = backend,
        internalModelDir = File(context.filesDir, "models").apply { mkdirs() },
        externalModelDir = context.getExternalFilesDir("models")?.apply { mkdirs() },
    )

    fun scanModels(): List<RuntimeModel> {
        val discovered = modelDirectories()
            .flatMap { dir ->
                dir.listFiles { file ->
                    file.isFile &&
                        file.extension.lowercase() == "gguf" &&
                        !file.name.startsWith("mmproj-", ignoreCase = true)
                }?.toList() ?: emptyList()
            }
            .distinctBy { it.absolutePath }

        val list = discovered.map { file ->
            val metadata = GgufMetadataReader.read(file)
            val id = file.nameWithoutExtension
            RuntimeModel(
                id = id,
                path = file.absolutePath,
                format = "gguf",
                backend = "llama.cpp",
                quantization = metadata.quantization,
                contextLength = metadata.contextLength,
                sizeBytes = file.length(),
                loaded = isExactPathLoaded(file.absolutePath),
                architecture = metadata.architecture,
                parameterCountB = metadata.parameterCountB,
                parameterLabel = metadata.parameterLabel,
                metadataSource = metadata.source
            )
        }

        return if (list.isNotEmpty()) {
            list
        } else {
            listOf(
                RuntimeModel(
                    id = "local-model",
                    path = File(externalModelDir ?: internalModelDir, "local-model.gguf").absolutePath,
                    format = "gguf",
                    backend = "llama.cpp",
                    quantization = "Q4_K_M",
                    contextLength = 4096,
                    sizeBytes = 0,
                    loaded = false,
                    architecture = "unknown",
                    parameterLabel = null,
                    metadataSource = "placeholder"
                )
            )
        }
    }

    fun scanProjectors(): List<RuntimeProjector> {
        return modelDirectories()
            .flatMap { dir ->
                dir.listFiles { file ->
                    file.isFile &&
                        file.extension.lowercase() == "gguf" &&
                        file.name.startsWith("mmproj-", ignoreCase = true)
                }?.toList() ?: emptyList()
            }
            .distinctBy { it.absolutePath }
            .map { file ->
                RuntimeProjector(
                    id = file.nameWithoutExtension,
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    modelFamily = pairingKey(file.nameWithoutExtension, projector = true),
                )
            }
    }

    fun defaultModelId(): String = "local-model"

    fun firstAvailableModel(): RuntimeModel? {
        return scanModels().firstOrNull { it.sizeBytes > 0 }
    }

    /**
     * Loads only a real GGUF contained by an app-owned model directory. The caller may pass a
     * path discovered by the trusted installer, but path containment is still checked here at the
     * runtime boundary.
     */
    fun loadModelFile(modelFile: File, options: LoadOptions = LoadOptions()): LoadResult {
        val resolved = runCatching { modelFile.canonicalFile }.getOrNull()
        val allowedRoots = modelDirectories().mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        val allowed = resolved != null &&
            resolved.isFile &&
            resolved.extension.equals("gguf", ignoreCase = true) &&
            !resolved.name.startsWith("mmproj-", ignoreCase = true) &&
            allowedRoots.any { root ->
                resolved.path.startsWith(root.path + File.separator)
            }
        return if (allowed) {
            backend.loadModel(requireNotNull(resolved).absolutePath, options)
        } else {
            LoadResult(
                ok = false,
                modelId = "invalid-model",
                loadTimeMs = 0L,
                memoryUsedMb = 0L,
            )
        }
    }

    fun unloadModel(): Boolean = backend.unloadModel()

    /** Process-local canonical identity. It must not be serialized by public API handlers. */
    fun activeModelPath(): String? {
        if (!backend.isModelLoaded()) return null
        return canonicalPath(backend.activeModelPath())
    }

    fun isExactPathLoaded(modelPath: String): Boolean {
        val activePath = activeModelPath() ?: return false
        val candidatePath = canonicalPath(modelPath) ?: return false
        return activePath == candidatePath
    }

    /** Resolves the loaded model only by its exact canonical artifact path. */
    fun activeModel(): RuntimeModel? {
        val path = activeModelPath() ?: return null
        return modelByPath(path)
    }

    /** Resolve a public model id without exposing or accepting a filesystem path. */
    fun modelById(modelId: String): RuntimeModel? {
        val normalized = modelId.trim()
        if (normalized.isEmpty()) return null
        return scanModels().filter { model ->
            model.sizeBytes > 0 && model.id.equals(normalized, ignoreCase = true)
        }.singleOrNull()
    }

    /** Exact path lookup used only after canonical containment has already been established. */
    fun modelByPath(modelPath: String?): RuntimeModel? {
        val normalized = canonicalPath(modelPath) ?: return null
        return scanModels().filter { model ->
            model.sizeBytes > 0 && canonicalPath(model.path) == normalized
        }.singleOrNull()
    }

    fun projectorById(projectorId: String): RuntimeProjector? {
        val normalized = projectorId.trim()
        if (normalized.isEmpty()) return null
        return scanProjectors().filter { projector ->
            projector.sizeBytes > 0 && projector.id.equals(normalized, ignoreCase = true)
        }.singleOrNull()
    }

    fun projectorByPath(projectorPath: String?): RuntimeProjector? {
        val normalized = canonicalPath(projectorPath) ?: return null
        return scanProjectors().filter { projector ->
            projector.sizeBytes > 0 && canonicalPath(projector.path) == normalized
        }.singleOrNull()
    }

    /**
     * Auto-pair only when exactly one same-directory projector has the same
     * normalized model family. Ambiguous projector sets require an explicit
     * public projector_id at the API boundary.
     */
    fun projectorForModel(modelId: String): RuntimeProjector? {
        val model = modelById(modelId) ?: return null
        val family = pairingKey(model.id, projector = false)
        val modelParent = File(model.path).parentFile?.absolutePath ?: return null
        return scanProjectors().filter { projector ->
            File(projector.path).parentFile?.absolutePath == modelParent &&
                projector.modelFamily == family
        }.singleOrNull()
    }

    fun isProjectorCompatible(modelId: String, projectorId: String): Boolean {
        val model = modelById(modelId) ?: return false
        val projector = projectorById(projectorId) ?: return false
        return File(model.path).parentFile?.absolutePath ==
            File(projector.path).parentFile?.absolutePath &&
            pairingKey(model.id, projector = false) == projector.modelFamily
    }

    fun modelDirectories(): List<File> {
        return listOfNotNull(internalModelDir, externalModelDir).distinctBy { canonicalPath(it.path) }
    }

    private fun canonicalPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return runCatching { File(path).canonicalPath }.getOrNull()
    }

    private fun pairingKey(value: String, projector: Boolean): String {
        var normalized = value.removeSuffix(".gguf").lowercase()
        if (projector) normalized = normalized.removePrefix("mmproj-").removePrefix("mmproj_")
        normalized = normalized
            .replace("qwen_qwen", "qwen")
            .replace("qwen-qwen", "qwen")
            .replace(
                Regex("(?i)[-_.](?:q[2-8](?:_[a-z0-9]+)*|bf16|f16|fp16|fp32)$"),
                "",
            )
        return normalized.replace(Regex("[^a-z0-9.]+"), "-").trim('-')
    }
}
