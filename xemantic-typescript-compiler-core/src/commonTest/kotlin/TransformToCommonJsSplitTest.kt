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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * (JIT.1)(e) round 819 — pins for the split of `Transformer.transformToCommonJS`
 * (**28,991 bytecodes**, 3.6x HotSpot's 8,000-byte `HugeMethodLimit`, so never
 * JIT-compiled — the LARGEST method in the compiler) into an entry plus nineteen
 * `tcjs*` helpers.
 *
 * This method is on the EMIT path, so every `--noEmit` A/B in this arc is
 * structurally blind to it: the gate is the corpus suite's emit baselines plus
 * these pins, and nothing here is a performance claim.
 *
 * `HugeMethodLimitTest` guards the SIZE. This class pins what a size check cannot
 * see: one ARM pin per helper — an observable only that stage produces, so a
 * dropped call site is visible AND attributable — plus the seams a compiler
 * cannot catch:
 *
 *  * **the ONE-ITERATION FRAME.** Two arms hold `continue`s that targeted the
 *    caller's `for (stmt in statementsToProcess)` loop; they move inside
 *    `for (stmt in listOf(stmtIn))`, where `continue` means exactly what it meant
 *    before. Nothing in the types says the list has ONE element — a two-element
 *    one type-checks and transforms every statement twice;
 *  * **the CONTAINER-IDENTITY seam.** `tcjsTransformFunctionDeclaration` appends
 *    to the CALLER's `functionExportStubs`, which a later stage inserts after the
 *    preamble. A helper handed a fresh list compiles fine and silently drops
 *    every hoisted function export;
 *  * **the RETURN-SIGNAL seam.** A helper cannot write back to the caller's
 *    `var`, so the four helper flags travel through holders. Dropping one
 *    write-back type-checks and drops the runtime helper body the flag asked
 *    for, while the call to that helper stays in the output;
 *  * **the ORDER seam.** `tcjsRewriteExportMutations` must run BEFORE the entry's
 *    own direct-export identifier rewrite, or the destructuring targets it looks
 *    for are already `exports.x` property accesses. Moving the call after it
 *    type-checks.
 */
class TransformToCommonJsSplitTest {

    private val cjs = "// @module: commonjs\n// @target: es2017\n// @esModuleInterop: true\n"

    /** The emitted JS of the output file whose name ends with [outputName]. */
    private fun js(@Language("typescript") source: String, outputName: String = "main.js"): String =
        TypeScriptCompiler().compile(cjs + source.trimIndent(), "main.ts")
            .jsOutputs.first { it.first.endsWith(outputName) }.second

    /** A tiny dependency every import-shaped pin resolves against. */
    private val dep = """
        // @Filename: dep.ts
        export const dv = 1;
        export default class Dep {}
        export function depFn(): void {}
    """.trimIndent() + "\n"

    // ── tcjsDetectModuleShape ───────────────────────────────────────────────

    @Test
    fun `module shape - an ordinary module gets the __esModule preamble`() {
        val out = js(
            """
            // @Filename: main.ts
            export const p = 1;
            """
        )
        assert(out.contains("Object.defineProperty(exports, \"__esModule\", { value: true });"))
    }

    /**
     * `export = X` files use `module.exports = X` directly and are incompatible
     * with ES module syntax, so they get NO preamble. This is the ONE decision
     * `tcjsDetectModuleShape` makes that nothing else in the pipeline can.
     */
    @Test
    fun `module shape - an export-equals file gets no preamble`() {
        val out = js(
            """
            // @Filename: main.ts
            interface OnlyType { a: number }
            declare const x: OnlyType;
            export = x;
            """
        )
        assert(!out.contains("__esModule"))
        assert(out.contains("module.exports = x;"))
    }

    // ── tcjsCollectDeclaredNames ────────────────────────────────────────────

    /**
     * `PureType` is declared ONLY as an interface, so its export specifier erases;
     * `realFn` is declared only as a function, so it takes the JS-hoisted STUB
     * path (`exports.realFn = realFn` before the body) rather than a void0 hoist.
     * Both answers come from this helper's name pre-scan.
     */
    @Test
    fun `declared-name pre-scan - a pure type erases and a function takes the stub path`() {
        val out = js(
            """
            // @Filename: main.ts
            interface PureType { b: number }
            class RealClass {}
            function realFn(): void {}
            export { PureType, RealClass, realFn };
            """
        )
        assert(!out.contains("PureType"))
        assert(out.contains("exports.realFn = realFn;"))
        assert(out.contains("exports.RealClass = void 0;"))
        assert(out.indexOf("exports.realFn = realFn;") < out.indexOf("class RealClass"))
    }

