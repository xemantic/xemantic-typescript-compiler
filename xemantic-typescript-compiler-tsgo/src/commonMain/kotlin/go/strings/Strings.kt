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

package com.xemantic.typescript.tsgo.go.strings

import com.xemantic.typescript.tsgo.go.unicode.isSpace
import com.xemantic.typescript.tsgo.go.unicode.simpleFold
import com.xemantic.typescript.tsgo.go.unicode.toLower as unicodeToLower
import com.xemantic.typescript.tsgo.go.unicode.toUpper as unicodeToUpper
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.Tuple3
import com.xemantic.typescript.tsgo.runtime.appendRuneBytes
import com.xemantic.typescript.tsgo.runtime.goDecodeLastRune
import com.xemantic.typescript.tsgo.runtime.goDecodeRune
import com.xemantic.typescript.tsgo.runtime.goPanic
import com.xemantic.typescript.tsgo.runtime.goPanicSlice
import com.xemantic.typescript.tsgo.runtime.goRuneCount
import com.xemantic.typescript.tsgo.runtime.goRuneToString

// The Go `strings` package over byte strings (docs/goport-design.md § 3). Byte-oriented
// functions map onto Kotlin's `String` operations directly (every char is a byte); rune-oriented
// ones decode UTF-8 with Go's rules (an invalid byte is RuneError of width 1).

private const val RUNE_ERROR = 0xFFFD
private const val RUNE_SELF = 0x80

private fun runeAt(s: String, i: Int): Long {
    val c = s[i].code
    return if (c < RUNE_SELF) (1L shl 32) or c.toLong() else goDecodeRune(s, i)
}

private fun Long.rune(): Int = toInt()
private fun Long.width(): Int = (this ushr 32).toInt()

/** `strings.Compare(a, b)`. */
fun compare(a: String, b: String): Int {
    val c = a.compareTo(b)
    return if (c < 0) -1 else if (c > 0) 1 else 0
}

/** `strings.Contains(s, substr)`. */
fun contains(s: String, substr: String): Boolean = s.indexOf(substr) >= 0

/** `strings.ContainsAny(s, chars)`. */
fun containsAny(s: String, chars: String): Boolean = indexAny(s, chars) >= 0

/** `strings.ContainsRune(s, r)`. */
fun containsRune(s: String, r: Int): Boolean = indexRune(s, r) >= 0

/** `strings.ContainsFunc(s, f)`. */
fun containsFunc(s: String, f: (Int) -> Boolean): Boolean = indexFunc(s, f) >= 0

/** `strings.Count(s, substr)`: non-overlapping; `Count(s, "")` is the rune count + 1. */
fun count(s: String, substr: String): Int {
    if (substr.isEmpty()) return goRuneCount(s) + 1
    var n = 0
    var i = 0
    while (true) {
        val j = s.indexOf(substr, i)
        if (j < 0) return n
        n++
        i = j + substr.length
    }
}

/** `strings.Cut(s, sep)` → (before, after, found). */
fun cut(s: String, sep: String): Tuple3<String, String, Boolean> {
    val i = s.indexOf(sep)
    return if (i >= 0) Tuple3(s.substring(0, i), s.substring(i + sep.length), true) else Tuple3(s, "", false)
}

/** `strings.CutPrefix(s, prefix)` → (after, found). */
fun cutPrefix(s: String, prefix: String): Tuple2<String, Boolean> =
    if (s.startsWith(prefix)) Tuple2(s.substring(prefix.length), true) else Tuple2(s, false)

/** `strings.CutSuffix(s, suffix)` → (before, found). */
fun cutSuffix(s: String, suffix: String): Tuple2<String, Boolean> =
    if (s.endsWith(suffix)) Tuple2(s.substring(0, s.length - suffix.length), true) else Tuple2(s, false)

