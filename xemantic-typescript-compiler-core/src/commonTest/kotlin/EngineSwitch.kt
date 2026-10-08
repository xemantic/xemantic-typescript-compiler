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
 * (TSGO.2) the ENGINE behind [diagnose] (docs/goport-pin-census.md): `null` means this module's own
 * compiler — the default, and then [diagnose] runs exactly the path it always ran. On the JVM,
 * `XTSC_ENGINE=tsgo` (an environment variable: Gradle does not forward `-D` to the test JVM) compiles
 * the same composed text through the PORTED tsgo compiler test harness (`-tsgo`, test scope only) and
 * maps its diagnostics into this module's [Diagnostic], so the hand-written pins run against both engines.
 */
internal expect fun engineDiagnose(text: String, fileName: String): List<Diagnostic>?
