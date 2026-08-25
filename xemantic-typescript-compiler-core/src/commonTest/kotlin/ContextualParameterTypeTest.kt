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
 * (CHK.39) A contextually-typed parameter carries a TYPE, not merely an ARITY.
 *
 * Before this, `spineIanyFnExprEnter` / `spineIanyObjLitMethodEnter` decided
 * TS7006 from the contextual signature's parameter COUNT (B224) and nothing
 * entered the parameter into the scope the assignability walkers read — so a
 * covered parameter went quiet AND stayed `any`, and a wrong-typed use of it was
 * reported nowhere. Every expectation below is tsc 7.0.2's, measured with
 * `tools/tsgo-7.0.2/lib/tsc --noEmit` on the same source.
 *
 * **THE PINS MUST BE ABOUT WHAT APPEARS.** A "the TS7006 went quiet" pin passes
 * against a binary that merely disabled the diagnostic, which is the whole reason
 * (CHK.30) shipped a false-POSITIVE fix with this false NEGATIVE still behind it.
 *
 * KNOWN GAP, deliberately pinned as such below: an object-literal METHOD's body
 * is not walked by the assignability walker at all in a `.ts` file
 * (`walkFunctionBodiesInExpr`'s `MethodDeclaration` arm is `if (jsLike)`), so the
 * parameter is typed — a hover on it answers the contextual type, see
 * `ProjectContextualParamHoverTest` — and no diagnostic inside that body can
 * fire. That is a SECOND defect, queued separately; typing the parameter is a
 * precondition for fixing it, not a fix for it.
 */
class ContextualParameterTypeTest {

    private val prelude = """
        interface N { kind: number }
        interface V { m(node: N): void }
        interface P { m: (n: N) => void }
        declare function take(f: (n: N) => void): void;
    """.trimIndent() + "\n"

    // --- the positive half: a wrong-typed USE of the parameter must REPORT -------

    @Test
    fun `a CALL-ARGUMENT arrow's parameter is typed by the callee's signature`() {
        val d = diagnose(prelude + "take((node) => { const bad: string = node.kind; });")
        assert(d.count { it.code == 2322 } == 1)
    }

    @Test
    fun `a CALL-ARGUMENT function expression's parameter is typed by the callee's signature`() {
        val d = diagnose(prelude + "take(function (node) { const bad: string = node.kind; });")
        assert(d.count { it.code == 2322 } == 1)
    }

    @Test
    fun `a PROPERTY-ASSIGNMENT arrow's parameter is typed by the member's function type`() {
        val d = diagnose(prelude + "const q: P = { m: (node) => { const bad: string = node.kind; } };")
        assert(d.count { it.code == 2322 } == 1)
    }

    @Test
    fun `a member's function type reached through an interface METHOD signature types it too`() {
        val d = diagnose(prelude + "const q: V = { m: (node) => { const bad: string = node.kind; } };")
        assert(d.count { it.code == 2322 } == 1)
    }

    @Test
    fun `an ANNOTATED variable's arrow parameter is typed by the annotation`() {
        val d = diagnose(prelude + "const q: (n: N) => void = (node) => { const bad: string = node.kind; };")
        assert(d.count { it.code == 2322 } == 1)
    }

    /**
     * A GENERIC callee: the contextual parameter type only exists after the
     * call's own type arguments are inferred, which is why the pull goes through
     * `cpaComputeArgCtxTypes` (the inference-aware resolver) rather than the
     * cheaper declaration-based `calleeDeclaredCtxParams`.
     */
    @Test
    fun `a GENERIC callee's inferred contextual parameter type reaches the body`() {
        val d = diagnose(
            prelude +
                "declare function each<T>(xs: T[], cb: (x: T) => void): void;\n" +
                "declare const ns: N[];\n" +
                "each(ns, (item) => { const bad: string = item.kind; });"
        )
        assert(d.count { it.code == 2322 } == 1)
    }

    // --- the PROPERTY-ACCESS family, which reads its context separately ----------

    /**
     * KNOWN GAP, and a MEASURED refusal rather than an oversight — **the two
     * walkers disagree about one caret and the fix is blocked on a THIRD
     * defect.**
     *
     * The assignability family has read a declaration's ANNOTATION as a
     * contextual type since round 462; the property-access family never has —
     * its spine anchor walks `decl.initializer` with no context at all — so in
     * `const a: (n: N) => void = (node) => { node.nope }` the TS2322 family sees
     * `node: N` and the TS2339 family sees `any`, and tsc reports the TS2339.
     *
     * The one-line fix was written and REVERTED. It gives exactly the rows tsc
     * gives on every fixture, and all 8 dashboard profiles stay `added=0
     * removed=0` — and it costs **+15 false positives on knip** (66 -> 79), every
     * one of them a parameter whose contextual type is a UNION that the body
     * then narrows by assignment (`if (typeof localConfig === 'function')
     * localConfig = localConfig()`, then `localConfig.files`). tsgo is silent at
     * every one. The property-access family has no assignment/`typeof`
     * narrowing for a parameter, so handing it a union contextual type
     * manufactures TS2339; that narrowing is the blocker, not the contextual
     * type. Recorded as the current answer so the round that fixes it sees these
     * go red rather than rediscovering the whole chain.
     */
    @Test
    fun `KNOWN GAP - an annotated variable's arrow parameter is NOT typed for the property-access family`() {
        val d = diagnose(prelude + "const q: (n: N) => void = (node) => { node.nope; };")
        assert(d.none { it.code == 2339 })
    }

    /** …and the same for an object-literal METHOD's body, which that walker does
     *  not walk at all (`cpaExprObjectLiteral`'s `else`). The ASSIGNABILITY half
     *  of the same body IS walked, since (CHK.39b) — that asymmetry is the whole
     *  point of pinning it. */
    @Test
    fun `KNOWN GAP - an object-literal method body is not property-access-walked`() {
        val d = diagnose(prelude + "const q: V = { m(node) { node.nope; } };")
        assert(d.none { it.code == 2339 })
    }

    /** The call-argument path DOES reach the property-access family, and has
     *  since B81.1 — the control that says the two gaps above are about the
     *  contextual SOURCE and not about the walker being unable to report. */
    @Test
    fun `a CALL-ARGUMENT arrow's parameter is typed for the property-access family`() {
        val d = diagnose(prelude + "take((node) => { node.nope; });")
        assert(d.count { it.code == 2339 } == 1)
    }

    // --- the negative half ------------------------------------------------------

    /** A CORRECT use of the same parameter stays silent — the pin above must not
     *  be satisfiable by typing the parameter as anything at all. */
    @Test
    fun `negative control - a correctly typed use of the parameter is silent`() {
        val d = diagnose(prelude + "take((node) => { const good: number = node.kind; });")
        assert(d.none { it.code == 2322 })
    }

    /** Nothing was disabled: a parameter with NO contextual type still reports
     *  TS7006 under `noImplicitAny`. */
    @Test
    fun `negative control - an uncontextualised parameter still reports TS7006`() {
        val d = diagnose(prelude + "function free(loose) { return loose; }")
        assert(d.count { it.code == 7006 } == 1)
    }

    /**
     * An ANNOTATION on the parameter OUTRANKS the contextual type — the pull runs
     * after `ctaTypeParamsIntoLocals` and writes only where there is no
     * annotation, so a deliberately wrong annotation must still be what the body
     * is checked against (here `node.kind` does not exist on `string`).
     */
    @Test
    fun `an explicit parameter annotation still wins over the contextual type`() {
        val d = diagnose(prelude + "take((node: any) => { const ok: string = node.kind; });")
        assert(d.none { it.code == 2322 })
    }

    /**
     * BEYOND the contextual signature's arity there is no contextual type, so the
     * B224 arity rule still owns the parameter and TS7006 still fires — the pin
     * that separates "typed the covered parameters" from "typed everything".
     */
    @Test
    fun `a parameter beyond the contextual arity is still an implicit any`() {
        val d = diagnose(prelude + "take((node, extra) => { const good: number = node.kind; });")
        assert(d.count { it.code == 7006 } == 1)
        assert(d.none { it.code == 2322 })
    }

    /**
     * AN OPTIONAL CONTEXTUAL PARAMETER IS `T | undefined` INSIDE THE BODY.
     *
     * This is the round's one measured FALSE POSITIVE, and it reached three
     * dashboard profiles before the rule was added: `findAllReferences.ts`
     * assigns `base = undefined` to a callback parameter the signature declares
     * `baseSymbol?: Symbol`, which is legal and which the bare `Symbol` rejects.
     * Both other contextual-typing sites in this checker already carried B85.1a;
     * a new one has to be written with it.
     */
    @Test
    fun `an OPTIONAL contextual parameter accepts undefined`() {
        val d = diagnose(
            prelude +
                "declare function pair(cb: (a: N, b?: N) => void): void;\n" +
                "pair((first, second) => { second = undefined; const bad: string = first.kind; });"
        )
        assert(d.count { it.code == 2322 } == 1)
    }

    /** …and it is still TYPED: a wrong-typed use of the optional parameter
     *  reports, so the rule above cannot be satisfied by leaving it `any`. */
    @Test
    fun `an OPTIONAL contextual parameter is still typed`() {
        val d = diagnose(
            prelude +
                "declare function pair(cb: (a: N, b?: N) => void): void;\n" +
                "pair((first, second) => { const bad: string = second!.kind; });"
        )
        assert(d.count { it.code == 2322 } == 1)
    }

    /**
     * (CHK.39b) An object-literal METHOD's body is CHECKED at all.
     *
     * The second defect this round found: `walkFunctionBodiesInExpr`'s
     * `MethodDeclaration` arm was `if (jsLike)`, and that gate is about whether
     * `this` is the object-literal type (which in TypeScript it is not — TS2683)
     * — it was silently deciding whether the body reaches the assignability
     * walker AT ALL. The spine's own anchor runs `recordOnly` inside a function
     * body, so nothing else emitted there: every statement in every
     * `{ m(node) {…} }` in every `.ts` file was unchecked.
     */
    @Test
    fun `an object-literal METHOD's body is checked, and its parameter is typed`() {
        val d = diagnose(prelude + "const q: V = { m(node) { const bad: string = node.kind; } };")
        assert(d.count { it.code == 2322 } == 1)
    }

    /** …through a member declared as a function-typed PROPERTY too. */
    @Test
    fun `an object-literal METHOD against a function-typed member is checked`() {
        val d = diagnose(prelude + "const q: P = { m(node) { const bad: string = node.kind; } };")
        assert(d.count { it.code == 2322 } == 1)
    }

    /** NEGATIVE CONTROL for the same arm: an object-literal method whose body is
     *  correct stays silent — the arm must CHECK the body, not condemn it. */
    @Test
    fun `negative control - a correct object-literal method body is silent`() {
        val d = diagnose(prelude + "const q: V = { m(node) { const good: number = node.kind; } };")
        assert(d.none { it.code == 2322 })
    }
}
