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
 * (JIT.1)(c) round 808 — the behavioural gate for the seven-way split of
 * `checkVarDeclAssignabilityCore`.
 *
 * The function was **19,296 bytecodes**, 2.4x HotSpot's 8,000-byte
 * `HugeMethodLimit`, so it was never JIT-compiled and ran interpreted for the
 * whole process. Its body is now an entry of 3,535 plus seven `cvda*` helpers,
 * each holding one CONTIGUOUS run of the committed [CtaSections] level-B
 * partition.
 *
 * **The shape, and what this class exists for.** Unlike rounds 803/805/806 (which
 * moved `when` ARMS) and round 807 (which moved runs of a LOOP body), this body is
 * a STRAIGHT-LINE STATEMENT SEQUENCE punctuated by ~40 early `return`s — round
 * 804's shape. A bare `return` cannot cross a function boundary, so each moved
 * region hands back a signal the entry replays:
 *
 * * four regions return `Boolean` — `true` meaning "I emitted, the caller must
 *   return". **A dropped `true` shows up as the coarse whole-declaration TS2322
 *   appearing NEXT TO the specific diagnostic that was supposed to replace it**,
 *   which is why the pins below assert a COUNT rather than a presence
 *   wherever a doubled diagnostic is possible;
 * * two regions (`cvdaRecordInferredLocalType`, `cvdaElaborateMismatch`) held
 *   blocks that returned unconditionally, so their bare `return`s stay bare and
 *   the caller returns straight after the call — for the first of the two that
 *   seam is COMPILER-ENFORCED (the entry's `typeAnnotation` is smart-cast
 *   non-null immediately below the call site, so a missing `return` does not
 *   compile);
 * * `cvdaNestedInitTargets` is the only one with a VALUE crossing the boundary —
 *   `nestedMissingEmitted`, read ~480 lines later by the `B_ELAB` gate. Round
 *   804's rule applies: it is RETURNED (`null` = "the caller must return"), never
 *   stashed in a `Checker` field, because these blocks re-enter the checker and
 *   "the outer write happens last" would not be guaranteed.
 *
 * Every pin is written against an OBSERVABLE of one region, never against a
 * function name — a size check already covers the names (`HugeMethodLimitTest`).
 *
 * **ABLATION RECORD (round 808; round 807's law — five mistakes, five SEPARATE
 * builds, never combined, control first at 32 pins / 0 failed).** `M1` (the
 * prologue walkers' `true` dropped) fails the prologue seam and the ordering
 * pin. `M2` (`nestedMissingEmitted` forced `false`, the `null` signal still
 * honoured) fails **exactly one pin, its own** — the sharpest statement that the
 * arm pins and the seam pins pin different things, and it is the seam that
 * matters most, since that flag is the only value crossing a boundary.
 *
 * **THREE SEAMS ARE NOT DISCRIMINATED, and that is a property of the FUNCTION,
 * not of these pins:** dropping `cvdaEarlyInitGates`', `cvdaMidGates`' or
 * `cvdaPostRelationGates`' `true` — each ALONE — leaves every pin green.
 * The helper still runs and still emits; only the early exit is lost, and
 * **every later emitter in this body is itself conditioned on the relation
 * verdict** (`canUse && !isAssignable`, or `isAssignable`), while the shapes
 * those three regions own are exactly the ones where the relation passes or
 * `canUseTypeEngine` declines. So nothing doubles and no pin can see it. On
 * today's code those three signals are REDUNDANT GUARDS; they are kept because
 * the monolith had them, and if a later gate is ever made unconditional they
 * become load-bearing with no pin to notice. Do not read their silence as
 * coverage.
 */
class CvdaSplitTest {

    // ------------------------------------------------ cvdaPrologueWalkers

    @Test
    fun `prologue arm - a near-miss string literal against a union alias reports the did-you-mean form`() {
        val d = diagnose(
            """
            type T1 = "string" | "number" | "boolean"
            const t1: T1 = "strong"
            """
        )
        assert(d.any {
            it.code == 2820 &&
                it.message == "Type '\"strong\"' is not assignable to type 'T1'. Did you mean '\"string\"'?"
        })
    }

    @Test
    fun `prologue seam - the did-you-mean walker ENDS the check, so the coarse TS2322 never joins it`() {
        val d = diagnose(
            """
            type T2 = "string" | "number" | "boolean"
            const t2: T2 = "strong"
            """
        )
        // The whole point of the region's `return true`: `cvdaElaborateMismatch`
        // would otherwise emit its own "Type '"strong"' is not assignable to type
        // 'T2'." at the same position. Exactly one diagnostic, and it is the
        // spelling-suggestion one.
        assert(d.size == 1)
        assert(d[0].code == 2820)
    }

    // ---------------------------------------- cvdaRecordInferredLocalType

    @Test
    fun `unannotated arm - a const records its initializer's LITERAL type, so a literal-union target accepts it`() {
        val d = diagnose(
            """
            const c1 = "hello"
            const lit1: "hello" | "bye" = c1
            """
        )
        // (WIDEN.1) round 781: the const-keeps-its-literal rule lives in this
        // region and nowhere else — every downstream read of `c1` resolves
        // through the `currentLocalTypes` entry it writes.
        assert(d.isEmpty())
    }

    @Test
    fun `unannotated arm - a let widens to the base primitive, which the same target rejects`() {
        val d = diagnose(
            """
            let v1 = "hello"
            const lit2: "hello" | "bye" = v1
            """
        )
        // The positive control for the pin above: without it, "the const case is
        // silent" could not tell a working recording from no recording at all.
        assert(d.any {
            it.code == 2322 &&
                it.message == "Type 'string' is not assignable to type '\"hello\" | \"bye\"'."
        })
    }

    // ------------------------------------------------- cvdaEarlyInitGates

    @Test
    fun `early-gate arm - a null trust-me cast ENDS the check before the relation ever runs`() {
        val d = diagnose(
            """
            const q1: string = null!
            """
        )
        // `null` is not assignable to `string` under strictNullChecks, so the
        // silence is this region's doing. It does NOT discriminate the region's
        // `return true`, though — ablated (round 808's M3) the pin stays green,
        // because the later emitters are gated on the relation verdict and
        // decline anyway. This is an ARM pin, not a seam pin.
        assert(d.isEmpty())
    }

    @Test
    fun `early-gate arm - an undefined trust-me cast does the same`() {
        val d = diagnose(
            """
            const q2: string = undefined!
            """
        )
        assert(d.isEmpty())
    }

    // ---------------------------------------------- cvdaNestedInitTargets

    @Test
    fun `nested arm - a class expression against a construct-signature target displays as typeof the const`() {
        val d = diagnose(
            """
            const CE: { new (): { p: number } } = class {}
            """
        )
        assert(d.any {
            it.code == 2322 &&
                it.message == "Type 'typeof CE' is not assignable to type 'new () => { p: number; }'." &&
                it.messageChain.any { c ->
                    c.trim() == "Property 'p' is missing in type 'CE' but required in type '{ p: number; }'."
                }
        })
    }

    @Test
    fun `nested seam - the cross-boundary nestedMissingEmitted suppresses the coarse TS2322`() {
        val d = diagnose(
            """
            interface Inner { a: number; b: string }
            interface Outer { xs: Inner[] }
            const o1: Outer = { xs: [{ a: 1 }] }
            """
        )
        // `nestedMissingEmitted` is the ONE value that crosses a helper boundary
        // here: the per-element TS2741 is emitted ~480 lines before the `B_ELAB`
        // gate reads it. Lose it and the coarse
        // "Type '{ xs: { a: number; }[]; }' is not assignable to type 'Outer'."
        // reappears beside the precise one.
        assert(d.size == 1)
        assert(d[0].code == 2741)
        assert(d[0].message ==
            "Property 'b' is missing in type '{ a: number; }' but required in type 'Inner'.")
    }

    // ------------------------------------------------------- cvdaMidGates

    @Test
    fun `mid arm - a call-signature source against a construct-signature target reports the no-match chain`() {
        val d = diagnose(
            """
            declare const fnv: () => void
            const P1: new () => object = fnv
            """
        )
        assert(d.any {
            it.code == 2322 &&
                it.message == "Type '() => void' is not assignable to type 'new () => object'." &&
                it.messageChain.any { c ->
                    c.trim() == "Type '() => void' provides no match for the signature 'new (): object'."
                }
        })
    }

    @Test
    fun `mid arm - that walker reports once, and the relation's own elaboration does not join it`() {
        val d = diagnose(
            """
            declare const fnv2: () => void
            const P2: new () => object = fnv2
            """
        )
        assert(d.size == 1)
        assert(d[0].code == 2322)
    }

    // ------------------------------------------------ cvdaPostRelationGates

    @Test
    fun `post arm - an array-typed source against a tuple target reports the element-count chain`() {
        val d = diagnose(
            """
            declare const arr1: number[]
            const tup1: [number] = arr1
            """
        )
        assert(d.any {
            it.code == 2322 &&
                it.message == "Type 'number[]' is not assignable to type '[number]'." &&
                it.messageChain.any { c ->
                    c.trim() == "Target requires 1 element(s) but source may have fewer."
                }
        })
    }

    @Test
    fun `post arm - the excess-property check reports once, with no second diagnostic after it`() {
        val d = diagnose(
            """
            interface E1 { b: number }
            const ex1: E1 = { b: 1, a: 2 }
            """
        )
        assert(d.size == 1)
        assert(d[0].code == 2353)
        assert(d[0].message ==
            "Object literal may only specify known properties, and 'a' does not exist in type 'E1'.")
    }

    // --------------------------------------------- cvdaElaborateMismatch

    @Test
    fun `elaboration arm - the coarse relation-failure TS2322 still fires from the moved block`() {
        val d = diagnose(
            """
            const e1: string = 42
            """
        )
        assert(d.size == 1)
        assert(d[0].code == 2322)
        assert(d[0].message == "Type 'number' is not assignable to type 'string'.")
    }

    // ----------------------------------------------------------- ordering

    @Test
    fun `the seven regions still run in their committed order within one declaration`() {
        // One declaration cannot exercise every region (each of the first six ends
        // the check), so the order is pinned across declarations instead: each
        // shape below is owned by a LATER region than the one above it, and every
        // one of them still reports. A region called out of order — or a signal
        // replayed at the wrong call site — loses one of these.
        val d = diagnose(
            """
            type T3 = "string" | "number"
            interface I3 { p: number }
            declare const arr3: number[]
            const a3: T3 = "strin"
            const b3: string = null!
            const c3: { new (): I3 } = class {}
            const d3: [number] = arr3
            const e3: string = 42
            """
        )
        assert(d.count { it.code == 2820 } == 1)   // cvdaPrologueWalkers
        assert(d.count { it.code == 2322 } == 3)   // nested + post + elaboration
        assert(d.size == 4)                        // ... and `b3` stayed silent
    }
}
