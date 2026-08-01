package ai.mobilecore.omni.artifact

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import java.io.File

class AndroidOmniInstallEnvironmentProbe(
    context: Context,
    private val installDirectory: File
) : OmniInstallEnvironmentProbe {
    private val applicationContext = context.applicationContext

    override fun probe(): OmniInstallEnvironment {
        installDirectory.mkdirs()
        val memoryInfo = ActivityManager.MemoryInfo()
        applicationContext.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        return OmniInstallEnvironment(
            availableMemoryBytes = memoryInfo.availMem,
            availableStorageBytes = StatFs(installDirectory.absolutePath).availableBytes,
            wifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        )
    }
}
