package com.irozar.ipdfmaster.pdfeditorspike

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class AnnotationCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { NONE, PEN, HIGHLIGHTER, SHAPE, SIGNATURE }

    data class Stroke(
        val mode: Mode,
        val points: MutableList<Pair<Float, Float>>,
        val color: Int,
        val width: Float
    )

    var mode: Mode = Mode.NONE
        set(value) {
            field = value
            visibility = if (value == Mode.NONE) GONE else VISIBLE
        }

    val strokes = mutableListOf<Stroke>()
    private var current: Stroke? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        visibility = GONE
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun clearAll() {
        strokes.clear()
        current = null
        invalidate()
    }

    fun undoLast(): Boolean {
        if (strokes.isEmpty()) return false
        strokes.removeAt(strokes.lastIndex)
        invalidate()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == Mode.NONE) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val width = if (mode == Mode.HIGHLIGHTER) 22f else 5f
                val color = when (mode) {
                    Mode.HIGHLIGHTER -> 0x88FFD54F.toInt()
                    Mode.SHAPE -> 0xFF2563EB.toInt()
                    else -> 0xFF111827.toInt()
                }
                current = Stroke(mode, mutableListOf(event.x to event.y), color, width)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                current?.points?.add(event.x to event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { strokes.add(it) }
                current = null
                parent.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokes.forEach { drawStroke(canvas, it) }
        current?.let { drawStroke(canvas, it) }
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        paint.color = stroke.color
        paint.strokeWidth = stroke.width
        paint.alpha = Color.alpha(stroke.color)
        if (stroke.mode == Mode.SHAPE && stroke.points.size > 1) {
            val first = stroke.points.first()
            val last = stroke.points.last()
            canvas.drawRect(first.first, first.second, last.first, last.second, paint)
            return
        }
        val path = Path()
        stroke.points.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(point.first, point.second) else path.lineTo(point.first, point.second)
        }
        canvas.drawPath(path, paint)
    }
}
