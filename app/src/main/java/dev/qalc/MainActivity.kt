package dev.qalc

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.qalc.engine.Calculator
import dev.qalc.engine.Formula

/**
 * Calculator screen.
 *  - Display: editable expression (tap to move the cursor), pull down for history.
 *  - Bottom pager:  [My formulas]  ←  [Numpad]  →  [Functions]
 */
class MainActivity : Activity() {
    private lateinit var ctl: CalcController
    private lateinit var root: FrameLayout
    private lateinit var tvPrevious: TextView
    private lateinit var etExpr: ExprField
    private lateinit var tvPreview: TextView
    private lateinit var btnAngle: Button
    private lateinit var pager: PagerView
    private lateinit var tabs: LinearLayout
    private lateinit var formulasPage: LinearLayout
    private lateinit var delKey: TextView
    private lateinit var historyPanel: LinearLayout
    private lateinit var historyList: LinearLayout

    /** True while we push controller state into the EditText (so its callbacks don't echo back). */
    private var syncing = false
    private var historyOpen = false

    private enum class Kind { DIGIT, OP, FN, EQ }

    private class Key(
        val label: String,
        val kind: Kind,
        val action: CalcController.() -> Unit,
        val long: (CalcController.() -> Unit)? = null,
    )

    private val numpad: List<List<Key>> = listOf(
        listOf(
            Key("(", Kind.FN, { operand("(") }),
            Key(")", Kind.FN, { suffix(")") }),
            Key(",", Kind.FN, { suffix(",") }),
            Key("÷", Kind.OP, { op("÷") }),
        ),
        listOf(Key("7", Kind.DIGIT, { digit("7") }), Key("8", Kind.DIGIT, { digit("8") }), Key("9", Kind.DIGIT, { digit("9") }), Key("×", Kind.OP, { op("×") })),
        listOf(Key("4", Kind.DIGIT, { digit("4") }), Key("5", Kind.DIGIT, { digit("5") }), Key("6", Kind.DIGIT, { digit("6") }), Key("−", Kind.OP, { op("−") })),
        listOf(Key("1", Kind.DIGIT, { digit("1") }), Key("2", Kind.DIGIT, { digit("2") }), Key("3", Kind.DIGIT, { digit("3") }), Key("+", Kind.OP, { op("+") })),
        listOf(
            Key(".", Kind.DIGIT, { digit(".") }),
            Key("0", Kind.DIGIT, { digit("0") }),
            Key("⌫", Kind.FN, { if (showClear) clear() else delete() }, long = { clear() }),
            Key("=", Kind.EQ, { equals() }), // f(x) expressions are routed to the graph in buildNumpad
        ),
    )

    /** Functions page: label and what it inserts. Built-ins insert "name(". */
    private fun fk(label: String, action: CalcController.() -> Unit) = label to action

    private val functionKeys: List<Pair<String, CalcController.() -> Unit>> = listOf(
        fk("x") { operand("x") },
        fk("^") { op("^") },
        fk("√") { operand("√") },
        fk("π") { operand("π") },
        fk("e") { operand("e") },
        fk("ans") { operand("ans") },
        fk("%") { suffix("%") },
        fk("!") { suffix("!") },
        fk("<") { suffix("<") },
        fk(">") { suffix(">") },
        fk("=") { suffix("=") },
    ) + Calculator.builtins.keys.map { name -> fk(name) { operand("$name(") } }

    private val tabTitles = listOf("ƒ  My formulas", "123", "Functions")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        root.padForSystemBars()

        tvPrevious = findViewById(R.id.tv_previous)
        etExpr = findViewById(R.id.tv_expr)
        tvPreview = findViewById(R.id.tv_preview)
        btnAngle = findViewById(R.id.btn_angle)
        pager = findViewById(R.id.pager)
        tabs = findViewById(R.id.tabs)
        historyPanel = findViewById(R.id.history_panel)
        historyList = findViewById(R.id.history_list)

