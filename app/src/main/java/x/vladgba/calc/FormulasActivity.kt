package x.vladgba.calc

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import x.vladgba.calc.engine.Calculator
import x.vladgba.calc.engine.Formula

class FormulasActivity : Activity() {
    private var formulas: List<Formula> = emptyList()
    private lateinit var list: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_formulas)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<View>(android.R.id.content).let { (it as? android.view.ViewGroup)?.getChildAt(0)?.padForSystemBars(top = false) }

        list = findViewById(R.id.list)
        tvEmpty = findViewById(R.id.tv_empty)
        findViewById<TextView>(R.id.tv_builtins).text =
            Calculator.builtins.values.joinToString("   ") +
                "\n\nConstants: π (pi), e, ans\n" +
                "Operators: + − × ÷ ^ % ! and < > <= >= == != (give 1 or 0)\n" +
                "Implicit multiplication works: 2π, 3(x+1), 2sin(x)"

        formulas = FormulaStore.load(this)
        render()

        if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_EDIT)?.let { name ->
                // An empty name means "open the editor for a new formula".
                if (name.isEmpty()) openEditor(null)
                else formulas.firstOrNull { it.name == name }?.let { openEditor(it) }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.formulas, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_add -> { openEditor(null); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun update(newList: List<Formula>) {
        formulas = newList
        FormulaStore.save(this, newList)
        render()
    }

    private fun render() {
        list.removeAllViews()
        tvEmpty.visibility = if (formulas.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        formulas.forEachIndexed { i, f ->
            val row = inflater.inflate(R.layout.item_formula, list, false)
            row.background = roundedBg(color(R.color.card), 16f)
            row.findViewById<TextView>(R.id.tv_signature).text = f.signature
            row.findViewById<TextView>(R.id.tv_body).text = MathStyle.styled("= ${f.body}")
            row.setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_INSERT, f.name))
                finish()
            }
            row.findViewById<Button>(R.id.btn_up).apply {
                isEnabled = i > 0
                alpha = if (isEnabled) 1f else 0.3f
                setOnClickListener { move(i, -1) }
            }
            row.findViewById<Button>(R.id.btn_down).apply {
                isEnabled = i < formulas.lastIndex
                alpha = if (isEnabled) 1f else 0.3f
                setOnClickListener { move(i, 1) }
            }
            row.findViewById<Button>(R.id.btn_plot).apply {
                // One-parameter formulas can be plotted as y = name(x).
                visibility = if (f.params.size == 1) View.VISIBLE else View.GONE
                setOnClickListener {
                    startActivity(Intent(this@FormulasActivity, GraphActivity::class.java)
                        .putExtra(GraphActivity.EXTRA_EXPR, "${f.name}(x)"))
                }
            }
            row.findViewById<Button>(R.id.btn_edit).setOnClickListener { openEditor(f) }
            row.findViewById<ImageButton>(R.id.btn_delete).setOnClickListener { confirmDelete(f) }
            list.addView(row)
        }
    }

    private fun move(i: Int, delta: Int) {
        val l = formulas.toMutableList()
        val j = i + delta
        if (j !in l.indices) return
        l[i] = l[j].also { l[j] = l[i] }
        update(l)
    }

    private fun confirmDelete(f: Formula) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${f.signature}?")
            .setPositiveButton(R.string.delete) { _, _ -> update(formulas - f) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openEditor(original: Formula?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_formula, null)
        val et = view.findViewById<EditText>(R.id.et_formula)
        val tvError = view.findViewById<TextView>(R.id.tv_error)
        val symbols = view.findViewById<LinearLayout>(R.id.symbols)

        // Quick symbols the system keyboard makes awkward.
        for (s in listOf("=", "(", ")", ",", "^", "×", "÷", "√")) {
            val b = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = s
                textSize = 18f
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    val start = et.selectionStart.coerceAtLeast(0)
                    val end = et.selectionEnd.coerceAtLeast(0)
                    et.text.replace(minOf(start, end), maxOf(start, end), s)
                }
            }
            symbols.addView(b, LinearLayout.LayoutParams(0, dpi(44), 1f))
        }

        et.setText(original?.source ?: "")
        MathStyle.attach(et)
        et.setSelection(et.text.length)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (original == null) R.string.add_formula else R.string.edit_formula)
            .setView(view)
            .setPositiveButton(R.string.save, null) // set below so invalid input doesn't close the dialog
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun validate(): Boolean {
            val text = et.text.toString()
            val err = if (text.isBlank()) null else FormulaStore.validate(text, formulas, original)
            tvError.text = err ?: ""
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = text.isNotBlank() && err == null
            return text.isNotBlank() && err == null
        }

        dialog.setOnShowListener {
            validate()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!validate()) return@setOnClickListener
                val f = Formula.parse(et.text.toString())
                val l = formulas.toMutableList()
                val idx = original?.let { l.indexOf(it) } ?: -1
                if (idx >= 0) l[idx] = f else l += f
                update(l)
                dialog.dismiss()
            }
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { validate() }
        })

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        et.requestFocus()
    }

    companion object {
        const val EXTRA_INSERT = "insert"
        const val EXTRA_EDIT = "edit"
    }
}
