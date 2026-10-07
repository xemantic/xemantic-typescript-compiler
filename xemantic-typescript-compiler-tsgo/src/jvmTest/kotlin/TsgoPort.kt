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

import com.xemantic.typescript.tsgo.ast.ExternalModuleIndicatorOptions
import com.xemantic.typescript.tsgo.ast.SourceFile
import com.xemantic.typescript.tsgo.ast.SourceFileParseOptions
import com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3.Uint128
import com.xemantic.typescript.tsgo.tspath.Path

/** The ported pipeline the parity tests drive: tsgo's `parsecache` parse, the binder, the API encoder. */
object TsgoPort {

    @Volatile private var initialized = false

    /** Go's package initialization for the packages the pipeline reaches (`parser` installs the JSDoc hook). */
    @Synchronized
    fun init() {
        if (initialized) return
        com.xemantic.typescript.tsgo.parser.goInitPackage()
        initialized = true
    }

    /** `parser.ParseSourceFile` with the options tsgo's project system derives for [fileName]. */
    fun parse(fileName: String, text: String, jsx: Boolean, force: Boolean): SourceFile {
        init()
        val opts = SourceFileParseOptions(
            fileName = fileName,
            path = Path(fileName),
            externalModuleIndicatorOptions = ExternalModuleIndicatorOptions(jsx = jsx, force = force),
        )
        val kind = com.xemantic.typescript.tsgo.core.getScriptKindFromFileName(fileName)
        return com.xemantic.typescript.tsgo.parser.parseSourceFile(opts, text, kind)!!
    }

    /** parse → `file.Hash = xxh3.HashString128(text)` → `binder.BindSourceFile` → encode: what `--api getSourceFile` answers. */
    fun bound(fileName: String, text: String, jsx: Boolean, force: Boolean): ByteArray {
        val sf = parse(fileName, text, jsx, force)
        sf.hash = hash(text)
        com.xemantic.typescript.tsgo.binder.bindSourceFile(sf)
        return encode(sf)
    }

    fun encode(sf: SourceFile): ByteArray {
        val r = com.xemantic.typescript.tsgo.api.encoder.encodeSourceFile(sf)
        check(r.third == null) { "encode error: ${r.third!!.error()}" }
        val out = r.first
        return ByteArray(out.len) { out[it].toByte() }
    }

    /** `xxh3.HashString128` of the decoded text (`project/parsecache.go`: `file.Hash = fh.Hash()`). */
    fun hash(text: String): Uint128 = com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3.hashString128(text)

    /** Whether the header hash is computed ([hash], the default) or copied from the expected bytes (`TSGO_ORACLE_REAL_HASH=0`). */
    val realHash: Boolean get() = System.getenv("TSGO_ORACLE_REAL_HASH") != "0"
}
