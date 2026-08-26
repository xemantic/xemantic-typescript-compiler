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
 * (CHK.42) A parameter ALWAYS introduces a binding, whether or not this checker can
 * work out its type — and until this class existed an un-annotated parameter whose
 * contextual type could not be determined was registered NOWHERE, so a read of its
 * name inside the body resolved to a same-named binding in an ENCLOSING scope.
 *
 * The shape that found it is tsc's own `importFixes.ts`, twice:
 * `flatMap(exportInfo, (exportInfo, i) => … mapDefined(specs, (spec) => ({ …,
 * exportInfo })))` — the callback parameter deliberately shadows the enclosing
 * function's `exportInfo`, and because `mapDefined` is GENERIC the pulled contextual
 * parameter type is the un-inferred `T`, which round 569 refuses (correctly: a bare
 * type parameter is not a type). Its comment said "the param stays `any`". It did
 * not: it stayed absent, and the shorthand `exportInfo` read the OUTER
 * `readonly (SymbolExportInfo | FutureSymbolExportInfo)[]`, producing a TS2322 that
 * `tools/tsgo-7.0.2/lib/tsc` does not have.
 *
 * The refusal is kept; only its claim is made true. `anyType` is the value round 475
 * already uses for exactly this purpose on binding-pattern parameters — "a
 * strictly-suppression fallback that stops the name falling through to a same-named
 * merged-globals binding".
 *
 * **THIS IS A SHIPPED DEFECT, NOT ONE (CHK.42) CREATED**, and the first pin says so:
 * it is an EXPRESSION-STATEMENT position, which the function-body walker has reached
 * for many rounds. Measured on a rebuilt parent binary, it fires there today; the
 * `return` position beside it is the instance (CHK.42)'s walk additionally exposes.
 *
 * The positive halves are what separate this from a binary that merely stopped
 * checking inside such a body: with the parameter correctly in scope, a genuinely
 * wrong member still reports, and a body that really does read the OUTER array
 * still reports too.
 */
class ContextualParamShadowingTest {

    private val prelude = """
        interface S { s: number }
        interface F { kind: number; exportInfo: S }
        declare function mapDefined<T, U>(xs: readonly T[], f: (x: T) => U | undefined): U[];
    """.trimIndent() + "\n"

    private fun codes(source: String): List<Int> =
        diagnose(prelude + source.trimIndent()).map { it.code }.sorted()

    /** SHIPPED TODAY — an expression-statement position, reached by the walker for rounds. */
    @Test
    fun `a shadowing contextual parameter is in scope in an expression-statement callback`() {
        assert(
            codes(
                """
                export function v1(exportInfo: readonly S[]): void {
                  mapDefined(exportInfo, (exportInfo): F | undefined => {
                    return { kind: 1, exportInfo };
                  });
                }
                """,
            ).isEmpty(),
        )
    }

    /** The instance (CHK.42)'s return-position walk additionally exposes. */
    @Test
    fun `a shadowing contextual parameter is in scope in a return-position callback`() {
        assert(
            codes(
                """
                export function v2(exportInfo: readonly S[]): F[] {
                  return mapDefined(exportInfo, (exportInfo): F | undefined => {
                    return { kind: 1, exportInfo };
                  });
                }
                """,
            ).isEmpty(),
        )
    }

    /**
     * POSITIVE. The parameter is in scope, so `exportInfo` is no longer the array —
     * and the OTHER member is genuinely wrong, so the literal must still report.
     * A binary that stopped checking inside the body loses this row.
     */
    @Test
    fun `a genuinely wrong member in the same body still reports`() {
        assert(
            codes(
                """
                export function v3(exportInfo: readonly S[]): void {
                  mapDefined(exportInfo, (exportInfo): F | undefined => {
                    void exportInfo;
                    return { kind: "s", exportInfo };
                  });
                }
                """,
            ).isNotEmpty(),
        )
    }

    /**
     * POSITIVE. No shadowing here — the body really does read the enclosing array, so
     * the outer binding must still be visible. A fix that blanket-`any`d every name in
     * such a body loses this row.
     */
    @Test
    fun `an enclosing binding that is NOT shadowed is still read`() {
        assert(
            codes(
                """
                export function v4(all: readonly S[]): void {
                  mapDefined(all, (one): F | undefined => {
                    void one;
                    return { kind: 1, exportInfo: all };
                  });
                }
                """,
            ).isNotEmpty(),
        )
    }
}
