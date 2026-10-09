package dev.qalc

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Minimal framework-only replacement for ViewPager: full-width pages in a HorizontalScrollView
 * that snap to the nearest page (or the next one on a fling).
 */
class PagerView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : HorizontalScrollView(ctx, attrs) {
    private val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private var startPage = 0
    private var flung = false

    var current = 0
        private set
    var onPageChanged: ((Int) -> Unit)? = null
    val pageCount get() = strip.childCount

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = true
        addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun addPage(v: View) {
        strip.addView(v, LinearLayout.LayoutParams(if (width > 0) width else 1, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw) return
        post {
            for (i in 0 until strip.childCount) strip.getChildAt(i).layoutParams.width = w
            strip.requestLayout()
            post { scrollTo(current * w, 0) }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            startPage = current
            flung = false
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.onTouchEvent(ev)
        val a = ev.actionMasked
        if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && !flung && width > 0) {
            val delta = scrollX - startPage * width
            val target = when {
                delta > width * 0.2f -> startPage + 1
                delta < -width * 0.2f -> startPage - 1
                else -> startPage
            }
            showPage(target)
        }
        return handled
    }

    /** Called by HorizontalScrollView on a fast swipe; snap one page in the fling direction instead. */
    override fun fling(velocityX: Int) {
        flung = true
        if (abs(velocityX) < 50) showPage(startPage)
        else showPage(if (velocityX > 0) startPage + 1 else startPage - 1)
    }

    fun showPage(page: Int, animate: Boolean = true) {
        val p = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val changed = p != current
        current = p
        if (animate) smoothScrollTo(p * width, 0) else scrollTo(p * width, 0)
        if (changed) onPageChanged?.invoke(p)
    }
}
