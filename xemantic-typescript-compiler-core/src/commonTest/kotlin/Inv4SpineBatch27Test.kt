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
 * INV.4(d) walker 7 (round 536): the use-before-declaration pass
 * checkUseBeforeDeclaration (TS2448/TS2449/TS2450 + the TS2454 co-emit and
 * the static-init TS2729) migrated onto the check spine — the per-file
 * driver and the nested-scope recursion walkers (checkUBDInStatement /
 * checkUBDInExprForNested) are DELETED; reach becomes a memoized boolean
 * ancestor classifier, the per-LIST blockScopedDecls map (a pure whole-list
 * function: let/const/class/enum/namespace decls minus hoisted fn/var
 * names) is memoized per list owner, the retained BOUNDED per-statement
 * walk checkUBDForwardRefs runs at direct statements of activated lists,
 * and the For/ForIn/ForOf loop-header SELF-ref checks re-host at the loop
 * statements' enters. The cross-file leg
 * (checkCrossFileUseBeforeDeclaration) stays a separate pass at the spine
 * slot; the producer sibling populateAmbientCyclicBaseClasses (the TS2449
 * ambient-cyclic suppression set) moves BEFORE the spine.
 *
 * All pins verified against the OLD walker (pre-migration checker) — a pure
 * reach-preserving migration. The sharpest bug-compat pins: the ForwardRefs
 * walk descends if/labeled ONLY (an unbraced if-body statement IS
 * forward-checked; a BRACED one is a fresh activation and is NOT), while
 * bodies / do-statements / for-headers / for-in/of expressions / try bodies
 * / switch CLAUSE statements are never forward-checked against the OUTER
 * list's later declarations.
 */
class Inv4SpineBatch27Test {

    // ── core TDZ emissions ──────────────────────────────────────────────────

    @Test
    fun `let used before declaration fires TS2448 with TS2454 co-emit`() {
        val d = diagnose("""
            function f() {
                console.log(x);
                let x = 1;
            }
        """)
        assert(d.count { it.code == 2448 } == 1)
        assert(d.count { it.code == 2454 } == 1)
    }

    @Test
    fun `reachable const used before declaration fires TS2448 only`() {
        val d = diagnose("""
            function f() {
                console.log(x);
                const x = 1;
            }
        """)
        assert(d.count { it.code == 2448 } == 1)
        assert(d.count { it.code == 2454 } == 0)
    }

    @Test
    fun `const declared after a top-level return co-emits TS2454 - unreachable rule`() {
        val d = diagnose("""
            function f() {
                console.log(x);
                return;
                const x = 1;
            }
        """)
        assert(d.count { it.code == 2448 } == 1)
        assert(d.count { it.code == 2454 } == 1)
    }

    @Test
    fun `class used before declaration fires TS2449`() {
        val d = diagnose("""
            const c = new C();
            class C {}
        """)
        assert(d.count { it.code == 2449 } == 1)
    }

    @Test
    fun `enum used before declaration fires TS2450`() {
        val d = diagnose("""
            const e = E.A;
            enum E { A }
        """)
        assert(d.count { it.code == 2450 } == 1)
    }

    @Test
    fun `negative control - const enum has no TDZ`() {
        diagnose("""
            const e = E.A;
            const enum E { A }
        """) should {
            have(none { it.code == 2450 })
        }
    }

    @Test
    fun `negative control - declare let and declare class have no TDZ`() {
        diagnose("""
            console.log(x);
            declare let x: number;
            const c = new C();
            declare class C {}
        """) should {
            have(none { it.code == 2448 || it.code == 2449 })
        }
    }

    @Test
    fun `negative control - namespace name itself draws nothing`() {
        diagnose("""
            const n = NS;
            namespace NS { export const v = 1; }
        """) should {
            have(none { it.code == 2448 || it.code == 2449 || it.code == 2450 })
        }
    }

