package ai.mobilecore.ui

/**
 * Host boundary for access to local photos. The gallery-search module deliberately does not
 * declare permissions or enumerate MediaStore itself. A host may implement this with Photo
 * Picker grants or a runtime media permission, then feed progress back through [GallerySearchEvent].
 */
interface GalleryMediaAccessContract {
    fun requestGalleryAccess()
    fun scanGrantedMedia()
    fun retryGalleryIndex()
    fun cancelGalleryIndex()
    fun clearGalleryIndex()
}

/** Host boundary for model preparation and retrieval. */
interface GallerySearchRuntimeContract {
    fun prepareSearchModels()
    fun releaseSearchModels()
    fun searchLocalGallery(query: String, topK: Int)
}

sealed interface GalleryIndexState {
    data object PermissionRequired : GalleryIndexState

    data class AccessGranted(
        val persistedIndexDetected: Boolean = false,
    ) : GalleryIndexState

    data class Scanning(val discoveredCount: Int = 0) : GalleryIndexState

    data class Indexing(
        val processedCount: Int,
        val totalCount: Int,
        val skippedCount: Int = 0,
    ) : GalleryIndexState

    data class Ready(
        val indexedCount: Int,
        val lastIndexedAtMs: Long? = null,
        val skippedCount: Int = 0,
    ) : GalleryIndexState

    data class Failed(
        val message: String,
        val retryable: Boolean = true,
    ) : GalleryIndexState
}

sealed interface GalleryModelState {
    data class Missing(
        val clipModelName: String = "MobileCLIP",
        val verifierModelName: String = "0.8B 小模型（可选）",
    ) : GalleryModelState

    data class Preparing(
        val componentName: String,
        val progressPercent: Int,
    ) : GalleryModelState

    data class Ready(
        val clipImageEncoder: String,
        val clipTextEncoder: String,
        val verifierModel: String? = null,
        val modelId: String = "user-imported/clip-compatible",
        val identityVerified: Boolean = false,
    ) : GalleryModelState

    data class Released(
        val reason: String,
    ) : GalleryModelState

    data class Failed(
        val message: String,
        val retryable: Boolean = true,
    ) : GalleryModelState
}

enum class GalleryResultSource {
    CLIP_DIRECT,
    G2D_VERIFIED,
}

/**
 * A search hit contains only host-owned identifiers and display metadata. [contentUri] may be a
 * persisted Photo Picker URI or a MediaStore URI. The screen never opens it directly; hosts bind
 * thumbnails through [GalleryThumbnailBinder].
 */
data class GallerySearchResult(
    val mediaId: String,
    val contentUri: String,
    val title: String,
    val subtitle: String,
    val similarity: Double,
    val source: GalleryResultSource,
)

sealed interface GalleryRetrievalState {
    data object Idle : GalleryRetrievalState

    data class Searching(val query: String) : GalleryRetrievalState

    data class Results(
        val query: String,
        val items: List<GallerySearchResult>,
    ) : GalleryRetrievalState

    data class NoMatch(val query: String) : GalleryRetrievalState

    data class Failed(
        val query: String,
        val message: String,
    ) : GalleryRetrievalState
}

data class GallerySearchState(
    val index: GalleryIndexState = GalleryIndexState.PermissionRequired,
    val models: GalleryModelState = GalleryModelState.Missing(),
    val query: String = "",
    val retrieval: GalleryRetrievalState = GalleryRetrievalState.Idle,
    val limitedPhotoAccess: Boolean = false,
) {
    val isIndexReady: Boolean
        get() = (index as? GalleryIndexState.Ready)?.indexedCount?.let { it > 0 } == true

    val areModelsReady: Boolean
        get() = models is GalleryModelState.Ready

    val canSearch: Boolean
        get() = isIndexReady && areModelsReady
}

