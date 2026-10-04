/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 */
package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (P18.295) (LIBS.3) DEFK2 + THISTYPE. Every expectation below is tsgo 7.0.2's own output for
 * the same file (`tools/tsgo-7.0.2/lib/tsc`), rendered `line:column TScode message` with the
 * elaboration lines trimmed beneath.
 *
 * DEFK2's own suspect — the (CHK.228) any-provenance gate — was REFUTED: type-fest's
 * `IsAny<T> = 0 extends 1 & NoInfer<T>` is deferred because `NoInfer<X>` evaluates only for a
 * genuine `any`, and evaluating it for every `X` was measured and refused (type-fest ours-only
 * 206 -> 249; evaluating it only inside a per-key `as` clause, 199 but with 14 new non-tsgo rows
 * leaking through shared caches into template-literal and default-options machinery). What the
 * repro chain exposed instead are six evaluation/relation defects, pinned here:
 *
 *  1. an OPTIONAL source property satisfied a REQUIRED target one in the relation itself
 *     (`Relater.propertiesRelatedTo`, and the intersection-source merge), so
 *     `O extends Record<'a', O['a']>` was `true`; with its elaboration line;
 *  2. a homomorphic mapped member dropped a mapped-`?` source's optionality (`Simplify<… &
 *     Partial<…>>`);
 *  3. `boolean` did not distribute through a naked check type parameter (`D<boolean>`);
 *  4. `boolean` did not relate to a `true | false` union target;
 *  5. `keyof [1, 2]` lacked the element keys `"0" | "1"`;
 *  6. a conditional alias's RESOLVED branch was displayed by the alias name.
 */
class OptionalRelationBooleanAndThisTypeTest {

    private fun render(d: List<Diagnostic>): List<String> = d.flatMap { x ->
        listOf("${x.line}:${x.character} TS${x.code} ${x.message}") + x.messageChain.map { it.trim() }
    }

    private val exactOptional = "// @strict: true\n// @exactOptionalPropertyTypes: true\n// @useRealLibs: true"

    private val realLibs = "// @strict: true\n// @useRealLibs: true"

    @Test
    fun `optional source property never satisfies a required target in a conditional or an assignment`() {
        val d = diagnose(
            """
            type O = { a?: string; b: number }
            const c1: 'z' = {} as (O extends Record<'a', O['a']> ? 1 : 2)
            const c2: 'z' = {} as (O extends { a: string | undefined } ? 1 : 2)
            const c3: 'z' = {} as (O extends Record<'b', O['b']> ? 1 : 2)
            declare const o: O
            let w: { a: any } = { a: 1 }
            w = o
            function r(): { a: any } { return o }
            const ok1: { a?: string } = o
            """,
            exactOptional,
        )
        assert(
            render(d) == listOf(
                "2:7 TS2322 Type '2' is not assignable to type '\"z\"'.",
                "3:7 TS2322 Type '2' is not assignable to type '\"z\"'.",
                "4:7 TS2322 Type '1' is not assignable to type '\"z\"'.",
                "7:1 TS2322 Type 'O' is not assignable to type '{ a: any; }'.",
                "Property 'a' is optional in type 'O' but required in type '{ a: any; }'.",
                "8:28 TS2322 Type 'O' is not assignable to type '{ a: any; }'.",
                "Property 'a' is optional in type 'O' but required in type '{ a: any; }'.",
            )
        )
    }

    @Test
    fun `an intersection property is optional only when every declaring constituent says so`() {
        val d = diagnose(
            """
            type I = { a?: 1 } & { b?: 2; c: 3 }
            const c4: 'z' = {} as (I extends Record<'a', I['a']> ? 1 : 2)
            const c5: 'z' = {} as (I extends Record<'c', I['c']> ? 1 : 2)
            """,
            exactOptional,
        )
        assert(
            render(d) == listOf(
                "2:7 TS2322 Type '2' is not assignable to type '\"z\"'.",
                "3:7 TS2322 Type '1' is not assignable to type '\"z\"'.",
            )
        )
    }

    @Test
    fun `a homomorphic mapped member keeps a mapped optional source property optional`() {
        val d = diagnose(
            """
            type Simplify<T> = { [K in keyof T]: T[K] } & {}
            const d1: Simplify<{ a: boolean } & Partial<Record<'s', never>>> = { a: false }
            """,
            exactOptional,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `boolean distributes through a naked check type parameter and its answer displays as boolean`() {
        val d = diagnose(
            """
            type D<T> = T extends true ? 1 : 2
            const e1: 'z' = {} as D<boolean>
            type E<T> = T extends true ? false : true
            const e2: 'z' = {} as E<boolean>
            type E2<T> = E<T>
            const e3: 'z' = {} as E2<boolean>
            """,
            realLibs,
        )
        assert(
            render(d) == listOf(
                "2:7 TS2322 Type '1 | 2' is not assignable to type '\"z\"'.",
                "Type '1' is not assignable to type '\"z\"'.",
                "4:7 TS2322 Type 'boolean' is not assignable to type '\"z\"'.",
                "6:7 TS2322 Type 'boolean' is not assignable to type '\"z\"'.",
            )
        )
    }

    @Test
    fun `boolean relates to a true or false union target and not to true alone`() {
        val d = diagnose(
            """
            declare let b: boolean
            const u1: true | false = b
            const u2: true = b
            """,
            realLibs,
        )
        assert(render(d) == listOf("3:7 TS2322 Type 'boolean' is not assignable to type 'true'."))
    }

    @Test
    fun `keyof a tuple carries its fixed element keys and none past a rest element`() {
        val d = diagnose(
            """
            type K<T> = '0' extends keyof T ? 1 : 2
            const k1: 'z' = {} as K<[1, 2]>
            const k2: 'z' = {} as K<[]>
            const k3: 'z' = {} as K<[...number[], 1]>
            const k4: 'z' = {} as K<number[]>
            """,
            realLibs,
        )
        assert(
            render(d) == listOf(
                "2:7 TS2322 Type '1' is not assignable to type '\"z\"'.",
                "3:7 TS2322 Type '2' is not assignable to type '\"z\"'.",
                "4:7 TS2322 Type '2' is not assignable to type '\"z\"'.",
                "5:7 TS2322 Type '2' is not assignable to type '\"z\"'.",
            )
        )
    }

    @Test
    fun `a conditional alias whose branch resolved displays the branch not the alias`() {
        val d = diagnose(
            """
            type S<T> = T extends string ? { s: T } : { n: T }
            const s1: 'z' = {} as S<number>
            """,
            realLibs,
        )
        assert(render(d) == listOf("2:7 TS2322 Type '{ n: number; }' is not assignable to type '\"z\"'."))
    }

    @Test
    fun `a key remap through an optional key test resolves to the optional keys`() {
        val d = diagnose(
            """
            type O = { a?: string; b: number }
            type IsOptKey<T extends object, K extends keyof T> = K extends keyof T ? T extends Record<K, T[K]> ? false : true : false
            type OK<T extends object> = keyof { [K in keyof T as IsOptKey<T, K> extends false ? never : K]: never }
            const m1: 'z' = {} as OK<O>
            const m2: 'z' = {} as IsOptKey<O, 'a'>
            """,
            exactOptional,
        )
        assert(
            render(d) == listOf(
                "4:7 TS2322 Type '\"a\"' is not assignable to type '\"z\"'.",
                "5:7 TS2322 Type 'true' is not assignable to type '\"z\"'.",
            )
        )
    }

    @Test
    fun `a ThisType marker types this in an object literal method alone in an intersection on either side nested in a union and through an annotation`() {
        val d = diagnose(
            """
            interface Z { _zod: { def: number }; clone(): Z; keyof(): string }
            declare function take(z: Z): void
            declare function c1(p: ThisType<Z>): void
            c1({ m() { take(this) } })
            declare function c2(p: { m?(): void } & ThisType<Z>): void
            c2({ m() { take(this) } })
            declare function c3(p: ThisType<Z> & { m?(): void }): void
            c3({ m() { take(this) } })
            declare function c4(p: ({ m?(): void } & { n?: 1 }) & ThisType<Z>): void
            c4({ m() { take(this) } })
            declare function c5(p: { m?(): void } | (ThisType<Z> & { n?(): void })): void
            c5({ n() { take(this) } })
            const v: { m?(): void } & ThisType<Z> = { m() { take(this) } }
            """,
            realLibs,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a generic call with explicit type arguments reaches the ThisType of a mapped intersection parameter`() {
        val d = diagnose(
            """
            interface Z { _zod: { def: number }; clone(): Z; keyof(): string }
            declare function take(z: Z): void
            type P<T> = { [K in keyof T]?: T[K] } & ThisType<T>
            declare function g1<T>(n: string, p?: P<T>): T
            g1<Z>("x", { keyof() { take(this); return "" } })
            g1<Z>("x", { clone() { return this.clone(1) } })
            """,
            realLibs,
        )
        assert(render(d) == listOf("6:42 TS2554 Expected 0 arguments, but got 1."))
    }

    @Test
    fun `negative control - without a ThisType marker this stays the literal`() {
        val d = diagnose(
            """
            interface Z { _zod: { def: number }; clone(): Z; keyof(): string }
            declare function take(z: Z): void
            declare function d1(p: { m?(): void }): void
            d1({ m() { take(this) } })
            """,
            realLibs,
        )
        assert(d.map { it.code } == listOf(2741))
    }
}