    @Test
    fun `negative control - a hoisted var alongside the let disables the TDZ check`() {
        // blockScopedDecls removes names that ALSO have a hoisted (var/function)
        // declaration in the same list.
        val d = diagnose("""
            function f() {
                console.log(dup);
                let dup = 1;
                var dup;
            }
        """)
        d should { have(none { it.code == 2448 }) }
    }

    // ── self-references ─────────────────────────────────────────────────────

    @Test
    fun `self-referencing initializer fires TS2448`() {
        val d = diagnose("""
            function f() {
                let x = x;
            }
        """)
        assert(d.count { it.code == 2448 } == 1)
    }

    @Test
    fun `binding-pattern defaults - later element self-ref fires - earlier is available`() {
        val d = diagnose("""
            declare const obj: any;
            function f() {
                const { b = c, c } = obj;
                const { a, d = a } = obj;
            }
        """)
        assert(d.count { it.code == 2448 } == 1)
    }

    @Test
    fun `for-loop header self-reference fires TS2448`() {
        val d = diagnose("""
            function f() {
                for (let i = i; ;) { break; }
            }
        """)
        assert(d.count { it.code == 2448 } == 1)
    }

    @Test
    fun `for-of expression referencing its own loop binding fires TS2448`() {
        val d = diagnose("""
            function f() {
                for (const a of a) {}
            }
        """)
        assert(d.count { it.code == 2448 } == 1)
    }

    // ── IIFE eager evaluation ───────────────────────────────────────────────

    @Test
    fun `IIFE self-reference in initializer fires TS2448`() {
        val d = diagnose("""
            function f() {
                let y = (() => y)();
            }
        """)
        assert(d.count { it.code == 2448 } == 1)
    }

    @Test
    fun `negative control - async IIFE self-reference is exempt`() {
        diagnose("""
            function f() {
                let y = (async () => y)();
            }
        """) should {
            have(none { it.code == 2448 })
        }
    }

    @Test
    fun `IIFE forward reference to a later declaration fires TS2448`() {
        val d = diagnose("""
            function f() {
                (() => { console.log(z); })();
                let z = 1;
            }
        """)
        assert(d.count { it.code == 2448 } == 1)
    }

    @Test
    fun `negative control - IIFE body shadowing kills the forward reference`() {
        diagnose("""
            function f() {
                (() => { let z = 2; console.log(z); })();
                let z = 1;
            }
        """) should {
            have(none { it.code == 2448 })
        }
    }

    @Test
    fun `negative control - non-invoked arrow captures lazily`() {
        diagnose("""
            function f() {
                const g = () => z;
                let z = 1;
                return g;
            }
        """) should {
            have(none { it.code == 2448 })
        }
    }

    // ── class heritage + static initializers ────────────────────────────────

    @Test
    fun `class extends forward reference fires TS2449`() {
        val d = diagnose("""
            class D extends B {}
            class B {}
        """)
        assert(d.count { it.code == 2449 } == 1)
    }

    @Test
    fun `static property initializer forward member access fires TS2729 and TS2449`() {
        val d = diagnose("""
            class C {
                static p = D.x;
            }
            class D {
                static x = 1;
            }
        """)
        d should { have(any { it.code == 2449 }) }
        d should { have(any { it.code == 2729 }) }
    }

    @Test
    fun `negative control - instance property initializers are deferred`() {
        diagnose("""
            class C {
                p = D.x;
            }
            class D {
                static x = 1;
            }
        """) should {
            have(none { it.code == 2449 || it.code == 2729 })
        }
    }

    @Test
    fun `class-EXPRESSION static initializer forward reference fires`() {
        val d = diagnose("""
            const c = class {
                static p = later;
            };
            let later = 1;
        """)
        assert(d.count { it.code == 2448 } == 1)
    }

    // ── ForwardRefs statement-position reach quirks ─────────────────────────

    @Test
    fun `unbraced if-body forward reference fires but a braced one does not`() {
        val d1 = diagnose("""
            declare const c: boolean;
            function f() {
                if (c) console.log(x);
                let x = 1;
            }
        """)
        assert(d1.count { it.code == 2448 } == 1)
        val d2 = diagnose("""
            declare const c: boolean;
            function f() {
                if (c) { console.log(x); }
                let x = 1;
            }
        """)
        assert(d2.count { it.code == 2448 } == 0)
    }

