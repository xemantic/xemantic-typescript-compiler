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
import kotlin.test.Test

/**
 * (CHK.218) M1 — mitt's `test-types-compilation.ts`, whose ten `@ts-expect-error` directives read
 * "unused" because two defects together made every one of those calls legal here:
 *
 *  1. a DEFAULT import of a DIRECTORY specifier (`import mitt from '..'`, `'./sub'`) resolved to
 *     nothing, so `mitt` was `any` (the named-import resolver had the crawl's answer, the alias
 *     ladder did not) — pinned through `ProjectCompiler`, since `diagnose()` has no crawl;
 *  2. a parameter typed by an INDEXED ACCESS over the signature's own type parameter
 *     (`event: Events[Key]`, `handler: Handler<Events[Key]>`) was `any`, because this checker has
 *     no deferred indexed-access type — now re-resolved per call with `Key` bound from the
 *     argument (`IndexedAccessParams`), plus the overload set's non-generic sibling re-resolved
 *     under the receiver's type arguments and tsgo's specialized-first "last overload".
 *
 * Every expected row is tsgo 7.0.2's (`build/bench/p18283-agent/r1`, `r11`), line and column
 * 1-based. The TS2769 chain's argument/parameter DISPLAY differs from tsgo's
 * (`'(x?: number | undefined) => void'` / `'(event: string) => void'` where tsgo prints
 * `'(x?: number) => void'` / `'Handler<string>'`) — a pre-existing display family that a plain
 * non-generic call shows too — so those pins assert the chain lines that agree.
 */
class IndexedAccessGenericArgumentTest {

    private val emitter = """
        type Handler<T = unknown> = (event: T) => void;
        type WildcardHandler<T = Record<string, unknown>> = (type: keyof T, event: T[keyof T]) => void;
        interface Emitter<Events extends Record<string | symbol, unknown>> {
          on<Key extends keyof Events>(type: Key, handler: Handler<Events[Key]>): void;
          on(type: '*', handler: WildcardHandler<Events>): void;
          emit<Key extends keyof Events>(type: Key, event: Events[Key]): void;
          emit<Key extends keyof Events>(type: undefined extends Events[Key] ? Key : never): void;
        }
        interface SomeEventData { name: string; }
        declare const emitter: Emitter<{ foo: string; someEvent: SomeEventData; bar?: number; }>;
        const barHandler = (x?: number) => {};
        const fooHandler = (x: string) => {};
        const wildcardHandler = (_type: 'foo' | 'bar' | 'someEvent', _event: string | SomeEventData | number | undefined) => {};
    """.trimIndent() + "\n"

    private fun Diagnostic.row() = "$line:$character TS$code $message"

    private fun rows(src: String): List<String> = diagnose(emitter + src).map { it.row() }

    @Test
    fun `an argument against an indexed access by an inferred key is checked`() {
        val r = rows("emitter.emit('foo', 1);")
        assert(r == listOf("14:21 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'."))
    }

    @Test
    fun `an object argument against the indexed member type is checked`() {
        val r = rows("emitter.emit('someEvent', 'NOT VALID');")
        assert(r == listOf("14:27 TS2345 Argument of type 'string' is not assignable to parameter of type 'SomeEventData'."))
    }

    @Test
    fun `the one-argument overload's conditional parameter is never for a required member`() {
        val r = rows("emitter.emit('foo');")
        assert(r == listOf("14:14 TS2345 Argument of type '\"foo\"' is not assignable to parameter of type 'never'."))
    }

    @Test
    fun `legal calls through the same overloads stay silent`() {
        val r = rows(
            """
            emitter.emit('someEvent', { name: 'jack' });
            emitter.emit('foo', 'string');
            emitter.emit('bar');
            emitter.emit('bar', 1);
            emitter.on('foo', fooHandler);
            emitter.on('bar', barHandler);
            emitter.on('*', wildcardHandler);
            emitter.on('*', fooHandler);
            declare const k: 'foo' | 'bar';
            emitter.emit(k, 1);
            """.trimIndent()
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a handler against the indexed member type fails the overload set at the handler`() {
        val d = diagnose(emitter + "emitter.on('foo', barHandler);")
        assert(d.map { it.row() } == listOf("14:19 TS2769 No overload matches this call."))
        val chain = d.single().messageChain
        assert(chain.first() == "  The last overload gave the following error.")
        assert(chain.any { it.trim() == "Types of parameters 'x' and 'event' are incompatible." })
        assert(chain.any { it.trim() == "Type 'string' is not assignable to type 'number'." })
    }

    @Test
    fun `the last overload is the generic one because the literal-typed overload is specialized`() {
        val d = diagnose(emitter + "emitter.on('*', barHandler);")
        assert(d.map { it.row() } == listOf("14:12 TS2769 No overload matches this call."))
        assert(d.single().messageChain == listOf(
            "  The last overload gave the following error.",
            "    Argument of type '\"*\"' is not assignable to parameter of type '\"bar\" | \"foo\" | \"someEvent\"'.",
        ))
    }

    @Test
    fun `a plain generic function with an indexed access parameter`() {
        val d = diagnose(
            """
            type Ev = { foo: string; bar: number; };
            declare function c1<Key extends keyof Ev>(type: Key, event: Ev[Key]): void;
            c1('foo', 1);
            c1('bar', 1);
            """.trimIndent()
        )
        assert(d.map { it.row() } == listOf("3:11 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'."))
    }

    @Test
    fun `a union key binds the union and indexes every member`() {
        val r = rows("declare const k: 'foo' | 'bar';\nemitter.emit(k, {});")
        assert(r == listOf("15:17 TS2345 Argument of type '{}' is not assignable to parameter of type 'string | number | undefined'."))
    }

    // -- the directory default import, through the crawl --------------------------------------

    private val esm =
        """{ "compilerOptions": { "strict": true, "target": "es2022", "module": "esnext", "moduleResolution": "bundler", "noEmit": true, "lib": ["es2022"], "types": [] }, "include": ["src"] }"""

    private fun build(files: Map<String, String>): List<String> {
        val vfs = InMemoryVfs(files.mapKeys { "/proj/src/${it.key}" } + ("/proj/tsconfig.json" to esm))
        return ProjectCompiler(vfs).build("/proj", noEmit = true).diagnostics
            .map { "${it.fileName?.substringAfterLast('/')}:${it.row()}" }.sorted()
    }

    private val dirFiles = mapOf(
        "sub/index.ts" to "export default function f(): number { return 1 }\nexport const n: number = 1;\n",
    )

    @Test
    fun `a default import of a directory resolves to its index`() {
        val r = build(dirFiles + ("t.ts" to "import f from './sub';\nconst b: boolean = f;\n"))
        assert(r == listOf("t.ts:2:7 TS2322 Type '() => number' is not assignable to type 'boolean'."))
    }

    @Test
    fun `a default import of the parent directory resolves to its index`() {
        val r = build(mapOf(
            "pkg/index.ts" to "export default function g(): number { return 1 }\n",
            "pkg/inner/u.ts" to "import g from '..';\nconst c: boolean = g;\n",
        ))
        assert(r == listOf("u.ts:2:7 TS2322 Type '() => number' is not assignable to type 'boolean'."))
    }

    @Test
    fun `a legal use of a directory default import stays silent`() {
        val r = build(dirFiles + ("t.ts" to "import f from './sub';\nconst x: number = f();\n"))
        assert(r.isEmpty())
    }
}
