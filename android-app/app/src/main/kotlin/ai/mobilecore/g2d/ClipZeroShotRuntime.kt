package ai.mobilecore.g2d

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ClipZeroShotResult(
    val ranking: List<G2dClipCandidate<Int>>,
    val elapsedMs: Double,
)

/**
 * Persistent ONNX Runtime session for the paper's OpenAI CLIP image encoder.
 * Text embeddings are prepared once from the exact prompt template and stored
 * in a versioned sidecar, so no desktop inference is counted as device work.
 */
class ClipZeroShotRuntime(
    modelFile: File,
    sidecarFile: File,
    expectedLabels: List<String>,
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions()
    private val session = environment.createSession(modelFile.absolutePath, options)
    private val inputName = session.inputInfo.keys.single()
    private val inputShape = (session.inputInfo.getValue(inputName).info as TensorInfo).shape
    private val inputPlan = ClipInputPlan.from(inputShape)
    private val metadata = ClipSidecar.open(sidecarFile)

    init {
        require(modelFile.isFile && modelFile.length() > 0L) {
            "CLIP image encoder is missing: $modelFile"
        }
        require(metadata.labels == expectedLabels) {
            "CLIP sidecar labels do not match the Oxford-Pets official class order."
        }
        require(metadata.embeddings.map(FloatArray::size).distinct().size == 1) {
            "CLIP text embeddings must use one shared dimension."
        }
    }

    fun classify(imageFile: File): ClipZeroShotResult {
        require(imageFile.isFile) { "Oxford-Pets image is missing: $imageFile" }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(imageFile.absolutePath)) {
            "Unable to decode Oxford-Pets image: $imageFile"
        }
        val started = SystemClock.elapsedRealtimeNanos()
        val input = preprocess(bitmap, inputPlan)
        val imageEmbedding = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            inputPlan.shape,
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                flatten(result[0].value)
            }
        }
        val logits = metadata.embeddings.map { textEmbedding ->
            cosine(imageEmbedding, textEmbedding) * metadata.logitScale
        }
        val probabilities = softmax(logits)
        val ranking = probabilities.indices
            .map { index ->
                G2dClipCandidate(
                    id = index,
                    probability = probabilities[index],
                    label = metadata.labels[index],
                )
            }
            .sortedByDescending(G2dClipCandidate<Int>::probability)
        return ClipZeroShotResult(
            ranking = ranking,
            elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
        )
    }

    override fun close() {
        session.close()
        options.close()
    }

    private fun preprocess(bitmap: Bitmap, plan: ClipInputPlan): FloatArray {
        val size = plan.imageSize
        val scale = size.toDouble() / minOf(bitmap.width, bitmap.height).toDouble()
        val scaledWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(size)
        val scaledHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(size)
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        val left = ((scaledWidth - size) / 2).coerceAtLeast(0)
        val top = ((scaledHeight - size) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(scaled, left, top, size, size)
        val means = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val stds = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        val values = FloatArray(size * size * 3)
        fun channel(pixel: Int, index: Int): Float {
            val raw = when (index) {
                0 -> Color.red(pixel)
                1 -> Color.green(pixel)
                else -> Color.blue(pixel)
            }
            return (raw / 255f - means[index]) / stds[index]
        }
        if (plan.nchw) {
            for (c in 0..2) for (y in 0 until size) for (x in 0 until size) {
                values[c * size * size + y * size + x] = channel(cropped.getPixel(x, y), c)
            }
        } else {
            var index = 0
            for (y in 0 until size) for (x in 0 until size) {
                val pixel = cropped.getPixel(x, y)
                values[index++] = channel(pixel, 0)
                values[index++] = channel(pixel, 1)
                values[index++] = channel(pixel, 2)
            }
        }
        if (cropped !== scaled) cropped.recycle()
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return values
    }

    private fun flatten(value: Any?): FloatArray {
        val values = ArrayList<Float>()
        fun visit(node: Any?) {
            when (node) {
                is FloatArray -> node.forEach(values::add)
                is DoubleArray -> node.forEach { values.add(it.toFloat()) }
                is Array<*> -> node.forEach(::visit)
            }
        }
        visit(value)
        return values.toFloatArray()
    }

    private fun cosine(left: FloatArray, right: FloatArray): Double {
        require(left.size == right.size) {
            "CLIP image/text embedding dimensions differ: ${left.size} vs ${right.size}."
        }
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            val l = left[index].toDouble()
            val r = right[index].toDouble()
            dot += l * r
            leftNorm += l * l
            rightNorm += r * r
        }
        return dot / (sqrt(leftNorm) * sqrt(rightNorm)).coerceAtLeast(1e-12)
    }

    private fun softmax(logits: List<Double>): List<Double> {
        val max = logits.maxOrNull() ?: return emptyList()
        val weights = logits.map { exp(it - max) }
        val total = weights.sum().coerceAtLeast(1e-12)
        return weights.map { it / total }
    }
}

private data class ClipInputPlan(
    val shape: LongArray,
    val imageSize: Int,
    val nchw: Boolean,
) {
    companion object {
        fun from(rawShape: LongArray): ClipInputPlan {
            require(rawShape.size == 4) { "CLIP expects one rank-4 image tensor." }
            // Optimum exports CLIP with symbolic channel/height/width axes;
            // that graph still follows the canonical NCHW pixel_values ABI.
            val allImageAxesDynamic = rawShape.drop(1).all { it <= 0L }
            val nchw = rawShape[1] == 3L || allImageAxesDynamic
            val nhwc = rawShape[3] == 3L
            require(nchw || nhwc) {
                "CLIP input must be [1,3,H,W] or [1,H,W,3]."
            }
            val size = (if (nchw) rawShape[2] else rawShape[1])
                .takeIf { it > 0L }
                ?.toInt()
                ?: 224
            return ClipInputPlan(
                shape = if (nchw) longArrayOf(1, 3, size.toLong(), size.toLong())
                    else longArrayOf(1, size.toLong(), size.toLong(), 3),
                imageSize = size,
                nchw = nchw,
            )
        }
    }
}

private data class ClipSidecar(
    val labels: List<String>,
    val embeddings: List<FloatArray>,
    val logitScale: Double,
) {
    companion object {
        fun open(file: File): ClipSidecar {
            require(file.isFile && file.length() > 0L) { "CLIP sidecar is missing: $file" }
            val root = JSONObject(file.readText())
            val entries = root.getJSONArray("embeddings")
            val labels = ArrayList<String>(entries.length())
            val embeddings = ArrayList<FloatArray>(entries.length())
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                labels += item.getString("label")
                val vector = item.getJSONArray("embedding")
                embeddings += FloatArray(vector.length()) { vector.getDouble(it).toFloat() }
            }
            require(labels.isNotEmpty() && labels.distinct().size == labels.size) {
                "CLIP sidecar requires unique labels."
            }
            return ClipSidecar(
                labels = labels,
                embeddings = embeddings,
                logitScale = root.optDouble("logit_scale", 100.0),
            )
        }
    }
}
