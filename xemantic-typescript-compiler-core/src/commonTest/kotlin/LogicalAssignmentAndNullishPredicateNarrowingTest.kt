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
 * (CHK.173) G3 + G6 — two flow-narrowing gaps that were false positives on the element-read
 * channel (`x['k']` reported TS18048 where tsgo is silent).
 *
 * G3: `x ??= v` / `x ||= v` with a right-hand side only the four resolving arms of
 * `narrowByAssignmentRhs` can type (a member read, an identifier, a conditional, an element
 * read) now narrows `x` to the antecedent's non-nullish (truthy) part joined with the declared
 * type reduced by `v`'s type — tsgo's `getAssignmentReducedType` join. `&&=` is untouched.
 *
 * G6: an un-annotated single-parameter arrow passed to a type-guard overload (`filter`) whose
 * body — an expression, or a block that is exactly `{ return <expr> }` — is a nullish test
 * (`p !== undefined` / `void 0`, `p !== null`, `p != null`, either operand order, `!!p`) infers
 * the predicate tsgo's `checkIfExpressionRefinesParameter` infers: the tested nullish members
 * are dropped, and for `!!p` every kept member must be never-falsy. `Boolean(p)` and
 * `filter(Boolean)` infer nothing in tsgo and stay that way here.
 *
 * Every expectation is tsgo 7.0.2's over the same source (code, 1-based position, message) —
 * the directive header does not count toward the line numbers.
 */
