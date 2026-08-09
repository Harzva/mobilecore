package ai.mobilecore.playground

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object PlaygroundManagedArtifactPolicy {
    fun isManagedFileName(catalog: PlaygroundCatalog, fileName: String): Boolean =
        catalog.entries.asSequence()
            .flatMap { it.requiredArtifactNames.asSequence() }
            .any { it.equals(fileName, ignoreCase = true) }
}

/** Never exposes a partially copied or overwrite-prone ordinary GGUF in the runtime directory. */
internal object AtomicGgufImport {
    fun copy(source: InputStream, temporary: File, destination: File) {
        val tempParent = temporary.canonicalFile.parentFile
        val destinationParent = destination.canonicalFile.parentFile
        require(tempParent == destinationParent) { "temporary and destination paths must share one directory" }
        require(!destination.exists()) { "destination already exists" }
        require(!temporary.exists()) { "temporary import path already exists" }
        destinationParent?.mkdirs()
        try {
            FileOutputStream(temporary, false).use { output ->
                source.copyTo(output)
                output.fd.sync()
            }
            require(temporary.length() >= GGUF_MAGIC.size) { "GGUF file is empty or truncated" }
            val magic = ByteArray(GGUF_MAGIC.size)
            FileInputStream(temporary).use { input ->
                require(input.read(magic) == magic.size && magic.contentEquals(GGUF_MAGIC)) {
                    "imported file is not GGUF"
                }
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
}
