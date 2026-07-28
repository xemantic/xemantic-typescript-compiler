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
 * Round 744: narrowing a reference whose type is a bare TYPE PARAMETER by a type
 * predicate must INTERSECT, never REPLACE.
 *
 * tsc's `getNarrowedType` can never take either subtype arm for a type parameter —
 * nothing is a subtype of a bare `T` and `T` is a subtype of nothing — so it falls to
 * the *instantiable* tail and answers `T & candidate`, per candidate constituent. Ours
 * answered "the candidate is assignable to `T`" whenever the candidate was a UNION (a
 * single-type candidate was correctly rejected — the two lenience directions cancel
 * there and compound here), so the guard handed back the whole candidate and DROPPED
 * `T`. The branch join then no longer carried the type parameter at all.
 *
 * Found as a compiler-profile false positive at tsc
 * `src/compiler/transformers/declarations.ts:846` (`rewriteModuleSpecifier`), whose
 * message named neither the real cause nor the real source constituent: it reported the
 * *declared* `T | undefined` against `T | StringLiteral` while the actual join type was
 * `T | StringLiteral | NoSubstitutionTemplateLiteral`. The shape below is that site with
 * the enums, the discriminants and the body removed — none of them are load-bearing, and
 * every one of them was tried as an explanation first.
 */
class TypeParamTypeGuardNarrowingTest {

    private val prelude = """
        interface Node { readonly tag: string }
        interface StringLiteral extends Node { text: string }
        interface NoSub extends Node { num: number }
        declare function isEither(node: Node): node is StringLiteral | NoSub
        declare function isOne(node: Node): node is StringLiteral

    """.trimIndent()

    /**
     * The pin. An empty guard body is deliberate — the defect is in the branch JOIN,
     * not in anything the branch does, and an empty body proves it.
     */
    @Test
    fun `a union type guard on a type parameter leaves the parameter in the branch join`() {
        diagnose(
            prelude + """
            export function f<T extends Node>(input: T | undefined): T | StringLiteral {
                if (!input) return undefined!;
                if (isEither(input)) { }
                return input;
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * The same shape with a SINGLE-type candidate, which passed before round 744 only
     * because a second lenience (`T` relates to any object target) cancelled the first.
     * Pinned so a later tightening of either one cannot silently break the other.
     */
    @Test
    fun `a single type guard on a type parameter leaves the parameter in the branch join`() {
        diagnose(
            prelude + """
            export function f<T extends Node>(input: T | undefined): T | StringLiteral {
                if (!input) return undefined!;
                if (isOne(input)) { }
                return input;
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * The intersection has to keep RESOLVING the candidate's members — returning the
     * bare type parameter would also fix the pin above and would be wrong, because the
     * guarded branch would lose `text`.
     */
    @Test
    fun `the guarded branch still resolves the candidate's own members`() {
        diagnose(
            prelude + """
            export function f<T extends Node>(input: T): string {
                if (isOne(input)) { return input.text; }
                return input.tag;
            }
            """,
        ) should { have(none { it.code == 2339 }) }
    }

    /**
     * And it must keep resolving the type parameter's CONSTRAINT members inside the
     * guarded branch — `T & StringLiteral` carries both sides, `StringLiteral` alone
     * would carry only one.
     */
    @Test
    fun `the guarded branch still resolves the constraint's members`() {
        diagnose(
            prelude + """
            export function f<T extends Node>(input: T): string {
                if (isOne(input)) { return input.tag + input.text; }
                return input.tag;
            }
            """,
        ) should { have(none { it.code == 2339 }) }
    }

    /**
     * Negative control — the intersection must carry the candidate's REAL member types,
     * not just their names, so a wrong use of a guarded member is still an error.
     */
    @Test
    fun `negative control - a guarded member keeps its own type`() {
        diagnose(
            prelude + """
            export function f<T extends Node>(input: T): void {
                if (isOne(input)) { const bad: number = input.text; }
            }
            """,
        ) should { have(any { it.code == 2322 }) }
    }

    /** The same on the CONSTRAINT side of the intersection. */
    @Test
    fun `negative control - a constraint member keeps its own type inside the guard`() {
        diagnose(
            prelude + """
            export function f<T extends Node>(input: T): void {
                if (isOne(input)) { const bad: number = input.tag; }
            }
            """,
        ) should { have(any { it.code == 2322 }) }
    }
}
