package ai.mobilecore.network

import org.json.JSONArray
import org.json.JSONObject

object OpenAiSseEncoder {
    fun encode(
        id: String,
        created: Long,
        model: String,
        content: String,
        finishReason: String,
        promptTokens: Int,
        completionTokens: Int,
        totalTokens: Int,
        mobileCoreMetadata: JSONObject,
        chunkCodePoints: Int = 48
    ): String {
        require(chunkCodePoints > 0) { "chunkCodePoints must be positive" }
        val events = mutableListOf<JSONObject>()
        events += chunkEvent(
            id = id,
            created = created,
            model = model,
            delta = JSONObject().put("role", "assistant")
        )
        codePointChunks(content, chunkCodePoints).forEach { chunk ->
            events += chunkEvent(
                id = id,
                created = created,
                model = model,
                delta = JSONObject().put("content", chunk)
            )
        }
        events += JSONObject().apply {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", model)
            put(
                "choices",
                JSONArray().put(
                    JSONObject().apply {
                        put("index", 0)
                        put("delta", JSONObject())
                        put("finish_reason", finishReason)
                    }
                )
            )
            put(
                "usage",
                JSONObject().apply {
                    put("prompt_tokens", promptTokens)
                    put("completion_tokens", completionTokens)
                    put("total_tokens", totalTokens)
                }
            )
            put("mobilecore", mobileCoreMetadata)
        }
        return buildString {
            events.forEach { event ->
                append("data: ")
                append(event.toString())
                append("\n\n")
            }
            append("data: [DONE]\n\n")
        }
    }

    private fun chunkEvent(
        id: String,
        created: Long,
        model: String,
        delta: JSONObject
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", created)
        put("model", model)
        put(
            "choices",
            JSONArray().put(
                JSONObject().apply {
                    put("index", 0)
                    put("delta", delta)
                    put("finish_reason", JSONObject.NULL)
                }
            )
        )
    }

    private fun codePointChunks(value: String, maximumCodePoints: Int): List<String> {
        if (value.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < value.length) {
            val remaining = value.codePointCount(start, value.length)
            val count = minOf(maximumCodePoints, remaining)
            val end = value.offsetByCodePoints(start, count)
            chunks += value.substring(start, end)
            start = end
        }
        return chunks
    }
}
