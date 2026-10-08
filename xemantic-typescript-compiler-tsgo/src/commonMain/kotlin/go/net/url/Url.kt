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

package com.xemantic.typescript.tsgo.go.net.url

// Go's `net/url`, the two functions tsgo's source-map baseline reaches
// (`tsbaseline.base64EncodeChunk`, (TSGO.3)): `QueryEscape` and `QueryUnescape` over Go byte
// strings (one char per byte), exactly as `escape`/`unescape` in `encodeQueryComponent` mode.

import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.Tuple2

/** `url.EscapeError`. */
class EscapeError(val value: String) : GoError {
    override fun error(): String = "invalid URL escape " + goQuote(value)
    override fun toString(): String = error()
}

private const val UPPER_HEX = "0123456789ABCDEF"

/** `shouldEscape(c, encodeQueryComponent)`: everything but the unreserved characters. */
private fun shouldEscape(c: Char): Boolean {
    if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') return false
    return when (c) {
        '-', '_', '.', '~' -> false
        else -> true // '$', '&', '+', ',', '/', ':', ';', '=', '?', '@' and every other byte
    }
}

/** `url.QueryEscape(s)`. */
fun queryEscape(s: String): String {
    var spaceCount = 0
    var hexCount = 0
    for (c in s) {
        if (shouldEscape(c)) {
            if (c == ' ') spaceCount++ else hexCount++
        }
    }
    if (spaceCount == 0 && hexCount == 0) return s
    val sb = StringBuilder(s.length + 2 * hexCount)
    for (c in s) {
        when {
            c == ' ' -> sb.append('+')
            shouldEscape(c) -> {
                val b = c.code and 0xFF
                sb.append('%').append(UPPER_HEX[b shr 4]).append(UPPER_HEX[b and 15])
            }
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private fun unhex(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> 0
}

/** `url.QueryUnescape(s)`. */
fun queryUnescape(s: String): Tuple2<String, GoError?> {
    var n = 0
    var hasPlus = false
    var i = 0
    while (i < s.length) {
        when (s[i]) {
            '%' -> {
                n++
                if (i + 2 >= s.length || !isHex(s[i + 1]) || !isHex(s[i + 2])) {
                    var rest = s.substring(i)
                    if (rest.length > 3) rest = rest.substring(0, 3)
                    return Tuple2("", EscapeError(rest))
                }
                i += 3
            }
            '+' -> { hasPlus = true; i++ }
            else -> i++
        }
    }
    if (n == 0 && !hasPlus) return Tuple2(s, null)
    val sb = StringBuilder(s.length - 2 * n)
    i = 0
    while (i < s.length) {
        when (val c = s[i]) {
            '%' -> { sb.append(((unhex(s[i + 1]) shl 4) or unhex(s[i + 2])).toChar()); i += 3 }
            '+' -> { sb.append(' '); i++ }
            else -> { sb.append(c); i++ }
        }
    }
    return Tuple2(sb.toString(), null)
}

/** `strconv.Quote` of the (at most 3 byte) offending escape, as EscapeError renders it. */
private fun goQuote(s: String): String = com.xemantic.typescript.tsgo.go.strconv.quote(s)

/** `shouldEscape(c, encodePathSegment)` (`url.PathEscape`). */
private fun shouldEscapePathSegment(c: Char): Boolean {
    if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') return false
    return when (c) {
        '-', '_', '.', '~' -> false
        '$', '&', '+', ':', '=', '@' -> false
        else -> true // '/', ';', ',', '?' and every other byte
    }
}

/** `url.PathEscape(s)`: escapes [s] so it can be placed inside a URL path segment. */
fun pathEscape(s: String): String {
    var hexCount = 0
    for (c in s) if (shouldEscapePathSegment(c)) hexCount++
    if (hexCount == 0) return s
    val sb = StringBuilder(s.length + 2 * hexCount)
    for (c in s) {
        if (shouldEscapePathSegment(c)) {
            val b = c.code and 0xFF
            sb.append('%').append(UPPER_HEX[b shr 4]).append(UPPER_HEX[b and 15])
        } else sb.append(c)
    }
    return sb.toString()
}

/** `url.URL`: the parts of a parsed URL tsgo reads. */
class URL(
    @kotlin.jvm.JvmField var scheme: String = "",
    @kotlin.jvm.JvmField var opaque: String = "",
    @kotlin.jvm.JvmField var host: String = "",
    @kotlin.jvm.JvmField var path: String = "",
    @kotlin.jvm.JvmField var rawQuery: String = "",
    @kotlin.jvm.JvmField var fragment: String = "",
)

/** `url.Error`. */
class Error(val op: String, val url: String, val err: GoError?) : GoError {
    override fun error(): String = op + " " + com.xemantic.typescript.tsgo.go.strconv.quote(url) + ": " + err?.error()
    override fun toString(): String = error()
}

private class PlainErr(val s: String) : GoError {
    override fun error(): String = s
}

/** `unescape(s, encodePath | encodeHost)`: `%XX` decoded, `+` kept. */
private fun unescapePath(s: String): Tuple2<String, GoError?> {
    if ('%' !in s) return Tuple2(s, null)
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%') {
            if (i + 2 >= s.length || !isHex(s[i + 1]) || !isHex(s[i + 2])) {
                var rest = s.substring(i)
                if (rest.length > 3) rest = rest.substring(0, 3)
                return Tuple2("", EscapeError(rest))
            }
            sb.append(((unhex(s[i + 1]) shl 4) or unhex(s[i + 2])).toChar())
            i += 3
        } else {
            sb.append(c)
            i++
        }
    }
    return Tuple2(sb.toString(), null)
}

/**
 * `url.Parse(rawURL)`: scheme, `//authority` (userinfo dropped, host unescaped), path (unescaped), query
 * and fragment — the shape every URI the language server hands tsgo has. An invalid escape is an error.
 */
fun parse(rawURL: String): Tuple2<URL?, GoError?> {
    var rest = rawURL
    val u = URL()
    val hash = rest.indexOf('#')
    if (hash >= 0) {
        val (frag, err) = unescapePath(rest.substring(hash + 1))
        if (err != null) return Tuple2(null, Error("parse", rawURL, err))
        u.fragment = frag
        rest = rest.substring(0, hash)
    }
    // getScheme: a letter, then letters, digits, '+', '-', '.' up to ':'.
    var i = 0
    while (i < rest.length) {
        val c = rest[i]
        if (c in 'a'..'z' || c in 'A'..'Z') {
            i++
            continue
        }
        if ((c in '0'..'9' || c == '+' || c == '-' || c == '.') && i > 0) {
            i++
            continue
        }
        break
    }
    if (i < rest.length && rest[i] == ':') {
        if (i == 0) return Tuple2(null, Error("parse", rawURL, PlainErr("missing protocol scheme")))
        u.scheme = rest.substring(0, i).lowercase()
        rest = rest.substring(i + 1)
    }
    val q = rest.indexOf('?')
    if (q >= 0) {
        u.rawQuery = rest.substring(q + 1)
        rest = rest.substring(0, q)
    }
    if (!rest.startsWith("/") && u.scheme != "") {
        u.opaque = rest
        return Tuple2(u, null)
    }
    if (rest.startsWith("//")) {
        val after = rest.substring(2)
        val slash = after.indexOf('/')
        var authority = if (slash < 0) after else after.substring(0, slash)
        rest = if (slash < 0) "" else after.substring(slash)
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        val (host, err) = unescapePath(authority)
        if (err != null) return Tuple2(null, Error("parse", rawURL, err))
        u.host = host
    }
    val (path, err) = unescapePath(rest)
    if (err != null) return Tuple2(null, Error("parse", rawURL, err))
    u.path = path
    return Tuple2(u, null)
}
