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

package com.xemantic.typescript.compiler.externals

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.SourceFileEntry
import kotlin.test.Test

/**
 * (EXT.7) THE SECOND FIXTURE-LADDER RUNG: the generator over the REAL
 * `smol-toml@1.7.1` type declarations — SEVEN files with relative `.js`
 * imports between them — gated by the metadata compile.
 *
 * The fixtures below are the verbatim `dist` declaration files of the `smol-toml`
 * npm package, version 1.7.1 — BSD-3-Clause, Copyright (c) Squirrel Chat et
 * al. (https://github.com/squirrelchat/smol-toml) — embedded here as test
 * INPUT; each file carries the licence's own copyright notice, list of
 * conditions and disclaimer verbatim, which is what the licence asks of a
 * redistribution in source form. It is the ladder's second rung because it
 * is what mitt is not: a MULTI-FILE package (imports and re-exports between
 * files, an `export default` of a VALUE, `export type { } from`), classes
 * with heritage to LIB types (`extends Date`, `extends Error`) and an
 * ECMAScript `#private` member, top-level function OVERLOADS in a `.d.ts`,
 * a destructured optional parameter, and recursive/index-signature aliases.
 *
 * (EXT.16) Generated WIRED to the package — `ModuleWiring("smol-toml",
 * "/smol-toml/dist/index.d.ts")`, the `types` entry of its `package.json` —
 * so the real output carries `@file:JsModule("smol-toml")` and the
 * default value's `@JsName("default")`; the gate compiles the
 * annotation-free variant.
 */
class KotlinExternalsSmolTomlGateTest {

    private val dateDts = """
/*!
 * Copyright (c) Squirrel Chat et al., All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
export declare class TomlDate extends Date {
    #private;
    constructor(date: string | Date);
    isDateTime(): boolean;
    isLocal(): boolean;
    isDate(): boolean;
    isTime(): boolean;
    isValid(): boolean;
    toISOString(): string;
    static wrapAsOffsetDateTime(jsDate: Date, offset?: string): TomlDate;
    static wrapAsLocalDateTime(jsDate: Date): TomlDate;
    static wrapAsLocalDate(jsDate: Date): TomlDate;
    static wrapAsLocalTime(jsDate: Date): TomlDate;
}
//# sourceMappingURL=date.d.ts.map"""

    private val errorDts = """
/*!
 * Copyright (c) Squirrel Chat et al., All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
type TomlErrorOptions = ErrorOptions & {
    toml: string;
    ptr: number;
};
export declare class TomlError extends Error {
    line: number;
    column: number;
    codeblock: string;
    constructor(message: string, options: TomlErrorOptions);
}
export {};
//# sourceMappingURL=error.d.ts.map"""

    private val indexDts = """
/*!
 * Copyright (c) Squirrel Chat et al., All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
import { parse } from './parse.js';
import { stringify } from './stringify.js';
import { TomlDate } from './date.js';
import { TomlError } from './error.js';
export type { TomlValue, TomlTable, TomlValueWithoutBigInt, TomlTableWithoutBigInt } from './util.js';
declare const _default: {
    parse: typeof parse;
    stringify: typeof stringify;
    TomlDate: typeof TomlDate;
    TomlError: typeof TomlError;
};
export default _default;
export { parse, stringify, TomlDate, TomlError };
export type { 
/** @deprecated use TomlValue instead */
TomlValue as TomlPrimitive, } from './util.js';
//# sourceMappingURL=index.d.ts.map"""

    private val parseDts = """
/*!
 * Copyright (c) Squirrel Chat et al., All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
import type { IntegersAsBigInt } from './primitive.js';
import { type TomlTable, type TomlTableWithoutBigInt } from './util.js';
export interface ParseOptions {
    maxDepth?: number;
    integersAsBigInt?: IntegersAsBigInt;
}
export declare function parse(toml: string, options?: ParseOptions & {
    integersAsBigInt: Exclude<IntegersAsBigInt, undefined | false>;
}): TomlTable;
export declare function parse(toml: string, options?: ParseOptions): TomlTableWithoutBigInt;
//# sourceMappingURL=parse.d.ts.map"""

    private val primitiveDts = """
/*!
 * Copyright (c) Squirrel Chat et al., All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
export type IntegersAsBigInt = undefined | boolean | 'asNeeded';
//# sourceMappingURL=primitive.d.ts.map"""

    private val stringifyDts = """
/*!
 * Copyright (c) Squirrel Chat et al., All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
export declare function stringify(obj: any, { maxDepth, numbersAsFloat }?: {
    maxDepth?: number;
    numbersAsFloat?: boolean;
}): string;
//# sourceMappingURL=stringify.d.ts.map"""

    private val utilDts = """
/*!
 * Copyright (c) Squirrel Chat et al., All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
import type { TomlDate } from './date.js';
export type TomlPrimitive = string | number | bigint | boolean | TomlDate;
export type TomlTable = {
    [key: string]: TomlValue;
};
export type TomlValue = TomlPrimitive | TomlValue[] | TomlTable;
export type TomlTableWithoutBigInt = {
    [key: string]: TomlValueWithoutBigInt;
};
export type TomlValueWithoutBigInt = Exclude<TomlPrimitive, bigint> | TomlValueWithoutBigInt[] | TomlTableWithoutBigInt;
//# sourceMappingURL=util.d.ts.map"""

