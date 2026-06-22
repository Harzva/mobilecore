package ai.mobilecore.runtime

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale

data class GgufModelMetadata(
    val architecture: String,
    val quantization: String,
    val contextLength: Int,
    val parameterCountB: Double?,
    val parameterLabel: String?,
    val source: String
)

object GgufMetadataReader {
    private val quantRegex = Regex(
        "(?i)(?:^|[-_.])((?:I)?Q[1-8](?:_[A-Z0-9]+){0,3}|F16|BF16)(?:[-_.]|$)"
    )
    private val parameterRegex = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*b(?:illion)?")

    fun read(file: File): GgufModelMetadata {
        val filename = fromFilename(file)
        if (!file.isFile || file.length() < 24) return filename

        return runCatching {
            FileInputStream(file).use { input ->
                if (String(input.readBytesExact(4), Charset.forName("US-ASCII")) != "GGUF") {
                    return@use filename
                }

                input.readUInt32()
                input.readUInt64()
                val metadataCount = input.readUInt64().coerceAtMost(4096)

                var architecture: String? = null
                var sizeLabel: String? = null
                var contextLength: Int? = null

                var index = 0
                while (index < metadataCount.toInt()) {
                    val key = input.readGgufString()
                    val valueType = input.readUInt32().toInt()
                    when (valueType) {
                        TYPE_STRING -> {
                            val value = input.readGgufString()
                            when {
                                key == "general.architecture" -> architecture = value
                                key == "general.size_label" -> sizeLabel = value
                                key == "general.name" && sizeLabel == null -> sizeLabel = parseParameterLabel(value)
                            }
                        }

                        TYPE_UINT32, TYPE_INT32 -> {
                            val value = input.readUInt32().toInt()
                            if (key.endsWith(".context_length")) {
                                contextLength = value
                            }
                        }

                        else -> input.skipGgufValue(valueType)
                    }
                    if (architecture != null && contextLength != null && sizeLabel != null) {
                        return@use modelMetadata(
                            filename = filename,
                            architecture = architecture,
                            contextLength = contextLength,
                            sizeLabel = sizeLabel
                        )
                    }
                    index++
                }

                modelMetadata(filename, architecture, contextLength, sizeLabel)
            }
        }.getOrElse { filename }
    }

    private fun modelMetadata(
        filename: GgufModelMetadata,
        architecture: String?,
        contextLength: Int?,
        sizeLabel: String?
    ): GgufModelMetadata {
        val label = sizeLabel ?: filename.parameterLabel
        return GgufModelMetadata(
            architecture = architecture?.ifBlank { null } ?: filename.architecture,
            quantization = filename.quantization,
            contextLength = contextLength ?: filename.contextLength,
            parameterCountB = parseParameterCount(label) ?: filename.parameterCountB,
            parameterLabel = label ?: filename.parameterLabel,
            source = if (architecture != null || contextLength != null || sizeLabel != null) "gguf+filename" else "filename"
        )
    }

    private fun fromFilename(file: File): GgufModelMetadata {
        val name = file.nameWithoutExtension
        val lower = name.lowercase(Locale.US)
        val architecture = when {
            "qwen" in lower -> "qwen"
            "llama" in lower -> "llama"
            "gemma" in lower -> "gemma"
            "phi" in lower -> "phi"
            "mistral" in lower || "mixtral" in lower -> "mistral"
            "smollm" in lower -> "smollm"
            "deepseek" in lower -> "deepseek"
            else -> "unknown"
        }
        val quant = quantRegex.find(name)?.groupValues?.getOrNull(1)?.uppercase(Locale.US) ?: "unknown"
        val label = parseParameterLabel(name)

        return GgufModelMetadata(
            architecture = architecture,
            quantization = quant,
            contextLength = 4096,
            parameterCountB = parseParameterCount(label),
            parameterLabel = label,
            source = "filename"
        )
    }

    private fun parseParameterLabel(value: String?): String? {
        val match = value?.let { parameterRegex.find(it) } ?: return null
        return "${match.groupValues[1]}B"
    }

    private fun parseParameterCount(value: String?): Double? {
        return value?.let { parameterRegex.find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull() }
    }

    private fun FileInputStream.skipGgufValue(type: Int) {
        when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> skipFully(1)
            TYPE_UINT16, TYPE_INT16 -> skipFully(2)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> skipFully(4)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> skipFully(8)
            TYPE_STRING -> skipFully(readUInt64().coerceAtMost(MAX_STRING_SKIP))
            TYPE_ARRAY -> {
                val elementType = readUInt32().toInt()
                val count = readUInt64().coerceAtMost(MAX_ARRAY_SKIP)
                repeat(count.toInt()) {
                    skipGgufValue(elementType)
                }
            }

            else -> throw EOFException("Unsupported GGUF metadata type: $type")
        }
    }

    private fun FileInputStream.readGgufString(): String {
        val length = readUInt64().coerceAtMost(MAX_STRING_READ)
        return String(readBytesExact(length.toInt()), Charsets.UTF_8)
    }

    private fun FileInputStream.readUInt32(): Long {
        return ByteBuffer.wrap(readBytesExact(4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    }

    private fun FileInputStream.readUInt64(): Long {
        val value = ByteBuffer.wrap(readBytesExact(8)).order(ByteOrder.LITTLE_ENDIAN).long
        if (value < 0) throw EOFException()
        return value
    }

    private fun FileInputStream.readBytesExact(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(bytes, offset, count - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
        return bytes
    }

    private fun FileInputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12
    private const val MAX_STRING_READ = 16 * 1024L
    private const val MAX_STRING_SKIP = 512 * 1024L
    private const val MAX_ARRAY_SKIP = 64 * 1024L
}
