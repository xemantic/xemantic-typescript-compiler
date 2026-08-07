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
 * Round 569: a contextually-typed callback parameter whose contextual type is
 * an UN-INFERRED callee type parameter must NOT be registered into the local
 * type map — our inference failed where tsc would have bound the TP to the
 * actual argument's (richer) type, so any member verdict derived from the bare
 * TP (via its constraint, or its absence) is an inference-gap artifact. The
 * verdict must not depend on WHETHER or WHEN the TP's constraint was
 * materialized (the round-568 fourslashImpl `span.prefixText` drift).
 */
class UninferredTpCallbackParamTest {

    private val prelude = """
        interface DocumentSpan {
            fileName: string;
            textSpan: string;
        }
        interface RenameLocation extends DocumentSpan {
            readonly prefixText?: string;
            readonly suffixText?: string;
        }
    """.trimIndent()

    @Test
    fun `un-inferred TP callback param does not FP TS2339 on a member beyond the constraint`() {
        // Two type params defeat the single-TP inference, so the callback's
        // contextual param type stays the bare `T` — the exact fourslashImpl
        // shape where `T extends DocumentSpan` lacks `prefixText` but the
        // actual argument's element type (RenameLocation) has it.
        diagnose(prelude + """

            declare function withEach<T extends DocumentSpan, U>(
                items: readonly T[],
                extra: U,
                cb: (item: T) => void,
            ): void;
            declare const locs: RenameLocation[];
            withEach(locs, "extra", span => {
                const p = span.prefixText;
                const s = span.suffixText;
            });
        """) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `un-inferred unconstrained TP callback param does not FP TS2339`() {
        diagnose(prelude + """

            declare function pick<T, U>(
                items: readonly T[],
                extra: U,
                cb: (item: T) => void,
            ): void;
            declare const locs: RenameLocation[];
            pick(locs, 1, span => {
                const p = span.prefixText;
            });
        """) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - concrete contextual param type still fires TS2339`() {
        // A non-generic callee's callback param IS registered — a genuinely
        // missing member must keep firing.
        diagnose(prelude + """

            declare function each(
                items: readonly DocumentSpan[],
                cb: (item: DocumentSpan) => void,
            ): void;
            declare const spans: DocumentSpan[];
            each(spans, span => {
                const b = span.bogusMember;
            });
        """) should {
            have(any { it.code == 2339 && it.message.contains("bogusMember") })
        }
    }
}
