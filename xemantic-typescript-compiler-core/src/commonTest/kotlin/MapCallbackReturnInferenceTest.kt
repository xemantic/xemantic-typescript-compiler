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
 * Round 466 (Blocker #2): map-callback return inference — the chain that cleared
 * tsc builder.ts:2390 (`arrayToMap(diagnostics, value => toFilePath(value[0]), …)`
 * must infer `K := Path` through a nested fn whose return flows from
 * `fileNames?.map(toPathInBuildInfoDirectory)`, an AMBIGUOUS 2-declaration nested
 * callback). Pins:
 *  - a NAMED-function callback arg binds a generic method's return TP from the
 *    fn's return type (annotated or single-return-inferred),
 *  - ReadonlyArray.map is generic (embedded-lib change),
 *  - a call to a UNIQUE body-nested fn resolves inside inference body typing,
 *  - an AMBIGUOUS nested-fn name resolves only when ALL declarations agree,
 *  - a `K | undefined` callback return position binds K,
 *  - a branded intersection (`string & { brand }`) is an acceptable candidate,
 *  - a heterogeneous ARRAY-LITERAL body contributes NO candidate (tuple targets).
 */
class MapCallbackReturnInferenceTest {

    private val pathPrelude = """
        type Path = string & { __pathBrand: any };
        declare function toPath(fileName: string): Path;
    """.trimIndent()

    @Test
    fun `a named top-level fn map callback binds U to its return type`() {
        diagnose(
            pathPrelude + """

            function convert(s: string) { return toPath(s); }
            export function f(names: string[]) {
                const paths = names.map(convert);
                const bad: number[] = paths;
                return bad;
            }
            """
        ) should {
            // Precise inference: Path[] is NOT assignable to number[].
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `ReadonlyArray map is generic - a readonly receiver infers the element too`() {
        diagnose(
            pathPrelude + """

            function convert(s: string) { return toPath(s); }
            export function f(names: readonly string[]) {
                const paths = names.map(convert);
                const bad: number[] = paths;
                return bad;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `a call to a unique nested fn types inside an inference body`() {
        diagnose(
            pathPrelude + """

            declare function pick<K>(makeKey: (value: string) => K | undefined): K[];
            export function f() {
                function conv(s: string): Path { return toPath(s); }
                const r = pick(value => conv(value));
                const bad: number[] = r;
                return bad;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `the builder-ts arrayToMap chain infers K through the nested-fn element access`() {
        diagnose(
            pathPrelude + """

            type FileId = number & { __fileIdBrand: any };
            interface ReusableDiagnostic { msg: string; }
            type EmitDiag = [fileId: FileId, diagnostics: readonly ReusableDiagnostic[]];
            interface BuildInfo { fileNames: readonly string[]; }
            declare function arrayToMap<K, V>(array: readonly V[], makeKey: (value: V) => K | undefined): Map<K, V>;
            declare function arrayToMap<K, V1, V2>(array: readonly V1[], makeKey: (value: V1) => K | undefined, makeValue: (value: V1) => V2): Map<K, V2>;
            declare function arrayToMap<T>(array: readonly T[], makeKey: (value: T) => string | undefined): Map<string, T>;
            declare function arrayToMap<T, U>(array: readonly T[], makeKey: (value: T) => string | undefined, makeValue: (value: T) => U): Map<string, U>;

            export function outer(buildInfo: BuildInfo) {
                const filePaths = buildInfo.fileNames?.map(toPathInBuildInfoDirectory);

                function toPathInBuildInfoDirectory(path: string) {
                    return toPath(path);
                }

                function toFilePath(fileId: FileId) {
                    return filePaths[fileId - 1];
                }

                function toPerFileEmitDiagnostics(diagnostics: readonly EmitDiag[] | undefined): Map<Path, readonly ReusableDiagnostic[]> | undefined {
                    return diagnostics && arrayToMap(diagnostics, value => toFilePath(value[0]), value => value[1]);
                }
                return toPerFileEmitDiagnostics;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `ambiguous nested fns that AGREE on the return type still resolve`() {
        diagnose(
            pathPrelude + """

            export function a(names: string[]) {
                function conv(s: string) { return toPath(s); }
                const bad: number[] = names.map(conv);
                return bad;
            }
            export function b(names: string[]) {
                function conv(s: string): Path { return toPath(s); }
                const bad: number[] = names.map(conv);
                return bad;
            }
            """
        ) should {
            // Both `conv` declarations return Path — the candidate binds and both
            // sites report Path[] vs number[].
            have(count { it.code == 2322 } == 2)
        }
    }

    @Test
    fun `negative control - ambiguous nested fns that DISAGREE stay un-inferred`() {
        diagnose(
            pathPrelude + """

            export function a(names: string[]) {
                function conv(s: string): Path { return toPath(s); }
                const bad: number[] = names.map(conv);
                return bad;
            }
            export function b(names: string[]) {
                function conv(s: string): string { return s; }
                const bad: number[] = names.map(conv);
                return bad;
            }
            """
        ) should {
            // Conflicting return types → no all-agree resolution → U stays
            // un-inferred (any[]) → neither site can prove a mismatch.
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a heterogeneous array-literal body contributes no candidate`() {
        diagnose(
            """
            type A = number & { __aBrand: any };
            type B = number & { __bBrand: any };
            declare function toA(s: string): A;
            declare function toB(s: string): B;
            export function f(keys: string[]) {
                // tsc contextually tuple-types the array literal; binding
                // U := (A | B)[] would FP against the tuple target.
                const pairs: [A, B][] = keys.map(key => [toA(key), toB(key)]);
                return pairs;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a concrete-param callback with K-or-undefined return binds K from the body`() {
        diagnose(
            pathPrelude + """

            declare function pick<K>(makeKey: (value: string) => K | undefined): K[];
            export function f() {
                const r = pick(value => toPath(value));
                const bad: number[] = r;
                return bad;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
