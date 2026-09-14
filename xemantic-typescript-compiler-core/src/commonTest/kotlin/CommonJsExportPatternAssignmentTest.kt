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
 * (LEGACY.0b) step 11, the CommonJS **export-pattern assignment**: TypeScript 7 emits an
 * `export`ed destructuring declaration in a CommonJS module as a destructuring **ASSIGNMENT**
 * through `exports.*` — `[exports.bar1] = [1];`, `({ x: exports.x, ...exports.rest } = …)`,
 * `({} = {})` — where TypeScript 6 flattened it into one `exports.x = init.x` per name.
 *
 * The rule is tsgo's `CommonJSModuleTransformer.transformInitializedVariable`
 * (`internal/transformers/moduletransforms/commonjsmodule.go`), and its own comment states the
 * reason: converting the pattern to an assignment *preserves native destructuring, and therefore
 * the iterator semantics of an array pattern*, wherever each leaf can be substituted to an export
 * reference. It closes SEVEN `tsgoPendingBaselines` rows, which had been filed as six plus one
 * singleton — `declarationEmitRetainsJsdocyComments` is the same mechanism, and needed the
 * printer half below as well.
 *
 * **EVERY EXPECTATION HERE WAS MEASURED AGAINST `tools/tsgo-7.0.2/lib/tsc`.** One project
 * carrying all of these declarations plus the refusals below was compiled by both compilers and
 * is byte-identical bar the three divergences named under "what the refusals cost", each of
 * which is PRE-EXISTING in the TypeScript-6 fallback this change does not touch.
 *
 * ## The printer half
 *
 * An exported object pattern whose binding element carries a JSDoc comment needs that
 * OWN-LINE leading comment to break
 * the line inside a SINGLE-LINE object literal (`{ ` newline comment newline property ` }`).
 * That branch of `Emitter.emitObjectLiteral` dropped such comments outright. It is reachable
 * only through a SYNTHESIZED single-line object literal, because a literal whose source spans
 * lines is parsed `multiLine` and takes the other branch — which is why the corpus screen reads
 * a blast radius of exactly zero for it, and why the row's own diff closing is its positive
 * control.
 *
 * ## What the refusals cost, measured
 *
 * The conversion is refused — keeping the TypeScript-6 lowering — for a re-aliased or
 * multi-exported leaf, for an object rest below ES2018, and for a declaration list that is not
 * ALL initialized binding patterns. In each case tsgo's own answer differs from the fallback's,
 * and that difference is pre-existing and NOT enlarged here:
 *
 *  * re-aliased: tsgo folds the alias into the flattened assignment
 *    (`exports.zz = exports.z = [1][0];`) where the fallback writes `exports.z = [1][0];` and a
 *    separate `exports.zz = exports.z;`;
 *  * object rest at `es2016`: tsgo keeps the pattern for the non-rest half
 *    (`({ x: exports.x } = (_a = …, _a), exports.rest = __rest(_a, ["x"]))`), where the fallback
 *    keeps a local declaration — and additionally exports a spurious `exports._a`;
 *  * mixed list: tsgo emits one comma expression `[exports.a2] = [1], exports.b2 = 2;`.
 *
 * None of the three is covered by any corpus baseline (the emit channel of
 * `scripts/corpus-screen.sh` reads 5,670 subtests and 0 mismatches across this change).
 */
class CommonJsExportPatternAssignmentTest {

    private fun emit(
        @Language("typescript") body: String,
        target: String = "esnext",
        module: String = "commonjs",
    ): String {
        val source = """
            // @target: $target
            // @module: $module
            // @noEmitHelpers: true
            // @filename: m.ts
            ${body.trimIndent().replace("\n", "\n            ")}
        """.trimIndent()
        return TypeScriptCompiler().compile(source, "m.ts")
            .jsOutputs.joinToString("\n") { it.second }.trim()
    }

    /** The last line of the emitted module — everything above it is the `exports.* = void 0` hoist. */
    private fun lastLine(js: String): String = js.trimEnd().substringAfterLast('\n')

    @Test
    fun `an exported array pattern becomes a destructuring assignment through exports`() {
        assert(lastLine(emit("export let [bar1] = [1];")) == "[exports.bar1] = [1];")
    }

    @Test
    fun `an exported object pattern is parenthesized as an expression statement`() {
        assert(lastLine(emit("export const {a: bar3} = { a: 1 };")) == "({ a: exports.bar3 } = { a: 1 });")
    }

    @Test
    fun `a shorthand property restates its key because the target is no longer an identifier`() {
        assert(lastLine(emit("export let { toString } = 1;")) == "({ toString: exports.toString } = 1);")
    }

