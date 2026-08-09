package ai.mobilecore.gallery.search

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest

sealed interface GalleryRuntimeOpenResult {
    data class Ready(val runtime: OnnxClipGalleryRuntime) : GalleryRuntimeOpenResult
    data class Blocked(val failure: GallerySearchFailure) : GalleryRuntimeOpenResult
}

/** Exact artifact discovery rules shared by the Android host and deployment scripts. */
data class GalleryClipArtifactSet(
    val imageEncoder: File,
    val textEncoder: File,
    val tokenizerDirectory: File,
) {
    companion object {
        const val IMAGE_ENCODER_FILE = "openai-clip-vit-b16-image.onnx"
        const val TEXT_ENCODER_FILE = "openai-clip-vit-b16-text.onnx"

        fun discover(modelsDirectory: File, tokenizerDirectory: File = modelsDirectory): GalleryClipArtifactSet =
            GalleryClipArtifactSet(
                imageEncoder = File(modelsDirectory, IMAGE_ENCODER_FILE),
                textEncoder = File(modelsDirectory, TEXT_ENCODER_FILE),
                tokenizerDirectory = tokenizerDirectory,
            )
    }
}

/** Real ONNX Runtime dual encoder; fixed Oxford label sidecars are never used for user queries. */
class OnnxClipGalleryRuntime private constructor(
    private val environment: OrtEnvironment,
    private val imageOptions: OrtSession.SessionOptions,
    private val textOptions: OrtSession.SessionOptions,
    private val imageSession: OrtSession,
    private val textSession: OrtSession,
    private val tokenizer: ClipBpeTokenizer,
    override val descriptor: GalleryClipRuntimeDescriptor,
    private val imageInputName: String,
    private val imageOutputName: String,
    private val textOutputName: String,
    private val imagePlan: ImageInputPlan,
) : GalleryClipRuntime {
    private val lock = Any()
    @Volatile private var closed = false

    override fun embedImage(
        photo: GalleryPhoto,
        mediaReader: GalleryMediaReader,
    ): NormalizedEmbedding = synchronized(lock) {
        checkOpen()
        val input = GalleryImageDecodeSafety.guard {
            val bitmap = decodeSampledBitmap(photo, mediaReader, imagePlan.imageSize)
                ?: throw imageDecodeFailure(null)
            preprocess(bitmap, imagePlan)
        }
        try {
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), imagePlan.shape).use { tensor ->
                imageSession.run(mapOf(imageInputName to tensor)).use { result ->
                    NormalizedEmbedding.from(flatten(result.get(imageOutputName).orElse(result[0]).value))
                }
            }
        } catch (error: GallerySearchException) {
            throw error
        } catch (error: Exception) {
            throw GallerySearchException(
                GallerySearchFailure(
                    GallerySearchFailureCode.IMAGE_ENCODER_UNAVAILABLE,
                    "The local CLIP image encoder failed.",
                    retryable = true,
                ),
                error,
            )
        }
    }

    override fun embedText(query: String): NormalizedEmbedding = synchronized(lock) {
        checkOpen()
        val tokens = try {
            tokenizer.tokenize(query)
        } catch (error: Exception) {
            throw GallerySearchException(
                GallerySearchFailure(
                    GallerySearchFailureCode.TEXT_TOKENIZER_UNAVAILABLE,
                    "The local CLIP tokenizer could not encode this query.",
                    retryable = false,
                ),
                error,
            )
        }
        val shape = longArrayOf(1, tokens.inputIds.size.toLong())
        val tensors = linkedMapOf<String, OnnxTensor>()
        try {
            tensors["input_ids"] = OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(tokens.inputIds),
                shape,
            )
            if ("attention_mask" in textSession.inputInfo) {
                tensors["attention_mask"] = OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(tokens.attentionMask),
                    shape,
                )
            }
            textSession.run(tensors).use { result ->
                NormalizedEmbedding.from(flatten(result.get(textOutputName).orElse(result[0]).value))
            }
        } catch (error: GallerySearchException) {
            throw error
        } catch (error: Exception) {
            throw GallerySearchException(
                GallerySearchFailure(
                    GallerySearchFailureCode.TEXT_ENCODER_UNAVAILABLE,
                    "The local CLIP text encoder failed.",
                    retryable = true,
                ),
                error,
            )
        } finally {
            tensors.values.forEach(OnnxTensor::close)
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            imageSession.close()
            textSession.close()
            imageOptions.close()
            textOptions.close()
        }
    }

    private fun checkOpen() {
        if (closed) {
            throw GallerySearchException(
                GallerySearchFailure(
                    GallerySearchFailureCode.MODEL_ABI_MISMATCH,
                    "The CLIP runtime is already closed.",
                    retryable = true,
                ),
            )
        }
    }

    companion object {
        fun open(artifacts: GalleryClipArtifactSet): GalleryRuntimeOpenResult {
            if (!artifacts.imageEncoder.isFile) return blocked(
                GallerySearchFailureCode.IMAGE_ENCODER_UNAVAILABLE,
                "CLIP image encoder is not installed.",
            )
            if (!artifacts.textEncoder.isFile) return blocked(
                GallerySearchFailureCode.TEXT_ENCODER_UNAVAILABLE,
                "CLIP text encoder is not installed; Oxford fixed-label embeddings cannot replace it.",
            )
            val tokenizerFiles = listOf("vocab.json", "merges.txt", "tokenizer_config.json")
                .map { File(artifacts.tokenizerDirectory, it) }
            if (tokenizerFiles.any { !it.isFile }) return blocked(
                GallerySearchFailureCode.TEXT_TOKENIZER_UNAVAILABLE,
                "CLIP tokenizer artifacts are not installed.",
            )
            val tokenizer = try {
                ClipBpeTokenizer.open(artifacts.tokenizerDirectory)
            } catch (_: OutOfMemoryError) {
                return GalleryRuntimeOpenResult.Blocked(GalleryRuntimeOpenFailures.outOfMemory())
            } catch (_: StackOverflowError) {
                return blocked(
                    GallerySearchFailureCode.TEXT_TOKENIZER_UNAVAILABLE,
                    "CLIP tokenizer artifacts exceed the audited parser budget.",
                    retryable = false,
                )
            } catch (_: Exception) {
                return blocked(
                    GallerySearchFailureCode.TEXT_TOKENIZER_UNAVAILABLE,
                    "CLIP tokenizer artifacts are invalid or exceed the audited budget.",
                    retryable = false,
                )
            }

            var imageOptions: OrtSession.SessionOptions? = null
            var textOptions: OrtSession.SessionOptions? = null
            var imageSession: OrtSession? = null
            var textSession: OrtSession? = null
            return try {
                val environment = OrtEnvironment.getEnvironment()
                imageOptions = OrtSession.SessionOptions()
                textOptions = OrtSession.SessionOptions()
                imageSession = environment.createSession(artifacts.imageEncoder.absolutePath, imageOptions)
                textSession = environment.createSession(artifacts.textEncoder.absolutePath, textOptions)
                val imageInput = imageSession.inputInfo.keys.singleOrNull()
                    ?: throw IllegalArgumentException("Image graph must expose one input.")
                val imageOutput = imageSession.outputInfo.keys.singleOrNull()
                    ?: throw IllegalArgumentException("Image graph must expose one output.")
                val textOutput = textSession.outputInfo.keys.singleOrNull()
                    ?: throw IllegalArgumentException("Text graph must expose one output.")
                require("input_ids" in textSession.inputInfo) { "Text graph lacks input_ids." }
                require(textSession.inputInfo.keys.all { it == "input_ids" || it == "attention_mask" }) {
                    "Text graph has an unsupported input ABI."
                }
                val imagePlan = ImageInputPlan.from(
                    (imageSession.inputInfo.getValue(imageInput).info as TensorInfo).shape,
                )
                val imageDimension = outputDimension(imageSession, imageOutput)
                val textDimension = outputDimension(textSession, textOutput)
                require(imageDimension == textDimension) {
                    "CLIP image/text dimensions differ: $imageDimension vs $textDimension."
                }
                val digestFiles = listOf(
                    artifacts.imageEncoder,
                    artifacts.textEncoder,
                    *tokenizerFiles.toTypedArray(),
                )
                val modelDigest = combinedDigest(digestFiles)
                val identity = GalleryClipProvenance.resolve(modelDigest)
                GalleryRuntimeOpenResult.Ready(
                    OnnxClipGalleryRuntime(
                        environment = environment,
                        imageOptions = imageOptions,
                        textOptions = textOptions,
                        imageSession = imageSession,
                        textSession = textSession,
                        tokenizer = tokenizer,
                        descriptor = GalleryClipRuntimeDescriptor(
                            modelId = identity.modelId,
                            modelDigest = modelDigest,
                            embeddingDimension = imageDimension,
                            imageEncoderName = artifacts.imageEncoder.name,
                            textEncoderName = artifacts.textEncoder.name,
                            tokenizerName = artifacts.tokenizerDirectory.name,
                            identityVerified = identity.verified,
                        ),
                        imageInputName = imageInput,
                        imageOutputName = imageOutput,
                        textOutputName = textOutput,
                        imagePlan = imagePlan,
                    ),
                )
            } catch (_: OutOfMemoryError) {
                closePartiallyOpenedRuntime(imageSession, textSession, imageOptions, textOptions)
                GalleryRuntimeOpenResult.Blocked(GalleryRuntimeOpenFailures.outOfMemory())
            } catch (error: Exception) {
                closePartiallyOpenedRuntime(imageSession, textSession, imageOptions, textOptions)
                GalleryRuntimeOpenResult.Blocked(
                    GallerySearchFailure(
                        GallerySearchFailureCode.MODEL_ABI_MISMATCH,
                        "CLIP artifacts were found but their ONNX/tokenizer ABI is incompatible.",
                        retryable = false,
                    ),
                )
            }
        }

        private fun outputDimension(session: OrtSession, outputName: String): Int {
            val shape = (session.outputInfo.getValue(outputName).info as TensorInfo).shape
            return shape.lastOrNull()?.takeIf { it in 1..4_096 }?.toInt()
                ?: throw IllegalArgumentException("CLIP output dimension must be static.")
        }

        private fun blocked(
            code: GallerySearchFailureCode,
            message: String,
            retryable: Boolean = true,
        ) = GalleryRuntimeOpenResult.Blocked(GallerySearchFailure(code, message, retryable))

        private fun closePartiallyOpenedRuntime(
            imageSession: OrtSession?,
            textSession: OrtSession?,
            imageOptions: OrtSession.SessionOptions?,
            textOptions: OrtSession.SessionOptions?,
        ) {
            runCatching { imageSession?.close() }
            runCatching { textSession?.close() }
            runCatching { imageOptions?.close() }
            runCatching { textOptions?.close() }
        }

        private fun combinedDigest(files: List<File>): String {
            val aggregate = MessageDigest.getInstance("SHA-256")
            files.forEach { file ->
                aggregate.update(file.name.toByteArray(Charsets.UTF_8))
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        aggregate.update(buffer, 0, count)
                    }
                }
            }
            return aggregate.digest().joinToString("") { "%02x".format(it) }
        }

        private fun flatten(value: Any?): FloatArray {
            val values = ArrayList<Float>()
            fun visit(node: Any?) {
                when (node) {
                    is FloatArray -> node.forEach(values::add)
                    is DoubleArray -> node.forEach { values += it.toFloat() }
                    is Array<*> -> node.forEach(::visit)
                }
            }
            visit(value)
            return values.toFloatArray()
        }

        private fun preprocess(bitmap: Bitmap, plan: ImageInputPlan): FloatArray {
            var square: Bitmap? = null
            var scaled: Bitmap? = null
            try {
                val size = plan.imageSize
                val cropSize = minOf(bitmap.width, bitmap.height)
                val squareBitmap = if (bitmap.width == bitmap.height) {
                    bitmap
                } else {
                    Bitmap.createBitmap(
                        bitmap,
                        (bitmap.width - cropSize) / 2,
                        (bitmap.height - cropSize) / 2,
                        cropSize,
                        cropSize,
                    )
                }
                square = squareBitmap
                val scaledBitmap = if (squareBitmap.width == size && squareBitmap.height == size) {
                    squareBitmap
                } else {
                    Bitmap.createScaledBitmap(squareBitmap, size, size, true)
                }
                scaled = scaledBitmap
                val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
                val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
                val pixels = IntArray(size * size)
                scaledBitmap.getPixels(pixels, 0, size, 0, 0, size, size)
                val values = FloatArray(size * size * 3)
                fun channel(pixel: Int, component: Int): Float {
                    val raw = when (component) {
                        0 -> Color.red(pixel)
                        1 -> Color.green(pixel)
                        else -> Color.blue(pixel)
                    }
                    return (raw / 255f - mean[component]) / std[component]
                }
                if (plan.nchw) {
                    for (component in 0..2) for (index in pixels.indices) {
                        values[component * pixels.size + index] = channel(pixels[index], component)
                    }
                } else {
                    var target = 0
                    pixels.forEach { pixel ->
                        values[target++] = channel(pixel, 0)
                        values[target++] = channel(pixel, 1)
                        values[target++] = channel(pixel, 2)
                    }
                }
                return values
            } finally {
                scaled?.let { value ->
                    if (value !== square && value !== bitmap) value.recycle()
                }
                square?.let { value ->
                    if (value !== bitmap) value.recycle()
                }
                bitmap.recycle()
            }
        }

        private fun decodeSampledBitmap(
            photo: GalleryPhoto,
            mediaReader: GalleryMediaReader,
            targetSize: Int,
        ): Bitmap? {
            val orientation = readExifOrientation(photo, mediaReader)
            return mediaReader.open(photo).use { stream ->
                val decoder = BitmapRegionDecoder.newInstance(stream, false)
                    ?: throw imageDecodeFailure(null)
                try {
                    val plan = ClipImageSampling.plan(decoder.width, decoder.height, targetSize)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = plan.inSampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val decoded = decoder.decodeRegion(
                        Rect(plan.left, plan.top, plan.right, plan.bottom),
                        options,
                    ) ?: throw imageDecodeFailure(null)
                    val actualPixels = decoded.width.toLong() * decoded.height.toLong()
                    val actualBytes = decoded.allocationByteCount.toLong()
                    if (!ClipImageSampling.withinDecodeBudget(actualPixels, actualBytes)) {
                        decoded.recycle()
                        throw imageDecodeFailure(null)
                    }
                    applyExifOrientation(decoded, orientation)
                } finally {
                    decoder.recycle()
                }
            }
        }

        private fun readExifOrientation(
            photo: GalleryPhoto,
            mediaReader: GalleryMediaReader,
        ): Int = try {
            mediaReader.open(photo).use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        } catch (_: Exception) {
            // Missing or malformed EXIF does not make otherwise decodable image pixels unusable.
            ExifInterface.ORIENTATION_NORMAL
        }

        private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
            val transform = ClipExifOrientation.transformFor(orientation)
            if (transform == ClipExifTransform.IDENTITY) return bitmap
            val matrix = Matrix().apply {
                setRotate(transform.rotationDegrees.toFloat())
                if (transform.flipHorizontal) postScale(-1f, 1f)
            }
            return try {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    .also { oriented ->
                        if (oriented !== bitmap) bitmap.recycle()
                    }
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
        }

        private fun imageDecodeFailure(cause: Throwable?) = GallerySearchException(
            GallerySearchFailure(
                GallerySearchFailureCode.IMAGE_DECODE_FAILED,
                "A granted local image could not be decoded within the local memory budget.",
                retryable = true,
            ),
            cause,
        )
    }
}

