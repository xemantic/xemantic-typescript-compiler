/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * xemantic-typescript-compiler - a conformant TypeScript compiler and type
 * checker that runs on JVM, native, and WebAssembly
 * Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * As a special exception, this file contains Helper Code covered by the
 * xemantic-typescript-compiler Output Exception; additional permissions
 * are granted as described in the file LICENSE-EXCEPTION.
 */

// Parts of this file are translated from the Go standard library (go1.27.1), Copyright The Go
// Authors, used under Go's BSD-style licence: see LICENSE-GO in this module.

package com.xemantic.typescript.tsgo.go.fmt

import com.xemantic.typescript.tsgo.go.strconv.formatFloat
import com.xemantic.typescript.tsgo.go.strconv.quote
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.GoMultiUnwrapper
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoUnwrapper
import com.xemantic.typescript.tsgo.runtime.appendRuneBytes
import com.xemantic.typescript.tsgo.runtime.goRuneCount

// A subset of Go's `fmt` covering what tsgo's spike closure formats: panic messages, error
// messages, `%016x` hashes, `\x%02x` escapes. Verbs: v d s q x X c t f e g E G o b %, flags
// `+ - # 0 space`, width and precision (also `*`). Go's error/Stringer method dispatch applies to
// v and s (and q, x, X). APPROXIMATIONS (docs/goport-runtime.md): `%v` of a struct or pointer
// prints the Kotlin `toString()` (Go prints `{f1 f2}` / `&{…}` / an address), `%T` prints a
// Go-like name only for builtin kinds, and `%q` keeps every valid non-ASCII rune (strconv.quote).

/** `fmt.Stringer`. The lowering declares it on every generated type with a `String() string` method. */
interface Stringer {
    fun string(): String
}

/** `fmt.Sprintf(format, a...)`. */
fun sprintf(format: String, vararg a: Any?): String = Printer(a).doPrintf(format)

/** `fmt.Sprint(a...)`: spaces between operands when neither side is a string. */
fun sprint(vararg a: Any?): String {
    val p = Printer(a)
    var prevString = false
    for ((i, arg) in a.withIndex()) {
        val isString = arg is String
        if (i > 0 && !isString && !prevString) p.buf.append(' ')
        p.printArg(arg, 'v')
        prevString = isString
    }
    return p.buf.toString()
}

/** `fmt.Sprintln(a...)`. */
fun sprintln(vararg a: Any?): String {
    val p = Printer(a)
    for ((i, arg) in a.withIndex()) {
        if (i > 0) p.buf.append(' ')
        p.printArg(arg, 'v')
    }
    p.buf.append('\n')
    return p.buf.toString()
}

/** Writes a formatted byte string to [w]: Go's `w.Write(p.buf)` and its (n, err). */
private fun writeTo(w: com.xemantic.typescript.tsgo.go.io.Writer?, text: String): com.xemantic.typescript.tsgo.runtime.Tuple2<Int, GoError?> {
    if (w == null) throw NullPointerException("fmt: nil io.Writer")
    return w.write(com.xemantic.typescript.tsgo.runtime.goStringToBytes(text))
}

/** `fmt.Fprintf(w, format, a...)` → (n, err). */
fun fprintf(w: com.xemantic.typescript.tsgo.go.io.Writer?, format: String, vararg a: Any?): com.xemantic.typescript.tsgo.runtime.Tuple2<Int, GoError?> =
    writeTo(w, sprintf(format, *a))

/** `fmt.Fprint(w, a...)`. */
fun fprint(w: com.xemantic.typescript.tsgo.go.io.Writer?, vararg a: Any?): com.xemantic.typescript.tsgo.runtime.Tuple2<Int, GoError?> =
    writeTo(w, sprint(*a))

/** `fmt.Fprintln(w, a...)`. */
fun fprintln(w: com.xemantic.typescript.tsgo.go.io.Writer?, vararg a: Any?): com.xemantic.typescript.tsgo.runtime.Tuple2<Int, GoError?> =
    writeTo(w, sprintln(*a))

private class WrapError(private val msg: String, private val err: GoError?) : GoError, GoUnwrapper {
    override fun error(): String = msg
    override fun unwrap(): GoError? = err
    override fun toString(): String = msg
}

