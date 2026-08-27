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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.62) A call to a function declared `: never` DIVERGES, so the flow past it is
 * unreachable — tsc's `getTypeAtFlowCall` answers `unreachableNeverType` and the enclosing
 * branch label drops that antecedent. We followed the antecedent instead, so a switch whose
 * `default:` clause is `Debug.assertNever(kind);` merged the PRE-switch type back into the
 * post-switch flow and every narrow the other clauses had established was lost — measured on
 * the server profile at `editorServices.ts:4449` (2 profiles), where `project` came back
 * `ConfiguredProject | undefined`.
 *
 * The controls are the same two shapes with a NON-`never` return: they must still report,
 * because a rule that treated any statement call as diverging would silence them.
 *
 * RESIDUE, deliberately not pinned (round 765): a `default`-LESS switch whose case values
 * exhaust the scrutinee reaches its own no-match edge, which tsc makes unreachable via
 * `isExhaustiveSwitchStatement`. We keep it, so such a switch still loses its narrows.
 */
class DivergingCallFlowTest {

    private val prelude = """
        interface ZzzProj { zzzId: number }
        interface ZzzRes { zzzProj: ZzzProj }
        declare function zzzFind(): ZzzProj | undefined;
        declare const zzzK: 1 | 2;
        declare function zzzNeverFn(x: never): never;
        declare function zzzPlainFn(x: never): void;
        namespace ZzzDbg { export declare function zzzAssertNever(x: never): never; }
        namespace ZzzDbg2 { export declare function zzzPlain(x: never): void; }
    """.trimIndent() + "\n"

    private fun body(tail: String) = prelude + """
        function zzzF(): ZzzRes {
          let zzzProj = zzzFind();
          switch (zzzK) {
            case 1: if (!zzzProj) throw new Error("x"); break;
            case 2: if (!zzzProj) throw new Error("y"); break;
            default: $tail
          }
          return { zzzProj };
        }
    """.trimIndent()

    @Test
    fun `a switch default that calls a never-returning function keeps the other clauses' narrows`() {
        diagnose(body("zzzNeverFn(zzzK);")) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `the namespace-member form - Debug assertNever - does too`() {
        diagnose(body("ZzzDbg.zzzAssertNever(zzzK);")) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `control - a plain function call in the default clause does NOT diverge`() {
        val rows = diagnose(body("zzzPlainFn(zzzK);")).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message.contains("ZzzProj | undefined"))
    }

    @Test
    fun `control - a plain namespace-member call in the default clause does NOT diverge`() {
        val rows = diagnose(body("ZzzDbg2.zzzPlain(zzzK);")).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message.contains("ZzzProj | undefined"))
    }

    @Test
    fun `a diverging call in an if branch makes only that branch unreachable`() {
        diagnose(
            prelude + """
            function zzzG(): ZzzRes {
              let zzzProj = zzzFind();
              if (!zzzProj) { zzzNeverFn(zzzK as never); }
              return { zzzProj };
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2322 })
        }
    }
}
