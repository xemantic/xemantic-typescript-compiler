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

package com.xemantic.typescript.goport

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.goport.lower.Literals
import com.xemantic.typescript.goport.lower.TypeMapper
import com.xemantic.typescript.goport.naming.Naming
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

/**
 * Pins for lowering rules whose failure is SILENT (a wrong value that still compiles). The
 * end-to-end gate is `-tsgo`'s OracleParityTest (encoded-AST byte equality against tsgo).
 */
class LoweringRulesTest {

    private fun c(kind: String, v: String) = JsonObject(mapOf("kind" to JsonPrimitive(kind), "v" to JsonPrimitive(v)))

    @Test
    fun `Go names map to Kotlin lowerCamelCase as the shims spell them`() {
        assert(Naming.lowerCamel("GetTypeOfSymbol") == "getTypeOfSymbol")
        assert(Naming.lowerCamel("ID") == "id")
        assert(Naming.lowerCamel("URLPath") == "urlPath")
        assert(Naming.lowerCamel("ToValidUTF8") == "toValidUTF8")
        assert(Naming.lowerCamel("NaN") == "naN")
        assert(Naming.lowerCamel("x") == "x")
        assert(Naming.escape("is") == "`is`")
    }

    @Test
    fun `Go packages map to Kotlin packages`() {
        assert(Naming.kotlinPackage("github.com/microsoft/typescript-go/internal/api/encoder") == "com.xemantic.typescript.tsgo.api.encoder")
        assert(Naming.kotlinPackage("unicode/utf8") == "com.xemantic.typescript.tsgo.go.unicode.utf8")
        assert(Naming.kotlinPackage("golang.org/x/text/language") == "com.xemantic.typescript.tsgo.go.golang_org.x.text.language")
    }

    @Test
    fun `a Go string literal is emitted as its bytes, escaped for a Kotlin literal`() {
        // "é$\n" in UTF-8: C3 A9 24 0A — one Kotlin char per byte, `$` escaped (no template).
        val lit = Literals.byteString(byteArrayOf(0xC3.toByte(), 0xA9.toByte(), '$'.code.toByte(), '\n'.code.toByte(), '"'.code.toByte()))
        assert(lit == "\"\\u00C3\\u00A9\\$\\n\\\"\"")
    }

    @Test
    fun `integer constants keep their exact value at the edges of each representation`() {
        assert(Literals.raw(c("int", "-2147483648"), TypeMapper.Rep.INT).code == "Int.MIN_VALUE")
        assert(Literals.raw(c("int", "-9223372036854775808"), TypeMapper.Rep.LONG).code == "Long.MIN_VALUE")
        assert(Literals.raw(c("int", "4294967295"), TypeMapper.Rep.UINT).code == "4294967295u")
        assert(Literals.raw(c("int", "18446744073709551615"), TypeMapper.Rep.ULONG).code == "18446744073709551615uL")
        // A negative constant in an unsigned slot is its two's complement (Go `uint32(^uint32(0))` folds there).
        assert(Literals.raw(c("int", "-1"), TypeMapper.Rep.UINT).code == "4294967295u")
        // A negative literal is a prefix expression: it gets parentheses as an operand.
        assert(Literals.raw(c("int", "-5"), TypeMapper.Rep.INT).at(100) == "(-5)")
    }

    @Test
    fun `a constant out of the 32-bit int range is refused, not wrapped`() {
        val refused = try {
            Literals.raw(c("int", "9223372036854775807"), TypeMapper.Rep.INT)
            false
        } catch (_: com.xemantic.typescript.goport.lower.Refusal) {
            true
        }
        assert(refused)
    }
}
