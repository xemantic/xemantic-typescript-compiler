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
 * (CHK.98) A contextually-typed parameter reaches the ARGUMENT and
 * PROPERTY-ACCESS readers, not only the assignability one.
 *
 * (CHK.39) gave a callback's parameters a TYPE for the assignability reader and
 * for hover. Two readers were left behind, for two different reasons, and both
 * are silent rather than wrong — which is why a green suite could not see either.
 *
 * * The **ccet frame** wrote every own parameter `anyType` — round 475's SHADOW,
 *   "do not let this name resolve to an enclosing binding", read by the one
 *   reader that owns call ARGUMENTS as a claim that the parameter IS `any`.
 *   Measured on the parent binary: an explicitly **ANNOTATED** arrow parameter
 *   was just as untyped there (`takeU((x: string | number) => takeS(x))` is
 *   silent), so the gap was never contextual typing alone — that frame simply
 *   never ran the ordinary parameter registrar, where the class-method and
 *   constructor frames beside it always have.
 * * The **property-access family** had no edge for three contextual SOURCES: a
 *   variable declaration's annotation, a class property's, and an object-literal
 *   METHOD (whose body it did not walk at all).
 *
 * Every expectation below is BOTH references' — `tools/tsgo-7.0.2/lib/tsc` and
 * pristine `typescript@6.0.3` agree on all 26 rows of the fixture set this class
 * is transcribed from.
 */
class ContextualParamReadersTest {

    private val prelude = """
        interface N { kind: number }
        interface V { m(node: N): void }
        declare function take(f: (n: N) => void): void;
        declare function takeS(s: string): void;
        declare function takeU(cb: (x: string | number) => void): void;
        declare function cfg(o: { onX: (n: N) => void }): void;
    """.trimIndent() + "\n"

    // --- (a) the ccet ARGUMENT reader --------------------------------------------

    @Test
    fun `an arrow argument's parameter reaches the argument reader`() {
        val d = diagnose(prelude + "take(n => { takeS(n); });")
        assert(d.count { it.code == 2345 } == 1)
    }

    @Test
    fun `a function expression argument's parameter reaches the argument reader`() {
        val d = diagnose(prelude + "take(function (n) { takeS(n); });")
        assert(d.count { it.code == 2345 } == 1)
    }

    /** The object-literal METHOD member — the SECOND `anyType` site, whose frame
     *  carried `this` and nothing at all about its own parameters. */
    @Test
    fun `an object-literal method's parameter reaches the argument reader`() {
        val d = diagnose(prelude + "cfg({ onX(n) { takeS(n); } });")
        assert(d.count { it.code == 2345 } == 1)
    }

    @Test
    fun `an object-literal property arrow's parameter reaches the argument reader`() {
        val d = diagnose(prelude + "cfg({ onX: n => { takeS(n); } });")
        assert(d.count { it.code == 2345 } == 1)
    }

    /** A NESTED arrow inside a callback — two frames deep, and both parameters
     *  must be typed at the same reader. */
    @Test
    fun `a nested arrow inside a callback types both parameters at the argument reader`() {
        val d = diagnose(prelude + "take(n => { [1].forEach(i => { takeS(n); takeS(i); }); });")
        assert(d.count { it.code == 2345 } == 2)
    }

    /**
     * The measured finding that reframed (a): an ANNOTATED arrow parameter was
     * untyped at this reader too, so what was missing is the ordinary registrar
     * and not only the contextual pull. This is the pin that says so — it needs
     * no contextual typing at all.
     */
    @Test
    fun `an ANNOTATED arrow parameter reaches the argument reader`() {
        val d = diagnose(prelude + "takeU((x: string | number) => { takeS(x); });")
        assert(d.count { it.code == 2345 } == 1)
    }

    // --- (a) the negative half ---------------------------------------------------

    /** A CORRECT argument stays silent — the pins above must not be satisfiable
     *  by typing the parameter as anything at all. */
    @Test
    fun `negative control - a correct argument stays silent`() {
        val d = diagnose(prelude + "take(n => { takeS(String(n.kind)); });")
        assert(d.none { it.code == 2345 })
    }

    /**
     * THE PRE-CHECK the queue item mandated. A union parameter narrowed by
     * `typeof` and passed as an argument in the THEN branch must stay silent —
     * handing the argument reader a union it cannot narrow would manufacture a
     * false positive at every idiomatic `typeof` guard in a callback. Both
     * references are silent here.
     */
    @Test
    fun `negative control - a typeof-narrowed union parameter stays silent in the then branch`() {
        val d = diagnose(
            prelude + """takeU(x => { if (typeof x === "string") { takeS(x); } });"""
        )
        assert(d.none { it.code == 2345 })
    }

    // --- (b) the PROPERTY-ACCESS readers ------------------------------------------

