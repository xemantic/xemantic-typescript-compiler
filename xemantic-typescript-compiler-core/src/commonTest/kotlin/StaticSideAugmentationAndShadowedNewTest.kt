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
 * (CHK.193) residues of (CHK.191). Every expectation is `tools/tsgo-7.0.2/lib/tsc`'s output
 * for the same file (`build/bench/p18250-agent/cells`); an empty list is a tsgo-silent cell.
 *
 * - (a) a base STATIC is not an instance-side override target (tsgo compares statics only
 *   against statics, TS2417); a base declaring both sides keeps its TS2416.
 * - (b) a `declare module "lib"` AUGMENTATION no leg can target (TS2664) defines no module:
 *   the import is TS2307 and its bindings are `any`; a SCRIPT file's `declare module` is a
 *   real ambient module and still types them.
 * - (c) `new N.C()` under a parameter `N` constructs through the parameter, not the outer
 *   namespace's class.
 * - (d) a merged interface's `extends` list contributes to the presence verdict (a class
 *   base) and to the TS2551 suggestion pool; a candidate under 3 characters is suggested
 *   only for a case-only difference.
 */
class StaticSideAugmentationAndShadowedNewTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @module: esnext\n// @lib: es2022,dom"

    private fun rows(source: String): List<String> =
        diagnose(source, directives = directives).map {
            "${it.fileName?.substringAfterLast('/')}(${it.line},${it.character}) TS${it.code}: ${it.message}"
        }.sorted()

    @Test
    fun `an instance member is not compared against a base static`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass A { static s = 1 }\nclass B extends A { s = \"x\" }\n")
        assert(r.isEmpty())
    }

    @Test
    fun `an instance method is not compared against a base static method`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass A { static m() { return 1 } }\nclass B extends A { m() { return \"x\" } }\n")
        assert(r.isEmpty())
    }

    @Test
    fun `control - a base static still meets a derived static as TS2417`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass A { static s = 1 }\nclass B extends A { static s = \"x\" }\n")
        assert(r == listOf("a.ts(3,7) TS2417: Class static side 'typeof B' incorrectly extends base class static side 'typeof A'."))
    }

    @Test
    fun `control - a base declaring both sides keeps its TS2416`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass A { s = 1; static s = true }\nclass B extends A { s = \"x\" }\n")
        assert(r == listOf("a.ts(3,21) TS2416: Property 's' in type 'B' is not assignable to the same property in base type 'A'."))
    }

    @Test
    fun `an untargeted augmentation leaves its import unresolved and its bindings any`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\ndeclare module \"lib\" { export class L { l: number } }\nimport { L } from \"lib\";\ndeclare const l: L;\nconst x2 = l.nope;\nconst x3 = new L().zz;\n")
        assert(
            r == listOf(
                "a.ts(2,16) TS2664: Invalid module name in augmentation, module 'lib' cannot be found.",
                "a.ts(3,19) TS2307: Cannot find module 'lib' or its corresponding type declarations.",
            )
        )
    }

    @Test
    fun `control - a script file's declare module is a real ambient module`() {
        val r = rows("// @Filename: /proj/src/a.ts\ndeclare module \"lib\" { export class L { l: number } }\n// @Filename: /proj/src/b.ts\nimport { L } from \"lib\";\ndeclare const l: L;\nconst x2 = l.nope;\n")
        assert(r == listOf("b.ts(3,14) TS2339: Property 'nope' does not exist on type 'L'."))
    }

    @Test
    fun `new through a shadowing parameter constructs the parameter's type`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nnamespace N { export class C { p = 0 } }\nfunction f(N: { C: new () => { z: number } }) { const v = new N.C(); const s: string = v; return v.z; }\nfunction g() { const v = new N.C(); const s: string = v; }\n")
        assert(
            r == listOf(
                "a.ts(3,76) TS2322: Type '{ z: number; }' is not assignable to type 'string'.",
                "a.ts(4,43) TS2322: Type 'C' is not assignable to type 'string'.",
            )
        )
    }

    @Test
    fun `a merged interface extending a class makes its absences TS2339`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass B { p = 0 }\ninterface C extends B {}\nclass C { m() { return 1; } }\ndeclare const c: C;\nconst x1 = c.nope;\nconst x2 = new C().nope;\nconst x3 = c.p;\n")
        assert(
            r == listOf(
                "a.ts(6,14) TS2339: Property 'nope' does not exist on type 'C'.",
                "a.ts(7,20) TS2339: Property 'nope' does not exist on type 'C'.",
            )
        )
    }

    @Test
    fun `the suggestion pool follows a merged interface's extends`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\nclass B { value = 0 }\ninterface C extends B {}\nclass C { m() { return 1; } }\ndeclare const c: C;\nconst x1 = c.valu;\ninterface I1 { ivalue: number }\ninterface D extends I1 {}\nclass D { }\ndeclare const d: D;\nconst x2 = d.ivalu;\n")
        assert(
            r == listOf(
                "a.ts(11,14) TS2551: Property 'ivalu' does not exist on type 'D'. Did you mean 'ivalue'?",
                "a.ts(6,14) TS2551: Property 'valu' does not exist on type 'C'. Did you mean 'value'?",
            )
        )
    }

    @Test
    fun `a candidate under three characters is not suggested for a non-case difference`() {
        val r = rows("// @Filename: /proj/src/a.ts\nexport {};\ninterface I1 { iv: number }\ninterface D extends I1 {}\nclass D { }\ndeclare const d: D;\nconst x2 = d.ivv;\n")
        assert(r == listOf("a.ts(6,14) TS2339: Property 'ivv' does not exist on type 'D'."))
    }
}
