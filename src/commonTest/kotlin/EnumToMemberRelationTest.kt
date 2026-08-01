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
 * (REL.2) round 784 — the enum → MEMBER relation direction, decided the way tsc does.
 *
 * Both an enum's own type and an enum MEMBER's type are member-LESS `Type.Object`s, so
 * without a dedicated rule the structural engine relates them VACUOUSLY and `K` is
 * assignable to `K.A`. tsc models a literal enum AS the union of its members, so `K`
 * relates to a target built out of K's members exactly when those members COVER K.
 *
 * The rule is contained to a target that is ENTIRELY members of THIS enum (the round-746
 * owner rule) — anything else falls through to the pre-existing answer — and to an enum
 * whose value domain decomposes completely.
 *
 * The direction was written and reverted three times (rounds 760/762/765/783) because the
 * vacuous `true` was MASKING flow-narrowing gaps: the raw price against the eight-profile
 * dashboard was seven distinct false positives. Every one of them is closed; the flip
 * itself is set-for-set free on all eight profiles.
 *
 * Round 760's site-local stand-in `enumMemberDomainProvesNotSubtype` is DELETED with this
 * — the relation now reaches its verdict itself, which is what
 * `EnumMemberGuardNegativeBranchTest` and `GenericGuardTargetEnumMemberTest` re-pin.
 */
class EnumToMemberRelationTest {

    private val prelude = """
        enum K { A, B, C }
        enum One { Only }
        enum Other { A, B, C }
        declare function takeA(x: K.A): void;
        declare function takeAB(x: K.A | K.B): void;
        declare function takeAll(x: K.A | K.B | K.C): void;
        declare function takeOnly(x: One.Only): void;
        declare function takeOtherA(x: Other.A): void;
        declare function takeK(x: K): void;
    """.trimIndent() + "\n"

    /** DISCRIMINATES — a whole enum is NOT assignable to one of its own members. */
    @Test
    fun `a whole enum is not assignable to one of its members`() {
        val diagnostics = diagnose(
            prelude + "export function f(k: K) { takeA(k); }"
        )
        assert(diagnostics.any { it.code == 2345 && it.message == "Argument of type 'K' is not assignable to parameter of type 'K.A'." })
    }

    /** DISCRIMINATES — nor to a PROPER subset of its members. */
    @Test
    fun `a whole enum is not assignable to a proper subset of its members`() {
        val diagnostics = diagnose(
            prelude + "export function f(k: K) { takeAB(k); }"
        )
        assert(diagnostics.any { it.code == 2345 && it.message == "Argument of type 'K' is not assignable to parameter of type 'K.A | K.B'." })
    }

    /**
     * The COVERING case must stay legal — the rule is a coverage test, not a blanket
     * rejection, and a target listing every member is the enum itself.
     */
    @Test
    fun `a whole enum is assignable to a target covering all of its members`() {
        val diagnostics = diagnose(
            prelude + "export function f(k: K) { takeAll(k); }"
        )
        assert(diagnostics.none { it.code == 2345 })
    }

    /** A ONE-MEMBER enum is covered by that member, so it must stay legal. */
    @Test
    fun `a one-member enum is assignable to its only member`() {
        val diagnostics = diagnose(
            prelude + "export function f(o: One) { takeOnly(o); }"
        )
        assert(diagnostics.none { it.code == 2345 })
    }

    /** DISCRIMINATES — the rule is not specific to one enum declaration. */
    @Test
    fun `a second enum is not assignable to one of its own members either`() {
        val diagnostics = diagnose(
            prelude + "export function f(t: Other) { takeOtherA(t); }"
        )
        assert(diagnostics.any { it.code == 2345 && it.message == "Argument of type 'Other' is not assignable to parameter of type 'Other.A'." })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — the MEMBER → enum direction is (REL.1)(c)'s and
     * must be untouched by this rule, which only ever answers for an enum SOURCE.
     */
    @Test
    fun `a member is still assignable to its own enum`() {
        val diagnostics = diagnose(
            prelude + "export function f() { takeK(K.A); }"
        )
        assert(diagnostics.none { it.code == 2345 })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — member-vs-member is (REL.1)(b)'s verdict, already
     * correct before this round; it is pinned so a coverage rule cannot swallow it.
     */
    @Test
    fun `a sibling member is still rejected`() {
        val diagnostics = diagnose(
            prelude + "export function f() { takeA(K.B); }"
        )
        assert(diagnostics.any { it.code == 2345 && it.message == "Argument of type 'K.B' is not assignable to parameter of type 'K.A'." })
    }

    /**
     * DISCRIMINATES — the tsc `services/completions.ts:2234` shape, and the single line
     * this flip cost before cause (G) was closed: `isModifierLike` returns `node.kind`
     * from a `node: Node` narrowed by `isModifier(node): node is Modifier`, where
     * `Modifier` is a union of `ModifierToken<K.X>` instantiations of a generic
     * `Token<TKind> { kind: TKind }`.
     *
     * The guards live in another file deliberately: a guard declared beside its use
     * resolves down a different path (round 760), and this shape is SILENT in a
     * single-file reduction — which is how it went unmodelled for a whole round.
     */
    @Test
    fun `a returned modifier kind survives the flip`() {
        val diagnostics = diagnose(
            """
            // @filename: g.ts
            export enum K { Abstract, Static, Async, Ident }
            export interface Node2 { kind: K; }
            export interface Token<TKind extends K> extends Node2 { kind: TKind; }
            export type ModifierToken<TKind extends K> = Token<TKind>;
            export type ModifierKind = K.Abstract | K.Static | K.Async;
            export type Modifier = ModifierToken<K.Abstract> | ModifierToken<K.Static> | ModifierToken<K.Async>;
            export function isModifier(n: Node2): n is Modifier { return n.kind !== K.Ident; }
            // @filename: a.ts
            import { K, Node2, ModifierKind, isModifier } from "./g.js";
            export function modifierKindOf(node: Node2): ModifierKind | undefined {
                if (isModifier(node)) {
                    return node.kind;
                }
                return undefined;
            }
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * NEGATIVE CONTROL for the pin above — without the guard the same return must fire,
     * so the silence there is the narrowing and not an inert shape.
     */
    @Test
    fun `negative control - the same return without the guard still fires`() {
        val diagnostics = diagnose(
            """
            // @filename: g.ts
            export enum K { Abstract, Static, Async, Ident }
            export interface Node2 { kind: K; }
            export type ModifierKind = K.Abstract | K.Static | K.Async;
            // @filename: a.ts
            import { K, Node2, ModifierKind } from "./g.js";
            export function modifierKindOf(node: Node2, d: ModifierKind): ModifierKind {
                return node.kind;
            }
            """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 && it.message == "Type 'K' is not assignable to type 'ModifierKind'." })
    }
}
