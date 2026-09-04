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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (REL.2) round 766: a bare-enum argument at a `never` PARAMETER keeps its narrowed
 * member subset instead of reverting to the declared enum.
 *
 * The flow read was never the missing step — round 441's `never`-parameter arm has
 * always PERFORMED it; what it does is DISCARD any result that is not exactly `never`,
 * so `default: assertNever(k)` in a partially-covered switch reported the whole enum
 * where tsc reports the uncovered member. That discard is load-bearing for every OTHER
 * declared type (a partial refinement of an interface would take the union-arg emission
 * path), which is why the exception is gated to an enum whose narrowed constituents are
 * all its OWN members — the round-746 owner rule shared with the subtractive (round 764)
 * and assert (round 765) directions.
 *
 * It cannot manufacture a diagnostic: the substituted type is a NON-EMPTY union of enum
 * members and nothing non-`never` is assignable to `never`, so the TS2345 that already
 * fired still fires and only its DISPLAY changes.
 *
 * Probe discipline: the target type is what discriminates here, so every `never`-target
 * pin has a `string`-target twin — `a string target still FIRES at the same position`
 * fails if the harness ever stops checking that argument at all. (PARITY.2) re-expected
 * that twin: since the enum arm of `Checker.baseTypeOfLiteralType` landed a `string`
 * target generalizes an enum-member source to its parent enum, so the twin reads `K`
 * where it used to read `K.D`. That IS tsc's answer there (measured on tsgo 7.0.2 and
 * pristine `typescript@6.0.3`), and there is NO other target that both reports and keeps
 * the member at an argument position here — a literal parameter and an enum parameter are
 * both silent ((CHK.83)) — so the twin can no longer name the member and says so. The controls are the two ways
 * the exception could go wrong: widening it out of the enum sub-case (the two interface
 * pins, which reach the arm through a ternary and an early return rather than an `if`
 * BLOCK, so they genuinely exercise the discard) and widening it past the owner rule
 * (the mixed case list and the unrelated enum).
 */
class EnumNeverParamNarrowingTest {

    private val prelude = """
        enum K { A, B, C, D }
        declare function assertNever(x: never): never;
        declare function probe(x: string): void;

    """.trimIndent()

    private fun narrowedTo(body: String, expected: String) {
        diagnose(prelude + body) should {
            have(any { it.code == 2345 && it.message.contains("Argument of type '$expected'") })
        }
    }

    // ---- targets: every one of these answered the bare enum `K` before this round ----

    @Test
    fun `a partially covered switch default at a never parameter names the uncovered member`() {
        narrowedTo(
            """
            export function f(k: K) {
              switch (k) { case K.A: break; case K.B: break; case K.C: break; default: assertNever(k); }
            }
            """.trimIndent(),
            "K.D",
        )
    }

    @Test
    fun `a switch default at a never parameter subtracts every cased member`() {
        narrowedTo(
            "export function f(k: K) { switch (k) { case K.A: break; default: assertNever(k); } }",
            "K.B | K.C | K.D",
        )
    }

    @Test
    fun `a switch default subtracting a MIDDLE member keeps declaration order at a never parameter`() {
        narrowedTo(
            "export function f(k: K) { switch (k) { case K.B: break; default: assertNever(k); } }",
            "K.A | K.C | K.D",
        )
    }

    @Test
    fun `equality guards subtract from a bare enum at a never parameter`() {
        // The subtractive direction round 764 landed, reaching a `never` parameter through
        // a shape with no switch at all — so the exception is not switch-specific.
        narrowedTo(
            """
            export function f(k: K) {
              if (k === K.A) return;
              if (k === K.B) return;
              if (k === K.C) return;
              assertNever(k);
            }
            """.trimIndent(),
            "K.D",
        )
    }

    @Test
    fun `a namespace qualified assertNever callee narrows the same way`() {
        // tsc writes `Debug.assertNever(k)`; the callee shape must not change the answer.
        narrowedTo(
            """
            namespace Debug { export declare function assertNever(x: never): never; }
            export function f(k: K) {
              switch (k) { case K.A: break; case K.B: break; case K.C: break; default: Debug.assertNever(k); }
            }
            """.trimIndent(),
            "K.D",
        )
    }

