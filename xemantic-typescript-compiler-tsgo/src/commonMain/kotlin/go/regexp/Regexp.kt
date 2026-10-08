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

package com.xemantic.typescript.tsgo.go.regexp

import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoPlainError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.goDecodeRune
import com.xemantic.typescript.tsgo.runtime.goPanic

/**
 * `regexp.Regexp`, backed by Kotlin's `Regex` over the byte string, with the pattern TRANSLATED from
 * Go's RE2 syntax ([translateRe2]) where the two dialects disagree. Both engines are leftmost-first
 * (Perl) matchers, so a translated pattern finds the same matches; see [translateRe2] for what is
 * and is not covered (docs/goport-runtime.md § 10.7).
 */
class Regexp internal constructor(private val expr: String, private val re: Regex) {

    /** `re.ReplaceAllStringFunc(src, repl)`. */
    fun replaceAllStringFunc(src: String, repl: (String) -> String): String = re.replace(src) { repl(it.value) }

    /** `re.ReplaceAllString(src, repl)`: `$` expansion with Go's `Expand` rules ([expand]). */
    fun replaceAllString(src: String, repl: String): String {
        if (!repl.contains('$')) return re.replace(src) { repl }
        return re.replace(src) { expand(repl, it) }
    }

    /** `re.ReplaceAllLiteralString(src, repl)`. */
    fun replaceAllLiteralString(src: String, repl: String): String = re.replace(src) { repl }

    /** `re.MatchString(s)`. */
    fun matchString(s: String): Boolean = re.containsMatchIn(s)

    /** `re.FindString(s)` (`""` when there is no match). */
    fun findString(s: String): String = re.find(s)?.value ?: ""

    /** `re.FindStringSubmatch(s)` → the nil slice, or the groups (`""` for an unmatched group). */
    fun findStringSubmatch(s: String): GoSlice<String> {
        val m = re.find(s) ?: return GoElem.STRING.nilSlice
        return GoSlice.of(GoElem.STRING, *m.groupValues.toTypedArray())
    }

    /** `re.String()`: the ORIGINAL (Go) source. */
    fun string(): String = expr

    /**
     * Go's `allMatches`: successive leftmost matches from `pos`; an EMPTY match right after the
     * previous match's end is skipped, and after an empty match the scan advances one rune.
     * [deliver] gets (start, end); at most [n] matches when `n >= 0`.
     */
    private fun allMatches(s: String, n: Int, deliver: (Int, Int) -> Unit) {
        var pos = 0
        var i = 0
        var prevMatchEnd = -1
        val end = s.length
        while ((n < 0 || i < n) && pos <= end) {
            val m = re.find(s, pos) ?: break
            val m0 = m.range.first
            val m1 = m.range.last + 1
            var accept = true
            if (m1 == pos) {
                if (m0 == prevMatchEnd) accept = false
                pos += if (pos < end) ((goDecodeRune(s, pos) ushr 32).toInt()) else end + 1
            } else {
                pos = m1
            }
            prevMatchEnd = m1
            if (accept) {
                deliver(m0, m1)
                i++
            }
        }
    }

    /** `re.FindAllStringSubmatch(s, n)`: per match, the groups (`""` for an unmatched group); nil when none. */
    fun findAllStringSubmatch(s: String, n: Int): GoSlice<GoSlice<String>> {
        var out = GoElem.slice(GoElem.STRING).nilSlice
        allMatches(s, n) { a, _ ->
            val m = re.find(s, a)!!
            out = out.append1(GoSlice.of(GoElem.STRING, *m.groupValues.toTypedArray()))
        }
        return out
    }

    /** `re.FindAllString(s, n)`: the nil slice when there is no match. */
    fun findAllString(s: String, n: Int): GoSlice<String> {
        var out = GoElem.STRING.nilSlice
        allMatches(s, n) { a, b -> out = out.append1(s.substring(a, b)) }
        return out
    }

    /** `re.Split(s, n)`: Go's rules (`n == 0` → nil; an empty input with a non-empty pattern → `[""]`). */
    fun split(s: String, n: Int): GoSlice<String> {
        if (n == 0) return GoElem.STRING.nilSlice
        if (expr.isNotEmpty() && s.isEmpty()) return GoSlice.of(GoElem.STRING, "")
        val matches = ArrayList<IntArray>()
        allMatches(s, n) { a, b -> matches += intArrayOf(a, b) }
        var out = GoSlice.make(GoElem.STRING, 0, matches.size)
        var beg = 0
        var end = 0
        for (match in matches) {
            if (n > 0 && out.len == n - 1) break
            end = match[0]
            if (match[1] != 0) out = out.append1(s.substring(beg, end))
            beg = match[1]
        }
        if (end != s.length) out = out.append1(s.substring(beg))
        return out
    }

