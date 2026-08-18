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

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (BUG.3) A `this` RECEIVER, at every position `this` can be written in a class —
 * round 922 found a caret on `this.` inside a NESTED ARROW answering no members at
 * all, and this file is the whole table that finding turned into.
 *
 * ## The layer this pins, and how that was decided
 *
 * By MEASUREMENT, before any code: the same shapes compiled through the ORDINARY
 * diagnostic path answer **byte-identically to tsc 7.0.2** — seventeen diagnostics,
 * same codes, same positions, `TS2339` on `C` inside an arrow and inside an arrow
 * inside an arrow, `TS2683` inside a `function` expression. So the CHECKER binds
 * `this` correctly in every one of these positions and the defect was the CAPTURE's
 * ambient reconstruction alone: `typeCaptureVisit` read `CtaFrame.classForThis`,
 * and the cta frame an ARROW body pushes carries `null` there, because a cta frame
 * is a TYPE-checking context and `this` is not one of the things it threads.
 *
 * ## The discriminator, written first
 *
 * `an arrow nested in an arrow answers`, together with
 * `a function EXPRESSION in a method answers NOTHING`. Every rival implementation
 * fails one of the two: reading the cta frame fails the first (that is the bug);
 * "any caret lexically inside a class answers" — i.e. round 922's
 * [Checker] `typeCaptureEnclosingClass`, which the accessibility filter already
 * uses — passes the first and fails the second, because a `function` expression
 * REBINDS `this` and TypeScript types it `any`; "the innermost function-like decides,
 * whatever it is" fails the first, because an arrow does not bind `this` at all.
 * Only the ascent that is TRANSPARENT to arrows and OPAQUE to every other
 * function-like passes both.
 *
 * ## The bias, stated because it decides every uncertain case
 *
 * PROVE TO OFFER, the mirror of round 922's accessibility bias and for the same
 * reason one level down: `this` answers members only where the ascent reaches a
 * class DECLARATION through a chain it fully understands. A static member, an
 * object literal's method, a `function` at any depth, a class EXPRESSION and a
 * caret in no class at all each answer NOTHING rather than guessing — which is
 * what makes an empty answer here mean "not a class instance", never "lost".
 */
class ProjectThisReceiverTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"

    /**
     * EVERY member access is spelled with a DIFFERENT member name, so a caret is
     * located by a needle that occurs exactly once — asserted in [afterDot], because
     * a needle matching twice would silently measure the first site while reading as
     * though it measured the other.
     *
     * The class's own member set is the same everywhere (`real`, `count` and the
     * methods), so every positive assertion compares against ONE expected list and a
     * shape answering the wrong class would be visible rather than plausible.
     */
    private val main = """
        class C {
            real: string = "";
            count: number = 0;
            private secret: number = 1;
            inMethod(): void { this.aDirect; }
            inArrow(): void { const f = () => { this.bArrow; }; f(); }
            inNestedArrow(): void {
                const f = () => { const g = () => { this.cNested; }; g(); };
                f();
            }
            inFunctionExpression(): void {
                const f = function () { this.dFnExpr; };
                f();
            }
            inFunctionDeclaration(): void {
                function inner(): void { this.eFnDecl; }
                inner();
            }
            inObjectLiteralMethod(): void {
                const o = { k(): void { this.fObjLit; } };
                o.k();
            }
            inArrowInObjectLiteral(): void {
                const o = { k: () => { this.gObjArrow; } };
                o.k();
            }
            inCtorArrow: string;
            constructor() {
                this.hCtor;
                const f = () => { this.iCtorArrow; };
                f();
                this.inCtorArrow = "";
            }
            get accessor(): number { const f = () => { this.jGetter; }; f(); return 0; }
            set accessor(v: number) { const f = () => { this.kSetter; }; f(); }
            initializer = (() => { this.nInitArrow; })();
            deepMix(): void {
                const f = () => { const h = function () { this.oDeepFn; }; h(); };
                f();
            }
            usesSecret(): void { const f = () => { this.pSecret; }; f(); }
            inExpressionArrow(): void { const f = () => this.tExprArrow; f(); }
            inNestedClassExpression(): void {
                const K = class { inner(): void { const f = () => { this.uClassExpr; }; f(); } };
                void K;
            }
            readsMember(): string {
                const f = () => { const g = () => { return this.real; }; return g(); };
                return f();
            }
        }
        class S {
            static inStatic(): void { this.lStatic; }
            static inStaticArrow(): void { const f = () => { this.mStaticArrow; }; f(); }
        }
        const outerArrow = () => { this.qTopLevel; };
        export const used = [C, S, outerArrow];
    """.trimIndent() + "\n"

    private fun project(): Project = Project.open(
        "/proj",
        InMemoryVfs(mapOf("/proj/tsconfig.json" to config, mainFile to main)),
    )

    /** The caret immediately after the dot of the UNIQUE `this.<member>` text. */
    private fun afterDot(access: String): Int {
        val at = main.indexOf(access)
        assert(at >= 0)
        assert(main.indexOf(access, at + 1) < 0)
        return at + access.indexOf('.') + 1
    }

    private fun namesAfterDot(access: String): List<String> =
        project().completionsAt(mainFile, afterDot(access)).items.map { it.name }

    /** Every member of `C` a caret INSIDE `C` may see, in the order the API reports. */
    private val insideC = listOf(
        "accessor", "count", "deepMix", "inArrow", "inArrowInObjectLiteral", "inCtorArrow",
        "inExpressionArrow", "inFunctionDeclaration", "inFunctionExpression", "inMethod",
        "inNestedArrow", "inNestedClassExpression", "inObjectLiteralMethod", "initializer",
        "readsMember", "real", "secret", "usesSecret",
    )

    // --- THE DISCRIMINATORS -------------------------------------------------------

    @Test
    fun `an arrow nested in an arrow answers with the enclosing class's members`() {
        assert(namesAfterDot("this.cNested") == insideC)
    }

    @Test
    fun `a function EXPRESSION in a method answers NOTHING - it rebinds this`() {
        assert(namesAfterDot("this.dFnExpr").isEmpty())
    }

    // --- THE POSITIONS THAT ANSWER ------------------------------------------------

    @Test
    fun `a method body answers - the control that already worked`() {
        assert(namesAfterDot("this.aDirect") == insideC)
    }

    @Test
    fun `an arrow directly in a method answers`() {
        assert(namesAfterDot("this.bArrow") == insideC)
    }

    @Test
    fun `a constructor body answers`() {
        assert(namesAfterDot("this.hCtor") == insideC)
    }

    @Test
    fun `an arrow in a constructor answers`() {
        assert(namesAfterDot("this.iCtorArrow") == insideC)
    }

    @Test
    fun `an arrow in a getter answers`() {
        assert(namesAfterDot("this.jGetter") == insideC)
    }

    @Test
    fun `an arrow in a setter answers`() {
        assert(namesAfterDot("this.kSetter") == insideC)
    }

    /**
     * An EXPRESSION-bodied arrow. Measured to have worked BEFORE the fix and pinned
     * anyway, because the reason is the mechanism's own shape: a cta frame is pushed
     * at a `Block` enter, so an arrow with no block pushes none and
     * `ctaFrames.last()` is still the enclosing method's. The bug was never "arrows"
     * — it was arrows that OPEN A BLOCK.
     */
    @Test
    fun `an expression-bodied arrow answers`() {
        assert(namesAfterDot("this.tExprArrow") == insideC)
    }

    @Test
    fun `an arrow in a property initializer answers`() {
        assert(namesAfterDot("this.nInitArrow") == insideC)
    }

    // --- THE POSITIONS THAT MUST NOT ANSWER ---------------------------------------

    /**
     * A nested `function` DECLARATION rebinds `this` exactly as a function
     * EXPRESSION does — tsc emits the same `TS2683` for both — and this is the arm
     * where the checker's own pull-based twin `spineCaClassCtx` is deliberately
     * bug-compatible with a narrower legacy walker and answers the class. Reusing it
     * verbatim would have passed every other pin in this file and failed here.
     */
    @Test
    fun `a function DECLARATION in a method answers NOTHING`() {
        assert(namesAfterDot("this.eFnDecl").isEmpty())
    }

    /**
     * A `function` nested inside an arrow: the arrow is transparent, so an ascent
     * that stopped at the FIRST function-like it could not classify would keep
     * walking past this one and answer the class.
     */
    @Test
    fun `a function EXPRESSION inside an arrow answers NOTHING`() {
        assert(namesAfterDot("this.oDeepFn").isEmpty())
    }

    /** An object literal's method binds `this` to the object literal, not the class. */
    @Test
    fun `an object literal method answers NOTHING`() {
        assert(namesAfterDot("this.fObjLit").isEmpty())
    }

    /**
     * ... but an ARROW inside an object literal does NOT rebind `this`, so it keeps
     * the enclosing method's — which is the class. The pair is what separates
     * "an object literal is opaque" from "an object literal's METHOD is opaque".
     */
    @Test
    fun `an arrow inside an object literal answers with the class`() {
        assert(namesAfterDot("this.gObjArrow") == insideC)
    }

    /**
     * A static `this` is `typeof C`, not `C`. The capture answers NOTHING rather
     * than offering instance members — round 917's rule, restated here because the
     * ascent is now what enforces it.
     */
    @Test
    fun `a static method answers NOTHING`() {
        assert(namesAfterDot("this.lStatic").isEmpty())
    }

    @Test
    fun `an arrow in a static method answers NOTHING`() {
        assert(namesAfterDot("this.mStaticArrow").isEmpty())
    }

    @Test
    fun `an arrow at top level answers NOTHING - there is no enclosing class`() {
        assert(namesAfterDot("this.qTopLevel").isEmpty())
    }

    // --- THE FILTER STILL APPLIES THROUGH THE ARROW -------------------------------

    /**
     * The (API.7) accessibility filter and this ascent must agree: `private secret`
     * is offered inside the class, so it must be offered from a nested arrow of that
     * same class too. A fix that answered with SOME class rather than the RIGHT one
     * would hide it.
     */
    @Test
    fun `a private member is still offered from inside a nested arrow`() {
        assert("secret" in namesAfterDot("this.pSecret"))
    }

    // --- THE CLASS THE ASCENT MUST STOP AT ----------------------------------------

    /**
     * A class EXPRESSION nested inside a method of `C`. Its own method's arrow binds
     * `this` to the ANONYMOUS class, which [Checker] `currentClassForThis` cannot
     * hold (it is typed `ClassDeclaration?`), so the honest answer is NOTHING.
     *
     * The pin is not a countdown on that bound — it is the invariant that the ascent
     * STOPS there. An ascent that only knew about `ClassDeclaration` would walk
     * straight past this class expression and answer with `C`'s members: a confident,
     * plausible, WRONG list, which is the one failure mode a completion UI cannot
     * show the user their way out of.
     */
    @Test
    fun `a class EXPRESSION nested in a method answers NOTHING - never the outer class`() {
        val names = namesAfterDot("this.uClassExpr")
        assert(names.isEmpty())
        assert(names != insideC)
    }

    // --- THE OTHER QUERY ON THE SAME PATH -----------------------------------------

    /**
     * Go-to-definition on `this.real` inside a BLOCK-bodied nested arrow. It reaches
     * the member through [Checker] `typeCaptureMemberSymbols`, the same
     * `currentClassForThis` consult one query over, so it was broken by the same
     * null and is fixed by the same install.
     *
     * BLOCK-bodied is load-bearing: an EXPRESSION-bodied arrow pushes no cta frame at
     * all (the push happens at a `Block` enter), so `ctaFrames.last()` there is still
     * the enclosing METHOD's frame and this query already worked — measured, and the
     * reason `inExpressionArrow` is a separate, always-green pin above rather than
     * this one's fixture.
     */
    @Test
    fun `go-to-definition on a this member works inside a nested arrow`() {
        val caret = main.indexOf("this.real") + "this.".length
        assert(main.indexOf("this.real", caret) < 0)
        val definitions = project().definitionsAt(mainFile, caret)
        assert(definitions.size == 1)
        assert(definitions[0].fileName == mainFile)
        assert(definitions[0].start == main.indexOf("real: string"))
        assert(definitions[0].length == "real".length)
    }

    /**
     * QUICK INFO on a member name is NOT on this path and is NOT fixed here — stated
     * because the obvious expectation is that it would be.
     *
     * Measured: `quickInfoAt` resolves the NARROWEST node at the caret, which for
     * `this.real` is the member Identifier `real`, and records
     * `getTypeOfExpression` of it — a bare name nothing in scope binds, so `any`.
     * The same reading of `o.k` with an ORDINARY receiver, and of `this.aDirect`
     * directly inside a method where completions and definitions both answer
     * correctly, is `any` too. So the gap is RECEIVER-INDEPENDENT and one query
     * over: quick info does not consult the member resolution at all. Attributing it
     * to `this` would be wrong, and pinning it here would pin the wrong subject.
     */
}
