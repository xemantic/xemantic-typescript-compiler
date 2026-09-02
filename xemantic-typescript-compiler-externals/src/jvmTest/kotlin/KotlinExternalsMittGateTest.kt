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
 * (EXT.6) THE FIRST FIXTURE-LADDER RUNG: the generator over the REAL
 * `mitt@3.0.1` type declarations, gated by the metadata compile.
 *
 * The fixture below is the verbatim `index.d.ts` of the `mitt` npm package,
 * version 3.0.1 — MIT License, Copyright (c) 2021 Jason Miller
 * (https://github.com/developit/mitt) — embedded here as test INPUT under the
 * MIT licence's notice requirement. It is the ladder's first rung because its
 * ~20 lines exercise almost everything the MVP has: generic aliases with
 * defaults and constraints, `keyof`/indexed-access/conditional shapes (all
 * LOUD fallbacks), a generic interface with overloaded generic methods, an
 * optional parameter, and the DEFAULT-exported generic entry function.
 *
 * (EXT.16) Generated WIRED to the package — `ModuleWiring("mitt",
 * "/mitt/index.d.ts")`, its `types` entry being its one file — so the real
 * output carries `@file:JsModule("mitt")` and the default export's
 * `@JsName("default")`; the gate still compiles the annotation-free variant.
 */
class KotlinExternalsMittGateTest {

    private val mittIndexDts = """
export declare type EventType = string | symbol;
export declare type Handler<T = unknown> = (event: T) => void;
export declare type WildcardHandler<T = Record<string, unknown>> = (type: keyof T, event: T[keyof T]) => void;
export declare type EventHandlerList<T = unknown> = Array<Handler<T>>;
export declare type WildCardEventHandlerList<T = Record<string, unknown>> = Array<WildcardHandler<T>>;
export declare type EventHandlerMap<Events extends Record<EventType, unknown>> = Map<keyof Events | '*', EventHandlerList<Events[keyof Events]> | WildCardEventHandlerList<Events>>;
export interface Emitter<Events extends Record<EventType, unknown>> {
    all: EventHandlerMap<Events>;
    on<Key extends keyof Events>(type: Key, handler: Handler<Events[Key]>): void;
    on(type: '*', handler: WildcardHandler<Events>): void;
    off<Key extends keyof Events>(type: Key, handler?: Handler<Events[Key]>): void;
    off(type: '*', handler: WildcardHandler<Events>): void;
    emit<Key extends keyof Events>(type: Key, event: Events[Key]): void;
    emit<Key extends keyof Events>(type: undefined extends Events[Key] ? Key : never): void;
}
/**
 * Mitt: Tiny (~200b) functional event emitter / pubsub.
 * @name mitt
 * @returns {Mitt}
 */
export default function mitt<Events extends Record<EventType, unknown>>(all?: EventHandlerMap<Events>): Emitter<Events>;
"""

    /**
     * (EXT.16) The generation WIRED to the package: `mitt`'s `types` entry is
     * its one file, and its default export binds as `@JsName("default")`.
     */
    internal fun generateMitt(): KotlinExternals = generateKotlinExternals(
        listOf(SourceFileEntry("/mitt/index.d.ts", mittIndexDts)),
        module = ModuleWiring("mitt", "/mitt/index.d.ts"),
    )

    @Test
    fun `mitt generates and the generated kotlin compiles`() {
        val result = generateMitt()
        val check = compileCheck(result.compileCheckSource)
        val compileErrors = check.errors
        assert(compileErrors.isEmpty())
        assert(check.successful)
    }

    @Test
    fun `mitt's spine renders - the handler typealias and the generic entry function`() {
        val result = generateMitt()
        val rendered = result.kotlin
        val handler = "public typealias Handler<T> = (T) -> Unit\n" in rendered
        val entry = "public external fun <Events> mitt(" in rendered
        // `Emitter<Events>` renders by NAME even though the lens resolves it
        // as `Emitter<any>` (the ambient substitutes the fn's own TP away,
        // measured by this gate's first run): the target's identity comes from
        // the checker, the arguments from their own annotations.
        val returnsEmitter = "): Emitter<Events>\n" in rendered
        // (EXT.16) Wired: the file opens with the package's `@file:JsModule`,
        // and the default export binds as `@JsName("default")` on the line
        // above the fun (below the fun's own constraint marker) — the
        // "consumers bind the module's default" marker IS the wiring now.
        val header = rendered.startsWith("@file:JsModule(\"mitt\")\n\n")
        val defaultBinding =
            "/* xtsc: constraint on Events: any not carried */\n@JsName(\"default\")\npublic external fun <Events> mitt(" in rendered
        val noDefaultMarker = "consumers bind the module's default" !in rendered
        val everythingReachable = "not exported by the package entry" !in rendered
        assert(handler)
        assert(entry)
        assert(returnsEmitter)
        assert(header)
        assert(defaultBinding)
        assert(noDefaultMarker)
        assert(everythingReachable)
    }

    @Test
    fun `mitt's inexpressible shapes fall back loudly - never silently`() {
        val result = generateMitt()
        val rendered = result.kotlin
        // The keyof/indexed-access aliases refuse as declarations...
        val wildcardSkip = "skipped generic type alias WildcardHandler" in rendered
        val mapSkip = "skipped generic type alias EventHandlerMap" in rendered
        // ...and the union alias refuses on its resolved body.
        val unionSkip = "skipped type alias EventType with unmappable body" in rendered
        assert(wildcardSkip)
        assert(mapSkip)
        assert(unionSkip)
    }

}