    /**
     * Go's `Regexp.expand` for a template: `$$` is `$`; `$name` takes the LONGEST run of
     * `[A-Za-z0-9_]`, `${name}` is braced; a number is a group index, anything else a group name;
     * an out-of-range index or unknown name expands to `""`, and a `$` not followed by a valid
     * reference is copied literally.
     */
    private fun expand(template: String, m: MatchResult): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c != '$' || i + 1 >= template.length) {
                sb.append(c)
                i++
                continue
            }
            if (template[i + 1] == '$') {
                sb.append('$')
                i += 2
                continue
            }
            val name: String
            val next: Int
            if (template[i + 1] == '{') {
                val close = template.indexOf('}', i + 2)
                val candidate = if (close < 0) "" else template.substring(i + 2, close)
                if (close < 0 || candidate.isEmpty() || !candidate.all { isWordChar(it) }) {
                    sb.append(c) // malformed: Go copies the `$` and continues after it
                    i++
                    continue
                }
                name = candidate
                next = close + 1
            } else {
                var j = i + 1
                while (j < template.length && isWordChar(template[j])) j++
                if (j == i + 1) {
                    sb.append(c)
                    i++
                    continue
                }
                name = template.substring(i + 1, j)
                next = j
            }
            sb.append(group(m, name))
            i = next
        }
        return sb.toString()
    }

    private fun group(m: MatchResult, name: String): String {
        val n = name.toIntOrNull()
        if (n != null && name.all { it in '0'..'9' }) {
            return if (n < m.groupValues.size) m.groupValues[n] else ""
        }
        val idx = groupNames.indexOf(name)
        return if (idx < 0) "" else m.groupValues.getOrElse(idx + 1) { "" }
    }

    /** Capture group names in order (`""` for an unnamed group), from the translated pattern. */
    private val groupNames: List<String> by lazy { captureNames(expr) }
}

private fun isWordChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

/** `regexp.MustCompile(expr)`: panics like Go on a pattern the engine rejects. */
fun mustCompile(expr: String): Regexp {
    val (re, err) = compile(expr)
    if (err != null) goPanic("regexp: Compile(" + quote(expr) + "): " + err.error())
    return re!!
}

/** `regexp.Compile(expr)`. */
fun compile(expr: String): Tuple2<Regexp?, GoError?> = try {
    Tuple2(Regexp(expr, Regex(translateRe2(expr))), null)
} catch (e: IllegalArgumentException) {
    Tuple2(null, GoPlainError("error parsing regexp: " + (e.message ?: "invalid pattern")))
}

/** `regexp.QuoteMeta(s)`: backslash-escapes every RE2 metacharacter `\.+*?()|[]{}^$`. */
fun quoteMeta(s: String): String {
    val sb = StringBuilder(s.length * 2)
    for (c in s) {
        if (c in "\\.+*?()|[]{}^$") sb.append('\\')
        sb.append(c)
    }
    return sb.toString()
}

private fun quote(s: String): String = "`$s`"

/** The capture-group names of an RE2 pattern in group order (`""` for an unnamed group). */
private fun captureNames(expr: String): List<String> {
    val names = ArrayList<String>()
    var i = 0
    var inClass = false
    while (i < expr.length) {
        val c = expr[i]
        when {
            c == '\\' -> i++
            inClass -> if (c == ']') inClass = false
            c == '[' -> {
                inClass = true
                if (i + 1 < expr.length && expr[i + 1] == '^') i++
                if (i + 1 < expr.length && expr[i + 1] == ']') i++
            }
            c == '(' -> {
                if (i + 1 < expr.length && expr[i + 1] == '?') {
                    val rest = expr.substring(i + 2)
                    val named = when {
                        rest.startsWith("P<") -> 3
                        rest.startsWith("<") -> 2
                        else -> -1
                    }
                    if (named > 0) {
                        val start = i + 1 + named
                        val end = expr.indexOf('>', start)
                        if (end > 0) names += expr.substring(start, end)
                    }
                } else {
                    names += ""
                }
            }
        }
        i++
    }
    return names
}

