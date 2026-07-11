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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 475 batch 3 — four more server burn-down families:
 *  - `<literal-union> || "literal"` keeps the right operand's literal type when the
 *    kept left is all string-literals (tsc editorServices telemetry configFileName);
 *  - `resolveMemberPropertyType` resolves `(A | B).p` as `A.p | B.p` (union-annotated
 *    param member switch — tsc utilities.ts getNewLineCharacter);
 *  - a REST-param target signature provides unbounded args — no arity failure
 *    (tsc server utilities.ts ThrottledOperations.run vs host.setTimeout);
 *  - a cross-file CLASS X + `interface X` merge is a conflation: a returned object
 *    literal satisfying the interface-declaring file's version passes (tsc
 *    services/utilities.ts createTextChange vs scriptVersionCache's class TextChange).
 */
class Round475BatchThreeTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "entry.ts").diagnostics

    @Test
    fun `literal-union OR literal keeps the right literal - no TS2322 on return`() {
        diagnose(
            """
            declare function getBaseConfigFileName(path: string): "tsconfig.json" | "jsconfig.json" | undefined;
            export function configFileName(path: string): "tsconfig.json" | "jsconfig.json" | "other" {
                return getBaseConfigFileName(path) || "other";
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - OR literal outside the target union still fires`() {
        diagnose(
            """
            declare function getBaseConfigFileName(path: string): "tsconfig.json" | "jsconfig.json" | undefined;
            export function configFileName(path: string): "tsconfig.json" | "jsconfig.json" | "other" {
                return getBaseConfigFileName(path) || "bogus";
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `union-annotated param member switch covering the domain - no TS2366`() {
        diagnose(
            """
            const enum NewLineKind { CarriageReturnLineFeed = 0, LineFeed = 1 }
            interface CompilerOptions { newLine?: NewLineKind; other?: string; }
            interface PrinterOptions { newLine?: NewLineKind; }
            export function getNewLineCharacter(options: CompilerOptions | PrinterOptions): string {
                switch (options.newLine) {
                    case NewLineKind.CarriageReturnLineFeed:
                        return "\r\n";
                    case NewLineKind.LineFeed:
                    case undefined:
                        return "\n";
                }
            }
            """
        ) should {
            have(none { it.code == 2366 })
        }
    }

    @Test
    fun `negative control - union param member switch missing a case keeps TS2366`() {
        diagnose(
            """
            const enum NewLineKind { CarriageReturnLineFeed = 0, LineFeed = 1 }
            interface CompilerOptions { newLine?: NewLineKind; }
            interface PrinterOptions { newLine?: NewLineKind; }
            export function getNewLineCharacter(options: CompilerOptions | PrinterOptions): string {
                switch (options.newLine) {
                    case NewLineKind.CarriageReturnLineFeed:
                        return "\r\n";
                    case NewLineKind.LineFeed:
                        return "\n";
                }
            }
            """
        ) should {
            have(any { it.code == 2366 })
        }
    }

    @Test
    fun `rest-param target accepts a multi-required-param source - no TS2345`() {
        diagnose(
            """
            interface Host { setTimeout(cb: (...args: any[]) => void, ms: number, ...args: any[]): any; }
            class ThrottledOperations {
                private static run(operationId: string, self: ThrottledOperations, cb: () => void): void {
                    cb();
                }
                schedule(host: Host, operationId: string, delay: number, cb: () => void): void {
                    host.setTimeout(ThrottledOperations.run, delay, operationId, this, cb);
                }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - fixed-arity target still rejects a needier source`() {
        diagnose(
            """
            declare function once(cb: (a: string) => void): void;
            function needsThree(a: string, b: number, c: boolean): void {}
            once(needsThree);
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `cross-file class+interface merge - returned objlit satisfies the interface`() {
        compile(
            """
            // @strict: true

            // @Filename: types.ts
            export interface TextSpan { start: number; length: number; }
            export interface TextChange { span: TextSpan; newText: string; }

            // @Filename: scriptVersionCache.ts
            export const marker = 1;
            class TextChange {
                constructor(public pos: number, public deleteLen: number) {}
                getTextChangeRange(): number { return this.pos + this.deleteLen; }
            }
            export function makeEdit(pos: number, deleteLen: number): number {
                return new TextChange(pos, deleteLen).getTextChangeRange();
            }

            // @Filename: utilities.ts
            import { TextChange, TextSpan } from "./types.js";
            export function createTextChange(span: TextSpan, newText: string): TextChange {
                return { span, newText };
            }
            """
        ) should {
            have(none { it.code == 2739 })
            have(none { it.code == 2740 })
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - objlit missing the interface's own member still fires`() {
        compile(
            """
            // @strict: true

            // @Filename: types.ts
            export interface TextSpan { start: number; length: number; }
            export interface TextChange { span: TextSpan; newText: string; }

            // @Filename: scriptVersionCache.ts
            export const marker = 1;
            class TextChange {
                constructor(public pos: number, public deleteLen: number) {}
            }
            export function makeEdit(pos: number, deleteLen: number): TextChange {
                return new TextChange(pos, deleteLen);
            }

            // @Filename: utilities.ts
            import { TextChange, TextSpan } from "./types.js";
            export function createTextChange(span: TextSpan): TextChange {
                return { span };
            }
            """
        ) should {
            have(any { it.code == 2739 || it.code == 2741 || it.code == 2322 })
        }
    }
}
