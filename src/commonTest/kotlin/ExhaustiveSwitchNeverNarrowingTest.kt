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
 * A discriminated-union `switch` whose cases cover EVERY member narrows the discriminant to
 * `never` in the `default` clause (tsc's exhaustiveness narrowing) — so `default: assertNever(x)`
 * / `assertType<never>(x)` type-checks. The default-clause negative narrowing already dropped the
 * matched members but returned `null` (no narrowing) when ALL were dropped; it now returns
 * `never`. Self-compile FP family (`Debug.assertNever`/`assertType<never>` in exhaustive switches,
 * programDiagnostics/tsbuildPublic).
 *
 * FP-safe: only literal-/enum-kind members are dropped (a wide-kind member is kept), so an empty
 * result is a genuine exhaustiveness proof — a NON-exhaustive switch narrows to the surviving
 * members and the `never`-param call still errors.
 */
class ExhaustiveSwitchNeverNarrowingTest {

    private val assertNever = "declare function assertNever(x: never): never;\n"

    @Test
    fun `exhaustive string-literal-discriminant switch default narrows to never - no TS2345`() {
        diagnose(
            assertNever +
                """
                type Shape = { kind: "a", a: number } | { kind: "b", b: string };
                function f(s: Shape): number {
                    switch (s.kind) {
                        case "a": return s.a;
                        case "b": return s.b.length;
                        default: return assertNever(s);
                    }
                }
                """,
            directives = "",
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `exhaustive enum-member-discriminant switch default narrows to never - no TS2345`() {
        diagnose(
            assertNever +
                """
                enum K { A, B }
                type Shape = { kind: K.A, a: number } | { kind: K.B, b: string };
                function f(s: Shape): number {
                    switch (s.kind) {
                        case K.A: return s.a;
                        case K.B: return s.b.length;
                        default: return assertNever(s);
                    }
                }
                """,
            directives = "",
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `assert-to-union re-types a non-union arg then the exhaustive switch narrows to never - no TS2345`() {
        // `asType<Shape>(node)` (an `asserts value is T` with an explicit type arg) casts the
        // non-union `node` to the union `Shape`; the exhaustive switch then narrows it to `never`.
        // The arg-check narrows a non-union arg for a `never` param ONLY when the walk proves
        // `never` (the round-441 gate that relaxed the previous never-param exclusion).
        diagnose(
            assertNever +
                """
                declare function asType<T>(value: unknown): asserts value is T;
                type Shape = { kind: "a", a: number } | { kind: "b", b: string };
                function f(node: { kind: "a" | "b" }): void {
                    asType<Shape>(node);
                    switch (node.kind) {
                        case "a": break;
                        case "b": break;
                        default: assertNever(node);
                    }
                }
                """,
            directives = "",
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - assert-to-union then NON-exhaustive switch keeps the survivor - TS2345 fires`() {
        // The switch omits "c", so the default narrows `node` to `{ kind: "c"; ... }` (a partial
        // union), NOT `never` — the gate must NOT use a partial refinement, so the never-param
        // call still errors.
        diagnose(
            assertNever +
                """
                declare function asType<T>(value: unknown): asserts value is T;
                type Shape = { kind: "a", a: number } | { kind: "b", b: string } | { kind: "c", c: boolean };
                function f(node: { kind: "a" | "b" | "c" }): void {
                    asType<Shape>(node);
                    switch (node.kind) {
                        case "a": break;
                        case "b": break;
                        default: assertNever(node);
                    }
                }
                """,
            directives = "",
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - non-exhaustive switch keeps the uncovered member - TS2345 fires`() {
        // 'c' is not cased, so reaching the default the discriminant is `{ kind: "c"; ... }`,
        // NOT `never` — the never-param call must still error.
        diagnose(
            assertNever +
                """
                type Shape = { kind: "a", a: number } | { kind: "b", b: string } | { kind: "c", c: boolean };
                function f(s: Shape): number {
                    switch (s.kind) {
                        case "a": return s.a;
                        case "b": return s.b.length;
                        default: return assertNever(s);
                    }
                }
                """,
            directives = "",
        ) should {
            have(any { it.code == 2345 && it.message.contains("\"c\"") })
        }
    }

    /**
     * (REL.1)(c) step 5b, round 752: the NON-union subject leg of this gate (round 460's
     * `isReferencedFile(reason)` shape — a single interface whose discriminant annotation is
     * an enum-member union) reads that annotation TYPE-FIRST, through round 751's shared
     * [Checker.discriminantKeysOfMember].
     *
     * The witness is a PARENTHESIZED annotation, as everywhere in this key-space family:
     * [Checker.enumMemberKeysOfTypeNode] has no `ParenthesizedType` arm, so before the flip
     * this site read NO keys, declined to narrow, and `assertNever(r)` failed with
     * `Argument of type 'Reason' is not assignable to parameter of type 'never'`. Measured
     * on a build of `e717aba2` with only this site left unflipped — so the pin is attributed
     * to this reader alone, not to the exhaustive-switch reader flipped in the same round.
     *
     * Cross-FILE on purpose (per [EnumDiscriminantKeySpaceTest]): within one file every
     * resolution path tends to reach the same enum `Symbol` instance, so a single-file pin
     * cannot tell a canonical key space from an accidental one.
     */
    @Test
    fun `a parenthesized enum-member-union discriminant on a NON-union subject narrows to never`() {
        diagnose(
            """
            // @filename: /src/types.ts
            export enum RK { First, Second }
            export interface Reason { readonly kind: (RK.First | RK.Second); n: number }
            // @filename: /src/user.ts
            import { RK, Reason } from "./types";
            export function assertNever(x: never): never { throw new Error("bad"); }
            export function describe(r: Reason): string {
                switch (r.kind) {
                    case RK.First: return "first";
                    case RK.Second: return "second";
                    default: return assertNever(r);
                }
            }
            """,
        ) should {
            have(none { it.code == 2345 })
        }
    }

    /**
     * The no-false-suppression control for the pin above: leave one member uncovered and the
     * subject must NOT narrow to `never`.
     *
     * It passes on an unflipped build too, and that is stated rather than hidden — TS2345
     * fires on both sides for opposite reasons (unflipped: the gate cannot read the
     * parenthesized keys at all; flipped: it reads them and finds `RK.Third` uncovered). So
     * this pin cannot DISCRIMINATE the two builds; it guards the direction the discriminating
     * pin above could otherwise be satisfied by.
     */
    @Test
    fun `negative control - a parenthesized union with an uncovered member still fires TS2345`() {
        diagnose(
            """
            // @filename: /src/types.ts
            export enum RK { First, Second, Third }
            export interface Reason { readonly kind: (RK.First | RK.Second | RK.Third); n: number }
            // @filename: /src/user.ts
            import { RK, Reason } from "./types";
            export function assertNever(x: never): never { throw new Error("bad"); }
            export function describe(r: Reason): string {
                switch (r.kind) {
                    case RK.First: return "first";
                    case RK.Second: return "second";
                    default: return assertNever(r);
                }
            }
            """,
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
