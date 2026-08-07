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
 * Round 477 (Blocker #3, tsc editorServices.ts:1253): the objlit-vs-union member
 * selection reads a NAMESPACE-IMPORT-QUALIFIED string-literal alias:
 *
 * - a member annotation `readonly eventName: protocol.CloseFileWatcherEventName`
 *   yields its `lit:s:` key via [resolveNamespaceQualifiedTypeAlias] (the
 *   [enumMemberKeysOfTypeNode] QualifiedName arm's non-enum fallback);
 * - a const annotated with the same qualified alias (`export const
 *   CloseFileWatcherEvent: protocol.CloseFileWatcherEventName =
 *   "closeFileWatcher"`) is indexed by [topLevelConstStringValues]
 *   (annotationAgrees' qualified arm);
 * - [checkExcessProperties]' UNION nested descent drills the
 *   DISCRIMINANT-matched constituent, not the first-with-the-prop.
 */
class NsQualifiedDiscriminantSelectionTest {

    private val decls = """
        // @module: nodenext
        // @strict: true
        // @filename: protocol.ts
        export type LargeFileEventName = "largeFile";
        export type CloseWatcherEventName = "closeWatcher";
        export interface CloseWatcherEventBody {
            readonly id: number;
        }
        // @filename: events.ts
        import * as protocol from "./protocol.js";
        export const LargeFileEvent: protocol.LargeFileEventName = "largeFile";
        export const CloseWatcherEvent: protocol.CloseWatcherEventName = "closeWatcher";
        export interface LargeFileEvent {
            eventName: protocol.LargeFileEventName;
            data: { file: string; fileSize: number; maxFileSize: number; };
        }
        export interface CloseWatcherEvent {
            readonly eventName: protocol.CloseWatcherEventName;
            readonly data: protocol.CloseWatcherEventBody;
        }
        export type ServiceEvent = LargeFileEvent | CloseWatcherEvent;
        export type ServiceEventHandler = (event: ServiceEvent) => void;
    """.trimIndent()

    @Test
    fun `nested objlit checks against the discriminant-matched union member`() {
        diagnose(
            decls + """

            // @filename: main.ts
            import { CloseWatcherEvent, ServiceEventHandler } from "./events.js";
            export function fire(handler: ServiceEventHandler, id: number): void {
                handler({ eventName: CloseWatcherEvent, data: { id } });
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2353 || it.code == 2561 || it.code == 2345 || it.code == 2322 })
        }
    }

    @Test
    fun `chimera union member - literal exactly satisfying one file's version passes`() {
        // protocol.ts ALSO declares interfaces named LargeFileEvent / CloseWatcherEvent
        // (the tsc server shape): the merged chimera demands BOTH files' members, so the
        // structural union-arg test fails — the round-477 conflated-declaration gate
        // accepts a literal that EXACTLY satisfies one declaring file's version.
        diagnose(
            """
            // @module: nodenext
            // @filename: protocol.ts
            export type LargeFileEventName = "largeFile";
            export type CloseWatcherEventName = "closeWatcher";
            export interface CloseWatcherEventBody {
                readonly id: number;
            }
            export interface LargeFileEvent {
                event: LargeFileEventName;
                body: { file: string; };
            }
            export interface CloseWatcherEvent {
                event: CloseWatcherEventName;
                body: CloseWatcherEventBody;
            }
            // @filename: events.ts
            import * as protocol from "./protocol.js";
            export const LargeFileEvent: protocol.LargeFileEventName = "largeFile";
            export const CloseWatcherEvent: protocol.CloseWatcherEventName = "closeWatcher";
            export interface LargeFileEvent {
                eventName: protocol.LargeFileEventName;
                data: { file: string; fileSize: number; maxFileSize: number; };
            }
            export interface CloseWatcherEvent {
                readonly eventName: protocol.CloseWatcherEventName;
                readonly data: protocol.CloseWatcherEventBody;
            }
            export type ServiceEvent = LargeFileEvent | CloseWatcherEvent;
            export type ServiceEventHandler = (event: ServiceEvent) => void;

            // @filename: main.ts
            import { CloseWatcherEvent, ServiceEventHandler } from "./events.js";
            export function fire(handler: ServiceEventHandler, id: number): void {
                handler({ eventName: CloseWatcherEvent, data: { id } });
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2353 || it.code == 2561 || it.code == 2345 || it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a top-level excess key still fires`() {
        // (A NESTED excess key inside a structurally-assignable member is a
        // PRE-EXISTING FN on the union-arg path — the `noUnionExcess &&
        // structurallyAssignable` gate returns before the excess check runs, so
        // only the top-level union-excess shape is pinnable here.)
        diagnose(
            decls + """

            // @filename: main.ts
            import { CloseWatcherEvent, ServiceEventHandler } from "./events.js";
            export function fire(handler: ServiceEventHandler, id: number): void {
                handler({ eventName: CloseWatcherEvent, data: { id }, bogus: 1 });
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2353 || it.code == 2561 || it.code == 2345 || it.code == 2322 })
        }
    }
}
