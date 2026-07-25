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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * M1.11 (round 389): call resolution respects lexical shadowing — a call to a name
 * bound by a PARAMETER (identifier, destructured, or fn-valued default), a body-local
 * variable, or a body-nested sibling function must NOT be checked against a same-named
 * file-level/imported/namespace-internal function's signature. tsc's own sources shadow
 * heavily (sys.ts's destructured `setTimeout`, utilities.ts's fn-typed `writeFile`
 * param, emitter.ts's nested `writeFile` sibling vs the utilities import,
 * program.ts's body-local `const fileOrDirectoryExistsUsingSource`) — each drew a
 * wrong-signature TS2554/TS2345 FP. Every suppression here is paired with a negative
 * control proving the check still fires where no shadow exists. Also pins the two
 * arity-model fixes from the same family: constructor-OVERLOAD arity ranges
 * (semver.ts's `Version`) and spread-argument too-few unsoundness
 * (`createDiagnostic(...args)` counts as 1 but expands to N).
 */
class NestedFnShadowingTest {

    private fun compile(@Language("typescript") source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")

    private fun ts2554(r: CompilationResult) = r.diagnostics.filter { it.code == 2554 }

    @Test
    fun `a fn-typed param shadows a file-level function for arity`() {
        // utilities.ts shape: `writeFileEnsuringDirectories(..., writeFile: (a,b,c)=>void)`
        // calls its 3-param PARAM while the file exports a 5-param `writeFile`.
        val r = compile(
            """
            export function writeFile(host: object, diag: object, fileName: string, text: string, bom: boolean): void {}
            export function ensuring(path: string, data: string, writeFile: (path: string, data: string) => void): void {
                writeFile(path, data);
            }
            """.trimIndent()
        )
        have(ts2554(r).isEmpty())
    }

    @Test
    fun `negative control - an unshadowed wrong-arity call still fires`() {
        val r = compile(
            """
            export function writeFile(host: object, diag: object, fileName: string, text: string, bom: boolean): void {}
            export function user(): void {
                writeFile({}, {});
            }
            """.trimIndent()
        )
        have(ts2554(r).any { it.message == "Expected 5 arguments, but got 2." })
    }

    @Test
    fun `a destructured param shadows a file-level declare`() {
        // sys.ts shape: `declare function setTimeout(handler, timeout)` at file level;
        // an enclosing function destructures a rest-taking `setTimeout` from its host param.
        val r = compile(
            """
            declare function setTimeout(handler: (...args: any[]) => void, timeout: number): any;
            interface Host { setTimeout: (handler: (...args: any[]) => void, timeout: number, ...args: any[]) => any; }
            export function watcher({ setTimeout }: Host): void {
                setTimeout(() => {}, 1000, "extra");
            }
            """.trimIndent()
        )
        have(ts2554(r).isEmpty())
    }

    @Test
    fun `a body-local const shadows the enclosing function name`() {
        // program.ts shape: a body-local `const` named like the ENCLOSING function
        // rebinds the name for calls in that body.
        val r = compile(
            """
            declare function pick(isFile: boolean): (x: string) => boolean;
            export function existsUsingSource(fileOrDirectory: string, isFile: boolean): boolean {
                const existsUsingSource = pick(isFile);
                return existsUsingSource(fileOrDirectory);
            }
            export const top = existsUsingSource("p", true);
            """.trimIndent()
        )
        have(ts2554(r).isEmpty())
    }

    @Test
    fun `a namespace-internal function does not leak to file level`() {
        // parser.ts shape: `namespace Parser { function visit(x) {} }` must not
        // hijack the file-level 2-param `visit` for calls OUTSIDE the namespace —
        // while the namespace-scoped signature still governs calls INSIDE it.
        val r = compile(
            """
            function visit(a: number, b: number): void {}
            namespace Q {
                function visit(x: number): void {}
                export function run(): void {
                    visit(1);
                    visit(1, 2, 3);
                }
            }
            visit(1, 2);
            """.trimIndent()
        )
        val msgs = ts2554(r).map { it.message }
        // file-level call must use the file signature (no error) and the in-namespace
        // 3-arg call must fail against the NAMESPACE-local 1-param fn
        assert(msgs == listOf("Expected 1 arguments, but got 3."))
    }

    @Test
    fun `constructor overloads use the arity range`() {
        // semver.ts shape: `constructor(text: string); constructor(major, minor?, ...)` —
        // a call within ANY overload's arity must not be checked against just the first.
        val r = compile(
            """
            export class Version {
                constructor(text: string);
                constructor(major: number, minor?: number, patch?: number);
                constructor(a: string | number, b: number = 0, c: number = 0) {}
            }
            export const v = new Version(1, 2, 3);
            export const w = new Version("1.2.3");
            """.trimIndent()
        )
        have(ts2554(r).isEmpty())
    }

    @Test
    fun `negative control - a single ctor with wrong arity still fires`() {
        val r = compile(
            """
            export class Single {
                constructor(text: string) {}
            }
            export const s = new Single("a", 2);
            """.trimIndent()
        )
        have(ts2554(r).any { it.message == "Expected 1 arguments, but got 2." })
    }

    @Test
    fun `a spread argument suppresses too-few`() {
        // commandLineParser.ts shape: `createDiagnostic(...args)` — the spread counts
        // as one argument but expands to N, so a too-FEW conclusion is unsound.
        val r = compile(
            """
            declare function three(a: string, b: string, c: string): void;
            declare const tup: [string, string, string];
            three(...tup);
            """.trimIndent()
        )
        have(ts2554(r).isEmpty())
    }

    @Test
    fun `negative control - a plain too-few still fires`() {
        val r = compile(
            """
            declare function three(a: string, b: string, c: string): void;
            three("x");
            """.trimIndent()
        )
        have(ts2554(r).any { it.message == "Expected 3 arguments, but got 1." })
    }

    @Test
    fun `a fn default param shadows a file-level function in argument position`() {
        // emitter.ts shape: `getOutputName(input, gcsd = (): string => "")` passes the
        // PARAM through as an argument typed `() => string` — must not resolve the
        // file-level 4-param `gcsd` (TS2345 fn-type mismatch FP).
        val r = compile(
            """
            export function gcsd(options: string, files: string, cwd: string, canonical: string): string { return ""; }
            declare function worker(input: string, cb: () => string): string;
            export function getOutputName(input: string, gcsd = (): string => ""): string {
                return worker(input, gcsd);
            }
            """.trimIndent()
        )
        have(r.diagnostics.none { it.code == 2345 })
    }

    @Test
    fun `negative control - a file-level fn as a wrong argument still fires`() {
        val r = compile(
            """
            export function gcsd(options: string, files: string, cwd: string, canonical: string): string { return ""; }
            declare function worker(input: string, cb: () => string): string;
            export const out = worker("in", gcsd);
            """.trimIndent()
        )
        have(r.diagnostics.any { it.code == 2345 })
    }

    // --- Type path: a body-nested SIBLING function shadows a same-named IMPORT
    // (emitter.ts:1331 — nested `writeFile(sourceFile, writer, undefined)` vs the
    // imported utilities `writeFile(host, diag, fileName: string, …)` whose 3rd
    // param drew the FP TS2345 'undefined' ≁ 'string').

    private fun buildProject(mainTs: String): ProjectCompiler.Result {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to
                    """{ "compilerOptions": { "strict": true, "outDir": "./dist" }, "include": ["src/**/*.ts"] }""",
                "/proj/src/util.ts" to
                    "export function writeFile(host: { h: number }, diag: { d: number }, fileName: string): void {}",
                "/proj/src/main.ts" to mainTs,
            )
        )
        return ProjectCompiler(vfs).build("/proj", noEmit = true)
    }

    @Test
    fun `a nested sibling function shadows an import on the type path`() {
        val r = buildProject(
            """
            import { writeFile } from "./util.js";
            export function createPrinter(): void {
                function printFile(sf: { h: number }, w: { d: number }): void {
                    writeFile(sf, w, undefined);
                }
                function writeFile(sf: { h: number }, w: { d: number }, gen: string | undefined): void {}
                printFile({ h: 1 }, { d: 2 });
            }
            """.trimIndent()
        )
        have(r.diagnostics.none { it.code == 2345 })
    }

    @Test
    fun `negative control - an imported fn with an undefined argument still fires`() {
        val r = buildProject(
            """
            import { writeFile } from "./util.js";
            writeFile({ h: 1 }, { d: 2 }, undefined);
            """.trimIndent()
        )
        have(r.diagnostics.any { it.code == 2345 && it.message.contains("'undefined'") })
    }
}
