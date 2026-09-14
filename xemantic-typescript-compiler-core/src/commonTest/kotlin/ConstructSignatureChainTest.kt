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
 * (LEGACY.0b) step 11, family F10 — **TypeScript 7 SHORTENS the construct-signature
 * elaboration chain by two links.** TypeScript 6 prefixed it with
 * `Types of construct signatures are incompatible.` and then restated the mismatch at the
 * SIGNATURE level (`Type 'new (x: string) => C' is not assignable to type
 * 'new (x: number) => void'.`) before giving the reason; TypeScript 7 gives the reason
 * straight away.
 *
 * The rule is read off tsgo's source and is total, not conditional: `Relater.signaturesRelatedTo`
 * (`internal/checker/relater.go`) has NO call site for the header —
 * `Types_of_construct_signatures_are_incompatible` survives in tsgo's message table
 * (`diagnostics_generated.go:1185`) with **zero** references from its checker — and its
 * single-signature arm hands straight to `signatureRelatedTo`, which reports the reason itself.
 *
 * Four `tsgoPendingBaselines` rows: `assignmentCompatWithOverloads`,
 * `assignmentCompatability44`, `assignmentCompatability45`, `classSideInheritance3`.
 *
 * **EVERY EXPECTATION HERE WAS MEASURED AGAINST `tools/tsgo-7.0.2/lib/tsc`** over one project
 * carrying all four shapes; the chains below are byte-identical to its output. The corpus
 * screen reads 3,046 errors subtests and 0 mismatches across the change, so no green baseline
 * moves — but note it is the `.errors.txt` channel and the hand-written pins that gate this:
 * the 8-profile grid cannot see a display change at all ((PARITY.1)), and measured, all eight
 * profiles are `added=0 removed=0`.
 */
class ConstructSignatureChainTest {

    private fun chainOf(source: String): List<String> {
        val ds = diagnose(source).filter { it.code == 2322 }
        assert(ds.size == 1)
        return listOf(ds[0].message) + ds[0].messageChain
    }

    @Test
    fun `too few target arguments is the chains own second line`() {
        assert(
            chainOf(
                """
                class Foo { constructor(x: number) {} }
                const foo: { new(): Foo } = Foo;
                """
            ) == listOf(
                "Type 'typeof Foo' is not assignable to type 'new () => Foo'.",
                "  Target signature provides too few arguments. Expected 1 or more, but got 0.",
            )
        )
    }

    @Test
    fun `an abstract target keeps the shortened chain`() {
        assert(
            chainOf(
                """
                abstract class A { constructor() {} }
                class B { constructor(x: number) {} }
                const b: typeof A = B;
                """
            ) == listOf(
                "Type 'typeof B' is not assignable to type 'typeof A'.",
                "  Target signature provides too few arguments. Expected 1 or more, but got 0.",
            )
        )
    }

    @Test
    fun `a derived class widening its constructor reports at the class-side target`() {
        assert(
            chainOf(
                """
                class P { constructor(x: string) {} }
                class Q extends P { constructor(x: string, data: string) { super(x); } }
                var r1: typeof P = Q;
                """
            ) == listOf(
                "Type 'typeof Q' is not assignable to type 'typeof P'.",
                "  Target signature provides too few arguments. Expected 2 or more, but got 1.",
            )
        )
    }

    @Test
    fun `a parameter mismatch reports the parameter pair and its reason and nothing above them`() {
        assert(
            chainOf(
                """
                class C { constructor(n: number) {} }
                declare let d: { new (s: string): C };
                d = C;
                """
            ) == listOf(
                "Type 'typeof C' is not assignable to type 'new (s: string) => C'.",
                "  Types of parameters 'n' and 's' are incompatible.",
                "    Type 'string' is not assignable to type 'number'.",
            )
        )
    }

    @Test
    fun `a return-type mismatch is dedented with the rest`() {
        // The chain's THIRD reachable link, and the ablation of its indentation alone reads 0
        // RED without this pin — no corpus baseline exercises a construct-signature RETURN
        // mismatch whose parameters all relate. Measured byte-identical to tsgo 7.0.2.
        assert(
            chainOf(
                """
                class C { constructor(n: number) {} }
                declare let d: { new (n: number): string };
                d = C;
                """
            ) == listOf(
                "Type 'typeof C' is not assignable to type 'new (n: number) => string'.",
                "  Type 'C' is not assignable to type 'string'.",
            )
        )
    }

    @Test
    fun `negative control - a CALL-signature chain is untouched by the construct-side shortening`() {
        // TypeScript 6 and 7 agree here, and this compiler has never emitted a
        // `Types of call signatures are incompatible.` header at all — so the chain below is
        // what BOTH references print, and a construct-side edit that leaked into it would show.
        assert(
            chainOf(
                """
                declare let f: { (x: string): string; (x: number): number };
                declare let g: (s1: string) => number;
                g = f;
                """
            ) == listOf(
                "Type '{ (x: string): string; (x: number): number; }' is not assignable to type '(s1: string) => number'.",
                "  Type 'string' is not assignable to type 'number'.",
            )
        )
    }
}
