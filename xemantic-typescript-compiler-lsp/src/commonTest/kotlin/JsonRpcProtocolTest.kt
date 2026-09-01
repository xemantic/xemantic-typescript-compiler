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

package com.xemantic.typescript.compiler.lsp

import com.xemantic.kotlin.test.assert
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

/**
 * The JSON-RPC policy decisions of [XtscLanguageServer.handleMessage], each one
 * the class KDoc documents pinned here — no streams, no project.
 */
class JsonRpcProtocolTest {

    private fun response(text: String?) = Json.parseToJsonElement(text ?: "null").jsonObject

    private fun errorCode(text: String?): Int? = response(text).obj("error")?.int("code")

    @Test
    fun `an unknown method with an id answers MethodNotFound echoing the id`() {
        val server = XtscLanguageServer()
        val reply = server.handleMessage("""{"jsonrpc":"2.0","id":7,"method":"nope/nothing"}""")
        assert(reply != null)
        val obj = response(reply)
        val code = obj.obj("error")?.int("code")
        val id = obj.int("id")
        assert(code == METHOD_NOT_FOUND)
        assert(id == 7)
    }

    @Test
    fun `an unknown notification is ignored silently`() {
        val server = XtscLanguageServer()
        val reply = server.handleMessage("""{"jsonrpc":"2.0","method":"nope/nothing"}""")
        assert(reply == null)
    }

    @Test
    fun `unparseable JSON answers ParseError with a null id`() {
        val server = XtscLanguageServer()
        val reply = server.handleMessage("""{"jsonrpc": oops""")
        val obj = response(reply)
        val code = obj.obj("error")?.int("code")
        val idIsNull = obj["id"] is JsonNull
        assert(code == PARSE_ERROR)
        assert(idIsNull)
    }

    @Test
    fun `a string id is echoed verbatim`() {
        val server = XtscLanguageServer()
        val reply = server.handleMessage("""{"jsonrpc":"2.0","id":"req-abc","method":"shutdown"}""")
        val obj = response(reply)
        val id = obj.string("id")
        val resultIsNull = obj["result"] is JsonNull
        assert(id == "req-abc")
        assert(resultIsNull)
    }

    @Test
    fun `every response carries the jsonrpc version`() {
        val server = XtscLanguageServer()
        val success = server.handleMessage("""{"jsonrpc":"2.0","id":1,"method":"shutdown"}""")
        val version = response(success).string("jsonrpc")
        assert(version == "2.0")
    }

    @Test
    fun `a request after shutdown answers InvalidRequest`() {
        val server = XtscLanguageServer()
        server.handleMessage("""{"jsonrpc":"2.0","id":1,"method":"shutdown"}""")
        val reply = server.handleMessage("""{"jsonrpc":"2.0","id":2,"method":"initialize"}""")
        val code = errorCode(reply)
        assert(code == INVALID_REQUEST)
    }

    @Test
    fun `a notification after shutdown is dropped without a response`() {
        val server = XtscLanguageServer()
        server.handleMessage("""{"jsonrpc":"2.0","id":1,"method":"shutdown"}""")
        val reply = server.handleMessage("""{"jsonrpc":"2.0","method":"initialized"}""")
        assert(reply == null)
    }

    @Test
    fun `a body that is not an object answers InvalidRequest`() {
        val server = XtscLanguageServer()
        val code = errorCode(server.handleMessage("""[1,2,3]"""))
        assert(code == INVALID_REQUEST)
    }

    @Test
    fun `a request without a method answers InvalidRequest echoing the id`() {
        val server = XtscLanguageServer()
        val obj = response(server.handleMessage("""{"jsonrpc":"2.0","id":9}"""))
        val code = obj.obj("error")?.int("code")
        val id = obj.int("id")
        assert(code == INVALID_REQUEST)
        assert(id == 9)
    }

    @Test
    fun `an explicit null id is treated as a notification`() {
        val server = XtscLanguageServer()
        val reply = server.handleMessage("""{"jsonrpc":"2.0","id":null,"method":"nope/nothing"}""")
        assert(reply == null)
    }

    @Test
    fun `hover before initialize answers a null result rather than an error`() {
        val server = XtscLanguageServer()
        val reply = server.handleMessage(
            """{"jsonrpc":"2.0","id":3,"method":"textDocument/hover","params":""" +
                """{"textDocument":{"uri":"file:///nowhere/a.ts"},""" +
                """"position":{"line":0,"character":0}}}""",
        )
        val obj = response(reply)
        val resultIsNull = obj["result"] is JsonNull
        val hasError = obj.obj("error") != null
        assert(resultIsNull)
        assert(!hasError)
    }
}
