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
 * Round 760: a type guard whose target pins a property to a single enum MEMBER
 * must not wash the guarded reference to `never` on its NEGATIVE branch.
 *
 * `Node { kind: K }` is NOT a subtype of `Mod { kind: K.A }` — an enum is the
 * union of its members' literal types, and a union is not a subtype of one
 * member. The structural engine cannot see that on its own:
 * [Checker.getDeclaredTypeOfEnumMember] mints member types carrying NO members,
 * so `Node` and `Mod` relate VACUOUSLY. Before this round the negative branch of
 * `if (isMod(node)) return …;` therefore collapsed `node` to `never`, and the
 * NEXT guard on the same reference had nothing left to narrow — which is how
 * tsc's own `services/completions.ts:2237` reported
 * `Argument of type 'Node' is not assignable to parameter of type 'Identifier'`
 * on `identifierToKeywordKind(node)` inside `if (isIdentifier(node))`.
 *
 * Round 472 knew this and vetoed the collapse with `kindDomainProvesNotSubtype`;
 * (REL.1)(c) round 753 deleted the veto on the premise that (REL.1)(a)/(b) had
 * taught the relation to make the distinction itself. It had — for
 * member-vs-member. The enum-vs-MEMBER direction is still decided vacuously, so
 * [Checker.enumMemberDomainProvesNotSubtype] restores exactly that one verdict at
 * exactly the one call site that needs it.
 *
 * The shape needs BOTH guards to live in another file (a guard declared beside
 * its use resolves down a different path) and the two guards to have DIFFERENT
 * targets — a second guard onto the same target never asks the vacuous question.
 */
class EnumMemberGuardNegativeBranchTest {

    private val guards = """
        // @filename: g.ts
        export enum K { A, B, I }
        export interface Node { kind: K; }
        export interface Ident extends Node { kind: K.I; name: string; }
        export interface Mod extends Node { kind: K.A; }
        export function isMod(n: Node): n is Mod { return n.kind === K.A; }
        export function isIdent(n: Node): n is Ident { return n.kind === K.I; }
        export function isIdentToo(n: Node): n is Ident { return n.kind === K.I; }
        export function take(n: Ident): number { return n.name.length; }
        // @filename: a.ts

    """.trimIndent()

    @Test
    fun `a second guard still narrows after a first guard early-returns`() {
        diagnose(
            guards + """
            import { Node, isIdent, isMod, take } from "./g.js";
            export function f(node: Node): number {
                if (isMod(node)) { return 1; }
                if (isIdent(node)) { return take(node); }
                return 0;
            }
            """.trimIndent(),
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `the negative branch keeps the declared type instead of never`() {
        // The sharp signal: `never` is assignable to `string`, so a washed
        // reference makes this assignment SILENT. It must report the declared
        // type, which is what proves the collapse did not happen.
        diagnose(
            guards + """
            import { Node, isMod } from "./g.js";
            export function f(node: Node): string {
                if (isMod(node)) { return "m"; }
                const s: string = node;
                return s;
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'Node'") })
        }
    }

    @Test
    fun `the guarded positive branch still narrows to the guard target`() {
        diagnose(
            guards + """
            import { Node, isMod, isIdent } from "./g.js";
            export function f(node: Node): string {
                if (isMod(node)) { return "m"; }
                if (isIdent(node)) { const s: string = node; return s; }
                return "";
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2322 && it.message.contains("Type 'Ident'") })
        }
    }

    @Test
    fun `negative control - two guards onto the same target were never affected`() {
        diagnose(
            guards + """
            import { Node, isIdent, isIdentToo, take } from "./g.js";
            export function f(node: Node): number {
                if (isIdentToo(node)) { return 1; }
                if (isIdent(node)) { return take(node); }
                return 0;
            }
            """.trimIndent(),
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `negative control - a genuinely wrong argument is still rejected`() {
        // The veto declines an over-narrowing; it must not suppress a real
        // mismatch. `Mod` has no `name`, so `take(node)` inside the POSITIVE
        // branch of `isMod` stays an error.
        diagnose(
            guards + """
            import { Node, isMod, take } from "./g.js";
            export function f(node: Node): number {
                if (isMod(node)) { return take(node); }
                return 0;
            }
            """.trimIndent(),
        ) should { have(any { it.code == 2345 }) }
    }

    @Test
    fun `negative control - a non-enum discriminant never took this path`() {
        diagnose(
            """
            // @filename: g.ts
            export interface Node { kind: number; }
            export interface Ident extends Node { name: string; }
            export interface Mod extends Node { mod: string; }
            export function isMod(n: Node): n is Mod { return "mod" in n; }
            export function isIdent(n: Node): n is Ident { return "name" in n; }
            export function take(n: Ident): number { return n.name.length; }
            // @filename: a.ts
            import { Node, isIdent, isMod, take } from "./g.js";
            export function f(node: Node): number {
                if (isMod(node)) { return 1; }
                if (isIdent(node)) { return take(node); }
                return 0;
            }
            """.trimIndent(),
        ) should { have(none { it.code == 2345 }) }
    }
}
