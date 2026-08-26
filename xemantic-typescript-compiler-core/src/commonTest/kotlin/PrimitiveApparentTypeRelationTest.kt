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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.32): a PRIMITIVE source is related to a STRUCTURAL (anonymous) object target
 * through its WRAPPER INTERFACE — `string` carries `charCodeAt`/`length`/`substring`
 * because `String` declares them.
 *
 * WHAT THE ITEM GOT RIGHT AND WHAT IT DID NOT. The item attributes all 7 of
 * `jsonrepair`'s TS2345 rows to this gap, on the strength of a repro naming its
 * interface `Text`. Measured, that repro is a NAME COLLISION with the DOM `Text`
 * global (rename it and the row vanishes on the UNFIXED binary), and the
 * NAMED-interface half of the item was already working — a round-B69.8 leg 120 lines
 * above this one has handled `target is Type.Interface` all along. The genuine gap is
 * the ANONYMOUS target, and it is real in every direction the item asked about: a
 * hand-written 14-row matrix run against tsgo 7.0.2 had **8** ours-only rows, all of
 * them anonymous targets, across argument, return and annotation position and across
 * `string`/`number`/`boolean`/`symbol`/`bigint`. The lib-global shadowing defect is
 * queued separately as (CHK.48) and is what actually moves `jsonrepair`.
 *
 * HOW VACUITY WAS RULED OUT, PIN BY PIN. Every `none { … }` pin in the first group was
 * run against the PARENT binary rebuilt in this session, through the project CLI over
 * byte-identical source, and every one of them REPORTED — the fixture and the pin are
 * the same text either side. The second group (`have(any { … })`) is green on the
 * parent too, by construction: those are the REFUSAL direction, and their falsifying
 * arms are the ablation arms rather than the parent binary — a1 (drop the enum/vacuity
 * guard) and a2 (drop the index-signature guard) each redden a named subset, recorded
 * in the session note. The third group is labelled CONTROL and is green everywhere; it
 * is not counted as coverage.
 *
 * The embedded lib declares `String`, `Number`, `Boolean` and `Symbol` but NOT
 * `BigInt`, so the `bigint` row of the matrix is not expressible here — its wrapper
 * lookup answers null and the leg is a no-op. It is pinned by the CLI matrix in the
 * session note against real libs, not by a test.
 */
class PrimitiveApparentTypeRelationTest {

    // ---------------------------------------------------------------- positives