private class WrapErrors(private val msg: String, private val errs: List<GoError?>) : GoError, GoMultiUnwrapper {
    override fun error(): String = msg
    override fun unwrapAll(): GoSlice<GoError?> = GoSlice.of(GoElem.ref(), *errs.toTypedArray())
    override fun toString(): String = msg
}

/** `fmt.Errorf(format, a...)`: `%w` operands are formatted as `%v` and wrapped. */
fun errorf(format: String, vararg a: Any?): GoError {
    val p = Printer(a, wrapErrs = true)
    val s = p.doPrintf(format)
    val wrapped = p.wrappedArgs
    return when (wrapped.size) {
        0 -> com.xemantic.typescript.tsgo.runtime.GoPlainError(s)
        1 -> WrapError(s, a[wrapped[0]] as? GoError)
        else -> WrapErrors(s, wrapped.sorted().map { a[it] as? GoError })
    }
}

/** The Go type name used in `%T` and bad-verb messages (builtin kinds only). */
private fun typeName(arg: Any?): String = when (arg) {
    null -> "<nil>"
    is Int -> "int"
    is Long -> "int64"
    is UInt -> "uint32"
    is ULong -> "uint64"
    is Double -> "float64"
    is Float -> "float32"
    is String -> "string"
    is Boolean -> "bool"
    is GoSlice<*> -> "[]"
    is GoMap<*, *> -> "map"
    else -> arg::class.simpleName ?: "?"
}

private class Printer(private val args: Array<out Any?>, private val wrapErrs: Boolean = false) {

    val buf = StringBuilder()
    val wrappedArgs = ArrayList<Int>()

    // Current directive state.
    private var plus = false
    private var minus = false
    private var sharp = false
    private var zero = false
    private var space = false
    private var width = -1
    private var prec = -1

    private fun reset() {
        plus = false; minus = false; sharp = false; zero = false; space = false; width = -1; prec = -1
    }

    fun doPrintf(format: String): String {
        var argNum = 0
        var i = 0
        val end = format.length
        while (i < end) {
            val lasti = i
            while (i < end && format[i] != '%') i++
            if (i > lasti) buf.append(format, lasti, i)
            if (i >= end) break
            i++ // skip %
            reset()
            // flags
            flags@ while (i < end) {
                when (format[i]) {
                    '#' -> sharp = true
                    '0' -> zero = true
                    '+' -> plus = true
                    '-' -> minus = true
                    ' ' -> space = true
                    else -> break@flags
                }
                i++
            }
            // width
            if (i < end && format[i] == '*') {
                i++
                val w = args.getOrNull(argNum)
                argNum++
                if (w is Int) {
                    width = w
                    if (width < 0) { minus = true; width = -width }
                } else {
                    buf.append("%!(BADWIDTH)")
                }
            } else {
                var w = 0
                var any = false
                while (i < end && format[i] in '0'..'9') { w = w * 10 + (format[i] - '0'); i++; any = true }
                if (any) width = w
            }
            // precision
            if (i + 1 <= end && i < end && format[i] == '.') {
                i++
                if (i < end && format[i] == '*') {
                    i++
                    val pr = args.getOrNull(argNum)
                    argNum++
                    if (pr is Int && pr >= 0) prec = pr else buf.append("%!(BADPREC)")
                } else {
                    var pr = 0
                    while (i < end && format[i] in '0'..'9') { pr = pr * 10 + (format[i] - '0'); i++ }
                    prec = pr
                }
            }
            if (i >= end) {
                buf.append("%!(NOVERB)")
                break
            }
            val verb = format[i]
            i++
            if (zero && minus) zero = false
            when {
                verb == '%' -> buf.append('%')
                argNum >= args.size -> buf.append("%!").append(verb).append("(MISSING)")
                verb == 'w' -> {
                    if (wrapErrs && args[argNum] is GoError) {
                        wrappedArgs.add(argNum)
                        printArg(args[argNum], 'v')
                    } else {
                        badVerb(verb, args[argNum])
                    }
                    argNum++
                }
                else -> {
                    printArg(args[argNum], verb)
                    argNum++
                }
            }
        }
        if (argNum < args.size) {
            buf.append("%!(EXTRA ")
            for (k in argNum until args.size) {
                if (k > argNum) buf.append(", ")
                val arg = args[k]
                if (arg == null) buf.append("<nil>") else {
                    buf.append(typeName(arg)).append('=')
                    printArg(arg, 'v')
                }
            }
            buf.append(')')
        }
        return buf.toString()
    }

