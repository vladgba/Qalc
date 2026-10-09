package dev.qalc

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.qalc.engine.CalcException
import dev.qalc.engine.Calculator
import dev.qalc.engine.Node
import org.json.JSONArray

/**
 * Plots one or more y = f(x) functions. Accepts "sin(x)", "y = x^2", "f(x) = …", and user formulas like "hyp(x, 3)".
 */
class GraphActivity : Activity() {
    private lateinit var graph: GraphView
    private lateinit var fnList: LinearLayout
    private val calc = Calculator()
    private var lastFocused: EditText? = null

    private class Row(val view: View, val input: EditText, val error: TextView, var color: Int)

    private val rows = ArrayList<Row>()

    private val palette = intArrayOf(
        0xFF2F6FE0.toInt(), 0xFFE0452F.toInt(), 0xFF1E9E5A.toInt(),
        0xFF9B44D6.toInt(), 0xFFE08A00.toInt(), 0xFF00A3B4.toInt(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_graph)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        (findViewById<View>(android.R.id.content) as? ViewGroup)?.getChildAt(0)?.padForSystemBars(top = false)

        graph = findViewById(R.id.graph)
        fnList = findViewById(R.id.fn_list)

        val prefs = FormulaStore.prefs(this)
        calc.degrees = false // graphs are always in radians, so sin(x) has its usual shape
        calc.ans = prefs.getString("ans", null)?.toDoubleOrNull() ?: 0.0
        calc.setFormulas(FormulaStore.load(this))
        calc.stepLimit = 20_000 // per point: keeps panning smooth even with recursive formulas
        graph.calc = calc

        buildSymbols()
        findViewById<Button>(R.id.btn_add_fn).setOnClickListener { addRow("").input.requestFocus() }

        val saved = loadSaved().toMutableList()
        intent.getStringExtra(EXTRA_EXPR)?.takeIf { it.isNotBlank() }?.let { e ->
            saved.remove(e)
            saved.add(0, e)
        }
        if (saved.isEmpty()) saved += "sin(x)"
        saved.forEach { addRow(it) }
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.graph, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_reset -> { graph.resetView(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        super.onPause()
        val list = rows.map { it.input.text.toString() }.filter { it.isNotBlank() }
        FormulaStore.prefs(this).edit().putString(KEY_SAVED, JSONArray(list).toString()).apply()
    }

    private fun loadSaved(): List<String> {
        val raw = FormulaStore.prefs(this).getString(KEY_SAVED, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun addRow(initial: String): Row {
        val curveColor = palette[rows.size % palette.size]
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(curveColor) }
        }
        line.addView(dot, LinearLayout.LayoutParams(dpi(14), dpi(14)).apply { marginEnd = dpi(10) })
        line.addView(TextView(this).apply {
            text = "y ="
            textSize = 18f
            setTextColor(color(R.color.text_dim))
        })
        val et = EditText(this).apply {
            setText(initial)
            hint = getString(R.string.graph_hint)
            textSize = 18f
            isSingleLine = true
            typeface = android.graphics.Typeface.MONOSPACE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setOnFocusChangeListener { v, has -> if (has) lastFocused = v as EditText }
        }
        line.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val remove = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = "✕"
            setTextColor(color(R.color.text_dim))
            minWidth = 0; minimumWidth = 0
        }
        line.addView(remove, LinearLayout.LayoutParams(dpi(44), dpi(44)))
        container.addView(line)
        val err = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.error))
            setPadding(dpi(24), 0, 0, dpi(4))
            visibility = View.GONE
        }
        container.addView(err)
        fnList.addView(container)

        val row = Row(container, et, err, curveColor)
        rows += row
        MathStyle.attach(et)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        remove.setOnClickListener {
            rows.remove(row)
            fnList.removeView(container)
            if (lastFocused == et) lastFocused = null
            refresh()
        }
        return row
    }

    /** Strips "y =" or "f(x) =" prefixes. */
    private fun body(text: String): String =
        text.replaceFirst(Regex("""^\s*(?:y|[A-Za-z_]\w*\s*\(\s*x\s*\))\s*=(?!=)"""), "").trim()

    private fun refresh() {
        val curves = ArrayList<GraphView.Curve>()
        for (r in rows) {
            val b = body(r.input.text.toString())
            var msg: String? = null
            if (b.isNotEmpty()) {
                try {
                    val node: Node = calc.compile(b)
                    calc.check(node, setOf("x"))
                    curves += GraphView.Curve(node, r.color)
                } catch (e: CalcException) {
                    msg = e.message
                }
            }
            r.error.text = msg ?: ""
            r.error.visibility = if (msg == null) View.GONE else View.VISIBLE
        }
        graph.curves = curves
    }

    private fun buildSymbols() {
        val bar = findViewById<LinearLayout>(R.id.symbols)
        val items = listOf("x", "^", "(", ")", "×", "÷", "−", "√", "π", ",", "sin(", "cos(", "tan(", "ln(", "log(", "abs(", "exp(") +
            FormulaStore.load(this).filter { it.params.size == 1 }.map { "${it.name}(" }
        for (s in items) {
            val b = TextView(this).apply {
                text = if (s == "(") s else s.removeSuffix("(")
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.text))
                background = roundedBg(color(if (s.endsWith("(") && s != "(") R.color.key_op else R.color.key_digit), 50f)
                setPadding(dpi(14), dpi(8), dpi(14), dpi(8))
                isClickable = true
                setOnClickListener { insert(s) }
            }
            bar.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dpi(6)
            })
        }
    }

    private fun insert(s: String) {
        val et = lastFocused ?: rows.lastOrNull()?.input ?: addRow("").input
        et.requestFocus()
        val a = minOf(et.selectionStart, et.selectionEnd).coerceAtLeast(0)
        val b = maxOf(et.selectionStart, et.selectionEnd).coerceAtLeast(0)
        et.text.replace(a, b, s)
    }

    companion object {
        const val EXTRA_EXPR = "expr"
        private const val KEY_SAVED = "graph_fns"
    }
}
