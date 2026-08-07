package ai.mobilecore.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

enum class OmniLifecycleStage {
    SERVICE_OFFLINE,
    READY,
    BLOCKED,
    INSTALLING,
    VERIFYING,
    INSTALLED,
    LOADED,
    FAILED,
    CANCELLED,
}

enum class OmniLifecycleAction {
    START_SERVICE,
    REFRESH,
    INSTALL,
    CANCEL,
    VERIFY,
    LOAD,
    UNINSTALL,
    OPEN_SOURCE,
}

data class OmniLifecycleSnapshot(
    val serviceReachable: Boolean = false,
    val phase: String = "idle",
    val pairVerified: Boolean = false,
    val loaded: Boolean = false,
    val wifiConnected: Boolean = false,
    val requiredMemoryBytes: Long = 0L,
    val availableMemoryBytes: Long = 0L,
    val requiredStorageBytes: Long = 0L,
    val availableStorageBytes: Long = 0L,
    val resourcesSufficient: Boolean = false,
    val mainInstalled: Boolean = false,
    val mainVerified: Boolean = false,
    val mmprojInstalled: Boolean = false,
    val mmprojVerified: Boolean = false,
    val licenseId: String = "qwen-research",
    val licenseReviewStatus: String = "source_declared_not_legal_reviewed",
    val revision: String = "",
    val failureCode: String? = null,
    val failureMessage: String? = null,
)