        ctl = CalcController(this) { render() }

        btnAngle.setOnClickListener { ctl.toggleDegrees() }
        findViewById<Button>(R.id.btn_formulas).setOnClickListener { openFormulas(null) }
        findViewById<Button>(R.id.btn_graph).setOnClickListener {
            openGraph(if (ctl.isFunctionOfX) ctl.expr else null)
        }

        setUpExpressionField()
        setUpHistory()

        formulasPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pager.addPage(ScrollView(this).apply {
            isFillViewport = true
            addView(formulasPage)
        })
        pager.addPage(buildNumpad())
        pager.addPage(buildFunctionsPage())
        pager.showPage(HOME, animate = false)
        pager.onPageChanged = { renderTabs() }
        buildTabs()
    }

    override fun onResume() {
        super.onResume()
        ctl.reloadFormulas()
        buildFormulasPage()
        render()
        etExpr.requestFocus()
        hideKeyboard()
    }

    /** The expression field uses our keypad only; make sure the system keyboard never appears. */
    private fun hideKeyboard() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        etExpr.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(etExpr.windowToken, 0)
            if (Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.ime())
        }
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FORMULAS && resultCode == RESULT_OK) {
            val name = data?.getStringExtra(FormulasActivity.EXTRA_INSERT) ?: return
            ctl.reloadFormulas()
            ctl.insertFormulaByName(name)
            pager.showPage(HOME, animate = false)
        }
    }

    @Deprecated("Framework Activity API")
    override fun onBackPressed() {
        when {
            historyOpen -> closeHistory()
            pager.current != HOME -> pager.showPage(HOME) // side panel -> numpad first
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    private fun openFormulas(editName: String?) {
        val i = Intent(this, FormulasActivity::class.java)
        if (editName != null) i.putExtra(FormulasActivity.EXTRA_EDIT, editName)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_FORMULAS)
    }

    private fun openGraph(expr: String?) {
        val i = Intent(this, GraphActivity::class.java)
        if (expr != null) i.putExtra(GraphActivity.EXTRA_EXPR, expr)
        startActivity(i)
    }

    // ---------- display ----------

    private fun setUpExpressionField() {
        MathStyle.attach(etExpr)
        etExpr.onSelection = { s, e -> if (!syncing) ctl.select(s, e) }
        etExpr.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                // Paste / hardware keyboard edits.
                if (!syncing) ctl.textEdited(s.toString(), etExpr.selectionStart)
            }
        })
    }

    private fun render() {
        tvPrevious.text = MathStyle.styled(ctl.previous)

        syncing = true
        if (etExpr.text.toString() != ctl.expr) etExpr.setText(ctl.expr)
        val len = etExpr.text.length
        etExpr.setSelection(ctl.selStart.coerceIn(0, len), ctl.selEnd.coerceIn(0, len))
        syncing = false
        etExpr.textSize = when (ctl.expr.length) {
            in 0..9 -> 60f
            in 10..13 -> 46f
            in 14..18 -> 36f
            in 19..30 -> 30f
            else -> 24f
        }

        val err = ctl.error
        tvPreview.text = err ?: ctl.preview
        tvPreview.setTextColor(color(if (err != null) R.color.error else R.color.text_dim))
        btnAngle.text = if (ctl.degrees) "DEG" else "RAD"
        delKey.text = if (ctl.showClear) "AC" else "⌫"
        delKey.contentDescription = if (ctl.showClear) "Clear" else "Delete"
    }

    // ---------- history (pull down from the display) ----------

    private fun setUpHistory() {
        findViewById<PullDownLayout>(R.id.display).listener = object : PullDownLayout.Listener {
            override fun onPull(dy: Float) {
                if (historyOpen) return
                if (historyPanel.visibility != View.VISIBLE) {
                    buildHistory()
                    historyPanel.visibility = View.VISIBLE
                }
                historyPanel.translationY = (-panelHeight() + dy).coerceAtMost(0f)
            }

            override fun onPullEnd(dy: Float, velocityY: Float) {
                if (historyOpen) return
                if (dy > panelHeight() * 0.25f || velocityY > 1200f) openHistory() else closeHistory()
            }
        }

        findViewById<Button>(R.id.btn_clear_history).setOnClickListener {
            History.clear(this)
            buildHistory()
        }

        // Grip at the bottom of the panel: drag up (or tap) to close.
        val grip = findViewById<View>(R.id.history_grip)
        var startY = 0f
        var tracker: VelocityTracker? = null
        grip.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = ev.rawY
                    tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(ev)
                    historyPanel.translationY = (ev.rawY - startY).coerceAtMost(0f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(ev)
                    tracker?.computeCurrentVelocity(1000)
                    val dy = ev.rawY - startY
                    val vy = tracker?.yVelocity ?: 0f
                    tracker?.recycle(); tracker = null
                    val tap = kotlin.math.abs(dy) < dp(8f)
                    if (tap || -dy > panelHeight() * 0.2f || vy < -1200f) closeHistory() else openHistory()
                }
            }
            true
        }
    }

    private fun panelHeight() = (if (historyPanel.height > 0) historyPanel.height else root.height).toFloat()

    private fun openHistory() {
        historyOpen = true
        historyPanel.visibility = View.VISIBLE
        historyPanel.animate().translationY(0f).setDuration(180).start()
    }

    private fun closeHistory() {
        historyOpen = false
        historyPanel.animate().translationY(-panelHeight()).setDuration(180)
            .withEndAction { if (!historyOpen) historyPanel.visibility = View.INVISIBLE }
            .start()
    }

    private fun buildHistory() {
        historyList.removeAllViews()
        val entries = History.load(this)
        if (entries.isEmpty()) {
            historyList.addView(TextView(this).apply {
                text = getString(R.string.history_empty)
                gravity = Gravity.CENTER
                setTextColor(color(R.color.text_dim))
                setPadding(0, dpi(48), 0, 0)
            })
            return
        }
        // Oldest at the top, newest at the bottom (closest to the display), like Xlythe.
        for (e in entries.asReversed()) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
                background = roundedBg(color(R.color.bg), 12f)
                isClickable = true
                setOnClickListener { ctl.insertResult(e.result); closeHistory() }
                onLongPress { ctl.restore(e.expr); closeHistory() }
            }
            item.addView(TextView(this).apply {
                text = MathStyle.styled(e.expr)
                textSize = 16f
                gravity = Gravity.END
                setTextColor(color(R.color.text_dim))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            item.addView(TextView(this).apply {
                text = "= ${e.result}"
                textSize = 24f
                gravity = Gravity.END
                setTextColor(color(R.color.text))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            historyList.addView(item)
        }
        val scroll = findViewById<ScrollView>(R.id.history_scroll)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- tabs ----------

    private fun buildTabs() {
        tabTitles.forEachIndexed { i, title ->
            val tv = TextView(this).apply {
                text = title
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dpi(10), 0, dpi(10))
                isClickable = true
                setOnClickListener { pager.showPage(i) }
            }
            tabs.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        renderTabs()
    }

    private fun renderTabs() {
        for (i in 0 until tabs.childCount) {
            val tv = tabs.getChildAt(i) as TextView
            val active = i == pager.current
            tv.setTextColor(color(if (active) R.color.accent else R.color.text_dim))
            tv.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            tv.background = if (active) roundedBg(color(R.color.key_fn), 50f) else null
        }
    }

    // ---------- numpad (centre page) ----------

    private fun buildNumpad(): View {
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(7), dpi(4), dpi(7), dpi(7))
        }
        for (row in numpad) {
            val line = addRow(pad)
            for (key in row) {
                val (bg, fg) = when (key.kind) {
                    Kind.DIGIT -> R.color.key_digit to R.color.text
                    Kind.OP -> R.color.key_op to R.color.text
                    Kind.FN -> R.color.key_fn to R.color.accent
                    Kind.EQ -> R.color.accent to R.color.on_accent
                }
                val onClick: () -> Unit = if (key.kind == Kind.EQ) {
                    { if (ctl.isFunctionOfX) openGraph(ctl.expr) else ctl.equals() }
                } else {
                    { key.action(ctl) }
                }
                val tv = keyView(key.label, 28f, bg, fg, bold = key.kind != Kind.DIGIT, onClick = onClick)
                if (key.label == "⌫") delKey = tv
                key.long?.let { l ->
                    tv.onLongPress {
                        tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        l(ctl)
                    }
                }
                addCell(line, tv)
            }
        }
        return pad
    }

    // ---------- user formulas (left page) ----------

    private fun buildFormulasPage() {
        formulasPage.removeAllViews()
        formulasPage.setPadding(dpi(7), dpi(4), dpi(7), dpi(7))
        val cols = 2
        val items = ctl.formulas.map<Formula, View> { f -> formulaKey(f) } + listOf(
            keyView("＋  New formula", 16f, R.color.key_fn, R.color.accent, bold = true) { openFormulas(NEW_FORMULA) },
            keyView("Manage…", 16f, R.color.key_fn, R.color.accent, bold = true) { openFormulas(null) },
        )
        items.chunked(cols).forEach { chunk ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            formulasPage.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(72)))
            chunk.forEach { addCell(line, it) }
            repeat(cols - chunk.size) { addCell(line, View(this)) }
        }
        if (ctl.formulas.isEmpty()) {
            formulasPage.addView(TextView(this).apply {
                text = getString(R.string.no_formulas)
                setTextColor(color(R.color.text_dim))
                gravity = Gravity.CENTER
                setPadding(0, dpi(24), 0, 0)
            })
        }
    }

    private fun formulaKey(f: Formula): View {
        val label = SpannableStringBuilder(f.name).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val detail = if (f.params.isEmpty()) "= ${f.body}" else "(${f.params.joinToString(", ")})"
            val start = length
            append("\n").append(MathStyle.styled(detail))
            setSpan(AbsoluteSizeSpan(12, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return TextView(this).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(color(R.color.chip_user_text))
            background = roundedBg(color(R.color.chip_user), 20f)
            isClickable = true
            setOnClickListener { ctl.insertFormula(f); pager.showPage(HOME) }
            onLongPress { openFormulas(f.name) }
        }
    }

    // ---------- functions (right page) ----------

    private fun buildFunctionsPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(7), dpi(4), dpi(7), dpi(7))
        }
        val cols = 5
        functionKeys.chunked(cols).forEach { chunk ->
            val line = addRow(page)
            chunk.forEach { (label, action) ->
                addCell(line, keyView(label, 17f, R.color.key_op, R.color.text, bold = label == "x") {
                    action(ctl)
                    pager.showPage(HOME)
                })
            }
            repeat(cols - chunk.size) { addCell(line, View(this)) }
        }
        return page
    }

    // ---------- helpers ----------

    private fun addRow(parent: LinearLayout): LinearLayout {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return line
    }

    private fun addCell(line: LinearLayout, v: View) {
        val gap = dpi(3)
        line.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(gap, gap, gap, gap)
        })
    }

    private fun keyView(label: String, sizeSp: Float, bgColor: Int, fgColor: Int, bold: Boolean, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = sizeSp
            setTextColor(color(fgColor))
            typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            background = roundedBg(color(bgColor), 28f)
            isClickable = true
            isFocusable = false // keep focus (and the cursor) in the expression field
            contentDescription = label
            setOnClickListener { onClick() }
        }

    companion object {
        private const val REQ_FORMULAS = 1
        private const val NEW_FORMULA = ""
        private const val HOME = 1 // numpad is the centre page
    }
}
