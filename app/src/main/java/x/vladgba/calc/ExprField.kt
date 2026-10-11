package x.vladgba.calc

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText

/** The calculator's expression field: editable with a cursor, but never opens the soft keyboard. */
class ExprField @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    EditText(ctx, attrs, android.R.attr.editTextStyle) {

    var onSelection: ((Int, Int) -> Unit)? = null

    init {
        showSoftInputOnFocus = false
    }

    // Belt and braces: some keyboards/OEM builds ignore showSoftInputOnFocus on focus gain at startup.
    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (focused) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(windowToken, 0)
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelection?.invoke(selStart, selEnd)
    }
}
