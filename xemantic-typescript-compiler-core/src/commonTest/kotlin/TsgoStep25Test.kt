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
 * (LEGACY.0b) step 25, 2026-09-17 — J1/J2/J4: the JavaScript class EXPANDO MEMBER MODEL,
 * and the CONSTRUCTOR-context display it exposed.
 *
 * **J1/J2.** Step 24 opened the property-access family for a checked `.js` file behind a
 * WHITELIST — an access is admitted only when its receiver's type is declared entirely in
 * `.ts`/`.d.ts` files, which no JavaScript assignment can extend. That left every
 * JavaScript-declared receiver refused, which is why it closed no ledger row. This step adds
 * the other half: a receiver whose expando set this checker can COMPUTE is decidable too.
 * Measured against tsgo 7.0.2, a JavaScript class's instance members are its class body's
 * plus every `this.X = …` / `this['X'] = …` ASSIGNMENT written in a member body, a member
 * initializer or an ARROW nested in one — and nothing else: a bare `this.X;`, a compound
 * `this.X += 1`, a parenthesized `(this.X) = 1`, an `Object.defineProperty(this, 'X', …)`,
 * a `this[k] = 1` with a non-literal key and a `this.X = …` written inside a nested non-arrow
 * `function` ALL leave `X` absent and tsgo reports it. The static side is the class body's
 * static members plus the file's `ClassName.p = …` assignments.
 *
 * The verdict has three values and the third is the load-bearing one: REFUSE when the name
 * IS an expando member, because no member table here knows one and every downstream emitter
 * would report a member that exists — which is also what leaves `checkClassFieldSuperAccessJs`
 * (TS2855/TS2565) sole owner of exactly those names.
 *
 * **J4 is NOT a JSDoc question**, which is what measuring it first showed. `this.missing` in
 * a generic class's CONSTRUCTOR rendered the bare `Gen` where tsgo renders `Gen<T, V>` — in a
 * plain `.ts` file, with no JavaScript involved — while the same read in a method, getter,
 * setter or property initializer already rendered `Gen<T, V>`. The axis is that a constructor
 * body reaches the `ctorClassSym` fallback inside `cmamCheckResolvedObjectType` (its member
 * table has not resolved yet) instead of `cmamEmitMissingProperty`, and that fallback printed
 * `symbol.name`. A JSDoc `@template` list is the same thing by another spelling: the parser
 * makes it the class's own `typeParameters`.
 *
 * **Stated residues, each measured** (tsgo reports, we stay silent): an instance-typed
 * VARIABLE receiver (`const h = new Holder(); h.missing`), a JavaScript OBJECT LITERAL — CLOSED
 * by (CHK.124) step 3, (P18.138), and re-pointed below — and
 * a class whose `extends` base is not a resolvable `ClassDeclaration` of the same file. And
 * one divergence J2 makes reachable rather than introduces: the STATIC-side spelling
 * suggestion — tsgo answers `TS2551 … Did you mean 'known'?` for `Statics.unknown` where we
 * answer TS2339, and the same fixture in a `.ts` file diverges identically (measured), so it
 * is a general gap and not a JavaScript one.
 */
class TsgoStep25Test {

    private val js =
        "// @strict: true\n// @allowJs: true\n// @checkJs: true\n// @target: esnext\n// @noEmit: true"
    private val jsNoCheck =
        "// @strict: true\n// @allowJs: true\n// @target: esnext\n// @noEmit: true"
    private val ts =
        "// @strict: true\n// @target: esnext\n// @noEmit: true"

    // NOTE: every fixture is spelled out in full rather than interpolated from a shared
    // prelude property. A MULTI-LINE interpolation defeats `trimIndent` — its continuation
    // lines carry no indentation of their own, so the common prefix becomes 0 and NOTHING is
    // stripped, and every column below would then read 12 too high.

    // ---------------------------------------------------------------- J1: the instance side

