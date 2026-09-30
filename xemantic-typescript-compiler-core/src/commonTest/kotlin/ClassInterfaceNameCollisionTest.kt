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
 * (CHK.182): a class instance's missing member is decided by the class's OWN symbol,
 * never by whether some interface ANYWHERE in the program shares its name.
 *
 * The chain walker behind every class-instance TS2339 ([Checker]'s
 * `lookupInstanceMemberInResolvableChain`) used to refuse any class whose NAME matched
 * a top-level interface in ANY program file. Module-scoped names never merge (INV.3(d)),
 * so `interface D` in one module silenced every TS2339 on `D` receivers in another —
 * parameters, file-level consts, body locals and `this`. The same refusal also silenced
 * a GENUINE same-scope merge (`interface D` + `class D` in one scope, or in two SCRIPT
 * files), where tsgo reports a member absent from both declarations. The walker now reads
 * the merged interfaces off the symbol and refuses only what it cannot read in full: a lib
 * interface, one with an `extends` list, one with an index / call / construct signature
 * (the last two are recorded residues — tsgo reports there too).
 *
 * Every expectation was measured against `tools/tsgo-7.0.2/lib/tsc` on the same files.
 */
class ClassInterfaceNameCollisionTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @module: esnext"

    private fun rows(source: String): List<String> =
        diagnose(source, directives = directives).map {
            "${it.fileName?.substringAfterLast('/')}(${it.line},${it.character}) TS${it.code}: ${it.message}"
        }.sorted()

    private val classFile = """
        class D { p = 0; opt?: D; m(k: string) { const self: D = this; const v = self.nope; } }
        function g(y: D) { return y.nope; }
        declare const z: D;
        const w = z.nope;
    """.trimIndent()

    private fun nopeRows(file: String, first: Int) = listOf(
        "$file(${first},79) TS2339: Property 'nope' does not exist on type 'D'.",
        "$file(${first + 1},29) TS2339: Property 'nope' does not exist on type 'D'.",
        "$file(${first + 3},13) TS2339: Property 'nope' does not exist on type 'D'.",
    )

    @Test
    fun `an interface in another MODULE file does not silence a class receiver`() {
        val r = rows(
            "// @Filename: /proj/src/a.ts\nexport {};\ninterface D { p: number; q: string }\n" +
                "// @Filename: /proj/src/b.ts\nexport {};\n$classFile\n"
        )
        assert(r == nopeRows("b.ts", 2))
    }

    @Test
    fun `the class declared in the FIRST file and the interface in the second`() {
        val r = rows(
            "// @Filename: /proj/src/a.ts\nexport {};\n$classFile\n" +
                "// @Filename: /proj/src/b.ts\nexport {};\ninterface D { p: number; q: string }\n"
        )
        assert(r == nopeRows("a.ts", 2))
    }

    @Test
    fun `a module interface does not silence a class in a SCRIPT file`() {
        val r = rows(
            "// @Filename: /proj/src/a.ts\nexport {};\ninterface D { p: number; q: string }\n" +
                "// @Filename: /proj/src/b.ts\n$classFile\n"
        )
        assert(r == nopeRows("b.ts", 1))
    }

    @Test
    fun `a script interface does not silence a class in a MODULE file`() {
        val r = rows(
            "// @Filename: /proj/src/a.ts\ninterface D { p: number; q: string }\n" +
                "// @Filename: /proj/src/b.ts\nexport {};\n$classFile\n"
        )
        assert(r == nopeRows("b.ts", 2))
    }

    @Test
    fun `a member only the other module's interface declares is missing on the class`() {
        val r = rows(
            "// @Filename: /proj/src/a.ts\nexport {};\ninterface D { q: string }\n" +
                "// @Filename: /proj/src/b.ts\nexport {};\n$classFile\nconst qq = z.q;\n"
        )
        assert(r == (nopeRows("b.ts", 2) + "b.ts(6,14) TS2339: Property 'q' does not exist on type 'D'.").sorted())
    }

    @Test
    fun `the spelling suggestion and the object literal check see the class alone`() {
        val r = rows(
            "// @Filename: /proj/src/a.ts\nexport {};\ninterface D { q: string }\n" +
                "// @Filename: /proj/src/b.ts\nexport {};\nclass D { pval = 0; }\ndeclare const z: D;\n" +
                "const a = z.pvl;\nconst b: D = { q: \"x\" };\nconst c = new D();\nconst n: number = c.q;\n"
        )
        assert(
            r == listOf(
                "b.ts(4,13) TS2551: Property 'pvl' does not exist on type 'D'. Did you mean 'pval'?",
                "b.ts(5,16) TS2353: Object literal may only specify known properties, and 'q' does not exist in type 'D'.",
                "b.ts(7,21) TS2339: Property 'q' does not exist on type 'D'.",
            )
        )
    }

    @Test
    fun `receivers in the INTERFACE's file keep their rows - control`() {
        val r = rows(
            "// @Filename: /proj/src/a.ts\nexport {};\ninterface D { q: string }\ndeclare const z: D;\n" +
                "const v = z.nope; const p = z.p;\n" +
                "// @Filename: /proj/src/b.ts\nexport {};\nclass D { p = 0 }\n"
        )
        assert(
            r == listOf(
                "a.ts(4,13) TS2339: Property 'nope' does not exist on type 'D'.",
                "a.ts(4,31) TS2339: Property 'p' does not exist on type 'D'.",
            )
        )
    }

    @Test
    fun `a same-scope merge reports a member absent from both and resolves the interface's`() {
        val r = rows(
            "// @Filename: /proj/src/a.ts\nexport {};\ninterface D { q: string }\nclass D { p = 0 }\n" +
                "declare const z: D;\nconst v = z.nope;\nconst s: string = z;\nconst n: number = z.q;\n"
        )
        assert(
            r == listOf(
                "a.ts(5,13) TS2339: Property 'nope' does not exist on type 'D'.",
                "a.ts(6,7) TS2322: Type 'D' is not assignable to type 'string'.",
                "a.ts(7,7) TS2322: Type 'string' is not assignable to type 'number'.",
            )
        )
    }

    @Test
    fun `two SCRIPT files still merge - the interface's member resolves on the class`() {
        val r = rows(
            "// @Filename: /proj/src/a.ts\ninterface D { q: string }\n" +
                "// @Filename: /proj/src/b.ts\n$classFile\nconst qq = z.q; const pp = z.p;\n"
        )
        assert(r == nopeRows("b.ts", 1))
    }

    @Test
    fun `a merge's spelling suggestion draws on the interface's members`() {
        val r = rows(
            "// @Filename: /proj/src/a.ts\nexport {};\ninterface D { quxx: string }\nclass D { p = 0 }\n" +
                "declare const z: D;\nconst a = z.qux;\n"
        )
        assert(r == listOf("a.ts(5,13) TS2551: Property 'qux' does not exist on type 'D'. Did you mean 'quxx'?"))
    }

    @Test
    fun `a merged class reached through a member access`() {
        val chained = rows(
            "// @Filename: /proj/src/a.ts\nexport {};\ninterface D { q: string }\nclass D { p = 0 }\n" +
                "declare const o: { d: D };\nconst a = o.d.nope; const b = o.d.q;\n"
        )
        assert(chained == listOf("a.ts(5,15) TS2339: Property 'nope' does not exist on type 'D'."))
    }

    @Test
    fun `a merged class reached through a new expression`() {
        val created = rows(
            "// @Filename: /proj/src/a.ts\ninterface D { q: string }\nclass D { p = 0 }\n" +
                "const a = new D().nope; const b = new D().q; const c = new D().qq;\n"
        )
        assert(
            created == listOf(
                "a.ts(3,19) TS2339: Property 'nope' does not exist on type 'D'.",
                "a.ts(3,64) TS2339: Property 'qq' does not exist on type 'D'.",
            )
        )
    }

    @Test
    fun `a subclass of a merged base reads the base's interface members`() {
        val r = rows(
            "// @Filename: /proj/src/a.ts\ninterface D { q: string }\n" +
                "class D { p = 0; m() { return this.nopex; } }\n" +
                "class E extends D { n() { return this.nope + this.q + this.qq; } }\n"
        )
        assert(
            r == listOf(
                "a.ts(2,36) TS2339: Property 'nopex' does not exist on type 'D'.",
                "a.ts(3,39) TS2339: Property 'nope' does not exist on type 'E'.",
                "a.ts(3,60) TS2339: Property 'qq' does not exist on type 'E'.",
            )
        )
    }

    @Test
    fun `a merged interface with a string index signature admits every name - control`() {
        val r = rows(
            "// @Filename: /proj/src/a.ts\nexport {};\ninterface D { [k: string]: unknown }\nclass D { p = 0 }\n" +
                "declare const z: D;\nconst a = z.nope;\n"
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a merged interface with an extends list is refused rather than read - control`() {
        // tsgo reports nothing here either: `r` comes from the interface's BASE, which
        // the walk does not follow, so reading the interface's own members alone would
        // report it as missing.
        val r = rows(
            "// @Filename: /proj/src/a.ts\nexport {};\ninterface Base { r: number }\n" +
                "interface D extends Base { q: string }\nclass D { p = 0 }\n" +
                "declare const z: D;\nconst b = z.r; const c = z.q;\n"
        )
        assert(r.isEmpty())
    }
}
