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
 * Round 468 (Blocker #3): two more return SHAPES route an object literal through
 * the conflated file-local interface check —
 * (1) a TERNARY arm (`return cond ? { node } : undefined` vs `Info | undefined`,
 * tsc fixExpectedComma's getInfo / importTracker's getExportInfo), via
 * checkConditionalReturnBranches;
 * (2) an `&&`-nested right operand (`return sides && { identifiers: sides, … }`,
 * tsc addMissingAwait.ts) — the result is falsy(LEFT) | RIGHT, so the LEFT's
 * nullish members must relate and a definitely-truthy member contributes nothing.
 * Multi-file tests (the conflation needs two module files declaring `interface X`).
 */
class ConflatedObjLitReturnArmsTest {

    @Test
    fun `a ternary-arm object literal checks against the conflated file-local interface`() {
        diagnose(
            """
            // @filename: other.ts
            export interface Info { node: string; extra: number; }
            export const dummy = 1;
            // @filename: main.ts
            interface Info { readonly node: string; }
            export function getInfo(node: string, ok: boolean): Info | undefined {
                return ok ? { node } : undefined;
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a ternary-arm object literal missing the FILE-LOCAL member still fires`() {
        diagnose(
            """
            // @filename: other.ts
            export interface Info { node: string; extra: number; }
            export const dummy = 1;
            // @filename: main.ts
            interface Info { readonly node: string; readonly kind: number; }
            export function getInfo(node: string, ok: boolean): Info | undefined {
                return ok ? { node } : undefined;
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            // `kind` is required by THIS file's own Info — genuinely missing.
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `an and-nested object literal checks against the conflated file-local interface`() {
        diagnose(
            """
            // @filename: other.ts
            export interface Identifiers { original: string; additional: number; }
            export const dummy = 1;
            // @filename: main.ts
            interface Identifiers { identifiers: string[]; isCompleteFix: boolean; }
            export function collect(sides: string[] | undefined): Identifiers | undefined {
                return sides && { identifiers: sides, isCompleteFix: true };
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an and-nested object literal missing the FILE-LOCAL member still fires`() {
        diagnose(
            """
            // @filename: other.ts
            export interface Identifiers { original: string; additional: number; }
            export const dummy = 1;
            // @filename: main.ts
            interface Identifiers { identifiers: string[]; isCompleteFix: boolean; }
            export function collect(sides: string[] | undefined): Identifiers | undefined {
                return sides && { identifiers: sides };
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            // `isCompleteFix` is required by THIS file's own Identifiers — genuinely missing.
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an and-left whose falsy remainder is non-nullish keeps the standard path`() {
        diagnose(
            """
            // @filename: other.ts
            export interface Identifiers { original: string; additional: number; }
            export const dummy = 1;
            // @filename: main.ts
            interface Identifiers { identifiers: string[]; isCompleteFix: boolean; }
            export function collect(count: number): Identifiers | undefined {
                return count && { identifiers: [], isCompleteFix: true };
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            // falsy(number) = 0 survives into the result type — not assignable to
            // `Identifiers | undefined` (matches tsc, which errors here).
            have(any { it.code == 2322 })
        }
    }
}
