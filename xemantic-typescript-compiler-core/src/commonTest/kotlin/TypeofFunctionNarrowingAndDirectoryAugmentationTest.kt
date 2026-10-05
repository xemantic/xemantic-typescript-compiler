/*
 * Copyright 2025-2026 Kazimierz Pogoda / Xemantic
 *
 * Licensed under the GNU Affero General Public License, Version 3 (AGPL-3.0-only)
 * WITH LicenseRef-xtsc-output-exception, see LICENSE.md.
 *
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (P18.297) Three hono families, every expected row measured against tsgo 7.0.2:
 *
 *  (a) `declare module '../..'` names a DIRECTORY, resolved through its `index.ts`
 *      ([NameResolver] `augmentationDirectoryIndex`) — under Bundler only; Node16/NodeNext
 *      ESM gets no directory resolution and tsgo keeps the TS2664.
 *  (b) `typeof x === "function"` FILTERS a union in every reader: the legacy if-arm (it used
 *      to install `any`), a ternary branch (nested references, e.g. the callee of
 *      `o(1)`), the assignment that stores such a ternary (union-subset reduction), the
 *      global `Function` interface (callable for `typeof`, an untyped call), and a class
 *      INSTANCE whose type carries its constructor's signatures.
 *  (c) a computed `unique symbol` key in a parameter pattern: contextually typed it is
 *      silent, uncontextual it is TS7031 only; a `symbol` key is TS2538, never TS2537.
 */
