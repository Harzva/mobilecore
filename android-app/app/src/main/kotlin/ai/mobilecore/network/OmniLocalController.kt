package ai.mobilecore.network

import ai.mobilecore.omni.artifact.AndroidOmniInstallEnvironmentProbe
import ai.mobilecore.omni.artifact.OmniArtifactFailure
import ai.mobilecore.omni.artifact.OmniArtifactFailureCode
import ai.mobilecore.omni.artifact.OmniArtifactInstaller
import ai.mobilecore.omni.artifact.OmniArtifactManifest
import ai.mobilecore.omni.artifact.OmniArtifactRole
import ai.mobilecore.omni.artifact.OmniInstallEnvironmentProbe
import ai.mobilecore.omni.artifact.OmniInstallPreflight
import ai.mobilecore.omni.artifact.OmniInstallRequest
import ai.mobilecore.omni.artifact.OmniInstallSnapshot
import ai.mobilecore.omni.artifact.OmniLoadPairResult
import ai.mobilecore.omni.artifact.Qwen25Omni3bArtifacts
import ai.mobilecore.runtime.ArtifactHealth
import ai.mobilecore.runtime.LoadOptions
import ai.mobilecore.runtime.MobileCoreHealthSnapshot
import ai.mobilecore.runtime.ModelManager
import ai.mobilecore.runtime.ModalityCapabilities
import ai.mobilecore.runtime.MultimodalRuntimeBackend
import ai.mobilecore.runtime.ResourcePreflightHealth
import ai.mobilecore.runtime.RuntimeBackend
import ai.mobilecore.runtime.RuntimeBridge
import ai.mobilecore.runtime.RuntimeModel
import ai.mobilecore.runtime.RuntimeProjector
import android.content.Context
import org.json.JSONObject
import java.io.File

internal data class OmniControllerResult(
    val accepted: Boolean,
    val body: JSONObject,
)

