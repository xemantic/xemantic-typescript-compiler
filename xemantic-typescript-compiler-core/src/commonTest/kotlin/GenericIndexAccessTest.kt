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
 * (CHK.220) TS2536 on an ELEMENT ACCESS whose index is a type parameter (tsgo
 * `checkIndexedAccessIndexType`): decided only where neither a deferred indexed-access type nor
 * a generic `keyof` is needed — an UNCONSTRAINED index, or a syntactically concrete constraint
 * against an unconstrained type-parameter receiver or a type-literal receiver. Everything else is
 * silent, and the controls pin the silence on the shapes tsgo accepts (`K extends keyof T`, a
 * `Record<K, …>` receiver, an `any` receiver, an array with a numeric key, `Extract<keyof T, …>`).
 *
 * Every expectation is tsgo 7.0.2's output (`build/bench/p18276-agent/m220`), 1-based columns.
 */
class GenericIndexAccessTest {

    private fun rows(source: String): List<String> =
        diagnose(source, directives = "// @strict: true\n// @target: es2022")
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    @Test
    fun `an unconstrained index type parameter cannot index anything but any`() {
        val r = rows(
            """
            export function c01<T, K>(t: T, k: K) { return t[k]; }
            export function c16<T, K>(t: T, k: K, v: any) { t[k] = v; }
            export function c39<K>(t: string[], k: K) { return t[k]; }
            export class C43<K> { m(t: { a: 1 }, k: K) { return t[k]; } }
            export function c46<K>(t: { a: 1 }, k: K) { return t?.[k]; }
            export const c49 = <K,>(t: { a: 1 }, k: K) => t[k];
            export function c34<K>(t: any, k: K) { return t[k]; }
            """
        )
        assert(
            r == listOf(
                "1:48 TS2536 Type 'K' cannot be used to index type 'T'.",
                "2:49 TS2536 Type 'K' cannot be used to index type 'T'.",
                "3:52 TS2536 Type 'K' cannot be used to index type 'string[]'.",
                "4:53 TS2536 Type 'K' cannot be used to index type '{ a: 1; }'.",
                "5:52 TS2536 Type 'K' cannot be used to index type '{ a: 1; }'.",
                "6:47 TS2536 Type 'K' cannot be used to index type '{ a: 1; }'.",
            )
        )
    }

    @Test
    fun `a concrete constraint is checked against an unconstrained receiver and a type literal`() {
        val r = rows(
            """
            export function c03<T, K extends string>(t: T, k: K) { return t[k]; }
            export function c41<K extends "a" | "b", T>(t: T, k: K) { return t[k]; }
            export function c08<K extends "a" | "c">(t: { a: 1; b: 2 }, k: K) { return t[k]; }
            export function c40<K extends "a">(t: { a: 1 } | { b: 2 }, k: K) { return t[k]; }
            export function c48<K extends string>(t: { a: 1; [x: number]: 2 }, k: K) { return t[k]; }
            export function c50<K extends number>(t: { a: 1 }, k: K) { return t[k]; }
            """
        )
        assert(
            r == listOf(
                "1:63 TS2536 Type 'K' cannot be used to index type 'T'.",
                "2:66 TS2536 Type 'K' cannot be used to index type 'T'.",
                "3:76 TS2536 Type 'K' cannot be used to index type '{ a: 1; b: 2; }'.",
                "4:75 TS2536 Type 'K' cannot be used to index type '{ a: 1; } | { b: 2; }'.",
                "5:83 TS2536 Type 'K' cannot be used to index type '{ [x: number]: 2; a: 1; }'.",
                "6:67 TS2536 Type 'K' cannot be used to index type '{ a: 1; }'.",
            )
        )
    }

    @Test
    fun `negative control - every index tsgo accepts stays silent`() {
        val r = rows(
            """
            export function c02<T, K extends keyof T>(t: T, k: K) { return t[k]; }
            export function c04<T extends Record<string, number>, K extends string>(t: T, k: K) { return t[k]; }
            export function c05<T extends { [k: string]: number }, K extends string>(t: T, k: K) { return t[k]; }
            export function c06<K extends string>(t: Record<K, number>, k: K) { return t[k]; }
            export function c07<K extends "a" | "b">(t: { a: 1; b: 2 }, k: K) { return t[k]; }
            export function c09<K extends number>(t: string[], k: K) { return t[k]; }
            export function c10<K extends number>(t: [1, 2], k: K) { return t[k]; }
            export function c14<T, K extends keyof T, J extends keyof T[K]>(t: T, k: K, j: J) { return t[k][j]; }
            export function c15<T, K extends keyof T>(t: T, k: K, v: T[K]) { t[k] = v; }
            export function c20<K extends string | number>(t: { [x: string]: number }, k: K) { return t[k]; }
            export function c23<T, K extends Extract<keyof T, string>>(t: T, k: K) { return t[k]; }
            export function c25<T, K extends keyof T>(t: Partial<T>, k: K) { return t[k]; }
            export function c33<K extends string>(t: any, k: K) { return t[k]; }
            export function c44<K extends 0>(t: { 0: string }, k: K) { return t[k]; }
            export function c47<K extends string>(t: { [x: string]: 1 }, k: K) { return t[k]; }
            """
        )
        assert(r.isEmpty())
    }
}
