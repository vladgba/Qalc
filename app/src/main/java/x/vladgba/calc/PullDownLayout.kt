package x.vladgba.calc

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Vertical LinearLayout that detects a downward drag anywhere on it (even over the EditText)
 * and reports it, so the history panel can follow the finger.
 */
class PullDownLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {

    interface Listener {
        fun onPull(dy: Float)
        fun onPullEnd(dy: Float, velocityY: Float)
    }

    var listener: Listener? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    init { orientation = VERTICAL }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (dy > slop * 2 && dy > abs(dx) * 1.5f) {
                    dragging = true
                    downY += slop * 2 // avoid a jump when the panel starts following
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return ev.actionMasked == MotionEvent.ACTION_DOWN
        tracker?.addMovement(ev)
        val dy = ev.y - downY
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> listener?.onPull(dy.coerceAtLeast(0f))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.computeCurrentVelocity(1000)
                listener?.onPullEnd(dy.coerceAtLeast(0f), tracker?.yVelocity ?: 0f)
                tracker?.recycle(); tracker = null
                dragging = false
            }
        }
        return true
    }
}
