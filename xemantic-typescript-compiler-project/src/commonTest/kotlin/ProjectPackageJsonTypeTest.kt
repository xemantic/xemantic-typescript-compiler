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
 * (CHK.29) A file's MODULE FORMAT under `node16`/`nodenext` is a property of the
 * nearest enclosing `package.json`'s `"type"` field — not of the compiler options
 * alone — and until this class existed nothing in this repository could see that we
 * did not derive it.
 *
 * **WHY THIS IS A PROJECT FIXTURE AND CANNOT BE A `diagnose()` PIN.** The lookup
 * walks a DIRECTORY TREE for a file that is not a program input. `diagnose()`
 * compiles a multi-file string that has no directory and no `package.json`, so a
 * pin written there is vacuous by construction — it measures the absence of the
 * thing it is supposed to be testing. Only [ProjectCompiler] (through a [Vfs], so
 * the language service's overlay is on the same path) can be asked the question.
 *
 * **AND WHY EVERY STANDING GATE IN THIS REPOSITORY IS BLIND TO IT.** tsc's own
 * sources — the corpus's reference codebase and all eight dashboard profiles — are
 * not `"type": "module"`, and the corpus harness never materialises a
 * `package.json` beside a fixture. So a green corpus suite, a `cost_gate.py` at
 * +0.00% and an `added=0 removed=0` eight-profile grid are the EXPECTED answers
 * here and none of them is evidence that any of this works. This class is the
 * instrument; the others are controls.
 *
 * **BOTH DIRECTIONS ARE PINNED, DELIBERATELY.** A "fix" that simply stopped
 * emitting TS1295/TS1287 would satisfy the `"type": "module"` half alone, so every
 * silence pin here is paired with a fixture whose only difference is the
 * `package.json` and which must still REPORT. The expected values are not
 * hand-written: they were read out of `tools/tsgo-7.0.2/lib/tsc` over the same six
 * fixtures on disk, which also agreed with us position-for-position on the CommonJS
 * rows before any of this landed.
 */
class ProjectPackageJsonTypeTest {

    /**
     * `verbatimModuleSyntax` is what makes the format OBSERVABLE: it is the option
     * whose two diagnostics (TS1295 on an ECMAScript import, TS1287 on a top-level
     * `export` modifier) fire if and only if the file is CommonJS. `nodenext` is
     * what makes the format a question at all — under an ES module kind every file
     * is ESM regardless of any `package.json`.
     */
    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "nodenext",""" +
            """ "moduleResolution": "nodenext", "verbatimModuleSyntax": true,""" +
            """ "strict": true, "noEmit": true }, "include": ["src/**/*.ts"] }"""

    private val aText = "export const x = 1;\n"

    /** An ECMAScript import AND an ECMAScript export, so both codes are in play. */
    private val bText = """
        import { x } from "./a.js";
        export const y = x + 1;
    """.trimIndent() + "\n"

    /** The fixture, with whatever `package.json` files the caller names. */
    private fun codesOf(vararg packageJson: Pair<String, String>): List<Int> {
        val files = mutableMapOf(
            "/proj/tsconfig.json" to config,
            "/proj/src/a.ts" to aText,
            "/proj/src/b.ts" to bText,
        )
        files.putAll(packageJson)
        return ProjectCompiler(InMemoryVfs(files))
            .build("/proj", noEmit = true)
            .diagnostics.map { it.code }.sorted()
    }

    /** What the CommonJS reading of this fixture costs: tsgo's exact three rows. */
    private val commonJsRows = listOf(1287, 1287, 1295)

    @Test
    fun `a package json declaring type module makes a plain ts file an ES module`() {
        assert(codesOf("/proj/package.json" to """{ "name": "p", "type": "module" }""").isEmpty())
    }

    @Test
    fun `negative control - with no package json at all the same source is CommonJS`() {
        assert(codesOf() == commonJsRows)
    }

    @Test
    fun `negative control - type commonjs is CommonJS`() {
        assert(
            codesOf("/proj/package.json" to """{ "name": "p", "type": "commonjs" }""") ==
                commonJsRows,
        )
    }

    /**
     * A `package.json` with NO `"type"` field is not a miss to be walked past — it
     * is the package scope, and its answer is CommonJS. Pinned because the natural
     * implementation (record an entry only when a `"type"` is present) would fall
     * through to an outer `package.json`, which is the next test.
     */
    @Test
    fun `negative control - a package json without a type field is CommonJS`() {
        assert(codesOf("/proj/package.json" to """{ "name": "p" }""") == commonJsRows)
    }

    /**
     * The manifest is read as JSON, not scanned for `"type"\s*:\s*"..."`. A real
     * `package.json` carries several nested `"type"` keys and they come FIRST: the
     * shape below is knip's own manifest reduced — `repository.type: "git"` and two
     * `funding[].type` precede the top-level `"type": "module"`, so a first-match
     * regex answers `"git"`, i.e. CommonJS, for a package that is an ES module. That
     * is 2,478 false positives on that library from a scan that looks correct.
     */
    @Test
    fun `a nested type key does not decide the scope`() {
        val manifest = """
            {
              "name": "p",
              "repository": { "type": "git", "url": "https://example.invalid/p" },
              "funding": [{ "type": "github", "url": "https://example.invalid/f" }],
              "type": "module"
            }
        """.trimIndent()
        assert(codesOf("/proj/package.json" to manifest).isEmpty())
    }

    @Test
    fun `the nearest package json wins over an outer one`() {
        assert(
            codesOf(
                "/proj/package.json" to """{ "name": "p", "type": "module" }""",
                "/proj/src/package.json" to """{ "name": "inner", "type": "commonjs" }""",
            ) == commonJsRows,
        )
    }

    /**
     * The walk stops at the FIRST `package.json` it meets even when that one names
     * no `"type"` — it does not keep climbing to a `"type": "module"` ancestor.
     * Measured against tsgo on the same shape, which reports the three CommonJS
     * rows here.
     */
    @Test
    fun `an inner package json without a type field stops the walk`() {
        assert(
            codesOf(
                "/proj/package.json" to """{ "name": "p", "type": "module" }""",
                "/proj/src/package.json" to """{ "name": "inner" }""",
            ) == commonJsRows,
        )
    }

    /**
     * The scope reaches DOWN as far as it has to: a file several directories below
     * the `package.json` is still in its scope.
     */
    @Test
    fun `the scope reaches a file nested well below the package json`() {
        val files = mapOf(
            "/proj/tsconfig.json" to config.replace("src/**/*.ts", "src/**/*.ts"),
            "/proj/package.json" to """{ "name": "p", "type": "module" }""",
            "/proj/src/deep/deeper/c.ts" to bText.replace("./a.js", "../../a.js"),
            "/proj/src/a.ts" to aText,
        )
        val codes = ProjectCompiler(InMemoryVfs(files))
            .build("/proj", noEmit = true)
            .diagnostics.map { it.code }
        assert(codes.isEmpty())
    }

    /**
     * The `.cts` / `.mts` extension overrides outrank the `package.json`, in both
     * directions — they are decided before the lookup and were already correct, so
     * this is a REGRESSION pin on behaviour the change must not disturb rather than
     * a claim about new work (tsgo agrees on both rows).
     */
    @Test
    fun `an extension override outranks the package json in both directions`() {
        val files = mapOf(
            "/proj/tsconfig.json" to
                """{ "compilerOptions": { "target": "es2020", "module": "nodenext",""" +
                """ "moduleResolution": "nodenext", "verbatimModuleSyntax": true,""" +
                """ "strict": true, "noEmit": true },""" +
                """ "include": ["src/**/*.ts", "src/**/*.cts", "src/**/*.mts"] }""",
            "/proj/package.json" to """{ "name": "p", "type": "module" }""",
            "/proj/src/plain.ts" to "export const p = 1;\n",
            "/proj/src/esm.mts" to "export const m = 1;\n",
            "/proj/src/cjs.cts" to "export const c = 1;\n",
        )
        val rows = ProjectCompiler(InMemoryVfs(files))
            .build("/proj", noEmit = true)
            .diagnostics.map { it.fileName to it.code }
        assert(rows == listOf<Pair<String?, Int>>("/proj/src/cjs.cts" to 1287))
    }

    /**
     * The lookup goes through the [Vfs], so the language service's IN-MEMORY overlay
     * is on the same path: overlaying a `package.json` that exists nowhere on disk
     * flips the whole project's format on the very next query, with no write.
     *
     * That is the language-service half of this item and it is not implied by the
     * build-level pins above — a lookup done once at [Project.open], or against a
     * real filesystem rather than the project's own [Vfs], would pass every one of
     * them and fail this.
     */
    @Test
    fun `overlaying a package json flips the format on the next query`() {
        val project = Project.open(
            "/proj",
            InMemoryVfs(
                mapOf(
                    "/proj/tsconfig.json" to config,
                    "/proj/src/a.ts" to aText,
                    "/proj/src/b.ts" to bText,
                ),
            ),
        )
        assert(project.diagnostics().map { it.code }.sorted() == commonJsRows)
        project.updateFile("/proj/package.json", """{ "name": "p", "type": "module" }""")
        assert(project.diagnostics().isEmpty())
        project.updateFile("/proj/package.json", """{ "name": "p", "type": "commonjs" }""")
        assert(project.diagnostics().map { it.code }.sorted() == commonJsRows)
    }

    /**
     * A `package.json` is consulted ONLY under a node resolution mode. Under an ES
     * module kind every file is ESM already, so the lookup can neither help nor
     * harm — and the eight dashboard profiles and the whole corpus live here, which
     * is why they are structurally unable to see any of the above.
     */
    @Test
    fun `under an ES module kind the package json is not consulted`() {
        val esConfig =
            """{ "compilerOptions": { "target": "es2020", "module": "esnext",""" +
                """ "verbatimModuleSyntax": true, "strict": true, "noEmit": true },""" +
                """ "include": ["src/**/*.ts"] }"""
        val files = mapOf(
            "/proj/tsconfig.json" to esConfig,
            "/proj/package.json" to """{ "name": "p", "type": "commonjs" }""",
            "/proj/src/a.ts" to aText,
            "/proj/src/b.ts" to bText.replace("./a.js", "./a"),
        )
        val codes = ProjectCompiler(InMemoryVfs(files))
            .build("/proj", noEmit = true)
            .diagnostics.map { it.code }
        assert(codes.isEmpty())
    }
}
