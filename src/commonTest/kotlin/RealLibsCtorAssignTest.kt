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
 * M2.2 (round 393): tsc reports a structural relation failure ONCE. When a source is
 * MISSING required target properties, the missing-property error (TS2739/TS2740) owns the
 * diagnostic and the construct-/call-signature mismatch (TS2322) is NOT additionally
 * reported. Under the embedded lib `ArrayConstructor` had no construct signature, so the
 * construct-sig branches never fired for `Array = fn`; under real libs it does, so the
 * assignment path double-emitted TS2322 alongside B444's TS2739 (the `redefineArray`
 * corpus failure). Fix: the 17.111 construct-sig branch and the general
 * `canUse && !isAssignable` block now defer when the source is missing required target
 * properties (`targetHasRequiredPropAbsentFromSource`, which — unlike
 * `collectMissingProperties` — does not bail on a null-members function source).
 */
class RealLibsCtorAssignTest {

    @Test
    fun `Array = fn reports only the missing-property TS2739, not a construct-sig TS2322`() {
        diagnose(
            "Array = function (n:number, s:string) {return n;};",
            directives = "// @useRealLibs: true\n// @target: es2015",
        ) should {
            have(any { it.code == 2739 })
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a function vs a construct-only interface (no named props) still reports TS2322`() {
        // POSITIVE control proving the guard is NARROW. `Ctor` has ONLY a construct
        // signature — no named properties — so nothing is "missing" from the function
        // source; the guard (`targetHasRequiredPropAbsentFromSource`) returns false and the
        // construct-sig mismatch TS2322 must still fire. This is the case the guard must NOT
        // suppress (unlike `Array = fn`, where ArrayConstructor's isArray/from/of ARE
        // missing). Embedded lib — no real libs needed.
        diagnose(
            """
            // @target: es2015
            interface Ctor { new (): object; }
            declare let c: Ctor;
            c = function () { return {}; };
            """,
            directives = "",
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
