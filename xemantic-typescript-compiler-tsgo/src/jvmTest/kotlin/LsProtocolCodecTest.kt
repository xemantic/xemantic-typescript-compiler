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

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.tsgo.api.xtscMarshal
import com.xemantic.typescript.tsgo.api.xtscResolveClientCapabilities
import com.xemantic.typescript.tsgo.lsp.lsproto.IntegerOrString
import com.xemantic.typescript.tsgo.runtime.GoBox
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.goBytesToString
import com.xemantic.typescript.tsgo.runtime.goStringToBytes
import kotlin.test.Test

/**
 * (TSGO.4-a) the LSP protocol codec of the port (`lsproto`'s reflective struct codec over the json and
 * reflect shims): what the language server unmarshals from a client and marshals back. The whole
 * protocol surface is graded by LsParityTest against the tsgo binary; these pins fix the codec paths
 * the first differential run found broken.
 */
class LsProtocolCodecTest {

    private fun bytes(s: String) = goStringToBytes(GoString.fromUtf16(s))

    @Test
    fun `initialize capabilities are unmarshalled and resolved as the server resolves them`() {
        val (caps, err) = onGoStack {
            xtscResolveClientCapabilities(
                bytes(
                    """{"processId":1,"rootUri":null,"capabilities":{"general":{"positionEncodings":["utf-16"]},""" +
                        """"textDocument":{"hover":{"contentFormat":["markdown","plaintext"]},"definition":{"linkSupport":true},""" +
                        """"completion":{"completionItem":{"snippetSupport":true,"tagSupport":{"valueSet":[1]}},"completionList":{"itemDefaults":["commitCharacters","editRange"]}}}}}""",
                ),
            )
        }
        assert(err == null)
        val hover = caps!!.textDocument.hover.contentFormat
        assert(hover.len == 2 && GoString.toUtf16(hover[0].value) == "markdown")
        assert(caps.textDocument.definition.linkSupport)
        assert(caps.textDocument.completion.completionItem.snippetSupport)
        assert(caps.textDocument.completion.completionList.itemDefaults.len == 2)
    }

    @Test
    fun `an integer diagnostic code marshals as its number`() {
        val code = IntegerOrString(integer = GoBox(6133))
        val (b, err) = onGoStack { xtscMarshal(code) }
        assert(err == null)
        assert(goBytesToString(b) == "6133")
    }
}
