package ai.mobilecore.network

import ai.mobilecore.runtime.ChatMessage
import ai.mobilecore.runtime.ChatOptions
import ai.mobilecore.runtime.ChatResult
import ai.mobilecore.runtime.MultimodalRuntimeBackend
import ai.mobilecore.runtime.MultimodalRuntimeException
import ai.mobilecore.runtime.RuntimeBackend
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.util.Base64
import java.util.UUID

internal enum class ApiFailureCode(val wireValue: String) {
    INVALID_REQUEST("invalid_request"),
    UNSUPPORTED_MODALITY("unsupported_modality"),
    ARTIFACT_MISSING("artifact_missing"),
    CHECKSUM_MISMATCH("checksum_mismatch"),
    INSUFFICIENT_MEMORY("insufficient_memory"),
    INSUFFICIENT_STORAGE("insufficient_storage"),
    MODEL_LOAD_FAILED("model_load_failed"),
    PROJECTOR_INCOMPATIBLE("projector_incompatible"),
    PROJECTOR_LOAD_FAILED("projector_load_failed"),
    MEDIA_TOO_LARGE("media_too_large"),
    CANCELLED("cancelled"),
    REMOTE_MEDIA_NOT_ALLOWED("remote_media_not_allowed"),
    LOCAL_MEDIA_NOT_ALLOWED("local_media_not_allowed"),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type"),
    INVALID_MEDIA("invalid_media"),
}

internal class ApiRequestException(
    val failureCode: ApiFailureCode,
    val publicMessage: String,
    cause: Throwable? = null,
) : RuntimeException(publicMessage, cause)

internal object OpenAiApiError {
    fun json(failureCode: ApiFailureCode, message: String): String = JSONObject().apply {
        put(
            "error",
            JSONObject().apply {
                put("message", message)
                put("type", "invalid_request_error")
                put("param", JSONObject.NULL)
                put("code", failureCode.wireValue)
            }
        )
    }.toString()
}

internal enum class InputMediaType(val wireValue: String) {
    IMAGE("image"),
    AUDIO("audio"),
}

internal data class ParsedMediaInput(
    val type: InputMediaType,
    val file: File,
    val temporary: Boolean,
)

internal class ParsedOpenAiChatRequest(
    val messages: List<ChatMessage>,
    val media: ParsedMediaInput?,
    private val temporaryFiles: List<File>,
) : AutoCloseable {
    fun prompt(): String = messages.joinToString("\n") { "${it.role}: ${it.content}" }

    override fun close() {
        temporaryFiles.forEach { file ->
            runCatching { file.delete() }
        }
    }
}

internal data class MediaSecurityLimits(
    val maxImageBytes: Long = 20L * 1024L * 1024L,
    val maxAudioBytes: Long = 25L * 1024L * 1024L,
    val maxAudioDurationMs: Long = 5L * 60L * 1000L,
    val maxTextCharacters: Int = 1024 * 1024,
    val maxMessages: Int = 128,
)

internal fun interface AudioDurationProbe {
    fun durationMs(file: File, mimeType: String): Long?
}

internal fun interface ControlledContentReader {
    fun open(uri: String): ResolvedContent?
}

internal data class ResolvedContent(
    val stream: InputStream,
    val mimeType: String?,
)

/**
 * Resolves media without making network requests. Every decoded/copied payload gets a random
 * cache filename and is deleted by [ParsedOpenAiChatRequest.close].
 */
