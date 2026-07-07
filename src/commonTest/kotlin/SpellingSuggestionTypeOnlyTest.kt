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
 * M2.2 (round 391): a VALUE-position unresolved name must never be spelling-corrected
 * to a TYPE-only name. The real lib declares `interface IArguments` with no `declare var`
 * companion, so it carries only a Type flag; the embedded lib lacked it entirely, which
 * is why the corpus `arguments`/`unaryOperatorsInStrictMode` baselines only diverged once
 * `useRealLibs` exposed it (we suggested "Did you mean 'IArguments'?" where tsc emits a
 * plain TS2304). The fix classifies lib/cross-file type-only symbols into the
 * value-position filter; these pins prove it removes the wrong suggestion WITHOUT touching
 * the type-position path (where `IArguments` IS a valid suggestion) or value suggestions
 * for names that DO carry a value (`Object`).
 */
class SpellingSuggestionTypeOnlyTest {

    @Test
    fun `value-position name is not spell-corrected to a type-only lib interface`() {
        // `IArgument` is one edit from the type-only `interface IArguments`.
        diagnose(
            """
            // @useRealLibs: true
            const x = IArgument;
            """,
            directives = "",
        ) should {
            have(any { it.code == 2304 })
            have(none { it.message.contains("IArguments") })
        }
    }

    @Test
    fun `type-position typo still suggests a type-only user interface`() {
        // The fix widens the VALUE-position `typeOnlyNames` filter to lib/cross-file
        // symbols; it must NOT leak into the type-position path. A type-only user
        // interface `MyThing` is now in `typeOnlyNames`, yet a type-position typo for it
        // must still be suggested (type-position never consults `typeOnlyNames`).
        diagnose(
            """
            // @useRealLibs: true
            interface MyThing { a: number; }
            let y: MyThin;
            """,
            directives = "",
        ) should {
            have(any { it.code == 2552 && it.message.contains("MyThing") })
        }
    }

    @Test
    fun `value-position name still suggests a lib name that carries a value`() {
        // Negative control: `Object` has a `declare var Object: ObjectConstructor`
        // companion, so it carries a Value flag and must remain suggestable in value
        // position — the filter must not over-remove.
        diagnose(
            """
            // @useRealLibs: true
            const z = Objectt.keys({});
            """,
            directives = "",
        ) should {
            have(any { it.code == 2552 && it.message.contains("Object") })
        }
    }
}
