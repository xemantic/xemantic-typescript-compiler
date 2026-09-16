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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.135) round (P18.117) — **A PROPERTY READ THROUGH AN INDEX SIGNATURE ANSWERED
 * `any`, AND A NUMERIC-NAMED STRING KEY NEVER REACHED A NUMBER INDEX SIGNATURE.**
 *
 * Two call sites of ONE applicability rule, tsc's `getApplicableIndexInfoForName`.
 *
 * **M1 — the PROPERTY-ACCESS miss path.** `computeRawTypeOfPropertyAccess` looked the
 * name up with `getPropertyOfType`, tried the tuple ((CHK.94)) and function ((CHK.134))
 * augmentations, and then fell through to `anyType`: an index signature was never
 * consulted for the TYPE. Round 479's `cmamIndexSignatureProvides` had already granted
 * such a name EXISTENCE — so `o.anything` on `{ [x: string]: number }` was silently
 * `any` while correctly producing no TS2339. Measured against tsgo 7.0.2, this is the
 * whole of the (CHK.135) gap for a hand-written index signature: the type literal, a
 * type ALIAS to one, an `interface` declaring one, a `class` declaring one, an
 * INHERITED one, and a receiver carrying both an index signature and declared members
 * (where the member must still win) were all silent here and all report in tsgo.
 *
 * **M4 — a numeric-literal STRING key against a NUMBER index signature.** Element
 * access already consulted the index signatures, but only through the operand's
 * PRIMITIVE flavour: a `StringLiteralNode` key types `string` ((CLAUDE.md:
 * `getTypeOfExpression` answers the BASE primitive for a literal node)), so it reached
 * the string index and never the number one. tsc's `isApplicableIndexType` has the
 * `target === numberType && isNumericLiteralName(source.value)` leg precisely for this,
 * and the PREFERENCE order was measured against tsgo on a receiver carrying both
 * indexes with different value types: `e["0"]` is the NUMBER index's type and `e["k"]`
 * the string index's, in both compilers.
 *
 * **WHAT THIS ROUND DID NOT LAND, MEASURED.** A mapped type whose key domain is the
 * whole of `string`/`number` — `Record<string, V>` — still resolves to bare `anyType`
 * (`getTypeFromMappedType` can only enumerate a LITERAL key domain). Building it costs
 * two rows in `consistentAliasVsNonAliasRecordBehavior`, whose mechanism is tsgo's
 * alias-variance probe (`relater.go:3391`, `source.alias.symbol == target.alias.symbol`)
 * — a shortcut this checker has no machinery for, and CLAUDE.md records global variance
 * as a measured dead end. The WRITE half (`o.x = v` / `o["k"] = v` against the index's
 * value type) is a separate emitter family and is also not landed. Both are recorded on
 * (CHK.135).
 *
 * The plain `{ [x: string]: number }` pins deliberately do NOT use the real libs, so
 * the rule is pinned independently of the lib snapshot; the `Record` pins that DO use
 * them are the literal-union CONTROL, which was already correct and must not move.
 */
class IndexSignatureReadTest {

    // ---------------------------------------------------------------- M1

    @Test
    fun `a property read through a plain string index signature answers the index type`() {
        val d = diagnose("""
            declare const holder: { [x: string]: number };
            const wrong: string = holder.anything;
        """)
        assert(d.size == 1)
        assert(d[0].code == 2322)
        assert(d[0].message == "Type 'number' is not assignable to type 'string'.")
        assert(d[0].line == 2)
        assert(d[0].character == 7)
    }

    @Test
    fun `a property read through an interface-declared index signature answers the index type`() {
        val d = diagnose("""
            interface Holder { [x: string]: number }
            declare const holder: Holder;
            const wrong: string = holder.anything;
        """)
        assert(d.size == 1)
        assert(d[0].code == 2322)
        assert(d[0].message == "Type 'number' is not assignable to type 'string'.")
        assert(d[0].line == 3)
    }

