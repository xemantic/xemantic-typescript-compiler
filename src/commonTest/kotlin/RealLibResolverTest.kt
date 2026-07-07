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
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * M2.1(b) (round 390): [RealLibResolver] expands lib names through the
 * `/// <reference lib>` DAG of the real lib headers, dedupes, and orders by
 * tsc's `getDefaultLibFilePriority` (libEntries index — NOT the DFS discovery
 * order: es5 references decorators, yet decorators sorts near the END because
 * its libs-array index is high). These tests pin the exact tsc semantics
 * against the real shipped headers.
 */
class RealLibResolverTest {

    @Test
    fun `lib option expands the es2015 composite through its reference DAG in priority order`() {
        // Exact tsc inclusion order: es5 (referenced by es2015) first, the composite
        // itself, its by-feature pieces in libEntries order, and es5's decorators
        // references LAST despite being discovered first via es5 — priority order,
        // not DFS order.
        RealLibResolver.resolve(
            listOf("es2015"), ScriptTarget.ES5
        ) should {
            have(orderedKeys == listOf(
                "es5", "es2015",
                "es2015.core", "es2015.collection", "es2015.generator", "es2015.iterable",
                "es2015.promise", "es2015.proxy", "es2015.reflect", "es2015.symbol",
                "es2015.symbol.wellknown",
                "decorators", "decorators.legacy",
            ))
            have(unknownNames.isEmpty())
            have(unavailable.isEmpty())
        }
    }

    @Test
    fun `target default without lib option uses the full variant and skips unshipped DOM libs`() {
        // lib.d.ts (= es5.full) has priority 0 -> first; its DOM/host references are
        // not shipped -> recorded, not included.
        RealLibResolver.resolve(
            null, ScriptTarget.ES5
        ) should {
            have(orderedKeys == listOf("es5.full", "es5", "decorators", "decorators.legacy"))
            have(unavailable == listOf(
                "lib.dom.d.ts", "lib.webworker.importscripts.d.ts", "lib.scripthost.d.ts",
            ))
            have(unknownNames.isEmpty())
        }
    }

    @Test
    fun `es2015 target default keeps tsc's lib es6 back-compat name at priority zero`() {
        assert(RealLibResolver.defaultLibFileName(ScriptTarget.ES2015) == "lib.es6.d.ts")
        // lib.es6.d.ts maps to the es2015.full source and sorts FIRST (priority 0).
        RealLibResolver.resolve(
            null, ScriptTarget.ES2015
        ) should {
            have(orderedKeys.first() == "es2015.full")
            have("es5" in orderedKeys)
            have("es2015.symbol.wellknown" in orderedKeys)
        }
    }

    @Test
    fun `aliases and case-insensitivity resolve through libMap like tsc`() {
        // "ES6" (case-insensitive alias) -> lib.es2015.d.ts — identical to "es2015".
        val canonical = RealLibResolver.resolve(listOf("es2015"), ScriptTarget.ES5)
        RealLibResolver.resolve(
            listOf("ES6"), ScriptTarget.ES5
        ) should {
            have(orderedKeys == canonical.orderedKeys)
        }
        // The back-compat fallbacks remap to their real homes — and the target's OWN
        // reference directives still expand (es2020.bigint -> es2020.intl -> es2018.intl).
        RealLibResolver.resolve(
            listOf("esnext.bigint"), ScriptTarget.ES5
        ) should {
            have(orderedKeys == listOf("es2018.intl", "es2020.bigint", "es2020.intl"))
        }
    }

    @Test
    fun `unknown lib names are reported not silently dropped`() {
        RealLibResolver.resolve(
            listOf("es2015.core", "nope.not.a.lib"), ScriptTarget.ES5
        ) should {
            have(unknownNames == listOf("nope.not.a.lib"))
            have(orderedKeys == listOf("es2015.core"))
        }
    }

    @Test
    fun `es2020 default closure is transitively complete and deduped`() {
        RealLibResolver.resolve(
            listOf("es2020"), ScriptTarget.ES5
        ) should {
            // Transitive: es2020 -> es2019 -> es2018 -> ... -> es5; every layer's pieces present.
            for (key in listOf(
                "es5", "es2015", "es2016", "es2017", "es2018", "es2019", "es2020",
                "es2015.iterable", "es2016.array.include", "es2017.string",
                "es2018.asyncgenerator", "es2019.array", "es2020.bigint", "es2020.intl",
            )) have(key in orderedKeys)
            // Deduped: es2015.symbol is referenced by iterable + symbol.wellknown + es2015.
            have(orderedKeys.size == orderedKeys.distinct().size)
            // Ordered: every composite before its own dotted pieces, layers ascending.
            have(orderedKeys.indexOf("es5") < orderedKeys.indexOf("es2015"))
            have(orderedKeys.indexOf("es2015") < orderedKeys.indexOf("es2015.core"))
            have(orderedKeys.indexOf("es2015.symbol.wellknown") < orderedKeys.indexOf("es2016.array.include"))
            have(orderedKeys.last() == "decorators.legacy")
        }
    }
}
