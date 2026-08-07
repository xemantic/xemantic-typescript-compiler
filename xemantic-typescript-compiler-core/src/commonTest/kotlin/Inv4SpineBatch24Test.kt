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
 * INV.4(d) walker 4 (round 533): checkDuplicateIdentifiers (TS2300 family)
 * migrated onto the check spine — the recursive checkDuplicatesInStatement(s)/
 * checkDuplicatesInExpr/checkDuplicatesInClassElement walkers and the per-file
 * pass driver are DELETED. Reach is reproduced by a memoized boolean
 * ancestor-chain classifier ([spineDupIdEdge] — the deleted walker's exact
 * dispatch arms, including the quirks pinned here: call arguments / binary
 * operands / if-and-loop CONDITIONS / case EXPRESSIONS / for headers /
 * param defaults / class property initializers / object-literal accessor
 * bodies all UNREACHED; checkDuplicateDeclarations groups fire only at file
 * level, FunctionDeclaration bodies, and ModuleBlocks — never arrow/method
 * bodies). The bounded leaf utilities (checkDuplicateTypeParams/Params,
 * checkDuplicatesInType(Member), the member-list checks,
 * checkDuplicateDeclarations) stay intact, dispatched at anchor enters; the
 * per-file top-level scans run from checkSpine's loop (with
 * currentFileLocals nulled — the legacy pass ran with it null). All pins
 * verified against the OLD walker (pre-migration checker) — a pure
 * reach-preserving migration.
 */
class Inv4SpineBatch24Test {

    // ── emission shapes ─────────────────────────────────────────────────────

