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
 * (CHK.106)(b): an intersection DEDUPES its constituents by type ID.
 *
 * tsc's `addTypeToIntersection` keys its set by id, so a constituent an instantiation
 * repeats collapses. `getIntersectionType` here flattened, dropped `unknown` and reduced
 * primitives but never deduped, so a GENERIC TYPE GUARD applied to a value that already
 * satisfies it — `isBP<T>(x: T): x is T & BP` over a `BP` — displayed `Type 'BP & BP'`
 * where both tsgo 7.0.2 and pristine `typescript@6.0.3` display `Type 'BP'`.
 *
 * It is IDENTITY, not structure, exactly as tsc does it: two DISTINCT declarations of the
 * same shape keep both slots.
 *
 * ## Stated residue - deliberately NOT pinned
 *
 * `T1 & T1`, an alias to an ANONYMOUS body repeated, still renders `T1 & T1` where both
 * references render `T1`: the constituent is anonymous and therefore exempt, and the only
 * rule that would separate it from the `{ p: number } & { p: number }` case reads
 * `aliasDisplayMap`, which is populated FIRST-WINS during the walk and would make the
 * dedupe a function of resolution ORDER (round 776). Unchanged from the parent.
 *
 * (CHK.106)'s other three parts are verification and are recorded in the (P18.42) note
 * rather than pinned: (a) an alias NAME lost through a carrier instantiation is
 * (INC.27)/(INC.29)'s interning-key question and is deliberately not attempted here — and
 * it is BROADER than the item recorded, since a DIRECT `Fn<number>` annotation loses the
 * name too while a direct `Obj<number>` keeps it; (c) closed by (CHK.100); (d) is a
 * reference divergence where ours follows tsgo.
 */
class IntersectionConstituentDedupeTest {

    private val prelude = """
        interface BP { p: number }
        interface A1 { p: number }
        interface A2 { q: number }
        interface B1 { p: number }
        type T2 = { p: number };
        type T3 = { p: number };
        declare function isBP<T>(x: T): x is T & BP;
    """.trimIndent()

    private fun decl(t: String) = "Type '$t' is not assignable to type 'boolean'."

    @Test
    fun `a generic guard over a value that already satisfies it renders one constituent`() {
        val d = diagnose(
            prelude + """

            function f(v: BP) {
              if (isBP(v)) {
                const w: boolean = v;
              }
            }
            """
        )
        assert(d.count { it.code == 2322 } == 1)
        assert(d.first { it.code == 2322 }.message == decl("BP"))
    }

    /**
     * An ANONYMOUS object constituent is EXEMPT: two separate `{ p: number }` type-literal
     * NODES are two types in tsc and ONE interned type here, so deduping them would collapse
     * a display BOTH references print.
     *
     * MEASURED BLIND under `diagnose()` and kept as documentation: the unrestricted
     * id-dedupe leaves this GREEN here, because under the single-file harness the two
     * literals get DISTINCT ids. The exemption's evidence is the PROJECT path, where they
     * share one: on `build/bench/chk106/r4` the unrestricted build printed
     * `Type '{ p: number; }'` and the guarded one prints
     * `Type '{ p: number; } & { p: number; }'`, which is what both references print. A pin
     * that could see it belongs in the `-project` module.
     */
    @Test
    fun `negative control - two anonymous object literals are kept`() {
        val d = diagnose(
            "declare const x: { p: number } & { p: number };\nconst w: boolean = x;\n"
        )
        assert(d.count { it.code == 2322 } == 1)
        assert(d.first { it.code == 2322 }.message == decl("{ p: number; } & { p: number; }"))
    }

    @Test
    fun `negative control - two distinct aliases of the same body are kept`() {
        val d = diagnose(prelude + "\n\ndeclare const x: T2 & T3;\nconst w: boolean = x;\n")
        assert(d.count { it.code == 2322 } == 1)
        assert(d.first { it.code == 2322 }.message == decl("T2 & T3"))
    }

    @Test
    fun `a repeated interface constituent collapses`() {
        val d = diagnose(prelude + "\n\ndeclare const x: A1 & A1;\nconst w: boolean = x;\n")
        assert(d.count { it.code == 2322 } == 1)
        assert(d.first { it.code == 2322 }.message == decl("A1"))
    }

    @Test
    fun `negative control - distinct constituents are kept`() {
        val d = diagnose(prelude + "\n\ndeclare const x: A1 & A2;\nconst w: boolean = x;\n")
        assert(d.count { it.code == 2322 } == 1)
        assert(d.first { it.code == 2322 }.message == decl("A1 & A2"))
    }

    /** The dedupe is by IDENTITY: two declarations of the same SHAPE are two types. */
    @Test
    fun `negative control - structurally identical but distinct declarations are kept`() {
        val d = diagnose(prelude + "\n\ndeclare const x: A1 & B1;\nconst w: boolean = x;\n")
        assert(d.count { it.code == 2322 } == 1)
        assert(d.first { it.code == 2322 }.message == decl("A1 & B1"))
    }
}