    @Test
    fun `a string argument satisfies an anonymous target declaring a String member`() {
        diagnose(
            """
            declare function isWhitespace(text: { charCodeAt(index: number): number }, index: number): boolean
            export function viaString(s: string) { return isWhitespace(s, 0) }
            """
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `the jsonrepair scanner shape - length charAt charCodeAt substring - accepts a string`() {
        diagnose(
            """
            declare function scan(text: {
              length: number
              charAt(index: number): string
              charCodeAt(index: number): number
              substring(start: number, end?: number): string
            }): void
            export function run(s: string) { scan(s) }
            """
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `a number argument satisfies an anonymous target declaring a Number member`() {
        diagnose(
            """
            declare function wantsToFixed(x: { toFixed(d?: number): string }): string
            export function viaNumber(n: number) { return wantsToFixed(n) }
            """
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `a boolean argument satisfies an anonymous target declaring a Boolean member`() {
        diagnose(
            """
            declare function wantsValueOf(x: { valueOf(): boolean }): void
            export function viaBoolean(b: boolean) { wantsValueOf(b) }
            """
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `a symbol argument satisfies an anonymous target declaring a Symbol member`() {
        diagnose(
            """
            declare function wantsToString(x: { toString(): string }): void
            export function viaSymbol(y: symbol) { wantsToString(y) }
            """
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `a string LITERAL argument satisfies the same anonymous target`() {
        diagnose(
            """
            declare function isWhitespace(text: { charCodeAt(index: number): number }): boolean
            export function viaLiteral() { return isWhitespace("abc") }
            """
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `the mirror direction - a primitive RETURNED against an anonymous object return type`() {
        diagnose(
            """
            export function asText(s: string): { charCodeAt(index: number): number } { return s }
            """
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `a primitive initializer against an anonymous object ANNOTATION`() {
        diagnose(
            """
            export const v: { charCodeAt(index: number): number } = "abc"
            """
        ) should { have(none { it.code == 2322 }) }
    }

    // ------------------------------------------------------------- the refusals

    @Test
    fun `a member the String wrapper does NOT declare still refuses the string`() {
        diagnose(
            """
            declare function wants(o: { zzzNotOnString: number }): void
            export function f(s: string) { wants(s) }
            """
        ) should { have(any { it.code == 2345 }) }
    }

    @Test
    fun `a member the Number wrapper does NOT declare still refuses the number`() {
        diagnose(
            """
            declare function wants(o: { zzzNotOnNumber: number }): void
            export function f(n: number) { wants(n) }
            """
        ) should { have(any { it.code == 2345 }) }
    }

    @Test
    fun `a member declared at the WRONG type still refuses the string`() {
        diagnose(
            """
            declare function wants(o: { length: string }): void
            export function f(s: string) { wants(s) }
            """
        ) should { have(any { it.code == 2345 }) }
    }

    @Test
    fun `the mirror direction refuses too - a missing member in RETURN position`() {
        diagnose(
            """
            export function f(s: string): { zzzNotOnString: number } { return s }
            """
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `a STRING-index-signature target still refuses a string - B418 owns that answer`() {
        diagnose(
            """
            declare var y: { [index: string]: any }
            export function f(s: string) { y = s }
            """
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `a NUMBER-index-signature target still refuses a boolean`() {
        diagnose(
            """
            declare var z: { [index: number]: any }
            export function f(b: boolean) { z = b }
            """
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `an index signature beside a member still refuses - the whole target is B418s`() {
        diagnose(
            """
            declare var w: { length: number; [k: string]: any }
            export function f(s: string) { w = s }
            """
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `a MISSING wrapper must answer null and not any - the embedded lib has no BigInt`() {
        // The embedded lib declares String, Number, Boolean and Symbol but NOT BigInt,
        // so `primitiveApparentWrapper` finds nothing here and the leg is a no-op. The
        // reason that matters is the direction of the alternative: had it answered
        // `anyType` the way `getApparentType` does, every bigint source would relate to
        // every object target — a false NEGATIVE, which no gate in this repo can see.
        diagnose(
            """
            declare function wants(o: { zzzNotOnBigInt: number }): void
            export function f(g: bigint) { wants(g) }
            """
        ) should { have(any { it.code == 2345 }) }
    }

    @Test
    fun `an ENUM target is member-less and must not relate vacuously through Number`() {
        diagnose(
            """
            enum E { A = 1, B = 2 }
            declare function wants(e: E.A): void
            export function f() { wants(3 as any as 3) }
            """
        ) should { have(any { it.code == 2345 }) }
    }

    // ---------------------------------------------------------------- CONTROLS
    // Green on the parent binary AND on every ablation arm. They are here to say
    // what the change did NOT touch, and are deliberately not counted as coverage.

    @Test
    fun `CONTROL - an OBJECT source against an anonymous member target was already fine`() {
        diagnose(
            """
            declare function wantsLen(o: { length: number }): number
            export function f(o: { length: number }) { return wantsLen(o) }
            """
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `CONTROL - a NAMED interface target already accepted a string via the B69_8 leg`() {
        diagnose(
            """
            interface HasCharCodeAt { charCodeAt(index: number): number }
            declare function wants(o: HasCharCodeAt): void
            export function f(s: string) { wants(s) }
            """
        ) should { have(none { it.code == 2345 }) }
    }

    @Test
    fun `CONTROL - a primitive against a MISMATCHED wrapper interface still refuses`() {
        diagnose(
            """
            declare function wantsNumberWrapper(o: Number): void
            export function f(s: string) { wantsNumberWrapper(s) }
            """
        ) should { have(any { it.code == 2345 }) }
    }
}
