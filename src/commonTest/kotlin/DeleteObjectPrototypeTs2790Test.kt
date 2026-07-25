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
 * M2.2 (round 394): `delete x.<Object.prototype member>` is always TS2790 "The operand
 * of a 'delete' operator must be optional." under strictNullChecks, because
 * Object.prototype members (`toString`/`valueOf`/`hasOwnProperty`/…) are non-optional
 * and present on EVERY object.
 *
 * Our `getApparentType` does NOT fold Object.prototype's members into an object type's
 * apparent members. Under the embedded lib the corpus test `keywordExpressionInternalComments`
 * happens to resolve `Array` (value position) to the `Array<any>` instance (which declares
 * its own `toString`), so the member IS found and TS2790 fires. Under real libs `Array`
 * resolves to `ArrayConstructor`, which has NO own `toString` — it inherits it from
 * `Object.prototype` — so the member was NOT found and no error fired. The fix adds an
 * Object.prototype fallback in the delete check (fires when the receiver is object-like,
 * the member name is an Object.prototype member, and the type has no own declaration of
 * it), gated so a user type declaring the name optionally still routes through the
 * own-member branch.
 */
class DeleteObjectPrototypeTs2790Test {

    private val realLibs = "// @useRealLibs: true\n// @target: es2015"
    private val embedded = "// @target: es2015"

    @Test
    fun `real libs - delete Array_toString fires TS2790 - the keywordExpressionInternalComments fix`() {
        diagnose("delete Array.toString;", directives = realLibs) should {
            have(
                any { it.code == 2790 },
                "delete Array.toString must be TS2790 under real libs (toString inherited from Object.prototype)",
            )
        }
    }

    @Test
    fun `real libs - delete Array_valueOf fires TS2790`() {
        diagnose("delete Array.valueOf;", directives = realLibs) should {
            have(any { it.code == 2790 })
        }
    }

    @Test
    fun `embedded lib - delete Array_toString still fires TS2790 - regression control`() {
        // Under the embedded lib the member is found as an OWN declaration, exercising the
        // pre-existing branch — this pins that the refactor did not disturb it.
        diagnose("delete Array.toString;", directives = embedded) should {
            have(any { it.code == 2790 })
        }
    }

    @Test
    fun `own optional Object-prototype member does NOT fire - fallback not reached`() {
        // The receiver declares `toString?` itself, so propSym is found and optional →
        // the own-member branch handles it (no TS2790). The fallback must NOT fire.
        diagnose(
            """
            interface I { toString?(): string; }
            declare const i: I;
            delete i.toString;
            """,
            directives = embedded,
        ) should {
            have(none { it.code == 2790 })
        }
    }

    @Test
    fun `non-prototype missing member does NOT fire the fallback`() {
        // `foo` is reached via the string index signature (no named property, propSym null)
        // and is NOT an Object.prototype member name — the fallback is scoped to that set,
        // so no TS2790.
        diagnose(
            """
            declare const x: { [k: string]: number };
            delete x.foo;
            """,
            directives = embedded,
        ) should {
            have(none { it.code == 2790 })
        }
    }

    @Test
    fun `strictNullChecks off suppresses the fallback`() {
        diagnose(
            "delete Array.toString;",
            directives = "$realLibs\n// @strict: false\n// @strictNullChecks: false",
        ) should {
            have(none { it.code == 2790 })
        }
    }
}
