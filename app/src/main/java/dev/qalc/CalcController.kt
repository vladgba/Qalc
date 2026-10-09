package dev.qalc

import android.content.Context
import dev.qalc.engine.CalcException
import dev.qalc.engine.Calculator
import dev.qalc.engine.Formula

/**
 * Keypad input logic and calculator state, cursor-aware: keys insert at the cursor / replace the selection.
 * UI-independent apart from SharedPreferences.
 */
class CalcController(private val ctx: Context, private val onChange: () -> Unit) {
    private val prefs = FormulaStore.prefs(ctx)
    private val calc = Calculator()

    var expr: String = prefs.getString("expr", "") ?: ""
        private set
    var selStart: Int = expr.length
        private set
    var selEnd: Int = expr.length
        private set
    var previous: String = prefs.getString("previous", "") ?: ""   // shown above after "="
        private set
    var error: String? = null
        private set
    var justEvaluated: Boolean = prefs.getBoolean("justEvaluated", false)
        private set
    var degrees: Boolean = prefs.getBoolean("deg", false)
        private set
    var formulas: List<Formula> = emptyList()
        private set

    /** Like Xlythe: the delete key becomes "AC" after "=" or on an error. */
    val showClear: Boolean
        get() = justEvaluated || error != null

    /** True when the expression has a free variable x, i.e. it is a function to plot rather than a value. */
    val isFunctionOfX: Boolean
        get() = Regex("""(?<![A-Za-z_])x(?![A-Za-z0-9_])""").containsMatchIn(expr) // "2x" counts; "max", "x2" don't

    val preview: String
        get() = when {
            expr.isBlank() || justEvaluated || isPlainNumber(expr) -> ""
            isFunctionOfX -> "y = f(x)  ·  press = to plot"
            else -> calc.tryEvaluate(expr) ?: ""
        }

    init {
        calc.degrees = degrees
        calc.ans = prefs.getString("ans", null)?.toDoubleOrNull() ?: 0.0
        reloadFormulas()
    }

    fun reloadFormulas() {
        formulas = FormulaStore.load(ctx)
        calc.setFormulas(formulas)
    }

    // ---------- text & cursor sync from the EditText ----------

    /** The user moved the cursor / selected text. Editing anywhere but the end leaves "result" mode. */
    fun select(start: Int, end: Int) {
        val s = minOf(start, end).coerceIn(0, expr.length)
        val e = maxOf(start, end).coerceIn(0, expr.length)
        selStart = s; selEnd = e
        if (justEvaluated && (s != expr.length || e != s)) {
            justEvaluated = false
            onChange()
        }
    }

    /** The user typed/pasted directly into the field (hardware keyboard, paste). */
    fun textEdited(text: String, cursor: Int) {
        if (text == expr) return
        justEvaluated = false
        previous = ""
        expr = text
        selStart = cursor.coerceIn(0, text.length); selEnd = selStart
        error = null
        save()
        onChange()
    }

    private val before get() = expr.substring(0, selStart)
    private val after get() = expr.substring(selEnd)

    private fun insert(s: String) = replace(before + s, after)

    /** Sets text as [left] + [right] with the cursor between them. */
    private fun replace(left: String, right: String) {
        expr = left + right
        selStart = left.length; selEnd = selStart
        error = null
        save()
        onChange()
    }

    // ---------- keys ----------

    private val binaryOps = "+−×÷^"

    fun digit(s: String) {
        if (justEvaluated) startFresh()
        if (s == ".") {
            val left = Regex("""[\d.]*$""").find(before)?.value ?: ""
            val right = Regex("""^[\d.]*""").find(after)?.value ?: ""
            if ('.' in left || '.' in right) return
            if (left.isEmpty()) { insert("0."); return }
        }
        insert(s)
    }

    /** Text that starts a new operand: function call "sin(", constant "π", "(" … */
    fun operand(s: String) {
        if (justEvaluated) startFresh()
        insert(s)
    }

    /** Postfix / separators that continue the current result: ")", "%", "!", "," */
    fun suffix(s: String) {
        continueFromResult()
        insert(s)
    }

    fun op(o: String) {
        continueFromResult()
        val left = before
        if (left.isEmpty()) {
            if (o == "−") insert(o)
            return
        }
        val last = left.last()
        when {
            last in "×÷^" && o == "−" -> insert(o)                         // 2×−3
            last in binaryOps -> {
                val trimmed = left.trimEnd { it in binaryOps }            // replace trailing operator(s)
                if (trimmed.isNotEmpty()) replace(trimmed + o, after)
            }
            last == '(' && o != "−" -> return
            else -> insert(o)
        }
    }

    fun delete() {
        justEvaluated = false
        if (selStart != selEnd) { replace(before, after); return }
        val left = before
        if (left.isEmpty()) return
        val fn = Regex("""[A-Za-z_]\w*\($""").find(left)
        replace(if (fn != null) left.substring(0, fn.range.first) else left.dropLast(1), after)
    }

    fun clear() {
        previous = ""
        justEvaluated = false
        replace("", "")
    }

    fun equals() {
        if (expr.isBlank()) return
        try {
            val v = calc.evaluate(expr)
            calc.ans = v
            prefs.edit().putString("ans", v.toString()).apply()
            val result = calc.format(v)
            History.add(ctx, expr, result)
            previous = expr
            justEvaluated = true
            replace(result, "")
        } catch (e: CalcException) {
            error = e.message
            onChange()
        } catch (_: StackOverflowError) {
            error = "Too deep"
            onChange()
        }
    }

    fun toggleDegrees() {
        degrees = !degrees
        calc.degrees = degrees
        prefs.edit().putBoolean("deg", degrees).apply()
        onChange()
    }

    /** Inserts a call to a user formula: "name(" or just "name" for constants. */
    /** Inserts a past result at the cursor. */
    fun insertResult(result: String) = operand(result)

    /** Replaces the whole expression with a past one. */
    fun restore(expression: String) {
        previous = ""
        justEvaluated = false
        replace(expression, "")
    }

    fun insertFormula(f: Formula) = operand(if (f.params.isEmpty()) f.name else "${f.name}(")

    fun insertFormulaByName(name: String) {
        formulas.firstOrNull { it.name == name }?.let { insertFormula(it) }
    }

    private fun startFresh() {
        previous = ""
        justEvaluated = false
        expr = ""
        selStart = 0; selEnd = 0
    }

    private fun continueFromResult() {
        if (justEvaluated) {
            justEvaluated = false
            previous = ""
            selStart = expr.length; selEnd = expr.length
        }
    }

    private fun save() {
        prefs.edit()
            .putString("expr", expr)
            .putString("previous", previous)
            .putBoolean("justEvaluated", justEvaluated)
            .apply()
    }

    private fun isPlainNumber(s: String) = s.replace('−', '-').toDoubleOrNull() != null
}
