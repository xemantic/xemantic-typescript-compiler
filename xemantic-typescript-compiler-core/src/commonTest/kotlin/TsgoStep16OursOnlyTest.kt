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
 * (LEGACY.0b) step 16, round (P18.101): rows where THIS compiler reported something TypeScript 7
 * (tsgo 7.0.2, the ONLY compatibility target) does not on plain `.ts` sources, or printed the
 * wrong HEAD for a right verdict. Every expectation below was read off
 * `tools/tsgo-7.0.2/lib/tsc -p .` over the same fixture text (2026-09-15) or off tsgo's harness
 * baselines under `typescript-go-repo/testdata/baselines/reference/submodule/`.
 *
 *  * **M1** — TS2346 *Call target does not contain any signatures.* has no emission site in
 *    tsgo; the 16.4db `super()` walker that pinned it is deleted. The TS2315 it rode on stays.
 *  * **M2** — TS2309 is tsgo's `checkExternalModuleExports`: the module's other exports must
 *    include a VALUE (aliases judged by their target, an unresolvable one counting as a value),
 *    or the `export =` target is a namespace that itself exports a type/namespace member while
 *    the module exports one too. No `.d.ts` skip, no `emitDeclarationOnly` skip, and a JS
 *    `module.exports = X` beside `exports.p = …` reports on the assignment expression.
 *  * **M3** — an `export { Sub }` specifier inside a `declare namespace exports { … }` body is
 *    not a second declaration of `Sub`; the dedicated clodule re-export pin walker is deleted.
 *  * **M4** — the readonly-vs-mutable chain entry (and the two excessive-complexity ones)
 *    REPLACES the head when both its arguments equal the head's (`RelationHeadSuppression`).
 *  * **M5** — a circular mapped type is TS2615 alone, never TS2615 beside TS2589.
 *  * **M6** — the `bigintWithLib` corpus pin's TS2769 chain names the last overload's argument
 *    error ONCE (tsc 6 nested it three times).
 */
class TsgoStep16OursOnlyTest {

    private fun baseline(text: String, fileName: String = "t.ts"): String =
        TypeScriptCompiler().compile(text, fileName).toErrorBaseline() ?: ""

    /** The `.errors.txt` summary block: every `file(line,col): error TSnnnn: …` row with its
     *  chain lines, i.e. the lines before the first blank one. */
    private fun rows(text: String, fileName: String = "t.ts"): List<String> =
        baseline(text, fileName).split("\r\n").takeWhile { it.isNotEmpty() }

    private val ts2309 = "error TS2309: An export assignment cannot be used in a module with other exported elements."

    // ------------------------------------------------------------------ M1

    private val interfaceMergeShape = "// @strict: true\n" +
        "export class SomeBaseClass { }\n" +
        "export interface SomeInterface { }\n" +
        "export interface MergedClass extends SomeInterface { }\n" +
        "export class MergedClass extends SomeBaseClass<any> {\n" +
        "\tpublic constructor() {\n" +
        "\t\tsuper();\n" +
        "\t}\n" +
        "}\n"

    @Test
    fun `M1 a super call under a refused non-generic base is not TS2346`() {
        val r = rows(interfaceMergeShape)
        assert(r == listOf("t.ts(4,34): error TS2315: Type 'SomeBaseClass' is not generic."))
    }

    @Test
    fun `M1 negative control - the TS2315 on the extends clause stays`() {
        val d = TypeScriptCompiler().compile(interfaceMergeShape, "t.ts").diagnostics
        val ts2315 = d.count { it.code == 2315 }
        val ts2346 = d.count { it.code == 2346 }
        assert(ts2315 == 1)
        assert(ts2346 == 0)
    }

    // ------------------------------------------------------------------ M2

    @Test
    fun `M2 an interface beside an export assignment is not another exported element`() {
        val r = rows(
            "// @strict: true\n" +
                "declare module \"foo\" {\n" +
                "    export interface x { a: string }\n" +
                "    interface y { a: Date }\n" +
                "    export = y;\n" +
                "}\n"
        )
        assert(r.isEmpty())
    }

    @Test
    fun `M2 negative control - a value export beside an export assignment reports`() {
        val r = rows(
            "// @strict: true\n" +
                "declare module \"baz\" {\n" +
                "    export namespace a { export var b: number; }\n" +
                "    namespace c { export var c: string; }\n" +
                "    export = c;\n" +
                "}\n"
        )
        assert(r == listOf("t.ts(4,5): $ts2309"))
    }