    @Test
    fun `a member on no declaration and no expando of a js class reports through this`() {
        // tsgo: a.js(6,26): error TS2339: Property 'nowhere' does not exist on type 'Der'.
        val d = diagnose(
            """
            // @Filename: /a.js
            class Base {
                constructor() { this.onBase = 1; }
                bm() {}
            }
            class Der extends Base {
                read() { return this.nowhere; }
                okBase() { return this.onBase; }
                okMethod() { return this.bm; }
            }
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 6 && it.character == 26 &&
                it.length == 7 &&
                it.message == "Property 'nowhere' does not exist on type 'Der'."
        })
    }

    @Test
    fun `a member on no declaration and no expando of a js base reports through super`() {
        // tsgo: a.js(7,26): error TS2339: Property 'alsoNowhere' does not exist on type 'Base'.
        // The display names the BASE, not the enclosing class.
        val d = diagnose(
            """
            // @Filename: /a.js
            class Base {
                constructor() { this.onBase = 1; }
                bm() {}
            }
            class Der extends Base {
                read() { return this.nowhere; }
                sup() { return super.alsoNowhere; }
            }
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 7 && it.character == 26 &&
                it.length == 11 &&
                it.message == "Property 'alsoNowhere' does not exist on type 'Base'."
        })
    }

    @Test
    fun `a compound assignment does not declare a js expando member`() {
        // tsgo: a.js(3,14) and a.js(5,24), both TS2339 on 'cmp'. `this.cmp += 1` is a
        // BinaryExpression whose operator is not `=`, so tsgo's
        // GetAssignmentDeclarationKind refuses it ((P18.123)).
        val d = diagnose(
            """
            // @Filename: /a.js
            class Odd {
                constructor() {
                    this.cmp += 1;
                }
                r1() { return this.cmp; }
            }
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 3 && it.character == 14 &&
                it.length == 3 &&
                it.message == "Property 'cmp' does not exist on type 'Odd'."
        })
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 5 && it.character == 24 &&
                it.length == 3 &&
                it.message == "Property 'cmp' does not exist on type 'Odd'."
        })
    }

    @Test
    fun `a parenthesized assignment target does not declare a js expando member`() {
        // tsgo: a.js(3,15) and a.js(5,24) — the LEFT of `=` must be an access expression,
        // and a ParenthesizedExpression is not one.
        val d = diagnose(
            """
            // @Filename: /a.js
            class Odd {
                constructor() {
                    (this.par) = 2;
                }
                r2() { return this.par; }
            }
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 3 && it.character == 15 &&
                it.length == 3 &&
                it.message == "Property 'par' does not exist on type 'Odd'."
        })
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 5 && it.character == 24 &&
                it.length == 3 &&
                it.message == "Property 'par' does not exist on type 'Odd'."
        })
    }

    @Test
    fun `a this write inside a nested function does not declare a js expando member`() {
        // tsgo: a.js(3,23) TS2339 on 'viaFn' (plus TS2683 at (2,26)). An ordinary `function`
        // rebinds `this`, so the write lands on nothing and the class never gains the member.
        val d = diagnose(
            """
            // @Filename: /a.js
            class Nested {
                m() { function g() { this.viaFn = 1; } }
                r() { return this.viaFn; }
            }
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 3 && it.character == 23 &&
                it.length == 5 &&
                it.message == "Property 'viaFn' does not exist on type 'Nested'."
        })
    }

    @Test
    fun `an element access assignment with a string literal key declares a js expando member`() {
        // Control: `this['el'] = 3` DOES declare — tsgo is silent for the read.
        val d = diagnose(
            """
            // @Filename: /a.js
            class Odd {
                constructor() {
                    this['el'] = 3;
                }
                r3() { return this.el; }
            }
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a this write in a method declares a js expando member`() {
        // Control: the expando set is NOT constructor-scoped.
        val d = diagnose(
            """
            // @Filename: /a.js
            class Meth {
                m() { this.viaMethod = 3; }
                r() { return this.viaMethod; }
            }
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a this write in an arrow declares a js expando member`() {
        // Control: an ARROW keeps the class's `this`, in a property initializer and in a
        // method body alike, so both writes declare.
        val d = diagnose(
            """
            // @Filename: /a.js
            class ArrowInit {
                f = () => { this.viaInit = 1; };
                r() { return this.viaInit; }
                q() { const h = () => { this.viaArrow = 1; }; h(); }
                r2() { return this.viaArrow; }
            }
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a base class expando member is visible on a derived this`() {
        // Control: the closure is UNIONED over the `extends` chain.
        val d = diagnose(
            """
            // @Filename: /a.js
            class Base {
                constructor() { this.onBase = 1; }
            }
            class Der extends Base {
                r() { return this.onBase; }
            }
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a base expando member read through super keeps its TS2855 and gains no TS2339`() {
        // tsgo: a.js(8,28) TS2855 and nothing else. The verdict REFUSES the access for a
        // name that IS an expando member, which is what leaves checkClassFieldSuperAccessJs
        // sole owner of the row — admitting it would add a TS2339 for a member that exists.
        val d = diagnose(
            """
            // @Filename: /a.js
            class Base {
                constructor() { this.onBase = 1; }
                bm() {}
            }
            class Der extends Base {
                supOk() { return super.onBase; }
            }
            """,
            js,
        )
        assert(d.count { it.code == 2855 } == 1)
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a js class whose extends base is not a resolvable class declaration is refused`() {
        // Stated residue: tsgo reports nothing here either, because an `any` base gives the
        // instance every member — but the refusal is what this pin fixes in place, since a
        // base we cannot read could carry any expando set at all.
        val d = diagnose(
            """
            // @Filename: /a.js
            /** @type {any} */
            const looseBase = null;
            class FromAny extends looseBase {
                constructor() { super(); this.own = 1; }
                r() { return this.zzzWhatever; }
            }
            class NoBase {
                r() { return this.zzzGone; }
            }
            """,
            js,
        )
        assert(d.none { it.code == 2339 && it.message.contains("'zzzWhatever'") })
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 8 && it.character == 23 &&
                it.length == 7 &&
                it.message == "Property 'zzzGone' does not exist on type 'NoBase'."
        })
    }

    @Test
    fun `the Object defineProperty row is emitted exactly once by the general path`() {
        // tsgo: a.js(4,36) TS2339 on '_prop', once. `Object.defineProperty(this, …)` declares
        // NOTHING (measured), so the model's set does not carry `_prop` and the funnel emits.
        // B428's dedicated walker (`checkJsObjectDefinePropertyThisReads`) existed only
        // because this family was off for `.js`; it is RETIRED in this step, measured with the
        // PassLab — disabling it on the landed binary leaves the corpus errors screen at
        // 3,102 / 0 — and without the retirement this fixture double-emits.
        val d = diagnose(
            """
            // @Filename: /a.js
            class C {
                constructor() {
                    Object.defineProperty(this, "_prop", { value: {} });
                    Object.defineProperty(this._prop, "num", { value: 12 });
                }
            }
            """,
            js,
        )
        assert(d.count { it.code == 2339 && it.message.contains("'_prop'") } == 1)
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 4 && it.character == 36 &&
                it.length == 5 &&
                it.message == "Property '_prop' does not exist on type 'C'."
        })
    }

    // ------------------------------------------------------------------ J2: the static side

    @Test
    fun `a static name that is never assigned on a js class reports`() {
        // tsgo: a.js(9,9): error TS2339: Property 'zzzMissing' does not exist on type
        // 'typeof Statics'. A TRUE row this compiler used to drop entirely.
        val d = diagnose(
            """
            // @Filename: /a.js
            class Statics {
                static declared = 1;
                static sm() {}
            }
            Statics.expando = 2;
            Statics.expando;
            Statics.declared;
            Statics.sm;
            Statics.zzzMissing;
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 9 && it.character == 9 &&
                it.length == 10 &&
                it.message == "Property 'zzzMissing' does not exist on type 'typeof Statics'."
        })
    }

    @Test
    fun `a file level assignment declares a static js expando member`() {
        // Control: `Statics.expando = 2` declares, so both the write target and the read are
        // silent — tsgo agrees. Without it the write itself is a false row on legal code,
        // which is the regression (P18.130)'s naive arm measured on
        // `classFieldSuperAccessibleJs1`.
        val d = diagnose(
            """
            // @Filename: /a.js
            class Statics {
                static declared = 1;
            }
            Statics.expando = 2;
            Statics.expando;
            Statics.declared;
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a static expando read through super in a static block keeps its TS2565 alone`() {
        // tsgo: index.js(9,23) TS2565 and nothing else — `classFieldSuperAccessibleJs1`'s own
        // shape. The static closure is what keeps the general path off it.
        val d = diagnose(
            """
            // @Filename: /index.js
            class C {
              static blah1 = 123;
            }
            C.blah2 = 456;

            class D extends C {
              static {
                console.log(super.blah1);
                console.log(super.blah2);
              }
            }
            """,
            js,
        )
        assert(d.count { it.code == 2565 } == 1)
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a same named local does not adopt a class's static expando closure`() {
        // The index is keyed by NAME, so the receiver identifier must RESOLVE to that class
        // declaration. Here it does not, and the access falls back to the immunity test,
        // which refuses a JavaScript object literal — as tsgo reports nothing beyond what it
        // reports for the literal itself, staying silent is the conservative answer.
        val d = diagnose(
            """
            // @Filename: /a.js
            class K {
                static declared = 1;
            }
            function f() {
                const K = { a: 1 };
                return K.declared;
            }
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    // --------------------------------------------------- J4: the constructor-context display

    @Test
    fun `undiscriminated control - a generic class in a ts constructor context`() {
        // tsgo: a.ts(2,26): Property 'inCtor' does not exist on type 'Gen<T, V>'. This is the
        // shape that showed J4 is a plain-TypeScript defect — measured through a `-project`
        // build, the parent binary renders the bare `Gen` here.
        //
        // MEASURED UNDISCRIMINATED (round 807's rule: a pin that does not discriminate is
        // renamed to say what it actually tests). In the `diagnose()` harness this cell is
        // GREEN on the parent binary and GREEN under the a5 ablation that reverts J4: a
        // single-file corpus compile resolves the class's member table before the
        // constructor body is walked, so the read reaches `cmamEmitMissingProperty`, which
        // has always rendered the parameters, instead of the `ctorClassSym` fallback J4
        // fixes. The DISCRIMINATING pin for J4 is the JSDoc one below, which is the only
        // cell of the four that goes red under a5.
        val d = diagnose(
            """
            class Gen<T, V> {
                constructor() { this.inCtor; }
                m(): void { this.inMethod; }
            }
            """,
            ts,
        )
        assert(d.any {
            it.code == 2339 && it.line == 2 && it.character == 26 && it.length == 6 &&
                it.message == "Property 'inCtor' does not exist on type 'Gen<T, V>'."
        })
    }

    @Test
    fun `control - a generic class in a ts method context`() {
        // Control, green on both arms by construction: the method context already rendered
        // `Gen<T, V>` everywhere — it reaches cmamEmitMissingProperty rather than the
        // ctorClassSym fallback. It is what made the axis legible.
        val d = diagnose(
            """
            class Gen<T, V> {
                constructor() { this.inCtor; }
                m(): void { this.inMethod; }
            }
            """,
            ts,
        )
        assert(d.any {
            it.code == 2339 && it.line == 3 && it.character == 22 && it.length == 8 &&
                it.message == "Property 'inMethod' does not exist on type 'Gen<T, V>'."
        })
    }

    @Test
    fun `control - a non generic class still renders bare in a constructor context`() {
        // Control for the other direction: no type parameters, no angle brackets. Green on
        // both arms; kept because it is what refuses an unconditional `<>` suffix.
        val d = diagnose(
            """
            class Plain {
                constructor() { this.inCtorPlain; }
            }
            """,
            ts,
        )
        assert(d.any {
            it.code == 2339 && it.line == 2 && it.character == 26 && it.length == 11 &&
                it.message == "Property 'inCtorPlain' does not exist on type 'Plain'."
        })
    }

    @Test
    fun `a JSDoc template list renders as a js class's type parameters`() {
        // tsgo: b.js(8,14): Property 'p' does not exist on type 'Tpl<T, V>'. Both halves are
        // needed: J1 admits the access at all, J4 renders the list. This is
        // `unusedTypeParameters_templateTag2`'s remaining pair of rows.
        val d = diagnose(
            """
            // @Filename: /b.js
            /**
             * @template T
             * @template V
             */
            class Tpl {
                constructor() {
                    /** @type {T} */
                    this.p;
                }
            }
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/b.js" && it.line == 8 && it.character == 14 &&
                it.length == 1 &&
                it.message == "Property 'p' does not exist on type 'Tpl<T, V>'."
        })
    }

    // ------------------------------------------------------------------- negative controls

    @Test
    fun `negative control - an unchecked js file reports nothing`() {
        val d = diagnose(
            """
            // @Filename: /a.js
            class Base {
                constructor() { this.onBase = 1; }
            }
            class Der extends Base {
                read() { return this.nowhere; }
                sup() { return super.alsoNowhere; }
            }
            """,
            jsNoCheck,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `residue - an instance typed variable receiver stays silent`() {
        // (P18.138) RE-POINTED AND SPLIT. This pin used to assert that BOTH halves of the
        // step-25 residue stayed silent; (CHK.124) step 3 closed the OBJECT-LITERAL half,
        // so the two are now separate observables and each is re-measured against
        // `tools/tsgo-7.0.2/lib/tsc` rather than edited to what this compiler prints.
        //
        // tsgo, both rows: a.js(5,3) TS2339 'residueMissing' on 'Holder', and a.js(7,5)
        // TS2339 'residueLit' on '{}'. The SURVIVING residue is the first: an
        // instance-typed VARIABLE receiver needs a class decided by its TYPE rather than
        // syntactically, which neither step 25 nor step 3 supplies.
        val d = diagnose(
            """
            // @Filename: /a.js
            class Holder {
                constructor() { this.inCtor = 1; }
            }
            const h = new Holder();
            h.residueMissing;
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `the javascript object literal half of the step 25 residue now reports`() {
        // tsgo: a.js(7,5): error TS2339: Property 'residueLit' does not exist on type
        //       '{}'. Closed by (CHK.124) step 3 — an `{}`-initialized `const` in a
        // JavaScript file is an expando host whose member table this checker computes in
        // full, so the access is decidable. Byte-identical to tsgo, line and column.
        val d = diagnose(
            """
            // @Filename: /a.js
            class Holder {
                constructor() { this.inCtor = 1; }
            }
            const h = new Holder();
            h.residueMissing;
            const lit = {};
            lit.residueLit;
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/a.js" && it.line == 7 && it.character == 5 &&
                it.length == 10 &&
                it.message == "Property 'residueLit' does not exist on type '{}'."
        })
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `negative control - a ts class in the same program is unaffected`() {
        val d = diagnose(
            """
            // @Filename: /d.ts
            class TsCls { known: number = 1 }
            // @Filename: /a.js
            class JsCls {
                constructor() { this.jsKnown = 1; }
                r() { return this.jsKnown; }
            }
            """,
            js,
        )
        assert(d.none { it.code == 2339 })
    }
}