    private fun badVerb(verb: Char, arg: Any?) {
        buf.append("%!").append(verb).append('(')
        if (arg == null) buf.append("<nil>") else {
            buf.append(typeName(arg)).append('=')
            val saved = Triple(width, prec, zero)
            width = -1; prec = -1
            printArg(arg, 'v')
            width = saved.first; prec = saved.second
        }
        buf.append(')')
    }

    private fun pad(s: String) {
        val n = width - goRuneCount(s)
        if (width < 0 || n <= 0) {
            buf.append(s)
            return
        }
        if (minus) {
            buf.append(s)
            repeat(n) { buf.append(' ') }
        } else {
            repeat(n) { buf.append(' ') }
            buf.append(s)
        }
    }

    /** Pads a number: zero padding goes after the sign. */
    private fun padNumber(s: String) {
        if (zero && width > 0 && !minus) {
            val n = width - s.length
            if (n > 0) {
                val signed = s.isNotEmpty() && (s[0] == '-' || s[0] == '+' || s[0] == ' ')
                if (signed) {
                    buf.append(s[0])
                    repeat(n) { buf.append('0') }
                    buf.append(s, 1, s.length)
                } else {
                    repeat(n) { buf.append('0') }
                    buf.append(s)
                }
                return
            }
        }
        pad(s)
    }

    fun printArg(arg: Any?, verb: Char) {
        if (arg == null) {
            when (verb) {
                'T', 'v' -> pad("<nil>")
                else -> badVerb(verb, null)
            }
            return
        }
        if (verb == 'T') {
            pad(typeName(arg))
            return
        }
        // Go's handleMethods: error, then Stringer, for the string-ish verbs.
        if (verb in "vsxXq") {
            when (arg) {
                is GoError -> { fmtString(arg.error(), verb); return }
                is Stringer -> { fmtString(arg.string(), verb); return }
            }
        }
        when (arg) {
            is Boolean -> if (verb == 'v' || verb == 't') pad(if (arg) "true" else "false") else badVerb(verb, arg)
            is Int -> fmtInteger(arg.toLong(), false, verb)
            is Long -> fmtInteger(arg, false, verb)
            is UInt -> fmtInteger(arg.toLong(), true, verb)
            is ULong -> fmtUnsigned64(arg, verb)
            is Double -> fmtFloat(arg, verb)
            is Float -> fmtFloat(arg.toDouble(), verb)
            is String -> fmtString(arg, verb)
            is GoSlice<*> -> if (verb == 'v') {
                buf.append('[')
                for (k in 0 until arg.len) {
                    if (k > 0) buf.append(' ')
                    printArg(arg[k], 'v')
                }
                buf.append(']')
            } else {
                badVerb(verb, arg)
            }
            is GoMap<*, *> -> if (verb == 'v') {
                buf.append("map[")
                val entries = arg.entriesSnapshot().sortedBy { it.first.toString() }
                for ((k, e) in entries.withIndex()) {
                    if (k > 0) buf.append(' ')
                    printArg(e.first, 'v')
                    buf.append(':')
                    printArg(e.second, 'v')
                }
                buf.append(']')
            } else {
                badVerb(verb, arg)
            }
            else -> if (verb == 'v' || verb == 's') pad(arg.toString()) else badVerb(verb, arg)
        }
    }

    private fun fmtString(s: String, verb: Char) {
        when (verb) {
            'v', 's' -> pad(if (prec >= 0) truncateRunes(s, prec) else s)
            'q' -> pad(quote(s))
            'x', 'X' -> {
                val hex = if (verb == 'x') "0123456789abcdef" else "0123456789ABCDEF"
                val sb = StringBuilder()
                val src = if (prec >= 0 && prec < s.length) s.substring(0, prec) else s
                for ((k, c) in src.withIndex()) {
                    if (space && k > 0) sb.append(' ')
                    if (sharp && (space || k == 0)) sb.append(if (verb == 'x') "0x" else "0X")
                    sb.append(hex[c.code shr 4]).append(hex[c.code and 15])
                }
                pad(sb.toString())
            }
            else -> badVerb(verb, s)
        }
    }