sealed interface GallerySearchEvent {
    data object AccessGranted : GallerySearchEvent
    data class AccessAvailable(val persistedIndexDetected: Boolean) : GallerySearchEvent
    data object AccessRevoked : GallerySearchEvent
    data class PhotoAccessScopeChanged(val limited: Boolean) : GallerySearchEvent
    data class ScanProgress(val discoveredCount: Int) : GallerySearchEvent
    data class ScanCompleted(val totalCount: Int, val completedAtMs: Long? = null) : GallerySearchEvent
    data class IndexProgress(
        val processedCount: Int,
        val totalCount: Int,
        val skippedCount: Int = 0,
    ) : GallerySearchEvent
    data class IndexCompleted(
        val indexedCount: Int,
        val completedAtMs: Long,
        val skippedCount: Int = 0,
    ) : GallerySearchEvent
    data class IndexRestored(val indexedCount: Int, val completedAtMs: Long) : GallerySearchEvent
    data object IndexCleared : GallerySearchEvent
    data class IndexFailed(val message: String, val retryable: Boolean = true) : GallerySearchEvent
    data object RetryIndex : GallerySearchEvent

    data class ModelPreparationStarted(val componentName: String) : GallerySearchEvent
    data class ModelPreparationProgress(
        val componentName: String,
        val progressPercent: Int,
    ) : GallerySearchEvent
    data class ModelsReady(
        val clipImageEncoder: String,
        val clipTextEncoder: String,
        val verifierModel: String? = null,
        val modelId: String = "user-imported/clip-compatible",
        val identityVerified: Boolean = false,
    ) : GallerySearchEvent
    data class ModelsReleased(val reason: String) : GallerySearchEvent
    data class ModelPreparationFailed(
        val message: String,
        val retryable: Boolean = true,
    ) : GallerySearchEvent

    data class QueryChanged(val value: String) : GallerySearchEvent
    data object SearchStarted : GallerySearchEvent
    data class SearchCompleted(
        val query: String,
        val items: List<GallerySearchResult>,
    ) : GallerySearchEvent
    data class SearchFailed(val query: String, val message: String) : GallerySearchEvent
    data object ClearSearch : GallerySearchEvent
}