internal object GalleryRuntimeOpenFailures {
    fun outOfMemory(): GallerySearchFailure = GallerySearchFailure(
        GallerySearchFailureCode.MODEL_LOAD_FAILED,
        "CLIP could not be loaded within the available local memory budget.",
        retryable = true,
    )
}

internal object GalleryImageDecodeSafety {
    fun <T> guard(block: () -> T): T = try {
        block()
    } catch (error: GallerySearchException) {
        throw error
    } catch (error: OutOfMemoryError) {
        throw GallerySearchException(
            GallerySearchFailure(
                GallerySearchFailureCode.IMAGE_DECODE_FAILED,
                "A granted local image could not be decoded within the local memory budget.",
                retryable = true,
            ),
            error,
        )
    } catch (error: Exception) {
        throw GallerySearchException(
            GallerySearchFailure(
                GallerySearchFailureCode.IMAGE_DECODE_FAILED,
                "A granted local image could not be decoded.",
                retryable = true,
            ),
            error,
        )
    }
}

internal data class GalleryClipArtifactIdentity(
    val modelId: String,
    val verified: Boolean,
)

/** Identity is derived from the complete paired-encoder/tokenizer digest, never filename or ABI. */
internal object GalleryClipProvenance {
    const val AUDITED_ONNX_COMMUNITY_DIGEST =
        "047ea6bcdda8b9f5b8fe9c04aa86106ef32f58731831d847eaee7dfc1f3b7046"

