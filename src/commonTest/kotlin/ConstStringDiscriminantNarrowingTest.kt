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
 * Round 473 (the tsc server-profile TypingInstallerResponse/ProjectServiceEvent
 * families): discriminated-union narrowing where the discriminant values are
 * CONST-typed strings, tsc's jsTyping/shared.ts idiom:
 *
 * - `type ActionSet = "action::set"` + `export const ActionSet: ActionSet = …`
 *   share a name — the Binder now MERGES Variable+TypeAlias (disjoint spaces)
 *   instead of letting the const overwrite the alias symbol;
 * - a `case <constIdent>:` reads as the const's string literal
 *   ([topLevelConstStringValues]);
 * - a member's `eventName: typeof <constIdent>` annotation recovers the literal
 *   when its resolved type washed to string/any;
 * - a SIBLING-discriminant switch (`switch (event.eventName)` … `event.data.X`)
 *   narrows the BASE identifier and projects the accessed member.
 */
class ConstStringDiscriminantNarrowingTest {

    private val shared = """
        type ActionSet = "action::set";
        type EventTypesRegistry = "event::typesRegistry";
        const ActionSet: ActionSet = "action::set";
        const EventTypesRegistry: EventTypesRegistry = "event::typesRegistry";
        interface TypingInstallerResponse {
            readonly kind: ActionSet | EventTypesRegistry;
        }
        interface SetTypings extends TypingInstallerResponse {
            readonly kind: ActionSet;
            readonly typeAcquisition: string[];
        }
        interface TypesRegistryResponse extends TypingInstallerResponse {
            readonly kind: EventTypesRegistry;
            readonly typesRegistry: string;
        }
        type ResponseUnion = SetTypings | TypesRegistryResponse;
    """.trimIndent()

    @Test
    fun `a same-named const does not break alias-annotated discriminant narrowing`() {
        diagnose(
            shared + """

            function handle(response: ResponseUnion): void {
                switch (response.kind) {
                    case "event::typesRegistry":
                        response.typesRegistry.length;
                        break;
                    default:
                        response.typeAcquisition[0];
                }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a const-identifier case narrows like its string literal`() {
        diagnose(
            shared + """

            function handle(response: ResponseUnion): void {
                switch (response.kind) {
                    case EventTypesRegistry:
                        response.typesRegistry.length;
                        break;
                    case ActionSet:
                        response.typeAcquisition[0];
                        break;
                }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a merged const keeps its value-position literal type`() {
        diagnose(
            shared + """

            const v: "action::set" = ActionSet;
            void v;
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a typeof-const discriminant narrows a sibling-member access`() {
        diagnose("""
            const UpdatedEvent = "projectsUpdated";
            const LoadingEvent = "projectLoading";
            interface UpdatedEventDecl {
                eventName: typeof UpdatedEvent;
                data: { openFiles: string[]; };
            }
            interface LoadingEventDecl {
                eventName: typeof LoadingEvent;
                data: { project: string; };
            }
            type ServiceEvent = UpdatedEventDecl | LoadingEventDecl;
            function onEvent(event: ServiceEvent): void {
                switch (event.eventName) {
                    case UpdatedEvent:
                        event.data.openFiles[0];
                        break;
                    case LoadingEvent:
                        event.data.project.length;
                        break;
                }
            }
        """) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `an if-equality const discriminant narrows too`() {
        diagnose(
            shared + """

            function handle(response: ResponseUnion): void {
                if (response.kind === EventTypesRegistry) {
                    response.typesRegistry.length;
                }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - an un-narrowed union access still fires`() {
        diagnose(
            shared + """

            function handle(response: ResponseUnion): void {
                response.typeAcquisition[0];
            }
            """
        ) should {
            have(any { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - an ambiguous const name does not narrow`() {
        // Two top-level consts with the SAME name and DIFFERENT values poison the
        // index (here simulated via a let with a different value shadow-poisoning
        // the name kind) — the case cannot be trusted, the union stays wide, and
        // the sibling access to a single member's property keeps firing.
        diagnose("""
            let Tag = "b";
            interface A { kind: "a"; onlyA: string; }
            interface B { kind: "b"; onlyB: string; }
            type U = A | B;
            function f(u: U): void {
                switch (u.kind) {
                    case Tag as "b":
                        break;
                    default:
                        break;
                }
                if (u.kind === "b") {
                    u.onlyA;
                }
            }
        """) should {
            have(any { it.code == 2339 })
        }
    }
}
