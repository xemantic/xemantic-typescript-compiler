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
 * (CHK.11) round 942 — DISCRIMINATED-UNION NARROWING THROUGH AN ELEMENT ACCESS.
 *
 * `switch (s["kind"])` narrowed nothing while `switch (s.kind)` narrowed correctly, and
 * an element access typed its receiver un-narrowed however the guard was written. Three
 * mechanisms, all keyed on the same fact — tsc's `isMatchingReference` compares
 * references by SYMBOL, so `x.a` and `x["a"]` are ONE reference, while ours compares the
 * path STRINGS [getReferencePath] builds:
 *
 *  1. `singleLevelDiscriminantSegment` — the switch's discriminant reader accepts a
 *     bracket segment beside a dotted one.
 *  2. `getTypeOfElementAccess` flow-narrows its UNION RECEIVER, the gate
 *     `computeRawTypeOfPropertyAccess` has carried since B1.1. (Its 17.34d twin — narrowing
 *     the access's own union RESULT — was written, measured INERT against all 21 pins and
 *     REMOVED; see the KDoc at that site.)
 *  3. `getReferencePath` normalises an identifier-spellable string index to the dotted
 *     segment, so a guard written one way and a read written the other match.
 *
 * Measured on pristine `typeGuardNarrowsIndexedAccessOfKnownProperty1`: 11 ours-only rows
 * (TS2339 / TS2322 / TS2366) against a baseline where pristine is SILENT — 11 -> 0.
 *
 * Every positive pin reads the narrowed type OUT of a diagnostic by assigning it to an
 * incompatible PRIMITIVE (round 762) or by naming the narrowed type in a TS2339 message:
 * a `none { code == 2339 }` alone cannot tell a correct narrow from a subject washed to
 * `any` or to `never`.
 */
class ElementAccessDiscriminantNarrowingTest {

    private val shapes = """
        interface Sq { kind: "square"; size: number }
        interface Rc { kind: "rect"; w: number }
        type Shape = Sq | Rc;
    """.trimIndent() + "\n"

    @Test
    fun `a bracket-spelled switch discriminant narrows the receiver`() {
        diagnose(
            shapes + """
                function f(s: Shape) {
                  switch (s["kind"]) {
                    case "square": { const n: string = s.size; break }
                  }
                }
            """
        ) should {
            have(any { it.code == 2322 && "'number'" in it.message && "'string'" in it.message })
        }
    }

    @Test
    fun `a bracket-spelled switch discriminant narrows AWAY from the other member`() {
        diagnose(
            shapes + """
                function f(s: Shape) {
                  switch (s["kind"]) {
                    case "square": { s.w; break }
                  }
                }
            """
        ) should {
            have(any { it.code == 2339 && "'Sq'" in it.message })
        }
    }

    @Test
    fun `an element-access read types from the narrowed receiver`() {
        diagnose(
            shapes + """
                function f(s: Shape) {
                  switch (s["kind"]) {
                    case "square": { const n: string = s["size"]; break }
                  }
                }
            """
        ) should {
            have(any { it.code == 2322 && "'number'" in it.message && "'string'" in it.message })
        }
    }

    @Test
    fun `a dotted guard and a bracket read are one reference`() {
        diagnose(
            shapes + """
                function f(s: Shape) {
                  switch (s.kind) {
                    case "square": { const n: string = s["size"]; break }
                  }
                }
            """
        ) should {
            have(any { it.code == 2322 && "'number'" in it.message && "'string'" in it.message })
        }
    }

    @Test
    fun `a numeric-index discriminant narrows its sibling index`() {
        diagnose(
            """
                interface X { 0: "xx"; 1: number }
                interface Y { 0: "yy"; 1: string }
                function f(z: X | Y) {
                  switch (z[0]) {
                    case "xx": { const n: string = z[1]; break }
                  }
                }
            """
        ) should {
            have(any { it.code == 2322 && "'number'" in it.message && "'string'" in it.message })
        }
    }

    @Test
    fun `a deep chain mixing both spellings narrows`() {
        diagnose(
            shapes + """
                interface Sub { "0": { sub: { under: { shape: Shape } } } }
                function f(s: Sub) {
                  switch (s[0]["sub"].under["shape"]["kind"]) {
                    case "square": { const n: string = s[0].sub.under.shape["size"]; break }
                  }
                }
            """
        ) should {
            have(any { it.code == 2322 && "'number'" in it.message && "'string'" in it.message })
        }
    }

    @Test
    fun `an exhaustive bracket-spelled switch has no implicit return`() {
        diagnose(
            shapes + """
                function f(s: Shape): number {
                  switch (s["kind"]) {
                    case "square": return s.size;
                    case "rect": return s.w;
                  }
                }
            """
        ) should { have(none { it.code == 2366 }) }
    }

    @Test
    fun `an exhaustive switch over a deep mixed-spelling chain has no implicit return`() {
        diagnose(
            shapes + """
                interface Sub { "0": { sub: { under: { shape: Shape } } } }
                function f(s: Sub): number {
                  switch (s[0]["sub"].under["shape"]["kind"]) {
                    case "square": return s[0].sub.under.shape["size"];
                    case "rect": return s[0]["sub"].under.shape.w;
                  }
                }
            """
        ) should { have(none { it.code == 2366 }) }
    }

    @Test
    fun `a bracket-spelled MULTI-segment guard narrows a dotted read of the same reference`() {
        // The reference is `b.inner`, and only [getReferencePath]'s normalisation makes the
        // guard's `b[inner][kind]` and the read's `b.inner` name it as one path.
        diagnose(
            shapes + """
                interface Box { inner: Shape }
                function f(b: Box) {
                  switch (b["inner"]["kind"]) {
                    case "square": { const n: string = b.inner.size; break }
                  }
                }
            """
        ) should {
            have(any { it.code == 2322 && "'number'" in it.message && "'string'" in it.message })
        }
    }

    @Test
    fun `a dotted MULTI-segment guard narrows a bracket-spelled read of the same reference`() {
        diagnose(
            shapes + """
                interface Box { inner: Shape }
                function f(b: Box) {
                  switch (b.inner.kind) {
                    case "square": { const n: string = b["inner"]["size"]; break }
                  }
                }
            """
        ) should {
            have(any { it.code == 2322 && "'number'" in it.message && "'string'" in it.message })
        }
    }

    @Test
    fun `negative control - a dotted discriminant still narrows`() {
        diagnose(
            shapes + """
                function f(s: Shape) {
                  switch (s.kind) {
                    case "square": { const n: string = s.size; break }
                  }
                }
            """
        ) should {
            have(any { it.code == 2322 && "'number'" in it.message && "'string'" in it.message })
        }
    }

    @Test
    fun `bound - a dynamically indexed discriminant does not narrow`() {
        diagnose(
            shapes + """
                declare const k: string;
                function f(s: Shape) {
                  switch (s[k]) {
                    case "square": { s.size; break }
                  }
                }
            """
        ) should { have(any { it.code == 2339 }) }
    }
}