    fun resolve(combinedArtifactDigest: String): GalleryClipArtifactIdentity {
        require(combinedArtifactDigest.matches(Regex("[a-f0-9]{64}")))
        return if (combinedArtifactDigest == AUDITED_ONNX_COMMUNITY_DIGEST) {
            GalleryClipArtifactIdentity(
                modelId = "onnx-community/clip-vit-base-patch16-ONNX",
                verified = true,
            )
        } else {
            GalleryClipArtifactIdentity(
                modelId = "user-imported/clip-compatible",
                verified = false,
            )
        }
    }
}

internal data class ClipImageDecodePlan(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val inSampleSize: Int,
    val maximumDecodedPixels: Long,
    val maximumDecodedBytes: Long,
) {
    val sourceCropWidth: Int get() = right - left
    val sourceCropHeight: Int get() = bottom - top
    val plannedDecodedWidth: Long get() = ceilDiv(sourceCropWidth.toLong(), inSampleSize.toLong())
    val plannedDecodedHeight: Long get() = ceilDiv(sourceCropHeight.toLong(), inSampleSize.toLong())
    val plannedDecodedPixels: Long get() = plannedDecodedWidth * plannedDecodedHeight
    val plannedDecodedBytes: Long get() = plannedDecodedPixels * ARGB_8888_BYTES_PER_PIXEL

    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top)
        require(sourceCropWidth == sourceCropHeight)
        require(inSampleSize > 0 && inSampleSize and (inSampleSize - 1) == 0)
        require(plannedDecodedPixels <= maximumDecodedPixels)
        require(plannedDecodedBytes <= maximumDecodedBytes)
    }

    companion object {
        private const val ARGB_8888_BYTES_PER_PIXEL = 4L

        private fun ceilDiv(value: Long, divisor: Long): Long =
            value / divisor + if (value % divisor == 0L) 0L else 1L
    }
}