/** `strings.EqualFold(s, t)`: Unicode simple case folding, Go's algorithm. */
fun equalFold(s0: String, t0: String): Boolean {
    var s = 0
    var t = 0
    // ASCII fast path.
    while (s < s0.length && t < t0.length) {
        val sr0 = s0[s].code
        val tr0 = t0[t].code
        if ((sr0 or tr0) >= RUNE_SELF) break
        s++; t++
        if (tr0 == sr0) continue
        var lo = sr0
        var hi = tr0
        if (hi < lo) { val x = lo; lo = hi; hi = x }
        if ('A'.code <= lo && lo <= 'Z'.code && hi == lo + 'a'.code - 'A'.code) continue
        return false
    }
    if (s == s0.length || t == t0.length) return s == s0.length && t == t0.length
    while (s < s0.length && t < t0.length) {
        val ps = runeAt(s0, s)
        val pt = runeAt(t0, t)
        var sr = ps.rune()
        var tr = pt.rune()
        s += ps.width()
        t += pt.width()
        if (tr == sr) continue
        if (tr < sr) { val x = tr; tr = sr; sr = x }
        if (tr < RUNE_SELF) {
            if ('A'.code <= sr && sr <= 'Z'.code && tr == sr + 'a'.code - 'A'.code) continue
            return false
        }
        var r = simpleFold(sr)
        while (r != sr && r < tr) r = simpleFold(r)
        if (r == tr) continue
        return false
    }
    return s == s0.length && t == t0.length
}

/** `strings.HasPrefix(s, prefix)`. */
fun hasPrefix(s: String, prefix: String): Boolean = s.startsWith(prefix)

/** `strings.HasSuffix(s, suffix)`. */
fun hasSuffix(s: String, suffix: String): Boolean = s.endsWith(suffix)

/** `strings.Index(s, substr)`. */
fun index(s: String, substr: String): Int = s.indexOf(substr)

/** `strings.IndexByte(s, c)`. */
fun indexByte(s: String, c: Int): Int = s.indexOf((c and 0xFF).toChar())

/** `strings.IndexRune(s, r)`. */
fun indexRune(s: String, r: Int): Int {
    if (r in 0 until RUNE_SELF) return s.indexOf(r.toChar())
    if (r == RUNE_ERROR) {
        var i = 0
        while (i < s.length) {
            val p = runeAt(s, i)
            if (p.rune() == RUNE_ERROR) return i
            i += p.width()
        }
        return -1
    }
    if (r < 0 || r > 0x10FFFF || r in 0xD800..0xDFFF) return -1
    return s.indexOf(goRuneToString(r))
}

/** `strings.IndexAny(s, chars)`. */
fun indexAny(s: String, chars: String): Int {
    if (chars.isEmpty()) return -1
    var i = 0
    while (i < s.length) {
        val p = runeAt(s, i)
        if (indexRune(chars, p.rune()) >= 0) return i
        i += p.width()
    }
    return -1
}

/** `strings.IndexFunc(s, f)`. */
fun indexFunc(s: String, f: (Int) -> Boolean): Int {
    var i = 0
    while (i < s.length) {
        val p = runeAt(s, i)
        if (f(p.rune())) return i
        i += p.width()
    }
    return -1
}

/** `strings.LastIndexFunc(s, f)`. */
fun lastIndexFunc(s: String, f: (Int) -> Boolean): Int = lastIndexFuncTruth(s, f, true)

private fun lastIndexFuncTruth(s: String, f: (Int) -> Boolean, truth: Boolean): Int {
    var i = s.length
    while (i > 0) {
        val p = goDecodeLastRune(s, i)
        i -= p.width()
        if (f(p.rune()) == truth) return i
    }
    return -1
}

private fun indexFuncTruth(s: String, f: (Int) -> Boolean, truth: Boolean): Int {
    var i = 0
    while (i < s.length) {
        val p = runeAt(s, i)
        if (f(p.rune()) == truth) return i
        i += p.width()
    }
    return -1
}

/** `strings.Join(elems, sep)`. */
fun join(elems: GoSlice<String>, sep: String): String {
    if (elems.len == 0) return ""
    val sb = StringBuilder()
    for (i in 0 until elems.len) {
        if (i > 0) sb.append(sep)
        sb.append(elems[i])
    }
    return sb.toString()
}

/** `strings.LastIndex(s, substr)`. */
fun lastIndex(s: String, substr: String): Int = s.lastIndexOf(substr)

