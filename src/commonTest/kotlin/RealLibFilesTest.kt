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

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import kotlin.test.Test

/**
 * M2.1(a) (round 390): [RealLibFiles] ships the real TypeScript lib `.d.ts`
 * sources (the non-DOM ES set) as generated Kotlin. The generator chunks every
 * file into string literals of ≤ 60,000 modified-UTF-8 value bytes (a JVM
 * class-file string constant caps at 65,535) and reassembles them at runtime —
 * these tests pin the reassembly invariant beyond "it compiles": the multi-chunk
 * es5 content must be complete, ordered, and byte-faithful (CRLF preserved).
 */
class RealLibFilesTest {

    @Test
    fun `ships the full non-DOM lib set keyed by bare lib names`() {
        val files = RealLibFiles.files
        // 100 files at the current pin; assert a floor so a pin bump doesn't
        // spuriously fail, plus exact membership of the load-bearing entries.
        have(files.size >= 90)
        for (key in listOf(
            "es5", "es2015", "es2015.core", "es2015.iterable", "es2015.symbol.wellknown",
            "es2020.bigint", "es2024.collection", "esnext", "decorators", "decorators.legacy",
        )) {
            have(key in files)
        }
        // Keys are bare lib names — no extension, no DOM/host libs.
        have(files.keys.none { it.endsWith(".d.ts") })
        have(files.keys.none { it.startsWith("dom") || it.startsWith("webworker") || it.startsWith("scripthost") })
        have(files.values.none { it.isBlank() })
    }

    @Test
    fun `es5 reassembles across chunks - complete ordered and CRLF-faithful`() {
        val es5 = RealLibFiles.files.getValue("es5")
        // es5.d.ts is ~218 KB — far past the 65,535-byte single-constant cap, so a
        // correct value here PROVES multi-chunk runtime reassembly.
        have(es5.length > 65_535)
        // First chunk: the file opens with its lib-reference directives.
        have(es5.startsWith("/// <reference lib=\"decorators\" />\r\n"))
        // Middle chunks: declarations that live past the first 60 KB.
        have("interface RegExpConstructor" in es5)
        have("interface Array<T>" in es5)
        // Last chunk: the file's final declaration.
        have(es5.trimEnd().endsWith("}"))
        have("toLocaleTimeString(locales?: string | string[], options?: Intl.DateTimeFormatOptions): string;" in es5)
        // CRLF line endings preserved byte-faithfully (no LF-only lines).
        assert(es5.count { it == '\n' } - es5.windowed(2).count { it == "\r\n" } == 0)
    }

    @Test
    fun `lib pieces carry their reference directives for the DAG resolver`() {
        // M2.1(b) will expand lib.es2015 -> es5 + es2015.* via these directives;
        // pin that the shipped text actually carries them.
        val es2015 = RealLibFiles.files.getValue("es2015")
        have("/// <reference lib=\"es5\" />" in es2015)
        have("/// <reference lib=\"es2015.core\" />" in es2015)
        have("/// <reference lib=\"es2015.iterable\" />" in es2015)
    }
}
