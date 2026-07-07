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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 436e: the OVERLOAD arg-check helpers mirror two single-sig rules —
 * (a) a `X | undefined` union arg is legal for an OPTIONAL param
 * (unionArgOkForOptionalParam: tsc program.ts's
 * `(Program | T).getOptionsDiagnostics(cancellationToken)` — the
 * union-receiver synthesized overload pair failed BOTH ways → TS2769 ×6);
 * (b) an arg carrying an un-inferred TP is unjudgeable. Suppression-only.
 */
class OverloadOptionalUnionArgTest {

    @Test
    fun `optional param accepts a union arg across union-receiver overloads`() {
        // The program.ts shape: union receiver, optional params, union args.
        diagnose(
            """
            interface CancellationToken2 { throwIfCancellationRequested(): void }
            interface Diagnostic2 { code: number }
            interface WriteFileCallback2 { (fileName: string, text: string): void }
            interface Program2 {
                getOptionsDiagnostics(cancellationToken?: CancellationToken2): readonly Diagnostic2[];
                emitBuildInfo(writeFile?: WriteFileCallback2, cancellationToken?: CancellationToken2): { x?: number };
            }
            interface BuilderProgram2 {
                getProgram(): Program2;
                getOptionsDiagnostics(cancellationToken?: CancellationToken2): readonly Diagnostic2[];
                emitBuildInfo(writeFile?: WriteFileCallback2, cancellationToken?: CancellationToken2): { x?: number };
            }
            function handle<T extends BuilderProgram2>(
                program: Program2 | T,
                cancellationToken: CancellationToken2 | undefined,
                writeFile: WriteFileCallback2 | undefined,
            ) {
                const a = program.getOptionsDiagnostics(cancellationToken);
                const r = program.emitBuildInfo(writeFile, cancellationToken);
                return [a, r];
            }
            """
        ) should {
            have(none { it.code == 2769 || it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a union arg whose non-undefined member also fails still reports`() {
        // 'string | undefined' vs the optional CancellationToken2 param must
        // still report across the union-receiver overloads.
        diagnose(
            """
            interface CancellationToken2 { throwIfCancellationRequested(): void }
            interface Program2 {
                getOptionsDiagnostics(cancellationToken?: CancellationToken2): void;
            }
            interface BuilderProgram2 {
                getProgram(): Program2;
                getOptionsDiagnostics(cancellationToken?: CancellationToken2): void;
            }
            declare const program: Program2 | BuilderProgram2;
            declare const bogus: string | undefined;
            program.getOptionsDiagnostics(bogus);
            """
        ) should {
            have(any { it.code == 2769 || it.code == 2345 })
        }
    }
}