    private fun truncateRunes(s: String, n: Int): String {
        var i = 0
        var count = 0
        while (i < s.length && count < n) {
            val c = s[i].code
            i += if (c < 0x80) 1 else (com.xemantic.typescript.tsgo.runtime.goDecodeRune(s, i) ushr 32).toInt()
            count++
        }
        return s.substring(0, i)
    }

    private fun fmtInteger(v: Long, unsigned: Boolean, verb: Char) {
        when (verb) {
            'v', 'd' -> integerText(v.toString().removePrefix("-"), v < 0, 10)
            'x' -> integerText(magnitude(v, unsigned, 16), v < 0 && !unsigned, 16)
            'X' -> integerText(magnitude(v, unsigned, 16).uppercase(), v < 0 && !unsigned, 16)
            'o' -> integerText(magnitude(v, unsigned, 8), v < 0 && !unsigned, 8)
            'b' -> integerText(magnitude(v, unsigned, 2), v < 0 && !unsigned, 2)
            'c' -> {
                val sb = StringBuilder()
                appendRuneBytes(sb, if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else -1)
                pad(sb.toString())
            }
            'q' -> {
                val sb = StringBuilder("'")
                appendRuneBytes(sb, v.toInt())
                sb.append('\'')
                pad(sb.toString())
            }
            'U' -> pad("U+" + v.toString(16).uppercase().padStart(4, '0'))
            else -> badVerb(verb, if (unsigned) v.toUInt() else v)
        }
    }

    private fun fmtUnsigned64(v: ULong, verb: Char) {
        when (verb) {
            'v', 'd' -> integerText(v.toString(), false, 10)
            'x' -> integerText(v.toString(16), false, 16)
            'X' -> integerText(v.toString(16).uppercase(), false, 16)
            'o' -> integerText(v.toString(8), false, 8)
            'b' -> integerText(v.toString(2), false, 2)
            else -> badVerb(verb, v)
        }
    }

    private fun magnitude(v: Long, unsigned: Boolean, radix: Int): String =
        if (unsigned) v.toULong().toString(radix) else if (v < 0) v.toULong().let { (0uL - it).toString(radix) } else v.toString(radix)

    private fun integerText(digits0: String, negative: Boolean, radix: Int) {
        var digits = digits0
        if (prec >= 0) {
            if (prec == 0 && digits == "0") digits = ""
            if (digits.length < prec) digits = "0".repeat(prec - digits.length) + digits
        }
        val prefix = if (sharp) when (radix) { 16 -> "0x"; 8 -> if (digits.startsWith("0")) "" else "0"; 2 -> "0b"; else -> "" } else ""
        val sign = if (negative) "-" else if (plus) "+" else if (space) " " else ""
        // Go zero-pads only when no precision is given.
        val savedZero = zero
        if (prec >= 0) zero = false
        padNumber(sign + prefix + digits)
        zero = savedZero
    }

    private fun fmtFloat(v: Double, verb: Char) {
        val s = when (verb) {
            'v', 'g' -> formatFloat(v, 'g'.code, prec, 64)
            'G' -> formatFloat(v, 'G'.code, prec, 64)
            'e', 'E', 'f', 'F' -> formatFloat(v, if (verb == 'F') 'f'.code else verb.code, if (prec < 0) 6 else prec, 64)
            else -> {
                badVerb(verb, v)
                return
            }
        }
        var out = s
        if (out == "+Inf" && !plus) out = if (space) " Inf" else "+Inf"
        if (plus && out[0] != '-' && out[0] != '+') out = "+$out"
        else if (space && out[0] != '-' && out[0] != '+') out = " $out"
        if (out.endsWith("Inf") || out == "NaN") {
            val savedZero = zero
            zero = false
            padNumber(out)
            zero = savedZero
        } else {
            padNumber(out)
        }
    }
}
