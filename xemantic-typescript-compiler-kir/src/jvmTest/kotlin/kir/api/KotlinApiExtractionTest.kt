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

package com.xemantic.typescript.compiler.kir.api

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.kir.front.checkTypeScript
import kotlin.test.Test

/**
 * What lands on the exported surface, and how it is typed.
 *
 * These pins stop at the MODEL — no kotlinc runs here — because the questions
 * they ask are about the erasure and the export surface, and answering them
 * through a compiled artifact would make every one of them cost a metadata
 * compilation. `KotlinMetadataKlibTest` is the other half: that the model can
 * be turned into an artifact a Kotlin consumer actually compiles against.
 */
class KotlinApiExtractionTest {

    @Test
    fun `exports a function with its parameter and return types`() {
        val api = apiOf(
            """
            export function greet(name: string, times: number): string {
                return name.repeat(times)
            }
            """
        )
        val greet = api.function("greet")
        assert(greet.parameters.map { it.name } == listOf("name", "times"))
        assert(greet.parameters[0].type == KotlinType.STRING)
        assert(greet.parameters[1].type == KotlinType.DOUBLE)
        assert(greet.returnType == KotlinType.STRING)
    }

    /**
     * `T | undefined` is the union the erasure keeps information about — every
     * other one collapses, and this is the one that must not.
     */
    @Test
    fun `an optional union erases to a nullable Kotlin type`() {
        val api = apiOf(
            """
            export function find(key: string): string | undefined {
                return key === "" ? undefined : key
            }
            """
        )
        val find = api.function("find")
        assert(find.returnType == KotlinType.Named("kotlin.String", nullable = true))
    }

    /** An OPTIONAL parameter is nullable whether or not its own type is. */
    @Test
    fun `an optional parameter is nullable`() {
        val api = apiOf("export function log(message?: string): void {}")
        assert(api.function("log").parameters.single().type ==
            KotlinType.Named("kotlin.String", nullable = true))
    }

    /** §3.2: members that do not agree erase to `Any`, nullability preserved. */
    @Test
    fun `a heterogeneous union erases to Any`() {
        val api = apiOf(
            """
            export function widen(x: string | number): string | number {
                return x
            }
            """
        )
        val widen = api.function("widen")
        assert(widen.returnType == KotlinType.ANY_NON_NULL)
        assert(widen.parameters.single().type == KotlinType.ANY_NON_NULL)
    }

    /** A function VALUE keeps its arity and nothing else — see [KotlinType.Function]. */
    @Test
    fun `a callback parameter keeps its arity`() {
        val api = apiOf(
            """
            export function on(handler: (event: string, count: number) => void): void {}
            """
        )
        assert(api.function("on").parameters.single().type.render() == "(Any?, Any?) -> Any?")
    }

    /**
     * An object type is a property bag at run time and no COMMON Kotlin type
     * names one yet, so it is `Any?` — the erasure this slice documents.
     */
    @Test
    fun `an object type erases to Any`() {
        val api = apiOf(
            """
            export interface Options { verbose: boolean }
            export function run(options: Options): Options { return options }
            """
        )
        val run = api.function("run")
        assert(run.parameters.single().type == KotlinType.ANY)
        assert(run.returnType == KotlinType.ANY)
        // The interface itself is not a declaration on the surface: it has no
        // runtime witness to declare.
        assert(api.declarations.none { it.name == "Options" })
    }

    @Test
    fun `exports a class with its public instance members`() {
        val api = apiOf(
            """
            export class Counter {
                readonly start: number
                private hidden: number
                constructor(start: number) {
                    this.start = start
                    this.hidden = 0
                }
                increment(by: number): number {
                    this.hidden = this.hidden + by
                    return this.hidden
                }
                static make(): Counter { return new Counter(0) }
            }
            """
        )
        val counter = api.declarations.filterIsInstance<KotlinClass>().single()
        assert(counter.name == "Counter")
        assert(counter.constructorParameters!!.single().type == KotlinType.DOUBLE)
        assert(counter.members.map { it.name } == listOf("start", "increment"))
        val start = counter.members.filterIsInstance<KotlinProperty>().single()
        assert(!start.mutable)
        assert(start.type == KotlinType.DOUBLE)
        val increment = counter.members.filterIsInstance<KotlinFunction>().single()
        assert(increment.returnType == KotlinType.DOUBLE)
    }

