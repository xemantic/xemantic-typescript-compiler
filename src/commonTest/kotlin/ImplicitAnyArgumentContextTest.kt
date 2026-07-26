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
 * Local corner-case tests for TS7006 on a function passed AS AN ARGUMENT.
 *
 * The implicit-any argument edge used "can I resolve the callee NAME"
 * ([isCalleeResolvable]) as its proxy for "does this argument have a contextual type".
 * Those come apart whenever the callee resolves but its parameter says nothing, which
 * is why the first two tests here were silent before.
 *
 * The controls are the interesting half, because each pins a narrowing that was
 * MEASURED rather than reasoned (round 712):
 *  - a generic/mapped parameter annotation we cannot resolve lands on `anyType` in our
 *    engine but DOES carry a contextual type in tsc, so the rule reads the SYNTACTIC
 *    annotation, never the resolved one;
 *  - the embedded lib's callback signatures are deliberately simplified to `any`, so
 *    their parameters must not be read as "no contextual type" — `.replace(…, s => …)`
 *    is the shape that caught this on the tsc-source profiles.
 */
class ImplicitAnyArgumentContextTest {

    private val directives = "// @noImplicitAny: true"

    private fun ts7006(source: String): List<Diagnostic> =
        diagnose(source, directives).filter { it.code == 7006 }

    @Test
    fun `an arrow argument against an 'any' parameter is implicitly any`() {
        val diags = ts7006(
            """
            declare function anyCb(cb: any): void;
            anyCb(j => j);
            """
        )
        assert(diags.size == 1)
        assert(diags[0].message == "Parameter 'j' implicitly has an 'any' type.")
    }

    @Test
    fun `an arrow argument to an IIFE whose own parameter is untyped is implicitly any`() {
        val diags = ts7006(
            """
            let twelve = (f => f(12))(k => k);
            """
        )
        assert(diags.any { it.message == "Parameter 'k' implicitly has an 'any' type." })
    }

    @Test
    fun `control - an annotated callback parameter provides context`() {
        val diags = ts7006(
            """
            declare function take(cb: (x: number) => number): void;
            take(i => i);
            """
        )
        assert(diags.isEmpty())
    }

    @Test
    fun `control - a GENERIC callback parameter provides context`() {
        // Our engine may resolve this to `any`; tsc does not, and reading the RESOLVED
        // type here is what red-lined three corpus baselines.
        val diags = ts7006(
            """
            declare function pick<T>(items: T[], cb: (item: T) => boolean): T[];
            pick([1, 2, 3], n => n > 1);
            """
        )
        assert(diags.isEmpty())
    }

    @Test
    fun `control - an embedded-lib callback is not treated as contextless`() {
        // `String.replace`'s replacer is simplified in the embedded lib; tsc states it
        // precisely, so this must stay silent. It is the shape that showed up as a
        // false positive on every tsc-source profile.
        val diags = ts7006(
            """
            declare const name: string;
            const out = name.replace(/a/g, s => s.substring(1));
            """
        )
        assert(diags.isEmpty())
    }
}
