package dev.qalc.engine

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.*

/**
 * A user formula: `name(a, b) = body`, or a named constant: `name = body`.
 * Bodies may use parameters, built-ins, constants, `ans`, and other user formulas (including recursion via `if`).
 */
data class Formula(val name: String, val params: List<String>, val body: String) {
    val signature: String get() = if (params.isEmpty()) name else "$name(${params.joinToString(", ")})"
    val source: String get() = "$signature = $body"

    companion object {
        private val header = Regex("""^\s*([A-Za-z_]\w*)\s*(?:\(\s*([^)]*)\)\s*)?=(?!=)(.*)$""")
        private val ident = Regex("""[A-Za-z_]\w*""")

        /** Parses "name(a, b) = body". Validates syntax; does not check that referenced names exist. */
        fun parse(text: String): Formula {
            val m = header.find(text.trim()) ?: throw CalcException("Use: name(x, y) = expression")
            val name = m.groupValues[1]
            val params = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val body = m.groupValues[3].trim()
            if (name.lowercase() in Calculator.reserved) throw CalcException("'$name' is a built-in name")
            params.forEach { if (!ident.matches(it)) throw CalcException("Bad parameter '$it'") }
            if (params.toSet().size != params.size) throw CalcException("Duplicate parameter")
            if (body.isEmpty()) throw CalcException("Formula body is empty")
            Parser.parse(body) // syntax check
            return Formula(name, params, body)
        }
    }
}

