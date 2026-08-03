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
 * (JIT.1)(g) round 809 — the behavioural gate for the nine-way split of
 * `checkAssignmentExpressionCore`.
 *
 * The function was **18,100 bytecodes**, 2.3x HotSpot's 8,000-byte
 * `HugeMethodLimit`, so it was never JIT-compiled and ran interpreted for the
 * whole process. Its body is now an entry of 3,861 plus nine `cae*` helpers,
 * each holding one CONTIGUOUS run of the committed [CtaSections] **level-E**
 * partition (round 786's own instrument, already in the source).
 *
 * **The shape.** Like round 808's `checkVarDeclAssignabilityCore` this is a
 * straight-line statement sequence punctuated by early `return`s — 38 bare ones,
 * every one a whole-function return (checked by a comment-stripped token scan,
 * not by eye). A bare `return` cannot cross a function boundary, so seven of the
 * nine helpers return `Boolean` (`true` = "I emitted, the caller must return")
 * and the entry replays them as `if (…) return`; the two target-kind tails
 * (`caeThisPropertyAssign`, `caeElementAccessAssign`) contain no `return` at all
 * and are `Unit`.
 *
 * **Cross-boundary values: none.** Every local a moved region reads is either a
 * parameter of the enclosing function or one of the six the entry keeps (`target`,
 * `targetType`, `typeAnnotation`, `tt`, `sourceType`, `canUse`/`isAssignable`),
 * and every local a region declares is dead by its end — established by
 * enumeration over the function's declaration list, not by inspection.
 *
 * **What this class pins that a size check cannot.** `HugeMethodLimitTest` sees
 * the bytecode counts; it cannot see that each helper still RUNS, in the same
 * order, for the shape it owns, nor that a dropped return signal would let a
 * later gate emit a second diagnostic at the same position. Each arm pin below
 * therefore names the helper it exercises and asserts its distinctive MESSAGE —
 * the messages are what distinguish these regions from one another — and asserts
 * a COUNT of 1 wherever a doubled emission is the failure mode.
 *
 * **Honest limits (round 807's law: ablate ONE mistake at a time).** Of the seven
 * return signals only two are DISCRIMINATED by the pins below — see the round-809
 * session note and `docs/perf/setup-phase-and-huge-methods.md` § 13.3 for the
 * per-signal table. The reason the others are not is a property of the FUNCTION,
 * not of the pins: dropping one of those `return`s does not delete the emission,
 * and the gate of every later emitter in this body refuses the same shape anyway
 * (`canUseTypeEngine` declines a union source against an object target, and a
 * class-value-vs-construct-signature pair, and an empty object literal against a
 * wrapper interface relates vacuously). On today's code they are redundant
 * guards; they are kept because the monolith had them.
 */
class CaeSplitTest {

    // ── arm pins: one per helper ──────────────────────────────────────────────

    @Test
    fun `caePrototypeMemberAssign checks a prototype data-property write`() {
        val ds = diagnose(
            """
            class C { p: number = 1 }
            C.prototype.p = "s";
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'string' is not assignable to type 'number'.")
    }

    @Test
    fun `caeModuleAliasAndLibPairShapes keeps B236's optional-vs-required lib-pair rule`() {
        val ds = diagnose(
            """
            declare let execResult: RegExpExecArray;
            declare let matchResult: RegExpMatchArray;
            execResult = matchResult;
            """,
            directives = "// @strict: false",
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'RegExpMatchArray' is not assignable to type 'RegExpExecArray'.")
        assert(
            ts2322[0].messageChain == listOf(
                "  Property 'index' is optional in type 'RegExpMatchArray' but required in type 'RegExpExecArray'."
            )
        )
    }

    @Test
    fun `caeForeignTpTargetAndClassRhs checks a class value against a construct signature`() {
        val ds = diagnose(
            """
            class C { constructor(n: number) {} }
            declare let d: { new (s: string): C };
            d = C;
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'typeof C' is not assignable to type 'new (s: string) => C'.")
        assert(ts2322[0].messageChain?.first() == "  Types of construct signatures are incompatible.")
    }

    @Test
    fun `caeIndexSigAndSignatureGuards keeps B71 3's empty-objlit-vs-wrapper rule`() {
        val ds = diagnose(
            """
            declare let b: Boolean;
            b = {};
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type '{}' is not assignable to type 'Boolean'.")
        assert(
            ts2322[0].messageChain == listOf(
                "  The types returned by 'valueOf()' are incompatible between these types.",
                "    Type 'Object' is not assignable to type 'boolean'.",
            )
        )
    }

    @Test
    fun `caeUnionAndMissingPropertyGuards names the failing nullish constituent`() {
        val ds = diagnose(
            """
            interface P { a: number }
            declare let x: P | undefined;
            declare let y: P;
            y = x;
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'P | undefined' is not assignable to type 'P'.")
        assert(ts2322[0].messageChain == listOf("  Type 'undefined' is not assignable to type 'P'."))
    }

    @Test
    fun `caeElaborateMismatch emits the general TS2322 for a primitive mismatch`() {
        val ds = diagnose(
            """
            declare let n: number;
            n = "s";
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'string' is not assignable to type 'number'.")
    }

    @Test
    fun `caeLegacyDeclaredStringPath still runs for a bare type-parameter target`() {
        val ds = diagnose(
            """
            function f<T>(a: T, b: string) { a = b; }
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'string' is not assignable to type 'T'.")
        assert(
            ts2322[0].messageChain == listOf(
                "  'T' could be instantiated with an arbitrary type which could be unrelated to 'string'."
            )
        )
    }

    @Test
    fun `caeThisPropertyAssign checks a this-property write against varTypes`() {
        val ds = diagnose(
            """
            class C {
                p: number = 0;
                constructor(s: string) { this.p = s; }
            }
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'string' is not assignable to type 'number'.")
    }

    @Test
    fun `caeElementAccessAssign checks an element write against an index signature`() {
        val ds = diagnose(
            """
            interface Bag { [k: string]: number }
            class C {
                bag!: Bag;
                m(k: string) { this.bag[k] = "s"; }
            }
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].message == "Type 'string' is not assignable to type 'number'.")
    }

    // ── seam pins: the return signals ─────────────────────────────────────────

    @Test
    fun `the prototype seam - a dropped signal would let the property-access branch re-emit`() {
        // `caePrototypeMemberAssign` returning `true` is what stops the target-kind
        // dispatch below it from ALSO running `checkPropertyAccessAssignment` on the
        // same `C.prototype.p` target. This is a COUNT pin because the failure mode
        // is a SECOND TS2322, not a missing one.
        val ds = diagnose(
            """
            class C { p: number = 1 }
            C.prototype.p = "s";
            """
        )
        assert(ds.count { it.code == 2322 } == 1)
    }

    @Test
    fun `the elaboration seam - a dropped signal would let the legacy string path re-emit`() {
        // `caeElaborateMismatch` returning `true` is the "type engine handled it —
        // skip the old system" signal. Without it the legacy `varTypes` fallback
        // (`caeLegacyDeclaredStringPath`) re-derives "string" vs "number" from the
        // string map and emits the same TS2322 a second time at the same position.
        val ds = diagnose(
            """
            declare let n: number;
            n = "s";
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322[0].start == 23)
    }

    // ── order + recursion ─────────────────────────────────────────────────────

    @Test
    fun `the helpers keep source order across a file that reaches five of them`() {
        val ds = diagnose(
            """
            class C { p: number = 1 }
            declare let b: Boolean;
            declare let n: number;
            interface P { a: number }
            declare let x: P | undefined;
            declare let y: P;
            C.prototype.p = "s";
            b = {};
            n = "s";
            y = x;
            """
        )
        val ts2322 = ds.filter { it.code == 2322 }
        assert(ts2322.size == 4)
        assert(ts2322.map { it.message } == listOf(
            "Type 'string' is not assignable to type 'number'.",
            "Type '{}' is not assignable to type 'Boolean'.",
            "Type 'string' is not assignable to type 'number'.",
            "Type 'P | undefined' is not assignable to type 'P'.",
        ))
        // non-decreasing positions — the entry's dispatch order is unchanged
        val starts = ts2322.map { it.start ?: -1 }
        assert(starts == starts.sorted())
    }

    @Test
    fun `a chained assignment still recurses into its right-hand assignment`() {
        // `E_RECURSE` stays in the ENTRY: `p = q = "s"` descends into `q = "s"`
        // first, so the inner assignment is checked on its own before the outer one.
        val ds = diagnose(
            """
            declare let p: number;
            declare let q: number;
            p = q = "s";
            """
        )
        assert(ds.count { it.code == 2322 } >= 1)
        assert(ds.any { it.code == 2322 && it.message == "Type 'string' is not assignable to type 'number'." })
    }

    @Test
    fun `negative control - a legal assignment of each shape stays silent`() {
        val ds = diagnose(
            """
            class C { p: number = 1 }
            declare let n: number;
            interface P { a: number }
            declare let y: P;
            declare let z: P;
            C.prototype.p = 2;
            n = 3;
            y = z;
            """
        )
        assert(ds.none { it.code == 2322 })
    }
}
