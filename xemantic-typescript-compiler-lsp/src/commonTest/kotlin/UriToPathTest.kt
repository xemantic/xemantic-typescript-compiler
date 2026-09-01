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
import kotlin.test.Test

/** The `file://` URI to path conversion and its percent-decoding. */
class UriToPathTest {

    @Test
    fun `a plain file URI maps to its path`() {
        assert(uriToPath("file:///proj/src/a.ts") == "/proj/src/a.ts")
    }

    @Test
    fun `a localhost authority is dropped`() {
        assert(uriToPath("file://localhost/proj/a.ts") == "/proj/a.ts")
    }

    @Test
    fun `a percent-encoded space decodes`() {
        assert(uriToPath("file:///my%20proj/a.ts") == "/my proj/a.ts")
    }

    @Test
    fun `a contiguous escape run decodes as one UTF-8 sequence`() {
        // %F0%9D%95%8F is U+1D54F - one astral character of four bytes - and
        // must not decode byte-by-byte into four replacement characters.
        val decoded = uriToPath("file:///p/%F0%9D%95%8F.ts")
        assert(decoded == "/p/\uD835\uDD4F.ts")
    }

    @Test
    fun `a non-file scheme answers null`() {
        assert(uriToPath("untitled:Untitled-1") == null)
        assert(uriToPath("https://example.com/a.ts") == null)
    }

    @Test
    fun `a malformed escape passes through literally`() {
        assert(percentDecode("/a%zz/b%2") == "/a%zz/b%2")
    }

    @Test
    fun `mixed literal and escaped segments keep their order`() {
        assert(percentDecode("a%20b%20c") == "a b c")
    }
}
