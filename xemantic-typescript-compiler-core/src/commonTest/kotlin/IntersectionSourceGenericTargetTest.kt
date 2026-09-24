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
 * (CHK.162), round P18.197 — an INTERSECTION source against a GENERIC reference target
 * (`VD & { initializer: Call }` against `VDI<Call>`, `interface VDI<T> extends VD { readonly
 * initializer: T }`) reported a false `TS2741: Property 'kind' is missing …` where tsgo 7.0.2 is
 * silent; the union target (`Other | VDI<Call>`) inherited it through its constituent comparison.
 *
 * The mechanism: no single constituent relates on its own, so the verdict falls to
 * `Checker.intersectionMergedSatisfiesTarget` (the acceptance half of tsgo's structural comparison
 * over the intersection's COMBINED properties, `relater.go` `structuredTypeRelatedTo` →
 * `propertiesRelatedTo` over `getPropertiesOfType(intersection)`). It read each member's type with
 * `getTypeOfSymbol`, which for a generic reference's own declaration answers the raw `T` —
 * `errorType` out of scope — so it bailed on `initializer`. It now reads
 * `getPropertyTypeForRelation`, the instantiated member type the structural relation itself
 * compares; `intersectionMergedContradictsTarget` and the intersection elaboration take the same
 * read. The elaboration also stopped naming a member ANOTHER constituent supplies as missing
 * (that was the `'kind'` in the message), and a union constituent now makes the contradiction
 * test undecidable (it hands the verdict to the distribution fallback) instead of a wrong
 * `true`.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW over the identical text (`build/scratch-p18197/cells`,
 * `strict`, `target: es2020`). A row is `line:column code message` with its chain appended as
 * ` / <line>`. Residue, asserted head-only below: tsgo drills an incompatible member one level
 * further (`Property 'c' is missing in type 'Ex' but required in type 'Call'.`) where this
 * intersection elaboration stops at `Type 'Ex' is not assignable to type 'Call'.` — pre-existing,
 * identical for a NON-generic target.
 */
class IntersectionSourceGenericTargetTest {

    private val prelude = """
        export {};
        interface Ex { e: 1 }
        interface Call extends Ex { c: 1 }
        interface VD { kind: 1; name: string; initializer?: Ex }
        interface VDI<T extends Ex> extends VD { readonly initializer: T }
        interface Other { kind: 2; o: 1 }
        type Alias = Other | VDI<Call>;
        declare const v: VD & { initializer: Call };
        declare function pn(n: number): void;
    """.trimIndent()

    private fun diagnostics(line10: String) =
        diagnose(prelude + "\n" + line10, directives = "// @strict: true\n// @target: es2020")

    private fun rows(line10: String): List<String> =
        diagnostics(line10).map { d ->
            "${d.line}:${d.character} ${d.code} ${d.message}" +
                d.messageChain.joinToString("") { " / " + it.trim() }
        }.sorted()

    private fun heads(line10: String): List<String> =
        diagnostics(line10).map { d -> "${d.line}:${d.character} ${d.code} ${d.message}" }.sorted()

    private fun codes(line10: String): List<String> =
        diagnostics(line10).map { d -> "${d.line}:${d.character} ${d.code}" }.sorted()

    @Test
    fun `the reducer is legal at a declaration an assignment an argument and a return`() {
        val decl = rows("const b: VDI<Call> = v;")
        val assign = rows("let b: VDI<Call>; b = v;")
        val arg = rows("declare function take(d: VDI<Call>): void; take(v);")
        val ret = rows("function r(): VDI<Call> { return v; }")
        assert(decl.isEmpty())
        assert(assign.isEmpty())
        assert(arg.isEmpty())
        assert(ret.isEmpty())
    }

    @Test
    fun `a union target holding the generic reference is satisfied through that constituent`() {
        val decl = rows("const a: Alias = v;")
        val arg = rows("declare function cb(d: Alias): void; cb(v);")
        assert(decl.isEmpty())
        assert(arg.isEmpty())
    }