    /** A class on the surface is NAMED by every signature that mentions it. */
    @Test
    fun `a signature mentioning an exported class names it`() {
        val api = apiOf(
            """
            export class Handle {}
            export function open(): Handle { return new Handle() }
            """
        )
        assert(api.function("open").returnType == KotlinType.Named("ts.Handle"))
    }

    @Test
    fun `a const is a val and a let is a var`() {
        val api = apiOf(
            """
            export const VERSION: string = "1.0"
            export let counter: number = 0
            """
        )
        val version = api.property("VERSION")
        val counter = api.property("counter")
        assert(!version.mutable)
        assert(version.type == KotlinType.STRING)
        assert(counter.mutable)
        assert(counter.type == KotlinType.DOUBLE)
    }

    /**
     * An enum has no runtime object here — a member access is replaced by its
     * constant — so it exports as an object of constants typed as the VALUES.
     */
    @Test
    fun `a string enum exports as an object of string constants`() {
        val api = apiOf(
            """
            export enum Kind { Alpha = "a", Beta = "b" }
            """
        )
        val kind = api.declarations.filterIsInstance<KotlinConstantObject>().single()
        assert(kind.members.map { it.name } == listOf("Alpha", "Beta"))
        assert(kind.members.all { it.type == KotlinType.STRING })
    }

    @Test
    fun `a numeric enum exports as an object of number constants`() {
        val api = apiOf("export enum Level { Low, High = 7 }")
        val level = api.declarations.filterIsInstance<KotlinConstantObject>().single()
        assert(level.members.all { it.type == KotlinType.DOUBLE })
    }

    /** Only what the module EXPORTS is public API. */
    @Test
    fun `an unexported declaration is not on the surface`() {
        val api = apiOf(
            """
            function internalHelper(): number { return 1 }
            export function exported(): number { return internalHelper() }
            """
        )
        assert(api.declarations.map { it.name } == listOf("exported"))
    }

    /** `export default f` is exported under the name `f` already has. */
    @Test
    fun `a default export keeps its own name`() {
        val api = apiOf(
            """
            function mitt(): number { return 1 }
            export default mitt
            """
        )
        assert(api.declarations.map { it.name } == listOf("mitt"))
    }

    /**
     * A construct that cannot be expressed is OMITTED and REPORTED — never
     * exported with a guessed type. An absent declaration is a compile error at
     * the consumer's use site; a wrong one is silent.
     */
    @Test
    fun `a rest parameter is refused rather than guessed`() {
        val extracted = extractionOf("export function all(...items: string[]): void {}")
        assert(extracted.module.declarations.isEmpty())
        assert(extracted.refusals.single().message.contains("rest parameter"))
    }

    /** The refusal names a position an author can find. */
    @Test
    fun `a refusal carries the position of the construct`() {
        val extracted = extractionOf(
            """
            export function fine(): void {}
            export function all(...items: string[]): void {}
            """
        )
        val refusal = extracted.refusals.single()
        assert(refusal.fileName == "api.ts")
        assert(refusal.line == 2)
    }

    /**
     * A TypeScript name that is a Kotlin KEYWORD is quoted, not refused.
     *
     * `is`, `in`, `object`, `when` and `val` are ordinary TypeScript names, and
     * the alternative to quoting is a generated source that does not parse —
     * i.e. a whole library refused for one member.
     */
    @Test
    fun `a name that is a Kotlin keyword is backtick quoted`() {
        val api = apiOf(
            """
            export function is(value: string): boolean { return value === "" }
            export const object: number = 1
            export class When { in(value: string): string { return value } }
            """
        )
        assert(api.declarations.map { it.name } == listOf("`is`", "`object`", "When"))
        val whenClass = api.declarations.filterIsInstance<KotlinClass>().single()
        assert(whenClass.members.single().name == "`in`")
    }

    // -----------------------------------------------------------------------
    // With the runtime's own metadata on the classpath (KAPI.3)
    // -----------------------------------------------------------------------

