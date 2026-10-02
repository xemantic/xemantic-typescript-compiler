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
 * (P18.260) a generic class member typed by a FUNCTION SHAPE that mentions the class's type
 * parameter, read through an instantiation. `resolveGenericPropertyTypeWorker` substituted the
 * receiver's type arguments into a FIELD's function-shaped type only; a PARAMETER PROPERTY and
 * a GETTER went through plain `instantiateType`, which no-ops a function shape, and a union or
 * a property bag carrying one was no-opped for all three — so `h.c` on `H<Ab>` kept the raw `T`
 * (a false TS2322 on `h.c = Ab`, a silent `const n: number = h.c`). All three arms now take the
 * method-parameter rule. Second mechanism: a construct-signature-only source reaches `Function`'s
 * apparent members (`prototype` above all), as tsgo's apparent type of any signature-carrying
 * object does, so `abstract new () => Ab` relates to `typeof Ab`. Every expectation is tsgo
 * 7.0.2's output over the same source (cells under `build/bench/p18260-agent`).
 */
class GenericMemberInstantiationTest {

    private val es2022 = "// @strict: true\n// @target: es2022\n// @module: esnext"

    private val prelude = "abstract class Ab { x = 1 }\nclass Co { x = 1 }\nclass Ot { y = 1 }\n"

    private fun rows(line4: String): List<String> =
        diagnose(prelude + line4, es2022).flatMap { d ->
            listOf("${d.line}:${d.character} TS${d.code} ${d.message}") + d.messageChain.map { "  | ${it.trim()}" }
        }

    private fun heads(line4: String): List<String> =
        diagnose(prelude + line4, es2022).map { d -> "${d.line}:${d.character} TS${d.code} ${d.message}" }

    /** cell a01 (the queue's s4): legal in tsgo. */
    @Test
    fun `an abstract constructor parameter property read through an instantiation accepts the class`() {
        assert(rows("class H<T> { constructor(public c: abstract new () => T) {} } declare const h: H<Ab>; h.c = Ab;").isEmpty())
    }

    /** cell b01. */
    @Test
    fun `a constructor-typed parameter property accepts the class`() {
        assert(rows("class H<T> { constructor(public c: new () => T) {} } declare const h: H<Co>; h.c = Co;").isEmpty())
    }

    /** cell b02. */
    @Test
    fun `a function-typed parameter property accepts a conforming arrow`() {
        assert(rows("class H<T> { constructor(public c: () => T) {} } declare const h: H<Co>; h.c = () => new Co();").isEmpty())
    }

    /** cell b07: silent before (the raw `T` read). */
    @Test
    fun `a function-typed parameter property reads its instantiated type`() {
        assert(rows("class H<T> { constructor(public c: () => T) {} } declare const h: H<Co>; const n: number = h.c;") == listOf(
            "4:80 TS2322 Type '() => Co' is not assignable to type 'number'.",
        ))
    }

    /** cell c04. */
    @Test
    fun `a function-typed getter reads its instantiated type`() {
        assert(rows("class H<T> { get c(): () => T { return null! } } declare const h: H<Co>; const n: number = h.c;") == listOf(
            "4:80 TS2322 Type '() => Co' is not assignable to type 'number'.",
        ))
    }

    /** cell c02: a union carrying a function shape, on a FIELD. */
    @Test
    fun `a field typed by a union carrying a function shape reads its instantiated type`() {
        assert(rows("class H<T> { c: (() => T) | null = null } declare const h: H<Co>; const n: number = h.c;") == listOf(
            "4:73 TS2322 Type '(() => Co) | null' is not assignable to type 'number'.",
            "  | Type 'null' is not assignable to type 'number'.",
        ))
    }

    /** cell c03: an optional parameter property. */
    @Test
    fun `an optional function-typed parameter property reads its instantiated type`() {
        assert(rows("class H<T> { constructor(public c?: () => T) {} } declare const h: H<Co>; const n: number = h.c;") == listOf(
            "4:81 TS2322 Type '(() => Co) | undefined' is not assignable to type 'number'.",
            "  | Type 'undefined' is not assignable to type 'number'.",
        ))
    }

    /** cell c06: a property bag carrying a function shape. */
    @Test
    fun `a field typed by a property bag carrying a function shape reads its instantiated type`() {
        assert(rows("class H<T> { c!: { f: () => T } } declare const h: H<Co>; const n: number = h.c;") == listOf(
            "4:65 TS2322 Type '{ f: () => Co; }' is not assignable to type 'number'.",
        ))
    }

    /** cell c12: inherited through a specialized base. */
    @Test
    fun `an inherited function-typed parameter property reads the base's type argument`() {
        assert(rows("class B<T> { constructor(public c: () => T) {} } class D extends B<Co> {} declare const d: D; const n: number = d.c;") == listOf(
            "4:101 TS2322 Type '() => Co' is not assignable to type 'number'.",
        ))
    }

