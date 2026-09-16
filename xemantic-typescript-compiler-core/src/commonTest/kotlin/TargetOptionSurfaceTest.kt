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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (LEGACY.1) step (j4) — **the `target` OPTION SURFACE, measured against tsgo 7.0.2 on
 * 2026-09-16** and not inherited from tsc 6.
 *
 * Five claims, each with its own measurement:
 *
 *  1. **The two target notions may NOT be collapsed.** The queue item asks for one
 *     ("their whole reason was the explicit-ES5 split"); both directions were built and
 *     both lose a row tsgo has. The split is now the MODEL of tsgo's missing ES5
 *     transformer: tsgo's emit at a written `es5` is byte-identical to its emit at
 *     `es2015` over 12 lowering shapes, while its CHECKER honours the written `es5`.
 *  2. **The es5 DEFAULT lib reaches es2015.** `lib.d.ts` -> `lib.dom.d.ts`, which opens
 *     `/// <reference lib="es2015" />`; measured on tsgo with `--listFiles` and the LSP,
 *     a written `es5` with no `lib` answers `Array.from`, `Reflect`, `Map`, `Set`,
 *     `Symbol`, `Promise` and `Iterable` exactly as `es2015` does, and reports nothing.
 *     An explicit `lib` is untouched — (LEGACY.1)(i)/(j2)'s TS2802 and TS2461/TS2488 pins
 *     live on `lib: ["es5"]`, where tsgo's answer is identical at every target.
 *  3. **`ES3` is not a `ScriptTarget` at all.** tsgo's `targetOptionMap` has no entry, so
 *     `"ES3"` is an invalid ARGUMENT — TS6046 at the value, after which the option is
 *     UNSET and the program checks and emits at the latest standard. Being unknown to
 *     [ScriptTarget.fromString] is the mechanism, which also covers `"es4"` and every
 *     other misspelling (tsgo reports those; before this round we reported none).
 *  4. **The three Checker sites reading the EMIT notion all KEEP it**, each for a
 *     measured reason rather than by inheritance — two because the two notions fall the
 *     same side of their bound, one because the emit notion is the arm that agrees with
 *     tsgo (which binds every file strict).
 *  5. **`effectiveModule`'s `else` arm is LIVE and reads the WRITTEN target.** The item
 *     calls it dead; tsgo defaults a written-`es5` project with no `module` to
 *     **CommonJS**, and did so in the measurement that found it.
 */
class TargetOptionSurfaceTest {

    private val realLibs = "// @useRealLibs: true"

    // ---- 1. the collapse, REFUSED -------------------------------------------------

    /**
     * The two notions differ at an explicit `es5` and NOWHERE else. This is the pin the
     * refusal rests on: a collapse in either direction makes this assertion unwritable.
     */
    @Test
    fun `the emit target and the written target differ at an explicit es5 and agree everywhere else`() {
        val es5 = CompilerOptions(target = ScriptTarget.ES5, targetExplicitlySet = true)
        assert(es5.effectiveTarget == ScriptTarget.ES2015)
        assert(es5.defaultedTarget == ScriptTarget.ES5)
        for (t in ScriptTarget.entries) {
            if (t == ScriptTarget.ES5) continue
            val o = CompilerOptions(target = t, targetExplicitlySet = true)
            assert(o.effectiveTarget == o.defaultedTarget)
            assert(o.effectiveTarget == t)
        }
        val unset = CompilerOptions()
        assert(unset.effectiveTarget == ScriptTarget.ES2024)
        assert(unset.defaultedTarget == ScriptTarget.ES2024)
    }

    /**
     * Constituency A of the refusal — the arm a collapse onto the WRITTEN target loses.
     * A script's strict-reserved BINDING rows are gated on
     * `spineStrictFileIsStrict`, whose `>= ES2015` disjunct reads the EMIT notion; under
     * that collapse a written `es5` goes non-strict and the rows vanish (measured through
     * the CLI: 2 -> 0, where tsgo reports them at es5, es2015 and an unset target alike).
     */
    @Test
    fun `an explicit es5 script still reports a strict reserved binding name`() {
        val src = """
            function outer() {
                var yield = 2;
                return yield;
            }
        """
        diagnose(src, directives = "// @target: es5\n// @ignoreDeprecations: 6.0") should {
            have(any { it.code == 1212 })
        }
    }

