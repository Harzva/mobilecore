package ai.mobilecore.network

import fi.iki.elonen.NanoHTTPD.IHTTPSession
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Reads JSON as the UTF-8 wire format required by RFC 8259.
 *
 * NanoHTTPD 2.3.1 falls back to US-ASCII when an `application/json` content type omits its
 * optional charset parameter. That silently replaces every non-ASCII request byte before the
 * JSON parser sees it, so JSON requests must not go through NanoHTTPD's generic form decoder.
 */
internal object Utf8JsonBody {
    fun handles(contentType: String?): Boolean {
        val mediaType = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?: return false
        return mediaType == "application/json" || mediaType.endsWith("+json")
    }

    fun read(session: IHTTPSession, maxBytes: Long): String {
        require(maxBytes in 1..Int.MAX_VALUE.toLong())
        requireUtf8Charset(session.headers["content-type"] ?: session.headers["Content-Type"])

        val declaredLength = session.headers["content-length"]
            ?.toLongOrNull()
            ?: session.headers["Content-Length"]?.toLongOrNull()
        if (declaredLength != null && (declaredLength < 0L || declaredLength > maxBytes)) {
            throw ApiRequestException(
                ApiFailureCode.MEDIA_TOO_LARGE,
                "request exceeds the configured size limit",
            )
        }
        val chunked = (session.headers["transfer-encoding"]
            ?: session.headers["Transfer-Encoding"])
            ?.split(',')
            ?.any { it.trim().equals("chunked", ignoreCase = true) } == true
        if (declaredLength == null && !chunked) {
            throw ApiRequestException(ApiFailureCode.INVALID_REQUEST, "JSON request length is required")
        }

        val initialCapacity = declaredLength
            ?.coerceAtMost(DEFAULT_BUFFER_SIZE.toLong())
            ?.toInt()
            ?: DEFAULT_BUFFER_SIZE
        val bytes = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = declaredLength
        while (remaining == null || remaining > 0L) {
            val requested = if (remaining == null) {
                buffer.size
            } else {
                minOf(buffer.size.toLong(), remaining).toInt()
            }
            val count = session.inputStream.read(buffer, 0, requested)
            if (count < 0) {
                if (remaining != null && remaining > 0L) {
                    throw ApiRequestException(ApiFailureCode.INVALID_REQUEST, "JSON request body is truncated")
                }
                break
            }
            if (count == 0) continue
            if (bytes.size().toLong() + count > maxBytes) {
                throw ApiRequestException(
                    ApiFailureCode.MEDIA_TOO_LARGE,
                    "request exceeds the configured size limit",
                )
            }
            bytes.write(buffer, 0, count)
            remaining = remaining?.minus(count.toLong())
        }
        return decode(bytes.toByteArray())
    }

    fun decode(bytes: ByteArray): String {
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw ApiRequestException(ApiFailureCode.INVALID_REQUEST, "JSON request body is not valid UTF-8", error)
        }
        return decoded.removePrefix("\uFEFF")
    }

    private fun requireUtf8Charset(contentType: String?) {
        val charset = contentType
            ?.split(';')
            ?.drop(1)
            ?.map { it.trim() }
            ?.firstOrNull { it.substringBefore('=').trim().equals("charset", ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() }
        if (charset != null &&
            !charset.equals("utf-8", ignoreCase = true) &&
            !charset.equals("utf8", ignoreCase = true)
        ) {
            throw ApiRequestException(ApiFailureCode.INVALID_REQUEST, "JSON requests must use UTF-8")
        }
    }
}
