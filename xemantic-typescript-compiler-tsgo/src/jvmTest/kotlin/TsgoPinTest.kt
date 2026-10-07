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
import com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3.Uint128
import kotlin.test.Test

/**
 * The always-on half of the (TSGO.1) gate: a handful of tiny committed fixtures
 * (`src/jvmTest/resources/tsgo-pins/`) through the ported parse → bind → encode, against tsgo's
 * bytes for them, so the ordinary test suite gates the port without the gitignored corpus oracle.
 *
 * Each fixture `X` has `X.bin`, written by tsgo's own pipeline under the stable virtual name
 * `/tsgo-pins/X` (so the bytes do not depend on the checkout's location):
 *
 * ```
 * xemantic-typescript-compiler-goport/oracle-go/build.sh
 * cd xemantic-typescript-compiler-tsgo/src/jvmTest/resources/tsgo-pins
 * ../../../../../build/goport/bin/tsgo-oracle encode [-force] -as /tsgo-pins/X -o X.bin X
 * ```
 *
 * (`-force` for `.mts`/`.cts`/`.mjs`/`.cjs`, as the binary derives it.) The fixtures cover every
 * binder-set node flag (ExportContext, ContainsThis, HasImplicitReturn, HasExplicitReturn,
 * ThisNodeOrAnySubNodesHasError, HasAsyncFunctions, Unreachable), JSX, JSDoc in a `.js` file and
 * non-ASCII text (UTF-16 positions against UTF-8 string offsets). The content hash is computed
 * (`TSGO_ORACLE_REAL_HASH=0` copies it from the expected bytes), exactly as in [OracleParityTest].
 */
class TsgoPinTest {

    private val fixtures = listOf("flow.ts", "this-and-errors.ts", "component.tsx", "jsdoc.js", "unicode.mts")

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/tsgo-pins/$name")) { "missing test resource tsgo-pins/$name" }.use { it.readBytes() }

    private fun le64(b: ByteArray, at: Int): ULong {
        var v = 0uL
        for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toULong() and 0xFFuL)
        return v
    }

    private fun port(name: String, expected: ByteArray): ByteArray {
        val bytes = resource(name)
        val text = String(CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() })
        val options = expected[20].toInt()
        val sf = TsgoPort.parse("/tsgo-pins/$name", text, jsx = options and 1 != 0, force = options and 2 != 0)
        sf.hash = if (TsgoPort.realHash) TsgoPort.hash(text)
        else Uint128(hi = le64(expected, 12), lo = le64(expected, 4))
        com.xemantic.typescript.tsgo.binder.bindSourceFile(sf)
        return TsgoPort.encode(sf)
    }

    @Test
    fun `ported parser binder and encoder reproduce tsgo's bytes for the pin fixtures`() {
        val differing = fixtures.filter { name ->
            val expected = resource("$name.bin")
            !port(name, expected).contentEquals(expected)
        }
        assert(differing.isEmpty())
    }

    @Test
    fun `negative control - the comparison sees a binder flag`() {
        // Parse without binding: flow.ts carries Unreachable/HasExplicitReturn/ExportContext bits,
        // so the unbound bytes must differ — otherwise the pin above could not see a lost binder.
        val name = "flow.ts"
        val expected = resource("$name.bin")
        val bytes = resource(name)
        val text = String(CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() })
        val sf = TsgoPort.parse("/tsgo-pins/$name", text, jsx = false, force = false)
        sf.hash = Uint128(hi = le64(expected, 12), lo = le64(expected, 4))
        val unbound = TsgoPort.encode(sf)
        val differs = !unbound.contentEquals(expected)
        assert(differs)
    }
}
