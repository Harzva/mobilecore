package ai.mobilecore

import ai.mobilecore.service.MobileCoreService
import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val PREF_RECOMMENDATION_MODE = "recommendation_preference"

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var runtimeChipText: TextView
    private lateinit var probeDeviceText: TextView
    private lateinit var probeMemoryText: TextView
    private lateinit var probeCpuText: TextView
    private lateinit var probeBackendText: TextView
    private lateinit var preferenceLabelText: TextView
    private lateinit var recommendationContainer: LinearLayout
    private lateinit var rootScrollView: ScrollView
    private var modelsAnchor: View? = null
    private var metricsAnchor: View? = null
    private var apiAnchor: View? = null
    private var recommendationPreference = RecommendationPreference.STABILITY
    private val serviceHost = "127.0.0.1"
    private val servicePort = 8080
    private val notificationPermissionRequestCode = 1001
    private val importModelRequestCode = 1002
    private var pendingAfterNotificationPermission: (() -> Unit)? = null
    private val pendingDownloads = mutableMapOf<Long, File>()
    private val modelHubItems = listOf(
        ModelHubItem(
            provider = "HuggingFace",
            shortName = "Qwen2.5 0.5B Q4",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"
        ),
        ModelHubItem(
            provider = "ModelScope",
            shortName = "Qwen2.5 0.5B Q4",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            url = "https://modelscope.cn/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/master/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        )
    )
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val destination = pendingDownloads.remove(downloadId) ?: return
            handleModelDownloadComplete(downloadId, destination)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recommendationPreference = readRecommendationPreference()
        actionBar?.hide()
        window.statusBarColor = Palette.background
        window.navigationBarColor = Color.WHITE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        rootScrollView = ScrollView(this).apply {
            setBackgroundColor(Palette.background)
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(18))
        }

        content.addView(buildHeader())
        content.addView(space(14))
        content.addView(buildHeroCard())
        content.addView(space(14))
        content.addView(sectionTitle("模型获取", "HuggingFace · ModelScope"))
        content.addView(buildModelHubCard())
        content.addView(space(18))
        content.addView(sectionTitle("本机体检", "根据设备能力给出推荐"))
        content.addView(buildDeviceProbeCard())
        content.addView(space(18))
        content.addView(sectionTitle("推荐模型", "优先推荐可稳定运行的 GGUF"))
        content.addView(buildRecommendationCard())
        content.addView(space(18))
        val metricsTitle = sectionTitle("Runtime Snapshot", "Local model status")
        metricsAnchor = metricsTitle
        content.addView(metricsTitle)
        content.addView(buildMetricStrip())
        content.addView(space(18))
        content.addView(sectionTitle("Quick Actions", "Start API, import GGUF, load model"))
        content.addView(buildActionGrid())
        content.addView(space(18))
        val modelsCard = buildRecentModelsCard()
        modelsAnchor = modelsCard
        content.addView(modelsCard)
        content.addView(space(18))
        val statusCard = buildStatusCard()
        apiAnchor = statusCard
        content.addView(statusCard)
        content.addView(space(18))
        content.addView(buildBottomNavigation())

        rootScrollView.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(rootScrollView)
        ContextCompat.registerReceiver(
            this,
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshRecommendationSnapshot()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(downloadCompleteReceiver) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshRecommendationSnapshot()
    }

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(
                PushBoxMarkView(context),
                LinearLayout.LayoutParams(dp(86), dp(68))
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        LinearLayout(context).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            addView(
                                label("Tui", 34f, Palette.ink, Typeface.BOLD),
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                            )
                            addView(
                                label("Ma", 34f, Palette.blue, Typeface.BOLD),
                                LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                            )
                        }
                    )
                    addView(label("推嘛 · MobileCore Runtime", 13f, Palette.muted, Typeface.NORMAL))
                    addView(space(8))
                    runtimeChipText = label("On-device LLM Ready", 13f, Palette.mintDark, Typeface.BOLD)
                    addView(
                        chip(runtimeChipText, Palette.mintPale, Palette.mint),
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            dp(34)
                        )
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    private fun buildHeroCard(): View {
        return FrameLayout(this).apply {
            background = roundedGradient(
                intArrayOf(Palette.mintWash, Palette.blueWash, Palette.lavenderWash),
                18f
            )
            elevation = dp(3).toFloat()
            setPadding(dp(18), dp(18), dp(16), dp(16))

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, dp(106), 0)
                    addView(label("Run model\non your phone", 24f, Palette.ink, Typeface.BOLD))
                    addView(space(7))
                    addView(label("Import GGUF, run a local API, and keep prompts on device.", 13f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
                    addView(space(12))
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            addView(
                                pillButton("Start API", Palette.sky, Palette.blue) {
                                    ensureNotificationPermissionAndStartService()
                                },
                                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
                            )
                            addView(
                                pillButton("Import", Palette.mintDark, Palette.mint) {
                                    openGgufPicker()
                                },
                                LinearLayout.LayoutParams(0, dp(48), 1f)
                            )
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    )
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START)
            )

            addView(
                PushBoxMarkView(context).apply { alpha = 0.96f },
                FrameLayout.LayoutParams(dp(98), dp(78), Gravity.END or Gravity.BOTTOM).apply {
                    bottomMargin = dp(18)
                }
            )
        }.apply {
            minimumHeight = dp(210)
        }
    }

    private fun buildMetricStrip(): View {
        val model = findFirstGguf()
        val loadedLabel = model?.let { displayModelName(it.nameWithoutExtension) } ?: "No GGUF yet"
        val modelSize = model?.let { formatBytes(it.length()) } ?: "Import model"
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(metricCard("Loaded Model", loadedLabel, modelSize, "cube", Palette.mint))
            addView(metricCard("Memory Use", modelSize, "model file", "chip", Palette.lavender))
            addView(metricCard("API Status", "Offline", "Tap Start API", "cloud", Palette.sky))
            addView(metricCard("Last Speed", "0.0 tok/s", "after chat", "gauge", Palette.blue))
        }
        scroll.addView(row)
        return scroll
    }

    private fun buildModelHubCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.surface, Palette.stroke, 18f)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(16), dp(16), dp(14))
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label("模型站", 18f, Palette.ink, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label("GGUF 下载", 13f, Palette.muted, Typeface.BOLD))
                }
            )
            addView(space(10))
            addView(
                actionRow(
                    actionTile("HuggingFace", "Qwen 0.5B", "download", Palette.blue) {
                        enqueueModelDownload(modelHubItems.first { it.provider == "HuggingFace" })
                    },
                    actionTile("ModelScope", "国内镜像", "download", Palette.mintDark) {
                        enqueueModelDownload(modelHubItems.first { it.provider == "ModelScope" })
                    }
                )
            )
            addView(space(10))
            addView(label("下载到应用模型库，完成后可直接加载。", 12f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
        }
    }

    private fun buildDeviceProbeCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.surface, Palette.stroke, 18f)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(16), dp(16), dp(14))
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label("Device Probe", 18f, Palette.ink, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label("Local-first", 13f, Palette.muted, Typeface.BOLD))
                }
            )
            addView(space(10))
            val scroll = HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
            }
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val deviceProfile = probeDeviceProfile()
            row.addView(
                metricCardWithValue(
                    "Device",
                    "${deviceProfile.manufacturer}/${deviceProfile.model}",
                    "CPU + vendor",
                    "cube",
                    Palette.mint
                ) { valueText ->
                    probeDeviceText = valueText
                }
            )
            row.addView(
                metricCardWithValue(
                    "RAM",
                    "${deviceProfile.availableRamMb} / ${deviceProfile.totalRamMb} MB",
                    "可用/总量",
                    "chip",
                    Palette.sky
                ) { valueText ->
                    probeMemoryText = valueText
                }
            )
            row.addView(
                metricCardWithValue(
                    "CPU",
                    deviceProfile.coreCount.toString(),
                    "logical cores",
                    "gauge",
                    Palette.lavender
                ) { valueText ->
                    probeCpuText = valueText
                }
            )
            row.addView(
                metricCardWithValue(
                    "Backend",
                    deviceProfile.backend,
                    "runtime",
                    "cloud",
                    Palette.blue
                ) { valueText ->
                    probeBackendText = valueText
                }
            )
            scroll.addView(row)
            addView(scroll)
            addView(space(6))
        }
    }

    private fun buildRecommendationCard(): View {
        recommendationContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        renderRecommendationPlaceholder("启动 API 后加载推荐；也可以先从模型站下载 GGUF。")

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.surface, Palette.stroke, 18f)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(16), dp(16), dp(14))
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label("Top recommendations", 18f, Palette.ink, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label("MobileCore", 13f, Palette.muted, Typeface.BOLD))
                }
            )
            addView(space(10))
            addView(buildPreferenceControl())
            addView(space(10))
            addView(recommendationContainer)
        }
    }

    private fun buildPreferenceControl(): View {
        val captions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("速度优先", 11f, Palette.muted, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("稳定优先", 11f, Palette.muted, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("小模型优先", 11f, Palette.muted, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.mintPale, tint(Palette.mint, 0.20f), 16f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label("Preference", 12f, Palette.muted, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    preferenceLabelText = label(recommendationPreference.label, 13f, Palette.mintDark, Typeface.BOLD)
                    addView(preferenceLabelText)
                }
            )
            addView(space(8))
            addView(
                SeekBar(context).apply {
                    max = 2
                    progress = recommendationPreference.progress
                    progressTintList = ColorStateList.valueOf(Palette.mintDark)
                    thumbTintList = ColorStateList.valueOf(Palette.blue)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            recommendationPreference = RecommendationPreference.fromProgress(progress)
                            updatePreferenceLabel()
                            if (fromUser) {
                                saveRecommendationPreference(recommendationPreference)
                                renderRecommendationPlaceholder("按${recommendationPreference.label}刷新推荐中...")
                                refreshRecommendationSnapshot()
                            }
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            )
            addView(captions)
        }
    }

    private fun updatePreferenceLabel() {
        if (::preferenceLabelText.isInitialized) {
            preferenceLabelText.text = recommendationPreference.label
        }
    }

    private fun readRecommendationPreference(): RecommendationPreference {
        val stored = getPreferences(MODE_PRIVATE).getString(PREF_RECOMMENDATION_MODE, null)
        return RecommendationPreference.fromQueryValue(stored)
    }

    private fun saveRecommendationPreference(preference: RecommendationPreference) {
        getPreferences(MODE_PRIVATE)
            .edit()
            .putString(PREF_RECOMMENDATION_MODE, preference.queryValue)
            .apply()
    }

    private fun metricCardWithValue(
        title: String,
        value: String,
        caption: String,
        icon: String,
        accent: Int,
        bindValue: (TextView) -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.surface, Palette.stroke, 18f)
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(14), dp(14), dp(12))
            addView(IconBadgeView(context, icon, accent), LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(space(12))
            addView(label(title, 12f, Palette.muted, Typeface.BOLD))
            addView(space(7))
            val valueText = label(value, 16f, Palette.ink, Typeface.BOLD).apply {
                maxLines = 2
            }
            bindValue(valueText)
            addView(valueText)
            addView(space(4))
            addView(label(caption, 12f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(136), dp(162)).apply {
                marginEnd = dp(12)
            }
        }
    }

    private fun metricCard(title: String, value: String, caption: String, icon: String, accent: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.surface, Palette.stroke, 18f)
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(14), dp(14), dp(12))
            addView(IconBadgeView(context, icon, accent), LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(space(12))
            addView(label(title, 12f, Palette.muted, Typeface.BOLD))
            addView(space(7))
            addView(label(value, 18f, Palette.ink, Typeface.BOLD).apply { maxLines = 2 })
            addView(space(4))
            addView(label(caption, 12f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(136), dp(162)).apply {
                marginEnd = dp(12)
            }
        }
    }

    private fun buildActionGrid(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                actionRow(
                    actionTile("Import GGUF", "Choose model file", "cube", Palette.mint) { openGgufPicker() },
                    actionTile("Start API", "Open localhost", "play", Palette.blue) {
                        ensureNotificationPermissionAndStartService()
                    }
                )
            )
            addView(space(12))
            addView(
                actionRow(
                    actionTile("Load Model", "First available GGUF", "gauge", Palette.lavender) {
                        ensureNotificationPermissionAndLoadFirstModel()
                    },
                    actionTile("Stop API", "Close service", "stop", Palette.sky) { stopMobileCoreService() }
                )
            )
        }
    }

    private fun actionRow(left: View, right: View): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, dp(112), 1f).apply { marginEnd = dp(6) })
            addView(right, LinearLayout.LayoutParams(0, dp(112), 1f).apply { marginStart = dp(6) })
        }
    }

    private fun actionTile(
        title: String,
        caption: String,
        icon: String,
        accent: Int,
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(rounded(tint(accent, 0.10f), Color.TRANSPARENT, 20f), accent)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(IconBadgeView(context, icon, accent), LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(space(10))
            addView(label(title, 15f, Palette.ink, Typeface.BOLD).apply { maxLines = 1 })
            addView(space(3))
            addView(label(caption, 12f, Palette.muted, Typeface.NORMAL).apply { maxLines = 1 })
        }
    }

    private fun buildRecentModelsCard(): View {
        val model = findFirstGguf()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.surface, Palette.stroke, 20f)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(16), dp(16), dp(14))
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label("Recent Models", 18f, Palette.ink, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label("GGUF", 13f, Palette.muted, Typeface.BOLD))
                }
            )
            addView(space(12))
            if (model != null) {
                addView(modelRow(model.nameWithoutExtension, "${formatBytes(model.length())} · GGUF", "Available", Palette.mint))
            } else {
                addView(modelRow("No local model", externalModelDir().absolutePath, "Empty", Palette.sky))
            }
            addView(thinDivider())
            addView(modelRow("Qwen2.5 0.5B", "Recommended tiny QA model", "Cached", Palette.lavender))
        }
    }

    private fun buildStatusCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.surface, Palette.stroke, 18f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(label("Endpoint", 12f, Palette.muted, Typeface.BOLD))
            addView(space(6))
            statusText = label("Local API URL: http://$serviceHost:$servicePort/v1", 14f, Palette.ink, Typeface.BOLD)
            addView(statusText)
            addView(space(4))
            addView(label("OpenAI-compatible routes stay on device.", 12f, Palette.muted, Typeface.NORMAL))
        }
    }

    private fun refreshRecommendationSnapshot() {
        Thread {
            var lastError: Exception? = null
            repeat(3) {
                try {
                    val url = "http://$serviceHost:$servicePort/v1/recommendations?preference=${recommendationPreference.queryValue}"
                    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer local")
                        connectTimeout = 1200
                        readTimeout = 1200
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader()?.readText() ?: "{}"
                    if (status in 200..299) {
                        applyRecommendationPayload(JSONObject(body))
                        return@Thread
                    }
                    lastError = Exception("status=$status body=$body")
                } catch (e: Exception) {
                    lastError = e
                }
                Thread.sleep(300)
            }
            runOnUiThread {
                renderRecommendationPlaceholder(lastError?.message ?: "推荐接口暂不可用")
            }
        }.start()
    }

    private fun applyRecommendationPayload(json: JSONObject) {
        val device = json.optJSONObject("device") ?: JSONObject()
        val profileText = "${device.optString("manufacturer", Build.MANUFACTURER)} ${device.optString("model", Build.MODEL)}"
        val ramText = "${device.optLong("available_ram_mb", 0L)} / ${device.optLong("total_ram_mb", 0L)} MB"

        val recommendations = json.optJSONArray("recommendations") ?: JSONArray()
        val backend = json.optJSONObject("runtime") ?: JSONObject()

        runOnUiThread {
            probeDeviceText.text = profileText
            probeMemoryText.text = ramText
            probeCpuText.text = "${device.optInt("core_count", Runtime.getRuntime().availableProcessors())} cores"
            probeBackendText.text = displayBackendName(backend.optString("backend", device.optString("backend", "llama.cpp")))

            recommendationContainer.removeAllViews()
            if (recommendations.length() == 0) {
                renderRecommendationPlaceholder("已连接服务，但未检测到 GGUF。可先导入模型。")
                return@runOnUiThread
            }

            for (i in 0 until recommendations.length()) {
                val recommendation = recommendations.optJSONObject(i) ?: continue
                val modelId = recommendation.optString("model_id", "unknown")
                val path = recommendation.optString("path", "")
                val fit = recommendation.optString("fit", "marginal")
                val score = recommendation.optDouble("score", 0.0)
                val expected = recommendation.optDouble("expected_tokens_per_second", 0.0)
                val loaded = recommendation.optBoolean("loaded", false)
                val reasonArray = recommendation.optJSONArray("reasons")
                val reason = if (reasonArray == null || reasonArray.length() == 0) {
                    "no extra reason"
                } else {
                    (0 until reasonArray.length()).joinToString(" · ") { idx ->
                        reasonArray.optString(idx)
                    }
                }
                recommendationContainer.addView(
                    buildRecommendationRow(
                        modelId = modelId,
                        path = path,
                        score = score,
                        fit = fit,
                        estimatedMemoryMb = recommendation.optLong("estimated_memory_mb", 0L),
                        expectedTokensPerSecond = expected,
                        loaded = loaded,
                        reason = reason
                    )
                )
            }
        }
    }

    private fun renderRecommendationPlaceholder(message: String) {
        recommendationContainer.removeAllViews()
        recommendationContainer.addView(
            label(message, 13f, Palette.muted, Typeface.NORMAL).apply {
                setPadding(0, dp(2), 0, dp(2))
            }
        )
    }

    private fun enqueueModelDownload(item: ModelHubItem) {
        val destination = File(externalModelDir(), item.fileName)
        if (destination.exists() && destination.length() > 1024 * 1024) {
            Toast.makeText(this, "${item.shortName} already downloaded", Toast.LENGTH_SHORT).show()
            ensureNotificationPermissionAndLoadModel(destination)
            return
        }

        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()
        val request = DownloadManager.Request(Uri.parse(item.url)).apply {
            setTitle("TuiMa ${item.shortName}")
            setDescription("${item.provider} · ${item.fileName}")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(destination))
        }
        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        pendingDownloads[downloadId] = destination
        updateStatus("Downloading ${item.shortName} from ${item.provider}")
        Toast.makeText(this, "${item.provider} download started", Toast.LENGTH_SHORT).show()
    }

    private fun handleModelDownloadComplete(downloadId: Long, destination: File) {
        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                updateStatus("Download finished, but status is unknown")
                return
            }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL && destination.exists()) {
                updateStatus("Downloaded ${destination.name}")
                Toast.makeText(this, "Downloaded ${destination.name}", Toast.LENGTH_SHORT).show()
                ensureNotificationPermissionAndLoadModel(destination)
                refreshRecommendationSnapshot()
            } else {
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                updateStatus("Download failed · reason=$reason")
                Toast.makeText(this, "Model download failed", Toast.LENGTH_LONG).show()
                if (destination.exists()) destination.delete()
            }
        }
    }

    private fun buildRecommendationRow(
        modelId: String,
        path: String,
        score: Double,
        fit: String,
        estimatedMemoryMb: Long,
        expectedTokensPerSecond: Double,
        loaded: Boolean,
        reason: String
    ): View {
        val scoreText = String.format(Locale.US, "%.1f", score)
        val speedText = String.format(Locale.US, "%.2f", expectedTokensPerSecond)
        val accent = when (fit.lowercase()) {
            "perfect" -> Palette.mint
            "good" -> Palette.sky
            "marginal" -> Palette.lavender
            else -> Palette.blue
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.surface, Palette.stroke, 16f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        modelRow(
                            modelId,
                            "fit=$fit, score=$scoreText",
                            fit.uppercase(),
                            accent
                        ),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    if (!loaded) {
                        addView(
                            pillButton("Load", Palette.sky, Palette.blue) {
                                if (path.isNotBlank()) {
                                    loadRecommendedModel(path)
                                } else {
                                    Toast.makeText(this@MainActivity, "模型路径为空，无法加载", Toast.LENGTH_SHORT).show()
                                }
                            }.apply {
                                gravity = Gravity.CENTER
                                setPadding(0, dp(4), 0, dp(4))
                            },
                            LinearLayout.LayoutParams(dp(72), dp(42))
                        )
                    }
                }
            )
            addView(space(5))
            addView(label("mem ${estimatedMemoryMb}MB · $speedText tok/s", 12f, Palette.muted, Typeface.NORMAL))
            addView(space(2))
            addView(label("Reason: $reason", 12f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
        }
    }

    private fun loadRecommendedModel(path: String) {
        Thread {
            try {
                val requestBody = JSONObject().apply {
                    put("path", path)
                }.toString()
                val connection = (URL("http://$serviceHost:$servicePort/mobilecore/model/load").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer local")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 1200
                    readTimeout = 3000
                }
                connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.readText() ?: "{}"
                val response = JSONObject(body)
                runOnUiThread {
                    if (status in 200..299 && response.optBoolean("ok", false)) {
                        val modelName = response.optString("model", path.substringAfterLast('/'))
                        Toast.makeText(this@MainActivity, "已加载 $modelName", Toast.LENGTH_SHORT).show()
                        updateStatus("Loaded $modelName")
                    } else {
                        Toast.makeText(this@MainActivity, response.optString("error", "Load failed"), Toast.LENGTH_LONG).show()
                    }
                    refreshRecommendationSnapshot()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Load failed: ${e.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun probeDeviceProfile(): DeviceProbeSnapshot {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val freeRam = memoryInfo.availMem / (1024 * 1024)
        val totalRam = memoryInfo.totalMem / (1024 * 1024)
        return DeviceProbeSnapshot(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            availableRamMb = freeRam,
            totalRamMb = totalRam,
            coreCount = Runtime.getRuntime().availableProcessors(),
            backend = "llama.cpp",
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        )
    }

    private data class DeviceProbeSnapshot(
        val manufacturer: String,
        val model: String,
        val availableRamMb: Long,
        val totalRamMb: Long,
        val coreCount: Int,
        val backend: String,
        val abi: String
    )

    private data class ModelHubItem(
        val provider: String,
        val shortName: String,
        val fileName: String,
        val url: String
    )

    private enum class RecommendationPreference(
        val progress: Int,
        val queryValue: String,
        val label: String
    ) {
        SPEED(0, "speed", "速度优先"),
        STABILITY(1, "stability", "稳定优先"),
        SMALL_MODEL(2, "small", "小模型优先");

        companion object {
            fun fromProgress(progress: Int): RecommendationPreference {
                return values().firstOrNull { it.progress == progress } ?: STABILITY
            }

            fun fromQueryValue(value: String?): RecommendationPreference {
                return values().firstOrNull { it.queryValue == value } ?: STABILITY
            }
        }
    }

    private fun modelRow(name: String, subtitle: String, badge: String, accent: Int): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(IconBadgeView(context, "cube", accent), LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(10), 0)
                    addView(label(name, 16f, Palette.ink, Typeface.BOLD).apply { maxLines = 1 })
                    addView(space(3))
                    addView(label(subtitle, 12f, Palette.muted, Typeface.NORMAL).apply { maxLines = 1 })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(chip(label(badge, 12f, tint(accent, 0.75f), Typeface.BOLD), tint(accent, 0.12f), accent))
        }
    }

    private fun buildBottomNavigation(): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = rounded(Palette.surface, Palette.stroke, 24f)
            elevation = dp(3).toFloat()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(navItem("Home", "cube", true) { rootScrollView.smoothScrollTo(0, 0) }, LinearLayout.LayoutParams(0, dp(64), 1f))
            addView(navItem("Models", "cube", false) { scrollToAnchor(modelsAnchor) }, LinearLayout.LayoutParams(0, dp(64), 1f))
            addView(navItem("Metrics", "gauge", false) { scrollToAnchor(metricsAnchor) }, LinearLayout.LayoutParams(0, dp(64), 1f))
            addView(navItem("API", "cloud", false) { scrollToAnchor(apiAnchor) }, LinearLayout.LayoutParams(0, dp(64), 1f))
        }
    }

    private fun navItem(title: String, icon: String, selected: Boolean, onClick: () -> Unit): View {
        val accent = if (selected) Palette.mint else Palette.muted
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ripple(
                if (selected) rounded(Palette.mintPale, Color.TRANSPARENT, 18f) else rounded(Color.TRANSPARENT, Color.TRANSPARENT, 18f),
                accent
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(IconBadgeView(context, icon, accent), LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(space(4))
            addView(label(title, 11f, accent, if (selected) Typeface.BOLD else Typeface.NORMAL))
        }
    }

    private fun scrollToAnchor(anchor: View?) {
        anchor ?: return
        rootScrollView.post {
            rootScrollView.smoothScrollTo(0, (anchor.top - dp(12)).coerceAtLeast(0))
        }
    }

    private fun sectionTitle(title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title, 18f, Palette.ink, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(subtitle, 12f, Palette.muted, Typeface.NORMAL))
        }
    }

    private fun ensureNotificationPermissionAndStartService() {
        withNotificationPermission {
            startServiceInForeground()
        }
    }

    private fun ensureNotificationPermissionAndLoadFirstModel() {
        withNotificationPermission {
            loadFirstModel()
        }
    }

    private fun withNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingAfterNotificationPermission = action
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequestCode)
            return
        }
        action()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != notificationPermissionRequestCode) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val action = pendingAfterNotificationPermission
            pendingAfterNotificationPermission = null
            action?.invoke() ?: startServiceInForeground()
        } else {
            pendingAfterNotificationPermission = null
            updateStatus("通知权限未授予，无法启动前台服务")
            Toast.makeText(this, "请允许通知权限后再启动服务", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("startActivityForResult keeps this skeleton dependency-light.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != importModelRequestCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importGguf(uri)
    }

    private fun startServiceInForeground() {
        val intent = Intent(this, MobileCoreService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        updateStatus("Service started · http://$serviceHost:$servicePort/v1")
        refreshRecommendationSnapshot()
    }

    private fun stopMobileCoreService() {
        val intent = Intent(this, MobileCoreService::class.java)
        stopService(intent)
        updateStatus("Service stopped · http://$serviceHost:$servicePort/v1")
        renderRecommendationPlaceholder("服务已停止，请重启 API 后刷新推荐。")
    }

    private fun openGgufPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/octet-stream", "application/x-gguf", "application/gguf")
            )
        }
        startActivityForResult(intent, importModelRequestCode)
    }

    private fun importGguf(uri: Uri) {
        val displayName = resolveDisplayName(uri) ?: "imported-${System.currentTimeMillis()}.gguf"
        val safeName = sanitizeModelFileName(displayName)
        val destination = File(internalModelDir(), safeName)

        updateStatus("Importing $safeName")
        Thread {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Unable to open selected file" }
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, "Imported ${destination.name}", Toast.LENGTH_SHORT).show()
                    ensureNotificationPermissionAndLoadModel(destination)
                }
            } catch (e: Exception) {
                if (destination.exists()) destination.delete()
                runOnUiThread {
                    updateStatus("Import failed: ${e.message ?: "unknown error"}")
                    Toast.makeText(this, "GGUF import failed", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun ensureNotificationPermissionAndLoadModel(model: File) {
        withNotificationPermission {
            startServiceWithModel(model)
        }
    }

    private fun loadFirstModel() {
        val model = findFirstGguf()
        if (model == null) {
            updateStatus("No GGUF found · push to ${externalModelDir().absolutePath}")
            Toast.makeText(this, "No GGUF model found", Toast.LENGTH_SHORT).show()
            return
        }

        startServiceWithModel(model)
    }

    private fun startServiceWithModel(model: File) {
        val intent = Intent(this, MobileCoreService::class.java).apply {
            putExtra("modelPath", model.absolutePath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        updateStatus("Loading ${model.name} · http://$serviceHost:$servicePort/v1")
    }

    private fun findFirstGguf(): File? {
        return modelDirs()
            .flatMap { dir ->
                dir.listFiles { file ->
                    file.isFile && file.extension.lowercase() == "gguf"
                }?.toList() ?: emptyList()
            }
            .minByOrNull { it.name }
    }

    private fun modelDirs(): List<File> {
        return listOf(internalModelDir(), externalModelDir()).onEach { it.mkdirs() }
    }

    private fun internalModelDir(): File {
        return File(filesDir, "models")
    }

    private fun externalModelDir(): File {
        return getExternalFilesDir("models") ?: File(filesDir, "models")
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else {
                    null
                }
            }
            ?: uri.lastPathSegment
    }

    private fun sanitizeModelFileName(name: String): String {
        val cleaned = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val nonBlank = cleaned.ifBlank { "imported-${System.currentTimeMillis()}.gguf" }
        return if (nonBlank.endsWith(".gguf", ignoreCase = true)) nonBlank else "$nonBlank.gguf"
    }

    private fun updateStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = message
        if (::runtimeChipText.isInitialized) {
            runtimeChipText.text = when {
                message.startsWith("Service started") -> "Local Runtime Online"
                message.startsWith("Loading") -> "Loading Local Model"
                message.startsWith("Downloading") -> "Downloading Model"
                message.startsWith("Downloaded") -> "Model Downloaded"
                message.startsWith("Service stopped") -> "Runtime Stopped"
                message.startsWith("No GGUF") -> "Model Needed"
                else -> "On-device LLM Ready"
            }
        }
    }

    private fun label(text: String, sizeSp: Float, color: Int, style: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, style)
            includeFontPadding = true
        }
    }

    private fun chip(textView: TextView, background: Int, border: Int): View {
        return FrameLayout(this).apply {
            this.background = rounded(background, tint(border, 0.28f), 18f)
            setPadding(dp(12), 0, dp(12), 0)
            addView(
                textView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
    }

    private fun pillButton(text: String, startColor: Int, endColor: Int, onClick: () -> Unit): View {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = ripple(roundedGradient(intArrayOf(startColor, endColor), 24f), endColor)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun thinDivider(): View {
        return View(this).apply {
            setBackgroundColor(Palette.stroke)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }
    }

    private fun space(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
        }
    }

    private fun rounded(color: Int, stroke: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }
    }

    private fun roundedGradient(colors: IntArray, radiusDp: Float): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun ripple(content: GradientDrawable, accent: Int): RippleDrawable {
        return RippleDrawable(ColorStateList.valueOf(tint(accent, 0.18f)), content, null)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun tint(color: Int, alpha: Float): Int {
        return Color.argb((255 * alpha).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }

    private fun displayModelName(name: String): String {
        return when {
            name.contains("qwen2.5", ignoreCase = true) -> "Qwen2.5 0.5B"
            name.contains("smollm", ignoreCase = true) -> "SmolLM2 135M"
            name.length <= 16 -> name
            else -> name.take(13).trim('-', '_') + "..."
        }
    }

    private fun displayBackendName(name: String): String {
        return when {
            name.contains("llama", ignoreCase = true) -> "llama.cpp"
            name.length <= 14 -> name
            else -> name.take(12).trim('-', '_') + "..."
        }
    }

    private object Palette {
        const val background = 0xFFFBFDFF.toInt()
        const val surface = 0xFFFFFFFF.toInt()
        const val ink = 0xFF14243F.toInt()
        const val muted = 0xFF697A95.toInt()
        const val stroke = 0xFFE5EEF7.toInt()
        const val mint = 0xFF7EE6C1.toInt()
        const val mintDark = 0xFF24AA8A.toInt()
        const val mintPale = 0xFFEFFFF9.toInt()
        const val sky = 0xFF43D1E8.toInt()
        const val blue = 0xFF6B8CFF.toInt()
        const val lavender = 0xFFB69CFF.toInt()
        const val mintWash = 0xFFF0FFF9.toInt()
        const val blueWash = 0xFFEEF7FF.toInt()
        const val lavenderWash = 0xFFF4F1FF.toInt()
    }

    private class PushBoxMarkView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            drawMotionLines(canvas, w, h)
            drawMascot(canvas, w, h)
            drawCube(canvas, w, h)
        }

        private fun drawMotionLines(canvas: Canvas, w: Float, h: Float) {
            strokePaint.color = Color.argb(120, 119, 222, 201)
            strokePaint.strokeWidth = h * 0.035f
            canvas.drawLine(w * 0.02f, h * 0.44f, w * 0.18f, h * 0.44f, strokePaint)
            canvas.drawLine(w * 0.00f, h * 0.57f, w * 0.16f, h * 0.57f, strokePaint)
        }

        private fun drawMascot(canvas: Canvas, w: Float, h: Float) {
            val body = RectF(w * 0.11f, h * 0.34f, w * 0.47f, h * 0.82f)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, body.top, body.right, body.bottom, 0xFF7EE6C1.toInt(), 0xFF43D1E8.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(body, w * 0.16f, w * 0.16f, paint)
            paint.shader = null

            paint.color = Color.WHITE
            canvas.drawOval(RectF(w * 0.19f, h * 0.42f, w * 0.41f, h * 0.63f), paint)
            paint.color = 0xFF14243F.toInt()
            canvas.drawCircle(w * 0.26f, h * 0.52f, w * 0.014f, paint)
            canvas.drawCircle(w * 0.35f, h * 0.52f, w * 0.014f, paint)
            strokePaint.color = 0xFF14243F.toInt()
            strokePaint.strokeWidth = w * 0.013f
            canvas.drawArc(RectF(w * 0.28f, h * 0.54f, w * 0.36f, h * 0.62f), 25f, 130f, false, strokePaint)

            strokePaint.color = 0xFF42C7BD.toInt()
            strokePaint.strokeWidth = w * 0.05f
            canvas.drawLine(w * 0.38f, h * 0.54f, w * 0.61f, h * 0.49f, strokePaint)

            strokePaint.strokeWidth = w * 0.022f
            canvas.drawLine(w * 0.28f, h * 0.34f, w * 0.30f, h * 0.24f, strokePaint)
            paint.color = 0xFF63DCC2.toInt()
            canvas.drawOval(RectF(w * 0.28f, h * 0.20f, w * 0.36f, h * 0.28f), paint)
        }

        private fun drawCube(canvas: Canvas, w: Float, h: Float) {
            val cube = RectF(w * 0.50f, h * 0.16f, w * 0.96f, h * 0.76f)
            paint.shader = LinearGradient(cube.left, cube.top, cube.right, cube.bottom, 0xFF6B8CFF.toInt(), 0xFFB69CFF.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(cube, w * 0.12f, w * 0.12f, paint)
            paint.shader = null

            paint.color = Color.argb(95, 255, 255, 255)
            canvas.drawRoundRect(RectF(w * 0.56f, h * 0.22f, w * 0.78f, h * 0.70f), w * 0.06f, w * 0.06f, paint)

            strokePaint.color = Color.argb(170, 255, 255, 255)
            strokePaint.strokeWidth = w * 0.012f
            val cx = w * 0.84f
            val cy = h * 0.46f
            val r = w * 0.055f
            val points = arrayOf(
                cx to cy - r,
                cx + r to cy,
                cx to cy + r,
                cx - r to cy
            )
            val path = Path().apply {
                moveTo(points[0].first, points[0].second)
                points.drop(1).forEach { lineTo(it.first, it.second) }
                close()
            }
            canvas.drawPath(path, strokePaint)
            canvas.drawLine(points[0].first, points[0].second, points[2].first, points[2].second, strokePaint)
            paint.color = Color.WHITE
            points.forEach { canvas.drawCircle(it.first, it.second, w * 0.016f, paint) }

            paint.color = Color.argb(145, 255, 255, 255)
            canvas.drawRoundRect(RectF(w * 0.62f, h * 0.09f, w * 0.83f, h * 0.18f), w * 0.035f, w * 0.035f, paint)
        }
    }

    private class IconBadgeView(context: Context, private val kind: String, private val accent: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent))
            canvas.drawRoundRect(RectF(0f, 0f, w, h), w * 0.28f, w * 0.28f, paint)
            strokePaint.color = accent
            strokePaint.strokeWidth = w * 0.08f
            when (kind) {
                "play" -> drawPlay(canvas, w, h)
                "stop" -> drawStop(canvas, w, h)
                "chip" -> drawChip(canvas, w, h)
                "cloud" -> drawCloud(canvas, w, h)
                "gauge" -> drawGauge(canvas, w, h)
                "download" -> drawDownload(canvas, w, h)
                else -> drawCube(canvas, w, h)
            }
        }

        private fun drawPlay(canvas: Canvas, w: Float, h: Float) {
            paint.color = accent
            val path = Path().apply {
                moveTo(w * 0.40f, h * 0.30f)
                lineTo(w * 0.72f, h * 0.50f)
                lineTo(w * 0.40f, h * 0.70f)
                close()
            }
            canvas.drawPath(path, paint)
        }

        private fun drawStop(canvas: Canvas, w: Float, h: Float) {
            paint.color = accent
            canvas.drawRoundRect(RectF(w * 0.34f, h * 0.34f, w * 0.66f, h * 0.66f), w * 0.05f, w * 0.05f, paint)
        }

        private fun drawCube(canvas: Canvas, w: Float, h: Float) {
            val path = Path().apply {
                moveTo(w * 0.50f, h * 0.22f)
                lineTo(w * 0.76f, h * 0.36f)
                lineTo(w * 0.76f, h * 0.66f)
                lineTo(w * 0.50f, h * 0.80f)
                lineTo(w * 0.24f, h * 0.66f)
                lineTo(w * 0.24f, h * 0.36f)
                close()
            }
            canvas.drawPath(path, strokePaint)
            canvas.drawLine(w * 0.50f, h * 0.50f, w * 0.50f, h * 0.80f, strokePaint)
            canvas.drawLine(w * 0.24f, h * 0.36f, w * 0.50f, h * 0.50f, strokePaint)
            canvas.drawLine(w * 0.76f, h * 0.36f, w * 0.50f, h * 0.50f, strokePaint)
        }

        private fun drawChip(canvas: Canvas, w: Float, h: Float) {
            canvas.drawRoundRect(RectF(w * 0.32f, h * 0.32f, w * 0.68f, h * 0.68f), w * 0.05f, w * 0.05f, strokePaint)
            canvas.drawLine(w * 0.18f, h * 0.40f, w * 0.30f, h * 0.40f, strokePaint)
            canvas.drawLine(w * 0.18f, h * 0.60f, w * 0.30f, h * 0.60f, strokePaint)
            canvas.drawLine(w * 0.70f, h * 0.40f, w * 0.82f, h * 0.40f, strokePaint)
            canvas.drawLine(w * 0.70f, h * 0.60f, w * 0.82f, h * 0.60f, strokePaint)
        }

        private fun drawCloud(canvas: Canvas, w: Float, h: Float) {
            canvas.drawArc(RectF(w * 0.22f, h * 0.42f, w * 0.50f, h * 0.72f), 190f, 220f, false, strokePaint)
            canvas.drawArc(RectF(w * 0.38f, h * 0.26f, w * 0.70f, h * 0.66f), 190f, 220f, false, strokePaint)
            canvas.drawArc(RectF(w * 0.56f, h * 0.40f, w * 0.82f, h * 0.72f), 210f, 190f, false, strokePaint)
            canvas.drawLine(w * 0.30f, h * 0.70f, w * 0.74f, h * 0.70f, strokePaint)
        }

        private fun drawGauge(canvas: Canvas, w: Float, h: Float) {
            canvas.drawArc(RectF(w * 0.22f, h * 0.28f, w * 0.78f, h * 0.84f), 200f, 140f, false, strokePaint)
            canvas.drawLine(w * 0.50f, h * 0.58f, w * 0.66f, h * 0.42f, strokePaint)
        }

        private fun drawDownload(canvas: Canvas, w: Float, h: Float) {
            canvas.drawLine(w * 0.50f, h * 0.24f, w * 0.50f, h * 0.58f, strokePaint)
            canvas.drawLine(w * 0.34f, h * 0.44f, w * 0.50f, h * 0.60f, strokePaint)
            canvas.drawLine(w * 0.66f, h * 0.44f, w * 0.50f, h * 0.60f, strokePaint)
            canvas.drawRoundRect(RectF(w * 0.26f, h * 0.66f, w * 0.74f, h * 0.78f), w * 0.04f, w * 0.04f, strokePaint)
        }
    }
}
