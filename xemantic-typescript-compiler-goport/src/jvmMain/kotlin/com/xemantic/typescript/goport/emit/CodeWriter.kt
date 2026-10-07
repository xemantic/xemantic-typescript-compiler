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

package com.xemantic.typescript.goport.emit

/** An indenting Kotlin source writer. */
class CodeWriter(initialDepth: Int = 0) {

    private val sb = StringBuilder()

    /** The current indentation level (4 spaces each). */
    var depth: Int = initialDepth
        private set

    fun line(s: String = "") {
        if (s.isEmpty()) {
            sb.append('\n')
            return
        }
        repeat(depth) { sb.append("    ") }
        sb.append(s).append('\n')
    }

    /** Appends pre-rendered lines, re-indented to the current depth. */
    fun raw(text: String) {
        for (l in text.trimEnd('\n').split('\n')) line(l)
    }

    inline fun block(head: String, tail: String = "}", body: () -> Unit) {
        line("$head {")
        indent(body)
        line(tail)
    }

    inline fun indent(body: () -> Unit) {
        push()
        try {
            body()
        } finally {
            pop()
        }
    }

    fun push() {
        depth++
    }

    fun pop() {
        depth--
    }

    val length: Int get() = sb.length

    override fun toString(): String = sb.toString()
}

/** A rendered Kotlin expression and the precedence of its top-level operator (higher binds tighter). */
class Ex(val code: String, val prec: Int) {

    /** This expression as an operand needing at least [min] precedence. */
    fun at(min: Int): String = if (prec >= min) code else "($code)"

    override fun toString(): String = code

    companion object {
        const val PRIMARY = 100
        const val PREFIX = 90
        const val AS = 80
        const val MUL = 70
        const val ADD = 60
        const val INFIX = 50
        const val ELVIS = 45
        const val IS = 40
        const val CMP = 35
        const val EQ = 30
        const val AND = 20
        const val OR = 10
        const val LOWEST = 0

        fun primary(code: String) = Ex(code, PRIMARY)
    }
}