    @Test
    fun `while condition is forward-checked but the body is not`() {
        val d1 = diagnose("""
            function f() {
                while (x) { break; }
                let x = 1;
            }
        """)
        assert(d1.count { it.code == 2448 } == 1)
        val d2 = diagnose("""
            declare const c: boolean;
            function f() {
                while (c) { console.log(x); break; }
                let x = 1;
            }
        """)
        assert(d2.count { it.code == 2448 } == 0)
    }

    @Test
    fun `negative control - do-statement condition and body are not forward-checked`() {
        diagnose("""
            function f() {
                do { break; } while (x);
                let x = 1;
            }
        """) should {
            have(none { it.code == 2448 })
        }
    }

    @Test
    fun `negative control - for headers and for-of expressions are not forward-checked`() {
        diagnose("""
            function f() {
                for (let i = x; ;) { break; }
                for (const q of y) {}
                let x = 1;
                let y = [1];
            }
        """) should {
            have(none { it.code == 2448 })
        }
    }

    @Test
    fun `switch subject is forward-checked but clause statements are not`() {
        val d = diagnose("""
            function f() {
                switch (x) { default: break; }
                let x = 1;
            }
        """)
        assert(d.count { it.code == 2448 } == 1)
        val d2 = diagnose("""
            declare const c: number;
            function f() {
                switch (c) { default: console.log(x); }
                let x = 1;
            }
        """)
        assert(d2.count { it.code == 2448 } == 0)
    }

    @Test
    fun `negative control - try bodies are not forward-checked against outer decls`() {
        diagnose("""
            function f() {
                try { console.log(x); } catch (e) {}
                let x = 1;
            }
        """) should {
            have(none { it.code == 2448 })
        }
    }

    @Test
    fun `return and throw expressions are forward-checked`() {
        val d = diagnose("""
            function f() {
                if (Math.random() > 0.5) { return; }
                console.log(a);
                let a = 1;
            }
            function g() {
                throw b;
                let b = 1;
            }
        """)
        assert(d.count { it.code == 2448 } == 2)
    }

    // ── nested-scope activations ────────────────────────────────────────────

    @Test
    fun `TDZ fires inside every nested list activation`() {
        val d = diagnose("""
            const o = {
                m() { console.log(a); let a = 1; },
            };
            class K {
                m() { console.log(b); let b = 1; }
                constructor() { console.log(c); let c = 1; }
                get g() { console.log(d); let d = 1; return 1; }
            }
            const arrow = () => { console.log(e); let e = 1; };
            namespace NS { console.log(g2); let g2 = 1; }
            {
                console.log(h);
                let h = 1;
            }
        """)
        assert(d.count { it.code == 2448 } == 7)
    }

    @Test
    fun `TDZ fires inside switch clause lists and try blocks own scopes`() {
        val d = diagnose("""
            declare const c: number;
            function f() {
                switch (c) {
                    default:
                        console.log(a);
                        let a = 1;
                }
                try {
                    console.log(b);
                    let b = 1;
                } catch (e) {}
            }
        """)
        assert(d.count { it.code == 2448 } == 2)
    }

    // ── cross-file leg ──────────────────────────────────────────────────────

    @Test
    fun `cross-file top-level use before a later file's let fires TS2448`() {
        val d = diagnose(
            """
            // @filename: a.ts
            crossLet = 30;
            // @filename: b.ts
            let crossLet: number;
            """,
        )
        assert(d.count { it.code == 2448 } == 1)
    }

    @Test
    fun `negative control - a local declaration beats the cross-file check`() {
        diagnose(
            """
            // @filename: a.ts
            var crossLet: number;
            crossLet = 30;
            // @filename: b.ts
            let crossOther: number;
            """,
        ) should {
            have(none { it.code == 2448 })
        }
    }
}
