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
 * (PARITY.1) — the two halves measured against `tools/tsgo-7.0.2/lib/tsc` and pristine
 * `typescript@6.0.3` on scratch projects, then re-measured here.
 *
 * (c) MEANING — a UNION source whose every constituent is primitive-like was refused by
 * `canUseTypeEngine` against an object target, so `const t: { x: number } = u` with
 * `u: "a" | "b"` reported NOTHING where both reference compilers report TS2322. That
 * refusal was a `Type.Union`-shaped hole between two rules the gate already had for a
 * SINGLE primitive source, at declaration, assignment and return positions alike;
 * argument and object-literal-member positions already reported.
 *
 * (b) FORM — tsc's `reportRelationError` generalizes a LITERAL source to its base type
 * unless the target could itself hold a top-level singleton, and `getBaseTypeOfLiteralType`
 * maps over a union where our `getWidenedLiteralType` had no union arm. Same code, same
 * span, same verdict; only the rendered source name differs. The `never` guard is the
 * other half of the same tsc line and fixes a pre-existing single-literal divergence.
 *
 * Every message string below is tsgo 7.0.2's, transcribed from the measurement.
 */
class TypeDisplayParityTest {

    // ---------------------------------------------------------------- (c) MEANING

    @Test
    fun `a literal-union source is rejected by an anonymous object annotation`() {
        val d = diagnose(
            """
            declare const u: "a" | "b";
            const t: { x: number } = u;
            export { t };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string' is not assignable to type '{ x: number; }'.")
    }

    @Test
    fun `a mixed primitive union source is rejected by an anonymous object annotation`() {
        val d = diagnose(
            """
            declare const u: string | number;
            const t: { x: number } = u;
            export { t };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string | number' is not assignable to type '{ x: number; }'.")
    }

    @Test
    fun `a literal-union source is rejected by a named interface annotation`() {
        val d = diagnose(
            """
            interface I { x: number }
            declare const u: "a" | "b";
            const t: I = u;
            export { t };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string' is not assignable to type 'I'.")
    }

    @Test
    fun `a literal-union source is rejected by an array annotation`() {
        val d = diagnose(
            """
            declare const u: "a" | "b";
            const t: number[] = u;
            export { t };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string' is not assignable to type 'number[]'.")
    }

    @Test
    fun `a literal-union source is rejected at an assignment to an object-annotated variable`() {
        val d = diagnose(
            """
            declare const u: "a" | "b";
            let w: { x: number };
            w = u;
            export { w };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string' is not assignable to type '{ x: number; }'.")
    }

    @Test
    fun `a literal-union source is rejected at a return against an object return type`() {
        val d = diagnose(
            """
            declare const u: "a" | "b";
            function r(): { x: number } { return u; }
            export { r };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
    }

    // -------------------------------------------- (c) negative controls: FP-safety

    @Test
    fun `negative control - a union narrowed to its object constituent still relates`() {
        val d = diagnose(
            """
            declare const v: string | { x: number };
            function f() {
              if (typeof v !== "string") {
                const t: { x: number } = v;
                return t;
              }
              return null;
            }
            export { f };
            """
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `negative control - a union narrowed to never on an unreachable path does not report`() {
        val d = diagnose(
            """
            declare const u: "a" | "b";
            function f() {
              if (u !== "a" && u !== "b") {
                const t: { x: number } = u;
                return t;
              }
              return null;
            }
            export { f };
            """
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `negative control - a literal union satisfies an object target through its apparent type`() {
        val d = diagnose(
            """
            declare const u: "a" | "b";
            const t: { length: number } = u;
            export { t };
            """
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `negative control - a literal union is assignable to a wider literal union`() {
        val d = diagnose(
            """
            declare const u: "a" | "b";
            const t: "a" | "b" | "c" = u;
            export { t };
            """
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `negative control - a union carrying an object constituent keeps the round-461 skip`() {
        val d = diagnose(
            """
            declare const v: string | { y: number };
            const t: { x: number } = v;
            export { t };
            """
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `negative control - an assignment whose RHS narrows to its object constituent still relates`() {
        val d = diagnose(
            """
            declare const v: string | { x: number };
            let w: { x: number };
            function f() {
              if (typeof v !== "string") {
                w = v;
              }
            }
            export { w, f };
            """
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `negative control - an assignment whose RHS narrows to never does not report`() {
        val d = diagnose(
            """
            declare const u: "a" | "b";
            let w: { x: number };
            function f() {
              if (u !== "a" && u !== "b") {
                w = u;
              }
            }
            export { w, f };
            """
        )
        assert(d.none { it.code == 2322 })
    }

    // ------------------------------------------------------------------ (b) FORM

    @Test
    fun `a literal-union source collapses to its base primitive against a primitive target`() {
        val d = diagnose(
            """
            declare const u: "a" | "b";
            const t: number = u;
            export { t };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string' is not assignable to type 'number'.")
    }

    @Test
    fun `a numeric literal union collapses to number`() {
        val d = diagnose(
            """
            declare const n: 1 | 2;
            const t: string = n;
            export { t };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'number' is not assignable to type 'string'.")
    }

    @Test
    fun `a mixed literal union collapses per constituent`() {
        val d = diagnose(
            """
            declare const m: "a" | 1;
            const t: number = m;
            export { t };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string | number' is not assignable to type 'number'.")
    }

    @Test
    fun `the collapse test is TOP-LEVEL so a target with a literal MEMBER still collapses`() {
        val d = diagnose(
            """
            declare const u: "a" | "b";
            const t: { x: 1 } = u;
            export { t };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string' is not assignable to type '{ x: 1; }'.")
    }

    @Test
    fun `a literal-union target keeps the source union`() {
        val d = diagnose(
            """
            declare const u: "a" | "b";
            const t: "a" | "c" = u;
            export { t };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type '\"a\" | \"b\"' is not assignable to type '\"a\" | \"c\"'.")
    }

    @Test
    fun `an INTERSECTION target holding a literal keeps the source union`() {
        // tsc's `typeCouldHaveTopLevelSingletonTypes` recurses through UnionOrIntersection
        // where [propTypeContainsLiteral] has no intersection arm, so the target-kind
        // allowlist in [relationErrorSourceDisplayType] is what keeps this one. It is the
        // shape `complicatedIndexedAccessKeyofReliesOnKeyofNeverUpperBound` spells
        // (`'"text" | "email"'` against `T & "text"`); measured against tsgo 7.0.2, which
        // reads `Type '"a" | "b"' is not assignable to type 'Tag & "text"'.`
        val d = diagnose(
            """
            interface Tag { __t: 1 }
            declare const u: "a" | "b";
            const t: Tag & "text" = u;
            export { t };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type '\"a\" | \"b\"' is not assignable to type 'Tag & \"text\"'.")
    }

    @Test
    fun `a never target keeps the source union - the assertNever case`() {
        val d = diagnose(
            """
            declare const u: "a" | "b";
            const t: never = u;
            export { t };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type '\"a\" | \"b\"' is not assignable to type 'never'.")
    }

    @Test
    fun `a never target keeps a SINGLE literal source too`() {
        val d = diagnose(
            """
            declare const one: "a";
            const t: never = one;
            export { t };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type '\"a\"' is not assignable to type 'never'.")
    }

    @Test
    fun `the collapse also applies at an assignment`() {
        val d = diagnose(
            """
            declare const u: "a" | "b";
            let w: number;
            w = u;
            export { w };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string' is not assignable to type 'number'.")
    }

    @Test
    fun `a single literal source still collapses against a primitive target`() {
        val d = diagnose(
            """
            declare const one: "a";
            const t: number = one;
            export { t };
            """
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type 'string' is not assignable to type 'number'.")
    }
}
