package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (LEGACY.0b) step 18 — three independent mechanisms, all read out of tsgo 7.0.2 and then
 * MEASURED against it before any code was written.
 *
 * **M2 — a self-name import whose project root is ambiguous does not resolve.** tsgo's
 * `tryLoadInputFileForPath` (`internal/module/resolver.go`) reverse-maps an `exports` entry
 * that points under `outDir`/`declarationDir` back onto a source file, and needs a project
 * root to do it: `rootDir` when set, else the config file's directory, else it raises TS2209
 * AND answers `unresolved()`. We used to raise TS2209 and resolve the import anyway, so the
 * ordinary TS2307 at the specifier was missing. Closes
 * `nodeNextPackageSelfNameWithOutDir` and `nodeNextPackageSelfNameWithOutDirDeclDir`.
 *
 * The walker that decides this reads `package.json` out of `parsed.files`, which under
 * `ProjectCompiler` is never a program input (a real project therefore reaches none of it —
 * measured: 0 diagnostics), so the pins live here and not in the `-project` module.
 *
 * **M3 — `isolatedDeclarations`: an initialized parameter that cannot be written optional.**
 * tsgo's `createParameterError` (`internal/transformers/declarations/diagnostics.go`) reports
 * TS9025 on the WHOLE PARAMETER when `requiresAddingImplicitUndefined` holds, and TS9011 on
 * the INITIALIZER otherwise. That predicate is `strictNullChecks` + an initializer + not
 * optional — and `isOptionalParameter` makes an initialized parameter optional exactly when
 * NO LATER PARAMETER IS REQUIRED, which is the whole discriminator: measured against tsgo,
 * `f(p = bar())` is TS9011 at `bar()` and `f(p = bar(), v: number)` is TS9025 at `p = bar()`.
 * A failure NESTED inside the initializer keeps its own TS9013. Closes
 * `isolatedDeclarationsAddUndefined`.
 *
 * **M3b — the family runs over a `.js` file too.** tsgo's declaration transform has no
 * whole-file JS skip; `allowJs` + `isolatedDeclarations` (itself TS5053) still reports there.
 * Closes `isolatedDeclarationsAllowJs`.
 *
 * M1 (a rendered signature keeping the source's quote style) was REFUSED — see the round
 * note: the mechanism is type-node REUSE, not quote preservation, and it also preserves
 * aliases, keyword spellings and generic spellings.
 */
class TsgoStep18Test {

    private val ambiguousPackageJson = """
        // @filename: package.json
        {
          "name": "@this/package",
          "type": "module",
          "exports": {
            ".": "./dist/index.js"
          }
        }
        // @filename: index.ts
        import * as me from "@this/package";

        me.thing();

        export function thing(): void {}
    """.trimIndent()

    @Test
    fun `an ambiguous project root refuses the self-name resolution so TS2307 follows`() {
        val diags = diagnose(
            ambiguousPackageJson,
            directives = "// @target: es2015\n// @module: nodenext\n// @outDir: ./dist",
        )
        val row = diags.single { it.code == 2307 }
        assert(row.message == "Cannot find module '@this/package' or its corresponding type declarations.")
        assert(row.fileName == "index.ts")
        assert(row.line == 1)
        assert(row.character == 21)
        assert(row.length == 15)
        diags should { have(any { it.code == 2209 }) }
    }

    @Test
    fun `the declarationDir variant refuses it the same way`() {
        val diags = diagnose(
            """
            // @filename: package.json
            {
              "name": "@this/package",
              "type": "module",
              "exports": {
                ".": {
                  "default": "./dist/index.js",
                  "types": "./types/index.d.ts"
                }
              }
            }
            // @filename: index.ts
            import * as me from "@this/package";

            export function thing(): void {}
            """.trimIndent(),
            directives = "// @target: es2015\n// @module: nodenext\n// @outDir: ./dist\n" +
                "// @declarationDir: ./types\n// @declaration: true",
        )
        val row = diags.single { it.code == 2307 }
        assert(row.character == 21)
        assert(row.length == 15)
        diags should { have(any { it.code == 2209 }) }
    }

    @Test
    fun `negative control - a supplied rootDir resolves the self-name and reports neither row`() {
        diagnose(
            ambiguousPackageJson,
            directives = "// @target: es2015\n// @module: nodenext\n// @outDir: ./dist\n// @rootDir: .",
        ) should {
            have(none { it.code == 2307 })
            have(none { it.code == 2209 })
        }
    }

    @Test
    fun `negative control - composite resolves the self-name and reports neither row`() {
        diagnose(
            ambiguousPackageJson,
            directives = "// @target: es2015\n// @module: nodenext\n// @outDir: ./dist\n// @composite: true",
        ) should {
            have(none { it.code == 2307 })
            have(none { it.code == 2209 })
        }
    }

    private val idDirectives =
        "// @target: es2015\n// @module: commonjs\n// @isolatedDeclarations: true\n" +
            "// @declaration: true\n// @strict: true"

    @Test
    fun `an inner parameter that cannot be written optional is TS9025 on the whole parameter`() {
        val diags = diagnose(
            """
            type T = number
            export function foo2(p = (ip = 10 as T, v: number): void => {}): void{}
            """.trimIndent(),
            directives = idDirectives,
        )
        val row = diags.single { it.code == 9025 }
        assert(
            row.message == "Declaration emit for this parameter requires implicitly adding " +
                "undefined to its type. This is not supported with --isolatedDeclarations."
        )
        assert(row.line == 2)
        assert(row.character == 27)
        assert(row.length == 12)
        val related = row.relatedInformation.single()
        assert(related.code == 9028)
        assert(related.message == "Add a type annotation to the parameter ip.")
        assert(related.line == 2)
        assert(related.character == 27)
        diags should { have(none { it.code == 9011 }) }
    }

    @Test
    fun `a top-level parameter followed by a required one is TS9025 too`() {
        val diags = diagnose(
            """
            declare function bar(): number;
            export function f(p = bar(), v: number): void {}
            """.trimIndent(),
            directives = idDirectives,
        )
        val row = diags.single { it.code == 9025 }
        assert(row.line == 2)
        assert(row.character == 19)
        assert(row.length == 9)
    }

    @Test
    fun `negative control - with no later required parameter it stays TS9011 at the initializer`() {
        val diags = diagnose(
            """
            declare function bar(): number;
            export function g(p = bar()): void {}
            """.trimIndent(),
            directives = idDirectives,
        )
        val row = diags.single { it.code == 9011 }
        assert(row.line == 2)
        assert(row.character == 23)
        assert(row.length == 5)
        diags should { have(none { it.code == 9025 }) }
    }

    @Test
    fun `negative control - a failure nested in the initializer keeps its own TS9013`() {
        val diags = diagnose(
            """
            declare function bar(): number;
            export function k(p = { a: bar() }, v: number): void {}
            """.trimIndent(),
            directives = idDirectives,
        )
        val row = diags.single { it.code == 9013 }
        assert(row.line == 2)
        assert(row.character == 28)
        diags should { have(none { it.code == 9025 }) }
    }

    @Test
    fun `negative control - without strictNullChecks the same shape stays TS9011`() {
        val diags = diagnose(
            """
            declare function bar(): number;
            export function h(p = bar(), v: number): void {}
            """.trimIndent(),
            directives = "// @target: es2015\n// @module: commonjs\n// @isolatedDeclarations: true\n" +
                "// @declaration: true\n// @strict: false",
        )
        val row = diags.single { it.code == 9011 }
        assert(row.line == 2)
        assert(row.character == 23)
        assert(row.length == 5)
        diags should { have(none { it.code == 9025 }) }
    }

    @Test
    fun `negative control - a trivially declarable initializer is silent in both positions`() {
        diagnose(
            """
            export function ok(p = (ip = 10, v: number): void => {}): void{}
            """.trimIndent(),
            directives = idDirectives,
        ) should {
            have(none { it.code == 9025 })
            have(none { it.code == 9011 })
        }
    }

    @Test
    fun `a js file under allowJs reports the isolatedDeclarations family`() {
        val diags = diagnose(
            """
            // @filename: file1.ts
            export var x;
            // @filename: file2.js
            export var y;
            """.trimIndent(),
            directives = "// @target: es2015\n// @module: commonjs\n// @isolatedDeclarations: true\n" +
                "// @allowJS: true\n// @declaration: true\n// @strict: false",
        )
        val row = diags.single { it.code == 9010 && it.fileName == "file2.js" }
        assert(row.message == "Variable must have an explicit type annotation with --isolatedDeclarations.")
        assert(row.line == 1)
        assert(row.character == 12)
        assert(row.length == 1)
        assert(row.relatedInformation.single().code == 9027)
        diags should { have(any { it.code == 9010 && it.fileName == "file1.ts" }) }
    }

    @Test
    fun `negative control - a declaration file is still skipped by the family`() {
        diagnose(
            """
            // @filename: file1.ts
            export var x;
            // @filename: file2.d.ts
            export declare var y;
            """.trimIndent(),
            directives = "// @target: es2015\n// @module: commonjs\n// @isolatedDeclarations: true\n" +
                "// @declaration: true\n// @strict: false",
        ) should {
            have(none { it.code == 9010 && it.fileName == "file2.d.ts" })
        }
    }
}
