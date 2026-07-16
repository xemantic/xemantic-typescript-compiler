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
 * INV.4(d) walker 2 (round 531): checkArithmeticOperandTypes (TS2362/TS2363/
 * TS2365/TS2367/TS2447/TS2358/TS2736/…) migrated onto the check spine. The
 * recursive checkArithmeticInExpr/checkArithmeticInStatement(s) functions are
 * RETAINED (checkComputedDestructKey calls checkArithmeticInExpr as a
 * utility), but the per-file pass driver is replaced by spine dispatch:
 * push-based spine-maintained state (the per-file currentLocalTypes copy with
 * its statement-ordered recordings, the typeof/truthy narrowing sets, the
 * B475 contextual-type frames) with ambient install around each emission.
 * Every pin here was verified against the OLD walker first — a pure
 * behavior-preserving migration, including the bug-compat quirks (block/
 * accessor recording leaks, the absorbed-`&&` no-narrowing left-spine
 * flatten, object-literal method bodies unreached).
 */
class Inv4SpineBatch22Test {

    // ── basic emission shapes ───────────────────────────────────────────────

    @Test
    fun `string times number fires TS2362 on the left operand`() {
        diagnose("""
            "s" * 2;
        """) should {
            have(any { it.code == 2362 })
        }
    }

    @Test
    fun `number times string fires TS2363 on the right operand`() {
        diagnose("""
            2 * "s";
        """) should {
            have(any { it.code == 2363 })
        }
    }

    @Test
    fun `cross-category relational comparison fires TS2365`() {
        diagnose("""
            1 < "a";
        """) should {
            have(any { it.code == 2365 })
        }
    }

    @Test
    fun `bitwise AND of two booleans fires TS2447`() {
        diagnose("""
            declare var b1: boolean;
            declare var b2: boolean;
            b1 & b2;
        """) should {
            have(any { it.code == 2447 })
        }
    }

    @Test
    fun `primitive instanceof LHS fires TS2358`() {
        diagnose("""
            1 instanceof Object;
        """) should {
            have(any { it.code == 2358 })
        }
    }

    @Test
    fun `unary plus on bigint fires TS2736`() {
        diagnose("""
            declare const b: bigint;
            +b;
        """, directives = "// @strict: true\n// @target: es2020") should {
            have(any { it.code == 2736 })
        }
    }

    // ── local-type recordings (statement-ordered, per-declarator) ───────────

    @Test
    fun `literal const recording resolves a later comparison operand`() {
        diagnose("""
            function f() {
                const t = true;
                1 >= t;
            }
        """) should {
            have(any { it.code == 2365 })
        }
    }

    @Test
    fun `recording only affects later statements - use before decl stays silent`() {
        diagnose("""
            function f() {
                1 >= t;
                const t = true;
            }
        """) should {
            have(none { it.code == 2365 })
        }
    }

    @Test
    fun `comparison-initializer recording types the local boolean`() {
        diagnose("""
            function f() {
                const c = 1 < 2;
                if (c >= 1) {}
            }
        """) should {
            have(any { it.code == 2365 })
        }
    }

    @Test
    fun `earlier declarator recording is visible in a later declarator initializer`() {
        diagnose("""
            function f() {
                const a = true, b = (1 >= a) ? 1 : 0;
            }
        """) should {
            have(any { it.code == 2365 })
        }
    }

    @Test
    fun `annotated body var recording resolves an interface operand`() {
        diagnose("""
            interface I { p: number; }
            function f() {
                var i!: I;
                i * 2;
            }
        """) should {
            have(any { it.code == 2362 })
        }
    }

    @Test
    fun `shadow-gated recording - const shadowing an outer function records the primitive`() {
        diagnose("""
            function size(): number { return 0; }
            function f(arr: number[]) {
                const size = arr.length;
                if (size < "a") {}
            }
        """) should {
            have(any { it.code == 2365 })
        }
    }

    // ── bug-compat recording leaks (block-unaware map) ──────────────────────

    @Test
    fun `bug-compat - a nested block recording leaks to later sibling statements`() {
        diagnose("""
            function f() {
                { const t = true; }
                1 >= t;
            }
        """) should {
            have(any { it.code == 2365 })
        }
    }

