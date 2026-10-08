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
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test

/**
 * (TSGO.4-a) end to end: the SHIPPED entry point ([main] in XtscLspMain.kt) in a child JVM, spoken to over
 * its stdin/stdout exactly as an editor does — initialize, didOpen of a file on disk, hover, a pushed
 * diagnostic, shutdown, exit — so the stdio framing, the disk project lookup and tsgo's language service
 * are exercised together, and nothing but protocol frames may reach stdout.
 */
class XtscLspStdioSmokeTest {

    private fun frame(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        return "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII) + body
    }

    private fun readFrame(input: InputStream): String? {
        var length = -1
        while (true) {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return null
                if (c == '\n'.code) break
                if (c != '\r'.code) line.append(c.toChar())
            }
            if (line.isEmpty()) break
            val (k, v) = line.split(':', limit = 2)
            if (k.trim().equals("Content-Length", ignoreCase = true)) length = v.trim().toInt()
        }
        return String(input.readNBytes(length), Charsets.UTF_8)
    }

    @Test
    fun `the stdio server answers an editor session`() {
        val dir = Files.createTempDirectory("xtsc-lsp-smoke").toFile()
        try {
            File(dir, "tsconfig.json").writeText("""{ "compilerOptions": { "strict": true }, "include": ["*.ts"] }""")
            val text = "const abc = 1;\nexport const x: number = \"s\";\n"
            File(dir, "a.ts").writeText(text)
            val uri = File(dir, "a.ts").toURI().toString().replaceFirst("file:/", "file:///")
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process = ProcessBuilder(java, "-Xss1g", "-cp", System.getProperty("java.class.path"), "com.xemantic.typescript.compiler.lsp.XtscLspMainKt")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val out = process.outputStream
            fun send(json: String) {
                out.write(frame(json))
                out.flush()
            }
            send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":"${dir.toURI()}","capabilities":{}}}""")
            send("""{"jsonrpc":"2.0","method":"initialized","params":{}}""")
            send("""{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"$uri","languageId":"typescript","version":1,"text":${kotlinx.serialization.json.JsonPrimitive(text)}}}}""")
            send("""{"jsonrpc":"2.0","id":2,"method":"textDocument/hover","params":{"textDocument":{"uri":"$uri"},"position":{"line":0,"character":6}}}""")
            send("""{"jsonrpc":"2.0","id":3,"method":"shutdown"}""")
            send("""{"jsonrpc":"2.0","method":"exit"}""")
            out.close()
            val frames = generateSequence { readFrame(process.inputStream) }.toList()
            val exit = process.waitFor()
            assert(exit == 0)
            val hover = frames.single { "\"id\":2" in it }
            assert("const abc: 1" in hover)
            val push = frames.single { "publishDiagnostics" in it }
            assert("\"code\":2322" in push && "Type 'string' is not assignable to type 'number'." in push)
            assert(frames.any { "\"id\":1" in it && "hoverProvider" in it })
        } finally {
            dir.deleteRecursively()
        }
    }
}
