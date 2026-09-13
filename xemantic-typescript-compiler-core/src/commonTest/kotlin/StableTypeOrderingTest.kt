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
 * (LEGACY.0a) — tsc's `stableTypeOrdering`, the union constituent order TypeScript 7 /
 * tsgo 7.0.2 renders and the re-pinned corpus (`typeScriptCommit` = tsgo's
 * `_submodules/TypeScript`) pins in 22 of its baselines.
 *
 * Every expectation below is what `tools/tsgo-7.0.2/lib/tsc` prints for the same
 * fixture, byte for byte; pristine `typescript@6.0.3` under `--stableTypeOrdering`
 * prints the same on every row (measured, 21 of 21 rows agreeing). The mechanism is
 * `StableTypeOrdering.comparator` inside `Checker.getUnionType` — the INTERNING order,
 * which is why `typeToString` needs no sort of its own — plus tsc's `formatUnionTypes`
 * on top (`null`, `undefined` last; `false | true` prints `boolean`; a whole enum's
 * member run prints the enum).
 *
 * A `never` target is used throughout: tsc suppresses its literal generalization there,
 * so the message names the union as it is.
 */
class StableTypeOrderingTest {

    private fun row(source: String): String? =
        diagnose(source).firstOrNull { it.code == 2322 }?.message

