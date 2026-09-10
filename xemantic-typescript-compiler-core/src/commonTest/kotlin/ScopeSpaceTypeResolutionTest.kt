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
 * (INV.0) step 10a — a TYPE reference resolves a declaration the main binder never
 * bound (B83.5), for the whole TYPE space and not only for `enum`.
 *
 * ## What B83.5 is, and why every fixture here is shaped the way it is
 *
 * `Binder` recurses into statements from exactly two places — a [SourceFile]'s own
 * list and a `ModuleBlock`'s — so it is not "declarations nested in a `Block`" that go
 * unbound but *everything that is not a direct statement of one of those two*. A
 * `class` at the very TOP of a function body, with no block nesting at all, is equally
 * unbound. The fixtures use that shape deliberately: it is the smallest one that
 * exhibits the defect, and a reader who assumes a `Block` is required will write a
 * fixture that measures nothing.
 *
 * Round 748 closed the `enum` half of this in TYPE position. This class is the other
 * three kinds — `interface`, `class`, `type` — plus the ordering question the enum
 * half never had to answer, because [Checker.getTypeFromTypeReference] asks the
 * enclosing-NAMESPACE chain itself and then calls
 * [NameResolver.resolveTypeNameToSymbol] with `enclosingNamespacesDone = true`, i.e.
 * past that function's own lexical-first arm.
 *
 * ## Why the assertions are on MESSAGE TEXT and never on silence
 *
 * The failure mode being closed is SILENT in both directions: a UNIQUE name resolved
 * to nothing, so the annotation degraded to `any` and every check under it went quiet;
 * a SHADOWING name resolved to the OUTER declaration, which is a wrong answer with
 * nothing at all to detect. So a pin that asserts "no error here" passes on a broken
 * binary, and one that asserts "an error here" cannot say WHICH declaration answered.
 * Every pin below reads the resolved type's NAME, or a member name that only one of
 * the two candidate declarations has, out of the diagnostic text.
 *
 * ## The ablations, one per pin, each measured
 *
 * All eight were RUN (`scripts/inv0s10-ablate.py`, one mistake at a time against a
 * sha256-verified snapshot), and what they measured is recorded here rather than
 * predicted — three of the predictions below were WRONG.
 *
 *  * **A1, the STAMP** (`indexSourceFile` back to `type`/`enum` only) — 5 RED, and the
 *    only arm that reaches [LexicalScopeDeferralTest]'s projection pin. Note it does NOT
 *    redden the `type alias` pin, which is the internal consistency check: `type` was
 *    already stamped before this round.
 *  * **A2, the GATE** (back to `lexicalBlockScopedEnumNames`) and **A3, the FLAG MASK**
 *    (back to `SymbolFlags.Enum`) — 5 RED each, and the two red sets are IDENTICAL.
 *    They were predicted to separate the two halves of the widening and they do not:
 *    either one alone disables the whole thing, so no fixture can tell them apart. That
 *    is round 927's PAIR, recorded rather than claimed — both are load-bearing, and
 *    neither has a pin of its own.
 *  * **A4, the ORDER at [NameResolver.resolveTypeNameToSymbol]** (consult moved below
 *    `lookupPerFileForNode`) — **0 RED, UNDISCRIMINATED**, and the reason is A5: this
 *    step gave [Checker.getTypeFromTypeReference] its own hoist, which serves every
 *    TYPE REFERENCE before that function's arm is reached. Round 748's ordering is
 *    therefore redundant *for type references* and is not redundant in general — that
 *    function has four other callers this class does not reach. Recorded as an
 *    undiscriminated arm, not as a passing pin.
 *  * **A5, the ORDER at [Checker.getTypeFromTypeReference]** (drop the hoist) — 1 RED,
 *    and it is exactly the namespace pin, because that is the one shape where the
 *    enclosing-namespace consult has an answer to win with. A4 and A5 together are one
 *    observable at two layers.
 *  * **A6, round 748's forbidden [LexicalScope.existing] read** — 1 RED, uniquely
 *    [LexicalScopeResolverTest]'s own pin. It does NOT redden this class's containment
 *    control below: in that fixture the `existing` hit and the correct answer are the
 *    SAME symbol, so no compile-level fixture can see the difference. The rule is pinned
 *    at the resolver level, where it is a value.
 *  * **A7, `keyof errorType` back to `stringType`** — 1 RED, unique. Its first fixture
 *    was a hand-reduced one and read 0 RED while the corpus baseline went red; the pin
 *    now carries the reproducing source verbatim.
 *  * **A8, the `keyof (X & T)` arm disabled** — 1 RED, unique, and it had to be ADDED:
 *    the arm read 0 RED against the original pin set, and a CLI probe on that very
 *    binary showed the false row returning. Without its pin the guard would have read
 *    as redundant and could have been deleted.
 */
