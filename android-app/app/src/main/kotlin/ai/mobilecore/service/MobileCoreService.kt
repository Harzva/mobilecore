package ai.mobilecore.service

import ai.mobilecore.network.LocalApiServer
import ai.mobilecore.runtime.MockRuntimeBackend
import ai.mobilecore.runtime.ModelManager
import ai.mobilecore.runtime.LoadOptions
import ai.mobilecore.runtime.ModelLoadStatusContract
import com.mobilecore.app.BuildConfig
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.IOException
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD

class MobileCoreService : Service() {
    private val tag = "MobileCoreService"
    private val channelId = "mobilecore-service"
    private val notificationId = 1101

    private lateinit var server: LocalApiServer
    private lateinit var modelManager: ModelManager
    private lateinit var backend: MockRuntimeBackend

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        promoteToForeground("MobileCore local API 启动中")

        backend = MockRuntimeBackend(applicationContext)
        modelManager = ModelManager(backend, applicationContext)
        server = LocalApiServer(
            backend = backend,
            modelManager = modelManager,
            context = applicationContext,
            apiVersion = BuildConfig.VERSION_NAME,
            port = 8080,
            apiKey = "local"
        )

        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: IOException) {
            Log.e(tag, "Failed to start local API server", e)
            stopSelf()
            return
        }

        startWakeLock()
        updateNotification("MobileCore API 可达：127.0.0.1:8080/v1")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Reassert foreground state before any model work on every delivery.
        // This also acknowledges a foreground-start obligation if another
        // entry point uses startForegroundService() in the future.
        promoteToForeground("MobileCore API 可达：127.0.0.1:8080/v1")

        val requestedModelPath = intent?.getStringExtra("modelPath")
        val loadFirstModel = intent?.getBooleanExtra("loadFirstModel", false) == true
        val modelPath = when {
            !requestedModelPath.isNullOrBlank() -> requestedModelPath
            loadFirstModel -> modelManager.firstAvailableModel()?.path
            else -> null
        }

        if (!modelPath.isNullOrBlank()) {
            broadcastModelLoadState(
                state = ModelLoadStatusContract.STATE_LOADING,
                modelPath = modelPath,
            )
            val result = backend.loadModel(modelPath, LoadOptions())
            updateNotification(
                if (result.ok) {
                    "Model loaded: ${result.modelId}"
                } else {
                    "Model load failed: ${result.modelId}"
                }
            )
            broadcastModelLoadState(
                state = if (result.ok) ModelLoadStatusContract.STATE_LOADED else ModelLoadStatusContract.STATE_FAILED,
                modelPath = modelPath,
                modelId = result.modelId,
                message = if (result.ok) "模型已加载" else "运行时未能加载模型",
            )
        }
        return START_STICKY
    }

    private fun broadcastModelLoadState(
        state: String,
        modelPath: String,
        modelId: String? = null,
        message: String? = null,
    ) {
        sendBroadcast(
            Intent(ModelLoadStatusContract.ACTION)
                .setPackage(packageName)
                .putExtra(ModelLoadStatusContract.EXTRA_STATE, state)
                .putExtra(ModelLoadStatusContract.EXTRA_MODEL_PATH, modelPath)
                .putExtra(ModelLoadStatusContract.EXTRA_MODEL_ID, modelId)
                .putExtra(ModelLoadStatusContract.EXTRA_MESSAGE, message)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { server.cancelBackgroundOperations() }
        runCatching { server.stop() }
        backend.unloadModel()
        stopWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    "MobileCore Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "MobileCore local API 与模型推理服务"
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("MobileCore")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        // Keep every notification replacement attached to the foreground service.
        // A plain NotificationManager.notify() update can leave the service record
        // non-foreground on newer Android releases, allowing the local API process
        // to be frozen as soon as another app moves to the foreground.
        promoteToForeground(content)
    }

    private fun promoteToForeground(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun startWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mobilecore:server")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(60 * 60 * 1000L)
    }

    private fun stopWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
