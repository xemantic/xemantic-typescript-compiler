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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * INV.4(b) batch 15 (round 521): the await-context family
 * (TS1308 await-outside-async / TS1103 for-await-outside-async /
 * TS2311 `await(...)` missing-async heuristic / TS1262 top-level `await`
 * binding in a module) migrated onto the check spine from the deleted
 * `checkAwaitContext` / `checkAwaitInStatements` / `checkAwaitInStatement` /
 * `checkAwaitInExpr` walk family. The threaded isAsync/enclosingFunc pair
 * became ONE full-chain parent walk ([spineAwaitCtx]): the FIRST function-like
 * boundary decides the flags (async modifier; the TS1356 related-info FuncRef),
 * and the WHOLE chain up to the SourceFile must consist of positions the old
 * walk descended — any unlisted parent kind means "unreached" (no emission),
 * exactly the old walker's silence.
 *
 * Reach is deliberately NOT widened (direct emitters — the emission-direction
 * rule): parameter defaults (TS2524 owns them), enum member initializers,
 * computed member names, class static blocks, shorthand destructuring
 * defaults, and object-literal ACCESSOR bodies stay unreached, pinned
 * negative below as signal-driven widening candidates. Every pin here passes
 * against the OLD walker (pre-verified) — a pure reach-preserving migration.
 */
class Inv4SpineBatch15Test {

    // ── TS1308: await in a non-async context ────────────────────────────────

    @Test
    fun `await in sync function body fires TS1308 with async-hint related info`() {
        diagnose(
            """
            function f() { await 1; }
            """,
        ) should {
            have(any { it.code == 1308 && it.relatedInformation.any { r -> r.code == 1356 } })
        }
    }

    @Test
    fun `await in constructor body fires TS1308`() {
        diagnose(
            """
            class C { constructor() { await 1; } }
            """,
        ) should {
            have(any { it.code == 1308 })
        }
    }

    @Test
    fun `await in getter body fires TS1308`() {
        diagnose(
            """
            class C { get g() { await 1; return 1; } }
            """,
        ) should {
            have(any { it.code == 1308 })
        }
    }

    @Test
    fun `await in class property initializer fires TS1308`() {
        diagnose(
            """
            class C { p = await 1; }
            """,
        ) should {
            have(any { it.code == 1308 })
        }
    }

    @Test
    fun `await in sync arrow body fires TS1308 with related info`() {
        diagnose(
            """
            const f = () => { await 1; };
            """,
        ) should {
            have(any { it.code == 1308 && it.relatedInformation.any { r -> r.code == 1356 } })
        }
    }

    @Test
    fun `await in sync function expression body fires TS1308`() {
        diagnose(
            """
            const f = function() { await 1; };
            """,
        ) should {
            have(any { it.code == 1308 })
        }
    }

    @Test
    fun `await in object-literal method body fires TS1308`() {
        diagnose(
            """
            const o = { m() { await 1; } };
            """,
        ) should {
            have(any { it.code == 1308 })
        }
    }

    @Test
    fun `top-level await in a SCRIPT file under module es2022 fires TS1308 without related info`() {
        diagnose(
            """
            await 1;
            """,
            directives = "// @strict: true\n// @module: es2022",
        ) should {
            have(any { it.code == 1308 && it.relatedInformation.isEmpty() })
        }
    }

    @Test
    fun `await in a namespace body of a script file fires TS1308`() {
        diagnose(
            """
            namespace N { const x = await 1; }
            """,
        ) should {
            have(any { it.code == 1308 })
        }
    }

    @Test
    fun `negative control - top-level await in a MODULE file does not fire TS1308`() {
        diagnose(
            """
            export {};
            await 1;
            """,
            directives = "// @strict: true\n// @module: es2022",
        ) should {
            have(none { it.code == 1308 })
        }
    }

    @Test
    fun `negative control - await in async function body does not fire TS1308`() {
        diagnose(
            """
            async function f() { await 1; }
            """,
        ) should {
            have(none { it.code == 1308 })
        }
    }

    @Test
    fun `negative control - await in async method body does not fire TS1308`() {
        diagnose(
            """
            class C { async m() { await 1; } }
            """,
        ) should {
            have(none { it.code == 1308 })
        }
    }

