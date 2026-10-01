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

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.190) Hover through a named re-export. An identifier's type is
 * `getTypeOfSymbol(alias)` → `resolveAlias`, so the resolver fix reaches
 * [Project.quickInfoAt] by construction; this pins it. Ground truth is tsgo 7.0.2's
 * LSP (`scripts/lsp_hover.py`): `const v: 1`, `const b: B`, `const d: D` — the last
 * through `export { default as D } from`, which typed `any` before.
 */
class ProjectNamedReExportHoverTest {

    private val config =
        """{ "compilerOptions": { "strict": true, "target": "es2022", "module": "esnext", "moduleResolution": "bundler", "noEmit": true, "lib": ["es2022"], "types": [] } }"""

    private val main =
        "// shift\nimport { B, n } from \"./r\";\nimport { D } from \"./rd\";\nconst v = n;\nconst b = new B();\nconst d = new D();\n"

    private fun project() = Project.open(
        "/proj",
        InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                "/proj/m.ts" to "export class B { p = 1; }\nexport const n = 1;\nexport default class D { d = 1; }\n",
                "/proj/r.ts" to "export { B, n } from \"./m\";\n",
                "/proj/rd.ts" to "export { default as D } from \"./m\";\n",
                "/proj/a.ts" to main,
            ),
        ),
    )

    private fun hoverOf(project: Project, local: String): String? =
        project.quickInfoAt("/proj/a.ts", main.indexOf("const $local") + 6)?.displayString

    @Test
    fun `hover through a same-name from clause names the declared types`() {
        val project = project()
        val v = hoverOf(project, "v")
        val b = hoverOf(project, "b")
        assert(v == "1")
        assert(b == "B")
    }

    @Test
    fun `hover through a default clause renamed to a name names the class`() {
        val d = hoverOf(project(), "d")
        assert(d == "D")
    }
}
