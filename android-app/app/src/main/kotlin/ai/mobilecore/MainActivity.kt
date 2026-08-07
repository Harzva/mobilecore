package ai.mobilecore

import ai.mobilecore.benchmark.AndroidBenchmarkTelemetry
import ai.mobilecore.benchmark.BenchmarkAggregator
import ai.mobilecore.benchmark.BenchmarkDeviceIdentity
import ai.mobilecore.benchmark.BenchmarkDigestVerifier
import ai.mobilecore.benchmark.BenchmarkFailureKind
import ai.mobilecore.benchmark.BenchmarkManifestRepository
import ai.mobilecore.benchmark.BenchmarkPreflight
import ai.mobilecore.benchmark.BenchmarkPreflightReason
import ai.mobilecore.benchmark.BenchmarkPreflightResult
import ai.mobilecore.benchmark.BenchmarkPreflightSnapshot
import ai.mobilecore.benchmark.BenchmarkProfile
import ai.mobilecore.benchmark.BenchmarkReport
import ai.mobilecore.benchmark.BenchmarkReportStore
import ai.mobilecore.benchmark.BenchmarkRunSample
import ai.mobilecore.benchmark.BenchmarkScoreEngine
import ai.mobilecore.benchmark.BenchmarkSpecV2
import ai.mobilecore.benchmark.ThermalStatus
import ai.mobilecore.benchmark.BenchmarkUiEvent
import ai.mobilecore.benchmark.BenchmarkUiState
import ai.mobilecore.benchmark.BenchmarkUiStateMachine
import ai.mobilecore.runtime.BenchmarkResult
import ai.mobilecore.runtime.BenchmarkScorer
import ai.mobilecore.runtime.BenchmarkSpec
import ai.mobilecore.runtime.GgufMetadataReader
import ai.mobilecore.runtime.ModelLoadStatusContract
import ai.mobilecore.runtime.RuntimeBridge
import ai.mobilecore.g2d.G2dBranchTool
import ai.mobilecore.g2d.OxfordPetsG2dRunner
import ai.mobilecore.g2d.OxfordPetsRunScale
import ai.mobilecore.service.MobileCoreService
import ai.mobilecore.ui.BenchmarkLiveSnapshot
import ai.mobilecore.ui.BenchmarkShareCardRenderer
import ai.mobilecore.ui.BenchmarkScreenPresenter
import ai.mobilecore.ui.GallerySearchActions
import ai.mobilecore.ui.GallerySearchEvent
import ai.mobilecore.ui.GallerySearchPresenter
import ai.mobilecore.ui.GallerySearchScreen
import ai.mobilecore.ui.GallerySearchState
import ai.mobilecore.ui.GallerySearchStateMachine
import ai.mobilecore.ui.G2dValidationCallbacks
import ai.mobilecore.ui.G2dValidationExperiment
import ai.mobilecore.ui.G2dValidationInput
import ai.mobilecore.ui.G2dValidationMeasurement
import ai.mobilecore.ui.G2dValidationPresenter
import ai.mobilecore.ui.G2dValidationRouteCounts
import ai.mobilecore.ui.G2dValidationRunState
import ai.mobilecore.ui.G2dValidationScreen
import ai.mobilecore.ui.HomeScreenPresenter
import ai.mobilecore.ui.IconBadgeView
import ai.mobilecore.ui.ModelLifecyclePhase
import ai.mobilecore.ui.ModelLifecyclePresenter
import ai.mobilecore.ui.ModelLifecycleTone
import ai.mobilecore.ui.ModelLifecycleUiModel
import ai.mobilecore.ui.OmniLifecycleAction
import ai.mobilecore.ui.OmniLifecycleCallbacks
import ai.mobilecore.ui.OmniLifecyclePresenter
import ai.mobilecore.ui.OmniLifecycleScreen
import ai.mobilecore.ui.OmniLifecycleSnapshot
import ai.mobilecore.ui.Palette
import ai.mobilecore.ui.ResultsScreenPresenter
import ai.mobilecore.ui.StandardModelDownloadPhase
import ai.mobilecore.ui.TuiMaCircularProgressView
import ai.mobilecore.ui.TuiMaTheme
import ai.mobilecore.ui.TuiMaThemeMode
import ai.mobilecore.ui.VisionModelImportCallbacks
import ai.mobilecore.ui.VisionModelImportCatalog
import ai.mobilecore.ui.VisionModelImportPresenter
import ai.mobilecore.ui.VisionModelImportScreen
import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
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
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.TextViewCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

private const val PREF_RECOMMENDATION_MODE = "recommendation_preference"
private const val PREF_UI_THEME_MODE = "ui_theme_mode"
private const val STATE_CURRENT_TAB = "current_tab"
private const val BYTES_PER_MB = 1024L * 1024L

