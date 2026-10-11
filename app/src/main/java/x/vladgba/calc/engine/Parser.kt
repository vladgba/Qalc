package x.vladgba.calc.engine

sealed interface Node {
    data class Num(val value: Double) : Node
    data class Ref(val name: String) : Node
    data class Neg(val arg: Node) : Node
    data class Bin(val op: String, val l: Node, val r: Node) : Node
    data class Post(val op: String, val arg: Node) : Node
    data class Call(val name: String, val args: List<Node>) : Node
}

/**
 * Precedence, lowest to highest:
 *   comparison  < > <= >= == !=
 *   additive    + -
 *   multiplic.  * /  and implicit multiplication (2π, 3(4+1), 2sin(x))
 *   unary       - + √
 *   power       ^  (right-assoc, so -2^2 = -4 and 2^3^2 = 512)
 *   postfix     % !
 * Unclosed parentheses at the end are auto-closed, like on a phone calculator.
 */
class Parser private constructor(private val toks: List<Token>) {
    private var p = 0
    private val peek get() = toks[p]

    companion object {
        fun parse(src: String): Node {
            val parser = Parser(Lexer.tokenize(src))
            if (parser.peek.type == T.EOF) throw CalcException("Empty")
            val n = parser.comparison()
            if (parser.peek.type != T.EOF) throw CalcException("Unexpected '${parser.peek.text}'")
            return n
        }
    }

    private fun next() = toks[p++]
    private fun isOp(vararg ops: String) = peek.type == T.OP && peek.text in ops

    private fun comparison(): Node {
        var l = additive()
        while (isOp("<", ">", "<=", ">=", "==", "!=")) {
            val op = next().text
            l = Node.Bin(op, l, additive())
        }
        return l
    }

    private fun additive(): Node {
        var l = multiplicative()
        while (isOp("+", "-")) {
            val op = next().text
            l = Node.Bin(op, l, multiplicative())
        }
        return l
    }

    private fun startsOperand() = when (peek.type) {
        T.NUM, T.ID, T.LPAR -> true
        T.OP -> peek.text == "√"
        else -> false
    }

    private fun multiplicative(): Node {
        var l = unary()
        while (true) {
            l = when {
                isOp("*", "/") -> { val op = next().text; Node.Bin(op, l, unary()) }
                startsOperand() -> Node.Bin("*", l, power()) // implicit: 2π, 3(4), 2√9
                else -> return l
            }
        }
    }

    private fun unary(): Node = when {
        isOp("-") -> { next(); Node.Neg(unary()) }
        isOp("+") -> { next(); unary() }
        else -> power()
    }

    private fun power(): Node {
        val base = if (isOp("√")) { next(); Node.Call("sqrt", listOf(power())) } else postfix()
        return if (isOp("^")) { next(); Node.Bin("^", base, unary()) } else base
    }

    private fun postfix(): Node {
        var n = primary()
        while (isOp("%", "!")) n = Node.Post(next().text, n)
        return n
    }

    private fun primary(): Node {
        val t = next()
        return when (t.type) {
            T.NUM -> Node.Num(t.text.toDoubleOrNull() ?: throw CalcException("Bad number"))
            T.LPAR -> {
                val inner = comparison()
                closeParen()
                inner
            }
            T.ID -> if (peek.type == T.LPAR) {
                next()
                val args = ArrayList<Node>()
                if (peek.type != T.RPAR && peek.type != T.EOF) {
                    args += comparison()
                    while (peek.type == T.COMMA) { next(); args += comparison() }
                }
                closeParen()
                Node.Call(t.text, args)
            } else Node.Ref(t.text)
            T.EOF -> throw CalcException("Incomplete")
            else -> throw CalcException("Unexpected '${t.text}'")
        }
    }

    private fun closeParen() {
        when (peek.type) {
            T.RPAR -> next()
            T.EOF -> Unit // auto-close
            else -> throw CalcException("Expected ')'")
        }
    }
}