class ScopeSpaceTypeResolutionTest {

    /**
     * The prize, measured against tsgo 7.0.2 and pristine `typescript@6.0.3` on
     * 2026-09-10: both report TS2353 and TS2322 here and this compiler reported
     * NOTHING, because `ZzzShape` resolved to no symbol and the annotation degraded to
     * `any`. Two rows, and the second one NAMES the type, which is what makes this a
     * value pin rather than a count.
     */
    @Test
    fun `an interface at the top of a function body types an annotation`() {
        val d = diagnose(
            """
            export function outerFn(): void {
                interface ZzzShape { a: number }
                const s: ZzzShape = { a: 1, bogus: 2 }
                const probe: string = s
            }
            """,
        )
        val messages = d.map { it.message }
        assert(
            messages == listOf(
                "Object literal may only specify known properties, and 'bogus' does not exist in type 'ZzzShape'.",
                "Type 'ZzzShape' is not assignable to type 'string'.",
            )
        )
    }

    /**
     * The kind the round-748 flag mask ([SymbolFlags.Enum]) could never admit. A class
     * is BOTH a type and a value here, and only the type half is this step's business —
     * the annotation resolves, `new ZzzCls()` does not (the VALUE-space consult is step
     * 10b), which is why the probe is an annotation and not a construction.
     */
    @Test
    fun `a class at the top of a function body types an annotation`() {
        val d = diagnose(
            """
            export function outerFn(): void {
                class ZzzCls { m(): number { return 1 } }
                const c: ZzzCls = { m: () => 1 }
                const probe: string = c
            }
            """,
        )
        assert(d.any { it.message == "Type 'ZzzCls' is not assignable to type 'string'." })
    }

    /**
     * A `type` alias, i.e. the kind whose ARITY round 945 already read out of scope
     * space ([Checker.lexicalTypeAliasArity]) while its RESOLUTION still went to the
     * outer world. The two now come from the same place.
     */
    @Test
    fun `a type alias in a nested block types an annotation`() {
        val d = diagnose(
            """
            export function outerFn(flag: boolean): void {
                if (flag) {
                    type ZzzAl = { a: number }
                    const s: ZzzAl = { a: 1 }
                    const probe: string = s
                }
            }
            """,
        )
        assert(d.any { it.message == "Type 'ZzzAl' is not assignable to type 'string'." })
    }

    /**
     * The resolution-ORDER half, and the only shape whose old answer was WRONG rather
     * than absent: before this step the inner `ZzzShape` was invisible and the
     * annotation was judged against the FILE-LEVEL one, so the object literal carrying
     * `innerOnly` was an error and the one carrying `outerOnly` was not — exactly
     * inverted. Both assertions are needed: either alone is satisfied by a binary that
     * resolves nothing at all.
     */
    @Test
    fun `an inner declaration wins over a same named outer one`() {
        val d = diagnose(
            """
            interface ZzzShape { outerOnly: number }
            export function outerFn(): void {
                interface ZzzShape { innerOnly: number }
                const good: ZzzShape = { innerOnly: 1 }
                const bad: ZzzShape = { outerOnly: 2 }
            }
            """,
        )
        val messages = d.map { it.message }
        assert(
            messages == listOf(
                "Object literal may only specify known properties, and 'outerOnly' does not exist in type 'ZzzShape'.",
            )
        )
    }

