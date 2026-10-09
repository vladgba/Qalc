package dev.qalc.engine

class CalcException(message: String) : Exception(message)

internal enum class T { NUM, ID, OP, LPAR, RPAR, COMMA, EOF }

internal data class Token(val type: T, val text: String, val pos: Int)

/** Turns "2×sin(30)+√9" into tokens. Accepts both ASCII and the pretty keypad symbols. */
internal object Lexer {
    private val twoCharOps = setOf("<=", ">=", "==", "!=", "**")

    fun tokenize(src: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    val start = i
                    var dot = false
                    while (i < src.length && (src[i].isDigit() || src[i] == '.')) {
                        if (src[i] == '.') {
                            if (dot) throw CalcException("Bad number")
                            dot = true
                        }
                        i++
                    }
                    // Scientific notation uses capital E only (lowercase e is Euler's number): 1.5E−7
                    if (i < src.length && src[i] == 'E') {
                        var j = i + 1
                        if (j < src.length && src[j] in "+-−") j++
                        if (j < src.length && src[j].isDigit()) {
                            while (j < src.length && src[j].isDigit()) j++
                            i = j
                        }
                    }
                    out += Token(T.NUM, src.substring(start, i).replace('−', '-'), start)
                }
                c == 'π' -> { out += Token(T.ID, "pi", i); i++ }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_') && src[i] != 'π') i++
                    out += Token(T.ID, src.substring(start, i), start)
                }
                c == '(' -> { out += Token(T.LPAR, "(", i); i++ }
                c == ')' -> { out += Token(T.RPAR, ")", i); i++ }
                c == ',' || c == ';' -> { out += Token(T.COMMA, ",", i); i++ }
                else -> {
                    val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                    if (two in twoCharOps) {
                        out += Token(T.OP, if (two == "**") "^" else two, i); i += 2
                    } else {
                        val op = when (c) {
                            '+' -> "+"
                            '-', '−', '–' -> "-"
                            '*', '×', '·' -> "*"
                            '/', '÷' -> "/"
                            '^' -> "^"
                            '%' -> "%"
                            '!' -> "!"
                            '√' -> "√"
                            '<' -> "<"
                            '>' -> ">"
                            '=' -> "=="
                            '≤' -> "<="
                            '≥' -> ">="
                            '≠' -> "!="
                            else -> throw CalcException("Unexpected '$c'")
                        }
                        out += Token(T.OP, op, i); i++
                    }
                }
            }
        }
        out += Token(T.EOF, "", src.length)
        return out
    }
}
