/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

class TextBuilder(
    @PublishedApi
    internal val appendable: Appendable,
) {

    @Suppress("NOTHING_TO_INLINE")
    inline operator fun String.unaryPlus() {
        appendable.append(this)
    }

    override fun toString(): String = appendable.toString()

}

inline fun text(
    crossinline block: TextBuilder.() -> Unit
): String {
    val builder = StringBuilder()
    builder.text { block() }
    return builder.toString()
}

inline fun Appendable.text(
    crossinline block: TextBuilder.() -> Unit
) {
    TextBuilder(this).block()
}