    @Test
    fun `M2 a shadowed namespace reports - the export target exports a type of its own`() {
        val r = rows(
            "// @strict: true\n" +
                "declare module \"sn\" {\n" +
                "    export interface I { a: 1 }\n" +
                "    namespace c { export interface J { b: 2 } }\n" +
                "    export = c;\n" +
                "}\n"
        )
        assert(r == listOf("t.ts(4,5): $ts2309"))
    }

    @Test
    fun `M2 negative control - a namespace target exporting only values does not shadow`() {
        val r = rows(
            "// @strict: true\n" +
                "declare module \"sn2\" {\n" +
                "    export interface I { a: 1 }\n" +
                "    namespace c { export var v: string; }\n" +
                "    export = c;\n" +
                "}\n"
        )
        assert(r.isEmpty())
    }

    @Test
    fun `M2 an alias is judged by its target - a type-only clause of an enum reports and export star does not`() {
        val r = rows(
            "// @strict: true\n" +
                "declare module \"enumX\" { export enum E { A } }\n" +
                "declare module \"typeOnlyClause\" {\n" +
                "    export type { E } from \"enumX\";\n" +
                "    namespace c { export var v: string; }\n" +
                "    export = c;\n" +
                "}\n" +
                "declare module \"starExport\" {\n" +
                "    export * from \"enumX\";\n" +
                "    namespace c { export var v: string; }\n" +
                "    export = c;\n" +
                "}\n"
        )
        assert(r == listOf("t.ts(5,5): $ts2309"))
    }

    @Test
    fun `M2 an exported namespace counts only when it is instantiated`() {
        val r = rows(
            "// @strict: true\n" +
                "declare module \"declNsTypeOnly\" {\n" +
                "    export namespace N { export interface I { a: 1 } }\n" +
                "    namespace c { export var v: string; }\n" +
                "    export = c;\n" +
                "}\n" +
                "declare module \"declNsValue\" {\n" +
                "    export namespace N { export var q: 1; }\n" +
                "    namespace c { export var v: string; }\n" +
                "    export = c;\n" +
                "}\n"
        )
        assert(r == listOf("t.ts(9,5): $ts2309"))
    }

    @Test
    fun `M2 an alias to an unexported namespace member is unknown and so a value`() {
        val r = rows(
            "// @module: commonjs\n" +
                "namespace x {\n" +
                "    interface c {\n" +
                "    }\n" +
                "}\n" +
                "export import a = x.c;\n" +
                "export = x;\n"
        )
        assert("t.ts(6,1): $ts2309" in r)
    }

    @Test
    fun `M2 a declaration file is an external module too`() {
        val r = rows(
            "// @strict: true\n" +
                "export declare const zz: number;\n" +
                "declare class Q {}\n" +
                "export = Q;\n",
            fileName = "d.d.ts",
        )
        assert(r == listOf("d.d.ts(3,1): $ts2309"))
    }

    @Test
    fun `M2 emitDeclarationOnly does not switch the rule off`() {
        val r = rows(
            "// @declaration: true\n" +
                "// @emitDeclarationOnly: true\n" +
                "// @module: commonjs\n" +
                "export class Z {}\n" +
                "interface y { a: Date }\n" +
                "export = y;\n"
        )
        assert(r == listOf("t.ts(3,1): $ts2309"))
    }

    @Test
    fun `M2 a JavaScript module-exports assignment beside an exports property reports on the expression`() {
        val d = diagnose(
            """
            module.exports = {
                p: 1,
            };
            exports.q = 2;
            """,
            directives = "// @allowJs: true\n// @checkJs: true",
            fileName = "file.js",
        )
        val hits = d.filter { it.code == 2309 }
        assert(hits.size == 1)
        val h = hits[0]
        val length = h.length
        val start = h.start
        assert(h.message == "An export assignment cannot be used in a module with other exported elements.")
        assert(start == 0)
        assert(length == "module.exports = {\n    p: 1,\n}".length)
    }

    @Test
    fun `M2 negative control - a lone JavaScript module-exports assignment is silent`() {
        val d = diagnose(
            """
            module.exports = { p: 1 };
            """,
            directives = "// @allowJs: true\n// @checkJs: true",
            fileName = "file.js",
        )
        assert(d.none { it.code == 2309 })
    }

