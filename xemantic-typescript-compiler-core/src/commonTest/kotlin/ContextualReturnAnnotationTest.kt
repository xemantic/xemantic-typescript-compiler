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
 * (CHK.30) A concise-body arrow's OWN return-type annotation is a contextual type
 * for its body, and until this class existed it was the one contextual source with
 * no path into the implicit-any walker at all.
 *
 * A BLOCK body always had one — `spineIanyReturnCtxAt` reads the enclosing
 * function-like's `type` at the `return` edge — so `(): V => { return { … } }` was
 * correct while `(): V => ({ … })` was not, which is why the gap survived: the two
 * spellings are interchangeable to a reader and only one of them is checked. The
 * expression-body edge inherited the incoming contextual SIGNATURE's return type
 * (round 472) and nothing else, so the curried factory
 * `(dep: D): Handler => (a, b) => …` — the idiom this cost 2 of knip's 3 residual
 * TS7006 rows — had no contextual type for its inner arrow either.
 *
 * **WHAT THIS CHANGE IS AND IS NOT.** It supplies the contextual ARITY, which is
 * what decides TS7006 (B224's rule: a parameter within a contextual signature's
 * arity is never implicitly `any`; one beyond it still is). It does NOT make the
 * parameter carry that type in the assignability walkers — measured, `const bad:
 * string = node.kind` inside such a body is silent here and reports TS2322 under
 * tsc, and that is equally true of every contextual shape this checker already
 * "supported", including a plain arrow ARGUMENT. So this is a false-POSITIVE fix
 * with a known false-NEGATIVE behind it; the queue entry that owns the second half
 * says so explicitly rather than leaving a future round to discover it.
 *
 * Which is exactly why the pins below are not "TS7006 went away": a binary that
 * merely stopped emitting the diagnostic satisfies that. They assert the ARITY was
 * read — the parameter the annotation covers is silent while the one BEYOND it
 * still reports — and the negative controls keep the diagnostic alive where no
 * annotation supplies a context. Rows were read out of `tools/tsgo-7.0.2/lib/tsc`.
 */
class ContextualReturnAnnotationTest {

    private val prelude = """
        interface N { kind: number }
        interface V { m(node: N): void }
        interface B { tag: string }
    """.trimIndent() + "\n"

    private fun codes(source: String): List<Int> =
        diagnose(prelude + source.trimIndent()).map { it.code }.sorted()

    @Test
    fun `a concise-body arrow's own return annotation types an object-literal method's parameter`() {
        assert(codes("export const p = (): V => ({ m(node) { node; } });").isEmpty())
    }

    @Test
    fun `a curried factory's inner arrow is typed by the outer annotation`() {
        assert(
            codes("export const p = (d: number): ((a: N) => void) => (a) => { a; d; };")
                .isEmpty(),
        )
    }

    /**
     * THE DISCRIMINATOR. The annotation provides arity 1, so `a` is contextually
     * typed and silent while `b` is beyond it and still implicitly `any`. A change
     * that silenced TS7006 rather than reading the annotation loses this row; a
     * change that never read the annotation gains a second one on `a`.
     *
     * The accompanying TS2322 is the arity mismatch at the assignment and is
     * reported by tsc too — it is here so the assertion is over the whole list and
     * cannot drift into a partial one.
     */
    @Test
    fun `a parameter beyond the annotation's arity still reports`() {
        assert(
            codes("export const p = (): ((a: N) => void) => (a, b) => { a; b; };") ==
                listOf(2322, 7006),
        )
    }

    /** The object-literal member form of the same discriminator. */
    @Test
    fun `an object-literal member parameter beyond the member's arity still reports`() {
        assert(
            codes("export const p = (): V => ({ m(node, extra) { node; extra; } });") ==
                listOf(2322, 7006),
        )
    }

    @Test
    fun `negative control - with no return annotation and no context both parameters report`() {
        assert(codes("export const p = () => (a, b) => { a; b; };") == listOf(7006, 7006))
    }

    @Test
    fun `negative control - a block-bodied arrow was already correct and stays so`() {
        assert(codes("export const p = (): V => { return { m(node) { node; } }; };").isEmpty())
    }

    /**
     * An arrow's OWN annotation outranks the contextual signature it is passed
     * against — tsc's `getContextualReturnType` consults the annotation first. Here
     * the call site says the inner parameter is `N` and the annotation says `B`; the
     * TS2339 on `x.tag` is what a checker that took the CALL SITE's answer reports,
     * so this pin fails in a NAMED direction rather than merely failing.
     */
    @Test
    fun `the arrow's own annotation outranks the contextual signature`() {
        val d = diagnose(
            prelude + """
                declare function use(f: () => (x: N) => void): void;
                use((): ((x: B) => void) => (x) => { x.tag; });
            """.trimIndent(),
        )
        assert(d.none { it.code == 2339 })
        assert(d.none { it.code == 7006 })
    }
}
