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
 * (LEGACY.1)(f) `module: amd` / `umd` / `system` are REMOVED values in TypeScript 7, and
 * tsgo 7.0.2 FOLDS them onto `commonjs`: `program.go:844-852` reports the option
 * (TS5108 `module=AMD` / `module=System` / `module=UMD`, value-anchored) and then
 * `GetEmitModuleKind()` answers the option as written while `getModuleTransformer`
 * (`emitter.go:82-101`) sends every kind it does not list to the CommonJS module
 * transform. The AMD / UMD / System transforms of this compiler were deleted 2026-07-02;
 * until (P18.107) a removed kind passed its module statements through UNTRANSFORMED.
 *
 * **Every expected value here is tsgo 7.0.2's**, measured 2026-09-15 on 20 scratch programs
 * × `module` ∈ {amd, umd, system, commonjs, esnext, unset} × `target` ∈ {es2020, esnext}
 * (tsgo CLI + `--outDir`, and the LSP's `textDocument/diagnostic` for the checker rows,
 * because the CLI stops at the option rows): the `amd` and `umd` cells are byte-identical
 * to the `commonjs` cell of the same program on every emitted file and every checker row;
 * the `system` cell is too, bar three checker arms tsgo keys on the WRITTEN kind (TS1218
 * on `export =`, top-level `await` allowed, `import.meta` allowed) and the enum/namespace
 * leading comments its `runtimesyntax.go` keeps under System. TS2725 prints the written
 * kind (`with module AMD`), TS2441 / TS2882 / TS2305 report under every removed kind, and
 * TS5071 has no emitter anywhere in tsgo.
 *
 * The one measured place this compiler does NOT copy tsgo: under `importHelpers` tsgo
 * skips its `tslib` check for the removed kinds and emits an UNBOUND `__exportStar(…)`
 * beside `const tslib_1 = require("tslib")` — a defect in a removed configuration; the
 * fold here reports TS2354 exactly as under `commonjs`.
 *
 * The emit pins are RELATIONAL (the removed kind's JavaScript equals the `commonjs`
 * JavaScript of the same program) so they state the fold and nothing about the CommonJS
 * transform itself; [ProjectRemovedModuleKindsTest] in the `-project` module pins the
 * value-anchored option rows and the emitted files on a real directory.
 */
class RemovedModuleKindsTest {

    private val removedKinds = listOf("amd", "umd", "system")

    private fun compileWith(module: String, source: String, extra: String = ""): CompilationResult =
        TypeScriptCompiler().compile("// @module: $module\n// @target: es2020$extra\n" + source.trimIndent(), "input.ts")

    private fun jsOf(module: String, source: String, extra: String = ""): List<Pair<String, String>> =
        compileWith(module, source, extra).jsOutputs.sortedBy { it.first }

    private val twoFile = """
        // @Filename: helper.ts
        export function helper(): number { return 1; }
        export default 42;
        export const c = "c";
        // @Filename: a.ts
        import { helper } from "./helper";
        import * as ns from "./helper";
        import dflt from "./helper";
        import "./helper";
        export * from "./helper";
        export { c as cc } from "./helper";
        export const answer = helper() + ns.helper() + dflt;
        export default answer;
        export class K { m() { return helper(); } }
    """

    // ── the fold: a removed kind's JavaScript IS the commonjs JavaScript ─────────────

    @Test
    fun `a removed module kind emits the commonjs JavaScript byte for byte`() {
        val commonjs = jsOf("commonjs", twoFile)
        assert(commonjs.size == 2)
        for (kind in removedKinds) {
            val js = jsOf(kind, twoFile)
            assert(js == commonjs)
        }
    }

    /** What the fold buys, stated positively — the CommonJS transform's own shapes appear under every removed kind. */
    @Test
    fun `a removed module kind gets the CommonJS transform - require exports and the zero-comma call wrapper`() {
        for (kind in removedKinds) {
            val a = jsOf(kind, twoFile).first { it.first.endsWith("a.js") }.second
            assert(a.contains("const helper_1 = require(\"./helper\");"))
            assert(a.contains("(0, helper_1.helper)()"))
            assert(a.contains("exports.answer = "))
            assert(a.contains("__exportStar(require(\"./helper\"), exports);"))
            assert(!a.contains("import { helper }"))
            assert(!a.contains("define("))
            assert(!a.contains("System.register("))
        }
    }

