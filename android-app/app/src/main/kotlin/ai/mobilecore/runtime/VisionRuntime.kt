package ai.mobilecore.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.sqrt

data class VisionModelCandidate(
    val id: String,
    val task: String,
    val backend: String,
    val status: String,
    val reason: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("task", task)
            put("backend", backend)
            put("status", status)
            put("reason", reason)
        }
    }
}

class VisionRuntime(private val modelManager: VisionModelManager) {
    fun status(): JSONObject {
        val models = modelManager.scanModels()
        val probes = models.map { probeModel(it) }
        val hasLoadableModel = probes.any { it.status == "loadable" }
        val hasMlKitOcr = true
        return JSONObject().apply {
            put("object", "vision.status")
            put(
                "status",
                when {
                    hasMlKitOcr || hasLoadableModel -> "backend_ready"
                    models.isEmpty() -> "models_missing"
                    else -> "model_load_error"
                }
            )
            put("ocr_available", hasMlKitOcr || modelManager.hasTask("ocr"))
            put("mlkit_ocr_available", hasMlKitOcr)
            put("clip_available", modelManager.hasTask("clip"))
            put("cifar10_available", modelManager.hasTask("cifar10"))
            put("mnist_available", modelManager.hasTask("mnist"))
            put("diffusion_available", modelManager.hasTask("diffusion"))
            put("diffusion_runtime_available", false)
            put("diffusion_loadable", probes.any { it.model.task == "diffusion" && it.status == "loadable" })
            put(
                "message",
                if (hasMlKitOcr && hasLoadableModel) {
                    "ML Kit OCR is ready, and at least one local ONNX/TFLite model is loadable."
                } else if (hasMlKitOcr) {
                    "ML Kit OCR is ready. Add ONNX/TFLite models under vision/models for CLIP, MNIST, CIFAR10, or custom OCR."
                } else if (hasLoadableModel) {
                    "Vision backend can load at least one local ONNX/TFLite model."
                } else {
                    "Add a valid ONNX/TFLite model under vision/models. Current files are missing or failed backend load checks."
                }
            )
            put("models", modelManager.toJson())
            put(
                "probes",
                JSONArray().apply {
                    probes.forEach { put(it.toJson()) }
                }
            )
            put(
                "candidates",
                JSONArray().apply {
                    candidates().forEach { put(it.toJson()) }
                }
            )
        }
    }

    fun generateDiffusion(prompt: String, width: Int, height: Int, steps: Int, seed: Long): JSONObject {
        val normalizedPrompt = prompt.trim().ifBlank { "a small mobilecore smoke image" }
        val requestedWidth = width.takeIf { it > 0 }?.coerceIn(64, 1024) ?: 512
        val requestedHeight = height.takeIf { it > 0 }?.coerceIn(64, 1024) ?: 512
        val requestedSteps = steps.takeIf { it > 0 }?.coerceIn(1, 50) ?: 4
        val model = modelManager.firstModelForTask("diffusion")
            ?: return missingDiffusionModel(normalizedPrompt, requestedWidth, requestedHeight, requestedSteps, seed)
        val probe = probeModel(model)
        if (probe.status == "unsupported_backend") {
            return JSONObject().apply {
                put("object", "vision.diffusion")
                put("status", "runtime_not_installed")
                put("task", "diffusion")
                put("prompt", normalizedPrompt)
                put("width", requestedWidth)
                put("height", requestedHeight)
                put("steps", requestedSteps)
                put("seed", seed)
                put("backend", probe.backend)
                put("model", probe.toJson())
                put(
                    "message",
                    "Diffusion model files are present, but MNN-Diffusion or an ONNX diffusion pipeline is not integrated in this RC."
                )
                put("models", modelManager.toJson())
            }
        }
        if (probe.status != "loadable") {
            return JSONObject().apply {
                put("object", "vision.diffusion")
                put("status", "model_load_error")
                put("task", "diffusion")
                put("prompt", normalizedPrompt)
                put("width", requestedWidth)
                put("height", requestedHeight)
                put("steps", requestedSteps)
                put("seed", seed)
                put("backend", probe.backend)
                put("model", probe.toJson())
                put("message", "Diffusion model file was found but failed backend load check: ${probe.reason}")
                put("models", modelManager.toJson())
            }
        }
        return JSONObject().apply {
            put("object", "vision.diffusion")
            put("status", "pipeline_not_implemented")
            put("task", "diffusion")
            put("prompt", normalizedPrompt)
            put("width", requestedWidth)
            put("height", requestedHeight)
            put("steps", requestedSteps)
            put("seed", seed)
            put("backend", probe.backend)
            put("model", probe.toJson())
            put(
                "message",
                "The model backend loads, but text-to-image tokenization, scheduler, UNet loop, and VAE decode are not implemented in this RC."
            )
            put("models", modelManager.toJson())
        }
    }

