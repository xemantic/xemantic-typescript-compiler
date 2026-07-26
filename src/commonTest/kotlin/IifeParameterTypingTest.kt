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
 * Local corner-case tests for the contextual typing of an immediately-invoked
 * function's parameters from the call's ARGUMENTS.
 *
 * Round 693 stopped these parameters from drawing implicit-any; this gives them the
 * type tsc gives them. The distinction that made the first attempt (round 694)
 * unobservable is worth restating: the body walkers read `currentLocalTypes`, filled by
 * `populateParameterLocalTypes`, and NOT the signature's symbol types — so typing the
 * signature changed nothing at all. The first pin here is exactly the probe that caught
 * that: it must FAIL if the parameter is `any`.
 */
class IifeParameterTypingTest {

    @Test
    fun `an IIFE parameter takes the argument's type, widened`() {
        val diags = diagnose(
            """
            ((a) => a.nope)("x");
            """
        ).filter { it.code == 2339 }
        assert(diags.size == 1)
        // 'string', not '"x"' — tsc widens the argument's literal type.
        assert(diags[0].message == "Property 'nope' does not exist on type 'string'.")
    }

    @Test
    fun `KNOWN LIMITATION - a function EXPRESSION IIFE is not yet typed`() {
        // Arrows are; function expressions are not, and this pins WHICH so the gap is
        // visible rather than folklore. Round 704 established that the site responsible
        // is NOT the no-contextual-annotation branch that blanket-registers `any` for a
        // callback's own parameters — deferring there changed nothing.
        val diags = diagnose(
            """
            (function (b) { b.nope; })(42);
            """
        ).filter { it.code == 2339 }
        assert(diags.isEmpty())
    }

    @Test
    fun `negative control - a NON-invoked arrow's parameter stays implicitly any`() {
        val diags = diagnose(
            """
            const plain = (z) => z.nope;
            """
        )
        assert(diags.none { it.code == 2339 })
        assert(diags.count { it.code == 7006 } == 1)
    }

    @Test
    fun `negative control - a parameter with a DEFAULT is typed by the default`() {
        // Its own initializer already types it; the argument must not override that.
        val diags = diagnose(
            """
            ((m = 10) => m + 1)(12);
            """
        )
        assert(diags.isEmpty())
    }

    @Test
    fun `negative control - a REST parameter is left alone`() {
        val diags = diagnose(
            """
            ((...numbers) => numbers.every(n => n > 0))(5, 6, 7);
            """
        ).filter { it.code == 2339 }
        assert(diags.isEmpty())
    }

    @Test
    fun `an argument-less call leaves a non-optional parameter untyped`() {
        // Nothing to take a type from, and the parameter is not optional, so this stays
        // as it was rather than being guessed at.
        val diags = diagnose(
            """
            ((x, y, z) => 42)();
            """
        ).filter { it.code == 2339 || it.code == 18048 }
        assert(diags.isEmpty())
    }
}
