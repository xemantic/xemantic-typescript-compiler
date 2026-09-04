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
 * (REL.4) round 768: the `Debug.assertNever(x)` family reaches `never`.
 *
 * Rounds 764-766 taught the enum directions to SUBTRACT, but the subtraction could never
 * finish: three independent steps stopped one short of `never`, which is the only answer
 * that makes an exhaustiveness assertion legal.
 *
 *  * **the LAST member** — [narrowUnionByLiteral]'s non-union `!keep` branch subtracts via
 *    `enumMinusMembers`, which only accepts an enum's OWN type; once a chain of `===`
 *    guards or ternary arms has peeled the union down to a SINGLE member type there was
 *    nothing left to subtract FROM, so the reference kept that member. This is why a
 *    partial chain narrowed correctly and a COMPLETE one did not — the failing step is
 *    the last, not the first.
 *  * **an enum-member case in a UNION subject** — case expressions split into literal
 *    NODES and enum keys, and the `Type.Union` `default:` arm filtered only the former,
 *    so a subject typed `SK.NewKeyword | SK.ImportKeyword` (tsc's
 *    `MetaProperty["keywordToken"]`) kept every constituent. The bare-enum arm has
 *    subtracted since round 765; this is the same subtraction for the already-decomposed
 *    shape, and it is what a PROPERTY-ACCESS subject needs too (its type is the union).
 *  * **the discarded `never`** — the round-462 argument gate excluded `n === neverType`
 *    outright, which is what round 765 recorded as "the narrowed `never` is discarded
 *    somewhere between the flow walk and the argument check". Without it the two fixes
 *    above are worse than useless at a non-`never` target: the chain's last step answers
 *    the WHOLE enum where it used to answer the last member.
 *
 * Probe discipline: a `never` target is SILENT for a correct narrow, so every silence
 * pin has a `string`-target twin that NAMES the type it narrowed to — a target that only
 * watched the silence could not tell "narrowed to never" from "the call was never
 * argument-checked at all".
 */
class EnumExhaustionToNeverTest {

    private val prelude = """
        enum K { A, B, C, D }
        enum SK { New, Import, Extends }
        type Pair = SK.New | SK.Import;
        type Trio = SK.New | SK.Import | SK.Extends;
        interface Meta { kw: SK.New | SK.Import }
        declare function assertNever(x: never): never;
        declare function probe(x: string): void;

    """.trimIndent()

    private fun reachesNever(body: String) {
        diagnose(prelude + body) should { have(none { it.code == 2345 }) }
    }

    private fun narrowedTo(body: String, expected: String) {
        diagnose(prelude + body) should {
            have(any { it.code == 2345 && it.message.contains("Argument of type '$expected'") })
        }
    }

    // ---- the LAST member: a complete chain must reach `never` ----