    @Test
    fun `the combined members satisfy the target whatever shape supplies them`() {
        val noExtends = rows("interface VDX<T extends Ex> { kind: 1; name: string; readonly initializer: T } const b: VDX<Call> = v;")
        val interfaceMember = rows("interface HasInit { initializer: Call } declare const w: VD & HasInit; const b: VDI<Call> = w;")
        val genericMember = rows("interface G<T> { initializer: T } declare const w: VD & G<Call>; const b: VDI<Call> = w;")
        val threeWay = rows("declare const w: VD & { initializer: Call } & { extra: 1 }; const b: VDI<Call> = w;")
        val intersectionTarget = rows("const b: VDI<Call> & { name: string } = v;")
        val bothGeneric = rows("interface G<T> { initializer: T } declare const w: VD & G<Call>; const b: VDI<Call> & G<Ex> = w;")
        val optionalTarget = rows("interface OT<X> { a?: X; b: 1 } declare const w: { a: number } & { b: 1 }; const t: OT<number> = w;")
        assert(noExtends.isEmpty())
        assert(interfaceMember.isEmpty())
        assert(genericMember.isEmpty())
        assert(threeWay.isEmpty())
        assert(intersectionTarget.isEmpty())
        assert(bothGeneric.isEmpty())
        assert(optionalTarget.isEmpty())
    }

    @Test
    fun `an intersection with a union member distributes rather than contradicting`() {
        val actual = rows("declare const w: VD & ({ initializer: Call } | { initializer: Call; z: 1 }); const b: VDI<Call> = w;")
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - a member missing from every constituent is still reported`() {
        val decl = rows("interface VDM<T extends Ex> extends VD { readonly initializer: T; extra: T } const b: VDM<Call> = v;")
        val arg = rows("interface VDM<T extends Ex> extends VD { readonly initializer: T; extra: T } declare function take(d: VDM<Call>): void; take(v);")
        val split = rows("interface T3<X> { a: X; b: 1; c: 1 } declare const w: { b: 1 } & { a: Call }; const t: T3<Call> = w;")
        assert(decl == listOf(
            "10:84 2741 Property 'extra' is missing in type 'VD & { initializer: Call; }' but required in type 'VDM<Call>'.",
        ))
        assert(arg == listOf(
            "10:126 2741 Property 'extra' is missing in type 'VD & { initializer: Call; }' but required in type 'VDM<Call>'.",
        ))
        assert(split == listOf(
            "10:85 2741 Property 'c' is missing in type '{ b: 1; } & { a: Call; }' but required in type 'T3<Call>'.",
        ))
    }

    @Test
    fun `negative control - a supplied member of the wrong type is still reported`() {
        val optional = rows("interface OT<X> { a?: X; b: 1 } declare const w: { a: string } & { b: 1 }; const t: OT<number> = w;")
        val generic = heads("declare const w: VD & { initializer: Ex }; const b: VDI<Call> = w;")
        val union = heads("declare const w: VD & { initializer: Ex }; const a: Alias = w;")
        val unionMember = codes("declare const w: VD & ({ initializer: Ex } | { initializer: Call; z: 1 }); const b: VDI<Call> = w;")
        assert(optional == listOf(
            "10:82 2322 Type '{ a: string; } & { b: 1; }' is not assignable to type 'OT<number>'." +
                " / Types of property 'a' are incompatible. / Type 'string' is not assignable to type 'number'.",
        ))
        assert(generic == listOf(
            "10:50 2322 Type 'VD & { initializer: Ex; }' is not assignable to type 'VDI<Call>'.",
        ))
        assert(union == listOf(
            "10:50 2322 Type 'VD & { initializer: Ex; }' is not assignable to type 'Alias'.",
        ))
        assert(unionMember == listOf("10:82 2322"))
    }

    @Test
    fun `negative control - the source is a real value of its type`() {
        val actual = rows("pn(v);")
        assert(actual == listOf(
            "10:4 2345 Argument of type 'VD & { initializer: Call; }' is not assignable to parameter of type 'number'.",
        ))
    }
}
