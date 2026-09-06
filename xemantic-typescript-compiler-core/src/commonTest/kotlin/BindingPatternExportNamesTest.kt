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
 * (CHK.99) [bindingPatternNames] — the checker-side mirror of the binder's
 * `bindVariableDeclarationName`, which is what five AST-derived export sets in
 * [Checker] were missing.
 *
 * **Why these pins are here and not only in the `-project` module.** The
 * cross-file half of (CHK.99) — a named import of a pattern leaf, an `export *`
 * barrel, `typeof NS` — needs a real directory and is pinned in
 * `ProjectDestructuredExportTest`. This class pins the ENUMERATION itself, at
 * the granularity a per-shape mistake is made: an implementation that reads
 * `propertyName` instead of `name`, that descends a default's initializer, that
 * treats an array HOLE as a binding, or that stops at the first nesting level
 * would still pass a coarse cross-file pin whose fixture happens to use only
 * shorthand members.
 *
 * The subject is PARSED rather than hand-built, so what is enumerated is what
 * this parser actually produces; a hand-built `ObjectBindingPattern` would pin
 * the test's own idea of the AST.
 */
class BindingPatternExportNamesTest {

    /** The `name` node of the single variable declaration in [source]. */
    private fun namesOf(source: String): List<String> {
        val sf = Parser(source, "t.ts").parse()
        val stmt = sf.statements.filterIsInstance<VariableStatement>().single()
        return bindingPatternNames(stmt.declarationList.declarations.single().name)
    }

    @Test
    fun `an Identifier answers itself so every call site is a drop-in`() {
        assert(namesOf("export const plain = 1;") == listOf("plain"))
    }

    @Test
    fun `a shorthand object member binds its own name`() {
        assert(namesOf("export const { p } = o;") == listOf("p"))
    }

    /**
     * The LOCAL name, never the `propertyName`. Reading `propertyName` produces a
     * set that is the right SIZE and wrong in every element — the mistake a count
     * assertion cannot see.
     */
    @Test
    fun `a renamed member binds the local name and not the property name`() {
        assert(namesOf("export const { q: renamed } = o;") == listOf("renamed"))
    }

    @Test
    fun `a default does not add a name and does not hide one`() {
        assert(namesOf("""export const { q = "z" } = o;""") == listOf("q"))
        assert(namesOf("""export const { q: r = "z" } = o;""") == listOf("r"))
    }

    @Test
    fun `an object rest element binds like any other element`() {
        assert(namesOf("export const { p, ...rest } = o;") == listOf("p", "rest"))
    }

    @Test
    fun `an array pattern binds its elements in order`() {
        assert(namesOf("export const [t0, t1] = tup;") == listOf("t0", "t1"))
    }

    /**
     * A hole is an `OmittedExpression`, which binds nothing — so the surviving
     * names must not shift. An implementation that indexed positionally, or that
     * fell through on the non-`BindingElement` element, would answer `["second"]`
     * for the wrong reason or throw.
     */
    @Test
    fun `an array hole binds nothing and does not shift the rest`() {
        assert(namesOf("export const [, second, , fourth] = tup;") == listOf("second", "fourth"))
    }

    @Test
    fun `an array rest binds its name`() {
        assert(namesOf("export const [head, ...tail] = tup;") == listOf("head", "tail"))
    }

    @Test
    fun `nesting is followed to the leaves in both pattern kinds`() {
        assert(namesOf("export const { n: { d, e } } = o;") == listOf("d", "e"))
        assert(namesOf("export const [[a, b], [c]] = x;") == listOf("a", "b", "c"))
        assert(namesOf("export const { n: [z] } = o;") == listOf("z"))
        assert(namesOf("export const [{ w }] = x;") == listOf("w"))
    }

    /**
     * A computed KEY is not a bound name; the local it binds still is. Pinned
     * because the tempting implementation reads the ELEMENT's `propertyName`,
     * which here is a `ComputedPropertyName` and is not a name at all.
     */
    @Test
    fun `a computed key binds only the local name`() {
        assert(namesOf("export const { [key]: computed } = o;") == listOf("computed"))
    }

    @Test
    fun `several declarations of one pattern are all enumerated`() {
        assert(namesOf("export const { p: a1, q: a2, n: { d: a3 } } = o;") == listOf("a1", "a2", "a3"))
    }
}
