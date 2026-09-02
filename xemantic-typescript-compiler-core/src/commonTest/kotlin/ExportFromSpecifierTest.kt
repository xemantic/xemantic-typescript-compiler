/*
 * Copyright 2025-2026 Xemantic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * `from` is a CONTEXTUAL keyword: inside an import/export clause it is an ordinary
 * specifier name. rxjs@7.8.2's own `index.d.ts` (line 43) re-exports its `from`
 * operator as `export { from } from './internal/observable/from';`, and
 * `parseNamedExports` used to read the `from` INSIDE the braces as the keyword that
 * ends the clause — three false parse diagnostics (TS1005 / TS1141 / TS1434) on a
 * statement tsc accepts. The named-IMPORTS list already asked
 * `isImportOrExportSpecifierListElement` (tsc's `isListElement`: a `from` is a
 * specifier unless a STRING LITERAL follows it); the named-EXPORTS list now asks the
 * same question. The two negative controls keep the one `from` that IS a missing-`}`
 * marker (`export { from './x'`) on its TS1005 path, and a genuinely missing module
 * specifier reporting.
 *
 * The x.ts sibling makes every re-exported name resolve, so the positive pins can
 * assert an EMPTY diagnostic list rather than only the absence of the parse codes.
 */
class ExportFromSpecifierTest {

    private val directives = "// @strict: true\n// @module: es2020\n// @target: es2020"

    private fun program(@Language("typescript") main: String): String = """
        // @filename: x.ts
        export const from = 1;
        export const f = 2;
        export const a = 3;
        export default 4;
        // @filename: main.ts
        $main
    """.trimIndent()

    private fun diagnosticsOf(@Language("typescript") main: String): List<Diagnostic> =
        diagnose(program(main), directives, "main.ts")

    private fun mainJsOf(@Language("typescript") main: String): String {
        val result = TypeScriptCompiler().compile(directives + "\n" + program(main), "main.ts")
        val js = result.jsOutputs.single { it.first.endsWith("main.js") }.second
        return js.trim()
    }

    @Test
    fun `export from re-exported from a module parses clean`() {
        val d = diagnosticsOf("export { from } from './x';")
        assert(d.isEmpty())
    }

    @Test
    fun `export from as f from a module parses clean`() {
        val d = diagnosticsOf("export { from as f } from './x';")
        assert(d.isEmpty())
    }

    @Test
    fun `export f as from from a module parses clean`() {
        val d = diagnosticsOf("export { f as from } from './x';")
        assert(d.isEmpty())
    }

    @Test
    fun `import from from a module parses clean`() {
        val d = diagnosticsOf("import { from } from './x'; export const v = from;")
        assert(d.isEmpty())
    }

    @Test
    fun `import from as g from a module parses clean`() {
        val d = diagnosticsOf("import { from as g } from './x'; export const v = g;")
        assert(d.isEmpty())
    }

    @Test
    fun `local export of a binding named from parses clean`() {
        val d = diagnosticsOf("const from = 1; export { from };")
        assert(d.isEmpty())
    }

    @Test
    fun `default import bound to the name from parses clean`() {
        val d = diagnosticsOf("import from from './x'; export const v = from;")
        assert(d.isEmpty())
    }

    @Test
    fun `type-only export of from parses clean`() {
        val d = diagnosticsOf("export { type from } from './x';")
        assert(d.isEmpty())
    }

    @Test
    fun `from as a later specifier in the list parses clean`() {
        val d = diagnosticsOf("export { a, from } from './x';")
        assert(d.isEmpty())
    }

    @Test
    fun `from twice in one export list parses clean`() {
        val d = diagnosticsOf("export { from, from as ff } from './x';")
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - a from followed by a string literal is still the missing close brace`() {
        // tsc: `main.ts(1,10): error TS1005: '}' expected.` — the one `from` inside the braces
        // that does mark a missing `}` (isListElement bails on `from "..."`).
        val d = diagnosticsOf("export { from './x';")
        d should {
            have(any { it.code == 1005 && it.start == 9 })
            have(none { it.code == 1434 })
        }
    }

    @Test
    fun `negative control - a missing module specifier after from still reports`() {
        // The specifier list is well formed, so the only defect is the absent module name;
        // nothing may report inside the braces (the old TS1005 at the specifier `from`).
        val d = diagnosticsOf("export { from } from")
        d should {
            have(any { it.code in 1000..1999 })
            have(none { it.code == 1005 })
            have(none { it.code == 1434 })
        }
    }

    @Test
    fun `emitted JavaScript keeps the from re-export verbatim`() {
        val js = mainJsOf("export { from } from './x';")
        assert(js == "export { from } from './x';")
    }

    @Test
    fun `emitted JavaScript keeps from as a renamed re-export verbatim`() {
        val js = mainJsOf("export { from as f } from './x';")
        assert(js == "export { from as f } from './x';")
    }

    @Test
    fun `emitted JavaScript elides a type-only export of from`() {
        val js = mainJsOf("export { type from } from './x';")
        assert(js == "export {};")
    }
}
