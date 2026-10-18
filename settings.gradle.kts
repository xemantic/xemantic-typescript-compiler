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

pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "xemantic-typescript-compiler"

include(
    "xemantic-typescript-compiler-api",
    "xemantic-typescript-compiler-cli",
    "xemantic-typescript-compiler-daemon",
    "xemantic-typescript-compiler-client",
    // SPIKE (branch `spike/ts-to-kotlin-ir`): the Kotlin-IR backend.
    "xemantic-typescript-compiler-kir",
    // PHASE 18 (owner directive 2026-09-01): the Kotlin externals generator
    // and the LSP server, both pre-approved additions.
    "xemantic-typescript-compiler-externals",
    "xemantic-typescript-compiler-lsp",
    // (TSGO.1) spike (owner decision 2026-10-06): tsgo ported to Kotlin —
    // the hand-written Go runtime and shims, plus the generated port.
    // docs/tsgo-port-plan.md, docs/goport-design.md.
    "xemantic-typescript-compiler-tsgo",
    // (TSGO.1): the porter that lowers tsgo's Go IR into `-tsgo`'s generated
    // Kotlin (a dev tool, JVM only, not published). docs/goport-design.md.
    "xemantic-typescript-compiler-goport"
)
