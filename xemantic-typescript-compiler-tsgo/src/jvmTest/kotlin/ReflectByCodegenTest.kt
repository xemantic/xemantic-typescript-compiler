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
import com.xemantic.typescript.tsgo.core.CompilerOptions
import com.xemantic.typescript.tsgo.core.ScriptTarget
import com.xemantic.typescript.tsgo.core.TSTrue
import com.xemantic.typescript.tsgo.core.clone
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.goStringToBytes
import kotlin.test.Test

/**
 * Reflection by codegen (docs/goport-lowering.md § 3, docs/goport-runtime.md § 11): the porter makes
 * `core.CompilerOptions` a GoReflectStruct + GoJsonStruct, its value-class fields GoBasicValues and
 * `*Tristate` a json Unmarshaler (`Tristate_Ptr`). These pins run the PORTED code over them.
 */
class ReflectByCodegenTest {

    @Test
    fun `tsgo's json form of CompilerOptions decodes into the ported struct`() {
        val opts = CompilerOptions()
        val err = com.xemantic.typescript.tsgo.json.unmarshal(
            goStringToBytes("{\"target\":2,\"strict\":true,\"noErrorTruncation\":true,\"lib\":[\"lib.es5.d.ts\"],\"newLine\":1}"), opts,
            GoElem.ref<com.xemantic.typescript.tsgo.go.github_com.go_json_experiment.json.Options?>().nilSlice,
        )
        assert(err == null)
        assert(opts.target == ScriptTarget(2))
        assert(opts.strict == TSTrue)
        assert(opts.noErrorTruncation == TSTrue)
        assert(opts.lib.len == 1 && opts.lib[0] == "lib.es5.d.ts")
    }

    @Test
    fun `CompilerOptions Clone copies every exported field through reflect`() {
        // A big struct (> 120 fields): its fields live in the class body.
        val opts = CompilerOptions().also { it.target = ScriptTarget(9); it.strict = TSTrue; it.lib = GoSlice.of(GoElem.STRING, "lib.esnext.d.ts") }
        val c = opts.clone()!!
        assert(c !== opts)
        assert(c.target == ScriptTarget(9) && c.strict == TSTrue && c.lib === opts.lib)
        assert(com.xemantic.typescript.tsgo.go.reflect.deepEqual(c, opts))
        c.strict = com.xemantic.typescript.tsgo.core.TSFalse
        assert(!com.xemantic.typescript.tsgo.go.reflect.deepEqual(c, opts))
    }

    @Test
    fun `reflect TypeFor a struct reads its fields and json tags`() {
        val t = com.xemantic.typescript.tsgo.go.reflect.typeFor(CompilerOptions.GO_STRUCT.let {
            com.xemantic.typescript.tsgo.runtime.GoTypeInfo(25, "core.CompilerOptions", CompilerOptions::class, structInfo = { it })
        })
        val target = (0 until t.numField()).map { t.field(it) }.first { it.name == "Target" }
        assert(target.isExported() && target.tag.get("json") == "target,omitzero")
    }
}