    fun ocr(imageName: String, imagePath: String): JSONObject {
        val imageInfo = inspectImage(imageName, imagePath)
        if (!imageInfo.valid) {
            return invalidImage("vision.ocr", imageInfo)
        }
        val mlKitResult = runMlKitOcr(imageInfo, imagePath)
        if (mlKitResult.optString("status") == "ok") {
            return mlKitResult
        }
        if (!modelManager.hasTask("ocr")) {
            return mlKitResult.apply {
                put("fallback", missingModel("vision.ocr", imageInfo, "ocr"))
            }
        }
        val model = modelManager.firstModelForTask("ocr")
        val probe = model?.let { probeModel(it) }
        if (probe == null || probe.status != "loadable") {
            return mlKitResult.apply {
                put("fallback", modelLoadError("vision.ocr", imageInfo, probe, "ocr"))
            }
        }
        return mlKitResult.apply {
            put("fallback", JSONObject().apply {
                put("object", "vision.ocr")
                put("status", "model_loadable")
                put("image_name", imageInfo.name)
                put("image", imageInfo.toJson())
                put("task", "ocr")
                put("backend", probe.backend)
                put("model", probe.toJson())
                put("message", "Custom OCR model is loadable; ML Kit remains the active OCR backend in this RC.")
            })
        }
    }