    @Test
    fun `export equals dynamic import default import and import-equals fold onto their commonjs emit`() {
        val programs = listOf(
            "const o = { a: 1 };\nexport = o;\n",
            "export async function load(): Promise<number> {\n    const m = await import(\"./dep\");\n    return m.x;\n}\n// @Filename: dep.ts\nexport const x = 1;\n",
            "// @Filename: decl.d.ts\ndeclare function f(): number;\nexport = f;\n// @Filename: main.ts\nimport d from \"./decl\";\nexport const n: number = d();\n",
            "// @Filename: decl.d.ts\ndeclare function f(): number;\nexport = f;\n// @Filename: main.ts\nimport f = require(\"./decl\");\nexport const n: number = f();\n",
        )
        for (program in programs) {
            val commonjs = jsOf("commonjs", program)
            assert(commonjs.isNotEmpty())
            for (kind in removedKinds) {
                val js = jsOf(kind, program)
                assert(js == commonjs)
            }
        }
    }

    @Test
    fun `control - an explicit commonjs program is unchanged by the fold`() {
        val a = jsOf("commonjs", twoFile).first { it.first.endsWith("a.js") }.second
        assert(a.contains("const helper_1 = require(\"./helper\");"))
        assert(a.contains("(0, helper_1.helper)()"))
    }

    @Test
    fun `control - an esnext program keeps its ES module syntax`() {
        val a = jsOf("esnext", twoFile).first { it.first.endsWith("a.js") }.second
        assert(a.contains("import { helper } from \"./helper\";"))
        assert(!a.contains("require("))
    }

    // ── the option row: tsgo's TS5108 at the shipped default, TS5107 at an explicit 6.0 ────

    @Test
    fun `a removed module kind reports tsgo's TS5108 at the shipped default and still emits`() {
        for ((kind, spelled) in listOf("amd" to "AMD", "umd" to "UMD", "system" to "System")) {
            val result = compileWith(kind, twoFile)
            assert(result.diagnostics.count { it.code == 5108 && it.message.contains("'module=$spelled'") } == 1)
            assert(result.diagnostics.none { it.code == 5107 })
            assert(result.jsOutputs.size == 2)
        }
    }

    /** Control: an EXPLICIT `@typeScriptVersion` below `7.0` still selects the TS5107 ladder. */
    @Test
    fun `control - an explicit typeScriptVersion 6 0 keeps a removed module kind on the TS5107 ladder`() {
        for ((kind, spelled) in listOf("amd" to "AMD", "umd" to "UMD", "system" to "System")) {
            val d = compileWith(kind, twoFile, extra = "\n// @typeScriptVersion: 6.0").diagnostics
            assert(d.count { it.code == 5107 && it.message.contains("'module=$spelled'") } == 1)
            assert(d.none { it.code == 5108 })
        }
    }

    /** tsgo: `Option 'module=AMD' has been removed. Please remove it from your configuration.` — `System` and `UMD` spelled as tsgo spells them. */
    @Test
    fun `under typeScriptVersion 7 0 a removed module kind is tsgo's TS5108 byte for byte`() {
        for ((kind, spelled) in listOf("amd" to "AMD", "umd" to "UMD", "system" to "System")) {
            val d = compileWith(kind, "export const a = 1;", extra = "\n// @typeScriptVersion: 7.0").diagnostics
            val rows = d.filter { it.code == 5108 }
            assert(rows.size == 1)
            assert(rows[0].message == "Option 'module=$spelled' has been removed. Please remove it from your configuration.")
            assert(d.none { it.code == 5107 })
        }
    }

