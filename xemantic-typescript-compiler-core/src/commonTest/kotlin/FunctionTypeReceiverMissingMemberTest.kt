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
 * (P18.134) A MEMBER MISSING FROM A **SYMBOL-CARRYING FUNCTION TYPE** REACHED THROUGH A
 * PROPERTY-ACCESS RECEIVER.
 *
 * `cmamAllMissingTrustedMember`'s `Type.Object` arm refused every symbol-carrying type
 * with one line, and that line was the whole difference between two shapes that are
 * otherwise identical:
 *
 * ```ts
 * declare const o: { m: () => void };   // anonymous  -> symbol == null -> REPORTED
 * declare const o: { m: typeof g };     // `function g(){}` -> symbol   -> SILENT
 * ```
 *
 * Measured against `tools/tsgo-7.0.2/lib/tsc` at `target es2015 / lib esnext /
 * noImplicitAny`, the silent half lost a diagnostic in five shapes at once, among them
 * the corpus's own `contextualReturnTypeOfIIFE2` — `declare namespace app { function
 * foo(): void }` plus `app.foo.bar` — whose two TS2339 rows are the TypeScript 6 -> 7
 * change the round closes: tsgo's BINDER declares expando properties onto the host
 * symbol, and a **namespace-qualified head declares at no hop**, so `app.foo.bar = …` is
 * an error rather than a declaration.
 *
 * ### What the class is for
 *
 * The positive rows are the smaller half. This model has NO expando member synthesis —
 * `function g(){} g.px = 1` leaves `typeof g` the bare signature where tsgo gives it
 * `px` — so trusting the absence of a member on a function type is unsound exactly where
 * the function IS an expando host, and the NEGATIVE controls are what separate a correct
 * relaxation from an over-reaching one. Every cell below was measured against tsgo
 * before it was written; the guard's four false-positive cells (§ 3) were produced by
 * an unguarded build and are the reason the guard is a guard and not a precaution.
 */
class FunctionTypeReceiverMissingMemberTest {

    private fun t2339(d: List<Diagnostic>) = d.filter { it.code == 2339 }

    /** The fixture's own directives — the corpus case sets exactly these three. */
    private val iifeDirectives = "// @target: es2015\n// @lib: esnext\n// @noImplicitAny: true"

    // --- 1. the rows that must now be REPORTED ------------------------------

    /**
     * `contextualReturnTypeOfIIFE2` itself, with the case file's own directives. tsgo
     * 7.0.2 reports both rows; TypeScript 6 reported neither.
     */
    @Test
    fun `a namespace-qualified function head reports at every write and read`() {
        val d = diagnose(
            """
            declare namespace app {
              function foo(): void;
            }

            app.foo.bar = (function () {
              const someFun = (arg: number) => {};
              return { someFun };
            })();

            app.foo.bar.someFun(1);
            """,
            directives = iifeDirectives,
        )
        val rows = t2339(d).map { it.message }
        assert(
            rows == listOf(
                "Property 'bar' does not exist on type '() => void'.",
                "Property 'bar' does not exist on type '() => void'.",
            )
        )
    }

    @Test
    fun `an ambient namespace function reports a missing member at a WRITE`() {
        val d = diagnose(
            """
            declare namespace ZzzA { function zzzFoo(): void }
            ZzzA.zzzFoo.zzzBar = 1;
            """
        )
        assert(
            t2339(d).single().message ==
                "Property 'zzzBar' does not exist on type '() => void'."
        )
    }

    @Test
    fun `an ambient namespace function reports a missing member at a READ`() {
        val d = diagnose(
            """
            declare namespace ZzzA { function zzzFoo(): void }
            const zzzR = ZzzA.zzzFoo.zzzBar;
            """
        )
        assert(
            t2339(d).single().message ==
                "Property 'zzzBar' does not exist on type '() => void'."
        )
    }

    @Test
    fun `a DOTTED ambient namespace function reports a missing member`() {
        val d = diagnose(
            """
            declare namespace ZzzA.ZzzB { function zzzFoo(): void }
            ZzzA.ZzzB.zzzFoo.zzzBar;
            """
        )
        assert(
            t2339(d).single().message ==
                "Property 'zzzBar' does not exist on type '() => void'."
        )
    }

    @Test
    fun `a CONCRETE namespace function reports a missing member`() {
        val d = diagnose(
            """
            namespace ZzzA { export function zzzFoo(): void {} }
            ZzzA.zzzFoo.zzzBar = 1;
            """
        )
        assert(
            t2339(d).single().message ==
                "Property 'zzzBar' does not exist on type '() => void'."
        )
    }

    /**
     * The minimal shape of the whole round: a `typeof <function declaration>` member on
     * an object type. Compare the anonymous twin below — before this round the ONLY
     * difference between them was the symbol riding on the type.
     */
    @Test
    fun `a typeof-function member of an object type reports a missing member`() {
        val d = diagnose(
            """
            function zzzG(): void {}
            declare const zzzO: { zzzM: typeof zzzG };
            zzzO.zzzM.zzzBar;
            """
        )
        assert(
            t2339(d).single().message ==
                "Property 'zzzBar' does not exist on type '() => void'."
        )
    }

    /** The anonymous twin, which has reported all along — the control for the pair. */
    @Test
    fun `an ANONYMOUS function-typed member of an object type still reports`() {
        val d = diagnose(
            """
            declare const zzzO: { zzzM: () => void };
            zzzO.zzzM.zzzBar;
            """
        )
        assert(
            t2339(d).single().message ==
                "Property 'zzzBar' does not exist on type '() => void'."
        )
    }

    /** The display is the type engine's, so a GENERIC signature comes out whole. */
    @Test
    fun `a GENERIC namespace function is named by its full signature`() {
        val d = diagnose(
            """
            declare namespace ZzzA { function zzzFoo<T>(zzzX: T): T }
            ZzzA.zzzFoo.zzzBar;
            """
        )
        assert(
            t2339(d).single().message ==
                "Property 'zzzBar' does not exist on type '<T>(zzzX: T) => T'."
        )
    }

    /** An OVERLOAD SET renders as a call-signature bag, exactly as tsgo renders it. */
    @Test
    fun `an OVERLOADED namespace function is named by its signature bag`() {
        val d = diagnose(
            """
            declare namespace ZzzA {
              function zzzFoo(zzzX: string): void;
              function zzzFoo(zzzX: number): void;
            }
            ZzzA.zzzFoo.zzzBar;
            """
        )
        assert(
            t2339(d).single().message ==
                "Property 'zzzBar' does not exist on type " +
                "'{ (zzzX: string): void; (zzzX: number): void; }'."
        )
    }

    // --- 2. the negative controls the brief names ---------------------------
    //
    // Every one of these is SILENT in tsgo 7.0.2 as well. They are the population the
    // relaxation must not reach: a receiver that is a bare IDENTIFIER never enters
    // `cmamCheckNestedObjectReceiver` at all, which is what keeps the whole
    // const-bound-function-expression family — for which this model has no expando
    // model either — correctly unchecked.

    @Test
    fun `negative control - an ambient function declaration written directly is silent`() {
        val d = diagnose(
            """
            declare function zzzF(): void;
            zzzF.zzzBar = 1;
            """
        )
        assert(t2339(d).isEmpty())
    }

    @Test
    fun `negative control - a concrete function declaration written directly is silent`() {
        val d = diagnose(
            """
            function zzzF(): void {}
            zzzF.zzzBar = 1;
            """
        )
        assert(t2339(d).isEmpty())
    }

    @Test
    fun `negative control - a const function-expression receiver is silent`() {
        val d = diagnose(
            """
            const zzzF = function (): void {};
            zzzF.zzzBar = 1;
            """
        )
        assert(t2339(d).isEmpty())
    }

    @Test
    fun `negative control - a const arrow receiver is silent`() {
        val d = diagnose(
            """
            const zzzF = (): void => {};
            zzzF.zzzBar = 1;
            """
        )
        assert(t2339(d).isEmpty())
    }

    @Test
    fun `negative control - a declared expando member reads back without a row`() {
        val d = diagnose(
            """
            function zzzF() {}
            zzzF.zzzBar = 1;
            const zzzR = zzzF.zzzBar;
            """
        )
        assert(t2339(d).isEmpty())
    }

    /**
     * `contextualReturnTypeOfIIFE3` in miniature — an ACTIVE corpus baseline. The member
     * is DECLARED, so the whole chain resolves and nothing may be reported.
     */
    @Test
    fun `negative control - a DECLARED member of a namespace var is silent`() {
        val d = diagnose(
            """
            declare namespace ZzzApp {
              var zzzFoo: { zzzBar: { zzzSomeFun: (zzzArg: number) => void } };
            }
            ZzzApp.zzzFoo.zzzBar = (function () {
              return { zzzSomeFun(zzzArg: number) {} };
            })();
            ZzzApp.zzzFoo.zzzBar.zzzSomeFun(1);
            """,
            directives = iifeDirectives,
        )
        assert(t2339(d).isEmpty())
    }

    /** `call`/`name` are `RUNTIME_PROPERTIES`; tsgo resolves them on `Function`. */
    @Test
    fun `negative control - a Function runtime member of a function type is silent`() {
        val d = diagnose(
            """
            declare const zzzO: { zzzM: () => void };
            zzzO.zzzM.call;
            zzzO.zzzM.name;
            """
        )
        assert(t2339(d).isEmpty())
    }

    // --- 3. THE EXPANDO GUARD, measured ------------------------------------
    //
    // Each of the four below emitted `Property 'zzzPx' does not exist on type '() =>
    // void'.` on a build WITHOUT `cmamExpandoDeclaringHost`, and tsgo says the property
    // EXISTS in every one (it answers TS2565 *used before being assigned*, or nothing).
    // They are the guard's attributable cells — round 902's dead-arm law — and they are
    // the reason the relaxation is not simply `m.symbol != null -> true`.

    @Test
    fun `an expando host declared at file scope refuses the trust gate`() {
        val d = diagnose(
            """
            function zzzG(): void {}
            zzzG.zzzPx = 1;
            declare const zzzO: { zzzM: typeof zzzG };
            zzzO.zzzM.zzzPx;
            """
        )
        assert(t2339(d).isEmpty())
    }

    @Test
    fun `an expando written inside a file-scope block refuses the trust gate`() {
        val d = diagnose(
            """
            function zzzG(): void {}
            if (1) { zzzG.zzzPx = 1; }
            declare const zzzO: { zzzM: typeof zzzG };
            zzzO.zzzM.zzzPx;
            """
        )
        assert(t2339(d).isEmpty())
    }

    @Test
    fun `an expando written through a string element access refuses the trust gate`() {
        val d = diagnose(
            """
            function zzzG(): void {}
            zzzG["zzzPx"] = 1;
            declare const zzzO: { zzzM: typeof zzzG };
            zzzO.zzzM.zzzPx;
            """
        )
        assert(t2339(d).isEmpty())
    }

    /**
     * The scan is scoped to the declaration's CONTAINER and not to its FILE: this cell
     * was a false positive while the guard walked `SourceFile.statements`, because the
     * declaring write lives in the enclosing `ModuleBlock`.
     */
    @Test
    fun `an expando written inside the enclosing namespace body refuses the trust gate`() {
        val d = diagnose(
            """
            namespace ZzzA {
              export function zzzFoo(): void {}
              zzzFoo.zzzPx = 1;
            }
            ZzzA.zzzFoo.zzzPx;
            """
        )
        assert(t2339(d).isEmpty())
    }

    /**
     * The guard is B431's OWN rule ([collectExpandoDecls]), reused so the two routes
     * cannot drift — and that rule is tsgo's: a write inside a NESTED FUNCTION declares
     * nothing, so both rows here are correct and a broader "any write anywhere" guard
     * would LOSE them. The first row is B431's own (a bare-identifier receiver), the
     * second is this round's.
     */
    @Test
    fun `a write inside a NESTED function declares nothing and both rows survive`() {
        val d = diagnose(
            """
            function zzzG(): void {}
            function zzzWrapper(): void { zzzG.zzzPx = 1; }
            declare const zzzO: { zzzM: typeof zzzG };
            zzzO.zzzM.zzzPx;
            """
        )
        val rows = t2339(d).map { it.message }
        assert(
            rows == listOf(
                "Property 'zzzPx' does not exist on type '() => void'.",
                "Property 'zzzPx' does not exist on type '() => void'.",
            )
        )
    }

    /**
     * A NAMESPACE-QUALIFIED head declares at no hop — the mechanism behind the target
     * baseline — so a write through one leaves the member missing and BOTH positions
     * report, exactly as tsgo does.
     */
    @Test
    fun `a write through a namespace-qualified head declares nothing`() {
        val d = diagnose(
            """
            namespace ZzzA { export function zzzFoo(): void {} }
            ZzzA.zzzFoo.zzzPx = 1;
            ZzzA.zzzFoo.zzzPx;
            """
        )
        val rows = t2339(d).map { it.message }
        assert(
            rows == listOf(
                "Property 'zzzPx' does not exist on type '() => void'.",
                "Property 'zzzPx' does not exist on type '() => void'.",
            )
        )
    }

    // --- 4. the syntactic pre-gate's deliberate refusals --------------------
    //
    // Both are LOST rows rather than false positives — tsgo reports `typeof C` and
    // `typeof zzzFoo` — and widening to either needs a MEMBER MODEL, not a wider gate:
    // a class's statics and a merged namespace's exports live on tables this trust
    // predicate is not reading. They are pinned so the refusal is a recorded decision.

    @Test
    fun `residue - a class static side is refused by the function-declaration pre-gate`() {
        val d = diagnose(
            """
            class ZzzC {}
            declare const zzzO: { zzzM: typeof ZzzC };
            zzzO.zzzM.zzzBar;
            """
        )
        assert(t2339(d).isEmpty())
    }

    @Test
    fun `residue - a function merged with a namespace is refused by the pre-gate`() {
        val d = diagnose(
            """
            declare namespace ZzzA {
              function zzzFoo(): void;
              namespace zzzFoo { const zzzPx: number }
            }
            ZzzA.zzzFoo.zzzBar;
            """
        )
        assert(t2339(d).isEmpty())
    }
}
