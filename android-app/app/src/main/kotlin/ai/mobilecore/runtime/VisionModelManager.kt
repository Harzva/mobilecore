package ai.mobilecore.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class VisionModelFile(
    val id: String,
    val task: String,
    val backend: String,
    val fileName: String,
    val sizeBytes: Long,
    val file: File
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("task", task)
            put("backend", backend)
            put("file_name", fileName)
            put("size_bytes", sizeBytes)
        }
    }
}

class VisionModelManager(context: Context) {
    private val internalDir = File(context.filesDir, "vision/models").apply { mkdirs() }
    private val externalDir = context.getExternalFilesDir("vision/models")?.apply { mkdirs() }
    private val supportedExtensions = setOf("onnx", "ort", "tflite", "mnn")

    fun modelDirectories(): List<File> {
        return listOfNotNull(internalDir, externalDir)
    }

    fun scanModels(): List<VisionModelFile> {
        return modelDirectories()
            .flatMap { dir ->
                dir.listFiles { file ->
                    file.isFile && file.extension.lowercase() in supportedExtensions
                }?.toList() ?: emptyList()
            }
            .distinctBy { it.absolutePath }
            .map { file ->
                VisionModelFile(
                    id = file.nameWithoutExtension,
                    task = inferTask(file.name),
                    backend = inferBackend(file.extension),
                    fileName = file.name,
                    sizeBytes = file.length(),
                    file = file
                )
            }
            .sortedWith(compareBy<VisionModelFile> { it.task }.thenBy { it.fileName })
    }

    fun hasTask(task: String): Boolean {
        return scanModels().any { it.task == task }
    }

    fun firstModelForTask(task: String): VisionModelFile? {
        return scanModels().firstOrNull { it.task == task }
    }

    fun toJson(): JSONObject {
        val models = scanModels()
        return JSONObject().apply {
            put("object", "vision.models")
            put("count", models.size)
            put(
                "directories",
                JSONArray().apply {
                    modelDirectories().forEach { dir -> put(dir.name) }
                }
            )
            put(
                "data",
                JSONArray().apply {
                    models.forEach { model -> put(model.toJson()) }
                }
            )
        }
    }

    private fun inferTask(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            "mnist" in lower -> "mnist"
            "clip" in lower || "vit" in lower -> "clip"
            "cifar" in lower -> "cifar10"
            "ocr" in lower || "ppocr" in lower || "paddle" in lower || "rapid" in lower || "trocr" in lower -> "ocr"
            "sd" in lower || "diffusion" in lower || "lcm" in lower -> "diffusion"
            else -> "vision"
        }
    }

    private fun inferBackend(extension: String): String {
        return when (extension.lowercase()) {
            "tflite" -> "tflite"
            "mnn" -> "mnn"
            else -> "onnxruntime-mobile"
        }
    }
}
