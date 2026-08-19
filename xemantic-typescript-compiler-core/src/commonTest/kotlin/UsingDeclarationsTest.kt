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
 * (CHK.25) round 948 — **`using` / `await using` DECLARATIONS (TypeScript 5.2, explicit
 * resource management) DID NOT PARSE AT ALL.**
 *
 * `using x = expr;` reported TS1434 `Unexpected keyword or identifier.` at the `using`
 * and then TS2304 for every name the failed statement never bound — the largest single
 * cascade in the whole pristine population (33 ours-only rows over four fixtures,
 * `docs/pristine-divergences.md` § 2.3 P1).
 *
 * **THE REPRESENTATION.** `using` reuses `VariableStatement` / `VariableDeclarationList`,
 * exactly as tsc does: the list's `flags` field already IS the head token
 * (`VarKeyword` / `LetKeyword` / `ConstKeyword`), so `using` needs no new node, no
 * `forEachChild` arm and no binder arm — the binder's `isVar` test already reads any
 * non-`var` head as block-scoped, which is what `using` is. `await using` is TWO tokens
 * collapsed onto the synthetic flags value [SyntaxKind.AwaitUsingKeyword], which the
 * scanner never produces (tsc spells the same distinction `NodeFlags.AwaitUsing`).
 *
 * **THE RISK IS THE CONTEXTUAL KEYWORD, AND IT IS WHERE THIS CLASS'S DISCRIMINATING PINS
 * ARE.** `using` is an ordinary identifier everywhere else — `const using = 1`,
 * `using.foo()`, `using[x]`, `{ using: 1 }`, a parameter named `using` — so what makes
 * the statement arm ADDITIVE is tsc's lookahead (`isUsingDeclaration`): a binding
 * identifier or a `{` binding pattern **on the same line**. An `[` is deliberately NOT a
 * start, because `using[x]` is an element access; and the same-line test is what keeps
 * ASI intact.
 *
 * **WHAT IS DELIBERATELY NOT HERE**, all false NEGATIVES: the DOWNLEVEL emit
 * (tsc's `__addDisposableResource` / `__disposeResources` helpers — the head is emitted
 * verbatim, which is tsc's own output at ESNext), `declare using` (TS1545, which needs
 * the `declare` statement production), the `case`/`default`-clause rule (TS1547/1548),
 * and the `await using` CONTEXT rules (TS2852/2853/2854, TS18054).
 */
class UsingDeclarationsTest {

    /** `Symbol.dispose` lives in `lib.esnext.disposable`, which the EMBEDDED lib does not
     *  carry — every disposability pin has to name the real ones. */
    private val realLibs = "// @strict: true\n// @useRealLibs: true\n// @target: esnext"

    // ── parse + bind: the statement form ─────────────────────────────────────

    @Test
    fun `a using declaration parses and binds its name`() {
        diagnose(
            """
                declare const d: { p: number };
                using r = d;
                r.p;
            """
        ) should {
            have(none { it.code == 1434 })
            have(none { it.code == 2304 })
            have(isEmpty())
        }
    }

    @Test
    fun `an await using declaration parses and binds its name`() {
        diagnose(
            """
                declare const d: { p: number };
                async function f() {
                    await using r = d;
                    r.p;
                }
            """
        ) should {
            have(none { it.code == 1434 })
            have(none { it.code == 2304 })
            have(isEmpty())
        }
    }