    @Test
    fun `primitives order by tsc's current TypeFlags with void before string and object last`() {
        assert(
            row("declare const u: number | string | boolean | bigint | symbol | undefined | null | void | object;\nconst p: never = u;") ==
                "Type 'void | string | number | bigint | symbol | boolean | object | null | undefined' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `string literals order by value before number literals by value`() {
        assert(
            row("declare const u: \"foo\" | \"bar\" | \"Baz\" | 2 | 1 | \"a1\" | \"a10\" | 10;\nconst p: never = u;") ==
                "Type '\"Baz\" | \"a1\" | \"a10\" | \"bar\" | \"foo\" | 1 | 2 | 10' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `enum members order after literals and a whole enum's run prints the enum`() {
        assert(
            row("enum E { B, A, C }\nenum S { Y = \"y\", X = \"x\" }\ndeclare const u: E.C | E.A | E.B | S.X | S.Y | \"z\" | 3;\nconst p: never = u;") ==
                "Type '\"z\" | 3 | E | S' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `named object types order by name with anonymous shapes by position and a tuple last`() {
        assert(
            row(
                """
                interface Zeta { z: number }
                interface Alpha { a: number }
                class Mid { m = 1 }
                declare const u: Zeta | Alpha | Mid | { q: number } | Zeta[] | Alpha[] | [Zeta, Alpha] | (() => void);
                const p: never = u;
                """,
            ) ==
                "Type 'Alpha | Alpha[] | Zeta[] | Mid | Zeta | { q: number; } | (() => void) | [Zeta, Alpha]' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `null and undefined render last and void does not`() {
        assert(
            row("interface Zeta { z: number }\ninterface Alpha { a: number }\ndeclare const u: Zeta | undefined | null | Alpha;\nconst p: never = u;") ==
                "Type 'Alpha | Zeta | null | undefined' is not assignable to type 'never'.",
        )
        assert(
            row("interface Zeta { z: number }\ndeclare const u: Zeta | void;\nconst p: never = u;") ==
                "Type 'void | Zeta' is not assignable to type 'never'.",
        )
        assert(
            row("interface Zeta { z: number }\ndeclare const u: string | void | undefined | Zeta;\nconst p: never = u;") ==
                "Type 'void | string | Zeta | undefined' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `intersections order after objects by their member lists and print parenthesized`() {
        assert(
            row(
                """
                interface Zeta { z: number }
                interface Alpha { a: number }
                class Mid { m = 1 }
                declare const u: (Zeta & Alpha) | (Alpha & Mid) | Zeta;
                const p: never = u;
                """,
            ) ==
                "Type 'Zeta | (Alpha & Mid) | (Zeta & Alpha)' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `a generic alias orders by its alias arguments and a nested union flattens into the order`() {
        assert(
            row(
                """
                interface Zeta { z: number }
                interface Alpha { a: number }
                class Mid { m = 1 }
                type Gen<T> = { g: T };
                declare const u: Mid | Gen<string> | Gen<number> | Gen<Zeta>;
                const p: never = u;
                """,
            ) ==
                "Type 'Gen<string> | Gen<number> | Gen<Zeta> | Mid' is not assignable to type 'never'.",
        )
        assert(
            row(
                """
                interface Zeta { z: number }
                interface Alpha { a: number }
                class Mid { m = 1 }
                declare const u: (Zeta | Alpha) | (Mid | "x") | number;
                const p: never = u;
                """,
            ) ==
                "Type 'number | \"x\" | Alpha | Mid | Zeta' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `an object literal type keeps its declared property order`() {
        assert(
            row("declare const o: { zz: number; aa: string; mm: boolean };\nconst p: never = o;") ==
                "Type '{ zz: number; aa: string; mm: boolean; }' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `boolean absorbs a true literal and false with true prints boolean`() {
        assert(
            row("enum E { B, A, C }\ndeclare const u: boolean | \"a\" | E.A | 5n | true;\nconst p: never = u;") ==
                "Type '\"a\" | 5n | boolean | E.A' is not assignable to type 'never'.",
        )
        assert(
            row("declare const u: symbol | true | false | 1n;\nconst p: never = u;") ==
                "Type 'symbol | 1n | boolean' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `type parameters order by name`() {
        val d = diagnose("function tp<T, U, Top>(x: T | Top | U, y: U | T) { const p: never = x; const q: never = y; }")
            .filter { it.code == 2322 }.map { it.message }
        assert(
            d == listOf(
                "Type 'T | Top | U' is not assignable to type 'never'.",
                "Type 'T | U' is not assignable to type 'never'.",
            ),
        )
    }

    @Test
    fun `a literal union target renders sorted and the suggestion breaks ties in that order`() {
        val d = diagnose("const d1: \"hddvd\" | \"bluray\" = \"hdpvd\";").filter { it.code == 2820 }.map { it.message }
        assert(d == listOf("Type '\"hdpvd\"' is not assignable to type '\"bluray\" | \"hddvd\"'. Did you mean '\"hddvd\"'?"))
    }

    @Test
    fun `a whole enum orders as a union after objects and a lone literal before both`() {
        assert(
            row("enum E { B, A, C }\ninterface Zeta { z: number }\ndeclare const u: Zeta | E | \"lit\";\nconst p: never = u;") ==
                "Type '\"lit\" | Zeta | E' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `two anonymous object types order by the position they were declared at`() {
        assert(
            row("interface Zeta { z: number }\ndeclare const u: { b: 1 } | { a: 2 } | Zeta;\nconst p: never = u;") ==
                "Type 'Zeta | { b: 1; } | { a: 2; }' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `tuples order by shape - readonly last - then by element lists`() {
        assert(
            row(
                """
                interface Zeta { z: number }
                interface Alpha { a: number }
                declare const u: (readonly [Zeta]) | [Zeta] | [Alpha, Zeta] | [Zeta?] | Zeta[];
                const p: never = u;
                """,
            ) ==
                "Type 'Zeta[] | [Zeta] | [(Zeta | undefined)?] | [Alpha, Zeta] | readonly [Zeta]' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `a union source chain names its first failing constituent in the stable order`() {
        val d = diagnose("var x: boolean = (true ? 1 : \"\");").filter { it.code == 2322 }
        assert(d.size == 1)
        assert(d[0].message == "Type 'string | number' is not assignable to type 'boolean'.")
        assert(d[0].messageChain == listOf("  Type 'string' is not assignable to type 'boolean'."))
    }

    @Test
    fun `a mapped type over an array's keys lists its properties by declaration and not by key order`() {
        // The mapped type's KEY union is sorted by value, its PROPERTIES are not: tsc's
        // `getNamedMembers` orders a homomorphic member by its source declaration.
        val d = diagnose(
            """
            interface Src { zz: number; aa: string }
            type M = { [K in keyof Src]: boolean };
            declare const m: M;
            const p: never = m;
            """,
        ).filter { it.code == 2322 }.map { it.message }
        assert(d == listOf("Type 'M' is not assignable to type 'never'."))
        val e = diagnose(
            """
            interface Src { zz: number; aa: string }
            declare const m: { [K in keyof Src]: boolean };
            const p: never = m;
            """,
        ).filter { it.code == 2322 }.map { it.message }
        assert(e == listOf("Type '{ zz: boolean; aa: boolean; }' is not assignable to type 'never'."))
    }
}
