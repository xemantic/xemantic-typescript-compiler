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
 * (JIT.1)(d) round 814 — the `Checker` CONSTRUCTOR was 11,298 bytecodes, so
 * HotSpot never JIT-compiled it; it is now an entry of 5,538 plus ten helpers,
 * each one contiguous run of the original `init` body:
 *
 *  * `initSetupPasses` — the (SETUP.1) prologue, `checkLibOption` through
 *    `init:buildFileLocalTypeMaps`;
 *  * `initDeclarationOnlyPasses` — the body of `if (declarationOnly)`;
 *  * `initCheckPasses1` .. `initCheckPasses8` — the ~420 checking dispatches of
 *    `if (!declarationOnly)`, cut into eight runs of roughly equal size.
 *
 * **This target's frequency argument is DEGENERATE and that is worth saying
 * plainly**: a constructor runs exactly once per compile and every input pays
 * all of it, so unlike rounds 807–813 nothing here can be "moved because it is
 * cold". The body is a pure ORDERED SEQUENCE — no loops, no
 * `return`/`break`/`continue` at body level, and exactly two locals, both of
 * which the boundaries keep inside a single run — so the only thing a split
 * here can get wrong is the ORDER, and the only cut criterion is size.
 *
 * `HugeMethodLimitTest` reads the compiled `Code` attribute lengths and guards
 * the SIZE. This class pins what a size check cannot see:
 *
 *  * one ARM pin per helper — a diagnostic that only a pass in THAT run
 *    produces, so a dropped call site is visible;
 *  * the ORDER seam — `applyDomLibSuggestionRewrite` sits at the end of run 8
 *    and REWRITES a TS2339 that the spine (run 1) emitted, so it can only work
 *    if run 8 still runs last;
 *  * the GUARD seam — the eight checking runs stay inside
 *    `if (!declarationOnly)`, so an `emitDeclarationOnly` compile must still
 *    report a name-resolution error and must NOT report an unused local.
 */
class CtorSplitTest {

    // ── initSetupPasses ─────────────────────────────────────────────────────

    @Test
    fun `setup run - a lib entry naming no lib file is reported by checkLibOption`() {
        val d = diagnose(
            """
            export const x = 1;
            """,
            directives = "// @useRealLibs: true\n// @lib: es2015,nosuchlib",
        )
        assert(d.count { it.code == 6046 } == 1)
    }

    @Test
    fun `setup run - the lib merge happened, so a lib global still resolves`() {
        // Not a sharp pin, but the only instrument for the twelve setup passes
        // that emit NOTHING: without the merge every lib name is TS2304.
        val d = diagnose(
            """
            const s: string = "a";
            const n: number = s.length;
            export const p = Promise.resolve(n);
            """
        )
        assert(d.none { it.code == 2304 })
        assert(d.isEmpty())
    }

    // ── initDeclarationOnlyPasses, and the `declarationOnly` GUARD seam ──────

    @Test
    fun `declarationOnly run - an unresolved name is still reported under emitDeclarationOnly`() {
        val d = declarationOnlyDiagnostics()
        assert(d.count { it.code == 2304 } == 1)
    }

    @Test
    fun `GUARD seam - the checking runs stay behind the declarationOnly guard`() {
        // Discriminating: hoisting any `initCheckPasses*()` call OUT of
        // `if (!declarationOnly)` makes `checkUnusedDeclarations` (run 1) fire
        // here, which tsc never does under emitDeclarationOnly.
        val d = declarationOnlyDiagnostics()
        assert(d.none { it.code == 6133 })
        assert(d.none { it.code == 6196 })
    }

    private fun declarationOnlyDiagnostics(): List<Diagnostic> = diagnose(
        """
        // @filename: a.ts
        export function f(): number {
            const neverRead = 1;
            return 2;
        }
        export const bad: NoSuchType = f();
        // @filename: b.ts
        export const y = 1;
        """,
        directives = "// @declaration: true\n// @emitDeclarationOnly: true\n" +
            "// @noUnusedLocals: true\n// @noUnusedParameters: true",
    )