    // ------------------------------------------------------------------ M3

    @Test
    fun `M3 a re-export specifier in a declare namespace body is not a duplicate declaration`() {
        val r = rows(
            "// @module: commonjs\n" +
                "export = exports;\n" +
                "declare class exports {\n" +
                "    constructor(p: number);\n" +
                "    t: number;\n" +
                "}\n" +
                "export class Sub {\n" +
                "    instance!: {\n" +
                "        t: number;\n" +
                "    };\n" +
                "}\n" +
                "declare namespace exports {\n" +
                "    export { Sub };\n" +
                "}\n",
            fileName = "input.ts",
        )
        assert(r == listOf("input.ts(1,1): $ts2309"))
    }

    @Test
    fun `M3 negative control - a genuine duplicate class reports at both declarations`() {
        val r = rows("// @strict: true\nexport class Sub {}\nexport class Sub {}\n")
        assert(
            r == listOf(
                "t.ts(1,14): error TS2300: Duplicate identifier 'Sub'.",
                "t.ts(2,14): error TS2300: Duplicate identifier 'Sub'.",
            )
        )
    }

    // ------------------------------------------------------------------ M4

    @Test
    fun `M4 a readonly tuple argument against a mutable parameter is a bare TS4104 head`() {
        val r = rows(
            "// @strict: true\n" +
                "let point = [3, 4] as const;\n" +
                "declare function arryFn(x: number[]): void;\n" +
                "arryFn(point);\n"
        )
        assert(r == listOf("t.ts(3,8): error TS4104: The type 'readonly [3, 4]' is 'readonly' and cannot be assigned to the mutable type 'number[]'."))
    }

    @Test
    fun `M4 a readonly array argument against a mutable parameter is a bare TS4104 head`() {
        val r = rows(
            "// @strict: true\n" +
                "declare function arryFn2(x: Array<number>): void;\n" +
                "declare const a: readonly number[];\n" +
                "arryFn2(a);\n"
        )
        assert(r == listOf("t.ts(3,9): error TS4104: The type 'readonly number[]' is 'readonly' and cannot be assigned to the mutable type 'number[]'."))
    }

    @Test
    /** Green on both arms by construction: the var-decl emitter already emits TS4104 as its own head,
     *  so this is a CONTROL that the rule leaves an already-bare head alone, not a pin on the rule. */
    fun `M4 control - the var-decl position's TS4104 head is emitted directly and untouched by the rule`() {
        val r = rows("// @strict: true\nconst t3: readonly [1] = [1];\nconst t4: [] = t3;\n")
        assert(r == listOf("t.ts(2,7): error TS4104: The type 'readonly [1]' is 'readonly' and cannot be assigned to the mutable type '[]'."))
    }

    private fun head(message: String, code: Int, vararg chain: String) = Diagnostic(
        message = message, category = DiagnosticCategory.Error, code = code,
        fileName = "t.ts", line = 0, character = 0, start = 0, length = 1,
        messageChain = chain.toList(),
    )

    @Test
    fun `M4 negative control - a head whose generalized source differs from the readonly entry's keeps its head`() {
        // variadicTuples1.ts(170,5): `Type 'T' is not assignable to type '[...T]'.` over
        // `The type 'readonly unknown[]' is 'readonly' and cannot be assigned to the mutable type '[...T]'.`
        val d = head(
            "Type 'T' is not assignable to type '[...T]'.", 2322,
            "  The type 'readonly unknown[]' is 'readonly' and cannot be assigned to the mutable type '[...T]'.",
        )
        val out = RelationHeadSuppression.suppressHead(d)
        assert(out === d)
    }

    @Test
    fun `M4 negative control - a readonly entry whose target differs from the head's keeps its head`() {
        val d = head(
            "Argument of type 'readonly [3, 4]' is not assignable to parameter of type 'Other'.", 2345,
            "  The type 'readonly [3, 4]' is 'readonly' and cannot be assigned to the mutable type 'number[]'.",
        )
        val out = RelationHeadSuppression.suppressHead(d)
        assert(out === d)
    }

