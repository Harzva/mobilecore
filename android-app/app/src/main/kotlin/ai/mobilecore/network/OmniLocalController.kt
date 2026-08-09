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
import ai.mobilecore.playground.PlaygroundArtifactHealthResolver
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
import android.app.ActivityManager
import android.content.Context
import android.os.Build
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
    /** Both lookup functions accept canonical process-local paths, never public model ids. */
    private val activeModelLookup: (String?) -> RuntimeModel? = { null },
    private val activeProjectorLookup: (String?) -> RuntimeProjector? = { null },
    private val activeArtifactHealthLookup: (RuntimeModel) -> ArtifactHealth? = { null },
    private val backgroundRestrictedProbe: () -> Boolean = { false },
    private val bootstrapPersistedTrust: Boolean = false,
) {
    init {
        if (bootstrapPersistedTrust) installer.startStartupVerification()
    }

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
        activeModelLookup = { activeModelPath ->
            modelManager.modelByPath(activeModelPath)
        },
        activeProjectorLookup = { projectorPath ->
            modelManager.projectorByPath(projectorPath)
        },
        activeArtifactHealthLookup = PlaygroundArtifactHealthResolver(
            context.applicationContext,
            modelManager,
        )::resolve,
        backgroundRestrictedProbe = {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .isBackgroundRestricted
        },
        bootstrapPersistedTrust = true,
    )

    fun health(): JSONObject {
        val snapshot = installer.snapshot()
        val environment = environmentProbe.probe()
        val native = runtimeInfo()
        val runtimeReportsLoaded = backend.isModelLoaded() && native.optBoolean("modelLoaded", true)
        val main = requireNotNull(manifest.artifact(OmniArtifactRole.MAIN))
        val mmproj = requireNotNull(manifest.artifact(OmniArtifactRole.MMPROJ))
        val activeModelPath = backend.activeModelPath()
        val activeRuntimeModel = activeModelLookup(activeModelPath)?.takeIf { model ->
            sameCanonicalPath(model.path, activeModelPath)
        }
        val runtimeMultimodal = (backend as? MultimodalRuntimeBackend)?.multimodalStatus()
        val activeRuntimeProjector = activeProjectorLookup(runtimeMultimodal?.projectorPath)
            ?.takeIf { projector ->
                sameCanonicalPath(projector.path, runtimeMultimodal?.projectorPath)
            }
        val loaded = runtimeReportsLoaded && activeRuntimeModel != null
        val activeModel = activeRuntimeModel?.id
        val activeArtifactHealth = activeRuntimeModel?.let(activeArtifactHealthLookup)
        val qwenOmniLoaded = loaded &&
            snapshot.pairVerified &&
            sameCanonicalPath(activeModelPath, File(installDirectory, main.fileName).path) &&
            sameCanonicalPath(
                runtimeMultimodal?.projectorPath,
                File(installDirectory, mmproj.fileName).path,
            )
        val genericMultimodalLoaded = loaded &&
            activeRuntimeModel != null &&
            activeRuntimeProjector != null &&
            (runtimeMultimodal?.imageInput == true || runtimeMultimodal?.audioInput == true)
        val multimodalLoaded = qwenOmniLoaded || genericMultimodalLoaded
        val base = MobileCoreHealthSnapshot(
            version = version,
            activeModel = activeModel,
            quantization = activeRuntimeModel?.quantization ?: "unknown",
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
                activeArtifactHealth ?: ArtifactHealth(
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
            backgroundRestricted = backgroundRestrictedProbe(),
        ).toJson()
        base.put("install", snapshotJson(snapshot, environment))
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
        return snapshotJson(installer.snapshot(), environment)
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
            ?: OmniControllerResult(true, snapshotJson(snapshot, environmentProbe.probe()))
    }

    fun load(request: JSONObject): OmniControllerResult {
        val projector = requireNotNull(manifest.artifact(OmniArtifactRole.MMPROJ))
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
            val multimodal = backend as? MultimodalRuntimeBackend
            val ok = multimodal?.loadProjector(
                projectorPath = mmprojPath,
                projectorId = projector.fileName.removeSuffix(".gguf"),
                threads = options.threads,
            ) == true
            if (!ok) {
                runtimeFailure = projectorRuntimeFailure("")
                backend.unloadModel()
                return@loadVerifiedPair false
            }
            val runtimeProjectorPath = (backend as? MultimodalRuntimeBackend)
                ?.multimodalStatus()
                ?.projectorPath
            val identityMatches = sameCanonicalPath(backend.activeModelPath(), mainPath) &&
                sameCanonicalPath(runtimeProjectorPath, mmprojPath)
            if (!identityMatches) {
                runtimeFailure = OmniArtifactFailure(
                    OmniArtifactFailureCode.MODEL_LOAD_FAILED,
                    "The runtime did not confirm the exact verified artifact pair",
                )
                backend.unloadModel()
            }
            identityMatches
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
        val main = requireNotNull(manifest.artifact(OmniArtifactRole.MAIN))
        val mmproj = requireNotNull(manifest.artifact(OmniArtifactRole.MMPROJ))
        if (backend.isModelLoaded()) {
            val activePath = backend.activeModelPath()
            if (activePath == null) {
                return OmniControllerResult(
                    false,
                    errorJson(
                        OmniArtifactFailure(
                            OmniArtifactFailureCode.MODEL_LOAD_FAILED,
                            "Runtime artifact identity is unavailable; uninstall was refused",
                            OmniArtifactRole.MAIN,
                        ),
                    ),
                )
            }
            val multimodalStatus = (backend as? MultimodalRuntimeBackend)?.multimodalStatus()
            if (multimodalStatus?.projectorId != null && multimodalStatus.projectorPath.isNullOrBlank()) {
                return OmniControllerResult(
                    false,
                    errorJson(
                        OmniArtifactFailure(
                            OmniArtifactFailureCode.MODEL_LOAD_FAILED,
                            "Runtime projector identity is unavailable; uninstall was refused",
                            OmniArtifactRole.MMPROJ,
                        ),
                    ),
                )
            }
            val usesManagedMain = sameCanonicalPath(
                activePath,
                File(installDirectory, main.fileName).path,
            )
            val usesManagedProjector = sameCanonicalPath(
                multimodalStatus?.projectorPath,
                File(installDirectory, mmproj.fileName).path,
            )
            if (usesManagedMain || usesManagedProjector) {
                val unloaded = backend.unloadModel()
                val projectorStillManaged = sameCanonicalPath(
                    (backend as? MultimodalRuntimeBackend)?.multimodalStatus()?.projectorPath,
                    File(installDirectory, mmproj.fileName).path,
                )
                if (!unloaded || backend.isModelLoaded() || projectorStillManaged) {
                    return OmniControllerResult(
                        false,
                        errorJson(
                            OmniArtifactFailure(
                                OmniArtifactFailureCode.MODEL_LOAD_FAILED,
                                "The active Omni runtime could not be confirmed unloaded",
                                OmniArtifactRole.MAIN,
                            ),
                        ),
                    )
                }
            }
        }
        val snapshot = installer.uninstall()
        return snapshot.failure?.let { OmniControllerResult(false, errorJson(it)) }
            ?: OmniControllerResult(true, snapshotJson(snapshot, environmentProbe.probe()))
    }

    private fun snapshotJson(
        snapshot: OmniInstallSnapshot,
        environment: ai.mobilecore.omni.artifact.OmniInstallEnvironment,
    ): JSONObject {
        val resourcesSufficient =
            environment.availableMemoryBytes >= manifest.minimumAvailableMemoryBytes &&
                environment.availableStorageBytes >= manifest.requiredStorageBytes
        val main = requireNotNull(manifest.artifact(OmniArtifactRole.MAIN))
        val mmproj = requireNotNull(manifest.artifact(OmniArtifactRole.MMPROJ))
        val multimodal = (backend as? MultimodalRuntimeBackend)?.multimodalStatus()
        val loaded = backend.isModelLoaded() &&
            snapshot.pairVerified &&
            sameCanonicalPath(backend.activeModelPath(), File(installDirectory, main.fileName).path) &&
            sameCanonicalPath(multimodal?.projectorPath, File(installDirectory, mmproj.fileName).path)
        return JSONObject().apply {
            put("model_id", snapshot.modelId)
            put("revision", snapshot.revision)
            put("phase", snapshot.phase.name.lowercase())
            put("pair_verified", snapshot.pairVerified)
            put("loaded", loaded)
            put("license", JSONObject().apply {
                put("id", manifest.licenseId)
                put("review_status", manifest.licenseReviewStatus.name.lowercase())
            })
            put("wifi_only_default", true)
            put("wifi_connected", environment.wifiConnected)
            put("artifacts", JSONObject().apply {
                put("main", verificationJson(snapshot.main))
                put("mmproj", verificationJson(snapshot.mmproj))
            })
            put("preflight", JSONObject().apply {
                put("required_memory_bytes", manifest.minimumAvailableMemoryBytes)
                put("required_storage_bytes", manifest.requiredStorageBytes)
                put("available_memory_bytes", environment.availableMemoryBytes)
                put("available_storage_bytes", environment.availableStorageBytes)
                put("memory_sufficient", environment.availableMemoryBytes >= manifest.minimumAvailableMemoryBytes)
                put("storage_sufficient", environment.availableStorageBytes >= manifest.requiredStorageBytes)
                put("resources_sufficient", resourcesSufficient)
                snapshot.lastPreflight?.let { preflight ->
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

    private fun sameCanonicalPath(first: String?, second: String?): Boolean {
        if (first.isNullOrBlank() || second.isNullOrBlank()) return false
        val canonicalFirst = runCatching { File(first).canonicalPath }.getOrNull() ?: return false
        val canonicalSecond = runCatching { File(second).canonicalPath }.getOrNull() ?: return false
        return canonicalFirst == canonicalSecond
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
