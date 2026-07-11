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
 * Round 475, two assignability-pass fixes:
 *
 * 1. Binding-pattern params register their element names into `currentLocalTypes`
 *    (`registerBindingPatternParamLocals`) — previously NOTHING was registered, so a
 *    destructured element name read in the body (e.g. an object-literal SHORTHAND value)
 *    fell through `getTypeOfIdentifier` to the merged globals and resolved a same-named
 *    CROSS-FILE function → FP TS2322 (tsc editorServices.ts convertTypeAcquisition /
 *    session.ts referenceEntryToReferencesResponseItem).
 *
 * 2. An object-literal ARG spreading an any/error-typed value is `any` in tsc (the
 *    spread poisons the literal) — the missing-props TS2345 arg branch now bails
 *    (tsc session.ts:1469 `this.getRange({ file: fileName, ...range }, …)`).
 */
class BindingPatternParamAndSpreadArgTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "entry.ts").diagnostics

    @Test
    fun `destructured param shorthand does not resolve a cross-file same-named function`() {
        compile(
            """
            // @strict: true

            // @Filename: utils.ts
            export function enable(system: string): boolean {
                return system.length > 0;
            }

            // @Filename: main.ts
            export interface TypeAcquisition {
                enable?: boolean;
                include?: string[];
            }
            export interface Data {
                enable: boolean | undefined;
                include: boolean;
            }
            export function convert({ enable, include }: TypeAcquisition): Data {
                return {
                    enable,
                    include: include !== undefined && include.length !== 0,
                };
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a genuinely wrong destructured member type still fires`() {
        compile(
            """
            // @strict: true

            // @Filename: main.ts
            export interface Args { flag?: boolean; }
            export interface Data { flag: string; }
            export function convert({ flag }: Args): Data {
                return { flag };
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `object literal arg with a spread of an untyped value - no TS2345 missing props`() {
        compile(
            """
            // @strict: true

            // @Filename: main.ts
            export interface FileRangeRequestArgs {
                file: string;
                startLine: number;
                endLine: number;
            }
            interface TextRange { pos: number; end: number; }
            declare class ScriptInfo { x: number; }
            export class Session {
                private getRange(args: FileRangeRequestArgs, scriptInfo: ScriptInfo): TextRange {
                    return { pos: args.startLine, end: args.endLine };
                }
                check(fileName: string, ranges: { startLine: number; endLine: number; }[] | undefined, scriptInfo: ScriptInfo): TextRange[] | undefined {
                    if (ranges) {
                        return ranges.map(range => this.getRange({ file: fileName, ...range }, scriptInfo));
                    }
                    return undefined;
                }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - object literal arg without spread still fires missing props`() {
        compile(
            """
            // @strict: true

            // @Filename: main.ts
            export interface FileRangeRequestArgs {
                file: string;
                startLine: number;
                endLine: number;
            }
            declare function getRange(args: FileRangeRequestArgs): void;
            export function check(fileName: string): void {
                getRange({ file: fileName });
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
