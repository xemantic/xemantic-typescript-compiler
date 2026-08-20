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

package com.xemantic.typescript.compiler.kir

/**
 * Marks this module's build as wired: the Kotlin compiler must be reachable at
 * RUNTIME here, not merely at compile time, because the backend drives kotlinc's
 * own JVM pipeline phases in-process (see `KotlinIrEmitter`).
 */
public object KirBuild {

    /** The Kotlin compiler version this backend constructs IR for. */
    public const val KOTLIN_VERSION: String = "2.4.10"

    /**
     * Loads the IR extension point by name. Reflective on purpose: it answers
     * "is the compiler on the RUNTIME classpath", which a direct reference —
     * resolvable at compile time from a `compileOnly` dependency — would not.
     */
    public fun compilerOnRuntimeClasspath(): Boolean = try {
        Class.forName("org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension")
        true
    } catch (_: ClassNotFoundException) {
        false
    }

}
