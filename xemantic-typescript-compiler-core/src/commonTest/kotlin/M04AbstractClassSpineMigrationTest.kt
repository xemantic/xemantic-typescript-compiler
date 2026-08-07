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
 * (M0.4, round 627): pins for the checkAbstractClassInstantiation (TS2511)
 * spine migration — the legacy walk's statement-LIST overlay scoping (a
 * block-level abstract class fires inside its block; a non-abstract class
 * SHADOWS an outer/global abstract name for its whole list), the file-scoped
 * typeof-abstract var set (typeof X annotations, alias/union resolution,
 * params), the `[A].map(cls => new cls())` callback-param extension
 * (including the nested outermost-first fold), and the reach quirks (a NEW
 * expression's CALLEE subtree, case-clause EXPRESSIONS, bare for-initializer
 * EXPRESSIONS, and object-literal METHOD bodies all stay unwalked). All
 * expectations verified green against the pre-migration legacy walker first.
 */
class M04AbstractClassSpineMigrationTest {

    @Test
    fun `new on an abstract class fires TS2511`() {
        diagnose(
            """
            abstract class A {}
            new A()
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `negative control - new on a concrete class draws no TS2511`() {
        diagnose(
            """
            class A {}
            new A()
            """
        ) should {
            have(none { it.code == 2511 })
        }
    }

    @Test
    fun `block-scoped abstract class fires inside its block`() {
        diagnose(
            """
            function f() {
                abstract class B {}
                return new B()
            }
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `a non-abstract block class shadows an outer abstract name`() {
        diagnose(
            """
            abstract class A {}
            function f() {
                class A {}
                return new A()
            }
            """
        ) should {
            have(none { it.code == 2511 })
        }
    }

    @Test
    fun `an inner abstract class shadows an outer concrete name and fires`() {
        diagnose(
            """
            class A {}
            function f() {
                abstract class A {}
                return new A()
            }
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `typeof-abstract var fires TS2511`() {
        diagnose(
            """
            abstract class A {}
            declare const ctor: typeof A
            new ctor()
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `typeof-abstract through a type alias union fires`() {
        diagnose(
            """
            abstract class A {}
            class B {}
            type C = typeof A | typeof B
            declare const ctor: C
            new ctor()
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `negative control - typeof a concrete class draws no TS2511`() {
        diagnose(
            """
            class B {}
            declare const ctor: typeof B
            new ctor()
            """
        ) should {
            have(none { it.code == 2511 })
        }
    }

    @Test
    fun `a parameter typed typeof-abstract fires in the body`() {
        diagnose(
            """
            abstract class A {}
            function make(ctor: typeof A) {
                return new ctor()
            }
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `array-map callback param inherits abstract-constructible state`() {
        diagnose(
            """
            abstract class A {}
            [A].map(cls => new cls())
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `negative control - array-map over concrete classes draws no TS2511`() {
        diagnose(
            """
            class A {}
            [A].map(cls => new cls())
            """
        ) should {
            have(none { it.code == 2511 })
        }
    }

    @Test
    fun `nested array-map extends outermost-first`() {
        // The OUTER extension makes `c` abstract-constructible, which is what
        // lets the INNER receiver's elements match — the legacy top-down walk
        // order, reproduced by the outermost-first fold on the anchor climb.
        diagnose(
            """
            abstract class A {}
            [A].map(c => [c].map(d => new d()))
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `reach - a new-expression ARGUMENT is walked`() {
        diagnose(
            """
            abstract class A {}
            class W { constructor(x: unknown) {} }
            new W(new A())
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `reach - a new-expression CALLEE subtree is not walked`() {
        diagnose(
            """
            abstract class A {}
            declare function wrap(x: unknown): new () => object
            new (wrap(new A()))()
            """
        ) should {
            have(none { it.code == 2511 })
        }
    }

    @Test
    fun `reach - an object-literal method body is not walked`() {
        diagnose(
            """
            abstract class A {}
            const o = { m() { return new A() } }
            """
        ) should {
            have(none { it.code == 2511 })
        }
    }

    @Test
    fun `reach - an object-literal property value is walked`() {
        diagnose(
            """
            abstract class A {}
            const o = { p: new A() }
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `reach - a case-clause EXPRESSION is not walked but its statements are`() {
        diagnose(
            """
            abstract class A {}
            declare const x: unknown
            switch (x) {
                case (new A() as unknown):
                    break
            }
            """
        ) should {
            have(none { it.code == 2511 })
        }
        diagnose(
            """
            abstract class A {}
            declare const x: unknown
            switch (x) {
                default:
                    new A()
            }
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `reach - a bare for-initializer EXPRESSION is not walked but a declaration list is`() {
        diagnose(
            """
            abstract class A {}
            let y: unknown
            for (y = new A() as unknown; false;) {}
            """
        ) should {
            have(none { it.code == 2511 })
        }
        diagnose(
            """
            abstract class A {}
            for (let y = new A(); false;) {}
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `class method bodies and property initializers are walked`() {
        diagnose(
            """
            abstract class A {}
            class C {
                p = new A()
            }
            """
        ) should {
            have(any { it.code == 2511 })
        }
        diagnose(
            """
            abstract class A {}
            class C {
                m() { return new A() }
            }
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `arrow expression body is walked`() {
        diagnose(
            """
            abstract class A {}
            const f = () => new A()
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `cross-file script global abstract class fires`() {
        diagnose(
            """
            // @filename: a.ts
            abstract class A {}
            // @filename: b.ts
            new A()
            """
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `named import of an abstract class fires`() {
        diagnose(
            """
            // @filename: a.ts
            export abstract class A {}
            // @filename: b.ts
            import { A } from "./a"
            new A()
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `default import of an abstract class fires`() {
        diagnose(
            """
            // @filename: a.ts
            export default abstract class A {}
            // @filename: b.ts
            import A from "./a"
            new A()
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(any { it.code == 2511 })
        }
    }

    @Test
    fun `negative control - named import of a concrete class draws no TS2511`() {
        diagnose(
            """
            // @filename: a.ts
            export class A {}
            // @filename: b.ts
            import { A } from "./a"
            new A()
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(none { it.code == 2511 })
        }
    }
}