    // ── tcjsCollectNamespaceExports ─────────────────────────────────────────

    /**
     * A namespace declared WITHOUT `export` and re-exported under an alias:
     * `iifeExportAliases` is the only place that mapping is built, and the IIFE
     * argument has to carry the EXPORT name, not the local one.
     */
    @Test
    fun `namespace pre-scan - a locally-declared namespace re-exported under an alias`() {
        val out = js(
            """
            // @Filename: main.ts
            namespace Local { export const w = 1; }
            export { Local as LocalAlias };
            """
        )
        assert(out.contains("})(Local || (exports.LocalAlias = Local = {}));"))
    }

    // ── tcjsCollectExportClauses ────────────────────────────────────────────

    /**
     * The `export { X as Y }` clause is seen AFTER both declarations, so only the
     * pre-scan can tell the class and the `let` that they are exported at all.
     */
    @Test
    fun `export-clause pre-scan - a clause after the declarations still exports both`() {
        val out = js(
            """
            // @Filename: main.ts
            class Klass {}
            let mutable = 1;
            export { Klass as KlassAlias, mutable as mutableAlias };
            """
        )
        assert(out.contains("exports.mutableAlias = exports.KlassAlias = void 0;"))
        assert(out.contains("exports.KlassAlias = Klass;"))
        assert(out.contains("exports.mutableAlias = mutable;"))
    }

    // ── tcjsSplitPrologueDirectives ─────────────────────────────────────────

    @Test
    fun `prologue split - use strict leads and other directives sit before the preamble`() {
        val out = js(
            """
            // @Filename: main.ts
            "use strict";
            "other directive";
            export const p = 1;
            """
        )
        assert(out.indexOf("\"use strict\";") < out.indexOf("\"other directive\";"))
        assert(out.indexOf("\"other directive\";") < out.indexOf("__esModule"))
    }

    // ── tcjsTransformVariableStatement ──────────────────────────────────────

    /**
     * Three distinct paths of the same arm: the DIRECT path (no local binding at
     * all), the KEEP-DECLARATION path (a function initializer keeps its `const`
     * and gets an assignment after it), and the empty-pattern SIDE-EFFECT path
     * (a hoisted `var _a;` plus a bare `_a = expr`).
     */
    @Test
    fun `variable arm - direct and keep-declaration and side-effect destructuring paths`() {
        val out = js(
            """
            // @Filename: main.ts
            export const plain = 1;
            export const fnVar = function () { return 1; };
            export const {} = { q: 1 };
            """
        )
        assert(out.contains("exports.plain = 1;"))
        assert(out.contains("const fnVar = function () { return 1; };"))
        assert(out.contains("exports.fnVar = fnVar;"))
        assert(out.contains("var _a;"))
        assert(out.contains("_a = { q: 1 };"))
    }

    /**
     * THE ONE-ITERATION FRAME, on the `VariableStatement` arm. A second iteration
     * would emit every one of these statements twice; `count` reads it directly.
     */
    @Test
    fun `variable arm - each exported declaration is emitted exactly once`() {
        val out = js(
            """
            // @Filename: main.ts
            export const one = 1;
            export const two = 2;
            """
        )
        assert(out.split("exports.one = 1;").size - 1 == 1)
        assert(out.split("exports.two = 2;").size - 1 == 1)
    }

    // ── tcjsTransformFunctionDeclaration ────────────────────────────────────

    @Test
    fun `function arm - an exported function gets a hoisted stub and a default gets exports-default`() {
        val out = js(
            """
            // @Filename: main.ts
            export function exportedFn(): number { return 1; }
            export default function defFn(): void {}
            """
        )
        assert(out.contains("exports.exportedFn = exportedFn;"))
        assert(out.contains("exports.default = defFn;"))
        assert(out.indexOf("exports.exportedFn = exportedFn;") < out.indexOf("function exportedFn()"))
    }

    // ── tcjsTransformClassDeclaration ───────────────────────────────────────

    /**
     * `export default class` records the name so the static initializer is
     * re-ordered AHEAD of `exports.default = X`, which is tsc's ordering.
     */
    @Test
    fun `class arm - a default-exported class emits its static initializer first`() {
        val out = js(
            """
            // @Filename: main.ts
            export default class Cls { static sp = 1; }
            """
        )
        assert(out.contains("Cls.sp = 1;"))
        assert(out.indexOf("Cls.sp = 1;") < out.indexOf("exports.default = Cls;"))
    }

    // ── tcjsTransformExportAssignment ───────────────────────────────────────

