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
 * Round 429c (M3.1): two argument-typing rules on the call-arg path.
 *
 *  (a) A `string | undefined` UNION arg is LEGAL for an OPTIONAL param
 *      (`configFileName?: string` / a default-initialized param) — tsc's
 *      effective param type unions `undefined` under strict
 *      (getTypeAtPosition). ×7 self-compile (tsc commandLineParser.ts's
 *      `configFileName` threading).
 *  (b) A non-null-asserted arg `x!` types as its nullish-STRIPPED union
 *      (tsc NonNullable) — local to the call-arg path, mirroring the
 *      arithmetic pass's round-415 rule (the global strip was reverted in
 *      round 407). ×5 self-compile (`tryParseJson(host.readFile(p)!)`).
 *  (c) An Identifier arg whose NON-union interface type is guard-narrowed
 *      DOWN (`isSourceFile(x) && isModule(x)` — Node narrows to SourceFile)
 *      substitutes the refined type, relation-gated (generalizes the
 *      round-428b `this`-only branch). ×~25 self-compile.
 */
class OptionalParamUnionArgTest {

    @Test
    fun `string-or-undefined union arg to an optional string param is clean`() {
        diagnose(
            """
            declare function getDefaults(configFileName?: string): object;
            export function parse(configFileName: string | undefined) {
                return getDefaults(configFileName);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `union arg to a default-initialized param is clean`() {
        diagnose(
            """
            declare function getComponents(path: string, currentDirectory?: string): string[];
            export function f(path: string, dir: string | undefined) {
                return getComponents(path, dir);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `non-null-asserted union arg to a required string param is clean`() {
        diagnose(
            """
            declare function readFile(path: string): string | undefined;
            declare function parseJson(text: string): object;
            export function f(path: string) {
                return parseJson(readFile(path)!);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - union arg to a REQUIRED param still fires`() {
        diagnose(
            """
            declare function getDefaults(configFileName: string): object;
            export function parse(configFileName: string | undefined) {
                return getDefaults(configFileName);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - wrong-category union arg to an optional param still fires`() {
        // Stripping undefined leaves `number`, which still fails vs `string` —
        // the optionality rule must not blanket-accept unions.
        diagnose(
            """
            declare function getDefaults(configFileName?: string): object;
            export function parse(x: number | undefined) {
                return getDefaults(x);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - null member is not excused by optionality`() {
        // null is NOT interchangeable with absence: `string | null` vs `string?`
        // keeps firing (only undefined members are stripped).
        diagnose(
            """
            declare function getDefaults(configFileName?: string): object;
            export function parse(x: string | null) {
                return getDefaults(x);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `guard-narrowed interface arg does not FP against the narrower param`() {
        diagnose(
            """
            interface Nd { kind: number; }
            interface SrcFile extends Nd { fileName: string; }
            declare function isSourceFile(node: Nd): node is SrcFile;
            declare function isExternalModule(file: SrcFile): boolean;
            export function isExportingScope(enclosingDeclaration: Nd): boolean {
                return isSourceFile(enclosingDeclaration) && isExternalModule(enclosingDeclaration);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `guard-narrowed if-statement interface arg does not FP`() {
        diagnose(
            """
            interface Nd { kind: number; }
            interface SrcFile extends Nd { fileName: string; }
            declare function isSourceFile(node: Nd): node is SrcFile;
            declare function getSymbol(file: SrcFile): string;
            export function f(node: Nd): string | undefined {
                if (isSourceFile(node)) {
                    return getSymbol(node);
                }
                return undefined;
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    // NOTE (round 429c): the interface-arg narrowing branch EXCLUDES `never`-typed
    // params (`assertType<never>(node)` in an exhaustive-switch default) — a partial
    // case-union refinement would take the union-arg emission path and FP where the
    // declared interface stays silent. That exclusion is pinned by the self-compile
    // by-site diff (3 sites), not a local test: for an IN-FILE resolvable callee tsc
    // itself errors on the non-exhaustive interface shape, and the exhaustive
    // discriminated-union shape needs exhaustiveness narrowing we don't model yet
    // (catalogued M3.4) — neither direction yields a stable local pin.

    @Test
    fun `negative control - un-narrowed interface arg still fires`() {
        diagnose(
            """
            interface Nd { kind: number; }
            interface SrcFile extends Nd { fileName: string; }
            declare function getSymbol(file: SrcFile): string;
            export function f(node: Nd): string {
                return getSymbol(node);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - un-asserted union arg still fires`() {
        diagnose(
            """
            declare function readFile(path: string): string | undefined;
            declare function parseJson(text: string): object;
            export function f(path: string) {
                return parseJson(readFile(path));
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
