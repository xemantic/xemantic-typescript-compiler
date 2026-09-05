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
 * (CHK.87) / (CHK.88) / (CHK.83) — the three residues that LANDED beside (CHK.85)'s stop.
 * Every row quoted here was reproduced against tsgo 7.0.2 AND pristine `typescript@6.0.3`
 * BEFORE any code was written, and the two references agree on every one of them.
 *
 * The file is organised by RULE and every rule carries negative controls, because all
 * three defects are SILENCES: a missing `TS2367`, a diagnostic inside a branch tsc has
 * already proved dead, and a `TS2345` the argument gate never asked for. A silence is
 * invisible to a green corpus by construction, so the controls are what separate "the
 * rule fires" from "the rule fires everywhere".
 *
 * WHAT IS DELIBERATELY NOT PINNED HERE, each measured this round and re-queued:
 *
 *  * (CHK.85)(a), the mutable object-literal member widening. It was BUILT and MEASURED
 *    and costs MEANING: with `const o = { v: K.A }`, widening the member to `K` fixes an
 *    ours-only `TS2322` on `o.v = K.B` and buys the two true positives both references
 *    report, and it breaks DISCRIMINATED-UNION SELECTION program-wide — **7 added rows on
 *    every profile and 22 on harness**, all of them `{ kind: SomeEnum; … }` literals that
 *    stopped selecting their constituent (`tsbuildPublic.ts`'s `UpToDateStatus`,
 *    `signatureHelp.ts`'s `Invocation`, `findAllReferences.ts`'s `SymbolAndEntries`).
 *    Our contextual keep is tsc's `isLiteralOfContextualType`, so what is missing is the
 *    CONTEXT reaching `getTypeOfObjectLiteral` at those sites, not the widening rule.
 *
 *  * (CHK.85)(b), the flow type of a `let`/`const` READ, and (CHK.85)(c), the `as const`
 *    property read — the second of which is not an enum question at all: `({ v: "a" } as
 *    const).v` and `[1, 2] as const` are equally unmodelled here, so a const ASSERTION is
 *    a feature rather than a residue.
 *
 *  * The INTERSECTION half of (CHK.83)'s five parameter kinds, refused with its
 *    measurement — see the negative control at the bottom of this file.
 */
class EnumWideningParityTest {

    // ---------------------------------------------------------------------
    // (CHK.88) — an enum compared to a literal it provably cannot hold
    // ---------------------------------------------------------------------

