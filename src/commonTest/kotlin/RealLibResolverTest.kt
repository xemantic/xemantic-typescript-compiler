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
    fun `target default without lib option pulls the host libs in through the full variant`() {
        // lib.d.ts (= es5.full) has priority 0 -> first, and its DOM / host references
        // are SHIPPED since (LIB.1)(b), so they are included rather than recorded as
        // unavailable. Note the second-order consequence, which is tsc's too: DOM
        // references es2015, so an ES5 target-default program gets the whole ES2015
        // layer even though nothing asked for it.
        RealLibResolver.resolve(
            null, ScriptTarget.ES5
        ) should {
            have(orderedKeys == listOf(
                "es5.full", "es5", "es2015",
                "dom.generated", "webworker.importscripts", "scripthost",
                "es2015.core", "es2015.collection", "es2015.generator", "es2015.iterable",
                "es2015.promise", "es2015.proxy", "es2015.reflect", "es2015.symbol",
                "es2015.symbol.wellknown", "es2018.asynciterable",
                "decorators", "decorators.legacy",
            ))
            // The whole point of (LIB.1)(b): NOTHING is unavailable any more. This list
            // was never consumed by anything, so every entry in it was a lib silently
            // degrading to `any`.
            have(unavailable.isEmpty())
            have(unknownNames.isEmpty())
        }
    }

    @Test
    fun `an explicit dom lib request resolves to the shipped host file`() {
        // `dom.generated` is the SOURCE name of what tsc distributes as `lib.dom.d.ts`;
        // the resolution must carry it, and the round trip back to the distributed name
        // must NOT print the source name (nothing distributes `lib.dom.generated.d.ts`).
        RealLibResolver.resolve(
            listOf("dom"), ScriptTarget.ES5
        ) should {
            have("dom.generated" in orderedKeys)
            // dom.generated references es2015 + es2018.asynciterable, so those come too.
            have("es2015" in orderedKeys)
            have("es2018.asynciterable" in orderedKeys)
            have(unavailable.isEmpty())
            have(unknownNames.isEmpty())
        }
        assert(RealLibResolver.keyToDistFileName("dom.generated") == "lib.dom.d.ts")
        assert(RealLibResolver.keyToDistFileName("webworker.generated") == "lib.webworker.d.ts")
        assert(RealLibResolver.keyToDistFileName("dom.iterable.generated") == "lib.dom.iterable.d.ts")
        assert(RealLibResolver.distFileNameToKey("lib.dom.d.ts") == "dom.generated")
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

    // ---- (LIB.1)(b/c): every lib tsc knows about is one we actually ship --------

    @Test
    fun `every lib name and every target default resolves with nothing unavailable`() {
        // This is the maintained form of what (LIB.1)(b) fixed. Before it, a `lib`
        // request for an unshipped file landed in `Resolution.unavailable` — a list
        // NOTHING consumed, so the lib silently degraded to `any` instead of failing.
        // The set is empty now, and this keeps it empty: a pin bump that adds a lib
        // name to `libMap` without a matching shipped file, or renames a source file
        // out from under `distFileNameToKey`, fails HERE rather than going quiet.
        val offenders = mutableListOf<String>()
        for (name in RealLibResolver.libMap.keys) {
            val resolution = RealLibResolver.resolve(listOf(name), ScriptTarget.ES5)
            if (resolution.unavailable.isNotEmpty() || resolution.unknownNames.isNotEmpty()) {
                offenders.add(name)
            }
        }
        for (target in ScriptTarget.entries) {
            val resolution = RealLibResolver.resolve(null, target)
            if (resolution.unavailable.isNotEmpty() || resolution.unknownNames.isNotEmpty()) {
                offenders.add("target:$target")
            }
        }
        assert(offenders.isEmpty())
    }

    @Test
    fun `every distributed lib file name round-trips through the key mapping`() {
        // The asymmetry that bit round 731: `distFileNameToKey` mapped
        // `lib.dom.d.ts` -> `dom.generated` while `keyToDistFileName` had no inverse,
        // so a DOM lib file's SourceFile.fileName came out as `lib.dom.generated.d.ts`
        // — a name that exists in no TypeScript distribution, and one that a
        // lib-declaration diagnostic (TS2728's "declared here") would print.
        val offenders = mutableListOf<String>()
        val distNames = RealLibResolver.libMap.values.toMutableSet()
        for (target in ScriptTarget.entries) distNames.add(RealLibResolver.defaultLibFileName(target))
        for (dist in distNames) {
            val key = RealLibResolver.distFileNameToKey(dist)
            if (key !in RealLibFiles.files) offenders.add("$dist -> $key (not shipped)")
            else if (RealLibResolver.keyToDistFileName(key) != dist) {
                offenders.add("$dist -> $key -> ${RealLibResolver.keyToDistFileName(key)}")
            }
        }
        assert(offenders.isEmpty())
    }
}
