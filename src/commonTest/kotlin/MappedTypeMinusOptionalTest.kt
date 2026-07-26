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
 * `Required<T>` — the standard `{ [P in keyof T]-?: T[P] }` — must REMOVE optionality,
 * and with it the `undefined` an optional source property contributes to `T[P]`.
 *
 * Found round 718 while burning down (LIB.1)'s real-lib false positives, where this is
 * eleven of the thirty-five: tsc's own `context.tracker.reportInferenceFallback(...)`
 * is declared `Required<Pick<SymbolTracker, "reportInferenceFallback">>` and we
 * reported TS2722 ("Cannot invoke an object which is possibly 'undefined'") on every
 * call.
 *
 * TWO reasons these cases run under `@useRealLibs` rather than the embedded lib, both
 * learned the hard way in the same round:
 *
 *  1. The embedded curated lib declares NO utility types at all — no `Required`, no
 *     `Pick`, no `Partial` — so on the default path `Required<…>` is an unresolved
 *     name that degrades to `any`, and `any` is silent. The defect is invisible there.
 *  2. Hand-rolling the mapped types locally (`type MyRequired<T> = { [P in keyof T]-?:
 *     T[P] }`) does NOT reproduce it: a first cut of this test did exactly that, and
 *     its CONTROLS came back empty — we emit nothing at all for those, so the target
 *     assertions were passing vacuously. Only the real lib declarations exercise the
 *     path the false positives come from.
 *
 * Every case here asserts on TS2722, deliberately, because that is the axis the fix
 * moves: the diagnostic gates on `isOptionalProperty(propSym)` — the SYMBOL's
 * optionality — and the codebase does not add `| undefined` to an optional member's
 * TYPE at all. An assignability-flavoured case (`const s: string = m.value` through
 * `Required`/`Partial`) therefore proves nothing here: a first cut had two, and they
 * were measuring an unrelated gap we do not model.
 *
 * Every case therefore carries its CONTROL — the same shape without the `-?` — so a
 * silent probe fails loudly instead of reading as a fix.
 */
class MappedTypeMinusOptionalTest {

    private val realLibs = "// @strict: true\n// @useRealLibs: true\n// @target: es2015"

    @Test
    fun `Required strips the undefined an optional method contributes so the call is not possibly undefined`() {
        val diagnostics = diagnose(
            """
            interface Tracker {
                report?(n: number): void
            }
            declare const b: Required<Pick<Tracker, "report">>
            b.report(1)
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2722 })
    }

    @Test
    fun `control - the same call through Pick alone stays possibly undefined`() {
        val diagnostics = diagnose(
            """
            interface Tracker {
                report?(n: number): void
            }
            declare const a: Pick<Tracker, "report">
            a.report(1)
            """,
            directives = realLibs,
        )
        // A silent control means the probe cannot see TS2722 here at all, in which
        // case the case above proves nothing.
        assert(diagnostics.any { it.code == 2722 })
    }

    @Test
    fun `Required applied directly to an interface also un-optionalises the method`() {
        val diagnostics = diagnose(
            """
            interface Tracker {
                report?(n: number): void
            }
            declare const t: Required<Tracker>
            t.report(1)
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2722 })
    }

    @Test
    fun `control - the same call on the bare interface stays possibly undefined`() {
        val diagnostics = diagnose(
            """
            interface Tracker {
                report?(n: number): void
            }
            declare const t: Tracker
            t.report(1)
            """,
            directives = realLibs,
        )
        assert(diagnostics.any { it.code == 2722 })
    }

    @Test
    fun `a required member is not disturbed by the mapped rewrite`() {
        val diagnostics = diagnose(
            """
            interface Tracker {
                keep(n: number): void
            }
            declare const t: Required<Tracker>
            t.keep(1)
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2722 })
    }
}
