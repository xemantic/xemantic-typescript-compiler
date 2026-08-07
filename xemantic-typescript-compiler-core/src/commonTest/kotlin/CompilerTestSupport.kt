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

import org.intellij.lang.annotations.Language

/**
 * Compiles an inline TypeScript [source] snippet and returns its diagnostics.
 *
 * The snippet is [String.trimIndent]-ed and prefixed with [directives]
 * (harness `// @option: value` lines); pass an empty string to compile
 * without any directives. Shared declarations reused across a test class
 * are interpolated or concatenated into [source] by the caller.
 */
internal fun diagnose(
    @Language("typescript") source: String,
    directives: String = "// @strict: true",
    fileName: String = "t.ts",
): List<Diagnostic> =
    TypeScriptCompiler().compile(
        (if (directives.isEmpty()) "" else directives + "\n") + source.trimIndent(),
        fileName,
    ).diagnostics
