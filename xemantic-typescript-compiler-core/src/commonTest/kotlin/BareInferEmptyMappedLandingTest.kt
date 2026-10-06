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
 * (P18.307) landing the two fixes held back since (P18.300) — a BARE `infer R` pattern and an EMPTY mapped
 * type — with the mechanisms whose type-fest rows they would otherwise have added, and the `as`-over-tuple
 * mapped type:
 *
 * 1. `T extends infer R [extends C] ? … : …` binds `R` to the check type (it answered `any`).
 * 2. A mapped type over an empty key set (`[K in never]`, `[K in keyof {}]`) is the empty object type `{}` —
 *    unless its constraint reads a type parameter still unbound, where tsgo defers the mapped type.
 * 3. `keyof` a mapped type literal this model could not enumerate is `any`, never `keyof any`'s closed
 *    `string | number | symbol`.
 * 4. A template `infer` pattern with a UNION span (`${infer R}${Whitespace}`) distributes over the union.
 * 5. A type literal keyed by a `unique symbol` is not the empty object `{}`: no primitive is assignable.
 * 6. A default import from a bare package specifier resolves through the crawl's own answer.
 * 7. A mapped type with an `as` clause over a tuple drops a non-literal key (`number`) whose remapped name is
 *    `never`, so it is an object, not `any`.
 * 8. `X & {}` for an object `X` is `X`.
 *
 * Every expected row is tsgo 7.0.2's row (head line), full text.
 */
class BareInferEmptyMappedLandingTest {

    private fun rows(d: List<Diagnostic>): List<String> = d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    private val realLibs = "// @strict: true\n// @useRealLibs: true\n// @lib: esnext"

