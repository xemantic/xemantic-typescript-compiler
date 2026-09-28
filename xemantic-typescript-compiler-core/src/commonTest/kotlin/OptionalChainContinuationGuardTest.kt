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
 * (CHK.173) S-G1c — a reference read inside the NON-NULLISH part of an optional chain
 * guarded by the same reference is not possibly nullish: an element index
 * (`t?.[t['length'] - 1]`), a call argument (`d?.m(d['p'])`), a later chain link, a
 * closure passed into the chain, through `!` and through a parenthesised receiver of a
 * later `?.` link — as tsgo's optional-chain flow condition does. The element-read
 * TS1804x arm reported these (a shipped false positive); B464 already suppressed the
 * closure form but only on the leftmost link and without the reassignment gate, so it
 * MISSED the rows where the root is reassigned after the closure or is a captured `var`.
 *
 * Controls: a closure whose root is reassigned at/after it, a captured `var`, a sibling
 * argument of an unrelated call, the next statement, another root's chain, an assignment
 * evaluated before the read, a function declaration (not a closure), and a paren that
 * ENDS the chain before a plain link (tsgo also reports TS2532 on `(d?.a)` there — a
 * separate gap; those two pins compare the TS1804x rows only).
 *
 * Every expectation is tsgo 7.0.2's over the same source (code, 1-based position, message).
 */
class OptionalChainContinuationGuardTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private val prelude = "declare function use(v: unknown): void;\ndeclare function run(cb: () => void): void;\ntype D = { a: number[]; p: number; i: number; m(x: unknown): void; o: { b(x: unknown): void }; q?: { c(x: unknown): void }; f(): D | undefined } | undefined;\ndeclare function mkD(): D;\n"

    private fun rows(d: List<Diagnostic>): List<String> =
        d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    private fun nullishRows(d: List<Diagnostic>): List<String> =
        rows(d.filter { it.code == 18047 || it.code == 18048 || it.code == 18049 })

    @Test
    fun `an element index inside its own optional element access is narrowed`() {
        val d = diagnose(
            prelude + "export function f(t: number[] | undefined) { return t?.[t['length'] - 1] }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `an argument of an optional call is narrowed`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.m(d['p']) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a closure argument of a later chain link is narrowed for a parameter`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.a.forEach(() => use(d['p'])) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a non-optional continuation link still carries the chain condition`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.o.b(d['p']) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a parenthesised optional chain as the receiver of a later optional link narrows through the paren`() {
        val d = diagnose(
            prelude + "export function f(d: D) { (d?.a)?.push(d['p']) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `an index in a later element link is narrowed`() {
        val d = diagnose(
            prelude + "export function f(d: D) { use(d?.a[d['i']]) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `nested closures inside the chain arguments are narrowed`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.a.forEach(() => [1].forEach(() => use(d['p']))) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a function expression argument is narrowed`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.a.forEach(function () { use(d['p']) }) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a let reassigned only before the closure is narrowed inside it`() {
        val d = diagnose(
            prelude + "export function f() { let d = mkD(); d = mkD(); d?.a.forEach(() => use(d['p'])) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a deeper optional link is narrowed`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.q?.c(d['p']) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a top-level declared const is narrowed`() {
        val d = diagnose(
            prelude + "declare const d: D; d?.m(d['p'])\nexport {}\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a conditional inside the argument is narrowed`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.m(Math.random() ? d['p'] : 0) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `an optional call receiver chain containing the reference narrows it`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.f()?.m(d['p']) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a non-null assertion link passes through`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.a!.push(d['p']) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a null root is narrowed too`() {
        val d = diagnose(
            prelude + "export function f(d: { m(x: unknown): void; p: number } | null) { d?.m(d['p']) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a read before an assignment in the same argument stays narrowed`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.m([d['p'], d = undefined]) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a template span argument is narrowed`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.m(`\${d['p']}`) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a captured property read in a chain closure is narrowed`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.f()?.a.forEach(() => use(d.p)) }\n",
            directives,
        )
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - a closure when the root is reassigned later keeps reporting`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.a.forEach(() => use(d['p'])); d = undefined }\n",
            directives,
        )
        val expected = listOf(
            "5:50 TS18048 'd' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - a sibling argument of a plain call keeps reporting`() {
        val d = diagnose(
            prelude + "declare function two(a: unknown, b: unknown): void;\nexport function f(d: D) { two(d?.a, d['p']) }\n",
            directives,
        )
        val expected = listOf(
            "6:37 TS18048 'd' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - the next statement keeps reporting`() {
        val d = diagnose(
            prelude + "export function f(d: D) { use(d?.a); use(d['p']) }\n",
            directives,
        )
        val expected = listOf(
            "5:42 TS18048 'd' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - a parenthesised chain before a plain link ends the chain`() {
        val d = diagnose(
            prelude + "export function f(d: D) { (d?.a).push(d['p']) }\n",
            directives,
        )
        val expected = listOf(
            "5:39 TS18048 'd' is possibly 'undefined'.",
        )
        assert(nullishRows(d) == expected)
    }

    @Test
    fun `negative control - a let reassigned after the closure keeps reporting`() {
        val d = diagnose(
            prelude + "export function f() { let d = mkD(); d?.a.forEach(() => use(d['p'])); d = mkD() }\n",
            directives,
        )
        val expected = listOf(
            "5:61 TS18048 'd' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - a captured var keeps reporting`() {
        val d = diagnose(
            prelude + "export function f() { var d = mkD(); d?.a.forEach(() => use(d['p'])) }\n",
            directives,
        )
        val expected = listOf(
            "5:61 TS18048 'd' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - a chain on another root keeps reporting`() {
        val d = diagnose(
            prelude + "export function f(d: D, x: D) { x?.m(d['p']) }\n",
            directives,
        )
        val expected = listOf(
            "5:38 TS18048 'd' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - an assignment before the read in the argument keeps reporting`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.m((d = undefined, d['p'])) }\n",
            directives,
        )
        val expected = listOf(
            "5:48 TS18048 'd' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - a function declaration inside the closure keeps reporting`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.a.forEach(() => { function g() { use(d['p']) } g() }) }\n",
            directives,
        )
        val expected = listOf(
            "5:67 TS18048 'd' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - a captured property read when the root is reassigned later keeps reporting`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.a.forEach(() => use(d.p)); d = undefined }\n",
            directives,
        )
        val expected = listOf(
            "5:50 TS18048 'd' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - a captured var property read keeps reporting`() {
        val d = diagnose(
            prelude + "export function f() { var d = mkD(); d?.a.forEach(() => use(d.p)) }\n",
            directives,
        )
        val expected = listOf(
            "5:61 TS18048 'd' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - an assignment argument after the closure keeps reporting`() {
        val d = diagnose(
            prelude + "export function f(d: D) { d?.a.forEach(() => use(d.p), (d = undefined)) }\n",
            directives,
        )
        val expected = listOf(
            "5:50 TS18048 'd' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - a parenthesised chain before a plain link keeps a captured read reporting`() {
        val d = diagnose(
            prelude + "export function f(d: D) { (d?.a).forEach(() => use(d.p)) }\n",
            directives,
        )
        val expected = listOf(
            "5:52 TS18048 'd' is possibly 'undefined'.",
        )
        assert(nullishRows(d) == expected)
    }

    @Test
    fun `negative control - a closure passed to an unrelated call keeps reporting`() {
        val d = diagnose(
            prelude + "export function f(d: D) { run(() => use(d.p)); use(d?.a) }\n",
            directives,
        )
        val expected = listOf(
            "5:41 TS18048 'd' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }
}
