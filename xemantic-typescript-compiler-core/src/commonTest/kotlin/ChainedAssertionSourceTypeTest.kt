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
import kotlin.test.Test

/**
 * (CHK.43) A type assertion's value has the ASSERTED type. When that type is one the
 * string-based assignability fallback cannot render — an array, a tuple, a function
 * type, a type literal — the fallback used to answer the OPERAND's type instead, and
 * for the `x as unknown as T` escape-hatch idiom the operand's type is `unknown`:
 * precisely the type the second assertion exists to assert away.
 *
 * The result was a SHIPPED false positive reachable at top level with no nesting:
 * `function m(): B | A | (B|A)[] { return r as unknown as B[] }` reported
 * `Type 'unknown' is not assignable to type 'B | A | (B | A)[]'` where
 * `tools/tsgo-7.0.2/lib/tsc` is silent.
 *
 * **WHY THE SHAPE LOOKED SO NARROW.** The string fallback runs only when the engine
 * has already declined to decide, and the engine decides — correctly, off the real
 * asserted type — whenever the source does NOT relate to the target. So the fallback
 * is reached only where the correct verdict is "assignable", and the FP therefore
 * needs a target that the WRONG source type (`unknown`) fails against while the right
 * one (`B[]`) passes. A union carrying an array member is such a target; a bare array
 * target and a union without one are not, which is why the first sighting read as a
 * ">= 3-member union" rule. It is not: `A | (B|A)[]` (two members) fires too.
 *
 * **THE PINS ARE IN BOTH DIRECTIONS ON PURPOSE.** A binary that simply stopped
 * emitting TS2322 for an assertion satisfies the negative half; the positive half is
 * the same chained-assertion shape against a target the asserted type genuinely does
 * not relate to, which must still report AND must name `B[]` — the asserted type —
 * as the source. Rows were read out of `tools/tsgo-7.0.2/lib/tsc`, which reports
 * `p1` byte-identically and is silent on `n1`/`n2`/`n3`.
 */
class ChainedAssertionSourceTypeTest {

    private val prelude = """
        interface A { a: number }
        interface B { b: number }
        interface C { c: number }
    """.trimIndent() + "\n"

    private fun diags(source: String) = diagnose(prelude + source.trimIndent())

    /** THE DEFECT, in the position it was found: a `return`. */
    @Test
    fun `a chained assertion in a return is the asserted type, not the inner one`() {
        assert(
            diags("export function n1(): B | A | (B|A)[] { const r: any = 0; return r as unknown as B[]; }")
                .isEmpty(),
        )
    }

    /** The same defect on the var-decl path — a different call site of the same helper. */
    @Test
    fun `a chained assertion in a variable declaration is the asserted type`() {
        assert(
            diags(
                "export function n2(): void { const r: any = 0; " +
                    "const x: B | A | (B|A)[] = r as unknown as B[]; void x; }",
            ).isEmpty(),
        )
    }

    /**
     * The two-member form. The item's first sighting recorded a ">= 3-member union"
     * trigger; the real rule is "the union carries an ARRAY member", and this row is
     * what separates the two.
     */
    @Test
    fun `a two-member union carrying an array member is silent too`() {
        assert(
            diags("export function n3(): A | (B|A)[] { const r: any = 0; return r as unknown as B[]; }")
                .isEmpty(),
        )
    }

    /**
     * THE POSITIVE HALF. The same chained assertion, against a union with no array
     * member, is a genuine error — and the message must name the ASSERTED type. A
     * binary that answered the operand's type would say `unknown` here; one that
     * stopped checking assertions altogether would say nothing.
     */
    @Test
    fun `a chained assertion that genuinely does not relate still reports, naming the asserted type`() {
        val d = diags("export function p1(): A | B | C { const r: any = 0; return r as unknown as B[]; }")
        val codes = d.map { it.code }
        val messages = d.map { it.message }
        assert(codes == listOf(2322))
        assert(messages == listOf("Type 'B[]' is not assignable to type 'A | B | C'."))
    }

    /** The var-decl mirror of the positive half. */
    @Test
    fun `a chained assertion assigned to an unrelated type still reports`() {
        val codes = diags(
            "export function p2(): void { const r: any = 0; const x: A = r as unknown as B[]; void x; }",
        ).map { it.code }
        assert(codes.isNotEmpty())
    }

    /**
     * The LEGACY `<T>expr` assertion spelling carries the identical rule, and it is
     * a separate `when` arm — so it needs its own row or half the fix is
     * un-gateable.
     */
    @Test
    fun `the angle-bracket assertion form is the asserted type too`() {
        assert(
            diags("export function n4(): B | A | (B|A)[] { const r: any = 0; return <B[]><unknown>r; }")
                .isEmpty(),
        )
    }

    /** …and its positive half. */
    @Test
    fun `an angle-bracket assertion that does not relate still reports`() {
        val d = diags("export function p4(): A | B { const r: any = 0; return <B[]><unknown>r; }")
        assert(d.map { it.code } == listOf(2322))
        assert(d.map { it.message } == listOf("Type 'B[]' is not assignable to type 'A | B'."))
    }

    /**
     * NEGATIVE CONTROL for the fix's scope: an assertion whose asserted type IS
     * renderable by the string fallback keeps being checked by it, so a wrong one
     * still reports. Nothing here was widened into a blanket "skip assertions".
     */
    @Test
    fun `an assertion to a simple named type that does not relate still reports`() {
        val codes = diags("export function p3(): A { const r: any = 0; return r as unknown as B; }")
            .map { it.code }
        assert(codes.isNotEmpty())
    }
}