/** POSIX bracket classes RE2 accepts inside `[...]`, as ASCII class contents. */
private val POSIX_CLASSES: Map<String, String> = mapOf(
    "alnum" to "0-9A-Za-z",
    "alpha" to "A-Za-z",
    "ascii" to "\\x00-\\x7F",
    "blank" to "\\t ",
    "cntrl" to "\\x00-\\x1F\\x7F",
    "digit" to "0-9",
    "graph" to "!-~",
    "lower" to "a-z",
    "print" to " -~",
    "punct" to "!-/:-@\\[-`{-~",
    "space" to "\\t\\n\\x0B\\f\\r ",
    "upper" to "A-Z",
    "word" to "0-9A-Za-z_",
    "xdigit" to "0-9A-Fa-f",
)

private val REPETITION = Regex("""\{(\d+)(,(\d*))?\}""")

/** RE2's `\s`: `[\t\n\f\r ]` — Java's also matches `\x0B`. */
private const val RE2_SPACE = "\\t\\n\\f\\r "

/** "end of text", portable across the JVM, JS and Native engines (Go's `$` without `(?m)`, and `\z`). */
private const val END_OF_TEXT = "(?![\\s\\S])"

/** "start of text" (Go's `\A`). */
private const val START_OF_TEXT = "(?<![\\s\\S])"

/**
 * Translates Go RE2 syntax into the Java-dialect syntax Kotlin's `Regex` compiles. Rules (each where
 * RE2 and Java differ; verified against real Go in `RegexpOracleTest`):
 *
 * - `{` that does not start a valid repetition `{n}`, `{n,}`, `{n,m}` is a LITERAL in RE2 (`{(\d+)}`,
 *   `a{,2}`) and a syntax error in Java → `\{`; a lone `}` → `\}`.
 * - Inside a class, `[` is literal in RE2 (Java: nested class) → `\[`, except a POSIX class
 *   `[:name:]` / `[:^name:]`, which is expanded to its ASCII range; `&` → `\&` (Java's `&&` is
 *   intersection); `]` first in a class is literal in both, emitted as `\]`.
 * - `.` (outside a class) matches any char but `\n` in RE2; Java also excludes `\r`, `\u0085`,
 *   ` `, ` ` → `[^\n]`. Left alone when the pattern sets the `s` flag anywhere.
 * - `$` without the `m` flag is end-of-TEXT in RE2; Java's also matches before a final line
 *   terminator → a "no char follows" lookahead. Left alone when the pattern sets `m` anywhere.
 * - `\z` / `\A` → the same portable lookarounds (JS has neither escape).
 * - `\s` / `\S` (outside a class) → RE2's ASCII space set, which lacks `\x0B`; `\s` inside a class →
 *   its members.
 * - `(?P<name>` / `(?<name>` → a plain `(`: group names are resolved by index (Java's group-name
 *   syntax rejects `_`, RE2's does not).
 * - `\Q…\E` is expanded to escaped literals.
 *
 * NOT covered (refused with a panic where detectable): RE2's `(?U)` (ungreedy — Java's `(?U)` means
 * something else), `\C`; `\S` inside a class keeps Java's meaning (which excludes `\x0B` where RE2's
 * does not); with `(?m)`, Java's `$`/`^` also treat `\r`, `\u0085`, ` `, ` ` as line ends.
 * Byte strings make every non-ASCII class/`.` match ONE BYTE where Go matches one rune.
 */
