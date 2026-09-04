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
 * (REL.4)(a) round 769: an enum DECLARED inside a `namespace` narrows.
 *
 * `resolveEnumSymbolForDiscriminant` — the single name-based entry into the
 * `"symId#member"` discriminant key space — resolved the enum name through
 * `currentFileLocals` / `lookupPerFileForNode`, i.e. FILE-level lookup only. A
 * namespace member is in neither table, so EVERY name-based enum reader answered null
 * and every narrowing direction went blind at once: exhaustion, the switch `default:`
 * subtraction, `===` guards, the positive direction. Isolated over 11 variants round
 * 768: it is the enum's DECLARATION site that decides, not the code's — a top-level
 * enum switched on inside a namespace has always worked.
 *
 * The blindness was self-concealing. `narrowBySwitchClause`'s default arm bails on the
 * FIRST case it cannot key (`enumMemberKeyOfExpr(...) ?: return null`), so the resolver
 * was only ever asked twice on the compiler profile — once per switch. With the
 * enclosing-namespace fallback the same profile asks it 130 times: closing the first
 * case unblocks all 27.
 *
 * The fallback is deliberately STRICTLY ADDITIVE — it runs only where the file-level
 * lookup already returned null. Measured over the 8 tsc-source profiles before landing:
 * of ~15k/~23k resolver calls, **zero** names resolve BOTH ways, so no existing key
 * moved; and for every enum the fallback newly reaches, its members' `parent` symbol
 * canonicalizes to the same id the fallback returns — which is what keeps this key
 * space unsplit (the round-425 catastrophe) since `getDeclaredTypeOfEnumMember` mints
 * its interning key from that parent.
 */
class NamespaceScopedEnumNarrowingTest {

    private val prelude = """
        declare function assertNever(x: never): never;
        declare function probe(x: string): void;
        declare function probeX(x: never): void;

    """.trimIndent()

    private fun reachesNever(body: String) {
        diagnose(prelude + body) should { have(none { it.code == 2345 }) }
    }

    private fun narrowedTo(body: String, expected: String) {
        diagnose(prelude + body) should {
            have(any { it.code == 2345 && it.message.contains("Argument of type '$expected'") })
        }
    }

    // ---- the switch-default direction: tsc's parser.ts ParsingContext ----

