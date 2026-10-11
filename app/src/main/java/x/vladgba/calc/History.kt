package x.vladgba.calc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Past calculations, newest first, persisted in SharedPreferences. */
object History {
    data class Entry(val expr: String, val result: String)

    private const val KEY = "history"
    private const val MAX = 200

    fun load(ctx: Context): List<Entry> {
        val raw = FormulaStore.prefs(ctx).getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.getString("e"), o.getString("r"))
        }
    }

    fun add(ctx: Context, expr: String, result: String) {
        val list = load(ctx).toMutableList()
        if (list.firstOrNull()?.expr == expr) return // pressing "=" twice
        list.add(0, Entry(expr, result))
        save(ctx, list.take(MAX))
    }

    fun clear(ctx: Context) = save(ctx, emptyList())

    private fun save(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("e", it.expr).put("r", it.result)) }
        FormulaStore.prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