private class BenchmarkRunException(
    val kind: BenchmarkFailureKind,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var runtimeChipText: TextView
    private lateinit var preferenceLabelText: TextView
    private lateinit var recommendationContainer: LinearLayout
    private lateinit var rootScrollView: ScrollView
    private lateinit var contentRoot: LinearLayout
    private lateinit var bottomNavHost: FrameLayout
    private var currentTab = AppTab.HOME
    private var routeStatusText: TextView? = null
    private var visionImageText: TextView? = null
    private var visionResultText: TextView? = null
    private var visionModelSummaryText: TextView? = null
    private var selectedVisionImageUri: Uri? = null
    private var selectedVisionImageName: String? = null
    private var selectedVisionImagePath: String? = null
    private var requiredModelDownloadContainer: LinearLayout? = null
    private var isTestRunning = false
    private val benchmarkUiStateMachine = BenchmarkUiStateMachine()
    private var selectedBenchmarkProfile = BenchmarkProfile.STANDARD
    private var selectedResultRunId: String? = null
    private var comparisonBaselineRunId: String? = null
    private var selectingComparisonBaseline = false
    private var selectedThemeMode = TuiMaThemeMode.SYSTEM
    private var gallerySearchState = GallerySearchState()
    private var g2dValidationInput = G2dValidationInput(
        datasetName = "Oxford-Pets（官方 test.txt）",
        targetSampleCount = 3_669,
        preparationMessage = "官方测试划分已锁定；导入 Oxford-Pets 图像包、CLIP 和 VLM 后才能开始。",
    )
    private var activeG2dRunner: OxfordPetsG2dRunner? = null
    private var benchmarkStartedAtMs = 0L
    private var benchmarkLiveSnapshot = BenchmarkLiveSnapshot()
    @Volatile private var benchmarkCancellationRequested = false
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
    private var pendingModelPath: String? = null
    private var modelLoadFailurePath: String? = null
    private var modelLoadFailureMessage: String? = null
    private var modelLoadReceiverRegistered = false
    private var omniLifecycleSnapshot = OmniLifecycleSnapshot()
    private var omniStatusRefreshInFlight = false
    private val modelLoadStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ModelLoadStatusContract.ACTION) return
            val modelPath = intent.getStringExtra(ModelLoadStatusContract.EXTRA_MODEL_PATH) ?: return
            when (intent.getStringExtra(ModelLoadStatusContract.EXTRA_STATE)) {
                ModelLoadStatusContract.STATE_LOADING -> {
                    pendingModelPath = modelPath
                    modelLoadFailurePath = null
                    modelLoadFailureMessage = null
                    updateStatus("正在加载模型")
                }
                ModelLoadStatusContract.STATE_LOADED -> {
                    activeModelPath = modelPath
                    pendingModelPath = null
                    modelLoadFailurePath = null
                    modelLoadFailureMessage = null
                    updateStatus("模型已加载")
                }
                ModelLoadStatusContract.STATE_FAILED -> {
                    if (activeModelPath == modelPath) activeModelPath = null
                    pendingModelPath = null
                    modelLoadFailurePath = modelPath
                    modelLoadFailureMessage = intent.getStringExtra(ModelLoadStatusContract.EXTRA_MESSAGE)
                    updateStatus("模型加载失败")
                }
            }
            if (currentTab in setOf(AppTab.HOME, AppTab.MODELS, AppTab.TEST)) {
                renderCurrentTab()
            }
        }
    }
    private val activeDownloadThreads = ConcurrentHashMap<String, Thread>()
    private val progressHandler = Handler(Looper.getMainLooper())
    private val omniStatusPollRunnable = Runnable { refreshOmniLifecycleStatus() }
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
            shortName = "Gemma3 270M Q4",
            fileName = "gemma-3-270m-it-Q4_K_M.gguf",
            url = "https://modelscope.cn/models/unsloth/gemma-3-270m-it-GGUF/resolve/master/gemma-3-270m-it-Q4_K_M.gguf"
        )
    )
    private val modelScopeSeeds = listOf(
        ModelScopeRepoSeed("unsloth", "gemma-3-270m-it-GGUF", "Gemma3 270M"),
        ModelScopeRepoSeed("unsloth", "gemma-3-1b-it-GGUF", "Gemma3 1B"),
        ModelScopeRepoSeed("Qwen", "Qwen2.5-0.5B-Instruct-GGUF", "Qwen2.5 0.5B"),
        ModelScopeRepoSeed("unsloth", "Qwen3-0.6B-GGUF", "Qwen3 0.6B"),
        ModelScopeRepoSeed("Qwen", "Qwen2.5-1.5B-Instruct-GGUF", "Qwen2.5 1.5B")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedThemeMode = readThemeMode()
        TuiMaTheme.configure(selectedThemeMode, isSystemDarkTheme())
        currentTab = savedInstanceState?.getString(STATE_CURRENT_TAB)?.let { saved ->
            AppTab.entries.firstOrNull { it.name == saved }
        } ?: currentTab
        recommendationPreference = readRecommendationPreference()
        if (providerStateByProvider.isEmpty()) {
            modelHubItems.forEach { providerStateByProvider[downloadTaskKey(it)] = ModelDownloadState(item = it) }
        }
        actionBar?.hide()
        window.statusBarColor = Palette.background
        window.navigationBarColor = Palette.background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !TuiMaTheme.isDark
            isAppearanceLightNavigationBars = !TuiMaTheme.isDark
        }

        val pageRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ambientPageBackground()
        }
        rootScrollView = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        contentRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(pageGutterDp()), dp(14), dp(pageGutterDp()), dp(10))
        }
        bottomNavHost = FrameLayout(this).apply {
            setBackgroundColor(Palette.background)
            setPadding(0, dp(2), 0, dp(8))
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
        ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            rootScrollView.setPadding(0, systemBars.top, 0, 0)
            rootScrollView.clipToPadding = true
            contentRoot.setPadding(dp(pageGutterDp()), dp(12), dp(pageGutterDp()), dp(12))
            bottomNavHost.setPadding(0, dp(2), 0, max(dp(8), systemBars.bottom + dp(4)))
            insets
        }
        setContentView(pageRoot)
        syncBenchmarkReadiness(render = false)
        renderCurrentTab()
        refreshRecommendationSnapshot()
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressPollRunnable)
        progressHandler.removeCallbacks(modelScopeSearchRunnable)
        progressHandler.removeCallbacks(omniStatusPollRunnable)
        providerStateByProvider.values.forEach { it.cancelRequested = true }
        activeDownloadThreads.values.forEach { it.interrupt() }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (!modelLoadReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                modelLoadStatusReceiver,
                IntentFilter(ModelLoadStatusContract.ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            modelLoadReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (modelLoadReceiverRegistered) {
            unregisterReceiver(modelLoadStatusReceiver)
            modelLoadReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CURRENT_TAB, currentTab.name)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        syncBenchmarkReadiness(render = false)
        refreshRuntimeModelState()
        refreshRecommendationSnapshot()
        if (currentTab == AppTab.OMNI) refreshOmniLifecycleStatus()
    }

    private fun renderCurrentTab(resetScroll: Boolean = false) {
        if (!::contentRoot.isInitialized) return
        val targetScrollY = if (resetScroll) 0 else rootScrollView.scrollY
        requiredModelDownloadContainer = null
        contentRoot.removeAllViews()
        when (currentTab) {
            AppTab.HOME -> renderHomeTab(contentRoot)
            AppTab.MODELS -> renderModelsTab(contentRoot)
            AppTab.GALLERY -> renderGalleryTab(contentRoot)
            AppTab.VISION_MODELS -> renderVisionModelsTab(contentRoot)
            AppTab.G2D_LAB -> renderG2dLabTab(contentRoot)
            AppTab.VISION -> renderVisionTab(contentRoot)
            AppTab.OMNI -> renderOmniTab(contentRoot)
            AppTab.TEST -> renderTestTab(contentRoot)
            AppTab.RESULTS -> renderResultsTab(contentRoot)
            AppTab.API -> renderApiTab(contentRoot)
            AppTab.SETTINGS -> renderSettingsTab(contentRoot)
        }
        if (::bottomNavHost.isInitialized) {
            bottomNavHost.removeAllViews()
            bottomNavHost.addView(buildBottomNavigation())
        }
        rootScrollView.post {
            rootScrollView.scrollTo(0, targetScrollY)
            if (resetScroll) {
                contentRoot.animate().cancel()
                contentRoot.alpha = 0f
                contentRoot.translationY = dp(8).toFloat()
                contentRoot.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .start()
            }
        }
    }

    private fun setTab(tab: AppTab) {
        if (currentTab == tab) return
        currentTab = tab
        // Reset synchronously so an immediate async refresh cannot capture the
        // previous tab's scroll offset and restore it over the new screen.
        rootScrollView.scrollTo(0, 0)
        renderCurrentTab(resetScroll = true)
        if (tab == AppTab.HOME || tab == AppTab.MODELS) {
            refreshRecommendationSnapshot()
        }
        if (tab == AppTab.OMNI) refreshOmniLifecycleStatus()
    }

    private fun renderHomeTab(content: LinearLayout) {
        content.addView(buildHeader())
        content.addView(space(12))
        content.addView(buildHomeDeviceOverview())
        content.addView(space(12))
        content.addView(buildHomePrimaryCard())
        content.addView(space(12))
        content.addView(buildLocalPrivacyRow())
    }

    private fun renderModelsTab(content: LinearLayout) {
        content.addView(buildCompactHeader("模型", "本机模型库与端侧运行状态", "cube"))
        content.addView(space(12))
        content.addView(buildStorageCard())
        content.addView(space(18))
        content.addView(sectionTitle("适配本机", "优先展示内存压力更低的 6 个模型"))
        content.addView(space(10))
        content.addView(buildFeaturedModelScopeCard())
        content.addView(space(18))
        content.addView(sectionTitle("在线搜索", "从 ModelScope 查找更多 GGUF"))
        content.addView(space(10))
        content.addView(buildModelScopeCatalogCard())
        content.addView(space(18))
        content.addView(sectionTitle("运行建议", "按设备能力和历史速度排序"))
        content.addView(space(10))
        content.addView(buildRecommendationCard())
    }

    private fun renderTestTab(content: LinearLayout) {
        content.addView(buildCompactHeader("跑分", "本机 AI 性能测试", "play"))
        content.addView(space(14))
        content.addView(buildTestChatCard())
        content.addView(space(12))
        content.addView(buildBenchmarkRequirementsCard())
    }

    private fun renderResultsTab(content: LinearLayout) {
        content.addView(buildCompactHeader("结果", "双层分数与性能解释", "gauge"))
        content.addView(space(18))
        content.addView(buildLatestBenchmarkResultCard())
        content.addView(space(18))
        content.addView(sectionTitle("历史记录", "最近 10 次 v2 测试"))
        content.addView(buildBenchmarkHistoryCard())
    }

    private fun renderVisionTab(content: LinearLayout) {
        content.addView(buildCompactHeader("视觉实验室", "OCR 与轻量视觉探针", "image"))
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

    private fun renderOmniTab(content: LinearLayout) {
        val screen = OmniLifecycleScreen(this)
        screen.bind(
            OmniLifecyclePresenter.present(omniLifecycleSnapshot),
            OmniLifecycleCallbacks(onAction = ::handleOmniLifecycleAction),
        )
        content.addView(screen)
    }

    private fun renderGalleryTab(content: LinearLayout) {
        val screen = GallerySearchScreen(this)
        screen.bind(GallerySearchPresenter.present(gallerySearchState), gallerySearchActions())
        content.addView(screen)
    }

    private fun renderVisionModelsTab(content: LinearLayout) {
        val files = (scanVisionModelFiles() + scanVisionSidecarFiles()).distinctBy(File::getAbsolutePath)
        val screen = VisionModelImportScreen(this)
        screen.bind(
            VisionModelImportPresenter.present(VisionModelImportCatalog.fromFiles(files)),
            VisionModelImportCallbacks(
                onImport = { openVisionModelPicker() },
                onReplace = { openVisionModelPicker() },
                onRetry = { openVisionModelPicker() },
                onDiagnose = {
                    setTab(AppTab.VISION)
                    contentRoot.post { runVisionModelsProbe() }
                },
                onRemove = ::showVisionPackageRemoveDialog
            )
        )
        content.addView(screen)
    }

    private fun renderG2dLabTab(content: LinearLayout) {
        val screen = G2dValidationScreen(this)
        screen.bind(
            G2dValidationPresenter.present(g2dValidationInput),
            G2dValidationCallbacks(
                onStart = ::startNextOxfordPetsStage,
                onCancel = { activeG2dRunner?.cancel() },
                onExport = ::shareLatestOxfordPetsReport,
            )
        )
        content.addView(screen)
    }

    private fun startNextOxfordPetsStage() {
        val runner = OxfordPetsG2dRunner(this)
        val readiness = runner.readiness()
        if (!readiness.optBoolean("ready")) {
            Toast.makeText(this, "请先把 Oxford-Pets、CLIP 与 Qwen VLM 资源放入应用 G2D 目录", Toast.LENGTH_LONG).show()
            return
        }
        val reports = File(requireNotNull(getExternalFilesDir("g2d")), "reports")
        val scale = when {
            !File(reports, "oxford-pets-smoke.json").isFile -> OxfordPetsRunScale.SMOKE
            !File(reports, "oxford-pets-pilot.json").isFile -> OxfordPetsRunScale.PILOT
            else -> OxfordPetsRunScale.FULL
        }
        activeG2dRunner = runner
        g2dValidationInput = G2dValidationInput(
            state = G2dValidationRunState.RUNNING,
            datasetName = "Oxford-Pets（${scale.displayName}）",
            targetSampleCount = scale.expectedSamples,
            totalWorkItems = scale.expectedSamples,
            preparationMessage = "真实端侧推理运行中；进度会按 CLIP 与 VLM 阶段更新。",
        )
        renderCurrentTab()
        Thread({
            runCatching {
                runner.run(scale) { progress ->
                    runOnUiThread {
                        g2dValidationInput = g2dValidationInput.copy(
                            completedWorkItems = progress.completed.coerceAtMost(progress.total),
                            totalWorkItems = progress.total,
                            preparationMessage = "${progress.stage}: ${progress.sampleId.orEmpty()}",
                        )
                        if (currentTab == AppTab.G2D_LAB) renderCurrentTab()
                    }
                }
            }.onSuccess { result ->
                runOnUiThread {
                    activeG2dRunner = null
                    g2dValidationInput = g2dValidationInput.copy(
                        state = G2dValidationRunState.COMPLETED,
                        completedWorkItems = scale.expectedSamples,
                        totalWorkItems = scale.expectedSamples,
                        measurements = g2dMeasurements(result.report),
                        preparationMessage = "${scale.displayName}真实端侧报告已保存。",
                    )
                    renderCurrentTab()
                }
            }.onFailure { error ->
                runOnUiThread {
                    activeG2dRunner = null
                    g2dValidationInput = g2dValidationInput.copy(
                        state = G2dValidationRunState.FAILED,
                        failureMessage = error.message ?: error.javaClass.simpleName,
                    )
                    renderCurrentTab()
                }
            }
        }, "oxford-pets-g2d-${scale.name.lowercase()}").start()
    }

    private fun g2dMeasurements(report: JSONObject): List<G2dValidationMeasurement> {
        val experiments = mapOf(
            "clip" to G2dValidationExperiment.CLIP_ONLY,
            "vlm" to G2dValidationExperiment.VLM_ONLY,
            "g2d_one" to G2dValidationExperiment.G2D_ONE_THETA,
            "g2d_two" to G2dValidationExperiment.G2D_TWO_THETA,
            "agentic" to G2dValidationExperiment.AGENTIC_G2D,
        )
        val methods = report.getJSONArray("methods")
        return (0 until methods.length()).mapNotNull { index ->
            val row = methods.getJSONObject(index)
            val experiment = experiments[row.getString("key")] ?: return@mapNotNull null
            val routes = row.getJSONObject("routes")
            val toolCounts = row.optJSONObject("tools")?.let { tools ->
                tools.keys().asSequence().mapNotNull { name ->
                    G2dBranchTool.fromWireName(name)?.let { it to tools.getInt(name) }
                }.toMap()
            }
            G2dValidationMeasurement(
                experiment = experiment,
                evaluatedSamples = row.getInt("samples"),
                correctSamples = row.getInt("correct"),
                routeCounts = if (experiment == G2dValidationExperiment.CLIP_ONLY ||
                    experiment == G2dValidationExperiment.VLM_ONLY) null else G2dValidationRouteCounts(
                    routeA = routes.optInt("A"),
                    routeB = routes.optInt("B"),
                    routeC = routes.optInt("C"),
                ),
                p50LatencyMs = row.getDouble("p50_latency_ms"),
                p95LatencyMs = row.getDouble("p95_latency_ms"),
                backend = "Android ONNX Runtime + llama.cpp/libmtmd",
                quantization = if (experiment == G2dValidationExperiment.CLIP_ONLY) "FP32" else "Q4_K_M + BF16 mmproj",
                agentToolCounts = toolCounts,
                agentFallbackCount = row.optInt("fallbacks").takeIf {
                    experiment == G2dValidationExperiment.AGENTIC_G2D
                },
                routerP50LatencyMs = row.optDouble("router_p50_ms").takeIf {
                    experiment == G2dValidationExperiment.AGENTIC_G2D
                },
            )
        }
    }

    private fun shareLatestOxfordPetsReport() {
        val reports = File(requireNotNull(getExternalFilesDir("g2d")), "reports")
        val file = listOf("full", "pilot", "smoke")
            .map { File(reports, "oxford-pets-$it.json") }
            .firstOrNull(File::isFile)
        if (file == null) {
            Toast.makeText(this, "尚无可导出的真实测量报告", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享 Oxford-Pets G2D 报告"))
    }

    private fun gallerySearchActions() = object : GallerySearchActions {
        override fun requestGalleryAccess() = showGalleryRuntimePending()
        override fun scanGrantedMedia() = showGalleryRuntimePending()
        override fun retryGalleryIndex() = showGalleryRuntimePending()
        override fun prepareSearchModels() = setTab(AppTab.VISION_MODELS)
        override fun searchLocalGallery(query: String, topK: Int) = showGalleryRuntimePending()
        override fun updateGalleryQuery(query: String) {
            gallerySearchState = GallerySearchStateMachine.reduce(gallerySearchState, GallerySearchEvent.QueryChanged(query))
        }
        override fun clearGallerySearch() {
            gallerySearchState = GallerySearchStateMachine.reduce(gallerySearchState, GallerySearchEvent.ClearSearch)
            renderCurrentTab()
        }
        override fun openGalleryResult(mediaId: String, contentUri: String) = Unit
    }

    private fun showGalleryRuntimePending() {
        Toast.makeText(this, "相册索引与 CLIP 双编码运行时尚待接入", Toast.LENGTH_SHORT).show()
    }

    private fun showVisionPackageRemoveDialog(packageId: String) {
        val allFiles = (scanVisionModelFiles() + scanVisionSidecarFiles()).distinctBy(File::getAbsolutePath)
        val artifactNames = VisionModelImportCatalog.fromFiles(allFiles)
            .firstOrNull { it.id == packageId }?.artifacts?.map { it.fileName }.orEmpty().toSet()
        if (artifactNames.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("移除模型包？")
            .setMessage("将从应用私有目录删除 ${artifactNames.size} 个文件。此操作不会影响系统相册。")
            .setNegativeButton("保留", null)
            .setPositiveButton("移除") { _, _ ->
                allFiles.filter { it.name in artifactNames }.forEach(File::delete)
                renderCurrentTab()
            }
            .show()
    }

    private fun renderApiTab(content: LinearLayout) {
        content.addView(buildCompactHeader("开发者接口", "本机 API 与诊断", "cloud"))
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
        content.addView(buildCompactHeader("我的", "隐私、本机数据与实验室", "person"))
        content.addView(space(12))
        content.addView(sectionTitle("我的", "隐私与本机数据"))
        content.addView(buildSettingsCard())
        content.addView(space(18))
        content.addView(sectionTitle("实验室", "高级功能"))
        content.addView(buildLabAccessCard())
    }

    private fun buildHeader(): View {
        val lifecycle = requiredBenchmarkModelLifecycle()
        val statusAccent = modelLifecycleAccent(lifecycle.tone)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(52)
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label("TuiMa", 22f, Palette.mint, Typeface.BOLD).apply { letterSpacing = -0.02f })
                    addView(space(2))
                    addView(label("端侧 AI 控制台", 11.5f, Palette.muted, Typeface.BOLD))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            runtimeChipText = label(lifecycle.statusLabel, 11f, statusAccent, Typeface.BOLD)
            addView(
                chip(runtimeChipText, tint(statusAccent, 0.12f), statusAccent),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginStart = dp(10) }
            )
            contentDescription = "TuiMa 端侧 AI 控制台，标准模型${lifecycle.statusLabel}"
        }
    }

    private fun buildHomeDeviceOverview(): View {
        val profile = probeDeviceProfile()
        val telemetry = runCatching { AndroidBenchmarkTelemetry(applicationContext).sample() }.getOrNull()
        val lifecycle = requiredBenchmarkModelLifecycle()
        val lifecycleAccent = modelLifecycleAccent(lifecycle.tone)
        val availableRam = if (profile.availableRamMb >= 1024L) {
            "${"%.1f".format(Locale.US, profile.availableRamMb / 1024.0)} GB"
        } else {
            "${profile.availableRamMb} MB"
        }
        val temperature = telemetry?.batteryTemperatureCelsius?.let {
            "${"%.1f".format(Locale.US, it)}°C"
        } ?: "检测中"

        return surfaceCard(Palette.mint, gradient = true) {
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        IconBadgeView(context, "chip", Palette.mint),
                        LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(11) }
                    )
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(label(profile.model, 15.5f, Palette.deepInk, Typeface.BOLD).apply { maxLines = 2 })
                            addView(space(3))
                            addView(label("${profile.abi} · ${profile.backend} · CPU", 11.5f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        chip(
                            label(lifecycle.statusLabel, 10.8f, lifecycleAccent, Typeface.BOLD),
                            tint(lifecycleAccent, 0.10f),
                            lifecycleAccent,
                        ),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(8) }
                    )
                }
            )
            addView(space(14))
            addView(thinDivider())
            addView(space(12))
            addView(
                LinearLayout(context).apply {
                    addView(instrumentMetric("可用内存", availableRam, Palette.blue), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(instrumentMetric("CPU 核心", "${profile.coreCount} 核", Palette.sky), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(instrumentMetric("当前电量", telemetry?.let { "${it.batteryPercent}%" } ?: "检测中", Palette.mint), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(instrumentMetric("设备温度", temperature, if ((telemetry?.batteryTemperatureCelsius ?: 0.0) >= 42.0) Palette.amber else Palette.mint), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            )
            addView(space(12))
            addView(label("标准模型 · ${lifecycle.supportingText}", 11.5f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
        }
    }

    private fun instrumentMetric(title: String, value: String, accent: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            addView(autoSizeSingleLineLabel(value, 13f, 9f, accent, Typeface.BOLD).apply { gravity = Gravity.CENTER })
            addView(space(3))
            addView(label(title, 9.7f, Palette.muted, Typeface.NORMAL).apply { gravity = Gravity.CENTER; maxLines = 1 })
            contentDescription = "$title，$value"
        }
    }

    private fun buildHomeIntro(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("TODAY ON DEVICE", 10.5f, Palette.mintDark, Typeface.BOLD).apply { letterSpacing = 0.12f })
            addView(space(7))
            addView(autoSizeSingleLineLabel("端侧 AI，一眼看清", 27f, 21f, Palette.deepInk, Typeface.BOLD))
            addView(space(7))
            addView(label("模型是否可用、设备是否适合、跑分结果如何，都以本机真实状态为准。", 13f, Palette.muted, Typeface.NORMAL).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            contentDescription = "端侧 AI 一眼看清。模型、设备和跑分均以本机真实状态为准"
        }
    }

    private fun buildCompactHeader(title: String, subtitle: String, icon: String): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(TuiMaTheme.compactHeaderHeightDp)
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(title, 23f, Palette.deepInk, Typeface.BOLD))
                    addView(space(4))
                    addView(label(subtitle, 12f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            tag = icon
            contentDescription = "$title，$subtitle"
        }
    }

    private fun buildHomePrimaryCard(): View {
        val state = benchmarkUiStateMachine.state
        val latest = latestScoredBenchmarkReport()
        val latestScore = latest?.optJSONObject("score")
        val modelFile = requiredBenchmarkModel()
        val lifecycle = requiredBenchmarkModelLifecycle()
        val accent = modelLifecycleAccent(lifecycle.tone)
        val headline = latestScore?.optInt("headline")
        val canonical = latestScore?.optInt("canonical")

        return surfaceCard(accent, gradient = true) {
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(label("标准跑分模型", 11f, Palette.muted, Typeface.BOLD))
                            addView(space(4))
                            addView(label("Qwen2.5 0.5B · Q4_K_M", 16.5f, Palette.deepInk, Typeface.BOLD).apply { maxLines = 2 })
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        chip(label(lifecycle.statusLabel, 10.8f, accent, Typeface.BOLD), tint(accent, 0.11f), accent),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(10) }
                    )
                }
            )
            addView(space(10))
            addView(label(lifecycle.supportingText, 12.5f, Palette.muted, Typeface.NORMAL).apply { maxLines = 3 })

            if (headline != null && canonical != null) {
                addView(space(16))
                addView(thinDivider())
                addView(space(14))
                addView(label("最近成绩", 10.5f, Palette.muted, Typeface.BOLD))
                addView(space(5))
                addView(
                    autoSizeSingleLineLabel(formatHeadlineScore(headline), 34f, 24f, Palette.blue, Typeface.BOLD),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
                addView(space(5))
                addView(label("TuiMa · 标准分 $canonical / 1000", 12f, Palette.mintDark, Typeface.BOLD).apply { maxLines = 2 })
            }

            addView(space(16))
            val actionText = when {
                state.isRunning -> "查看跑分进度"
                lifecycle.phase == ModelLifecyclePhase.LOADED -> if (latestScore != null) "重新跑分" else "开始标准跑分"
                lifecycle.phase == ModelLifecyclePhase.DOWNLOADED || lifecycle.phase == ModelLifecyclePhase.LOAD_FAILED -> lifecycle.actionLabel
                lifecycle.phase == ModelLifecyclePhase.LOADING -> "模型加载中"
                lifecycle.phase == ModelLifecyclePhase.DOWNLOADING -> "暂停下载"
                lifecycle.phase == ModelLifecyclePhase.PAUSED -> "继续下载"
                lifecycle.phase == ModelLifecyclePhase.DOWNLOAD_FAILED -> "重新下载"
                else -> "下载标准模型 · 469 MB"
            }
            addView(
                pillButton(actionText, Palette.mintDark, Palette.mint) {
                    when {
                        state.isRunning -> setTab(AppTab.TEST)
                        lifecycle.phase == ModelLifecyclePhase.LOADED -> {
                            selectedBenchmarkProfile = BenchmarkProfile.STANDARD
                            setTab(AppTab.TEST)
                        }
                        lifecycle.phase == ModelLifecyclePhase.DOWNLOADED || lifecycle.phase == ModelLifecyclePhase.LOAD_FAILED -> {
                            modelFile?.let(::ensureNotificationPermissionAndLoadModel)
                        }
                        lifecycle.phase == ModelLifecyclePhase.DOWNLOADING -> {
                            val item = modelHubItems.first { it.fileName == requiredBenchmarkModelName() }
                            pauseModelDownload(downloadTaskKey(item))
                        }
                        else -> downloadRequiredBenchmarkModel()
                    }
                }.apply {
                    contentDescription = actionText
                    isEnabled = lifecycle.actionEnabled || state.isRunning || lifecycle.phase == ModelLifecyclePhase.LOADED
                    alpha = if (isEnabled) 1f else 0.55f
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            )
        }
    }

    private fun buildHomeReadinessCard(): View {
        val model = requiredBenchmarkModel()
        val lifecycle = requiredBenchmarkModelLifecycle()
        val telemetry = runCatching { AndroidBenchmarkTelemetry(applicationContext).sample() }.getOrNull()
        val thermalReady = telemetry?.thermalStatus?.ordinal?.let { it <= ThermalStatus.LIGHT.ordinal } ?: true
        return surfaceCard(Palette.sky) {
            addView(label("本机准备度", 14f, Palette.deepInk, Typeface.BOLD))
            addView(space(10))
            addView(readinessRow("标准模型", lifecycle.statusLabel, model != null, modelLifecycleAccent(lifecycle.tone)))
            addView(thinDivider())
            addView(readinessRow("当前电量", telemetry?.let { "${it.batteryPercent}%" } ?: "检测中", (telemetry?.batteryPercent ?: 30) >= 30))
            addView(thinDivider())
            addView(readinessRow("设备温控", if (thermalReady) "适合跑分" else "建议冷却", thermalReady))
            addView(space(8))
            addView(label("开始跑分前还会再次校验模型完整性、存储和运行时。", 11.8f, Palette.muted, Typeface.NORMAL).apply { maxLines = 3 })
        }
    }

    private fun readinessMetric(title: String, value: String, ready: Boolean): View {
        val accent = if (ready) Palette.mintDark else Palette.amber
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(tint(accent, 0.08f), tint(accent, 0.22f), 7f)
            addView(label(if (ready) "✓" else "!", 16f, accent, Typeface.BOLD))
            addView(space(2))
            addView(label(value, 12.5f, accent, Typeface.BOLD).apply { maxLines = 1 })
            addView(space(2))
            addView(label(title, 10.2f, Palette.muted, Typeface.NORMAL))
            contentDescription = "$title，$value"
        }
    }

    private fun buildRequiredModelDownloadCard(): View {
        requiredModelDownloadContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        renderRequiredModelDownloadStatus()
        return surfaceCard(Palette.mint, gradient = true) {
            addView(requiredModelDownloadContainer)
        }
    }

    private fun renderRequiredModelDownloadStatus() {
        val container = requiredModelDownloadContainer ?: return
        val item = modelHubItems.firstOrNull { it.fileName == requiredBenchmarkModelName() } ?: return
        val taskKey = downloadTaskKey(item)
        val state = providerStateByProvider.getOrPut(taskKey) { ModelDownloadState(item) }
        val phase = when (state.status) {
            DownloadState.DOWNLOADING -> StandardModelDownloadPhase.DOWNLOADING
            DownloadState.PAUSED -> StandardModelDownloadPhase.PAUSED
            DownloadState.FAILED, DownloadState.CANCELLED -> StandardModelDownloadPhase.FAILED
            DownloadState.SUCCESS -> StandardModelDownloadPhase.COMPLETE
            DownloadState.IDLE -> StandardModelDownloadPhase.IDLE
        }
        val model = HomeScreenPresenter.standardModelDownload(
            phase = phase,
            bytesDownloaded = state.bytesDownloaded,
            totalBytes = state.totalBytes,
            startedAtMs = state.transferStartedAtMs,
            startedBytes = state.transferStartedBytes,
            nowMs = System.currentTimeMillis()
        )

        container.removeAllViews()
        container.addView(cardHeader(model.title, "Qwen2.5 0.5B · TuiMa 标准模型", "download", Palette.mint, "本机"))
        container.addView(space(12))
        container.addView(
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = model.progressPercent
                progressTintList = ColorStateList.valueOf(Palette.mintDark)
                progressBackgroundTintList = ColorStateList.valueOf(tint(Palette.muted, 0.16f))
                contentDescription = "标准模型下载进度 ${model.progressPercent}%"
                visibility = if (phase == StandardModelDownloadPhase.IDLE) View.GONE else View.VISIBLE
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
        )
        if (phase != StandardModelDownloadPhase.IDLE) container.addView(space(10))
        container.addView(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(model.progressLabel, 13f, Palette.ink, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(label(model.remainingLabel, 12f, Palette.muted, Typeface.NORMAL))
            }
        )
        if (!state.failureMessage.isNullOrBlank() && phase == StandardModelDownloadPhase.FAILED) {
            container.addView(space(6))
            container.addView(label(state.failureMessage.orEmpty(), 12f, Palette.blue, Typeface.NORMAL).apply { maxLines = 2 })
        }
        container.addView(space(12))
        container.addView(
            pillButton(model.actionLabel, Palette.mintDark, Palette.mint) {
                when (phase) {
                    StandardModelDownloadPhase.DOWNLOADING -> pauseModelDownload(taskKey)
                    StandardModelDownloadPhase.COMPLETE -> {
                        requiredBenchmarkModel()?.let(::ensureNotificationPermissionAndLoadModel)
                    }
                    else -> enqueueModelDownload(item)
                }
            }.apply {
                isEnabled = model.actionEnabled
                contentDescription = model.actionLabel
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TuiMaTheme.minimumTouchTargetDp))
        )
    }

    private fun readinessRow(title: String, value: String, ready: Boolean, accentOverride: Int? = null): View {
        val accent = accentOverride ?: if (ready) Palette.mintDark else Palette.blue
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, dp(7), 0, dp(7))
            addView(
                View(context).apply { background = rounded(accent, Color.TRANSPARENT, 4f) },
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) }
            )
            addView(label(title, 13.5f, Palette.ink, Typeface.BOLD).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(value, 12f, accent, Typeface.BOLD).apply { maxLines = 2; gravity = Gravity.END }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
            contentDescription = "$title，$value"
        }
    }

    private fun buildModelLifecycleBanner(lifecycle: ModelLifecycleUiModel, modelName: String): View {
        val accent = modelLifecycleAccent(lifecycle.tone)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(tint(accent, 0.075f), Color.TRANSPARENT, TuiMaTheme.cardRadiusDp)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        View(context).apply { background = rounded(accent, Color.TRANSPARENT, 4f) },
                        LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(9) }
                    )
                    addView(label(modelName, 12.8f, Palette.ink, Typeface.BOLD).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label(lifecycle.statusLabel, 11.5f, accent, Typeface.BOLD).apply { maxLines = 2; gravity = Gravity.END }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
                }
            )
            addView(space(6))
            addView(label(lifecycle.supportingText, 11.5f, Palette.muted, Typeface.NORMAL).apply { maxLines = 3 })
            contentDescription = "$modelName，${lifecycle.statusLabel}。${lifecycle.supportingText}"
        }
    }

    private fun buildHomeLatestResultCard(): View {
        val latest = latestScoredBenchmarkReport()
        val snapshot = latest?.let(ResultsScreenPresenter::parse)
        return surfaceCard(Palette.lavender) {
            if (latest == null || snapshot == null) {
                addView(cardHeader("还没有成绩", "完成一次测试后在这里查看五维表现", "gauge", Palette.lavender))
                addView(space(12))
                addView(chipButton("前往跑分", false) { setTab(AppTab.TEST) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            } else {
                val insight = ResultsScreenPresenter.insight(snapshot)
                addView(cardHeader("能力洞察 · ${insight.rating}", insight.summary, "gauge", Palette.lavender, "${snapshot.canonicalScore}/1000", Palette.mintDark))
                addView(space(12))
                addView(softInfoBlock(insight.recommendation, Palette.lavender, maxLines = 3))
                addView(space(12))
                addView(chipButton("查看五维结果  →", false) { setTab(AppTab.RESULTS) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            }
        }
    }

    private fun buildLocalPrivacyRow(): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            addView(IconBadgeView(context, "chip", Palette.mintDark), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) })
            addView(label("离线运行 · 数据仅留本机", 12.5f, Palette.muted, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("›", 22f, Palette.muted, Typeface.NORMAL))
            isClickable = true
            isFocusable = true
            background = ripple(rounded(Color.TRANSPARENT, Color.TRANSPARENT, 7f), Palette.mint)
            contentDescription = "离线运行，数据仅留本机。查看隐私与本机数据"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                setTab(AppTab.SETTINGS)
            }
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

    private fun notificationBubble(): View {
        return FrameLayout(this).apply {
            background = ripple(rounded(Palette.surface, Palette.stroke, 22f), Palette.blue)
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "查看跑分结果"
            setOnClickListener { setTab(AppTab.RESULTS) }
            addView(
                IconBadgeView(context, "gauge", Palette.deepInk),
                FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
            )
        }
    }

    private fun surfaceCard(accent: Int, gradient: Boolean = false, block: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (gradient) {
                roundedGradient(
                    intArrayOf(
                        Palette.surface,
                        mixColor(Palette.surface, accent, if (TuiMaTheme.isDark) 0.075f else 0.045f),
                    ),
                    TuiMaTheme.cardRadiusDp,
                )
            } else {
                rounded(Palette.surface, tint(Palette.stroke, 0.48f), TuiMaTheme.cardRadiusDp)
            }
            elevation = dp(1).toFloat()
            setPadding(dp(15), dp(16), dp(15), dp(15))
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
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(IconBadgeView(context, icon, accent), LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
                    addView(
                        label(title, 15.2f, tint(Palette.ink, 0.88f), Typeface.BOLD).apply { maxLines = 2 },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    if (badge != null) {
                        addView(
                            chip(label(badge, 10.8f, badgeAccent, Typeface.BOLD), tint(badgeAccent, 0.12f), badgeAccent),
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply { marginStart = dp(8) }
                        )
                    }
                }
            )
            if (caption.isNotBlank()) {
                addView(space(7))
                addView(label(caption, 12f, Palette.muted, Typeface.NORMAL).apply {
                    maxLines = 3
                    setLineSpacing(dp(2).toFloat(), 1f)
                })
            }
        }
    }

    private fun softInfoBlock(text: String, accent: Int, maxLines: Int = 2): TextView {
        return label(text, 12.4f, tint(Palette.ink, 0.68f), Typeface.NORMAL).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(tint(accent, 0.075f), Color.TRANSPARENT, 7f)
            this.maxLines = maxLines
        }
    }

    private fun buildStorageCard(): View {
        val freeMb = runCatching { externalModelDir().freeSpace / (1024 * 1024) }.getOrDefault(0L)
        val totalMb = runCatching { externalModelDir().totalSpace / (1024 * 1024) }.getOrDefault(0L)
        val usedMb = (totalMb - freeMb).coerceAtLeast(0L)
        val storagePercent = if (totalMb > 0L) {
            ((usedMb.toDouble() / totalMb.toDouble()) * 100).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        val modelBytes = modelDirs()
            .flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            .sumOf { it.length() }
        val storageLine = if (totalMb > 0) {
            "模型 ${formatBytes(modelBytes)} · 可用 ${freeMb / 1024} / ${totalMb / 1024} GB"
        } else {
            "模型 ${formatBytes(modelBytes)}"
        }
        val localModels = availableGgufModels()
        val activeName = localModels.firstOrNull { it.absolutePath == activeModelPath }?.nameWithoutExtension
        return surfaceCard(Palette.mint) {
            addView(cardHeader("本机模型库", storageLine, "chip", Palette.mint, "${localModels.size} 个"))
            addView(space(12))
            addView(
                ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = storagePercent
                    progressTintList = ColorStateList.valueOf(Palette.blue)
                    progressBackgroundTintList = ColorStateList.valueOf(tint(Palette.muted, 0.14f))
                    contentDescription = "设备存储已使用 $storagePercent%"
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6))
            )
            addView(space(10))
            addView(readinessRow("运行时", activeName?.let { "已加载 ${displayModelName(it)}" } ?: "暂无已加载模型", activeName != null))
            addView(thinDivider())
            addView(readinessRow("本地文件", if (localModels.isEmpty()) "未下载" else "已下载 ${localModels.size} 个", localModels.isNotEmpty()))
            addView(space(10))
            addView(
                chipButton("复制模型目录", false) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("TuiMa model directory", externalModelDir().absolutePath))
                    Toast.makeText(this@MainActivity, "模型目录已复制", Toast.LENGTH_SHORT).show()
                    updateStatus("模型目录已复制")
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
            .take(6)
        val best = featuredItems.firstOrNull()
        val headerText = best?.let {
            "${fitLabel(it, profile)} · 首推 ${it.parameterLabel} ${it.quantization}"
        } ?: "准备推荐"

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                label(
                    "${profile.coreCount} 核 · 可用内存 ${profile.availableRamMb}MB · $headerText",
                    11.8f,
                    Palette.mintDark,
                    Typeface.BOLD,
                ).apply {
                    setPadding(dp(2), 0, dp(2), 0)
                    maxLines = 2
                }
            )
            addView(space(10))
            featuredItems.forEachIndexed { index, entry ->
                addView(buildModelScopeResultRow(entry, compact = index >= 2))
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
        val modelHubItem = modelScopeItem(entry)
        val taskKey = downloadTaskKey(modelHubItem)
        val downloadState = providerStateByProvider[taskKey]
        val lifecycle = modelLifecycle(
            file = localFile.takeIf { downloaded },
            expectedFileName = entry.fileName,
            downloadState = downloadState,
        )
        val loadedInRuntime = lifecycle.phase == ModelLifecyclePhase.LOADED
        val profile = probeDeviceProfile()
        val estimatedMemoryMb = estimateMobileMemoryMb(entry)
        val fit = fitLabel(entry, profile)
        val reason = entry.recommendationReason.ifBlank {
            "$fit · 预计内存 ${estimatedMemoryMb}MB · ${recommendationReasonFor(entry, profile)}"
        }
        val accent = modelLifecycleAccent(lifecycle.tone)
        val actionAccent = when (lifecycle.phase) {
            ModelLifecyclePhase.NOT_DOWNLOADED,
            ModelLifecyclePhase.DOWNLOADING,
            ModelLifecyclePhase.PAUSED -> Palette.mintDark
            ModelLifecyclePhase.DOWNLOADED,
            ModelLifecyclePhase.LOADING -> Palette.blue
            ModelLifecyclePhase.LOADED -> Palette.mint
            ModelLifecyclePhase.DOWNLOAD_FAILED,
            ModelLifecyclePhase.LOAD_FAILED -> Palette.danger
        }
        val badge = lifecycle.statusLabel
        val primaryAction = lifecycle.actionLabel
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(
                rounded(
                    mixColor(Palette.surface, accent, if (loadedInRuntime) 0.11f else 0.035f),
                    if (loadedInRuntime) tint(accent, 0.42f) else tint(Palette.stroke, 0.50f),
                    TuiMaTheme.cardRadiusDp,
                ),
                accent,
            )
            setPadding(dp(12), dp(11), dp(12), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Toast.makeText(this@MainActivity, "${entry.repoId}\n${entry.fileName}\n$reason", Toast.LENGTH_LONG).show()
            }
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        IconBadgeView(context, "cube", accent),
                        LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) },
                    )
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(label(entry.displayTitle, 14f, Palette.ink, Typeface.BOLD).apply { maxLines = 2 })
                            addView(space(3))
                            addView(
                                label(
                                    "${entry.parameterLabel} · ${entry.quantization} · ${formatBytes(entry.sizeBytes)} · $fit",
                                    11.2f,
                                    Palette.muted,
                                    Typeface.NORMAL,
                                ).apply { maxLines = 2 }
                            )
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        chip(label(badge, 10.5f, accent, Typeface.BOLD), tint(accent, 0.10f), accent),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply { marginStart = dp(8) },
                    )
                }
            )
            if (!compact) {
                addView(space(8))
                addView(
                    label(
                        reason,
                        11.5f,
                        Palette.muted,
                        Typeface.NORMAL,
                    ).apply { maxLines = 2 }
                )
            }
            addView(space(9))
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        label(lifecycle.supportingText, 11.4f, accent, Typeface.BOLD).apply { maxLines = 2 },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) },
                    )
                    addView(
                        compactActionButton(primaryAction, actionAccent, lifecycle.actionEnabled) {
                            val currentFile = File(externalModelDir(), entry.fileName)
                            val currentState = providerStateByProvider[taskKey]
                            val currentDownloaded = currentFile.exists() && currentFile.length() > 1024 * 1024
                            val currentLifecycle = modelLifecycle(
                                file = currentFile.takeIf { currentDownloaded },
                                expectedFileName = entry.fileName,
                                downloadState = currentState,
                            )
                            when (currentLifecycle.phase) {
                                ModelLifecyclePhase.LOADED, ModelLifecyclePhase.LOADING -> Unit
                                ModelLifecyclePhase.DOWNLOADED, ModelLifecyclePhase.LOAD_FAILED -> ensureNotificationPermissionAndLoadModel(currentFile)
                                ModelLifecyclePhase.DOWNLOADING -> pauseModelDownload(taskKey)
                                else -> enqueueModelDownload(modelHubItem)
                            }
                        },
                        LinearLayout.LayoutParams(dp(92), dp(44)),
                    )
                }
            )
            contentDescription = "${entry.displayTitle}，${lifecycle.statusLabel}，$fit。点击查看详情"
        }
    }

    private fun featuredModelScopeCatalog(): List<ModelScopeCatalogEntry> {
        fun mb(value: Long) = value * 1024L * 1024L
        return listOf(
            ModelScopeCatalogEntry(
                repoId = "unsloth/gemma-3-270m-it-GGUF",
                displayTitle = "Gemma3 270M Instruct",
                fileName = "gemma-3-270m-it-Q4_K_M.gguf",
                filePath = "gemma-3-270m-it-Q4_K_M.gguf",
                sizeBytes = 253115424L,
                quantization = "Q4_K_M",
                parameterLabel = "270M",
                architecture = "gemma3",
                downloads = 0L,
                recommendationReason = "Gemma3 超轻文本入口，适合先验证加载和对话链路。",
                tier = "tiny"
            ),
            ModelScopeCatalogEntry(
                repoId = "unsloth/gemma-3-1b-it-GGUF",
                displayTitle = "Gemma3 1B Instruct",
                fileName = "gemma-3-1b-it-Q4_K_M.gguf",
                filePath = "gemma-3-1b-it-Q4_K_M.gguf",
                sizeBytes = 806058272L,
                quantization = "Q4_K_M",
                parameterLabel = "1B",
                architecture = "gemma3",
                downloads = 0L,
                recommendationReason = "Gemma3 手机质量基线，Q4 量化更稳。",
                tier = "phone"
            ),
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
                repoId = "unsloth/gemma-3-270m-it-GGUF",
                displayTitle = "Gemma3-270M-Instruct-GGUF",
                fileName = "gemma-3-270m-it-Q4_K_M.gguf",
                filePath = "gemma-3-270m-it-Q4_K_M.gguf",
                sizeBytes = 253115424L,
                quantization = "Q4_K_M",
                parameterLabel = "270M",
                architecture = "gemma3",
                downloads = 0L,
                recommendationReason = "Gemma3 超轻文本入口，适合先验证加载和对话链路。",
                tier = "tiny"
            ),
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
            repoId.contains("gemma", ignoreCase = true) -> "gemma3"
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

    private fun buildTestChatCard(): View {
        val state = benchmarkUiStateMachine.state
        val activeProfile = state.profile ?: selectedBenchmarkProfile
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                surfaceCard(Palette.mint) {
                    addView(label("测试模式", 13f, Palette.muted, Typeface.BOLD))
                    addView(space(10))
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            BenchmarkProfile.entries.forEachIndexed { index, profile ->
                                addView(
                                    benchmarkProfileOption(profile, activeProfile == profile, state.isRunning),
                                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                        if (index > 0) marginStart = dp(4)
                                        if (index < BenchmarkProfile.entries.lastIndex) marginEnd = dp(4)
                                    }
                                )
                            }
                        }
                    )
                    addView(space(10))
                    val modeDetail = when (activeProfile) {
                        BenchmarkProfile.QUICK -> "快速：先看大致性能，不参与榜单。"
                        BenchmarkProfile.STANDARD -> "标准：统一规格重复 3 次，可生成榜单成绩。"
                        BenchmarkProfile.STRESS -> "压力：持续运行，观察温度与性能衰减。"
                    }
                    addView(label(modeDetail, 11.8f, Palette.muted, Typeface.NORMAL).apply { maxLines = 3 })
                }
            )
            addView(space(12))
            addView(
                surfaceCard(Palette.lavender, gradient = true) {
                    addView(cardHeader("本机 AI 性能测试", "统一模型、统一提示词、评分算法固定", "play", Palette.lavender, "v2"))
                    addView(space(12))
                    addView(buildModelLifecycleBanner(requiredBenchmarkModelLifecycle(), "Qwen2.5 0.5B 标准模型"))
                    addView(space(14))
                    addView(buildBenchmarkStatePanel(state))
                    addView(space(14))
                    when {
                        state is BenchmarkUiState.NeedsModel -> addView(
                            pillButton("下载标准模型 · 469 MB", Palette.mintDark, Palette.mint) { downloadRequiredBenchmarkModel() },
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
                        )
                        state.isRunning -> {
                            addView(
                                softInfoBlock("测试正在运行，请保持应用在前台。", Palette.sky, maxLines = 2).apply {
                                    gravity = Gravity.CENTER
                                    contentDescription = "跑分进行中，请保持应用在前台"
                                },
                                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            )
                            if (state !is BenchmarkUiState.Cancelling) {
                                addView(space(8))
                                addView(chipButton("取消本次跑分", false) { confirmCancelBenchmark() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
                            }
                        }
                        state is BenchmarkUiState.Completed -> addView(
                            pillButton("查看本次结果", Palette.mintDark, Palette.mint) { setTab(AppTab.RESULTS) },
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
                        )
                        else -> {
                            val actionText = if (state is BenchmarkUiState.Blocked || state is BenchmarkUiState.Failed) "重新检测" else "开始${benchmarkProfileName(selectedBenchmarkProfile)}"
                            addView(
                                pillButton(actionText, Palette.mintDark, Palette.mint) { runBenchmark(selectedBenchmarkProfile) },
                                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
                            )
                        }
                    }
                }
            )
        }
    }

    private fun benchmarkProfileOption(profile: BenchmarkProfile, selected: Boolean, disabled: Boolean): View {
        val accent = if (selected) Palette.mintDark else Palette.muted
        val caption = when (profile) {
            BenchmarkProfile.QUICK -> "预览"
            BenchmarkProfile.STANDARD -> "3 次 · 可入榜"
            BenchmarkProfile.STRESS -> "持续测试"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(64)
            setPadding(dp(5), dp(10), dp(5), dp(10))
            background = ripple(
                rounded(
                    if (selected) Palette.mintPale else mixColor(Palette.surface, Palette.muted, 0.025f),
                    if (selected) tint(accent, 0.44f) else Color.TRANSPARENT,
                    7f,
                ),
                accent
            )
            isClickable = !disabled
            isFocusable = true
            isEnabled = !disabled
            alpha = if (disabled && !selected) 0.52f else 1f
            contentDescription = "${benchmarkProfileName(profile)}，$caption${if (selected) "，已选择" else ""}"
            setOnClickListener {
                selectedBenchmarkProfile = profile
                renderCurrentTab()
            }
            addView(autoSizeSingleLineLabel(benchmarkProfileName(profile).removeSuffix("模式"), 13.5f, 11f, accent, Typeface.BOLD))
            addView(space(4))
            addView(label(caption, 10.5f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2; gravity = Gravity.CENTER })
        }
    }

    private fun buildBenchmarkStatePanel(state: BenchmarkUiState): View {
        val screen = benchmarkScreenUi(state)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            addView(label(screen.title, 16f, Palette.ink, Typeface.BOLD))
            addView(space(5))
            addView(label(screen.message, 13f, Palette.muted, Typeface.NORMAL).apply {
                maxLines = 4
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            if (state !is BenchmarkUiState.NeedsModel && state !is BenchmarkUiState.Blocked && state !is BenchmarkUiState.Failed && state != BenchmarkUiState.Cancelled) {
                addView(space(14))
                addView(buildBenchmarkStepIndicator(state))
            }
            if (state.isRunning || state is BenchmarkUiState.Completed) {
                addView(space(12))
                addView(
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            FrameLayout(context).apply {
                                addView(TuiMaCircularProgressView(context).apply { progress = screen.progressPercent }, FrameLayout.LayoutParams(dp(96), dp(96)))
                                addView(label("${screen.progressPercent}%", 18f, Palette.deepInk, Typeface.BOLD).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(dp(96), dp(96)))
                            },
                            LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(14) }
                        )
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(label(screen.phaseLabel, 13.5f, Palette.ink, Typeface.BOLD))
                                addView(space(7))
                                addView(label("预计剩余 ${screen.remainingLabel}", 12f, Palette.muted, Typeface.NORMAL))
                                addView(space(5))
                                addView(label("请保持应用在前台", 11.5f, Palette.mintDark, Typeface.BOLD))
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        )
                    }
                )
                addView(space(12))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(liveMetricTile("电量", benchmarkLiveSnapshot.batteryPercent?.let { "$it%" } ?: "--"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
                        addView(liveMetricTile("温度", benchmarkLiveSnapshot.temperatureCelsius?.let { "${"%.1f".format(Locale.US, it)}°C" } ?: "--"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                        addView(liveMetricTile("实时速度", benchmarkLiveSnapshot.decodeTokensPerSecond?.let { "${"%.1f".format(Locale.US, it)} tok/s" } ?: "--"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
                    }
                )
            }
            if (state is BenchmarkUiState.Blocked) {
                addView(space(10))
                state.reasons.forEachIndexed { index, reason ->
                    addView(label("• ${preflightRecoveryLabel(reason)}", 12.5f, Palette.ink, Typeface.NORMAL))
                    if (index != state.reasons.lastIndex) addView(space(5))
                }
            }
            contentDescription = "${screen.title}。${screen.message}"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    }

    private fun buildBenchmarkStepIndicator(state: BenchmarkUiState): View {
        val activeStep = when (state) {
            is BenchmarkUiState.Checking -> 0
            is BenchmarkUiState.LoadingModel -> 1
            is BenchmarkUiState.WarmingUp -> 2
            is BenchmarkUiState.Measuring, is BenchmarkUiState.Cooling, is BenchmarkUiState.Cancelling -> 3
            is BenchmarkUiState.Completed -> 4
            else -> -1
        }
        val labels = listOf("检查", "模型", "预热", "计分", "结果")
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            labels.forEachIndexed { index, title ->
                val reached = activeStep >= index
                val current = activeStep == index
                val accent = if (reached || current) Palette.mintDark else Palette.muted
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        addView(
                            label(if (activeStep > index) "✓" else "${index + 1}", 11f, if (reached) Palette.background else Palette.muted, Typeface.BOLD).apply {
                                gravity = Gravity.CENTER
                                background = rounded(if (reached) Palette.mint else tint(Palette.muted, 0.10f), tint(accent, 0.28f), 14f)
                            },
                            LinearLayout.LayoutParams(dp(28), dp(28))
                        )
                        addView(space(5))
                        addView(label(title, 10f, accent, if (current) Typeface.BOLD else Typeface.NORMAL))
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            contentDescription = if (activeStep >= 0) "当前阶段 ${activeStep + 1}，${labels[activeStep]}" else "等待开始，共五个阶段"
        }
    }

    private fun liveMetricTile(title: String, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(64)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = rounded(tint(Palette.surface, 0.76f), tint(Palette.sky, 0.18f), 11f)
            addView(label(value, 11.5f, Palette.ink, Typeface.BOLD).apply { maxLines = 1 })
            addView(space(3))
            addView(label(title, 10f, Palette.muted, Typeface.NORMAL))
            contentDescription = "$title，$value"
        }
    }

    private fun benchmarkScreenUi(state: BenchmarkUiState) = BenchmarkScreenPresenter.present(
        state = state,
        live = benchmarkLiveSnapshot.copy(
            elapsedMs = if (benchmarkStartedAtMs > 0L) (System.currentTimeMillis() - benchmarkStartedAtMs).coerceAtLeast(0L) else 0L
        ),
        modelDisplayName = ::displayModelName
    )

    private fun benchmarkStateTitle(state: BenchmarkUiState): String = benchmarkScreenUi(state).title

    private fun benchmarkStateMessage(state: BenchmarkUiState): String = benchmarkScreenUi(state).message

    private fun buildBenchmarkRequirementsCard(): View {
        return surfaceCard(Palette.sky) {
            addView(label("开始前检查", 14f, Palette.deepInk, Typeface.BOLD))
            addView(space(10))
            addView(readinessRow("电量", "至少 30%", true))
            addView(thinDivider())
            addView(readinessRow("温控", "保持凉爽", true))
            addView(thinDivider())
            addView(readinessRow("运行", "应用保持前台", true))
            addView(space(8))
            addView(label("标准模式具备榜单资格；快速模式用于预览，压力模式观察持续性能。", 12f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
        }
    }

    private fun requirementMetric(title: String, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(tint(Palette.sky, 0.06f), tint(Palette.sky, 0.18f), 7f)
            addView(label(value, 12f, Palette.deepInk, Typeface.BOLD).apply { maxLines = 1 })
            addView(space(3))
            addView(label(title, 10f, Palette.muted, Typeface.NORMAL))
            contentDescription = "$title，$value"
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
            addView(space(8))
            addView(visionTaskRow("扩散", "MNN-Diffusion / SD1.5 资源包", "diffusion", Palette.sky))
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
            addView(routeRow("GET", "/v1/benchmark/latest", "最新 TuiMa v2 报告") {
                callLocalApi("/v1/benchmark/latest", "GET", null, onResult = { status, body, _ ->
                    routeStatusText?.text = if (status in 200..299) body.take(500) else "暂无 v2 跑分报告"
                })
            })
            addView(routeRow("GET", "/v1/recommendations", "按设备能力推荐") {
                setTab(AppTab.HOME)
            })
            addView(routeRow("GET", "/leaderboard/local", "读取本机跑分榜") { runLocalLeaderboardProbe() })
            addView(routeRow("GET", "/leaderboard/shared", "共享榜配置状态") { runSharedLeaderboardProbe() })
            addView(routeRow("POST", "/leaderboard/shared", "上传本机榜记录") { runSharedLeaderboardSync() })
            addView(routeRow("GET", "/vision/status", "视觉能力状态") { runVisionStatusProbe() })
            addView(routeRow("GET", "/vision/models", "已导入视觉模型") { runVisionModelsProbe() })
            addView(routeRow("POST", "/vision/diffusion", "扩散生成 readiness") { runVisionDiffusionProbe() })
        }
    }

    private fun buildSettingsCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.surface, Palette.stroke, 18f)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(16), dp(16), dp(14))
            addView(modelRow("本机处理", "跑分与模型推理默认在手机内完成", "私密", Palette.mint))
            addView(thinDivider())
            addView(modelRow("模型目录", "应用私有模型库，支持 GGUF 导入和下载", "文件", Palette.sky))
            addView(thinDivider())
            addView(modelRow("跑分记录", "最多保存 50 份 v2 报告", "本机", Palette.lavender))
            addView(thinDivider())
            addView(
                miniListCard(
                    title = "外观",
                    subtitle = "跟随系统、浅色或深色显示",
                    badge = selectedThemeMode.displayName,
                    icon = "image",
                    accent = Palette.blue,
                    onClick = ::cycleThemeMode
                )
            )
        }
    }

    private fun buildLabAccessCard(): View {
        return surfaceCard(Palette.sky) {
            val links = listOf(
                LabLink("本地多模态", "Omni 授权、预检与双 artifact 管理", "实验", "chip", Palette.lavender, AppTab.OMNI),
                LabLink("本地相册搜索", "CLIP 召回 + G2D 候选复核", "产品", "image", Palette.mint, AppTab.GALLERY),
                LabLink("G2D 端侧验证", "Oxford-Pets 五种策略实测", "论文", "chip", Palette.lavender, AppTab.G2D_LAB),
                LabLink("视觉模型管理", "YOLO、CLIP 与小型 VLM", "模型", "cube", Palette.sky, AppTab.VISION_MODELS),
                LabLink("视觉识别", "OCR 与轻量视觉探针", "实验", "image", Palette.lavender, AppTab.VISION),
                LabLink("开发者接口", "本机 API、服务与诊断路由", "高级", "cloud", Palette.sky, AppTab.API)
            )
            links.forEachIndexed { index, link ->
                addView(miniListCard(link.title, link.subtitle, link.badge, link.icon, link.accent) { setTab(link.tab) })
                if (index != links.lastIndex) addView(space(10))
            }
        }
    }

    private data class LabLink(
        val title: String,
        val subtitle: String,
        val badge: String,
        val icon: String,
        val accent: Int,
        val tab: AppTab
    )

    private fun buildLatestBenchmarkResultCard(): View {
        val reports = BenchmarkReportStore(applicationContext).toJson(limit = 50).optJSONArray("data") ?: JSONArray()
        val report = selectedScoredBenchmarkReport(reports)
        val snapshot = report?.let(ResultsScreenPresenter::parse)
        if (report == null || snapshot == null) {
            return surfaceCard(Palette.lavender, gradient = true) {
                addView(cardHeader("还没有有效成绩", "完成一次跑分后，这里会展示双层分数与五维详情", "gauge", Palette.lavender))
                addView(space(16))
                addView(pillButton("开始标准测试", Palette.mintDark, Palette.mint) {
                    selectedBenchmarkProfile = BenchmarkProfile.STANDARD
                    setTab(AppTab.TEST)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            }
        }
        val insight = ResultsScreenPresenter.insight(snapshot)
        val selectedBaseline = ResultsScreenPresenter.comparableByRunId(snapshot, reports, comparisonBaselineRunId)
        val previous = selectedBaseline ?: ResultsScreenPresenter.previousComparable(snapshot, reports)
        val comparison = ResultsScreenPresenter.compare(snapshot, previous)

        return surfaceCard(Palette.mint, gradient = true) {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(label("本次测试结果", 11f, Palette.muted, Typeface.BOLD).apply { letterSpacing = 0.04f })
                    addView(space(9))
                    addView(autoSizeSingleLineLabel(formatHeadlineScore(snapshot.headlineScore), 48f, 28f, Palette.blue, Typeface.BOLD).apply {
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(label("TuiMa", 16f, Palette.deepInk, Typeface.BOLD).apply { gravity = Gravity.CENTER })
                    addView(space(7))
                    addView(label("标准分 ${snapshot.canonicalScore} / 1000", 14f, Palette.mintDark, Typeface.BOLD).apply { gravity = Gravity.CENTER })
                    addView(space(10))
                    addView(
                        chip(
                            label(insight.rating, 15f, Palette.mintDark, Typeface.BOLD),
                            Palette.mintPale,
                            Palette.mint
                        ),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
                    )
                    addView(space(8))
                    addView(label(insight.modeHint, 10.8f, Palette.muted, Typeface.BOLD).apply {
                        gravity = Gravity.CENTER
                        maxLines = 2
                    })
                    addView(space(8))
                    addView(
                        chip(label(snapshot.executionLabel, 10.5f, Palette.blue, Typeface.BOLD).apply { maxLines = 2 }, Palette.blueWash, Palette.sky),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(space(18))
            addView(buildResultInsightCard(insight))
            addView(space(18))
            addView(label("五维表现", 14f, Palette.ink, Typeface.BOLD))
            addView(space(12))
            snapshot.dimensions.forEachIndexed { index, dimension ->
                val accent = listOf(Palette.mint, Palette.sky, Palette.lavender, Palette.blue, Palette.mintDark)[index]
                addView(scoreDimensionRow(dimension.label, dimension.value, dimension.maximum, accent))
                if (index != snapshot.dimensions.lastIndex) addView(space(10))
            }
            addView(space(18))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(resultMetricTile("生成速度", "${"%.2f".format(Locale.US, snapshot.decodeTokensPerSecond)} tok/s"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
                    addView(resultMetricTile("首字响应", "${snapshot.firstTokenMs} ms"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                    addView(resultMetricTile("峰值内存", "${snapshot.memoryPeakMb} MB"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
                }
            )
            addView(space(10))
            val temperatureText = snapshot.temperaturePeakCelsius?.let { "${"%.1f".format(Locale.US, it)}°C" } ?: "--"
            addView(label("电量变化 ${snapshot.batteryDeltaPercent}% · 最高温度 $temperatureText · ${formatReportDate(snapshot.createdAtMs)}", 12f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
            addView(space(16))
            addView(buildResultComparisonCard(comparison, customBaseline = selectedBaseline != null))
            addView(space(16))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(chipButton("分享成绩", false) { shareBenchmarkResult(report) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
                    addView(pillButton("再测一次", Palette.mintDark, Palette.mint) { setTab(AppTab.TEST) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
                }
            )
        }
    }

    private fun buildResultInsightCard(insight: ai.mobilecore.ui.ResultInsight): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            addView(label("能力解读", 14f, Palette.deepInk, Typeface.BOLD))
            addView(space(7))
            addView(label(insight.summary, 13.5f, Palette.ink, Typeface.NORMAL).apply { maxLines = 2 })
            addView(space(10))
            addView(readinessRow("强项", insight.strongest.joinToString("、"), true))
            addView(thinDivider())
            addView(readinessRow("主要瓶颈", insight.bottleneck, false))
            addView(space(10))
            addView(label(insight.recommendation, 12.5f, Palette.muted, Typeface.NORMAL).apply {
                maxLines = 3
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
    }

    private fun buildResultComparisonCard(comparison: ai.mobilecore.ui.ResultComparison?, customBaseline: Boolean): View {
        if (comparison == null) {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(tint(Palette.lavender, 0.06f), tint(Palette.lavender, 0.18f), 13f)
                setPadding(dp(13), dp(12), dp(13), dp(12))
                addView(label("与上次相比", 13.5f, Palette.ink, Typeface.BOLD))
                addView(space(4))
                addView(label("暂无相同设备、模型、规格与模式的上一次成绩。", 12f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
            }
        }
        val comparisonTitle = if (customBaseline) "与所选成绩相比" else "与上次相比"
        val headline = comparison.canonicalPercentDelta?.let { "$comparisonTitle ${formatSignedPercent(it)}" }
            ?: "$comparisonTitle ${formatSignedInt(comparison.canonicalDelta)} 分"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(tint(Palette.lavender, 0.07f), tint(Palette.lavender, 0.20f), 13f)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            addView(label(headline, 14f, Palette.deepInk, Typeface.BOLD))
            addView(space(9))
            addView(comparisonRow(
                "标准分",
                comparison.current.canonicalScore.toString(),
                comparison.previous.canonicalScore.toString(),
                formatSignedInt(comparison.canonicalDelta),
                comparisonDeltaColor(comparison.canonicalDelta.toDouble(), lowerIsBetter = false)
            ))
            addView(space(6))
            addView(comparisonRow(
                "生成速度",
                "${"%.2f".format(Locale.US, comparison.current.decodeTokensPerSecond)}",
                "${"%.2f".format(Locale.US, comparison.previous.decodeTokensPerSecond)}",
                comparison.speedPercentDelta?.let(::formatSignedPercent) ?: "--",
                comparison.speedPercentDelta?.let { comparisonDeltaColor(it, lowerIsBetter = false) } ?: Palette.muted
            ))
            addView(space(6))
            addView(comparisonRow(
                "首字响应",
                "${comparison.current.firstTokenMs} ms",
                "${comparison.previous.firstTokenMs} ms",
                comparison.firstTokenPercentDelta?.let(::formatSignedPercent) ?: "--",
                comparison.firstTokenPercentDelta?.let { comparisonDeltaColor(it, lowerIsBetter = true) } ?: Palette.muted
            ))
            addView(space(6))
            addView(comparisonRow(
                "峰值内存",
                "${comparison.current.memoryPeakMb} MB",
                "${comparison.previous.memoryPeakMb} MB",
                "${if (comparison.memoryDeltaMb >= 0) "+" else ""}${comparison.memoryDeltaMb} MB",
                comparisonDeltaColor(comparison.memoryDeltaMb.toDouble(), lowerIsBetter = true)
            ))
            addView(space(6))
            addView(comparisonRow(
                "最高温度",
                comparison.current.temperaturePeakCelsius?.let { "${"%.1f".format(Locale.US, it)}°C" } ?: "--",
                comparison.previous.temperaturePeakCelsius?.let { "${"%.1f".format(Locale.US, it)}°C" } ?: "--",
                comparison.temperatureDeltaCelsius?.let { "${if (it >= 0) "+" else ""}${"%.1f".format(Locale.US, it)}°C" } ?: "--",
                comparison.temperatureDeltaCelsius?.let { comparisonDeltaColor(it, lowerIsBetter = true) } ?: Palette.muted
            ))
            addView(space(7))
            addView(label("速度单位 tok/s；仅比较相同设备、模型、后端、规格与测试模式。", 10.8f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
        }
    }

    private fun comparisonHeaderRow(): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("指标", 10.5f, Palette.muted, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f))
            addView(label("本次", 10.5f, Palette.muted, Typeface.BOLD).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("上次", 10.5f, Palette.muted, Typeface.BOLD).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("变化", 10.5f, Palette.muted, Typeface.BOLD).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun comparisonRow(labelText: String, current: String, previous: String, delta: String, deltaColor: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(tint(Palette.surface, 0.32f), Color.TRANSPARENT, TuiMaTheme.cardRadiusDp)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            addView(label(labelText, 11.5f, Palette.ink, Typeface.BOLD))
            addView(space(7))
            addView(
                LinearLayout(context).apply {
                    addView(comparisonValueCell("本次", current, Palette.ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
                    addView(comparisonValueCell("上次", previous, Palette.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                    addView(comparisonValueCell("变化", delta, deltaColor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
                }
            )
        }
    }

    private fun comparisonValueCell(caption: String, value: String, color: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(autoSizeSingleLineLabel(value, 11f, 8f, color, Typeface.BOLD).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(space(3))
            addView(label(caption, 9.5f, Palette.muted, Typeface.NORMAL).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun comparisonDeltaColor(value: Double, lowerIsBetter: Boolean): Int {
        if (value == 0.0) return Palette.muted
        val improved = if (lowerIsBetter) value < 0.0 else value > 0.0
        return if (improved) Palette.mintDark else Palette.blue
    }

    private fun formatSignedInt(value: Int): String = "${if (value >= 0) "+" else ""}$value"

    private fun formatSignedPercent(value: Double): String = "${if (value >= 0) "+" else ""}${"%.1f".format(Locale.US, value)}%"

    private fun scoreDimensionRow(title: String, value: Int, maximum: Int, accent: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(context).apply {
                    addView(label(title, 13.5f, Palette.ink, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label("$value / $maximum", 13f, accent, Typeface.BOLD))
                }
            )
            addView(space(6))
            addView(
                ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = maximum
                    progress = value.coerceIn(0, maximum)
                    progressTintList = ColorStateList.valueOf(accent)
                    progressBackgroundTintList = ColorStateList.valueOf(tint(Palette.muted, 0.16f))
                    contentDescription = "$title $value 分，满分 $maximum 分"
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
            )
        }
    }

    private fun resultMetricTile(title: String, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(72)
            setPadding(dp(5), dp(9), dp(5), dp(9))
            background = rounded(tint(Palette.sky, 0.08f), tint(Palette.sky, 0.20f), 7f)
            addView(autoSizeSingleLineLabel(value, 12.5f, 9f, Palette.ink, Typeface.BOLD).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(space(3))
            addView(label(title, 10.5f, Palette.muted, Typeface.NORMAL))
            contentDescription = "$title，$value"
        }
    }

    private fun buildBenchmarkHistoryCard(): View {
        val data = BenchmarkReportStore(applicationContext).toJson(limit = 10).optJSONArray("data") ?: JSONArray()
        return surfaceCard(Palette.sky) {
            if (data.length() == 0) {
                addView(label("暂无历史记录。完成跑分后，最近 10 次结果会保存在本机。", 13f, Palette.muted, Typeface.NORMAL).apply {
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    maxLines = 3
                })
            } else {
                addView(buildHistoryComparisonSelector(data))
                addView(space(12))
                for (index in 0 until data.length()) {
                    val report = data.optJSONObject(index) ?: continue
                    addView(buildBenchmarkHistoryRow(report))
                    if (index < data.length() - 1) addView(space(8))
                }
            }
        }
    }

    private fun buildHistoryComparisonSelector(data: JSONArray): View {
        val allReports = BenchmarkReportStore(applicationContext).toJson(limit = 50).optJSONArray("data") ?: data
        val current = selectedScoredBenchmarkReport(allReports)?.let(ResultsScreenPresenter::parse)
        val baseline = current?.let { ResultsScreenPresenter.comparableByRunId(it, allReports, comparisonBaselineRunId) }
        val status = when {
            current == null -> "先完成一次有效跑分"
            selectingComparisonBaseline -> "请在下方点选另一条同规格成绩"
            baseline != null -> "已选 ${formatReportDate(baseline.createdAtMs)} · 标准分 ${baseline.canonicalScore}"
            else -> "默认自动对比上一次同规格成绩"
        }
        val action = when {
            selectingComparisonBaseline -> "取消选择"
            baseline != null -> "更换基准"
            else -> "选择对比基准"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(tint(Palette.blue, 0.07f), tint(Palette.blue, 0.20f), 13f)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            addView(label("两次成绩对比", 13.5f, Palette.ink, Typeface.BOLD))
            addView(space(4))
            addView(label(status, 11.8f, if (selectingComparisonBaseline) Palette.mintDark else Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
            addView(space(9))
            addView(
                chipButton(action, selectingComparisonBaseline) {
                    selectingComparisonBaseline = !selectingComparisonBaseline
                    renderCurrentTab()
                }.apply { isEnabled = current != null },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            )
        }
    }

    private fun latestScoredBenchmarkReport(): JSONObject? {
        val data = BenchmarkReportStore(applicationContext).toJson(limit = 50).optJSONArray("data") ?: return null
        return selectedScoredBenchmarkReport(data)
    }

    private fun selectedScoredBenchmarkReport(data: JSONArray): JSONObject? {
        if (!selectedResultRunId.isNullOrBlank()) {
            for (index in 0 until data.length()) {
                val report = data.optJSONObject(index) ?: continue
                if (report.optString("run_id") == selectedResultRunId && report.optBoolean("valid", false) && report.optJSONObject("score") != null) {
                    return report
                }
            }
        }
        for (index in 0 until data.length()) {
            val report = data.optJSONObject(index) ?: continue
            if (report.optBoolean("valid", false) && report.optJSONObject("score") != null) return report
        }
        return null
    }

    private fun buildBenchmarkHistoryRow(report: JSONObject): View {
        val score = report.optJSONObject("score")
        val spec = report.optJSONObject("spec") ?: JSONObject()
        val summary = report.optJSONObject("summary") ?: JSONObject()
        val valid = report.optBoolean("valid", false) && score != null
        val title = if (valid) "${formatHeadlineScore(score?.optInt("headline") ?: 0)} TuiMa" else "未生成成绩"
        val detail = if (valid) {
            val backend = ResultsScreenPresenter.parse(report)?.backendLabel ?: "CPU"
            "标准分 ${score?.optInt("canonical")} / 1000 · ${profileDisplayName(spec.optString("profile"))} · $backend"
        } else {
            "${report.optString("failure_kind", "测试未完成")} · 已完成 ${summary.optInt("completed_runs")}/${summary.optInt("measured_runs")}"
        }
        val accent = if (valid) Palette.mint else Palette.lavender
        val selected = report.optString("run_id") == selectedResultRunId
        val comparisonBaseline = report.optString("run_id") == comparisonBaselineRunId
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            background = if (valid) {
                val active = selected || comparisonBaseline
                ripple(rounded(tint(accent, if (active) 0.16f else 0.07f), tint(accent, if (active) 0.42f else 0.18f), 13f), accent)
            } else {
                rounded(tint(accent, 0.07f), tint(accent, 0.18f), 13f)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = valid
            isFocusable = valid
            if (valid) {
                setOnClickListener {
                    if (selectingComparisonBaseline) {
                        selectComparisonBaseline(report)
                    } else {
                        selectedResultRunId = report.optString("run_id")
                        comparisonBaselineRunId = null
                        selectingComparisonBaseline = false
                        renderCurrentTab()
                    }
                }
            }
            addView(IconBadgeView(context, if (valid) "gauge" else "stop", accent).apply { contentDescription = null }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(title, 14f, Palette.ink, Typeface.BOLD))
                    addView(space(3))
                    addView(label(detail, 11.5f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    addView(label(formatReportDate(report.optLong("created_at_ms")), 10.5f, Palette.muted, Typeface.NORMAL))
                    if (comparisonBaseline) {
                        addView(space(4))
                        addView(label("对比基准", 10f, Palette.mintDark, Typeface.BOLD))
                    }
                }
            )
            contentDescription = "$title，$detail，${formatReportDate(report.optLong("created_at_ms"))}${when {
                comparisonBaseline -> "，当前对比基准"
                selected -> "，当前查看"
                selectingComparisonBaseline && valid -> "，点击设为对比基准"
                valid -> "，点击查看详情"
                else -> ""
            }}"
        }
    }

    private fun selectComparisonBaseline(report: JSONObject) {
        val allReports = BenchmarkReportStore(applicationContext).toJson(limit = 50).optJSONArray("data") ?: JSONArray()
        val current = selectedScoredBenchmarkReport(allReports)?.let(ResultsScreenPresenter::parse)
        val candidate = ResultsScreenPresenter.parse(report)
        when {
            current == null || candidate == null -> Toast.makeText(this, "缺少可比较的有效成绩", Toast.LENGTH_SHORT).show()
            candidate.runId == current.runId -> Toast.makeText(this, "请选择另一条成绩", Toast.LENGTH_SHORT).show()
            candidate.comparisonKey != current.comparisonKey -> Toast.makeText(this, "只能比较相同设备、模型、后端、规格和模式", Toast.LENGTH_LONG).show()
            else -> {
                comparisonBaselineRunId = candidate.runId
                selectingComparisonBaseline = false
                renderCurrentTab()
            }
        }
    }

    private fun formatReportDate(createdAtMs: Long): String {
        if (createdAtMs <= 0L) return "时间未知"
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(createdAtMs))
    }

    private fun formatHeadlineScore(value: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(value)

    private fun profileDisplayName(apiName: String): String = when (apiName) {
        BenchmarkProfile.STANDARD.apiName -> "标准"
        BenchmarkProfile.STRESS.apiName -> "压力"
        else -> "快速"
    }

    private fun shareBenchmarkResult(report: JSONObject) {
        val snapshot = ResultsScreenPresenter.parse(report) ?: return
        val insight = ResultsScreenPresenter.insight(snapshot)
        runCatching {
            val file = BenchmarkShareCardRenderer.render(applicationContext, snapshot, insight)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val shareText = "我的手机跑出了 ${formatHeadlineScore(snapshot.headlineScore)} TuiMa，标准分 ${snapshot.canonicalScore} / 1000。"
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        clipData = ClipData.newUri(contentResolver, "TuiMa 成绩卡", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "分享 TuiMa 成绩卡"
                )
            )
        }.onFailure {
            Toast.makeText(this, "成绩卡生成失败，请稍后重试", Toast.LENGTH_SHORT).show()
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
        val statusText = label("未下载", 12f, Palette.muted, Typeface.BOLD)
        val messageText = label("模型文件尚未保存在本机", 12f, Palette.muted, Typeface.NORMAL)
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

    private fun readThemeMode(): TuiMaThemeMode {
        return TuiMaThemeMode.fromPreference(getPreferences(MODE_PRIVATE).getString(PREF_UI_THEME_MODE, null))
    }

    private fun cycleThemeMode() {
        selectedThemeMode = selectedThemeMode.next()
        getPreferences(MODE_PRIVATE)
            .edit()
            .putString(PREF_UI_THEME_MODE, selectedThemeMode.preferenceValue)
            .apply()
        TuiMaTheme.configure(selectedThemeMode, isSystemDarkTheme())
        recreate()
    }

    private fun isSystemDarkTheme(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
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
                    addView(label(title, 12.2f, Palette.ink, Typeface.BOLD).apply { maxLines = 2 })
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

    private fun actionRow(left: View, right: View): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) })
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
            minimumHeight = dp(92)
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
                val lifecycle = modelLifecycle(model, model.name, null)
                addView(modelRow(model.nameWithoutExtension, "${formatBytes(model.length())} · ${lifecycle.supportingText}", lifecycle.statusLabel, modelLifecycleAccent(lifecycle.tone)))
            } else {
                addView(modelRow("暂无本地模型", "从模型页下载，或从文件导入 GGUF", "未下载", Palette.muted))
            }
            addView(space(8))
            if (model?.name != requiredBenchmarkModelName()) {
                val standardLifecycle = requiredBenchmarkModelLifecycle()
                addView(modelRow("Qwen2.5 0.5B", standardLifecycle.supportingText, standardLifecycle.statusLabel, modelLifecycleAccent(standardLifecycle.tone)))
            }
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
        val recommendations = json.optJSONArray("recommendations") ?: JSONArray()

        runOnUiThread {
            if (!::recommendationContainer.isInitialized) return@runOnUiThread

            recommendationContainer.removeAllViews()
            if (recommendations.length() == 0) {
                renderRecommendationPlaceholder("已连接服务，但未检测到 GGUF。可先导入模型。")
                return@runOnUiThread
            }

            for (i in 0 until recommendations.length()) {
                val recommendation = recommendations.optJSONObject(i) ?: continue
                val modelId = recommendation.optString("model_id", "unknown")
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
            syncBenchmarkReadiness()
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
        state.transferStartedAtMs = System.currentTimeMillis()
        state.transferStartedBytes = resumeBytes
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
                syncBenchmarkReadiness()
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
                                if (modelId.isNotBlank() && modelId != "unknown") {
                                    loadRecommendedModel(modelId)
                                } else {
                                    Toast.makeText(this@MainActivity, "模型标识无效，无法加载", Toast.LENGTH_SHORT).show()
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
        if (state.item.fileName == requiredBenchmarkModelName()) {
            renderRequiredModelDownloadStatus()
        }
        val titleView = providerTitleByProvider[taskKey]
        val statusView = providerStatusByProvider[taskKey] ?: return
        val messageView = providerMessageByProvider[taskKey] ?: return
        val progressView = providerProgressByProvider[taskKey] ?: return
        val cancelView = providerCancelByProvider[taskKey]
        val tile = providerTileByProvider[taskKey]
        val bytesTotalText = if (state.totalBytes > 0) formatBytes(state.totalBytes) else "未知"
        val percentText = if (state.totalBytes > 0L) "${state.percent}%" else "未知"
        titleView?.text = "${state.item.provider} · ${state.item.shortName}"
        val localFile = availableGgufModels().firstOrNull { it.name.equals(state.item.fileName, ignoreCase = true) }
        val lifecycle = modelLifecycle(localFile, state.item.fileName, state)
        val accent = modelLifecycleAccent(lifecycle.tone)
        statusView.text = lifecycle.statusLabel
        statusView.setTextColor(accent)
        messageView.text = when {
            lifecycle.phase == ModelLifecyclePhase.LOAD_FAILED && !modelLoadFailureMessage.isNullOrBlank() -> modelLoadFailureMessage
            !state.failureMessage.isNullOrBlank() && lifecycle.phase in setOf(ModelLifecyclePhase.DOWNLOAD_FAILED, ModelLifecyclePhase.PAUSED) -> state.failureMessage
            else -> lifecycle.supportingText
        }
        cancelView?.text = lifecycle.actionLabel
        cancelView?.visibility = if (lifecycle.actionEnabled) View.VISIBLE else View.GONE
        tile?.alpha = if (lifecycle.phase == ModelLifecyclePhase.DOWNLOADING) 0.6f else 1f
        progressView.text = "进度 ${formatBytes(state.bytesDownloaded)} / $bytesTotalText · $percentText"
        progressView.visibility = if (lifecycle.phase in setOf(
                ModelLifecyclePhase.DOWNLOADING,
                ModelLifecyclePhase.PAUSED,
                ModelLifecyclePhase.DOWNLOAD_FAILED,
            )) View.VISIBLE else View.GONE
    }

    private fun handleDownloadControl(taskKey: String) {
        val state = providerStateByProvider[taskKey] ?: return
        val localFile = availableGgufModels().firstOrNull { it.name.equals(state.item.fileName, ignoreCase = true) }
        when (modelLifecycle(localFile, state.item.fileName, state).phase) {
            ModelLifecyclePhase.LOADED, ModelLifecyclePhase.LOADING -> Unit
            ModelLifecyclePhase.DOWNLOADED, ModelLifecyclePhase.LOAD_FAILED -> localFile?.let(::ensureNotificationPermissionAndLoadModel)
            ModelLifecyclePhase.DOWNLOADING -> pauseModelDownload(taskKey)
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

    private fun loadRecommendedModel(modelId: String) {
        Thread {
            try {
                val requestBody = JSONObject().apply {
                    put("model_id", modelId)
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
                        val modelName = response.optString("model", modelId)
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
        var transferStartedAtMs: Long = 0L,
        var transferStartedBytes: Long = 0L,
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
        GALLERY,
        VISION_MODELS,
        G2D_LAB,
        VISION,
        OMNI,
        TEST,
        RESULTS,
        API,
        SETTINGS
    }

    private fun modelRow(name: String, subtitle: String, badge: String, accent: Int): View {
        return miniListCard(name, subtitle, badge, "cube", accent)
    }

    private fun buildBottomNavigation(): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = rounded(Palette.surface, Color.TRANSPARENT, 0f)
            elevation = dp(8).toFloat()
            setPadding(dp(7), dp(3), dp(7), dp(3))
            addView(navItem("首页", "home", AppTab.HOME), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(navItem("跑分", "play", AppTab.TEST), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(navItem("结果", "gauge", AppTab.RESULTS), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(navItem("模型", "cube", AppTab.MODELS), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(navItem("我的", "person", AppTab.SETTINGS), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun navItem(title: String, icon: String, tab: AppTab): View {
        val selected = currentTab == tab || (tab == AppTab.SETTINGS && currentTab in setOf(
            AppTab.GALLERY, AppTab.VISION_MODELS, AppTab.G2D_LAB, AppTab.VISION, AppTab.OMNI, AppTab.API
        ))
        val accent = if (selected) Palette.mint else Palette.muted
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(62)
            setPadding(dp(2), dp(4), dp(2), dp(4))
            background = ripple(
                rounded(Color.TRANSPARENT, Color.TRANSPARENT, 7f),
                accent
            )
            isClickable = true
            isFocusable = true
            contentDescription = "$title${if (selected) "，已选择" else ""}"
            isSelected = selected
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                setTab(tab)
            }
            addView(
                View(context).apply {
                    background = rounded(if (selected) Palette.mintDark else Color.TRANSPARENT, Color.TRANSPARENT, 2f)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(22), dp(3)).apply { bottomMargin = dp(4) }
            )
            addView(
                FrameLayout(context).apply {
                    addView(IconBadgeView(context, icon, accent), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                    if (tab == AppTab.TEST && benchmarkUiStateMachine.state.isRunning) {
                        addView(
                            View(context).apply {
                                background = rounded(Palette.mintDark, Color.WHITE, 5f)
                                contentDescription = "跑分进行中"
                            },
                            FrameLayout.LayoutParams(dp(9), dp(9), Gravity.END or Gravity.TOP)
                        )
                    }
                },
                LinearLayout.LayoutParams(dp(24), dp(20))
            )
            addView(space(2))
            addView(label(title, 11f, accent, if (selected) Typeface.BOLD else Typeface.NORMAL).apply { maxLines = 1 })
        }
    }

    private fun sectionTitle(title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(34)
            addView(label(title, 15.5f, Palette.deepInk, Typeface.BOLD))
            if (subtitle.isNotBlank()) {
                addView(space(3))
                addView(label(subtitle, 11.2f, Palette.muted, Typeface.NORMAL).apply { maxLines = 2 })
            }
            contentDescription = "$title，$subtitle"
        }
    }

    private fun chipButton(text: String, selected: Boolean, onClick: () -> Unit): View {
        val accent = if (selected) Palette.mintDark else Palette.blue
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            minimumHeight = dp(TuiMaTheme.minimumTouchTargetDp)
            maxLines = 2
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(if (selected) Palette.mintDark else Palette.muted)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = ripple(
                rounded(if (selected) Palette.mintPale else Palette.surface, tint(accent, 0.30f), 7f),
                accent
            )
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                11,
                14,
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
        }
    }

    private fun compactActionButton(text: String, accent: Int, enabled: Boolean, onClick: () -> Unit): View {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 12f
            maxLines = 1
            minimumHeight = dp(44)
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(accent)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = ripple(rounded(tint(accent, 0.10f), tint(accent, 0.30f), 7f), accent)
            isClickable = enabled
            isFocusable = enabled
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.52f
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                9,
                12,
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
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

    private fun runVisionDiffusionProbe() {
        ensureNotificationPermissionAndStartService()
        callLocalApi(
            path = "/vision/diffusion",
            method = "POST",
            body = JSONObject().apply {
                put("prompt", "a small mobilecore smoke image")
                put("width", 512)
                put("height", 512)
                put("steps", 4)
                put("seed", 42)
            }.toString(),
            onResult = { status, body, _ ->
                val json = runCatching { JSONObject(body) }.getOrNull()
                val diffusionStatus = json?.optString("status", "unknown") ?: "unknown"
                val message = when (diffusionStatus) {
                    "model_missing" -> "扩散模型缺失"
                    "runtime_not_installed" -> "扩散 runtime 未接入"
                    "pipeline_not_implemented" -> "扩散 pipeline 未实现"
                    "model_load_error" -> "扩散模型加载失败"
                    else -> "扩散状态：$diffusionStatus"
                }
                routeStatusText?.text = if (status in 200..299) message else "扩散 readiness 请求失败"
                visionResultText?.text = json?.optString("message").orEmpty().ifBlank { message }
                updateStatus(message)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun runTestChat() {
        if (isTestRunning) {
            Toast.makeText(this, "测试正在运行", Toast.LENGTH_SHORT).show()
            return
        }
        isTestRunning = true
        routeStatusText?.text = "正在启动本地 API 并发送测试请求..."
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
                routeStatusText?.text = if (status in 200..299) {
                    "试聊完成 · ${elapsed}ms\n${answer.take(160)}\n速度 ${"%.2f".format(Locale.US, tps)} tok/s · 首字 ${firstToken}ms · 总耗时 ${total}ms"
                } else {
                    "请求失败，请确认模型已加载后再试。"
                }
                updateStatus(if (status in 200..299) "测试完成 · ${elapsed}ms" else "测试失败")
            },
            onError = {
                isTestRunning = false
                routeStatusText?.text = "测试失败，请确认本机服务已启动，并且模型可加载。"
                updateStatus("测试失败")
            }
        )
    }

    private fun requiredBenchmarkModelName(): String {
        return runCatching { BenchmarkManifestRepository(applicationContext).load().model.fileName }
            .getOrDefault("qwen2.5-0.5b-instruct-q4_k_m.gguf")
    }

    private fun requiredBenchmarkModel(): File? {
        val requiredName = requiredBenchmarkModelName()
        return availableGgufModels().firstOrNull { it.name == requiredName }
    }

    private fun requiredBenchmarkModelLifecycle(): ModelLifecycleUiModel {
        val file = requiredBenchmarkModel()
        val item = modelHubItems.firstOrNull { it.fileName == requiredBenchmarkModelName() }
        return modelLifecycle(
            file = file,
            expectedFileName = requiredBenchmarkModelName(),
            downloadState = item?.let { providerStateByProvider[downloadTaskKey(it)] },
        )
    }

    private fun modelLifecycle(
        file: File?,
        expectedFileName: String,
        downloadState: ModelDownloadState?,
    ): ModelLifecycleUiModel {
        val downloadedFile = file?.takeIf { it.isFile && it.length() > 1024 * 1024 }
            ?: availableGgufModels().firstOrNull { it.name.equals(expectedFileName, ignoreCase = true) }
        val path = downloadedFile?.absolutePath
        return ModelLifecyclePresenter.present(
            downloaded = downloadedFile != null,
            active = path != null && activeModelPath == path,
            loading = path != null && pendingModelPath == path,
            downloadStatus = downloadState?.status?.name,
            loadFailed = path != null && modelLoadFailurePath == path,
            progressPercent = downloadState?.percent ?: 0,
        )
    }

    private fun modelLifecycleAccent(tone: ModelLifecycleTone): Int = when (tone) {
        ModelLifecycleTone.ACTIVE -> Palette.mint
        ModelLifecycleTone.READY -> Palette.sky
        ModelLifecycleTone.PROGRESS -> Palette.blue
        ModelLifecycleTone.WARNING -> Palette.amber
        ModelLifecycleTone.ERROR -> Palette.danger
        ModelLifecycleTone.NEUTRAL -> Palette.muted
    }

    private fun refreshRuntimeModelState() {
        callLocalApi(
            path = "/health",
            method = "GET",
            body = null,
            retryCount = 1,
            onResult = { status, body, _ ->
                if (status !in 200..299) return@callLocalApi
                val health = runCatching { JSONObject(body) }.getOrNull() ?: return@callLocalApi
                val loaded = health.optBoolean("model_loaded", false)
                val activeId = health.optString("active_model").takeIf { it.isNotBlank() && it != "null" }
                val resolvedPath = if (loaded && activeId != null) {
                    availableGgufModels().firstOrNull {
                        it.nameWithoutExtension.equals(activeId, ignoreCase = true) ||
                            it.absolutePath.equals(activeId, ignoreCase = true)
                    }?.absolutePath
                } else {
                    null
                }
                activeModelPath = resolvedPath
                if (resolvedPath != null) {
                    pendingModelPath = null
                    modelLoadFailurePath = null
                    modelLoadFailureMessage = null
                }
                if (currentTab in setOf(AppTab.HOME, AppTab.MODELS, AppTab.TEST)) renderCurrentTab()
            },
            onError = { /* A stopped local service is a valid idle state. */ },
        )
    }

    private fun syncBenchmarkReadiness(render: Boolean = true) {
        val missingModel = requiredBenchmarkModel().let { if (it == null) requiredBenchmarkModelName() else null }
        dispatchBenchmarkUi(BenchmarkUiEvent.ReadinessChanged(missingModel), render)
    }

    private fun downloadRequiredBenchmarkModel() {
        val item = modelHubItems.firstOrNull { it.fileName == requiredBenchmarkModelName() }
        if (item == null) {
            Toast.makeText(this, "标准模型下载项暂不可用", Toast.LENGTH_SHORT).show()
            return
        }
        enqueueModelDownload(item)
    }

    private fun dispatchBenchmarkUi(event: BenchmarkUiEvent, render: Boolean = true) {
        val update = {
            val previous = benchmarkUiStateMachine.state
            val next = benchmarkUiStateMachine.dispatch(event)
            isTestRunning = next.isRunning
            if (render && previous != next && currentTab in setOf(AppTab.HOME, AppTab.TEST, AppTab.RESULTS)) {
                renderCurrentTab()
                contentRoot.announceForAccessibility("${benchmarkStateTitle(next)}。${benchmarkStateMessage(next)}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update() else runOnUiThread(update)
    }

    private fun updateBenchmarkLive(
        batteryPercent: Int?,
        temperatureCelsius: Double?,
        decodeTokensPerSecond: Double?
    ) {
        val update = {
            benchmarkLiveSnapshot = BenchmarkLiveSnapshot(
                batteryPercent = batteryPercent,
                temperatureCelsius = temperatureCelsius,
                decodeTokensPerSecond = decodeTokensPerSecond ?: benchmarkLiveSnapshot.decodeTokensPerSecond,
                elapsedMs = if (benchmarkStartedAtMs > 0L) System.currentTimeMillis() - benchmarkStartedAtMs else 0L
            )
            if (currentTab == AppTab.TEST && benchmarkUiStateMachine.state.isRunning) renderCurrentTab()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update() else runOnUiThread(update)
    }

    private fun runBenchmark(profile: BenchmarkProfile) {
        if (benchmarkUiStateMachine.state.isRunning) {
            Toast.makeText(this, "TuiMa 跑分正在运行", Toast.LENGTH_SHORT).show()
            return
        }

        selectedBenchmarkProfile = profile
        benchmarkStartedAtMs = System.currentTimeMillis()
        benchmarkLiveSnapshot = BenchmarkLiveSnapshot()
        dispatchBenchmarkUi(BenchmarkUiEvent.Started(profile))

        val manifestRepository = BenchmarkManifestRepository(applicationContext)
        val manifest = runCatching { manifestRepository.load() }.getOrElse {
            dispatchBenchmarkUi(
                BenchmarkUiEvent.Failed(
                    profile,
                    BenchmarkFailureKind.MODEL_INVALID,
                    "跑分清单校验失败，当前构建不可计分。"
                )
            )
            return
        }
        val model = availableGgufModels().firstOrNull { it.name == manifest.model.fileName }
        if (model == null) {
            dispatchBenchmarkUi(BenchmarkUiEvent.Failed(profile, BenchmarkFailureKind.MODEL_INVALID, "缺少标准模型 ${manifest.model.fileName}。"))
            dispatchBenchmarkUi(BenchmarkUiEvent.ReadinessChanged(manifest.model.fileName))
            updateStatus("缺少 TuiMa 标准模型")
            return
        }

        withNotificationPermission {
            isTestRunning = true
            benchmarkCancellationRequested = false
            val deviceProfile = probeDeviceProfile()
            val spec = BenchmarkSpecV2.forProfile(profile, threads = deviceProfile.coreCount.coerceAtMost(6))
            startServiceInForeground()

            Thread {
                try {
                    val health = localApiRequestBlocking(
                        path = "/health",
                        method = "GET",
                        body = null,
                        retryCount = 8,
                        readTimeoutMs = 2500
                    )

                    val prompt = manifestRepository.loadPrompt(manifest).trimEnd()
                    val telemetry = AndroidBenchmarkTelemetry(applicationContext)
                    val initialTelemetry = telemetry.sample()
                    updateBenchmarkLive(
                        batteryPercent = initialTelemetry.batteryPercent,
                        temperatureCelsius = initialTelemetry.batteryTemperatureCelsius,
                        decodeTokensPerSecond = null
                    )
                    val modelHashMatches = BenchmarkDigestVerifier.matches(model, manifest.model.sha256)
                    val preflight = BenchmarkPreflight.evaluate(
                        BenchmarkPreflightSnapshot(
                            batteryPercent = initialTelemetry.batteryPercent,
                            charging = initialTelemetry.charging,
                            thermalStatus = initialTelemetry.thermalStatus,
                            freeStorageMb = initialTelemetry.freeStorageMb,
                            modelSizeMb = (model.length() + BYTES_PER_MB - 1L) / BYTES_PER_MB,
                            modelHashMatches = modelHashMatches,
                            promptHashMatches = true,
                            apiHealthy = health.status in 200..299 &&
                                runCatching { JSONObject(health.body).optString("status") == "ok" }.getOrDefault(false),
                            benchmarkRunning = false
                        )
                    )
                    if (preflight is BenchmarkPreflightResult.Blocked) {
                        dispatchBenchmarkUi(BenchmarkUiEvent.PreflightBlocked(profile, preflight.reasons))
                        throw BenchmarkRunException(
                            BenchmarkFailureKind.PREFLIGHT_BLOCKED,
                            "跑分门禁：${preflight.reasons.joinToString("、", transform = ::preflightReasonLabel)}"
                        )
                    }

                    dispatchBenchmarkUi(BenchmarkUiEvent.ModelLoading(profile, model.name))
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
                        readTimeoutMs = 120000
                    )
                    if (loadResult.status !in 200..299) {
                        throw BenchmarkRunException(
                            BenchmarkFailureKind.MODEL_INVALID,
                            "模型加载失败 ${loadResult.status}: ${loadResult.body.take(180)}"
                        )
                    }
                    val loadJson = JSONObject(loadResult.body)
                    if (!loadJson.optBoolean("ok", false)) {
                        throw BenchmarkRunException(BenchmarkFailureKind.MODEL_INVALID, "标准模型加载失败")
                    }
                    activeModelPath = model.absolutePath
                    pendingModelPath = null
                    modelLoadFailurePath = null
                    modelLoadFailureMessage = null
                    val loadMs = loadJson.optLong("load_time_ms", loadResult.elapsedMs)

                    val chatBody = JSONObject().apply {
                        put("model", model.nameWithoutExtension)
                        put("max_tokens", spec.profile.outputTokens)
                        put("temperature", spec.temperature.toDouble())
                        put(
                            "messages",
                            JSONArray().apply {
                                put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", prompt)
                                })
                            }
                        )
                    }.toString()

                    repeat(spec.profile.warmupRuns) { index ->
                        throwIfBenchmarkCancelled()
                        dispatchBenchmarkUi(BenchmarkUiEvent.WarmupProgress(profile, index + 1, spec.profile.warmupRuns))
                        executeBenchmarkChat(chatBody, spec.timeoutMs)
                        throwIfBenchmarkCancelled()
                    }

                    val samples = ArrayList<BenchmarkRunSample>(spec.profile.measuredRuns)
                    repeat(spec.profile.measuredRuns) { index ->
                        throwIfBenchmarkCancelled()
                        dispatchBenchmarkUi(BenchmarkUiEvent.MeasurementProgress(profile, index + 1, spec.profile.measuredRuns))
                        val before = telemetry.sample()
                        val chat = executeBenchmarkChat(chatBody, spec.timeoutMs)
                        throwIfBenchmarkCancelled()
                        val after = telemetry.sample()
                        val usage = chat.optJSONObject("usage") ?: JSONObject()
                        val metrics = chat.optJSONObject("mobilecore") ?: JSONObject()
                        val promptTokens = usage.optInt("prompt_tokens", 0)
                        val generatedTokens = usage.optInt("completion_tokens", 0)
                        val promptEvalMs = metrics.optLong("prompt_eval_ms", 0L)
                        val decodeTps = metrics.optDouble("decode_tokens_per_second", 0.0)
                        updateBenchmarkLive(
                            batteryPercent = after.batteryPercent,
                            temperatureCelsius = after.batteryTemperatureCelsius,
                            decodeTokensPerSecond = decodeTps.takeIf { it > 0.0 }
                        )
                        val thermalPeak = listOf(before.thermalStatus, after.thermalStatus).maxBy { it.ordinal }
                        val temperaturePeak = listOfNotNull(
                            before.batteryTemperatureCelsius,
                            after.batteryTemperatureCelsius
                        ).maxOrNull()
                        samples += BenchmarkRunSample(
                            runIndex = index,
                            promptTokens = promptTokens,
                            generatedTokens = generatedTokens,
                            loadTimeMs = loadMs,
                            promptEvalMs = promptEvalMs,
                            firstTokenMs = metrics.optLong("first_token_ms", 0L),
                            decodeMs = metrics.optLong("decode_ms", 0L),
                            totalMs = metrics.optLong("total_ms", 0L),
                            prefillTokensPerSecond = if (promptEvalMs > 0L) {
                                promptTokens * 1000.0 / promptEvalMs
                            } else {
                                0.0
                            },
                            decodeTokensPerSecond = decodeTps,
                            memoryPeakMb = metrics.optLong("memory_peak_mb", 0L),
                            availableMemoryBeforeMb = before.availableMemoryMb,
                            batteryPercentStart = before.batteryPercent,
                            batteryPercentEnd = after.batteryPercent,
                            batteryTemperatureStartCelsius = before.batteryTemperatureCelsius,
                            batteryTemperaturePeakCelsius = temperaturePeak,
                            batteryTemperatureEndCelsius = after.batteryTemperatureCelsius,
                            thermalStart = before.thermalStatus,
                            thermalPeak = thermalPeak,
                            thermalEnd = after.thermalStatus,
                            chargingStart = before.charging,
                            chargingEnd = after.charging,
                            completed = generatedTokens > 0 && decodeTps > 0.0,
                            failureKind = if (generatedTokens > 0 && decodeTps > 0.0) null else BenchmarkFailureKind.METRICS_INCOMPLETE
                        )
                        if (index < spec.profile.measuredRuns - 1 && spec.profile.cooldownMs > 0L) {
                            var remainingMs = spec.profile.cooldownMs
                            while (remainingMs > 0L) {
                                dispatchBenchmarkUi(BenchmarkUiEvent.Cooldown(profile, (remainingMs + 999L) / 1000L))
                                val waitMs = remainingMs.coerceAtMost(1_000L)
                                Thread.sleep(waitMs)
                                remainingMs -= waitMs
                                throwIfBenchmarkCancelled()
                            }
                        }
                    }

                    val summary = BenchmarkAggregator.aggregate(spec, samples)
                    val score = BenchmarkScoreEngine.score(summary)
                    val report = BenchmarkReport(
                        runId = "run-${UUID.randomUUID()}",
                        createdAtMs = System.currentTimeMillis(),
                        manifestSha256 = BenchmarkManifestRepository.EXPECTED_MANIFEST_SHA256,
                        device = BenchmarkDeviceIdentity(
                            manufacturer = deviceProfile.manufacturer,
                            model = deviceProfile.model,
                            device = Build.DEVICE,
                            androidRelease = Build.VERSION.RELEASE,
                            apiLevel = Build.VERSION.SDK_INT,
                            abi = deviceProfile.abi,
                            totalMemoryMb = deviceProfile.totalRamMb,
                            coreCount = deviceProfile.coreCount
                        ),
                        summary = summary,
                        score = score
                    )
                    BenchmarkReportStore(applicationContext).record(report)

                    runOnUiThread {
                        if (score == null) {
                            dispatchBenchmarkUi(
                                BenchmarkUiEvent.Failed(
                                    profile,
                                    summary.failureKind ?: BenchmarkFailureKind.METRICS_INCOMPLETE,
                                    "跑分无效，已完成 ${summary.completedRuns}/${summary.measuredRuns} 次计分。"
                                )
                            )
                        } else {
                            dispatchBenchmarkUi(BenchmarkUiEvent.Finished(profile, score.headlineScore, score.canonicalScore))
                            transitionToResultsAfterBenchmark()
                        }
                        routeStatusText?.text = if (score != null) "跑分完成 · ${score.headlineScore} TuiMa" else "跑分无效"
                        updateStatus(if (score != null) "TuiMa ${score.headlineScore}" else "跑分无效")
                        refreshRecommendationSnapshot()
                    }
                } catch (e: Throwable) {
                    val failureKind = when (e) {
                        is BenchmarkRunException -> e.kind
                        is SocketTimeoutException -> BenchmarkFailureKind.TIMEOUT
                        is OutOfMemoryError -> BenchmarkFailureKind.OOM
                        else -> BenchmarkFailureKind.RUNTIME_UNAVAILABLE
                    }
                    val failedSummary = BenchmarkAggregator.invalid(spec, failureKind)
                    runCatching {
                        BenchmarkReportStore(applicationContext).record(
                            BenchmarkReport(
                                runId = "run-${UUID.randomUUID()}",
                                createdAtMs = System.currentTimeMillis(),
                                manifestSha256 = BenchmarkManifestRepository.EXPECTED_MANIFEST_SHA256,
                                device = BenchmarkDeviceIdentity(
                                    manufacturer = deviceProfile.manufacturer,
                                    model = deviceProfile.model,
                                    device = Build.DEVICE,
                                    androidRelease = Build.VERSION.RELEASE,
                                    apiLevel = Build.VERSION.SDK_INT,
                                    abi = deviceProfile.abi,
                                    totalMemoryMb = deviceProfile.totalRamMb,
                                    coreCount = deviceProfile.coreCount
                                ),
                                summary = failedSummary,
                                score = null
                            )
                        )
                    }
                    runOnUiThread {
                        when {
                            failureKind == BenchmarkFailureKind.CANCELLED -> dispatchBenchmarkUi(BenchmarkUiEvent.Cancelled)
                            failureKind == BenchmarkFailureKind.PREFLIGHT_BLOCKED && benchmarkUiStateMachine.state is BenchmarkUiState.Blocked -> Unit
                            else -> dispatchBenchmarkUi(
                                BenchmarkUiEvent.Failed(
                                    profile,
                                    failureKind,
                                    e.message?.takeIf { it.isNotBlank() }
                                        ?: "TuiMa 跑分失败，请稍后重试。"
                                )
                            )
                        }
                        routeStatusText?.text = "TuiMa 跑分失败"
                        updateStatus("TuiMa 跑分失败")
                    }
                }
            }.start()
        }
    }

    private fun executeBenchmarkChat(body: String, timeoutMs: Long): JSONObject {
        val result = try {
            localApiRequestBlocking(
                path = "/v1/chat/completions",
                method = "POST",
                body = body,
                retryCount = 1,
                readTimeoutMs = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        } catch (e: SocketTimeoutException) {
            RuntimeBridge.cancel()
            throw BenchmarkRunException(BenchmarkFailureKind.TIMEOUT, "推理超时", e)
        }
        if (result.status !in 200..299) {
            throw BenchmarkRunException(
                BenchmarkFailureKind.RUNTIME_UNAVAILABLE,
                "推理请求失败 ${result.status}: ${result.body.take(160)}"
            )
        }
        return JSONObject(result.body)
    }

    private fun transitionToResultsAfterBenchmark() {
        contentRoot.postDelayed({
            if (currentTab != AppTab.TEST || benchmarkUiStateMachine.state !is BenchmarkUiState.Completed) return@postDelayed
            contentRoot.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction {
                    selectedResultRunId = null
                    comparisonBaselineRunId = null
                    selectingComparisonBaseline = false
                    setTab(AppTab.RESULTS)
                    contentRoot.alpha = 0f
                    contentRoot.animate().alpha(1f).setDuration(240L).start()
                }
                .start()
        }, 700L)
    }

    private fun cancelBenchmark() {
        if (!benchmarkUiStateMachine.state.isRunning) {
            Toast.makeText(this, "当前没有跑分任务", Toast.LENGTH_SHORT).show()
            return
        }
        benchmarkCancellationRequested = true
        RuntimeBridge.cancel()
        dispatchBenchmarkUi(BenchmarkUiEvent.CancelRequested)
    }

    private fun confirmCancelBenchmark() {
        if (!benchmarkUiStateMachine.state.isRunning) return
        AlertDialog.Builder(this)
            .setTitle("取消本次跑分？")
            .setMessage("本次测试不会生成成绩，已经完成的诊断数据仍会保存在本机。")
            .setNegativeButton("继续跑分", null)
            .setPositiveButton("确认取消") { _, _ -> cancelBenchmark() }
            .show()
    }

    private fun throwIfBenchmarkCancelled() {
        if (benchmarkCancellationRequested) {
            throw BenchmarkRunException(BenchmarkFailureKind.CANCELLED, "跑分已取消")
        }
    }

    private fun benchmarkProfileName(profile: BenchmarkProfile): String = when (profile) {
        BenchmarkProfile.QUICK -> "快速模式"
        BenchmarkProfile.STANDARD -> "标准模式"
        BenchmarkProfile.STRESS -> "压力模式"
    }

    private fun preflightReasonLabel(reason: BenchmarkPreflightReason): String = when (reason) {
        BenchmarkPreflightReason.BATTERY_TOO_LOW -> "电量低于 30% 或无法读取"
        BenchmarkPreflightReason.DEVICE_CHARGING -> "请断开充电"
        BenchmarkPreflightReason.THERMAL_TOO_HIGH -> "设备温度过高"
        BenchmarkPreflightReason.STORAGE_TOO_LOW -> "存储空间不足"
        BenchmarkPreflightReason.MODEL_INVALID -> "标准模型校验失败"
        BenchmarkPreflightReason.PROMPT_INVALID -> "提示词校验失败"
        BenchmarkPreflightReason.RUNTIME_UNAVAILABLE -> "本机推理服务不可用"
        BenchmarkPreflightReason.BENCHMARK_ALREADY_RUNNING -> "已有跑分任务"
    }

    private fun preflightRecoveryLabel(reason: BenchmarkPreflightReason): String = when (reason) {
        BenchmarkPreflightReason.BATTERY_TOO_LOW -> "将电量充至 30% 以上，再断开充电器"
        BenchmarkPreflightReason.DEVICE_CHARGING -> "断开充电器，等待电量状态稳定"
        BenchmarkPreflightReason.THERMAL_TOO_HIGH -> "锁屏静置几分钟，等待设备降温"
        BenchmarkPreflightReason.STORAGE_TOO_LOW -> "释放至少 512 MB 加标准模型体积的空间"
        BenchmarkPreflightReason.MODEL_INVALID -> "重新下载标准模型，确保文件完整"
        BenchmarkPreflightReason.PROMPT_INVALID -> "当前构建的测试资源异常，请重新安装"
        BenchmarkPreflightReason.RUNTIME_UNAVAILABLE -> "关闭占用资源的应用后重新检测"
        BenchmarkPreflightReason.BENCHMARK_ALREADY_RUNNING -> "等待当前跑分结束或先取消"
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
        readTimeoutMs: Int = 8000,
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
                        readTimeout = readTimeoutMs
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

    private fun handleOmniLifecycleAction(action: OmniLifecycleAction) {
        when (action) {
            OmniLifecycleAction.START_SERVICE -> withNotificationPermission {
                startServiceInForeground()
                progressHandler.postDelayed(omniStatusPollRunnable, 650L)
            }
            OmniLifecycleAction.REFRESH -> refreshOmniLifecycleStatus()
            OmniLifecycleAction.INSTALL -> showOmniInstallConsentDialog()
            OmniLifecycleAction.CANCEL -> performOmniLifecycleRequest(
                actionLabel = "取消安装",
                path = "/mobilecore/omni/cancel",
                body = "{}",
                readTimeoutMs = 45_000,
            )
            OmniLifecycleAction.VERIFY -> performOmniLifecycleRequest(
                actionLabel = "校验 artifact",
                path = "/mobilecore/omni/verify",
                body = "{}",
                readTimeoutMs = 300_000,
            )
            OmniLifecycleAction.LOAD -> performOmniLifecycleRequest(
                actionLabel = "加载多模态模型",
                path = "/mobilecore/omni/load",
                body = JSONObject()
                    .put("context_length", 4096)
                    .put("threads", 4)
                    .toString(),
                readTimeoutMs = 180_000,
            )
            OmniLifecycleAction.UNINSTALL -> showOmniUninstallDialog()
            OmniLifecycleAction.OPEN_SOURCE -> openOmniPinnedSource()
        }
    }

    private fun refreshOmniLifecycleStatus() {
        if (omniStatusRefreshInFlight) return
        omniStatusRefreshInFlight = true
        progressHandler.removeCallbacks(omniStatusPollRunnable)
        callLocalApi(
            path = "/mobilecore/omni/status",
            method = "GET",
            body = null,
            retryCount = 1,
            onResult = { status, body, _ ->
                omniStatusRefreshInFlight = false
                omniLifecycleSnapshot = if (status in 200..299) {
                    runCatching { OmniLifecyclePresenter.parseStatus(body) }.getOrElse {
                        omniLifecycleSnapshot.copy(
                            serviceReachable = true,
                            failureCode = "request_failed",
                            failureMessage = "状态响应无法解析",
                        )
                    }
                } else {
                    OmniLifecyclePresenter.withApiFailure(omniLifecycleSnapshot, body)
                }
                renderOmniLifecycleIfVisible()
                scheduleOmniPollIfBusy()
            },
            onError = {
                omniStatusRefreshInFlight = false
                omniLifecycleSnapshot = OmniLifecycleSnapshot()
                renderOmniLifecycleIfVisible()
            },
        )
    }

    private fun performOmniLifecycleRequest(
        actionLabel: String,
        path: String,
        body: String,
        readTimeoutMs: Int = 15_000,
    ) {
        updateStatus("正在$actionLabel")
        callLocalApi(
            path = path,
            method = "POST",
            body = body,
            retryCount = 1,
            readTimeoutMs = readTimeoutMs,
            onResult = { status, responseBody, _ ->
                omniLifecycleSnapshot = if (status in 200..299) {
                    runCatching { OmniLifecyclePresenter.parseStatus(responseBody) }.getOrElse {
                        omniLifecycleSnapshot.copy(
                            serviceReachable = true,
                            failureCode = "request_failed",
                            failureMessage = "操作完成，但状态响应无法解析",
                        )
                    }
                } else {
                    OmniLifecyclePresenter.withApiFailure(omniLifecycleSnapshot, responseBody)
                }
                updateStatus(if (status in 200..299) "$actionLabel 已提交" else "$actionLabel 未完成")
                Toast.makeText(
                    this,
                    if (status in 200..299) "$actionLabel 已提交" else OmniLifecyclePresenter.present(omniLifecycleSnapshot).statusDetail,
                    Toast.LENGTH_LONG,
                ).show()
                renderOmniLifecycleIfVisible()
                scheduleOmniPollIfBusy()
            },
            onError = {
                omniLifecycleSnapshot = omniLifecycleSnapshot.copy(
                    serviceReachable = false,
                    failureCode = null,
                    failureMessage = null,
                )
                updateStatus("$actionLabel 失败：本机服务不可达")
                Toast.makeText(this, "$actionLabel 失败，本机服务不可达", Toast.LENGTH_LONG).show()
                renderOmniLifecycleIfVisible()
            },
        )
    }

    private fun scheduleOmniPollIfBusy() {
        progressHandler.removeCallbacks(omniStatusPollRunnable)
        if (OmniLifecyclePresenter.present(omniLifecycleSnapshot).isBusy) {
            progressHandler.postDelayed(omniStatusPollRunnable, 1_000L)
        }
    }

    private fun renderOmniLifecycleIfVisible() {
        if (currentTab == AppTab.OMNI) renderCurrentTab()
    }

    private fun showOmniInstallConsentDialog() {
        val model = OmniLifecyclePresenter.present(omniLifecycleSnapshot)
        val installAllowed = omniLifecycleSnapshot.resourcesSufficient && omniLifecycleSnapshot.wifiConnected
        if (!installAllowed) {
            Toast.makeText(this, "设备条件尚未通过，请先重新检查", Toast.LENGTH_LONG).show()
            refreshOmniLifecycleStatus()
            return
        }
        val consent = CheckBox(this).apply {
            text = "我已阅读来源与许可说明，同意本次仅通过 Wi-Fi 下载约 3.39 GiB 到 MobileCore 私有目录。"
            setTextColor(Palette.ink)
            textSize = 13f
            setPadding(dp(4), dp(6), dp(4), dp(6))
            contentDescription = "明确同意本次 Omni 模型下载"
        }
        val message = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(label(
                "发布者是 ggml-org，不是 Qwen 官方 GGUF。许可标识为 ${omniLifecycleSnapshot.licenseId}，状态为“来源声明，未做法律审查”。下载包含 Q4_K_M 主模型和 Q8_0 mmproj；每个文件都必须通过固定字节数和 SHA-256 校验。",
                13f,
                Palette.ink,
                Typeface.NORMAL,
            ).apply { setLineSpacing(0f, 1.16f) })
            addView(space(10))
            addView(label(
                "内存：${model.memoryLabel}\n存储：${model.storageLabel}\n网络：${model.wifiLabel}",
                12f,
                Palette.muted,
                Typeface.NORMAL,
            ))
            addView(space(10))
            addView(consent)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("安装本地多模态模型？")
            .setView(message)
            .setNegativeButton("取消", null)
            .setNeutralButton("查看来源", null)
            .setPositiveButton("同意并开始", null)
            .create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = false
            consent.setOnCheckedChangeListener { _, checked -> positive.isEnabled = checked }
            positive.setOnClickListener {
                if (!consent.isChecked) return@setOnClickListener
                dialog.dismiss()
                performOmniLifecycleRequest(
                    actionLabel = "Omni 安装",
                    path = "/mobilecore/omni/install",
                    body = JSONObject()
                        .put("explicit_consent", true)
                        .put("accepted_license_id", omniLifecycleSnapshot.licenseId)
                        .put("wifi_only", true)
                        .toString(),
                    readTimeoutMs = 20_000,
                )
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { openOmniPinnedSource() }
        }
        dialog.show()
    }

    private fun showOmniUninstallDialog() {
        AlertDialog.Builder(this)
            .setTitle("卸载本地多模态模型？")
            .setMessage("将先卸载运行时，再删除这组固定主模型、mmproj、临时文件和校验记录。MobileCode 的对话与证据不会被修改。")
            .setNegativeButton("保留", null)
            .setPositiveButton("卸载") { _, _ ->
                performOmniLifecycleRequest(
                    actionLabel = "卸载多模态模型",
                    path = "/mobilecore/omni/uninstall",
                    body = "{}",
                    readTimeoutMs = 60_000,
                )
            }
            .show()
    }

    private fun openOmniPinnedSource() {
        val revision = omniLifecycleSnapshot.revision
            .takeIf { it.matches(Regex("[0-9a-f]{40}")) }
            ?: "75f1b73b657a50f5092502799457ccb4a4a1f9df"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
            "https://huggingface.co/ggml-org/Qwen2.5-Omni-3B-GGUF/tree/$revision",
        )))
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
            val wasWaitingForBenchmark = benchmarkUiStateMachine.state is BenchmarkUiState.Checking
            val waitingProfile = benchmarkUiStateMachine.state.profile
            pendingAfterNotificationPermission = null
            updateStatus("通知权限未授予，无法启动前台服务")
            if (wasWaitingForBenchmark) {
                dispatchBenchmarkUi(
                    BenchmarkUiEvent.Failed(
                        waitingProfile,
                        BenchmarkFailureKind.RUNTIME_UNAVAILABLE,
                        "需要通知权限才能在跑分期间保持本机服务运行。"
                    )
                )
            }
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
        // Every entry point here is user-initiated from the visible activity.
        // startService avoids creating another foreground-start timeout when
        // the already-promoted local API service receives a refresh request.
        // MobileCoreService promotes itself in onCreate/onStartCommand.
        startService(intent)
        updateStatus("本机服务已启动")
        refreshRecommendationSnapshot()
    }

    private fun stopMobileCoreService() {
        val intent = Intent(this, MobileCoreService::class.java)
        stopService(intent)
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
                    if (currentTab == AppTab.VISION || currentTab == AppTab.VISION_MODELS) renderCurrentTab()
                }
            } catch (e: Exception) {
                if (destination.exists()) destination.delete()
                runOnUiThread {
                    visionResultText?.text = "视觉模型导入失败。支持 ONNX / ORT / TFLite / MNN / JSON / GGUF / mmproj。"
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
        pendingModelPath = model.absolutePath
        modelLoadFailurePath = null
        modelLoadFailureMessage = null
        val intent = Intent(this, MobileCoreService::class.java).apply {
            putExtra("modelPath", model.absolutePath)
        }
        startService(intent)
        updateStatus("正在加载模型：${model.name}")
        if (currentTab in setOf(AppTab.HOME, AppTab.MODELS, AppTab.TEST)) renderCurrentTab()
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
        val supportedExtensions = setOf("onnx", "ort", "tflite", "mnn", "gguf", "mmproj")
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
            return "还没有导入视觉模型。可导入 ONNX / ORT / TFLite / MNN，CLIP 可配 JSON，VLM 需 GGUF + mmproj。"
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
        val allowed = setOf("onnx", "ort", "tflite", "mnn", "json", "gguf", "mmproj")
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
                message.contains("加载失败") -> "加载失败"
                message.contains("服务已停止") -> "服务已停止"
                message.contains("未找到 GGUF") -> "需要模型"
                message.contains("失败") -> "需要处理"
                else -> "本机服务"
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
            setLineSpacing(dp(1).toFloat(), 1f)
        }
    }

    private fun autoSizeSingleLineLabel(
        text: String,
        maximumSp: Float,
        minimumSp: Float,
        color: Int,
        style: Int,
    ): TextView {
        return label(text, maximumSp, color, style).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                minimumSp.toInt(),
                maximumSp.toInt(),
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
        }
    }

    private fun chip(textView: TextView, background: Int, border: Int): View {
        return FrameLayout(this).apply {
            this.background = rounded(background, tint(border, 0.24f), 7f)
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
            minimumHeight = dp(TuiMaTheme.minimumTouchTargetDp)
            maxLines = 2
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = ripple(roundedGradient(intArrayOf(startColor, endColor), 8f), endColor)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                12,
                14,
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onClick()
            }
        }
    }

    private fun ambientPageBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                mixColor(Palette.background, Palette.blueWash, if (TuiMaTheme.isDark) 0.44f else 0.56f),
                Palette.background,
                Palette.background
            )
        )
    }

    private fun pageGutterDp(): Int = if (resources.configuration.screenWidthDp < 380) 14 else 18

    private fun mixColor(base: Int, overlay: Int, amount: Float): Int {
        val ratio = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(base) + (Color.red(overlay) - Color.red(base)) * ratio).roundToInt(),
            (Color.green(base) + (Color.green(overlay) - Color.green(base)) * ratio).roundToInt(),
            (Color.blue(base) + (Color.blue(overlay) - Color.blue(base)) * ratio).roundToInt()
        )
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

}