    private fun runMlKitOcr(imageInfo: VisionImageInfo, imagePath: String): JSONObject {
        val started = System.currentTimeMillis()
        val bitmap = BitmapFactory.decodeFile(imagePath)
            ?: return invalidImage("vision.ocr", imageInfo)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                15,
                TimeUnit.SECONDS
            )
            buildMlKitOcrResult(imageInfo, result, System.currentTimeMillis() - started)
        } catch (error: Exception) {
            JSONObject().apply {
                put("object", "vision.ocr")
                put("status", "ocr_error")
                put("image_name", imageInfo.name)
                put("image", imageInfo.toJson())
                put("task", "ocr")
                put("backend", "mlkit-text-recognition")
                put("text", "")
                put("elapsed_ms", System.currentTimeMillis() - started)
                put("message", "ML Kit OCR failed: ${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            recognizer.close()
        }
    }

    private fun buildMlKitOcrResult(imageInfo: VisionImageInfo, result: Text, elapsedMs: Long): JSONObject {
        return JSONObject().apply {
            put("object", "vision.ocr")
            put("status", "ok")
            put("image_name", imageInfo.name)
            put("image", imageInfo.toJson())
            put("task", "ocr")
            put("backend", "mlkit-text-recognition")
            put("text", result.text)
            put("elapsed_ms", elapsedMs)
            put(
                "blocks",
                JSONArray().apply {
                    result.textBlocks.forEach { block ->
                        put(JSONObject().apply {
                            put("text", block.text)
                            put(
                                "lines",
                                JSONArray().apply {
                                    block.lines.forEach { line ->
                                        put(JSONObject().apply {
                                            put("text", line.text)
                                            put(
                                                "elements",
                                                JSONArray().apply {
                                                    line.elements.forEach { element ->
                                                        put(element.text)
                                                    }
                                                }
                                            )
                                        })
                                    }
                                }
                            )
                        })
                    }
                }
            )
            put("message", "ML Kit on-device OCR completed.")
        }
    }

    fun classify(imageName: String, imagePath: String, dataset: String): JSONObject {
        val imageInfo = inspectImage(imageName, imagePath)
        if (!imageInfo.valid) {
            return invalidImage("vision.classification", imageInfo).apply {
                put("dataset", dataset)
            }
        }
        val normalizedDataset = when (dataset.lowercase()) {
            "mnist" -> "mnist"
            "cifar10" -> "cifar10"
            else -> "clip"
        }
        if (normalizedDataset == "cifar10" && !modelManager.hasTask("cifar10") && !modelManager.hasTask("clip")) {
            return missingModel("vision.classification", imageInfo, "cifar10").apply {
                put("dataset", "cifar10")
                put("message", "Add a CIFAR10 .tflite classifier or a CLIP ONNX model to vision/models before running CIFAR10 classification.")
            }
        }
        val requiredTask = when (normalizedDataset) {
            "mnist" -> "mnist"
            "cifar10" -> if (modelManager.hasTask("cifar10")) "cifar10" else "clip"
            else -> "clip"
        }
        if (!modelManager.hasTask(requiredTask)) {
            return missingModel("vision.classification", imageInfo, requiredTask).apply {
                put("dataset", normalizedDataset)
            }
        }
        val model = modelManager.firstModelForTask(requiredTask)
        val probe = model?.let { probeModel(it) }
        if (probe == null || probe.status != "loadable") {
            return modelLoadError("vision.classification", imageInfo, probe, requiredTask).apply {
                put("dataset", normalizedDataset)
            }
        }
        if (normalizedDataset == "mnist") {
            return runMnistTfliteClassification(imageInfo, model, probe, imagePath)
        }
        if (normalizedDataset == "cifar10" && requiredTask == "cifar10") {
            return runCifar10TfliteClassification(imageInfo, model, probe, imagePath)
        }
        if (normalizedDataset == "cifar10" && requiredTask == "clip") {
            return runClipCifar10ZeroShot(imageInfo, model, probe, imagePath)
        }
        return JSONObject().apply {
            put("object", "vision.classification")
            put("status", "postprocess_not_implemented")
            put("image_name", imageName)
            put("image", imageInfo.toJson())
            put("task", requiredTask)
            put("dataset", normalizedDataset)
            put("backend", probe.backend)
            put("model", probe.toJson())
            put("label", "")
            put("confidence", 0.0)
            put("elapsed_ms", probe.elapsedMs)
            put(
                "message",
                when (normalizedDataset) {
                    "mnist" -> "MNIST model loaded successfully; image normalization and label mapping still need to be implemented."
                    "cifar10" -> "CLIP model loaded successfully, but CIFAR10 text embeddings were not available."
                    else -> "CLIP image encoder loaded successfully. Add text embeddings sidecars for zero-shot datasets."
                }
            )
        }
    }

    private fun probeModel(model: VisionModelFile): VisionBackendProbeResult {
        val started = System.currentTimeMillis()
        return when (model.backend.lowercase(Locale.US)) {
            "tflite" -> probeTfliteModel(model, started)
            "onnxruntime-mobile" -> probeOnnxModel(model, started)
            else -> VisionBackendProbeResult(
                model = model,
                status = "unsupported_backend",
                reason = "${model.backend} loading is not implemented in this RC.",
                inputSummary = emptyList(),
                outputSummary = emptyList(),
                elapsedMs = System.currentTimeMillis() - started
            )
        }
    }

    private fun probeOnnxModel(model: VisionModelFile, started: Long): VisionBackendProbeResult {
        return runCatching {
            val environment = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                environment.createSession(model.file.absolutePath, options).use { session ->
                    VisionBackendProbeResult(
                        model = model,
                        status = "loadable",
                        reason = "ONNX Runtime loaded the model session.",
                        inputSummary = session.inputInfo.keys.toList(),
                        outputSummary = session.outputInfo.keys.toList(),
                        elapsedMs = System.currentTimeMillis() - started
                    )
                }
            }
        }.getOrElse { error ->
            VisionBackendProbeResult(
                model = model,
                status = "load_error",
                reason = sanitizeBackendError(error.message ?: error.javaClass.simpleName, model),
                inputSummary = emptyList(),
                outputSummary = emptyList(),
                elapsedMs = System.currentTimeMillis() - started
            )
        }
    }

    private fun probeTfliteModel(model: VisionModelFile, started: Long): VisionBackendProbeResult {
        return runCatching {
            Interpreter(model.file, Interpreter.Options()).use { interpreter ->
                val inputs = (0 until interpreter.inputTensorCount).map { index ->
                    val tensor = interpreter.getInputTensor(index)
                    "${tensor.name()} ${tensor.dataType()} ${tensor.shape().joinToString(prefix = "[", postfix = "]")}"
                }
                val outputs = (0 until interpreter.outputTensorCount).map { index ->
                    val tensor = interpreter.getOutputTensor(index)
                    "${tensor.name()} ${tensor.dataType()} ${tensor.shape().joinToString(prefix = "[", postfix = "]")}"
                }
                VisionBackendProbeResult(
                    model = model,
                    status = "loadable",
                    reason = "TensorFlow Lite loaded the model interpreter.",
                    inputSummary = inputs,
                    outputSummary = outputs,
                    elapsedMs = System.currentTimeMillis() - started
                )
            }
        }.getOrElse { error ->
            VisionBackendProbeResult(
                model = model,
                status = "load_error",
                reason = sanitizeBackendError(error.message ?: error.javaClass.simpleName, model),
                inputSummary = emptyList(),
                outputSummary = emptyList(),
                elapsedMs = System.currentTimeMillis() - started
            )
        }
    }

    private fun runMnistTfliteClassification(
        imageInfo: VisionImageInfo,
        model: VisionModelFile,
        probe: VisionBackendProbeResult,
        imagePath: String
    ): JSONObject {
        val started = System.currentTimeMillis()
        if (model.backend != "tflite") {
            return unsupportedVisionModel(
                objectName = "vision.classification",
                imageInfo = imageInfo,
                probe = probe,
                dataset = "mnist",
                message = "MNIST demo currently requires a .tflite small CNN model."
            )
        }
        return runCatching {
            Interpreter(model.file, Interpreter.Options()).use { interpreter ->
                val inputTensor = interpreter.getInputTensor(0)
                val outputTensor = interpreter.getOutputTensor(0)
                val inputShape = inputTensor.shape()
                val outputShape = outputTensor.shape()
                if (inputShape.product() != 28 * 28 || outputShape.product() != 10) {
                    return unsupportedVisionModel(
                        objectName = "vision.classification",
                        imageInfo = imageInfo,
                        probe = probe,
                        dataset = "mnist",
                        message = "Expected MNIST TFLite shape input=784 values and output=10 classes; got input=${inputShape.shapeText()} output=${outputShape.shapeText()}."
                    )
                }
                val bitmap = BitmapFactory.decodeFile(imagePath)
                    ?: return invalidImage("vision.classification", imageInfo).apply {
                        put("dataset", "mnist")
                    }
                val input = mnistInputBuffer(bitmap, inputTensor.dataType())
                val output = ByteBuffer
                    .allocateDirect(outputShape.product() * outputTensor.dataType().byteSize())
                    .order(ByteOrder.nativeOrder())
                interpreter.run(input, output)
                val scores = readScores(output, outputTensor.dataType(), outputShape.product())
                val normalized = normalizedScores(scores)
                val bestIndex = normalized.indices.maxByOrNull { normalized[it] } ?: 0
                JSONObject().apply {
                    put("object", "vision.classification")
                    put("status", "ok")
                    put("image_name", imageInfo.name)
                    put("image", imageInfo.toJson())
                    put("task", "mnist")
                    put("dataset", "mnist")
                    put("backend", probe.backend)
                    put("model", probe.toJson())
                    put("label", bestIndex.toString())
                    put("confidence", normalized.getOrElse(bestIndex) { 0.0 })
                    put("elapsed_ms", System.currentTimeMillis() - started)
                    put(
                        "scores",
                        JSONArray().apply {
                            normalized.forEachIndexed { index, value ->
                                put(JSONObject().apply {
                                    put("label", index.toString())
                                    put("confidence", value)
                                })
                            }
                        }
                    )
                    put("message", "MNIST TFLite inference completed on-device.")
                }
            }
        }.getOrElse { error ->
            JSONObject().apply {
                put("object", "vision.classification")
                put("status", "inference_error")
                put("image_name", imageInfo.name)
                put("image", imageInfo.toJson())
                put("task", "mnist")
                put("dataset", "mnist")
                put("backend", probe.backend)
                put("model", probe.toJson())
                put("elapsed_ms", System.currentTimeMillis() - started)
                put("message", sanitizeBackendError(error.message ?: error.javaClass.simpleName, model))
            }
        }
    }

    private fun runCifar10TfliteClassification(
        imageInfo: VisionImageInfo,
        model: VisionModelFile,
        probe: VisionBackendProbeResult,
        imagePath: String
    ): JSONObject {
        val started = System.currentTimeMillis()
        if (model.backend != "tflite") {
            return unsupportedVisionModel(
                objectName = "vision.classification",
                imageInfo = imageInfo,
                probe = probe,
                dataset = "cifar10",
                message = "CIFAR10 direct classification currently requires a .tflite image classifier."
            )
        }
        return runCatching {
            Interpreter(model.file, Interpreter.Options()).use { interpreter ->
                val inputTensor = interpreter.getInputTensor(0)
                val outputTensor = interpreter.getOutputTensor(0)
                val inputShape = inputTensor.shape()
                val outputShape = outputTensor.shape()
                val imageSize = cifarInputImageSize(inputShape)
                    ?: return unsupportedVisionModel(
                        objectName = "vision.classification",
                        imageInfo = imageInfo,
                        probe = probe,
                        dataset = "cifar10",
                        message = "Expected CIFAR10 image input with 3 channels, got input=${inputShape.shapeText()}."
                    )
                if (outputShape.product() != CIFAR10_LABELS.size) {
                    return unsupportedVisionModel(
                        objectName = "vision.classification",
                        imageInfo = imageInfo,
                        probe = probe,
                        dataset = "cifar10",
                        message = "Expected CIFAR10 output=10 classes; got output=${outputShape.shapeText()}."
                    )
                }
                val bitmap = BitmapFactory.decodeFile(imagePath)
                    ?: return invalidImage("vision.classification", imageInfo).apply {
                        put("dataset", "cifar10")
                    }
                val input = rgbImageInputBuffer(bitmap, imageSize, inputTensor.dataType())
                val output = ByteBuffer
                    .allocateDirect(outputShape.product() * outputTensor.dataType().byteSize())
                    .order(ByteOrder.nativeOrder())
                interpreter.run(input, output)
                val scores = readScores(output, outputTensor.dataType(), outputShape.product())
                val normalized = normalizedScores(scores)
                val bestIndex = normalized.indices.maxByOrNull { normalized[it] } ?: 0
                val label = CIFAR10_LABELS.getOrElse(bestIndex) { bestIndex.toString() }
                JSONObject().apply {
                    put("object", "vision.classification")
                    put("status", "ok")
                    put("image_name", imageInfo.name)
                    put("image", imageInfo.toJson())
                    put("task", "cifar10")
                    put("dataset", "cifar10")
                    put("backend", probe.backend)
                    put("model", probe.toJson())
                    put("label", label)
                    put("confidence", normalized.getOrElse(bestIndex) { 0.0 })
                    put("elapsed_ms", System.currentTimeMillis() - started)
                    put(
                        "scores",
                        JSONArray().apply {
                            normalized.forEachIndexed { index, value ->
                                put(JSONObject().apply {
                                    put("label", CIFAR10_LABELS.getOrElse(index) { index.toString() })
                                    put("confidence", value)
                                })
                            }
                        }
                    )
                    put("message", "CIFAR10 TFLite inference completed on-device.")
                }
            }
        }.getOrElse { error ->
            JSONObject().apply {
                put("object", "vision.classification")
                put("status", "inference_error")
                put("image_name", imageInfo.name)
                put("image", imageInfo.toJson())
                put("task", "cifar10")
                put("dataset", "cifar10")
                put("backend", probe.backend)
                put("model", probe.toJson())
                put("elapsed_ms", System.currentTimeMillis() - started)
                put("message", sanitizeBackendError(error.message ?: error.javaClass.simpleName, model))
            }
        }
    }

    private fun runClipCifar10ZeroShot(
        imageInfo: VisionImageInfo,
        model: VisionModelFile,
        probe: VisionBackendProbeResult,
        imagePath: String
    ): JSONObject {
        val started = System.currentTimeMillis()
        if (model.backend != "onnxruntime-mobile") {
            return unsupportedVisionModel(
                objectName = "vision.classification",
                imageInfo = imageInfo,
                probe = probe,
                dataset = "cifar10",
                message = "CLIP zero-shot currently requires an ONNX image encoder."
            )
        }
        val embeddings = findClipTextEmbeddings(model, dataset = "cifar10")
            ?: return missingClipEmbeddings(imageInfo, probe, started)
        if (embeddings.isEmpty()) {
            return missingClipEmbeddings(imageInfo, probe, started)
        }
        val bitmap = BitmapFactory.decodeFile(imagePath)
            ?: return invalidImage("vision.classification", imageInfo).apply {
                put("dataset", "cifar10")
            }

        return runCatching {
            val environment = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                environment.createSession(model.file.absolutePath, options).use { session ->
                    val inputName = session.inputInfo.keys.firstOrNull()
                        ?: error("CLIP model has no input tensor.")
                    val tensorInfo = session.inputInfo[inputName]?.info as? TensorInfo
                        ?: error("CLIP input tensor info is unavailable.")
                    val inputPlan = clipInputPlan(tensorInfo.shape)
                        ?: return unsupportedVisionModel(
                            objectName = "vision.classification",
                            imageInfo = imageInfo,
                            probe = probe,
                            dataset = "cifar10",
                            message = "Expected CLIP image input shaped [1,3,H,W] or [1,H,W,3]; got ${tensorInfo.shape.joinToString(prefix = "[", postfix = "]")}."
                        )
                    val input = clipImageInput(bitmap, inputPlan)
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values), input.shape).use { tensor ->
                        session.run(mapOf(inputName to tensor)).use { result ->
                            val imageEmbedding = flattenFloats(result[0].value)
                            if (imageEmbedding.isEmpty()) {
                                error("CLIP output embedding is empty.")
                            }
                            val ranked = embeddings
                                .map { embedding ->
                                    embedding.label to cosineSimilarity(imageEmbedding, embedding.values)
                                }
                                .sortedByDescending { it.second }
                            val best = ranked.firstOrNull()
                            JSONObject().apply {
                                put("object", "vision.classification")
                                put("status", "ok")
                                put("image_name", imageInfo.name)
                                put("image", imageInfo.toJson())
                                put("task", "clip")
                                put("dataset", "cifar10")
                                put("backend", probe.backend)
                                put("model", probe.toJson())
                                put("label", best?.first.orEmpty())
                                put("confidence", best?.second ?: 0.0)
                                put("elapsed_ms", System.currentTimeMillis() - started)
                                put("sidecar", findClipTextEmbeddingFile(model, "cifar10")?.name.orEmpty())
                                put(
                                    "scores",
                                    JSONArray().apply {
                                        ranked.take(10).forEach { (label, score) ->
                                            put(JSONObject().apply {
                                                put("label", label)
                                                put("confidence", score)
                                            })
                                        }
                                    }
                                )
                                put("message", "CLIP ONNX image encoder zero-shot ranking completed with CIFAR10 text embeddings.")
                            }
                        }
                    }
                }
            }
        }.getOrElse { error ->
            JSONObject().apply {
                put("object", "vision.classification")
                put("status", "inference_error")
                put("image_name", imageInfo.name)
                put("image", imageInfo.toJson())
                put("task", "clip")
                put("dataset", "cifar10")
                put("backend", probe.backend)
                put("model", probe.toJson())
                put("elapsed_ms", System.currentTimeMillis() - started)
                put("message", sanitizeBackendError(error.message ?: error.javaClass.simpleName, model))
            }
        }
    }

    private fun missingClipEmbeddings(
        imageInfo: VisionImageInfo,
        probe: VisionBackendProbeResult,
        started: Long
    ): JSONObject {
        return JSONObject().apply {
            put("object", "vision.classification")
            put("status", "text_embeddings_missing")
            put("image_name", imageInfo.name)
            put("image", imageInfo.toJson())
            put("task", "clip")
            put("dataset", "cifar10")
            put("backend", probe.backend)
            put("model", probe.toJson())
            put("elapsed_ms", System.currentTimeMillis() - started)
            put("message", "Add cifar10-text-embeddings.json beside the CLIP ONNX model. Format: [{\"label\":\"cat\",\"embedding\":[...]}].")
        }
    }

    private fun unsupportedVisionModel(
        objectName: String,
        imageInfo: VisionImageInfo,
        probe: VisionBackendProbeResult,
        dataset: String,
        message: String
    ): JSONObject {
        return JSONObject().apply {
            put("object", objectName)
            put("status", "unsupported_model_shape")
            put("image_name", imageInfo.name)
            put("image", imageInfo.toJson())
            put("task", probe.model.task)
            put("dataset", dataset)
            put("backend", probe.backend)
            put("model", probe.toJson())
            put("message", message)
        }
    }

    private fun mnistInputBuffer(bitmap: Bitmap, dataType: DataType): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, 28, 28, true)
        val buffer = ByteBuffer
            .allocateDirect(28 * 28 * dataType.byteSize())
            .order(ByteOrder.nativeOrder())
        for (y in 0 until 28) {
            for (x in 0 until 28) {
                val pixel = resized.getPixel(x, y)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                val gray = (red * 0.299f + green * 0.587f + blue * 0.114f).coerceIn(0f, 255f)
                val ink = ((255f - gray) / 255f).coerceIn(0f, 1f)
                when (dataType) {
                        DataType.FLOAT32 -> buffer.putFloat(ink)
                        DataType.UINT8 -> buffer.put((ink * 255f).toInt().coerceIn(0, 255).toByte())
                        DataType.INT8 -> buffer.put(((ink * 255f).toInt().coerceIn(0, 255) - 128).toByte())
                        else -> throw IllegalArgumentException("Unsupported MNIST input type: $dataType")
                    }
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun rgbImageInputBuffer(bitmap: Bitmap, imageSize: Int, dataType: DataType): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, imageSize, imageSize, true)
        val buffer = ByteBuffer
            .allocateDirect(imageSize * imageSize * 3 * dataType.byteSize())
            .order(ByteOrder.nativeOrder())
        for (y in 0 until imageSize) {
            for (x in 0 until imageSize) {
                val pixel = resized.getPixel(x, y)
                val channels = intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                channels.forEach { channel ->
                    when (dataType) {
                        DataType.FLOAT32 -> buffer.putFloat((channel / 255f).coerceIn(0f, 1f))
                        DataType.UINT8 -> buffer.put(channel.coerceIn(0, 255).toByte())
                        DataType.INT8 -> buffer.put((channel.coerceIn(0, 255) - 128).toByte())
                        else -> throw IllegalArgumentException("Unsupported RGB input type: $dataType")
                    }
                }
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun clipInputPlan(shape: LongArray): ClipInputPlan? {
        val normalized = shape.mapIndexed { index, value ->
            when {
                value > 0L -> value
                index == 0 -> 1L
                else -> -1L
            }
        }.toLongArray()
        if (normalized.size != 4) return null
        val nchw = normalized[1] == 3L
        val nhwc = normalized[3] == 3L
        if (!nchw && !nhwc) return null
        val imageSize = if (nchw) normalized[2].takeIf { it > 0L } else normalized[1].takeIf { it > 0L }
        val resolvedSize = imageSize?.toInt() ?: 224
        val resolvedShape = if (nchw) {
            longArrayOf(1L, 3L, resolvedSize.toLong(), resolvedSize.toLong())
        } else {
            longArrayOf(1L, resolvedSize.toLong(), resolvedSize.toLong(), 3L)
        }
        return ClipInputPlan(shape = resolvedShape, imageSize = resolvedSize, nchw = nchw)
    }

    private fun clipImageInput(bitmap: Bitmap, plan: ClipInputPlan): ClipInput {
        val resized = Bitmap.createScaledBitmap(bitmap, plan.imageSize, plan.imageSize, true)
        val values = FloatArray(plan.imageSize * plan.imageSize * 3)
        val means = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val stds = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        fun normalized(channel: Int, value: Int): Float {
            return ((value / 255f) - means[channel]) / stds[channel]
        }
        if (plan.nchw) {
            for (c in 0 until 3) {
                for (y in 0 until plan.imageSize) {
                    for (x in 0 until plan.imageSize) {
                        val pixel = resized.getPixel(x, y)
                        val value = when (c) {
                            0 -> Color.red(pixel)
                            1 -> Color.green(pixel)
                            else -> Color.blue(pixel)
                        }
                        values[c * plan.imageSize * plan.imageSize + y * plan.imageSize + x] = normalized(c, value)
                    }
                }
            }
        } else {
            var index = 0
            for (y in 0 until plan.imageSize) {
                for (x in 0 until plan.imageSize) {
                    val pixel = resized.getPixel(x, y)
                    values[index++] = normalized(0, Color.red(pixel))
                    values[index++] = normalized(1, Color.green(pixel))
                    values[index++] = normalized(2, Color.blue(pixel))
                }
            }
        }
        return ClipInput(values = values, shape = plan.shape)
    }

    private fun findClipTextEmbeddings(model: VisionModelFile, dataset: String): List<TextEmbedding>? {
        val file = findClipTextEmbeddingFile(model, dataset) ?: return null
        val jsonText = runCatching { file.readText() }.getOrNull() ?: return null
        val entries = runCatching {
            if (jsonText.trim().startsWith("[")) {
                parseEmbeddingArray(JSONArray(jsonText))
            } else {
                val root = JSONObject(jsonText)
                val array = root.optJSONArray("embeddings")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("labels")
                    ?: JSONArray()
                parseEmbeddingArray(array)
            }
        }.getOrNull() ?: return null
        return entries.takeIf { it.isNotEmpty() }
    }

    private fun findClipTextEmbeddingFile(model: VisionModelFile, dataset: String): File? {
        val dir = model.file.parentFile ?: return null
        val base = model.file.nameWithoutExtension
        val candidates = listOf(
            "$base-$dataset-embeddings.json",
            "$base-${dataset}-text-embeddings.json",
            "$dataset-text-embeddings.json",
            "clip-$dataset-embeddings.json",
            "clip-${dataset}-text-embeddings.json"
        )
        return candidates
            .map { File(dir, it) }
            .firstOrNull { it.isFile && it.length() > 0L }
    }

    private fun parseEmbeddingArray(array: JSONArray): List<TextEmbedding> {
        val embeddings = mutableListOf<TextEmbedding>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val label = item.optString("label", item.optString("text", ""))
            if (label.isBlank()) continue
            val embeddingArray = item.optJSONArray("embedding")
                ?: item.optJSONArray("vector")
                ?: item.optJSONArray("values")
                ?: continue
            val values = FloatArray(embeddingArray.length()) { valueIndex ->
                embeddingArray.optDouble(valueIndex, 0.0).toFloat()
            }
            if (values.isNotEmpty()) {
                embeddings.add(TextEmbedding(label = label, values = values))
            }
        }
        return embeddings
    }

    private fun flattenFloats(value: Any?): FloatArray {
        val output = mutableListOf<Float>()
        fun visit(node: Any?) {
            when (node) {
                is FloatArray -> node.forEach { output.add(it) }
                is DoubleArray -> node.forEach { output.add(it.toFloat()) }
                is IntArray -> node.forEach { output.add(it.toFloat()) }
                is LongArray -> node.forEach { output.add(it.toFloat()) }
                is Array<*> -> node.forEach { visit(it) }
                is Float -> output.add(node)
                is Double -> output.add(node.toFloat())
                is Int -> output.add(node.toFloat())
                is Long -> output.add(node.toFloat())
            }
        }
        visit(value)
        return output.toFloatArray()
    }

    private fun cosineSimilarity(left: FloatArray, right: FloatArray): Double {
        val count = minOf(left.size, right.size)
        if (count <= 0) return 0.0
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in 0 until count) {
            val l = left[index].toDouble()
            val r = right[index].toDouble()
            dot += l * r
            leftNorm += l * l
            rightNorm += r * r
        }
        val denom = sqrt(leftNorm) * sqrt(rightNorm)
        return if (denom <= 0.0) 0.0 else ((dot / denom) + 1.0) / 2.0
    }

    private fun readScores(buffer: ByteBuffer, dataType: DataType, count: Int): FloatArray {
        buffer.rewind()
        return FloatArray(count) {
            when (dataType) {
                DataType.FLOAT32 -> buffer.float
                DataType.UINT8 -> (buffer.get().toInt() and 0xff).toFloat()
                DataType.INT8 -> buffer.get().toFloat()
                else -> throw IllegalArgumentException("Unsupported MNIST output type: $dataType")
            }
        }
    }

    private fun normalizedScores(scores: FloatArray): List<Double> {
        if (scores.isEmpty()) return emptyList()
        val min = scores.minOrNull() ?: 0f
        val max = scores.maxOrNull() ?: 0f
        if (min >= 0f && max <= 1f) {
            val total = scores.sum().takeIf { it > 0f } ?: 1f
            return scores.map { (it / total).toDouble() }
        }
        val maxScore = max.toDouble()
        val exps = scores.map { exp((it.toDouble() - maxScore).coerceIn(-40.0, 40.0)) }
        val total = exps.sum().takeIf { it > 0.0 } ?: 1.0
        return exps.map { it / total }
    }

    private fun IntArray.product(): Int {
        return fold(1) { acc, value -> acc * value.coerceAtLeast(1) }
    }

    private fun IntArray.shapeText(): String {
        return joinToString(prefix = "[", postfix = "]")
    }

    private fun cifarInputImageSize(shape: IntArray): Int? {
        val positive = shape.filter { it > 0 }
        if (positive.size < 3) return null
        if (positive.last() == 3) {
            val width = positive[positive.size - 2]
            val height = positive[positive.size - 3]
            return width.takeIf { width == height }
        }
        if (positive.size >= 4 && positive[1] == 3) {
            val height = positive[2]
            val width = positive[3]
            return width.takeIf { width == height }
        }
        if (positive.first() == 3) {
            val width = positive.getOrNull(2) ?: return null
            val height = positive.getOrNull(1) ?: return null
            return width.takeIf { width == height }
        }
        return null
    }

    private fun sanitizeBackendError(message: String, model: VisionModelFile): String {
        return message
            .replace(model.file.absolutePath, model.fileName)
            .replace(model.file.parentFile?.absolutePath.orEmpty(), "vision/models")
    }

    private fun inspectImage(imageName: String, imagePath: String): VisionImageInfo {
        val file = File(imagePath)
        if (!file.isFile || file.length() <= 0L) {
            return VisionImageInfo(
                name = imageName,
                sizeBytes = 0L,
                width = 0,
                height = 0,
                valid = false,
                error = "Image file is missing or empty."
            )
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val valid = options.outWidth > 0 && options.outHeight > 0
        return VisionImageInfo(
            name = file.name,
            sizeBytes = file.length(),
            width = options.outWidth.coerceAtLeast(0),
            height = options.outHeight.coerceAtLeast(0),
            valid = valid,
            error = if (valid) null else "Image file could not be decoded."
        )
    }

    private fun invalidImage(objectName: String, imageInfo: VisionImageInfo): JSONObject {
        return JSONObject().apply {
            put("object", objectName)
            put("status", "invalid_image")
            put("image_name", imageInfo.name)
            put("image", imageInfo.toJson())
            put("message", imageInfo.error ?: "Invalid image.")
        }
    }

    private fun missingModel(objectName: String, imageInfo: VisionImageInfo, task: String): JSONObject {
        return JSONObject().apply {
            put("object", objectName)
            put("status", "model_missing")
            put("task", task)
            put("image_name", imageInfo.name)
            put("image", imageInfo.toJson())
            put("message", "Add a $task model file to vision/models before running this task.")
            put("models", modelManager.toJson())
        }
    }

    private fun missingDiffusionModel(prompt: String, width: Int, height: Int, steps: Int, seed: Long): JSONObject {
        return JSONObject().apply {
            put("object", "vision.diffusion")
            put("status", "model_missing")
            put("task", "diffusion")
            put("prompt", prompt)
            put("width", width)
            put("height", height)
            put("steps", steps)
            put("seed", seed)
            put(
                "message",
                "Add an MNN-Diffusion resource bundle or converted ONNX diffusion pipeline before running text-to-image generation."
            )
            put("models", modelManager.toJson())
        }
    }

    private fun modelLoadError(
        objectName: String,
        imageInfo: VisionImageInfo,
        probe: VisionBackendProbeResult?,
        task: String
    ): JSONObject {
        return JSONObject().apply {
            put("object", objectName)
            put("status", "model_load_error")
            put("task", task)
            put("image_name", imageInfo.name)
            put("image", imageInfo.toJson())
            if (probe != null) {
                put("model", probe.toJson())
                put("backend", probe.backend)
                put("message", "Model file was found but failed backend load check: ${probe.reason}")
            } else {
                put("message", "Model file was found but could not be inspected.")
            }
            put("models", modelManager.toJson())
        }
    }

    private fun candidates(): List<VisionModelCandidate> {
        return listOf(
            VisionModelCandidate(
                id = "mlkit-text-recognition",
                task = "ocr",
                backend = "mlkit",
                status = "active",
                reason = "Bundled on-device OCR path for image to text in the 0.1.2 release candidate."
            ),
            VisionModelCandidate(
                id = "rapidocr-ppocr-onnx",
                task = "ocr",
                backend = "onnxruntime-mobile",
                status = "recommended",
                reason = "Best first OCR path for Android; supports practical Chinese and English OCR."
            ),
            VisionModelCandidate(
                id = "clip-vit-b32-onnx",
                task = "clip",
                backend = "onnxruntime-mobile",
                status = "candidate",
                reason = "Good path for CIFAR10 zero-shot classification and image/text similarity."
            ),
            VisionModelCandidate(
                id = "cifar10-small-cnn-tflite",
                task = "cifar10",
                backend = "tflite",
                status = "candidate",
                reason = "Direct CIFAR10 classifier path for quick on-device image classification checks."
            ),
            VisionModelCandidate(
                id = "mnist-small-cnn-tflite",
                task = "mnist",
                backend = "tflite",
                status = "candidate",
                reason = "Small CNN is a better MNIST demo than CLIP for handwritten digits."
            ),
            VisionModelCandidate(
                id = "sd15-mnn-opencl",
                task = "diffusion",
                backend = "mnn-diffusion",
                status = "blocked",
                reason = "Stable Diffusion needs the MNN-Diffusion native pipeline and resource bundle; this RC exposes readiness only."
            )
        )
    }

    companion object {
        private val CIFAR10_LABELS = listOf(
            "airplane",
            "automobile",
            "bird",
            "cat",
            "deer",
            "dog",
            "frog",
            "horse",
            "ship",
            "truck"
        )
    }

    private data class VisionImageInfo(
        val name: String,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val valid: Boolean,
        val error: String?
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("name", name)
                put("size_bytes", sizeBytes)
                put("width", width)
                put("height", height)
                put("valid", valid)
                if (error != null) put("error", error)
            }
        }
    }

    private data class VisionBackendProbeResult(
        val model: VisionModelFile,
        val status: String,
        val reason: String,
        val inputSummary: List<String>,
        val outputSummary: List<String>,
        val elapsedMs: Long
    ) {
        val backend: String
            get() = model.backend

        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("id", model.id)
                put("task", model.task)
                put("backend", model.backend)
                put("file_name", model.fileName)
                put("size_bytes", model.sizeBytes)
                put("status", status)
                put("reason", reason)
                put("elapsed_ms", elapsedMs)
                put("inputs", JSONArray().apply { inputSummary.forEach { put(it) } })
                put("outputs", JSONArray().apply { outputSummary.forEach { put(it) } })
            }
        }
    }

    private data class ClipInputPlan(
        val shape: LongArray,
        val imageSize: Int,
        val nchw: Boolean
    )

    private data class ClipInput(
        val values: FloatArray,
        val shape: LongArray
    )

    private data class TextEmbedding(
        val label: String,
        val values: FloatArray
    )
}
