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
 * (CHK.49) A MODULE FILE'S OWN DECLARATION OF A LIB GLOBAL NAME IS MODULE-SCOPED,
 * AND MERGING IT INTO THE LIB SYMBOL CORRUPTED THE LIB SYMBOL **PROGRAM-WIDE**.
 *
 * `mergeSingleSymbol` ADOPTS (round 884: `globals[name]` IS the binder's object),
 * so `interface Text { … }` written in one module file grew the DOM `Text`
 * symbol's declaration list and every OTHER file in the program then saw the
 * fusion. Both directions were wrong:
 *
 *  * a value of the module's own shape was rejected against the module's own
 *    interface, because the parameter type had picked up the lib's members; and
 *  * — the dangerous one — a read of a lib member off the module's own type was
 *    SILENT, because the module's interface answered the lib type's members.
 *
 * The population is every lib global name: 253 for a plain `es2020` project and
 * 2,505 with `dom`. `Text`, `Event`, `Request`, `Response`, `File`, `Range`,
 * `Selection`, `Node`, `Element`, `Date`, `Map`, `Set` are ordinary domain nouns,
 * and `jsonrepair`'s `export interface Text { charCodeAt: … }` is a shipped
 * instance — all 7 of its TS2345 rows were this.
 *
 * ## What the corpus and the dashboard could not see
 *
 * The eight tsc-source profiles collide on exactly two names (`Symbol` in
 * `types.ts`, and `ImportAttributes`), and neither shape reaches the member-read
 * direction; the embedded lib the generated corpus compiles against is small.
 * So the instrument for this defect is a real library plus these pins.
 *
 * ## Why the pins use `Date` and not `Text`
 *
 * `Date` is an `es2020` lib global, so the fixtures need `@useRealLibs` but not
 * `@lib: dom`, and `Date` exercises the harder half of the mechanism as well: the
 * lib declares BOTH `interface Date` and `declare var Date: DateConstructor`, so
 * a TYPE-only shadow must leave the VALUE meaning reachable. `Event` is pinned
 * separately as the generalisation, since the queue item's own repro used a DOM
 * name.
 *
 * Every "stays silent" pin below has a named falsifying arm in the session note;
 * the ones labelled CONTROL are green on every arm by construction and are NOT
 * counted as coverage.
 */
class LibGlobalNameShadowTest {

    private val realLibs = "// @strict: true\n// @useRealLibs: true"
    private val realLibsDom = "// @strict: true\n// @useRealLibs: true\n// @lib: es2020,dom"

    /**
     * Direction 1 — a value of the module's OWN shape is accepted by the module's
     * own interface. Before the fix the parameter type was the lib `Date` fused
     * with this declaration, so the argument was missing every lib member.
     */
    @Test
    fun `a module-local interface shadowing a lib global accepts its own shape`() {
        val diagnostics = diagnose(
            """
            export interface Date { zzzUnique: number }
            declare function zzzWant(t: Date): void
            export function zzzP() { zzzWant({ zzzUnique: 1 }) }
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2345 })
        assert(diagnostics.none { it.code == 2353 })
    }

    /**
     * Direction 2 — THE DANGEROUS ONE. A read of a LIB member off the
     * module-local type must be reported: the module's `Date` has no `getTime`.
     * Silence here is a wrong type reaching an editor and a compile alike.
     */
    @Test
    fun `a lib member read off a module-local shadow is reported`() {
        val diagnostics = diagnose(
            """
            export interface Date { zzzUnique: number }
            declare const zzzT: Date
            export const zzzW = zzzT.getTime()
            """,
            directives = realLibs,
        )
        val ts2339 = diagnostics.filter { it.code == 2339 }
        assert(ts2339.size == 1)
        assert(ts2339.single().message == "Property 'getTime' does not exist on type 'Date'.")
    }

    /**
     * The generalisation the queue item names: a DOM global behaves like an
     * `es2020` one. `Event` rather than `Text` so the two pins do not share a
     * name with the class KDoc's running example.
     */
    @Test
    fun `the same holds for a DOM global`() {
        val diagnostics = diagnose(
            """
            export interface Event { zzzUnique: number }
            declare function zzzWant(t: Event): void
            export function zzzP() { zzzWant({ zzzUnique: 1 }) }
            declare const zzzT: Event
            export const zzzW = zzzT.bubbles
            """,
            directives = realLibsDom,
        )
        assert(diagnostics.none { it.code == 2345 })
        val ts2339 = diagnostics.filter { it.code == 2339 }
        assert(ts2339.size == 1)
        assert(ts2339.single().message == "Property 'bubbles' does not exist on type 'Event'.")
    }

    /**
     * The PROGRAM-WIDE half: a file that declares nothing must keep the pristine
     * lib type. Before the fix `b.ts`'s declaration had been adopted into the lib
     * symbol itself, so `a.ts` — which never mentions the shadow — saw the fusion
     * and this assignment was silent.
     *
     * The assertion is an ASSIGNMENT and not a member read: this checker does not
     * report a missing member on the real `Date` interface (measured — a separate,
     * pre-existing gap), so a `zzzT.zzzUnique` probe would be vacuous in both
     * arms.
     */
    @Test
    fun `a foreign module's shadow does not leak into another file's lib type`() {
        val diagnostics = diagnose(
            """
            // @Filename: b.ts
            export interface Date { zzzUnique: number }
            export const zzzQ = 1
            // @Filename: a.ts
            import { zzzQ } from "./b"
            declare const zzzDom: Date
            export const zzzBad: { zzzUnique: number } = zzzDom
            export const zzzR = zzzQ
            """,
            directives = realLibs,
        )
        val rows = diagnostics.filter { it.code == 2739 || it.code == 2741 }
        assert(rows.size == 1)
        assert("zzzUnique" in rows.single().message)
    }

    /**
     * The VALUE meaning survives a TYPE-only shadow. A module's own
     * `interface Map<K, V>` hides the lib TYPE and leaves `declare var Map:
     * MapConstructor` reachable, so `new Map()` still constructs.
     *
     * This is the half that made the naive retire cost three corpus subtests
     * (`isolatedModulesShadowGlobalTypeNotValue`): without it the shadow hid both
     * meanings and `new` reported TS2351.
     */
    @Test
    fun `a type-only shadow leaves the lib value meaning reachable`() {
        val diagnostics = diagnose(
            """
            export interface Map<K, V> { zzzOwn: K }
            export function zzzG() { return new Map<string, number>() }
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2351 })
        assert(diagnostics.none { it.code == 2350 })
    }

    /**
     * …and the value meaning survives in a MEMBER READ and in an ARGUMENT, not
     * only under `new`.
     *
     * The TS2345 half is a POSITIVE pin — passing the shadowed `Date` where a
     * `DateConstructor` is wanted was a false positive on the (CHK.49) PARENT
     * too, because the merged symbol's VALUE side lost to its TYPE side there.
     * The TS2339 half is the refusal direction and its falsifying arm is a3
     * (the second chance moved back below the file-level type map, where the
     * shadow's own entry answers first).
     */
    @Test
    fun `a shadowed lib global keeps its value meaning in a member read and an argument`() {
        val diagnostics = diagnose(
            """
            export interface Date { zzzUnique: number }
            export const zzzNow: number = Date.now()
            export function zzzTakes(c: DateConstructor) { return c }
            export const zzzPassed = zzzTakes(Date)
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2339 })
        assert(diagnostics.none { it.code == 2345 })
    }

    /** …and the same when the TYPE-only shadow arrives as an IMPORT rather than a
     *  declaration: the alias is resolved onward before its meanings are read. */
    @Test
    fun `an imported type-only shadow leaves the lib value meaning reachable`() {
        val diagnostics = diagnose(
            """
            // @Filename: zzzTypes.ts
            export interface Date { zzzDay: number }
            // @Filename: zzzUse.ts
            import { Date } from "./zzzTypes"
            export function zzzFoo(a: Date) { return new Date().getTime() + a.zzzDay }
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2351 })
    }

    /**
     * The second chance must NOT fire for a shadow that HAS a value meaning of
     * its own: a module's `const Date = { … }` is the value `Date` in that file,
     * and handing the lib's constructor back would be the defect inverted.
     * Falsifying arm a7 (the helper's two value-meaning refusals dropped).
     */
    @Test
    fun `a VALUE shadow of a lib global is not overridden by the lib value`() {
        val diagnostics = diagnose(
            """
            export const Date = { zzzOwn: 1 }
            export const zzzA: number = Date.zzzOwn
            export const zzzB = Date.now()
            """,
            directives = realLibs,
        )
        val ts2339 = diagnostics.filter { it.code == 2339 }
        assert(ts2339.size == 1)
        assert(ts2339.single().message == "Property 'now' does not exist on type '{ zzzOwn: number; }'.")
    }

    /**
     * …and the same when the value shadow arrives as an IMPORT. The identifier
     * position hands the helper the RAW alias, so the alias must be resolved
     * onward before its meanings are read. Falsifying arm a6 (that hop dropped).
     */
    @Test
    fun `an imported VALUE shadow of a lib global is not overridden by the lib value`() {
        val diagnostics = diagnose(
            """
            // @Filename: zzzVals.ts
            export const Date = { zzzOwn: 1 }
            // @Filename: zzzUse.ts
            import { Date } from "./zzzVals"
            export const zzzA: number = Date.zzzOwn
            export const zzzB = Date.now()
            """,
            directives = realLibs,
        )
        val ts2339 = diagnostics.filter { it.code == 2339 }
        assert(ts2339.size == 1)
        assert(ts2339.single().message == "Property 'now' does not exist on type '{ zzzOwn: number; }'.")
    }

    /**
     * A namespace-qualified heritage target resolves through the file's OWN
     * namespace. `resolveHeritageBaseSymbol`'s Identifier root was a raw `globals`
     * consult, so before (CHK.49) this worked ONLY while the namespace's name
     * collided with a lib global and the merge fused the two — spelled `zzzNsp`
     * it silently found nothing and the TS2420 was never emitted.
     */
    @Test
    fun `a namespace-qualified implements target resolves per file`() {
        val diagnostics = diagnose(
            """
            export declare class zzzNsp<R> implements zzzNsp.Thenable<R> {
                zzzOnly(): void
            }
            export declare namespace zzzNsp {
                interface Thenable<R> { zzzThen(): void }
            }
            """,
            directives = realLibs,
        )
        val ts2420 = diagnostics.filter { it.code == 2420 }
        assert(ts2420.size == 1)
        assert("zzzNsp<R>" in ts2420.single().message)
    }

    /** …and the lib-colliding spelling of the identical shape keeps working. */
    @Test
    fun `a namespace-qualified implements target still resolves when it collides with a lib global`() {
        val diagnostics = diagnose(
            """
            export declare class Promise<R> implements Promise.Thenable<R> {
                zzzOnly(): void
            }
            export declare namespace Promise {
                interface Thenable<R> { zzzThen(): void }
            }
            """,
            directives = realLibs,
        )
        val ts2420 = diagnostics.filter { it.code == 2420 }
        assert(ts2420.size == 1)
        assert("Promise<R>" in ts2420.single().message)
    }

    /**
     * MUST-STILL-MERGE control 1 — a global SCRIPT file (no import/export) DOES
     * contribute to the global namespace, so its `interface Date` augmentation is
     * a genuine declaration merge and both members must be visible.
     */
    @Test
    fun `CONTROL - a global script file still merges into the lib global`() {
        val diagnostics = diagnose(
            """
            interface Date { zzzUnique: number }
            declare const zzzT: Date
            const zzzA: number = zzzT.zzzUnique
            const zzzB: number = zzzT.getTime()
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2339 })
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * MUST-STILL-MERGE control 2 — `declare global { }` inside a MODULE is the
     * deliberate global contribution INV.3(d) keeps (`moduleLocalContributesGlobally`
     * answers true for the name `global`), and it must keep merging.
     *
     * (CHK.49) could only assert this on a `var`, because
     * `declare global { interface Date { … } }` did not reach `globals` at all —
     * queued as (CHK.50) rather than pinned, since a pin on a known-open gap is a
     * countdown and not a guard (round 765). (CHK.50) LANDED, so the assertion is
     * now a WRITE probe: cross-file, `zzzGlobalVar` typed silently `any` before
     * that round and this pin would have been vacuous either way.
     */
    @Test
    fun `CONTROL - a declare global block in a module still merges into the lib globals`() {
        val diagnostics = diagnose(
            """
            // @Filename: zzzAug.ts
            export {}
            declare global { var zzzGlobalVar: number }
            // @Filename: zzzUse.ts
            export {}
            export const zzzA: number = zzzGlobalVar
            export const zzzB: string = zzzGlobalVar
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2304 })
        val ts2322 = diagnostics.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322.single().message == "Type 'number' is not assignable to type 'string'.")
    }

    /**
     * CONTROL — a name that collides with NOTHING is unaffected in both
     * directions. Green on every arm; it exists so a failure of the pins above
     * can be read as "the shadow mechanism", not "member reads are broken".
     */
    @Test
    fun `CONTROL - a non-colliding module-local interface behaves the same way`() {
        val diagnostics = diagnose(
            """
            export interface ZzzChars { zzzUnique: number }
            declare function zzzWant(t: ZzzChars): void
            export function zzzP() { zzzWant({ zzzUnique: 1 }) }
            declare const zzzT: ZzzChars
            export const zzzW = zzzT.getTime()
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2345 })
        val ts2339 = diagnostics.filter { it.code == 2339 }
        assert(ts2339.size == 1)
        assert(ts2339.single().message == "Property 'getTime' does not exist on type 'ZzzChars'.")
    }
}