    @Test
    fun `an exhaustive switch default on a namespace scoped enum reaches never`() {
        reachesNever(
            """
            export namespace P {
              enum K { A, B, C }
              export function f(k: K) {
                switch (k) { case K.A: break; case K.B: break; case K.C: break; default: assertNever(k); }
              }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `an exhaustive switch default on a namespace scoped const enum reaches never`() {
        // tsc parser.ts declares `const enum ParsingContext` inside `namespace Parser`.
        reachesNever(
            """
            export namespace P {
              const enum K { A, B, C }
              export function f(k: K) {
                switch (k) { case K.A: break; case K.B: break; case K.C: break; default: assertNever(k); }
              }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a partial switch default on a namespace scoped enum names the uncovered MIDDLE member`() {
        // The order pin: removing the LAST member cannot test declaration order.
        narrowedTo(
            """
            export namespace P {
              enum K { A, B, C }
              export function f(k: K) {
                switch (k) { case K.B: break; default: assertNever(k); }
              }
            }
            """.trimIndent(),
            "K.A | K.C",
        )
    }

    @Test
    fun `an exhaustive switch default on a namespace scoped enum delivers never to a string parameter`() {
        // The discriminating twin: a `never` target is silent for a correct narrow AND
        // for a call that was never argument-checked at all.
        reachesNever(
            """
            export namespace P {
              enum K { A, B, C }
              export function f(k: K) {
                switch (k) { case K.A: break; case K.B: break; case K.C: break; default: probe(k); }
              }
            }
            """.trimIndent(),
        )
    }

    // ---- a PROPERTY-ACCESS subject: tsc's findAllReferences.ts SpecialSearchKind ----

    @Test
    fun `an exhaustive switch default on a namespace scoped enum property reaches never`() {
        reachesNever(
            """
            export namespace P {
              const enum K { A, B, C }
              interface State { kind: K }
              export function f(s: State) {
                switch (s.kind) { case K.A: break; case K.B: break; case K.C: break; default: assertNever(s.kind); }
              }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a partial switch default on a namespace scoped enum property names the survivors`() {
        // (PARITY.2): `probeX`, a `never` parameter — the shared `probe` is a `string`
        // one and tsc's `reportRelationError` generalizes an enum-member source to its
        // parent enum there, so this pin would read `K` for every narrow and go BLIND.
        // Expectation measured on tsgo 7.0.2 AND pristine `typescript@6.0.3`.
        narrowedTo(
            """
            export namespace P {
              const enum K { A, B, C }
              interface State { kind: K }
              export function f(s: State) {
                switch (s.kind) { case K.B: break; default: probeX(s.kind); }
              }
            }
            """.trimIndent(),
            "K.A | K.C",
        )
    }

    // ---- the subtractive equality direction ----

    @Test
    fun `equality guards over every member of a namespace scoped enum reach never`() {
        reachesNever(
            """
            export namespace P {
              enum K { A, B, C }
              export function f(k: K) {
                if (k === K.A) return;
                if (k === K.B) return;
                if (k === K.C) return;
                assertNever(k);
              }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a partial guard chain on a namespace scoped enum names the residual members`() {
        // (PARITY.2): `probeX`, a `never` parameter — the shared `probe` is a `string`
        // one and tsc's `reportRelationError` generalizes an enum-member source to its
        // parent enum there, so this pin would read `K` for every narrow and go BLIND.
        // Expectation measured on tsgo 7.0.2 AND pristine `typescript@6.0.3`.
        narrowedTo(
            """
            export namespace P {
              enum K { A, B, C }
              export function f(k: K) {
                if (k === K.B) return;
                probeX(k);
              }
            }
            """.trimIndent(),
            "K.A | K.C",
        )
    }

    // ---- scope shape: the walk climbs the ENCLOSING chain, and only that ----

    @Test
    fun `an enum of an OUTER namespace narrows inside a NESTED one`() {
        reachesNever(
            """
            export namespace Outer {
              enum K { A, B }
              export namespace Inner {
                export function f(k: K) {
                  switch (k) { case K.A: break; case K.B: break; default: assertNever(k); }
                }
              }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a same named enum in a SIBLING namespace is not what resolves`() {
        // If the fallback searched namespaces at large rather than the enclosing chain,
        // `A`'s two-member K could answer here and the three-case switch would not
        // exhaust — so this silence is a resolution pin, not a narrowing one.
        reachesNever(
            """
            export namespace A { export enum K { X, Y } }
            export namespace B {
              enum K { X, Y, Z }
              export function f(k: K) {
                switch (k) { case K.X: break; case K.Y: break; case K.Z: break; default: assertNever(k); }
              }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a FILE level enum still narrows inside a namespace`() {
        // The declaration site is what decides; this shape worked before the fallback
        // existed and the enclosing-namespace walk must not disturb it.
        reachesNever(
            """
            enum K { A, B }
            export namespace P {
              export function f(k: K) {
                switch (k) { case K.A: break; case K.B: break; default: assertNever(k); }
              }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a namespace scoped enum narrows inside a nested function and an arrow`() {
        reachesNever(
            """
            export namespace P {
              enum K { A, B }
              export function f(k: K) {
                const g = () => {
                  if (k === K.A) return;
                  if (k === K.B) return;
                  assertNever(k);
                };
                g();
              }
            }
            """.trimIndent(),
        )
    }

    // ---- controls ----

    @Test
    fun `negative control - a non exhaustive namespace switch still fires`() {
        // A fallback that manufactured `never` unconditionally would pass every silence
        // pin above and break this one.
        narrowedTo(
            """
            export namespace P {
              enum K { A, B, C }
              export function f(k: K) {
                switch (k) { case K.A: break; default: assertNever(k); }
              }
            }
            """.trimIndent(),
            "K.B | K.C",
        )
    }

    @Test
    fun `negative control - a namespace scoped INTERFACE named like an enum does not narrow`() {
        // The fallback returns null for a non-enum namespace member rather than keying
        // it; `S.A` is a property read, and the subject keeps its declared type.
        narrowedTo(
            """
            export namespace P {
              enum K { A, B, C }
              declare const S: { A: K; B: K };
              export function f(k: K) {
                switch (k) { case S.A: break; case S.B: break; default: assertNever(k); }
              }
            }
            """.trimIndent(),
            "K",
        )
    }

    @Test
    fun `negative control - a foreign namespace enums like named member does not subtract`() {
        // (PARITY.2): `probeX`, a `never` parameter — the shared `probe` is a `string`
        // one and tsc's `reportRelationError` generalizes an enum-member source to its
        // parent enum there, so this pin would read `K` for every narrow and go BLIND.
        // Expectation measured on tsgo 7.0.2 AND pristine `typescript@6.0.3`.
        narrowedTo(
            """
            export namespace A { export enum J { X, Y } }
            export namespace B {
              enum K { X, Y, Z }
              export function f(k: K, j: A.J) {
                if (j === A.J.X) return;
                if (k === K.X) return;
                probeX(k);
              }
            }
            """.trimIndent(),
            "K.Y | K.Z",
        )
    }
}
