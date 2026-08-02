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
 * (IANY.1) round 800: pins the CALL/NEW **argument-edge** gate — round 799
 * measured that one arm at **249 ms over 31,575 edges (7.9 µs each), 32% of
 * `spineIanyEnterNode`**, and it exists to decide ONE boolean (`typed`) on a
 * state with no type in it.
 *
 * **What the gate skips**, and the predicate that decides it
 * (`spineIanyArgSubtreeMayRead`): the state is a `kind = 0` one whose readers
 * are exactly two node kinds — an arrow / function expression, and an object
 * literal. Every other reader (an objlit `MethodDeclaration`, the
 * `PropertyAssignment` edge, an arrow's expression body) sits strictly inside
 * one of those. When neither can be reached, the arm's callee resolution and
 * its frame are both pure cost.
 *
 * **Why the obvious predicate is UNSOUND, and what these pins therefore
 * defend.** `rhsCanConsumeFnCtx` — the round-472 sibling this whole arc is
 * built on — descends only paren / conditional / `||` `??` `&&` `,` /
 * objlit-property positions. The state's real propagation set adds two entries,
 * and each has a pin here:
 *
 * 1. an **`ArrayLiteralExpression`** passes the state to its elements, so
 *    `mountAll([{ run(a) {} }])` has a reader it reports as absent;
 * 2. **every parent kind with NO arm at all** — `as`, `satisfies`, `!`, unary,
 *    spread, member access, template spans — does not redefine the state, so it
 *    stays current for the whole subtree below: `mount({ run(a) {} } as any)`
 *    is the same failure.
 *
 * Both pins fail against a predicate that omits the corresponding descent, and
 * both were checked to REACH the population (round 797's law) — the
 * `--ianySections` census asserts a non-zero arm-entry count on the same
 * fixtures.
 */
class IanyArgGateTest {

    @Test
    fun `an object literal inside an ARRAY-LITERAL argument keeps its contextual type`() {
        val d = diagnose(
            """
            declare function mountAll(o: { run(a: string): void }[]): void;
            mountAll([{ run(a) { a.length; } }]);
            """.trimIndent()
        )
        // The array literal PASSES the argument's state to its elements, so the
        // object literal's method is contextually typed. A predicate that does
        // not descend array elements skips the arm and this TS7006 appears.
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `an object literal under a NO-ARM parent keeps its contextual type`() {
        val d = diagnose(
            """
            declare function mount(o: { run(a: string): void }): void;
            mount({ run(a) { a.length; } } as any);
            """.trimIndent()
        )
        // `as` has no arm in `spineIanyEdgeEnter`, so it does not redefine the
        // state — the object literal below it still reads the argument edge's.
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `a logical-OR argument still reaches the object literal on its right`() {
        val d = diagnose(
            """
            declare function mount(o: { run(a: string): void }): void;
            declare const fallback: { run(a: string): void } | undefined;
            mount(fallback || { run(a) { a.length; } });
            """.trimIndent()
        )
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `an UNCONTEXTUALISED arrow argument is untouched by the gate`() {
        val d = diagnose(
            """
            declare function run(cb: any): void;
            run(y => y);
            """.trimIndent()
        )
        // An arrow argument is a READER, and the gate lives in the `else` branch
        // of the arrow/fn-expr test, so this path is structurally ungatable.
        assert(d.count { it.code == 7006 } == 1)
    }

    /**
     * The sharpest form of the claim: the SAME source under both settings of
     * `IanySections.argGateOff` — the pre-800 arm is in this binary — must give
     * the same diagnostics. The fixture mixes the shapes the soundness argument
     * rests on: a gated argument (a nested call), the two counter-shapes above,
     * an arrow argument that keeps its context, and an un-annotated declaration
     * so the comparison is never vacuous.
     */
    @Test
    fun `the arg gate is diagnostic-equivalent to the pre-change path`() {
        val source = """
            declare function take(p): void;
            declare function mount(o: { run(a: string): void }): void;
            declare function mountAll(o: { run(a: string): void }[]): void;
            declare function make(): { run(a: string): void };
            declare function each(cb: (x: string) => void): void;
            declare function loose(cb: any): void;
            mount(make());
            mount({ run(a) { a.length; } } as any);
            mountAll([{ run(a) { a.length; } }]);
            each(q => { q.length; });
            loose(w => w);
            take("s");
        """.trimIndent()
        val saved = IanySections.argGateOff
        try {
            IanySections.argGateOff = true
            val pre = diagnose(source).map { "${it.code}@${it.start}" }
            IanySections.argGateOff = false
            val gated = diagnose(source).map { "${it.code}@${it.start}" }
            assert(gated == pre)
            assert(pre.isNotEmpty())
        } finally {
            IanySections.argGateOff = saved
        }
    }

    /**
     * Round 797's law, applied: a pin proves nothing unless its fixture reaches
     * the population. The `--ianySections` census counts arm entries and the
     * predicate's verdicts, so this asserts directly that the equivalence
     * fixture above both ENTERS the arm and gets SKIPPED by it.
     */
    @Test
    fun `the fixture reaches the population the gate skips`() {
        val savedMode = IanySections.mode
        val savedGate = IanySections.argGateOff
        try {
            IanySections.argGateOff = false
            IanySections.reset()
            IanySections.mode = IanySections.ON
            diagnose(
                """
                declare function mount(o: { run(a: string): void }): void;
                declare function make(): { run(a: string): void };
                mount(make());
                mount({ run(a) { a.length; } } as any);
                """.trimIndent()
            )
            val entries = IanySections.armEntries
            val skipped = IanySections.armNoReader
            IanySections.mode = savedMode
            // `mount(make())` is an argument with a subtree whose only content is
            // a nested call — the predicate stops there and the arm is skipped;
            // `mount({…} as any)` enters and is kept.
            assert(entries >= 2L)
            assert(skipped >= 1L)
        } finally {
            IanySections.mode = savedMode
            IanySections.argGateOff = savedGate
            IanySections.reset()
        }
    }

    @Test
    fun `negative control - an un-annotated parameter is still reported at its declaration`() {
        val d = diagnose(
            """
            declare function take(p): void;
            declare function make(): string;
            take(make());
            """.trimIndent()
        )
        // The argument is a nested call, so the gate fires; the TS7006 that
        // survives belongs to the DECLARATION and must be unaffected.
        assert(d.count { it.code == 7006 } == 1)
    }
}
