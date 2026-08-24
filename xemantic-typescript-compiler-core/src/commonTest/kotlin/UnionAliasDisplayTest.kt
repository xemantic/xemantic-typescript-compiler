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
 * (INC.27) WHAT B416's STRUCTURAL UNION-ALIAS TABLE ACTUALLY GUARANTEES, AND THE
 * BOUNDARY IT CANNOT CROSS.
 *
 * `Checker.unionAliasStructural` maps a SORTED MEMBER-ID LIST to an alias name, so a
 * union reconstructed by flow narrowing or switch fallthrough still displays as the
 * alias it came from. These two pins are the population that must survive any future
 * work on union display; the KDoc on that field carries the measurement of what the
 * table gets WRONG and the proof that no rule over this key can fix it.
 *
 * In one line: tsc keys its union cache by
 * `getTypeListId(types) + getAliasId(aliasSymbol, …)`, so `type ModuleName = Ident |
 * Str` and `type ModuleExportName = Ident | Str` are two instances there and a union
 * built with no alias is a third — three answers, measured against
 * `tools/tsgo-7.0.2/lib/tsc`. INV.5(a) (round 545) interns ours by member-id list
 * alone, so all three are ONE `Type` here, and no member-set-keyed table can tell them
 * apart. Nothing below pins that divergence: an open gap belongs in the session note,
 * not in a test (CLAUDE.md, round 765).
 */
class UnionAliasDisplayTest {

    /**
     * A SOLITARY alias for a member set names it — the ordinary annotated case, and the
     * answer tsc gives for a site that spells the alias.
     */
    @Test
    fun `a solitary union alias names its member set`() {
        val messages = diagnose(
            """
            interface Ident { i: number }
            interface Str { s: number }
            type ModuleName = Ident | Str;
            declare const byName: ModuleName;
            const bad: number = byName;
            """,
            directives = "// @strict: true",
        ).filter { it.code == 2322 }.map { it.message }
        assert(messages.size == 1)
        assert("'ModuleName'" in messages.single())
    }

    /**
     * B416's own population, pinned locally as well as by the corpus: a union
     * RECONSTRUCTED by switch fallthrough is a different `Type` object from the one
     * the annotation produced in tsc, and the same interned one here — either way it
     * must display under the alias. `narrowByClauseExpressionInSwitchTrue6`'s pristine
     * baseline reports `Property 'cProps' does not exist on type 'MyType'` for exactly
     * this shape, so a rule that stopped naming solitary member sets would move AWAY
     * from tsc.
     */
    @Test
    fun `a reconstructed full union still displays as its solitary alias`() {
        val messages = diagnose(
            """
            interface A {
                kind: "a";
                aProps: string;
            }

            interface B {
                kind: "b";
                bProps: string;
            }

            interface C {
                kind: "c";
                cProps: string;
            }


            type MyType = A | B | C;

            function isA(x: MyType) {
                switch (true) {
                    default:
                        const never: never = x;
                    case x.kind === "a":
                        x.aProps;
                        break;
                    case x.kind === "b":
                        x.bProps;
                        break;
                    case x.kind === "c":
                        x.cProps;
                        break;
                }

                switch (true) {
                    default:
                        const never: never = x;
                    case x.kind === "a": {
                        x.aProps;
                        break;
                    }
                    case x.kind === "b": {
                        x.bProps;
                        break;
                    }
                    case x.kind === "c": {
                        x.cProps;
                        break;
                    }
                }

                switch (true) {
                    default:
                        x.aProps;
                        break;
                    case x.kind === "b":
                        x.bProps;
                        break;
                    case x.kind === "c":
                        x.cProps;
                        break;
                }

                switch (true) {
                    default:
                        const never: never = x;
                    case x.kind === "a":
                        x.aProps;
                        // fallthrough
                    case x.kind === "b":
                        x.bProps;
                        // fallthrough
                    case x.kind === "c":
                        x.cProps;
                }
            }
            """,
            directives = "// @target: es2015\n// @strict: true\n// @noEmit: true",
        ).filter { it.code == 2339 }.map { it.message }
        assert(messages.size == 2)
        assert(messages.any { "'MyType'" in it })
        assert(messages.any { "'A | B'" in it })
    }
}
