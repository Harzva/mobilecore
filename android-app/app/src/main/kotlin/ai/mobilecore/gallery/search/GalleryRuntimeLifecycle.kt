package ai.mobilecore.gallery.search

import java.util.concurrent.Future

/**
 * Process-wide hand-off between Activity instances. A replacement Activity must not open another
 * pair of ONNX sessions until the previous Activity's serialized close operation has completed.
 */
internal object GalleryRuntimeReleaseBarrier {
    private val lock = Any()
    private val releases = mutableListOf<Future<*>>()

    fun register(future: Future<*>) = synchronized(lock) {
        if (releases.none { it === future }) releases += future
    }

    fun pending(): List<Future<*>> = synchronized(lock) {
        releases.removeAll { completedSuccessfully(it) }
        releases.toList()
    }

    fun clear(completed: Future<*>) = synchronized(lock) {
        if (completedSuccessfully(completed)) releases.removeAll { it === completed }
    }

    internal fun resetForTest() = synchronized(lock) {
        releases.clear()
    }

    private fun completedSuccessfully(future: Future<*>): Boolean =
        future.isDone && runCatching { future.get() }.isSuccess
}

internal enum class GalleryEvidenceEnvironment(val wireName: String) {
    ANDROID_EMULATOR("android_emulator"),
    ANDROID_PHYSICAL("android_physical"),
}

/** Conservative environment classifier used by evidence harnesses before making device claims. */
internal object GalleryEvidenceEnvironmentClassifier {
    fun classify(
        fingerprint: String,
        model: String,
        manufacturer: String,
        brand: String,
        device: String,
        product: String,
        hardware: String,
    ): GalleryEvidenceEnvironment {
        val values = listOf(fingerprint, model, manufacturer, brand, device, product, hardware)
            .map { it.lowercase() }
        val emulator = values[0].startsWith("generic") ||
            values[0].contains("emulator") ||
            values[0].contains("unknown") ||
            values[1].contains("google_sdk") ||
            values[1].contains("emulator") ||
            values[1].contains("android sdk") ||
            values[1].contains("sdk_gphone") ||
            values[3].startsWith("generic") ||
            values[4].startsWith("generic") ||
            values[5].contains("sdk_gphone") ||
            values[6].contains("goldfish") ||
            values[6].contains("ranchu")
        return if (emulator) {
            GalleryEvidenceEnvironment.ANDROID_EMULATOR
        } else {
            GalleryEvidenceEnvironment.ANDROID_PHYSICAL
        }
    }
}
