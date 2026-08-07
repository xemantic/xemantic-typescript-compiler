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
 * (JIT.1)(e) round 816 — `compileParsedCore` was **21,535 bytecodes**, 2.7x
 * HotSpot's 8,000-byte `HugeMethodLimit`, so it was never JIT-compiled. It is
 * now an entry of 293 plus ten helpers, each a CONTIGUOUS run of the original
 * body:
 *
 *  * `cpcCheckDeprecatedOptions` / `cpcCheckEmitOptionConflicts` /
 *    `cpcCheckModuleAndLibOptions` / `cpcCheckProjectShapeOptions` — the four
 *    option-validation runs of the prologue;
 *  * `cpcCompileSingleFile` / `cpcCompileMultiFile` — the two arms of the
 *    single-vs-multi dispatch, moved WHOLE, so all four whole-function
 *    `return`s went with them and no helper needs a return signal;
 *  * `cpcScanFiles` / `cpcBindAndCheck` / `cpcTransformAndEmit` /
 *    `cpcRequireOnlyOrphans` — four runs of the multi-file arm.
 *
 * `HugeMethodLimitTest` reads the compiled `Code` attribute lengths and guards
 * the SIZE. This class pins what a size check cannot see:
 *
 *  * one ARM pin per helper — an observable only that run produces, so a
 *    dropped call site is visible and attributable;
 *  * the `options` SEAM — the entry keeps the ONE place `options` is
 *    reassigned (the NodeNext `package.json` scan) and passes the RESULT to
 *    every helper; passing `baseOptions` instead compiles fine and silently
 *    loses it;
 *  * the ARGUMENT-IDENTITY seam — `cpcScanFiles` takes 18 parameters, three of
 *    them `MutableList<Pair<String, String>>`, so a POSITIONAL call could
 *    permute two of them and still type-check. The call site names every
 *    argument; this pin is what notices if it stops doing so.
 */
class CpcSplitTest {

    private fun compile(@Language("typescript") source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "input.ts")

    // ── cpcCheckDeprecatedOptions ───────────────────────────────────────────

    @Test
    fun `deprecated-options run - baseUrl reports TS5101 exactly once`() {
        val d = compile(
            """
            // @baseUrl: ./src
            export const x = 1;
            """
        ).diagnostics
        assert(d.count { it.code == 5101 } == 1)
    }

    @Test
    fun `deprecated-options run - an explicit ES5 target reports TS5107`() {
        // The SECOND half of the same run (`addDeprecation`, TS5107/TS5108) —
        // a separate arm, so dropping either half of the region is visible.
        val d = compile(
            """
            // @target: es5
            export const x = 1;
            """
        ).diagnostics
        assert(d.count { it.code == 5107 } == 1)
    }

    @Test
    fun `deprecated-options run - ignoreDeprecations 6_0 suppresses the baseUrl report`() {
        // `effectiveIgnoreDeprecations` and `isDeprecationSuppressed` are declared
        // INSIDE this run and read by its emitters — the boundary keeps them
        // together, and this is the pin that says so.
        val d = compile(
            """
            // @baseUrl: ./src
            // @ignoreDeprecations: 6.0
            export const x = 1;
            """
        ).diagnostics
        assert(d.none { it.code == 5101 })
    }

    // ── cpcCheckEmitOptionConflicts ─────────────────────────────────────────

    @Test
    fun `emit-conflict run - declarationMap without declaration reports TS5069`() {
        val d = compile(
            """
            // @declarationMap: true
            export const x = 1;
            """
        ).diagnostics
        assert(d.count { it.code == 5069 } == 1)
    }

    // ── cpcCheckModuleAndLibOptions ─────────────────────────────────────────

    @Test
    fun `module-lib run - noLib with an explicit lib reports TS5053`() {
        val d = compile(
            """
            // @noLib: true
            // @lib: es5
            export const x = 1;
            """
        ).diagnostics
        assert(d.count { it.code == 5053 } == 1)
    }

    @Test
    fun `module-lib run - moduleResolution nodenext without module nodenext reports TS5110`() {
        val d = compile(
            """
            // @moduleResolution: nodenext
            // @module: commonjs
            export const x = 1;
            """
        ).diagnostics
        assert(d.count { it.code == 5110 } == 1)
    }

    // ── cpcCheckProjectShapeOptions ─────────────────────────────────────────

    @Test
    fun `project-shape run - an unsupported input extension reports TS6054`() {
        val d = compile(
            """
            // @Filename: notes.txt
            hello
            // @Filename: a.ts
            export const x = 1;
            """
        ).diagnostics
        assert(d.count { it.code == 6054 } == 1)
    }

    // ── cpcCompileSingleFile ────────────────────────────────────────────────

