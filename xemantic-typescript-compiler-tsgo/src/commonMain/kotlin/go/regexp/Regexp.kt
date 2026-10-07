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
import com.xemantic.typescript.tsgo.runtime.GoSlice

/**
 * `regexp.Regexp`, backed by Kotlin's `Regex` over the byte string.
 *
 * APPROXIMATION: Go's RE2 syntax and Kotlin's (JVM/JS/Native) regex syntax and semantics agree
 * on the simple patterns tsgo's spike closure compiles — `{(\d+)}`, `\\.`, `_+`, `^_+(\D)`,
 * `_$` — and on byte strings (Go's `\d`/`\D` are ASCII, and a byte string's non-ASCII bytes are
 * single chars that `\d` never matches). Anything else (Unicode classes, RE2-only syntax,
 * leftmost-first vs backtracking differences) is NOT verified.
 */
class Regexp internal constructor(private val expr: String, private val re: Regex) {

    /** `re.ReplaceAllStringFunc(src, repl)`. */
    fun replaceAllStringFunc(src: String, repl: (String) -> String): String = re.replace(src) { repl(it.value) }

    /** `re.ReplaceAllString(src, repl)` — only for a [repl] without `$` expansions. */
    fun replaceAllString(src: String, repl: String): String {
        if (repl.contains('$')) TODO("shim: regexp.ReplaceAllString with \$ expansion")
        return re.replace(src) { repl }
    }

    /** `re.MatchString(s)`. */
    fun matchString(s: String): Boolean = re.containsMatchIn(s)

    /** `re.FindStringSubmatch(s)` → the nil slice, or the groups (`""` for an unmatched group). */
    fun findStringSubmatch(s: String): GoSlice<String> {
        val m = re.find(s) ?: return GoElem.STRING.nilSlice
        return GoSlice.of(GoElem.STRING, *m.groupValues.toTypedArray())
    }

    /** `re.String()`. */
    fun string(): String = expr
}

/** `regexp.MustCompile(expr)`. */
fun mustCompile(expr: String): Regexp = Regexp(expr, Regex(expr))