class TypeofFunctionNarrowingAndDirectoryAugmentationTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private fun rows(source: String): List<String> =
        diagnose(source, directives).sortedWith(compareBy({ it.line }, { it.character }))
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    private val prelude = """
        interface RO { docType?: string; stream?: boolean }
        type F = (c: number) => RO
    """.trimIndent() + "\n"

    // --- (b) typeof "function" / "object" ------------------------------------------------

    @Test
    fun `the then-branch of typeof function narrows to the callable constituent`() {
        val r = rows(prelude + """
            export function t1(o: RO | F) { if (typeof o === 'function') { const n: number = o } else { const n: number = o } }
        """.trimIndent())
        assert(r == listOf(
            "3:70 TS2322 Type 'F' is not assignable to type 'number'.",
            "3:99 TS2322 Type 'RO' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `typeof object keeps null and drops the function in the then-branch`() {
        val r = rows(prelude + """
            export function t5(o: RO | F | null) { if (typeof o === 'object') { const n: number = o } else { const n: number = o } }
        """.trimIndent())
        assert(r.map { it.substringBefore(" Type") } == listOf("3:75 TS2322", "3:104 TS2322"))
        assert(r[0].contains("Type 'RO | null' is not assignable to type 'number'."))
        assert(r[1].contains("Type 'F' is not assignable to type 'number'."))
    }

    @Test
    fun `an interface with a call signature and a class constructor type count as functions`() {
        val r = rows(prelude + """
            interface Callable { (x: number): string; tag: string }
            class K { a = 1 }
            export function t10(o: Callable | RO) { if (typeof o === 'function') { const n: number = o } }
            export function t11(o: typeof K | RO) { if (typeof o === 'function') { const n: number = o } }
        """.trimIndent())
        assert(r == listOf(
            "5:78 TS2322 Type 'Callable' is not assignable to type 'number'.",
            "6:78 TS2322 Type 'typeof K' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `a ternary branch narrows the callee of a nested call`() {
        val r = rows(prelude + """
            export function p3(o: RO | F) { const r = typeof o === 'function' ? o(1) : 0; const n: number = r }
            export function p6(o: RO | F) { const r = typeof o === 'function' ? o(1) : o; const n: number = r }
        """.trimIndent())
        // tsgo prints `'0 | RO'` for p3's head; ours names the failing constituent (a display
        // residue of the literal-union head, not of this narrowing), so p3 pins the position.
        assert(r.map { it.substringBefore(" Type") } == listOf("3:85 TS2322", "4:85 TS2322"))
        assert(r[1] == "4:85 TS2322 Type 'RO' is not assignable to type 'number'.")
    }

    @Test
    fun `the literal-branch ternary types its other branch under the condition`() {
        val r = rows(prelude + """
            export function p1(o: RO | F) { const r = typeof o === 'function' ? o : null; const s: string = r }
        """.trimIndent())
        assert(r.size == 1 && r[0].startsWith("3:85 TS2322 Type 'F | null' is not assignable to type 'string'."))
    }

    @Test
    fun `assigning the narrowed ternary back reduces the declared type - the hono jsx-renderer shape`() {
        val r = rows(prelude + """
            export function t8(o?: RO | F) { o = typeof o === 'function' ? o(1) : o; o?.docType; const n: number = o }
            export function t9(o?: RO | F) { return () => { o = typeof o === 'function' ? o(1) : o; o?.docType; const n: number = o } }
        """.trimIndent())
        assert(r == listOf(
            "3:92 TS2322 Type 'RO | undefined' is not assignable to type 'number'.",
            "4:107 TS2322 Type 'RO | undefined' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `assigning a subset union variable reduces the declared type`() {
        val r = rows(prelude + """
            declare const g: RO | undefined
            export function a2(o: RO | F | undefined) { o = g; o?.docType; const n: number = o }
        """.trimIndent())
        assert(r == listOf("4:70 TS2322 Type 'RO | undefined' is not assignable to type 'number'."))
    }

    @Test
    fun `the global Function interface is a function for typeof and an untyped call`() {
        val r = rows("""
            type RO = { current: number }
            export function x1(ref: RO | Function) { if (typeof ref === 'function') ref(1); else ref.current }
            export function x2(ref: RO | Function | undefined) { if (ref) { if (typeof ref === 'function') { const n: number = ref } } }
        """.trimIndent())
        assert(r == listOf("3:104 TS2322 Type 'Function' is not assignable to type 'number'."))
    }

    @Test
    fun `a class instance with a declared constructor is not a function - the hono timeout shape`() {
        val r = rows("""
            class HE extends Error { constructor(public status: number) { super() } }
            type HF = (n: number) => HE
            export const t3 = (ex: HF | HE) => typeof ex === 'function' ? ex(1) : ex
            export function t4(ex: HF | HE) { if (typeof ex !== 'function') { const n: number = ex } }
        """.trimIndent())
        assert(r == listOf("4:73 TS2322 Type 'HE' is not assignable to type 'number'."))
    }

    @Test
    fun `negative control - a ternary does not mint a type for an unknown operand`() {
        // `instanceof` narrowing of `unknown` is left to the bare-reference rule; installing it
        // for the whole branch exposed a `then` inference gap in hono's jsx/base.ts.
        val r = rows("""
            export const f = (value: unknown): Promise<string> | string => {
              const s = (value as { toString(): unknown }).toString()
              const escape = (r: unknown): string | Promise<string> => String(r)
              return s instanceof Promise ? s.then(escape) : escape(s)
            }
        """.trimIndent())
        assert(r.isEmpty())
    }

    // --- (c) computed symbol keys in a `{}`-typed pattern --------------------------------

    @Test
    fun `a contextually typed pattern parameter with unique symbol keys is silent`() {
        val r = rows("""
            const A: unique symbol = Symbol();
            const B: unique symbol = Symbol();
            interface C { [A]: string; [B]: number }
            declare const cs: C[];
            cs.forEach(({ [A]: a, [B]: b }) => { a; b });
            declare function take(cb: (c: C) => void): void
            take(function ({ [A]: a }) { a })
        """.trimIndent())
        assert(r.isEmpty())
    }

    @Test
    fun `a contextually typed pattern parameter with a string key reads no empty-object TS2537`() {
        val r = rows("""
            declare const rs: Record<string, number>[];
            declare const k: string
            rs.forEach(({ [k]: v }) => { v })
        """.trimIndent())
        assert(r.none { it.contains("TS2537") })
    }

    @Test
    fun `an uncontextual pattern parameter - unique symbol is TS7031 only and symbol is TS2538`() {
        val r = rows("""
            const A: unique symbol = Symbol();
            declare const sy: symbol
            declare const k: string
            export const f1 = ({ [A]: a }) => a
            export const f4 = ({ [sy]: a }) => a
            export const f2 = ({ [k]: a }) => a
        """.trimIndent())
        assert("4:27 TS7031 Binding element 'a' implicitly has an 'any' type." in r)
        assert("5:23 TS2538 Type 'symbol' cannot be used as an index type." in r)
        assert("6:23 TS2537 Type '{}' has no matching index signature for type 'string'." in r)
        assert(r.none { it.startsWith("4:23") })
        assert(r.none { it.contains("for type 'symbol'") })
    }

    @Test
    fun `a destructured empty literal - unique symbol and symbol keys are TS2538`() {
        val r = rows("""
            const A: unique symbol = Symbol();
            declare const sy: symbol
            const { [A]: v1 } = {}
            const { [sy]: v2 } = {}
        """.trimIndent())
        assert(r == listOf(
            "3:10 TS2538 Type 'unique symbol' cannot be used as an index type.",
            "4:10 TS2538 Type 'symbol' cannot be used as an index type.",
        ))
    }

    // --- (a) the directory augmentation ---------------------------------------------------

    private fun augmentationProject(compilerOptions: String, extra: Map<String, String> = emptyMap()) = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to """{ "compilerOptions": { "strict": true, $compilerOptions }, "include": ["src/**/*.ts"] }""",
            "/proj/src/index.ts" to "export interface Map1 { base: number }\n",
            "/proj/src/mw/t/index.ts" to "export {}\ndeclare module '../..' {\n  interface Map1 { extra: string }\n}\n",
            "/proj/src/use.ts" to "import type { Map1 } from './index.js'\nconst m: Map1 = { base: 1 }\nexport const e: number = ({} as Map1).extra\n",
        ) + extra,
    )

    private fun projectRows(vfs: Vfs): List<String> =
        ProjectCompiler(vfs).build("/proj", noEmit = true).diagnostics
            .map { "${it.fileName?.substringAfter("/proj/")}:${it.line}:${it.character} TS${it.code} ${it.message}" }
            .sorted()

    @Test
    fun `a directory augmentation resolves through index ts and its members reach importers`() {
        val r = projectRows(augmentationProject(""""module": "ES2020", "moduleResolution": "bundler""""))
        assert(r == listOf(
            "src/use.ts:2:7 TS2741 Property 'extra' is missing in type '{ base: number; }' but required in type 'Map1'.",
            "src/use.ts:3:14 TS2322 Type 'string' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `negative control - under nodenext an ESM importer gets no directory resolution`() {
        val r = projectRows(augmentationProject(""""module": "nodenext"""", mapOf("/proj/package.json" to """{ "type": "module" }""")))
        assert(r.any { it.startsWith("src/mw/t/index.ts:2:16 TS2664 Invalid module name in augmentation, module '../..' cannot be found.") })
    }

    @Test
    fun `negative control - a directory with no index file is still TS2664`() {
        val vfs = InMemoryVfs(mapOf(
            "/proj/tsconfig.json" to """{ "compilerOptions": { "strict": true, "module": "ES2020", "moduleResolution": "bundler" }, "include": ["src/**/*.ts"] }""",
            "/proj/src/main.ts" to "export interface Map1 { base: number }\n",
            "/proj/src/mw/t/index.ts" to "export {}\ndeclare module '../..' {\n  interface Map1 { extra: string }\n}\n",
        ))
        assert(projectRows(vfs) == listOf(
            "src/mw/t/index.ts:2:16 TS2664 Invalid module name in augmentation, module '../..' cannot be found.",
        ))
    }
}