class Calculator(
    formulas: List<Formula> = emptyList(),
    var degrees: Boolean = false,
    var ans: Double = 0.0,
) {
    companion object {
        const val MAX_DEPTH = 200

        val builtins: Map<String, String> = linkedMapOf(
            "sin" to "sin(x)", "cos" to "cos(x)", "tan" to "tan(x)",
            "asin" to "asin(x)", "acos" to "acos(x)", "atan" to "atan(x)",
            "sinh" to "sinh(x)", "cosh" to "cosh(x)", "tanh" to "tanh(x)",
            "ln" to "ln(x)", "log" to "log(x) or log(x, base)", "exp" to "exp(x)",
            "sqrt" to "sqrt(x)", "cbrt" to "cbrt(x)", "abs" to "abs(x)",
            "floor" to "floor(x)", "ceil" to "ceil(x)", "round" to "round(x) or round(x, digits)",
            "min" to "min(a, b, …)", "max" to "max(a, b, …)", "mod" to "mod(a, b)",
            "if" to "if(cond, then, else)",
        )
        val constants = setOf("pi", "e", "ans")
        val reserved: Set<String> = builtins.keys + constants
    }

    /** Max evaluation steps per call; guards against e.g. fib(40) freezing the UI. */
    var stepLimit = 3_000_000
    private var steps = 0

    private var defs: Map<String, Formula> = emptyMap()
    private val bodyCache = HashMap<String, Node>()

    init { setFormulas(formulas) }

    fun setFormulas(list: List<Formula>) {
        defs = list.associateBy { it.name }
        bodyCache.clear()
    }

    fun evaluate(expr: String): Double {
        steps = 0
        val v = eval(Parser.parse(expr), emptyMap(), 0)
        if (v.isNaN()) throw CalcException("Not a number")
        if (v.isInfinite()) throw CalcException(if (v > 0) "Infinity" else "-Infinity")
        return if (v == 0.0) 0.0 else v // normalise -0
    }

    /** Parses once for repeated evaluation (graphs). */
    fun compile(expr: String): Node = Parser.parse(expr)

    /** Evaluates [node] with free variables bound (e.g. x); returns NaN instead of throwing. */
    fun evalAt(node: Node, vars: Map<String, Double>): Double = try {
        steps = 0
        eval(node, vars, 0)
    } catch (_: CalcException) {
        Double.NaN
    } catch (_: StackOverflowError) {
        Double.NaN
    }

    /** Throws if [node] references unknown names, assuming [vars] are free variables. */
    fun check(node: Node, vars: Set<String>) {
        fun walk(n: Node, bound: Set<String>) {
            when (n) {
                is Node.Num -> Unit
                is Node.Neg -> walk(n.arg, bound)
                is Node.Post -> walk(n.arg, bound)
                is Node.Bin -> { walk(n.l, bound); walk(n.r, bound) }
                is Node.Ref -> if (n.name !in bound && n.name !in constants && n.name !in defs)
                    throw CalcException("Unknown '${n.name}'")
                is Node.Call -> {
                    if (n.name !in bound && n.name !in constants && n.name !in defs && n.name !in builtins)
                        throw CalcException("Unknown function '${n.name}'")
                    n.args.forEach { walk(it, bound) }
                }
            }
        }
        walk(node, vars)
    }

    /** Returns formatted result, or null if the expression is incomplete/invalid (used for live preview). */
    fun tryEvaluate(expr: String): String? =
        try { format(evaluate(expr)) } catch (_: CalcException) { null } catch (_: StackOverflowError) { null }

    private fun bodyOf(f: Formula) = bodyCache.getOrPut(f.name) { Parser.parse(f.body) }

    private fun toRad(x: Double) = if (degrees) Math.toRadians(x) else x
    private fun fromRad(x: Double) = if (degrees) Math.toDegrees(x) else x

    private fun eval(n: Node, scope: Map<String, Double>, depth: Int): Double {
        if (depth > MAX_DEPTH) throw CalcException("Recursion too deep")
        if (++steps > stepLimit) throw CalcException("Too complex")
        return when (n) {
            is Node.Num -> n.value
            is Node.Neg -> -eval(n.arg, scope, depth)
            is Node.Ref -> scope[n.name] ?: when (n.name) {
                "pi" -> PI
                "e" -> E
                "ans" -> ans
                else -> {
                    val f = defs[n.name] ?: throw CalcException("Unknown '${n.name}'")
                    if (f.params.isNotEmpty()) throw CalcException("${f.name} needs ${f.params.size} argument(s)")
                    eval(bodyOf(f), emptyMap(), depth + 1)
                }
            }
            is Node.Post -> {
                val v = eval(n.arg, scope, depth)
                if (n.op == "%") v / 100 else factorial(v)
            }
            is Node.Bin -> binary(n, scope, depth)
            is Node.Call -> call(n, scope, depth)
        }
    }

    private fun binary(n: Node.Bin, scope: Map<String, Double>, depth: Int): Double {
        val a = eval(n.l, scope, depth)
        val b = eval(n.r, scope, depth)
        fun bool(x: Boolean) = if (x) 1.0 else 0.0
        return when (n.op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> if (b == 0.0) throw CalcException("Can't divide by 0") else a / b
            "^" -> a.pow(b)
            "<" -> bool(a < b)
            ">" -> bool(a > b)
            "<=" -> bool(a <= b)
            ">=" -> bool(a >= b)
            "==" -> bool(abs(a - b) <= 1e-12 * max(1.0, max(abs(a), abs(b))))
            "!=" -> bool(abs(a - b) > 1e-12 * max(1.0, max(abs(a), abs(b))))
            else -> throw CalcException("Bad operator ${n.op}")
        }
    }

    private fun call(n: Node.Call, scope: Map<String, Double>, depth: Int): Double {
        // `if` is lazy so recursive formulas terminate.
        if (n.name == "if") {
            if (n.args.size != 3) throw CalcException("if needs 3 arguments")
            val c = eval(n.args[0], scope, depth)
            return eval(if (c != 0.0) n.args[1] else n.args[2], scope, depth)
        }

        // A parameter/constant followed by parentheses means multiplication: a(b+1)
        val asValue = scope[n.name] ?: if (n.name in constants) eval(Node.Ref(n.name), scope, depth) else null
        if (asValue != null) {
            if (n.args.size != 1) throw CalcException("'${n.name}' is not a function")
            return asValue * eval(n.args[0], scope, depth)
        }

        val args = n.args.map { eval(it, scope, depth) }
        defs[n.name]?.let { f ->
            if (args.size != f.params.size)
                throw CalcException("${f.name} needs ${f.params.size} argument(s)")
            return eval(bodyOf(f), f.params.zip(args).toMap(), depth + 1)
        }

        fun one(): Double {
            if (args.size != 1) throw CalcException("${n.name} needs 1 argument")
            return args[0]
        }
        fun two(): Pair<Double, Double> {
            if (args.size != 2) throw CalcException("${n.name} needs 2 arguments")
            return args[0] to args[1]
        }
        return when (n.name) {
            "sin" -> cleanTrig(sin(toRad(one())))
            "cos" -> cleanTrig(cos(toRad(one())))
            "tan" -> {
                val x = toRad(one())
                if (abs(cleanTrig(cos(x))) == 0.0) throw CalcException("tan undefined") else cleanTrig(tan(x))
            }
            "asin" -> fromRad(asin(one()))
            "acos" -> fromRad(acos(one()))
            "atan" -> fromRad(atan(one()))
            "sinh" -> sinh(one())
            "cosh" -> cosh(one())
            "tanh" -> tanh(one())
            "ln" -> ln(one())
            "log" -> when (args.size) {
                1 -> log10(args[0])
                2 -> ln(args[0]) / ln(args[1])
                else -> throw CalcException("log needs 1 or 2 arguments")
            }
            "exp" -> exp(one())
            "sqrt" -> sqrt(one())
            "cbrt" -> cbrt(one())
            "abs" -> abs(one())
            "floor" -> floor(one())
            "ceil" -> ceil(one())
            "round" -> when (args.size) {
                1 -> Math.round(args[0]).toDouble()
                2 -> BigDecimal(args[0]).setScale(args[1].toInt(), java.math.RoundingMode.HALF_UP).toDouble()
                else -> throw CalcException("round needs 1 or 2 arguments")
            }
            "min" -> args.minOrNull() ?: throw CalcException("min needs arguments")
            "max" -> args.maxOrNull() ?: throw CalcException("max needs arguments")
            "mod" -> two().let { (a, b) ->
                if (b == 0.0) throw CalcException("Can't divide by 0")
                val r = a % b
                if (r != 0.0 && (r < 0) != (b < 0)) r + b else r
            }
            else -> throw CalcException("Unknown function '${n.name}'")
        }
    }

    /** sin(π) should be 0, not 1.2e-16. */
    private fun cleanTrig(v: Double) = if (abs(v) < 1e-14) 0.0 else v

    private fun factorial(v: Double): Double {
        if (v < 0 || v != floor(v)) throw CalcException("! needs a whole number ≥ 0")
        if (v > 170) throw CalcException("Too large")
        var r = 1.0
        for (i in 2..v.toInt()) r *= i
        return r
    }

    /** Up to 12 significant digits, trailing zeros stripped, scientific notation for very large/small values. */
    fun format(v: Double): String {
        if (v == 0.0) return "0"
        val bd = BigDecimal(v).round(MathContext(12)).stripTrailingZeros()
        val exp = bd.precision() - bd.scale() - 1
        val s = if (exp in -6..14) bd.toPlainString()
        else {
            val mant = bd.movePointLeft(exp).stripTrailingZeros().toPlainString()
            "${mant}E$exp"
        }
        return s.replace('-', '−')
    }
}
