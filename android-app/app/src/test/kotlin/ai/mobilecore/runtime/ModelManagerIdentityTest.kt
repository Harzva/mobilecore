package ai.mobilecore.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelManagerIdentityTest {
    private lateinit var root: File
    private lateinit var internal: File
    private lateinit var external: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("mobilecore-model-identity").toFile()
        internal = root.resolve("internal").apply { mkdirs() }
        external = root.resolve("external").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun duplicatePublicIdFailsClosedWhileExactActivePathSelectsOnlyLoadedFile() {
        val internalModel = internal.resolve("same-q4_k_m.gguf").apply { writeText("internal") }
        val externalModel = external.resolve("same-q4_k_m.gguf").apply { writeText("external") }
        val backend = PathRuntimeBackend(
            activeId = "same-q4_k_m",
            activePath = externalModel.canonicalPath,
        )
        val manager = ModelManager(backend, internal, external)

        val models = manager.scanModels()

        assertEquals(2, models.size)
        assertEquals(listOf(externalModel.canonicalPath), models.filter { it.loaded }.map { File(it.path).canonicalPath })
        assertFalse(manager.isExactPathLoaded(internalModel.absolutePath))
        assertTrue(manager.isExactPathLoaded(externalModel.absolutePath))
        assertEquals(externalModel.canonicalPath, File(requireNotNull(manager.activeModel()).path).canonicalPath)
        assertNull(manager.modelById("same-q4_k_m"))
        assertEquals(
            externalModel.canonicalPath,
            File(requireNotNull(manager.modelByPath(externalModel.absolutePath)).path).canonicalPath,
        )
    }

    @Test
    fun basenameMetricWithoutRuntimePathDoesNotMarkEitherDuplicateLoaded() {
        internal.resolve("same-q4_k_m.gguf").writeText("internal")
        external.resolve("same-q4_k_m.gguf").writeText("external")
        val manager = ModelManager(
            PathRuntimeBackend(activeId = "same-q4_k_m", activePath = null),
            internal,
            external,
        )

        assertTrue(manager.scanModels().none { it.loaded })
        assertNull(manager.activeModel())
        assertNull(manager.activeModelPath())
    }

    @Test
    fun duplicateProjectorIdRequiresExactPathLookup() {
        internal.resolve("mmproj-same-bf16.gguf").writeText("internal-projector")
        val externalProjector = external.resolve("mmproj-same-bf16.gguf").apply {
            writeText("external-projector")
        }
        val manager = ModelManager(PathRuntimeBackend(null, null, loaded = false), internal, external)

        assertNull(manager.projectorById("mmproj-same-bf16"))
        assertEquals(
            externalProjector.canonicalPath,
            File(requireNotNull(manager.projectorByPath(externalProjector.absolutePath)).path).canonicalPath,
        )
    }
}

private class PathRuntimeBackend(
    private val activeId: String?,
    private val activePath: String?,
    private val loaded: Boolean = true,
) : RuntimeBackend {
    override fun backendInfo() = BackendInfo("test", "jvm", "test", emptyList(), listOf("cpu"), "ok")
    override fun loadModel(modelPath: String, options: LoadOptions) = LoadResult(false, "none", 0L, 0L)
    override fun unloadModel() = false
    override fun isModelLoaded() = loaded
    override fun activeModelPath() = activePath
    override fun chat(messages: List<ChatMessage>, options: ChatOptions) =
        ChatResult(model = options.model, message = "not used")

    override fun streamChat(messages: List<ChatMessage>, options: ChatOptions): Sequence<ChatToken> =
        emptySequence()

    override fun metrics() = RuntimeMetrics(activeModel = activeId, backend = "test")
}
