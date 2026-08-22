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
 * (INC.10) THE WHOLE-PROGRAM ALIAS-REFERENCE WALK RUNS ON THE ASK, AND IMPORT
 * ELISION IS UNMOVED.
 *
 * `init:trackAllImportReferences` was **29.4 ms of a 393 ms incremental floor**
 * — the second-largest row a language-service error query paid for, and one
 * whose entire product is read by the EMIT path alone. `referencedAliases` has
 * one reader, `Checker.isReferencedAliasDeclaration`, and one caller of that
 * reader, `Transformer`; round 738's `skipEmitOutputs` gate means a `--noEmit`
 * build never constructs a `Transformer` at all. So on every query the walk ran
 * to fill a set nothing could read.
 *
 * Two halves are pinned here and they fail in opposite directions.
 *
 *  * **The deferral happened.** A freshly constructed `Checker` has NOT walked;
 *    the first `isReferencedAliasDeclaration` is what makes it walk. Restoring
 *    the eager pass leaves the compiler correct and reddens this half — which is
 *    the only thing that separates a landed change from an inert one.
 *  * **The answer is unchanged.** Import elision is decided by that set, so a
 *    walk that does not run elides a LIVE import and changes emitted JavaScript.
 *    Dropping the `ensureImportReferencesTracked()` call from the ask reddens
 *    this half and nothing else.
 *
 * The elision fixture had to be found rather than chosen. `referencedAliases`
 * has ONE consumer and it is narrow: a single line of `Transformer` reached only
 * by `import x = require(…)` under `module: preserve` without
 * `verbatimModuleSyntax`. An ordinary ESM `import { a } from "./x"` fixture does
 * not reach it at all — measured, ESM elision answers correctly with the set
 * empty, so the first draft of these pins passed against a deliberately broken
 * deferral. It is kept below as a CONTROL, because that fact is also what bounds
 * the blast radius of this change.
 */
class DeferredSetupPassTest {

    private fun checkerOver(source: String, fileName: String): Pair<Checker, List<BinderResult>> {
        val options = CompilerOptions()
        val results = listOf(Binder(options).bind(Parser(source, fileName).parse()))
        return Checker(options, results, isMultiFileSource = true) to results
    }

    /**
     * The ONE consumer, spelled exactly. `isReferencedAliasDeclaration` is
     * reached from a single line of `Transformer` — an `import x = require(…)`
     * under `module: preserve` without `verbatimModuleSyntax` — so this is the
     * only shape in the language whose emitted JavaScript is a function of
     * [referencedAliases]. A fixture that merely imports and uses a name in ESM
     * does NOT reach it: measured, the ESM elision path answers correctly with
     * the set EMPTY, so such a fixture passes on a broken deferral. That draft
     * was written, ablated, found vacuous, and replaced by this one.
     */
    private val requirePreserve = """
        // @module: preserve
        // @Filename: dep.ts
        export const v: number = 1;
        // @Filename: main.ts
        import used = require("./dep");
        import unused = require("./dep");
        export const out: number = used.v;
    """.trimIndent()

    private fun mainJs(source: String) =
        TypeScriptCompiler().compile(source, "input.ts")
            .jsOutputs.first { it.first.endsWith("main.js") }.second

    @Test
    fun `a fresh checker has not walked alias references`() {
        val (checker, _) = checkerOver(
            "import { a } from \"./x\";\nexport const b = a;\n",
            "/proj/m.ts",
        )
        // DISCRIMINATING: an eager `init:trackAllImportReferences` makes this true
        // before anybody asks, which is exactly the pre-(INC.10) state.
        assert(!checker.importReferencesTracked)
    }

    @Test
    fun `asking whether an alias is referenced is what performs the walk`() {
        val (checker, results) = checkerOver(
            "import { a } from \"./x\";\nexport const b = a;\n",
            "/proj/m.ts",
        )
        val importDecl = results[0].sourceFile.statements.first { it is ImportDeclaration }
        checker.isReferencedAliasDeclaration(importDecl)
        assert(checker.importReferencesTracked)
    }

    @Test
    fun `a second ask does not walk again`() {
        val (checker, results) = checkerOver(
            "import { a } from \"./x\";\nexport const b = a;\n",
            "/proj/m.ts",
        )
        val importDecl = results[0].sourceFile.statements.first { it is ImportDeclaration }
        val first = checker.isReferencedAliasDeclaration(importDecl)
        val second = checker.isReferencedAliasDeclaration(importDecl)
        // The ANSWER half is a control — an eager build satisfies it too. It says
        // the memo is a memo, i.e. that "runs once" is a performance claim and
        // not a correctness one.
        assert(second == first)
        assert(checker.importReferencesTracked)
    }

    @Test
    fun `a referenced import-require survives elision through the deferred walk`() {
        val js = mainJs(requirePreserve)
        // DISCRIMINATING: with the walk never run, [referencedAliases] is empty,
        // this alias reads as unreferenced and the whole statement is elided.
        assert(js.contains("used"))
        assert(js.contains("require(\"./dep\")"))
    }

    @Test
    fun `an unreferenced import-require is still elided through the deferred walk`() {
        val js = mainJs(requirePreserve)
        // The other direction, and the reason the fixture declares TWO imports of
        // one module: a walk that marked everything would satisfy the assertion
        // above by accident.
        assert(!js.contains("unused"))
    }

    @Test
    fun `negative control - the emitted program is not empty`() {
        // Round 790's law: a "the name is absent" assertion reads the same on a
        // correct build and on one that emitted nothing at all.
        val js = mainJs(requirePreserve)
        assert(js.isNotEmpty())
        assert(js.contains("out"))
    }

    @Test
    fun `control - ESM elision does not go through this set at all`() {
        // Recorded because it is what makes the fixture above the only usable
        // one, and because it bounds the blast radius of the deferral: the ESM
        // import path elides a type-only import on its own evidence, so the
        // whole of [referencedAliases] answers for `import x = require(…)` under
        // `module: preserve` and nothing else.
        val js = mainJs(
            """
            // @Filename: dep.ts
            export function used(n: number): number { return n; }
            export interface Shape { readonly n: number }
            // @Filename: main.ts
            import { used } from "./dep";
            import { Shape } from "./dep";
            export const out: number = used(1);
            export const shaped: Shape = { n: 2 };
            """.trimIndent()
        )
        assert(js.contains("used"))
        assert(!js.contains("Shape"))
    }

    @Test
    fun `the pass table no longer carries an init row for the walk`() {
        PassTiming.reset()
        PassTiming.enabled = true
        try {
            diagnose("export const x: number = 1;")
        } finally {
            PassTiming.enabled = false
        }
        val recorded = LinkedHashMap(PassTiming.passNanos)
        // DISCRIMINATING: restoring `pass("init:trackAllImportReferences") { … }`
        // reddens exactly this. `SetupPhasePartitionTest` pins the surviving
        // sixteen; this pins the one that left.
        assert("init:trackAllImportReferences" !in recorded)
        // …and the pass it used to stand beside is still there, so the assertion
        // above is about ONE row and not about an empty table.
        assert("init:buildFileLocalTypeMaps" in recorded)
        PassTiming.reset()
    }
}
