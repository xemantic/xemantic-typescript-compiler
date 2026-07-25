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
 * Round 422 (M3.4): the overload arg-check helpers (`allArgumentsMatch` and friends) must
 * flow-narrow a bare Identifier/PropertyAccess argument before matching it against each
 * overload — mirroring B469's single-signature rule. Without it, a guard-narrowed union
 * argument fails EVERY overload and FPs TS2769:
 *
 *   const dir = containingFile ? getDirectoryPath(containingFile) : undefined;  // tsc: OK
 *   if (typeof version === "string") version = new Version(version);           // tsc: OK
 *
 * (tsc's own moduleNameResolver.ts:545 / semver.ts:228 / path.ts:515 shapes.)
 * Narrowing only removes union members, so the fix is suppression-only: an
 * un-narrowed union argument still fails every overload and TS2769 stands.
 */
class OverloadArgFlowNarrowingTest {

    private val overloadedFn = """
        type Path = string & { __pathBrand: any };
        declare function getDirectoryPath(path: Path): Path;
        declare function getDirectoryPath(path: string): string;
    """

    @Test
    fun `ternary truthy guard narrows union arg so an overload matches`() {
        diagnose(
            """
            $overloadedFn
            export function f(containingFile: string | undefined) {
                const dir = containingFile ? getDirectoryPath(containingFile) : undefined;
                return dir;
            }
            """,
        ) should {
            have(none { it.code == 2769 || it.code == 2345 })
        }
    }

    @Test
    fun `logical AND guard narrows union arg so an overload matches`() {
        diagnose(
            """
            $overloadedFn
            export function f(x: string | undefined) {
                return x && getDirectoryPath(x);
            }
            """,
        ) should {
            have(none { it.code == 2769 || it.code == 2345 })
        }
    }

    @Test
    fun `typeof guard narrows union arg for an overloaded constructor`() {
        diagnose(
            """
            declare class Version {
                constructor(text: string);
                constructor(major: number, minor?: number);
            }
            export function f(version: string | Version) {
                if (typeof version === "string") version = new Version(version);
                return version;
            }
            """,
        ) should {
            have(none { it.code == 2769 || it.code == 2345 })
        }
    }

    @Test
    fun `if guard narrows a property-access arg so an overload matches`() {
        diagnose(
            """
            $overloadedFn
            interface Host { file: string | undefined; }
            export function f(host: Host) {
                if (host.file !== undefined) {
                    return getDirectoryPath(host.file);
                }
                return undefined;
            }
            """,
        ) should {
            have(none { it.code == 2769 || it.code == 2345 })
        }
    }

    @Test
    fun `negation guard narrows a boolean arg to false so a literal overload matches`() {
        // `boolean` is not modeled as `true | false`, so the union-gated narrowing can't
        // refine it — the synthetic-literal-union path must. tsc's own
        // parseParametersWorker(flags, allowAmbiguity: true/false) overload pair.
        diagnose(
            """
            declare function worker(flags: number, allowAmbiguity: true): string;
            declare function worker(flags: number, allowAmbiguity: false): string | undefined;
            export function parse(flags: number, allowAmbiguity: boolean): string | undefined {
                if (!allowAmbiguity) {
                    return worker(flags, allowAmbiguity);
                }
                return worker(flags, allowAmbiguity);
            }
            """,
        ) should {
            // `!allowAmbiguity` must narrow boolean to `false` (and the
            // else-continuation to `true`) so the literal overloads match
            have(none { it.code == 2769 || it.code == 2345 })
        }
    }

    @Test
    fun `un-narrowed boolean arg still fails literal-only overloads - TS2769 stands`() {
        diagnose(
            """
            declare function worker(flags: number, allowAmbiguity: true): string;
            declare function worker(flags: number, allowAmbiguity: false): string | undefined;
            export function parse(flags: number, allowAmbiguity: boolean): string | undefined {
                return worker(flags, allowAmbiguity);
            }
            """,
        ) should {
            // an un-narrowed `boolean` matches neither literal overload (tsc errors
            // here too)
            have(any { it.code == 2769 })
        }
    }

    @Test
    fun `un-narrowed union arg still fails every overload - TS2769 stands`() {
        diagnose(
            """
            $overloadedFn
            export function f(x: string | undefined) {
                return getDirectoryPath(x);
            }
            """,
        ) should {
            have(any { it.code == 2769 })
        }
    }

    @Test
    fun `guard on a DIFFERENT variable does not narrow the arg - TS2769 stands`() {
        diagnose(
            """
            $overloadedFn
            export function f(x: string | undefined, y: string | undefined) {
                if (y !== undefined) {
                    return getDirectoryPath(x);
                }
                return undefined;
            }
            """,
        ) should {
            have(any { it.code == 2769 })
        }
    }
}
