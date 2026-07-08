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
 * Round 444 (Blocker #3, self-compile burn-down): an interface whose heritage base is a
 * NAMESPACE-QUALIFIED name where the qualifier is a MODULE namespace-import alias
 * (`RefactorContext extends textChanges.TextChangesContext`, with
 * `import * as textChanges from "./textChanges.js"`) did not inherit the base's members —
 * `resolveAlias` does not resolve an `import * as NS` / `export * as NS` namespace alias to a
 * module with `.exports` (the alias's declaration is the NamespaceImport node, which none of
 * `resolveAlias`'s branches handle), so `resolveHeritageBaseSymbol`'s exports lookup returned null
 * and the base resolved to `errorType`. Inherited members were then invisible → FP TS2339
 * (services.ts `RefactorContext`/`CodeFixContextBase` extend `textChanges.TextChangesContext`,
 * `ctx.host` ×17 on the services profile).
 *
 * Fix: `getTypeFromBaseTypeExpression` falls back to the merged-global LAST-SEGMENT name
 * (`globals[baseExpr.name.text]`) when `resolveHeritageBaseSymbol` fails — exactly what
 * `getTypeFromTypeReference` already does for the same qualified shape in ANNOTATION position
 * (which is why a direct `ctx: textChanges.TextChangesContext` annotation resolved while the
 * heritage base did not). Suppression-only: resolving a base only ADDS inherited members.
 */
class NamespaceQualifiedBaseInheritanceTest {

    private fun compile(source: String, primary: String = "consumer.ts") =
        TypeScriptCompiler().compile(source.trimIndent(), primary).diagnostics

    @Test
    fun `inherited member from a namespace-import-aliased base is resolved - no TS2339`() {
        compile(
            """
            // @strict: true
            // @module: nodenext
            // @moduleResolution: nodenext

            // @Filename: textChanges.ts
            export interface TextChangesContext {
                host: string;
                formatContext: number;
            }

            // @Filename: consumer.ts
            import * as textChanges from "./textChanges.js";
            export interface RefactorContext extends textChanges.TextChangesContext {
                file: string;
            }
            export function use(ctx: RefactorContext): string {
                return ctx.host;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `namespace-import-aliased base resolved through an 'export star as' barrel - no TS2339`() {
        // Mirrors tsc's `_namespaces/ts.ts` (`export * as textChanges from "./ts.textChanges.js"`).
        compile(
            """
            // @strict: true
            // @module: nodenext
            // @moduleResolution: nodenext

            // @Filename: textChanges.ts
            export interface TextChangesContext {
                host: string;
                formatContext: number;
            }

            // @Filename: ns.ts
            export * as textChanges from "./textChanges.js";

            // @Filename: consumer.ts
            import { textChanges } from "./ns.js";
            export interface CodeFixContextBase extends textChanges.TextChangesContext {
                sourceFile: string;
            }
            export function use(ctx: CodeFixContextBase): string {
                return ctx.host;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `the own member of a derived interface also resolves - no TS2339`() {
        compile(
            """
            // @strict: true
            // @module: nodenext
            // @moduleResolution: nodenext

            // @Filename: textChanges.ts
            export interface TextChangesContext {
                host: string;
            }

            // @Filename: consumer.ts
            import * as textChanges from "./textChanges.js";
            export interface RefactorContext extends textChanges.TextChangesContext {
                file: string;
            }
            export function use(ctx: RefactorContext): string {
                return ctx.host + ctx.file;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `the fallback does not disable missing-member checking on a base-less interface - TS2339`() {
        // Firewall: the fallback is scoped to a QUALIFIED heritage base. A base-less interface
        // still reports a genuinely-missing member — proving the change did not globally suppress
        // the missing-member check. (An interface WITH a resolvable base is conservatively skipped
        // by the pre-existing "has base types" FP-avoidance, which is orthogonal to this fix.)
        diagnose(
            """
            interface Widget { name: string; }
            export function f(w: Widget): string { return w.nonexistent; }
            """
        ) should {
            have(any { it.code == 2339 && it.message.contains("nonexistent") })
        }
    }
}