    @Test
    fun `M4 the excessive-complexity and stack-depth entries suppress the same way`() {
        val complexity = RelationHeadSuppression.suppressHead(head(
            "Type 'T1 & T2' is not assignable to type 'T1 | null'.", 2322,
            "  Excessive complexity comparing types 'T1 & T2' and 'T1 | null'.",
        ))
        val depth = RelationHeadSuppression.suppressHead(head(
            "Argument of type 'A' is not assignable to parameter of type 'B'.", 2345,
            "  Excessive stack depth comparing types 'A' and 'B'.",
            "    Type 'x' is not assignable to type 'y'.",
        ))
        assert(complexity.code == 2859)
        assert(complexity.message == "Excessive complexity comparing types 'T1 & T2' and 'T1 | null'.")
        assert(complexity.messageChain.isEmpty())
        assert(depth.code == 2321)
        assert(depth.message == "Excessive stack depth comparing types 'A' and 'B'.")
        assert(depth.messageChain == listOf("  Type 'x' is not assignable to type 'y'."))
    }

    @Test
    fun `M4 the readonly case has no conversion exclusion`() {
        // relater.go ~4798: only the missing-property arms test isConversionOrInterfaceImplementationMessage.
        val d = RelationHeadSuppression.suppressHead(head(
            "Conversion of type 'readonly [1]' to type '[1]' may be a mistake because neither type sufficiently " +
                "overlaps with the other. If this was intentional, convert the expression to 'unknown' first.", 2352,
            "  The type 'readonly [1]' is 'readonly' and cannot be assigned to the mutable type '[1]'.",
        ))
        assert(d.code == 4104)
    }

    // ------------------------------------------------------------------ M5

    @Test
    fun `M5 a circular mapped type is TS2615 alone`() {
        val r = rows(
            "// @strict: true\n" +
                "type N<T, K extends string> = T | { [P in K]: N<T, K> }[K];\n" +
                "\n" +
                "type M = N<number, \"M\">;\n"
        )
        assert(r == listOf("t.ts(3,10): error TS2615: Type of property 'M' circularly references itself in mapped type '{ [P in \"M\"]: any; }'."))
    }

    @Test
    fun `M5 negative control - a concrete alias whose body is a genuinely deep instantiation still trips TS2589`() {
        // tsgo 7.0.2 (2026-09-15): `type X = Foo<"true", {}>` reports TS2589 at the alias body — no
        // mapped-type circularity here, so the alias site keeps its TS2589 arm.
        val r = rows(
            "// @strict: true\n" +
                "type Foo<T extends \"true\", B> = { \"true\": Foo<T, Foo<T, B>> }[T];\n" +
                "type X = Foo<\"true\", {}>;\n"
        )
        assert(r == listOf("t.ts(2,10): error TS2589: Type instantiation is excessively deep and possibly infinite."))
    }

    /** Green on both arms of the alias-site ablation: this row is emitted at the ANNOTATION site,
     *  so it is a CONTROL that the annotation-site arm is untouched, not a pin on the alias site. */
    @Test
    fun `M5 control - a deep instantiation in a variable annotation still trips TS2589 at the annotation`() {
        // limitDeepInstantiations: tsgo reports the depth limit at the concrete alias's use.
        val r = rows(
            "// @strict: true\n" +
                "type Foo<T extends \"true\", B> = { \"true\": Foo<T, Foo<T, B>> }[T];\n" +
                "let f1: Foo<\"true\", {}>;\n" +
                "let f2: Foo<\"false\", {}>;\n"
        )
        assert(
            r == listOf(
                "t.ts(2,9): error TS2589: Type instantiation is excessively deep and possibly infinite.",
                "t.ts(3,13): error TS2344: Type '\"false\"' does not satisfy the constraint '\"true\"'.",
            )
        )
    }

    // ------------------------------------------------------------------ M6

    @Test
    fun `M6 the bigintWithLib pin's overload chain names the argument error once`() {
        val source = "// @strict: false\n// @target: es2020\n" +
            "let bigIntArray: BigInt64Array = new BigInt64Array();\n" +
            "bigIntArray = new BigInt64Array([1, 2, 3]);\n"
        val d = TypeScriptCompiler().compile(source, "bigintWithLib.ts").diagnostics
        val overloads = d.filter { it.code == 2769 }
        val chains = overloads.map { it.messageChain }.distinct()
        assert(overloads.size == 6)
        assert(
            chains == listOf(
                listOf(
                    "  The last overload gave the following error.",
                    "    Type 'number' is not assignable to type 'bigint'.",
                ),
            )
        )
    }
}
