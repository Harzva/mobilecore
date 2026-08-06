package ai.mobilecore.network

import ai.mobilecore.runtime.BackendInfo
import ai.mobilecore.runtime.ChatMessage
import ai.mobilecore.runtime.ChatOptions
import ai.mobilecore.runtime.ChatResult
import ai.mobilecore.runtime.ChatToken
import ai.mobilecore.runtime.LoadOptions
import ai.mobilecore.runtime.LoadResult
import ai.mobilecore.runtime.ModelManager
import ai.mobilecore.runtime.MultimodalRuntimeBackend
import ai.mobilecore.runtime.RuntimeBackend
import ai.mobilecore.runtime.RuntimeMetrics
import ai.mobilecore.runtime.RuntimeMultimodalStatus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalMultimodalApiSmokeTest {
    private val backend = RecordingBackend()
    private lateinit var server: LocalApiServer
    private lateinit var controlModel: File
    private lateinit var controlProjector: File
    private lateinit var incompatibleProjector: File
    private val port = 18_083

    @Before
    fun startServer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        controlModel = context.filesDir.resolve("models/control-test-q4_k_m.gguf").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        }
        controlProjector = context.filesDir.resolve("models/mmproj-control-test-bf16.gguf").apply {
            writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        }
        incompatibleProjector = context.filesDir.resolve("models/mmproj-other-family-bf16.gguf").apply {
            writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        }
        server = LocalApiServer(
            backend = backend,
            modelManager = ModelManager(backend, context),
            context = context,
            apiKey = API_KEY,
            port = port,
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    @After
    fun stopServer() {
        server.stop()
        controlModel.delete()
        controlProjector.delete()
        incompatibleProjector.delete()
    }

    @Test
    fun modelControlUsesPublicIdsAndSupportsUnload() {
        val models = request("GET", "/v1/models")
        assertEquals(200, models.code)
        assertFalse(models.body.contains("\"path\""))
        val ids = JSONObject(models.body).getJSONArray("data")
        val controlled = (0 until ids.length())
            .map { ids.getJSONObject(it) }
            .first { it.getString("id") == "control-test-q4_k_m" }
        val controlledMetadata = controlled.getJSONObject("mobilecore")
        assertEquals("mmproj-control-test-bf16", controlledMetadata.getString("projector_id"))
        assertTrue(controlledMetadata.getJSONObject("capabilities").getBoolean("image_input"))

        val recommendations = request("GET", "/v1/recommendations")
        assertEquals(200, recommendations.code)
        assertFalse(recommendations.body.contains("\"path\""))

        val incompatible = request(
            method = "POST",
            path = "/mobilecore/model/load",
            body = JSONObject()
                .put("model_id", "control-test-q4_k_m")
                .put("projector_id", "mmproj-other-family-bf16")
                .toString(),
        )
        assertEquals(400, incompatible.code)
        assertEquals("projector_incompatible", errorCode(incompatible.body))

        backend.projectorLoadOk = false
        val rejectedProjector = request(
            method = "POST",
            path = "/mobilecore/model/load",
            body = JSONObject()
                .put("model_id", "control-test-q4_k_m")
                .put("projector_id", "mmproj-control-test-bf16")
                .toString(),
        )
        assertEquals(400, rejectedProjector.code)
        assertEquals("projector_load_failed", errorCode(rejectedProjector.body))
        backend.projectorLoadOk = true

        val loaded = request(
            method = "POST",
            path = "/mobilecore/model/load",
            body = JSONObject()
                .put("model_id", "control-test-q4_k_m")
                .put("context_length", 1024)
                .toString(),
        )
        assertEquals(200, loaded.code)
        val loadedJson = JSONObject(loaded.body)
        assertEquals("control-test-q4_k_m", loadedJson.getString("model"))
        assertEquals("mmproj-control-test-bf16", loadedJson.getString("projector_id"))
        assertTrue(loadedJson.getJSONObject("capabilities").getBoolean("image_input"))

        val metrics = request("GET", "/metrics")
        assertEquals("control-test-q4_k_m", JSONObject(metrics.body).getString("active_model"))

        val rejected = request(
            method = "POST",
            path = "/mobilecore/model/load",
            body = JSONObject().put("model_id", "../../private").toString(),
        )
        assertEquals(400, rejected.code)
        assertEquals("artifact_missing", errorCode(rejected.body))

        val unloaded = request("POST", "/mobilecore/model/unload", "{}")
        assertEquals(200, unloaded.code)
        assertFalse(JSONObject(unloaded.body).getBoolean("model_loaded"))
    }

    @Test
    fun localhostContractSupportsDeclaredInputsAndRejectsUnsupportedOnes() {
        val health = request("GET", "/health")
        assertEquals(200, health.code)
        val healthJson = JSONObject(health.body)
        val capabilities = healthJson.getJSONObject("capabilities")
        assertFalse(capabilities.getBoolean("video_input"))
        assertFalse(capabilities.getBoolean("audio_output"))
        assertTrue(healthJson.getJSONObject("artifacts").getJSONObject("main").has("digest"))
        assertTrue(healthJson.getJSONObject("artifacts").getJSONObject("mmproj").has("digest"))
        assertTrue(healthJson.has("preflight"))

        val text = chat(JSONObject().apply {
            put("model", "smoke-model")
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "health check")
            }))
        })
        assertEquals(200, text.code)
        assertEquals("text-ok", assistantText(text.body))

        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0, 0, 0, 0, 0, 0, 0, 0,
        )
        val image = chat(structuredMediaRequest(
            part = JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put(
                    "url",
                    "data:image/png;base64,${Base64.getEncoder().encodeToString(pngHeader)}",
                ))
            },
        ))
        assertEquals(200, image.code)
        assertEquals("image-ok", assistantText(image.body))

        val audio = chat(structuredMediaRequest(
            part = JSONObject().apply {
                put("type", "input_audio")
                put("input_audio", JSONObject().apply {
                    put("format", "wav")
                    put("data", Base64.getEncoder().encodeToString(oneSecondSilentWav()))
                })
            },
        ))
        assertEquals(200, audio.code)
        assertEquals("audio-ok", assistantText(audio.body))
        assertEquals(listOf("image", "audio"), backend.mediaTypes)

        val video = chat(structuredMediaRequest(JSONObject().apply {
            put("type", "input_video")
            put("video_url", "data:video/mp4;base64,AAAA")
        }))
        assertEquals(400, video.code)
        assertEquals("unsupported_modality", errorCode(video.body))

        val audioOutput = chat(JSONObject().apply {
            put("model", "smoke-model")
            put("modalities", JSONArray().put("text").put("audio"))
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "speak")
            }))
        })
        assertEquals(400, audioOutput.code)
        assertEquals("unsupported_modality", errorCode(audioOutput.body))

        val remoteImage = chat(structuredMediaRequest(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().put("url", "https://example.invalid/private.png"))
        }))
        assertEquals(400, remoteImage.code)
        assertEquals("remote_media_not_allowed", errorCode(remoteImage.body))

        val noConsent = request(
            method = "POST",
            path = "/mobilecore/omni/install",
            body = JSONObject().put("explicit_consent", false).toString(),
        )
        assertEquals(400, noConsent.code)
        assertEquals("explicit_consent_required", errorCode(noConsent.body))

        val mediaCache = InstrumentationRegistry.getInstrumentation().targetContext
            .cacheDir.resolve("openai-media")
        assertTrue(mediaCache.listFiles().isNullOrEmpty())

        val metrics = JSONObject(request("GET", "/metrics").body)
        assertTrue(metrics.getLong("uptime_seconds") >= 0L)
        assertTrue(metrics.getLong("requests_total") >= 6L)
        assertTrue(metrics.getLong("requests_failed") >= 3L)
        assertTrue(metrics.getDouble("average_decode_tokens_per_second") >= 0.0)
    }

    private fun structuredMediaRequest(part: JSONObject): JSONObject = JSONObject().apply {
        put("model", "smoke-model")
        put("messages", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", "describe")
                })
                put(part)
            })
        }))
    }

    private fun chat(body: JSONObject): HttpResult = request(
        method = "POST",
        path = "/v1/chat/completions",
        body = body.toString(),
    )

    private fun request(method: String, path: String, body: String? = null): HttpResult {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer $API_KEY")
            connection.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val responseBody = stream?.use { input ->
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            return HttpResult(code, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun assistantText(body: String): String = JSONObject(body)
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .getString("content")

    private fun errorCode(body: String): String = JSONObject(body)
        .getJSONObject("error")
        .getString("code")

    private fun oneSecondSilentWav(): ByteArray {
        val sampleRate = 16_000
        val channels = 1
        val bitsPerSample = 16
        val dataBytes = sampleRate * channels * bitsPerSample / 8
        return ByteBuffer.allocate(44 + dataBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataBytes)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(sampleRate * channels * bitsPerSample / 8)
                putShort((channels * bitsPerSample / 8).toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes)
                put(ByteArray(dataBytes))
            }
            .array()
    }

    private data class HttpResult(val code: Int, val body: String)

    private class RecordingBackend : RuntimeBackend, MultimodalRuntimeBackend {
        val mediaTypes = mutableListOf<String>()
        var projectorLoadOk = true
        private var activeModel: String? = null

        override fun backendInfo() = BackendInfo(
            id = "instrumented-fake",
            platform = "android",
            engine = "fake",
            modelFormats = listOf("gguf"),
            acceleration = listOf("cpu"),
            status = "ready",
        )

        override fun loadModel(modelPath: String, options: LoadOptions): LoadResult {
            activeModel = File(modelPath).nameWithoutExtension
            return LoadResult(true, requireNotNull(activeModel), 1, 8)
        }

        override fun unloadModel(): Boolean {
            activeModel = null
            activeProjector = null
            return true
        }

        override fun isModelLoaded() = activeModel != null

        override fun chat(messages: List<ChatMessage>, options: ChatOptions) =
            result(options.model, "text-ok")

        override fun streamChat(messages: List<ChatMessage>, options: ChatOptions): Sequence<ChatToken> =
            emptySequence()

        override fun metrics() = RuntimeMetrics(activeModel = activeModel, backend = "instrumented-fake")

        private var activeProjector: String? = null

        override fun loadProjector(
            projectorPath: String,
            projectorId: String,
            threads: Int,
        ): Boolean {
            activeProjector = projectorId.takeIf { projectorLoadOk }
            return projectorLoadOk
        }

        override fun multimodalStatus() = RuntimeMultimodalStatus(
            projectorId = activeProjector,
            imageInput = activeProjector != null,
        )

        override fun mediaChat(
            modelId: String,
            mediaPath: String,
            mediaType: String,
            prompt: String,
            maxTokens: Int,
        ): ChatResult {
            mediaTypes += mediaType
            return result(modelId, "$mediaType-ok")
        }

        private fun result(model: String, message: String) = ChatResult(
            model = model,
            message = message,
            promptTokens = 1,
            completionTokens = 1,
            totalTokens = 2,
        )
    }

    private companion object {
        const val API_KEY = "instrumented-local-key"
    }
}