    @Test
    fun `export-assignment arm - export default of a local binds exports-default`() {
        val out = js(
            """
            // @Filename: main.ts
            const val9 = 1;
            export default val9;
            """
        )
        assert(out.contains("const val9 = 1;"))
        assert(out.contains("exports.default = val9;"))
    }

    // ── tcjsTransformImportDeclaration ──────────────────────────────────────

    /**
     * Every interop form in one file: a default import (`__importDefault`), a
     * namespace import (`__importStar`), the combined default+named form (which
     * shares ONE temp), and a plain named import (no helper at all). The renames
     * are this arm's other product.
     */
    @Test
    fun `import arm - the four interop forms and their renames`() {
        val out = js(
            dep +
            """
            // @Filename: main.ts
            import Dep from "./dep";
            import * as All from "./dep";
            import Both, { dv } from "./dep";
            import { depFn } from "./dep";
            export const use = [new Dep(), All.dv, Both, dv, depFn];
            """
        )
        assert(out.contains("const dep_1 = __importDefault(require(\"./dep\"));"))
        assert(out.contains("const All = __importStar(require(\"./dep\"));"))
        assert(out.contains("const dep_2 = __importStar(require(\"./dep\"));"))
        assert(out.contains("const dep_3 = require(\"./dep\");"))
        // the FLAGS the arm hands back, seen through the helper BODIES they request
        assert(out.contains("var __importDefault ="))
        assert(out.contains("var __importStar ="))
        assert(out.contains("exports.use = [new dep_1.default(), All.dv, dep_2.default, dep_2.dv, dep_3.depFn];"))
    }

    /**
     * THE ONE-ITERATION FRAME, on the `ImportDeclaration` arm — five of the six
     * caller-loop `continue`s live here. A wholly unused combined
     * default+namespace import is skipped by one of them, so nothing at all is
     * emitted for it, and the used import beside it is emitted exactly once.
     */
    @Test
    fun `import arm - a wholly unused import emits nothing and a used one emits once`() {
        val out = js(
            dep +
            """
            // @Filename: main.ts
            import UnusedD, * as UnusedNs from "./dep";
            import { dv } from "./dep";
            export const k = dv;
            """
        )
        assert(!out.contains("UnusedD"))
        assert(!out.contains("UnusedNs"))
        assert(out.split("require(\"./dep\")").size - 1 == 1)
    }

    // ── tcjsTransformExportDeclaration ──────────────────────────────────────

    @Test
    fun `export-declaration arm - star and star-as-namespace and aliased re-export forms`() {
        val out = js(
            dep +
            """
            // @Filename: main.ts
            export * from "./dep";
            export * as depNs from "./dep";
            export { dv as dvAlias } from "./dep";
            """
        )
        assert(out.contains("__exportStar(require(\"./dep\"), exports);"))
        assert(out.contains("exports.depNs = __importStar(require(\"./dep\"));"))
        assert(out.contains(
            "Object.defineProperty(exports, \"dvAlias\", { enumerable: true, " +
                "get: function () { return dep_1.dv; } });"
        ))
    }

    // ── tcjsTransformOtherStatement ─────────────────────────────────────────

    /**
     * An exported namespace and an exported enum reach the transform as
     * already-lowered IIFE expression statements; the `else` arm is what rewrites
     * their argument to `exports.X = X = {}`.
     */
    @Test
    fun `else arm - exported namespace and enum IIFE arguments become exports-X`() {
        val out = js(
            """
            // @Filename: main.ts
            export namespace NS { export const v = 1; }
            export enum E { A }
            """
        )
        assert(out.contains("})(NS || (exports.NS = NS = {}));"))
        assert(out.contains("})(E || (exports.E = E = {}));"))
    }

    // ── tcjsExtractEarlyPrePreamble ─────────────────────────────────────────

    /**
     * A detached header comment above the first REAL statement is lifted between
     * `"use strict"` and the preamble. This runs before the hoist insertions
     * move the statement it reads positions from — see the ORDER seam.
     */
    @Test
    fun `early pre-preamble - a header comment is lifted above the preamble`() {
        val out = js(
            """
            // @Filename: main.ts
            // leading header comment

            export const {} = { q: 1 };
            export function hf(): void {}
            export const after = 1;
            """
        )
        assert(out.contains("// leading header comment"))
        assert(out.indexOf("// leading header comment") < out.indexOf("var _a;"))
        assert(out.indexOf("// leading header comment") < out.indexOf("__esModule"))
    }

    // ── tcjsPrependHoistedVars ──────────────────────────────────────────────

