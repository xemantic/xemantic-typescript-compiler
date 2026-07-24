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
import kotlin.test.assertEquals

/**
 * (M0.4, round 652): pins for the checkImplicitAnyYieldExpressions (TS7057 —
 * a `yield` whose result type is implicitly `any` because its containing
 * generator FUNCTION DECLARATION lacks a return-type annotation) spine
 * migration.
 *
 * The deleted walk threaded ONE downward boolean, `inGen`: FALSE at top-level
 * statements, set by a generator `function*` DECLARATION lacking a return-type
 * annotation (its body only), and RESET to false by EVERY nested function-like
 * (arrow / function expression / class-member body / class property
 * initializer) — so it is NOT monotone and rides the classifier status.
 *
 * Frozen quirks pinned here (all verified against the pre-migration walker):
 * a STATEMENT-position `yield x;` (parens transparent) draws NOTHING — its
 * result is discarded (tsc `expressionResultIsUnused`) — while its OPERAND is
 * still walked; only FunctionDeclaration generators are flagged (a generator
 * METHOD / FunctionExpression is contextually typable, the FP-safe subset);
 * if/loop/switch HEADS, for-initializers/conditions/incrementors ARE walked;
 * object-literal METHODS and shorthand properties are NOT; the run-level gate
 * is `noImplicitAny || strict`, plus a `.d.ts` file skip.
 */
class M04ImplicitAnyYieldSpineMigrationTest {

    // ── fires: inGen = true positions ──────────────────────────────────────

    @Test
    fun `TS7057 - yield in a variable initializer inside a generator`() {
        diagnose(
            """
            function* g() { const x = yield 1; }
            """
        ) should {
            have(any { it.code == 7057 })
        }
    }

    @Test
    fun `TS7057 - squiggles the yield keyword`() {
        val src = "function* g() { const x = yield 1; }"
        val hit = diagnose(src).first { it.code == 7057 }
        // The directives line is stripped before checking, so positions index
        // into the bare snippet.
        assertEquals(src.indexOf("yield"), hit.start)
        assertEquals(5, hit.length)
    }

    @Test
    fun `TS7057 - yield as a call argument`() {
        diagnose(
            """
            declare function use(v: any): void;
            function* g() { use(yield 1); }
            """
        ) should {
            have(any { it.code == 7057 })
        }
    }

    @Test
    fun `TS7057 - yield in a binary expression`() {
        diagnose(
            """
            function* g() { const n = 1 + (yield 2); }
            """
        ) should {
            have(any { it.code == 7057 })
        }
    }

    @Test
    fun `TS7057 - yield in a return expression`() {
        diagnose(
            """
            function* g() { return yield 1; }
            """
        ) should {
            have(any { it.code == 7057 })
        }
    }

    @Test
    fun `TS7057 - yield in an if condition`() {
        diagnose(
            """
            function* g() { if (yield 1) { } }
            """
        ) should {
            have(any { it.code == 7057 })
        }
    }

    @Test
    fun `TS7057 - yield in a while condition`() {
        diagnose(
            """
            function* g() { while (yield 1) { } }
            """
        ) should {
            have(any { it.code == 7057 })
        }
    }

    @Test
    fun `TS7057 - yield in a for header initializer and condition`() {
        diagnose(
            """
            function* g() { for (let i = yield 1; yield 2; ) { break; } }
            """
        ) should {
            have(count { it.code == 7057 } == 2)
        }
    }

    @Test
    fun `TS7057 - yield in a switch subject and a case expression`() {
        diagnose(
            """
            function* g() { switch (yield 1) { case (yield 2): break; } }
            """
        ) should {
            have(count { it.code == 7057 } == 2)
        }
    }

    @Test
    fun `TS7057 - yield inside try catch and finally blocks`() {
        diagnose(
            """
            function* g() {
                try { const a = yield 1; }
                catch (e) { const b = yield 2; }
                finally { const c = yield 3; }
            }
            """
        ) should {
            have(count { it.code == 7057 } == 3)
        }
    }

    @Test
    fun `TS7057 - yield inside a nested block and a labeled statement`() {
        diagnose(
            """
            function* g() {
                { const a = yield 1; }
                lbl: { const b = yield 2; }
            }
            """
        ) should {
            have(count { it.code == 7057 } == 2)
        }
    }

    @Test
    fun `TS7057 - yield in an array literal and an object literal property`() {
        diagnose(
            """
            function* g() {
                const a = [yield 1];
                const o = { p: yield 2 };
            }
            """
        ) should {
            have(count { it.code == 7057 } == 2)
        }
    }

    @Test
    fun `TS7057 - yield in a template span and an element access argument`() {
        diagnose(
            """
            declare const arr: any[];
            function* g() {
                const s = `${'$'}{yield 1}`;
                const v = arr[yield 2];
            }
            """
        ) should {
            have(count { it.code == 7057 } == 2)
        }
    }

    @Test
    fun `TS7057 - the operand of a discarded statement-position yield is still walked`() {
        diagnose(
            """
            function* g() { yield (yield 1); }
            """
        ) should {
            // The OUTER (statement-position) yield is unused → silent; the
            // inner one is an operand → fires. Exactly one.
            have(count { it.code == 7057 } == 1)
        }
    }

    @Test
    fun `TS7057 - a generator declaration nested in a plain function fires`() {
        diagnose(
            """
            function outer() {
                function* g() { const x = yield 1; }
            }
            """
        ) should {
            have(any { it.code == 7057 })
        }
    }

