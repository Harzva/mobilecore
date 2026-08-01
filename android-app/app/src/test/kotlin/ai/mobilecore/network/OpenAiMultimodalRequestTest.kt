package ai.mobilecore.network

import ai.mobilecore.runtime.BackendInfo
import ai.mobilecore.runtime.ChatMessage
import ai.mobilecore.runtime.ChatOptions
import ai.mobilecore.runtime.ChatResult
import ai.mobilecore.runtime.ChatToken
import ai.mobilecore.runtime.LoadOptions
import ai.mobilecore.runtime.LoadResult
import ai.mobilecore.runtime.MultimodalRuntimeBackend
import ai.mobilecore.runtime.RuntimeBackend
import ai.mobilecore.runtime.RuntimeMetrics
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class OpenAiMultimodalRequestTest {
    private lateinit var root: File
    private lateinit var allowed: File
    private lateinit var cache: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("mobilecore-openai-test").toFile()
        allowed = File(root, "allowed").apply { mkdirs() }
        cache = File(allowed, "cache").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun stringContentRemainsTextChatCompatible() {
        val parsed = parser().parse(
            requestWithContent("hello from MobileCode")
        )
        val backend = RecordingTextBackend()

        parsed.use {
            val result = OpenAiChatDispatcher.dispatch(
                backend,
                it,
                ChatOptions(model = "local-model"),
            )
            assertEquals("text-result", result.message)
        }
        assertEquals("hello from MobileCode", backend.messages.single().content)
    }

    @Test
    fun structuredTextContentIsPreserved() {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "first"))
            .put(JSONObject().put("type", "text").put("text", "second"))

        parser().parse(requestWithContent(content)).use { parsed ->
            assertEquals("first\nsecond", parsed.messages.single().content)
            assertEquals(null, parsed.media)
        }
    }

    @Test
    fun imageDataUriDispatchesToImageRuntimeAndDeletesTempFile() {
        val image = pngBytes()
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "describe"))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put(
                            "url",
                            "data:image/png;base64,${Base64.getEncoder().encodeToString(image)}",
                        )
                    )
            )
        val parsed = parser().parse(requestWithContent(content))
        val mediaPath = requireNotNull(parsed.media).file
        val backend = RecordingMultimodalBackend()
        assertTrue(mediaPath.isFile)

        parsed.use {
            val result = OpenAiChatDispatcher.dispatch(
                backend,
                it,
                ChatOptions(model = "qwen-omni", maxTokens = 64),
            )
            assertEquals("media-result", result.message)
            assertEquals("image", backend.mediaType)
            assertTrue(File(requireNotNull(backend.mediaPath)).isFile)
        }
        assertFalse(mediaPath.exists())
    }

    @Test
    fun inputAudioBase64DispatchesToAudioRuntimeAndDeletesTempFile() {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "transcribe"))
            .put(
                JSONObject()
                    .put("type", "input_audio")
                    .put(
                        "input_audio",
                        JSONObject()
                            .put("format", "wav")
                            .put("data", Base64.getEncoder().encodeToString(wavBytes())),
                    )
            )
        val parsed = parser().parse(requestWithContent(content))
        val mediaPath = requireNotNull(parsed.media).file
        val backend = RecordingMultimodalBackend()

        parsed.use {
            val result = OpenAiChatDispatcher.dispatch(
                backend,
                it,
                ChatOptions(model = "qwen-omni", maxTokens = 64),
            )
            assertEquals("media-result", result.message)
            assertEquals("audio", backend.mediaType)
        }
        assertFalse(mediaPath.exists())
    }

    @Test
    fun remoteMediaUrlIsRejectedWithoutFetching() {
        val content = JSONArray().put(
            JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", "https://example.invalid/private.png"))
        )

        assertFailure(ApiFailureCode.REMOTE_MEDIA_NOT_ALLOWED) {
            parser().parse(requestWithContent(content))
        }
    }

    @Test
    fun fileTraversalOutsideControlledRootsIsRejected() {
        val outside = File(root, "outside.png").apply { writeBytes(pngBytes()) }
        val traversal = File(allowed, "../${outside.name}").absolutePath
        val content = JSONArray().put(
            JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", "file://$traversal"))
        )

        assertFailure(ApiFailureCode.LOCAL_MEDIA_NOT_ALLOWED) {
            parser().parse(requestWithContent(content))
        }
    }

    @Test
    fun oversizedMediaIsRejectedBeforeDecode() {
        val tinyLimits = MediaSecurityLimits(maxImageBytes = 4L)
        val encoded = Base64.getEncoder().encodeToString(pngBytes())
        val content = JSONArray().put(
            JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/png;base64,$encoded"))
        )

        assertFailure(ApiFailureCode.MEDIA_TOO_LARGE) {
            parser(tinyLimits).parse(requestWithContent(content))
        }
    }

    @Test
    fun temporaryMediaIsDeletedWhenLaterValidationFails() {
        val limits = MediaSecurityLimits(maxTextCharacters = 3)
        val encoded = Base64.getEncoder().encodeToString(pngBytes())
        val content = JSONArray()
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/png;base64,$encoded"))
            )
            .put(JSONObject().put("type", "text").put("text", "too long"))

        assertFailure(ApiFailureCode.INVALID_REQUEST) {
            parser(limits).parse(requestWithContent(content))
        }
        assertTrue(cache.listFiles().isNullOrEmpty())
    }

    @Test
    fun overDurationAudioIsRejected() {
        val limits = MediaSecurityLimits(maxAudioDurationMs = 1_000L)
        val encoded = Base64.getEncoder().encodeToString(wavBytes())
        val content = JSONArray().put(
            JSONObject()
                .put("type", "input_audio")
                .put(
                    "input_audio",
                    JSONObject().put("format", "wav").put("data", encoded),
                )
        )

        assertFailure(ApiFailureCode.MEDIA_TOO_LARGE) {
            parser(limits, durationMs = 1_001L).parse(requestWithContent(content))
        }
    }

    @Test
    fun videoInputIsExplicitlyUnsupported() {
        val content = JSONArray().put(
            JSONObject().put("type", "input_video").put("video_url", "data:video/mp4;base64,AA==")
        )

        assertFailure(ApiFailureCode.UNSUPPORTED_MODALITY) {
            parser().parse(requestWithContent(content))
        }
    }

    @Test
    fun audioOutputIsExplicitlyUnsupported() {
        val request = requestWithContent("speak this")
            .put("modalities", JSONArray().put("text").put("audio"))
            .put("audio", JSONObject().put("voice", "default"))

        assertFailure(ApiFailureCode.UNSUPPORTED_MODALITY) {
            parser().parse(request)
        }
    }

    @Test
    fun requiredTypedFailureCodesUseOpenAiErrorCodeField() {
        val required = setOf(
            "unsupported_modality",
            "artifact_missing",
            "checksum_mismatch",
            "insufficient_memory",
            "insufficient_storage",
            "model_load_failed",
            "media_too_large",
            "cancelled",
        )
        val actual = ApiFailureCode.values().map { it.wireValue }.toSet()
        assertTrue(actual.containsAll(required))
        val error = JSONObject(OpenAiApiError.json(ApiFailureCode.ARTIFACT_MISSING, "artifact missing"))
            .getJSONObject("error")
        assertEquals("artifact_missing", error.getString("code"))
        assertEquals("invalid_request_error", error.getString("type"))
    }

    private fun parser(
        limits: MediaSecurityLimits = MediaSecurityLimits(),
        durationMs: Long = 1_000L,
    ): OpenAiChatRequestParser {
        val store = SecureMediaStore(
            cacheDir = cache,
            allowedRoots = listOf(allowed),
            durationProbe = AudioDurationProbe { _, _ -> durationMs },
            limits = limits,
        )
        return OpenAiChatRequestParser(store, limits)
    }

    private fun requestWithContent(content: Any): JSONObject {
        return JSONObject()
            .put("model", "local-model")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content)
                )
            )
    }

    private fun assertFailure(code: ApiFailureCode, block: () -> Unit) {
        try {
            block()
            fail("expected ${code.wireValue}")
        } catch (error: ApiRequestException) {
            assertEquals(code, error.failureCode)
        }
    }

    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x00,
    )

    private fun wavBytes(): ByteArray = ByteArray(44).also { bytes ->
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WAVE".toByteArray().copyInto(bytes, 8)
        "fmt ".toByteArray().copyInto(bytes, 12)
    }
}

