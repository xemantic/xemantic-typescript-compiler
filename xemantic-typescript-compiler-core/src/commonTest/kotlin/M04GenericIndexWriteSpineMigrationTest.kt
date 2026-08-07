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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (M0.4, round 637): pins for the checkGenericIndexWrite (TS2862 "Type 'T'
 * is generic and can only be indexed for reading.") spine migration — the
 * purely DOWNWARD (tparams, tpProps, refs) triple rebuilt as a pull-based
 * per-anchor ancestor fold: tparams ACCUMULATE through class/fn boundaries,
 * refs REBUILD at every fn-like boundary from params + a body-WIDE locals
 * prepass (use-before-decl matches; switch/try locals are NOT collected —
 * the prepass descent is narrower than the scan's), tpProps come from the
 * nearest enclosing class's bare-TP property annotations (reset by a nested
 * FunctionDeclaration; cleared for property initializers), and the legacy
 * REACH silences (arrow/fn-expression bodies, class expressions, object
 * literals, compound assignments). All expectations verified against the
 * pre-migration walker.
 */
class M04GenericIndexWriteSpineMigrationTest {

    // ── the emitter's gates ────────────────────────────────────────────────

    @Test
    fun `TS2862 - index write through a param typed as a Record-constrained type param`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(t: T, k: string) {
                t[k] = 1;
            }
            """
        ) should {
            have(any { it.code == 2862 && "Type 'T' is generic and can only be indexed for reading." == it.message })
        }
    }

    @Test
    fun `negative control - an index READ draws nothing`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(t: T, k: string) {
                const v = t[k];
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    @Test
    fun `negative control - a numeric-literal index write is the allowed form`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(t: T) {
                t[0] = 1;
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    @Test
    fun `negative control - a constraint WITHOUT a string index signature never fires`() {
        diagnose(
            """
            function f<T extends object>(t: T, k: string) {
                t[k] = 1;
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    @Test
    fun `TS2862 - inline index-signature constraint`() {
        diagnose(
            """
            function f<T extends { [s: string]: number }>(t: T, k: string) {
                t[k] = 1;
            }
            """
        ) should {
            have(any { it.code == 2862 })
        }
    }

    @Test
    fun `TS2862 - intersection constraint containing a Record`() {
        diagnose(
            """
            function f<T extends { a: number } & Record<string, number>>(t: T, k: string) {
                t[k] = 1;
            }
            """
        ) should {
            have(any { it.code == 2862 })
        }
    }

    @Test
    fun `negative control - a number-keyed Record constraint never fires`() {
        diagnose(
            """
            function f<T extends Record<number, string>>(t: T, k: number) {
                t[k] = "x";
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    @Test
    fun `TS2862 - paren-wrapped write target unwraps`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(t: T, k: string) {
                (t[k]) = 1;
            }
            """
        ) should {
            have(any { it.code == 2862 })
        }
    }

    @Test
    fun `TS2862 - a chained assignment emits once per generic write target`() {
        val count = diagnose(
            """
            function f<T extends Record<string, any>>(t: T, k: string, k2: string) {
                t[k] = t[k2] = 1;
            }
            """
        ).count { it.code == 2862 }
        assert(count == 2)
    }

    @Test
    fun `negative control - a compound assignment write never fires - Equals only`() {
        diagnose(
            """
            function f<T extends Record<string, number>>(t: T, k: string) {
                t[k] += 1;
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    // ── refs: params, the body-WIDE locals prepass, nested-fn rebuild ──────

    @Test
    fun `TS2862 - a body local annotated with the type param fires even BEFORE its declaration`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(k: string) {
                t[k] = 1;
                let t: T;
            }
            """
        ) should {
            have(any { it.code == 2862 })
        }
    }

    @Test
    fun `frozen - a local declared inside a SWITCH case is not collected by the prepass`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(k: string, n: number) {
                switch (n) {
                    case 1:
                        let t: T;
                        t[k] = 1;
                }
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    @Test
    fun `frozen - a local declared inside a TRY block is not collected by the prepass`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(k: string) {
                try {
                    let t: T;
                    t[k] = 1;
                } catch (e) {}
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    @Test
    fun `TS2862 - a local declared inside a nested IF block IS collected`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(k: string, b: boolean) {
                if (b) {
                    let t: T;
                    t[k] = 1;
                }
            }
            """
        ) should {
            have(any { it.code == 2862 })
        }
    }

    @Test
    fun `frozen - a nested function does NOT inherit the outer body's refs`() {
        diagnose(
            """
            function outer<T extends Record<string, any>>(t: T, k: string) {
                function inner() {
                    t[k] = 1;
                }
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    @Test
    fun `TS2862 - tparams ACCUMULATE into a nested function's own params`() {
        diagnose(
            """
            function outer<T extends Record<string, any>>() {
                function inner(t: T, k: string) {
                    t[k] = 1;
                }
            }
            """
        ) should {
            have(any { it.code == 2862 })
        }
    }

    // ── class members: tpProps, own/method TPs, accessors, initializers ────

    @Test
    fun `TS2862 - a this-property annotated with the class type param fires in a method body`() {
        diagnose(
            """
            class C<T extends Record<string, any>> {
                p!: T;
                m(k: string) {
                    this.p[k] = 1;
                }
            }
            """
        ) should {
            have(any { it.code == 2862 && "Type 'T' is generic" in it.message })
        }
    }

    @Test
    fun `frozen - a property INITIALIZER runs with cleared tpProps and refs`() {
        diagnose(
            """
            class C<T extends Record<string, any>> {
                p!: T;
                q = (this.p["x"] = 1);
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    @Test
    fun `frozen - a FunctionDeclaration nested in a method resets tpProps`() {
        diagnose(
            """
            class C<T extends Record<string, any>> {
                p!: T;
                m() {
                    function g() {
                        this.p["x"] = 1;
                    }
                }
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    @Test
    fun `TS2862 - a method's OWN type params fire`() {
        diagnose(
            """
            class C {
                m<T extends Record<string, any>>(t: T, k: string) {
                    t[k] = 1;
                }
            }
            """
        ) should {
            have(any { it.code == 2862 })
        }
    }

    @Test
    fun `TS2862 - constructor and set-accessor param refs fire`() {
        diagnose(
            """
            class C<T extends Record<string, any>> {
                constructor(t: T, k: string) {
                    t[k] = 1;
                }
                set s(t: T) {
                    t["a"] = 1;
                }
            }
            """
        ) should {
            have(any { it.code == 2862 && it.line == 3 })
            have(any { it.code == 2862 && it.line == 6 })
        }
    }

    @Test
    fun `TS2862 - a get-accessor body's LOCALS are collected - no params`() {
        diagnose(
            """
            class C<T extends Record<string, any>> {
                get g(): number {
                    let t: T;
                    t["a"] = 1;
                    return 1;
                }
            }
            """
        ) should {
            have(any { it.code == 2862 })
        }
    }

    @Test
    fun `TS2862 - a class nested in a method body builds its own context through the chain`() {
        diagnose(
            """
            class Outer {
                m() {
                    class Inner<U extends Record<string, any>> {
                        im(u: U, k: string) {
                            u[k] = 1;
                        }
                    }
                }
            }
            """
        ) should {
            have(any { it.code == 2862 && "Type 'U' is generic" in it.message })
        }
    }

    // ── reach: legacy-walked positions fire, legacy silences stay silent ───

    @Test
    fun `frozen - an ARROW body is never scanned`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(t: T, k: string) {
                const g = () => { t[k] = 1; };
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    @Test
    fun `frozen - a FUNCTION-EXPRESSION body is never scanned`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(t: T, k: string) {
                const g = function () { t[k] = 1; };
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    @Test
    fun `frozen - a CLASS-EXPRESSION member body is never scanned`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(t: T, k: string) {
                const C = class {
                    m() {
                        t[k] = 1;
                    }
                };
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    @Test
    fun `frozen - an OBJECT-LITERAL property value is never scanned`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(t: T, k: string) {
                const o = { v: (t[k] = 1) };
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }

    @Test
    fun `TS2862 - reached expression positions - if condition and for incrementor`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(t: T, k: string) {
                if ((t[k] = 1)) {}
                for (let i = 0; i < 1; t[k] = i) {}
            }
            """
        ) should {
            have(any { it.code == 2862 && it.line == 2 })
            have(any { it.code == 2862 && it.line == 3 })
        }
    }

    @Test
    fun `TS2862 - reached statement positions - switch clause - try-finally - labeled - loop bodies`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(t: T, k: string, n: number) {
                switch (n) { case 1: t[k] = 1; }
                try { t[k] = 2; } finally { t[k] = 3; }
                lbl: t[k] = 4;
                for (const x of [1]) { t[k] = 5; }
                while (n > 0) { t[k] = 6; }
                do { t[k] = 7; } while (n > 0);
            }
            """
        ) should {
            have(count { it.code == 2862 } == 7)
        }
    }

    @Test
    fun `TS2862 - a function inside a NAMESPACE body fires`() {
        diagnose(
            """
            namespace N {
                function f<T extends Record<string, any>>(t: T, k: string) {
                    t[k] = 1;
                }
            }
            """
        ) should {
            have(any { it.code == 2862 })
        }
    }

    @Test
    fun `negative control - a non-this property-access receiver never fires`() {
        diagnose(
            """
            function f<T extends Record<string, any>>(o: { t: T }, k: string) {
                o.t[k] = 1;
            }
            """
        ) should {
            have(none { it.code == 2862 })
        }
    }
}
