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
 * (INC.42) A GENERIC ALIAS REFERENCE WHOSE ARGUMENT IS A BARE TYPE PARAMETER.
 *
 * ## The defect, which shipped on ORDINARY builds
 *
 * B57.1b skips an alias substitution when an argument fails its parameter's constraint,
 * and it judged that with `checkTypeRelatedTo`, which has no "TypeParam source via its
 * constraint" rule. So `R1<X>` — where `X extends Nd` is handed to `R1<T extends Nd>`,
 * the argument and the parameter constrained IDENTICALLY — read as a constraint FAILURE,
 * the whole reference answered `errorType`, and it rendered `any`. Where the alias body
 * was a function type the `any` landed in the return position, which is how tsc's own
 * `type Visitor<TIn extends Node = Node, TOut extends Node | undefined = TIn | undefined>
 * = (node: TIn) => VisitResult<TOut>` rendered `(node: TIn) => any` — **213 rows** of
 * (INC.41)'s classification, in BOTH of its arms.
 *
 * ## Why nothing here saw it
 *
 * The capture sweeps (`capture-equivalence.sh`, `capture-channel-equivalence.sh`,
 * `replay-differential.sh`) are DIFFERENTIALS, so a defect present in both arms is
 * invisible to them by construction — (INC.28)'s law, whose own two-arms-agree test
 * stayed GREEN against an unfixed binary. The corpus is blind too: a display question
 * with no diagnostic attached moves no baseline. So every assertion below names the
 * rendered STRING, and every one of them read `any` before the fix.
 *
 * ## Ground truth
 *
 * Read out of `tools/tsgo-7.0.2/lib/tsc --lsp -stdio` over this exact fixture
 * (`scripts/lsp_hover.py`), never hand-written:
 *
 * ```
 * A1  type A1<X extends Nd> = (n: number) => R1<X>
 * A2  type A2<X extends Nd | undefined> = (n: number) => R2<X>
 * A3  type A3<X> = (n: number) => R3<X>
 * A4  type A4<X extends Nd> = (n: number) => R2<X>
 * C1  type C1<X extends Nd | undefined> = (n: number) => B1<X>
 * ```
 *
 * `C1` is the one row we deliberately still refuse: `X extends Nd | undefined` does NOT
 * satisfy `B1<T extends Nd>`, so this is a genuine constraint violation and the guard is
 * doing its job. tsc renders the reference anyway and reports the violation separately;
 * matching that is a different question from this one.
 */
class GenericAliasConstrainedArgumentTest {

    private val fileName = "/work/m.ts"