    @Test
    fun `a bare infer binds the check type and its constraint decides the branch`() {
        val d = diagnose(
            """
            type First<T> = T extends infer R extends string ? [R] : 0;
            declare const f1: First<"x">; const g1: ["y"] = f1;
            declare const f2: First<1>; const g2: 1 = f2;
            type Same<T> = T extends infer R ? { v: R } : never;
            declare const s1: Same<number>; const h1: { v: string } = s1;
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf(
            "2:37 TS2322 Type '[\"x\"]' is not assignable to type '[\"y\"]'.",
            "3:35 TS2322 Type '0' is not assignable to type '1'.",
            "5:39 TS2322 Type '{ v: number; }' is not assignable to type '{ v: string; }'.",
        ))
    }

    @Test
    fun `a mapped type over an empty key set is the empty object type`() {
        val d = diagnose(
            """
            type E1 = { [K in never]: 1 };
            declare const e1: E1; const n1: number = e1;
            type E2 = { [K in keyof {}]: 1 };
            declare const e2: E2; const n2: number = e2;
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf(
            "2:29 TS2322 Type 'E1' is not assignable to type 'number'.",
            "4:29 TS2322 Type 'E2' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `control - a mapped type whose constraint reads an unbound type parameter stays permissive`() {
        // tsc's own deprecatedCompat shape: `OD` is a mapped type over `number` (an `any` here), so the
        // constraint computes `never`; tsgo defers the generic mapped type and reports nothing.
        val d = diagnose(
            """
            type OD = { readonly [P in number]: (...args: any[]) => any };
            type OK<T extends OD> = Extract<keyof T, number>;
            type OB<T extends OD> = { [P in OK<T>]: (args: unknown) => boolean | undefined };
            declare function hasProperty(o: object, k: string): boolean;
            export function cb<T extends OD>(overloads: T, binder: OB<T>) {
                for (let i = 0; hasProperty(overloads, `${'$'}{i}`) && hasProperty(binder, `${'$'}{i}`); i++) {
                    const fn = binder[i];
                }
            }
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d).isEmpty())
    }

    @Test
    fun `keyof an unenumerable mapped type literal is not the closed keyof any`() {
        val d = diagnose(
            """
            type IsAnyK<T> = 0 extends 1 & NoInfer<T> ? true : false;
            type ES<L, R> = IsAnyK<L> extends true ? false : [L] extends [R] ? true : false;
            type CK<B, C> = keyof { [K in (keyof B & {}) as ES<B[K], C> extends true ? K : never]: never };
            type Ex = { a: string; d: Record<string, unknown> };
            declare const ck: CK<Ex, string>;
            const ck1: "a" = ck;
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d).isEmpty())
    }

    @Test
    fun `a template infer pattern distributes over a union span`() {
        val d = diagnose(
            """
            type WS = ' ' | '_' | '-';
            type TrimR<V extends string> = V extends `${'$'}{infer R}${'$'}{WS}` ? TrimR<R> : V;
            type TrimL<V extends string> = V extends `${'$'}{WS}${'$'}{infer R}` ? TrimL<R> : V;
            declare const t1: TrimR<'ab _'>; const u1: 5 = t1;
            declare const t2: TrimL<'_ ab'>; const u2: 5 = t2;
            declare const t3: TrimR<'ab'>; const u3: 5 = t3;
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf(
            "4:40 TS2322 Type '\"ab\"' is not assignable to type '5'.",
            "5:40 TS2322 Type '\"ab\"' is not assignable to type '5'.",
            "6:38 TS2322 Type '\"ab\"' is not assignable to type '5'.",
        ))
    }

    @Test
    fun `a type literal keyed by a unique symbol is not a supertype of a primitive`() {
        val d = diagnose(
            """
            declare const tag: unique symbol;
            type Tagged<T, N extends string> = T & { readonly [tag]: { [K in N]: never } };
            type UserId = Tagged<string, 'UserId'>;
            declare const s: string;
            const id: UserId = s;
            type X = [string] extends [UserId] ? true : false;
            declare const x: X; const x1: true = x;
            declare const idv: UserId; const back: string = idv;
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf(
            "5:7 TS2322 Type 'string' is not assignable to type 'UserId'.",
            "7:27 TS2322 Type 'false' is not assignable to type 'true'.",
        ))
    }

    @Test
    fun `a default import from a bare package specifier is typed`() {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to """{"compilerOptions":{"strict":true,"noEmit":true,"target":"esnext","module":"node20","moduleResolution":"node16","lib":["ES2023"],"types":[]},"include":["t.ts"]}""",
                "/proj/node_modules/pk/package.json" to """{"name":"pk","type":"module","exports":{"types":"./index.d.ts"}}""",
                "/proj/node_modules/pk/index.d.ts" to "declare const tag: unique symbol;\nexport default tag;\nexport declare const d: string;\n",
                "/proj/t.ts" to "import tag from 'pk';\ntype O = { readonly [tag]: 1 };\ndeclare const s: string;\nconst o: O = s;\n",
            ),
        )
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val r = result.diagnostics.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(r == listOf("4:7 TS2322 Type 'string' is not assignable to type 'O'."))
    }

    @Test
    fun `an as clause over a tuple dropping number keys maps to an object`() {
        val d = diagnose(
            """
            type T3 = [number, number, number];
            type NoLen = { [P in keyof T3 as P extends number | 'length' | 'push' ? never : P]: T3[P] };
            declare const nl: NoLen;
            nl.push(1);
            const nl0: string = nl[0];
            const nl1: number = nl[1];
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf(
            "4:4 TS2339 Property 'push' does not exist on type 'NoLen'.",
            "5:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    private fun bodyEvaluations(references: Int, reference: String): Long {
        val source = buildString {
            appendLine("type Opts = { x?: boolean };")
            appendLine("type Impl<S extends string, O> = S extends `${'$'}{infer H}${'$'}{infer R}` ? [H, ...Impl<R, O>] : [];")
            appendLine("type Chars<S extends string, O extends Opts = {}> = Impl<S, O>;")
            for (i in 1..references) appendLine("declare const c$i: $reference; const n$i: number = c$i;")
        }
        val before = AliasSubstitutionCensus.bodyEvaluations
        val d = diagnose(source, directives = realLibs)
        val evaluations = AliasSubstitutionCensus.bodyEvaluations - before
        assert(d.size == references)
        return evaluations
    }

    @Test
    fun `a repeated alias reference with a literal and a defaulted empty object argument is evaluated once`() {
        val one = bodyEvaluations(1, "Chars<'abcdef'>")
        val many = bodyEvaluations(20, "Chars<'abcdef'>")
        assert(one > 0 && many == one)
    }

    @Test
    fun `a repeated reference to a conditional alias is evaluated once`() {
        val one = bodyEvaluations(1, "Impl<'abcdef', {}>")
        val many = bodyEvaluations(20, "Impl<'abcdef', {}>")
        assert(one > 0 && many == one)
    }

    @Test
    fun `control - distinct literal arguments to a cached alias keep distinct answers`() {
        val d = diagnose(
            """
            type Pick1<S> = S extends 'a' ? 1 : S extends 1 ? 3 : 2;
            declare const p1: Pick1<'a'>; const q1: 2 = p1;
            declare const p2: Pick1<'b'>; const q2: 1 = p2;
            declare const p3: Pick1<1>; const q3: 1 = p3;
            declare const p4: Pick1<'1'>; const q4: 1 = p4;
            declare const p5: Pick1<'a'>; const q5: 1 = p5;
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf(
            "2:37 TS2322 Type '1' is not assignable to type '2'.",
            "3:37 TS2322 Type '2' is not assignable to type '1'.",
            "4:35 TS2322 Type '3' is not assignable to type '1'.",
            "5:37 TS2322 Type '2' is not assignable to type '1'.",
        ))
    }

    @Test
    fun `an object intersected with the empty object type is the object`() {
        val d = diagnose(
            """
            declare const ie: { a: number } & {};
            ie.b;
            const ie1: string = ie.a;
            """.trimIndent(),
            directives = realLibs,
        )
        assert(rows(d) == listOf(
            "2:4 TS2339 Property 'b' does not exist on type '{ a: number; }'.",
            "3:7 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }
}
