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
 * (CHK.72) The flow walk's CALL right-hand-side shortcut reads a RETURN ANNOTATION
 * off ONE declaration, and for an OVERLOADED callee that declaration is the FIRST
 * one rather than the SELECTED one.
 *
 * [Checker.resolveFlowCalleeDecl] answers `symbol.valueDeclaration ?:
 * declarations.firstOrNull()` and performs no overload selection at all, so both of
 * its return-annotation consumers — [Checker.resolvedCallReturnTypeForFlow] (the
 * post-overwrite reset) and [Checker.callRhsHasNonNullishReturnAnnotation] (the
 * structural non-nullish claim behind [Checker.rhsIsDefinitelyNonNullish]) — were
 * answering about a signature the call does not select.
 *
 * **The reader every positive names is the DECLARATION one with a PRIMITIVE target**
 * (`const q: number = c`), which is the instrument that PRINTS the flow type in its
 * TS2322 message; the value is asserted, never merely the presence of a row. The
 * subject must be a local whose type is INFERRED from the call — a directly-read call
 * (`const q: number = f("x")`) goes through [Checker.getReturnTypeOfCallExpression],
 * which resolved the overload correctly the whole time and is GREEN on both binaries.
 * That asymmetry is the defect: the call path and the flow path disagreed.
 *
 * Two shipped failure modes, one per consumer, and they are not the same bug:
 *
 *  * `p1` — the first overload's return is itself NULLISH, so the reset arm fires and
 *    installs the WRONG overload's type (`Other | undefined` for a call that returns
 *    `Stats | undefined`). A wrong type, not a lost narrow.
 *  * `p2` — the first overload's return is NON-nullish, so the structural claim is
 *    true of it, the caller takes the overwrite branch and STRIPS the `| undefined`
 *    the selected overload genuinely has. A false negative at every later read.
 *
 * Every positive is RED on the parent binary; `c1`-`c3` are GREEN on both and are
 * labelled CONTROLS, not coverage. `c3` is the one that matters most: it proves the
 * fix selects an overload rather than merely refusing to narrow.
 *
 * Verified against tsgo 7.0.2 (`tools/tsgo-7.0.2/lib/tsc`), which reports
 * `Stats | undefined` at p1, p2 and c1 and `Stats` at c3.
 */
class OverloadedFlowCalleeReturnTest {

    private val prelude = """
        interface Stats { mtime: number; }
        interface Other { other: number; }
        declare function twoNullish(k: number): Other | undefined;
        declare function twoNullish(k: string): Stats | undefined;
        declare function firstNonNullish(k: number): Other;
        declare function firstNonNullish(k: string): Stats | undefined;
        declare function selectedNonNullish(k: number): Other;
        declare function selectedNonNullish(k: string): Stats;
        declare function single(k: string): Stats | undefined;
    """.trimIndent() + "\n"

    private fun d(body: String) = diagnose(prelude + body.trimIndent())

    private fun sourceTypeOfTs2322(ds: List<Diagnostic>): String? =
        ds.firstOrNull { it.code == 2322 }
            ?.message
            ?.substringAfter("Type '", "")
            ?.substringBefore("' is not assignable")

    // ---- positives ------------------------------------------------------------

    @Test
    fun `an overloaded callee whose first overload is nullish installs the SELECTED overload's return`() {
        val ds = d(
            """
            function p1(): void {
              const c = twoNullish("x");
              const q: number = c;
            }
            """,
        )
        // Parent binary answers `Other | undefined` — the FIRST overload's return.
        assert(sourceTypeOfTs2322(ds) == "Stats | undefined")
    }

    @Test
    fun `an overloaded callee whose first overload is non-nullish does not strip the selected overload's undefined`() {
        val ds = d(
            """
            function p2(): void {
              const c = firstNonNullish("x");
              const q: number = c;
            }
            """,
        )
        // Parent binary answers `Stats` — the overwrite branch fired on the FIRST
        // overload's non-nullish annotation and stripped a genuine `| undefined`.
        assert(sourceTypeOfTs2322(ds) == "Stats | undefined")
    }

    @Test
    fun `the same defect through a plain assignment rather than a declaration`() {
        val ds = d(
            """
            function p3(): void {
              let c: Stats | Other | undefined = undefined;
              c = firstNonNullish("x");
              const q: number = c;
            }
            """,
        )
        assert(sourceTypeOfTs2322(ds) == "Stats | undefined")
    }

    // ---- controls: green on BOTH binaries -------------------------------------

    @Test
    fun `CONTROL a NON-overloaded callee is unaffected`() {
        val ds = d(
            """
            function c1(): void {
              const c = single("x");
              const q: number = c;
            }
            """,
        )
        assert(sourceTypeOfTs2322(ds) == "Stats | undefined")
    }

    @Test
    fun `CONTROL the direct call reader resolved the overload correctly all along`() {
        val ds = d(
            """
            function c2(): void {
              const q: number = twoNullish("x");
            }
            """,
        )
        assert(sourceTypeOfTs2322(ds) == "Stats | undefined")
    }

    @Test
    fun `CONTROL an overload set whose SELECTED overload is non-nullish still narrows`() {
        val ds = d(
            """
            function c3(): void {
              const c = selectedNonNullish("x");
              const q: number = c;
            }
            """,
        )
        // Not "refuse to narrow": the selected overload's `Stats` must survive, so the
        // fix has to SELECT rather than merely decline the first declaration.
        assert(sourceTypeOfTs2322(ds) == "Stats")
    }
}