/** Pure reducer used by the Android screen host and unit tests. */
object GallerySearchStateMachine {
    fun reduce(state: GallerySearchState, event: GallerySearchEvent): GallerySearchState = when (event) {
        GallerySearchEvent.AccessGranted -> state.copy(
            index = GalleryIndexState.Scanning(),
            retrieval = GalleryRetrievalState.Idle,
        )
        is GallerySearchEvent.AccessAvailable -> state.copy(
            index = GalleryIndexState.AccessGranted(event.persistedIndexDetected),
            retrieval = GalleryRetrievalState.Idle,
        )
        GallerySearchEvent.AccessRevoked -> state.copy(
            index = GalleryIndexState.PermissionRequired,
            retrieval = GalleryRetrievalState.Idle,
            limitedPhotoAccess = false,
        )
        is GallerySearchEvent.PhotoAccessScopeChanged -> state.copy(
            limitedPhotoAccess = event.limited,
        )
        is GallerySearchEvent.ScanProgress -> if (state.index is GalleryIndexState.Scanning) {
            state.copy(index = GalleryIndexState.Scanning(event.discoveredCount.coerceAtLeast(0)))
        } else {
            state
        }
        is GallerySearchEvent.ScanCompleted -> if (state.index is GalleryIndexState.Scanning) {
            if (event.totalCount <= 0) {
                state.copy(index = GalleryIndexState.Ready(0, event.completedAtMs))
            } else {
                state.copy(index = GalleryIndexState.Indexing(0, event.totalCount))
            }
        } else {
            state
        }
        is GallerySearchEvent.IndexProgress -> if (state.index is GalleryIndexState.Indexing) {
            val total = event.totalCount.coerceAtLeast(0)
            state.copy(
                index = GalleryIndexState.Indexing(
                    processedCount = event.processedCount.coerceIn(0, total),
                    totalCount = total,
                    skippedCount = event.skippedCount.coerceIn(0, event.processedCount.coerceAtLeast(0)),
                ),
            )
        } else {
            state
        }
        is GallerySearchEvent.IndexCompleted -> if (state.index is GalleryIndexState.Indexing) {
            state.copy(
                index = GalleryIndexState.Ready(
                    event.indexedCount.coerceAtLeast(0),
                    event.completedAtMs,
                    event.skippedCount.coerceAtLeast(0),
                ),
                retrieval = GalleryRetrievalState.Idle,
            )
        } else {
            state
        }
        is GallerySearchEvent.IndexRestored -> state.copy(
            index = GalleryIndexState.Ready(
                indexedCount = event.indexedCount.coerceAtLeast(0),
                lastIndexedAtMs = event.completedAtMs.coerceAtLeast(0L),
            ),
            retrieval = GalleryRetrievalState.Idle,
        )
        GallerySearchEvent.IndexCleared -> state.copy(
            index = GalleryIndexState.AccessGranted(persistedIndexDetected = false),
            retrieval = GalleryRetrievalState.Idle,
        )
        is GallerySearchEvent.IndexFailed -> state.copy(
            index = GalleryIndexState.Failed(event.message, event.retryable),
            retrieval = GalleryRetrievalState.Idle,
        )
        GallerySearchEvent.RetryIndex -> if (
            (state.index is GalleryIndexState.Failed && state.index.retryable) ||
            state.index is GalleryIndexState.Ready ||
            state.index is GalleryIndexState.AccessGranted
        ) {
            state.copy(index = GalleryIndexState.Scanning(), retrieval = GalleryRetrievalState.Idle)
        } else {
            state
        }
        is GallerySearchEvent.ModelPreparationStarted -> state.copy(
            models = GalleryModelState.Preparing(event.componentName, 0),
            retrieval = GalleryRetrievalState.Idle,
        )
        is GallerySearchEvent.ModelPreparationProgress -> if (state.models is GalleryModelState.Preparing) {
            state.copy(
                models = GalleryModelState.Preparing(
                    componentName = event.componentName,
                    progressPercent = event.progressPercent.coerceIn(0, 100),
                ),
            )
        } else {
            state
        }
        is GallerySearchEvent.ModelsReady -> state.copy(
            models = GalleryModelState.Ready(
                clipImageEncoder = event.clipImageEncoder,
                clipTextEncoder = event.clipTextEncoder,
                verifierModel = event.verifierModel,
                modelId = event.modelId,
                identityVerified = event.identityVerified,
            ),
        )
        is GallerySearchEvent.ModelsReleased -> state.copy(
            models = GalleryModelState.Released(event.reason),
            retrieval = GalleryRetrievalState.Idle,
        )
        is GallerySearchEvent.ModelPreparationFailed -> state.copy(
            models = GalleryModelState.Failed(event.message, event.retryable),
            retrieval = GalleryRetrievalState.Idle,
        )
        is GallerySearchEvent.QueryChanged -> state.copy(
            query = event.value,
            retrieval = GalleryRetrievalState.Idle,
        )
        GallerySearchEvent.SearchStarted -> {
            val normalized = state.query.trim()
            if (state.canSearch && normalized.isNotEmpty()) {
                state.copy(query = normalized, retrieval = GalleryRetrievalState.Searching(normalized))
            } else {
                state
            }
        }
        is GallerySearchEvent.SearchCompleted -> if (state.retrieval.matches(event.query)) {
            val unique = event.items
                .sortedByDescending { it.similarity }
                .distinctBy { it.mediaId }
            state.copy(
                retrieval = if (unique.isEmpty()) {
                    GalleryRetrievalState.NoMatch(event.query)
                } else {
                    GalleryRetrievalState.Results(event.query, unique)
                },
            )
        } else {
            state
        }
        is GallerySearchEvent.SearchFailed -> if (state.retrieval.matches(event.query)) {
            state.copy(retrieval = GalleryRetrievalState.Failed(event.query, event.message))
        } else {
            state
        }
        GallerySearchEvent.ClearSearch -> state.copy(query = "", retrieval = GalleryRetrievalState.Idle)
    }

