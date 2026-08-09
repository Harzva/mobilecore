package ai.mobilecore.gallery.search

import org.json.JSONObject
import java.io.File
import java.io.EOFException
import java.util.LinkedHashMap

data class ClipTokenizedText(
    val inputIds: LongArray,
    val attentionMask: LongArray,
)

/** Minimal OpenAI CLIP byte-level BPE tokenizer for on-device text-encoder inference. */
class ClipBpeTokenizer private constructor(
    private val vocabulary: Map<String, Int>,
    private val mergeRanks: Map<Pair<String, String>, Int>,
    private val contextLength: Int,
) {
    private val byteEncoder: Map<Int, String> = byteToUnicode()
    private val cache = object : LinkedHashMap<String, List<String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean =
            size > 2_048
    }
    private val bosId = requireNotNull(vocabulary[START_TOKEN]) { "CLIP BOS token is missing." }
    private val eosId = requireNotNull(vocabulary[END_TOKEN]) { "CLIP EOS token is missing." }

    fun tokenize(text: String): ClipTokenizedText {
        val normalized = text.trim().replace(Regex("\\s+"), " ").lowercase()
        require(normalized.isNotEmpty()) { "CLIP query is empty." }
        val contentIds = ArrayList<Int>()
        TOKEN_PATTERN.findAll(normalized).forEach { match ->
            val encoded = buildString {
                match.value.toByteArray(Charsets.UTF_8).forEach { byte ->
                    append(requireNotNull(byteEncoder[byte.toInt() and 0xff]))
                }
            }
            bpe(encoded).forEach { token -> contentIds += vocabulary[token] ?: eosId }
        }
        val retained = contentIds.take((contextLength - 2).coerceAtLeast(0))
        val ids = LongArray(contextLength) { eosId.toLong() }
        val attention = LongArray(contextLength)
        ids[0] = bosId.toLong()
        attention[0] = 1L
        retained.forEachIndexed { index, id ->
            ids[index + 1] = id.toLong()
            attention[index + 1] = 1L
        }
        val eosPosition = retained.size + 1
        ids[eosPosition] = eosId.toLong()
        attention[eosPosition] = 1L
        return ClipTokenizedText(ids, attention)
    }

    @Synchronized
    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        if (token.isEmpty()) return emptyList()
        val word = token.map(Char::toString).toMutableList()
        word[word.lastIndex] = word.last() + END_OF_WORD
        while (word.size > 1) {
            val rankedPair = word.zipWithNext()
                .mapNotNull { pair -> mergeRanks[pair]?.let { rank -> pair to rank } }
                .minByOrNull { it.second }
                ?.first
                ?: break
            val merged = ArrayList<String>(word.size)
            var index = 0
            while (index < word.size) {
                if (index < word.lastIndex &&
                    word[index] == rankedPair.first && word[index + 1] == rankedPair.second
                ) {
                    merged += word[index] + word[index + 1]
                    index += 2
                } else {
                    merged += word[index]
                    index += 1
                }
            }
            word.clear()
            word += merged
        }
        return word.toList().also { cache[token] = it }
    }

    companion object {
        private const val START_TOKEN = "<|startoftext|>"
        private const val END_TOKEN = "<|endoftext|>"
        private const val END_OF_WORD = "</w>"
        private val TOKEN_PATTERN = Regex(
            "(?i)<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
        )

        fun open(tokenizerDirectory: File): ClipBpeTokenizer {
            val vocabFile = File(tokenizerDirectory, "vocab.json")
            val mergesFile = File(tokenizerDirectory, "merges.txt")
            val configFile = File(tokenizerDirectory, "tokenizer_config.json")
            require(vocabFile.isFile && mergesFile.isFile && configFile.isFile) {
                "CLIP tokenizer requires vocab.json, merges.txt and tokenizer_config.json."
            }
            val vocabJson = JSONObject(vocabFile.readUtf8Bounded(MAX_VOCAB_BYTES, "vocab.json"))
            require(vocabJson.length() in 2..MAX_VOCAB_ENTRIES) {
                "CLIP vocabulary entry count exceeds the audited budget."
            }
            val vocabulary = buildMap(vocabJson.length()) {
                vocabJson.keys().forEach { token ->
                    require(token.length <= MAX_TOKEN_LENGTH) { "CLIP vocabulary token is too long." }
                    val id = vocabJson.getInt(token)
                    require(id in 0..MAX_TOKEN_ID) { "CLIP vocabulary token ID is invalid." }
                    put(token, id)
                }
            }
            val mergeRanks = linkedMapOf<Pair<String, String>, Int>()
            mergesFile.readUtf8Bounded(MAX_MERGES_BYTES, "merges.txt")
                .lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                    .forEachIndexed { rank, line ->
                        require(rank < MAX_MERGE_ENTRIES) {
                            "CLIP merge entry count exceeds the audited budget."
                        }
                        require(line.length <= MAX_MERGE_LINE_LENGTH) {
                            "CLIP BPE merge line is too long."
                        }
                        val parts = line.trim().split(Regex("\\s+"))
                        require(parts.size == 2) { "Invalid CLIP BPE merge at rank $rank." }
                        require(parts.all { it.length <= MAX_TOKEN_LENGTH }) {
                            "CLIP BPE merge token is too long."
                        }
                        mergeRanks[parts[0] to parts[1]] = rank
                    }
            val config = JSONObject(configFile.readUtf8Bounded(MAX_CONFIG_BYTES, "tokenizer_config.json"))
            require(config.length() <= MAX_CONFIG_ENTRIES) {
                "CLIP tokenizer config entry count exceeds the audited budget."
            }
            val length = config.optInt("model_max_length", 77)
            require(length in 2..512) { "Invalid CLIP tokenizer context length." }
            return ClipBpeTokenizer(vocabulary, mergeRanks, length)
        }

        internal fun fromForTest(
            vocabulary: Map<String, Int>,
            merges: List<Pair<String, String>>,
            contextLength: Int = 77,
        ): ClipBpeTokenizer = ClipBpeTokenizer(
            vocabulary,
            merges.withIndex().associate { it.value to it.index },
            contextLength,
        )

        private fun byteToUnicode(): Map<Int, String> {
            val visible = (33..126) + (161..172) + (174..255)
            val bytes = visible.toMutableList()
            val codePoints = visible.toMutableList()
            var extra = 0
            for (value in 0..255) {
                if (value !in bytes) {
                    bytes += value
                    codePoints += 256 + extra
                    extra += 1
                }
            }
            return bytes.indices.associate { index ->
                bytes[index] to String(Character.toChars(codePoints[index]))
            }
        }

        internal const val MAX_VOCAB_BYTES = 8L * 1024L * 1024L
        internal const val MAX_MERGES_BYTES = 8L * 1024L * 1024L
        internal const val MAX_CONFIG_BYTES = 256L * 1024L
        internal const val MAX_VOCAB_ENTRIES = 100_000
        internal const val MAX_MERGE_ENTRIES = 100_000
        internal const val MAX_CONFIG_ENTRIES = 256
        private const val MAX_TOKEN_LENGTH = 2_048
        private const val MAX_MERGE_LINE_LENGTH = 4_096
        private const val MAX_TOKEN_ID = 2_000_000
    }
}

/** Reads exactly one bounded UTF-8 artifact so a size check cannot be raced by file growth. */
private fun File.readUtf8Bounded(maxBytes: Long, label: String): String {
    val expected = length()
    require(expected in 1..maxBytes) { "$label exceeds the audited byte budget." }
    val bytes = ByteArray(expected.toInt())
    inputStream().buffered().use { input ->
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw EOFException("$label changed while it was being validated.")
            offset += count
        }
        require(input.read() == -1) { "$label grew beyond the audited byte budget." }
    }
    return bytes.toString(Charsets.UTF_8)
}
