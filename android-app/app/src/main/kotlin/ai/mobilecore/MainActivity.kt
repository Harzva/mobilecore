package ai.mobilecore

import ai.mobilecore.runtime.BenchmarkResult
import ai.mobilecore.runtime.BenchmarkScorer
import ai.mobilecore.runtime.BenchmarkSpec
import ai.mobilecore.runtime.BenchmarkLeaderboardStore
import ai.mobilecore.runtime.BenchmarkLeaderboardEntry
import ai.mobilecore.runtime.GgufMetadataReader
import ai.mobilecore.service.MobileCoreService
import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

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
    private lateinit var contentRoot: LinearLayout
    private lateinit var bottomNavHost: FrameLayout
    private var currentTab = AppTab.HOME
    private var testResultText: TextView? = null
    private var testMetricsText: TextView? = null
    private var routeStatusText: TextView? = null
    private var runtimeLoadedModelText: TextView? = null
    private var runtimeMemoryUseText: TextView? = null
    private var runtimeApiStatusText: TextView? = null
    private var runtimeSpeedText: TextView? = null
    private var homeModelText: TextView? = null
    private var homeMemoryText: TextView? = null
    private var homeServiceText: TextView? = null
    private var visionImageText: TextView? = null
    private var visionResultText: TextView? = null
    private var visionModelSummaryText: TextView? = null
    private var selectedVisionImageUri: Uri? = null
    private var selectedVisionImageName: String? = null
    private var selectedVisionImagePath: String? = null
    private var benchmarkLeaderboardContainer: LinearLayout? = null
    private var isTestRunning = false
    private var recommendationPreference = RecommendationPreference.STABILITY
    private val serviceHost = "127.0.0.1"
    private val servicePort = 8080
    private val notificationPermissionRequestCode = 1001
    private val importModelRequestCode = 1002
    private val pickVisionImageRequestCode = 1003
    private val importVisionModelRequestCode = 1004
    private var pendingAfterNotificationPermission: (() -> Unit)? = null
    private val providerStateByProvider = mutableMapOf<String, ModelDownloadState>()
    private val providerTitleByProvider = mutableMapOf<String, TextView>()
    private val providerStatusByProvider = mutableMapOf<String, TextView>()
    private val providerMessageByProvider = mutableMapOf<String, TextView>()
    private val providerProgressByProvider = mutableMapOf<String, TextView>()
    private val providerCancelByProvider = mutableMapOf<String, TextView>()
    private val providerTileByProvider = mutableMapOf<String, View>()
    private var modelScopeSearchQuery = ""
    private var modelScopeLoading = false
    private var modelScopeLoaded = false
    private var modelScopeError: String? = null
    private var modelScopeLoadedQuery = ""
    private var modelScopeRemoteTotal: Int? = null
    private val modelScopeCatalog = mutableListOf<ModelScopeCatalogEntry>()
    private var modelScopeStatusText: TextView? = null
    private var modelScopeResultsContainer: LinearLayout? = null
    private var activeModelPath: String? = null
    private val activeDownloadThreads = ConcurrentHashMap<String, Thread>()
    private val progressHandler = Handler(Looper.getMainLooper())
    private val modelScopeSearchRunnable = Runnable {
        refreshModelScopeCatalog(force = true)
    }
    private val progressPollRunnable = object : Runnable {
        override fun run() {
            if (hasActiveDownload()) {
                progressHandler.postDelayed(this, 900L)
            }
        }
    }
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
    private val modelScopeSeeds = listOf(
        ModelScopeRepoSeed("Qwen", "Qwen2.5-0.5B-Instruct-GGUF", "Qwen2.5 0.5B"),
        ModelScopeRepoSeed("unsloth", "Qwen3-0.6B-GGUF", "Qwen3 0.6B"),
        ModelScopeRepoSeed("Qwen", "Qwen2.5-1.5B-Instruct-GGUF", "Qwen2.5 1.5B")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recommendationPreference = readRecommendationPreference()
        if (providerStateByProvider.isEmpty()) {
            modelHubItems.forEach { providerStateByProvider[downloadTaskKey(it)] = ModelDownloadState(item = it) }
        }
        actionBar?.hide()
        window.statusBarColor = Palette.background
        window.navigationBarColor = Color.WHITE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val pageRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.background)
        }
        rootScrollView = ScrollView(this).apply {
            setBackgroundColor(Palette.background)
            isFillViewport = true
        }
        contentRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(10))
        }
        bottomNavHost = FrameLayout(this).apply {
            setBackgroundColor(Palette.background)
            setPadding(dp(18), dp(3), dp(18), dp(8))
        }

        rootScrollView.addView(
            contentRoot,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        pageRoot.addView(rootScrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pageRoot.addView(bottomNavHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(pageRoot)
        renderCurrentTab()
        refreshRecommendationSnapshot()
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressPollRunnable)
        progressHandler.removeCallbacks(modelScopeSearchRunnable)
        providerStateByProvider.values.forEach { it.cancelRequested = true }
        activeDownloadThreads.values.forEach { it.interrupt() }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshRecommendationSnapshot()
    }

    private fun renderCurrentTab() {
        if (!::contentRoot.isInitialized) return
        contentRoot.removeAllViews()
        when (currentTab) {
            AppTab.HOME -> renderHomeTab(contentRoot)
            AppTab.MODELS -> renderModelsTab(contentRoot)
            AppTab.VISION -> renderVisionTab(contentRoot)
            AppTab.TEST -> renderTestTab(contentRoot)
            AppTab.API -> renderApiTab(contentRoot)
            AppTab.SETTINGS -> renderSettingsTab(contentRoot)
        }
        if (::bottomNavHost.isInitialized) {
            bottomNavHost.removeAllViews()
            bottomNavHost.addView(buildBottomNavigation())
        }
        rootScrollView.post { rootScrollView.scrollTo(0, 0) }
    }

    private fun setTab(tab: AppTab) {
        if (currentTab == tab) return
        currentTab = tab
        renderCurrentTab()
        if (tab == AppTab.HOME || tab == AppTab.MODELS) {
            refreshRecommendationSnapshot()
        }
    }

    private fun renderHomeTab(content: LinearLayout) {
        content.addView(buildHeader())
        content.addView(space(14))
        content.addView(buildHeroCard())
        content.addView(space(14))
        content.addView(sectionTitle("本机体检", "根据设备能力给出推荐"))
        content.addView(buildDeviceProbeCard())
        content.addView(space(18))
        content.addView(sectionTitle("推荐模型", "速度 / 稳定 / 小模型"))
        content.addView(buildRecommendationCard())
        content.addView(space(18))
        content.addView(sectionTitle("快速操作", "导入、加载、测试"))
        content.addView(buildActionGrid())
        content.addView(space(18))
        content.addView(sectionTitle("运行状态", "本机"))
        content.addView(buildHomeRuntimeCard())
    }

    private fun renderModelsTab(content: LinearLayout) {
        content.addView(buildHeader())
        content.addView(space(12))
        content.addView(buildStorageCard())
        content.addView(space(12))
        content.addView(sectionTitle("10 个精选模型", "结合本机配置推荐下载"))
        content.addView(buildFeaturedModelScopeCard())
        content.addView(space(12))
        content.addView(sectionTitle("ModelScope", "模型列表 / 搜索 / GGUF"))
        content.addView(buildModelScopeCatalogCard())
        content.addView(space(18))
        content.addView(sectionTitle("快捷下载", "ModelScope / HuggingFace"))
        content.addView(buildModelHubCard())
        content.addView(space(18))
        content.addView(buildRecentModelsCard())
        content.addView(space(18))
        content.addView(sectionTitle("推荐排序", "按本机能力计算"))
        content.addView(buildRecommendationCard())
    }

    private fun renderTestTab(content: LinearLayout) {
        content.addView(buildHeader())
        content.addView(space(12))
        content.addView(sectionTitle("本机检测", "模型回复与速度"))
        content.addView(buildTestChatCard())
        content.addView(space(18))
        content.addView(sectionTitle("检测结果", "上次运行"))
        content.addView(buildMetricStrip())
        content.addView(space(18))
        content.addView(sectionTitle("本机排行榜", "标准跑分 v1"))
        content.addView(buildLocalLeaderboardCard())
        content.addView(space(18))
        content.addView(sectionTitle("设备状态", "CPU / 内存 / 引擎"))
        content.addView(buildDeviceProbeCard())
    }

    private fun renderVisionTab(content: LinearLayout) {
        content.addView(buildHeader())
        content.addView(space(12))
        content.addView(sectionTitle("视觉 OCR", "图片文字识别"))
        content.addView(buildVisionHeroCard())
        content.addView(space(14))
        content.addView(sectionTitle("模型状态", "ONNX / TFLite / MNN"))
        content.addView(buildVisionModelStatusCard())
        content.addView(space(14))
        content.addView(sectionTitle("OCR 模型", "独立视觉后端"))
        content.addView(buildOcrModelCard())
        content.addView(space(14))
        content.addView(sectionTitle("CLIP / 分类", "CIFAR10 / MNIST"))
        content.addView(buildVisionClassificationCard())
        content.addView(space(14))
        content.addView(sectionTitle("识别结果", "本机处理状态"))
        content.addView(buildOcrResultCard())
    }

    private fun renderApiTab(content: LinearLayout) {
        content.addView(buildHeader())
        content.addView(space(12))
        content.addView(buildApiEndpointCard())
        content.addView(space(14))
        content.addView(buildApiActionStrip())
        content.addView(space(14))
        content.addView(buildApiRoutesCard())
        content.addView(space(14))
        content.addView(buildStatusCard())
    }

    private fun renderSettingsTab(content: LinearLayout) {
        content.addView(buildHeader())
        content.addView(space(12))
        content.addView(sectionTitle("设置", "本地优先"))
        content.addView(buildSettingsCard())
        content.addView(space(18))
        content.addView(sectionTitle("接口", "本机服务"))
        content.addView(buildApiEndpointCard())
        content.addView(space(14))
        content.addView(buildApiActionStrip())
        content.addView(space(14))
        content.addView(buildApiRoutesCard())
        content.addView(space(18))
        content.addView(sectionTitle("未来计划", "不阻塞本地推理"))
        content.addView(buildFuturePlanCard())
    }

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            addView(
                PushBoxMarkView(context),
                LinearLayout.LayoutParams(dp(64), dp(50)).apply { marginEnd = dp(8) }
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(tuimaWordmark(compact = true))
                    addView(space(4))
                    addView(label("推嘛 · 手机本地模型", 11f, Palette.muted, Typeface.NORMAL))
                    addView(space(5))
                    runtimeChipText = label("本机就绪", 11f, Palette.mintDark, Typeface.BOLD)
                    addView(
                        chip(runtimeChipText, Palette.mintPale, Palette.mint),
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            dp(30)
                        )
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(notificationBubble(), LinearLayout.LayoutParams(dp(42), dp(42)))
        }
    }

    private fun buildHeroCard(): View {
        return FrameLayout(this).apply {
            background = roundedGradient(
                intArrayOf(Palette.surface, Palette.mintWash, Palette.blueWash, Palette.lavenderWash),
                26f
            )
            elevation = dp(4).toFloat()
            setPadding(dp(20), dp(20), dp(18), dp(18))

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, dp(152), 0)
                    addView(tuimaWordmark(compact = false))
                    addView(space(10))
                    addView(label("MobileCore for\non-device LLM\ninference", 16.4f, Palette.deepInk, Typeface.BOLD).apply {
                        maxLines = 3
                        setLineSpacing(dp(2).toFloat(), 1f)
                    })
                    addView(space(9))
                    addView(label("Run large models locally\non your phone", 13.4f, Palette.muted, Typeface.NORMAL).apply {
                        maxLines = 2
                        setLineSpacing(dp(1).toFloat(), 1f)
                    })
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START)
            )

            addView(
                tuimaHeroArtwork(),
                FrameLayout.LayoutParams(dp(180), dp(118), Gravity.END or Gravity.TOP).apply {
                    rightMargin = dp(-6)
                    topMargin = dp(106)
                }
            )

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        pillButton("启动服务", Palette.sky, Palette.blue) {
                            ensureNotificationPermissionAndStartService()
                        },
                        LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) }
                    )
                    addView(
                        pillButton("导入 GGUF", Palette.mintDark, Palette.mint) {
                            openGgufPicker()
                        },
                        LinearLayout.LayoutParams(0, dp(46), 1f)
                    )
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), Gravity.BOTTOM).apply {
                    leftMargin = dp(0)
                    rightMargin = dp(0)
                }
            )
        }.apply {
            minimumHeight = dp(300)
        }
    }

    private fun tuimaWordmark(compact: Boolean): View {
        val latinSize = if (compact) 24f else 42f
        val chineseSize = if (compact) 15f else 23f
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Tui", latinSize, Palette.deepInk, Typeface.BOLD))
            addView(label("Ma", latinSize, Palette.blue, Typeface.BOLD))
            addView(space(if (compact) 6 else 10))
            addView(label("推嘛", chineseSize, Palette.mintDark, Typeface.BOLD))
        }
    }

    private fun tuimaHeroArtwork(): View {
        return ImageView(this).apply {
            setImageResource(com.mobilecore.app.R.drawable.tuima_pushbox_hero)
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = false
            alpha = 0.98f
        }
    }

    private fun notificationBubble(): View {
        return FrameLayout(this).apply {
            background = ripple(rounded(Palette.surface, Palette.stroke, 22f), Palette.blue)
            elevation = dp(2).toFloat()
            addView(
                IconBadgeView(context, "bell", Palette.deepInk),
                FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
            )
        }
    }

    private fun surfaceCard(accent: Int, gradient: Boolean = false, block: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (gradient) {
                roundedGradient(intArrayOf(Palette.surface, tint(accent, 0.10f), Palette.blueWash), 18f)
            } else {
                rounded(Palette.surface, tint(accent, 0.18f), 18f)
            }
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(16), dp(16), dp(14))
            block()
        }
    }

    private fun cardHeader(
        title: String,
        caption: String,
        icon: String,
        accent: Int,
        badge: String? = null,
        badgeAccent: Int = accent
    ): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(IconBadgeView(context, icon, accent), LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(title, 14.2f, tint(Palette.ink, 0.82f), Typeface.BOLD).apply { maxLines = 1 })
                    if (caption.isNotBlank()) {
                        addView(space(3))
                        addView(label(caption, 11.6f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (badge != null) {
                addView(chip(label(badge, 10.8f, tint(badgeAccent, 0.82f), Typeface.BOLD), tint(badgeAccent, 0.12f), badgeAccent))
            }
        }
    }

    private fun softInfoBlock(text: String, accent: Int, maxLines: Int = 2): TextView {
        return label(text, 12.4f, tint(Palette.ink, 0.68f), Typeface.NORMAL).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(tint(accent, 0.08f), tint(accent, 0.18f), 14f)
            this.maxLines = maxLines
        }
    }

    private fun buildStorageCard(): View {
        val freeMb = runCatching { externalModelDir().freeSpace / (1024 * 1024) }.getOrDefault(0L)
        val totalMb = runCatching { externalModelDir().totalSpace / (1024 * 1024) }.getOrDefault(0L)
        val usedMb = (totalMb - freeMb).coerceAtLeast(0L)
        val modelBytes = modelDirs()
            .flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            .sumOf { it.length() }
        val storageLine = if (totalMb > 0) {
            "模型 ${formatBytes(modelBytes)} · 已用 ${usedMb / 1024} / ${totalMb / 1024} GB"
        } else {
            "模型 ${formatBytes(modelBytes)}"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Palette.surface, tint(Palette.mint, 0.18f), 18f)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(IconBadgeView(context, "chip", Palette.mint), LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, dp(12), 0)
                    addView(label("模型空间", 14f, tint(Palette.ink, 0.86f), Typeface.BOLD))
                    addView(space(4))
                    addView(label(storageLine, 13f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                pillButton("位置", Palette.mintDark, Palette.mint) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("TuiMa model directory", externalModelDir().absolutePath))
                    Toast.makeText(this@MainActivity, "模型目录已复制", Toast.LENGTH_SHORT).show()
                    updateStatus("模型目录已复制")
                },
                LinearLayout.LayoutParams(dp(118), dp(44))
            )
        }
    }

    private fun buildFeaturedModelScopeCard(): View {
        val profile = probeDeviceProfile()
        val featuredItems = featuredModelScopeCatalog()
            .sortedWith(
                compareByDescending<ModelScopeCatalogEntry> { mobileFitScore(it, profile) }
                    .thenBy { modelParameterValue(it.parameterLabel) }
                    .thenBy { estimateMobileMemoryMb(it) }
            )
        val best = featuredItems.firstOrNull()
        val headerText = best?.let {
            "${fitLabel(it, profile)} · 首推 ${it.parameterLabel} ${it.quantization}"
        } ?: "准备推荐"

        return surfaceCard(Palette.mint, gradient = true) {
            addView(cardHeader("精选 ModelScope 模型", "按本机内存、核心数和量化等级排序", "download", Palette.mint, "适配本机", Palette.mintDark))
            addView(space(5))
            addView(
                label(
                    "内存 ${profile.availableRamMb}/${profile.totalRamMb}MB · ${profile.coreCount} 核 · $headerText",
                    12f,
                    Palette.muted,
                    Typeface.NORMAL
                ).apply { maxLines = 2 }
            )
            addView(space(10))
            featuredItems.forEachIndexed { index, entry ->
                addView(buildModelScopeResultRow(entry, compact = index >= 5))
                if (index != featuredItems.lastIndex) {
                    addView(space(8))
                }
            }
        }
    }

    private fun buildModelScopeCatalogCard(): View {
        return surfaceCard(Palette.blue) {
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(cardHeader("ModelScope GGUF", "搜索、筛选并下载可运行模型", "cloud", Palette.blue), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(
                        chipButton(if (modelScopeLoading) "加载中" else "搜索", false) {
                            refreshModelScopeCatalog(force = true)
                        },
                        LinearLayout.LayoutParams(dp(88), dp(40))
                    )
                }
            )
            addView(space(10))
            addView(
                EditText(context).apply {
                    setText(modelScopeSearchQuery)
                    hint = "搜索 Qwen、Q4_K_M、0.5B..."
                    textSize = 14f
                    setSingleLine(true)
                    setTextColor(Palette.ink)
                    setHintTextColor(Palette.muted)
                    background = rounded(tint(Palette.blueWash, 0.70f), Palette.stroke, 16f)
                    setPadding(dp(14), 0, dp(14), 0)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            val nextQuery = s?.toString().orEmpty()
                            if (nextQuery == modelScopeSearchQuery) return
                            modelScopeSearchQuery = nextQuery
                            renderModelScopeResults()
                            scheduleModelScopeSearch()
                        }

                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            )
            addView(space(8))
            modelScopeStatusText = label("", 12f, Palette.muted, Typeface.NORMAL)
            addView(modelScopeStatusText)
            addView(space(8))
            modelScopeResultsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(modelScopeResultsContainer)
            renderModelScopeResults()
            if (!modelScopeLoaded && !modelScopeLoading) {
                refreshModelScopeCatalog(force = false)
            }
        }
    }

    private fun scheduleModelScopeSearch() {
        progressHandler.removeCallbacks(modelScopeSearchRunnable)
        progressHandler.postDelayed(modelScopeSearchRunnable, 650L)
    }

    private fun renderModelScopeResults() {
        val container = modelScopeResultsContainer ?: return
        container.removeAllViews()
        val query = modelScopeSearchQuery.trim().lowercase(Locale.US)
        val visibleItems = modelScopeCatalog
            .filter { entry -> query.isBlank() || entry.searchText.contains(query) }
            .sortedWith(compareBy<ModelScopeCatalogEntry> { it.sizeBytes }.thenBy { it.fileName })
            .take(8)

        modelScopeStatusText?.text = when {
            modelScopeLoading -> "正在从 ModelScope 拉取仓库详情和 GGUF 文件列表..."
            visibleItems.isNotEmpty() -> {
                val repoText = modelScopeRemoteTotal?.let { " · 搜到 $it 个仓库" } ?: ""
                "已展开 ${modelScopeCatalog.size} 个 GGUF 文件 · 当前显示 ${visibleItems.size} 个$repoText"
            }
            modelScopeError != null -> "ModelScope 暂不可用：$modelScopeError"
            modelScopeLoaded -> "没有匹配的 GGUF，试试 qwen / q4 / 0.5b"
            else -> "准备加载 ModelScope 模型列表"
        }

        if (visibleItems.isEmpty()) {
            container.addView(
                label(
                    if (modelScopeLoading) "请稍等，正在连接 ModelScope。" else "暂无结果；可以点搜索重试。",
                    13f,
                    Palette.muted,
                    Typeface.NORMAL
                ).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = rounded(tint(Palette.mint, 0.08f), Palette.stroke, 14f)
                },
            )
            return
        }

        visibleItems.forEachIndexed { index, entry ->
            container.addView(buildModelScopeResultRow(entry))
            if (index != visibleItems.lastIndex) {
                container.addView(space(8))
            }
        }
    }

    private fun buildModelScopeResultRow(entry: ModelScopeCatalogEntry, compact: Boolean = false): View {
        val localFile = File(externalModelDir(), entry.fileName)
        val downloaded = localFile.exists() && localFile.length() > 1024 * 1024
        val loadedInRuntime = downloaded && activeModelPath == localFile.absolutePath
        val modelHubItem = modelScopeItem(entry)
        val taskKey = downloadTaskKey(modelHubItem)
        val downloadState = providerStateByProvider[taskKey]
        val profile = probeDeviceProfile()
        val estimatedMemoryMb = estimateMobileMemoryMb(entry)
        val fit = fitLabel(entry, profile)
        val reason = entry.recommendationReason.ifBlank {
            "$fit · 预计内存 ${estimatedMemoryMb}MB · ${recommendationReasonFor(entry, profile)}"
        }
        val accent = when {
            loadedInRuntime -> Palette.blue
            downloaded -> Palette.mintDark
            mobileFitScore(entry, profile) >= 90 -> Palette.mint
            entry.quantization.startsWith("Q4", ignoreCase = true) -> Palette.mint
            entry.quantization.startsWith("Q2", ignoreCase = true) -> Palette.sky
            else -> Palette.lavender
        }
        val badge = when {
            loadedInRuntime -> "已加载"
            downloaded -> "本地"
            downloadState?.status == DownloadState.DOWNLOADING -> "下载中"
            downloadState?.status == DownloadState.PAUSED -> "已暂停"
            downloadState?.status == DownloadState.FAILED -> "重试"
            else -> entry.quantization
        }
        val primaryAction = when {
            loadedInRuntime -> "已加载"
            downloaded -> "加载"
            downloadState?.status == DownloadState.DOWNLOADING -> "暂停"
            downloadState?.status == DownloadState.PAUSED -> "继续"
            downloadState?.status == DownloadState.FAILED || downloadState?.status == DownloadState.CANCELLED -> "重试"
            else -> "下载"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(tint(accent, 0.055f), tint(accent, 0.16f), 14f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(
                miniListCard(
                    title = entry.displayTitle,
                    subtitle = entry.repoId,
                    badge = badge,
                    icon = "cube",
                    accent = accent,
                    selected = loadedInRuntime
                )
            )
            addView(space(8))
            addView(label(entry.fileName, 12f, Palette.ink, Typeface.BOLD).apply { maxLines = 1 })
            addView(space(3))
            addView(
                label(
                    "${entry.parameterLabel} · ${formatBytes(entry.sizeBytes)} · 预计 ${estimatedMemoryMb}MB · $fit",
                    12f,
                    Palette.muted,
                    Typeface.NORMAL
                ).apply { maxLines = 2 }
            )
            if (!compact) {
                addView(space(3))
                addView(
                    label(
                        reason,
                        12f,
                        Palette.muted,
                        Typeface.NORMAL
                    ).apply { maxLines = 2 }
                )
            }
            addView(space(10))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        pillButton(
                            primaryAction,
                            Palette.mintDark,
                            Palette.mint
                        ) {
                            val currentFile = File(externalModelDir(), entry.fileName)
                            val currentState = providerStateByProvider[taskKey]
                            val currentDownloaded = currentFile.exists() && currentFile.length() > 1024 * 1024
                            val currentLoaded = currentDownloaded && activeModelPath == currentFile.absolutePath
                            when {
                                currentLoaded -> {
                                    updateStatus("模型已加载：${currentFile.name}")
                                    Toast.makeText(this@MainActivity, "模型已加载", Toast.LENGTH_SHORT).show()
                                }
                                currentDownloaded -> {
                                    ensureNotificationPermissionAndLoadModel(currentFile)
                                }
                                currentState?.status == DownloadState.DOWNLOADING -> {
                                    pauseModelDownload(taskKey)
                                }
                                else -> {
                                    enqueueModelDownload(modelHubItem)
                                }
                            }
                        },
                        LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
                    )
                    addView(
                        chipButton("详情", false) {
                            Toast.makeText(this@MainActivity, "${entry.repoId}\n${entry.fileName}\n$reason", Toast.LENGTH_LONG).show()
                        },
                        LinearLayout.LayoutParams(dp(82), dp(44))
                    )
                }
            )
        }
    }

    private fun featuredModelScopeCatalog(): List<ModelScopeCatalogEntry> {
        fun mb(value: Long) = value * 1024L * 1024L
        return listOf(
            ModelScopeCatalogEntry(
                repoId = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                displayTitle = "Qwen2.5 0.5B Instruct",
                fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                filePath = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                sizeBytes = mb(469),
                quantization = "Q4_K_M",
                parameterLabel = "0.5B",
                architecture = "qwen2",
                downloads = 0L,
                recommendationReason = "入门首选，体积小，适合第一次检测。",
                tier = "tiny"
            ),
            ModelScopeCatalogEntry(
                repoId = "unsloth/Qwen3-0.6B-GGUF",
                displayTitle = "Qwen3 0.6B Ultra Small",
                fileName = "Qwen3-0.6B-UD-IQ1_S.gguf",
                filePath = "Qwen3-0.6B-UD-IQ1_S.gguf",
                sizeBytes = mb(205),
                quantization = "UD-IQ1_S",
                parameterLabel = "0.6B",
                architecture = "qwen3",
                downloads = 0L,
                recommendationReason = "最小下载包，低内存手机优先试这个。",
                tier = "tiny"
            ),
            ModelScopeCatalogEntry(
                repoId = "unsloth/Qwen3-0.6B-GGUF",
                displayTitle = "Qwen3 0.6B Balanced",
                fileName = "Qwen3-0.6B-Q4_K_M.gguf",
                filePath = "Qwen3-0.6B-Q4_K_M.gguf",
                sizeBytes = mb(468),
                quantization = "Q4_K_M",
                parameterLabel = "0.6B",
                architecture = "qwen3",
                downloads = 0L,
                recommendationReason = "小模型但量化更稳，适合日常测试。",
                tier = "tiny"
            ),
            ModelScopeCatalogEntry(
                repoId = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
                displayTitle = "Qwen2.5 1.5B Instruct",
                fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                filePath = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                sizeBytes = mb(1066),
                quantization = "Q4_K_M",
                parameterLabel = "1.5B",
                architecture = "qwen2",
                downloads = 0L,
                recommendationReason = "小手机可用，回答质量比 0.5B 明显更好。",
                tier = "phone"
            ),
            ModelScopeCatalogEntry(
                repoId = "unsloth/Qwen3-1.7B-GGUF",
                displayTitle = "Qwen3 1.7B Compact",
                fileName = "Qwen3-1.7B-Q2_K.gguf",
                filePath = "Qwen3-1.7B-Q2_K.gguf",
                sizeBytes = mb(742),
                quantization = "Q2_K",
                parameterLabel = "1.7B",
                architecture = "qwen3",
                downloads = 0L,
                recommendationReason = "参数更大但文件仍小，适合速度优先。",
                tier = "phone"
            ),
            ModelScopeCatalogEntry(
                repoId = "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
                displayTitle = "DeepSeek R1 Qwen 1.5B",
                fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q2_K.gguf",
                filePath = "DeepSeek-R1-Distill-Qwen-1.5B-Q2_K.gguf",
                sizeBytes = mb(718),
                quantization = "Q2_K",
                parameterLabel = "1.5B",
                architecture = "qwen2",
                downloads = 0L,
                recommendationReason = "轻量 reasoning 体验，适合演示推理链路。",
                tier = "phone"
            ),
            ModelScopeCatalogEntry(
                repoId = "AI-ModelScope/Phi-3.1-mini-4k-instruct-GGUF",
                displayTitle = "Phi-3.1 Mini 4K Instruct",
                fileName = "Phi-3.1-mini-4k-instruct-IQ2_M.gguf",
                filePath = "Phi-3.1-mini-4k-instruct-IQ2_M.gguf",
                sizeBytes = mb(1255),
                quantization = "IQ2_M",
                parameterLabel = "3.8B",
                architecture = "phi3",
                downloads = 0L,
                recommendationReason = "中端机可尝试，质量和体积比较均衡。",
                tier = "phone"
            ),
            ModelScopeCatalogEntry(
                repoId = "unsloth/Qwen3-4B-GGUF",
                displayTitle = "Qwen3 4B Compact",
                fileName = "Qwen3-4B-Q2_K.gguf",
                filePath = "Qwen3-4B-Q2_K.gguf",
                sizeBytes = mb(1592),
                quantization = "Q2_K",
                parameterLabel = "4B",
                architecture = "qwen3",
                downloads = 0L,
                recommendationReason = "高内存手机的推荐甜点位，质量更接近可用助手。",
                tier = "tablet"
            ),
            ModelScopeCatalogEntry(
                repoId = "unsloth/DeepSeek-R1-Distill-Qwen-7B-GGUF",
                displayTitle = "DeepSeek R1 Qwen 7B",
                fileName = "DeepSeek-R1-Distill-Qwen-7B-Q2_K.gguf",
                filePath = "DeepSeek-R1-Distill-Qwen-7B-Q2_K.gguf",
                sizeBytes = mb(2876),
                quantization = "Q2_K",
                parameterLabel = "7B",
                architecture = "qwen2",
                downloads = 0L,
                recommendationReason = "旗舰机可尝试的大模型，适合展示 reasoning。",
                tier = "heavy"
            ),
            ModelScopeCatalogEntry(
                repoId = "unsloth/Qwen3-8B-GGUF",
                displayTitle = "Qwen3 8B Ultra Compact",
                fileName = "Qwen3-8B-UD-IQ1_S.gguf",
                filePath = "Qwen3-8B-UD-IQ1_S.gguf",
                sizeBytes = mb(2170),
                quantization = "UD-IQ1_S",
                parameterLabel = "8B",
                architecture = "qwen3",
                downloads = 0L,
                recommendationReason = "旗舰机体验大参数量，优先看内存余量。",
                tier = "heavy"
            )
        )
    }

    private fun refreshModelScopeCatalog(force: Boolean) {
        if (modelScopeLoading) return
        val requestedQuery = modelScopeSearchQuery.trim()
        if (modelScopeLoaded && !force && modelScopeLoadedQuery == requestedQuery) {
            renderModelScopeResults()
            return
        }
        modelScopeLoading = true
        modelScopeError = null
        renderModelScopeResults()
        Thread {
            try {
                val remoteTotal: Int?
                val repos = if (requestedQuery.isBlank()) {
                    remoteTotal = null
                    modelScopeSeeds
                } else {
                    val searchResult = searchModelScopeRepos(requestedQuery)
                    remoteTotal = searchResult.totalCount
                    searchResult.repos
                }
                val loaded = repos
                    .distinctBy { it.repoId.lowercase(Locale.US) }
                    .take(8)
                    .flatMap { seed ->
                        runCatching { fetchModelScopeRepo(seed) }.getOrDefault(emptyList())
                    }
                runOnUiThread {
                    if (modelScopeSearchQuery.trim() != requestedQuery) {
                        modelScopeLoading = false
                        scheduleModelScopeSearch()
                        return@runOnUiThread
                    }
                    modelScopeCatalog.clear()
                    modelScopeCatalog.addAll(
                        if (loaded.isEmpty() && requestedQuery.isBlank()) fallbackModelScopeCatalog() else loaded
                    )
                    modelScopeLoaded = true
                    modelScopeLoadedQuery = requestedQuery
                    modelScopeRemoteTotal = remoteTotal
                    modelScopeLoading = false
                    modelScopeError = null
                    renderModelScopeResults()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    modelScopeCatalog.clear()
                    modelScopeCatalog.addAll(fallbackModelScopeCatalog())
                    modelScopeLoaded = true
                    modelScopeLoadedQuery = requestedQuery
                    modelScopeRemoteTotal = null
                    modelScopeLoading = false
                    modelScopeError = readableDownloadError(e)
                    renderModelScopeResults()
                }
            }
        }.start()
    }

    private fun searchModelScopeRepos(query: String): ModelScopeSearchResult {
        val searchQuery = if (query.contains("gguf", ignoreCase = true)) query else "$query gguf"
        val requestBody = JSONObject().apply {
            put("PageSize", 12)
            put("PageNumber", 1)
            put("SortBy", "Default")
            put("Target", "")
            put(
                "Criterion",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("category", "libraries")
                        put("predicate", "contains")
                        put("values", JSONArray().apply { put("gguf") })
                    })
                }
            )
            put("SingleCriterion", JSONArray())
            put("Name", searchQuery)
        }
        val json = requestModelScopeJson(
            url = "https://modelscope.cn/api/v1/dolphin/model/suggestv2",
            method = "POST",
            body = requestBody
        )
        val modelData = json.optJSONObject("Data")?.optJSONObject("Model") ?: JSONObject()
        val suggestions = modelData.optJSONArray("Suggests") ?: JSONArray()
        val repos = mutableListOf<ModelScopeRepoSeed>()
        for (index in 0 until suggestions.length()) {
            val item = suggestions.optJSONObject(index) ?: continue
            val owner = item.optString("Path")
            val name = item.optString("Name")
            if (owner.isBlank() || name.isBlank()) continue
            repos.add(
                ModelScopeRepoSeed(
                    owner = owner,
                    name = name,
                    label = item.optString("ChineseName").ifBlank { name }
                )
            )
        }
        return ModelScopeSearchResult(
            repos = repos,
            totalCount = modelData.optInt("TotalCount", repos.size)
        )
    }

    private fun fetchModelScopeRepo(seed: ModelScopeRepoSeed): List<ModelScopeCatalogEntry> {
        val repoId = seed.repoId
        val detailData = requestModelScopeJson("https://modelscope.cn/api/v1/models/$repoId")
            .optJSONObject("Data")
            ?: JSONObject()
        val fileData = requestModelScopeJson("https://modelscope.cn/api/v1/models/$repoId/repo/files?Revision=master&Recursive=true")
            .optJSONObject("Data")
            ?: JSONObject()
        val files = fileData.optJSONArray("Files") ?: JSONArray()
        val displayName = detailData.optString("ChineseName").ifBlank {
            detailData.optString("Name").ifBlank { seed.label }
        }
        val downloads = detailData.optLong("Downloads", 0L)
        val modelInfo = detailData.optJSONObject("ModelInfos")?.optJSONObject("gguf")
        val architecture = modelInfo?.optString("architecture")?.ifBlank { null } ?: inferArchitecture(repoId)
        val entries = mutableListOf<ModelScopeCatalogEntry>()
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: continue
            val name = file.optString("Name")
            if (!name.endsWith(".gguf", ignoreCase = true)) continue
            val size = file.optLong("Size", 0L)
            if (size <= 1024 * 1024) continue
            entries.add(
                ModelScopeCatalogEntry(
                    repoId = repoId,
                    displayTitle = displayName,
                    fileName = name,
                    filePath = file.optString("Path").ifBlank { name },
                    sizeBytes = size,
                    quantization = extractQuantization(name),
                    parameterLabel = inferParameterLabel("$repoId $name"),
                    architecture = architecture,
                    downloads = downloads
                )
            )
        }
        return entries
    }

    private fun requestModelScopeJson(url: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "TuiMa-MobileCore/0.1.1 Android")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.readText()
                ?: "{}"
            if (status !in 200..299) throw IOException("ModelScope HTTP $status")
            JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun fallbackModelScopeCatalog(): List<ModelScopeCatalogEntry> {
        return listOf(
            ModelScopeCatalogEntry(
                repoId = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                displayTitle = "千问2.5-0.5B-Instruct-GGUF",
                fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                filePath = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                sizeBytes = 491400032L,
                quantization = "Q4_K_M",
                parameterLabel = "0.5B",
                architecture = "qwen2",
                downloads = 0L
            ),
            ModelScopeCatalogEntry(
                repoId = "unsloth/Qwen3-0.6B-GGUF",
                displayTitle = "Qwen3-0.6B-GGUF",
                fileName = "Qwen3-0.6B-Q4_K_M.gguf",
                filePath = "Qwen3-0.6B-Q4_K_M.gguf",
                sizeBytes = 396705472L,
                quantization = "Q4_K_M",
                parameterLabel = "0.6B",
                architecture = "qwen3",
                downloads = 0L
            )
        )
    }

    private fun modelScopeDownloadUrl(entry: ModelScopeCatalogEntry): String {
        return "https://modelscope.cn/models/${entry.repoId}/resolve/master/${entry.filePath.replace(" ", "%20")}"
    }

    private fun modelScopeItem(entry: ModelScopeCatalogEntry): ModelHubItem {
        return ModelHubItem(
            provider = "ModelScope",
            shortName = "${entry.parameterLabel} ${entry.quantization}",
            fileName = entry.fileName,
            url = modelScopeDownloadUrl(entry)
        )
    }

    private fun downloadTaskKey(item: ModelHubItem): String {
        return "${item.provider}:${item.fileName}".lowercase(Locale.US)
    }

    private fun extractQuantization(fileName: String): String {
        val patterns = listOf(
            "UD-IQ\\d(?:_[A-Z]+)?",
            "IQ\\d(?:_[A-Z]+)?",
            "Q\\d(?:_[A-Z0-9]+)?",
            "BF16",
            "FP16",
            "F16"
        )
        val upper = fileName.uppercase(Locale.US)
        return patterns.firstNotNullOfOrNull { pattern ->
            Regex(pattern).find(upper)?.value
        } ?: "GGUF"
    }

    private fun inferParameterLabel(text: String): String {
        val normalized = text.replace("-", " ")
        Regex("(\\d+(?:\\.\\d+)?)\\s*[Bb]").find(normalized)?.let {
            return "${it.groupValues[1]}B"
        }
        Regex("(\\d+)\\s*[Mm]").find(normalized)?.let {
            return "${it.groupValues[1]}M"
        }
        return "LLM"
    }

    private fun inferArchitecture(repoId: String): String {
        return when {
            repoId.contains("qwen3", ignoreCase = true) -> "qwen3"
            repoId.contains("qwen", ignoreCase = true) -> "qwen2"
            repoId.contains("llama", ignoreCase = true) -> "llama"
            else -> "gguf"
        }
    }

    private fun estimateMobileMemoryMb(entry: ModelScopeCatalogEntry): Long {
        val sizeMb = (entry.sizeBytes / (1024 * 1024)).coerceAtLeast(1L)
        val quant = entry.quantization.lowercase(Locale.US)
        val multiplier = when {
            quant.contains("iq1") || quant.contains("ud-iq1") -> 0.70
            quant.contains("iq2") || quant.contains("q2") -> 0.78
            quant.contains("q3") -> 0.90
            quant.contains("q4") -> 1.05
            quant.contains("q5") -> 1.18
            quant.contains("q6") -> 1.30
            quant.contains("q8") || quant.contains("f16") || quant.contains("bf16") -> 1.55
            else -> 1.10
        }
        val params = modelParameterValue(entry.parameterLabel)
        val cacheOverhead = when {
            params >= 7.0 -> 640L
            params >= 4.0 -> 512L
            params >= 1.5 -> 384L
            else -> 256L
        }
        return (sizeMb * multiplier).toLong() + cacheOverhead
    }

    private fun mobileFitScore(entry: ModelScopeCatalogEntry, profile: DeviceProbeSnapshot): Int {
        val estimated = estimateMobileMemoryMb(entry)
        val available = profile.availableRamMb.coerceAtLeast(512L)
        val memoryScore = when {
            estimated <= available * 0.55 -> 100
            estimated <= available * 0.70 -> 90
            estimated <= available * 0.88 -> 72
            estimated <= available -> 55
            else -> 25
        }
        val params = modelParameterValue(entry.parameterLabel)
        val cpuBonus = when {
            profile.coreCount >= 8 && params >= 4.0 -> 6
            profile.coreCount >= 6 -> 3
            params <= 1.7 -> 4
            else -> 0
        }
        val preferenceBonus = when (recommendationPreference) {
            RecommendationPreference.SPEED -> if (params <= 1.7 || entry.quantization.contains("IQ1", ignoreCase = true)) 8 else 0
            RecommendationPreference.STABILITY -> if (entry.quantization.contains("Q4", ignoreCase = true) || params in 1.0..4.0) 8 else 0
            RecommendationPreference.SMALL_MODEL -> if (estimated <= 1100L) 10 else 0
        }
        return (memoryScore + cpuBonus + preferenceBonus).coerceIn(0, 100)
    }

    private fun fitLabel(entry: ModelScopeCatalogEntry, profile: DeviceProbeSnapshot): String {
        val estimated = estimateMobileMemoryMb(entry)
        val available = profile.availableRamMb.coerceAtLeast(512L)
        return when {
            estimated <= available * 0.70 -> "推荐"
            estimated <= available * 0.90 -> "可尝试"
            estimated <= available -> "偏吃紧"
            else -> "不建议"
        }
    }

    private fun recommendationReasonFor(entry: ModelScopeCatalogEntry, profile: DeviceProbeSnapshot): String {
        val params = modelParameterValue(entry.parameterLabel)
        return when {
            fitLabel(entry, profile) == "不建议" -> "当前可用 RAM 偏低，建议先选 0.6B/1.5B。"
            params >= 7.0 -> "适合高内存旗舰机，下载前确认存储和散热。"
            params >= 4.0 -> "适合中高端手机，质量优先时选择。"
            params >= 1.5 -> "手机端质量和速度比较平衡。"
            else -> "适合快速验证 API、下载和加载链路。"
        }
    }

    private fun modelParameterValue(label: String): Double {
        val normalized = label.trim().uppercase(Locale.US)
        return when {
            normalized.endsWith("B") -> normalized.removeSuffix("B").toDoubleOrNull() ?: 0.0
            normalized.endsWith("M") -> (normalized.removeSuffix("M").toDoubleOrNull() ?: 0.0) / 1000.0
            else -> Regex("(\\d+(?:\\.\\d+)?)").find(normalized)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        }
    }

    private fun formatCount(value: Long): String {
        return when {
            value >= 1_000_000 -> "%.1fM".format(Locale.US, value / 1_000_000.0)
            value >= 1_000 -> "%.1fk".format(Locale.US, value / 1_000.0)
            else -> value.toString()
        }
    }

    private fun buildTestChatCard(): View {
        return surfaceCard(Palette.lavender, gradient = true) {
            addView(cardHeader("本机推理检测", "自动准备模型、试聊并记录速度", "play", Palette.lavender, "Smoke"))
            addView(space(12))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        miniMetricCard("Load", "模型", "GGUF", "cube", Palette.mint, compact = true),
                        LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginEnd = dp(6) }
                    )
                    addView(
                        miniMetricCard("Chat", "回复", "48 tok", "play", Palette.sky, compact = true),
                        LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginStart = dp(6); marginEnd = dp(6) }
                    )
                    addView(
                        miniMetricCard("Metrics", "速度", "tok/s", "gauge", Palette.lavender, compact = true),
                        LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginStart = dp(6) }
                    )
                }
            )
            addView(space(12))
            addView(
                roundedTextBlock("测试句：MobileCore 在手机上运行 GGUF。"),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
            )
            addView(space(12))
            testResultText = softInfoBlock("点击开始检测，完成后会显示回复、速度和内存。", Palette.sky, maxLines = 5)
            addView(testResultText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(space(8))
            testMetricsText = label("速度 -- · 首字 -- · 总耗时 --", 11.5f, Palette.muted, Typeface.BOLD)
            addView(testMetricsText)
            addView(space(12))
            addView(
                pillButton("开始检测", Palette.mintDark, Palette.mint) { runSmokeTest() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            )
            addView(space(8))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        pillButton("启动服务", Palette.sky, Palette.blue) { ensureNotificationPermissionAndStartService() },
                        LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
                    )
                    addView(
                        pillButton("试聊", Palette.mintDark, Palette.mint) { runTestChat() },
                        LinearLayout.LayoutParams(0, dp(48), 1f)
                    )
                }
            )
        }
    }

    private fun buildApiEndpointCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.surface, Palette.stroke, 18f)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(16), dp(16), dp(14))
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(IconBadgeView(context, "cloud", Palette.blue), LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
                    addView(label("本机接口", 14f, tint(Palette.ink, 0.86f), Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(chip(label("本地令牌", 12f, Palette.mintDark, Typeface.BOLD), Palette.mintPale, Palette.mint))
                }
            )
            addView(space(12))
            addView(
                roundedTextBlock("http://127.0.0.1:8080"),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            )
            addView(space(8))
            routeStatusText = label("请求只在本机服务内处理。", 12f, Palette.muted, Typeface.NORMAL)
            addView(routeStatusText)
        }
    }

    private fun buildVisionHeroCard(): View {
        val imageName = selectedVisionImageName ?: "尚未选择图片"
        return surfaceCard(Palette.sky, gradient = true) {
            addView(cardHeader("选择图片做 OCR", "独立视觉后端，不占用 GGUF 模型库", "image", Palette.sky, "Vision"))
            addView(space(12))
            visionImageText = label(imageName, 13f, Palette.ink, Typeface.BOLD).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(tint(Palette.sky, 0.08f), Palette.stroke, 14f)
                maxLines = 2
            }
            addView(visionImageText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(space(12))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        pillButton("选择图片", Palette.sky, Palette.blue) { openVisionImagePicker() },
                        LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
                    )
                    addView(
                        pillButton("开始 OCR", Palette.mintDark, Palette.mint) { runOcrProbe() },
                        LinearLayout.LayoutParams(0, dp(48), 1f)
                    )
                }
            )
        }
    }

    private fun buildVisionModelStatusCard(): View {
        val models = scanVisionModelFiles()
        val sidecars = scanVisionSidecarFiles()
        return surfaceCard(Palette.mint) {
            addView(cardHeader("视觉模型库", "ONNX / TFLite / MNN / sidecar", "chip", Palette.mint, "${models.size + sidecars.size} 个", Palette.mintDark))
            addView(space(8))
            visionModelSummaryText = label(visionModelSummary(models, sidecars), 12f, Palette.muted, Typeface.NORMAL).apply {
                maxLines = 3
            }
            addView(visionModelSummaryText)
            addView(space(10))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        pillButton("导入模型", Palette.mintDark, Palette.mint) { openVisionModelPicker() },
                        LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) }
                    )
                    addView(
                        pillButton("检查模型", Palette.sky, Palette.blue) { runVisionModelsProbe() },
                        LinearLayout.LayoutParams(0, dp(46), 1f)
                    )
                }
            )
            addView(space(8))
            addView(
                chipButton("复制视觉模型目录", false) { copyVisionModelDir() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
            )
            addView(space(8))
            addView(visionTaskRow("OCR", "rapid / ppocr / paddle / trocr", "ocr", Palette.mint))
            addView(space(8))
            addView(visionTaskRow("CLIP", "clip / vit ONNX encoder", "clip", Palette.sky))
            addView(space(8))
            addView(visionTaskRow("CIFAR10", "cifar10 TFLite 小 CNN", "cifar10", Palette.blue))
            addView(space(8))
            addView(visionTaskRow("MNIST", "mnist TFLite 小 CNN", "mnist", Palette.lavender))
            addView(space(10))
            addView(
                softInfoBlock("可导入 .onnx / .ort / .tflite / .mnn，也可导入 CLIP 的 cifar10-text-embeddings.json。", Palette.sky, maxLines = 3)
            )
        }
    }

    private fun visionTaskRow(title: String, hint: String, task: String, accent: Int): View {
        val installed = hasVisionModelTask(task)
        return modelRow(
            title,
            hint,
            if (installed) "已导入" else "缺失",
            if (installed) accent else Palette.muted
        )
    }

    private fun buildOcrModelCard(): View {
        return surfaceCard(Palette.mint) {
            addView(cardHeader("可用方案", "OCR 模型保持独立，不混入 LLM 目录", "image", Palette.mint, "视觉", Palette.mintDark))
            addView(space(8))
            addView(modelRow("RapidOCR / PP-OCR", "ONNX Runtime Mobile，适合首个 Android OCR demo", "优先", Palette.mint))
            addView(space(8))
            addView(modelRow("PaddleOCR 小模型", "检测 + 识别两段式，中文场景更稳", "候选", Palette.sky))
            addView(space(8))
            addView(modelRow("TrOCR tiny", "Transformer OCR，后续做文档图片评测", "研究", Palette.lavender))
        }
    }

    private fun buildVisionClassificationCard(): View {
        return surfaceCard(Palette.blue) {
            addView(cardHeader("图像分类", "CLIP、CIFAR10、MNIST 分开验收", "gauge", Palette.blue, "实验"))
            addView(space(8))
            addView(modelRow("CLIP zero-shot", "ONNX image/text encoder，适合 CIFAR10 演示", "CIFAR10", Palette.sky))
            addView(space(8))
            addView(modelRow("CIFAR10 小 CNN", "TFLite 直接分类，适合本机图像快测", "CIFAR10", Palette.blue))
            addView(space(8))
            addView(modelRow("MNIST 小 CNN", "TFLite 更适合手写数字，不强行走 CLIP", "MNIST", Palette.lavender))
            addView(space(10))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        pillButton("测 CIFAR10", Palette.sky, Palette.blue) { runVisionClassify("cifar10") },
                        LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) }
                    )
                    addView(
                        pillButton("测 MNIST", Palette.mintDark, Palette.mint) { runVisionClassify("mnist") },
                        LinearLayout.LayoutParams(0, dp(46), 1f)
                    )
                }
            )
        }
    }

    private fun buildOcrResultCard(): View {
        return surfaceCard(Palette.lavender) {
            addView(cardHeader("结果", "识别文本、耗时和后端状态", "play", Palette.lavender, "本机"))
            addView(space(10))
            visionResultText = softInfoBlock("请选择图片。OCR 引擎接入后，这里会显示识别文本和耗时。", Palette.mint, maxLines = 5)
            addView(visionResultText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun buildApiActionStrip(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                actionTile("复制命令", "复制示例", "chip", Palette.mint) { copyCurlExample() },
                LinearLayout.LayoutParams(0, dp(126), 1f).apply { marginEnd = dp(6) }
            )
            addView(
                actionTile("模型列表", "本机模型", "cube", Palette.sky) { runModelsProbe() },
                LinearLayout.LayoutParams(0, dp(126), 1f).apply { marginStart = dp(6); marginEnd = dp(6) }
            )
            addView(
                actionTile("试聊", "本机回复", "play", Palette.lavender) { runTestChat() },
                LinearLayout.LayoutParams(0, dp(126), 1f).apply { marginStart = dp(6) }
            )
        }
    }

    private fun buildApiRoutesCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.surface, Palette.stroke, 18f)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(16), dp(16), dp(14))
            addView(label("接口能力", 14f, tint(Palette.ink, 0.86f), Typeface.BOLD))
            addView(space(10))
            addView(routeRow("GET", "/v1/models", "查看本地 GGUF 模型") { runModelsProbe() })
            addView(routeRow("POST", "/v1/chat/completions", "发送一条本机回复") { runTestChat() })
            addView(routeRow("GET", "/metrics", "速度、首字、内存") { runMetricsProbe() })
            addView(routeRow("GET", "/v1/recommendations", "按设备能力推荐") {
                setTab(AppTab.HOME)
            })
            addView(routeRow("GET", "/leaderboard/local", "读取本机跑分榜") { runLocalLeaderboardProbe() })
            addView(routeRow("GET", "/leaderboard/shared", "共享榜配置状态") { runSharedLeaderboardProbe() })
            addView(routeRow("POST", "/leaderboard/shared", "上传本机榜记录") { runSharedLeaderboardSync() })
            addView(routeRow("GET", "/vision/status", "视觉能力状态") { runVisionStatusProbe() })
            addView(routeRow("GET", "/vision/models", "已导入视觉模型") { runVisionModelsProbe() })
        }
    }

    private fun buildSettingsCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.surface, Palette.stroke, 18f)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(16), dp(16), dp(14))
            addView(modelRow("本机接口", "127.0.0.1:8080 · 本地令牌", "本地", Palette.mint))
            addView(thinDivider())
            addView(modelRow("模型目录", "应用私有模型库，支持 GGUF 导入和下载", "文件", Palette.sky))
            addView(thinDivider())
            addView(modelRow("Google 登录", "未来计划：仅用于排行榜/云同步，不影响离线推理", "计划中", Palette.lavender))
        }
    }

    private fun buildFuturePlanCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(tint(Palette.lavender, 0.08f), Palette.stroke, 18f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(label("下一步", 12f, Palette.muted, Typeface.BOLD))
            addView(space(8))
            addView(label("1. Google 登录：用于共享排行榜和云同步，不影响离线推理。", 13f, tint(Palette.ink, 0.82f), Typeface.NORMAL).apply { maxLines = 3 })
            addView(space(6))
            addView(label("2. 下载体验：支持完成、取消、失败恢复和继续下载。", 13f, tint(Palette.ink, 0.82f), Typeface.NORMAL).apply { maxLines = 3 })
            addView(space(6))
            addView(label("3. 推理体验：流式输出、参数控制、CPU/RAM 动态面板。", 13f, tint(Palette.ink, 0.82f), Typeface.NORMAL).apply { maxLines = 3 })
        }
    }


    private fun buildMetricStrip(): View {
        val model = findPreferredGguf()
        val loadedLabel = model?.let { displayModelName(it.nameWithoutExtension) } ?: "待选择"
        val modelSize = model?.let { formatBytes(it.length()) } ?: "导入后显示"
        val apiLabel = if (activeModelPath != null) "运行中" else "未开启"
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(metricCardWithValue("模型", loadedLabel, modelSize, "cube", Palette.mint) { runtimeLoadedModelText = it })
            addView(metricCardWithValue("内存", modelSize, "峰值占用", "chip", Palette.lavender) { runtimeMemoryUseText = it })
            addView(metricCardWithValue("服务", apiLabel, "本机接口", "cloud", Palette.sky) { runtimeApiStatusText = it })
            addView(metricCardWithValue("速度", "0.0 tok/s", "上次检测", "gauge", Palette.blue) { runtimeSpeedText = it })
        }
        scroll.addView(row)
        return scroll
    }

    private fun buildHomeRuntimeCard(): View {
        val model = findPreferredGguf()
        val loadedLabel = model?.let { displayModelName(it.nameWithoutExtension) } ?: "待选择"
        val modelSize = model?.let { formatBytes(it.length()) } ?: "导入模型后显示"
        val serviceLabel = if (activeModelPath != null) "运行中" else "未开启"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(tint(Palette.blueWash, 0.52f), tint(Palette.sky, 0.18f), 16f)
            elevation = dp(1f).toFloat()
            setPadding(dp(13), dp(12), dp(13), dp(12))
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        label("运行概览", 11.6f, tint(Palette.ink, 0.58f), Typeface.BOLD),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(chip(label("本机", 9.4f, Palette.mintDark, Typeface.BOLD), Palette.mintPale, Palette.mint))
                }
            )
            addView(space(9))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(homeStatusItem("模型", loadedLabel, modelSize, Palette.mint) { homeModelText = it }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(7) })
                    addView(homeStatusItem("内存", modelSize, "上次记录", Palette.lavender) { homeMemoryText = it }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(7) })
                    addView(homeStatusItem("服务", serviceLabel, "本机运行", Palette.sky) { homeServiceText = it }, LinearLayout.LayoutParams(0, dp(54), 1f))
                }
            )
        }
    }

    private fun homeStatusItem(
        title: String,
        value: String,
        caption: String,
        accent: Int,
        bindValue: (TextView) -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(tint(accent, 0.07f), tint(accent, 0.14f), 13f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(label(title, 8.8f, Palette.muted, Typeface.BOLD))
            addView(space(2))
            val valueText = label(value, 9.2f, tint(Palette.ink, 0.50f), Typeface.BOLD).apply {
                maxLines = 1
            }
            bindValue(valueText)
            addView(valueText)
            addView(space(1))
            addView(label(caption, 8.6f, Palette.muted, Typeface.NORMAL).apply { maxLines = 1 })
        }
    }

    private fun buildLocalLeaderboardCard(): View {
        benchmarkLeaderboardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        renderLocalLeaderboard()
        return surfaceCard(Palette.sky) {
            addView(cardHeader("本机前 10", "标准跑分 v1，保存在本机", "gauge", Palette.sky, "本机保存"))
            addView(space(10))
            addView(benchmarkLeaderboardContainer)
        }
    }

    private fun renderLocalLeaderboard() {
        val container = benchmarkLeaderboardContainer ?: return
        container.removeAllViews()
        val entries = BenchmarkLeaderboardStore(applicationContext).entries().take(10)
        if (entries.isEmpty()) {
            container.addView(
                label("暂无跑分记录。点击开始检测后，会自动生成本机排名。", 13f, Palette.muted, Typeface.NORMAL).apply {
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(tint(Palette.mint, 0.08f), Palette.stroke, 14f)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            return
        }
        entries.forEachIndexed { index, entry ->
            container.addView(buildLeaderboardRow(index + 1, entry))
            if (index != entries.lastIndex) {
                container.addView(space(8))
            }
        }
    }

    private fun buildLeaderboardRow(rank: Int, entry: BenchmarkLeaderboardEntry): View {
        val accent = when (rank) {
            1 -> Palette.mint
            2 -> Palette.sky
            3 -> Palette.lavender
            else -> Palette.blue
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(tint(accent, 0.08f), Palette.stroke, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(
                label(rank.toString(), 16f, tint(accent, 0.80f), Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    background = rounded(tint(accent, 0.14f), Color.TRANSPARENT, 14f)
                },
                LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) }
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(displayModelName(entry.modelId), 13f, tint(Palette.ink, 0.82f), Typeface.BOLD).apply { maxLines = 1 })
                    addView(space(2))
                    addView(
                        label(
                            "${"%.2f".format(Locale.US, entry.decodeTokensPerSecond)} tok/s · 首字 ${entry.firstTokenMs}ms · 内存 ${entry.memoryPeakMb}MB",
                            11f,
                            Palette.muted,
                            Typeface.NORMAL
                        ).apply { maxLines = 1 }
                    )
                    addView(space(1))
                    addView(
                        label(
                            "${entry.quantization} · 加载 ${entry.loadTimeMs}ms · 总耗时 ${entry.totalMs}ms",
                            10.5f,
                            Palette.muted,
                            Typeface.NORMAL
                        ).apply { maxLines = 1 }
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
            addView(label(entry.score.total.toString(), 15f, Palette.ink, Typeface.BOLD))
                    addView(label("综合分", 10.5f, Palette.muted, Typeface.NORMAL))
                }
            )
        }
    }

    private fun buildModelHubCard(): View {
        providerStateByProvider.putIfAbsent(downloadTaskKey(modelHubItems[0]), ModelDownloadState(modelHubItems[0]))
        providerStateByProvider.putIfAbsent(downloadTaskKey(modelHubItems[1]), ModelDownloadState(modelHubItems[1]))
        return surfaceCard(Palette.mint) {
            addView(cardHeader("模型站", "ModelScope / HuggingFace 下载队列", "download", Palette.mint, "GGUF"))
            addView(space(10))
            val modelScopeTile = actionTile("ModelScope", "国内镜像（推荐）", "download", Palette.mintDark) {
                enqueueModelDownload(modelHubItems.first { it.provider == "ModelScope" })
            }
            val huggingFaceTile = actionTile("HuggingFace", "Qwen 0.5B", "download", Palette.blue) {
                enqueueModelDownload(modelHubItems.first { it.provider == "HuggingFace" })
            }
            providerTileByProvider[downloadTaskKey(modelHubItems.first { it.provider == "HuggingFace" })] = huggingFaceTile
            providerTileByProvider[downloadTaskKey(modelHubItems.first { it.provider == "ModelScope" })] = modelScopeTile
            addView(
                actionRow(modelScopeTile, huggingFaceTile)
            )
            addView(space(10))
            addView(buildModelHubStatusRows())
            addView(space(10))
            addView(label("下载到应用模型库，完成后可直接加载。", 12f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
        }
    }

    private fun buildModelHubStatusRows(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("下载状态", 13f, Palette.ink, Typeface.BOLD).apply { maxLines = 1 })
            addView(space(6))
            addView(buildModelHubStatusRow(modelHubItems.first { it.provider == "ModelScope" }))
            addView(space(6))
            addView(buildModelHubStatusRow(modelHubItems.first { it.provider == "HuggingFace" }))
        }
    }

    private fun buildModelHubStatusRow(item: ModelHubItem): View {
        val taskKey = downloadTaskKey(item)
        val titleText = label("${item.provider} · ${item.shortName}", 13f, Palette.ink, Typeface.BOLD)
        val statusText = label("状态：空闲", 12f, Palette.muted, Typeface.NORMAL)
        val messageText = label("可下载", 12f, Palette.muted, Typeface.NORMAL)
        val progressText = label("进度：0B / 未知 (0%)", 11f, Palette.muted, Typeface.NORMAL)
        val cancelButton = (pillButton("暂停", Palette.sky, Palette.blue) {
            handleDownloadControl(taskKey)
        } as TextView).apply {
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(4))
        }

        providerTitleByProvider[taskKey] = titleText
        providerStatusByProvider[taskKey] = statusText
        providerMessageByProvider[taskKey] = messageText
        providerProgressByProvider[taskKey] = progressText
        providerCancelByProvider[taskKey] = cancelButton
        refreshModelDownloadStatus(taskKey)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(tint(Palette.mint, 0.10f), Palette.stroke, 14f)
            addView(
                LinearLayout(context).apply {
                    addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(cancelButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            )
            addView(space(4))
            addView(statusText)
            addView(space(2))
            addView(messageText)
            addView(progressText)
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
                    addView(label("设备能力", 14f, tint(Palette.ink, 0.86f), Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label("本机优先", 13f, Palette.muted, Typeface.BOLD))
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
                    "设备",
                    displayDeviceName(deviceProfile.manufacturer, deviceProfile.model),
                    "型号信息",
                    "cube",
                    Palette.mint
                ) { valueText ->
                    probeDeviceText = valueText
                }
            )
            row.addView(
                metricCardWithValue(
                    "内存",
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
                    "处理器",
                    deviceProfile.coreCount.toString(),
                    "核心数",
                    "gauge",
                    Palette.lavender
                ) { valueText ->
                    probeCpuText = valueText
                }
            )
            row.addView(
                metricCardWithValue(
                    "引擎",
                    deviceProfile.backend,
                    "推理后端",
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
                    addView(label("推荐模型", 14f, tint(Palette.ink, 0.86f), Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
                    addView(label("偏好", 12f, Palette.muted, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

    private fun miniListCard(
        title: String,
        subtitle: String,
        badge: String?,
        icon: String,
        accent: Int,
        selected: Boolean = false,
        onClick: (() -> Unit)? = null
    ): View {
        val backgroundColor = tint(accent, if (selected) 0.14f else 0.075f)
        val borderColor = tint(accent, if (selected) 0.34f else 0.18f)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            background = if (onClick == null) rounded(backgroundColor, borderColor, 14f) else ripple(rounded(backgroundColor, borderColor, 14f), accent)
            setPadding(dp(11), dp(9), dp(10), dp(9))
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            addView(IconBadgeView(context, icon, accent), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) })
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(title, 11.7f, tint(Palette.ink, 0.64f), Typeface.BOLD).apply { maxLines = 1 })
                    addView(space(4))
                    addView(label(subtitle, 10.5f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (!badge.isNullOrBlank()) {
                addView(
                    chip(label(badge, 10.2f, tint(accent, 0.78f), Typeface.BOLD), tint(accent, 0.12f), accent),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply { marginStart = dp(8) }
                )
            }
        }
    }

    private fun miniMetricCard(
        title: String,
        value: String,
        caption: String,
        icon: String,
        accent: Int,
        compact: Boolean = false,
        bindValue: ((TextView) -> Unit)? = null
    ): LinearLayout {
        val iconSize = if (compact) 22 else 28
        val titleSize = if (compact) 8.7f else 9.8f
        val valueSize = if (compact) 10.0f else 11.4f
        val captionSize = if (compact) 8.4f else 9.6f
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(tint(accent, 0.065f), tint(accent, 0.18f), 14f)
            elevation = dp(0.5f).toFloat()
            setPadding(dp(9), dp(if (compact) 7 else 9), dp(9), dp(if (compact) 7 else 9))
            addView(IconBadgeView(context, icon, accent), LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))
            addView(space(if (compact) 4 else 7))
            addView(label(title, titleSize, Palette.muted, Typeface.BOLD).apply { maxLines = 1 })
            addView(space(3))
            val valueText = label(value, valueSize, tint(Palette.ink, 0.58f), Typeface.BOLD).apply { maxLines = 2 }
            bindValue?.invoke(valueText)
            addView(valueText)
            addView(space(2))
            addView(label(caption, captionSize, Palette.muted, Typeface.NORMAL).apply { maxLines = 1 })
        }
    }

    private fun metricCardWithValue(
        title: String,
        value: String,
        caption: String,
        icon: String,
        accent: Int,
        bindValue: (TextView) -> Unit
    ): View {
        return miniMetricCard(title, value, caption, icon, accent, bindValue = bindValue).apply {
            layoutParams = LinearLayout.LayoutParams(dp(100), dp(104)).apply {
                marginEnd = dp(8)
            }
        }
    }

    private fun metricCard(title: String, value: String, caption: String, icon: String, accent: Int): View {
        return miniMetricCard(title, value, caption, icon, accent).apply {
            layoutParams = LinearLayout.LayoutParams(dp(126), dp(148)).apply {
                marginEnd = dp(10)
            }
        }
    }

    private fun buildActionGrid(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                actionRow(
                    actionTile("导入模型", "选择 GGUF", "cube", Palette.mint) { openGgufPicker() },
                    actionTile("启动服务", "本机运行", "play", Palette.blue) {
                        ensureNotificationPermissionAndStartService()
                    }
                )
            )
            addView(space(12))
            addView(
                actionRow(
                    actionTile("加载模型", "优先本地模型", "gauge", Palette.lavender) {
                        ensureNotificationPermissionAndLoadFirstModel()
                    },
                    actionTile("停止服务", "关闭本机接口", "stop", Palette.sky) { stopMobileCoreService() }
                )
            )
            addView(space(12))
            addView(
                actionRow(
                    actionTile("试聊", "本机回复", "play", Palette.mintDark) { runTestChat() },
                    actionTile("ModelScope", "下载小模型", "download", Palette.blue) {
                        enqueueModelDownload(modelHubItems.first { it.provider == "ModelScope" })
                    }
                )
            )
        }
    }

    private fun actionRow(left: View, right: View): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, dp(94), 1f).apply { marginEnd = dp(6) })
            addView(right, LinearLayout.LayoutParams(0, dp(94), 1f).apply { marginStart = dp(6) })
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
            background = ripple(rounded(tint(accent, 0.075f), tint(accent, 0.18f), 14f), accent)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(IconBadgeView(context, icon, accent), LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(space(7))
            addView(label(title, 11.5f, tint(Palette.ink, 0.62f), Typeface.BOLD).apply { maxLines = 1 })
            addView(space(3))
            addView(label(caption, 10.3f, Palette.muted, Typeface.NORMAL).apply { maxLines = 1 })
        }
    }

    private fun buildRecentModelsCard(): View {
        val model = findPreferredGguf()
        return surfaceCard(Palette.lavender) {
            addView(cardHeader("最近模型", "导入后可直接加载测试", "cube", Palette.lavender, "GGUF"))
            addView(space(12))
            if (model != null) {
                addView(modelRow(model.nameWithoutExtension, "${formatBytes(model.length())} · GGUF", "可用", Palette.mint))
            } else {
                addView(modelRow("暂无本地模型", "从模型页下载，或从文件导入 GGUF", "空", Palette.sky))
            }
            addView(space(8))
            addView(modelRow("Qwen2.5 0.5B", "推荐的小体积测试模型", "推荐", Palette.lavender))
        }
    }

    private fun buildStatusCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.surface, Palette.stroke, 18f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(label("本机地址", 12f, Palette.muted, Typeface.BOLD))
            addView(space(6))
            statusText = label("http://$serviceHost:$servicePort/v1", 14f, Palette.ink, Typeface.BOLD)
            addView(statusText)
            addView(space(4))
            addView(label("兼容 OpenAI 格式，请求保留在本机。", 12f, Palette.muted, Typeface.NORMAL))
        }
    }

    private fun refreshRecommendationSnapshot() {
        Thread {
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
                } catch (_: Exception) {
                    // Keep raw networking details out of the visible UI.
                }
                Thread.sleep(300)
            }
            runOnUiThread {
                renderRecommendationPlaceholder("推荐暂不可用，请先启动本机服务。")
            }
        }.start()
    }

    private fun applyRecommendationPayload(json: JSONObject) {
        val device = json.optJSONObject("device") ?: JSONObject()
        val profileText = displayDeviceName(device.optString("manufacturer", Build.MANUFACTURER), device.optString("model", Build.MODEL))
        val ramText = "${device.optLong("available_ram_mb", 0L)} / ${device.optLong("total_ram_mb", 0L)} MB"

        val recommendations = json.optJSONArray("recommendations") ?: JSONArray()
        val backend = json.optJSONObject("runtime") ?: JSONObject()

        runOnUiThread {
            if (::probeDeviceText.isInitialized) probeDeviceText.text = profileText
            if (::probeMemoryText.isInitialized) probeMemoryText.text = ramText
            if (::probeCpuText.isInitialized) probeCpuText.text = "${device.optInt("core_count", Runtime.getRuntime().availableProcessors())} 核"
            if (::probeBackendText.isInitialized) {
                probeBackendText.text = displayBackendName(backend.optString("backend", device.optString("backend", "llama.cpp")))
            }
            if (!::recommendationContainer.isInitialized) return@runOnUiThread

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
                    "适合当前设备配置。"
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
        if (!::recommendationContainer.isInitialized) return
        recommendationContainer.removeAllViews()
        recommendationContainer.addView(
            label(message, 13f, Palette.muted, Typeface.NORMAL).apply {
                setPadding(0, dp(2), 0, dp(2))
            }
        )
    }

    private fun enqueueModelDownload(item: ModelHubItem) {
        val taskKey = downloadTaskKey(item)
        val state = providerStateByProvider[taskKey] ?: ModelDownloadState(item)
        val destination = File(externalModelDir(), item.fileName)
        providerStateByProvider[taskKey] = state

        if (state.isActive) {
            Toast.makeText(this, "${item.shortName} 下载正在进行中", Toast.LENGTH_SHORT).show()
            return
        }

        state.item = item
        if (destination.exists() && destination.length() > 1024 * 1024) {
            state.status = DownloadState.SUCCESS
            state.bytesDownloaded = destination.length()
            state.totalBytes = destination.length()
            state.percent = 100
            state.failureMessage = null
            refreshModelDownloadStatus(taskKey)
            Toast.makeText(this, "${item.shortName} 已在本机", Toast.LENGTH_SHORT).show()
            ensureNotificationPermissionAndLoadModel(destination)
            return
        }

        destination.parentFile?.mkdirs()
        state.status = DownloadState.DOWNLOADING
        state.destination = destination
        val partFile = File(destination.parentFile, "${destination.name}.part")
        val resumeBytes = partFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
        state.bytesDownloaded = resumeBytes
        state.percent = if (state.totalBytes > 0L && resumeBytes > 0L) {
            ((resumeBytes.toDouble() / state.totalBytes.toDouble()) * 100).toInt().coerceIn(0, 99)
        } else {
            0
        }
            state.failureMessage = null
            state.cancelRequested = false
        refreshModelDownloadStatus(taskKey)
        updateStatus(
            if (resumeBytes > 0L) "继续下载 ${item.shortName}"
            else "正在下载 ${item.shortName}"
        )
        progressHandler.removeCallbacks(progressPollRunnable)
        progressHandler.post(progressPollRunnable)
        Toast.makeText(this, "${item.shortName} 开始下载", Toast.LENGTH_SHORT).show()
        val thread = Thread {
            downloadModelInApp(taskKey, item, destination, state)
        }
        activeDownloadThreads[taskKey] = thread
        thread.start()
    }

    private fun downloadModelInApp(taskKey: String, item: ModelHubItem, destination: File, state: ModelDownloadState) {
        val partFile = File(destination.parentFile, "${destination.name}.part")
        var connection: HttpURLConnection? = null
        try {
            if (destination.exists()) destination.delete()
            var resumeBytes = partFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
            connection = openDownloadConnection(item.url, startByte = resumeBytes)
            if (resumeBytes > 0L && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                partFile.delete()
                resumeBytes = 0L
            }
            val totalBytes = (connection.contentLengthLong.coerceAtLeast(0L) + resumeBytes).coerceAtLeast(0L)
            state.totalBytes = totalBytes
            state.bytesDownloaded = resumeBytes
            FileOutputStream(partFile, resumeBytes > 0L).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = resumeBytes
                    var lastUiUpdate = 0L
                    while (true) {
                        if (state.cancelRequested || Thread.currentThread().isInterrupted) {
                            throw DownloadPausedException()
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read.toLong()
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate > 250L) {
                            lastUiUpdate = now
                            state.bytesDownloaded = downloaded
                            state.percent = if (totalBytes > 0L) {
                                ((downloaded.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 99)
                            } else {
                                0
                            }
                            runOnUiThread { refreshModelDownloadStatus(taskKey) }
                        }
                    }
                }
            }

            if (partFile.length() <= 1024 * 1024) {
                throw IOException("下载文件过小，可能不是 GGUF 模型")
            }
            if (!partFile.renameTo(destination)) {
                partFile.copyTo(destination, overwrite = true)
                partFile.delete()
            }
            state.status = DownloadState.SUCCESS
            state.bytesDownloaded = destination.length()
            state.totalBytes = destination.length()
            state.percent = 100
            state.failureMessage = null
            state.cancelRequested = false
            activeDownloadThreads.remove(taskKey)
            runOnUiThread {
                refreshModelDownloadStatus(taskKey)
                updateStatus("模型已下载：${destination.name}")
                Toast.makeText(this, "模型已下载", Toast.LENGTH_SHORT).show()
                ensureNotificationPermissionAndLoadModel(destination)
                refreshRecommendationSnapshot()
                progressEndIfNeeded()
                if (currentTab == AppTab.MODELS) renderCurrentTab()
            }
        } catch (_: DownloadPausedException) {
            state.status = DownloadState.PAUSED
            state.bytesDownloaded = partFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: state.bytesDownloaded
            state.percent = if (state.totalBytes > 0L) {
                ((state.bytesDownloaded.toDouble() / state.totalBytes.toDouble()) * 100).toInt().coerceIn(0, 99)
            } else {
                0
            }
            state.failureMessage = "已暂停，可继续"
            state.cancelRequested = false
            activeDownloadThreads.remove(taskKey)
            runOnUiThread {
                refreshModelDownloadStatus(taskKey)
                updateStatus("${item.shortName} 下载已暂停")
                Toast.makeText(this, "${item.shortName} 已暂停", Toast.LENGTH_SHORT).show()
                progressEndIfNeeded()
                if (currentTab == AppTab.MODELS) renderCurrentTab()
            }
        } catch (e: Exception) {
            state.status = DownloadState.FAILED
            state.failureMessage = "失败原因：${readableDownloadError(e)}"
            state.bytesDownloaded = partFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: state.bytesDownloaded
            state.cancelRequested = false
            activeDownloadThreads.remove(taskKey)
            runOnUiThread {
                refreshModelDownloadStatus(taskKey)
                updateStatus("模型下载失败：${readableDownloadError(e)}")
                Toast.makeText(this, "模型下载失败", Toast.LENGTH_LONG).show()
                progressEndIfNeeded()
                if (currentTab == AppTab.MODELS) renderCurrentTab()
            }
        } finally {
            connection?.disconnect()
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
                            "适配 ${fitLabelForUi(fit)} · 评分 $scoreText",
                            fitLabelForUi(fit),
                            accent
                        ),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    if (!loaded) {
                        addView(
                            pillButton("加载", Palette.sky, Palette.blue) {
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
            addView(label("预计内存 ${estimatedMemoryMb}MB · 约 $speedText tok/s", 12f, Palette.muted, Typeface.NORMAL))
            addView(space(2))
            addView(label("推荐原因：$reason", 12f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
        }
    }

    private fun fitLabelForUi(fit: String): String {
        return when (fit.lowercase(Locale.US)) {
            "perfect" -> "优秀"
            "good" -> "良好"
            "marginal" -> "可尝试"
            else -> "观察"
        }
    }

    private fun refreshModelDownloadStatus(taskKey: String) {
        val state = providerStateByProvider[taskKey] ?: return
        val titleView = providerTitleByProvider[taskKey]
        val statusView = providerStatusByProvider[taskKey] ?: return
        val messageView = providerMessageByProvider[taskKey] ?: return
        val progressView = providerProgressByProvider[taskKey] ?: return
        val cancelView = providerCancelByProvider[taskKey]
        val tile = providerTileByProvider[taskKey]
        val bytesTotalText = if (state.totalBytes > 0) formatBytes(state.totalBytes) else "未知"
        val percentText = if (state.totalBytes > 0L) "${state.percent}%" else "未知"
        titleView?.text = "${state.item.provider} · ${state.item.shortName}"

        when (state.status) {
            DownloadState.IDLE -> {
                statusView.text = "状态：空闲"
                messageView.text = "${state.item.provider} 可下载"
                cancelView?.visibility = View.GONE
                tile?.alpha = 1f
            }
            DownloadState.DOWNLOADING -> {
                statusView.text = "状态：进行中"
                messageView.text = "${state.item.shortName} 下载中"
                cancelView?.text = "暂停"
                cancelView?.visibility = View.VISIBLE
                tile?.alpha = 0.6f
            }
            DownloadState.PAUSED -> {
                statusView.text = "状态：已暂停"
                messageView.text = state.failureMessage ?: "已暂停，可继续"
                cancelView?.text = "继续"
                cancelView?.visibility = View.VISIBLE
                tile?.alpha = 1f
            }
            DownloadState.SUCCESS -> {
                statusView.text = "状态：成功"
                messageView.text = "${state.item.shortName} 下载完成，可直接加载"
                cancelView?.visibility = View.GONE
                tile?.alpha = 1f
            }
            DownloadState.FAILED -> {
                statusView.text = "状态：失败"
                messageView.text = state.failureMessage ?: "${state.item.shortName} 下载失败"
                cancelView?.text = "重试"
                cancelView?.visibility = View.VISIBLE
                tile?.alpha = 1f
            }
            DownloadState.CANCELLED -> {
                statusView.text = "状态：已取消"
                messageView.text = "已取消，可重试"
                cancelView?.text = "重试"
                cancelView?.visibility = View.VISIBLE
                tile?.alpha = 1f
            }
        }
        progressView.text = "进度：${formatBytes(state.bytesDownloaded)} / $bytesTotalText ($percentText)"
        progressView.visibility = View.VISIBLE
    }

    private fun handleDownloadControl(taskKey: String) {
        val state = providerStateByProvider[taskKey] ?: return
        when (state.status) {
            DownloadState.DOWNLOADING -> pauseModelDownload(taskKey)
            DownloadState.PAUSED,
            DownloadState.FAILED,
            DownloadState.CANCELLED -> enqueueModelDownload(state.item)
            else -> enqueueModelDownload(state.item)
        }
    }

    private fun pauseModelDownload(taskKey: String) {
        val state = providerStateByProvider[taskKey] ?: return
        if (state.status != DownloadState.DOWNLOADING) {
            Toast.makeText(this, "没有正在下载的任务", Toast.LENGTH_SHORT).show()
            return
        }
        state.cancelRequested = true
        state.status = DownloadState.PAUSED
        state.failureMessage = "暂停中..."
        refreshModelDownloadStatus(taskKey)
        activeDownloadThreads[taskKey]?.interrupt()
    }

    private fun openDownloadConnection(rawUrl: String, redirectLimit: Int = 5, startByte: Long = 0L): HttpURLConnection {
        var nextUrl = rawUrl
        repeat(redirectLimit) {
            val connection = (URL(nextUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "TuiMa-MobileCore/0.1.1 Android")
                setRequestProperty("Accept", "application/octet-stream,*/*")
                if (startByte > 0L) {
                    setRequestProperty("Range", "bytes=$startByte-")
                }
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("重定向缺少 Location")
                }
                nextUrl = URL(URL(nextUrl), location).toString()
            } else if (code in 200..299) {
                return connection
            } else {
                val error = connection.errorStream?.bufferedReader()?.readText()?.take(160)
                connection.disconnect()
                throw IOException("HTTP $code ${error ?: ""}".trim())
            }
        }
        throw IOException("重定向过多")
    }

    private fun readableDownloadError(error: Exception): String {
        return when (error) {
            is java.net.SocketTimeoutException -> "网络超时"
            is java.net.UnknownHostException -> "无法解析主机"
            is java.net.ConnectException -> "连接失败"
            is IOException -> {
                val message = error.message.orEmpty()
                when {
                    message.contains("下载文件过小") -> "文件校验失败"
                    message.contains("重定向") || message.contains("Location") -> "下载链接暂不可用"
                    message.startsWith("HTTP") -> "下载服务暂不可用"
                    else -> "文件或网络错误"
                }
            }
            else -> "操作失败，请稍后重试"
        }
    }

    private class DownloadPausedException : IOException("download paused")

    private fun hasActiveDownload(): Boolean {
        return providerStateByProvider.values.any { it.isActive }
    }

    private fun progressEndIfNeeded() {
        if (!hasActiveDownload()) {
            progressHandler.removeCallbacks(progressPollRunnable)
        }
    }

    private fun itemFromProvider(provider: String): ModelHubItem? {
        return modelHubItems.firstOrNull { it.provider == provider }
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
                        updateStatus("模型已加载：$modelName")
                    } else {
                        Toast.makeText(this@MainActivity, response.optString("error", "模型加载失败"), Toast.LENGTH_LONG).show()
                    }
                    refreshRecommendationSnapshot()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "模型加载失败，请确认服务已启动", Toast.LENGTH_SHORT).show()
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

    private data class LocalApiResult(
        val status: Int,
        val body: String,
        val elapsedMs: Long
    )

    private data class ModelScopeRepoSeed(
        val owner: String,
        val name: String,
        val label: String
    ) {
        val repoId: String
            get() = "$owner/$name"
    }

    private data class ModelScopeSearchResult(
        val repos: List<ModelScopeRepoSeed>,
        val totalCount: Int
    )

    private data class ModelScopeCatalogEntry(
        val repoId: String,
        val displayTitle: String,
        val fileName: String,
        val filePath: String,
        val sizeBytes: Long,
        val quantization: String,
        val parameterLabel: String,
        val architecture: String,
        val downloads: Long,
        val recommendationReason: String = "",
        val tier: String = ""
    ) {
        val searchText: String
            get() = listOf(repoId, displayTitle, fileName, quantization, parameterLabel, architecture, recommendationReason, tier)
                .joinToString(" ")
                .lowercase(Locale.US)
    }

    private enum class DownloadState {
        IDLE,
        DOWNLOADING,
        PAUSED,
        SUCCESS,
        FAILED,
        CANCELLED
    }

    private data class ModelDownloadState(
        var item: ModelHubItem,
        var status: DownloadState = DownloadState.IDLE,
        var destination: File? = null,
        var bytesDownloaded: Long = 0L,
        var totalBytes: Long = 0L,
        var percent: Int = 0,
        var failureMessage: String? = null,
        @Volatile var cancelRequested: Boolean = false
    ) {
        val isActive: Boolean
            get() = status == DownloadState.DOWNLOADING
    }

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

    private enum class AppTab {
        HOME,
        MODELS,
        VISION,
        TEST,
        API,
        SETTINGS
    }

    private fun modelRow(name: String, subtitle: String, badge: String, accent: Int): View {
        return miniListCard(name, subtitle, badge, "cube", accent)
    }

    private fun buildBottomNavigation(): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = rounded(tint(Palette.surface, 0.96f), tint(Palette.sky, 0.16f), 22f)
            elevation = dp(2).toFloat()
            setPadding(dp(7), dp(5), dp(7), dp(5))
            addView(navItem("首页", "cube", AppTab.HOME), LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(navItem("模型", "cube", AppTab.MODELS), LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(navItem("视觉", "image", AppTab.VISION), LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(navItem("检测", "play", AppTab.TEST), LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(navItem("设置", "gauge", AppTab.SETTINGS), LinearLayout.LayoutParams(0, dp(52), 1f))
        }
    }

    private fun navItem(title: String, icon: String, tab: AppTab): View {
        val selected = currentTab == tab
        val accent = if (selected) Palette.mint else Palette.muted
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ripple(
                if (selected) rounded(tint(Palette.mint, 0.16f), Color.TRANSPARENT, 15f) else rounded(Color.TRANSPARENT, Color.TRANSPARENT, 15f),
                accent
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { setTab(tab) }
            addView(IconBadgeView(context, icon, accent), LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(space(2))
            addView(label(title, 9f, accent, if (selected) Typeface.BOLD else Typeface.NORMAL).apply { maxLines = 1 })
        }
    }

    private fun sectionTitle(title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title, 11.8f, tint(Palette.ink, 0.58f), Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(subtitle, 10.2f, Palette.muted, Typeface.NORMAL).apply { maxLines = 1 })
        }
    }

    private fun chipButton(text: String, selected: Boolean, onClick: () -> Unit): View {
        val accent = if (selected) Palette.mintDark else Palette.blue
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(if (selected) Palette.mintDark else Palette.muted)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = ripple(
                rounded(if (selected) Palette.mintPale else Palette.surface, tint(accent, 0.35f), 18f),
                accent
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun roundedTextBlock(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER_VERTICAL
            textSize = 12.8f
            setTextColor(tint(Palette.ink, 0.72f))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(tint(Palette.blueWash, 0.65f), Palette.stroke, 14f)
            maxLines = 2
        }
    }

    private fun routeRow(method: String, path: String, caption: String, onClick: () -> Unit): View {
        val accent = if (method == "GET") Palette.mintDark else Palette.blue
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(rounded(Color.WHITE, Palette.stroke, 12f), accent)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                chip(label(method, 12f, accent, Typeface.BOLD), tint(accent, 0.10f), accent),
                LinearLayout.LayoutParams(dp(76), dp(34)).apply { marginEnd = dp(10) }
            )
            addView(label(path, 13f, tint(Palette.ink, 0.76f), Typeface.BOLD).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(caption, 11.5f, Palette.muted, Typeface.NORMAL).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun copyCurlExample() {
        val command = """
            curl -s http://127.0.0.1:8080/v1/chat/completions \
              -H 'Authorization: Bearer local' \
              -H 'Content-Type: application/json' \
              -d '{"model":"local","messages":[{"role":"user","content":"Say hi from MobileCore"}],"max_tokens":48}'
        """.trimIndent()
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MobileCore cURL", command))
        routeStatusText?.text = "已复制 cURL 示例"
        Toast.makeText(this, "cURL 已复制", Toast.LENGTH_SHORT).show()
    }

    private fun runModelsProbe() {
        ensureNotificationPermissionAndStartService()
        callLocalApi(
            path = "/v1/models",
            method = "GET",
            body = null,
            onResult = { status, body, elapsed ->
                val count = runCatching { JSONObject(body).optJSONArray("data")?.length() ?: 0 }.getOrDefault(0)
                val message = if (status in 200..299) {
                    "模型列表已刷新 · $count 个 · ${elapsed}ms"
                } else {
                    "模型列表暂不可用"
                }
                routeStatusText?.text = message
                updateStatus(message)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun runMetricsProbe() {
        ensureNotificationPermissionAndStartService()
        callLocalApi(
            path = "/metrics",
            method = "GET",
            body = null,
            onResult = { status, body, _ ->
                val json = runCatching { JSONObject(body) }.getOrNull()
                val tps = json?.optDouble("last_decode_tokens_per_second", 0.0) ?: 0.0
                val firstToken = json?.optLong("last_first_token_ms", 0L) ?: 0L
                val message = if (status in 200..299) {
                    "推理指标已刷新 · ${"%.2f".format(Locale.US, tps)} tok/s · 首字 ${firstToken}ms"
                } else {
                    "推理指标暂不可用"
                }
                routeStatusText?.text = message
                refreshRuntimeSnapshotCard(
                    apiStatus = if (status in 200..299) "已启动" else "异常",
                    speedText = "${"%.2f".format(Locale.US, tps)} tok/s"
                )
                updateStatus(message)
                Toast.makeText(this, "指标已刷新", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun runLocalLeaderboardProbe() {
        ensureNotificationPermissionAndStartService()
        callLocalApi(
            path = "/leaderboard/local?limit=10",
            method = "GET",
            body = null,
            onResult = { status, body, _ ->
                val count = runCatching { JSONObject(body).optInt("count", 0) }.getOrDefault(0)
                val message = if (status in 200..299) "本机榜已刷新 · $count 条" else "本机榜暂不可用"
                routeStatusText?.text = message
                updateStatus(if (status in 200..299) "本机榜已刷新" else "本机榜请求异常")
                Toast.makeText(this, "本机榜已刷新", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun runSharedLeaderboardProbe() {
        ensureNotificationPermissionAndStartService()
        callLocalApi(
            path = "/leaderboard/shared",
            method = "GET",
            body = null,
            onResult = { status, body, _ ->
                val sharedStatus = runCatching { JSONObject(body).optString("status", "local_only") }.getOrDefault("local_only")
                val displayStatus = if (sharedStatus == "not_configured") "未配置" else sharedStatus
                val message = if (status in 200..299) "共享榜已检查 · $displayStatus" else "共享榜暂不可用"
                routeStatusText?.text = message
                updateStatus("共享榜状态已检查")
                Toast.makeText(this, "共享榜状态已检查", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun runSharedLeaderboardSync() {
        ensureNotificationPermissionAndStartService()
        callLocalApi(
            path = "/leaderboard/shared?limit=10",
            method = "POST",
            body = "{}",
            onResult = { status, body, _ ->
                val json = runCatching { JSONObject(body) }.getOrNull()
                val sharedStatus = json?.optString("status", "unknown") ?: "unknown"
                val uploaded = json?.optInt("uploaded", 0) ?: 0
                val displayStatus = when (sharedStatus) {
                    "ok" -> "已上传 $uploaded 条"
                    "not_configured" -> "未配置"
                    "empty" -> "暂无本机记录"
                    else -> sharedStatus
                }
                val message = if (status in 200..299) "共享榜：$displayStatus" else "共享榜同步失败"
                routeStatusText?.text = message
                updateStatus(if (sharedStatus == "ok") "共享榜已同步" else "共享榜未同步")
                Toast.makeText(this, displayStatus, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun runVisionStatusProbe() {
        ensureNotificationPermissionAndStartService()
        callLocalApi(
            path = "/vision/status",
            method = "GET",
            body = null,
            onResult = { status, body, _ ->
                val json = runCatching { JSONObject(body) }.getOrNull()
                val visionStatus = json?.optString("status", "unknown") ?: "unknown"
                val message = if (status in 200..299) "视觉后端已检查 · $visionStatus" else "视觉后端暂不可用"
                routeStatusText?.text = message
                updateStatus(if (visionStatus == "backend_not_installed") "视觉后端未安装" else "视觉后端已检查")
                Toast.makeText(this, "视觉后端已检查", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun runVisionModelsProbe() {
        ensureNotificationPermissionAndStartService()
        callLocalApi(
            path = "/vision/models",
            method = "GET",
            body = null,
            onResult = { status, body, _ ->
                val json = runCatching { JSONObject(body) }.getOrNull()
                val count = json?.optInt("count", 0) ?: 0
                val message = if (status in 200..299) "视觉模型已检查 · $count 个" else "视觉模型暂不可用"
                routeStatusText?.text = message
                val models = scanVisionModelFiles()
                visionModelSummaryText?.text = visionModelSummary(models)
                visionResultText?.text = if (count > 0) {
                    "已检测到 $count 个视觉模型。\n${visionModelSummary(models)}"
                } else {
                    "未导入视觉模型。\n请放入 .onnx / .ort / .tflite / .mnn 到视觉模型目录。"
                }
                updateStatus(if (count > 0) "已检测到视觉模型" else "未导入视觉模型")
                Toast.makeText(this, if (count > 0) "已检测到 $count 个视觉模型" else "未导入视觉模型", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun runTestChat() {
        if (isTestRunning) {
            Toast.makeText(this, "测试正在运行", Toast.LENGTH_SHORT).show()
            return
        }
        isTestRunning = true
        testResultText?.text = "正在启动本地 API 并发送测试请求..."
        testMetricsText?.text = "速度 -- · 首字 -- · 总耗时 --"
        ensureNotificationPermissionAndStartService()

        val requestBody = JSONObject().apply {
            put("model", findPreferredGguf()?.nameWithoutExtension ?: "local")
            put("max_tokens", 48)
            put("temperature", 0.2)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Only output this exact sentence: MobileCore runs GGUF language models locally on your phone.")
                    })
                }
            )
        }.toString()

        callLocalApi(
            path = "/v1/chat/completions",
            method = "POST",
            body = requestBody,
            retryCount = 4,
            onResult = { status, body, elapsed ->
                isTestRunning = false
                val json = runCatching { JSONObject(body) }.getOrNull()
                val answer = json
                    ?.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.takeIf { it.isNotBlank() }
                    ?: body.take(220)
                val mobilecore = json?.optJSONObject("mobilecore")
                val tps = mobilecore?.optDouble("decode_tokens_per_second", 0.0) ?: 0.0
                val firstToken = mobilecore?.optLong("first_token_ms", 0L) ?: 0L
                val total = mobilecore?.optLong("total_ms", elapsed) ?: elapsed
                testResultText?.text = if (status in 200..299) answer else "请求失败。请确认模型已加载后再试。"
                testMetricsText?.text = "速度 ${"%.2f".format(Locale.US, tps)} tok/s · 首字 ${firstToken}ms · 总耗时 ${total}ms"
                routeStatusText?.text = if (status in 200..299) "试聊完成 · ${elapsed}ms" else "试聊失败"
                updateStatus(if (status in 200..299) "测试完成 · ${elapsed}ms" else "测试失败")
            },
            onError = {
                isTestRunning = false
                testResultText?.text = "测试失败。请确认本机服务已启动，并且模型可加载。"
                testMetricsText?.text = "速度 -- · 首字 -- · 总耗时 --"
                routeStatusText?.text = "测试失败"
                updateStatus("测试失败")
            }
        )
    }

    private fun runSmokeTest() {
        if (isTestRunning) {
            Toast.makeText(this, "检测正在运行", Toast.LENGTH_SHORT).show()
            return
        }
        val model = findPreferredGguf()
        if (model == null) {
            testResultText?.text = "未找到 GGUF 模型。请先在模型页下载或导入模型。"
            testMetricsText?.text = "速度 -- · 首字 -- · 总耗时 --"
            updateStatus("未找到 GGUF 模型")
            return
        }

        withNotificationPermission {
            isTestRunning = true
            testResultText?.text = "准备本机服务..."
            testMetricsText?.text = "速度 -- · 首字 -- · 总耗时 --"
            val deviceProfile = probeDeviceProfile()
            val spec = BenchmarkSpec.v1(threads = deviceProfile.coreCount.coerceAtMost(6))
            startServiceInForeground()

            Thread {
                try {
                    updateTestStatus("正在检查本机服务...")
                    localApiRequestBlocking(
                        path = "/health",
                        method = "GET",
                        body = null,
                        retryCount = 8,
                        readTimeoutMs = 2500
                    )

                    updateTestStatus("正在加载模型 ${model.name}...")
                    val loadBody = JSONObject().apply {
                        put("path", model.absolutePath)
                        put("context_length", spec.contextLength)
                        put("threads", spec.threads)
                        put("gpu_layers", 0)
                    }.toString()
                    val loadResult = localApiRequestBlocking(
                        path = "/mobilecore/model/load",
                        method = "POST",
                        body = loadBody,
                        retryCount = 2,
                        readTimeoutMs = 15000
                    )
                    if (loadResult.status !in 200..299) {
                        throw IOException("load failed ${loadResult.status}: ${loadResult.body.take(180)}")
                    }
                    val loadJson = JSONObject(loadResult.body)
                    activeModelPath = model.absolutePath

                    updateTestStatus("正在生成测试回复...")
                    val chatBody = JSONObject().apply {
                        put("model", model.nameWithoutExtension)
                        put("max_tokens", spec.maxTokens)
                        put("temperature", spec.temperature.toDouble())
                        put(
                            "messages",
                            JSONArray().apply {
                                put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", spec.prompt)
                                })
                            }
                        )
                    }.toString()
                    val chatResult = localApiRequestBlocking(
                        path = "/v1/chat/completions",
                        method = "POST",
                        body = chatBody,
                        retryCount = 1,
                        readTimeoutMs = 45000
                    )
                    if (chatResult.status !in 200..299) {
                        throw IOException("chat failed ${chatResult.status}: ${chatResult.body.take(180)}")
                    }
                    val chatJson = JSONObject(chatResult.body)
                    val answer = chatJson
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        ?.takeIf { it.isNotBlank() }
                        ?: chatResult.body.take(220)
                    val mobilecore = chatJson.optJSONObject("mobilecore") ?: JSONObject()

                    updateTestStatus("正在读取速度和内存...")
                    val metricsResult = localApiRequestBlocking(
                        path = "/metrics",
                        method = "GET",
                        body = null,
                        retryCount = 2,
                        readTimeoutMs = 4000
                    )
                    if (metricsResult.status !in 200..299) {
                        throw IOException("metrics failed ${metricsResult.status}: ${metricsResult.body.take(180)}")
                    }
                    val metricsJson = JSONObject(metricsResult.body)
                    val tps = metricsJson.optDouble(
                        "last_decode_tokens_per_second",
                        mobilecore.optDouble("decode_tokens_per_second", 0.0)
                    )
                    val firstToken = metricsJson.optLong(
                        "last_first_token_ms",
                        mobilecore.optLong("first_token_ms", 0L)
                    )
                    val total = metricsJson.optLong(
                        "last_total_ms",
                        mobilecore.optLong("total_ms", chatResult.elapsedMs)
                    )
                    val loadMs = loadJson.optLong("load_time_ms", loadResult.elapsedMs)
                    val memoryPeak = metricsJson.optLong("memory_peak_mb", mobilecore.optLong("memory_peak_mb", 0L))
                    val quantization = GgufMetadataReader.read(model).quantization
                    val benchmarkResult = BenchmarkResult(
                        spec = spec,
                        deviceName = displayDeviceName(deviceProfile.manufacturer, deviceProfile.model),
                        totalRamMb = deviceProfile.totalRamMb,
                        availableRamMb = deviceProfile.availableRamMb,
                        coreCount = deviceProfile.coreCount,
                        modelId = model.nameWithoutExtension,
                        modelPath = model.absolutePath,
                        quantization = quantization,
                        modelSizeBytes = model.length(),
                        backend = deviceProfile.backend,
                        loadTimeMs = loadMs,
                        firstTokenMs = firstToken,
                        totalMs = total,
                        decodeTokensPerSecond = tps,
                        memoryPeakMb = memoryPeak,
                        completed = true
                    )
                    val score = BenchmarkScorer.score(benchmarkResult)
                    val (_, localRank) = BenchmarkLeaderboardStore(applicationContext).record(benchmarkResult, score)

                    runOnUiThread {
                        isTestRunning = false
                        testResultText?.text = "检测完成 · 综合分 ${score.total} · 本机第 ${localRank}\n${benchmarkOutputPreview(answer)}"
                        testMetricsText?.text = "速度 ${score.speed} · 响应 ${score.response} · 内存 ${score.memory} · 稳定 ${score.stability} · ${"%.2f".format(Locale.US, tps)} tok/s"
                        refreshRuntimeSnapshotCard(
                            modelName = model.nameWithoutExtension,
                            memoryText = if (memoryPeak > 0L) "${memoryPeak} MB" else formatBytes(model.length()),
                            apiStatus = "已启动",
                            speedText = "${"%.2f".format(Locale.US, tps)} tok/s"
                        )
                        routeStatusText?.text = "检测完成 · 本机第 ${localRank}"
                        updateStatus("检测完成 · 综合分 ${score.total}")
                        renderLocalLeaderboard()
                        refreshRecommendationSnapshot()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        isTestRunning = false
                        testResultText?.text = "检测失败。请确认模型文件完整、服务已启动后再试。"
                        testMetricsText?.text = "速度 -- · 首字 -- · 总耗时 --"
                        routeStatusText?.text = "检测失败"
                        updateStatus("检测失败")
                    }
                }
            }.start()
        }
    }

    private fun updateTestStatus(message: String) {
        runOnUiThread {
            testResultText?.text = message
            updateStatus(message)
        }
    }

    private fun benchmarkOutputPreview(answer: String): String {
        val normalized = answer.replace(Regex("\\s+"), " ").trim()
        val replacementCount = normalized.count { it == '\uFFFD' }
        if (normalized.isBlank() || replacementCount >= 3) {
            return "模型已完成生成；输出包含不可显示字符，建议换更稳定模型复测。"
        }
        val aligned = normalized.contains("MobileCore", ignoreCase = true) &&
            normalized.contains("GGUF", ignoreCase = true)
        val snippet = if (normalized.length > 150) normalized.take(147).trimEnd() + "..." else normalized
        return if (aligned) {
            "输出片段：$snippet"
        } else {
            "模型已完成生成；内容偏离提示，可换指令模型复测。"
        }
    }

    private fun refreshRuntimeSnapshotCard(
        modelName: String? = null,
        memoryText: String? = null,
        apiStatus: String? = null,
        speedText: String? = null
    ) {
        modelName?.let {
            val display = displayModelName(it)
            runtimeLoadedModelText?.text = display
            homeModelText?.text = display
        }
        memoryText?.let {
            runtimeMemoryUseText?.text = it
            homeMemoryText?.text = it
        }
        apiStatus?.let {
            runtimeApiStatusText?.text = it
            homeServiceText?.text = it
        }
        speedText?.let { runtimeSpeedText?.text = it }
    }

    private fun localApiRequestBlocking(
        path: String,
        method: String,
        body: String?,
        retryCount: Int,
        readTimeoutMs: Int
    ): LocalApiResult {
        var lastError: Exception? = null
        repeat(retryCount.coerceAtLeast(1)) { attempt ->
            try {
                if (attempt > 0) Thread.sleep(450L)
                val started = System.currentTimeMillis()
                val connection = (URL("http://$serviceHost:$servicePort$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    setRequestProperty("Authorization", "Bearer local")
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 1800
                    readTimeout = readTimeoutMs
                    if (body != null) doOutput = true
                }
                try {
                    if (body != null) {
                        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val responseBody = stream?.bufferedReader()?.readText() ?: ""
                    return LocalApiResult(status, responseBody, System.currentTimeMillis() - started)
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("本机接口请求失败")
    }

    private fun callLocalApi(
        path: String,
        method: String,
        body: String?,
        retryCount: Int = 2,
        onResult: (Int, String, Long) -> Unit,
        onError: (Exception) -> Unit = {
            runOnUiThread {
                routeStatusText?.text = "本机接口请求失败"
                Toast.makeText(this, "API 请求失败", Toast.LENGTH_SHORT).show()
            }
        }
    ) {
        Thread {
            var lastError: Exception? = null
            repeat(retryCount) { attempt ->
                try {
                    if (attempt > 0) Thread.sleep(450L)
                    val started = System.currentTimeMillis()
                    val connection = (URL("http://$serviceHost:$servicePort$path").openConnection() as HttpURLConnection).apply {
                        requestMethod = method
                        setRequestProperty("Authorization", "Bearer local")
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 1600
                        readTimeout = 8000
                        if (body != null) {
                            doOutput = true
                        }
                    }
                    if (body != null) {
                        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val responseBody = stream?.bufferedReader()?.readText() ?: ""
                    val elapsed = System.currentTimeMillis() - started
                    runOnUiThread { onResult(status, responseBody, elapsed) }
                    return@Thread
                } catch (e: Exception) {
                    lastError = e
                }
            }
            runOnUiThread { onError(lastError ?: IOException("本机接口请求失败")) }
        }.start()
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
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            importModelRequestCode -> importGguf(uri)
            pickVisionImageRequestCode -> handleVisionImage(uri)
            importVisionModelRequestCode -> handleVisionModelFile(uri)
        }
    }

    private fun startServiceInForeground() {
        val intent = Intent(this, MobileCoreService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        refreshRuntimeSnapshotCard(apiStatus = "已启动")
        updateStatus("本机服务已启动")
        refreshRecommendationSnapshot()
    }

    private fun stopMobileCoreService() {
        val intent = Intent(this, MobileCoreService::class.java)
        stopService(intent)
        refreshRuntimeSnapshotCard(apiStatus = "未启动")
        updateStatus("本机服务已停止")
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

    private fun openVisionImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, pickVisionImageRequestCode)
    }

    private fun openVisionModelPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/octet-stream",
                    "application/json",
                    "text/json",
                    "application/x-tflite",
                    "application/x-onnx"
                )
            )
        }
        startActivityForResult(intent, importVisionModelRequestCode)
    }

    private fun handleVisionImage(uri: Uri) {
        selectedVisionImageUri = uri
        val displayName = resolveDisplayName(uri) ?: "selected-image-${System.currentTimeMillis()}"
        val safeName = sanitizeVisionImageFileName(displayName)
        val destination = File(internalVisionImageDir(), safeName)
        selectedVisionImageName = safeName
        selectedVisionImagePath = null
        visionImageText?.text = "正在导入 $safeName..."
        visionResultText?.text = "正在复制图片到本机视觉工作区..."
        updateStatus("正在导入图片")

        Thread {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法打开所选图片" }
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                }
                runOnUiThread {
                    selectedVisionImagePath = destination.absolutePath
                    visionImageText?.text = "${destination.name} · ${formatBytes(destination.length())}"
                    visionResultText?.text = "图片已导入。点击开始 OCR 或分类按钮进行本机检查。"
                    updateStatus("图片已导入")
                }
            } catch (e: Exception) {
                if (destination.exists()) destination.delete()
                runOnUiThread {
                    selectedVisionImageName = null
                    selectedVisionImagePath = null
                    visionImageText?.text = "图片导入失败"
                    visionResultText?.text = "图片导入失败。请换一张本机图片重试。"
                    updateStatus("图片导入失败")
                    Toast.makeText(this, "图片导入失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleVisionModelFile(uri: Uri) {
        val displayName = resolveDisplayName(uri) ?: "vision-model-${System.currentTimeMillis()}"
        val safeName = sanitizeVisionModelFileName(displayName)
        val destination = File(internalVisionModelDir(), safeName)
        visionResultText?.text = "正在导入视觉模型：$safeName"
        updateStatus("正在导入视觉模型")

        Thread {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法打开所选视觉模型" }
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                }
                runOnUiThread {
                    visionModelSummaryText?.text = visionModelSummary()
                    visionResultText?.text = "${destination.name} 已导入 · ${formatBytes(destination.length())}\n点击检查模型刷新后端状态。"
                    Toast.makeText(this, "视觉模型已导入", Toast.LENGTH_SHORT).show()
                    updateStatus("视觉模型已导入")
                    if (currentTab == AppTab.VISION) renderCurrentTab()
                }
            } catch (e: Exception) {
                if (destination.exists()) destination.delete()
                runOnUiThread {
                    visionResultText?.text = "视觉模型导入失败。支持 .onnx / .ort / .tflite / .mnn / .json。"
                    Toast.makeText(this, "视觉模型导入失败", Toast.LENGTH_SHORT).show()
                    updateStatus("视觉模型导入失败")
                }
            }
        }.start()
    }

    private fun runOcrProbe() {
        val imageName = selectedVisionImageName
        val imagePath = selectedVisionImagePath
        if (selectedVisionImageUri == null || imageName.isNullOrBlank() || imagePath.isNullOrBlank()) {
            visionResultText?.text = "请先选择一张图片。"
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }
        visionResultText?.text = "正在检查 OCR 引擎..."
        ensureNotificationPermissionAndStartService()
        callLocalApi(
            path = "/vision/ocr",
            method = "POST",
            body = JSONObject().apply {
                put("image_name", imageName)
                put("image_path", imagePath)
            }.toString(),
            retryCount = 4,
            onResult = { _, body, elapsed ->
                val json = runCatching { JSONObject(body) }.getOrNull()
                val backendStatus = json?.optString("status", "unknown") ?: "unknown"
                val image = json?.optJSONObject("image")
                val imageLine = image?.let {
                    val width = it.optInt("width", 0)
                    val height = it.optInt("height", 0)
                    val bytes = it.optLong("size_bytes", 0L)
                    if (width > 0 && height > 0) "图片 ${width}x${height} · ${formatBytes(bytes)}" else "图片 ${formatBytes(bytes)}"
                } ?: "图片已读取"
                val message = when (backendStatus) {
                    "ok" -> json?.optString("text").orEmpty().ifBlank { "未识别到文字" }
                    "invalid_image" -> json?.optString("message", "图片无法读取。") ?: "图片无法读取。"
                    "backend_not_installed" -> "OCR 引擎未安装。建议先接 RapidOCR / PP-OCR（ONNX Runtime Mobile）。"
                    else -> json?.optString("message", "OCR 暂不可用，请稍后重试。") ?: "OCR 暂不可用，请稍后重试。"
                }
                visionResultText?.text = "$imageLine\n$message\n\n耗时 ${elapsed}ms"
                updateStatus(if (backendStatus == "ok") "OCR 完成" else "OCR 引擎未安装")
            }
        )
    }

    private fun runVisionClassify(dataset: String) {
        val imageName = selectedVisionImageName
        val imagePath = selectedVisionImagePath
        if (selectedVisionImageUri == null || imageName.isNullOrBlank() || imagePath.isNullOrBlank()) {
            visionResultText?.text = "请先选择一张图片。"
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }
        val displayDataset = if (dataset == "mnist") "MNIST" else "CIFAR10"
        visionResultText?.text = "正在检查 $displayDataset 分类引擎..."
        ensureNotificationPermissionAndStartService()
        callLocalApi(
            path = "/vision/classify",
            method = "POST",
            body = JSONObject().apply {
                put("image_name", imageName)
                put("image_path", imagePath)
                put("dataset", dataset)
            }.toString(),
            retryCount = 4,
            onResult = { _, body, elapsed ->
                val json = runCatching { JSONObject(body) }.getOrNull()
                val backendStatus = json?.optString("status", "unknown") ?: "unknown"
                val image = json?.optJSONObject("image")
                val imageLine = image?.let {
                    val width = it.optInt("width", 0)
                    val height = it.optInt("height", 0)
                    val bytes = it.optLong("size_bytes", 0L)
                    if (width > 0 && height > 0) "图片 ${width}x${height} · ${formatBytes(bytes)}" else "图片 ${formatBytes(bytes)}"
                } ?: "图片已读取"
                val message = when (backendStatus) {
                    "ok" -> {
                        val label = json?.optString("label").orEmpty().ifBlank { "未知类别" }
                        val confidence = json?.optDouble("confidence", 0.0) ?: 0.0
                        "$displayDataset：$label · 置信度 ${"%.2f".format(Locale.US, confidence)}"
                    }
                    "invalid_image" -> json?.optString("message", "图片无法读取。") ?: "图片无法读取。"
                    "model_missing" -> json?.optString("message").orEmpty()
                        .ifBlank { "请先导入 $displayDataset 对应的 TFLite/ONNX 模型。" }
                    "text_embeddings_missing" -> json?.optString("message").orEmpty()
                        .ifBlank { "CLIP 已就绪，但缺少 CIFAR10 文本 embedding sidecar。" }
                    "unsupported_model_shape", "model_load_error", "inference_error" -> json?.optString("message").orEmpty()
                        .ifBlank { "$displayDataset 分类模型暂不可用。" }
                    else -> json?.optString("message", "分类暂不可用，请稍后重试。") ?: "分类暂不可用，请稍后重试。"
                }
                visionResultText?.text = "$imageLine\n$message\n\n耗时 ${elapsed}ms"
                updateStatus(if (backendStatus == "ok") "分类完成" else "分类需要模型")
            }
        )
    }

    private fun importGguf(uri: Uri) {
        val displayName = resolveDisplayName(uri) ?: "imported-${System.currentTimeMillis()}.gguf"
        val safeName = sanitizeModelFileName(displayName)
        val destination = File(internalModelDir(), safeName)

        updateStatus("正在导入模型：$safeName")
        Thread {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法打开所选文件" }
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, "模型已导入", Toast.LENGTH_SHORT).show()
                    ensureNotificationPermissionAndLoadModel(destination)
                }
            } catch (e: Exception) {
                if (destination.exists()) destination.delete()
                runOnUiThread {
                    updateStatus("模型导入失败")
                    Toast.makeText(this, "GGUF 导入失败", Toast.LENGTH_SHORT).show()
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
        val model = findPreferredGguf()
        if (model == null) {
            updateStatus("未找到 GGUF 模型")
            Toast.makeText(this, "请先导入 GGUF 模型", Toast.LENGTH_SHORT).show()
            return
        }

        startServiceWithModel(model)
    }

    private fun startServiceWithModel(model: File) {
        activeModelPath = model.absolutePath
        val intent = Intent(this, MobileCoreService::class.java).apply {
            putExtra("modelPath", model.absolutePath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        refreshRuntimeSnapshotCard(
            modelName = model.nameWithoutExtension,
            memoryText = formatBytes(model.length()),
            apiStatus = "已启动"
        )
        updateStatus("正在加载模型：${model.name}")
        if (currentTab == AppTab.MODELS) renderCurrentTab()
    }

    private fun findPreferredGguf(): File? {
        return availableGgufModels()
            .minWithOrNull(
                compareBy<File> { preferredGgufScore(it) }
                    .thenBy { it.name.lowercase(Locale.US) }
            )
    }

    private fun availableGgufModels(): List<File> {
        return modelDirs()
            .flatMap { dir ->
                dir.listFiles { file ->
                    file.isFile && file.extension.lowercase(Locale.US) == "gguf"
                }?.toList() ?: emptyList()
            }
            .distinctBy { it.absolutePath }
    }

    private fun preferredGgufScore(file: File): Int {
        val metadata = GgufMetadataReader.read(file)
        val quantization = metadata.quantization.uppercase(Locale.US)
        val quantizationPriority = when {
            quantization.startsWith("Q4") -> 0
            quantization.startsWith("Q5") -> 1
            quantization == "F16" || quantization == "BF16" || quantization.startsWith("Q6") -> 2
            quantization.startsWith("Q3") -> 3
            quantization.startsWith("Q2") || quantization.startsWith("IQ2") -> 4
            quantization.startsWith("Q8") || quantization.startsWith("Q7") -> 5
            quantization.startsWith("Q1") || quantization.startsWith("IQ1") || quantization.contains("IQ1") -> 8
            else -> 6
        }
        val parameterPenalty = ((metadata.parameterCountB ?: 99.0) * 100).roundToInt().coerceAtMost(9900)
        val sizePenalty = (file.length() / (128L * 1024L * 1024L)).toInt().coerceAtMost(99)
        return quantizationPriority * 100_000 + parameterPenalty * 100 + sizePenalty
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

    private fun internalVisionImageDir(): File {
        return File(filesDir, "vision/images").apply { mkdirs() }
    }

    private fun visionModelDirs(): List<File> {
        return listOf(internalVisionModelDir(), externalVisionModelDir()).onEach { it.mkdirs() }
    }

    private fun internalVisionModelDir(): File {
        return File(filesDir, "vision/models")
    }

    private fun externalVisionModelDir(): File {
        return getExternalFilesDir("vision/models") ?: File(filesDir, "vision/models")
    }

    private fun scanVisionModelFiles(): List<File> {
        val supportedExtensions = setOf("onnx", "ort", "tflite", "mnn")
        return visionModelDirs()
            .flatMap { dir ->
                dir.listFiles { file ->
                    file.isFile && file.extension.lowercase(Locale.US) in supportedExtensions
                }?.toList() ?: emptyList()
            }
            .distinctBy { it.absolutePath }
            .sortedBy { it.name.lowercase(Locale.US) }
    }

    private fun scanVisionSidecarFiles(): List<File> {
        return visionModelDirs()
            .flatMap { dir ->
                dir.listFiles { file ->
                    file.isFile && file.extension.equals("json", ignoreCase = true)
                }?.toList() ?: emptyList()
            }
            .distinctBy { it.absolutePath }
            .sortedBy { it.name.lowercase(Locale.US) }
    }

    private fun hasVisionModelTask(task: String): Boolean {
        return scanVisionModelFiles().any { inferVisionTask(it.name) == task }
    }

    private fun inferVisionTask(fileName: String): String {
        val lower = fileName.lowercase(Locale.US)
        return when {
            "mnist" in lower -> "mnist"
            "clip" in lower || "vit" in lower -> "clip"
            "cifar" in lower -> "cifar10"
            "ocr" in lower || "ppocr" in lower || "paddle" in lower || "rapid" in lower || "trocr" in lower -> "ocr"
            "sd" in lower || "diffusion" in lower || "lcm" in lower -> "diffusion"
            else -> "vision"
        }
    }

    private fun visionModelSummary(
        models: List<File> = scanVisionModelFiles(),
        sidecars: List<File> = scanVisionSidecarFiles()
    ): String {
        if (models.isEmpty() && sidecars.isEmpty()) {
            return "还没有导入视觉模型。先导入 .onnx / .ort / .tflite / .mnn，CLIP 可另配 .json sidecar。"
        }
        val groups = models.groupingBy { inferVisionTask(it.name) }.eachCount()
        val modelSummary = groups.entries
            .sortedBy { it.key }
            .joinToString(" · ") { "${it.key.uppercase(Locale.US)} ${it.value}" }
        val sidecarSummary = if (sidecars.isNotEmpty()) "SIDECAR ${sidecars.size}" else ""
        return listOf(modelSummary, sidecarSummary)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun copyVisionModelDir() {
        val directory = externalVisionModelDir().absolutePath
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MobileCore vision models", directory))
        visionResultText?.text = "视觉模型目录已复制。"
        Toast.makeText(this, "视觉模型目录已复制", Toast.LENGTH_SHORT).show()
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

    private fun sanitizeVisionImageFileName(name: String): String {
        val cleaned = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val nonBlank = cleaned.ifBlank { "vision-${System.currentTimeMillis()}.png" }
        val allowed = setOf("jpg", "jpeg", "png", "webp", "bmp")
        return if (nonBlank.substringAfterLast('.', "").lowercase(Locale.US) in allowed) {
            nonBlank
        } else {
            "$nonBlank.png"
        }
    }

    private fun sanitizeVisionModelFileName(name: String): String {
        val cleaned = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val nonBlank = cleaned.ifBlank { "vision-model-${System.currentTimeMillis()}.onnx" }
        val allowed = setOf("onnx", "ort", "tflite", "mnn", "json")
        val extension = nonBlank.substringAfterLast('.', "").lowercase(Locale.US)
        return if (extension in allowed) nonBlank else "$nonBlank.onnx"
    }

    private fun updateStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = message
        if (::runtimeChipText.isInitialized) {
            runtimeChipText.text = when {
                message.contains("服务已启动") -> "本机服务已启动"
                message.contains("正在加载模型") -> "正在加载模型"
                message.contains("正在下载") || message.startsWith("Downloading") -> "正在下载模型"
                message.contains("已下载") || message.startsWith("Downloaded") -> "模型已下载"
                message.contains("模型已加载") -> "模型已加载"
                message.contains("服务已停止") -> "服务已停止"
                message.contains("未找到 GGUF") -> "需要模型"
                message.contains("失败") -> "需要处理"
                else -> "本机模型就绪"
            }
        }
    }

    private fun label(text: String, sizeSp: Float, color: Int, style: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, style)
            includeFontPadding = false
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
            textSize = 14f
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

    private fun displayDeviceName(manufacturer: String, model: String): String {
        val cleanManufacturer = manufacturer.ifBlank { "Android" }
        val cleanModel = model
            .replace("sdk_gphone64_", "", ignoreCase = true)
            .replace("sdk_gphone_", "", ignoreCase = true)
            .replace("arm64", "arm64", ignoreCase = true)
            .trim('_', '-', ' ')
            .ifBlank { model }
        val combined = if (cleanModel.contains(cleanManufacturer, ignoreCase = true)) {
            cleanModel
        } else {
            "$cleanManufacturer $cleanModel"
        }
        return if (combined.length <= 18) combined else combined.take(15).trim('_', '-', ' ') + "..."
    }

    private object Palette {
        const val background = 0xFFFBFDFF.toInt()
        const val surface = 0xFFFFFFFF.toInt()
        const val deepInk = 0xFF0B2B68.toInt()
        const val ink = 0xFF41516D.toInt()
        const val muted = 0xFF7A89A2.toInt()
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
                "image" -> drawImage(canvas, w, h)
                "bell" -> drawBell(canvas, w, h)
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

        private fun drawBell(canvas: Canvas, w: Float, h: Float) {
            strokePaint.color = accent
            strokePaint.strokeWidth = w * 0.075f
            val body = RectF(w * 0.28f, h * 0.24f, w * 0.72f, h * 0.68f)
            canvas.drawArc(body, 205f, 130f, false, strokePaint)
            canvas.drawLine(w * 0.29f, h * 0.48f, w * 0.24f, h * 0.68f, strokePaint)
            canvas.drawLine(w * 0.71f, h * 0.48f, w * 0.76f, h * 0.68f, strokePaint)
            canvas.drawLine(w * 0.24f, h * 0.68f, w * 0.76f, h * 0.68f, strokePaint)
            canvas.drawLine(w * 0.50f, h * 0.18f, w * 0.50f, h * 0.26f, strokePaint)
            paint.color = accent
            canvas.drawCircle(w * 0.50f, h * 0.80f, w * 0.045f, paint)
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

        private fun drawImage(canvas: Canvas, w: Float, h: Float) {
            canvas.drawRoundRect(RectF(w * 0.24f, h * 0.28f, w * 0.76f, h * 0.72f), w * 0.06f, w * 0.06f, strokePaint)
            paint.color = accent
            canvas.drawCircle(w * 0.60f, h * 0.42f, w * 0.055f, paint)
            val mountain = Path().apply {
                moveTo(w * 0.30f, h * 0.66f)
                lineTo(w * 0.44f, h * 0.52f)
                lineTo(w * 0.54f, h * 0.62f)
                lineTo(w * 0.64f, h * 0.54f)
                lineTo(w * 0.72f, h * 0.66f)
            }
            canvas.drawPath(mountain, strokePaint)
        }
    }
}