    private fun GalleryRetrievalState.matches(query: String): Boolean =
        this is GalleryRetrievalState.Searching && this.query == query.trim()
}

enum class GalleryStatusAction {
    REQUEST_ACCESS,
    RETRY_INDEX,
    CANCEL_INDEX,
    CLEAR_INDEX,
    PREPARE_MODELS,
    RELEASE_MODELS,
    SELECT_MORE_PHOTOS,
}

data class GalleryStatusUiModel(
    val eyebrow: String,
    val title: String,
    val detail: String,
    val progressPercent: Int? = null,
    val actionLabel: String? = null,
    val action: GalleryStatusAction? = null,
    val actionEnabled: Boolean = true,
    val secondaryActionLabel: String? = null,
    val secondaryAction: GalleryStatusAction? = null,
    val secondaryActionEnabled: Boolean = true,
    val isSuccess: Boolean = false,
    val isBusy: Boolean = false,
)

data class GalleryResultUiModel(
    val rank: Int,
    val mediaId: String,
    val contentUri: String,
    val title: String,
    val subtitle: String,
    val scoreLabel: String,
    val sourceLabel: String,
    val sourceDetail: String,
    val source: GalleryResultSource,
)

data class GallerySearchUiModel(
    val indexStatus: GalleryStatusUiModel,
    val modelStatus: GalleryStatusUiModel,
    val query: String,
    val queryEnabled: Boolean,
    val searchEnabled: Boolean,
    val searchActionLabel: String,
    val searchHint: String,
    val resultTitle: String,
    val resultMessage: String,
    val results: List<GalleryResultUiModel>,
    val isSearching: Boolean,
    val showNoMatch: Boolean,
    val privacyLabel: String,
    val topK: Int,
)

object GallerySearchPresenter {
    const val DEFAULT_TOP_K = 20

    fun present(state: GallerySearchState, topK: Int = DEFAULT_TOP_K): GallerySearchUiModel {
        val safeTopK = topK.coerceAtLeast(1)
        val ranked = (state.retrieval as? GalleryRetrievalState.Results)
            ?.items
            .orEmpty()
            .sortedByDescending { it.similarity }
            .take(safeTopK)
            .mapIndexed { index, result -> result.toUi(index + 1) }
        val isSearching = state.retrieval is GalleryRetrievalState.Searching
        val noMatch = state.retrieval is GalleryRetrievalState.NoMatch
        val resultQuery = when (val retrieval = state.retrieval) {
            is GalleryRetrievalState.Results -> retrieval.query
            is GalleryRetrievalState.NoMatch -> retrieval.query
            is GalleryRetrievalState.Failed -> retrieval.query
            else -> state.query.trim()
        }
        val resultMessage = when (val retrieval = state.retrieval) {
            GalleryRetrievalState.Idle -> when {
                state.index is GalleryIndexState.Ready && state.index.indexedCount == 0 ->
                    "没有发现可建立索引的照片。授权更多照片后再试。"
                !state.canSearch -> "索引与 CLIP 模型就绪后，即可用自然语言搜索照片。"
                else -> "试试“海边穿红衣服的人”或“有柱状图的会议照片”。"
            }
            is GalleryRetrievalState.Searching -> "正在从本机向量索引计算 CLIP 余弦相似度。"
            is GalleryRetrievalState.Results -> "“${retrieval.query}”返回余弦相似度最高的 ${ranked.size} 个候选"
            is GalleryRetrievalState.NoMatch -> "本机索引没有返回可排序的候选照片。"
            is GalleryRetrievalState.Failed -> retrieval.message
        }
        return GallerySearchUiModel(
            indexStatus = indexStatus(state.index, state.limitedPhotoAccess),
            modelStatus = modelStatus(state.models),
            query = state.query,
            queryEnabled = state.canSearch && !isSearching,
            searchEnabled = state.canSearch && state.query.isNotBlank() && !isSearching,
            searchActionLabel = if (isSearching) "正在搜索" else "搜索本机照片",
            searchHint = if (state.canSearch) "描述人物、物体、动物、颜色或场景" else "先完成相册索引与模型准备",
            resultTitle = if (ranked.isNotEmpty()) "Top-${ranked.size} 结果" else if (resultQuery.isNotEmpty()) "搜索结果" else "开始搜索",
            resultMessage = resultMessage,
            results = ranked,
            isSearching = isSearching,
            showNoMatch = noMatch,
            privacyLabel = "MobileCore 不上传照片、查询或索引",
            topK = safeTopK,
        )
    }

