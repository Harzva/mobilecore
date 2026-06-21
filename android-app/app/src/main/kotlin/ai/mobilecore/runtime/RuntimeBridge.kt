package ai.mobilecore.runtime

object RuntimeBridge {
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

    fun loadModel(modelPath: String, contextLength: Int): String =
        callNative(defaultValue = "{\"ok\":false,\"message\":\"native library unavailable\"}") {
            nativeLoadModel(modelPath, contextLength)
        }

    fun chat(modelId: String, prompt: String, maxTokens: Int, temperature: Float): String =
        callNative(defaultValue = "[mock fallback] model=$modelId; prompt=${prompt.take(64)}") {
            nativeChat(modelId, prompt, maxTokens, temperature)
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

    fun info(): String =
        callNative(defaultValue = "{\"backend\":\"stub\",\"status\":\"unavailable\"}") {
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

    private external fun nativeLoadModel(modelPath: String, contextLength: Int): String
    private external fun nativeChat(modelId: String, prompt: String, maxTokens: Int, temperature: Float): String
    private external fun nativeUnload()
    private external fun nativeBackendInfo(): String
}
