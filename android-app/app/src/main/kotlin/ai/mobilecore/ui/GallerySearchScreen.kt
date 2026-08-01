package ai.mobilecore.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Actions supplied by a future Activity/Fragment host. Implementations own all permission,
 * MediaStore/Photo Picker, indexing, model and result-opening behavior.
 */
interface GallerySearchActions : GalleryMediaAccessContract, GallerySearchRuntimeContract {
    fun updateGalleryQuery(query: String)
    fun clearGallerySearch()
    fun openGalleryResult(mediaId: String, contentUri: String)
}

/** Host-owned thumbnail decoding boundary; this view never opens a content URI itself. */
fun interface GalleryThumbnailBinder {
    fun bind(target: ImageView, result: GalleryResultUiModel)
}

/**
 * Native View implementation for the local text-to-photo search product surface.
 *
 * This class intentionally has no Activity dependency so it can be mounted under a future tab,
 * Fragment or standalone Activity without coupling the search state machine to MainActivity.
 */
class GallerySearchScreen(context: Context) : LinearLayout(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(24))
    }

    init {
        orientation = LinearLayout.VERTICAL
        clipToPadding = false
        setBackgroundColor(Palette.background)
        addView(
            content,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    fun bind(
        model: GallerySearchUiModel,
        actions: GallerySearchActions,
        thumbnailBinder: GalleryThumbnailBinder? = null,
    ) {
        setBackgroundColor(Palette.background)
        content.removeAllViews()
        content.addView(buildHeader(model))
        content.addView(space(18))
        content.addView(buildStatusCard(model.indexStatus, actions))
        content.addView(space(12))
        content.addView(buildStatusCard(model.modelStatus, actions))
        content.addView(space(22))
        content.addView(buildSearchComposer(model, actions))
        content.addView(space(22))
        content.addView(buildResultSection(model, actions, thumbnailBinder))
    }

    private fun buildHeader(model: GallerySearchUiModel): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL

        addView(TextView(context).apply {
            text = "本地视觉搜索"
            textSize = 12f
            setTextColor(Palette.mintDark)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
        })
        addView(TextView(context).apply {
            text = "一句话，搜索本地相册"
            textSize = 27f
            setTextColor(Palette.deepInk)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(7))
        })
        addView(TextView(context).apply {
            text = "CLIP 负责快速召回，模糊结果可由 G2D 小模型在候选集内复核。"
            textSize = 14f
            setTextColor(Palette.ink)
            setLineSpacing(0f, 1.18f)
        })
        addView(TextView(context).apply {
            text = "  ${model.privacyLabel}  "
            textSize = 12f
            setTextColor(Palette.mintDark)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(Palette.mintPale, Palette.mint, 999f)
            setPadding(dp(7), dp(5), dp(7), dp(5))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
    }

    private fun buildStatusCard(
        status: GalleryStatusUiModel,
        actions: GallerySearchActions,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(
            color = if (status.isSuccess) Palette.mintWash else Palette.surface,
            stroke = if (status.isSuccess) Palette.mint else Palette.stroke,
            radiusDp = TuiMaTheme.cardRadiusDp,
        )

        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(IconBadgeView(
            context,
            if (status.eyebrow.startsWith("相册")) "image" else "chip",
            if (status.isSuccess) Palette.mintDark else Palette.blue,
        ), LinearLayout.LayoutParams(dp(38), dp(38)))
        heading.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
            addView(TextView(context).apply {
                text = status.eyebrow
                textSize = 11f
                setTextColor(if (status.isSuccess) Palette.mintDark else Palette.muted)
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.04f
            })
            addView(TextView(context).apply {
                text = status.title
                textSize = 16f
                setTextColor(Palette.deepInk)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(heading)

        addView(TextView(context).apply {
            text = status.detail
            textSize = 13f
            setTextColor(Palette.ink)
            setLineSpacing(0f, 1.18f)
            setPadding(0, dp(11), 0, 0)
        })

        if (status.isBusy) {
            addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = status.progressPercent == null
                status.progressPercent?.let { progress = it }
                progressTintList = ColorStateList.valueOf(Palette.mintDark)
                indeterminateTintList = ColorStateList.valueOf(Palette.mintDark)
                progressBackgroundTintList = ColorStateList.valueOf(Palette.stroke)
                contentDescription = status.progressPercent?.let { "进度 $it%" } ?: "正在处理"
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8),
            ).apply { topMargin = dp(12) })
        }

        status.actionLabel?.let { label ->
            addView(primaryButton(label, status.actionEnabled) {
                when (status.action) {
                    GalleryStatusAction.REQUEST_ACCESS -> actions.requestGalleryAccess()
                    GalleryStatusAction.RETRY_INDEX -> actions.retryGalleryIndex()
                    GalleryStatusAction.PREPARE_MODELS -> actions.prepareSearchModels()
                    null -> Unit
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(TuiMaTheme.minimumTouchTargetDp),
            ).apply { topMargin = dp(13) })
        }
    }

    private fun buildSearchComposer(
        model: GallerySearchUiModel,
        actions: GallerySearchActions,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionEyebrow("自然语言检索"))
        addView(TextView(context).apply {
            text = "你想找什么？"
            textSize = 21f
            setTextColor(Palette.deepInk)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(12))
        })

        val queryInput = EditText(context).apply {
            setText(model.query)
            setSelection(text.length)
            hint = model.searchHint
            textSize = 15f
            setTextColor(Palette.deepInk)
            setHintTextColor(Palette.muted)
            isEnabled = model.queryEnabled
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rounded(
                color = if (model.queryEnabled) Palette.surface else Palette.background,
                stroke = if (model.queryEnabled) Palette.blue else Palette.stroke,
                radiusDp = 13f,
            )
            setPadding(dp(15), 0, dp(15), 0)
            contentDescription = "照片搜索描述"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    actions.updateGalleryQuery(s?.toString().orEmpty())
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH && model.searchEnabled) {
                    performSearch(model.copy(query = text.toString()), actions)
                    true
                } else {
                    false
                }
            }
        }
        addView(queryInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52),
        ))

        addView(primaryButton(model.searchActionLabel, model.searchEnabled) {
            performSearch(model.copy(query = queryInput.text.toString()), actions)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52),
        ).apply { topMargin = dp(10) })

        if (model.query.isNotBlank()) {
            addView(TextView(context).apply {
                text = "清除搜索"
                textSize = 13f
                setTextColor(Palette.blue)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                minHeight = dp(TuiMaTheme.minimumTouchTargetDp)
                setPadding(dp(10), dp(12), dp(10), dp(10))
                setOnClickListener { actions.clearGallerySearch() }
            })
        }
    }

    private fun performSearch(model: GallerySearchUiModel, actions: GallerySearchActions) {
        val query = model.query.trim()
        if (!model.queryEnabled || query.isEmpty()) return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(windowToken, 0)
        actions.updateGalleryQuery(query)
        actions.searchLocalGallery(query, model.topK)
    }

    private fun buildResultSection(
        model: GallerySearchUiModel,
        actions: GallerySearchActions,
        thumbnailBinder: GalleryThumbnailBinder?,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionEyebrow(if (model.results.isEmpty()) "搜索结果" else "本机召回"))
        addView(TextView(context).apply {
            text = model.resultTitle
            textSize = 21f
            setTextColor(Palette.deepInk)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
        })
        addView(TextView(context).apply {
            text = model.resultMessage
            textSize = 13f
            setTextColor(if (model.showNoMatch) Palette.ink else Palette.muted)
            setLineSpacing(0f, 1.18f)
        })

        if (model.isSearching) {
            addView(ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(Palette.mintDark)
                contentDescription = "正在搜索本机照片"
            }, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            })
        }

        if (model.results.isNotEmpty()) {
            val grid = GridLayout(context).apply {
                columnCount = 2
                alignmentMode = GridLayout.ALIGN_BOUNDS
                useDefaultMargins = false
            }
            model.results.forEachIndexed { index, result ->
                val row = index / 2
                val column = index % 2
                grid.addView(
                    buildResultCard(result, actions, thumbnailBinder),
                    GridLayout.LayoutParams(
                        GridLayout.spec(row),
                        GridLayout.spec(column, 1f),
                    ).apply {
                        width = 0
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                        topMargin = dp(12)
                        if (column == 0) rightMargin = dp(6) else leftMargin = dp(6)
                    },
                )
            }
            addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(TextView(context).apply {
                text = "CLIP 直出表示相似度达到阈值；G2D 复核表示小模型仅在候选照片中完成判别。"
                textSize = 12f
                setTextColor(Palette.muted)
                setLineSpacing(0f, 1.16f)
                setPadding(0, dp(14), 0, 0)
            })
        }
    }

    private fun buildResultCard(
        result: GalleryResultUiModel,
        actions: GallerySearchActions,
        thumbnailBinder: GalleryThumbnailBinder?,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        contentDescription = "第 ${result.rank} 个结果，${result.title}，${result.scoreLabel}，${result.sourceLabel}"
        background = rounded(Palette.surface, Palette.stroke, 13f)
        setPadding(dp(8), dp(8), dp(8), dp(11))
        setOnClickListener { actions.openGalleryResult(result.mediaId, result.contentUri) }

        val thumbnail = FrameLayout(context).apply {
            background = rounded(
                if (result.source == GalleryResultSource.G2D_VERIFIED) Palette.lavenderWash else Palette.blueWash,
                Color.TRANSPARENT,
                10f,
            )
            addView(TextView(context).apply {
                text = "照片 ${result.rank}"
                textSize = 13f
                setTextColor(Palette.muted)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = result.title
            }
            addView(image, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            thumbnailBinder?.bind(image, result)
        }
        addView(thumbnail, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(116),
        ))

        addView(TextView(context).apply {
            text = result.title
            textSize = 14f
            maxLines = 1
            setTextColor(Palette.deepInk)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(9), dp(2), 0)
        })
        addView(TextView(context).apply {
            text = result.subtitle
            textSize = 11f
            maxLines = 1
            setTextColor(Palette.muted)
            setPadding(dp(2), dp(2), dp(2), 0)
        })

        val metadata = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metadata.addView(TextView(context).apply {
            text = result.sourceLabel
            textSize = 10f
            setTextColor(if (result.source == GalleryResultSource.G2D_VERIFIED) Palette.lavender else Palette.blue)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(
                if (result.source == GalleryResultSource.G2D_VERIFIED) Palette.lavenderWash else Palette.blueWash,
                Color.TRANSPARENT,
                999f,
            )
            setPadding(dp(7), dp(4), dp(7), dp(4))
        })
        metadata.addView(TextView(context).apply {
            text = result.scoreLabel
            textSize = 10f
            setTextColor(Palette.ink)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(metadata, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
    }

    private fun sectionEyebrow(value: String): TextView = TextView(context).apply {
        text = value
        textSize = 11f
        setTextColor(Palette.mintDark)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.06f
    }

    private fun primaryButton(label: String, enabled: Boolean, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        isEnabled = enabled
        setTextColor(if (enabled) Color.WHITE else Palette.muted)
        setTypeface(typeface, Typeface.BOLD)
        backgroundTintList = null
        background = rounded(
            if (enabled) Palette.blue else Palette.surface,
            if (enabled) Palette.blue else Palette.stroke,
            12f,
        )
        stateListAnimator = null
        contentDescription = label
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, stroke: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun space(heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
