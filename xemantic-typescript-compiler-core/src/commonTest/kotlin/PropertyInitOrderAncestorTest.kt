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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (P18.309) TS2729 against tsgo 7.0.2's `checkPropertyNotUsedBeforeDeclaration` (cells under
 * `build/bench/p18309-agent/m1/`): the ancestor exemption (`isPropertyDeclaredInAncestorClass`)
 * applies only WITHOUT `useDefineForClassFields`, resolves the base as the class's members are
 * resolved (a module-local, imported, namespace-qualified, default-imported or block-scoped base;
 * a lib base such as `Error`), looks on the base's INSTANCE side even for a static read, and finds
 * nothing for a class in an `extends` cycle; an optional field is never reported, a definite `!`
 * one is; a write target `this.b = …` is a use. Every expected row is tsgo's (1-based column).
 */
class PropertyInitOrderAncestorTest {

    private val es2020 = "// @strict: true\n// @target: es2020\n// @module: esnext\n// @moduleResolution: bundler\n// @useRealLibs: true"
    private val esDefault = "// @strict: true\n// @module: esnext\n// @moduleResolution: bundler\n// @useRealLibs: true"

    private fun rows(source: String, directives: String = es2020): List<String> =
        diagnose(source, directives = directives, fileName = "t.ts")
            .filter { it.code == 2729 || it.code == 2302 }
            .map { "${it.fileName?.substringAfterLast('/')}:${it.line}:${it.character} TS${it.code} ${it.message}" }
            .sorted()

    private fun used(file: String, line: Int, col: Int, name: String) =
        "$file:$line:$col TS2729 Property '$name' is used before its initialization."

    private val redeclaredInModule = """
        class Base {
            q = 1;
        }
        export class D extends Base {
            a = this.q;
            q = 2;
        }
    """

    @Test
    fun `a module-local base exempts a redeclared inherited field`() {
        assert(rows(redeclaredInModule).isEmpty())
    }

    @Test
    fun `under useDefineForClassFields a redeclared inherited field is reported`() {
        assert(rows(redeclaredInModule, esDefault) == listOf(used("t.ts", 5, 14, "q")))
        assert(
            rows(
                """
                class Base10 {
                    q = 1;
                }
                class D10 extends Base10 {
                    a = this.q;
                    q = 2;
                }
                """,
                esDefault,
            ) == listOf(used("t.ts", 5, 14, "q")),
        )
    }

    @Test
    fun `an imported, a default-imported, a namespace-import and a grand base exempt it`() {
        val r = rows(
            """
            // @Filename: /p/base.ts
            export class Base {
                q = 1;
            }
            export default class Def {
                q = 1;
            }
            export class G {
                q = 1;
            }
            // @Filename: /p/mid.ts
            import { G } from './base';
            export class M extends G {}
            // @Filename: /p/t.ts
            import Def, { Base } from './base';
            import * as b from './base';
            import { M } from './mid';
            export class D1 extends Base {
                a = this.q;
                q = 2;
            }
            export class D2 extends Def {
                a = this.q;
                q = 2;
            }
            export class D3 extends b.Base {
                a = this.q;
                q = 2;
            }
            export class D4 extends M {
                a = this.q;
                q = 2;
                c = this.r;
                r = 1;
            }
            """,
        )
        assert(r == listOf(used("t.ts", 19, 14, "r")))
    }

    @Test
    fun `a namespace-qualified, a block-scoped and a lib base exempt it`() {
        val r = rows(
            """
            namespace N {
                export class Base {
                    q = 1;
                }
            }
            export class D extends N.Base {
                a = this.q;
                q = 2;
            }
            export function f() {
                class Base {
                    q = 1;
                }
                class D extends Base {
                    a = this.q;
                    q = 2;
                }
                return D;
            }
            export class E extends Error {
                a = this.message;
                message = 'x';
            }
            """,
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a static read is exempt only by an instance member of the base`() {
        val r = rows(
            """
            class B1 {
                static q = 1;
            }
            export class D1 extends B1 {
                static a = D1.q;
                static q = 2;
            }
            class B2 {
                q = 1;
            }
            export class D2 extends B2 {
                static a = D2.q;
                static q = 2;
            }
            """,
        )
        assert(r == listOf(used("t.ts", 5, 19, "q")))
    }

    @Test
    fun `a class in an extends cycle inherits nothing and its subclass only the cycle head's own members`() {
        val r = rows(
            """
            class A extends B { x = this.y; y = 1; }
            class B extends A { z = 1; }
            export class C extends A {
                a = this.z;
                z = 2;
                b = this.x;
                x = 3;
            }
            """,
        )
        assert(r == listOf(used("t.ts", 1, 30, "y"), used("t.ts", 4, 14, "z")))
    }

    @Test
    fun `a self-extending class inherits nothing`() {
        val r = rows(
            """
            class A extends A { x = this.y; y = 1; }
            export {};
            """,
        )
        assert(r == listOf(used("t.ts", 1, 30, "y")))
    }

    @Test
    fun `an optional field is never reported and a definite one is`() {
        val r = rows(
            """
            export class C {
                a = this.b;
                b?: number;
                c = this.d;
                d!: number;
            }
            """,
        )
        assert(r == listOf(used("t.ts", 4, 14, "d")))
    }

    @Test
    fun `a write target in a field initializer is a use`() {
        val r = rows(
            """
            export class C {
                a = (this.b = 2, [this.b]);
                b = 1;
                c = this.d = 2;
                d = 1;
            }
            """,
        )
        assert(r == listOf(used("t.ts", 2, 15, "b"), used("t.ts", 2, 28, "b"), used("t.ts", 4, 14, "d")))
    }

    @Test
    fun `negative control - a mixin call base is answered conservatively`() {
        val r = rows(
            """
            type Ctor = new (...a: any[]) => object;
            function Mix<T extends Ctor>(B: T) { return class extends B { q = 1; }; }
            class Base {}
            export class D extends Mix(Base) {
                a = this.q;
                q = 2;
            }
            """,
        )
        assert(r.isEmpty())
    }

    @Test
    fun `TS2302 reaches keyof, readonly, a named tuple member, a template and a mapped type`() {
        val ts2302 = "TS2302 Static members cannot reference class type parameters."
        val r = rows(
            """
            export class C<T, U> {
                static e: T[keyof U];
                static g: readonly T[];
                static a: [x: T];
                static b: `a${'$'}{T & string}`;
                static c: { [K in keyof T]: U };
                static d: { [T in string]: T };
                static h: (x: any) => x is T;
            }
            """,
        )
        assert(
            r == listOf(
                "t.ts:2:15 $ts2302", "t.ts:2:23 $ts2302", "t.ts:3:24 $ts2302", "t.ts:4:19 $ts2302",
                "t.ts:5:19 $ts2302", "t.ts:6:29 $ts2302", "t.ts:6:33 $ts2302", "t.ts:8:32 $ts2302",
            ).sorted(),
        )
    }
}