/** `strings.LastIndexByte(s, c)`. */
fun lastIndexByte(s: String, c: Int): Int = s.lastIndexOf((c and 0xFF).toChar())

/** `strings.Map(mapping, s)` — Go's two-phase algorithm, so an unchanged string keeps its invalid bytes. */
fun map(mapping: (Int) -> Int, s0: String): String {
    var s = s0
    var b: StringBuilder? = null
    var i = 0
    while (i < s.length) {
        val p = runeAt(s, i)
        var c = p.rune()
        val r = mapping(c)
        if (r == c && c != RUNE_ERROR) {
            i += p.width()
            continue
        }
        val width: Int
        if (c == RUNE_ERROR) {
            val q = goDecodeRune(s, i)
            c = q.rune()
            width = q.width()
            if (width != 1 && r == c) {
                i += width
                continue
            }
        } else {
            width = p.width()
        }
        val sb = StringBuilder(s.length + 4)
        sb.append(s, 0, i)
        if (r >= 0) appendRuneBytes(sb, r)
        s = s.substring(i + width)
        b = sb
        break
    }
    if (b == null) return s0
    var j = 0
    while (j < s.length) {
        val p = runeAt(s, j)
        val r = mapping(p.rune())
        if (r >= 0) appendRuneBytes(b, r)
        j += p.width()
    }
    return b.toString()
}

/** `strings.Repeat(s, count)`. */
fun repeat(s: String, count: Int): String {
    if (count < 0) goPanic("strings: negative Repeat count")
    return s.repeat(count)
}

/** `strings.Replace(s, old, new, n)`. */
fun replace(s: String, old: String, new: String, nIn: Int): String {
    if (old == new || nIn == 0) return s
    var n = nIn
    val m = count(s, old)
    if (m == 0) return s
    if (n < 0 || m < n) n = m
    val sb = StringBuilder(s.length + n * (new.length - old.length).coerceAtLeast(0))
    var start = 0
    for (k in 0 until n) {
        var j = start
        if (old.isEmpty()) {
            if (k > 0 && start < s.length) j += runeAt(s, start).width()
        } else {
            j += s.indexOf(old, start) - start
        }
        sb.append(s, start, j)
        sb.append(new)
        start = j + old.length
    }
    sb.append(s, start, s.length)
    return sb.toString()
}

/** `strings.ReplaceAll(s, old, new)`. */
fun replaceAll(s: String, old: String, new: String): String = replace(s, old, new, -1)

/** `strings.SplitSeq(s, sep)`: the substrings `Split` returns, as an iterator. */
fun splitSeq(s: String, sep: String): com.xemantic.typescript.tsgo.go.iter.Seq<String> = { yield ->
    val parts = genSplit(s, sep, 0, -1)
    for (i in 0 until parts.len) if (!yield(parts[i])) break
}

/** `strings.Split(s, sep)`. */
fun split(s: String, sep: String): GoSlice<String> = genSplit(s, sep, 0, -1)

/** `strings.SplitN(s, sep, n)`. */
fun splitN(s: String, sep: String, n: Int): GoSlice<String> = genSplit(s, sep, 0, n)

/** `strings.SplitAfter(s, sep)`. */
fun splitAfter(s: String, sep: String): GoSlice<String> = genSplit(s, sep, sep.length, -1)

private fun explode(s: String, nIn: Int): GoSlice<String> {
    val l = goRuneCount(s)
    val n = if (nIn < 0 || nIn > l) l else nIn
    val a = arrayOfNulls<Any?>(n)
    var rest = s
    for (i in 0 until n - 1) {
        val w = runeAt(rest, 0).width()
        a[i] = rest.substring(0, w)
        rest = rest.substring(w)
    }
    if (n > 0) a[n - 1] = rest
    return GoSlice.wrap(GoElem.STRING, a)
}