internal class SecureMediaStore(
    private val cacheDir: File,
    allowedRoots: List<File>,
    private val allowedContentAuthorities: Set<String> = emptySet(),
    private val contentReader: ControlledContentReader? = null,
    private val durationProbe: AudioDurationProbe,
    private val limits: MediaSecurityLimits = MediaSecurityLimits(),
) {
    private val canonicalAllowedRoots = allowedRoots.mapNotNull { root ->
        runCatching { root.canonicalFile }.getOrNull()
    }

    fun resolveImage(reference: String): ParsedMediaInput {
        return resolveReference(reference, InputMediaType.IMAGE, null)
    }

    fun resolveAudio(reference: String, format: String?): ParsedMediaInput {
        val expectedMime = format?.let(::audioMimeForFormat)
        return if (reference.startsWith("data:", ignoreCase = true)) {
            resolveDataUri(reference, InputMediaType.AUDIO, expectedMime)
        } else if (looksLikeBase64(reference)) {
            if (expectedMime == null) {
                fail(ApiFailureCode.UNSUPPORTED_MEDIA_TYPE, "input_audio requires a supported format")
            }
            resolveBase64(reference, InputMediaType.AUDIO, expectedMime)
        } else {
            resolveReference(reference, InputMediaType.AUDIO, expectedMime)
        }
    }

    private fun resolveReference(
        reference: String,
        mediaType: InputMediaType,
        expectedMime: String?,
    ): ParsedMediaInput {
        if (reference.startsWith("data:", ignoreCase = true)) {
            return resolveDataUri(reference, mediaType, expectedMime)
        }

        val scheme = runCatching { URI(reference).scheme?.lowercase() }.getOrNull()
        return when (scheme) {
            "http", "https" -> fail(
                ApiFailureCode.REMOTE_MEDIA_NOT_ALLOWED,
                "remote media URLs are disabled",
            )

            "content" -> resolveControlledContent(reference, mediaType, expectedMime)
            null, "file" -> resolveControlledFile(reference, scheme, mediaType, expectedMime)
            else -> fail(ApiFailureCode.LOCAL_MEDIA_NOT_ALLOWED, "media URI is not app-controlled")
        }
    }

    private fun resolveControlledFile(
        reference: String,
        scheme: String?,
        mediaType: InputMediaType,
        expectedMime: String?,
    ): ParsedMediaInput {
        val requested = try {
            if (scheme == "file") File(requireNotNull(URI(reference).path)) else File(reference)
        } catch (_: Exception) {
            fail(ApiFailureCode.INVALID_MEDIA, "invalid local media URI")
        }
        val canonical = runCatching { requested.canonicalFile }.getOrElse {
            fail(ApiFailureCode.INVALID_MEDIA, "invalid local media URI")
        }
        if (canonicalAllowedRoots.none { root -> canonical.isWithin(root) }) {
            fail(ApiFailureCode.LOCAL_MEDIA_NOT_ALLOWED, "local media is outside app-controlled storage")
        }
        if (!canonical.isFile) {
            fail(ApiFailureCode.INVALID_MEDIA, "local media is unavailable")
        }
        val limit = byteLimit(mediaType)
        if (canonical.length() > limit) {
            fail(ApiFailureCode.MEDIA_TOO_LARGE, "media exceeds the configured size limit")
        }
        canonical.inputStream().use { input ->
            return copyValidated(input, expectedMime, mediaType, limit)
        }
    }

    private fun resolveControlledContent(
        reference: String,
        mediaType: InputMediaType,
        expectedMime: String?,
    ): ParsedMediaInput {
        val authority = runCatching { URI(reference).authority }.getOrNull()
        if (authority.isNullOrBlank() || authority !in allowedContentAuthorities) {
            fail(ApiFailureCode.LOCAL_MEDIA_NOT_ALLOWED, "content URI is not app-controlled")
        }
        val resolved = contentReader?.open(reference)
            ?: fail(ApiFailureCode.INVALID_MEDIA, "content URI is unavailable")
        resolved.stream.use { input ->
            return copyValidated(
                input = input,
                expectedMime = expectedMime ?: resolved.mimeType,
                mediaType = mediaType,
                limit = byteLimit(mediaType),
            )
        }
    }

    private fun resolveDataUri(
        value: String,
        mediaType: InputMediaType,
        expectedMime: String?,
    ): ParsedMediaInput {
        val comma = value.indexOf(',')
        if (comma !in 1..256) {
            fail(ApiFailureCode.INVALID_MEDIA, "invalid data URI")
        }
        val metadata = value.substring(5, comma).split(';')
        val mimeType = metadata.firstOrNull()?.lowercase()?.takeIf { it.contains('/') }
            ?: fail(ApiFailureCode.UNSUPPORTED_MEDIA_TYPE, "data URI requires a MIME type")
        if (metadata.none { it.equals("base64", ignoreCase = true) }) {
            fail(ApiFailureCode.INVALID_MEDIA, "only base64 data URIs are supported")
        }
        if (expectedMime != null && normalizeMime(expectedMime) != normalizeMime(mimeType)) {
            fail(ApiFailureCode.UNSUPPORTED_MEDIA_TYPE, "media MIME type does not match its format")
        }
        return resolveBase64(value.substring(comma + 1), mediaType, mimeType)
    }

    private fun resolveBase64(
        encoded: String,
        mediaType: InputMediaType,
        mimeType: String,
    ): ParsedMediaInput {
        validateMimeFamily(mimeType, mediaType)
        val limit = byteLimit(mediaType)
        val maxEncodedLength = ((limit + 2L) / 3L) * 4L + 8L
        if (encoded.length.toLong() > maxEncodedLength) {
            fail(ApiFailureCode.MEDIA_TOO_LARGE, "media exceeds the configured size limit")
        }
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            fail(ApiFailureCode.INVALID_MEDIA, "invalid base64 media")
        }
        if (decoded.size.toLong() > limit) {
            fail(ApiFailureCode.MEDIA_TOO_LARGE, "media exceeds the configured size limit")
        }
        return decoded.inputStream().use { input ->
            copyValidated(input, mimeType, mediaType, limit)
        }
    }

    private fun copyValidated(
        input: InputStream,
        expectedMime: String?,
        mediaType: InputMediaType,
        limit: Long,
    ): ParsedMediaInput {
        cacheDir.mkdirs()
        val destination = File(cacheDir, "openai-media-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) {
                        fail(ApiFailureCode.MEDIA_TOO_LARGE, "media exceeds the configured size limit")
                    }
                    output.write(buffer, 0, count)
                }
            }
            val detectedMime = detectMime(destination)
                ?: fail(ApiFailureCode.INVALID_MEDIA, "media signature is not recognized")
            validateMimeFamily(detectedMime, mediaType)
            if (expectedMime != null && normalizeMime(expectedMime) != normalizeMime(detectedMime)) {
                fail(ApiFailureCode.UNSUPPORTED_MEDIA_TYPE, "media MIME type does not match its content")
            }
            if (mediaType == InputMediaType.AUDIO) {
                val durationMs = durationProbe.durationMs(destination, detectedMime)
                    ?: fail(ApiFailureCode.INVALID_MEDIA, "audio duration could not be verified")
                if (durationMs < 0L || durationMs > limits.maxAudioDurationMs) {
                    fail(ApiFailureCode.MEDIA_TOO_LARGE, "audio exceeds the configured duration limit")
                }
            }
            return ParsedMediaInput(mediaType, destination, temporary = true)
        } catch (e: Exception) {
            runCatching { destination.delete() }
            throw e
        }
    }

    private fun validateMimeFamily(mimeType: String, mediaType: InputMediaType) {
        val normalized = normalizeMime(mimeType)
        val allowed = when (mediaType) {
            InputMediaType.IMAGE -> setOf("image/jpeg", "image/png", "image/webp")
            InputMediaType.AUDIO -> setOf(
                "audio/wav",
                "audio/mpeg",
                "audio/flac",
                "audio/ogg",
                "audio/mp4",
            )
        }
        if (normalized !in allowed) {
            fail(ApiFailureCode.UNSUPPORTED_MEDIA_TYPE, "media MIME type is not supported")
        }
    }

    private fun detectMime(file: File): String? {
        val header = ByteArray(16)
        val count = file.inputStream().use { it.read(header) }
        if (count >= 12 && header.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png"
        }
        if (count >= 3 && header.startsWith(0xFF, 0xD8, 0xFF)) return "image/jpeg"
        if (count >= 12 && header.ascii(0, 4) == "RIFF" && header.ascii(8, 4) == "WEBP") {
            return "image/webp"
        }
        if (count >= 12 && header.ascii(0, 4) == "RIFF" && header.ascii(8, 4) == "WAVE") {
            return "audio/wav"
        }
        if (count >= 3 && header.ascii(0, 3) == "ID3") return "audio/mpeg"
        if (count >= 2 &&
            (header[0].toInt() and 0xFF) == 0xFF &&
            (header[1].toInt() and 0xE0) == 0xE0
        ) {
            return "audio/mpeg"
        }
        if (count >= 4 && header.ascii(0, 4) == "fLaC") return "audio/flac"
        if (count >= 4 && header.ascii(0, 4) == "OggS") return "audio/ogg"
        if (count >= 12 && header.ascii(4, 4) == "ftyp") return "audio/mp4"
        return null
    }

    private fun audioMimeForFormat(format: String): String {
        return when (format.lowercase()) {
            "wav", "wave" -> "audio/wav"
            "mp3", "mpeg" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "m4a", "mp4", "aac" -> "audio/mp4"
            else -> fail(ApiFailureCode.UNSUPPORTED_MEDIA_TYPE, "input_audio format is not supported")
        }
    }

    private fun normalizeMime(value: String): String = when (value.lowercase().substringBefore(';')) {
        "image/jpg" -> "image/jpeg"
        "audio/x-wav", "audio/wave" -> "audio/wav"
        "audio/x-flac" -> "audio/flac"
        "audio/x-m4a", "audio/aac" -> "audio/mp4"
        else -> value.lowercase().substringBefore(';')
    }

    private fun byteLimit(mediaType: InputMediaType): Long = when (mediaType) {
        InputMediaType.IMAGE -> limits.maxImageBytes
        InputMediaType.AUDIO -> limits.maxAudioBytes
    }

    private fun looksLikeBase64(value: String): Boolean {
        if (value.length < 8 || value.length % 4 != 0) return false
        return value.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    }
}

