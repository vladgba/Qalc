# Qalc

A simple Android calculator, with **your own formulas** as first-class keys, a function plotter and history.

Written in Kotlin on the plain Android framework — **no external libraries** (no AndroidX, no Compose, no Material). The only dependency is the Kotlin standard library that the Kotlin Gradle plugin adds.

## Features

- **Swipeable keypad** — three full-size panels: `ƒ My formulas` ← `123` → `Functions`. The numpad is the centre page; inserting a formula or function jumps back to it.
- **Editable expression** — tap anywhere to move the cursor or select text; keys insert at the cursor. The system keyboard never opens (copy/paste and hardware keyboards still work).
- **Live preview** of the result while you type; `⌫` turns into `AC` after `=` or an error (long-press `⌫` always clears).
- **Textbook powers** — `2^10` is displayed as 2¹⁰; towers like `2^3^2` stack.
- **User formulas** — define functions and constants, call them like built-ins, reorder, edit, delete. Long-press a formula key to edit it.
- **Graphs** — plot several `y = f(x)` curves at once; drag to pan, pinch to zoom, tap to trace values, double-tap to reset. Pressing `=` on an expression containing `x` opens the graph.
- **History** — pull down on the display. Tap an entry to insert its result, long-press to restore its expression.
- **DEG / RAD** toggle (graphs always use radians), light/dark theme, adaptive + themed launcher icon.

## Writing formulas

```
name(param1, param2, …) = expression
name = expression            # a named constant
```

Examples (these are pre-loaded on first run):

```
hyp(a, b)   = √(a^2 + b^2)
bmi(kg, m)  = kg ÷ m^2
vat         = 20
gross(net)  = net × (1 + vat%)
fib(n)      = if(n < 2, n, fib(n − 1) + fib(n − 2))
```

Formulas can use parameters, constants, `ans`, built-ins and **other formulas**, including recursion via the lazy `if(cond, then, else)`. Names of built-ins are reserved. One-parameter formulas get a **Plot** button (`name(x)`).

## Expression language

| | |
|---|---|
| Operators | `+ − × ÷ ^ %` (percent = ÷100), `!` (factorial), `√` |
| Comparisons | `< > <= >= == !=` → `1` or `0` (`=` in an expression means `==`) |
| Constants | `π` / `pi`, `e`, `ans` (last result) |
| Functions | `sin cos tan asin acos atan sinh cosh tanh ln log(x[, base]) exp sqrt cbrt abs floor ceil round(x[, digits]) min max mod if` |
| Conveniences | implicit multiplication (`2π`, `3(x+1)`, `2sin(x)`), auto-closing of `(`, scientific input `1.5E−7` |

Precedence, lowest to highest: comparisons → `+ −` → `× ÷` (and implicit) → unary `− √` → `^` (right-associative, `-2^2 = −4`) → postfix `% !`.

Results are shown with up to 12 significant digits; very large/small values use `E` notation.

## Project structure

```
app/src/main/java/dev/qalc/
├── engine/               Pure Kotlin, no Android imports
│   ├── Lexer.kt          tokens (accepts both ASCII and × ÷ − √ π)
│   ├── Parser.kt         recursive-descent parser → AST
│   └── Calculator.kt     evaluator, built-ins, user formulas, formatting
├── CalcController.kt     keypad logic, cursor handling, persisted state
├── MainActivity.kt       calculator screen, pages, history panel
├── FormulasActivity.kt   formula list + editor dialog
├── GraphActivity.kt      graph screen (function list)
├── GraphView.kt          Canvas plotter (pan / zoom / trace)
├── PagerView.kt          minimal ViewPager replacement
├── PullDownLayout.kt     pull-down gesture for history
├── ExprField.kt          EditText that never shows the soft keyboard
├── MathStyle.kt          span styling for superscript powers
├── FormulaStore.kt / History.kt   SharedPreferences persistence (JSON)
└── Ui.kt                 small helpers (dp, ripples, system-bar insets)
```

The `engine` package has no Android dependencies and can be reused or unit-tested on the JVM.

## Building

Requirements: JDK 17+, Android SDK with platform 35.

Open the project in IntelliJ IDEA / Android Studio and run the `app` configuration, or from the command line:

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # minified; signed with the debug key — set your own signingConfig
```

- `minSdk` 26 (Android 8.0), `targetSdk` / `compileSdk` 35
- Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Gradle 8.14.3 (wrapper included)

## License

[MIT](LICENSE) © 2026 vladgba
