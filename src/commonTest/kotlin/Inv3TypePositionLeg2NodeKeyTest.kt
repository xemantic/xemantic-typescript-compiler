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
 * INV.3(c)(iv) leg 2 (round 509): the remaining type-position/value-tail
 * merged-globals fallbacks key by the name node's owning file —
 * `getTypeFromBaseTypeExpression`'s Identifier branch (heritage bases),
 * `emitTs2345ForBareTpArgToConstrainedTpParam`, `getOverloadImplementationRelated`
 * (keyed by the overload DECL's own name node), and
 * `calleeReturnAnnotationForImplicitAny`. A heritage base or callee with no
 * per-file meaning no longer resolves to a foreign module file's leaked
 * declaration (real tsc: TS2304 territory, no derived members / no
 * constraint verdicts).
 */
class Inv3TypePositionLeg2NodeKeyTest {

    @Test
    fun `an UNIMPORTED foreign heritage base no longer grafts members - the leaked TS2741 dies`() {
        diagnose(
            """
            // @filename: a.ts
            export const anchor = 1;
            class Base3 { m(): string { return "x"; } }

            // @filename: b.ts
            export class D extends Base3 {}
            const d: D = {};
            """
        ) should {
            have(none { it.code == 2741 })
        }
    }

    @Test
    fun `negative control - an IMPORTED heritage base keeps grafting - the real TS2741 fires`() {
        diagnose(
            """
            // @filename: a.ts
            export class Base3 { m(): string { return "x"; } }

            // @filename: b.ts
            import { Base3 } from "./a";
            export class D extends Base3 {}
            const d: D = {};
            """
        ) should {
            have(any { it.code == 2741 })
        }
    }

    @Test
    fun `negative control - a cross-file SCRIPT heritage base keeps grafting - TS2741 fires`() {
        diagnose(
            """
            // @filename: a.ts
            class Base3 { m(): string { return "x"; } }

            // @filename: b.ts
            class D extends Base3 {}
            const d: D = {};
            """
        ) should {
            have(any { it.code == 2741 })
        }
    }

    @Test
    fun `an UNIMPORTED foreign constrained-TP callee no longer manufactures TS2345`() {
        diagnose(
            """
            // @filename: a.ts
            export const anchor = 1;
            function take<T extends string>(x: T): void {}

            // @filename: b.ts
            export function g<U>(u: U): void {
                take(u);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an OWN-FILE constrained-TP callee keeps firing TS2345`() {
        diagnose(
            """
            // @filename: b.ts
            export function take<T extends string>(x: T): void {}
            export function g<U>(u: U): void {
                take(u);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
