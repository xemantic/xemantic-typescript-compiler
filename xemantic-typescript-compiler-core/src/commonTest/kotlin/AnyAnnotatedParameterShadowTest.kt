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
 * (P18.160): an `any`-ANNOTATED parameter shadows its enclosing scope.
 *
 * (CHK.42)'s pre-pass fills `currentLocalTypes` for a parameter nothing else can type —
 * because that map is a FLAT COPY of the enclosing scope, so a parameter registered
 * nowhere is not merely untyped: the outer same-named entry is still sitting there and
 * every read inside the body resolves to IT. The pre-pass skipped an ANNOTATED parameter,
 * and the arm below it writes only when the annotation resolves to neither `anyType` nor
 * `errorType` — so an explicit `: any` fell through both and leaked the outer binding.
 *
 * Measured against `tools/tsgo-7.0.2/lib/tsc`: every row below was a FALSE POSITIVE on
 * legal code, and tsgo reports only TS7006 for the un-annotated callback parameters
 * (which this compiler does not emit there — a missing row, recorded, not this fix's).
 *
 * **IT WAS INVISIBLE UNTIL (CHK.73).** While a module symbol typed `any`, the wrong
 * resolution had the right answer by accident; giving a namespace import a real type is
 * what made one instance of it observable, and that round shipped a contained guard whose
 * general form is this. The defect is older and wider than either — the last pin needs no
 * import at all.
 */
class AnyAnnotatedParameterShadowTest {

    private fun rows(source: String, code: Int): List<String> =
        diagnose(source, directives = "// @strict: true\n// @module: commonjs")
            .filter { it.code == code }
            .map { it.message }

    private val files = """
        // @Filename: /proj/src/nsmod.ts
        export function ztake(cb: (p: string) => void) {}

        // @Filename: /proj/src/use.ts
    """.trimIndent() + "\n"

    @Test
    fun `an any-annotated parameter shadowing a file-level const is not the const`() {
        assert2322Empty(
            """
            export const zstr: string = "x";
            export function f(zstr: any) { const n: number = zstr; return n; }
            """
        )
    }

    @Test
    fun `an any-annotated parameter shadowing a file-level object is not that object`() {
        assert2322Empty(
            """
            export const zloc = { ztake(cb: (p: string) => void) {} };
            export function f(zloc: any) { zloc.ztake((p) => { const n: number = p; }); }
            """
        )
    }

    @Test
    fun `an any-annotated parameter shadowing a namespace import is not the module`() {
        diagnose(
            files + """
            import * as zns from "./nsmod";
            export function f(zns: any) { zns.ztake((p) => { const n: number = p; }); }
            """.trimIndent(),
            "// @strict: true\n// @module: commonjs",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a TYPED parameter still shadows with its own type`() {
        // The pre-pass must stay a PRE-pass: a knowable annotation overwrites the seed,
        // so a genuine mismatch inside the body must still report.
        assert(
            rows(
                """
                export const zstr: string = "x";
                export function f(zstr: number) { const n: string = zstr; return n; }
                """.trimIndent(),
                2322,
            ) == listOf("Type 'number' is not assignable to type 'string'.")
        )
    }

    @Test
    fun `negative control - an un-annotated parameter keeps round 453's shadow`() {
        assert2322Empty(
            """
            export const zstr: string = "x";
            export function f(zstr) { const n: number = zstr; return n; }
            """
        )
    }

    private fun assert2322Empty(source: String) {
        diagnose(source.trimIndent(), "// @strict: true\n// @module: commonjs") should {
            have(none { it.code == 2322 })
        }
    }
}
