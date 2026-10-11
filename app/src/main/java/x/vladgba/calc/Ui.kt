package x.vladgba.calc

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.os.Build
import android.view.View
import android.view.WindowInsets

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.dpi(v: Int): Int = dp(v.toFloat()).toInt()
fun Context.color(id: Int): Int = getColor(id)

/** Rounded, filled background with a touch ripple. */
fun Context.roundedBg(fill: Int, radiusDp: Float): Drawable {
    val shape = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp)
    }
    val mask = GradientDrawable().apply {
        setColor(0xFFFFFFFF.toInt())
        cornerRadius = dp(radiusDp)
    }
    return RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), shape, mask)
}

fun View.onLongPress(action: () -> Unit) = setOnLongClickListener { action(); true }

/**
 * Pads this view by the system bar insets (needed because targetSdk 35 forces edge-to-edge on Android 15+).
 * [top] = false for screens whose action bar already sits below the status bar.
 */
fun View.padForSystemBars(top: Boolean = true) {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { v, insets ->
        val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime()) // keyboard: adjustResize no longer works edge-to-edge
            listOf(i.left, i.top, i.right, maxOf(i.bottom, ime.bottom))
        } else {
            @Suppress("DEPRECATION")
            listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        v.setPadding(base[0] + l, base[1] + if (top) t else 0, base[2] + r, base[3] + b)
        insets
    }
    requestApplyInsets()
}