    /** Flips `ContextualParameterTypeTest`'s first KNOWN-GAP pin. */
    @Test
    fun `a variable annotation types its arrow's parameter for the property-access family`() {
        val d = diagnose(prelude + "const h: (n: N) => void = n => { n.nope; };")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a variable annotation types its function expression's parameter for the property-access family`() {
        val d = diagnose(prelude + "const h2: (n: N) => void = function (n) { n.nope; };")
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `a class property annotation types its arrow's parameter for the property-access family`() {
        val d = diagnose(prelude + "class C { p: (n: N) => void = n => { n.nope; }; }")
        assert(d.count { it.code == 2339 } == 1)
    }

    /** Flips `ContextualParameterTypeTest`'s second KNOWN-GAP pin — that body was
     *  not walked by this family at all (`cpaExprObjectLiteral`'s `else`). */
    @Test
    fun `an object-literal method body is property-access-walked under a variable annotation`() {
        val d = diagnose(prelude + "const q: V = { m(node) { node.nope; } };")
        assert(d.count { it.code == 2339 } == 1)
    }

    /**
     * KNOWN GAP, and a MEASURED scope rather than an oversight. The
     * `MethodDeclaration` arm is gated to an ANNOTATION-sourced context: opened
     * for every object literal in the program it walks a very large new
     * population and costs **one false positive on three of the eight profiles**
     * — `src/services/refactors/inlineVariable.ts:102`, an `info` narrowed by a
     * NEGATED namespace-qualified type-guard call inside `registerRefactor({ … })`,
     * where that narrowing does not reach this family. The row is a PRE-EXISTING
     * gap the walk merely made reachable, not one this item introduces; the
     * ASSIGNABILITY half of the same body has been walked since (CHK.39b).
     */
    @Test
    fun `KNOWN GAP - an object-literal method body under a CALL ARGUMENT is not property-access-walked`() {
        val d = diagnose(prelude + "cfg({ onX(node) { node.nope; } });")
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `negative control - a correct member read under an annotation stays silent`() {
        val d = diagnose(prelude + "const h3: (n: N) => void = n => { const k: number = n.kind; };")
        assert(d.none { it.code == 2339 })
    }

    /**
     * THE UNION GATE. The property-access family has narrowing gaps a call
     * argument's contextual types happen not to reach; until (CHK.98b) lands, an
     * annotation-sourced context refuses a UNION contextual parameter type rather
     * than manufacturing TS2339 on a body that narrows it. Both references
     * REPORT here — this pin records a deliberate false NEGATIVE, and goes red on
     * the day the gate lifts.
     */
    @Test
    fun `KNOWN GAP - an annotation-sourced UNION contextual parameter type is refused`() {
        val d = diagnose(
            prelude + "type UF = (u: N | { other: string }) => void;\n" +
                "const hu: UF = u => { u.nope; };"
        )
        assert(d.none { it.code == 2339 })
    }

    // --- (c) the pull's exact arms ------------------------------------------------

    @Test
    fun `a conditional expression passes its contextual type to both branches`() {
        val d = diagnose(
            prelude + "declare const c: boolean;\n" +
                "take(c ? (n => { const w: boolean = n; }) : (n => { const v: boolean = n.kind; }));"
        )
        assert(d.count { it.code == 2322 } == 2)
    }

    @Test
    fun `an as-expression's asserted type contextually types its operand`() {
        val d = diagnose(prelude + "const g2 = (n => { const w: boolean = n; }) as (n: N) => void;")
        assert(d.count { it.code == 2322 } == 1)
    }

    @Test
    fun `a satisfies-expression's type contextually types its operand`() {
        val d = diagnose(
            prelude + "const g3 = ((n) => { const w: boolean = n; }) satisfies (n: N) => void;"
        )
        assert(d.count { it.code == 2322 } == 1)
    }

    @Test
    fun `an assignment's left-hand type contextually types its right-hand arrow`() {
        val d = diagnose(
            prelude + "declare const o2: { cb: (n: N) => void };\n" +
                "o2.cb = n => { const w: boolean = n; };"
        )
        assert(d.count { it.code == 2322 } == 1)
    }

    @Test
    fun `a REST parameter takes the contextual signature's own rest type`() {
        val d = diagnose(
            prelude + "declare function takeR(cb: (...xs: number[]) => void): void;\n" +
                "takeR((...xs) => { const w: boolean = xs; });"
        )
        assert(d.count { it.code == 2322 } == 1)
    }

    @Test
    fun `a REST parameter after a fixed one takes the aligned rest type`() {
        val d = diagnose(
            prelude + "declare function takeR2(cb: (a: string, ...xs: number[]) => void): void;\n" +
                "takeR2((a, ...rest) => { const w: boolean = a; const v: boolean = rest; });"
        )
        assert(d.count { it.code == 2322 } == 2)
    }

    @Test
    fun `a contextual signature's this parameter types this inside a function expression`() {
        val d = diagnose(
            prelude + "declare function takeT(cb: (this: N, x: number) => void): void;\n" +
                "takeT(function (x) { const w: boolean = this; const v: boolean = x; });"
        )
        assert(d.count { it.code == 2322 } == 2)
    }

    /**
     * The ARRAY-LITERAL edge, which was an ours-only FALSE TS7006: the CALL
     * ARGUMENT edge records `typed = true` with no TYPE (round 799 keeps the
     * callee resolution off that hot path), the array arm clears `typed` for a
     * function element and finds nothing to put in its place, and the arrow ended
     * with no context at all — while the assignability half typed its parameter
     * through (CHK.40)(a)'s pull.
     */
    @Test
    fun `an arrow inside a contextually-typed array literal argument is not implicitly any`() {
        val d = diagnose(
            prelude + "declare function many(cbs: ((x: number) => void)[]): void;\n" +
                "many([x => { }]);"
        )
        assert(d.none { it.code == 7006 })
    }

    /** …and it is TYPED, not merely covered — the (CHK.30) rule that a fix which
     *  only makes TS7006 go away has probably typed nothing. */
    @Test
    fun `an arrow inside a contextually-typed array literal argument is TYPED`() {
        val d = diagnose(
            prelude + "declare function many(cbs: ((x: number) => void)[]): void;\n" +
                "many([x => { const w: boolean = x; }]);"
        )
        assert(d.count { it.code == 2322 } == 1)
    }

    /**
     * A SPREAD argument's count is not knowable from `arguments.size`, and the
     * arity check now bails rather than counting it as one. Both references are
     * silent; before the guard this was `Expected 2 arguments, but got 1`, and it
     * became reachable only once (CHK.98)(a) gave the RECEIVER a type at all (the
     * corpus's `bindingPatternCannotBeOnlyInferenceSource`).
     */
    @Test
    fun `a spread argument does not report a too-few-arguments row`() {
        val d = diagnose(
            "interface F { two(a: number, b: number): void }\n" +
                "declare function useF(cb: (f: F, p: number[]) => void): void;\n" +
                "useF((f, p) => { f.two(...p); });"
        )
        assert(d.none { it.code == 2554 })
    }

    // --- (CHK.98c) the optional-chain `typeof` narrow, (b)'s prerequisite ----------

    /**
     * `typeof c.g?.r === "string"` proves `c.g` non-nullish: a nullish link
     * short-circuits the WHOLE chain to `undefined`, whose `typeof` is
     * `"undefined"`. PRE-EXISTING on the parent binary — it fired for a plain
     * function-declaration parameter as much as for a contextually-typed one —
     * and it is what (b) would otherwise have made reachable from two more
     * sources (knip's release-it plugin, two rows).
     */
    @Test
    fun `an optional-chain typeof guard narrows every link non-nullish`() {
        val d = diagnose(
            "type G = { g?: { r?: string | null } };\n" +
                "function f1(c: G) { if (typeof c.g?.r === 'string') { c.g.r; } }"
        )
        assert(d.none { it.code == 18048 })
    }

    /** …the same through a contextually-typed callback parameter. */
    @Test
    fun `an optional-chain typeof guard narrows inside a contextually-typed callback`() {
        val d = diagnose(
            "type G = { g?: { r?: string | null } };\n" +
                "declare function useG(cb: (c: G) => void): void;\n" +
                "useG(c => { if (typeof c.g?.r === 'string') { c.g.r; } });"
        )
        assert(d.none { it.code == 18048 })
    }

    /** `"undefined"` proves the OPPOSITE — that tag is exactly what a
     *  short-circuited chain answers. */
    @Test
    fun `negative control - a typeof undefined guard does not narrow the chain`() {
        val d = diagnose(
            "type G = { g?: { r?: string | null } };\n" +
                "function f2(c: G) { if (typeof c.g?.r === 'undefined') { c.g.r; } }"
        )
        assert(d.count { it.code == 18048 } == 1)
    }

    /** The NEGATED branch proves nothing: `typeof c.g?.r !== 'string'` is true
     *  when the chain short-circuited. */
    @Test
    fun `negative control - the negated branch of an optional-chain typeof does not narrow`() {
        val d = diagnose(
            "type G = { g?: { r?: string | null } };\n" +
                "function f3(c: G) { if (typeof c.g?.r !== 'string') { c.g.r; } }"
        )
        assert(d.count { it.code == 18048 } == 1)
    }

    /** …and its ELSE branch DOES narrow, which is the same rule with the polarity
     *  the walker hands it. */
    @Test
    fun `the else branch of a negated optional-chain typeof narrows`() {
        val d = diagnose(
            "type G = { g?: { r?: string | null } };\n" +
                "function f3b(c: G) { if (typeof c.g?.r !== 'string') { } else { c.g.r; } }"
        )
        assert(d.none { it.code == 18048 })
    }

    /** No guard at all — a genuine TS18048 both references report. */
    @Test
    fun `negative control - an unguarded optional member still reports`() {
        val d = diagnose(
            "type G = { g?: { r?: string | null } };\n" +
                "function f4(c: G) { c.g.r; }"
        )
        assert(d.count { it.code == 18048 } == 1)
    }
}
