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
 * Round 459: a NUMERIC-literal element access on a UNION-OF-TUPLES receiver
 * (`value[1]` where value: `[A] | [A, B]`) resolves per-member in tsc with
 * `undefined` for the out-of-bounds members — never TS2339 as long as SOME
 * member has the index. tsc's toBuilderFileEmit (builder.ts:2242) reads
 * `value[1] || BuilderFileEmit.Dts` after an `isNumber(value)` guard.
 *
 * The bail tests the NARROWED receiver (the guard removes the non-tuple
 * member), mirroring the TS2339 emission path itself.
 */
class TupleUnionElementAccessTest {

    @Test
    fun `index valid for one tuple member of a union - no TS2339`() {
        diagnose("""
            const enum BuilderFileEmit { None = 0, Js = 1, Dts = 2 }
            type FileId = number & { __brand: any };
            type PendingEmit = FileId | [fileId: FileId] | [fileId: FileId, emitKind: BuilderFileEmit];
            declare function isNumber(x: unknown): x is number;
            function toBuilderFileEmit(value: PendingEmit, fullEmitForOptions: BuilderFileEmit): BuilderFileEmit {
                return isNumber(value) ? fullEmitForOptions : value[1] || BuilderFileEmit.Dts;
            }
        """.trimIndent()) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a non-tuple union member missing the numeric prop still fires TS2339`() {
        diagnose("""
            interface A { a: string; }
            interface B { 1: string; other: number; }
            function neg(v: A | B) {
                return v[1];
            }
        """.trimIndent()) should {
            have(any { it.code == 2339 })
        }
    }
}