/**
 * A CLIP resize-short-side-then-center-crop is geometrically the centered square of the source.
 * Decode that square directly so a 100 x 5,000,000 image never allocates the 500 MP full bitmap.
 */
internal object ClipImageSampling {
    const val MAX_TARGET_SIZE = 1_024
    const val MAX_DECODED_PIXELS = 4_194_304L
    const val MAX_DECODED_BYTES = 16_777_216L

    fun plan(width: Int, height: Int, targetSize: Int): ClipImageDecodePlan {
        require(width > 0 && height > 0)
        require(targetSize in 1..MAX_TARGET_SIZE)
        val cropSize = minOf(width, height)
        val left = (width - cropSize) / 2
        val top = (height - cropSize) / 2
        var sample = 1
        while (sample <= Int.MAX_VALUE / 2 && cropSize / (sample * 2L) >= targetSize) {
            sample *= 2
        }
        while (true) {
            val pixels = decodedPixels(cropSize, sample)
            if (withinDecodeBudget(pixels, Math.multiplyExact(pixels, 4L))) break
            require(sample <= Int.MAX_VALUE / 2) { "Image dimensions exceed the decode budget." }
            sample *= 2
        }
        return ClipImageDecodePlan(
            left = left,
            top = top,
            right = left + cropSize,
            bottom = top + cropSize,
            inSampleSize = sample,
            maximumDecodedPixels = MAX_DECODED_PIXELS,
            maximumDecodedBytes = MAX_DECODED_BYTES,
        )
    }

