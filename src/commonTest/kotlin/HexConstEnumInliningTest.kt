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

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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

    private fun emitMulti(main: String): String {
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
    fun `same-file hex const enum inlines (Transformer collector)`() {
        val js = TypeScriptCompiler().compile(
            "// @target: es2020\nconst enum L { hex = 0x7F, dec = 127 }\nexport const a = L.hex;\nexport const b = L.dec;"
        ).javascript ?: error("no js")
        assertContains(js, "127 /* L.hex */")
        assertContains(js, "127 /* L.dec */")
    }

    @Test
    fun `cross-module hex const enum inlines (Checker enumValues)`() {
        val js = emitMulti(
            """
            import { Hex } from "./e";
            export const a = Hex.maxAscii;
            """.trimIndent()
        )
        assertContains(js, "127 /* Hex.maxAscii */")
        assertFalse("e_1.Hex" in js, "must not keep a qualified access, got:\n$js")
    }

    @Test
    fun `hex through a star-export BARREL inlines`() {
        val js = emitMulti(
            """
            import { Hex } from "./barrel";
            export const a = Hex.lf;
            """.trimIndent()
        )
        assertContains(js, "10 /* Hex.lf */")
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
        assertContains(js, "10 /* Bin.a */")
        assertContains(js, "15 /* Oct.a */")
    }

    @Test
    fun `a negative and a zero member still inline (regression guard)`() {
        val js = emitMulti(
            """
            import { Hex } from "./e";
            export const a = Hex.EOF;
            export const b = Hex.nul;
            """.trimIndent()
        )
        assertContains(js, "-1 /* Hex.EOF */")
        assertContains(js, "0 /* Hex.nul */")
    }

    // ── the shared parser itself ──────────────────────────────────────────

    @Test
    fun `tsNumericLiteralToDouble handles every base and separators`() {
        assertEquals(127.0, tsNumericLiteralToDouble("0x7F"))
        assertEquals(127.0, tsNumericLiteralToDouble("0X7f"))
        assertEquals(10.0, tsNumericLiteralToDouble("0b1010"))
        assertEquals(15.0, tsNumericLiteralToDouble("0o17"))
        assertEquals(1000000.0, tsNumericLiteralToDouble("1_000_000"))
        assertEquals(255.0, tsNumericLiteralToDouble("0xF_F"))
        assertEquals(1.5, tsNumericLiteralToDouble("1.5"))
        assertEquals(1500.0, tsNumericLiteralToDouble("1.5e3"))
    }

    @Test
    fun `negative control - a BigInt literal is not a const enum value`() {
        assertNull(tsNumericLiteralToDouble("123n"))
    }

    @Test
    fun `negative control - garbage text yields null rather than a wrong value`() {
        assertNull(tsNumericLiteralToDouble("0xZZ"))
        assertNull(tsNumericLiteralToDouble("nonsense"))
    }
}
