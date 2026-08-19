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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.22) round 946 — **THE `for...of` / ARRAY-SPREAD OPERAND'S `[Symbol.iterator]`
 * RETURN WAS NEVER CHECKED, AT ANY TARGET.**
 *
 * tsc's rule, read from its own `checker.ts`
 * (`getIteratedTypeOrElementType` -> `getIterationTypesOfIterable` ->
 * `getIterationTypesOfIterableSlow` -> `getIterationTypesOfIteratorWorker` ->
 * `getIterationTypesOfMethod`): under **uplevel iteration** the operand's
 * `[Symbol.iterator]` member must be NON-OPTIONAL, must have a signature callable
 * with zero arguments, and that signature's RETURN type must itself carry a `next`
 * method — a missing `next` adds the related **TS2489 `An iterator must have a
 * 'next()' method.`** to the root **TS2488**.
 *
 * Round 945 measured this compiler SILENT for all three pristine shapes at `es5`,
 * unset, `es2015` and `esnext` alike, which is what re-filed it out of (CHK.21): no
 * gate was suppressing them, the check did not exist.
 *
 * **MOST OF THIS CLASS IS NEGATIVE, AND THAT IS THE POINT.** The landed check is
 * POSITIVE-EVIDENCE-ONLY: it fires only when the `[Symbol.iterator]` member is FOUND
 * and provably broken, and bails on every question it cannot answer. Reproducing
 * tsc's other half — rejecting a type that declares NO `[Symbol.iterator]` — needs a
 * complete model of what is iterable, and one gap in such a model is a false positive
 * on the most common construct in the language. The bails are therefore false
 * NEGATIVES by construction, and each is pinned below so a later widening has to move
 * a named pin rather than discover the population by accident.
 *
 * Note which lib each pin runs on. [diagnose] uses the EMBEDDED lib, whose ONLY
 * declaration of `[Symbol.iterator]` in the whole lib is
 * `interface IterableIterator<T> extends Iterator<T>` — so the built-in-iterable
 * negatives below must ask for `@useRealLibs` or they measure nothing.
 */
class IterableOperandProtocolTest {

    /** Real libs, and an unset target — i.e. `defaultedTarget` = the latest standard,
     *  which is where the check is live. */
    private val realLibs = "// @strict: true\n// @useRealLibs: true"

    // ── the three pristine shapes ────────────────────────────────────────────

    @Test
    fun `a class whose Symbol iterator returns this is TS2488 in a for-of`() {
        // pristine for-of16.ts, both lines.
        diagnose(
            """
                class MyStringIterator {
                    [Symbol.iterator]() {
                        return this;
                    }
                }
                var v: string;
                for (v of new MyStringIterator) { }
                for (v of new MyStringIterator) { }
            """
        ) should {
            have(count { it.code == 2488 } == 2)
            have(any {
                it.code == 2488 &&
                    it.message == "Type 'MyStringIterator' must have a " +
                    "'[Symbol.iterator]()' method that returns an iterator."
            })
        }
    }

    @Test
    fun `the missing-next TS2488 carries the related TS2489`() {
        diagnose(
            """
                class MyStringIterator {
                    [Symbol.iterator]() {
                        return this;
                    }
                }
                var v: string;
                for (v of new MyStringIterator) { }
            """
        ) should {
            have(any {
                it.code == 2488 &&
                    it.relatedInformation.size == 1 &&
                    it.relatedInformation[0].code == 2489 &&
                    it.relatedInformation[0].message == "An iterator must have a 'next()' method."
            })
        }
    }

    @Test
    fun `an OPTIONAL Symbol iterator member is TS2488`() {
        // pristine for-of29.ts.
        diagnose(
            """
                declare var iterableWithOptionalIterator: {
                    [Symbol.iterator]?(): { next(): any }
                };
                for (var v of iterableWithOptionalIterator) { }
            """
        ) should {
            have(count { it.code == 2488 } == 1)
        }
    }

    @Test
    fun `an OPTIONAL Symbol iterator member carries NO related information`() {
        // The two failure kinds are distinct in pristine: for-of29's baseline has no
        // `!!! related` line, because tsc never reaches the `next` question there.
        diagnose(
            """
                declare var iterableWithOptionalIterator: {
                    [Symbol.iterator]?(): { next(): any }
                };
                for (var v of iterableWithOptionalIterator) { }
            """
        ) should {
            have(any { it.code == 2488 && it.relatedInformation.isEmpty() })
        }
    }