    /**
     * Constituency B — the gate a collapse onto the EMIT notion would OPEN. tsgo's
     * `getTypeFromArrayBindingPattern` asks for the `Iterable` global only at
     * `languageVersion >= ES2015` (the WRITTEN target, `checker.go:17879`), so at a
     * written `es5` with a lib that has no `Iterable` it is silent. (LEGACY.1)(j2) pinned
     * the same gate from the other side; this pin exists so a later collapse cannot pass.
     */
    @Test
    fun `an explicit es5 target does not ask for the Iterable global`() {
        val src = "const [...rest] = [1, 2, 3];"
        diagnose(src, directives = "$realLibs\n// @lib: es5\n// @target: es5\n// @ignoreDeprecations: 6.0") should {
            have(none { it.code == 2318 })
        }
    }

    @Test
    fun `control - an explicit es2015 target does ask for the Iterable global`() {
        val src = "const [...rest] = [1, 2, 3];"
        diagnose(src, directives = "$realLibs\n// @lib: es5\n// @target: es2015") should {
            have(any { it.code == 2318 })
        }
    }

    // ---- 2. the es5 DEFAULT lib level ---------------------------------------------

    /**
     * The table, read off [RealLibResolver.defaultLibFileName]'s own arms: `lib.d.ts`
     * reaches es2015 through `dom`, every `esNNNN.full` reaches its own version.
     */
    @Test
    fun `the default lib level of an es5 target is es2015 and of every other target itself`() {
        assert(RealLibResolver.defaultLibEsLevel(ScriptTarget.ES5) == ScriptTarget.ES2015)
        for (t in ScriptTarget.entries) {
            if (t == ScriptTarget.ES5) continue
            assert(RealLibResolver.defaultLibEsLevel(t) == t)
        }
    }

    /** `Reflect` is es2015 and the es5 default lib HAS it — tsgo reports nothing here. */
    @Test
    fun `an explicit es5 target resolves an es2015 global from the default lib`() {
        diagnose(
            "const p: object | null = Reflect.getPrototypeOf({});",
            directives = "$realLibs\n// @target: es5\n// @ignoreDeprecations: 6.0",
        ) should { have(none { it.code == 2583 }) }
    }

    /** `Array.from` is es2015 and is a MEMBER, i.e. the TS2550 half of the same gate. */
    @Test
    fun `an explicit es5 target resolves an es2015 member from the default lib`() {
        diagnose(
            "const a: number[] = Array.from([1, 2]);",
            directives = "$realLibs\n// @target: es5\n// @ignoreDeprecations: 6.0",
        ) should { have(none { it.code == 2550 }) }
    }

    /**
     * The BOUND, and what keeps the change from being blanket suppression: the es5
     * default lib stops at es2015, so an es2017 member is still reported — tsgo reports
     * it at a written `es5` and at `es2015` alike (measured).
     */
    @Test
    fun `an explicit es5 target still reports an es2017 member`() {
        diagnose(
            """const s: string = "a".padStart(3);""",
            directives = "$realLibs\n// @target: es5\n// @ignoreDeprecations: 6.0",
        ) should { have(any { it.code == 2550 }) }
    }

    @Test
    fun `control - an explicit lib es5 still reports an es2015 global whatever the target`() {
        // (LEGACY.1)(i)/(j2) pin TS2802 and the TS2461/TS2488 forks on exactly this
        // configuration; the target-derived default must not reach it.
        diagnose(
            "const p: object | null = Reflect.getPrototypeOf({});",
            directives = "$realLibs\n// @lib: es5\n// @target: es5\n// @ignoreDeprecations: 6.0",
        ) should { have(any { it.code == 2583 }) }
    }

    @Test
    fun `control - the es5 default lib FILE set is still the es5 one`() {
        // the FILE set is picked by `defaultedTarget` and is NOT what this round changed
        val o = CompilerOptions(target = ScriptTarget.ES5, targetExplicitlySet = true)
        val keys = RealLibResolver.resolve(null, o.defaultedTarget).orderedKeys
        assert("es5.full" in keys)
        assert("es2024.full" !in keys)
    }

    // ---- 3. ES3 is an invalid ARGUMENT --------------------------------------------

    @Test
    fun `es3 names no script target`() {
        assert(ScriptTarget.fromString("es3") == null)
        assert(ScriptTarget.fromString("ES3") == null)
        assert(ScriptTarget.entries.none { it.name == "ES3" })
        // and `es5` is still a KNOWN value — tsgo reports it TS5108, not TS6046
        assert(ScriptTarget.fromString("es5") == ScriptTarget.ES5)
    }