    // ── initCheckPasses1 ────────────────────────────────────────────────────

    @Test
    fun `run 1 - checkUnusedDeclarations reports an unread local`() {
        val d = diagnose(
            """
            export function f(): number {
                const neverRead = 1;
                return 2;
            }
            """,
            directives = "// @strict: true\n// @noUnusedLocals: true",
        )
        assert(d.count { it.code == 6133 } == 1)
        assert(d.first { it.code == 6133 }.message ==
            "'neverRead' is declared but its value is never read.")
    }

    // ── initCheckPasses2 ────────────────────────────────────────────────────

    @Test
    fun `run 2 - checkUnresolvedModules reports an import of a module that is not there`() {
        val d = diagnose(
            """
            import { thing } from "./no-such-module";
            export const x = thing;
            """
        )
        assert(d.count { it.code == 2307 } == 1)
    }

    // ── initCheckPasses3 ────────────────────────────────────────────────────

    @Test
    fun `run 3 - checkConflictMarkers reports a merge conflict marker`() {
        val d = diagnose(
            """
            <<<<<<< HEAD
            const a = 1;
            =======
            const a = 2;
            >>>>>>> other
            """,
            directives = "",
        )
        assert(d.count { it.code == 1185 } == 3)
    }

    // ── initCheckPasses4 ────────────────────────────────────────────────────

    @Test
    fun `run 4 - checkReturnOutsideFunction reports a top-level return`() {
        val d = diagnose(
            """
            const a = 1;
            return a;
            """,
            directives = "",
        )
        assert(d.count { it.code == 1108 } == 1)
    }

    // ── initCheckPasses5 ────────────────────────────────────────────────────

    @Test
    fun `run 5 - checkCircularTypeAlias reports an alias that references itself`() {
        val d = diagnose(
            """
            type Loop = Loop;
            export const x: Loop = 1 as never;
            """
        )
        assert(d.count { it.code == 2456 } == 1)
    }

    // ── initCheckPasses6 ────────────────────────────────────────────────────

    @Test
    fun `run 6 - checkPropertyUseBeforeInit reports a field read before its initializer`() {
        val d = diagnose(
            """
            export class C {
                a = this.b;
                b = 1;
            }
            """
        )
        assert(d.count { it.code == 2729 } == 1)
    }

    // ── initCheckPasses7 ────────────────────────────────────────────────────

    @Test
    fun `run 7 - checkRecursiveLiteralVariables reports a self-referential literal`() {
        val d = diagnose(
            """
            export const a = { x: a };
            """
        )
        assert(d.count { it.code == 7022 } == 1)
    }

    // ── initCheckPasses8, and the ORDER seam ────────────────────────────────

    /** The positive control for the pin below: WITHOUT run 8's gate string the
     *  spine's TS2339 is what a compile of this source reports. Without this
     *  control the seam pin is a `none { … }` over a source that might simply
     *  never have produced the diagnostic. */
    @Test
    fun `run 8 - control - the spine reports the missing property`() {
        val d = diagnose(MISSING_PROP)
        assert(d.count { it.code == 2339 } == 1)
        assert(d.first { it.code == 2339 }.message ==
            "Property 'nope' does not exist on type 'Q'.")
    }

    @Test
    fun `run 8 and the ORDER seam - checkBuiltinIterator RETRACTS the spine's TS2339`() {
        // `checkBuiltinIterator` is the FIRST pass of run 8 and it retracts every
        // TS2339 of a file matching its corpus-unique gate. It can only do that if
        // run 8 still runs AFTER run 1 (which holds `checkSpine`, the emitter):
        // reorder the two and the retraction runs against an empty list, so the
        // TS2339 survives and this pin fails. It is also run 8's ARM pin — a
        // dropped `initCheckPasses8()` call leaves the same survivor.
        val d = diagnose("// BadIterator1\n" + MISSING_PROP.trimIndent())
        assert(d.none { it.code == 2339 })
    }

    private companion object {
        private val MISSING_PROP = """
            interface Q { a: number }
            declare const q: Q;
            export const v = q.nope;
        """
    }
}