private fun genSplit(s0: String, sep: String, sepSave: Int, nIn: Int): GoSlice<String> {
    var n = nIn
    if (n == 0) return GoElem.STRING.nilSlice
    if (sep.isEmpty()) return explode(s0, n)
    if (n < 0) n = count(s0, sep) + 1
    if (n > s0.length + 1) n = s0.length + 1
    val a = arrayOfNulls<Any?>(n)
    n--
    var s = s0
    var i = 0
    while (i < n) {
        val m = s.indexOf(sep)
        if (m < 0) break
        a[i] = s.substring(0, m + sepSave)
        s = s.substring(m + sep.length)
        i++
    }
    a[i] = s
    return GoSlice.wrap(GoElem.STRING, a).slice(0, i + 1)
}

/** `strings.ToLower(s)`. */
fun toLower(s: String): String {
    var hasUpper = false
    for (c in s) {
        if (c.code >= RUNE_SELF) return map(::unicodeToLower, s)
        hasUpper = hasUpper || c in 'A'..'Z'
    }
    if (!hasUpper) return s
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(if (c in 'A'..'Z') c + ('a' - 'A') else c)
    return sb.toString()
}

/** `strings.ToUpper(s)`. */
fun toUpper(s: String): String {
    var hasLower = false
    for (c in s) {
        if (c.code >= RUNE_SELF) return map(::unicodeToUpper, s)
        hasLower = hasLower || c in 'a'..'z'
    }
    if (!hasLower) return s
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(if (c in 'a'..'z') c - ('a' - 'A') else c)
    return sb.toString()
}

/** `strings.ToValidUTF8(s, replacement)`: each run of invalid bytes becomes one [replacement]. */
fun toValidUTF8(s: String, replacement: String): String {
    val b = StringBuilder(s.length)
    var invalid = false
    var i = 0
    while (i < s.length) {
        val c = s[i].code
        if (c < RUNE_SELF) {
            i++
            invalid = false
            b.append(c.toChar())
            continue
        }
        val p = goDecodeRune(s, i)
        val wid = p.width()
        if (wid == 1) {
            i++
            if (!invalid) {
                invalid = true
                b.append(replacement)
            }
            continue
        }
        invalid = false
        b.append(s, i, i + wid)
        i += wid
    }
    return b.toString()
}

/** `strings.TrimFunc(s, f)`. */
fun trimFunc(s: String, f: (Int) -> Boolean): String = trimRightFunc(trimLeftFunc(s, f), f)

/** `strings.TrimLeftFunc(s, f)`. */
fun trimLeftFunc(s: String, f: (Int) -> Boolean): String {
    val i = indexFuncTruth(s, f, false)
    return if (i == -1) "" else s.substring(i)
}

/** `strings.TrimRightFunc(s, f)`. */
fun trimRightFunc(s: String, f: (Int) -> Boolean): String {
    var i = lastIndexFuncTruth(s, f, false)
    if (i >= 0 && s[i].code >= RUNE_SELF) {
        i += goDecodeRune(s, i).width()
    } else {
        i++
    }
    return s.substring(0, i)
}

private fun inCutset(cutset: String, r: Int): Boolean = indexRune(cutset, r) >= 0

/** `strings.TrimLeft(s, cutset)` (cutset is a set of runes). */
fun trimLeft(s: String, cutset: String): String {
    if (s.isEmpty() || cutset.isEmpty()) return s
    var i = 0
    while (i < s.length) {
        val p = runeAt(s, i)
        if (!inCutset(cutset, p.rune())) break
        i += p.width()
    }
    return s.substring(i)
}

/** `strings.TrimRight(s, cutset)`. */
fun trimRight(s: String, cutset: String): String {
    if (s.isEmpty() || cutset.isEmpty()) return s
    var end = s.length
    while (end > 0) {
        val p = goDecodeLastRune(s, end)
        if (!inCutset(cutset, p.rune())) break
        end -= p.width()
    }
    return s.substring(0, end)
}

/** `strings.Trim(s, cutset)`. */
fun trim(s: String, cutset: String): String = trimRight(trimLeft(s, cutset), cutset)

/** `strings.TrimPrefix(s, prefix)`. */
fun trimPrefix(s: String, prefix: String): String = if (s.startsWith(prefix)) s.substring(prefix.length) else s

