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
     * CLOSED by (CHK.98)(b) — this pin was a KNOWN GAP asserting the SILENCE, and
     * the round that closed it flipped it rather than deleting it.
     *
     * The assignability family has read a declaration's ANNOTATION as a
     * contextual type since round 462; the property-access family never had —
     * its spine anchor walked `decl.initializer` with no context at all — so in
     * `const a: (n: N) => void = (node) => { node.nope }` the TS2322 family saw
     * `node: N` and the TS2339 family saw `any`, and tsc reports the TS2339.
     *
     * The old refusal blamed **+15 knip false positives**, all of them a UNION
     * contextual parameter type the body then narrows. Two of the three
     * mechanisms behind them have since closed — (CHK.41) the assignment /
     * `typeof` narrowing, and (CHK.98c) the optional-chain `typeof` — and the
     * third, (CHK.98b)'s nested-ternary predicate narrow, is what
     * [cpaAnnotationCtx]'s union gate now stands in for: an annotation-sourced
     * context refuses a UNION parameter type and takes every other shape. The
     * gate's own pin is in `ContextualParamReadersTest`.
     */
    @Test
    fun `an annotated variable's arrow parameter is typed for the property-access family`() {
        val d = diagnose(prelude + "const q: (n: N) => void = (node) => { node.nope; };")
        assert(d.count { it.code == 2339 } == 1)
    }

    /** …and the same for an object-literal METHOD's body, which that walker did
     *  not walk at all (`cpaExprObjectLiteral`'s `else`) until (CHK.98)(b) gave
     *  it an arm. The ASSIGNABILITY half of the same body has been walked since
     *  (CHK.39b) — that asymmetry was the whole point of pinning it. */
    @Test
    fun `an object-literal method body is property-access-walked`() {
        val d = diagnose(prelude + "const q: V = { m(node) { node.nope; } };")
        assert(d.count { it.code == 2339 } == 1)
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

    // ══════════════════════════════════════════════════════════════════════════
    // (CHK.40) round 951 — the four contextual-type SOURCES the walker did not
    // read, plus the `async` return type that made one of them a FALSE POSITIVE.
    // Every expectation below is tsc 7.0.2's, measured with
    // `tools/tsgo-7.0.2/lib/tsc --noEmit` on the same source.
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * (CHK.40)(e) THE ROOT CAUSE, and the only row in the item that painted a
     * WRONG error rather than a spurious TS7006: an `async` function-like whose
     * return type is INFERRED returns `Promise<T>`, not `T`.
     *
     * The item read it as "an async object-literal method's parameters are not
     * contextually typed"; measured, the parameters were fine and the RETURN
     * TYPE was not — `{ async m(node) {…} }` against `{ m(n: N): Promise<void> }`
     * reported `Type 'void' is not assignable to type 'Promise<void>'`, and the
     * same defect fired for an `async` function declaration, arrow, function
     * expression and class method. It is symmetric: three false POSITIVES and
     * four false NEGATIVES on one seven-shape fixture.
     *
     * These pins assert the TYPE — a silencing fix cannot satisfy them, because
     * the assignment they name is one tsc REPORTS.
     */
    @Test
    fun `an async function declaration's inferred return type is a Promise`() {
        val d = diagnose("async function f() { return 1; }\nconst n: number = f();")
        assert(d.count { it.code == 2322 } == 1)
        assert(d.first { it.code == 2322 }.message.contains("'Promise<number>'"))
    }

    @Test
    fun `an async arrow's inferred return type is a Promise`() {
        val d = diagnose("const f = async () => 1;\nconst n: number = f();")
        assert(d.count { it.code == 2322 } == 1)
        assert(d.first { it.code == 2322 }.message.contains("'Promise<number>'"))
    }

    @Test
    fun `an async function expression's inferred return type is a Promise`() {
        val d = diagnose("const f = async function () { return \"s\"; };\nconst n: number = f();")
        assert(d.count { it.code == 2322 } == 1)
        assert(d.first { it.code == 2322 }.message.contains("'Promise<string>'"))
    }

    @Test
    fun `an async class method's inferred return type is a Promise`() {
        val d = diagnose("class C { async m() { return 1; } }\nconst n: number = new C().m();")
        assert(d.count { it.code == 2322 } == 1)
        assert(d.first { it.code == 2322 }.message.contains("'Promise<number>'"))
    }

    /** …and the VOID case, which is the one that produced the item's own false
     *  positive: an async body with no `return` answers `Promise<void>`. */
    @Test
    fun `an async function with no return is assignable to a Promise-returning type`() {
        val d = diagnose("async function f() { }\nconst g: () => Promise<void> = f;")
        assert(d.none { it.code == 2322 })
    }

    /** NEGATIVE CONTROL. A NON-async function's inferred return type is NOT
     *  wrapped — a fix that wrapped every inferred return would pass all four
     *  positives above and fail here. */
    @Test
    fun `negative control - a non-async function's inferred return is not a Promise`() {
        val d = diagnose("function f() { return 1; }\nconst n: number = f();")
        assert(d.none { it.code == 2322 })
    }

    /** NEGATIVE CONTROL. An ANNOTATED return type is never touched — the
     *  annotation already spells whatever wrapper it wants. */
    @Test
    fun `negative control - an annotated async return type is used verbatim`() {
        val d = diagnose(
            "async function f(): Promise<number> { return 1; }\n" +
                "const g: () => Promise<number> = f;"
        )
        assert(d.none { it.code == 2322 })
    }

    /** (CHK.40)(e) The item's own fixture: the false TS2322 about the LITERAL is
     *  gone, and the one tsc does report — inside the body — survives. Asserting
     *  the count alone would pass against a binary that lost both. */
    @Test
    fun `an async object-literal method is assignable, and its body is still checked`() {
        val d = diagnose(
            prelude +
                "interface Pr { m(n: N): Promise<void> }\n" +
                "const q: Pr = { async m(node) { const bad: string = node.kind; } };"
        )
        assert(d.count { it.code == 2322 } == 1)
        assert(d.first { it.code == 2322 }.message.contains("Type 'number' is not assignable to type 'string'"))
    }

    /**
     * (CHK.40)(c) A STRING-LITERAL member name, and the root cause was NOT the
     * TS7006 walker: `getTypeOfSymbolWorker`'s MethodDeclaration arm extracted
     * the name with `decl.name as? Identifier` and answered `anyType` for
     * anything else, so `interface VS { "m-x"(node: N): void }` had that member
     * PRESENT and typed `any` — while the property form `"m-x": (n: N) => void`
     * was byte-correct. The name is now taken with `declaredMemberName`, the very
     * helper `resolveInterfaceMembersCore` used to REGISTER the member, so the
     * two cannot disagree about which member this is.
     */
    @Test
    fun `a string-literal-named method's parameter is typed and its body is checked`() {
        val d = diagnose(
            "interface N2 { kind: number }\n" +
                "interface VS { \"m-x\"(node: N2): void }\n" +
                "const q: VS = { \"m-x\"(node) { const bad: string = node.kind; } };"
        )
        assert(d.count { it.code == 2322 } == 1)
        assert(d.none { it.code == 7006 })
    }

    /** …and through the PROPERTY-ASSIGNMENT form of the same member, which is the
     *  second extraction site (`spineIanyPropAssignEdge`). */
    @Test
    fun `a string-literal-named property's arrow parameter is typed`() {
        val d = diagnose(
            "interface N2 { kind: number }\n" +
                "interface VS { \"m-x\"(node: N2): void }\n" +
                "const q: VS = { \"m-x\": (node) => { const bad: string = node.kind; } };"
        )
        assert(d.count { it.code == 2322 } == 1)
        assert(d.none { it.code == 7006 })
    }

    /**
     * (CHK.40)(a)/(b)/(d) — the three RETURN-position sources.
     *
     * These are TS7006-SUPPRESSION pins and nothing more, and that is a
     * deliberate, recorded limitation rather than the (CHK.39) mistake: a
     * function body nested in a `return` expression is not walked by the
     * assignability walker AT ALL ((CHK.40)(f), measured and queued), so no
     * diagnostic inside it can fire in either direction and no positive
     * assertion is expressible here. The TYPE half of the same fixtures IS
     * asserted, in `ProjectContextualParamHoverTest`, whose expectations are read
     * out of tsc 7.0.2's own language server.
     */
    @Test
    fun `an ARRAY LITERAL returned at an annotated array type types its elements`() {
        val d = diagnose(prelude + "function f(): V[] { return [{ m(node) { } }]; }")
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `- at a readonly array type`() {
        val d = diagnose(prelude + "function f(): readonly V[] { return [{ m(node) { } }]; }")
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `- and at a TUPLE type, positionally`() {
        val d = diagnose(prelude + "function f(): [V] { return [{ m(node) { } }]; }")
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `an async function's Promise return annotation is unwrapped for its return`() {
        val d = diagnose(prelude + "async function f(): Promise<V> { return { m(node) { } }; }")
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `an object-literal METHOD's own CONTEXTUAL return type reaches its return`() {
        val d = diagnose(
            prelude + "interface Outer { inner(): V }\n" +
                "const q: Outer = { inner() { return { m(node) { } }; } };"
        )
        assert(d.none { it.code == 7006 })
    }

    /**
     * NEGATIVE CONTROLS for the whole return family. A `return` position that
     * supplies NO usable type must leave the parameter implicitly `any` and still
     * report — the arms above are additive by construction and a fix that simply
     * stopped emitting in return position would pass all five and fail these.
     */
    @Test
    fun `negative control - an UNANNOTATED function's returned literal still reports TS7006`() {
        val d = diagnose(prelude + "function f() { return [{ m(node) { } }]; }")
        assert(d.count { it.code == 7006 } == 1)
    }

    /** NEGATIVE CONTROL for (b). The unwrap is gated on `async` and on nothing
     *  else: a NON-async function annotated `Promise<V>` returns the literal AT
     *  `Promise<V>`, so `m` is an excess property (TS2353) and its parameter stays
     *  implicitly `any` — both rows, byte for byte, are tsc 7.0.2's. */
    @Test
    fun `negative control - a NON-async Promise return annotation is not unwrapped`() {
        val d = diagnose(prelude + "function f(): Promise<V> { return { m(node) { } }; }")
        assert(d.count { it.code == 7006 } == 1)
        assert(d.count { it.code == 2353 } == 1)
    }

    @Test
    fun `a tuple SECOND slot receives its OWN element type`() {
        val d = diagnose(
            prelude + "interface W { z(a: number, b: number): void }\n" +
                "function f(): [V, W] { return [{ m(node) { } }, { z(p, q) { } }]; }"
        )
        assert(d.none { it.code == 7006 })
    }
}