    @Test
    fun `bug-compat - an accessor body recording leaks into a later member body`() {
        diagnose("""
            class C {
                get g(): number { const t = true; return 0; }
                m(): number { return (1 >= t) ? 1 : 0; }
            }
        """) should {
            have(any { it.code == 2365 })
        }
    }

    @Test
    fun `negative control - a function body recording does NOT leak to the file level`() {
        diagnose("""
            function f() { const t = true; }
            1 >= t;
        """) should {
            have(none { it.code == 2365 })
        }
    }

    // ── narrowing scopes (typeof guard, && right, ternary branches) ─────────

    @Test
    fun `maybe-bigint mixing fires TS2365 without a guard`() {
        diagnose("""
            declare var v: number | bigint;
            v * 2;
        """, directives = "// @strict: true\n// @target: es2020") should {
            have(any { it.code == 2365 })
        }
    }

    @Test
    fun `typeof guard suppresses the maybe-bigint mixing TS2365 in both branches`() {
        diagnose("""
            declare var v: number | bigint;
            if (typeof v === "number") {
                v * 2;
            } else {
                v * 3;
            }
        """, directives = "// @strict: true\n// @target: es2020") should {
            have(none { it.code == 2365 })
        }
    }

    @Test
    fun `nullish union operand fires TS2362 without a guard`() {
        diagnose("""
            declare var u: number | undefined;
            u * 2;
        """) should {
            have(any { it.code == 2362 })
        }
    }

    @Test
    fun `truthy && guard strips the nullish member for the right operand`() {
        diagnose("""
            declare var u: number | undefined;
            u && u * 2;
        """) should {
            have(none { it.code == 2362 })
        }
    }

    @Test
    fun `an && absorbed into a left-spine chain still narrows via the flow-graph layer`() {
        // `u && u * 2 || 1` — the top `||` flatten ABSORBS the `&&` node, so the
        // legacy syntactic special branch (truthy-set narrowing while walking the
        // right) never runs — but the round-453 flow-graph layer
        // (arithFlowNarrowedNonNullish) independently proves `u` non-nullish at
        // `u * 2`, so no TS2362 either way. Pinned during pre-verification
        // against the OLD walker (the syntactic-set absence is masked here; the
        // migration reproduces both layers).
        diagnose("""
            declare var u: number | undefined;
            u && u * 2 || 1;
        """) should {
            have(none { it.code == 2362 })
        }
    }

    @Test
    fun `ternary whenFalse narrows a nullish-tested operand`() {
        diagnose("""
            declare var u: number | undefined;
            u === undefined ? 0 : u * 2;
        """) should {
            have(none { it.code == 2362 })
        }
    }

    @Test
    fun `ternary whenTrue keeps the un-narrowed operand firing`() {
        diagnose("""
            declare var u: number | undefined;
            u === undefined ? u * 2 : 0;
        """) should {
            have(any { it.code == 2362 })
        }
    }

    // ── scoped for-in loop variable ─────────────────────────────────────────

    @Test
    fun `for-in loop variable is string within the body - no TS2367 against a string literal`() {
        diagnose("""
            for (const k in []) {
                if (k == "1") {}
            }
            let k = 1;
        """) should {
            have(none { it.code == 2367 })
        }
    }

    // ── B475 contextual typing (assignment-RHS object literal) ──────────────

    @Test
    fun `index-signature contextual typing types an object-literal arrow param`() {
        diagnose("""
            interface IX { [s: string]: (s: string) => number; }
            declare var xo: IX;
            xo = { s: t => t * t };
        """) should {
            have(any { it.code == 2362 })
            have(any { it.code == 2363 })
        }
    }

    // ── parameter typing at function-like boundaries ────────────────────────

    @Test
    fun `arrow expression body is reached and its annotated param types the operand`() {
        diagnose("""
            const f = (u: number | undefined) => u * 2;
        """) should {
            have(any { it.code == 2362 })
        }
    }

    @Test
    fun `arrow expression body narrows via its own && guard`() {
        diagnose("""
            const f = (u: number | undefined) => u && u * 2;
        """) should {
            have(none { it.code == 2362 })
        }
    }