    @Test
    fun `an enum member compared to a numeric literal outside its own value reports TS2367`() {
        val messages = diagnose(
            """
            enum K { A = 0, B = 1 }
            declare const ka: K.A
            const c = ka === 1
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'K.A' and '1' have no overlap.",
            ),
        )
    }

    @Test
    fun `a whole enum compared to a numeric literal outside its domain reports TS2367`() {
        val messages = diagnose(
            """
            enum K { A = 0, B = 1 }
            declare const k: K
            const c = k === 5
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'K' and '5' have no overlap.",
            ),
        )
    }

    @Test
    fun `a negated numeric literal is read as a literal and reports TS2367`() {
        val messages = diagnose(
            """
            enum K { A = 0, B = 1 }
            declare const ka: K.A
            const c = ka === -1
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'K.A' and '-1' have no overlap.",
            ),
        )
    }

    @Test
    fun `the inequality operator reports the same pair`() {
        val messages = diagnose(
            """
            enum K { A = 0, B = 1 }
            declare const k: K
            const c = k !== 5
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'K' and '5' have no overlap.",
            ),
        )
    }

    @Test
    fun `a const enum and a bit-shift-computed enum both carry a known domain`() {
        val messages = diagnose(
            """
            const enum C { P = 3 }
            enum M { A = 1 << 0, B = 1 << 1 }
            declare const cp: C
            declare const m: M
            const c1 = cp === 4
            const c2 = m === 4
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'C' and '4' have no overlap.",
                "This comparison appears to be unintentional because the types 'M' and '4' have no overlap.",
            ),
        )
    }

    @Test
    fun `a union covering every member of one enum prints as the bare enum`() {
        val messages = diagnose(
            """
            enum K { A = 0, B = 1 }
            declare const kab: K.A | K.B
            const c = kab === 5
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'K' and '5' have no overlap.",
            ),
        )
    }

    @Test
    fun `a string enum member compared to a foreign string literal reports TS2367`() {
        val messages = diagnose(
            """
            enum S { P = "p", Q = "q" }
            declare const sp: S.P
            const c = sp === "z"
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'S.P' and '\"z\"' have no overlap.",
            ),
        )
    }

    @Test
    fun `negative control - a literal the enum can hold stays legal at every shape`() {
        val codes = diagnose(
            """
            enum K { A = 0, B = 1 }
            const enum C { P = 3 }
            enum M { A = 1 << 0, B = 1 << 1 }
            enum S { P = "p", Q = "q" }
            declare const ka: K.A
            declare const k: K
            declare const cp: C
            declare const m: M
            declare const sp: S.P
            const c1 = ka === 0
            const c2 = k === 1
            const c3 = cp === 3
            const c4 = m === 2
            const c5 = sp === "p"
            """,
        ).map { it.code }
        assert(codes.isEmpty())
    }

    @Test
    fun `negative control - an enum whose domain we cannot evaluate accepts every literal`() {
        val codes = diagnose(
            """
            declare enum D { X, Y }
            enum Comp { X = "ab".length }
            declare const dx: D
            declare const cx: Comp
            const c1 = dx === 7
            const c2 = cx === 9
            """,
        ).map { it.code }
        assert(codes.isEmpty())
    }

    @Test
    fun `negative control - a literal of the wrong flavour is left to the category rule`() {
        val messages = diagnose(
            """
            enum K { A = 0, B = 1 }
            declare const ka: K.A
            const c1 = ka === "z"
            const c2 = ka === true
            """,
        ).map { it.message }
        // The category rule owns this pair, which is what BOTH references do too — they
        // print 'K' and 'string' / 'K' and 'boolean'. Our operand display there is the
        // ORIGINAL rather than tsc's `getBaseTypesIfUnrelated` base, a PRE-EXISTING
        // divergence that predates this round and is queued separately; what this pin
        // asserts is that the value rule above did NOT claim the pair.
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'K.A' and 'string' have no overlap.",
                "This comparison appears to be unintentional because the types 'K.A' and 'boolean' have no overlap.",
            ),
        )
    }

    // ---------------------------------------------------------------------
    // (CHK.87) — the `never` narrow of an impossible enum comparison
    // ---------------------------------------------------------------------

    @Test
    fun `the true branch of an impossible enum comparison narrows to never`() {
        val messages = diagnose(
            """
            enum K { A, B }
            enum J { X, Y }
            declare const k: K
            declare const j: J
            declare function pn(x: never): void
            if (k === j) { pn(k) }
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'K' and 'J' have no overlap.",
            ),
        )
    }

    @Test
    fun `two members of one enum narrow to never in the true branch`() {
        val messages = diagnose(
            """
            enum K { A, B }
            declare const ka: K.A
            declare const kb: K.B
            declare function pn(x: never): void
            if (ka === kb) { pn(ka) }
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'K.A' and 'K.B' have no overlap.",
            ),
        )
    }

    @Test
    fun `negative control - the same reference outside the branch is not narrowed`() {
        val messages = diagnose(
            """
            enum K { A, B }
            enum J { X, Y }
            declare const k: K
            declare const j: J
            declare function pn(x: never): void
            if (k === j) { }
            pn(k)
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'K' and 'J' have no overlap.",
                "Argument of type 'K' is not assignable to parameter of type 'never'.",
            ),
        )
    }

    @Test
    fun `negative control - the false branch of an impossible comparison keeps the type`() {
        val messages = diagnose(
            """
            enum K { A, B }
            enum J { X, Y }
            declare const k: K
            declare const j: J
            declare function pn(x: never): void
            if (k !== j) { pn(k) }
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "This comparison appears to be unintentional because the types 'K' and 'J' have no overlap.",
                "Argument of type 'K' is not assignable to parameter of type 'never'.",
            ),
        )
    }

    @Test
    fun `negative control - an enum comparison that CAN overlap narrows nothing away`() {
        val messages = diagnose(
            """
            enum K { A, B }
            declare const k: K
            declare const k2: K
            declare function pn(x: never): void
            if (k === k2) { pn(k) }
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "Argument of type 'K' is not assignable to parameter of type 'never'.",
            ),
        )
    }

    // ---------------------------------------------------------------------
    // (CHK.83) — a primitive-like argument against a composite parameter
    // ---------------------------------------------------------------------

    @Test
    fun `a primitive-only union argument reports against an array parameter`() {
        val messages = diagnose(
            """
            declare const u: string | number
            declare function f(x: string[]): void
            f(u)
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "Argument of type 'string | number' is not assignable to parameter of type 'string[]'.",
            ),
        )
    }

    @Test
    fun `a primitive-only union argument reports against a function-typed parameter`() {
        val messages = diagnose(
            """
            declare const u: string | number
            declare function f(x: (n: number) => void): void
            f(u)
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "Argument of type 'string | number' is not assignable to parameter of type '(n: number) => void'.",
            ),
        )
    }

    @Test
    fun `a primitive-only union argument reports against an enum parameter`() {
        val messages = diagnose(
            """
            enum E { A, B }
            declare const u: string | number
            declare function f(x: E): void
            f(u)
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "Argument of type 'string | number' is not assignable to parameter of type 'E'.",
            ),
        )
    }

    @Test
    fun `a primitive-only union argument reports against a union parameter`() {
        val messages = diagnose(
            """
            declare const u: string | number
            declare function f(x: { a: number } | { b: number }): void
            f(u)
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "Argument of type 'string | number' is not assignable to parameter of type '{ a: number; } | { b: number; }'.",
            ),
        )
    }

    @Test
    fun `a bare primitive argument reports against an array a construct signature and Function`() {
        val messages = diagnose(
            """
            declare function fArr(x: number[]): void
            declare function fCtor(x: new () => object): void
            declare function fFunc(x: Function): void
            fArr(1)
            fCtor("a")
            fFunc("a")
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "Argument of type 'number' is not assignable to parameter of type 'number[]'.",
                "Argument of type 'string' is not assignable to parameter of type 'new () => object'.",
                "Argument of type 'string' is not assignable to parameter of type 'Function'.",
            ),
        )
    }

    @Test
    fun `negative control - a REST parameter takes its ELEMENT and stays silent`() {
        val codes = diagnose(
            """
            enum CC { A = 1 }
            declare function f(...xs: string[]): void
            declare function g(a: number, ...ys: Array<string | number>): void
            declare function h(...zs: number[]): void
            f("a")
            f("a", "b")
            g(1, "a", 2)
            h(CC.A)
            """,
        ).map { it.code }
        assert(codes.isEmpty())
    }

    @Test
    fun `negative control - a primitive that DOES satisfy the composite parameter stays silent`() {
        val codes = diagnose(
            """
            enum E { A = 0, B = 1 }
            declare const n: number
            declare function fEnum(x: E): void
            declare function fUn(x: string | number[]): void
            fEnum(0)
            fEnum(n)
            fUn("a")
            """,
        ).map { it.code }
        assert(codes.isEmpty())
    }

    @Test
    fun `negative control - an ARITY-mismatched call reports only TS2554`() {
        // tsc reports the arity error and nothing per argument; the corpus is what
        // measured it — opening this gate without the arity guard added a second row to
        // `couldNotSelectGenericOverload` and DUPLICATED an existing one in
        // `recursiveFunctionTypes`, whose `f6("", 3)` TS2345 another emitter already owns.
        val codes = diagnose(
            """
            declare function f(items: any[]): void
            f(1, "")
            """,
        ).map { it.code }
        assert(codes == listOf(2554))
    }

    @Test
    fun `negative control - an un-inferred generic parameter stays silent`() {
        // `items: T[]` is the callee's OWN type parameter, which tsc infers from the
        // arguments; reporting against the un-substituted `T[]` would be both a wrong
        // verdict and a display no reference produces.
        val codes = diagnose(
            """
            declare function makeArray<T>(items: T[]): T[]
            const r = makeArray(1)
            """,
        ).map { it.code }
        assert(codes.isEmpty())
    }

    @Test
    fun `negative control - an OVERLOAD-SET parameter is refused and stays silent`() {
        // Refused with its measurement. The corpus caught a DUPLICATE row on
        // `recursiveFunctionTypes`, where the file-name-gated pin walker
        // `checkRecursiveFunctionTypes` already owns the TS2345 for `f6("")` against
        // `{ (): typeof f6; (a: typeof f6): () => number; }` — the `--passTiming`
        // emissions census names both emitters, and the reduced fixture below reaches
        // neither because that walker is keyed to the corpus file's own name.
        //
        // So this silence is a PRE-EXISTING false negative that the refusal keeps: both
        // references report TWO rows here (a TS2394 on the second overload signature and
        // the TS2345), and we report none. Lifting the refusal would buy the second row
        // on THIS shape and duplicate it on the corpus one, which is why the whole
        // overload-set family stays out until its emitters are unified.
        val codes = diagnose(
            """
            function f6(): typeof f6
            function f6(a: typeof f6): () => number
            function f6(a?: any) { return f6 }
            f6("")
            """,
        ).map { it.code }
        assert(codes.isEmpty())
    }

    @Test
    fun `negative control - an INTERSECTION parameter is refused and stays silent`() {
        // Refused with its measurement, and the reason is (CHK.55)'s law rather than the
        // relation: on the harness profile `vpath.parse(path)` resolves to
        // `getPathComponents`, an OVERLOAD SET whose FIRST signature takes tsc's branded
        // `Path` (`string & { __pathBrand: any }`) and whose second takes `string`.
        // `signatureAcceptsArgs` does not ask this question, so selection keeps the first
        // and opening the gate reported a TS2345 NEITHER reference produces. The family
        // needs the matching rule in overload SELECTION first.
        val codes = diagnose(
            """
            declare const s: string
            declare function f(x: { a: number } & { b: number }): void
            f(s)
            """,
        ).map { it.code }
        assert(codes.isEmpty())
    }
}