/** `strings.TrimSuffix(s, suffix)`. */
fun trimSuffix(s: String, suffix: String): String = if (s.endsWith(suffix)) s.substring(0, s.length - suffix.length) else s

/** `strings.TrimSpace(s)`. */
fun trimSpace(s: String): String = trimFunc(s, ::isSpace)

/** `strings.Fields(s)`: runs of non-space runes. */
fun fields(s: String): GoSlice<String> {
    var out = GoElem.STRING.nilSlice
    var i = 0
    var start = -1
    while (i < s.length) {
        val p = runeAt(s, i)
        if (isSpace(p.rune())) {
            if (start >= 0) {
                out = out.append1(s.substring(start, i)); start = -1
            }
        } else if (start < 0) {
            start = i
        }
        i += p.width()
    }
    if (start >= 0) out = out.append1(s.substring(start))
    return if (out.isNil) GoSlice.make(GoElem.STRING, 0) else out
}

// ---------------------------------------------------------------------------------------------
// NOT Go API: the window variants the lowering fuses `F(s[from:], …)` / `F(s[from:to], …)` into
// (docs/goport-lowering.md § 3, "substring elimination"). Each checks Go's slice bounds, then
// answers exactly what `F` answers on the slice; an index result is RELATIVE to [from], as Go's is
// relative to the slice. Rune-decoding functions have only the suffix (`At`) form: a rune crossing
// a window's end decodes differently inside the window than in the base string.

private fun checkFrom(s: String, from: Int) {
    if (from < 0 || from > s.length) goPanicSlice("[$from:] with length ${s.length}")
}

private fun checkIn(s: String, from: Int, to: Int) {
    if (to < 0 || to > s.length) goPanicSlice("[:$to] with length ${s.length}")
    if (from < 0 || from > to) goPanicSlice("[$from:$to]")
}

/** `strings.HasPrefix(s[from:], prefix)`. */
fun hasPrefixAt(s: String, from: Int, prefix: String): Boolean {
    checkFrom(s, from)
    return s.startsWith(prefix, from)
}

/** `strings.HasPrefix(s[from:to], prefix)`. */
fun hasPrefixIn(s: String, from: Int, to: Int, prefix: String): Boolean {
    checkIn(s, from, to)
    return to - from >= prefix.length && s.startsWith(prefix, from)
}

/** `strings.HasSuffix(s[from:], suffix)`. */
fun hasSuffixAt(s: String, from: Int, suffix: String): Boolean {
    checkFrom(s, from)
    return s.length - from >= suffix.length && s.endsWith(suffix)
}

/** `strings.Index(s[from:to], substr)`, scanning only the window. */
private fun indexRange(s: String, from: Int, to: Int, substr: String): Int {
    val m = substr.length
    if (m == 0) return 0
    val first = substr[0]
    val last = to - m
    var i = from
    while (i <= last) {
        if (s[i] == first && s.regionMatches(i + 1, substr, 1, m - 1)) return i - from
        i++
    }
    return -1
}

/** `strings.Index(s[from:], substr)`. */
fun indexAt(s: String, from: Int, substr: String): Int {
    checkFrom(s, from)
    val r = s.indexOf(substr, from)
    return if (r < 0) -1 else r - from
}

/** `strings.Index(s[from:to], substr)`. */
fun indexIn(s: String, from: Int, to: Int, substr: String): Int {
    checkIn(s, from, to)
    return indexRange(s, from, to, substr)
}

/** `strings.LastIndex(s[from:], substr)`. */
fun lastIndexAt(s: String, from: Int, substr: String): Int {
    checkFrom(s, from)
    if (substr.isEmpty()) return s.length - from
    val r = s.lastIndexOf(substr)
    return if (r < from) -1 else r - from
}

/** `strings.LastIndex(s[from:to], substr)`, scanning only the window. */
fun lastIndexIn(s: String, from: Int, to: Int, substr: String): Int {
    checkIn(s, from, to)
    if (substr.isEmpty()) return to - from
    val r = s.lastIndexOf(substr, to - substr.length)
    return if (r < from) -1 else r - from
}

