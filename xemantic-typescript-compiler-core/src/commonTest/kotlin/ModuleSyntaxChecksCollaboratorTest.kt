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
 * (INV.0) (P18.279) The module-syntax family as it lives in `ModuleSyntaxChecks` and the
 * TypeScript-only-syntax-in-JS family as it lives in `TsSyntaxInJsFiles` after the
 * extraction. Every expected row is tsgo 7.0.2's (cells under
 * `build/bench/p18279-agent/matrix/`, 1-based column); all eleven cells there read
 * byte-identical on both arms of the move.
 */
class ModuleSyntaxChecksCollaboratorTest {

    private fun rows(source: String, directives: String = "// @strict: true\n// @target: es2020\n// @module: esnext", fileName: String = "t.ts"): List<String> =
        diagnose(source, directives = directives, fileName = fileName)
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `an import conflicting with a local declaration is TS2440`() {
        val r = rows(
            """
            // @Filename: m.ts
            export const x = 1;

            // @Filename: t.ts
            import { x } from "./m";
            const x = 2;
            export { };
            """
        )
        assert(r == listOf("1:10 TS2440 Import declaration conflicts with local declaration of 'x'."))
    }

    @Test
    fun `an import declaration inside a namespace is TS1147`() {
        val r = rows(
            """
            // @Filename: m.ts
            export const x = 1;

            // @Filename: t.ts
            namespace N {
                import { x } from "./m";
            }
            export { };
            """
        )
        assert(r == listOf("2:23 TS1147 Import declarations in a namespace cannot reference a module."))
    }

    @Test
    fun `a namespace in a global script under isolatedModules is TS1280`() {
        val r = rows(
            """
            namespace N {
                export const a = 1;
            }
            """,
            directives = "// @strict: true\n// @target: es2020\n// @module: esnext\n// @isolatedModules: true",
        )
        assert(
            r == listOf(
                "1:11 TS1280 Namespaces are not allowed in global script files when 'isolatedModules' is enabled. " +
                    "If this file is not intended to be a global script, set 'moduleDetection' to 'force' or add an empty 'export {}' statement."
            )
        )
    }

    @Test
    fun `TypeScript-only syntax in a checked JS file reports the TS8xxx family`() {
        val r = rows(
            """
            let x: number = 1;
            interface I { a: number }
            type A = string;
            function f(a?: number): void {}
            enum E { A }
            export { x };
            """,
            directives = "// @strict: true\n// @target: es2020\n// @module: esnext\n// @allowJs: true\n// @checkJs: true",
            fileName = "a.js",
        )
        assert(
            r == listOf(
                "1:8 TS8010 Type annotations can only be used in TypeScript files.",
                "2:11 TS8006 'interface' declarations can only be used in TypeScript files.",
                "3:6 TS8008 Type aliases can only be used in TypeScript files.",
                "4:13 TS8009 The '?' modifier can only be used in TypeScript files.",
                "4:16 TS8010 Type annotations can only be used in TypeScript files.",
                "4:25 TS8010 Type annotations can only be used in TypeScript files.",
                "5:6 TS8006 'enum' declarations can only be used in TypeScript files.",
            )
        )
    }

    @Test
    fun `negative control - a clean module with an ordinary import reports nothing`() {
        val r = rows(
            """
            // @Filename: m.ts
            export const x = 1;

            // @Filename: t.ts
            import { x } from "./m";
            export const y = x;
            """
        )
        assert(r.isEmpty())
    }
}
