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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.104): calling a LITERAL-typed or OBJECT-typed value is TS2349.
 *
 * ## The defect
 *
 * `ccetNoCallSignatureDiagnostics`' primitive arm read `calleeType is Type.Intrinsic`,
 * which is exactly the WIDENED half of the population: `let s = "a"` (type `string`)
 * reported and `const s = "a"` (type `"a"`) did not — and neither did a number or
 * bigint literal, a `"a"`-annotated const or parameter, a template literal, an
 * `as const`, an enum MEMBER, or a body-local. Its object arm fired only for a
 * syntactic `new X()` callee, so a class instance, an interface-typed value, an array
 * and an object literal's type were all silent. Measured on one fixture: 7 rows here
 * against 22 in BOTH tsgo 7.0.2 and pristine `typescript@6.0.3`.
 *
 * ## The two rules
 *
 * A literal's callability is decided by the same wrapper interface its base primitive's
 * is, so the primitive arm is a WIDER GATE and no new decision
 * ([calleeIsNonCallablePrimitiveLike]). The object arm is (CHK.45)'s rule: an object
 * callee is reported only with POSITIVE evidence its member table is complete
 * ([calleeObjectTableIsComplete]) — an array reference, a class or interface declared
 * in a PROGRAM file with no `extends` clause, or an anonymous object with a non-empty
 * member table and no signatures.
 *
 * ## What the displays are
 *
 * tsc names `getApparentType` of the callee, which for `bigint` and `symbol` is the
 * `BigInt` / `Symbol` wrapper that this checker's own `getApparentType` does not
 * supply — so `sym()` printed `Type 'symbol'` where both references print
 * `Type 'Symbol'`, a pre-existing FORM divergence [noCallSignatureDisplay] closes on
 * the way past. An enum MEMBER has no wrapper of its own and is named by its VALUE's
 * flavour.
 *
 * ## Stated refusals - deliberately NOT pinned
 *
 * A class with an `extends` clause, a LIB type (`Date`) and an EMPTY anonymous object
 * (`const o = {}; o()`) are all reported by both references and stay silent here: a
 * base type can bring a member this checker did not build, a lib heritage type is the
 * exact shape (CHK.45) measured two false positives on, and `{}` from a literal is
 * indistinguishable from `{}` from an unfinished resolution. A parenthesized inline
 * literal callee (`({ a: 1 })()`) is silent for a different reason — its type reaches
 * the reader as `any`. Per CLAUDE.md a known-open gap is recorded in the session note,
 * never pinned as a control.
 */
class NonCallableValueTest {

    private val directives = "// @strict: true\n// @target: es2020"

    private val prelude = """
        enum K { A = 1, B = 2 }
        enum SK { A = "a" }
        class C { x: number = 1 }
        class Base { b: number = 1 }
        class Derived extends Base { d: number = 2 }
        interface I { p: number }
        class Impl implements I { p: number = 1 }
    """.trimIndent()

    private fun chainOf(d: List<Diagnostic>): List<String> =
        d.first { it.code == 2349 }.messageChain

    // ---- rule A: a primitive-LIKE callee ----------------------------------------