    @Test
    fun `a target value tsgo has no entry for is reported TS6046 and leaves the option unset`() {
        val d = diagnose("const a: number[] = Array.from([1, 2]);", directives = "$realLibs\n// @target: es3")
        d should { have(any { it.code == 6046 }) }
        // the option is UNSET afterwards, so the LATEST standard lib is loaded and the
        // es2015 member resolves — tsgo: `lib.es2025.full.d.ts`, no checker row
        d should { have(none { it.code == 2550 }) }
        d should { have(none { it.code == 5107 }) }
    }

    @Test
    fun `an unknown target spelling is reported TS6046 too`() {
        // before (LEGACY.1)(j4) this was SILENT here and TS6046 in tsgo
        diagnose("export const q = 1;", directives = "// @target: es4") should {
            have(any { it.code == 6046 })
        }
    }

    @Test
    fun `control - es5 keeps its deprecation ladder and is not TS6046`() {
        val d = diagnose("export const q = 1;", directives = "// @target: es5")
        d should { have(any { it.code == 5107 }) }
        d should { have(none { it.code == 6046 }) }
    }

    @Test
    fun `control - the unset target still answers the latest standard`() {
        val o = CompilerOptions()
        assert(!o.targetExplicitlySet)
        assert(o.target == ScriptTarget.ES5)
        assert(o.defaultedTarget == ScriptTarget.ES2024)
        assert(o.effectiveTarget == ScriptTarget.ES2024)
        // the two surviving RAW readers are `target >= ES2015` strict-mode determinations
        // and are correct only while the zero value stays below ES2015
        assert(o.target < ScriptTarget.ES2015)
    }

    // ---- 4. the three Checker sites reading the EMIT notion ------------------------

    /**
     * `checkBigIntExponentiation`'s pass gate is `effectiveTarget < ES2016`; tsgo's is
     * `languageVersion < ES2016`. ES5 and ES2015 — the only inputs on which the notions
     * differ — are both below it, so the pass runs at a written es5 either way, and the
     * three-way split (fires at es5 and es2015, silent at an unset target) is the same.
     */
    @Test
    fun `control - the bigint literal gate answers the same on either target notion`() {
        val src = "const b = 2n ** 3n;"
        diagnose(src, directives = "// @target: es5\n// @ignoreDeprecations: 6.0") should {
            have(any { it.code == 2737 })
        }
        diagnose(src, directives = "// @target: es2015") should { have(any { it.code == 2737 }) }
        diagnose(src, directives = "// @strict: true") should { have(none { it.code == 2737 }) }
    }

    /**
     * `checkTopLevelAwaitTargetGate`'s `effectiveTarget >= ES2017` — again a bound both
     * notions fall below at an explicit es5.
     */
    @Test
    fun `control - the top level await gate answers the same on either target notion`() {
        val src = """
            export {};
            declare const p: Promise<number>;
            const v = await p;
        """
        diagnose(src, directives = "// @module: esnext\n// @target: es5\n// @ignoreDeprecations: 6.0") should {
            have(any { it.code == 1378 })
        }
        diagnose(src, directives = "// @module: esnext\n// @target: es2015") should {
            have(any { it.code == 1378 })
        }
        diagnose(src, directives = "// @module: esnext") should { have(none { it.code == 1378 }) }
    }

    // ---- 5. the module default reads the WRITTEN target ----------------------------

    @Test
    fun `an explicit es5 target with no module option defaults to CommonJS`() {
        val es5 = CompilerOptions(target = ScriptTarget.ES5, targetExplicitlySet = true)
        assert(es5.effectiveModule == ModuleKind.CommonJS)
        val es2015 = CompilerOptions(target = ScriptTarget.ES2015, targetExplicitlySet = true)
        assert(es2015.effectiveModule == ModuleKind.ES2015)
        assert(CompilerOptions().effectiveModule == ModuleKind.ES2015)
    }

    /**
     * The same fact in EMITTED BYTES, which is the channel a value pin on the property
     * cannot see. tsgo at a written `es5` with no `module` emits the CommonJS prologue
     * (measured over a two-file ESM program) and native `import`/`export` at es2015.
     */
    @Test
    fun `an explicit es5 target emits the CommonJS prologue`() {
        val js = TypeScriptCompiler().compile(
            "// @target: es5\n// @ignoreDeprecations: 6.0\nexport const a = 1;\n",
            "t.ts",
        ).jsOutputs.single().second
        assert("""Object.defineProperty(exports, "__esModule", { value: true });""" in js)
        assert("export const a" !in js)
    }

    @Test
    fun `control - an explicit es2015 target emits native ESM`() {
        val js = TypeScriptCompiler().compile(
            "// @target: es2015\nexport const a = 1;\n",
            "t.ts",
        ).jsOutputs.single().second
        assert("export const a = 1;" in js)
        assert("__esModule" !in js)
    }
}
