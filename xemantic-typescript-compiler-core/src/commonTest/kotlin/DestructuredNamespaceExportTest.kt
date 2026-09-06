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
 * (CHK.99), the SAME-FILE half: a namespace-scoped `export const { p } = o` is an
 * export of the namespace, so `NS.p` is legal — and it read
 * `Property 'p' does not exist on type 'typeof NS'` here, because
 * `isNameExportedFromNamespace`'s variable arm matched only `d.name is Identifier`.
 *
 * **This half needs no directory**, which is why it is pinned in the core module
 * while the cross-file half (a named import of a pattern leaf, directly and through
 * an `export *` barrel) lives in `-project`'s `ProjectDestructuredExportTest`. Both
 * are needed: the two sets are computed by different code and only the namespace one
 * is reachable from a single file.
 *
 * Every expectation was read out of `tools/tsgo-7.0.2/lib/tsc` and pristine
 * `typescript@6.0.3` over the same sources, which agree on all of them.
 */
class DestructuredNamespaceExportTest {

    private val prelude = """
        interface I { p: number; q: string; n: { d: number } }
        declare const obj: I;
        declare const tup: [number, string];
    """.trimIndent()

    /** [body] is the namespace body; the member read is `NS.<member>`. */
    private fun readMember(body: String, member: String) = diagnose(
        """
        $prelude
        export namespace NS { $body }
        const bad: boolean = NS.$member;
        """.trimIndent(),
    )

    @Test
    fun `a shorthand namespace member is exported and carries its type`() {
        readMember("export const { p } = obj;", "p") should {
            have(none { it.code == 2339 })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'boolean'." })
        }
    }

    @Test
    fun `a renamed namespace member is exported under its local name`() {
        readMember("export const { q: renamed } = obj;", "renamed") should {
            have(none { it.code == 2339 })
            have(any { it.code == 2322 && it.message == "Type 'string' is not assignable to type 'boolean'." })
        }
    }

    @Test
    fun `an array namespace member is exported and carries its type`() {
        readMember("export const [t0] = tup;", "t0") should {
            have(none { it.code == 2339 })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'boolean'." })
        }
    }

    @Test
    fun `a nested namespace member is exported`() {
        readMember("export const { n: { d } } = obj;", "d") should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a let and a var pattern are exported`() {
        readMember("export let { p: viaLet } = obj;", "viaLet") should { have(none { it.code == 2339 }) }
        readMember("export var { p: viaVar } = obj;", "viaVar") should { have(none { it.code == 2339 }) }
    }

    /**
     * The name a member access must use is the LOCAL one — an enumeration reading
     * `propertyName` would pass the renamed pin above and fail here.
     */
    @Test
    fun `negative control - the property name of a renamed member is not a namespace member`() {
        readMember("export const { q: renamed } = obj;", "q") should {
            have(any { it.code == 2339 })
        }
    }

    /**
     * The suppression is EXPORT-sensitive, not "any pattern in the body": an
     * unexported destructuring inside the namespace is not a member. Without this
     * the fix could have been a blanket `return true`.
     */
    @Test
    fun `negative control - an unexported destructuring is not a namespace member`() {
        diagnose(
            """
            $prelude
            export namespace NS { const { p } = obj; export const other = 1; }
            const bad: boolean = NS.p;
            """.trimIndent(),
        ) should {
            have(any { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a name declared nowhere is still not a namespace member`() {
        readMember("export const { p } = obj;", "neverDeclared") should {
            have(any { it.code == 2339 })
        }
    }

    /**
     * The OTHER namespace-scoped export set: TS2484 ("Export declaration conflicts
     * with exported declaration of 'x'") is decided by `collectExportedNamesInBody`,
     * a set separate from the one the member access above consults — so this is a
     * second, independently-failing site, and both references report the row. It is
     * a LOST diagnostic rather than a false positive, which is the direction a
     * silence-only pin can never see.
     *
     * The fixture is a SCRIPT (nothing exported at file level): `export { … }` inside
     * a namespace of a MODULE file is TS1194, which would make the shape a grammar
     * error rather than this rule. The column diverges from tsc (we anchor the
     * exported name, tsc the local one) for the PLAIN shape too — pre-existing and
     * unrelated — so the pin names the code and message, not the span.
     */
    @Test
    fun `a namespace-scoped pattern leaf participates in the export-conflict rule`() {
        diagnose(
            """
            $prelude
            namespace NB { export const { p } = obj; }
            namespace NB { const s = 1; export { s as p }; }
            """.trimIndent(),
        ) should {
            have(any {
                it.code == 2484 &&
                    it.message == "Export declaration conflicts with exported declaration of 'p'."
            })
        }
    }

    /**
     * Its control: the same conflict over an ordinary `export const` already fired
     * before (CHK.99), so the row above is the pattern leaf JOINING a rule rather
     * than a new rule being invented.
     */
    @Test
    fun `control - the export-conflict rule already applied to a plain exported const`() {
        diagnose(
            """
            $prelude
            namespace NC { export const p = obj.p; }
            namespace NC { const s = 1; export { s as p }; }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2484 })
        }
    }

    /**
     * And the negative direction, so the rule is not "any two namespace blocks
     * conflict": a non-colliding re-export name is silent.
     */
    @Test
    fun `negative control - a non-colliding export name is not a conflict`() {
        diagnose(
            """
            $prelude
            namespace ND { export const { p } = obj; }
            namespace ND { const s = 1; export { s as zz }; }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2484 })
        }
    }

    /**
     * The plain control the whole family is graded against: an ordinary
     * `export const` member behaved correctly throughout, so a red pin here means
     * the change broke something rather than that it failed to help.
     */
    @Test
    fun `control - a plain exported const member is unaffected`() {
        readMember("export const plainNs = 1;", "plainNs") should {
            have(none { it.code == 2339 })
            have(any { it.code == 2322 })
        }
    }
}