    @Test
    fun `class method annotated param resolves an interface operand`() {
        diagnose("""
            interface I { p: number; }
            class C {
                m(i: I): number { return i * 2; }
            }
        """) should {
            have(any { it.code == 2362 })
        }
    }

    // ── reach quirks (positions the legacy walker never visited) ────────────

    @Test
    fun `parameter defaults are unreached`() {
        diagnose("""
            function f(a = "s" * 2) {}
        """) should {
            have(none { it.code == 2362 || it.code == 2363 })
        }
    }

    @Test
    fun `enum member initializers are unreached`() {
        diagnose("""
            enum E { A = "s" * 2 }
        """) should {
            have(none { it.code == 2362 || it.code == 2363 })
        }
    }

    @Test
    fun `computed property names in object literals are unreached`() {
        diagnose("""
            const o = { ["k" * 2]: 1 };
        """) should {
            have(none { it.code == 2362 || it.code == 2363 })
        }
    }

    @Test
    fun `bug-compat - object-literal method bodies are unreached`() {
        diagnose("""
            const o = { m() { return "s" * 2; } };
        """) should {
            have(none { it.code == 2362 })
        }
    }

    @Test
    fun `class-expression method bodies ARE reached`() {
        diagnose("""
            const c = class { m() { return "s" * 2; } };
        """) should {
            have(any { it.code == 2362 })
        }
    }

    @Test
    fun `tagged-template span expressions are reached`() {
        diagnose("""
            declare function tag(s: any, v: any): string;
            tag`x${'$'}{"s" * 2}`;
        """, directives = "// @strict: true\n// @target: es2015") should {
            have(any { it.code == 2362 })
        }
    }

    @Test
    fun `for-header declarations are walked but never recorded`() {
        // The for-initializer declaration list's initializers ARE walked for
        // emissions, but the literal-recording branch is VariableStatement-only,
        // so `t` stays unrecorded → the comparison in the second declarator's
        // initializer resolves `t` as any → no TS2365.
        diagnose("""
            function f() {
                for (let t = true, i = (1 >= t) ? 1 : 0;;) { break; }
            }
        """) should {
            have(none { it.code == 2365 })
        }
    }

    @Test
    fun `recording happens after the declarator's own initializer walk`() {
        diagnose("""
            function f() {
                const a = (1 >= a) ? true : false;
            }
        """) should {
            have(none { it.code == 2365 })
        }
    }

    @Test
    fun `namespace-level identifier-init chain recording shields a file-level same-name interface var`() {
        // The qualify.ts shape: inside `namespace M.N`, `y` is the namespace-local
        // `var y = m` (number via the chain from the recorded `m`), NOT the
        // file-level `var y: I` that the block-unaware fileLocalTypeMaps would
        // otherwise supply — at the pass's old post-giants slot the TS2322
        // walk's namespace-level recording residue provided this; the migrated
        // pass records the chain itself (round 531).
        diagnose("""
            namespace M {
                export var m = 0;
                export namespace N {
                    export var n = 1;
                }
            }
            namespace M {
                export namespace N {
                    var y = m;
                    var x = n + y;
                }
            }
            interface I { k: number; }
            var y: I = undefined as any;
        """, directives = "// @strict: false\n// @target: es2015") should {
            have(none { it.code == 2365 })
        }
    }

    @Test
    fun `chain recording is namespace-level only - function bodies keep the conservative any`() {
        // A function-body `const y = m` chained from a recorded primitive does
        // NOT record (the old residue never contained function-body recordings —
        // checkFunctionBody save/restores), so the comparison stays silent.
        diagnose("""
            function f() {
                const m = 1;
                const y = m;
                if (y < "a") {}
            }
        """) should {
            have(none { it.code == 2365 })
        }
    }

    @Test
    fun `module block statements are reached`() {
        diagnose("""
            namespace M {
                export const x = "s" * 2;
            }
        """) should {
            have(any { it.code == 2362 })
        }
    }

    @Test
    fun `dts files are skipped`() {
        diagnose("""
            declare const n: number;
        """, fileName = "t.d.ts") should {
            have(none { it.code == 2362 || it.code == 2363 || it.code == 2365 })
        }
    }
}
