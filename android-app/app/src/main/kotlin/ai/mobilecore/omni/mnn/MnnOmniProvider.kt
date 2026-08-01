package ai.mobilecore.omni.mnn

/**
 * Deliberately separate from the llama.cpp/libmtmd provider. No MNN runtime or model assets are
 * bundled today, so this contract reports unavailable instead of advertising speech output.
 */
interface MnnOmniProvider {
    fun probe(): MnnOmniProviderStatus
}

data class MnnOmniProviderStatus(
    val providerId: String,
    val runtimeIntegrated: Boolean,
    val artifactManifestVerified: Boolean,
    val modelLoaded: Boolean,
    val textInput: Boolean,
    val imageInput: Boolean,
    val audioInput: Boolean,
    val videoInput: Boolean,
    val textOutput: Boolean,
    val audioOutput: Boolean,
    val reason: String
)

class UnavailableMnnOmniProvider : MnnOmniProvider {
    override fun probe(): MnnOmniProviderStatus {
        return MnnOmniProviderStatus(
            providerId = "mnn-qwen2.5-omni-3b",
            runtimeIntegrated = false,
            artifactManifestVerified = false,
            modelLoaded = false,
            textInput = false,
            imageInput = false,
            audioInput = false,
            videoInput = false,
            textOutput = false,
            audioOutput = false,
            reason = "mnn_p1_not_integrated"
        )
    }
}
