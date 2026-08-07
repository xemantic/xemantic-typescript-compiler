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
 * (ENGINE.2b) round 788 — the `cpaArgsMayConsumeContext` PRE-GATE on
 * `cpaComputeArgCtxTypes`.
 *
 * The gate skips the contextual-argument-type computation for a call whose argument
 * subtrees contain none of the four kinds that can READ `contextualType`
 * (ArrowFunction / FunctionExpression / ObjectLiteralExpression /
 * ArrayLiteralExpression). These pins state the invariant that makes the skip safe:
 * **every shape that CAN consume a contextual argument type still gets one.**
 *
 * They are therefore controls against a gate that is too AGGRESSIVE, not against
 * HEAD — a build that always computes (pre-788) passes all of them, which is the
 * point. The falsifying binary is one whose predicate returns `false` unconditionally.
 *
 * The observable is a TS2339 inside the callback body: without a contextual type the
 * un-annotated parameter stays `any` and NOTHING is reported, so *the error firing is
 * the evidence that the contextual type arrived* — the reverse of a suppression pin.
 */
class CpaArgCtxPreGateTest {

    private val prelude = """
        interface Box { a: number }
        declare function each(cb: (x: Box) => void): void;
        declare function pair(n: number, cb: (x: Box) => void): void;
    """.trimIndent() + "\n"

    @Test
    fun `an arrow argument's un-annotated parameter is still contextually typed`() {
        val d = diagnose(prelude + """
            each(v => { v.nope; });
        """)
        assert(d.any { it.code == 2339 && it.message.contains("nope") })
    }

    @Test
    fun `negative control - the same arrow reading a real member is silent`() {
        val d = diagnose(prelude + """
            each(v => { v.a; });
        """)
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `a function-expression argument's parameter is still contextually typed`() {
        val d = diagnose(prelude + """
            each(function (v) { v.nope; });
        """)
        assert(d.any { it.code == 2339 && it.message.contains("nope") })
    }

    @Test
    fun `an arrow in a LATER argument position is still contextually typed`() {
        val d = diagnose(prelude + """
            pair(1, v => { v.nope; });
        """)
        assert(d.any { it.code == 2339 && it.message.contains("nope") })
    }

    @Test
    fun `an arrow nested inside an object-literal argument is still contextually typed`() {
        val d = diagnose(prelude + """
            declare function reg(o: { cb: (x: Box) => void }): void;
            reg({ cb: v => { v.nope; } });
        """)
        assert(d.any { it.code == 2339 && it.message.contains("nope") })
    }

    @Test
    fun `an arrow wrapped in parentheses inside an argument is still contextually typed`() {
        val d = diagnose(prelude + """
            each((v => { v.nope; }));
        """)
        assert(d.any { it.code == 2339 && it.message.contains("nope") })
    }

    @Test
    fun `an arrow returned from an arrow argument is still contextually typed`() {
        val d = diagnose(prelude + """
            declare function outer(cb: () => (x: Box) => void): void;
            outer(() => v => { v.nope; });
        """)
        assert(d.any { it.code == 2339 && it.message.contains("nope") })
    }

    @Test
    fun `an arrow in the argument of a NESTED call takes the inner callee's context`() {
        val d = diagnose(prelude + """
            interface Other { z: number }
            declare function inner(cb: (x: Other) => void): number;
            each2(inner(v => { v.a; }));
            declare function each2(n: number): void;
        """)
        // `v` is contextually `Other`, so reading `a` — a member of Box, not Other —
        // must still be reported: the inner call's own context wins.
        assert(d.any { it.code == 2339 && it.message.contains("'a'") })
    }

    @Test
    fun `an argument subtree past the scan budget still gets its contextual type`() {
        // The pre-gate pops the LAST argument first, so the 200-term chain is walked
        // before the arrow is ever seen: the budget runs out and the predicate answers
        // TRUE (compute as before). This is the branch that keeps the gate bounded.
        val chain = (1..200).joinToString(" + ") { "$it" }
        val d = diagnose(prelude + """
            declare function both(cb: (x: Box) => void, n: number): void;
            both(v => { v.nope; }, $chain);
        """)
        assert(d.any { it.code == 2339 && it.message.contains("nope") })
    }

    @Test
    fun `negative control - a call whose arguments cannot consume a context is unaffected`() {
        val d = diagnose(prelude + """
            declare function take(s: string, n: number): void;
            const s = "x";
            take(s, 1);
            take(s.toUpperCase(), s.length);
        """)
        assert(d.isEmpty())
    }

    @Test
    fun `an arrow argument inside a template expression argument is still contextually typed`() {
        val d = diagnose(prelude + """
            declare function fmt(s: string): void;
            declare function toS(cb: (x: Box) => void): string;
            fmt(`v=${'$'}{toS(v => { v.nope; })}`);
        """)
        assert(d.any { it.code == 2339 && it.message.contains("nope") })
    }
}
