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
 * Round 762: round 760's enum-member guard veto must also fire when the guard
 * target's enum-member `kind` arrives through a GENERIC INSTANTIATION.
 *
 * Round 760 restored [Checker.enumMemberDomainProvesNotSubtype] so that
 * `!isMod(node)` stops washing `node` to `never`, and pinned it with targets that
 * declare `kind: K.A` DIRECTLY. tsc's real `Modifier` does not: it is a union of
 * `ModifierToken<SyntaxKind.X>` aliases, and `kind` is declared once, on the
 * generic base `Token<TKind> { kind: TKind }`. The veto read the property with
 * `getTypeOfSymbol`, which answers with the DECLARING interface's member type —
 * the bare type parameter `TKind`, carrying no `EnumLiteral` flag — so it declined,
 * the wash to `never` survived on the real source, and
 * `services/completions.ts:2237` kept reporting
 * `Argument of type 'Node' is not assignable to parameter of type 'Identifier'`
 * while every plain-interface reduction stayed clean.
 *
 * [Checker.propertyTypeOnCarrier] substitutes through the carrier's type
 * arguments, which is what makes the generic constituent answer `K.Abstract`.
 *
 * The guards live in another file because a guard declared beside its use resolves
 * down a different path (round 760).
 */
class GenericGuardTargetEnumMemberTest {

    private val guards = """
        // @filename: g.ts
        export enum K { Abstract, Static, Ident }
        export interface Node { kind: K; }
        export interface Token<TKind extends K> extends Node { kind: TKind; }
        export interface ModifierToken<TKind extends K> extends Token<TKind> { mod?: boolean; }
        export type AbstractKeyword = ModifierToken<K.Abstract>;
        export type StaticKeyword = ModifierToken<K.Static>;
        export type Modifier = AbstractKeyword | StaticKeyword;
        export interface Ident extends Node { kind: K.Ident; name: string; }
        export interface Plain extends Node { kind: K.Static; plain: boolean; }
        export function isModifier(n: Node): n is Modifier { return n.kind !== K.Ident; }
        export function isPlain(n: Node): n is Plain { return n.kind === K.Static; }
        export function isIdent(n: Node): n is Ident { return n.kind === K.Ident; }
        export function take(n: Ident): number { return n.name.length; }
        // @filename: a.ts

    """.trimIndent()

    @Test
    fun `a guard after a GENERIC-instantiated union guard still narrows`() {
        diagnose(
            guards + """
            import { Node, isIdent, isModifier, take } from "./g.js";
            export function f(node: Node): number {
                if (isModifier(node)) { return 1; }
                if (isIdent(node)) { return take(node); }
                return 0;
            }
            """.trimIndent(),
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `a GENERIC-instantiated union guard leaves the declared type on its negative branch`() {
        // The sharp signal: `never` is assignable to `string`, so a washed
        // reference makes this assignment SILENT. Reporting the declared type is
        // what proves the collapse did not happen.
        diagnose(
            guards + """
            import { Node, isModifier } from "./g.js";
            export function f(node: Node): string {
                if (isModifier(node)) { return "m"; }
                const s: string = node;
                return s;
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'Node'") })
        }
    }

    @Test
    fun `a single GENERIC-instantiated guard target also declines the collapse`() {
        diagnose(
            guards + """
            import { Node, isModifier } from "./g.js";
            import { AbstractKeyword } from "./g.js";
            declare function isAbstract(n: Node): n is AbstractKeyword;
            export function f(node: Node): string {
                if (isAbstract(node)) { return "a"; }
                const s: string = node;
                return s;
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'Node'") })
        }
    }

    @Test
    fun `the guarded positive branch still narrows to the generic target`() {
        diagnose(
            guards + """
            import { Node, isModifier, isIdent } from "./g.js";
            export function f(node: Node): string {
                if (isModifier(node)) { return "m"; }
                if (isIdent(node)) { const s: string = node; return s; }
                return "";
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'Ident'") })
        }
    }

    @Test
    fun `negative control - a NON-generic member target was already handled`() {
        // isPlain declares `kind: K.Static` directly, so round 760's veto already
        // fired here. It must keep firing — this fails if the substitution change
        // broke the non-generic carrier path.
        diagnose(
            guards + """
            import { Node, isPlain } from "./g.js";
            export function f(node: Node): string {
                if (isPlain(node)) { return "p"; }
                const s: string = node;
                return s;
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'Node'") })
        }
    }

    @Test
    fun `negative control - a guard onto the SAME enum member still collapses`() {
        // `Ident` pins `kind: K.Ident` and the source pins the same member, so the
        // veto must NOT fire: this is member-vs-member, which the relation decides
        // itself. The negative branch of a guard on an already-Ident reference is
        // genuinely `never`, so the probe stays SILENT.
        diagnose(
            guards + """
            import { Ident, isIdent } from "./g.js";
            export function f(node: Ident): string {
                if (isIdent(node)) { return "i"; }
                const s: string = node;
                return s;
            }
            """.trimIndent(),
        ) should { have(none { it.code == 2322 }) }
    }
}
