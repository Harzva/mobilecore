package ai.mobilecore.benchmark

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs

data class BenchmarkTelemetrySnapshot(
    val capturedAtMs: Long,
    val batteryPercent: Int,
    val charging: Boolean,
    val batteryTemperatureCelsius: Double?,
    val thermalStatus: ThermalStatus,
    val availableMemoryMb: Long,
    val totalMemoryMb: Long,
    val freeStorageMb: Long
)

class AndroidBenchmarkTelemetry(context: Context) {
    private val appContext = context.applicationContext

    fun sample(): BenchmarkTelemetrySnapshot {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val temperatureTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val batteryPercent = if (level >= 0 && scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            -1
        }
        val charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING

        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val androidThermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
        val storage = StatFs(appContext.filesDir.absolutePath)

        return BenchmarkTelemetrySnapshot(
            capturedAtMs = System.currentTimeMillis(),
            batteryPercent = batteryPercent,
            charging = charging,
            batteryTemperatureCelsius = temperatureTenths
                .takeIf { it != Int.MIN_VALUE && it > 0 }
                ?.div(10.0),
            thermalStatus = ThermalStatus.fromAndroidStatus(androidThermalStatus),
            availableMemoryMb = memory.availMem / BYTES_PER_MB,
            totalMemoryMb = memory.totalMem / BYTES_PER_MB,
            freeStorageMb = storage.availableBytes / BYTES_PER_MB
        )
    }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
