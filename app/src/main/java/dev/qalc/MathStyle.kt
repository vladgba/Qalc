package dev.qalc

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.SuperscriptSpan
import android.widget.EditText

/**
 * "Paper" style powers: `2^10` is drawn as 2¹⁰ — the caret is hidden and the exponent raised & shrunk.
 * The underlying text keeps the `^`, so editing and evaluation are unaffected.
 * While the exponent is still empty (`2^`) the caret stays visible so you can see you're typing a power.
 */
object MathStyle {
    private class HiddenSpan : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = 0
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
    }

    private class PowSize : RelativeSizeSpan(0.62f)
    private class PowRaise : SuperscriptSpan()

    fun styled(text: CharSequence): Spannable = SpannableString(text).also { apply(it) }

    /** Re-styles [s] in place. Safe to call on every edit. */
    fun apply(s: Spannable) {
        for (cls in listOf(HiddenSpan::class.java, PowSize::class.java, PowRaise::class.java)) {
            s.getSpans(0, s.length, cls).forEach { s.removeSpan(it) }
        }
        val str = s.toString()
        for (k in str.indices) {
            if (str[k] != '^') continue
            val end = operandEnd(str, k + 1)
            if (end <= k + 1) continue // exponent not typed yet: keep "^" visible
            s.setSpan(HiddenSpan(), k, k + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(PowRaise(), k + 1, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(PowSize(), k + 1, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Keeps an EditText styled as the user types. */
    fun attach(et: EditText) {
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) = apply(s)
        })
        apply(et.text)
    }

    /** End (exclusive) of the exponent operand starting at [start]; returns [start] if there is none. */
    private fun operandEnd(s: String, start: Int): Int {
        var i = start
        val n = s.length
        while (i < n && s[i] == ' ') i++
        if (i < n && s[i] in "-−+") i++
        if (i >= n) return start
        var end = when {
            s[i] == '(' -> matchParen(s, i)
            s[i] == '√' -> operandEnd(s, i + 1).let { if (it == i + 1) start else it }
            s[i].isDigit() || s[i] == '.' -> {
                var j = i
                while (j < n && (s[j].isDigit() || s[j] == '.')) j++
                j
            }
            s[i] == 'π' -> i + 1
            s[i].isLetter() || s[i] == '_' -> {
                var j = i
                while (j < n && (s[j].isLetterOrDigit() || s[j] == '_') && s[j] != 'π') j++
                if (j < n && s[j] == '(') matchParen(s, j) else j
            }
            else -> return start
        }
        if (end == start) return start
        while (end < n && s[end] in "!%") end++
        // Towers: 2^3^2 — the inner power belongs to the exponent.
        if (end < n && s[end] == '^') {
            val e2 = operandEnd(s, end + 1)
            if (e2 > end + 1) end = e2
        }
        return end
    }

    private fun matchParen(s: String, open: Int): Int {
        var depth = 0
        for (j in open until s.length) {
            if (s[j] == '(') depth++
            if (s[j] == ')') { depth--; if (depth == 0) return j + 1 }
        }
        return s.length // unclosed: runs to the end, like the auto-closing parser
    }
}