fun translateRe2(expr: String): String {
    val dotAll = hasFlag(expr, 's')
    val multiLine = hasFlag(expr, 'm')
    if (hasFlag(expr, 'U')) goPanic("regexp shim: RE2's (?U) (ungreedy) flag is not translated")
    val out = StringBuilder(expr.length + 16)
    var i = 0
    var inClass = false
    var atomBefore = false // whether a repetition `{n}` would have something to repeat
    while (i < expr.length) {
        val c = expr[i]
        if (c == '\\') {
            if (i + 1 >= expr.length) {
                out.append("\\\\")
                i++
                continue
            }
            val e = expr[i + 1]
            if (e == 'Q') {
                val end = expr.indexOf("\\E", i + 2).let { if (it < 0) expr.length else it }
                for (ch in expr.substring(i + 2, end)) {
                    if (!(ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9')) out.append('\\')
                    out.append(ch)
                }
                i = if (end >= expr.length) end else end + 2
                atomBefore = true
                continue
            }
            if (e == 'C') goPanic("regexp shim: RE2's \\C is not translated")
            if (inClass) {
                when (e) {
                    's' -> out.append(RE2_SPACE)
                    else -> out.append(c).append(e)
                }
            } else {
                when (e) {
                    's' -> out.append('[').append(RE2_SPACE).append(']')
                    'S' -> out.append("[^").append(RE2_SPACE).append(']')
                    'z' -> out.append(END_OF_TEXT)
                    'A' -> out.append(START_OF_TEXT)
                    else -> out.append(c).append(e)
                }
            }
            i += 2
            // `\pN` / `\p{Name}` / `\x{…}`: copy the braced tail verbatim
            if ((e == 'p' || e == 'P' || e == 'x') && i < expr.length && expr[i] == '{') {
                val close = expr.indexOf('}', i)
                if (close > 0) {
                    out.append(expr, i, close + 1)
                    i = close + 1
                }
            }
            atomBefore = true
            continue
        }
        if (inClass) {
            when (c) {
                ']' -> {
                    out.append(']')
                    inClass = false
                    atomBefore = true
                }
                '[' -> {
                    val posix = posixAt(expr, i)
                    if (posix != null) {
                        out.append(posix.first)
                        i = posix.second
                        continue
                    }
                    out.append("\\[")
                }
                '&' -> out.append("\\&")
                else -> out.append(c)
            }
            i++
            continue
        }
        when (c) {
            '[' -> {
                out.append('[')
                i++
                if (i < expr.length && expr[i] == '^') {
                    out.append('^')
                    i++
                }
                if (i < expr.length && expr[i] == ']') {
                    out.append("\\]")
                    i++
                }
                inClass = true
                continue
            }
            '{' -> {
                val m = REPETITION.matchAt(expr, i)
                if (m != null && atomBefore) {
                    out.append(m.value)
                    i += m.value.length
                    atomBefore = true // RE2 accepts a repeated repetition as a literal-free sequence
                    continue
                }
                out.append("\\{")
                atomBefore = true
            }
            '}' -> {
                out.append("\\}")
                atomBefore = true
            }
            '.' -> {
                out.append(if (dotAll) "." else "[^\\n]")
                atomBefore = true
            }
            '$' -> {
                out.append(if (multiLine) "$" else END_OF_TEXT)
                atomBefore = false
            }
            '(' -> {
                // a named group becomes a plain capturing group: names are resolved by index
                // ([captureNames]), so Java's stricter name syntax (no `_`) never applies
                val named = when {
                    expr.startsWith("(?P<", i) -> 4
                    expr.startsWith("(?<", i) -> 3
                    else -> 0
                }
                if (named > 0) {
                    val close = expr.indexOf('>', i + named)
                    if (close > 0) {
                        out.append('(')
                        i = close + 1
                        atomBefore = false
                        continue
                    }
                }
                out.append(c)
                atomBefore = false
            }
            '|', '^' -> {
                out.append(c)
                atomBefore = false
            }
            else -> {
                out.append(c)
                atomBefore = true
            }
        }
        i++
    }
    return out.toString()
}

/** A POSIX class `[:name:]` / `[:^name:]` at [i] (inside a bracket) → (translation, index after it). */
private fun posixAt(expr: String, i: Int): Pair<String, Int>? {
    if (!expr.startsWith("[:", i)) return null
    val end = expr.indexOf(":]", i + 2)
    if (end < 0) return null
    var name = expr.substring(i + 2, end)
    val negated = name.startsWith("^")
    if (negated) name = name.substring(1)
    val body = POSIX_CLASSES[name] ?: return null
    // a negated POSIX class inside a bracket: Java's nested class syntax expresses it directly
    return Pair(if (negated) "[^$body]" else body, end + 2)
}

/** Whether a flag group `(?flags)` / `(?flags:` turns [flag] ON anywhere in [expr]. */
private fun hasFlag(expr: String, flag: Char): Boolean {
    var i = expr.indexOf("(?")
    while (i >= 0) {
        // skip an escaped `\(`
        if (i == 0 || expr[i - 1] != '\\') {
            var j = i + 2
            while (j < expr.length && expr[j] != ')' && expr[j] != ':' && expr[j] != '-') {
                if (expr[j] == flag) return true
                if (!expr[j].isLetter()) break
                j++
            }
        }
        i = expr.indexOf("(?", i + 2)
    }
    return false
}
