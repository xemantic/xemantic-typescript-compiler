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
 * (REL.1)(c) step 5b, round 751: `filterUnionByEnumDiscriminant` reads the discriminant's
 * RESOLVED TYPE, with the annotation walk kept only as a fallback.
 *
 * The AST reader ([Checker.enumMemberKeysOfTypeNode]) matches a fixed list of TypeNode
 * shapes, so a discriminant annotation it has no arm for silently yields NO keys — and a
 * member with no keys is conservatively KEPT, i.e. narrowing quietly stops removing it. The
 * type reader has no such list: whatever the annotation resolves to, an enum-member type
 * carries `TypeFlags.EnumLiteral` and its symbol's `parent` is the enum.
 *
 * A PARENTHESIZED annotation is the cheapest witness of that difference — legal TypeScript,
 * with no `ParenthesizedType` arm in the AST reader. It is the shape these pins use because
 * the failure it exposes is the one this whole key-space family exists to prevent: narrowing
 * that stops matching, which is silent until a case body FPs TS2339.
 *
 * The cross-FILE shape is deliberate throughout, per [EnumDiscriminantKeySpaceTest]: within
 * one file every path tends to find the same enum `Symbol` instance, so a single-file pin
 * cannot tell a canonical key space from an accidental one.
 *
 * MEASURED before the flip, over the whole compiler profile: 198 distinct
 * (property, key-set) sightings, **198 agreeing, 0 mismatched**, 0 where the type path lost
 * a key the AST path had. With the AST fallback ABLATED the profile stayed byte-identical at
 * 46 errors, which is what shows the type path is doing the work rather than riding a
 * fallback that still answers everything.
 */
class EnumDiscriminantTypeReadTest {

    private val parenthesized = """
        // @filename: /src/types.ts
        export enum Kind { Alpha, Beta }
        export interface A { readonly kind: (Kind.Alpha); a: number }
        export interface B { readonly kind: (Kind.Beta); b: string }
        export type AB = A | B
    """.trimIndent()

    @Test
    fun `a parenthesized enum member discriminant narrows across a file boundary`() {
        val diagnostics = diagnose(
            """
            $parenthesized
            // @filename: /src/user.ts
            import { Kind, AB } from "./types";
            export function read(x: AB): number {
                switch (x.kind) {
                    case Kind.Alpha: return x.a;
                    case Kind.Beta: return x.b.length;
                    default: return 0;
                }
            }
            """,
        )
        assert(diagnostics.none { it.code == 2339 })
    }

    /**
     * The REMOVAL leg (`keep = false`), and it is asserted through the DEFAULT branch on
     * purpose. The obvious shape — `case Kind.Alpha: return x.b` asserting that a TS2339
     * appears — pins nothing: it fires on an unflipped build too, because a union that was
     * never narrowed also has no `b`. Same code, opposite causes. Reading the default branch
     * inverts that: only a build that actually SUBTRACTED `A` lets `x.b` resolve, so the
     * assertion is `none`, and an unflipped build fails it with
     * `Property 'b' does not exist on type 'AB'`.
     */
    @Test
    fun `a parenthesized discriminant also REMOVES the other constituent`() {
        val diagnostics = diagnose(
            """
            $parenthesized
            // @filename: /src/user.ts
            import { Kind, AB } from "./types";
            export function neg(x: AB): string {
                switch (x.kind) {
                    case Kind.Alpha: return "a";
                    default: return x.b;
                }
            }
            """,
        )
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `nested parentheses do not defeat the discriminant read`() {
        val diagnostics = diagnose(
            """
            // @filename: /src/types.ts
            export enum Kind { Alpha, Beta }
            export interface A { readonly kind: ((Kind.Alpha)); a: number }
            export interface B { readonly kind: ((Kind.Beta)); b: string }
            export type AB = A | B
            // @filename: /src/user.ts
            import { Kind, AB } from "./types";
            export function neg(x: AB): string {
                switch (x.kind) {
                    case Kind.Alpha: return "a";
                    default: return x.b;
                }
            }
            """,
        )
        assert(diagnostics.none { it.code == 2339 })
    }

    /**
     * The type path is restricted to a property that HAS an annotation, so that the flip is a
     * re-derivation of the AST reader's answer rather than a widening of what narrowing may
     * remove. That restriction is currently UNOBSERVABLE, and this pin records WHY rather than
     * pretending to guard it: an unannotated `readonly kind = Kind.Alpha` does not reach the
     * reader as an enum-MEMBER type at all — it widens to the enum `Kind` first (verified by
     * how it prints: `Type 'Kind' is not assignable to type 'string'`, against `'Kind.Alpha'`
     * for the annotated sibling), and an enum's own type carries `Enum`, not `EnumLiteral`.
     *
     * So the property is not a discriminant here, the union is not narrowed, and `x.a` is a
     * genuine TS2339. If inference ever starts PRESERVING the member type on an unannotated
     * property, this pin flips — and that is the intended signal: dropping the restriction
     * becomes a real widening at that moment, and must be decided deliberately rather than
     * inherited silently.
     */
    @Test
    fun `negative control - an inferred discriminant widens to the enum and does not narrow`() {
        val diagnostics = diagnose(
            """
            // @filename: /src/types.ts
            export enum Kind { Alpha, Beta }
            export class A { readonly kind = Kind.Alpha; a: number = 1 }
            export class B { readonly kind = Kind.Beta; b: string = "x" }
            export type AB = A | B
            // @filename: /src/user.ts
            import { Kind, AB } from "./types";
            export function read(x: AB): number {
                switch (x.kind) {
                    case Kind.Alpha: return x.a;
                    default: return 0;
                }
            }
            """,
        )
        assert(diagnostics.any { it.code == 2339 })
    }

    /**
     * ROUND 752 FLIPPED THIS. It shipped in round 751 as a negative control asserting the
     * OPPOSITE — `any { it.code == 2366 }` — marking the exhaustive-switch gate as the next
     * reader to convert, in the round-748 convention of pinning a known divergence on purpose.
     * That gate is now type-first too, so the same source is silent on both counts: narrowing
     * removes the other constituent AND the switch is accepted as exhaustive.
     *
     * Kept here (rather than moved to [ExhaustiveSwitchTypeReadTest], which owns the gate's
     * own pins) because what it records is that ONE parenthesized annotation now answers BOTH
     * questions — a reader-by-reader flip is finished at a site only when every consumer of
     * that site's key space agrees.
     */
    @Test
    fun `the exhaustive switch gate reads the same parenthesized discriminant`() {
        val diagnostics = diagnose(
            """
            $parenthesized
            // @filename: /src/user.ts
            import { Kind, AB } from "./types";
            export function read(x: AB): number {
                switch (x.kind) {
                    case Kind.Alpha: return x.a;
                    case Kind.Beta: return x.b.length;
                }
            }
            """,
        )
        assert(diagnostics.none { it.code == 2366 })
        assert(diagnostics.none { it.code == 2339 })
    }
}