data class OmniLifecycleActionUiModel(
    val action: OmniLifecycleAction,
    val label: String,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

data class OmniLifecycleUiModel(
    val stage: OmniLifecycleStage,
    val statusLabel: String,
    val statusDetail: String,
    val isBusy: Boolean,
    val memoryLabel: String,
    val memoryReady: Boolean,
    val storageLabel: String,
    val storageReady: Boolean,
    val wifiLabel: String,
    val mainArtifactLabel: String,
    val mmprojArtifactLabel: String,
    val licenseLabel: String,
    val revisionLabel: String,
    val actions: List<OmniLifecycleActionUiModel>,
)

/** Pure projection of the loopback lifecycle contract into an honest user-facing state. */
object OmniLifecyclePresenter {
    fun parseStatus(body: String): OmniLifecycleSnapshot {
        val root = JSONObject(body)
        val status = root.optJSONObject("status") ?: root
        val preflight = status.optJSONObject("preflight") ?: JSONObject()
        val artifacts = status.optJSONObject("artifacts") ?: JSONObject()
        val main = artifacts.optJSONObject("main") ?: JSONObject()
        val mmproj = artifacts.optJSONObject("mmproj") ?: JSONObject()
        val license = status.optJSONObject("license") ?: JSONObject()
        val failure = status.optJSONObject("failure")
        val requiredMemory = preflight.optLong("required_memory_bytes", 0L)
        val availableMemory = preflight.optLong("available_memory_bytes", 0L)
        val requiredStorage = preflight.optLong("required_storage_bytes", 0L)
        val availableStorage = preflight.optLong("available_storage_bytes", 0L)
        return OmniLifecycleSnapshot(
            serviceReachable = true,
            phase = status.optString("phase", "idle"),
            pairVerified = status.optBoolean("pair_verified", false),
            loaded = status.optBoolean("loaded", false),
            wifiConnected = status.optBoolean("wifi_connected", false),
            requiredMemoryBytes = requiredMemory,
            availableMemoryBytes = availableMemory,
            requiredStorageBytes = requiredStorage,
            availableStorageBytes = availableStorage,
            resourcesSufficient = preflight.optBoolean(
                "resources_sufficient",
                requiredMemory > 0L && requiredStorage > 0L &&
                    availableMemory >= requiredMemory && availableStorage >= requiredStorage,
            ),
            mainInstalled = main.optBoolean("installed", false),
            mainVerified = main.optBoolean("verified", false),
            mmprojInstalled = mmproj.optBoolean("installed", false),
            mmprojVerified = mmproj.optBoolean("verified", false),
            licenseId = license.optString("id", "qwen-research"),
            licenseReviewStatus = license.optString(
                "review_status",
                "source_declared_not_legal_reviewed",
            ),
            revision = status.optString("revision", ""),
            failureCode = failure?.optString("code")?.takeIf(String::isNotBlank),
            failureMessage = failure?.optString("message")?.takeIf(String::isNotBlank),
        )
    }

    fun withApiFailure(current: OmniLifecycleSnapshot, body: String): OmniLifecycleSnapshot {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        return current.copy(
            serviceReachable = true,
            failureCode = error?.optString("code")?.takeIf(String::isNotBlank) ?: "request_failed",
            failureMessage = error?.optString("message")?.takeIf(String::isNotBlank),
        )
    }

    fun present(snapshot: OmniLifecycleSnapshot): OmniLifecycleUiModel {
        val phase = snapshot.phase.lowercase(Locale.US)
        val busy = phase in setOf("preflight", "downloading", "verifying")
        val resourceFailure = snapshot.failureCode in setOf("insufficient_memory", "insufficient_storage", "wifi_required")
        val stage = when {
            !snapshot.serviceReachable -> OmniLifecycleStage.SERVICE_OFFLINE
            snapshot.loaded && snapshot.pairVerified -> OmniLifecycleStage.LOADED
            snapshot.pairVerified -> OmniLifecycleStage.INSTALLED
            phase == "verifying" -> OmniLifecycleStage.VERIFYING
            phase in setOf("preflight", "downloading") -> OmniLifecycleStage.INSTALLING
            phase == "cancelled" -> OmniLifecycleStage.CANCELLED
            (phase == "failed" || snapshot.failureCode != null) && !resourceFailure ->
                OmniLifecycleStage.FAILED
            resourceFailure || !snapshot.resourcesSufficient || !snapshot.wifiConnected -> OmniLifecycleStage.BLOCKED
            else -> OmniLifecycleStage.READY
        }
        val (statusLabel, detail) = statusCopy(stage, snapshot)
        return OmniLifecycleUiModel(
            stage = stage,
            statusLabel = statusLabel,
            statusDetail = detail,
            isBusy = busy,
            memoryLabel = resourceLabel(snapshot.availableMemoryBytes, snapshot.requiredMemoryBytes),
            memoryReady = snapshot.requiredMemoryBytes > 0L &&
                snapshot.availableMemoryBytes >= snapshot.requiredMemoryBytes,
            storageLabel = resourceLabel(snapshot.availableStorageBytes, snapshot.requiredStorageBytes),
            storageReady = snapshot.requiredStorageBytes > 0L &&
                snapshot.availableStorageBytes >= snapshot.requiredStorageBytes,
            wifiLabel = if (snapshot.wifiConnected) "已连接，可按仅 Wi-Fi 策略下载" else "未检测到 Wi-Fi",
            mainArtifactLabel = artifactLabel(snapshot.mainInstalled, snapshot.mainVerified),
            mmprojArtifactLabel = artifactLabel(snapshot.mmprojInstalled, snapshot.mmprojVerified),
            licenseLabel = "${snapshot.licenseId} · ${licenseReviewLabel(snapshot.licenseReviewStatus)}",
            revisionLabel = snapshot.revision.take(12).ifBlank { "等待服务返回" },
            actions = actions(stage, snapshot),
        )
    }

    private fun statusCopy(
        stage: OmniLifecycleStage,
        snapshot: OmniLifecycleSnapshot,
    ): Pair<String, String> = when (stage) {
        OmniLifecycleStage.SERVICE_OFFLINE -> "服务未连接" to "启动本机服务后读取实时资源和模型状态。"
        OmniLifecycleStage.READY -> "可以安装" to "设备条件满足；仍需阅读来源与许可说明并逐次明确同意。"
        OmniLifecycleStage.BLOCKED -> "当前设备条件不足" to blockedDetail(snapshot)
        OmniLifecycleStage.INSTALLING -> "正在下载" to "两个固定版本 artifact 正在写入应用私有目录，可随时取消。"
        OmniLifecycleStage.VERIFYING -> "正在校验" to "正在核对固定字节数与 SHA-256，校验完成前不会加载。"
        OmniLifecycleStage.INSTALLED -> "已校验，尚未加载" to "主模型与 mmproj 均已验证；加载后才会公布图片和音频能力。"
        OmniLifecycleStage.LOADED -> "本地多模态已加载" to "运行时已确认这组固定 artifact；实际能力仍以 /health 为准。"
        OmniLifecycleStage.CANCELLED -> "安装已取消" to "临时下载文件已清理；再次安装仍需重新明确同意。"
        OmniLifecycleStage.FAILED -> "操作失败" to failureLabel(snapshot.failureCode, snapshot.failureMessage)
    }

    private fun actions(
        stage: OmniLifecycleStage,
        snapshot: OmniLifecycleSnapshot,
    ): List<OmniLifecycleActionUiModel> = when (stage) {
        OmniLifecycleStage.SERVICE_OFFLINE -> listOf(
            OmniLifecycleActionUiModel(OmniLifecycleAction.START_SERVICE, "启动服务并检查"),
        )
        OmniLifecycleStage.READY -> listOf(
            OmniLifecycleActionUiModel(
                OmniLifecycleAction.INSTALL,
                "阅读说明并同意下载",
                enabled = snapshot.resourcesSufficient && snapshot.wifiConnected,
            ),
            OmniLifecycleActionUiModel(OmniLifecycleAction.REFRESH, "重新检查"),
        )
        OmniLifecycleStage.BLOCKED -> listOf(
            OmniLifecycleActionUiModel(OmniLifecycleAction.REFRESH, "重新检查设备条件"),
        )
        OmniLifecycleStage.INSTALLING,
        OmniLifecycleStage.VERIFYING -> listOf(
            OmniLifecycleActionUiModel(OmniLifecycleAction.CANCEL, "取消安装", destructive = true),
        )
        OmniLifecycleStage.INSTALLED -> listOf(
            OmniLifecycleActionUiModel(OmniLifecycleAction.LOAD, "加载到本机运行时"),
            OmniLifecycleActionUiModel(OmniLifecycleAction.VERIFY, "重新校验"),
            OmniLifecycleActionUiModel(OmniLifecycleAction.UNINSTALL, "卸载", destructive = true),
        )
        OmniLifecycleStage.LOADED -> listOf(
            OmniLifecycleActionUiModel(OmniLifecycleAction.VERIFY, "重新校验"),
            OmniLifecycleActionUiModel(OmniLifecycleAction.UNINSTALL, "卸载并释放", destructive = true),
        )
        OmniLifecycleStage.CANCELLED,
        OmniLifecycleStage.FAILED -> buildList {
            if (snapshot.resourcesSufficient && snapshot.wifiConnected) {
                add(OmniLifecycleActionUiModel(OmniLifecycleAction.INSTALL, "重新阅读并安装"))
            }
            add(OmniLifecycleActionUiModel(OmniLifecycleAction.REFRESH, "刷新状态"))
            if (snapshot.mainInstalled || snapshot.mmprojInstalled) {
                add(OmniLifecycleActionUiModel(OmniLifecycleAction.UNINSTALL, "清理本地文件", destructive = true))
            }
        }
    }

    private fun blockedDetail(snapshot: OmniLifecycleSnapshot): String = when {
        snapshot.requiredStorageBytes > 0L && snapshot.availableStorageBytes < snapshot.requiredStorageBytes ->
            "应用私有存储不足，安装不会启动。"
        snapshot.requiredMemoryBytes > 0L && snapshot.availableMemoryBytes < snapshot.requiredMemoryBytes ->
            "当前可用内存低于保守加载门槛，安装不会启动。"
        !snapshot.wifiConnected -> "仅 Wi-Fi 策略已启用，连接 Wi-Fi 后再检查。"
        else -> failureLabel(snapshot.failureCode, snapshot.failureMessage)
    }

    private fun failureLabel(code: String?, message: String?): String = when (code) {
        "checksum_mismatch" -> "文件摘要不匹配，不能加载；请清理后重新安装。"
        "artifact_missing" -> "固定 artifact 对不完整，不能加载。"
        "download_failed" -> "下载未完成，请检查网络后重试。"
        "projector_incompatible" -> "mmproj 与主模型不兼容。"
        "projector_load_failed" -> "运行时拒绝了已校验的 mmproj。"
        "model_load_failed" -> "运行时未能加载已校验模型。"
        "wifi_required" -> "仅 Wi-Fi 下载需要有效 Wi-Fi 连接。"
        "insufficient_memory" -> "当前可用内存低于保守门槛。"
        "insufficient_storage" -> "应用私有存储不足。"
        "request_failed" -> "本机服务未完成请求，请刷新后重试。"
        else -> message?.takeIf(String::isNotBlank) ?: "本机生命周期操作未完成。"
    }

    private fun artifactLabel(installed: Boolean, verified: Boolean): String = when {
        verified -> "已安装 · SHA-256 已校验"
        installed -> "已安装 · 尚未校验"
        else -> "未安装"
    }

    private fun licenseReviewLabel(value: String): String = when (value) {
        "source_declared_not_legal_reviewed" -> "来源声明，未做法律审查"
        else -> value.ifBlank { "审查状态未知" }
    }

    private fun resourceLabel(available: Long, required: Long): String {
        if (required <= 0L) return "等待本机服务返回"
        return "${formatBytes(available)} 可用 / ${formatBytes(required)} 需要"
    }

    private fun formatBytes(bytes: Long): String {
        val gib = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0 * 1024.0)
        return "%.2f GiB".format(Locale.US, gib)
    }
}