    private fun indexStatus(
        state: GalleryIndexState,
        limitedPhotoAccess: Boolean,
    ): GalleryStatusUiModel = when (state) {
        GalleryIndexState.PermissionRequired -> GalleryStatusUiModel(
            eyebrow = "相册索引 · 未授权",
            title = "允许访问后建立本机索引",
            detail = "只读取你授权的照片；原图、向量和查询都留在本机。",
            actionLabel = "授权并建立索引",
            action = GalleryStatusAction.REQUEST_ACCESS,
        )
        is GalleryIndexState.AccessGranted -> GalleryStatusUiModel(
            eyebrow = "相册索引 · 已授权",
            title = if (state.persistedIndexDetected) "发现待验证的本机索引" else "尚未建立照片索引",
            detail = if (state.persistedIndexDetected) {
                "准备 CLIP 后会校验并恢复已有索引；模型变化时必须重新建立。"
            } else {
                "扫描已授权照片并生成本机向量，过程中可随时取消并续建。"
            },
            actionLabel = if (limitedPhotoAccess) "选择更多并建立索引" else "建立照片索引",
            action = if (limitedPhotoAccess) {
                GalleryStatusAction.SELECT_MORE_PHOTOS
            } else {
                GalleryStatusAction.RETRY_INDEX
            },
            secondaryActionLabel = if (state.persistedIndexDetected) "清除已有索引" else null,
            secondaryAction = if (state.persistedIndexDetected) GalleryStatusAction.CLEAR_INDEX else null,
        )
        is GalleryIndexState.Scanning -> GalleryStatusUiModel(
            eyebrow = "相册索引 · 扫描中",
            title = "正在发现可搜索照片",
            detail = "已发现 ${state.discoveredCount} 张照片，扫描完成后自动生成向量。",
            progressPercent = null,
            isBusy = true,
        )
        is GalleryIndexState.Indexing -> {
            val progress = if (state.totalCount > 0) {
                (state.processedCount * 100 / state.totalCount).coerceIn(0, 100)
            } else {
                0
            }
            GalleryStatusUiModel(
                eyebrow = "相册索引 · 索引中",
                title = "正在生成图像向量",
                detail = buildString {
                    append("${state.processedCount} / ${state.totalCount} 张 · $progress%")
                    if (state.skippedCount > 0) append(" · 已跳过 ${state.skippedCount} 张")
                },
                progressPercent = progress,
                actionLabel = "取消并保存进度",
                action = GalleryStatusAction.CANCEL_INDEX,
                isBusy = true,
            )
        }
        is GalleryIndexState.Ready -> GalleryStatusUiModel(
            eyebrow = "相册索引 · 已就绪",
            title = if (state.indexedCount > 0) "${state.indexedCount} 张照片可搜索" else "暂时没有可搜索照片",
            detail = if (state.indexedCount > 0) {
                buildString {
                    append("可增量更新；照片和向量不会上传云端。")
                    if (state.skippedCount > 0) append(" 本次跳过 ${state.skippedCount} 张无法读取的照片。")
                }
            } else {
                "授权更多照片后重新建立索引。"
            },
            actionLabel = when {
                limitedPhotoAccess -> "选择更多并更新索引"
                state.indexedCount == 0 -> "重新扫描"
                else -> "更新索引"
            },
            action = if (limitedPhotoAccess) {
                GalleryStatusAction.SELECT_MORE_PHOTOS
            } else {
                GalleryStatusAction.RETRY_INDEX
            },
            secondaryActionLabel = "清除索引",
            secondaryAction = GalleryStatusAction.CLEAR_INDEX,
            isSuccess = state.indexedCount > 0,
        )
        is GalleryIndexState.Failed -> GalleryStatusUiModel(
            eyebrow = "相册索引 · 失败",
            title = "本机索引未完成",
            detail = state.message,
            actionLabel = if (state.retryable) "重新建立索引" else null,
            action = if (state.retryable) GalleryStatusAction.RETRY_INDEX else null,
            actionEnabled = state.retryable,
        )
    }