    /**
     * The hoisted `var _a;` goes ABOVE the preamble, and the function export stub
     * goes BELOW it — one helper decides both positions, and it returns how many
     * statements it prepended so the later helper insertion stays aligned.
     */
    @Test
    fun `hoisted vars - the temp is above the preamble and the stub below it`() {
        val out = js(
            """
            // @Filename: main.ts
            export const {} = { q: 1 };
            export function f(): void {}
            """
        )
        assert(out.indexOf("var _a;") < out.indexOf("__esModule"))
        assert(out.indexOf("__esModule") < out.indexOf("exports.f = f;"))
    }

    // ── tcjsRewriteExportMutations ──────────────────────────────────────────

    /**
     * B322: a destructuring assignment whose targets are exported names is
     * FLATTENED into a temp plus per-element `exports.N =`, and B321 rewrites the
     * postfix increment of a late-exported local.
     */
    @Test
    fun `export mutations - destructuring assignment flattens and increments rewrite`() {
        val out = js(
            """
            // @Filename: main.ts
            export let ea = 1;
            export let eb = 2;
            export function mutate() { ({ ea, eb } = { ea: 3, eb: 4 }); ea++; }
            """
        )
        assert(out.contains("(_a = { ea: 3, eb: 4 }, exports.ea = _a.ea, exports.eb = _a.eb)"))
        assert(out.contains("exports.ea++;"))
    }

    // ── tcjsCollectInternalAliasNames ───────────────────────────────────────

    /**
     * An `import x = M.N` alias whose name occurs nowhere else is erased; the one
     * that is referenced survives. Only this helper can tell the two apart — it
     * counts the name's occurrences in the ORIGINAL source text.
     */
    @Test
    fun `internal aliases - the unreferenced import-equals alias is erased`() {
        val out = js(
            """
            // @Filename: main.ts
            namespace Outer { export const inner = 1; }
            import aliasUnused = Outer.inner;
            import aliasUsed = Outer.inner;
            export const u = aliasUsed;
            """
        )
        assert(!out.contains("aliasUnused"))
        assert(out.contains("var aliasUsed = Outer.inner;"))
    }

    // ── tcjsElideUnusedImports ──────────────────────────────────────────────

    /**
     * Both imports are referenced only from a TYPE position, so the whole require
     * goes — the file ends up with no `require` at all.
     */
    @Test
    fun `import elision - a require referenced only in type position is dropped`() {
        val out = js(
            dep +
            """
            // @Filename: main.ts
            import { dv } from "./dep";
            import Unused from "./dep";
            export type T = typeof dv;
            export const k = 1;
            """
        )
        assert(!out.contains("require("))
        assert(out.contains("exports.k = 1;"))
    }

    // ── tcjsMoveDetachedHeaderComments ──────────────────────────────────────

    /**
     * The POST-elision twin of the early extraction: here the first statement is
     * an import, so the copyright block travels with the surviving require and is
     * only lifted once elision has run.
     */
    @Test
    fun `detached header comments - a copyright block above an import moves to the preamble`() {
        val out = js(
            dep +
            """
            // @Filename: main.ts
            /* copyright block */

            import { dv } from "./dep";
            export const u = dv;
            """
        )
        assert(out.contains("/* copyright block */"))
        assert(out.indexOf("/* copyright block */") < out.indexOf("__esModule"))
        assert(out.indexOf("__esModule") < out.indexOf("require(\"./dep\")"))
    }

    // ── tcjsInsertHelpersAndPrologue ────────────────────────────────────────

    /**
     * `importStarUsedFirst` decides which of `__importStar` and `__exportStar` is
     * emitted first, and it is the ONLY flag whose value depends on the order the
     * two were REACHED. Both directions are pinned, because a helper that always
     * answered the same way would pass one of them.
     */
    @Test
    fun `helper insertion - exportStar first when the star re-export comes first`() {
        val out = js(
            dep +
            """
            // @Filename: main.ts
            export * from "./dep";
            import * as Star from "./dep";
            export const u = Star.dv;
            """
        )
        assert(out.contains("var __importStar ="))
        assert(out.indexOf("var __exportStar =") < out.indexOf("var __importStar ="))
    }

    @Test
    fun `helper insertion - importStar first when the namespace import comes first`() {
        val out = js(
            dep +
            """
            // @Filename: main.ts
            import * as Star from "./dep";
            export * from "./dep";
            export const u = Star.dv;
            """
        )
        assert(out.contains("var __importStar ="))
        assert(out.indexOf("var __importStar =") < out.indexOf("var __exportStar ="))
    }
}
