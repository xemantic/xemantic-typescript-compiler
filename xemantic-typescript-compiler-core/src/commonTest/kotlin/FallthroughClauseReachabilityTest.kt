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
 * (CHK.201) TS7029 asks whether the END of a non-empty clause is reachable — tsgo's
 * `clause.FallthroughFlowNode`. The syntactic `isDefinitelyTerminating` predicate has no
 * `Block` / labelled arm, so `case X: { return }` (all 60 zod locale files) read as falling
 * through; the flow graph's clause-end reachability now refuses those reports.
 *
 * Every expected row was read out of `tools/tsgo-7.0.2/lib/tsc` over the same fixture.
 */
class FallthroughClauseReachabilityTest {

    private val directives =
        "// @strict: true\n// @noFallthroughCasesInSwitch: true\n// @allowUnreachableCode: true\n// @allowUnusedLabels: true"

    private fun rows(source: String, code: Int): List<String> =
        diagnose(source, directives).filter { it.code == code }
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    private val matrix = """
            declare function fail(): never;
            declare const b: boolean;
            function f(c: number, xs: number[]): number {
              switch (c) {
                case 1: {
                  return 1;
                }
                case 2: {
                  {
                    return 2;
                  }
                }
                case 3:
                  if (b) { return 3; } else { return 4; }
                case 4:
                  if (b) { return 3; }
                case 5:
                  try { return 5; } finally { }
                case 6:
                  try { return 5; } catch { }
                case 7: {
                  throw new Error();
                }
                case 8: {
                  break;
                }
                case 9:
                case 10: {
                  c++;
                }
                case 11:
                  lbl: {
                    break lbl;
                  }
                case 12:
                  lbl2: {
                    return 1;
                  }
                case 13:
                  for (const x of xs) { if (x) break; }
                case 14:
                  while (true) { }
                case 15:
                  for (;;) { if (b) break; }
                case 16: {
                  fail();
                }
                case 17: {
                  if (b) { return 1; } else { throw new Error(); }
                }
                case 18:
                  try { c++; } finally { return 2; }
                case 19: {
                  switch (c) { case 1: return 1; default: return 2; }
                }
                case 20: {
                  if (b) break;
                  return 1;
                }
                case 21:
                  { c++; }
                  return 3;
                default:
                  return 0;
              }
            }
            function g(c: number, xs: number[]) {
              outer: for (const x of xs) {
                switch (c) {
                  case 1: {
                    continue;
                  }
                  case 2: {
                    break outer;
                  }
                  case 3: {
                    continue outer;
                  }
                  case 4:
                    c++;
                  case 5:
                    return x;
                }
              }
              return 0;
            }
    """

    @Test
    fun `only the clauses whose end is reachable report TS7029 - the tsgo rows`() {
        val actual = rows(matrix, 7029)
        val expected = listOf(15, 19, 28, 31, 39, 43, 79).map { line ->
            val col = if (line == 79) 7 else 5
            "$line:$col TS7029 Fallthrough case in switch."
        }.sorted()
        assert(actual == expected)
    }

    @Test
    fun `a block body ending in return is not a fallthrough`() {
        val actual = rows(
            """
            function f(c: string): string {
                switch (c) {
                    case "a": {
                        const x = c + "!";
                        return x;
                    }
                    case "b":
                        return "b";
                    default:
                        return "z";
                }
            }
            """,
            7029,
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - a block body that completes normally still reports`() {
        val actual = rows(
            """
            function f(c: string): string {
                switch (c) {
                    case "a": {
                        c = c + "!";
                    }
                    case "b":
                        return "b";
                }
                return "";
            }
            """,
            7029,
        )
        assert(actual == listOf("3:9 TS7029 Fallthrough case in switch."))
    }

    @Test
    fun `negative control - a labelled block left by break lbl falls into the next clause`() {
        val actual = rows(
            """
            function f(c: number): number {
                switch (c) {
                    case 1:
                        lbl: {
                            break lbl;
                        }
                    case 2:
                        return 2;
                }
                return 0;
            }
            """,
            7029,
        )
        assert(actual == listOf("3:9 TS7029 Fallthrough case in switch."))
    }
}
