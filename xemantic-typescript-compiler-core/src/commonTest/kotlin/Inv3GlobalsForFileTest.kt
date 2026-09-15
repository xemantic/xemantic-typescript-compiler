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
 * INV.3(b)(ii) (round 502): the pilot consumer of the per-file resolution
 * primitive — the TS2315/TS2346 heritage-base "not generic" gate consults
 * `globals` through [Checker.globalsForFile], so a FOREIGN module file's
 * same-named local can no longer decide "Type 'X' is not generic." about a
 * name the consulting file cannot see (real tsc: an unresolvable heritage
 * base is TS2304 territory, never TS2315). Every legitimate visibility route
 * — own declaration, own import (probed through `lookupPerFile`), script-file
 * global — must keep the check firing byte-identically.
 */
class Inv3GlobalsForFileTest {

    @Test
    fun `a foreign module file's non-generic class must not draw TS2315 in a file that never imports it`() {
        diagnose(
            """
            // @filename: a.ts
            export class Foo {}

            // @filename: b.ts
            export class C extends Foo<number> {}
            """
        ) should {
            have(none { it.code == 2315 })
        }
    }

    @Test
    fun `an imported non-generic base keeps drawing TS2315`() {
        diagnose(
            """
            // @filename: a.ts
            export class Foo {}

            // @filename: b.ts
            import { Foo } from "./a";
            export class C extends Foo<number> {}
            """
        ) should {
            have(any { it.code == 2315 && it.message.contains("'Foo' is not generic") })
        }
    }

    @Test
    fun `a same-file non-generic base keeps drawing TS2315`() {
        diagnose(
            """
            export class Foo {}
            export class C extends Foo<number> {}
            """
        ) should {
            have(any { it.code == 2315 && it.message.contains("'Foo' is not generic") })
        }
    }

    @Test
    fun `a script-file non-generic base is globally visible and keeps drawing TS2315 cross-file`() {
        diagnose(
            """
            // @filename: a.ts
            class Foo {}

            // @filename: b.ts
            class C extends Foo<number> {}
            """
        ) should {
            have(any { it.code == 2315 && it.message.contains("'Foo' is not generic") })
        }
    }

    @Test
    fun `negative control - an imported GENERIC base draws no TS2315`() {
        diagnose(
            """
            // @filename: a.ts
            export class Gen<T> {}

            // @filename: b.ts
            import { Gen } from "./a";
            export class C extends Gen<number> {}
            """
        ) should {
            have(none { it.code == 2315 })
        }
    }

    @Test
    fun `the mirrored TS2346 super-call gate is suppressed for a foreign unimported base too`() {
        diagnose(
            """
            // @filename: a.ts
            export class Base {}

            // @filename: b.ts
            export interface C {}
            export class C extends Base<number> {
                constructor() {
                    super();
                }
            }
            """
        ) should {
            have(none { it.code == 2346 })
        }
    }

    @Test
    fun `a same-file non-generic base with a super call draws TS2315 alone - TS2346 has no tsgo emission site`() {
        // Re-measured against tsgo 7.0.2 (2026-09-15, (P18.101) M1): `Type 'Base' is not generic.`
        // at the extends clause and NOTHING at `super()` — TS2346 is emitted nowhere in tsgo, and
        // the 16.4db walker that mirrored the TS2315 gate to emit it is deleted. This pin used to
        // assert that walker kept firing; it is now the negative control for its absence.
        diagnose(
            """
            class Base {}
            interface C {}
            class C extends Base<number> {
                constructor() {
                    super();
                }
            }
            """
        ) should {
            have(none { it.code == 2346 })
            have(any { it.code == 2315 && it.message == "Type 'Base' is not generic." })
        }
    }
}
