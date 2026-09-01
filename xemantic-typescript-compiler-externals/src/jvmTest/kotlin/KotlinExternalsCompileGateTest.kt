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

package com.xemantic.typescript.compiler.externals

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * THE COMPILE GATE: the generated Kotlin must COMPILE, checked in-test with
 * kotlinc's own metadata compiler (`KotlinMetadataCompiler`, the pattern of
 * kir's `KotlinMetadataKlib.kt` — `metadataKlib = true` is load-bearing there
 * and here, and the generated surface names only Kotlin built-ins, so no
 * classpath is needed).
 *
 * ## Which branch this gate took, and why
 *
 * Decided EMPIRICALLY (2026-09-01, Kotlin 2.4.10). Branch (i) — gating the
 * VERBATIM output including the `external` modifier — is refused by the
 * metadata compiler with three errors on the declaration:
 *
 *  - `error: only top-level functions can be external.`
 *  - `error: modifier 'external' is not applicable to 'class'.`
 *  - `error: modifier 'external' is not applicable to 'interface'.`
 *
 * `external interface` is a Kotlin/JS platform notion and a metadata
 * compilation has no platform. So this gate is branch (ii): it compiles
 * [KotlinExternals.compileCheckSource] — the SAME renderer invoked with the
 * flag that omits `external`, never a text strip — and therefore grades the
 * TYPE MAPPING; the `external` modifier itself is outside the gate, and
 * `external modifier is refused by the metadata compiler` below keeps the
 * decision honest: the day a Kotlin release starts accepting it, that pin
 * reddens and the gate should move to the verbatim output.
 */
class KotlinExternalsCompileGateTest {

    /** The MVP surface, end to end: every mapping, a fallback, backticks. */
    private val fixture = """
        export type Species = string;
        export interface Creature {
            name: string;
            limbCount: number;
            winged: boolean;
            nickname?: string;
            readonly kind: Species;
            tags: string | number;
            describe(prefix: string): string;
            touch(): void;
        }
        export interface Keys {
            object: string;
            val: number;
            in: boolean;
        }
        export interface Box<T> {
            value: T;
            wrap(x: T): T;
        }
        export interface Holder {
            occupant: Creature;
            boxed: Box<string>;
        }
        export type Beast = Creature;
        export function greet(who: string, count: number): string { return who; }
        export function id<T>(x: T): T { return x; }
        export interface Handlers {
            onName: (name: string) => void;
            cb?: (n: number) => string;
            ping?(x: string): void;
        }
        export enum Direction { Up, Down }
        export class Animal {
            name: string;
            readonly kind: string;
            constructor(name: string) { this.name = name; this.kind = "beast"; }
            speak(volume: number): string { return this.name; }
            static create(name: string): Animal { return new Animal(name); }
        }
        export abstract class Shape { area(): number { return 0; } }
        export class Pen<T> { occupant?: T; }
        export class Dog extends Animal implements Farmable {
            constructor(name: string, breed: string);
            breed: string;
            speak(volume: number): string;
            graze(): void;
        }
        export interface Farmable { graze(): void; }
        export interface Named extends Farmable { name: string; }
        export declare const DEFAULT_NAME: string;
        export let population: number;
        export class Meter {
            get value(): number;
            set value(v: number);
            get label(): string;
            static get shared(): Meter;
        }
        export interface Farm {
            star: Animal;
            pen: Pen<Animal>;
            dir: Direction;
        }
        export type Callback<T> = (event: T) => void;
        export interface Emitter {
            on<Key extends string>(type: Key, count: number): void;
            pick(x: string): string;
            pick(x: number, y: number): number;
        }
    """.trimIndent()

    @Test
    fun `generated externals compile as kotlin metadata`() {
        val result = generateKotlinExternals("t.ts", fixture)
        val check = compileCheck(result.compileCheckSource)
        val compileErrors = check.errors
        assert(compileErrors.isEmpty())
        assert(check.successful)
    }

    @Test
    fun `negative control - a deliberately broken source fails the same gate`() {
        // Round 790's law: a verifier without its complement population reads
        // 0 both when the output is sound and when the instrument is dead.
        val check = compileCheck(
            """
            public interface Broken {
                public val p: NoSuchType
            }
            """.trimIndent()
        )
        val mentionsTheBrokenName = check.errors.any { "NoSuchType" in it }
        assert(!check.successful)
        assert(mentionsTheBrokenName)
    }

    @Test
    fun `external modifier is refused by the metadata compiler`() {
        // The living record of the branch decision in the class KDoc: while
        // this pin holds, the gate MUST grade the no-external variant; when it
        // reddens, the metadata compiler has started accepting `external` and
        // the gate should move to the verbatim output.
        val result = generateKotlinExternals("t.ts", fixture)
        val check = compileCheck(result.kotlin)
        val mentionsTheModifier = check.errors.any { "external" in it }
        assert(!check.successful)
        assert(mentionsTheModifier)
    }

}