    /**
     * **residue — this asserts an answer tsgo does NOT give, deliberately.** (P18.133)
     * `module: none` is not a removed option in TypeScript 7; it is not a `module` VALUE at all.
     * tsgo's `moduleOptionMap` (`tsoptions/enummaps.go:171`) has no `none` key and
     * `createRemovedOptionDiagnostic` has no `ModuleKindNone` case, so tsgo answers the
     * out-of-map ARGUMENT diagnostic at the VALUE and leaves the option unset — measured over
     * `{ "compilerOptions": { "module": "None" } }`:
     *
     *     tsconfig.json(1,34): error TS6046: Argument for '--module' option must be: 'commonjs', 'es6', …
     *
     * where its three siblings above are
     *
     *     tsconfig.json(1,34): error TS5108: Option 'module=AMD' has been removed. Please remove it from your configuration.
     *
     * This compiler keeps `module=None` on the shared removed-option ladder, so the row moved
     * TS5107 → TS5108 with its siblings when the default moved: **wrong before, wrong now, not
     * widened by (P18.133)**. Closing it is the (LEGACY.1)(j4) `targetValueInvalid` mechanism one
     * option over — a `moduleValueInvalid` flag plus a change to what `effectiveModule` derives
     * from an unset option — which is a round of its own. The family has no active corpus
     * coverage at all (none of the eight `@module: none` case files generates a subtest, and
     * tsgo's testdata holds no `moduleNone*` baseline), so this pin is its only record.
     */
    @Test
    fun `residue - module none is TS5108 here where tsgo answers TS6046`() {
        val d = compileWith("none", "export const a = 1;").diagnostics
        val row = d.single { it.code == 5108 }
        assert(row.message == "Option 'module=None' has been removed. Please remove it from your configuration.")
        assert(d.none { it.code == 6046 })
    }


    // ── TS5071 has no emitter in tsgo ─────────────────────────────────────────────────

    @Test
    fun `resolveJsonModule under umd system or none no longer reports TS5071`() {
        for (kind in listOf("umd", "system", "none")) {
            val d = compileWith(kind, "export const a = 1;", extra = "\n// @resolveJsonModule: true").diagnostics
            assert(d.none { it.code == 5071 })
        }
    }

    // ── checker rows tsgo reports under every removed kind (the deleted exemptions) ──

    /**
     * tsgo: `main.ts(1,8): error TS2882: Cannot find module or type declarations for side-effect
     * import of 'nonexistent-pkg-zz'.` in every cell. A CONTROL here, measured: the harness path
     * reports this row through a second TS2882 emitter that never carried the removed-kind
     * exemption, so this pin is GREEN on the pre-(P18.107) binary too — the exemption's
     * deletion is pinned on the project path ([ProjectRemovedModuleKindsTest]), where the
     * exempting emitter is the only one and the row was MISSING before.
     */
    @Test
    fun `control - a side-effect import of an unresolvable package is TS2882 under every removed kind through the harness`() {
        val source = "import \"nonexistent-pkg-zz\";\nexport const s = 1;\n"
        val commonjs = compileWith("commonjs", source).diagnostics.filter { it.code == 2882 }
        assert(commonjs.size == 1)
        assert(commonjs[0].message == "Cannot find module or type declarations for side-effect import of 'nonexistent-pkg-zz'.")
        for (kind in removedKinds) {
            val rows = compileWith(kind, source).diagnostics.filter { it.code == 2882 }
            assert(rows.size == 1)
            assert(rows[0].message == commonjs[0].message)
            assert(rows[0].start == commonjs[0].start)
        }
    }

    /** tsgo: `a.ts(1,10): error TS2305: Module '"./b"' has no exported member 'default'.` under `system` as under `commonjs`. */
    @Test
    fun `a re-export of a missing default is TS2305 under system`() {
        val source = "// @Filename: b.ts\nexport const q = 1;\n// @Filename: a.ts\nexport { default } from \"./b\";\nexport { q } from \"./b\";\n"
        val commonjs = compileWith("commonjs", source).diagnostics.filter { it.code == 2305 }
        assert(commonjs.size == 1)
        assert(commonjs[0].message == "Module '\"./b\"' has no exported member 'default'.")
        for (kind in removedKinds) {
            val rows = compileWith(kind, source).diagnostics.filter { it.code == 2305 }
            assert(rows.size == 1)
            assert(rows[0].message == commonjs[0].message)
        }
    }