    @Test
    fun `old-walk quirk - namespace body in a MODULE file inherits module top-level asyncness`() {
        // The old walk passed the file-level isAsync (= isModule) INTO namespace
        // bodies unchanged, so an await at namespace level in a module file never
        // fired (tsc WOULD error — signal-driven faithfulness candidate).
        diagnose(
            """
            export {};
            namespace N { export const x = await 1; }
            """,
            directives = "// @strict: true\n// @module: es2022",
        ) should {
            have(none { it.code == 1308 })
        }
    }

    // ── old-walk reach negatives (unreached positions, preserved) ───────────

    @Test
    fun `old reach - await in async fn parameter default is TS2524 territory, not TS1308`() {
        diagnose(
            """
            async function f(g = await 1) {}
            """,
        ) should {
            have(any { it.code == 2524 })
            have(none { it.code == 1308 })
        }
    }

    @Test
    fun `old reach - await in enum member initializer stays unreached`() {
        diagnose(
            """
            enum E { A = await 1 }
            """,
        ) should {
            have(none { it.code == 1308 })
        }
    }

    @Test
    fun `old reach - await in computed method name stays unreached`() {
        diagnose(
            """
            class C { [await 1]() {} }
            """,
        ) should {
            have(none { it.code == 1308 })
        }
    }

    @Test
    fun `old reach - await in class static block stays unreached`() {
        diagnose(
            """
            class C { static { await 1; } }
            """,
        ) should {
            have(none { it.code == 1308 })
        }
    }

    @Test
    fun `old reach - await in shorthand destructuring default stays unreached`() {
        diagnose(
            """
            function f(obj: any) { let q; ({ q = await 1 } = obj); }
            """,
        ) should {
            have(none { it.code == 1308 })
        }
    }

    @Test
    fun `old reach - await in object-literal getter body stays unreached`() {
        diagnose(
            """
            const o = { get g() { await 1; return 1; } };
            """,
        ) should {
            have(none { it.code == 1308 })
        }
    }

    // ── TS1103: for-await outside async ─────────────────────────────────────

    @Test
    fun `for-await in sync function fires TS1103 with related info`() {
        diagnose(
            """
            function f(xs: any) { for await (const x of xs) {} }
            """,
        ) should {
            have(any { it.code == 1103 && it.relatedInformation.any { r -> r.code == 1356 } })
        }
    }

    @Test
    fun `negative control - for-await in async function does not fire TS1103`() {
        diagnose(
            """
            async function f(xs: any) { for await (const x of xs) {} }
            """,
        ) should {
            have(none { it.code == 1103 })
        }
    }

    @Test
    fun `negative control - top-level for-await in a MODULE file does not fire TS1103`() {
        diagnose(
            """
            export {};
            declare const xs: any;
            for await (const x of xs) {}
            """,
            directives = "// @strict: true\n// @module: es2022",
        ) should {
            have(none { it.code == 1103 })
        }
    }

    // ── TS2311: await(...) missing-async heuristic ──────────────────────────

    @Test
    fun `await-call in sync function fires TS2311`() {
        diagnose(
            """
            function f() { await(1); }
            """,
        ) should {
            have(any { it.code == 2311 })
        }
    }

    @Test
    fun `top-level await binding suppresses TS2311 and fires TS1262`() {
        diagnose(
            """
            export {};
            function await(x: number) {}
            function g() { await(1); }
            """,
        ) should {
            have(any { it.code == 1262 })
            have(none { it.code == 2311 })
        }
    }

    // ── TS1262: `await` bound at module top level ───────────────────────────

    @Test
    fun `top-level var named await in a module fires TS1262`() {
        diagnose(
            """
            export {};
            var await = 1;
            """,
        ) should {
            have(any { it.code == 1262 })
        }
    }

    @Test
    fun `destructured top-level binding named await in a module fires TS1262`() {
        diagnose(
            """
            export {};
            declare const obj: any;
            const { await } = obj;
            """,
        ) should {
            have(any { it.code == 1262 })
        }
    }

    @Test
    fun `negative control - var named await in a script file does not fire TS1262`() {
        diagnose(
            """
            var await = 1;
            """,
        ) should {
            have(none { it.code == 1262 })
        }
    }
}
