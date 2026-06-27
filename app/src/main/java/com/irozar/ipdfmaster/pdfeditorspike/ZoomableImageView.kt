package com.irozar.ipdfmaster.pdfeditorspike

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    var onTapBitmap: ((bx: Float, by: Float) -> Unit)? = null
    var onUserScroll: (() -> Unit)? = null   // used to dismiss the edit overlay (#14)

    private val m = Matrix()
    private val scroller = OverScroller(context)
    private var minScale = 1f
    private val maxScaleFactor = 5f       // how far past fit-width the user may zoom
    private var bmpW = 0
    private var bmpH = 0

    // Visible region insets so the page is never hidden behind the top / bottom bars.
    private var topInset = 0f
    private var bottomInset = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                var f = d.scaleFactor
                val current = currentScale()
                val target = current * f
                val maxScale = minScale * maxScaleFactor
                // Never zoom out below fit-width, never zoom in past the max.
                if (target < minScale) f = minScale / current
                else if (target > maxScale) f = maxScale / current
                m.postScale(f, f, d.focusX, d.focusY)
                fixTranslation()
                imageMatrix = m
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                m.postTranslate(-dx, -dy)
                fixTranslation()
                imageMatrix = m
                onUserScroll?.invoke()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val v = FloatArray(9)
                m.getValues(v)
                scroller.forceFinished(true)
                scroller.fling(
                    v[Matrix.MTRANS_X].toInt(), v[Matrix.MTRANS_Y].toInt(),
                    vx.toInt(), vy.toInt(),
                    -bmpW * 4, bmpW * 4, -bmpH * 4, bmpH * 4
                )
                postInvalidateOnAnimation()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val p = screenToBitmap(e.x, e.y)
                onTapBitmap?.invoke(p.x, p.y)
                return true
            }
        })

    /** Heights of the overlaying top / bottom bars, so the page fits between them. */
    fun setInsets(top: Int, bottom: Int) {
        topInset = top.toFloat()
        bottomInset = bottom.toFloat()
        if (bmpW != 0) post { fitWidth() }
    }

    fun setPageBitmap(bmp: Bitmap) {
        setImageBitmap(bmp)
        bmpW = bmp.width
        bmpH = bmp.height
        post { fitWidth() }   // #18 fit-width as the default view
    }

    private fun fitWidth() {
        if (bmpW == 0 || width == 0) return
        minScale = width.toFloat() / bmpW
        m.reset()
        m.postScale(minScale, minScale)
        // Drop the top of the page just below the top bar, then clamp into the visible area.
        m.postTranslate(0f, topInset)
        fixTranslation()
        imageMatrix = m
    }

    /**
     * Keeps the page inside the visible area (between the top and bottom bars).
     * When the content is smaller than the region it is centered; when larger
     * (i.e. zoomed in or a tall page) its edges are clamped so blank space never
     * appears and the page never slides behind a bar.
     */
    private fun fixTranslation() {
        if (bmpW == 0) return
        val v = FloatArray(9)
        m.getValues(v)
        val scale = v[Matrix.MSCALE_X]
        val contentW = bmpW * scale
        val contentH = bmpH * scale
        val fixX = getFixTrans(v[Matrix.MTRANS_X], 0f, width.toFloat(), contentW)
        val fixY = getFixTrans(v[Matrix.MTRANS_Y], topInset, height - bottomInset, contentH)
        if (fixX != 0f || fixY != 0f) m.postTranslate(fixX, fixY)
    }

    private fun getFixTrans(trans: Float, regionStart: Float, regionEnd: Float, contentSize: Float): Float {
        val regionSize = regionEnd - regionStart
        val minTrans: Float
        val maxTrans: Float
        if (contentSize <= regionSize) {
            // Smaller than the visible region: center it.
            minTrans = regionStart + (regionSize - contentSize) / 2f
            maxTrans = minTrans
        } else {
            // Larger than the region: clamp edges to the region bounds.
            maxTrans = regionStart
            minTrans = regionEnd - contentSize
        }
        return when {
            trans < minTrans -> minTrans - trans
            trans > maxTrans -> maxTrans - trans
            else -> 0f
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val v = FloatArray(9)
            m.getValues(v)
            m.postTranslate(scroller.currX - v[Matrix.MTRANS_X], scroller.currY - v[Matrix.MTRANS_Y])
            fixTranslation()
            imageMatrix = m
            postInvalidateOnAnimation()
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    fun currentScale(): Float {
        val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X]
    }

    fun screenToBitmap(sx: Float, sy: Float): PointF {
        val inv = Matrix()
        m.invert(inv)
        val pts = floatArrayOf(sx, sy)
        inv.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    /** Map a bitmap-space rect to current screen coordinates (to place the edit overlay). */
    fun bitmapToScreenRect(r: RectF): RectF {
        val out = RectF(r)
        m.mapRect(out)
        return out
    }

    init { scaleType = ScaleType.MATRIX }
}