private open class RecordingTextBackend : RuntimeBackend {
    var messages: List<ChatMessage> = emptyList()

    override fun backendInfo() = BackendInfo("test", "jvm", "test", emptyList(), emptyList(), "ok")
    override fun loadModel(modelPath: String, options: LoadOptions) = LoadResult(true, modelPath, 0, 0)
    override fun unloadModel() = true
    override fun isModelLoaded() = true
    override fun chat(messages: List<ChatMessage>, options: ChatOptions): ChatResult {
        this.messages = messages
        return ChatResult(model = options.model, message = "text-result")
    }

    override fun streamChat(messages: List<ChatMessage>, options: ChatOptions): Sequence<ChatToken> = emptySequence()
    override fun metrics() = RuntimeMetrics(null, "test")
}

private class RecordingMultimodalBackend : RecordingTextBackend(), MultimodalRuntimeBackend {
    var mediaPath: String? = null
    var mediaType: String? = null

    override fun mediaChat(
        modelId: String,
        mediaPath: String,
        mediaType: String,
        prompt: String,
        maxTokens: Int,
    ): ChatResult {
        this.mediaPath = mediaPath
        this.mediaType = mediaType
        assertTrue(File(mediaPath).isFile)
        return ChatResult(model = modelId, message = "media-result")
    }
}