    fun inSampleSize(width: Int, height: Int, targetSize: Int): Int =
        plan(width, height, targetSize).inSampleSize

    fun withinDecodeBudget(pixelCount: Long, byteCount: Long): Boolean =
        pixelCount in 1..MAX_DECODED_PIXELS && byteCount in 1..MAX_DECODED_BYTES

    private fun decodedPixels(cropSize: Int, sample: Int): Long {
        val side = cropSize.toLong() / sample +
            if (cropSize.toLong() % sample == 0L) 0L else 1L
        return Math.multiplyExact(side, side)
    }
}

internal data class ClipExifTransform(
    val rotationDegrees: Int,
    val flipHorizontal: Boolean,
) {
    companion object {
        val IDENTITY = ClipExifTransform(0, false)
    }
}

internal object ClipExifOrientation {
    fun transformFor(orientation: Int): ClipExifTransform = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ClipExifTransform(0, true)
        ExifInterface.ORIENTATION_ROTATE_180 -> ClipExifTransform(180, false)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> ClipExifTransform(180, true)
        ExifInterface.ORIENTATION_TRANSPOSE -> ClipExifTransform(90, true)
        ExifInterface.ORIENTATION_ROTATE_90 -> ClipExifTransform(90, false)
        ExifInterface.ORIENTATION_TRANSVERSE -> ClipExifTransform(270, true)
        ExifInterface.ORIENTATION_ROTATE_270 -> ClipExifTransform(270, false)
        else -> ClipExifTransform.IDENTITY
    }
}

private data class ImageInputPlan(
    val shape: LongArray,
    val imageSize: Int,
    val nchw: Boolean,
) {
    companion object {
        fun from(rawShape: LongArray): ImageInputPlan {
            require(rawShape.size == 4) { "CLIP expects one rank-4 image tensor." }
            val allDynamic = rawShape.drop(1).all { it <= 0L }
            val nchw = rawShape[1] == 3L || allDynamic
            val nhwc = rawShape[3] == 3L
            require(nchw || nhwc) { "CLIP image input must be NCHW or NHWC RGB." }
            val size = (if (nchw) rawShape[2] else rawShape[1]).takeIf { it > 0L }?.toInt() ?: 224
            require(size in 1..ClipImageSampling.MAX_TARGET_SIZE) {
                "CLIP image input exceeds the audited local bitmap/tensor budget."
            }
            return ImageInputPlan(
                if (nchw) longArrayOf(1, 3, size.toLong(), size.toLong())
                else longArrayOf(1, size.toLong(), size.toLong(), 3),
                size,
                nchw,
            )
        }
    }
}
