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

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * EP.2b (round 675): a const enum whose members are written in a NON-DECIMAL
 * base (hex / binary / octal) must inline like any other.
 *
 * The bug: all three const-enum evaluators parsed the literal with
 * `text.toDoubleOrNull()`, which is decimal-only — Kotlin accepts a hex FLOAT
 * (`0x1.8p3`) but not a hex INTEGER (`0x7F`), so the member silently became
 * un-inlinable. That is why tsc's `CharacterCodes` (almost entirely hex) kept
 * `ts_js_1.CharacterCodes.doubleQuote` in the emit while `SymbolFlags` and
 * `Extension` — same file, decimal and string valued — inlined fine. 638 of the
 * 675 residual un-inlined reads on the compiler profile were that one enum.
 *
 * THREE separate evaluators had to be fixed and each has its own path, so all
 * three are pinned: the Transformer's same-file collector, the Checker's
 * `literalConstantValue`, and the Checker's `evaluateEnumInitializer` (which
 * builds the cross-module `enumValues` table).
 */
class HexConstEnumInliningTest {

    private fun emitMulti(@Language("typescript") main: String): String {
        val source = """
            // @module: commonjs
            // @filename: e.ts
            export const enum Hex { EOF = -1, nul = 0, maxAscii = 0x7F, lf = 0x0A }
            export const enum Bin { a = 0b1010 }
            export const enum Oct { a = 0o17 }
            // @filename: barrel.ts
            export * from "./e";
            // @filename: m.ts
            $main
        """.trimIndent()
        return TypeScriptCompiler().compile(source, "m.ts").jsOutputs.joinToString("\n") { it.second }
    }

    @Test
    fun `same-file hex const enum inlines - Transformer collector`() {
        val js = TypeScriptCompiler().compile(
            "// @target: es2020\nconst enum L { hex = 0x7F, dec = 127 }\nexport const a = L.hex;\nexport const b = L.dec;"
        ).javascript ?: error("no js")
        assert("127 /* L.hex */" in js)
        assert("127 /* L.dec */" in js)
    }

    @Test
    fun `cross-module hex const enum inlines - Checker enumValues`() {
        val js = emitMulti(
            """
            import { Hex } from "./e";
            export const a = Hex.maxAscii;
            """.trimIndent()
        )
        assert("127 /* Hex.maxAscii */" in js)
        // Inlined: no qualified access to the imported enum may remain.
        assert("e_1.Hex" !in js)
    }

    @Test
    fun `hex through a star-export BARREL inlines`() {
        val js = emitMulti(
            """
            import { Hex } from "./barrel";
            export const a = Hex.lf;
            """.trimIndent()
        )
        assert("10 /* Hex.lf */" in js)
    }

    @Test
    fun `binary and octal literals inline too`() {
        val js = emitMulti(
            """
            import { Bin, Oct } from "./e";
            export const a = Bin.a;
            export const b = Oct.a;
            """.trimIndent()
        )
        assert("10 /* Bin.a */" in js)
        assert("15 /* Oct.a */" in js)
    }

    @Test
    fun `a negative and a zero member still inline - regression guard`() {
        val js = emitMulti(
            """
            import { Hex } from "./e";
            export const a = Hex.EOF;
            export const b = Hex.nul;
            """.trimIndent()
        )
        assert("-1 /* Hex.EOF */" in js)
        assert("0 /* Hex.nul */" in js)
    }

    // ── the shared parser itself ──────────────────────────────────────────

    @Test
    fun `tsNumericLiteralToDouble handles every base and separators`() {
        assert(tsNumericLiteralToDouble("0x7F") == 127.0)
        assert(tsNumericLiteralToDouble("0X7f") == 127.0)
        assert(tsNumericLiteralToDouble("0b1010") == 10.0)
        assert(tsNumericLiteralToDouble("0o17") == 15.0)
        assert(tsNumericLiteralToDouble("1_000_000") == 1000000.0)
        assert(tsNumericLiteralToDouble("0xF_F") == 255.0)
        assert(tsNumericLiteralToDouble("1.5") == 1.5)
        assert(tsNumericLiteralToDouble("1.5e3") == 1500.0)
    }

    @Test
    fun `negative control - a BigInt literal is not a const enum value`() {
        assert(tsNumericLiteralToDouble("123n") == null)
    }

    @Test
    fun `negative control - garbage text yields null rather than a wrong value`() {
        assert(tsNumericLiteralToDouble("0xZZ") == null)
        assert(tsNumericLiteralToDouble("nonsense") == null)
    }
}
