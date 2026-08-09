package ai.mobilecore.gallery.search

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ClipBpeTokenizerCompatibilityTest {
    @Test
    fun `mandatory minimal golden matches Hugging Face English and Chinese reference tokens`() {
        assertReferenceTokens(minimalGoldenTokenizer())
    }

    @Test
    fun `optional full OpenAI CLIP tokenizer matches the same reference tokens`() {
        val configured = System.getProperty("clipTokenizerDir")?.let(::File)
        val discovered = generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) {
            it.parentFile
        }.map { root -> File(root, "tmp/g2d-models/clip-source") }
        val tokenizerDirectory = listOfNotNull(configured).asSequence()
            .plus(discovered)
            .firstOrNull { File(it, "vocab.json").isFile }
        assumeTrue("Local CLIP tokenizer artifacts are optional in CI.", tokenizerDirectory != null)
        assertReferenceTokens(ClipBpeTokenizer.open(requireNotNull(tokenizerDirectory)))
    }

    private fun assertReferenceTokens(tokenizer: ClipBpeTokenizer) {
        val english = tokenizer.tokenize("a photo of a cat")
        assertArrayEquals(
            longArrayOf(49406, 320, 1125, 539, 320, 2368, 49407),
            english.inputIds.take(7).toLongArray(),
        )
        assertEquals(7L, english.attentionMask.sum())

        val chinese = tokenizer.tokenize("海边穿红衣服的人")
        assertArrayEquals(
            longArrayOf(
                49406, 162, 113, 115, 164, 122, 117, 163, 102, 123, 163, 118,
                95, 164, 94, 96, 19277, 235, 163, 248, 226, 21078, 374, 49407,
            ),
            chinese.inputIds.take(24).toLongArray(),
        )
        assertEquals(24L, chinese.attentionMask.sum())
    }

    /**
     * Frozen subset extracted from onnx-community/clip-vit-base-patch16-ONNX. It keeps the
     * reference test mandatory in CI without committing the 1.3 MB upstream tokenizer bundle.
     */
    private fun minimalGoldenTokenizer(): ClipBpeTokenizer = ClipBpeTokenizer.fromForTest(
        vocabulary = mapOf(
            "<|startoftext|>" to 49406,
            "<|endoftext|>" to 49407,
            "a</w>" to 320,
            "photo</w>" to 1125,
            "of</w>" to 539,
            "cat</w>" to 2368,
            "æ" to 162,
            "µ" to 113,
            "·" to 115,
            "è" to 164,
            "¾" to 122,
            "¹" to 117,
            "ç" to 163,
            "©" to 102,
            "¿" to 123,
            "º" to 118,
            "¢" to 95,
            "¡" to 94,
            "£" to 96,
            "æľ" to 19277,
            "į" to 235,
            "ļ" to 248,
            "Ħ" to 226,
            "äº" to 21078,
            "º</w>" to 374,
        ),
        merges = listOf(
            "t" to "o</w>",
            "a" to "t</w>",
            "o" to "f</w>",
            "h" to "o",
            "p" to "ho",
            "pho" to "to</w>",
            "c" to "at</w>",
            "æ" to "ľ",
            "ä" to "º",
        ),
    )
}
