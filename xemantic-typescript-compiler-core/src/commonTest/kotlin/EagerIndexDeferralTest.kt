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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (INC.53) `Checker`'s three whole-program INDEX field initializers are built on
 * FIRST ASK, so a build that does not need one does not pay for it.
 *
 * ## What this is a pin on
 *
 * Measured with a purpose-built front-end probe, `Checker`'s ~494 property
 * initializers cost 16-30 ms on EVERY build — and four of them are essentially all
 * of it, the other ~490 being 0.2-1.2 ms between them. On a 5.2 s full compile that
 * is 0.4%, which is why ~950 rounds never saw it; on a 63-72 ms INCREMENTAL FLOOR
 * it is ~30%, and the floor is what an editor pays per keystroke.
 *
 * The pass-gating arc ((INC.7)/(INC.20)/(INC.21)) could not have found this: it
 * swept `pass("…")` bodies, and a field initializer contributes to no `--passTiming`
 * row, no `cost_gate.py` counter and no diagnostic. That is the transferable half —
 * see [EagerIndexCensus].
 *
 * ## Why the assertions are COUNTS
 *
 * (INC.52)'s law. A per-pass row on a ~68 ms floor read 13.16 ms in one draw and
 * 8.42 in the next of the SAME binary, so a timed assertion here would be a coin
 * flip (round 868). [EagerIndexCensus] counts the POPULATION instead, which is
 * deterministic.
 *
 * The laziness is unconditional — there is no mode to arm — so these pins are on
 * the shipped default, which is (INC.16)'s law: a pin that installs the mode it
 * wants leaves the default pinned by nothing.
 *
 * ## The vacuity guard
 *
 * Every "it was not built" assertion is paired with one that it IS built when
 * asked, and with a VALUE assertion that the deferred index still answers — a pin
 * asserting only an absence passes against a binary that deleted the mechanism.
 */
class EagerIndexDeferralTest {

    /** The counters are process-global, so every test SAVES AND RESTORES them. */
    private fun <T> withCensus(block: () -> T): T {
        val lta = EagerIndexCensus.localTypeAliasFileScans
        val eii = EagerIndexCensus.enclosingImportBuilds
        val tlc = EagerIndexCensus.topLevelConstBuilds
        EagerIndexCensus.resetCounters()
        try {
            return block()
        } finally {
            EagerIndexCensus.localTypeAliasFileScans = lta
            EagerIndexCensus.enclosingImportBuilds = eii
            EagerIndexCensus.topLevelConstBuilds = tlc
        }
    }

    private fun bind(vararg files: Pair<String, String>): List<BinderResult> {
        val options = CompilerOptions()
        return files.map { (name, src) -> Binder(options).bind(Parser(src.trimIndent(), name).parse()) }
    }

    /**
     * Three files, each carrying a nested (B83.5-unbound) type alias, an import and a
     * top-level `const` string — i.e. every one of the three indices has something to
     * find in every file, so a count below the file total cannot be an empty program.
     */
    /**
     * Three files, each reaching [Checker.findLocalTypeAlias] — the ONE read site of
     * the per-file index. The shape is the one that helper exists for: a FUNCTION-LOCAL
     * (B83.5-unbound) discriminated-union alias used as the element type of an
     * array-literal assignment, which is what makes the named reference resolve to
     * `errorType` and fall through to the index.
     *
     * That the fixture REACHES it is the point: a first draft whose aliases were
     * ordinary local `type`s never called the helper at all, so the "fewer files than
     * the program" assertion below passed as 0 < 3 — a blind pin. The
     * `an unpartitioned build` test is the guard that caught it and is why it exists.
     */
    private val program = arrayOf(
        "/proj/a.ts" to """
            export interface Foo { x: number }
            export const KIND_A = "kind::a";
            export function fa() {
                type KindA = { k: "a"; a: number } | { k: "b"; b: string };
                const xs: KindA[] = [{ k: "a", a: 1 }, { k: "b", b: "s" }];
                return xs;
            }
        """,
        "/proj/b.ts" to """
            import { Foo } from "./a";
            export const KIND_B = "kind::b";
            export const useB: Foo = { x: 1 };
            export function fb() {
                type KindB = { k: "a"; a: number } | { k: "b"; b: string };
                const xs: KindB[] = [{ k: "a", a: 2 }, { k: "b", b: "t" }];
                return xs;
            }
        """,
        "/proj/c.ts" to """
            import { Foo } from "./a";
            export const KIND_C = "kind::c";
            export const useC: Foo = { x: 2 };
            export function fc() {
                type KindC = { k: "a"; a: number } | { k: "b"; b: string };
                const xs: KindC[] = [{ k: "a", a: 3 }, { k: "b", b: "u" }];
                return xs;
            }
        """,
    )

    @Test
    fun `a check partition that names no file of the program builds none of the three indices`() {
        withCensus {
            val results = bind(*program)
            runWithDeepStack {
                Checker(
                    CompilerOptions(), results, isMultiFileSource = true,
                    assignedFileNames = setOf("/proj/nowhere.ts"),
                ).getDiagnostics()
            }
            // The FLOOR of a language-service query: the checker checks nothing, so
            // none of the three whole-program indices has an asker.
            assert(EagerIndexCensus.localTypeAliasFileScans == 0)
            assert(EagerIndexCensus.enclosingImportBuilds == 0)
            assert(EagerIndexCensus.topLevelConstBuilds == 0)
        }
    }

    @Test
    fun `the nested-type-alias index is built per FILE, never for the whole program`() {
        withCensus {
            val results = bind(*program)
            runWithDeepStack {
                Checker(
                    CompilerOptions(), results, isMultiFileSource = true,
                    assignedFileNames = setOf("/proj/b.ts"),
                ).getDiagnostics()
            }
            // The eager form scanned all three; a narrowed build may only reach the
            // files it actually asks about, and it must not reach all of them.
            assert(EagerIndexCensus.localTypeAliasFileScans < results.size)
        }
    }

    /**
     * The vacuity guard for both counts above, and the reason they are not simply
     * measuring a checker that never ran: an UNPARTITIONED build does ask.
     */
    @Test
    fun `an unpartitioned build still builds the indices it needs`() {
        withCensus {
            val results = bind(*program)
            runWithDeepStack {
                Checker(CompilerOptions(), results, isMultiFileSource = true).getDiagnostics()
            }
            assert(EagerIndexCensus.localTypeAliasFileScans > 0)
        }
    }

    /**
     * The VALUE pin: a deferred index must still answer. `findLocalTypeAlias` is the
     * one read site, and it is reached through the nested-alias resolution that
     * [Checker.findLocalTypeAlias]'s own KDoc describes — so this asserts the program
     * type-checks exactly as it did, rather than asserting a count nobody consumes.
     */
    @Test
    fun `a deferred per-file alias index answers what the eager whole-program one answered`() {
        withCensus {
            val results = bind(*program)
            val diagnostics = runWithDeepStack {
                Checker(CompilerOptions(), results, isMultiFileSource = true).getDiagnostics()
            }
            assert(diagnostics.none { it.code == 2304 })
            assert(diagnostics.none { it.code == 2322 })
            assert(diagnostics.none { it.code == 2339 })
        }
    }
}
