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
 * (CHK.202) TS7016 for an import the project crawl resolved to a JavaScript file inside
 * `node_modules` and left out of the program ([UntypedModuleResolution]) — tsgo's
 * `errorOnImplicitAnyModule` (`internal/checker/checker.go`), reached from
 * `resolveExternalModule` when a resolution exists but its extension is not TypeScript
 * or JSON. It is an ERROR only under `noImplicitAny` (a suggestion otherwise, which this
 * compiler does not report) and never for a side-effect import.
 *
 * The elaboration line is tsgo's `CreateModuleNotFoundChain`, reached only for a bare
 * specifier whose package has an id (`name` + `version`). Its `GetPackagesMap()` test is
 * approximated by asking whether a program file lives in the `@types` package
 * (`If … consider sending a pull request …`) or is a declaration file of the package
 * itself (`If … try adding a new declaration …`); otherwise `Try npm i …`.
 */
internal object UntypedModuleImports {

    fun diagnostic(
        specifier: Expression,
        moduleName: String,
        untyped: UntypedModuleResolution,
        fileName: String,
        line: Int,
        character: Int,
        programFileNames: Collection<String>,
    ): Diagnostic {
        val start = specifier.pos
        val pkg = untyped.packageName
        val chain =
            if (pkg == null || moduleName.startsWith("./") || moduleName.startsWith("../") || moduleName.startsWith("/")) null
            else listOf("  " + chainMessage(pkg, moduleName, programFileNames))
        return Diagnostic(
            message = "Could not find a declaration file for module '$moduleName'. '${untyped.resolvedFileName}' implicitly has an 'any' type.",
            category = DiagnosticCategory.Error,
            code = 7016,
            fileName = fileName,
            line = line,
            character = character,
            start = start,
            length = moduleName.length + 2,
            messageChain = chain ?: emptyList(),
        )
    }

    private fun chainMessage(pkg: String, moduleName: String, programFileNames: Collection<String>): String {
        val mangled = if (pkg.startsWith("@") && '/' in pkg) pkg.substring(1).replaceFirst("/", "__") else pkg
        if (programFileNames.any { "/node_modules/@types/$mangled/" in it }) {
            return "If the '$pkg' package actually exposes this module, consider sending a pull request to amend 'https://github.com/DefinitelyTyped/DefinitelyTyped/tree/master/types/$mangled'"
        }
        if (programFileNames.any { "/node_modules/$pkg/" in it && it.endsWith(".d.ts") }) {
            return "If the '$pkg' package actually exposes this module, try adding a new declaration (.d.ts) file containing `declare module '$moduleName';`"
        }
        return "Try `npm i --save-dev @types/$mangled` if it exists or add a new declaration (.d.ts) file containing `declare module '$moduleName';`"
    }
}
