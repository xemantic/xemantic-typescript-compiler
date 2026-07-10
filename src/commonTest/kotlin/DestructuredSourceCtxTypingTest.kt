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
 * Pins the round-464 invariant: an assignment target rooted at an
 * object-DESTRUCTURED local resolves its contextual type from the
 * destructuring SOURCE's declared member — so the RHS arrow's params are
 * contextually typed and TS7006 stays silent. tsc's tsbuildPublic.ts:594
 * (`const { parseConfigFileHost } = state;
 * parseConfigFileHost.onUnRecoverableConfigFileDiagnostic = d => …`) is the
 * dashboard shape; a destructured local is B83.5-unbound, so the walker's
 * getTypeOfExpression fallback resolved anyType and the arrow param fired.
 */
class DestructuredSourceCtxTypingTest {

    private val prelude = """
        interface Diag { messageText: string; }
        type DiagnosticReporter = (diagnostic: Diag) => void;
        interface Host { onDiag: DiagnosticReporter; }
        interface St { readonly host: Host; readonly other: number; }
    """.trimIndent() + "\n"

    @Test
    fun `an assignment through a destructured local's fn member contextually types the arrow param`() {
        diagnose(prelude + """
            function parse(state: St): void {
                let diagnostic: Diag | undefined;
                const { host, other } = state;
                host.onDiag = d => diagnostic = d;
                void other;
                void diagnostic;
            }
        """.trimIndent()) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `a renamed destructured element resolves through its property name`() {
        diagnose(prelude + """
            function parse(state: St): void {
                const { host: h } = state;
                h.onDiag = d => { void d.messageText; };
            }
        """.trimIndent()) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - a destructured local from an UNTYPED source keeps TS7006 firing`() {
        diagnose(prelude + """
            function parse(state: St): void {
                let anySource;
                const { host } = anySource;
                host.onDiag = (d) => { void d; };
            }
        """.trimIndent()) should {
            have(any { it.code == 7006 })
        }
    }
}