    private fun modelStatus(state: GalleryModelState): GalleryStatusUiModel = when (state) {
        is GalleryModelState.Missing -> GalleryStatusUiModel(
            eyebrow = "搜索模型 · 待准备",
            title = "准备 CLIP 图文编码器",
            detail = "${state.clipModelName} 用于召回；${state.verifierModelName} 可对模糊候选做 G2D 复核。",
            actionLabel = "准备搜索模型",
            action = GalleryStatusAction.PREPARE_MODELS,
        )
        is GalleryModelState.Preparing -> GalleryStatusUiModel(
            eyebrow = "搜索模型 · 准备中",
            title = "正在准备 ${state.componentName}",
            detail = "进度 ${state.progressPercent.coerceIn(0, 100)}% · 完成后即可离线搜索",
            progressPercent = state.progressPercent.coerceIn(0, 100),
            isBusy = true,
        )
        is GalleryModelState.Ready -> GalleryStatusUiModel(
            eyebrow = if (state.identityVerified) {
                "搜索模型 · 已就绪 · 身份已验证"
            } else {
                "搜索模型 · 已就绪 · 身份未验证"
            },
            title = if (state.verifierModel == null) "CLIP 检索已就绪" else "CLIP + G2D 已就绪",
            detail = buildString {
                append("图像：${state.clipImageEncoder} · 文本：${state.clipTextEncoder}")
                if (state.identityVerified) {
                    append(" · 身份已验证：${state.modelId}")
                } else {
                    append(" · 身份未验证：用户导入的 CLIP 兼容模型")
                }
                if (state.verifierModel == null) {
                    append(" · G2D 复核未启用")
                } else {
                    append(" · 复核：${state.verifierModel}")
                }
            },
            actionLabel = "释放模型内存",
            action = GalleryStatusAction.RELEASE_MODELS,
            isSuccess = true,
        )
        is GalleryModelState.Released -> GalleryStatusUiModel(
            eyebrow = "搜索模型 · 已释放",
            title = "CLIP 文件仍在本机",
            detail = state.reason,
            actionLabel = "重新加载搜索模型",
            action = GalleryStatusAction.PREPARE_MODELS,
        )
        is GalleryModelState.Failed -> GalleryStatusUiModel(
            eyebrow = "搜索模型 · 失败",
            title = "模型准备未完成",
            detail = state.message,
            actionLabel = if (state.retryable) "重新准备" else null,
            action = if (state.retryable) GalleryStatusAction.PREPARE_MODELS else null,
            actionEnabled = state.retryable,
        )
    }

    private fun GallerySearchResult.toUi(rank: Int): GalleryResultUiModel = GalleryResultUiModel(
        rank = rank,
        mediaId = mediaId,
        contentUri = contentUri,
        title = title,
        subtitle = subtitle,
        scoreLabel = "余弦 ${String.format(java.util.Locale.US, "%.3f", similarity)}",
        sourceLabel = when (source) {
            GalleryResultSource.CLIP_DIRECT -> "CLIP 直出"
            GalleryResultSource.G2D_VERIFIED -> "G2D 复核"
        },
        sourceDetail = when (source) {
            GalleryResultSource.CLIP_DIRECT -> "由 CLIP 余弦相似度排序，未应用校准阈值"
            GalleryResultSource.G2D_VERIFIED -> "小模型已在候选集内复核"
        },
        source = source,
    )
}
