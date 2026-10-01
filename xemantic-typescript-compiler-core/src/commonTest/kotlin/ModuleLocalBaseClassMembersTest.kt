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
 * (CHK.187): the class-instance missing-member family resolves a base class, and the class
 * a `new` constructs, where the name is WRITTEN — the declaring file's per-file scope, its
 * enclosing namespaces and the B83.5 scope-space tables — instead of through `globals`,
 * which INV.3(d) keeps free of module locals. A module-local or imported base used to make
 * every chain walk answer "unresolvable", so `c.nope`, `this.nope` and `new D().nope` were
 * all silent inside a module. Also pinned: a merged interface's `extends` list is followed
 * rather than refused, a `[k: number]` index signature no longer refuses an identifier
 * name, and the `new` branch reports a static as TS2576 and a near miss as TS2551.
 *
 * Every expectation is `tools/tsgo-7.0.2/lib/tsc`'s output for the same files
 * (`build/bench/p18246-agent/cells`). The silent receivers inside each fixture (members
 * present on the base, shadowing bindings, `NaN` / `Infinity` under a number index) are
 * the controls.
 */
class ModuleLocalBaseClassMembersTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @module: esnext\n// @lib: es2022,dom"

    private fun rows(source: String): List<String> =
        diagnose(source, directives = directives).map {
            "${it.fileName?.substringAfterLast('/')}(${it.line},${it.character}) TS${it.code}: ${it.message}"
        }.sorted()

    @Test
    fun `a module-local base supplies members and its absences are TS2339 TS2576 and TS2551`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass B { p = 0; value = 2; static s = 1 }\nclass C extends B { k = 1 }\ndeclare const c: C;\nconst x1 = c.nope;\nconst x2 = c.p;\nconst x3 = c.s;\nconst x4 = c.valu;\n")
        assert(
            r == listOf(
                "a.ts(5,14) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(7,14) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
                "a.ts(8,14) TS2551: Property 'valu' does not exist on type 'C'. Did you mean 'value'?",
            )
        )
    }

    @Test
    fun `new of a module-local class reports a missing member and draws statics and suggestions from the base`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass B { p = 0; value = 2; static s = 1 }\nclass C extends B { k = 1 }\nconst x1 = new C().nope;\nconst x2 = new C().p;\nconst x3 = new C().s;\nconst x4 = new C().valu;\nconst x5 = new B().nope;\n")
        assert(
            r == listOf(
                "a.ts(4,20) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(6,20) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
                "a.ts(7,20) TS2551: Property 'valu' does not exist on type 'C'. Did you mean 'value'?",
                "a.ts(8,20) TS2339: Property 'nope' does not exist on type 'B'.",
            )
        )
    }

    @Test
    fun `this in a subclass of a module-local base`() {
        // `this.s` (a static on the base) is left out: tsgo reports TS2576 there and this
        // path is silent, in a script file as well — a recorded residue, not pinned.
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass B { p = 0; value = 2; static s = 1 }\nclass C extends B {\n  m() { return [this.nope, this.p, this.valu]; }\n}\n")
        assert(
            r == listOf(
                "a.ts(4,22) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(4,41) TS2551: Property 'valu' does not exist on type 'C'. Did you mean 'value'?",
            )
        )
    }

    @Test
    fun `a base imported from another module`() {
        val r = rows("// @Filename: /proj/src/b.ts\nexport class B { p = 0; value = 2; static s = 1 }\n// @Filename: /proj/src/a.ts\nimport { B } from \"./b\";\nclass C extends B { k = 1 }\ndeclare const c: C;\nconst x1 = c.nope;\nconst x2 = c.p;\nconst x3 = c.s;\nconst x4 = c.valu;\nconst x5 = new C().nope;\nconst x6 = new B().nope;\n")
        assert(
            r == listOf(
                "a.ts(4,14) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(6,14) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
                "a.ts(7,14) TS2551: Property 'valu' does not exist on type 'C'. Did you mean 'value'?",
                "a.ts(8,20) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(9,20) TS2339: Property 'nope' does not exist on type 'B'.",
            )
        )
    }

    @Test
    fun `a default-imported base`() {
        val r = rows("// @Filename: /proj/src/b.ts\nexport default class B { p = 0; value = 2 }\n// @Filename: /proj/src/a.ts\nimport B from \"./b\";\nclass C extends B { m() { return this.nope; } }\ndeclare const c: C;\nconst x1 = c.nope;\nconst x2 = c.p;\nconst x3 = new B().nope;\nconst x4 = c.valu;\n")
        assert(
            r == listOf(
                "a.ts(2,39) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(4,14) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(6,20) TS2339: Property 'nope' does not exist on type 'B'.",
                "a.ts(7,14) TS2551: Property 'valu' does not exist on type 'C'. Did you mean 'value'?",
            )
        )
    }

    @Test
    fun `a block-scoped base shadowing a module-local class of the same name`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass B { p = 0 }\nfunction f() { class B { q = 1 } class C extends B { m() { return [this.q, this.p]; } } return new C().q; }\n")
        assert(
            r == listOf(
                "a.ts(3,81) TS2339: Property 'p' does not exist on type 'C'.",
            )
        )
    }

    @Test
    fun `new of a block-scoped class answers that class and a shadowing parameter or local stays silent`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass D { x = 1 }\nfunction f() { class D { y = 1 } return [new D().y, new D().x]; }\nfunction g(D: any) { return new D().zz; }\nfunction h() { const D = class { z = 1 }; return new D().z; }\nfunction k() { let D = Date; return new D().getTime(); }\nconst ok = new D().x;\n")
        assert(
            r == listOf(
                "a.ts(3,61) TS2339: Property 'x' does not exist on type 'D'.",
            )
        )
    }

    @Test
    fun `a merged interface's extends chain is followed`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\ninterface Base0 { z: number }\ninterface Base extends Base0 { r: number }\ninterface D extends Base { q: string }\nclass D { p = 0 }\ndeclare const d: D;\nconst x1 = d.nope;\nconst x2 = d.r;\nconst x3 = d.z;\nconst x4 = d.q;\nconst x5 = new D().z;\nconst x6 = new D().nope;\n")
        assert(
            r == listOf(
                "a.ts(12,20) TS2339: Property 'nope' does not exist on type 'D'.",
                "a.ts(7,14) TS2339: Property 'nope' does not exist on type 'D'.",
            )
        )
    }

    @Test
    fun `a class number index signature cannot name an identifier member`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass D { [k: number]: string; p = '' }\ndeclare const d: D;\nconst x1 = d.nope;\nconst x2 = d.NaN;\nconst x3 = d[0];\nconst x4 = new D().nope;\nclass E extends D { m() { return this.nope; } }\n")
        assert(
            r == listOf(
                "a.ts(4,14) TS2339: Property 'nope' does not exist on type 'D'.",
                "a.ts(7,20) TS2339: Property 'nope' does not exist on type 'D'.",
                "a.ts(8,39) TS2339: Property 'nope' does not exist on type 'E'.",
            )
        )
    }

    @Test
    fun `a merged number index signature admits NaN and Infinity but not other identifiers`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\ninterface D { [k: number]: string }\nclass D { p = '' }\ndeclare const d: D;\nconst x1 = d.nope;\nconst x2 = d.Infinity;\nconst x3 = d._x;\n")
        assert(
            r == listOf(
                "a.ts(5,14) TS2339: Property 'nope' does not exist on type 'D'.",
                "a.ts(7,14) TS2339: Property '_x' does not exist on type 'D'.",
            )
        )
    }

    @Test
    fun `a merged string index signature admits every name - control`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\ninterface D { q: string; [k: string]: string | number }\nclass D { p = 0 }\ndeclare const d: D;\nconst x1 = d.nope;\n")
        assert(r.isEmpty())
    }

    @Test
    fun `new in a SCRIPT file reports TS2576 and TS2551 rather than TS2339`() {
        val r = rows("// @Filename: /proj/src/a.ts\nclass B { p = 0; value = 2; static s = 1 }\nclass C extends B { k = 1 }\nconst x1 = new C().nope;\nconst x2 = new C().p;\nconst x3 = new C().s;\nconst x4 = new C().valu;\nconst x5 = new B().nope;\n")
        assert(
            r == listOf(
                "a.ts(3,20) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(5,20) TS2576: Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?",
                "a.ts(6,20) TS2551: Property 'valu' does not exist on type 'C'. Did you mean 'value'?",
                "a.ts(7,20) TS2339: Property 'nope' does not exist on type 'B'.",
            )
        )
    }
}