    @Test
    fun `TS7057 - a generator declaration inside a namespace body fires`() {
        diagnose(
            """
            namespace N {
                export function* g() { const x = yield 1; }
            }
            """
        ) should {
            have(any { it.code == 7057 })
        }
    }

    @Test
    fun `TS7057 - a generator declaration inside a class method body fires`() {
        diagnose(
            """
            class C {
                m() {
                    function* g() { const x = yield 1; }
                }
            }
            """
        ) should {
            have(any { it.code == 7057 })
        }
    }

    @Test
    fun `TS7057 - a generator declaration inside an arrow body fires`() {
        diagnose(
            """
            const f = () => {
                function* g() { const x = yield 1; }
            };
            """
        ) should {
            have(any { it.code == 7057 })
        }
    }

    @Test
    fun `TS7057 - a generator declaration inside a function expression body fires`() {
        diagnose(
            """
            const f = function () {
                function* g() { const x = yield 1; }
            };
            """
        ) should {
            have(any { it.code == 7057 })
        }
    }

    @Test
    fun `TS7057 - a generator declaration inside a class property initializer fires`() {
        diagnose(
            """
            class C {
                p = () => {
                    function* g() { const x = yield 1; }
                };
            }
            """
        ) should {
            have(any { it.code == 7057 })
        }
    }

    @Test
    fun `TS7057 - yield in an object-literal computed name and a spread`() {
        diagnose(
            """
            function* g() {
                const a = { [yield 1]: 2 };
                const b = { ...(yield 3) };
            }
            """
        ) should {
            have(count { it.code == 7057 } == 2)
        }
    }

    // ── silent: the discarded-result rule ──────────────────────────────────

    @Test
    fun `negative control - a statement-position yield draws nothing`() {
        diagnose(
            """
            function* g() { yield 1; }
            """
        ) should {
            have(none { it.code == 7057 })
        }
    }

    @Test
    fun `negative control - a parenthesized statement-position yield draws nothing`() {
        diagnose(
            """
            function* g() { ((yield 1)); }
            """
        ) should {
            have(none { it.code == 7057 })
        }
    }

    // ── silent: inGen = false positions ────────────────────────────────────

    @Test
    fun `negative control - the generator has a return-type annotation`() {
        diagnose(
            """
            function* g(): Generator<number, void, number> { const x = yield 1; }
            """
        ) should {
            have(none { it.code == 7057 })
        }
    }

    @Test
    fun `negative control - a generator method is never flagged`() {
        diagnose(
            """
            class C { *m() { const x = yield 1; } }
            """
        ) should {
            have(none { it.code == 7057 })
        }
    }

    @Test
    fun `negative control - a generator function expression is never flagged`() {
        diagnose(
            """
            const g = function* () { const x = yield 1; };
            """
        ) should {
            have(none { it.code == 7057 })
        }
    }

    @Test
    fun `negative control - a nested function expression resets the generator context`() {
        diagnose(
            """
            function* g() {
                const inner = function () { const x = yield 1; };
            }
            """
        ) should {
            have(none { it.code == 7057 })
        }
    }

    @Test
    fun `negative control - a nested arrow resets the generator context`() {
        diagnose(
            """
            function* g() {
                const inner = () => { const x = yield 1; };
            }
            """
        ) should {
            have(none { it.code == 7057 })
        }
    }

    @Test
    fun `negative control - a nested arrow with an expression body resets it too`() {
        diagnose(
            """
            function* g() {
                const inner = () => (yield 1);
            }
            """
        ) should {
            have(none { it.code == 7057 })
        }
    }

    @Test
    fun `negative control - a class member body inside a generator resets it`() {
        diagnose(
            """
            function* g() {
                class C { m() { const x = yield 1; } }
            }
            """
        ) should {
            have(none { it.code == 7057 })
        }
    }

    @Test
    fun `negative control - a class property initializer inside a generator resets it`() {
        diagnose(
            """
            function* g() {
                class C { p = (yield 1); }
            }
            """
        ) should {
            have(none { it.code == 7057 })
        }
    }

    @Test
    fun `negative control - a nested non-generator function declaration resets it`() {
        diagnose(
            """
            function* g() {
                function inner() { const x = yield 1; }
            }
            """
        ) should {
            have(none { it.code == 7057 })
        }
    }

    @Test
    fun `negative control - an object-literal method body is never walked`() {
        diagnose(
            """
            function* g() {
                const o = { m() { function* h() { const x = yield 1; } } };
            }
            """
        ) should {
            have(none { it.code == 7057 })
        }
    }

    @Test
    fun `negative control - a plain function is never a generator context`() {
        diagnose(
            """
            function g() { const x = yield 1; }
            """
        ) should {
            have(none { it.code == 7057 })
        }
    }

    // ── silent: the run-level and file-level gates ─────────────────────────

    @Test
    fun `negative control - strict false without noImplicitAny disables the pass`() {
        diagnose(
            """
            function* g() { const x = yield 1; }
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 7057 })
        }
    }

    @Test
    fun `TS7057 - noImplicitAny alone enables the pass`() {
        diagnose(
            """
            function* g() { const x = yield 1; }
            """,
            directives = "// @noImplicitAny: true",
        ) should {
            have(any { it.code == 7057 })
        }
    }
}
