package ai.mobilecore.playground

import android.os.StatFs
import java.io.File

class AndroidPlaygroundInstallEnvironmentProbe(
    private val installDirectory: File,
) : PlaygroundInstallEnvironmentProbe {
    override fun probe(): PlaygroundInstallEnvironment {
        installDirectory.mkdirs()
        return PlaygroundInstallEnvironment(
            availableStorageBytes = StatFs(installDirectory.absolutePath).availableBytes,
        )
    }
}
