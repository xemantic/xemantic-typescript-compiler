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
 * M4.9 (round 681): a PARAMETER must shadow a same-named namespace that reached
 * `globals` from an ambient module body.
 *
 * `checkMemberAccessMissing` bails to the locally-known type only for names in
 * `currentShadowedNames`, which [applyBodyLocalShadowing] fills for body-local
 * VAR declarations and deliberately excludes parameter names (a var redeclaring
 * a same-function param is a redeclaration, not a shadow). So a parameter
 * colliding with an outer binding never took that bail and fell through to the
 * symbol-based branches, which resolve the receiver through globals.
 *
 * tsc's own `function formatJSDocLink(link: JSDocLink | …)` hit it the moment
 * `@types/node` entered the program (M4.8): `fs.d.ts` declares
 * `export namespace link`, our binder merges ambient-module locals into globals,
 * and the parameter lost — 18 diagnostics reading *"Property 'kind' does not
 * exist on type 'typeof link'"*. A LOCAL of the same name was already correct,
 * which is what localised the fault to parameters.
 */
class ParameterShadowsAmbientNamespaceTest {

    /** An ambient module whose body declares `namespace link`. */
    private val ambient = """
        // @filename: fsx.d.ts
        declare module "fsx" {
            export function link(a: string): void;
            export namespace link { function __promisify__(a: string): void; }
        }
    """.trimIndent() + "\n"

    private val shapes = """
        // @filename: main.ts
        interface A { kind: 1; text: string }
        interface B { kind: 2; text: string }
    """.trimIndent() + "\n"

    private fun check(body: String) =
        diagnose(ambient + shapes + body.trimIndent(), "// @strict: true")

    @Test
    fun `a parameter named like an ambient-module namespace resolves to the parameter`() {
        check("export function f(link: A | B) { return link.text; }") should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `the union discriminant on such a parameter still resolves`() {
        check(
            """
            export function f(link: A | B) {
                return link.kind === 1 ? "a" : "b";
            }
            """
        ) should { have(none { it.code == 2339 }) }
    }

    @Test
    fun `a NON-union parameter of the same name resolves too`() {
        check("export function f(link: A) { return link.text; }") should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `an arrow parameter of the same name resolves`() {
        check("export const f = (link: A) => link.text;") should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a LOCAL of the same name still resolves (control - this always worked)`() {
        check(
            """
            export function f() {
                const link: A = { kind: 1, text: "x" };
                return link.text;
            }
            """
        ) should { have(none { it.code == 2339 }) }
    }

    // ── negative controls: a genuinely absent member must still report ─────

    @Test
    fun `negative control - a genuinely missing member on the parameter still reports`() {
        check("export function f(link: A) { return link.nope; }") should {
            have(any { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a genuinely missing member on a NON-colliding parameter reports`() {
        check("export function f(other: A) { return other.nope; }") should {
            have(any { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - the namespace itself is untouched where there is no local`() {
        // The bail is keyed on a locally-known type for the receiver, so a
        // top-level access through the namespace cannot reach it and namespace
        // semantics are unchanged.
        //
        // (Separately: we do not currently report a missing member on a global
        // namespace at all — verified pre-existing, unrelated to this fix, and
        // deliberately NOT asserted here so this pin cannot drift into claiming
        // credit for behaviour it does not control.)
        check("export function f(link: A) { return link.kind; }") should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a member missing on BOTH the parameter and the namespace reports`() {
        // The sharp case for the gate: the name collides with the namespace AND the
        // member is absent from the parameter's type, so the suppression must not fire.
        check("export function f(link: A | B) { return link.absent; }") should {
            have(any { it.code == 2339 })
        }
    }
}
