package ai.mobilecore.runtime

object RuntimeBridge {
    enum class MtmdInputModality(val wireName: String) {
        IMAGE("image"),
        AUDIO("audio"),
    }

    private var libraryReady: Boolean = false

    init {
        libraryReady = try {
            System.loadLibrary("mobilecore_llama")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    fun isLibraryReady(): Boolean = libraryReady

    fun loadModel(modelPath: String, contextLength: Int, threads: Int): String =
        callNative(defaultValue = "{\"ok\":false,\"message\":\"native library unavailable\"}") {
            nativeLoadModel(modelPath, contextLength, threads)
        }

    fun chat(modelId: String, prompt: String, maxTokens: Int, temperature: Float): String =
        callNative(defaultValue = "[mock fallback] model=$modelId; prompt=${prompt.take(64)}") {
            nativeChat(modelId, prompt, maxTokens, temperature)
        }

    fun loadVisionProjector(
        projectorPath: String,
        threads: Int,
        imageMinTokens: Int = 0,
        imageMaxTokens: Int = 0,
    ): String = loadMtmdProjector(projectorPath, threads, imageMinTokens, imageMaxTokens)

    fun loadMtmdProjector(
        projectorPath: String,
        threads: Int,
        imageMinTokens: Int = 0,
        imageMaxTokens: Int = 0,
    ): String = callNative(
        defaultValue = "{\"ok\":false,\"code\":\"model_load_failed\",\"message\":\"native library unavailable\"}",
    ) {
        nativeLoadMtmdProjector(projectorPath, threads, imageMinTokens, imageMaxTokens)
    }

    fun visionChat(
        modelId: String,
        imagePath: String,
        prompt: String,
        maxTokens: Int,
    ): String = mediaChat(modelId, imagePath, MtmdInputModality.IMAGE, prompt, maxTokens)

    fun audioChat(
        modelId: String,
        audioPath: String,
        prompt: String,
        maxTokens: Int,
    ): String = mediaChat(modelId, audioPath, MtmdInputModality.AUDIO, prompt, maxTokens)

    fun mediaChat(
        modelId: String,
        mediaPath: String,
        modality: String,
        prompt: String,
        maxTokens: Int,
    ): String {
        val typedModality = MtmdInputModality.entries.firstOrNull { it.wireName == modality }
            ?: return "{\"ok\":false,\"code\":\"unsupported_modality\",\"message\":\"only image or audio input is supported\"}"
        return mediaChat(modelId, mediaPath, typedModality, prompt, maxTokens)
    }

    fun mediaChat(
        modelId: String,
        mediaPath: String,
        modality: MtmdInputModality,
        prompt: String,
        maxTokens: Int,
    ): String = callNative(
        defaultValue = "{\"ok\":false,\"code\":\"model_load_failed\",\"message\":\"native library unavailable\"}",
    ) {
        nativeMediaChat(modelId, mediaPath, modality.wireName, prompt, maxTokens)
    }

    fun unload(): Boolean {
        return if (libraryReady) {
            runCatching {
                nativeUnload()
                true
            }.getOrDefault(false)
        } else {
            false
        }
    }

    fun cancel(): Boolean = if (libraryReady) {
        runCatching {
            nativeCancel()
            true
        }.getOrDefault(false)
    } else {
        false
    }

    fun info(): String =
        callNative(
            defaultValue = "{\"backend\":\"stub\",\"status\":\"unavailable\",\"mtmdLoaded\":false,\"visionInput\":false,\"audioInput\":false,\"audioSampleRate\":0}",
        ) {
            nativeBackendInfo()
        }

    private fun callNative(defaultValue: String, block: () -> String): String {
        return if (libraryReady) {
            runCatching(block).getOrElse {
                libraryReady = false
                defaultValue
            }
        } else {
            defaultValue
        }
    }

    private external fun nativeLoadModel(modelPath: String, contextLength: Int, threads: Int): String
    private external fun nativeChat(modelId: String, prompt: String, maxTokens: Int, temperature: Float): String
    private external fun nativeLoadMtmdProjector(
        projectorPath: String,
        threads: Int,
        imageMinTokens: Int,
        imageMaxTokens: Int,
    ): String
    private external fun nativeMediaChat(
        modelId: String,
        mediaPath: String,
        modality: String,
        prompt: String,
        maxTokens: Int,
    ): String
    private external fun nativeCancel()
    private external fun nativeUnload()
    private external fun nativeBackendInfo(): String
}
