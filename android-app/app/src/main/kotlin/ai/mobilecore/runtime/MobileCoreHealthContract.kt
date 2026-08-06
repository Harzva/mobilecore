package ai.mobilecore.runtime

import org.json.JSONObject

data class ModalityCapabilities(
    val textInput: Boolean,
    val imageInput: Boolean,
    val audioInput: Boolean,
    val videoInput: Boolean,
    val textOutput: Boolean,
    val audioOutput: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text_input", textInput)
        put("image_input", imageInput)
        put("audio_input", audioInput)
        put("video_input", videoInput)
        put("text_output", textOutput)
        put("audio_output", audioOutput)
    }
}

data class ArtifactHealth(
    val fileName: String,
    val expectedSha256: String,
    val expectedBytes: Long,
    val present: Boolean,
    val verified: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("file_name", fileName)
        put("digest_algorithm", if (expectedSha256.isBlank()) JSONObject.NULL else "sha256")
        put("digest", if (expectedSha256.isBlank()) JSONObject.NULL else expectedSha256)
        put("expected_bytes", expectedBytes)
        put("present", present)
        put("verified", verified)
    }
}

data class ResourcePreflightHealth(
    val availableMemoryBytes: Long,
    val requiredMemoryBytes: Long,
    val availableStorageBytes: Long,
    val requiredStorageBytes: Long,
) {
    val memoryOk: Boolean = availableMemoryBytes >= requiredMemoryBytes
    val storageOk: Boolean = availableStorageBytes >= requiredStorageBytes

    fun toJson(): JSONObject = JSONObject().apply {
        put("memory", JSONObject().apply {
            put("available_bytes", availableMemoryBytes)
            put("required_bytes", requiredMemoryBytes)
            put("ok", memoryOk)
        })
        put("storage", JSONObject().apply {
            put("available_bytes", availableStorageBytes)
            put("required_bytes", requiredStorageBytes)
            put("ok", storageOk)
        })
        put("ok", memoryOk && storageOk)
        put(
            "failure_code",
            when {
                !memoryOk -> "insufficient_memory"
                !storageOk -> "insufficient_storage"
                else -> JSONObject.NULL
            },
        )
    }
}

data class MobileCoreHealthSnapshot(
    val version: String,
    val activeModel: String?,
    val quantization: String,
    val modelLoaded: Boolean,
    val runtime: String,
    val backend: String,
    val llamaRevision: String,
    val capabilities: ModalityCapabilities,
    val mainArtifact: ArtifactHealth,
    val projectorArtifact: ArtifactHealth,
    val preflight: ResourcePreflightHealth,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("status", "ok")
        put("service", "mobilecore")
        put("version", version)
        put("model_loaded", modelLoaded)
        put("active_model", activeModel ?: JSONObject.NULL)
        put("quantization", quantization)
        put("runtime", runtime)
        put("backend", backend)
        put("llama_cpp_revision", llamaRevision)
        put("capabilities", capabilities.toJson())
        put("artifacts", JSONObject().apply {
            put("main", mainArtifact.toJson())
            put("mmproj", projectorArtifact.toJson())
        })
        put("preflight", preflight.toJson())
    }
}