internal class OpenAiChatRequestParser(
    private val mediaStore: SecureMediaStore,
    private val limits: MediaSecurityLimits = MediaSecurityLimits(),
) {
    fun parse(request: JSONObject): ParsedOpenAiChatRequest {
        rejectUnsupportedOutput(request)
        val messages = request.optJSONArray("messages") ?: JSONArray()
        if (messages.length() > limits.maxMessages) {
            fail(ApiFailureCode.INVALID_REQUEST, "too many messages")
        }
        val parsedMessages = ArrayList<ChatMessage>(messages.length())
        val temporaryFiles = ArrayList<File>()
        var parsedMedia: ParsedMediaInput? = null
        var totalTextCharacters = 0
        try {
            for (index in 0 until messages.length()) {
                val item = messages.optJSONObject(index)
                    ?: fail(ApiFailureCode.INVALID_REQUEST, "message must be an object")
                val role = item.optString("role", "user")
                val content = item.opt("content")
                val parsed = parseContent(content)
                parsed.second?.let { media ->
                    if (parsedMedia != null) {
                        if (media.temporary) runCatching { media.file.delete() }
                        fail(
                            ApiFailureCode.UNSUPPORTED_MODALITY,
                            "this runtime accepts one image or audio input per request",
                        )
                    }
                    parsedMedia = media
                    if (media.temporary) temporaryFiles += media.file
                }
                totalTextCharacters += parsed.first.length
                if (totalTextCharacters > limits.maxTextCharacters) {
                    fail(ApiFailureCode.INVALID_REQUEST, "text content exceeds the configured limit")
                }
                parsedMessages += ChatMessage(role, parsed.first)
            }
            return ParsedOpenAiChatRequest(parsedMessages, parsedMedia, temporaryFiles)
        } catch (e: Exception) {
            temporaryFiles.forEach { file -> runCatching { file.delete() } }
            throw e
        }
    }

    private fun parseContent(content: Any?): Pair<String, ParsedMediaInput?> {
        if (content == null || content === JSONObject.NULL) return "" to null
        if (content is String) return content to null
        if (content !is JSONArray) {
            fail(ApiFailureCode.INVALID_REQUEST, "message content must be a string or array")
        }
        val texts = ArrayList<String>()
        var media: ParsedMediaInput? = null
        try {
            for (index in 0 until content.length()) {
                val part = content.optJSONObject(index)
                    ?: fail(ApiFailureCode.INVALID_REQUEST, "content part must be an object")
                when (val type = part.optString("type", "")) {
                    "text", "input_text" -> texts += part.optString("text", "")
                    "image_url", "input_image" -> {
                        val imageValue = part.opt("image_url") ?: part.opt("image")
                        val reference = when (imageValue) {
                            is JSONObject -> imageValue.optString("url", "")
                            is String -> imageValue
                            else -> ""
                        }
                        if (reference.isBlank()) {
                            fail(ApiFailureCode.INVALID_MEDIA, "image_url requires a URL")
                        }
                        if (media != null) {
                            fail(ApiFailureCode.UNSUPPORTED_MODALITY, "one media input is supported per message")
                        }
                        media = mediaStore.resolveImage(reference)
                    }

                    "input_audio" -> {
                        val audioValue = part.optJSONObject("input_audio")
                            ?: fail(ApiFailureCode.INVALID_MEDIA, "input_audio requires an object")
                        val reference = audioValue.optString("data", "")
                            .ifBlank { audioValue.optString("url", "") }
                        if (reference.isBlank()) {
                            fail(ApiFailureCode.INVALID_MEDIA, "input_audio requires data or a controlled URL")
                        }
                        if (media != null) {
                            fail(ApiFailureCode.UNSUPPORTED_MODALITY, "one media input is supported per message")
                        }
                        val format = audioValue.optString("format", "").ifBlank { null }
                        media = mediaStore.resolveAudio(reference, format)
                    }

                    "video", "video_url", "input_video", "audio", "output_audio" -> fail(
                        ApiFailureCode.UNSUPPORTED_MODALITY,
                        "requested modality is not supported by this backend",
                    )

                    else -> fail(
                        ApiFailureCode.INVALID_REQUEST,
                        if (type.isBlank()) "content part type is required" else "content part type is not supported",
                    )
                }
            }
            return texts.joinToString("\n") to media
        } catch (e: Exception) {
            if (media?.temporary == true) runCatching { media.file.delete() }
            throw e
        }
    }

    private fun rejectUnsupportedOutput(request: JSONObject) {
        val modalities = request.optJSONArray("modalities")
        if (modalities != null) {
            for (index in 0 until modalities.length()) {
                if (!modalities.optString(index).equals("text", ignoreCase = true)) {
                    fail(
                        ApiFailureCode.UNSUPPORTED_MODALITY,
                        "this backend supports text output only",
                    )
                }
            }
        }
        if (request.has("audio")) {
            fail(ApiFailureCode.UNSUPPORTED_MODALITY, "audio output is not supported by this backend")
        }
    }
}

