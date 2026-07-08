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
 * Round 442 (Blocker #3, self-compile burn-down): a top-level `let`/`var`/`const` variable in a
 * MODULE file leaks into `globals` via the file-locals merge but is NOT legitimately visible by
 * bare name in another file. A common local name like `parent` (navigationBar.ts's module-level
 * `let parent: NavigationBarNode`) otherwise shadowed every OTHER file's local `parent` — a
 * block-scoped const or nested-fn param our scope machinery misses (B83.5) — so the property-access
 * walker resolved `parent` to `NavigationBarNode` and FP-fired TS2339 on `Node`/`BinaryExpression`
 * members (279 on the services profile). `checkMemberAccessMissing` now bails for a bare-Identifier
 * receiver whose name is EXCLUSIVELY a module-file-local variable, UNLESS it IS the current file's
 * own top-level binding (currentFileLocals).
 */
class ModuleFileLocalVarLeakTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "b.ts").diagnostics

    @Test
    fun `another file's local shadows the module-var leak - no TS2339`() {
        // a.ts (module) has `let parent: NavNode`; b.ts has a block-scoped local `parent` of an
        // unrelated shape. `parent.left` in b.ts must NOT resolve to NavNode → no TS2339.
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            export interface NavNode { node: number; parent: NavNode | undefined; }
            let parent: NavNode = undefined!;
            export function walk(): number { return parent.node; }

            // @Filename: b.ts
            interface Expr { left: number; }
            export function useExpr(e: Expr): number {
                const parent = e;
                return parent.left;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a property-access chain rooted at the module-var leak does not FP - no TS2339`() {
        // Round 444: the leaked root can be behind a PROPERTY-ACCESS chain — `parent.parent.kind`
        // (checker.ts): the bare `parent` leaks NavNode, so `parent.parent` resolves to NavNode
        // (it has a `.parent`) and `.kind` FP'd on the CHAIN, not the bare identifier. Walk to the
        // root; if it's a leaked module var, the whole chain is bogus → bail.
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            export interface NavNode { node: number; parent: NavNode; }
            let parent: NavNode = undefined!;
            export function walk(): number { return parent.node; }

            // @Filename: b.ts
            interface Expr { kind: number; parent: Expr; }
            export function useExpr(e: Expr): number {
                const parent = e;
                return parent.parent.kind;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `another file's local passed as an argument - no TS2345`() {
        // a.ts (module) has `let parent: NavNode`; b.ts passes its own local `parent` (of an
        // unrelated but compatible shape) as an arg. Without the fix, `parent` leaks to NavNode
        // and FP-fires TS2345 against the real param (the companion arg-check bail).
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            export interface NavNode { node: number; parent: NavNode | undefined; }
            let parent: NavNode = undefined!;
            export function walk(): number { return parent.node; }

            // @Filename: b.ts
            interface Expr { left: number; }
            declare function takesExpr(e: Expr): void;
            export function useExpr(e: Expr): void {
                const parent = e;
                takesExpr(parent);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a property-access chain rooted at the module-var leak passed as an argument - no TS2345`() {
        // Round 447: the arg-side complement of round 444's receiver chain-walk. `parent.parent` as
        // an arg, where `parent` leaks NavNode, resolves the whole chain to NavNode → FP TS2345
        // against a real Node-shaped param (`isCallExpression(parent.parent)` in tsc's codefixes).
        // Walk to the root; bail when it is a leaked module var (a CALL in the chain breaks it).
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            export interface NavNode { node: number; parent: NavNode; }
            let parent: NavNode = undefined!;
            export function walk(): number { return parent.node; }

            // @Filename: b.ts
            interface Expr { kind: number; parent: Expr; }
            declare function takesExpr(e: Expr): void;
            export function useExpr(e: Expr): void {
                const parent = e;
                takesExpr(parent.parent);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a genuinely-mismatched non-leaked arg still fires - TS2345`() {
        // Negative control for the arg-chain bail: an arg NOT rooted at a leaked module var must
        // keep firing an ordinary type mismatch (the bail only affects leaked-var roots).
        TypeScriptCompiler().compile(
            """
            // @strict: true

            // @Filename: a.ts
            export interface NavNode { node: number; parent: NavNode; }
            let parent: NavNode = undefined!;

            // @Filename: b.ts
            declare function needNum(n: number): void;
            export function f(): void { needNum("nope"); }
            """.trimIndent(),
            "b.ts",
        ).diagnostics should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `the declaring file's own module var still reports missing members - TS2339`() {
        // Negative control: within a.ts, `parent` IS the file's own top-level var (currentFileLocals),
        // so a genuinely-missing member must still fire TS2339 (the bail is gated to OTHER files).
        TypeScriptCompiler().compile(
            """
            // @strict: true

            // @Filename: a.ts
            export interface NavNode { node: number; parent: NavNode | undefined; }
            let parent: NavNode = undefined!;
            export function walk(): number { return parent.nonexistent; }

            // @Filename: b.ts
            export const other = 1;
            """.trimIndent(),
            "a.ts",
        ).diagnostics should {
            have(any { it.code == 2339 && it.message.contains("nonexistent") })
        }
    }
}
