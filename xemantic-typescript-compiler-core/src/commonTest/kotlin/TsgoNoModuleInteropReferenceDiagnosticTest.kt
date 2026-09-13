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
 * (LEGACY.0b) step 3, family F6 — TypeScript 7 has NO TS2497.
 *
 * `This module can only be referenced with ECMAScript imports/exports by turning on the
 * '{0}' flag and referencing its default export.` names `esModuleInterop` in one wording and
 * `allowSyntheticDefaultImports` in the other, and TypeScript 7 removed the `false` value of
 * BOTH — so the rule became unreachable and the emission went with it. The receipt is that
 * the message survives in tsgo's generated table
 * (`internal/diagnostics/diagnostics_generated.go`, code 2497) and is referenced by NO tsgo
 * code at all, and that ZERO of tsgo's ~8,800 checked-in baselines carry the code where 13
 * tsc-6 baselines do.
 *
 * What TypeScript 7 still reports for the same programs is the PER-SPECIFIER diagnostic that
 * used to accompany it — TS2616 / TS2595 / TS2597, one per named import, telling you which
 * import form to use. Each test below therefore asserts the companion row is INTACT beside
 * the absence, so it cannot pass by the checker having gone silent about the shape.
 *
 * Every expectation was read off `tools/tsgo-7.0.2/lib/tsc`. Baselines closed:
 * `importNonExportedMember5`, `importNonExportedMember7`, `importNonExportedMember9`,
 * `importNonExportedMember11`, `es6ImportEqualsExportModuleEs2015Error`,
 * `conflictingDeclarationsImportFromNamespace1`, `conflictingDeclarationsImportFromNamespace2`.
 */
class TsgoNoModuleInteropReferenceDiagnosticTest {

    private val ts2497Cjs =
        "This module can only be referenced with ECMAScript imports/exports by turning on " +
            "the 'esModuleInterop' flag and referencing its default export."

    private val ts2497Esm =
        "This module can only be referenced with ECMAScript imports/exports by turning on " +
            "the 'allowSyntheticDefaultImports' flag and referencing its default export."

    /**
     * `import { Foo } from "./a"` against `export = Foo` under CommonJS output. tsgo:
     * `b.ts(1,10): error TS2616` and nothing else. This is `importNonExportedMember5`.
     */
    @Test
    fun `a named import of an export-equals class reports TS2616 alone`() {
        val diagnostics = diagnose(
            """
            // @Filename: a.ts
            class Foo { m(): void {} }
            export = Foo;

            // @Filename: b.ts
            import { Foo } from "./a";
            Foo;
            """,
            directives = "// @strict: true\n// @module: commonjs\n// @target: es2015",
        )
        assert(diagnostics.none { it.code == 2497 })
        assert(diagnostics.none { it.message == ts2497Cjs })
        val row = diagnostics.single { it.code == 2616 }
        assert(
            row.message ==
                "'Foo' can only be imported by using 'import Foo = require(\"./a\")' or a " +
                "default import."
        )
    }

    /**
     * The same import under an ES-module output target. tsgo: `TS1203` at the `export =`
     * plus `b.ts(1,10): error TS2595`, and no TS2497 with the
     * `allowSyntheticDefaultImports` wording. This is `importNonExportedMember7`.
     */
    @Test
    fun `a named import of an export-equals class under ESM output reports TS2595 alone`() {
        val diagnostics = diagnose(
            """
            // @Filename: a.ts
            class Foo { m(): void {} }
            export = Foo;

            // @Filename: b.ts
            import { Foo } from "./a";
            Foo;
            """,
            directives = "// @strict: true\n// @module: es2015\n// @target: es2015",
        )
        assert(diagnostics.none { it.code == 2497 })
        assert(diagnostics.none { it.message == ts2497Esm })
        val row = diagnostics.single { it.code == 2595 }
        assert(row.message == "'Foo' can only be imported by using a default import.")
        assert(diagnostics.any { it.code == 1203 })
    }

    /**
     * A JavaScript importer takes the third wording, TS2597, and likewise loses its TS2497
     * companion. This is `importNonExportedMember9`.
     */
    @Test
    fun `a named import of an export-equals class from a JS file reports TS2597 alone`() {
        val diagnostics = diagnose(
            """
            // @Filename: a.ts
            class Foo { m(): void {} }
            export = Foo;

            // @Filename: b.js
            import { Foo } from "./a";
            Foo;
            """,
            directives =
                "// @strict: true\n// @module: commonjs\n// @target: es2015\n" +
                    "// @allowJs: true\n// @checkJs: true\n// @noEmit: true",
        )
        assert(diagnostics.none { it.code == 2497 })
        assert(diagnostics.none { it.message == ts2497Cjs })
        val row = diagnostics.single { it.code == 2597 }
        assert(
            row.message ==
                "'Foo' can only be imported by using a 'require' call or by using a default " +
                "import."
        )
    }

    /**
     * A NAMESPACE import of an `export =` module — the shape that used to be TS2497 on its
     * own, with no companion at all — is simply ACCEPTED by TypeScript 7. This is
     * `es6ImportEqualsExportModuleEs2015Error`, whose whole tsgo baseline is the TS1203 at
     * the `export =` and `main.ts (0 errors)`.
     */
    @Test
    fun `a namespace import of an export-equals module is accepted`() {
        val diagnostics = diagnose(
            """
            // @Filename: a.ts
            class a { }
            export = a;

            // @Filename: main.ts
            import * as aa from "./a";
            aa;
            """,
            directives = "// @strict: true\n// @module: es2015\n// @target: es2015",
        )
        assert(diagnostics.none { it.code == 2497 })
        assert(diagnostics.none { it.message == ts2497Esm })
        assert(diagnostics.single().code == 1203)
    }

    /** The CommonJS spelling of the same namespace import, likewise silent. */
    @Test
    fun `a namespace import of an export-equals module under CommonJS output is accepted`() {
        val diagnostics = diagnose(
            """
            // @Filename: a.ts
            class a { }
            export = a;

            // @Filename: main.ts
            import * as aa from "./a";
            aa;
            """,
            directives = "// @strict: true\n// @module: commonjs\n// @target: es2015",
        )
        assert(diagnostics.none { it.code == 2497 })
        assert(diagnostics.none { it.message == ts2497Cjs })
        assert(diagnostics.isEmpty())
    }

    /**
     * Negative control: a named import of a module that really DOES export that name is
     * silent for a different reason, so the three positive tests above are about the
     * `export =` shape and not about named imports having stopped being checked.
     */
    @Test
    fun `negative control - a named import of a real export reports nothing`() {
        val diagnostics = diagnose(
            """
            // @Filename: a.ts
            export class Foo { m(): void {} }

            // @Filename: b.ts
            import { Foo } from "./a";
            Foo;
            """,
            directives = "// @strict: true\n// @module: commonjs\n// @target: es2015",
        )
        assert(diagnostics.isEmpty())
    }
}