/** Product-facing adapter for the pinned GGUF + mmproj lifecycle. */
internal class OmniLocalController(
    private val backend: RuntimeBackend,
    private val version: String,
    private val installDirectory: File,
    private val environmentProbe: OmniInstallEnvironmentProbe,
    private val manifest: OmniArtifactManifest = Qwen25Omni3bArtifacts.manifest,
    private val installer: OmniArtifactInstaller = OmniArtifactInstaller(
        installDirectory = installDirectory,
        environmentProbe = environmentProbe,
        manifest = manifest,
    ),
    private val runtimeInfo: () -> JSONObject = {
        runCatching { JSONObject(RuntimeBridge.info()) }.getOrElse { JSONObject() }
    },
    private val activeModelQuantization: (String?) -> String = { "unknown" },
    private val activeModelLookup: (String?) -> RuntimeModel? = { null },
    private val activeProjectorLookup: (String?) -> RuntimeProjector? = { null },
) {
    constructor(
        context: Context,
        backend: RuntimeBackend,
        version: String,
        modelManager: ModelManager,
    ) : this(
        backend = backend,
        version = version,
        installDirectory = modelManager.modelDirectories().first(),
        environmentProbe = AndroidOmniInstallEnvironmentProbe(context, modelManager.modelDirectories().first()),
        activeModelQuantization = { activeModel ->
            modelManager.scanModels()
                .firstOrNull { it.id.equals(activeModel, ignoreCase = true) }
                ?.quantization
                ?: "unknown"
        },
        activeModelLookup = { activeModel ->
            activeModel?.let(modelManager::modelById)
        },
        activeProjectorLookup = { projectorId ->
            projectorId?.let(modelManager::projectorById)
        },
    )

    fun health(): JSONObject {
        val snapshot = installer.snapshot()
        val environment = environmentProbe.probe()
        val native = runtimeInfo()
        val loaded = backend.isModelLoaded() && native.optBoolean("modelLoaded", true)
        val main = requireNotNull(manifest.artifact(OmniArtifactRole.MAIN))
        val mmproj = requireNotNull(manifest.artifact(OmniArtifactRole.MMPROJ))
        val activeModel = backend.metrics().activeModel
        val activeRuntimeModel = activeModelLookup(activeModel)
        val runtimeMultimodal = (backend as? MultimodalRuntimeBackend)?.multimodalStatus()
        val activeRuntimeProjector = activeProjectorLookup(runtimeMultimodal?.projectorId)
        val qwenOmniLoaded = loaded && snapshot.pairVerified && activeModel.equals(
            main.fileName.removeSuffix(".gguf"),
            ignoreCase = true,
        )
        val genericMultimodalLoaded = loaded &&
            activeRuntimeModel != null &&
            activeRuntimeProjector != null &&
            (runtimeMultimodal?.imageInput == true || runtimeMultimodal?.audioInput == true)
        val multimodalLoaded = qwenOmniLoaded || genericMultimodalLoaded
        val base = MobileCoreHealthSnapshot(
            version = version,
            activeModel = activeModel,
            quantization = activeModelQuantization(activeModel),
            modelLoaded = loaded,
            runtime = if (multimodalLoaded) "llama.cpp/libmtmd" else "llama.cpp",
            backend = "cpu",
            llamaRevision = Qwen25Omni3bArtifacts.LLAMA_CPP_REVISION,
            capabilities = ModalityCapabilities(
                textInput = loaded,
                imageInput = when {
                    qwenOmniLoaded -> native.optBoolean("visionInput", false)
                    genericMultimodalLoaded -> runtimeMultimodal?.imageInput == true
                    else -> false
                },
                audioInput = when {
                    qwenOmniLoaded -> native.optBoolean("audioInput", false)
                    genericMultimodalLoaded -> runtimeMultimodal?.audioInput == true
                    else -> false
                },
                videoInput = false,
                textOutput = loaded,
                audioOutput = false,
            ),
            mainArtifact = if (loaded && activeRuntimeModel != null && !qwenOmniLoaded) {
                ArtifactHealth(
                    fileName = File(activeRuntimeModel.path).name,
                    expectedSha256 = "",
                    expectedBytes = activeRuntimeModel.sizeBytes,
                    present = true,
                    verified = false,
                )
            } else {
                ArtifactHealth(
                    fileName = main.fileName,
                    expectedSha256 = snapshot.main.expectedSha256,
                    expectedBytes = snapshot.main.expectedBytes,
                    present = snapshot.main.installed,
                    verified = snapshot.main.verified,
                )
            },
            projectorArtifact = if (genericMultimodalLoaded && activeRuntimeProjector != null) {
                ArtifactHealth(
                    fileName = File(activeRuntimeProjector.path).name,
                    expectedSha256 = "",
                    expectedBytes = activeRuntimeProjector.sizeBytes,
                    present = true,
                    verified = false,
                )
            } else if (loaded && activeRuntimeModel != null && !qwenOmniLoaded) {
                ArtifactHealth(
                    fileName = "",
                    expectedSha256 = "",
                    expectedBytes = 0L,
                    present = false,
                    verified = false,
                )
            } else {
                ArtifactHealth(
                    fileName = mmproj.fileName,
                    expectedSha256 = snapshot.mmproj.expectedSha256,
                    expectedBytes = snapshot.mmproj.expectedBytes,
                    present = snapshot.mmproj.installed,
                    verified = snapshot.mmproj.verified,
                )
            },
            preflight = if (loaded && activeRuntimeModel != null && !qwenOmniLoaded) {
                ordinaryModelPreflight(activeRuntimeModel, activeRuntimeProjector, environment)
            } else {
                ResourcePreflightHealth(
                    availableMemoryBytes = environment.availableMemoryBytes,
                    requiredMemoryBytes = manifest.minimumAvailableMemoryBytes,
                    availableStorageBytes = environment.availableStorageBytes,
                    requiredStorageBytes = manifest.requiredStorageBytes,
                )
            },
        ).toJson()
        base.put("install", snapshotJson(snapshot, environment.wifiConnected))
        base.put(
            "audio_sample_rate_hz",
            if (genericMultimodalLoaded) runtimeMultimodal?.audioSampleRateHz ?: 0
            else native.optInt("audioSampleRate", 0),
        )
        return base
    }

    private fun ordinaryModelPreflight(
        model: RuntimeModel,
        projector: RuntimeProjector?,
        environment: ai.mobilecore.omni.artifact.OmniInstallEnvironment,
    ): ResourcePreflightHealth {
        val contextOverheadBytes = 128L * 1024L * 1024L
        val requiredMemoryBytes = (model.sizeBytes + (projector?.sizeBytes ?: 0L) + contextOverheadBytes)
            .coerceAtLeast(contextOverheadBytes)
        return ResourcePreflightHealth(
            availableMemoryBytes = environment.availableMemoryBytes,
            requiredMemoryBytes = requiredMemoryBytes,
            availableStorageBytes = environment.availableStorageBytes,
            requiredStorageBytes = 0L,
        )
    }

    fun status(): JSONObject {
        val environment = environmentProbe.probe()
        return snapshotJson(installer.snapshot(), environment.wifiConnected)
    }

    fun install(request: JSONObject): OmniControllerResult {
        val installRequest = OmniInstallRequest(
            explicitConsent = request.optBoolean("explicit_consent", false),
            acceptedLicenseId = request.optString("accepted_license_id", "").takeIf(String::isNotBlank),
            wifiOnly = request.optBoolean("wifi_only", true),
        )
        val preflight = OmniInstallPreflight(manifest).evaluate(installRequest, environmentProbe.probe())
        if (!preflight.passed) {
            return OmniControllerResult(false, errorJson(requireNotNull(preflight.failure)))
        }
        val handle = installer.install(installRequest)
        return if (handle.started) {
            OmniControllerResult(
                accepted = true,
                body = JSONObject().apply {
                    put("accepted", true)
                    put("status", status())
                },
            )
        } else {
            OmniControllerResult(false, errorJson(requireNotNull(handle.startFailure)))
        }
    }

    fun cancel(): JSONObject {
        installer.cancel()
        return JSONObject().apply {
            put("cancel_requested", true)
            put("status", status())
        }
    }

    fun verify(): OmniControllerResult {
        val snapshot = installer.verifyInstalledPair()
        return snapshot.failure?.let { OmniControllerResult(false, errorJson(it)) }
            ?: OmniControllerResult(true, snapshotJson(snapshot, environmentProbe.probe().wifiConnected))
    }

    fun load(request: JSONObject): OmniControllerResult {
        val options = LoadOptions(
            contextLength = request.optInt("context_length", 4096).coerceIn(128, 32_768),
            threads = request.optInt("threads", 4).coerceIn(1, 16),
            gpuLayers = 0,
        )
        var runtimeFailure: OmniArtifactFailure? = null
        val result = installer.loadVerifiedPair { mainPath, mmprojPath ->
            val mainLoad = backend.loadModel(mainPath, options)
            if (!mainLoad.ok) {
                runtimeFailure = OmniArtifactFailure(
                    OmniArtifactFailureCode.MODEL_LOAD_FAILED,
                    "The runtime rejected the verified main model",
                    OmniArtifactRole.MAIN,
                )
                return@loadVerifiedPair false
            }
            val projectorLoad = runCatching {
                JSONObject(
                    RuntimeBridge.loadMtmdProjector(
                        projectorPath = mmprojPath,
                        threads = options.threads,
                    ),
                )
            }.getOrNull()
            val ok = projectorLoad?.optBoolean("ok", false) == true
            if (!ok) {
                runtimeFailure = projectorRuntimeFailure(projectorLoad?.optString("code", ""))
                backend.unloadModel()
            }
            ok
        }
        return when (result) {
            OmniLoadPairResult.Loaded -> OmniControllerResult(
                accepted = true,
                body = JSONObject().apply {
                    put("loaded", true)
                    put("status", status())
                },
            )

            is OmniLoadPairResult.Failed -> OmniControllerResult(
                false,
                errorJson(runtimeFailure ?: result.failure),
            )
        }
    }

    fun uninstall(): OmniControllerResult {
        backend.unloadModel()
        val snapshot = installer.uninstall()
        return snapshot.failure?.let { OmniControllerResult(false, errorJson(it)) }
            ?: OmniControllerResult(true, snapshotJson(snapshot, environmentProbe.probe().wifiConnected))
    }

    private fun snapshotJson(snapshot: OmniInstallSnapshot, wifiConnected: Boolean): JSONObject {
        return JSONObject().apply {
            put("model_id", snapshot.modelId)
            put("revision", snapshot.revision)
            put("phase", snapshot.phase.name.lowercase())
            put("pair_verified", snapshot.pairVerified)
            put("license", JSONObject().apply {
                put("id", manifest.licenseId)
                put("review_status", manifest.licenseReviewStatus.name.lowercase())
            })
            put("wifi_only_default", true)
            put("wifi_connected", wifiConnected)
            put("artifacts", JSONObject().apply {
                put("main", verificationJson(snapshot.main))
                put("mmproj", verificationJson(snapshot.mmproj))
            })
            put("preflight", JSONObject().apply {
                put("required_memory_bytes", manifest.minimumAvailableMemoryBytes)
                put("required_storage_bytes", manifest.requiredStorageBytes)
                snapshot.lastPreflight?.let { preflight ->
                    put("available_memory_bytes", preflight.availableMemoryBytes)
                    put("available_storage_bytes", preflight.availableStorageBytes)
                    put("passed", preflight.passed)
                    put("failure_code", preflight.failure?.code?.wireValue ?: JSONObject.NULL)
                }
            })
            snapshot.failure?.let { put("failure", errorJson(it).getJSONObject("error")) }
        }
    }

    private fun verificationJson(value: ai.mobilecore.omni.artifact.OmniArtifactVerification): JSONObject {
        return JSONObject().apply {
            put("digest_algorithm", "sha256")
            put("digest", value.expectedSha256)
            put("expected_bytes", value.expectedBytes)
            put("installed", value.installed)
            put("verified", value.verified)
            put("verified_at_epoch_ms", value.verifiedAtEpochMs ?: JSONObject.NULL)
        }
    }

    private fun errorJson(failure: OmniArtifactFailure): JSONObject = JSONObject().apply {
        put("error", JSONObject().apply {
            put("message", failure.message)
            put("type", "mobilecore_artifact_error")
            put("code", failure.code.wireValue)
            put("artifact_role", failure.artifactRole?.name?.lowercase() ?: JSONObject.NULL)
        })
    }
}

internal fun projectorRuntimeFailure(rawCode: String?): OmniArtifactFailure {
    val code = when (rawCode) {
        OmniArtifactFailureCode.ARTIFACT_MISSING.wireValue -> OmniArtifactFailureCode.ARTIFACT_MISSING
        OmniArtifactFailureCode.UNSUPPORTED_MODALITY.wireValue -> OmniArtifactFailureCode.PROJECTOR_INCOMPATIBLE
        else -> OmniArtifactFailureCode.PROJECTOR_LOAD_FAILED
    }
    val message = when (code) {
        OmniArtifactFailureCode.ARTIFACT_MISSING -> "The verified projector is no longer available"
        OmniArtifactFailureCode.PROJECTOR_INCOMPATIBLE -> "The projector is incompatible with the selected model"
        else -> "The runtime rejected the verified projector"
    }
    return OmniArtifactFailure(code, message, OmniArtifactRole.MMPROJ)
}
