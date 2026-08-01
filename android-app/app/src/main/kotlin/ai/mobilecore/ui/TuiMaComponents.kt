package ai.mobilecore.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.min

/** Package-wide bridge from semantic component colors to the active app theme. */
internal object Palette {
    val background get() = TuiMaTheme.background
    val surface get() = TuiMaTheme.surface
    val deepInk get() = TuiMaTheme.deepInk
    val ink get() = TuiMaTheme.ink
    val muted get() = TuiMaTheme.muted
    val stroke get() = TuiMaTheme.stroke
    val mint get() = TuiMaTheme.mint
    val mintDark get() = TuiMaTheme.mintDark
    val mintPale get() = TuiMaTheme.mintPale
    val sky get() = TuiMaTheme.sky
    val blue get() = TuiMaTheme.blue
    val lavender get() = TuiMaTheme.lavender
    val amber get() = TuiMaTheme.amber
    val danger get() = TuiMaTheme.danger
    val mintWash get() = TuiMaTheme.mintWash
    val blueWash get() = TuiMaTheme.blueWash
    val lavenderWash get() = TuiMaTheme.lavenderWash
    val amberWash get() = TuiMaTheme.amberWash
    val dangerWash get() = TuiMaTheme.dangerWash
}

/** Allocation-free circular progress primitive used by the benchmark screen. */
class TuiMaCircularProgressView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x197A89A2
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TuiMaTheme.mintDark
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            contentDescription = "跑分总进度 ${field}%"
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = min(width, height) * 0.085f
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        val inset = stroke / 2f + 2f
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(bounds, -90f, 360f, false, trackPaint)
        canvas.drawArc(bounds, -90f, 360f * progress / 100f, false, progressPaint)
    }
}

fun formatRemainingDuration(milliseconds: Long?): String {
    if (milliseconds == null || milliseconds <= 0L) return "正在估算"
    val seconds = (milliseconds + 999L) / 1000L
    return when {
        seconds < 60L -> "约 ${seconds} 秒"
        else -> "约 ${seconds / 60L} 分 ${seconds % 60L} 秒"
    }
}

/** Compact TuiMa brand mark used only on the home surface. */
internal class PushBoxMarkView(context: Context) : View(context) {
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
        paint.shader = LinearGradient(
            0f,
            body.top,
            body.right,
            body.bottom,
            0xFF7EE6C1.toInt(),
            0xFF43D1E8.toInt(),
            Shader.TileMode.CLAMP
        )
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
        paint.shader = LinearGradient(
            cube.left,
            cube.top,
            cube.right,
            cube.bottom,
            0xFF6B8CFF.toInt(),
            0xFFB69CFF.toInt(),
            Shader.TileMode.CLAMP
        )
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

/** Small semantic icon primitive shared by headers, cards, and bottom navigation. */
internal class IconBadgeView(
    context: Context,
    private val kind: String,
    private val accent: Int
) : View(context) {
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
            "home" -> drawHome(canvas, w, h)
            "person" -> drawPerson(canvas, w, h)
            else -> drawCube(canvas, w, h)
        }
    }

    private fun drawHome(canvas: Canvas, w: Float, h: Float) {
        val roof = Path().apply {
            moveTo(w * 0.24f, h * 0.48f)
            lineTo(w * 0.50f, h * 0.24f)
            lineTo(w * 0.76f, h * 0.48f)
        }
        canvas.drawPath(roof, strokePaint)
        canvas.drawRoundRect(RectF(w * 0.30f, h * 0.45f, w * 0.70f, h * 0.76f), w * 0.04f, w * 0.04f, strokePaint)
        canvas.drawLine(w * 0.46f, h * 0.76f, w * 0.46f, h * 0.60f, strokePaint)
        canvas.drawLine(w * 0.58f, h * 0.76f, w * 0.58f, h * 0.60f, strokePaint)
    }

    private fun drawPerson(canvas: Canvas, w: Float, h: Float) {
        canvas.drawCircle(w * 0.50f, h * 0.37f, w * 0.12f, strokePaint)
        canvas.drawArc(RectF(w * 0.27f, h * 0.50f, w * 0.73f, h * 0.83f), 195f, 150f, false, strokePaint)
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