    private val source =
        "export interface Nd { kind: number }\n" +
            "export type R1<T extends Nd> = T | readonly Nd[];\n" +
            "export type R2<T extends Nd | undefined> = T | readonly Nd[];\n" +
            "export type R3<T> = T | readonly Nd[];\n" +
            "export type B1<T extends Nd> = { v: T };\n" +
            "export type A1<X extends Nd> = (n: number) => R1<X>;\n" +
            "export type A2<X extends Nd | undefined> = (n: number) => R2<X>;\n" +
            "export type A3<X> = (n: number) => R3<X>;\n" +
            "export type A4<X extends Nd> = (n: number) => R2<X>;\n" +
            "export type C1<X extends Nd | undefined> = (n: number) => B1<X>;\n" +
            "export type VisitResult<T extends Nd | undefined> = T | readonly Nd[];\n" +
            "export type Visitor<TIn extends Nd = Nd, TOut extends Nd | undefined = TIn | undefined>" +
            " = (node: TIn) => VisitResult<TOut>;\n"

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/work/tsconfig.json" to """{"compilerOptions":{"module":"esnext","target":"es2020","strict":true}}""",
            "/work/m.ts" to source,
        ),
    )

    private fun spans(): List<TypeCaptureSpan> {
        val file = Parser(source, fileName).parse()
        val out = ArrayList<TypeCaptureSpan>()
        val stack = ArrayList<Node>()
        stack.add(file)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (node is Identifier) out.add(TypeCaptureSpan(fileName, node.pos, node.end))
            forEachChild(node) { child -> stack.add(child) }
        }
        return out.distinct()
    }

    /**
     * The rendered types at every caret whose source text begins with `"$name<"` — i.e.
     * the alias declaration's own name, which reports that alias's type. Asserted for
     * BOTH the whole-program and the narrowed arm, because a defect both arms share is
     * exactly what a differential cannot see.
     */
    private fun rendered(name: String): List<String> {
        val out = ArrayList<String>()
        for (arm in listOf(null, setOf(fileName))) {
            val result = ProjectCompiler(vfs()).build(
                "/work",
                noEmit = true,
                recheckOnly = arm,
                typeCapture = TypeCaptureRequest(spans()),
            )
            val rows = result.capturedTypes
                .filter { it.fileName == fileName && source.startsWith("$name<", it.start) }
                .map { it.typeText }
            assert(rows.isNotEmpty())
            out.addAll(rows)
        }
        return out
    }

    @Test
    fun `a type parameter argument satisfies an identical constraint`() {
        // Before the fix: `(n: number) => any`, in both arms.
        assert(rendered("A1").all { it == "(n: number) => R1<X>" })
    }

    @Test
    fun `a type parameter argument satisfies a wider constraint`() {
        assert(rendered("A2").all { it == "(n: number) => R2<X>" })
        assert(rendered("A4").all { it == "(n: number) => R2<X>" })
    }

    @Test
    fun `tsc's own Visitor alias keeps its VisitResult return`() {
        // The 213-row family of (INC.41)'s classification, minimised. Before the fix
        // this read `(node: TIn) => any` on every ordinary build.
        assert(rendered("Visitor").all { it == "(node: TIn) => VisitResult<TOut>" })
    }

    @Test
    fun `negative control - an unconstrained parameter was never affected`() {
        // A3's inner alias declares no constraint, so the guard never ran for it and
        // this row read correctly before the fix too. It is what says the fixture's
        // capture population is real rather than empty.
        assert(rendered("A3").all { it == "(n: number) => R3<X>" })
    }

    @Test
    fun `the recursion brake inside a self-referential alias is untouched`() {
        // THE GATE, PINNED. B57.1b's refusal of a bare type parameter is what stops
        // `BuildTree`'s expansion — not because the argument fails anything (`N extends
        // number` handed to `N extends number` plainly does not), but because refusing
        // `Length<I>` and `Prepend<any, I>` keeps `BuildTree`'s own parametric body
        // degraded. A census of this very fixture says those two references are the ONLY
        // four decisions the relaxation flips in it. Let them through and `grandUser`
        // gains a TS2322 that pristine tsc does not emit — which is exactly the corpus
        // baseline `excessPropertyCheckIntersectionWithRecursiveType` pins, reproduced
        // here so the gate has a pin of its own rather than only a generated one.
        val diagnostics = diagnose(
            """
            type Prepend<V, T extends any[]> = ((head: V, ...args: T) => void) extends (
              ...args: infer R
            ) => void
              ? R
              : any;
            type Length<T extends any[]> = T["length"];

            type BuildTree<T, N extends number = -1, I extends any[] = []> = {
              1: T;
              0: T & { children: BuildTree<T, N, Prepend<any, I>>[] };
            }[Length<I> extends N ? 1 : 0];

            interface User { name: string }
            type GrandUser = BuildTree<User, 2>;

            const grandUser: GrandUser = {
              name: "Grand User",
              children: [{ name: "Son", children: [{ name: "Grand son" }] }],
            };
            """,
            directives = "// @strict: true\n// @target: es2015",
        )
        assert(diagnostics.none { it.code == 2322 })
        assert(diagnostics.none { it.code == 2589 })
    }

    @Test
    fun `negative control - a genuinely unsatisfied constraint is still refused`() {
        // `X extends Nd | undefined` does NOT satisfy `B1<T extends Nd>`. The relaxation
        // consults the ARGUMENT'S OWN constraint, so this must stay refused — a guard
        // that accepted everything would pass every assertion above for the wrong reason.
        assert(rendered("C1").all { it == "(n: number) => any" })
    }
}
