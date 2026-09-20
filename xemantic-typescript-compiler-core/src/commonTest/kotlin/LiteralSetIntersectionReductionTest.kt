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
 * (LEGACY.0b) — tsc's `getIntersectionType` reduction over the LITERAL-SET family, and
 * the value-keyed literal dedupe `getUnionType` needs for its output to be a set.
 *
 * Every expectation below is what `tools/tsgo-7.0.2/lib/tsc` prints for the same
 * fixture, measured BEFORE the code was written. A `never` target is used throughout:
 * tsc suppresses its literal generalization there ((CHK.83)), so the message names the
 * type as it is rather than as `string`.
 *
 * **WHAT WAS BROKEN, and why no gate here saw it.** A mapped type whose key source is an
 * intersection produced NO TYPE AT ALL — `getTypeFromMappedType` enumerates a
 * `Type.StringLiteral` or a union of them and `else`-bails to `anyType` — so
 * `{ [K in keyof T & keyof C]: T[K] }`, the reverse-mapped idiom, was a silent `any` and
 * every check under it went quiet. `any` is legal everywhere, so the corpus, the
 * 8-profile grid and `cost_gate.py` are all green either way; the defect is only visible
 * against a reference. The displays repaired on the way (`"alpha" | "zoo" & "alpha" |
 * "beta"` for what tsgo prints as `"alpha"`) are a consequence, not the point.
 *
 * **THE SECOND, INDEPENDENT DEFECT** is that literal types are not interned in this
 * model (~25 `Type.StringLiteral(...)` construction sites, none of them through a
 * factory), so `getUnionType`'s id-keyed dedupe kept BOTH instances and
 * `keyof Zed | keyof Wye` rendered `"alpha" | "alpha" | "beta" | "zoo"`. It is fixed by
 * keying the dedupe on the literal's VALUE, which leaves every literal type's own
 * identity — and therefore every relation cache and id-pair key — untouched.
 *
 * **THE NEGATIVE CONTROLS ARE THE HALF THAT MAKES THIS FALSIFIABLE.** Round 777 refused
 * distributing `X & (A | B)` at construction because it changes every intersection's
 * identity, display and relation behaviour, and built `distributedNarrowingType` as an
 * on-demand view whose applicability test requires every operand to be OBJECT-capable.
 * The reduction here is that test's complement, and the `& Zed` row below is what
 * proves it: an object operand still yields an un-distributed intersection. Without
 * that row a pin set holding only the reduced shapes cannot tell this rule from one
 * that distributed everything.
 *
 * **RESIDUES, measured against tsgo and deliberately NOT bought here** — each named so a
 * later round meets a recorded decision rather than a surprise:
 *  * an ENUM member is excluded from the family (a `Type.Object` flagged `EnumLiteral`,
 *    (REL.2)'s measured leniency arc), so `E.A & "x"` and `E.A & E.B` stay intersections
 *    where tsgo reduces both to `never`;
 *  * `keyof T` over a FREE type parameter degrades to `string` here where tsgo keeps
 *    an index type, so a generic DECLARATION renders the reduced key set instead of the
 *    mapped-type node — pinned below as a residue, with the measurement that the row it
 *    replaced was `any`;
 *  * an intersection printing a UNION member needs parentheses — tsgo prints
 *    `("a" | "b") & Zed` where this renders `"a" | "b" & Zed`. That is (CHK.130)'s rule
 *    one container over and is a DISPLAY family of its own;
 *  * `(true | false) & boolean` reduces to a two-member union that DISPLAYS as
 *    `boolean` (tsc's own rule) but elaborates per constituent, so its chain carries one
 *    sub-line tsgo does not print. Only the head is pinned.
 */
class LiteralSetIntersectionReductionTest {

    private val types = """
        interface Zed { zoo: number; alpha: string; }
        interface Wye { alpha: string; beta: boolean; }
        interface Dis { gamma: number; }
    """.trimIndent() + "\n"

    private fun row(source: String): String? =
        diagnose(types + source).firstOrNull { it.code == 2322 }?.message

    private fun rows(source: String): List<String> =
        diagnose(types + source).filter { it.code == 2322 }.map { it.message }

    // ---------------------------------------------------------------- reduction

    @Test
    fun `two literal-union operands reduce to their common member`() {
        assert(
            row("declare const a: (\"zoo\" | \"alpha\") & (\"alpha\" | \"beta\");\nconst p: never = a;") ==
                "Type '\"alpha\"' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `two keyof operands reduce to the common key`() {
        assert(
            row("declare const a: keyof Zed & keyof Wye;\nconst p: never = a;") ==
                "Type '\"alpha\"' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `a literal absorbs the primitive of its own domain`() {
        assert(
            row("declare const a: \"a\" & string;\nconst p: never = a;") ==
                "Type '\"a\"' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `a literal union absorbs the primitive of its own domain`() {
        assert(
            row("declare const a: (\"a\" | \"b\") & string;\nconst p: never = a;") ==
                "Type '\"a\" | \"b\"' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `keyof intersected with string is the key union`() {
        assert(
            row("declare const a: keyof Zed & string;\nconst p: never = a;") ==
                "Type '\"alpha\" | \"zoo\"' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `number literal unions reduce to their common member`() {
        assert(
            row("declare const a: (1 | 2) & (2 | 3);\nconst p: never = a;") ==
                "Type '2' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `a string literal and a number literal of the same text are different domains`() {
        // The one row that separates a VALUE-keyed reduction from a TEXT-keyed one:
        // `"2"` matches `"2"` and must NOT match `2`.
        assert(
            row("declare const a: (\"1\" | \"2\") & (\"2\" | 2);\nconst p: never = a;") ==
                "Type '\"2\"' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `a literal and a primitive of another domain reduce to never`() {
        // The row that discriminates the literal-vs-primitive DOMAIN check: without it
        // `"a" & number` answers `"a"` instead of the empty intersection tsgo reports
        // nothing for. Written as a whole-list assertion with its own positive control,
        // because the correct answer here is an ABSENCE.
        assert(
            rows(
                """
                declare const a: "a" & number;
                const p: never = a;
                declare const b: "a" & string;
                const q: never = b;
                """.trimIndent(),
            ) == listOf("Type '\"a\"' is not assignable to type 'never'."),
        )
    }

    @Test
    fun `disjoint key sets reduce to never and report nothing`() {
        // An absence, so it carries its own positive control in the same fixture:
        // `never` is assignable to everything, and only the second row may survive.
        assert(
            rows(
                """
                declare const a: keyof Zed & keyof Dis;
                const p: never = a;
                declare const b: keyof Zed;
                const q: never = b;
                """.trimIndent(),
            ) == listOf("Type '\"alpha\" | \"zoo\"' is not assignable to type 'never'."),
        )
    }

    // ------------------------------------------------------- the mapped-type consumer

    @Test
    fun `a mapped type over an intersection key source has members instead of being any`() {
        assert(
            row("declare const a: { [K in keyof Zed & keyof Wye]: Zed[K] };\nconst p: never = a;") ==
                "Type '{ alpha: string; }' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `a mapped type's members follow the reduced key union's stable order`() {
        // `Zed` declares `zoo` first; the reduced key union is sorted, so the members
        // come out alphabetically — which is exactly tsgo's answer for this shape and
        // the whole reason `reverseMappedTypeIntersectionConstraint`'s rows read
        // `{ anotherField: "a"; field: 1; }`.
        assert(
            row("declare const a: { [K in keyof Zed & keyof Zed]: Zed[K] };\nconst p: never = a;") ==
                "Type '{ alpha: string; zoo: number; }' is not assignable to type 'never'.",
        )
    }

    // ------------------------------------------------------------- the union dedupe

    @Test
    fun `a union of two keyof operands keeps one member per key`() {
        assert(
            row("declare const a: keyof Zed | keyof Wye;\nconst p: never = a;") ==
                "Type '\"alpha\" | \"beta\" | \"zoo\"' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `the union dedupe is by value and keeps a string beside a same-text number`() {
        assert(
            row("declare const a: \"1\" | 1;\nconst p: never = a;") ==
                "Type '\"1\" | 1' is not assignable to type 'never'.",
        )
    }

    // ---------------------------------------------------------- negative controls

    @Test
    fun `negative control - an object operand keeps the intersection un-distributed`() {
        // Round 777's refusal, still in force: with `Zed` present the reduction declines
        // and the type stays an intersection. A rule that distributed everything would
        // answer a union of `"a" & Zed` / `"b" & Zed` here.
        val message = row("declare const a: (\"a\" | \"b\") & Zed;\nconst p: never = a;")
        assert(message != null)
        assert(message.contains("& Zed"))
    }

    @Test
    fun `negative control - a bare type parameter operand keeps the intersection`() {
        // A `Type.TypeParam` is refused by name in [isPrimitiveDomainOperand], so the
        // reduction declines even though the OTHER operand is a primitive. tsgo reduces
        // this to `T` (a type parameter absorbs the constraint it is already bounded
        // by); we keep `T & string`, which is a pre-existing divergence and is exactly
        // what makes this row a control — the reduction cannot have run.
        val message = row(
            """
            declare function k<T extends string>(s: T & string): T;
            declare const a: typeof k;
            const p: never = a;
            """.trimIndent(),
        )
        assert(message != null)
        assert(message.contains("T & string"))
    }

    @Test
    fun `residue - a generic declaration renders the mapped type's members not its node`() {
        // MEASURED, and it is why the reduction's blast radius had to be read at a
        // generic DECLARATION and not only at a call. tsgo prints the mapped type NODE
        // (`{ [K in keyof T & keyof Zed]: T[K]; }`) because `keyof T` over a free type
        // parameter stays an index type there; this checker degrades `keyof T` to
        // `string` (pre-existing, and visible on its own as `(s: keyof T)` rendering
        // `(s: string)`), so the reduction sees `string & ("alpha" | "zoo")` and answers
        // the key set. The row therefore CHANGED — from `any`, which is what the
        // un-reduced intersection used to bake the mapped type to — and neither the old
        // nor the new text is tsgo's. It is recorded rather than chased because the
        // unblocker is `keyof T`, not this reduction, and because the new answer is the
        // one that makes the parameter checkable at all.
        assert(
            row(
                """
                declare function f<T extends Zed>(s: { [K in keyof T & keyof Zed]: T[K] }): T;
                declare const a: typeof f;
                const p: never = a;
                """.trimIndent(),
            ) ==
                "Type '<T extends Zed>(s: { alpha: string; zoo: number; }) => T' is not assignable to type 'never'.",
        )
    }

    @Test
    fun `residue - an enum member is outside the family and is not reduced`() {
        // tsgo reduces `E.A & "x"` to `never` and reports nothing. This model excludes
        // every `EnumLike` type from the reduction on purpose ((REL.2)'s arc), so the
        // intersection survives. Pinned so the exclusion is a decision, not an accident.
        val message = row("enum E { A = \"x\", B = \"y\" }\ndeclare const a: E.A & \"x\";\nconst p: never = a;")
        assert(message != null)
        assert(message.contains("&"))
    }

    // ------------------------------------------------- the reverse-mapped consumer

    @Test
    fun `a reverse-mapped excess-property row names its members in key order`() {
        // B218's display was built in the OBJECT LITERAL's property order; the members
        // are the mapped type's, so their order is the reduced key set's. Byte-identical
        // to tsgo 7.0.2 for this fixture, member order included.
        assert(
            diagnose(
                """
                interface Zed { zoo: number; alpha: string; }
                declare function take<T extends Zed>(props: { [K in keyof T & keyof Zed]: T[K] }): T;
                take({ zoo: 1, alpha: 'a', extra: 2 });
                """.trimIndent(),
            ).filter { it.code == 2353 }.map { it.message } ==
                listOf(
                    "Object literal may only specify known properties, and 'extra' does not exist in type " +
                        "'{ alpha: \"a\"; zoo: 1; }'.",
                ),
        )
    }

    @Test
    fun `the reverse-mapped walker replaces the general row rather than adding to it`() {
        // The list above is asserted WHOLE for this reason: with the key source reduced,
        // the general excess-property path now fires at the same position too, typed by
        // the CONSTRAINT (`{ zoo: number; alpha: string; }`) because this checker
        // substitutes an un-inferred type parameter with its constraint. Two rows for one
        // excess property is what B218's `removeAll` prevents — so the receipt is the
        // COUNT, which no display assertion can give.
        assert(
            diagnose(
                """
                interface Zed { zoo: number; alpha: string; }
                declare function take<T extends Zed>(props: { [K in keyof T & keyof Zed]: T[K] }): T;
                take({ zoo: 1, alpha: 'a', extra: 2 });
                """.trimIndent(),
            ).count { it.code == 2353 } == 1,
        )
    }

    @Test
    fun `a boolean literal union absorbs boolean`() {
        assert(
            row("declare const a: (true | false) & boolean;\nconst p: never = a;") ==
                "Type 'boolean' is not assignable to type 'never'.",
        )
    }
}
