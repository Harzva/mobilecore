package ai.mobilecore.runtime

import android.content.Context
import java.io.File

class ModelManager(
    private val backend: RuntimeBackend,
    private val context: Context
) {
    private val internalModelDir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val externalModelDir: File? = context.getExternalFilesDir("models")?.apply { mkdirs() }

    fun scanModels(): List<RuntimeModel> {
        val activeModel = backend.metrics().activeModel
        val discovered = modelDirectories()
            .flatMap { dir ->
                dir.listFiles { file ->
                    file.isFile && file.extension.lowercase() == "gguf"
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
                loaded = activeModel.equals(id, ignoreCase = true),
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

    fun defaultModelId(): String = "local-model"

    fun firstAvailableModel(): RuntimeModel? {
        return scanModels().firstOrNull { it.sizeBytes > 0 }
    }

    fun modelDirectories(): List<File> {
        return listOfNotNull(internalModelDir, externalModelDir)
    }
}
