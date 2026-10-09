package dev.qalc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import dev.qalc.engine.Calculator
import dev.qalc.engine.Node
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Plots y = f(x) curves. Drag to pan, pinch to zoom, tap to trace values, double-tap to reset.
 */
class GraphView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    class Curve(val node: Node, val color: Int)

    var calc: Calculator = Calculator()
    var curves: List<Curve> = emptyList()
        set(v) { field = v; invalidate() }

    // View state: world point at the view centre, and pixels per world unit.
    private var cx = 0.0
    private var cy = 0.0
    private var ppu = 0.0
    private var traceX: Double? = null
    private val vars = HashMap<String, Double>(1)

    private val gridPaint = Paint().apply { color = ctx.color(R.color.key_digit); strokeWidth = ctx.dp(1f) }
    private val axisPaint = Paint().apply { color = ctx.color(R.color.text_dim); strokeWidth = ctx.dp(1.5f) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.color(R.color.text_dim); textSize = ctx.dp(11f) }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(2.5f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tracePaint = Paint().apply { color = ctx.color(R.color.text_faint); strokeWidth = ctx.dp(1f) }
    private val traceText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dp(13f); isFakeBoldText = true }
    private val path = Path()

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            // Zoom around the fingers' focus point.
            val fx = toWorldX(d.focusX); val fy = toWorldY(d.focusY)
            ppu = (ppu * d.scaleFactor).coerceIn(1e-6, 1e9)
            cx += fx - toWorldX(d.focusX)
            cy += fy - toWorldY(d.focusY)
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            cx += dx / ppu
            cy -= dy / ppu
            invalidate()
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val x = toWorldX(e.x)
            traceX = if (traceX != null && abs((traceX!! - x) * ppu) < ctx.dp(24f)) null else x
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { resetView(); return true }
    })

    fun resetView() {
        cx = 0.0; cy = 0.0
        ppu = if (width > 0) width / 20.0 else 0.0 // x from −10 to 10
        traceX = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (ppu == 0.0) resetView()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(ev)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(ev)
        return true
    }

    private fun toWorldX(px: Float) = cx + (px - width / 2.0) / ppu
    private fun toWorldY(py: Float) = cy - (py - height / 2.0) / ppu
    private fun toScreenX(x: Double) = (width / 2.0 + (x - cx) * ppu).toFloat()
    private fun toScreenY(y: Double) = (height / 2.0 - (y - cy) * ppu).toFloat()

    private fun eval(c: Curve, x: Double): Double {
        vars["x"] = x
        return calc.evalAt(c.node, vars)
    }

    override fun onDraw(canvas: Canvas) {
        if (ppu == 0.0) return
        val w = width.toFloat(); val h = height.toFloat()
        drawGrid(canvas, w, h)

        val stepPx = 1.5f
        for (c in curves) {
            curvePaint.color = c.color
            path.reset()
            var penDown = false
            var prevY = 0f
            var px = 0f
            while (px <= w) {
                val y = eval(c, toWorldX(px))
                if (!y.isFinite()) { penDown = false; px += stepPx; continue }
                val sy = toScreenY(y).coerceIn(-h * 4, h * 5)
                // Break the line across asymptotes (huge jump between neighbouring samples).
                if (penDown && abs(sy - prevY) > h * 1.5f) penDown = false
                if (penDown) path.lineTo(px, sy) else path.moveTo(px, sy)
                penDown = true
                prevY = sy
                px += stepPx
            }
            canvas.drawPath(path, curvePaint)
        }

        traceX?.let { tx -> drawTrace(canvas, tx, h) }
    }

    private fun niceStep(): Double {
        val raw = ctxDp(90f) / ppu
        val mag = 10.0.pow(floor(log10(raw)))
        return listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
    }

    private fun ctxDp(v: Float) = context.dp(v).toDouble()

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        val step = niceStep()
        val x0 = toWorldX(0f); val x1 = toWorldX(w)
        val y0 = toWorldY(h); val y1 = toWorldY(0f)
        val axisX = toScreenX(0.0).coerceIn(0f, w)          // where the y-axis is drawn
        val axisY = toScreenY(0.0).coerceIn(0f, h)          // where the x-axis is drawn
        val pad = context.dp(3f)

        var k = ceil(x0 / step)
        while (k * step <= x1) {
            val sx = toScreenX(k * step)
            canvas.drawLine(sx, 0f, sx, h, gridPaint)
            if (k != 0.0) {
                val label = calc.format(k * step)
                val ly = (axisY + labelPaint.textSize + pad).coerceAtMost(h - pad)
                canvas.drawText(label, sx + pad, ly, labelPaint)
            }
            k += 1
        }
        k = ceil(y0 / step)
        while (k * step <= y1) {
            val sy = toScreenY(k * step)
            canvas.drawLine(0f, sy, w, sy, gridPaint)
            if (k != 0.0) {
                val label = calc.format(k * step)
                val lw = labelPaint.measureText(label)
                val lx = (axisX + pad).coerceAtMost(w - lw - pad)
                canvas.drawText(label, lx, sy - pad, labelPaint)
            }
            k += 1
        }
        canvas.drawLine(0f, toScreenY(0.0), w, toScreenY(0.0), axisPaint)
        canvas.drawLine(toScreenX(0.0), 0f, toScreenX(0.0), h, axisPaint)
        canvas.drawText("0", toScreenX(0.0) + pad, toScreenY(0.0) + labelPaint.textSize + pad, labelPaint)
    }

    private fun drawTrace(canvas: Canvas, tx: Double, h: Float) {
        val sx = toScreenX(tx)
        canvas.drawLine(sx, 0f, sx, h, tracePaint)
        var line = 0
        traceText.color = context.color(R.color.text)
        canvas.drawText("x = ${calc.format(tx)}", context.dp(10f), context.dp(20f), traceText)
        for (c in curves) {
            val y = eval(c, tx)
            line++
            traceText.color = c.color
            val text = if (y.isFinite()) "y = ${calc.format(y)}" else "y undefined"
            canvas.drawText(text, context.dp(10f), context.dp(20f) + line * context.dp(18f), traceText)
            if (y.isFinite()) {
                dotPaint.color = c.color
                canvas.drawCircle(sx, toScreenY(y), context.dp(5f), dotPaint)
            }
        }
    }
}