    /**
     * [Checker.getTypeFromTypeReference] asks the enclosing-NAMESPACE chain BEFORE
     * [NameResolver.resolveTypeNameToSymbol], and passes `enclosingNamespacesDone =
     * true`, so that function's own lexical-first arm is never reached from here. Left
     * alone, a namespace member would therefore displace a declaration nested INSIDE
     * one of its own functions — the outer declaration winning over the inner one, at a
     * second site. Innermost-first has to hold at both.
     */
    @Test
    fun `an enclosing namespace does not displace a function body declaration`() {
        val d = diagnose(
            """
            namespace ZzzNs {
                export interface ZzzShape { outerOnly: number }
                export function inner(): void {
                    interface ZzzShape { innerOnly: number }
                    const good: ZzzShape = { innerOnly: 1 }
                    const bad: ZzzShape = { outerOnly: 2 }
                }
            }
            """,
        )
        val messages = d.map { it.message }
        assert(
            messages == listOf(
                "Object literal may only specify known properties, and 'outerOnly' does not exist in type 'ZzzShape'.",
            )
        )
    }

    /**
     * (INV.0) step 10a's ONE regression, and why it is a defect this change EXPOSED
     * rather than one it introduced.
     *
     * `keyof` over a type that did not resolve used to answer `string`, under a comment
     * saying its result "is never displayed/checked meaningfully". That was measurably
     * false: it is a CLOSED key domain for an UNKNOWN type, so anything assigning to a
     * binding annotated with it gets a false TS2322. It stayed invisible only because a
     * B83.5-unbound alias resolved to NOTHING — the annotation was `any`, and `keyof any`
     * IS the correct open domain. Making the type real narrowed a correct superset into a
     * wrong subset.
     *
     * **The fixture is `tests/cases/compiler/keyRemappingKeyofResult.ts` VERBATIM, and a
     * reduced one was tried and was BLIND.** Delta-debugging says the two rows need three
     * ingredients simultaneously — the file-level `Oops`/`x` block, the INNER `Remapped`
     * mapped type and the inner `Oops`/`x` block — because what makes `Orig` answer
     * `errorType` is a cyclic mapped type re-entering `keyof Orig` while `Orig` is in
     * flight. A hand-reduced version carrying all three still did not reproduce under the
     * corpus harness, and the ablation caught it: arm A7 (this fix reverted) left the
     * reduced pin GREEN while the corpus baseline went RED. Keeping the source verbatim is
     * what makes this pin an instrument rather than a decoration.
     */
    @Test
    fun `keyof over an unresolved type does not manufacture a closed key domain`() {
        val d = diagnose(
            """
            const sym = Symbol("")
            type Orig = { [k: string]: any, str: any, [sym]: any }

            type Okay = Exclude<keyof Orig, never>
            // type Okay = string | number | typeof sym

            type Remapped = { [K in keyof Orig as {} extends Record<K, any> ? never : K]: any }
            /* type Remapped = {
                str: any;
                [sym]: any;
            } */
            // no string index signature, right?

            type Oops = Exclude<keyof Remapped, never>
            declare let x: Oops;
            x = sym;
            x = "str";
            // type Oops = typeof sym <-- what happened to "str"?

            // equivalently, with an unresolved generic (no `exclude` shenanigans, since conditions won't execute):
            function f<T>() {
                type Orig = { [k: string]: any, str: any, [sym]: any } & T;
    
                type Okay = keyof Orig;
                let a: Okay;
                a = "str";
                a = sym;
                a = "whatever";
                // type Okay = string | number | typeof sym
    
                type Remapped = { [K in keyof Orig as {} extends Record<K, any> ? never : K]: any }
                /* type Remapped = {
                    str: any;
                    [sym]: any;
                } */
                // no string index signature, right?
    
                type Oops = keyof Remapped;
                let x: Oops;
                x = sym;
                x = "str";
            }

            // and another generic case with a _distributive_ mapping, to trigger a different branch in `getIndexType`
            function g<T>() {
                type Orig = { [k: string]: any, str: any, [sym]: any } & T;
    
                type Okay = keyof Orig;
                let a: Okay;
                a = "str";
                a = sym;
                a = "whatever";
                // type Okay = string | number | typeof sym

                type NonIndex<T extends PropertyKey> = {} extends Record<T, any> ? never : T;
                type DistributiveNonIndex<T extends PropertyKey> = T extends unknown ? NonIndex<T> : never;
    
                type Remapped = { [K in keyof Orig as DistributiveNonIndex<K>]: any }
                /* type Remapped = {
                    str: any;
                    [sym]: any;
                } */
                // no string index signature, right?
    
                type Oops = keyof Remapped;
                let x: Oops;
                x = sym;
                x = "str";
                x = "whatever"; // error
            }

            export {};
            """,
            directives = "// @target: es6",
        ).filter { it.code == 2322 }
        // Both references report exactly ONE row here, at the LAST line of `g`
        // (`x = "whatever"`). The two rows this pin exists for sat at `a = sym` inside
        // `f` and `g`, where `Okay` is `keyof Orig` and `Orig` did not resolve — put
        // `keyof errorType` back to `stringType` and they return.
        assert(d.map { it.line } == listOf(69))
    }