    @Test
    fun `a using declaration list takes several declarators`() {
        diagnose(
            """
                declare const d: { p: number };
                using a = d, b = d;
                a.p;
                b.p;
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `a using declaration name is block-scoped, not function-scoped`() {
        // The binder's `isVar` test is what decides this: any non-`var` head binds a
        // `SymbolFlags.BlockScopedVariable`, so a `using` name declared inside a block is
        // NOT visible after it — where a `var` in the same position hoists to the whole
        // function.  Both halves are asserted, so the pin fails if `using` ever starts
        // hoisting AND if the control stops hoisting.
        diagnose(
            """
                function f() {
                    { var b = 1; b; }
                    b;
                }
                function g() {
                    { using c = null; c; }
                    c;
                }
                f(); g();
            """
        ) should {
            have(any { it.code == 2304 && it.message == "Cannot find name 'c'." })
            have(none { it.code == 2304 && it.message == "Cannot find name 'b'." })
        }
    }

    @Test
    fun `a using head is accepted in a for-of head`() {
        diagnose(
            """
                declare const ds: { p: number }[];
                for (using d of ds) { d.p; }
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `an await using head is accepted in a for-of head`() {
        diagnose(
            """
                declare const ds: { p: number }[];
                async function f() {
                    for (await using d of ds) { d.p; }
                }
            """
        ) should {
            have(isEmpty())
        }
    }

    // ── the CONTEXTUAL-KEYWORD bound: `using` is still an ordinary identifier ─

    @Test
    fun `using is still an ordinary variable name`() {
        diagnose(
            """
                const using = 1;
                const n: number = using + 1;
                n;
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `using is still a receiver of a property access`() {
        diagnose(
            """
                declare const using: { foo(): number };
                using.foo();
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `using is still a receiver of an element access`() {
        // tsc's lookahead deliberately refuses `[` as a destructuring start for exactly
        // this shape: `using[x]` must stay an element access, not an array binding
        // pattern.  A `using` head with an array pattern is therefore unreachable.
        diagnose(
            """
                declare const using: number[];
                declare const i: number;
                const n: number = using[i];
                n;
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `using is still an object literal property name and a member name`() {
        diagnose(
            """
                const o = { using: 1 };
                const n: number = o.using;
                class C { using = 2; }
                const m: number = new C().using;
                n; m;
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `using is still a parameter name`() {
        diagnose(
            """
                function f(using: number): number { return using + 1; }
                f(1);
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `a using on its own line does not swallow the next line - ASI`() {
        // The lookahead's SAME-LINE test.  Without it these are one declaration and `x`
        // is never assigned; with it they are two expression statements, so `x` is a use
        // of the outer `const` and the annotation below type-checks.
        diagnose(
            """
                declare const using: number;
                declare const x: number;
                using
                x
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `a using followed by an identifier on the same line IS a declaration`() {
        // The positive twin of the ASI pin: same two names, one line, and now the second
        // name is a DECLARATOR, so the outer `x` is shadowed and TS1155 fires on it.
        diagnose(
            """
                declare const x: number;
                using x
            """
        ) should {
            have(any { it.code == 1155 })
        }
    }

    @Test
    fun `the bound - in a for head an of after using is the loop operator`() {
        // tsc's `disallowOf` variant of the lookahead.  Without it `using` is read as a
        // declaration head whose declarator is NAMED `of`, and the rest of the header
        // then cascades; with it this is an ordinary for-of whose assignment TARGET is
        // the variable `using`.
        diagnose(
            """
                declare const xs: number[];
                let using = 0;
                for (using of xs) { const n: number = using; n; }
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `regression guard - of is still usable as a declarator name in a for head`() {
        diagnose(
            """
                declare const using: number[];
                for (const of of using) { const n: number = of; n; }
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `await is still an ordinary await expression`() {
        diagnose(
            """
                async function f(p: Promise<number>): Promise<number> {
                    const n = await p;
                    return n;
                }
                f(Promise.resolve(1));
            """
        ) should {
            have(isEmpty())
        }
    }

    // ── the grammar rules tsc attaches to the form ───────────────────────────

    @Test
    fun `a using declarator must be initialized - TS1155`() {
        diagnose(
            """
                {
                    using a;
                }
            """
        ) should {
            have(any { it.code == 1155 && it.message == "'using' declarations must be initialized." })
        }
    }

    @Test
    fun `an await using declarator must be initialized - TS1155`() {
        diagnose(
            """
                async function f() {
                    await using a;
                }
            """
        ) should {
            have(any {
                it.code == 1155 && it.message == "'await using' declarations must be initialized."
            })
        }
    }

    @Test
    fun `a using declarator may not have a binding pattern - TS1492`() {
        // pristine `usingDeclarations.5` — the pattern is a MIDDLE declarator of a comma
        // list, and the squiggle is the NAME (three characters), not the declarator.
        val d = diagnose(
            """
                {
                    using a = null,
                          [b] = null,
                          c = null;
                }
            """
        )
        d should {
            have(any { it.code == 1492 })
        }
        val e = d.first { it.code == 1492 }
        assert(e.message == "'using' declarations may not have binding patterns.")
        assert(e.length == 3)
    }

    @Test
    fun `an object binding pattern in a for-of using head is TS1492`() {
        // pristine `usingDeclarationsInForOf.3`, whose squiggle is the two characters `{}`.
        val d = diagnose(
            """
                for (using {} of []) {
                }
            """
        )
        d should {
            have(any { it.code == 1492 })
        }
        assert(d.first { it.code == 1492 }.length == 2)
    }

    @Test
    fun `a for-in head with a using declaration is TS1493`() {
        // pristine `usingDeclarationsInForIn`, squiggle `using x` = seven characters.
        val d = diagnose(
            """
                for (using x in {}) {
                }
            """
        )
        d should {
            have(any {
                it.code == 1493 && it.message ==
                    "The left-hand side of a 'for...in' statement cannot be a 'using' declaration."
            })
        }
        assert(d.first { it.code == 1493 }.length == 7)
    }

    @Test
    fun `a for-in head with an await using declaration is TS1494`() {
        diagnose(
            """
                async function f() {
                    for (await using x in {}) {
                    }
                }
            """
        ) should {
            have(any {
                it.code == 1494 && it.message == "The left-hand side of a 'for...in' " +
                    "statement cannot be an 'await using' declaration."
            })
        }
    }

    @Test
    fun `an export modifier on a using declaration is TS1491`() {
        // pristine `usingDeclarations.13`: the statement PARSES (tsc takes the modifiers
        // and then rejects them), so the name still binds — hence no TS2304 here.
        diagnose(
            """
                export using x = null;
            """
        ) should {
            have(any {
                it.code == 1491 &&
                    it.message == "'export' modifier cannot appear on a 'using' declaration."
            })
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `an export modifier on an await using declaration is TS1495`() {
        diagnose(
            """
                export await using x = null;
            """
        ) should {
            have(any {
                it.code == 1495 &&
                    it.message == "'export' modifier cannot appear on an 'await using' declaration."
            })
        }
    }

    @Test
    fun `negative control - a let declaration takes no using grammar diagnostic`() {
        diagnose(
            """
                let a;
                a = 1;
                a;
            """
        ) should {
            have(none { it.code == 1155 || it.code == 1491 || it.code == 1492 || it.code == 1493 })
        }
    }

    // ── the disposability rule (TS2850 / TS2851) ─────────────────────────────

    @Test
    fun `a using initializer without Symbol dispose is TS2850`() {
        // pristine `usingDeclarations.14`.
        diagnose(
            """
                using x = {};
            """,
            directives = realLibs,
        ) should {
            have(any {
                it.code == 2850 && it.message == "The initializer of a 'using' declaration " +
                    "must be either an object with a '[Symbol.dispose]()' method, or be " +
                    "'null' or 'undefined'."
            })
        }
    }

    @Test
    fun `a using initializer with Symbol dispose is silent`() {
        diagnose(
            """
                using x = { [Symbol.dispose]() {} };
                x;
            """,
            directives = realLibs,
        ) should {
            have(none { it.code == 2850 })
        }
    }

    @Test
    fun `null and undefined are legal using initializers`() {
        diagnose(
            """
                using a = null;
                using b = undefined;
                a; b;
            """,
            directives = realLibs,
        ) should {
            have(none { it.code == 2850 })
        }
    }

    @Test
    fun `a primitive using initializer is TS2850`() {
        diagnose(
            """
                using x = 1;
            """,
            directives = realLibs,
        ) should {
            have(any { it.code == 2850 })
        }
    }

    @Test
    fun `an await using initializer without either dispose method is TS2851`() {
        diagnose(
            """
                async function f() {
                    await using x = {};
                }
            """,
            directives = realLibs,
        ) should {
            have(any {
                it.code == 2851 && it.message == "The initializer of an 'await using' " +
                    "declaration must be either an object with a '[Symbol.asyncDispose]()' " +
                    "or '[Symbol.dispose]()' method, or be 'null' or 'undefined'."
            })
        }
    }

    @Test
    fun `a synchronous Symbol dispose satisfies an await using initializer`() {
        // tsc's target for `await using` is `AsyncDisposable | Disposable | null |
        // undefined` — a plain `[Symbol.dispose]` is enough.
        diagnose(
            """
                async function f() {
                    await using x = { [Symbol.dispose]() {} };
                    x;
                }
            """,
            directives = realLibs,
        ) should {
            have(none { it.code == 2851 })
        }
    }

    @Test
    fun `negative control - the disposability rule is silent for let and const`() {
        diagnose(
            """
                const x = {};
                let y = 1;
                x; y;
            """,
            directives = realLibs,
        ) should {
            have(none { it.code == 2850 || it.code == 2851 })
        }
    }

    // ── emit ────────────────────────────────────────────────────────────────

    @Test
    fun `a using head is emitted verbatim`() {
        // tsc's own output at a target with explicit resource management
        // (`usingDeclarationsDeclarationEmit.1.js`).  Rewriting the head to `var` would
        // silently DELETE the disposal — a wrong answer rather than a missing one.
        val js = TypeScriptCompiler().compile(
            "// @target: esnext\n" +
                "declare const d: any;\n" +
                "using r1 = d;\n" +
                "async function f() { await using r2 = d; }\n",
            "t.ts",
        ).javascript
        assert(js != null)
        assert("using r1 = d;" in js)
        assert("await using r2 = d;" in js)
    }
}
