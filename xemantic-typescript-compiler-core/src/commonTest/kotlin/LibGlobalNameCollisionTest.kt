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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (LIB.5) G1 — a MODULE file's own binding must win over a LIB global of the same name.
 *
 * INV.3(d) keeps a module file's locals OUT of `globals`, so every raw `globals[name]`
 * consult in the checker answers with the LIB declaration for such a name. Two readers
 * did exactly that and both produced a confident wrong answer on legal TypeScript:
 *
 *  - `cpaResolveClassTypeCore` typed `this` inside a class's own body from
 *    `globals[<class name>]`, so `class Performance { ... this.own ... }` read the lib
 *    `interface Performance` and every own-member access was a TS2339;
 *  - `checkAssignmentExpressionCore` read an assignment TARGET's declared type from
 *    `globals[<identifier>]` behind a shadow test that knew only about a body-local
 *    `var`, so a PARAMETER or a module file-level `let` named `performance` took the type
 *    of the lib's `declare var performance: Performance`.
 *
 * Measured on `rxjs` 7.8.2 (`lib: ["ES2020","DOM"]`), where the two accounted for 8 of
 * our 29 ours-only rows against tsgo's 1 for the whole library: three TS2339 in
 * `Notification.ts`, two in `Scheduler.ts`, and three TS2739 naming the DOM `Scheduler`'s
 * `postTask`/`yield` in `bindCallbackInternals.ts` / `generate.ts` / `timer.ts`.
 *
 * THE NAME IS THE AXIS, so every positive pin here is PAIRED with the identical source
 * under a non-colliding name — a shape that works only for a lib-colliding name is
 * working by accident, and the converse pin is what says the collision is what broke it.
 *
 * FIXTURE NOTE — `diagnose()` compiles against the EMBEDDED lib, which is 809 lines and
 * carries NONE of the DOM names this defect was found on: `Scheduler`, `Notification`,
 * `File` and `scheduler` are all absent from it, so a pin spelled with those is vacuous in
 * BOTH directions and would pass on a broken binary. `interface Performance` and
 * `declare var performance: Performance` ARE in it, which is why every fixture below is
 * spelled with those two. The DOM spellings are measured on the library probe instead.
 *
 * Every expectation below is byte-identical to `tools/tsgo-7.0.2/lib/tsc`, measured on the
 * equivalent `Scheduler`/`scheduler` fixture under `lib: ["ES2020","DOM"]`.
 */
class LibGlobalNameCollisionTest {

    private val lib = """
        export interface ZzzLike { zzzNow(): number; }
        declare function zzzNum(): number;
        declare function zzzMk(): ZzzLike;
    """.trimIndent() + "\n"

    @Test
    fun `a class named after a lib type resolves its own this-members`() {
        val d = diagnose(
            """
            export class Performance {
              zzzOwn: number = 1;
              read(): number { return this.zzzOwn; }
            }
            """
        )
        assert(d.none { it.code == 2339 })
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - the same class under a non-colliding name`() {
        val d = diagnose(
            """
            export class ZzzPerf {
              zzzOwn: number = 1;
              read(): number { return this.zzzOwn; }
            }
            """
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a class named after a lib type gives its own this-member the OWN type`() {
        // VALUE pin over the WHOLE row list, and that is deliberate: ablation a1 measured
        // the TS2322 MESSAGE ALONE to be BLIND here. The assignability reader types
        // `this.zzzOwn` from the class's own member table on BOTH binaries, so the broken
        // one prints the identical `number`/`string` row AND a spurious TS2339 beside it —
        // only the whole list separates them.
        val d = diagnose(
            """
            export class Performance {
              zzzOwn: number = 1;
              probe(): void { const bad: string = this.zzzOwn; }
            }
            """
        )
        val rows = d.map { it.code to it.message }
        assert(rows == listOf(2322 to "Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `negative control - the same this-member value pin under a non-colliding name`() {
        val d = diagnose(
            """
            export class ZzzPerf {
              zzzOwn: number = 1;
              probe(): void { const bad: string = this.zzzOwn; }
            }
            """
        )
        val rows = d.map { it.code to it.message }
        assert(rows == listOf(2322 to "Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `a parameter named after a lib global keeps its own type as an assignment target`() {
        val d = diagnose(lib + "export function f(performance?: ZzzLike): void { performance = zzzMk(); }")
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - the same parameter under a non-colliding name`() {
        val d = diagnose(lib + "export function f(zzzPerf?: ZzzLike): void { zzzPerf = zzzMk(); }")
        assert(d.isEmpty())
    }

    @Test
    fun `an assignment to a parameter named after a lib global names the OWN target type`() {
        // VALUE pin: 'ZzzLike' is the parameter's own annotation; a resolution to the lib
        // `declare var performance: Performance` names 'Performance' here instead.
        val d = diagnose(lib + "export function f(performance?: ZzzLike): void { performance = zzzNum(); }")
        val messages = d.filter { it.code == 2322 }.map { it.message }
        assert(messages == listOf("Type 'number' is not assignable to type 'ZzzLike'."))
    }

    @Test
    fun `negative control - the same assignment value pin under a non-colliding name`() {
        val d = diagnose(lib + "export function f(zzzPerf?: ZzzLike): void { zzzPerf = zzzNum(); }")
        val messages = d.filter { it.code == 2322 }.map { it.message }
        assert(messages == listOf("Type 'number' is not assignable to type 'ZzzLike'."))
    }

    @Test
    fun `a module file-level binding named after a lib global wins as an assignment target`() {
        // The binding's name IS the lib global. Spelling it `performance2` here made the
        // pin VACUOUS — ablation a2 left it green — because a non-colliding name never
        // reached the `globals` consult this round narrows.
        val d = diagnose(
            lib + """
            let performance: ZzzLike | undefined;
            export function g(): void { performance = zzzMk(); }
            """.trimIndent()
        )
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - the same file-level binding under a non-colliding name`() {
        val d = diagnose(
            lib + """
            let zzzPerf: ZzzLike | undefined;
            export function g(): void { zzzPerf = zzzMk(); }
            """.trimIndent()
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a module file-level binding named after a lib global names the OWN target type`() {
        // The binding's own name IS the lib global here -- the shape rxjs's `scheduler`
        // has. A file-level `let` in a MODULE file is not in `globals` at all, so before
        // (LIB.5) this read the lib's `Performance`.
        val d = diagnose(
            lib + """
            let performance: ZzzLike | undefined;
            export function g(): void { performance = zzzNum(); }
            """.trimIndent()
        )
        val messages = d.filter { it.code == 2322 }.map { it.message }
        assert(messages == listOf("Type 'number' is not assignable to type 'ZzzLike'."))
    }

    @Test
    fun `negative control - a genuine lib global is still checked at an assignment target`() {
        // The guard demands a LOCAL binding for the name; with none, `globals` still
        // decides and the lib type is still enforced. tsgo agrees on the equivalent
        // `scheduler = zzzMk()` under `lib: dom` -- it is the ONLY row it reports there.
        val d = diagnose(lib + "export function n(): void { performance = zzzNum(); }")
        assert(d.any { it.code == 2322 && it.message.contains("'Performance'") })
    }

    @Test
    fun `negative control - a genuine lib global read still has the lib type`() {
        val d = diagnose(lib + "export function n(): void { const bad: number = performance; }")
        assert(d.any { it.code == 2322 && it.message.contains("'Performance'") })
    }
}