    /**
     * The whole point of the runtime klib: an object type stops being `Any?`.
     */
    @Test
    fun `with the runtime, an object type is a JsObject`() {
        val api = apiOf(
            """
            export interface Options { verbose: boolean }
            export function run(options: Options): Options { return options }
            """,
            runtimeTypes = true,
        )
        val run = api.function("run")
        assert(run.parameters.single().type == KirRuntimeApi.jsObject)
        assert(run.returnType == KirRuntimeApi.jsObject)
    }

    @Test
    fun `with the runtime, an array is a JsArray`() {
        val api = apiOf(
            "export function names(): string[] { return [] }",
            runtimeTypes = true,
        )
        assert(api.function("names").returnType == KirRuntimeApi.jsArray)
    }

    /** An anonymous object type is a bag by construction — it has no declaration. */
    @Test
    fun `with the runtime, an inline object type is a JsObject`() {
        val api = apiOf(
            """
            export function make(): { a: number } { return { a: 1 } }
            """,
            runtimeTypes = true,
        )
        assert(api.function("make").returnType == KirRuntimeApi.jsObject)
    }

    /**
     * A BRANDED options type — an intersection of shapes — is one bag.
     *
     * The shape every real library's options parameter has, and the one that
     * made `smol-toml` export `options: Any?` until the intersection rule
     * landed. A member that is NOMINAL still refuses: an intersection with a
     * `Date` is not a bag.
     */
    @Test
    fun `with the runtime, an intersection of shapes is a JsObject`() {
        val api = apiOf(
            """
            export interface Options { maxDepth?: number }
            export function parse(options?: Options & { strict: boolean }): void {}
            """,
            runtimeTypes = true,
        )
        assert(api.function("parse").parameters.single().type ==
            KirRuntimeApi.jsObject.asNullable())
    }

    @Test
    fun `with the runtime, an intersection with a library type is not a JsObject`() {
        val api = apiOf(
            """
            export interface Tag { tag: string }
            export function stamp(value: Date & Tag): void {}
            """,
            runtimeTypes = true,
        )
        assert(api.function("stamp").parameters.single().type == KotlinType.ANY)
    }

    /**
     * A LIBRARY type is named by the table `KirIntrinsics.libraryClass` mirrors,
     * so a `Date` on an exported signature and a `Date` in the compiled program
     * are the same runtime class.
     */
    @Test
    fun `with the runtime, a library type is its runtime class`() {
        val api = apiOf(
            """
            export function now(): Date { return new Date() }
            export function counts(): Map<string, number> { return new Map() }
            export function pattern(): RegExp { return /x/ }
            """,
            runtimeTypes = true,
        )
        assert(api.function("now").returnType ==
            KotlinType.Named("${KirRuntimeApi.PACKAGE}.JsDate"))
        assert(api.function("counts").returnType ==
            KotlinType.Named("${KirRuntimeApi.PACKAGE}.JsMap"))
        assert(api.function("pattern").returnType ==
            KotlinType.Named("${KirRuntimeApi.PACKAGE}.JsRegExp"))
    }

    /**
     * NEGATIVE CONTROL for the gate, and the reason it exists: a library type
     * the table does NOT name is not a property bag either — typing one as
     * `JsObject` would offer a consumer members the value does not have — so it
     * stays `Any?` rather than falling through to the bag.
     */
    @Test
    fun `with the runtime, an unmapped library type is still Any`() {
        val api = apiOf(
            "export function later(): Promise<string> { return Promise.resolve(\"\") }",
            runtimeTypes = true,
        )
        assert(api.function("later").returnType == KotlinType.ANY)
    }

    private fun apiOf(source: String, runtimeTypes: Boolean = false): KotlinApiModule =
        extractionOf(source, runtimeTypes).module

    private fun extractionOf(source: String, runtimeTypes: Boolean = false): ExtractedApi {
        val checked = checkTypeScript("api.ts", source.trimIndent())
        assert(checked.errors.isEmpty())
        return TypeScriptApiExtractor(
            listOf(checked.sourceFile),
            checked.facts,
            "ts",
            runtimeTypes = runtimeTypes,
        ).extract(checked.sourceFile)
    }

    private fun KotlinApiModule.function(name: String): KotlinFunction =
        declarations.filterIsInstance<KotlinFunction>().single { it.name == name }

    private fun KotlinApiModule.property(name: String): KotlinProperty =
        declarations.filterIsInstance<KotlinProperty>().single { it.name == name }

}
