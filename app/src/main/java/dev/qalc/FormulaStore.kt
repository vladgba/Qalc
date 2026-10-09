package dev.qalc

import android.content.Context
import dev.qalc.engine.CalcException
import dev.qalc.engine.Formula
import org.json.JSONArray

/** Persists user formulas as a JSON array of their source lines, e.g. ["hyp(a, b) = √(a^2 + b^2)"]. */
object FormulaStore {
    private const val PREFS = "qalc"
    private const val KEY = "formulas"

    val DEFAULTS = listOf(
        "hyp(a, b) = √(a^2 + b^2)",
        "bmi(kg, m) = kg ÷ m^2",
        "vat = 20",
        "gross(net) = net × (1 + vat%)",
        "fib(n) = if(n < 2, n, fib(n − 1) + fib(n − 2))",
    )

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Formula> {
        val raw = prefs(ctx).getString(KEY, null) ?: return DEFAULTS.map { Formula.parse(it) }
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull {
            try { Formula.parse(arr.getString(it)) } catch (_: CalcException) { null }
        }
    }

    fun save(ctx: Context, list: List<Formula>) {
        prefs(ctx).edit().putString(KEY, JSONArray(list.map { it.source }).toString()).apply()
    }

    /** Returns an error message, or null if [text] is a valid, non-clashing formula. */
    fun validate(text: String, existing: List<Formula>, original: Formula?): String? {
        val f = try { Formula.parse(text) } catch (e: CalcException) { return e.message }
        if (existing.any { it.name == f.name && it != original }) return "'${f.name}' already exists"
        return null
    }
}