internal object OpenAiChatDispatcher {
    fun dispatch(
        backend: RuntimeBackend,
        request: ParsedOpenAiChatRequest,
        options: ChatOptions,
    ): ChatResult {
        val media = request.media ?: return backend.chat(request.messages, options)
        val multimodal = backend as? MultimodalRuntimeBackend
            ?: fail(ApiFailureCode.UNSUPPORTED_MODALITY, "runtime does not support media input")
        return try {
            multimodal.mediaChat(
                modelId = options.model,
                mediaPath = media.file.absolutePath,
                mediaType = media.type.wireValue,
                prompt = request.prompt(),
                maxTokens = options.maxTokens,
            )
        } catch (e: MultimodalRuntimeException) {
            val code = ApiFailureCode.values().firstOrNull { it.wireValue == e.failureCode }
                ?: ApiFailureCode.MODEL_LOAD_FAILED
            fail(code, publicRuntimeMessage(code))
        }
    }
}

internal fun createSecureMediaStore(context: Context): SecureMediaStore {
    val appContext = context.applicationContext
    val roots = buildList {
        add(appContext.filesDir)
        add(appContext.cacheDir)
        appContext.getExternalFilesDir(null)?.let(::add)
        appContext.externalCacheDir?.let(::add)
    }
    val authority = "${appContext.packageName}.fileprovider"
    return SecureMediaStore(
        cacheDir = File(appContext.cacheDir, "openai-media"),
        allowedRoots = roots,
        allowedContentAuthorities = setOf(authority),
        contentReader = ControlledContentReader { rawUri ->
            val uri = Uri.parse(rawUri)
            val stream = appContext.contentResolver.openInputStream(uri) ?: return@ControlledContentReader null
            ResolvedContent(stream, appContext.contentResolver.getType(uri))
        },
        durationProbe = AudioDurationProbe { file, _ ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (_: RuntimeException) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        },
    )
}