    @Test
    fun `single-file arm - a one-file program emits its JS and is not multi-file`() {
        val r = compile(
            """
            export const x: number = 1;
            """
        )
        assert(!r.isMultiFile)
        assert(r.jsOutputs.size == 1)
        assert(r.javascript!!.contains("x = 1"))
    }

    // ── cpcCompileMultiFile ─────────────────────────────────────────────────

    @Test
    fun `multi-file arm - a two-file program emits one JS per file`() {
        val r = compile(
            """
            // @Filename: a.ts
            export const a: number = 1;
            // @Filename: b.ts
            export const b: number = 2;
            """
        )
        assert(r.isMultiFile)
        assert(r.jsOutputs.size == 2)
    }

    // ── cpcScanFiles ────────────────────────────────────────────────────────

    @Test
    fun `scan run - the parser diagnostics of the SECOND file are collected`() {
        // The per-file scan is the only place a non-first file is parsed and its
        // parser diagnostics are appended.
        val d = compile(
            """
            // @Filename: a.ts
            export const a: number = 1;
            // @Filename: b.ts
            export const b: number = ;
            """
        ).diagnostics
        assert(d.any { it.fileName?.endsWith("b.ts") == true && it.code == 1109 })
    }

    @Test
    fun `scan run - every input file is echoed in input order`() {
        // ARGUMENT-IDENTITY seam: `sourceEchoes` and `jsonOutputs` are both
        // `MutableList<Pair<String, String>>` parameters of `cpcScanFiles`, so a
        // positional call site could swap them and still compile.
        val r = compile(
            """
            // @Filename: a.ts
            export const a: number = 1;
            // @Filename: b.ts
            export const b: number = 2;
            """
        )
        assert(r.sourceEchoes.map { it.first.substringAfterLast('/') } == listOf("a.ts", "b.ts"))
    }

    // ── cpcBindAndCheck ─────────────────────────────────────────────────────

    @Test
    fun `bind-check run - a cross-file argument error needs the SHARED checker`() {
        val d = compile(
            """
            // @Filename: a.ts
            export function take(n: number): number { return n; }
            // @Filename: b.ts
            import { take } from "./a";
            export const bad = take("no");
            """
        ).diagnostics
        assert(d.count { it.code == 2345 } == 1)
    }

    // ── cpcTransformAndEmit ─────────────────────────────────────────────────

    @Test
    fun `transform-emit run - each file's JS is the TRANSFORMED output`() {
        val r = compile(
            """
            // @Filename: a.ts
            export enum E { A = 1 }
            // @Filename: b.ts
            export const b: number = 2;
            """
        )
        val js = r.jsOutputs.first { it.first.endsWith("a.js") }.second
        assert(js.contains("E[E[\"A\"] = 1] = \"A\""))
    }

    // ── cpcRequireOnlyOrphans ───────────────────────────────────────────────

    @Test
    fun `orphan run - a file reached only by a bare require is not emitted`() {
        val r = compile(
            """
            // @Filename: b.ts
            export const y: number = 2;
            // @Filename: a.ts
            declare const require: any;
            require('./b');
            export const x: number = 1;
            """
        )
        assert(r.jsOutputs.none { it.first.endsWith("b.js") })
        assert(r.jsOutputs.any { it.first.endsWith("a.js") })
    }

    // ── the `options` seam ──────────────────────────────────────────────────

    @Test
    fun `options seam - the package_json type scan reaches the multi-file arm`() {
        // The entry is the ONE place `options` is reassigned (`packageJsonTypes`),
        // and it passes the RESULT down. Handing the helpers `baseOptions` instead
        // compiles cleanly and silently drops the ESM decision.
        val r = compile(
            """
            // @module: nodenext
            // @moduleResolution: nodenext
            // @Filename: package.json
            { "type": "module" }
            // @Filename: a.ts
            export const x: number = 1;
            """
        )
        val js = r.jsOutputs.first { it.first.endsWith("a.js") }.second
        assert(js.contains("export const x"))
    }

    // ── negative control ────────────────────────────────────────────────────

    @Test
    fun `negative control - the four validation runs do not read each other`() {
        // Each run only APPENDS to `diagnostics`; none reads it back, and no two
        // emit the same code. So a source that triggers all four reports all four
        // whatever order the calls are made in — which is why the round's
        // boundary-order ablation is predicted to fail 0 pins.
        val d = compile(
            """
            // @baseUrl: ./src
            // @declarationMap: true
            // @noLib: true
            // @lib: es5
            // @Filename: notes.txt
            hello
            // @Filename: a.ts
            export const x = 1;
            """
        ).diagnostics
        assert(d.count { it.code == 5101 } == 1)
        assert(d.count { it.code == 5069 } == 1)
        assert(d.count { it.code == 5053 } == 1)
        assert(d.count { it.code == 6054 } == 1)
    }
}