    @Test
    fun `a const string literal callee reports with the String wrapper`() {
        val d = diagnose("const s = \"a\";\ns();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'String' has no call signatures."))
    }

    @Test
    fun `a const number literal callee reports with the Number wrapper`() {
        val d = diagnose("const n = 1;\nn();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'Number' has no call signatures."))
    }

    /**
     * `@useRealLibs` is load-bearing: the EMBEDDED lib declares no `BigInt` interface, so
     * [primitiveApparentWrapper] answers null there and the display falls back to the
     * literal itself (`Type '1n'`). Under the real libs — the configuration a project
     * build uses — it is the wrapper both references print.
     */
    @Test
    fun `a const bigint literal callee reports with the BigInt wrapper`() {
        val d = diagnose(
            "const bi = 1n;\nbi();\n",
            "// @strict: true\n// @target: es2020\n// @useRealLibs: true",
        )
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'BigInt' has no call signatures."))
    }

    @Test
    fun `a literal-annotated const callee reports`() {
        val d = diagnose("const sa: \"a\" = \"a\";\nsa();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'String' has no call signatures."))
    }

    @Test
    fun `a template literal callee reports`() {
        val d = diagnose("const t = `x`;\nt();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'String' has no call signatures."))
    }

    @Test
    fun `a const-asserted literal callee reports`() {
        val d = diagnose("const ac = \"a\" as const;\nac();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'String' has no call signatures."))
    }

    @Test
    fun `a numeric enum member callee reports with the Number wrapper`() {
        val d = diagnose(prelude + "\n\nconst em = K.A;\nem();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'Number' has no call signatures."))
    }

    @Test
    fun `a string enum member callee reports with the String wrapper`() {
        val d = diagnose(prelude + "\n\nconst sem = SK.A;\nsem();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'String' has no call signatures."))
    }

    @Test
    fun `a body-local literal callee reports`() {
        val d = diagnose("function f() {\n  const bl = \"a\";\n  bl();\n}\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'String' has no call signatures."))
    }

    @Test
    fun `a literal-typed parameter callee reports`() {
        val d = diagnose("function f(p: \"a\") { p(); }\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'String' has no call signatures."))
    }

    @Test
    fun `a symbol callee names the Symbol wrapper`() {
        val d = diagnose("declare const sym: symbol;\nsym();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'Symbol' has no call signatures."))
    }

    @Test
    fun `a never callee reports and names never`() {
        val d = diagnose("declare const nv: never;\nnv();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'never' has no call signatures."))
    }

    @Test
    fun `negative control - a callable value is silent`() {
        diagnose("declare const f: () => void;\nf();\n", directives) should {
            have(none { it.code == 2349 })
        }
    }

    @Test
    fun `negative control - a nullish callee keeps its own diagnostic`() {
        val d = diagnose("declare const u: undefined;\nu();\n", directives)
        assert(d.count { it.code == 2349 } == 0)
        assert(d.count { it.code == 2722 } == 1)
    }

    // ---- rule B: an OBJECT callee with a complete member table -------------------

    @Test
    fun `a class instance callee reports and names the class`() {
        val d = diagnose(prelude + "\n\ndeclare const inst: C;\ninst();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'C' has no call signatures."))
    }

    @Test
    fun `an implements-only class instance callee reports`() {
        val d = diagnose(prelude + "\n\ndeclare const im: Impl;\nim();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'Impl' has no call signatures."))
    }

    @Test
    fun `an interface-typed callee reports`() {
        val d = diagnose(prelude + "\n\ndeclare const i: I;\ni();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'I' has no call signatures."))
    }

    @Test
    fun `an array callee reports and names the array type`() {
        val d = diagnose("const arr = [1];\narr();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type 'number[]' has no call signatures."))
    }

    @Test
    fun `a non-empty object literal's type is a callee that reports`() {
        val d = diagnose("const ne = { a: 1 };\nne();\n", directives)
        assert(d.count { it.code == 2349 } == 1)
        assert(chainOf(d) == listOf("  Type '{ a: number; }' has no call signatures."))
    }

    /**
     * The binder's `canMerge` refuses Variable+Function, so `globals[name]` keeps ONE of
     * them and this reader can be handed the VARIABLE's type while the call itself is
     * checked against the FUNCTION's signature. tsc reports only the argument error; the
     * corpus's `errorElaboration` is the shipped instance and it grew an ours-only TS2349
     * the moment the object arm opened.
     */
    @Test
    fun `negative control - a duplicated identifier does not report an object callee`() {
        val d = diagnose(
            """
            declare function foo(x: () => number): void;
            declare let a: () => number;
            foo(a);
            const foo = { bar: 'a' };
            """.trimIndent(),
            directives,
        )
        assert(d.count { it.code == 2349 } == 0)
    }

    /**
     * `tryEmitUncallableTypeArgs` owns the same row for a class-instance callee carrying
     * explicit TYPE ARGUMENTS, and it runs AFTER the spine — so the dedupe lives there.
     * The corpus's `untypedFunctionCallsWithTypeParameters1` is the shipped instance.
     */
    @Test
    fun `a class instance callee with explicit type arguments reports exactly once`() {
        val d = diagnose(
            prelude + "\n\ndeclare const inst: C;\nconst r = inst<number>();\n",
            directives,
        )
        assert(d.count { it.code == 2349 } == 1)
    }
}
