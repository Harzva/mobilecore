package ai.mobilecore.playground

import android.app.Application
import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide installer ownership keeps UI recreation and trust bootstrap on the same state. */
object PlaygroundInstallerRegistry {
    private val installers = ConcurrentHashMap<String, PlaygroundArtifactInstaller>()

    fun getOrCreate(context: Context, entry: PlaygroundCatalogEntry): PlaygroundArtifactInstaller? {
        if (!entry.distribution.downloadable || entry.distribution.installTransport != "https_direct") {
            return null
        }
        installers[entry.id]?.let { return it }
        val appContext = context.applicationContext
        val modelDirectory = File(appContext.filesDir, "models")
        val installer = runCatching {
            PlaygroundArtifactInstaller(
                installDirectory = modelDirectory,
                environmentProbe = AndroidPlaygroundInstallEnvironmentProbe(modelDirectory),
                spec = PlaygroundInstallSpec.fromCatalogEntry(entry),
            )
        }.getOrNull() ?: return null
        return installers.putIfAbsent(entry.id, installer) ?: installer
    }

    fun values(): List<PlaygroundArtifactInstaller> = installers.values.toList()

    fun byModelId(modelId: String): PlaygroundArtifactInstaller? =
        installers[modelId.trim()]

    fun forManagedModelPath(path: String): PlaygroundArtifactInstaller? =
        installers.values.filter { it.managesModelPath(path) }.singleOrNull()

    internal fun clearForTests() {
        installers.clear()
    }
}

/**
 * Sequentially restores process-local digest proofs for catalog artifacts that have persisted,
 * stat-bound verification metadata. GET /health only reads this registry and never hashes.
 */
internal class PlaygroundTrustBootstrap(
    private val installers: () -> List<PlaygroundArtifactInstaller>,
) {
    fun start(): Thread = Thread(
        {
            installers().forEach { installer ->
                if (!installer.beginStartupVerification()) return@forEach
                val handle = installer.verifyInstalled()
                if (handle.started) {
                    handle.await(Long.MAX_VALUE)
                } else {
                    installer.releaseStartupVerificationClaim()
                }
            }
        },
        "mobilecore-playground-trust-bootstrap",
    ).apply {
        isDaemon = true
        start()
    }
}

internal object PlaygroundProcessTrustBootstrap {
    private val started = AtomicBoolean(false)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val entries = runCatching { PlaygroundCatalogRepository(appContext).load().entries }
            .getOrElse {
                started.set(false)
                return
            }
        val catalogInstallers = entries.mapNotNull {
            PlaygroundInstallerRegistry.getOrCreate(appContext, it)
        }
        PlaygroundTrustBootstrap { catalogInstallers }.start()
    }
}

class MobileCoreApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PlaygroundProcessTrustBootstrap.start(this)
    }
}
