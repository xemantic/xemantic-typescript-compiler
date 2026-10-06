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

/**
 * (P18.307) How many times a type alias BODY was evaluated — a miss of `Checker`'s alias-substitution cache.
 * A COUNT, read by `BareInferEmptyMappedLandingTest`'s cost pin: a repeated identical reference must not
 * evaluate the body again (type-fest's 40 repeated `Words<'…'>` cost 44 ms each when literal arguments were
 * keyed by type id and conditional-bodied results were never cached). Written from the checking thread only
 * in the tests that read it; under `--workers` the increments may race, which only skews this diagnostic count.
 */
internal object AliasSubstitutionCensus {
    var bodyEvaluations: Long = 0
}
