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

package com.xemantic.typescript.tsgo.go.golang_org.x.text.language

import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoPlainError
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.Tuple3

/**
 * `language.Tag`: a BCP 47 tag, kept as its normalized string form. The zero value `Tag()` is
 * `und`, as Go's `language.Tag{}` is.
 *
 * APPROXIMATION: x/text canonicalizes tags against CLDR (aliases, script suppression, …); this
 * keeps the case-normalized subtags only. It is reached from `diagnostics`/`locale` (localized
 * message selection), not from the parser/encoder path.
 */
class Tag(private val s: String = "und") {
    fun string(): String = s

    /** A Go value copy: a `Tag` is immutable, so the value itself. */
    fun goCopy(): Tag = this

    /** Go struct `==`. */
    fun goEquals(other: Tag): Boolean = s == other.s

    fun goHash(): Int = s.hashCode()

    override fun equals(other: Any?): Boolean = other is Tag && other.s == s
    override fun hashCode(): Int = s.hashCode()
    override fun toString(): String = s
}

/** `language.Und`. */
val und: Tag = Tag("und")

/** `language.English`. */
val english: Tag = Tag("en")

/** `language.Confidence`. */
@kotlin.jvm.JvmInline
value class Confidence(val value: Int)

/** `language.No`, `Low`, `High`, `Exact`. */
val No: Confidence = Confidence(0)
val Low: Confidence = Confidence(1)
val High: Confidence = Confidence(2)
val Exact: Confidence = Confidence(3)

private fun normalize(s: String): String? {
    if (s.isEmpty()) return null
    val parts = s.replace('_', '-').split('-')
    if (parts.any { it.isEmpty() || it.length > 8 || !it.all { c -> c.isLetterOrDigit() && c.code < 0x80 } }) return null
    return parts.mapIndexed { i, p ->
        when {
            i == 0 -> p.lowercase()
            p.length == 2 -> p.uppercase()
            p.length == 4 -> p.lowercase().replaceFirstChar { it.uppercase() }
            else -> p.lowercase()
        }
    }.joinToString("-")
}

/** `language.Parse(s)`. */
fun parse(s: String): Tuple2<Tag, GoError?> {
    val n = normalize(s) ?: return Tuple2(und, GoPlainError("language: tag is not well-formed"))
    return Tuple2(Tag(n), null)
}

/** `language.MustParse(s)`. */
fun mustParse(s: String): Tag {
    val (t, err) = parse(s)
    if (err != null) com.xemantic.typescript.tsgo.runtime.goPanic(err)
    return t
}

/** `language.Matcher`. */
interface Matcher {
    /** `m.Match(want...)` → (tag, index, confidence). */
    fun match(vararg want: Tag): Tuple3<Tag, Int, Confidence>
}

/**
 * `language.NewMatcher(supported)`. APPROXIMATION: exact tag match, then a match on the language
 * subtag alone (Confidence High), else the first supported tag (Confidence No) — not CLDR's
 * distance-based matching.
 */
fun newMatcher(supported: com.xemantic.typescript.tsgo.runtime.GoSlice<Tag>): Matcher = object : Matcher {
    override fun match(vararg want: Tag): Tuple3<Tag, Int, Confidence> {
        for (w in want) for (i in 0 until supported.len) if (supported[i] == w) return Tuple3(supported[i], i, Exact)
        for (w in want) {
            val lang = w.string().substringBefore('-')
            for (i in 0 until supported.len) {
                if (supported[i].string().substringBefore('-') == lang) return Tuple3(supported[i], i, High)
            }
        }
        return Tuple3(if (supported.len > 0) supported[0] else und, 0, No)
    }
}