    @Test
    fun `duplicate type params on function declaration fire TS2300`() {
        diagnose("""
            function f<T, T>(x: T): T { return x; }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate parameters on function declaration fire TS2300 on both`() {
        diagnose("""
            function f(a: number, a: number) { return a; }
        """) should {
            have(count { it.code == 2300 } == 2)
        }
    }

    @Test
    fun `duplicate parameter via binding pattern fires TS2300`() {
        diagnose("""
            function f(a: number, { a }: { a: number }) { return a; }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate classes in function body fire TS2300`() {
        diagnose("""
            function f() {
                class X {}
                class X {}
            }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate let in function body fires TS2451`() {
        diagnose("""
            function f() {
                let x = 1;
                let x = 2;
            }
        """) should {
            have(any { it.code == 2451 })
        }
    }

    @Test
    fun `duplicate type params on arrow in var initializer fire TS2300`() {
        diagnose("""
            const f = <T, T>(x: T) => x;
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate params on arrow nested in arrow expression body fire TS2300`() {
        diagnose("""
            const f = () => (a: number, a: number) => a;
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate params on object literal method fire TS2300`() {
        diagnose("""
            const o = { m(a: string, a: string) { return a; } };
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate params reached through nested object literal property values fire TS2300`() {
        diagnose("""
            const o = { p: { q: function (a: number, a: number) { return a; } } };
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate interface properties fire TS2300`() {
        diagnose("""
            interface I { x: number; x: number; }
        """) should {
            have(count { it.code == 2300 } == 2)
        }
    }

    @Test
    fun `duplicate members in interface method return type literal fire TS2300`() {
        diagnose("""
            interface I { m(): { y: number; y: number }; }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate members in type alias literal fire TS2300`() {
        diagnose("""
            type T = { a: string; a: string };
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate members in variable annotation literal fire TS2300`() {
        diagnose("""
            declare let v: { b: number; b: number };
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate members in function return type literal fire TS2300`() {
        diagnose("""
            declare function f(): { c: string; c: string };
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate members in parameter type literal fire TS2300`() {
        diagnose("""
            declare function f(p: { d: boolean; d: boolean }): void;
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate enum members fire TS2300`() {
        diagnose("""
            enum E { A, A }
        """) should {
            have(count { it.code == 2300 } == 2)
        }
    }

    @Test
    fun `enum member after uncomputable initializer fires TS1061`() {
        diagnose("""
            declare function mk(): number;
            enum E { A = mk(), B }
        """) should {
            have(any { it.code == 1061 })
        }
    }

    @Test
    fun `duplicate export specifier aliases fire TS2300`() {
        diagnose("""
            const a = 1;
            const b = 2;
            export { a as z, b as z };
        """) should {
            have(count { it.code == 2300 } == 2)
        }
    }

    @Test
    fun `duplicate classes in namespace body fire TS2300`() {
        diagnose("""
            namespace N {
                class Y {}
                class Y {}
            }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate classes across merged namespace blocks fire TS2300`() {
        diagnose("""
            namespace M { export class C {} }
            namespace M { export class C {} }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `static prototype method fires TS2300`() {
        diagnose("""
            class P { static prototype() {} }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `static prototype property fires TS2699`() {
        diagnose("""
            class P { static prototype = 1; }
        """) should {
            have(any { it.code == 2699 })
        }
    }

    @Test
    fun `clodule prototype member fires TS2300`() {
        diagnose("""
            class Q {}
            namespace Q { export var prototype: number; }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `direct self typeof annotation fires TS2502`() {
        diagnose("""
            declare var s: typeof s;
        """) should {
            have(any { it.code == 2502 })
        }
    }

    @Test
    fun `mutual var typeof cycle fires TS2502 on each`() {
        diagnose("""
            declare var b1: typeof c1;
            declare var c1: typeof b1;
        """) should {
            have(count { it.code == 2502 } == 2)
        }
    }

    @Test
    fun `var-assigned self-calling function expression fires TS7023`() {
        diagnose("""
            var f1 = function () { return f1(); };
        """) should {
            have(any { it.code == 7023 })
        }
    }

    @Test
    fun `duplicate class members fire TS2300`() {
        diagnose("""
            class C {
                m() { return 1; }
                m = 2;
            }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    // ── reach: nested statement positions ───────────────────────────────────

    @Test
    fun `duplicate params in switch clause body fire TS2300`() {
        diagnose("""
            switch (1 as number) {
                case 1:
                    function s(a: number, a: number) { return a; }
                    break;
            }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate params in try catch finally bodies fire TS2300`() {
        diagnose("""
            try {
                function t1(a: number, a: number) { return a; }
            } catch (e) {
                function t2(b: number, b: number) { return b; }
            } finally {
                function t3(c: number, c: number) { return c; }
            }
        """) should {
            have(count { it.code == 2300 } == 6)
        }
    }

    @Test
    fun `duplicate params in labeled block fire TS2300`() {
        diagnose("""
            lbl: {
                function l1(a: number, a: number) { return a; }
            }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate params in returned function expression fire TS2300`() {
        diagnose("""
            function r() {
                return function (a: number, a: number) { return a; };
            }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate params in thrown function expression fire TS2300`() {
        diagnose("""
            function r2() {
                throw function (a: number, a: number) { return a; };
            }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate params in if-branch and loop bodies fire TS2300`() {
        diagnose("""
            declare const cond: boolean;
            if (cond) {
                function i1(a: number, a: number) { return a; }
            } else {
                function i2(b: number, b: number) { return b; }
            }
            while (cond) {
                function w1(c: number, c: number) { return c; }
                break;
            }
            for (;;) {
                function f1(d: number, d: number) { return d; }
                break;
            }
        """) should {
            have(count { it.code == 2300 } == 8)
        }
    }

    @Test
    fun `duplicate params in class accessor body nested function fire TS2300`() {
        diagnose("""
            class C {
                get g() {
                    function h(a: number, a: number) { return a; }
                    return 1;
                }
            }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate params in constructor body nested function fire TS2300`() {
        diagnose("""
            class C {
                constructor() {
                    function h(a: number, a: number) { return a; }
                }
            }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    @Test
    fun `duplicate constructor parameters fire TS2300`() {
        diagnose("""
            class C {
                constructor(a: number, a: number) {}
            }
        """) should {
            have(any { it.code == 2300 })
        }
    }

    // ── reach quirks (negative controls, legacy-verbatim) ───────────────────

    @Test
    fun `negative control - duplicate params on arrow in call argument stay silent`() {
        diagnose("""
            declare function foo(cb: (a: number, b: number) => number): void;
            foo((a: number, a: number) => a);
        """) should {
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `negative control - duplicate params on function expression in binary operand stay silent`() {
        diagnose("""
            const x = true || function (a: number, a: number) { return a; };
        """) should {
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `negative control - duplicate params in class property initializer stay silent`() {
        diagnose("""
            class C { p = (a: number, a: number) => a; }
        """) should {
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `negative control - duplicate params in parameter default stay silent`() {
        diagnose("""
            function f(p = function (a: number, a: number) { return a; }) {}
        """, directives = "// @strict: false") should {
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `negative control - duplicate params in for-header initializer stay silent`() {
        diagnose("""
            for (var q = function (a: number, a: number) { return a; }; false;) {}
        """) should {
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `negative control - duplicate params in if condition stay silent`() {
        diagnose("""
            if (!!function (a: number, a: number) { return a; }) {}
        """) should {
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `negative control - duplicate params in case expression stay silent`() {
        diagnose("""
            switch (0 as number) {
                case (function (a: number, a: number) { return a; })(1, 2):
                    break;
            }
        """) should {
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `negative control - object literal accessor body nested function stays silent`() {
        diagnose("""
            const o = {
                get g() {
                    function h(a: number, a: number) { return a; }
                    return 1;
                }
            };
        """) should {
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `negative control - duplicate classes in arrow body stay silent`() {
        diagnose("""
            const g = () => {
                class X {}
                class X {}
            };
        """) should {
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `negative control - duplicate classes in method body stay silent`() {
        diagnose("""
            class C {
                m() {
                    class X {}
                    class X {}
                }
            }
        """) should {
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `negative control - duplicate params in dts file stay silent`() {
        diagnose("""
            declare function f(a: number, a: number): void;
        """, fileName = "t.d.ts") should {
            have(none { it.code == 2300 })
        }
    }

    @Test
    fun `negative control - single parameters draw no TS2300`() {
        diagnose("""
            function f<T, U>(a: T, b: U) { return b; }
            class C { m(x: number) { return x; } }
            interface I { y: number; z: string; }
        """) should {
            have(none { it.code == 2300 })
        }
    }
}