    @Test
    fun `equality guards over every member of a bare enum reach never`() {
        reachesNever(
            """
            export function f(k: K) {
              if (k === K.A) return;
              if (k === K.B) return;
              if (k === K.C) return;
              if (k === K.D) return;
              assertNever(k);
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a ternary chain over every member of a bare enum reaches never`() {
        reachesNever(
            """
            export function f(k: K): string {
              return k === K.A ? "a" : k === K.B ? "b" : k === K.C ? "c" : k === K.D ? "d" : assertNever(k);
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `equality guards over every member of a member union reach never`() {
        reachesNever(
            """
            export function f(k: Pair) {
              if (k === SK.New) return;
              if (k === SK.Import) return;
              assertNever(k);
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a partial guard chain still names the residual member - the step before the last`() {
        // The chain's earlier steps were already correct; this pins that closing the last
        // one did not disturb them.
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

    // ---- an enum-member case in a UNION subject ----

    @Test
    fun `an exhaustive switch default on a member union subject reaches never`() {
        reachesNever(
            """
            export function f(k: Pair) {
              switch (k) { case SK.New: break; case SK.Import: break; default: assertNever(k); }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a partial switch default on a member union subject names the uncovered member`() {
        narrowedTo(
            """
            export function f(k: Trio) {
              switch (k) { case SK.New: break; case SK.Import: break; default: assertNever(k); }
            }
            """.trimIndent(),
            "SK.Extends",
        )
    }

    @Test
    fun `a member union switch default subtracting a MIDDLE member keeps declaration order`() {
        // Round 764's display trap: removing the LAST member cannot test order, and a
        // survivor set equal to a declared alias would read the alias name instead.
        narrowedTo(
            "export function f(k: Trio) { switch (k) { case SK.Import: break; default: assertNever(k); } }",
            "SK.New | SK.Extends",
        )
    }

    @Test
    fun `an exhaustive switch default on a property access subject reaches never`() {
        // tsc's `switch (node.keywordToken) { … default: Debug.assertNever(node.keywordToken) }`.
        reachesNever(
            """
            export function f(n: Meta) {
              switch (n.kw) { case SK.New: break; case SK.Import: break; default: assertNever(n.kw); }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a partial switch default on a property access subject names the uncovered member`() {
        narrowedTo(
            "export function f(n: Meta) { switch (n.kw) { case SK.New: break; default: probe(n.kw); } }",
            "SK.Import",
        )
    }

    @Test
    fun `sequential early return guards on a property access subject reach never`() {
        // tsc checker.ts's `checkMetaProperty`: two `if (node.keywordToken === X) { … return }`
        // guards and then `return Debug.assertNever(node.keywordToken)`.
        reachesNever(
            """
            export function f(n: Meta): string {
              if (n.kw === SK.New) { return "n"; }
              if (n.kw === SK.Import) { return "i"; }
              return assertNever(n.kw);
            }
            """.trimIndent(),
        )
    }

    // ---- the discarded `never`: a non-`never` target must see it too ----

    @Test
    fun `an exhaustive bare enum switch default delivers never to a string parameter`() {
        reachesNever(
            """
            export function f(k: K) {
              switch (k) { case K.A: break; case K.B: break; case K.C: break; case K.D: break; default: probe(k); }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `equality guards over every member deliver never to a string parameter`() {
        reachesNever(
            """
            export function f(k: K) {
              if (k === K.A) return;
              if (k === K.B) return;
              if (k === K.C) return;
              if (k === K.D) return;
              probe(k);
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a string target names the same narrowed member - the harness discriminates`() {
        // The silence pins above are only evidence while a non-silent twin proves the
        // argument IS being checked at that position.
        narrowedTo(
            """
            export function f(k: K) {
              if (k === K.A) return;
              if (k === K.B) return;
              if (k === K.C) return;
              probe(k);
            }
            """.trimIndent(),
            "K.D",
        )
    }

    // ---- controls ----

    @Test
    fun `negative control - a mixed enum and literal case list keeps the whole enum`() {
        // The round-746 owner rule: a plain literal is not a member of anything, so the
        // subtraction must refuse the whole switch rather than subtract what it can.
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
    fun `negative control - a foreign enums like named member does not subtract`() {
        narrowedTo(
            """
            enum J { A, B }
            export function f(k: Pair, j: J) {
              if (j === J.A) return;
              switch (k) { case SK.New: break; default: assertNever(k); }
              assertNever(k as never);
            }
            """.trimIndent(),
            "SK.Import",
        )
    }

    @Test
    fun `negative control - an interface narrowed through a ternary keeps its declared type`() {
        // The `never`-acceptance is gated to an enum subject; the round-462 exclusion must
        // still hold for every other declared type. An `if` BLOCK would type its body from
        // currentLocalTypes and never reach the arm, so this uses a ternary.
        narrowedTo(
            """
            interface Animal { legs: number }
            interface Dog extends Animal { bark(): void }
            declare function isDog(a: Animal): a is Dog;
            export function f(a: Animal): string {
              return isDog(a) ? "d" : (probe(a), "x");
            }
            """.trimIndent(),
            "Animal",
        )
    }

    @Test
    fun `negative control - a literal union exhaustive switch default still reaches never`() {
        // Round 425's arm answered `never` here long before this round; the enum-member
        // filter added beside it must not disturb a purely literal case list.
        diagnose(
            prelude +
                """
                type L = "a" | "b" | "c";
                declare function probeN(x: number): void;
                export function f(s: L) {
                  switch (s) { case "a": break; case "b": break; case "c": break; default: probeN(s); }
                }
                """.trimIndent(),
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `negative control - a literal union partial switch default still names the survivors`() {
        // (PARITY.1)(b-residue): the probe target is `never`, not the class's shared
        // PRIMITIVE `probe`. The source display now takes tsc's `reportRelationError`
        // generalization, which collapses a LITERAL UNION to its base — so a primitive
        // target renders `string`/`number` here and the pin would go BLIND rather than
        // red. A `never` target is the one tsc suppresses the generalization for, so the
        // narrowed union is named in full; measured identical in tsgo 7.0.2 and pristine
        // `typescript@6.0.3`.
        diagnose(
            prelude +
                """
                type L = "a" | "b" | "c";
                declare function probeX(x: never): void;
                export function f(s: L) { switch (s) { case "b": break; default: probeX(s); } }
                """.trimIndent(),
        ) should {
            have(any { it.code == 2345 && it.message.contains("""Argument of type '"a" | "c"'""") })
        }
    }

    @Test
    fun `negative control - an unguarded member still fires at a never parameter`() {
        // A chain that does NOT cover the domain must keep erroring; a fix that reached
        // `never` unconditionally would pass every silence pin above and break this one.
        narrowedTo(
            """
            export function f(k: K) {
              if (k === K.A) return;
              assertNever(k);
            }
            """.trimIndent(),
            "K.B | K.C | K.D",
        )
    }
}