class LogicalAssignmentAndNullishPredicateNarrowingTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private fun rows(d: List<Diagnostic>): List<String> =
        d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `a nullish assignment from a member read narrows the reference`() {
        val d = diagnose(
            """
            interface M { size: number }
            export function f(s: { m: M }, m: M | undefined) {
              m ??= s.m;
              use(m['size']);
            }
            declare function use(v: unknown): void;
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a nullish assignment from an identifier narrows the reference`() {
        val d = diagnose(
            """
            interface M { size: number }
            export function f(o: M, m: M | undefined) {
              m ??= o;
              use(m['size']);
            }
            declare function use(v: unknown): void;
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a logical-or assignment from a member read narrows the reference`() {
        val d = diagnose(
            """
            interface M { size: number }
            export function f(s: { m: M }, m: M | undefined) {
              m ||= s.m;
              use(m['size']);
            }
            declare function use(v: unknown): void;
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a nullish assignment used as a value still narrows the reference after it`() {
        val d = diagnose(
            """
            interface M { size: number }
            export function f(s: { m: M }, m: M | undefined) {
              use((m ??= s.m));
              use(m['size']);
            }
            declare function use(v: unknown): void;
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a nullish assignment to a property path narrows that path`() {
        val d = diagnose(
            """
            interface M { size: number }
            interface S { cache: M | undefined }
            export function f(st: S, m: M) {
              st.cache ??= m;
              use(st.cache['size']);
            }
            declare function use(v: unknown): void;
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `the narrowed type after a nullish assignment drops undefined in the message`() {
        val d = diagnose(
            """
            interface M { size: number }
            export function f(s: { m: M }, m: M | undefined) {
              m ??= s.m;
              const b: boolean = m;
            }
            """,
            directives,
        )
        assert(
            rows(d) == listOf(
                "4:9 TS2322 Type 'M' is not assignable to type 'boolean'.",
            ),
        )
    }

    @Test
    fun `control - a nullable right-hand side keeps the reference possibly undefined`() {
        val d = diagnose(
            """
            interface M { size: number }
            export function f(s: { mu?: M }, m: M | undefined) {
              m ??= s.mu;
              use(m['size']);
            }
            declare function use(v: unknown): void;
            """,
            directives,
        )
        assert(
            rows(d) == listOf(
                "4:7 TS18048 'm' is possibly 'undefined'.",
            ),
        )
    }

    @Test
    fun `control - a logical-and assignment does not narrow away undefined`() {
        val d = diagnose(
            """
            interface M { size: number }
            export function f(s: { m: M }, m: M | undefined) {
              m &&= s.m;
              use(m['size']);
            }
            declare function use(v: unknown): void;
            """,
            directives,
        )
        assert(
            rows(d) == listOf(
                "4:7 TS18048 'm' is possibly 'undefined'.",
            ),
        )
    }

    @Test
    fun `a logical-or assignment of a number keeps number without undefined`() {
        val d = diagnose(
            """
            export function f(s: { n: number }, n: number | undefined) {
              n ||= s.n;
              const b: boolean = n;
            }
            """,
            directives,
        )
        assert(
            rows(d) == listOf(
                "3:9 TS2322 Type 'number' is not assignable to type 'boolean'.",
            ),
        )
    }

    @Test
    fun `a nullish assignment joins the kept antecedent with the assigned member`() {
        val d = diagnose(
            """
            interface M { size: number }
            interface N { name: string }
            export function f(s: { n: N }, m: M | N | undefined) {
              m ??= s.n;
              const b: boolean = m;
            }
            """,
            directives,
        )
        assert(
            rows(d) == listOf(
                "5:9 TS2322 Type 'M | N' is not assignable to type 'boolean'.",
            ),
        )
    }

    @Test
    fun `filter with a strict undefined test infers a predicate`() {
        val d = diagnose(
            """
            interface R { fileName: string }
            export function f(xs: readonly (R | undefined)[]) {
              const ok = xs.filter(x => x !== undefined);
              return ok.map(x => x['fileName']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `filter with a double negation over an object element infers a predicate`() {
        val d = diagnose(
            """
            interface R { fileName: string }
            export function f(xs: readonly (R | undefined)[]) {
              const ok = xs.filter(x => !!x);
              return ok.map(x => x['fileName']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `filter with a loose null test infers a predicate`() {
        val d = diagnose(
            """
            interface R { fileName: string }
            export function f(xs: readonly (R | undefined)[]) {
              const ok = xs.filter(x => x != null);
              return ok.map(x => x['fileName']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `filter with a single-return block body infers a predicate`() {
        val d = diagnose(
            """
            interface R { fileName: string }
            export function f(xs: readonly (R | undefined)[]) {
              const ok = xs.filter(x => { return x !== undefined });
              return ok.map(x => x['fileName']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `inferred nullish predicates type the filter result as tsgo does`() {
        val d = diagnose(
            """
            interface R { fileName: string }
            declare const ys: readonly (R | null | undefined)[];
            declare const ls: readonly ("a" | 0 | R | undefined)[];
            declare const ss: readonly (string | undefined)[];
            export const a1: number = ys.filter(x => x !== undefined);
            export const a2: number = ys.filter(x => undefined !== x);
            export const a3: number = ys.filter(x => null != x);
            export const a4: number = ys.filter(x => !!x);
            export const a5: number = ls.filter(x => !!x);
            export const a6: number = ss.filter(x => x !== undefined);
            export const a7: number = ys.filter(x => (x !== void 0));
            export const a8: number = ys.filter(x => x !== null);
            """,
            directives,
        )
        assert(
            rows(d) == listOf(
                "10:14 TS2322 Type 'string[]' is not assignable to type 'number'.",
                "11:14 TS2322 Type '(R | null)[]' is not assignable to type 'number'.",
                "12:14 TS2322 Type '(R | undefined)[]' is not assignable to type 'number'.",
                "5:14 TS2322 Type '(R | null)[]' is not assignable to type 'number'.",
                "6:14 TS2322 Type '(R | null)[]' is not assignable to type 'number'.",
                "7:14 TS2322 Type 'R[]' is not assignable to type 'number'.",
                "8:14 TS2322 Type 'R[]' is not assignable to type 'number'.",
                "9:14 TS2322 Type '(\"a\" | R)[]' is not assignable to type 'number'.",
            ),
        )
    }

    @Test
    fun `control - shapes tsgo infers no predicate for keep the element type`() {
        val d = diagnose(
            """
            interface R { fileName: string }
            declare const xs: readonly (R | undefined)[];
            declare const ss: readonly (string | undefined)[];
            declare const us: readonly ({} | undefined)[];
            export const b1: number = xs.filter(x => Boolean(x));
            export const b2: number = xs.filter(x => x !== null);
            export const b3: number = xs.filter(Boolean);
            export const b4: number = ss.filter(x => !!x);
            export const b5: number = xs.filter(x => { if (x) return true; return x !== undefined });
            export const b6: number = us.filter(x => !!x);
            """,
            directives,
        )
        assert(
            rows(d) == listOf(
                "10:14 TS2322 Type '({} | undefined)[]' is not assignable to type 'number'.",
                "5:14 TS2322 Type '(R | undefined)[]' is not assignable to type 'number'.",
                "6:14 TS2322 Type '(R | undefined)[]' is not assignable to type 'number'.",
                "7:14 TS2322 Type '(R | undefined)[]' is not assignable to type 'number'.",
                "8:14 TS2322 Type '(string | undefined)[]' is not assignable to type 'number'.",
                "9:14 TS2322 Type '(R | undefined)[]' is not assignable to type 'number'.",
            ),
        )
    }

    @Test
    fun `control - filter Boolean keeps the element possibly undefined`() {
        val d = diagnose(
            """
            interface R { fileName: string }
            export function f(xs: readonly (R | undefined)[]) {
              const ok = xs.filter(Boolean);
              return ok.map(x => x['fileName']);
            }
            """,
            directives,
        )
        assert(
            rows(d) == listOf(
                "4:22 TS18048 'x' is possibly 'undefined'.",
            ),
        )
    }

    @Test
    fun `control - a double negation over a string element infers nothing`() {
        val d = diagnose(
            """
            export function f(xs: readonly (string | undefined)[]) {
              const ok = xs.filter(x => !!x);
              return ok.map(x => x['length']);
            }
            """,
            directives,
        )
        assert(
            rows(d) == listOf(
                "3:22 TS18048 'x' is possibly 'undefined'.",
            ),
        )
    }
}