    /**
     * The fold's one deliberate divergence from tsgo: with `importHelpers` and no `tslib`,
     * tsgo 7.0.2 reports NO TS2354 under a removed kind and emits an unbound `__exportStar`
     * beside its `tslib` import; this compiler reports what it reports under `commonjs`.
     */
    @Test
    fun `importHelpers without tslib reports TS2354 under every removed kind exactly as under commonjs`() {
        val source = "// @Filename: dep.ts\nexport const d = 1;\nexport default 2;\n// @Filename: main.ts\nexport * from \"./dep\";\nimport d from \"./dep\";\nimport * as ns from \"./dep\";\nexport const n = d + ns.d;\n"
        fun rows(kind: String) = compileWith(kind, source, extra = "\n// @importHelpers: true").diagnostics
            .filter { it.code == 2354 }.map { Triple(it.fileName, it.start, it.message) }
        val commonjs = rows("commonjs")
        assert(commonjs.isNotEmpty())
        for (kind in removedKinds) {
            assert(rows(kind) == commonjs)
        }
    }

    // ── the explicit-commonjs unresolved-import arms admit the folded kinds ───────────

    /** tsgo (every cell): `a.ts(1,19): error TS2307: Cannot find module './nope' or its corresponding type declarations.` */
    @Test
    fun `a missing relative import is TS2307 under every removed kind as under commonjs`() {
        val source = "// @Filename: /a.ts\nimport { x } from \"./nope\"; x;\n// @Filename: /b.ts\nexport const q = 1;\n"
        val commonjs = compileWith("commonjs", source).diagnostics.filter { it.code == 2307 }
        assert(commonjs.size == 1)
        assert(commonjs[0].message == "Cannot find module './nope' or its corresponding type declarations.")
        for (kind in removedKinds) {
            val rows = compileWith(kind, source).diagnostics.filter { it.code == 2307 }
            assert(rows.size == 1)
            assert(rows[0].message == commonjs[0].message)
        }
    }

    /** tsgo (every cell): `error TS2307: Cannot find module 'server'` — a bare specifier never names a sibling file. */
    @Test
    fun `a bare specifier naming a sibling file is TS2307 under every removed kind as under commonjs`() {
        val source = "// @Filename: /a.ts\nimport { x } from \"server\"; x;\n// @Filename: /server.ts\nexport const x = 1;\n"
        assert(compileWith("commonjs", source).diagnostics.count { it.code == 2307 } == 1)
        for (kind in removedKinds) {
            assert(compileWith(kind, source).diagnostics.count { it.code == 2307 } == 1)
        }
    }

    // ── controls: arms tsgo keeps for the removed kinds, unmoved by the fold ──────────

    /** tsgo (`system` × `es2016`): TS1378 — System is in the case list, so the target gate decides. */
    @Test
    fun `control - a top-level await under system below es2017 is TS1378`() {
        val d = TypeScriptCompiler().compile("// @module: system\n// @target: es2016\nexport const x: number = await Promise.resolve(1);\n", "input.ts").diagnostics
        assert(d.count { it.code == 1378 } == 1)
    }

    /** tsgo: `Class name cannot be 'Object' when targeting ES5 and above with module AMD.` — the WRITTEN kind. */
    @Test
    fun `control - TS2725 names the written module kind`() {
        for ((kind, spelled) in listOf("commonjs" to "CommonJS", "amd" to "AMD", "umd" to "UMD", "system" to "System")) {
            val rows = compileWith(kind, "export class Object { }\n").diagnostics.filter { it.code == 2725 }
            assert(rows.size == 1)
            assert(rows[0].message == "Class name cannot be 'Object' when targeting ES5 and above with module $spelled.")
        }
        assert(compileWith("esnext", "export class Object { }\n").diagnostics.none { it.code == 2725 })
    }

    /** tsgo: two TS2441 rows in every removed-kind cell, as under `commonjs`; none under `esnext`. */
    @Test
    fun `control - TS2441 reserves exports and require under every removed kind`() {
        val source = "export const x = 1;\nfunction exports() { }\nclass require { }\n"
        for (kind in listOf("commonjs") + removedKinds) {
            assert(compileWith(kind, source).diagnostics.count { it.code == 2441 } == 2)
        }
        assert(compileWith("esnext", source).diagnostics.none { it.code == 2441 })
    }

    /** tsgo keeps `system` in TS1378's case list (`grammarchecks.go:1727`): no row under `system` × `es2020`. */
    @Test
    fun `control - a top-level await under system at es2020 is not TS1378`() {
        val d = compileWith("system", "export const x: number = await Promise.resolve(1);\n").diagnostics
        assert(d.none { it.code == 1378 || it.code == 1432 })
    }
}