/** `strings.LastIndexByte(s[from:], c)`. */
fun lastIndexByteAt(s: String, from: Int, c: Int): Int = lastIndexByteIn(s, from, s.length, c)

/** `strings.LastIndexByte(s[from:to], c)`. */
fun lastIndexByteIn(s: String, from: Int, to: Int, c: Int): Int {
    checkIn(s, from, to)
    val r = s.lastIndexOf((c and 0xFF).toChar(), to - 1)
    return if (r < from) -1 else r - from
}

/** `strings.Contains(s[from:], substr)`. */
fun containsAt(s: String, from: Int, substr: String): Boolean = indexAt(s, from, substr) >= 0

/** `strings.Contains(s[from:to], substr)`. */
fun containsIn(s: String, from: Int, to: Int, substr: String): Boolean = indexIn(s, from, to, substr) >= 0

/** `strings.IndexByte(s[from:], c)`. */
fun indexByteAt(s: String, from: Int, c: Int): Int {
    checkFrom(s, from)
    val r = s.indexOf((c and 0xFF).toChar(), from)
    return if (r < 0) -1 else r - from
}

/** `strings.IndexByte(s[from:to], c)`. */
fun indexByteIn(s: String, from: Int, to: Int, c: Int): Int {
    checkIn(s, from, to)
    val ch = (c and 0xFF).toChar()
    var i = from
    while (i < to) {
        if (s[i] == ch) return i - from
        i++
    }
    return -1
}

/** `strings.IndexRune(s[from:], r)`. */
fun indexRuneAt(s: String, from: Int, r: Int): Int {
    checkFrom(s, from)
    if (r in 0 until RUNE_SELF) {
        val i = s.indexOf(r.toChar(), from)
        return if (i < 0) -1 else i - from
    }
    return indexRune(s.substring(from), r)
}

/** `strings.IndexAny(s[from:], chars)`. */
fun indexAnyAt(s: String, from: Int, chars: String): Int {
    checkFrom(s, from)
    if (chars.isEmpty()) return -1
    var i = from
    while (i < s.length) {
        val p = runeAt(s, i)
        if (indexRune(chars, p.rune()) >= 0) return i - from
        i += p.width()
    }
    return -1
}

/** `strings.ContainsAny(s[from:], chars)`. */
fun containsAnyAt(s: String, from: Int, chars: String): Boolean = indexAnyAt(s, from, chars) >= 0

/** `strings.Clone(s)`: Kotlin strings are immutable, so the string itself (Go allocates a copy; equal either way). */
fun clone(s: String): String = s

/**
 * `strings.Replacer`: [replace] scans left to right and, at each position, takes the old string
 * with the HIGHEST PRIORITY (the earliest argument pair) that is a prefix of the rest — not the
 * longest — exactly as Go's `genericReplacer` (whose byte-only special cases have the same
 * semantics). An empty old string matches at every position, but not twice in a row.
 */
class Replacer internal constructor(private val oldnew: Array<out String>) {

    /** `r.Replace(s)`. */
    fun replace(s: String): String {
        if (oldnew.isEmpty()) return s
        val sb = StringBuilder(s.length)
        var last = 0
        var prevMatchEmpty = false
        var i = 0
        while (i <= s.length) {
            var best = -1
            var k = 0
            while (k < oldnew.size) {
                val old = oldnew[k]
                if ((old.isNotEmpty() || !prevMatchEmpty) && s.startsWith(old, i)) {
                    best = k
                    break
                }
                k += 2
            }
            if (best >= 0) {
                val keylen = oldnew[best].length
                prevMatchEmpty = keylen == 0
                sb.append(s, last, i)
                sb.append(oldnew[best + 1])
                i += keylen
                last = i
                continue
            }
            prevMatchEmpty = false
            i++
        }
        if (last != s.length) sb.append(s, last, s.length)
        return sb.toString()
    }

    fun goCopy(): Replacer = this
}

/** `strings.NewReplacer(oldnew...)`: panics on an odd argument count, as Go does. */
fun newReplacer(vararg oldnew: String): Replacer {
    if (oldnew.size % 2 == 1) goPanic("strings.NewReplacer: odd argument count")
    return Replacer(oldnew)
}