    @Test
    fun `an empty array pattern keeps the pattern instead of minting a temporary`() {
        assert(lastLine(emit("export const [] = [];")) == "[] = [];")
    }

    @Test
    fun `an empty object pattern keeps the pattern instead of minting a temporary`() {
        assert(lastLine(emit("export const {} = {};")) == "({} = {});")
    }

    @Test
    fun `an object rest at esnext is spread into the assignment rather than lowered to __rest`() {
        assert(
            lastLine(emit("export const { x, ...rest } = { x: 'x', y: 'y' };"))
                == "({ x: exports.x, ...exports.rest } = { x: 'x', y: 'y' });"
        )
    }

    @Test
    fun `holes stay holes and the patterns own trailing comma is not carried`() {
        assert(
            lastLine(emit("export let [,,[,[],,[],]] = undefined as any;"))
                == "[, , [, [], , []]] = undefined;"
        )
    }

    @Test
    fun `a default on a binding element becomes a default on the assignment target`() {
        assert(lastLine(emit("export const [dflt = 1] = [];")) == "[exports.dflt = 1] = [];")
    }

    @Test
    fun `a nested object pattern substitutes only its leaves`() {
        assert(
            lastLine(emit("export const { p: { q } } = { p: { q: 1 } };"))
                == "({ p: { q: exports.q } } = { p: { q: 1 } });"
        )
    }

    @Test
    fun `an array rest becomes a spread element`() {
        assert(lastLine(emit("export const [h, ...t] = [1, 2, 3];")) == "[exports.h, ...exports.t] = [1, 2, 3];")
    }

    @Test
    fun `a string-literal property name is carried verbatim`() {
        assert(
            lastLine(emit("""export const { "str-key": sk } = { "str-key": 1 };"""))
                == """({ "str-key": exports.sk } = { "str-key": 1 });"""
        )
    }

    @Test
    fun `an own-line leading comment on a property breaks the line inside a single-line object literal`() {
        val js = emit(
            """
            export let {
                /**
                * comment5
                */
                someMethod
            } = null as any;
            """
        )
        assert(
            js.endsWith(
                "({ \n" +
                    "    /**\n" +
                    "    * comment5\n" +
                    "    */\n" +
                    "    someMethod: exports.someMethod } = null);"
            )
        )
    }

    @Test
    fun `a later read of a converted name is rewritten to its export reference`() {
        // The local binding does not exist at run time on either side of the conversion, so
        // every later read must still become `exports.x` — byte-identical to tsgo.
        val js = emit(
            """
            export let [bar1] = [1];
            export const { a: bar3 } = { a: 1 };
            function later() { return bar1 + bar3; }
            """
        )
        assert(js.endsWith("function later() { return exports.bar1 + exports.bar3; }"))
    }

    // ------------------------------------------------------------------
    // Negative controls — each keeps the TypeScript-6 lowering.
    // ------------------------------------------------------------------

    @Test
    fun `negative control - a non-exported destructuring keeps its declaration`() {
        assert(lastLine(emit("const local = 0;\nconst [q] = [1];")) == "const [q] = [1];")
    }

    @Test
    fun `negative control - an ES module leaves the export binding alone`() {
        assert(lastLine(emit("export const [a] = [1];", module = "esnext")) == "export const [a] = [1];")
    }

    @Test
    fun `negative control - a re-aliased leaf refuses the native form and flattens`() {
        // tsgo: `exports.zz = exports.z = [1][0];` then `exports.zz = exports.z;`. The first line
        // is where the TypeScript-6 fallback differs, and that difference predates this change.
        val js = emit("export const [z] = [1];\nexport { z as zz };")
        assert(js.endsWith("exports.z = [1][0];\nexports.zz = exports.z;"))
    }

    @Test
    fun `negative control - an object rest below ES2018 keeps the __rest lowering`() {
        val js = emit("export const { x, ...rest } = { x: 'x', y: 'y' };", target = "es2016")
        assert(js.contains("__rest(_a, [\"x\"])") && !js.contains("...exports.rest"))
    }

    @Test
    fun `negative control - a declaration list that is not all patterns keeps its shape`() {
        val js = emit("export const [a2] = [1], b2 = 2;")
        assert(js.endsWith("const [a2] = [1], b2 = 2;\nexports.a2 = a2;\nexports.b2 = b2;"))
    }

    @Test
    fun `negative control - a namespace-level exported destructuring stays flattened`() {
        val js = emit("export namespace M {\n    export let [bar5] = [1];\n}")
        assert(js.contains("M.bar5 = [1][0];") && !js.contains("[M.bar5] = [1]"))
    }
}
