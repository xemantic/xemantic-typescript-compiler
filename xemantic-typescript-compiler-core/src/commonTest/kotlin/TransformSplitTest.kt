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
 * (JIT.1)(e) round 817 — pins for the split of `Transformer.transform`
 * (**8,934 bytecodes**, over HotSpot's 8,000-byte `HugeMethodLimit`, so never
 * JIT-compiled) into an entry plus seven `tf*` helpers.
 *
 * **This is the first target in the arc on the EMIT path**, which changes what a
 * gate can see: every A/B in this arc is `--noEmit` and therefore blind to it, so
 * the real gate is the corpus suite's EMIT baselines plus these pins. Nothing
 * here is a performance claim — `transform` runs once per FILE, and this lands
 * for the threshold and for the (JIT.1)(f) ratchet.
 *
 * `HugeMethodLimitTest` guards the SIZE. This class pins what a size check cannot
 * see: one ARM pin per helper (an observable only that stage produces, so a
 * dropped call site is visible AND attributable), plus the two seams that a
 * compiler cannot catch:
 *
 *  * the **ORDER seam** between `tfCollectHelperStatements` and
 *    `tfLiftLeadingComments`. Both take `helpers` as an argument and neither
 *    returns it, so swapping the two calls TYPE-CHECKS — and silently drops every
 *    helper body, because the lift reads `helpers` by value at the moment it
 *    runs. Nothing in the data flow enforces the order; these pins do;
 *  * the **SET-IDENTITY seam** in `tfCollectTopLevelNames`. It fills the CALLER's
 *    `topLevelRuntimeNames`, which the caller then subtracts from
 *    `topLevelTypeOnlyNames`. A helper that allocated its own set compiles fine
 *    and makes every name that is BOTH a type and a value look type-only, so
 *    `export { X }` is wrongly erased.
 */
class TransformSplitTest {

    private fun compile(@Language("typescript") source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "input.ts")

    private fun js(@Language("typescript") source: String): String =
        compile(source).jsOutputs.joinToString("\n") { it.second }

    // ── tfCollectTopLevelNames ──────────────────────────────────────────────

    @Test
    fun `top-level name pre-pass - an export specifier naming only a type is erased`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            interface PureType { b: number }
            class RealClass {}
            export { PureType, RealClass };
            """
        )
        assert(out.contains("export { RealClass }"))
        assert(!out.contains("PureType"))
    }

    /**
     * THE SET-IDENTITY SEAM. `Both` is declared as an interface AND as a `const`,
     * so it enters both sets and survives only because the caller subtracts the
     * very set instance the helper filled. A helper with its own set leaves
     * `Both` in the type-only set and erases the export.
     */
    @Test
    fun `top-level name pre-pass - a name that is both a type and a value stays exported`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            interface Both { a: number }
            const Both = 1;
            export { Both };
            """
        )
        assert(out.contains("const Both = 1"))
        assert(out.contains("export { Both }"))
    }

    // ── tfCollectHelperStatements ───────────────────────────────────────────

    @Test
    fun `helper-statement run - a downlevelled async function gets the inline __awaiter body`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            export async function go(): Promise<void> {}
            """
        )
        assert(out.contains("var __awaiter = (this && this.__awaiter)"))
        assert(out.contains("__awaiter(this, void 0, void 0"))
    }

    // ── tfLiftLeadingComments ───────────────────────────────────────────────

    /**
     * THE ORDER SEAM, and the arm pin for the lift in one shape: the banner must
     * come FIRST and the helper must still be there. Swapping the two calls loses
     * the helper entirely; dropping the lift leaves the banner below it.
     */
    @Test
    fun `comment lift - a detached banner is emitted above the helper bodies`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            // detached banner

            export const f = async (): Promise<void> => {};
            """
        )
        val banner = out.indexOf("// detached banner")
        val helper = out.indexOf("var __awaiter")
        assert(banner >= 0)
        assert(helper >= 0)
        assert(banner < helper)
    }

    // ── tfInjectTslibImport ─────────────────────────────────────────────────

    @Test
    fun `tslib injection - importHelpers on an ESM module imports the helper by name`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            // @importHelpers: true
            export async function go(): Promise<void> {}
            """
        )
        assert(out.contains("import { __awaiter } from \"tslib\""))
        // ... and the inline body is NOT emitted beside it.
        assert(!out.contains("var __awaiter = (this && this.__awaiter)"))
    }

    // ── tfElideInternalImportAliases ────────────────────────────────────────

    @Test
    fun `internal alias elision - a type-only import-equals emits no runtime alias`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            namespace N { export const b = 1; }
            import type A = N.b;
            import B = N.b;
            export const use = B;
            """
        )
        assert(out.contains("var B = N.b"))
        assert(!out.contains("var A = N.b"))
    }

    // ── tfWrapNoLibMetadataArgs ─────────────────────────────────────────────

    @Test
    fun `noLib metadata wrap - a design-type naming a questionable global is guarded`() {
        val out = js(
            """
            // @module: esnext
            // @target: es2015
            // @noLib: true
            // @isolatedModules: true
            // @experimentalDecorators: true
            // @emitDecoratorMetadata: true
            declare function dec(t: any, k: any): void;
            export class C { @dec m: Map<string, string>; }
            """
        )
        assert(out.contains("typeof (_a = typeof Map !== \"undefined\" && Map) === \"function\" ? _a : Object"))
        // the hoisted temp the wrap needs, placed after the helper bodies
        assert(out.contains("var _a;"))
    }

    // ── tfInjectCreateRequireHeader ─────────────────────────────────────────

    @Test
    fun `createRequire header - an ESM file with import-equals-require gets the module import`() {
        val out = js(
            """
            // @module: nodenext
            // @target: es2015
            // @Filename: a.mts
            declare const zz: number;
            import fs = require("./other.cjs");
            export const x = zz;
            """
        )
        assert(out.contains("import { createRequire as _createRequire } from \"module\""))
        assert(out.contains("const __require = _createRequire(import.meta.url)"))
        assert(out.contains("const fs = __require(\"./other.cjs\")"))
    }

    // ── what STAYED in the entry ────────────────────────────────────────────

    /**
     * The CommonJS branch holds one of the entry's whole-function `return`s and
     * was deliberately NOT moved — that is what buys the round-813 property that
     * no helper needs a return signal. This pin is the control that the branch
     * still reached: a moved-out `return` that failed to propagate would show up
     * as ESM output here.
     */
    @Test
    fun `the CommonJS branch stayed in the entry and still returns from it`() {
        val out = js(
            """
            // @module: commonjs
            // @target: es2015
            export const x: number = 1;
            """
        )
        assert(out.contains("Object.defineProperty(exports, \"__esModule\""))
        assert(out.contains("exports.x = 1"))
        assert(!out.contains("export const x"))
    }
}
