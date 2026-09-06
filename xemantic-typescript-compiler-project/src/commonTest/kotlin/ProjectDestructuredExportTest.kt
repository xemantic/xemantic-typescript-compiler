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

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test

/**
 * (CHK.99) A destructured `export const/let/var { … } = …` / `[ … ] = …` exports
 * every name its pattern BINDS — the checker's five AST-derived export sets read
 * `(decl.name as? Identifier)` and so held none of them.
 *
 * **WHY THIS IS A PROJECT FIXTURE.** The defect is an ABSENCE reported across a
 * MODULE BOUNDARY: a named import of a pattern leaf was TS2305, directly and
 * through an `export *` barrel. That needs a second file, a directory and real
 * module resolution, so the instrument is [ProjectCompiler] over a [Vfs]. The
 * same-file half of the family — a namespace-scoped pattern export read as
 * `NS.p`, which was TS2339 on `typeof NS` — is single-file and is pinned in the
 * core module's `DestructuredNamespaceExportTest`; nothing here duplicates it.
 *
 * **AND WHY EVERY STANDING GATE IS A CONTROL HERE.** The population on all eight
 * dashboard profiles is **0** — tsc's own sources contain not one top-level
 * `export const {`/`export const [` — so an `added=0 removed=0` grid says nothing
 * about this fix. The corpus is blind for a second, independent reason: its
 * `export*BindingPattern` baselines are all INACTIVE (amd/system variants that
 * `usesUnsupportedOption` skips), and the two ACTIVE exported-destructuring
 * baselines are single-file, so no active baseline ever imports a pattern leaf.
 * This class and its core sibling are the instruments; the grid and the corpus
 * are controls.
 *
 * **EVERY POSITIVE PIN IS A VALUE PIN, DELIBERATELY.** A "fix" that stopped
 * emitting TS2305 would satisfy any silence assertion, so each shape is imported
 * and then MIS-ASSIGNED: the surviving diagnostic must be the TS2322 naming the
 * leaf's real type. All expected rows were read out of `tools/tsgo-7.0.2/lib/tsc`
 * AND pristine `typescript@6.0.3` over the same fixtures on disk, which agree
 * with each other on every one.
 */
class ProjectDestructuredExportTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "lib": ["es2020"], "module": "es2020",""" +
            """ "strict": true, "noEmit": true }, "include": ["src/**/*.ts"] }"""

    /** The declaring module — one exported binding pattern per shape. */
    private val lib = """
        interface I { p: number; q: string; n: { d: number } }
        declare const obj: I;
        declare const tup: [number, string];
        export const plain = 1;
        export const { p } = obj;
        export const { q: renamed } = obj;
        export const { q: withDefault = "z" } = obj;
        export const { n: { d } } = obj;
        export const [t0] = tup;
        export const [, second] = tup;
        export const { ...rest } = obj;
        export let { p: viaLet } = obj;
        export var { p: viaVar } = obj;
    """.trimIndent() + "\n"

    private fun buildOf(vararg extra: Pair<String, String>): List<Pair<Int, String>> {
        val files = mutableMapOf(
            "/proj/tsconfig.json" to config,
            "/proj/src/lib.ts" to lib,
            "/proj/src/barrel.ts" to "export * from \"./lib\";\n",
        )
        files.putAll(extra)
        return ProjectCompiler(InMemoryVfs(files))
            .build("/proj", noEmit = true)
            .diagnostics.map { it.code to it.message }
    }

    /** A `main.ts` importing from [from] and mis-assigning [name] to `boolean`. */
    private fun rowsForImport(name: String, from: String = "./lib"): List<Pair<Int, String>> =
        buildOf(
            "/proj/src/main.ts" to
                "import { $name } from \"$from\";\nconst bad: boolean = $name;\nexport {};\n",
        )

    private fun ts2322(type: String) =
        listOf(2322 to "Type '$type' is not assignable to type 'boolean'.")

    // ---------------------------------------------------------------- shapes

    @Test
    fun `a shorthand object member is an export and carries its type`() {
        assert(rowsForImport("p") == ts2322("number"))
    }

    @Test
    fun `a renamed object member is exported under its LOCAL name`() {
        assert(rowsForImport("renamed") == ts2322("string"))
    }

    /**
     * The name the import must use is `renamed`, not the source key `q` — an
     * enumeration that collected `propertyName` would make THIS the passing case
     * and the one above the failure, so both directions are pinned.
     */
    @Test
    fun `negative control - the property name of a renamed member is NOT exported`() {
        val rows = rowsForImport("q")
        assert(rows.size == 1)
        assert(rows[0].first == 2305)
    }

    @Test
    fun `a defaulted member is an export and carries its type`() {
        assert(rowsForImport("withDefault") == ts2322("string"))
    }

    @Test
    fun `a nested pattern's leaf is an export and carries its type`() {
        assert(rowsForImport("d") == ts2322("number"))
    }

    @Test
    fun `an array element is an export and carries its type`() {
        assert(rowsForImport("t0") == ts2322("number"))
    }

    @Test
    fun `an array element after a hole is an export and carries its type`() {
        assert(rowsForImport("second") == ts2322("string"))
    }

    @Test
    fun `an object rest element is an export`() {
        val rows = rowsForImport("rest")
        assert(rows.size == 1)
        assert(rows[0].first == 2322)
    }

    @Test
    fun `a let pattern is an export and carries its type`() {
        assert(rowsForImport("viaLet") == ts2322("number"))
    }

    @Test
    fun `a var pattern is an export and carries its type`() {
        assert(rowsForImport("viaVar") == ts2322("number"))
    }

    // ------------------------------------------------------------- the gates

    /**
     * The whole set at once: importing every leaf plus the plain control must
     * leave the program with NO import-resolution row. Before (CHK.99) this
     * fixture read ten TS2305.
     */
    @Test
    fun `every leaf of every shape resolves through a named import`() {
        val names = "plain, p, renamed, withDefault, d, t0, second, rest, viaLet, viaVar"
        val rows = buildOf(
            "/proj/src/main.ts" to "import { $names } from \"./lib\";\nexport {};\n",
        )
        assert(rows.none { it.first == 2305 })
        assert(rows.isEmpty())
    }

    /**
     * The barrel arm. `getModuleNamedExports` is the leaf of
     * `collectExportsFollowingStars`, so an `export *` re-export inherits the
     * defect and its fix; a fix applied only at the direct-import emitter would
     * leave this row standing.
     */
    @Test
    fun `a pattern leaf is re-exported through an export star barrel`() {
        assert(rowsForImport("p", from = "./barrel") == ts2322("number"))
    }

    /**
     * The negative control for the barrel arm, and the one that proves the export
     * set is not simply "every name in the file": a module-local destructuring
     * that is not exported stays unexported through the barrel too.
     */
    @Test
    fun `negative control - an unexported destructuring is not exported anywhere`() {
        val rows = buildOf(
            "/proj/src/priv.ts" to "declare const o: { z: number };\nconst { z } = o;\nexport const other = 1;\n",
            "/proj/src/main.ts" to "import { z } from \"./priv\";\nexport {};\n",
        )
        assert(rows.map { it.first } == listOf(2305))
    }

    /**
     * The export set is genuinely WIDER, not merely consulted differently: two
     * barrels each exporting a pattern leaf of the same name is TS2308 in both
     * references, and we did not report it before this. A pin asserting only
     * silence could never see this direction.
     */
    @Test
    fun `two star barrels exporting the same pattern leaf are ambiguous`() {
        val rows = ProjectCompiler(
            InMemoryVfs(
                mapOf(
                    "/proj/tsconfig.json" to config,
                    "/proj/src/x.ts" to "declare const o: { amb: number };\nexport const { amb } = o;\n",
                    "/proj/src/y.ts" to "declare const o: { amb: number };\nexport const { amb } = o;\n",
                    "/proj/src/both.ts" to "export * from \"./x\";\nexport * from \"./y\";\n",
                ),
            ),
        ).build("/proj", noEmit = true).diagnostics.map { it.code }
        assert(rows == listOf(2308))
    }

    /**
     * And the control for THAT: the same two barrels over an ordinary
     * `export const amb = 1` are ambiguous too, so the row above is the pattern
     * leaf joining a rule that already worked rather than a new rule.
     */
    @Test
    fun `negative control - two star barrels over a plain export are ambiguous the same way`() {
        val rows = ProjectCompiler(
            InMemoryVfs(
                mapOf(
                    "/proj/tsconfig.json" to config,
                    "/proj/src/x.ts" to "export const amb = 1;\n",
                    "/proj/src/y.ts" to "export const amb = 2;\n",
                    "/proj/src/both.ts" to "export * from \"./x\";\nexport * from \"./y\";\n",
                ),
            ),
        ).build("/proj", noEmit = true).diagnostics.map { it.code }
        assert(rows == listOf(2308))
    }

    /**
     * **The guard against "finishing the job".** The (CHK.99) queue entry named a
     * sixth site — `StarExportIndex.varDecls`, behind
     * `computeExportedVarDeclThroughStars` — and registering a pattern's leaves
     * THERE is a WRONG TYPE, not a fix: its one consumer,
     * `importedTopLevelVarAnnotationType`, reads the declaration's `type`, so the
     * leaf `shared` of `export const { shared }: { shared: never[] } = x` would be
     * typed `{ shared: never[]; }` — the annotation of the whole pattern.
     *
     * **The fixture needs the COLLISION**, and that is the point of writing it out
     * rather than reusing the class's own module: that consumer fires only when two
     * module files declare the same top-level name and the import is identity-matched
     * through its own barrel (round 473's `emptyArray` shape). Without `other.ts`
     * below the path is never entered and the pin is green against the mistake —
     * measured, in this round, before the collision was added.
     *
     * Both references print `never[]`; the leaf-registering variant prints
     * `{ shared: never[]; }`.
     */
    @Test
    fun `an ANNOTATED pattern export types its leaf and not the whole annotation`() {
        val rows = ProjectCompiler(
            InMemoryVfs(
                mapOf(
                    "/proj/tsconfig.json" to config,
                    "/proj/src/core.ts" to
                        "declare const x: { shared: never[] };\n" +
                        "export const { shared }: { shared: never[] } = x;\n",
                    "/proj/src/other.ts" to "export const shared: string[] = [];\n",
                    "/proj/src/via.ts" to "export * from \"./core\";\n",
                    "/proj/src/reader.ts" to
                        "import { shared } from \"./via\";\nconst bad: boolean = shared;\nexport {};\n",
                ),
            ),
        ).build("/proj", noEmit = true).diagnostics.map { it.code to it.message }
        assert(rows.none { it.first == 2305 })
        assert(rows == listOf(2322 to "Type 'never[]' is not assignable to type 'boolean'."))
    }

    /**
     * A name that is genuinely absent must still be TS2305 — without this the
     * whole class would be satisfied by an emitter that had simply been deleted.
     */
    @Test
    fun `negative control - a genuinely absent name is still TS2305`() {
        val rows = rowsForImport("neverDeclared")
        assert(rows.any { it.first == 2305 })
    }
}
