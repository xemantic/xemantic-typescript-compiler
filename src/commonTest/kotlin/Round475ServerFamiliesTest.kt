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
 * Round 475 — the server project.ts burn-down families (tsc server/project.ts):
 *  - `A && B` types as `falsy(A) | B` (the left's definitely-falsy members flow through);
 *  - an OPTIONAL bodyless class method (`m?(): boolean;`) needs no implementation (TS2391);
 *  - a MUTABLE un-annotated literal-initialized property override widens (`override p = true`
 *    vs base `p = false` — both `boolean`, no TS2416);
 *  - a ctor-body `switch` assigning a property in its clauses satisfies TS2564;
 *  - a class-property initializer carrying an un-inferred foreign TP bails (maybeBind).
 * (The `x.p = expr!` NonNull-RHS narrowing arm of rhsIsDefinitelyNonNullish is pinned by
 * the server dashboard — project.ts:1694 — small-test emitters don't reach that path.)
 */
class Round475ServerFamiliesTest {

    @Test
    fun `logical AND keeps the left's falsy part - assignment of undefined stays legal`() {
        diagnose(
            """
            interface State { x: number; }
            interface Prog { state: State; }
            declare const oldProgram: Prog | undefined;
            let oldState = oldProgram && oldProgram.state;
            oldState = undefined;
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - AND of two non-nullish operands stays non-nullish`() {
        diagnose(
            """
            interface State { x: number; }
            declare const a: State;
            declare const b: State;
            let both = a && b;
            both = undefined;
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `optional bodyless class method needs no implementation - no TS2391`() {
        diagnose(
            """
            class Project {
                useSourceOfProjectReferenceRedirect?(): boolean;
                getParsedCommandLine?(fileName: string): string | undefined;
                isOrphan(): boolean { return false; }
            }
            """
        ) should {
            have(none { it.code == 2391 })
        }
    }

    @Test
    fun `negative control - non-optional bodyless method without impl still fires`() {
        // The wrong-name-follows shape reports TS2389; a trailing bodyless method
        // reports TS2391 — either way the non-optional signature must keep erroring.
        diagnose(
            """
            class Project {
                useSourceOfProjectReferenceRedirect(): boolean;
                isOrphan(): boolean { return false; }
            }
            """
        ) should {
            have(any { it.code == 2391 || it.code == 2389 })
        }
    }

    @Test
    fun `mutable literal-initialized property override widens - no TS2416`() {
        diagnose(
            """
            class Project {
                initialLoadPending = false;
            }
            class ConfiguredProject extends Project {
                override initialLoadPending = true;
            }
            """
        ) should {
            have(none { it.code == 2416 })
        }
    }

    @Test
    fun `negative control - readonly literal-initialized override keeps the literal comparison`() {
        diagnose(
            """
            class Project {
                readonly initialLoadPending = false;
            }
            class ConfiguredProject extends Project {
                override readonly initialLoadPending = true;
            }
            """
        ) should {
            have(any { it.code == 2416 })
        }
    }

    @Test
    fun `ctor switch assigning a property in every clause satisfies TS2564`() {
        diagnose(
            """
            declare function assertNever(x: never): never;
            const enum Mode { Semantic, Syntactic }
            class Project {
                public languageServiceEnabled: boolean;
                constructor(mode: Mode) {
                    switch (mode) {
                        case Mode.Semantic:
                            this.languageServiceEnabled = true;
                            break;
                        case Mode.Syntactic:
                            this.languageServiceEnabled = false;
                            break;
                        default:
                            assertNever(mode);
                    }
                }
            }
            """
        ) should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `negative control - property never assigned still fires TS2564`() {
        diagnose(
            """
            class Project {
                public languageServiceEnabled: boolean;
                constructor() {}
            }
            """
        ) should {
            have(any { it.code == 2564 })
        }
    }

    @Test
    fun `class property initializer with un-inferred generic call result - no TS2322`() {
        diagnose(
            """
            declare function maybeBind<T, A extends unknown[], R>(obj: T, fn: ((this: T, ...args: A) => R) | undefined): ((...args: A) => R) | undefined;
            interface Host { createHash?(data: string): string; }
            class Project {
                constructor(readonly host: Host) {}
                createHash: ((data: string) => string) | undefined = maybeBind(this.host, this.host.createHash);
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }


    @Test
    fun `objlit union member selection by const-string discriminant - no TS2353`() {
        // The round-473 const-string discriminant key space wired into the
        // objlit-vs-union member selection: `{ eventName: CloseFileWatcherEvent,
        // data: { id } }` must select the CloseFileWatcherEvent member, not FP excess
        // 'id' against another member's data shape (tsc editorServices.ts:1253).
        diagnose(
            """
            const LargeFileEvent = "largeFileReferenced";
            interface LargeFileEvent {
                readonly eventName: typeof LargeFileEvent;
                readonly data: { file: string; fileSize: number; };
            }
            const CloseFileWatcherEvent = "closeFileWatcher";
            interface CloseFileWatcherEvent {
                readonly eventName: typeof CloseFileWatcherEvent;
                readonly data: { id: number; };
            }
            type ServiceEvent = LargeFileEvent | CloseFileWatcherEvent;
            declare function handle(event: ServiceEvent): void;
            export function notify(id: number): void {
                handle({ eventName: CloseFileWatcherEvent, data: { id } });
            }
            """
        ) should {
            have(none { it.code == 2353 })
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - objlit not matching the selected member's data still fires`() {
        diagnose(
            """
            const LargeFileEvent = "largeFileReferenced";
            interface LargeFileEvent {
                readonly eventName: typeof LargeFileEvent;
                readonly data: { file: string; fileSize: number; };
            }
            const CloseFileWatcherEvent = "closeFileWatcher";
            interface CloseFileWatcherEvent {
                readonly eventName: typeof CloseFileWatcherEvent;
                readonly data: { id: number; };
            }
            type ServiceEvent = LargeFileEvent | CloseFileWatcherEvent;
            declare function handle(event: ServiceEvent): void;
            export function notify(id: number): void {
                handle({ eventName: CloseFileWatcherEvent, data: { bogus: id } });
            }
            """
        ) should {
            have(any { it.code == 2353 || it.code == 2322 || it.code == 2345 || it.code == 2739 || it.code == 2741 })
        }
    }
}
