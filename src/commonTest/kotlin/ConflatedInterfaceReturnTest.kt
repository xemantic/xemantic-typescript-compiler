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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * Round 445 (Blocker #3, self-compile burn-down): a name X declared as a top-level
 * `interface X` in ≥2 DISTINCT MODULE files merges via `mergeSymbolTable` into one polluted
 * `globals[X]` (the union of every file's members), even though each module's `interface X`
 * is really module-scoped and separate. tsc's own codefixes all declare a private
 * `interface Info` — so `getInfo(): Info | undefined` returning `{ importNode, name,
 * moduleSpecifier }` (matching the FILE-LOCAL Info) looked "missing properties" against the
 * merged union (services TS2322 `Info | undefined` ×11 → 1). The return object-literal
 * assignability path now bails when the target is a conflated interface X, the current file
 * declares its own `interface X`, and the object literal satisfies the file-local X (checked
 * AST-side — the merged symbol's `declarations` list is polluted).
 */
class ConflatedInterfaceReturnTest {

    private fun compile(@Language("typescript") source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "entry.ts").diagnostics

    @Test
    fun `object literal returned to a conflated file-local interface - no TS2322 or TS2739`() {
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            interface Info { foo: number; }
            export function getA(): Info | undefined {
                return { foo: 1 };
            }

            // @Filename: b.ts
            interface Info { bar: string; baz: number; }
            export function getB(): Info | undefined {
                return { bar: "x", baz: 2 };
            }
            """
        ) should {
            have(none { it.code == 2322 })
            have(none { it.code == 2739 })
            have(none { it.code == 2740 })
        }
    }

    @Test
    fun `negative control - object missing a required file-local member still fires`() {
        // Info is conflated (declared in both files), but a.ts's return object is missing its
        // OWN interface's required `bar`, so tsc (and we) must still report it.
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            interface Info { foo: number; bar: number; }
            export function getA(): Info | undefined {
                return { foo: 1 };
            }

            // @Filename: b.ts
            interface Info { qux: string; }
            export function getB(): Info | undefined {
                return { qux: "x" };
            }
            """
        ) should {
            have(any { it.code == 2739 || it.code == 2741 || it.code == 2322 })
        }
    }

    @Test
    fun `negative control - single-file interface missing-property still fires`() {
        // Not conflated (only one declaration) — the normal missing-property path must fire.
        diagnose(
            """
            interface Single { a: number; b: number; }
            function f(): Single {
                return { a: 1 };
            }
            """,
        ) should {
            // The coarse object-literal-vs-interface path reports TS2322 for this shape.
            have(any { it.code == 2322 || it.code == 2739 || it.code == 2741 })
        }
    }

    @Test
    fun `object literal returned to a MULTI-member union with a conflated interface - no TS2322`() {
        // Round 447: tsc's refactor `getInfo(): FunctionInfo | RefactorErrorInfo | undefined` shape.
        // `Fi` is conflated (declared in both files); the return `{ decl, node }` satisfies a.ts's
        // file-local `Fi` — assignable to the union `Fi | Err | undefined` — but the merged (polluted)
        // `Fi` looked "missing properties". The union bail now checks each conflated member exactly.
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            interface Fi { decl: number; node: string; }
            interface Err { error: string; }
            export function getA(x: boolean): Fi | Err | undefined {
                if (x) return { decl: 1, node: "n" };
                return { error: "bad" };
            }

            // @Filename: b.ts
            interface Fi { extra1: number; extra2: string; other: number; }
            export function getB(): Fi | undefined {
                return { extra1: 1, extra2: "y", other: 2 };
            }
            """
        ) should {
            have(none { it.code == 2322 })
            have(none { it.code == 2739 })
            have(none { it.code == 2740 })
        }
    }

    @Test
    fun `negative control - object matching NO union member still fires`() {
        // Firewall for the multi-member union bail: an object that satisfies neither conflated member
        // (excess property present on neither) must still report a relation failure.
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            interface Fi { decl: number; node: string; }
            interface Err { error: string; }
            export function getA(): Fi | Err | undefined {
                return { decl: 1, node: "n", junk: 9 };
            }

            // @Filename: b.ts
            interface Fi { extra1: number; }
            export function getB(): Fi | undefined { return { extra1: 1 }; }
            """
        ) should {
            have(any { it.code == 2322 || it.code == 2739 || it.code == 2741 || it.code == 2353 })
        }
    }
}
