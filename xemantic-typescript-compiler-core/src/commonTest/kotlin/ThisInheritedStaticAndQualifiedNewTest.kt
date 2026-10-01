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
 * (CHK.191), the residues of (CHK.187) in the class-instance missing-member family
 * (`ClassInstanceMembers.kt`):
 *  - `this.X` in an instance member reading a STATIC of a BASE class is TS2576 naming the
 *    receiver class (`C.s`, `C<U>.s`) — in a method, a getter, a property initializer and a
 *    constructor, script files too; an element access squiggles the whole access and spells
 *    the key as written (`C["s"]`), which also corrects the own-class static case;
 *  - a QUALIFIED `new` receiver (`new N.C().x`, `new ns.C().x` through a namespace import)
 *    reports TS2339 / TS2576 / TS2551 as an unqualified one does;
 *  - a DOTTED base (`extends N.B`) and a `declare class` base written in a program `.ts`
 *    file resolve for the chain walks.
 *
 * Every expectation is `tools/tsgo-7.0.2/lib/tsc`'s output for the same files
 * (`build/bench/p18247-agent/cells`). The silent receivers inside each fixture (members
 * present on a base, a static shadowed by an instance member, a static method's `this`,
 * a parameter shadowing the namespace) are the controls.
 */
class ThisInheritedStaticAndQualifiedNewTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @module: esnext\n// @lib: es2022,dom"

    private fun rows(source: String): List<String> =
        diagnose(source, directives = directives).map {
            "${it.fileName?.substringAfterLast('/')}(${it.line},${it.character}) TS${it.code}: ${it.message}"
        }.sorted()

    @Test
    fun `this reading a static inherited from a base is TS2576 in a script file`() {
        val r = rows("// @Filename: /proj/src/a.ts\nclass B { p = 0; value = 2; static s = 1 }\nclass C extends B {\n  m() { return [this.nope, this.p, this.s, this.valu]; }\n}\n")
        assert(
            r == listOf(
                "a.ts(3,22) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(3,41) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
                "a.ts(3,49) TS2551: Property 'valu' does not exist on type 'C'. Did you mean 'value'?",
            )
        )
    }

    @Test
    fun `an element access on this squiggles the whole access and spells the inherited static key as written`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass B { p = 0; static s = 1; static f() { return 1; } }\nclass C extends B {\n  m() { return [this.s, this[\"s\"], this.f(), this['f']]; }\n}\n")
        assert(
            r == listOf(
                "a.ts(4,22) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
                "a.ts(4,25) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C[\"s\"]' instead?",
                "a.ts(4,41) TS2576: Property 'f' does not exist on type 'C'. Did you mean to access the static member 'C.f' instead?",
                "a.ts(4,46) TS2576: Property 'f' does not exist on type 'C'. Did you mean to access the static member 'C['f']' instead?",
            )
        )
    }

    @Test
    fun `an element access on this reading the class's own static spells the key as written`() {
        val r = rows("// @Filename: /proj/src/a.ts\nclass C { static s = 1; p = 0; m() { return [this[\"s\"], this['s'], this.s, this.p]; } }\n")
        assert(
            r == listOf(
                "a.ts(1,46) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C[\"s\"]' instead?",
                "a.ts(1,57) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C['s']' instead?",
                "a.ts(1,73) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
            )
        )
    }

    @Test
    fun `a generic subclass names its own type parameters in the inherited static TS2576`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass B<T> { p = 0; static s = 1 }\nclass C<U> extends B<U> {\n  m() { return [this.s, this.nope]; }\n}\nclass D extends B<string> {\n  m() { return [this.s]; }\n}\n")
        assert(
            r == listOf(
                "a.ts(4,22) TS2576: Property 's' does not exist on type 'C<U>'. Did you mean to access the static member 'C<U>.s' instead?",
                "a.ts(4,30) TS2339: Property 'nope' does not exist on type 'C<U>'.",
                "a.ts(7,22) TS2576: Property 's' does not exist on type 'D'. Did you mean to access the static member 'D.s' instead?",
            )
        )
    }

    @Test
    fun `getter property initializer and constructor report the inherited static and a static method is silent`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass B { static s = 1 }\nclass C extends B {\n  static m() { return this.s; }\n  get g() { return this.s; }\n  q = this.s;\n  constructor() { super(); this.s; }\n}\n")
        assert(
            r == listOf(
                "a.ts(5,25) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
                "a.ts(6,12) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
                "a.ts(7,33) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
            )
        )
    }

    @Test
    fun `an inherited static shadowed by an instance member on the chain is silent`() {
        // `B` redeclares `s` as an INSTANCE member over `A`'s static, so `this.s` is legal.
        // Both are `number`: with a `string` instance member this checker emits an ours-only
        // TS2416 (an instance member compared against a base STATIC) — a separate residue.
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass A { static s = 1 }\nclass B extends A { static t = 2; s = 0 }\nclass C extends B {\n  m() { return [this.s, this.t]; }\n}\nnamespace N { export class B { static s = 1 } export class C extends B { m() { return this.s; } } }\n")
        assert(
            r == listOf(
                "a.ts(5,30) TS2576: Property 't' does not exist on type 'C'. Did you mean to access the static member 'C.t' instead?",
                "a.ts(7,92) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
            )
        )
    }

    @Test
    fun `new through a namespace qualified constructor reports missing members statics and suggestions`() {
        val r = rows("// @Filename: /proj/src/a.ts\nnamespace N { export class B { p = 0; value = 1; static s = 2 } export class C extends B { } }\nconst x1 = new N.C().nope;\nconst x2 = new N.C().p;\nconst x3 = new N.C().valu;\nconst x4 = new N.C().s;\nnamespace M.Q { export class D { q = 1 } }\nconst y1 = new M.Q.D().nope;\nconst y2 = new M.Q.D().q;\n")
        assert(
            r == listOf(
                "a.ts(2,22) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(4,22) TS2551: Property 'valu' does not exist on type 'C'. Did you mean 'value'?",
                "a.ts(5,22) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
                "a.ts(7,24) TS2339: Property 'nope' does not exist on type 'D'.",
            )
        )
    }

    @Test
    fun `new through a namespace import reports a missing member on the imported class`() {
        val r = rows(
            "// @Filename: /proj/src/m.ts\nexport class B { p = 0; value = 1; static s = 2 }\nexport class C extends B { }\n" +
                "// @Filename: /proj/src/a.ts\nimport * as ns from \"./m\";\nconst x1 = new ns.B().nope;\nconst x2 = new ns.C().p;\nconst x3 = new ns.C().valu;\nconst x4 = new ns.C().s;\nconst x5 = new ns.C().nope;\n"
        )
        assert(
            r == listOf(
                "a.ts(2,23) TS2339: Property 'nope' does not exist on type 'B'.",
                "a.ts(4,23) TS2551: Property 'valu' does not exist on type 'C'. Did you mean 'value'?",
                "a.ts(5,23) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
                "a.ts(6,23) TS2339: Property 'nope' does not exist on type 'C'.",
            )
        )
    }

    @Test
    fun `a parameter shadowing the namespace of a qualified new is silent`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nnamespace N { export class C { p = 0 } }\nfunction f(N: { C: new () => { z: number } }) { return new N.C().z; }\nconst x = new N.C().p;\n")
        assert(r.isEmpty())
    }

    @Test
    fun `a dotted base resolves for the chain walks`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nnamespace N { export class B { p = 0 } }\nclass C extends N.B { m() { return this.nope; } }\ndeclare const c: C;\nconst x1 = c.nope;\nconst x2 = new C().nope;\nconst x3 = c.p;\n")
        assert(
            r == listOf(
                "a.ts(3,41) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(5,14) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(6,20) TS2339: Property 'nope' does not exist on type 'C'.",
            )
        )
    }

    @Test
    fun `a declare class base written in a program source file resolves for the chain walks`() {
        val r = rows("// @Filename: /proj/src/a.ts\ndeclare class B { p: number; static s: number }\nclass C extends B { m() { return [this.nope, this.s, this.p]; } }\ndeclare const c: C;\nconst x1 = c.nope;\nconst x2 = new C().nope;\nconst x3 = c.p;\ndeclare class E { e: number }\ndeclare const e: E;\nconst x4 = e.nope;\n")
        assert(
            r == listOf(
                "a.ts(2,40) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(2,51) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
                "a.ts(4,14) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(5,20) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(9,14) TS2339: Property 'nope' does not exist on type 'E'.",
            )
        )
    }

    @Test
    fun `a dotted base sharing its subclass's name is walked and supplies inherited members and statics`() {
        // The chain walks' cycle guard used to be keyed by class NAME, so `class Server
        // extends net.Server` read the base as "already walked" and its members as missing.
        val r = rows(
            "// @Filename: /proj/src/m.ts\nexport class Server { zzServ = \"\"; static s = 1 }\n" +
                "// @Filename: /proj/src/a.ts\nimport * as net from \"./m\";\nnamespace N { export class Server { inner = 0 } }\nclass Server extends net.Server { zzTls = true; m() { return [this.zzServ, this.nope, this.s]; } }\nclass S2 extends N.Server { m() { return [this.inner, this.nope]; } }\ndeclare const sv: Server;\nconst p1 = sv.zzServ;\nconst p2 = sv.nope;\nconst p3 = new Server().zzServ;\n"
        )
        assert(
            r == listOf(
                "a.ts(3,81) TS2339: Property 'nope' does not exist on type 'Server'.",
                "a.ts(3,92) TS2576: Property 's' does not exist on type 'Server'. Did you mean to access the static member 'Server.s' instead?",
                "a.ts(4,60) TS2339: Property 'nope' does not exist on type 'S2'.",
                "a.ts(7,15) TS2339: Property 'nope' does not exist on type 'Server'.",
            )
        )
    }

    @Test
    fun `a same-named base reached through a star re-exporting ambient module supplies its members`() {
        val r = rows(
            "// @Filename: /proj/src/net.d.ts\ndeclare module \"net\" { class Server { zzServ: string; } }\ndeclare module \"node:net\" { export * from \"net\"; }\ndeclare module \"tls\" {\n  import * as net from \"node:net\";\n  class Server extends net.Server { zzTlsServer: boolean; }\n}\n" +
                "// @Filename: /proj/src/a.ts\nimport tls = require(\"tls\");\ndeclare const sv: tls.Server;\nconst p5: number = sv.zzServ;\nconst p6 = sv.zzTlsServer;\nconst p7 = sv.nope;\n"
        )
        assert(
            r == listOf(
                "a.ts(1,1) TS1202: Import assignment cannot be used when targeting ECMAScript modules. Consider using 'import * as ns from \"mod\"', 'import {a} from \"mod\"', 'import d from \"mod\"', or another module format instead.",
                "a.ts(3,7) TS2322: Type 'string' is not assignable to type 'number'.",
                "a.ts(5,15) TS2339: Property 'nope' does not exist on type 'Server'.",
            )
        )
    }
}