    @Test
    fun `a const enum narrows at a never parameter`() {
        narrowedTo(
            """
            declare const enum CE { X, Y, Z }
            export function f(c: CE) {
              switch (c) { case CE.X: break; case CE.Y: break; default: assertNever(c); }
            }
            """.trimIndent(),
            "CE.Z",
        )
    }

    @Test
    fun `an asserts substitution reaches a never parameter`() {
        // Round 765's assert arm produces the member subset; before this round the
        // `never` parameter threw it away again.
        narrowedTo(
            """
            declare function assertAB(k: K): asserts k is K.A | K.B;
            export function f(k: K) { assertAB(k); assertNever(k); }
            """.trimIndent(),
            "K.A | K.B",
        )
    }

    // ---- controls: identical before and after ----

    @Test
    fun `negative control - a string target still FIRES at the same position`() {
        // The twin of the first target: the same source at an ORDINARY parameter must
        // still be argument-checked, so a TS2345 fires. (PARITY.2): it names the
        // GENERALIZED enum `K` rather than the narrowed `K.D`, which is what tsgo 7.0.2
        // and pristine `typescript@6.0.3` both print at a `string` parameter for this
        // source; the member-naming half of the twin lives at the `never`-target pins
        // above, and nothing else at an argument position can carry it.
        narrowedTo(
            """
            export function f(k: K) {
              switch (k) { case K.A: break; case K.B: break; case K.C: break; default: probe(k); }
            }
            """.trimIndent(),
            "K",
        )
    }

    @Test
    fun `negative control - an exhaustive switch at a never parameter stays silent`() {
        // Round 460b's exhaustion arm answers `never`, which round 441 already accepted.
        diagnose(
            prelude +
                """
                export function f(k: K) {
                  switch (k) {
                    case K.A: break; case K.B: break; case K.C: break; case K.D: break;
                    default: assertNever(k);
                  }
                }
                """.trimIndent(),
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `negative control - an interface narrowed through a ternary keeps its declared type`() {
        // Round 441's discard, exercised for real: a ternary condition needs the flow read,
        // so this arrives at the arm with `Animal` and a narrowed `Dog`. A widening of the
        // exception out of the enum sub-case fails HERE rather than on a corpus baseline.
        narrowedTo(
            """
            interface Animal { legs: number }
            interface Dog extends Animal { bark(): void }
            declare function isDog(a: Animal): a is Dog;
            export function f(a: Animal) { return isDog(a) ? assertNever(a) : 0; }
            """.trimIndent(),
            "Animal",
        )
    }

    @Test
    fun `negative control - an interface narrowed through an early return keeps its declared type`() {
        narrowedTo(
            """
            interface Animal { legs: number }
            interface Dog extends Animal { bark(): void }
            declare function isDog(a: Animal): a is Dog;
            export function f(a: Animal) { if (!isDog(a)) return 0; return assertNever(a); }
            """.trimIndent(),
            "Animal",
        )
    }

    @Test
    fun `negative control - a mixed enum and literal case list keeps the whole enum`() {
        // The owner rule bails on a literal case, so nothing is subtracted and the
        // declared enum survives the exception.
        narrowedTo(
            """
            export function f(k: K) {
              switch (k as K | "z") { case K.A: break; case "z": break; default: assertNever(k); }
            }
            """.trimIndent(),
            "K",
        )
    }

    @Test
    fun `negative control - an unrelated enum's member subtracts nothing`() {
        narrowedTo(
            """
            enum J { X, Y }
            declare function assertJX(j: J): asserts j is J.X;
            export function f(k: K, j: J) { assertJX(j); assertNever(k); }
            """.trimIndent(),
            "K",
        )
    }

    @Test
    fun `negative control - a literal union subject at a never parameter still narrows`() {
        // The union arm above round 441's has always handled this; the exception must not
        // change any answer it already gives.
        narrowedTo(
            """
            export function f(s: "a" | "b" | "c") {
              switch (s) { case "a": break; case "b": break; default: assertNever(s); }
            }
            """.trimIndent(),
            "\"c\"",
        )
    }
}
