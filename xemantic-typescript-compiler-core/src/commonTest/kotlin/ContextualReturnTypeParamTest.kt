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
 * (CHK.148) round P18.178 — a callee type parameter that only the CONTEXTUAL RETURN
 * position can bind takes the context's type, not `unknown`.
 *
 * tsc's `inferTypeArguments` OPENS by inferring from the call's own contextual type to
 * the signature's return type (`checker.go:9366`); this checker had no such leg at all,
 * so `createOperatorSubscriber<T>(destination, onNext?: (value: T) => void):
 * Subscriber<T>` called inside `source.subscribe(…)` — `T` in a callback PARAMETER,
 * which contributes no first-pass candidate, and in the RETURN — fell to
 * `freeTypeParamMapper`'s `unknown` and wrote `Type 'unknown' is not assignable to type
 * 'T'` on legal code.
 *
 * THE PRIORITY RULE NEEDS NO `InferencePriority` MACHINERY. A return-type candidate
 * arrives at `InferencePriorityReturnType` and an ARGUMENT one at `InferencePriorityNone`
 * = 0, and `inference.go:189` WIPES the weaker set outright — so contributing ONLY where
 * the arguments bind nothing reproduces tsc exactly. Two pins hold that down from
 * opposite sides: an argument candidate must WIN over a differing contextual one, and a
 * callback whose parameter type mentions the parameter in its own RETURN position must
 * keep binding it itself (tsc's second pass does produce a priority-0 candidate there).
 *
 * EVERY EXPECTATION BELOW IS tsgo 7.0.2's OWN ROW, MEASURED, and every pin asserts a
 * VALUE: the probe is an ARGUMENT at a `number` parameter, whose TS2345 NAMES the type
 * the callback parameter was given. A silence cannot tell `string` from `unknown` from
 * `any`, which is the whole distinction this family is about — and a `const p: number =
 * v` probe is blind here besides, because the var-decl reader accepts an unconstrained
 * type parameter as a source.
 *
 * TWO REFUSALS ARE DELIBERATE AND NOT PINNED, because pinning today's wrong answer is a
 * countdown: a contextual type that is a union with two REAL members is refused (tsc
 * builds a candidate from each constituent and picks a common supertype — a guess this
 * leg does not make), and an expression-bodied arrow supplies no contextual position at
 * all, because `pullContextualTypeAt` has no arm for one. Both are recorded in the round
 * note with their measured tsgo answers.
 */
class ContextualReturnTypeParamTest {

    /** `T` occurs ONLY in a callback PARAMETER — which contributes no candidate — and in
     *  the RETURN, so the contextual position is the only inference source there is. */
    private val prelude = """
        declare class Sub<T> { next(v: T): void; }
        declare function createOp<T>(dest: number, onNext?: (value: T) => void): Sub<T>;
        declare function probeNum(n: number): void;
    """.trimIndent() + "\n"

    private val namesString =
        "Argument of type 'string' is not assignable to parameter of type 'number'."
    private val namesUnknown =
        "Argument of type 'unknown' is not assignable to parameter of type 'number'."

    private fun rows(source: String): List<Diagnostic> = diagnose(prelude + source)

    private fun namedString(source: String): Int =
        rows(source).count { it.code == 2345 && it.message == namesString }

    private fun namedUnknown(source: String): Int =
        rows(source).count { it.code == 2345 && it.message == namesUnknown }

    @Test
    fun `a call argument position binds a return-only type parameter`() {
        val source = """
            declare function takeSub(s: Sub<string>): void;
            takeSub(createOp(0, (v) => probeNum(v)));
        """.trimIndent()
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    @Test
    fun `a variable annotation binds a return-only type parameter`() {
        val source = "const a: Sub<string> = createOp(0, (v) => probeNum(v));"
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    @Test
    fun `a return position binds a return-only type parameter`() {
        val source = "function f(): Sub<string> { return createOp(0, (v) => probeNum(v)); }"
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    @Test
    fun `a satisfies expression binds a return-only type parameter`() {
        val source = "const a = createOp(0, (v) => probeNum(v)) satisfies Sub<string>;"
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    @Test
    fun `an as expression binds a return-only type parameter`() {
        val source = "const a = createOp(0, (v) => probeNum(v)) as Sub<string>;"
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    @Test
    fun `an array literal element binds a return-only type parameter`() {
        val source = "const a: Sub<string>[] = [createOp(0, (v) => probeNum(v))];"
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    @Test
    fun `a ternary branch binds a return-only type parameter`() {
        val source = """
            declare const cond: boolean;
            const a: Sub<string> = cond ? createOp(0, (v) => probeNum(v)) : null!;
        """.trimIndent()
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    /** A FILE-LEVEL target: the pull's `=` arm reads `getTypeOfExpression(left)`, which
     *  answers nothing usable for a BLOCK-SCOPED local (B83.5) — a pre-existing gap of
     *  that arm, measured this round and owned elsewhere. */
    @Test
    fun `a plain assignment binds a return-only type parameter`() {
        val source = """
            let a: Sub<string>;
            a = createOp(0, (v) => probeNum(v));
        """.trimIndent()
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    @Test
    fun `an assignment to a member binds a return-only type parameter`() {
        val source = "function f(o: { s: Sub<string> }) { o.s = createOp(0, (v) => probeNum(v)); }"
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    @Test
    fun `a class property initializer binds a return-only type parameter`() {
        val source = "class K { m: Sub<string> = createOp(0, (v) => probeNum(v)); }"
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    @Test
    fun `a new expression argument binds a return-only type parameter`() {
        val source = """
            declare class Holder { constructor(s: Sub<string>); }
            const h = new Holder(createOp(0, (v) => probeNum(v)));
        """.trimIndent()
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    /** `Sub<string> | null` is the ordinary optional annotation — stripping the nullish
     *  members leaves the one type the position really names. */
    @Test
    fun `a nullish union context binds a return-only type parameter`() {
        val source = "const a: Sub<string> | null = createOp(0, (v) => probeNum(v));"
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    @Test
    fun `a nested type reference binds through its type arguments`() {
        val source = "const a: Sub<Sub<string>> = createOp(0, (v) => probeNum(v));"
        val d = rows(source)
        val named = d.count {
            it.code == 2345 &&
                it.message == "Argument of type 'Sub<string>' is not assignable to parameter of type 'number'."
        }
        assert(named == 1)
        assert(d.count { it.message == namesUnknown } == 0)
    }

    /** Per TYPE PARAMETER, not per call: `A` comes from the argument and `B` — which no
     *  argument can bind — from the contextual return position, in one inference. */
    @Test
    fun `a type parameter bound by an argument and one bound by the context coexist`() {
        val source = """
            declare function pair<A, B>(a: A, onB?: (b: B) => void): Sub<B>;
            const a: Sub<string> = pair(1, (b) => probeNum(b));
        """.trimIndent()
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    /**
     * THE PRIORITY PIN. The argument binds `T` to `number` and the context would bind it
     * to `string`; tsc's priority-0 argument candidate WIPES the return-type one, so the
     * call is `Sub<number>` and the row is the OUTER TS2322 naming it — never a TS2345 at
     * the probe, which `number` satisfies. A leg contributing for every type parameter
     * rather than only the unbound ones inverts exactly this.
     */
    @Test
    fun `an argument candidate beats the contextual return one`() {
        val source = """
            declare function seeded<T>(seed: T, onNext?: (value: T) => void): Sub<T>;
            const a: Sub<string> = seeded(1, (v) => probeNum(v));
        """.trimIndent()
        val d = rows(source)
        val outer = d.count {
            it.code == 2322 && it.message == "Type 'Sub<number>' is not assignable to type 'Sub<string>'."
        }
        assert(outer == 1)
        assert(d.count { it.code == 2345 } == 0)
    }

    /**
     * negative control - a NON-function-like argument sitting at a parameter whose type
     * MENTIONS the type parameter is a priority-0 candidate even where THIS checker's own
     * argument inference cannot read the shape (here a union), so the leg must stay out
     * rather than substitute the contextual type over it.
     *
     * It is the only pin that separates the `typeParamBoundByArguments` half of the
     * pre-gate: without it that arm reads 0 RED across every other pin here AND 0
     * mismatches over the 8,725-subtest corpus screen, while the fixture below grows an
     * ours-only TS2345 naming `'string'` on code tsgo types `Sub<number>` — measured. The
     * TS2322 tsgo additionally reports here is a PRE-EXISTING gap of the argument
     * inference, not this leg's, so it is deliberately not asserted.
     */
    @Test
    fun `an argument our own inference cannot read still keeps the leg out`() {
        val source = """
            declare function g<T>(x: { a: T } | T[], cb?: (v: T) => void): Sub<T>;
            const s: Sub<string> = g([1, 2], (v) => probeNum(v));
        """.trimIndent()
        assert(rows(source).none { it.code == 2345 })
    }

    @Test
    fun `an explicit type argument still decides`() {
        val source = "const a: Sub<string> = createOp<string>(0, (v) => probeNum(v));"
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    /** FALLBACK CONTROL — the leg must not move `freeTypeParamMapper`'s measured-correct
     *  answer where there is no contextual position at all. */
    @Test
    fun `a call with no contextual type keeps the unknown fallback`() {
        val source = "createOp(0, (v) => probeNum(v));"
        assert(namedUnknown(source) == 1)
        assert(namedString(source) == 0)
    }

    /** FALLBACK CONTROL — a constrained parameter with no candidates answers its
     *  CONSTRAINT, which is `getInferredType`'s own ladder and is not this leg's. */
    @Test
    fun `a constrained type parameter with no context keeps its constraint`() {
        val source = """
            declare function constrained<T extends string>(d: number, onNext?: (value: T) => void): Sub<T>;
            constrained(0, (v) => probeNum(v));
        """.trimIndent()
        assert(namedString(source) == 1)
        assert(namedUnknown(source) == 0)
    }

    /**
     * THE CHANGE IS TWO-DIRECTIONAL. A member read on a parameter that is `unknown` is
     * SILENT; once the parameter carries a real type those reads start being CHECKED, so
     * this row is one the fix ADDS — and tsgo writes it byte for byte.
     */
    @Test
    fun `a member read on the inferred parameter starts being checked`() {
        val d = rows("const a: Sub<string> = createOp(0, (v) => { v.toFixed(2); });")
        val named = d.count {
            it.code == 2551 &&
                it.message == "Property 'toFixed' does not exist on type 'string'. Did you mean 'fixed'?"
        }
        assert(named == 1)
    }

    /** negative control - a member the inferred type DOES have stays silent. */
    @Test
    fun `a member read that exists on the inferred type stays silent`() {
        val d = rows("const a: Sub<string> = createOp(0, (v) => { v.toUpperCase(); });")
        assert(d.none { it.code == 2551 || it.code == 2339 || it.code == 18046 })
    }

    /** A class CONSTRUCTOR's return type is the class's UNINSTANTIATED `Type.Interface`,
     *  standing in for `C<T…>` — the `new` arm shares this rule with the call arm, as
     *  `ctxArgTypesFromSignatures` says it does. */
    @Test
    fun `a generic class reached through new takes the contextual type argument`() {
        val d = rows(
            """
            declare class Box<T> { constructor(cb: (v: T) => void); }
            const b: Box<string> = new Box((v) => { v.toFixed(2); });
            """.trimIndent()
        )
        val named = d.count {
            it.code == 2551 &&
                it.message == "Property 'toFixed' does not exist on type 'string'. Did you mean 'fixed'?"
        }
        assert(named == 1)
    }

    /**
     * THE OTHER SIDE OF THE PRIORITY RULE. `useMemo<T>(func: () => T): T` puts `T` in the
     * callback's RETURN position, where tsc's SECOND pass types the lambda's own returns
     * and contributes a priority-0 candidate — which wipes the contextual one. So the
     * contextual `(input: string) => boolean` must NOT bind `T`, and the inner `x =>
     * x.length > 0` keeps its TS7006. This is the corpus case
     * `subtypeReductionWithAnyFunctionType`, on which tsgo and pristine agree; the first
     * cut of this leg deleted its row.
     */
    @Test
    fun `a callback whose return position mentions the type parameter binds it itself`() {
        val d = rows(
            """
            declare function useMemo<T>(func: () => T): T;
            function getPredicate(alwaysTrue: boolean) {
              const predicate: (input: string) => boolean = useMemo(() => {
                if (alwaysTrue) { return () => true; }
                return x => x.length > 0;
              });
              return predicate;
            }
            """.trimIndent()
        )
        assert(d.count { it.code == 7006 } == 1)
        assert(d.count { it.code == 2322 || it.code == 2345 } == 0)
    }
}
