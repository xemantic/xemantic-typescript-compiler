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
 * An object-literal argument with a STRING-LITERAL property value, passed to an
 * OVERLOADED callee whose parameter declares that property as a literal union.
 *
 * We have no fresh-literal machinery: `getTypeOfExpression` types `"sort"` as `string`.
 * The single-signature path contextually types the literal and is fine, but overload
 * resolution compares each candidate against the raw argument type, so
 * `{ usage: "sort" }` failed a `usage?: "sort" | "search"` parameter on EVERY overload
 * and produced TS2769. Round 728 gives the existing `overloadingOnConstants2` rule (keep
 * the literal against a LITERAL parameter) its per-property analogue, evaluated only
 * after the relation has already failed.
 *
 * Found as one of (LIB.1)'s remaining seven real-lib false positives — tsc's own
 * `new Intl.Collator(locale, { usage: "sort", sensitivity: "variant", numeric: true })`
 * (core.ts), where `Intl.CollatorConstructor` has two `new` overloads.
 *
 * The controls are what keep the rule from becoming "an object literal always matches an
 * overload": a wrong literal VALUE, an EXCESS property and a wrongly typed NON-literal
 * property must all still reject, and only an overloaded callee is affected — the
 * single-signature shape must stay silent for the reason it always was.
 */
class OverloadObjectLiteralLiteralPropTest {

    private val prelude = """
        interface COpts { usage?: "sort" | "search" | undefined; numeric?: boolean | undefined }
        interface Fn2 {
            (locales?: string, o?: COpts): void
            (locales?: readonly string[], o?: COpts): void
        }
        declare const f: Fn2
        declare const loc: string | undefined

    """.trimIndent()

    @Test
    fun `an object literal with a literal property matches an overload`() {
        val diagnostics = diagnose(prelude + """f(loc, { usage: "sort", numeric: true })""")
        assert(diagnostics.none { it.code == 2769 })
    }

    @Test
    fun `the same object literal matches an overloaded construct signature`() {
        val diagnostics = diagnose(
            """
            interface COpts { usage?: "sort" | "search" | undefined; numeric?: boolean | undefined }
            interface Ctor {
                new (locales?: string, o?: COpts): object
                new (locales?: readonly string[], o?: COpts): object
            }
            declare const C: Ctor
            declare const loc: string | undefined
            const c = new C(loc, { usage: "sort", numeric: true })
            """,
        )
        assert(diagnostics.none { it.code == 2769 })
    }

    @Test
    fun `control - a wrong literal value is still rejected`() {
        val diagnostics = diagnose(prelude + """f(loc, { usage: "nope" })""")
        assert(diagnostics.any { it.code == 2769 })
    }

    @Test
    fun `control - an excess property is still rejected`() {
        val diagnostics = diagnose(prelude + """f(loc, { usage: "sort", bogus: 1 })""")
        assert(diagnostics.any { it.code == 2769 })
    }

    @Test
    fun `control - a wrongly typed non-literal property is still rejected`() {
        val diagnostics = diagnose(prelude + """f(loc, { numeric: "yes" })""")
        assert(diagnostics.any { it.code == 2769 })
    }

    @Test
    fun `control - a missing required property is still rejected`() {
        val diagnostics = diagnose(
            """
            interface COpts { usage?: "sort" | "search" | undefined; must: number }
            interface Fn2 {
                (locales?: string, o?: COpts): void
                (locales?: readonly string[], o?: COpts): void
            }
            declare const f: Fn2
            declare const loc: string | undefined
            f(loc, { usage: "sort" })
            """,
        )
        assert(diagnostics.any { it.code == 2769 })
    }

    @Test
    fun `a single-signature callee accepts the same object literal`() {
        val diagnostics = diagnose(
            """
            interface COpts { usage?: "sort" | "search" | undefined; numeric?: boolean | undefined }
            declare function f1(o?: COpts): void
            f1({ usage: "sort", numeric: true })
            """,
        )
        assert(diagnostics.isEmpty())
    }
}