    @Test
    fun `an ARRAY-LITERAL SPREAD of the same class is TS2488`() {
        // pristine iteratorSpreadInArray10.ts.
        diagnose(
            """
                class SymbolIterator {
                    [Symbol.iterator]() {
                        return this;
                    }
                }
                var array = [...new SymbolIterator];
            """
        ) should {
            have(count { it.code == 2488 } == 1)
        }
    }

    @Test
    fun `a this RETURN ANNOTATION is the same failure as a this-returning body`() {
        diagnose(
            """
                class Ann { [Symbol.iterator](): this { return this; } }
                declare const a: Ann;
                for (const x of a) { }
            """
        ) should {
            have(count { it.code == 2488 } == 1)
        }
    }

    // ── the BOUND of each positive ───────────────────────────────────────────

    @Test
    fun `a class returning this that DOES declare next is silent`() {
        // The bound of the this-return route: the carrier IS the iterator here.
        diagnose(
            """
                class Ok { [Symbol.iterator]() { return this; } next(): any { return null; } }
                declare const o: Ok;
                for (const x of o) { }
                const s = [...o];
            """
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `a class whose Symbol iterator returns an object with next is silent`() {
        diagnose(
            """
                class Ok { [Symbol.iterator]() { return { next(): any { return null; } }; } }
                declare const o: Ok;
                for (const x of o) { }
                const s = [...o];
            """
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `an interface whose Symbol iterator returns an iterator is silent`() {
        diagnose(
            """
                interface Ifc { [Symbol.iterator](): { next(): any } }
                declare const i: Ifc;
                for (const x of i) { }
            """
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `a REQUIRED Symbol iterator is not confused with an optional one`() {
        diagnose(
            """
                declare var it: { [Symbol.iterator](): { next(): any } };
                for (var v of it) { }
            """
        ) should {
            have(none { it.code == 2488 })
        }
    }

    // ── the built-in iterables, on the REAL libs ─────────────────────────────

    @Test
    fun `an array a readonly array and a tuple are silent`() {
        diagnose(
            """
                declare const a: number[];
                declare const r: readonly number[];
                declare const t: [number, string];
                for (const x of a) { }
                for (const x of r) { }
                for (const x of t) { }
                const s = [...a, ...r, ...t];
            """,
            directives = realLibs,
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `a string a Set and a Map are silent`() {
        diagnose(
            """
                declare const s: string;
                declare const st: Set<number>;
                declare const mp: Map<string, number>;
                for (const x of s) { }
                for (const x of st) { }
                for (const x of mp) { }
                const sp = [...s, ...st];
            """,
            directives = realLibs,
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `an Iterable an IterableIterator and a Generator are silent`() {
        diagnose(
            """
                declare const i: Iterable<number>;
                declare const ii: IterableIterator<number>;
                declare const g: Generator<number>;
                for (const x of i) { }
                for (const x of ii) { }
                for (const x of g) { }
                const s = [...i, ...ii, ...g];
            """,
            directives = realLibs,
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `a generator method declared with an asterisk is silent`() {
        diagnose(
            """
                class Gen { *[Symbol.iterator]() { yield 1; } }
                declare const g: Gen;
                for (const x of g) { }
                const s = [...g];
            """,
            directives = realLibs,
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `a UNION of iterables is silent`() {
        diagnose(
            """
                declare const u: number[] | Set<number>;
                for (const x of u) { }
                const s = [...u];
            """,
            directives = realLibs,
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `a type PARAMETER constrained to an iterable is silent`() {
        diagnose(
            """
                function f<T extends Iterable<number>>(t: T) {
                    for (const x of t) { }
                    return [...t];
                }
            """,
            directives = realLibs,
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `an any operand is silent`() {
        diagnose(
            """
                declare const a: any;
                for (const x of a) { }
                const s = [...a];
            """,
            directives = realLibs,
        ) should {
            have(none { it.code == 2488 })
        }
    }

    // ── the deliberate BAILS — every one of these is a FALSE NEGATIVE ────────

    @Test
    fun `a type declaring NO Symbol iterator at all is silent - the scoped-out half`() {
        // tsc reports TS2488 here. Reproducing it needs a complete model of what is
        // iterable; until then this is a documented false negative, not an oversight.
        diagnose(
            """
                class Plain { p: number = 1; }
                declare const p: Plain;
                for (const x of p) { }
            """
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `an OPAQUE member-less iterator return is silent`() {
        // An empty member table means "not resolved", not "has no next" — the embedded
        // lib declares `interface ArrayIterator<T> { }` exactly that way, so treating
        // an empty table as evidence would light up every array in the corpus.
        diagnose(
            """
                interface Opaque { }
                class Opq { [Symbol.iterator](): Opaque { return null as any; } }
                declare const o: Opq;
                for (const x of o) { }
            """
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `an iterator return carrying a STRING INDEX SIGNATURE is silent`() {
        diagnose(
            """
                interface Idx { [k: string]: any; other: number }
                class Sidx { [Symbol.iterator](): Idx { return null as any; } }
                declare const s: Sidx;
                for (const x of s) { }
            """
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `an OPTIONAL next on the iterator is silent`() {
        diagnose(
            """
                interface OptNext { next?(): any }
                class ONext { [Symbol.iterator](): OptNext { return null as any; } }
                declare const o: ONext;
                for (const x of o) { }
            """
        ) should {
            have(none { it.code == 2488 })
        }
    }

    // ── no overlap with the checks that already own a position ───────────────

    @Test
    fun `a Symbol iterator REQUIRING an argument stays with the B438e walker - one TS2488 not two`() {
        diagnose(
            """
                var obj = { [Symbol.iterator](x: number) { return { next(): any { return null; } }; } };
                for (const a of obj) { }
            """
        ) should {
            have(count { it.code == 2488 } == 1)
        }
    }

    @Test
    fun `a Symbol iterator REQUIRING an argument on a CLASS is silent - a documented false negative`() {
        // tsc reports TS2488 here with a TS2322 related chain. B438e's walker owns the
        // object-literal spelling of the same defect and cannot see a class, so nothing
        // emits — the `zeroArg` filter refuses the population rather than guessing at
        // the elaboration.
        diagnose(
            """
                class ArgIter { [Symbol.iterator](x: number) { return this; } }
                declare const a: ArgIter;
                for (const x of a) { }
            """
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `a for-await-of operand is not checked here - TS2504 owns the async protocol`() {
        diagnose(
            """
                class Bad { [Symbol.iterator]() { return this; } }
                declare const b: Bad;
                async function f() { for await (const x of b) { } }
            """
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `a CALL-argument spread is not checked - the construct is scoped out`() {
        diagnose(
            """
                class Bad { [Symbol.iterator]() { return this; } }
                declare const b: Bad;
                declare function f(...xs: number[]): void;
                f(...b);
            """
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `an array DESTRUCTURING is not checked - the construct is scoped out`() {
        diagnose(
            """
                class Bad { [Symbol.iterator]() { return this; } }
                declare const b: Bad;
                const [first] = b;
            """
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `a yield-star operand is not checked - the construct is scoped out`() {
        diagnose(
            """
                class Bad { [Symbol.iterator]() { return this; } }
                declare const b: Bad;
                function* g() { yield* b; }
            """
        ) should {
            have(none { it.code == 2488 })
        }
    }

    // ── the target / lib gate ────────────────────────────────────────────────

    @Test
    fun `an es5-only lib leaves the position to the array-like leg`() {
        // `spineForOfNonIterableActive`'s condition inverted: with the ES2015+ libs
        // excluded tsc's `uplevelIteration` is false and TS2495 / TS2461 own the
        // position, so TS2488 must not fire.
        diagnose(
            """
                class Bad { [Symbol.iterator]() { return this; } }
                declare const b: Bad;
                for (const x of b) { }
            """,
            directives = "// @strict: true\n// @lib: es5",
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `noLib leaves the position alone`() {
        diagnose(
            """
                class Bad { [Symbol.iterator]() { return this; } }
                declare const b: Bad;
                for (const x of b) { }
            """,
            directives = "// @strict: true\n// @noLib: true",
        ) should {
            have(none { it.code == 2488 })
        }
    }

    @Test
    fun `a declaration file is not checked`() {
        // The `for...of` is what makes this pin DISCRIMINATE: a fixture holding only the
        // declarations has no iteration position at all and is green whatever the gate
        // does. Our parser accepts a statement in an ambient file, so the shape exists.
        diagnose(
            """
                declare class Bad { [Symbol.iterator](): Bad; }
                declare const b: Bad;
                for (const x of b) { }
            """,
            fileName = "t.d.ts",
        ) should {
            have(none { it.code == 2488 })
        }
    }
}
