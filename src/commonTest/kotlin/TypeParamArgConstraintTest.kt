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
 * Round 442 — a bare TypeParam ARGUMENT (`K`/`T`) whose declared constraint is assignable to
 * a concrete (primitive) parameter type is itself assignable: tsc's rule that a type parameter
 * relates to `X` iff its constraint does. The relation engine deliberately has NO general
 * `source is Type.TypeParam && target !is Type.TypeParam` branch (the documented 39+ cycle
 * regression gate), so `checkArgumentsAgainstSignature` gets a per-site bail-out that checks the
 * RAW constraint (NOT `getApparentType`, which would wrap a bare `string` constraint into the
 * String interface — not assignable to primitive `string`). Distinct from the TS2344 type-ARG
 * position (GenericCallArgConstraintTest). Cleared 4 self-compile TS2345 FPs
 * (`readPackageJsonField<K extends keyof PackageJson>` → `hasProperty(json, fieldName)`,
 * `changeExtension<T extends string | Path>` → `changeAnyExtension(path, …)`).
 */
class TypeParamArgConstraintTest {

    @Test
    fun `type-param arg with a keyof constraint passed to a string param - no TS2345`() {
        diagnose(
            """
            interface Bag { a: number; b: number; }
            declare function needString(s: string): void;
            function f<K extends keyof Bag>(key: K): void { needString(key); }
            """,
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `type-param arg with a string-union constraint passed to a string param - no TS2345`() {
        diagnose(
            """
            type Brand = string & { __brand: unknown };
            declare function needString(s: string): void;
            function f<T extends string | Brand>(path: T): void { needString(path); }
            """,
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `type-param arg with a bare string constraint passed to a string param - no TS2345`() {
        // The RAW-constraint check (vs getApparentType, which wraps `string` into the String
        // interface) is what makes this pass.
        diagnose(
            """
            declare function needString(s: string): void;
            function f<T extends string>(x: T): void { needString(x); }
            """,
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `unconstrained type-param arg passed to a string param - TS2345 fires`() {
        // Negative control: an unconstrained `T` is NOT assignable to `string` (its constraint
        // is null → the bail-out is skipped → the relation fails → TS2345 must fire).
        diagnose(
            """
            declare function needString(s: string): void;
            function f<T>(x: T): void { needString(x); }
            """,
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `type-param arg whose constraint is unrelated to the param - TS2345 fires`() {
        // Negative control: `T extends object` is not assignable to `number`, so the constraint
        // check fails and TS2345 must fire.
        diagnose(
            """
            declare function needNumber(n: number): void;
            function f<T extends object>(x: T): void { needNumber(x); }
            """,
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