data class OmniLifecycleCallbacks(
    val onAction: (OmniLifecycleAction) -> Unit = {},
)

/** MobileCore-owned lifecycle surface; it exposes no device-control or Phone Use action. */
class OmniLifecycleScreen(context: Context) : LinearLayout(context) {
    private val content = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, dp(4), 0, dp(24))
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.background)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(model: OmniLifecycleUiModel, callbacks: OmniLifecycleCallbacks) {
        content.removeAllViews()
        content.addView(header())
        content.addView(space(12))
        content.addView(statusCard(model, callbacks))
        content.addView(space(12))
        content.addView(preflightCard(model))
        content.addView(space(12))
        content.addView(artifactCard(model))
        content.addView(space(12))
        content.addView(licenseCard(model, callbacks))
        content.addView(space(12))
        content.addView(boundaryCard())
    }

    private fun header(): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(TuiMaTheme.compactHeaderHeightDp)
        addView(IconBadgeView(context, "chip", Palette.lavender), LayoutParams(dp(42), dp(42)).apply {
            marginEnd = dp(12)
        })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(text("本地多模态", 20f, Palette.deepInk, Typeface.BOLD))
            addView(text("Qwen2.5-Omni-3B 固定双 artifact 生命周期", 12f, Palette.muted))
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(pill("实验", Palette.lavender))
    }

    private fun statusCard(model: OmniLifecycleUiModel, callbacks: OmniLifecycleCallbacks): View =
        card(stageColor(model.stage)) {
            addView(text(model.statusLabel, 18f, Palette.deepInk, Typeface.BOLD))
            addView(space(5))
            addView(text(model.statusDetail, 13f, Palette.ink))
            if (model.isBusy) {
                addView(space(12))
                addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(Palette.blue)
                    progressBackgroundTintList = ColorStateList.valueOf(Palette.stroke)
                    contentDescription = model.statusLabel
                }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
            }
            if (model.actions.isNotEmpty()) {
                addView(space(13))
                model.actions.forEachIndexed { index, action ->
                    if (index > 0) addView(space(8))
                    addView(actionButton(action) { callbacks.onAction(action.action) }, LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(TuiMaTheme.minimumTouchTargetDp),
                    ))
                }
            }
        }

    private fun preflightCard(model: OmniLifecycleUiModel): View = card(Palette.sky) {
        addView(sectionTitle("安装前检查", "每次读取实时值，不缓存授权"))
        addView(space(9))
        addView(metaRow("内存", model.memoryLabel, model.memoryReady))
        addView(metaRow("存储", model.storageLabel, model.storageReady))
        addView(metaRow("网络", model.wifiLabel, model.wifiLabel.startsWith("已连接")))
    }

    private fun artifactCard(model: OmniLifecycleUiModel): View = card(Palette.mint) {
        addView(sectionTitle("固定 artifact 对", "任一文件未校验都不会公布多模态能力"))
        addView(space(9))
        addView(metaRow("主模型", model.mainArtifactLabel, model.mainArtifactLabel.contains("已校验")))
        addView(metaRow("mmproj", model.mmprojArtifactLabel, model.mmprojArtifactLabel.contains("已校验")))
        addView(metaRow("revision", model.revisionLabel, model.revisionLabel != "等待服务返回"))
    }

    private fun licenseCard(model: OmniLifecycleUiModel, callbacks: OmniLifecycleCallbacks): View = card(Palette.amber) {
        addView(sectionTitle("来源与许可", "下载前必须单独确认"))
        addView(space(8))
        addView(text("发布者：ggml-org（不是 Qwen 官方 GGUF）", 13f, Palette.ink))
        addView(text("许可：${model.licenseLabel}", 13f, Palette.ink).apply { setPadding(0, dp(4), 0, 0) })
        addView(text("完整下载约 3.39 GiB；仅写入 MobileCore 应用私有目录。", 12f, Palette.muted).apply {
            setPadding(0, dp(7), 0, 0)
        })
        addView(space(11))
        addView(actionButton(OmniLifecycleActionUiModel(OmniLifecycleAction.OPEN_SOURCE, "查看固定版本来源")) {
            callbacks.onAction(OmniLifecycleAction.OPEN_SOURCE)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TuiMaTheme.minimumTouchTargetDp)))
    }

    private fun boundaryCard(): View = card(Palette.lavender) {
        addView(sectionTitle("能力边界", "MobileCore 只负责本地推理"))
        addView(space(7))
        addView(text("此页面不能点击其他 App、登录账号或下单。Phone Use、交易审批与证据链仍由 MobileCode 控制。", 12.5f, Palette.ink))
        addView(text("当前 GGUF 路线仅支持文本/图片/音频输入到文本输出；不支持视频输入和语音输出。", 12f, Palette.muted).apply {
            setPadding(0, dp(7), 0, 0)
        })
    }

    private fun card(accent: Int, block: LinearLayout.() -> Unit): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(Palette.surface, tint(accent, 0.42f), 16f)
        elevation = dp(1).toFloat()
        block()
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(text(title, 16f, Palette.deepInk, Typeface.BOLD))
        addView(text(subtitle, 12f, Palette.muted).apply { setPadding(0, dp(3), 0, 0) })
    }

    private fun metaRow(label: String, value: String, ready: Boolean): View = LinearLayout(context).apply {
        gravity = Gravity.TOP
        minimumHeight = dp(42)
        setPadding(0, dp(6), 0, dp(6))
        addView(View(context).apply {
            background = rounded(if (ready) Palette.mintDark else Palette.amber, Color.TRANSPARENT, 4f)
        }, LayoutParams(dp(8), dp(8)).apply { topMargin = dp(5); marginEnd = dp(9) })
        addView(text(label, 12.5f, Palette.ink, Typeface.BOLD), LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(text(value, 12f, Palette.muted).apply { gravity = Gravity.END }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        contentDescription = "$label，$value"
    }

    private fun actionButton(action: OmniLifecycleActionUiModel, onClick: () -> Unit): Button = Button(context).apply {
        text = action.label
        textSize = 13f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        isEnabled = action.enabled
        alpha = if (action.enabled) 1f else 0.46f
        setTextColor(if (action.destructive) Palette.danger else Palette.deepInk)
        background = rounded(
            if (action.destructive) Palette.dangerWash else Palette.mintPale,
            if (action.destructive) tint(Palette.danger, 0.45f) else Palette.mint,
            14f,
        )
        contentDescription = action.label
        setOnClickListener { if (action.enabled) onClick() }
    }

    private fun stageColor(stage: OmniLifecycleStage): Int = when (stage) {
        OmniLifecycleStage.READY,
        OmniLifecycleStage.INSTALLED,
        OmniLifecycleStage.LOADED -> Palette.mint
        OmniLifecycleStage.INSTALLING,
        OmniLifecycleStage.VERIFYING -> Palette.blue
        OmniLifecycleStage.BLOCKED,
        OmniLifecycleStage.CANCELLED -> Palette.amber
        OmniLifecycleStage.FAILED -> Palette.danger
        OmniLifecycleStage.SERVICE_OFFLINE -> Palette.muted
    }

    private fun pill(value: String, accent: Int): TextView = text(value, 11f, accent, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = rounded(tint(accent, 0.12f), tint(accent, 0.38f), 99f)
    }

    private fun text(value: String, sizeSp: Float, color: Int, style: Int = Typeface.NORMAL): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            setTypeface(typeface, style)
            setLineSpacing(0f, 1.14f)
        }

    private fun rounded(color: Int, stroke: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun tint(color: Int, alpha: Float): Int = Color.argb(
        (255 * alpha).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun space(heightDp: Int): View = View(context).apply {
        layoutParams = LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