    /** cell c10: the contextual parameter of the assigned arrow is the instantiated `Co`. */
    @Test
    fun `an arrow assigned to a parameter property is contextually typed by the instantiation`() {
        assert(rows("class H<T> { constructor(public c: (a: T) => void) {} } declare const h: H<Co>; h.c = (a) => { const n: number = a; };") == listOf(
            "4:102 TS2322 Type 'Co' is not assignable to type 'number'.",
        ))
    }

    /** cell b10: a true positive keeps firing, now naming the instantiated target. (tsgo adds
     *  the chain line `Property 'x' is missing in type 'Ot' but required in type 'Co'.` — residue.) */
    @Test
    fun `a wrong class assigned to an abstract constructor parameter property is reported`() {
        assert(heads("class H<T> { constructor(public c: abstract new () => T) {} } declare const h: H<Co>; h.c = Ot;") == listOf(
            "4:87 TS2322 Type 'typeof Ot' is not assignable to type 'abstract new () => Co'.",
        ))
    }

    /** cell t03: abstract into non-abstract is still refused (tsgo's chain line is residue). */
    @Test
    fun `an abstract constructor member read into a non-abstract target is reported`() {
        assert(heads("class H<T> { c!: abstract new () => T } declare const h: H<Ab>; const x: new () => Ab = h.c;") == listOf(
            "4:71 TS2322 Type 'abstract new () => Ab' is not assignable to type 'new () => Ab'.",
        ))
    }

    /** cells a06 / b11: `Function`'s `prototype` is on the apparent type of a constructor type. */
    @Test
    fun `an abstract constructor type relates to the class constructor type`() {
        assert(rows("class H<T> { c!: abstract new () => T } declare const h: H<Ab>; const x: typeof Ab = h.c;").isEmpty())
        assert(rows("declare const c: abstract new () => Ab; const x: typeof Ab = c;").isEmpty())
    }

    /** cell d01. */
    @Test
    fun `a construct signature type relates to a class constructor type with no statics`() {
        assert(rows("declare const c: new () => Co; const x: typeof Co = c;").isEmpty())
    }

    /** cell d03: a missing STATIC still refuses (tsgo heads it TS2741 `Property 's' is missing in
     *  type 'new () => St' but required in type 'typeof St'.`; our head is TS2322 — residue). */
    @Test
    fun `a construct signature type lacking a required static is still refused`() {
        val d = diagnose("class St { static s = 1; x = 1 }\ndeclare const c: new () => St; const x: typeof St = c;", es2022)
        assert(d.size == 1 && d[0].line == 2 && d[0].code in setOf(2322, 2741))
    }

    /** cell t06: a class constructor type carries its OWN `prototype`, which is still compared. */
    @Test
    fun `a wrong class constructor type assigned to a class-constructor-typed member is reported`() {
        assert(heads("class H<T> { c!: T } declare const h: H<typeof Co>; h.c = Ot;") == listOf(
            "4:53 TS2322 Type 'typeof Ot' is not assignable to type 'typeof Co'.",
        ))
    }

    /** cell e01: tsgo never compares the class constructor type's own binder-made `prototype`
     *  (a pre-existing false positive, third mechanism). */
    @Test
    fun `the target class constructor type's prototype is not compared`() {
        assert(rows("declare const c: { new (): Co; prototype: Ot }; const x: typeof Co = c;").isEmpty())
    }

    /** cell e03: before, `prototype` read as MISSING (a false TS2741). */
    @Test
    fun `a construct signature type with its own unrelated member relates to a class constructor type`() {
        assert(rows("declare const c: { new (): Co; length: string }; const x: typeof Co = c;").isEmpty())
    }

    /** cell e04: a construct-signature source's OWN member is still compared, not taken from `Function`. */
    @Test
    fun `a construct signature source's own call member is still compared`() {
        assert(rows("declare const c: { new (): Co; call: number }; const x: { new (): Co; call(): void } = c;") == listOf(
            "4:54 TS2322 Type '{ new (): Co; call: number; }' is not assignable to type '{ new (): Co; call(): void; }'.",
            "  | Types of property 'call' are incompatible.",
            "  | Type 'number' is not assignable to type '() => void'.",
        ))
    }

    /** cell e05: an ordinary `prototype` member of an anonymous target is still compared. */
    @Test
    fun `an anonymous target's prototype member is still compared`() {
        assert(rows("declare const c: { new (): Co; prototype: number }; const x: { new (): Co; prototype: string } = c;") == listOf(
            "4:59 TS2322 Type '{ new (): Co; prototype: number; }' is not assignable to type '{ new (): Co; prototype: string; }'.",
            "  | Types of property 'prototype' are incompatible.",
            "  | Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** negative control, cell c01: the optional FIELD always worked. */
    @Test
    fun `negative control - an optional function-typed field read`() {
        assert(rows("class H<T> { c?: () => T } declare const h: H<Co>; const n: number = h.c;") == listOf(
            "4:58 TS2322 Type '(() => Co) | undefined' is not assignable to type 'number'.",
            "  | Type 'undefined' is not assignable to type 'number'.",
        ))
    }
}
