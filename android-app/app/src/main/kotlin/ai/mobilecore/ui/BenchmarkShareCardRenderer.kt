package ai.mobilecore.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BenchmarkShareCardRenderer {
    private const val WIDTH = 1080
    private const val HEIGHT = 1350

    fun render(context: Context, snapshot: BenchmarkResultSnapshot, insight: ResultInsight): File {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(
            0f,
            0f,
            WIDTH.toFloat(),
            HEIGHT.toFloat(),
            intArrayOf(Color.WHITE, TuiMaShareTheme.mintWash, TuiMaShareTheme.blueWash),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null

        drawText(canvas, paint, "TuiMa 推嘛", 72f, 82f, 62f, TuiMaShareTheme.deepInk, Typeface.BOLD)
        drawText(canvas, paint, "本机 AI 性能成绩", 72f, 145f, 30f, TuiMaShareTheme.muted, Typeface.NORMAL)

        paint.color = 0xEFFFFFFF.toInt()
        canvas.drawRoundRect(RectF(56f, 205f, 1024f, 1180f), 44f, 44f, paint)

        drawText(canvas, paint, NumberFormat.getIntegerInstance(Locale.US).format(snapshot.headlineScore), 96f, 350f, 112f, TuiMaShareTheme.blue, Typeface.BOLD)
        drawText(canvas, paint, "TuiMa", 96f, 410f, 40f, TuiMaShareTheme.deepInk, Typeface.BOLD)
        drawText(canvas, paint, "标准分 ${snapshot.canonicalScore} / 1000", 96f, 476f, 34f, TuiMaShareTheme.mintDark, Typeface.BOLD)

        drawText(canvas, paint, "本机 AI 能力：${insight.rating}", 96f, 564f, 38f, TuiMaShareTheme.deepInk, Typeface.BOLD)
        drawText(canvas, paint, insight.summary, 96f, 615f, 27f, TuiMaShareTheme.ink, Typeface.NORMAL)
        drawText(canvas, paint, insight.recommendation, 96f, 664f, 24f, TuiMaShareTheme.muted, Typeface.NORMAL, maxWidth = 880f)

        val accents = intArrayOf(TuiMaShareTheme.mint, TuiMaShareTheme.sky, TuiMaShareTheme.lavender, TuiMaShareTheme.blue, TuiMaShareTheme.mintDark)
        var y = 760f
        snapshot.dimensions.forEachIndexed { index, dimension ->
            drawText(canvas, paint, dimension.label, 96f, y, 27f, TuiMaShareTheme.ink, Typeface.BOLD)
            drawText(canvas, paint, "${dimension.value} / ${dimension.maximum}", 824f, y, 26f, accents[index], Typeface.BOLD)
            paint.color = 0x147A89A2
            canvas.drawRoundRect(RectF(96f, y + 18f, 924f, y + 34f), 8f, 8f, paint)
            paint.color = accents[index]
            canvas.drawRoundRect(RectF(96f, y + 18f, 96f + 828f * dimension.ratio.toFloat().coerceIn(0f, 1f), y + 34f), 8f, 8f, paint)
            y += 78f
        }

        val profileLabel = when (snapshot.profile) {
            "standard" -> "标准模式"
            "stress" -> "压力模式（不入榜）"
            else -> "快速模式（仅预览）"
        }
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(snapshot.createdAtMs))
        drawText(
            canvas,
            paint,
            "${snapshot.deviceName} · $profileLabel · ${snapshot.executionLabel}",
            72f,
            1235f,
            24f,
            TuiMaShareTheme.muted,
            Typeface.NORMAL,
            maxWidth = 936f
        )
        drawText(canvas, paint, date, 72f, 1272f, 23f, TuiMaShareTheme.muted, Typeface.NORMAL)
        drawText(canvas, paint, "✓ 本机完成 · 原始内容不上传", 72f, 1320f, 26f, TuiMaShareTheme.mintDark, Typeface.BOLD)

        val directory = File(context.cacheDir, "shared-results").apply { mkdirs() }
        val file = File(directory, "tuima-${snapshot.runId.ifBlank { snapshot.createdAtMs.toString() }}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }
        bitmap.recycle()
        return file
    }

    private fun drawText(
        canvas: Canvas,
        paint: Paint,
        text: String,
        x: Float,
        baseline: Float,
        size: Float,
        color: Int,
        style: Int,
        maxWidth: Float? = null
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.typeface = Typeface.create(Typeface.DEFAULT, style)
        val output = if (maxWidth != null && paint.measureText(text) > maxWidth) {
            val suffix = "…"
            var candidate = text
            while (candidate.isNotEmpty() && paint.measureText(candidate + suffix) > maxWidth) candidate = candidate.dropLast(1)
            candidate + suffix
        } else text
        canvas.drawText(output, x, baseline, paint)
    }
}