    @Test
    fun `a property read through a class-declared index signature answers the index type`() {
        diagnose("""
            class Holder { [x: string]: number }
            declare const holder: Holder;
            const wrong: string = holder.anything;
        """) should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
    }

    @Test
    fun `a property read through an INHERITED index signature answers the index type`() {
        diagnose("""
            interface Base { [x: string]: number }
            interface Derived extends Base { known: number }
            declare const holder: Derived;
            const wrong: string = holder.other;
        """) should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
    }

    @Test
    fun `a property read through a type ALIAS to an index signature answers the index type`() {
        diagnose("""
            type Holder = { [x: string]: number };
            declare const holder: Holder;
            const wrong: string = holder.anything;
        """) should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
    }

    @Test
    fun `a DECLARED member wins over the index signature that also covers its name`() {
        // `holder.declared` is `string` — the member — while `holder.other` is `number`.
        // Both rows report, and each names the type its own resolution produced: a rule
        // that let the index answer for a declared member would swap them.
        val d = diagnose("""
            declare const holder: { [x: string]: number; declared: number };
            const a: boolean = holder.declared;
            const b: boolean = holder.other;
        """).filter { it.code == 2322 }
        assert(d.size == 2)
        assert(d[0].message == "Type 'number' is not assignable to type 'boolean'.")
        assert(d[0].line == 2)
        assert(d[1].message == "Type 'number' is not assignable to type 'boolean'.")
        assert(d[1].line == 3)
    }

    @Test
    fun `an OPTIONAL index value type carries its undefined into the property read`() {
        diagnose("""
            declare const holder: { [x: string]: number | undefined };
            const wrong: string = holder.anything;
        """) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'number | undefined' is not assignable to type 'string'."
            })
        }
    }

    // ---------------------------------------------------------------- M4

    @Test
    fun `a numeric-named string key reaches a NUMBER index signature`() {
        val d = diagnose("""
            declare const holder: { [x: number]: string };
            const wrong: number = holder["0"];
        """)
        assert(d.size == 1)
        assert(d[0].code == 2322)
        assert(d[0].message == "Type 'string' is not assignable to type 'number'.")
        assert(d[0].line == 2)
    }

    @Test
    fun `a numeric-named string key PREFERS the number index over the string index`() {
        // tsc's `findApplicableIndexInfo` prefers an applicable non-string info; measured
        // identical in tsgo 7.0.2 on this exact receiver.
        val d = diagnose("""
            declare const holder: { [x: string]: string; [y: number]: number };
            const a: boolean = holder["0"];
            const b: boolean = holder["k"];
        """).filter { it.code == 2322 }
        assert(d.size == 2)
        assert(d[0].message == "Type 'number' is not assignable to type 'boolean'.")
        assert(d[1].message == "Type 'string' is not assignable to type 'boolean'.")
    }

    // ---------------------------------------------------------------- controls

    @Test
    fun `negative control - a receiver with NO index signature still reports TS2339`() {
        diagnose("""
            declare const holder: { known: number };
            const wrong = holder.missing;
        """) should {
            have(any {
                it.code == 2339 &&
                    it.message == "Property 'missing' does not exist on type '{ known: number; }'."
            })
        }
    }

    @Test
    fun `negative control - a NON-numeric string key does not reach a number index signature`() {
        diagnose("""
            declare const holder: { [x: number]: string };
            const wrong: number = holder["k"];
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `control - a literal-union Record still reports its read and its missing member`() {
        val d = diagnose(
            """
            declare const holder: Record<"a" | "b", number>;
            const wrong: string = holder.a;
            const missing = holder.nope;
            """,
            directives = "// @strict: true\n// @useRealLibs: true",
        )
        assert(d.any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        assert(d.any {
            it.code == 2339 &&
                it.message == "Property 'nope' does not exist on type 'Record<\"a\" | \"b\", number>'."
        })
    }
}
