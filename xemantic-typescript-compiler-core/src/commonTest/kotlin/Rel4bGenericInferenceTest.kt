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
 * (REL.4)(b) round 779 — the two defects behind `Debug.assertIsDefined` /
 * `Debug.checkDefined`, which cost 4 false positives on the compiler profile and
 * 7 on services once (REL.4)'s namespace-callee flip is scaffolded in.
 *
 * **Defect 1 — an inferred bare type parameter is not a requirement.**
 * `tryEmitOptionalMemberArgVsRequiredNamedTs2345` synthesizes the `| undefined`
 * of an optional member locally and fires when undefined is the SOLE failure
 * against a required NAMED parameter. For `assertIsDefined<T>(value: T)` the
 * named parameter type is one WE inferred from the very argument being judged, so
 * the premise "the parameter independently requires this type" is false — a bare
 * type parameter requires nothing, and tsc accepts every one of these calls.
 *
 * **Defect 2 — a `tp | null | undefined` parameter must strip ALL nullish
 * constituents from the source before inferring `tp`.** The round-428 rule did so
 * only when exactly ONE non-nullish member survived, so a multi-member source
 * (tsc's `__String | undefined`, whose `__String` is itself a 3-member union)
 * bound `tp` to the whole nullish-carrying union and `checkDefined`'s return type
 * kept `undefined`.
 *
 * Five of these ten pins DISCRIMINATE — measured against a binary with
 * `REL4B_GATE` flipped to `false`, not assumed.
 */
class Rel4bGenericInferenceTest {

    private val optionalMemberPrelude = """
        interface Decl { d: number; }
        interface Sym { valueDeclaration?: Decl; }
        declare const s: Sym;
        declare const anyDecl: Decl;
        declare function assertIsDefinedLocal<TAD>(value: TAD): void;
        declare function assertConstrained<TC extends Decl>(value: TC): void;
        declare function needsDecl(value: Decl): void;
        declare function twoParams<TT>(a: TT, b: Decl): void;
    """.trimIndent() + "\n"

    private val checkDefinedPrelude = """
        interface A { a: number; }
        interface B { b: number; }
        interface C { c: number; }
        declare function checkDefinedLocal<TCD>(value: TCD | null | undefined): TCD;
        declare function identityLocal<TI>(value: TI): TI;
        declare const ab: A | B | undefined;
        declare const abc: A | B | C | null | undefined;
        declare const one: A | undefined;
        declare const abPlain: A | B;
    """.trimIndent() + "\n"

    // --- defect 1: the optional-member walker vs an inferred bare TP ---------

    /** DISCRIMINATES — TS2345 without the fix. */
    @Test
    fun `an optional member argument to an unconstrained bare type parameter is accepted`() {
        val diagnostics = diagnose(
            optionalMemberPrelude + "assertIsDefinedLocal(s.valueDeclaration);"
        )
        assert(diagnostics.none { it.code == 2345 })
    }

    /** DISCRIMINATES — TS2345 without the fix; the constraint changes nothing. */
    @Test
    fun `an optional member argument to a CONSTRAINED bare type parameter is accepted`() {
        val diagnostics = diagnose(
            optionalMemberPrelude + "assertConstrained(s.valueDeclaration);"
        )
        assert(diagnostics.none { it.code == 2345 })
    }

    /** Guard — fires on BOTH sides: a genuinely required named parameter still rejects. */
    @Test
    fun `negative control - an optional member argument to a required named parameter still fires`() {
        val diagnostics = diagnose(
            optionalMemberPrelude + "needsDecl(s.valueDeclaration);"
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message == "Argument of type 'Decl | undefined' is not assignable to parameter of type 'Decl'."
        })
    }

    /**
     * Guard — fires on BOTH sides. The suppression is per-PARAMETER, not
     * per-call: the same generic callee's CONCRETE parameter still rejects.
     */
    @Test
    fun `negative control - a concrete parameter of the same generic callee still fires`() {
        val diagnostics = diagnose(
            optionalMemberPrelude + "twoParams(anyDecl, s.valueDeclaration);"
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message == "Argument of type 'Decl | undefined' is not assignable to parameter of type 'Decl'."
        })
    }

    // --- defect 2: the nullish strip in candidate gathering -------------------

    /** DISCRIMINATES — TS2322 naming `A | B | undefined` without the fix. */
    @Test
    fun `a two member source through a nullable type parameter returns the non nullable side`() {
        val diagnostics = diagnose(
            checkDefinedPrelude + "function f(): A | B { return checkDefinedLocal(ab); }"
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /** DISCRIMINATES — TS2322 naming `A | B | C | null | undefined` without the fix. */
    @Test
    fun `both null and undefined are stripped from a three member source`() {
        val diagnostics = diagnose(
            checkDefinedPrelude + "function f(): A | B | C { return checkDefinedLocal(abc); }"
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * DISCRIMINATES BY MESSAGE, which is the sharpest pin here: it names the
     * inferred type instead of merely observing silence, so it proves the strip
     * removed the nullish members and NOTHING else.
     */
    @Test
    fun `the stripped inference keeps every non nullish member`() {
        val diagnostics = diagnose(
            checkDefinedPrelude + "function f(): A | B { return checkDefinedLocal(abc); }"
        )
        assert(diagnostics.any {
            it.code == 2322 &&
                it.message == "Type 'A | B | C' is not assignable to type 'A | B'."
        })
    }

    /** Guard — silent on both sides: the round-428 single-survivor path is untouched. */
    @Test
    fun `a single non nullish member still infers as before`() {
        val diagnostics = diagnose(
            checkDefinedPrelude + "function f(): A { return checkDefinedLocal(one); }"
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /** Guard — silent on both sides: a source with NO nullish member is a no-op. */
    @Test
    fun `a source with no nullish member is passed through unchanged`() {
        val diagnostics = diagnose(
            checkDefinedPrelude + "function f(): A | B { return checkDefinedLocal(abPlain); }"
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * Guard — silent on both sides. The strip is gated on the PARAMETER being a
     * nullable union of the type parameter; a bare `tp` parameter must keep
     * inferring the whole union, nullish members included.
     */
    @Test
    fun `a bare type parameter still infers the whole union`() {
        val diagnostics = diagnose(
            checkDefinedPrelude + "function f(): A | B { return identityLocal(abPlain); }"
        )
        assert(diagnostics.none { it.code == 2322 })
    }
}