    /**
     * The SIBLING of the pin above, and a SECOND shape whose key domain is open: an
     * INTERSECTION one of whose constituents is a bare type parameter. `keyof (X & T)`
     * cannot be enumerated here — tsc defers it as an `Index` type and we cannot — so
     * answering the closed domain of the half we CAN see is the same round-463 error,
     * and it produces the same false TS2322 at `a = sym`.
     *
     * It needs its own pin because it is a genuinely different input: `Orig` RESOLVES
     * here (to a `Type.Intersection`), where the fixture above makes it `errorType`. The
     * ablation is what established that — arm A8 (this arm disabled, the `errorType` arm
     * intact) leaves every other pin in this class GREEN while re-emitting the row, so
     * without this pin the guard would read as redundant and could be deleted.
     *
     * `@useRealLibs` is load-bearing: `Symbol("")` must produce a real `unique symbol`,
     * and the embedded lib does not declare `Symbol`.
     */
    @Test
    fun `keyof over an intersection with a type parameter does not close the key domain`() {
        val d = diagnose(
            """
            const sym = Symbol("")
            export function outerFn<T>(): void {
                type Orig = { [k: string]: any, str: any, [sym]: any } & T;
                type Okay = keyof Orig;
                let a: Okay;
                a = sym;
                a = "str";
            }
            """,
            directives = "// @strict: true\n// @target: es2020\n// @useRealLibs: true",
        ).filter { it.code == 2322 }
        assert(d.isEmpty())
    }

    /**
     * The CONTAINMENT control, and the whole soundness argument in one fixture:
     * `declareLexical` refuses any name the main binder already bound in that
     * container, so a conventionally-bound name is absent from `scope.symbols`
     * everywhere and keeps resolving exactly as it did. Here the function body declares
     * NOTHING named `ZzzShape`, so the file-level interface must still answer — and it
     * must answer with its OWN member, which is what the mis-assignment prints.
     *
     * **NO ARM OF ITS OWN, MEASURED: it is a NEGATIVE control and stays green under all
     * eight.** A6 (the forbidden [LexicalScope.existing] read) was expected to redden it
     * and does not, because in this fixture the `existing` hit IS the file-level symbol —
     * the same answer by a wrong route, which no compile-level assertion can see. The
     * `existing` rule is pinned where it is a VALUE, in [LexicalScopeResolverTest]. What
     * this pin is for is the other direction: it fails if the widening ever starts letting
     * scope space shadow a bound name.
     */
    @Test
    fun `a conventionally bound name is untouched by the scope space consult`() {
        val d = diagnose(
            """
            interface ZzzShape { outerOnly: number }
            export function outerFn(): void {
                class ZzzUnrelated {}
                const good: ZzzShape = { outerOnly: 1 }
                const bad: ZzzShape = { nope: 2 }
            }
            """,
        )
        val messages = d.map { it.message }
        assert(
            messages == listOf(
                "Object literal may only specify known properties, and 'nope' does not exist in type 'ZzzShape'.",
            )
        )
    }
}