private fun publicRuntimeMessage(code: ApiFailureCode): String = when (code) {
    ApiFailureCode.ARTIFACT_MISSING -> "required model artifact is missing"
    ApiFailureCode.CHECKSUM_MISMATCH -> "model artifact checksum verification failed"
    ApiFailureCode.INSUFFICIENT_MEMORY -> "insufficient memory for the requested model"
    ApiFailureCode.INSUFFICIENT_STORAGE -> "insufficient storage for the requested model"
    ApiFailureCode.CANCELLED -> "request was cancelled"
    ApiFailureCode.UNSUPPORTED_MODALITY -> "requested modality is not supported"
    ApiFailureCode.PROJECTOR_INCOMPATIBLE -> "projector is incompatible with the selected model"
    ApiFailureCode.PROJECTOR_LOAD_FAILED -> "projector failed to load"
    ApiFailureCode.MEDIA_TOO_LARGE -> "media exceeds the configured limit"
    else -> "multimodal model inference failed"
}

private fun File.isWithin(root: File): Boolean {
    if (this == root) return true
    return path.startsWith(root.path + File.separator)
}

private fun ByteArray.startsWith(vararg bytes: Int): Boolean {
    if (size < bytes.size) return false
    return bytes.indices.all { index -> this[index].toInt() and 0xFF == bytes[index] }
}

private fun ByteArray.ascii(offset: Int, count: Int): String {
    return String(this, offset, count, Charsets.US_ASCII)
}

private fun fail(code: ApiFailureCode, message: String): Nothing {
    throw ApiRequestException(code, message)
}
