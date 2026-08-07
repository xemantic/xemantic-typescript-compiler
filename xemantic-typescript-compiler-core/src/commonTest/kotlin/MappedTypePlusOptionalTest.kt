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
 * `Partial<T>` — the standard `{ [P in keyof T]?: T[P] }` — must ADD optionality.
 *
 * A mapped type has FOUR modifiers and the materializer recorded only three: `readonly`
 * and `-readonly` since M1.10, `-?` since round 718 — and never the plain `?`. A
 * homomorphic mapped member CARRIES ITS SOURCE DECLARATION (for the "declared here"
 * related info), and `isOptionalProperty` reads optionality off that declaration, so a
 * `Partial<T>` member of a REQUIRED source property stayed required: an object literal
 * omitting it was rejected with "Property 'x' is missing in type '…' but required in
 * type 'Partial<…>'".
 *
 * Found round 728 as one of (LIB.1)'s remaining seven real-lib false positives — tsc's
 * own `lookupFromPackageJson(): Partial<CreateSourceFileOptions>` returning an object
 * literal without the source-required `languageVersion` (program.ts).
 *
 * Unlike the `-?` case pinned by [MappedTypeMinusOptionalTest], this one DOES reproduce
 * on a hand-rolled mapped type, so most cases here need no `@useRealLibs` — but one runs
 * against the real lib's own `Partial` so the pin covers the declaration the false
 * positive actually came from.
 *
 * The marker is a [SymbolFlags.MappedOptional] BIT rather than the id-keyed side-channel
 * its `-?` sibling uses, because the arm that must consult it is the hot one: every
 * declared-REQUIRED property in the program reaches it.
 */
class MappedTypePlusOptionalTest {

    private val realLibs = "// @strict: true\n// @useRealLibs: true\n// @target: es2015"

    @Test
    fun `a question-mark mapped type makes a required source property optional`() {
        val diagnostics = diagnose(
            """
            interface Opts { languageVersion: number; impliedNodeFormat?: string }
            type MyPartial<T> = { [P in keyof T]?: T[P] }
            function g(): MyPartial<Opts> { return { impliedNodeFormat: "x" } }
            """,
        )
        assert(diagnostics.none { it.code == 2322 })
        assert(diagnostics.none { it.code == 2741 })
    }

    @Test
    fun `the real lib Partial makes a required source property optional`() {
        val diagnostics = diagnose(
            """
            interface Opts { languageVersion: number; impliedNodeFormat?: string }
            function g(): Partial<Opts> { return { impliedNodeFormat: "x" } }
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2322 })
        assert(diagnostics.none { it.code == 2741 })
    }

    @Test
    fun `an empty object literal satisfies a question-mark mapped type`() {
        val diagnostics = diagnose(
            """
            interface Opts { languageVersion: number; impliedNodeFormat?: string }
            type MyPartial<T> = { [P in keyof T]?: T[P] }
            const o: MyPartial<Opts> = { }
            """,
        )
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `control - the same mapped type without the question mark still requires the property`() {
        val diagnostics = diagnose(
            """
            interface Opts { languageVersion: number; impliedNodeFormat?: string }
            type MyIdent<T> = { [P in keyof T]: T[P] }
            function g(): MyIdent<Opts> { return { impliedNodeFormat: "x" } }
            """,
        )
        assert(diagnostics.any { it.code == 2322 || it.code == 2741 })
    }

    @Test
    fun `control - a wrongly typed property through the mapped type is still rejected`() {
        val diagnostics = diagnose(
            """
            interface Opts { languageVersion: number; impliedNodeFormat?: string }
            type MyPartial<T> = { [P in keyof T]?: T[P] }
            function g(): MyPartial<Opts> { return { languageVersion: "no" } }
            """,
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    @Test
    fun `control - a minus-question mapped type still strips optionality`() {
        val diagnostics = diagnose(
            """
            interface Tracker { report?(n: number): void }
            declare const t: Required<Tracker>
            t.report(1)
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2722 })
    }
}