    private val smolTomlDist: List<SourceFileEntry> = listOf(
        SourceFileEntry("/smol-toml/dist/date.d.ts", dateDts),
        SourceFileEntry("/smol-toml/dist/error.d.ts", errorDts),
        SourceFileEntry("/smol-toml/dist/index.d.ts", indexDts),
        SourceFileEntry("/smol-toml/dist/parse.d.ts", parseDts),
        SourceFileEntry("/smol-toml/dist/primitive.d.ts", primitiveDts),
        SourceFileEntry("/smol-toml/dist/stringify.d.ts", stringifyDts),
        SourceFileEntry("/smol-toml/dist/util.d.ts", utilDts),
    )

    /** (EXT.16) Wired to the package: `smol-toml`'s `types` entry is `dist/index.d.ts`. */
    internal fun generateSmolToml(): KotlinExternals =
        generateKotlinExternals(smolTomlDist, module = ModuleWiring("smol-toml", "/smol-toml/dist/index.d.ts"))

    @Test
    fun `smol-toml generates and the generated kotlin compiles`() {
        val result = generateSmolToml()
        val check = compileCheck(result.compileCheckSource)
        val compileErrors = check.errors
        assert(compileErrors.isEmpty())
        assert(check.successful)
    }

    @Test
    fun `smol-toml's classes render with lib heritage marked and the private name omitted`() {
        val result = generateSmolToml()
        val rendered = result.kotlin
        val dateHeader = "public open external class TomlDate(date: Any? /* xtsc: unmapped string | Date */) {\n" +
            "    /* xtsc: skipped heritage clause extends Date */\n" in rendered
        val statics = "        public fun wrapAsLocalDate(jsDate: Any? /* xtsc: unmapped Date */): TomlDate\n" in rendered
        val errorHeader = "public open external class TomlError(message: String, options: Any? " +
            "/* xtsc: unmapped ErrorOptions & { toml: string; ptr: number; } */) {\n" +
            "    /* xtsc: skipped heritage clause extends Error */\n" +
            "    public var line: Double\n" in rendered
        // `#private;` is class-private: omitted like a `private` member, and
        // NOT as a "member with a non-identifier name" marker.
        val privateAbsent = "#private" !in rendered && "non-identifier name" !in rendered
        val errorCodes = result.errors.map { it.code }
        assert(dateHeader)
        assert(statics)
        assert(errorHeader)
        assert(privateAbsent)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `smol-toml's overloaded parse renders both signatures and its wiring binds every export`() {
        val result = generateSmolToml()
        val rendered = result.kotlin
        // The two `.d.ts` overloads map to DIFFERENT Kotlin signatures (an
        // intersection falls back, a plain optional interface parameter maps
        // by name), so both survive the collapse.
        val bigIntOverload = "public external fun parse(toml: String, options: Any? /* xtsc: unmapped ParseOptions & " in rendered
        val plainOverload = "public external fun parse(toml: String, options: ParseOptions?): Any? /* xtsc: unmapped TomlTableWithoutBigInt */\n" in rendered
        // (EXT.11b) `obj: any` is `Any?` with no marker; the destructured
        // options parameter keeps its marker.
        val destructured = "public external fun stringify(obj: Any?, p1: Any? /* xtsc: unmapped { maxDepth?: number | undefined; numbersAsFloat?: boolean | undefined; } */): String\n" in rendered
        val options = "public external interface ParseOptions {\n    public var maxDepth: Double?\n" in rendered
        // (EXT.16) Wired to the package: `declare const _default` carries
        // no `export` and IS the package's default, so it joins the surface
        // as `@JsName("default")`; `export { parse, stringify, TomlDate,
        // TomlError }` re-exports IMPORTS, each bound under its own name in
        // the file declaring it, and the type-only re-exports bind no
        // runtime name — so no export statement of the entry keeps a marker,
        // and nothing in the seven files is an internal path.
        val header = rendered.startsWith("@file:JsModule(\"smol-toml\")\n\n")
        val defaultValue = "@JsName(\"default\")\npublic external val _default: Any? /* xtsc: unmapped { parse: " in rendered
        val noWiringMarkers = "module wiring is a later rung" !in rendered && "re-export" !in rendered
        val everythingReachable = "not exported by the package entry" !in rendered
        val noOtherJsName = Regex("@JsName").findAll(rendered).count() == 1
        assert(bigIntOverload)
        assert(plainOverload)
        assert(destructured)
        assert(options)
        assert(header)
        assert(defaultValue)
        assert(noWiringMarkers)
        assert(everythingReachable)
        assert(noOtherJsName)
    }

    @Test
    fun `smol-toml's recursive and union aliases refuse loudly`() {
        val result = generateSmolToml()
        val rendered = result.kotlin
        val primitive = "/* xtsc: skipped type alias TomlPrimitive with unmappable body string | number | boolean | bigint | TomlDate */" in rendered
        val bigInt = "/* xtsc: skipped type alias IntegersAsBigInt with unmappable body" in rendered
        val table = "/* xtsc: skipped type alias TomlTable with unmappable body" in rendered
        val value = "/* xtsc: skipped type alias TomlValue with unmappable body" in rendered
        val noTypealias = "typealias" !in rendered
        assert(primitive)
        assert(bigInt)
        assert(table)
        assert(value)
        assert(noTypealias)
    }

}
