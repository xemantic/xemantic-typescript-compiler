package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (LEGACY.1)(c) — `alwaysStrict: false` is a REMOVED value in TypeScript 7 and this
 * compiler no longer honours it.
 *
 * Measured on tsgo 7.0.2 (2026-09-15) over a two-file `commonjs` project — a SCRIPT
 * `with (o) { a; }` and a MODULE `export const y = 1;` — in four configurations
 * (`alwaysStrict: false` alone, `alwaysStrict: false` + `strict: false`, `strict: false`
 * alone, nothing set): TS1101 fires at the `with` in EVERY one (read through the LSP,
 * because tsgo's CLI stops at the options diagnostic), `"use strict"` is emitted for BOTH
 * files in every one, and the only effect of the explicit `false` is
 * `TS5108: Option 'alwaysStrict=false' has been removed. Please remove it from your
 * configuration.` at the VALUE. The Go source agrees: `core.CompilerOptions.AlwaysStrict`
 * has exactly three references (parse, field, `program.go:858`'s removed-option report),
 * the binder has no `inStrictMode` at all, and `estransforms/usestrict.go` never reads
 * the flag.
 *
 * Before this round the compiler HONOURED the false — no TS1101, no TS1344, no
 * `"use strict"` in either file — and reported no diagnostic for it (tsc 6.0.3
 * deprecates it in the same 6.0→7.0 ladder as `esModuleInterop=false`).
 *
 * Every non-control pin here was proven RED against the pre-change binary by a
 * stash-ablation; the `control -` pins are green on both arms and exist to show the
 * configurations the deletion must NOT move.
 */
class AlwaysStrictRemovedTest {

    private val withScript = """
        declare const o: any;
        with (o) {}
    """

    private fun ts1101(diagnostics: List<Diagnostic>): Diagnostic? =
        diagnostics.singleOrNull { it.code == 1101 }

    private fun assertWithReported(diagnostics: List<Diagnostic>) {
        val row = ts1101(diagnostics)
        assert(row != null)
        assert(row.message == "'with' statements are not allowed in strict mode.")
        assert(row.category == DiagnosticCategory.Error)
        assert(row.fileName == "t.ts")
        assert(row.line == 2)
        assert(row.character == 1)
        assert(row.length == 4)
        // The TS2410 that always accompanies an outermost `with` is unchanged.
        assert(diagnostics.count { it.code == 2410 } == 1)
    }

    // ── TS1101 — the `with` statement is strict-refused whatever the option says ──

    @Test
    fun `with under alwaysStrict false fires TS1101`() {
        assertWithReported(diagnose(withScript, directives = "// @alwaysStrict: false"))
    }

    @Test
    fun `with under alwaysStrict false and strict false fires TS1101`() {
        assertWithReported(
            diagnose(withScript, directives = "// @alwaysStrict: false\n// @strict: false"),
        )
    }

    @Test
    fun `control - with under strict false alone fires TS1101`() {
        assertWithReported(diagnose(withScript, directives = "// @strict: false"))
    }

    @Test
    fun `control - with under no strict-family option fires TS1101`() {
        assertWithReported(diagnose(withScript, directives = ""))
    }

    // ── TS1344 — the other consumer of the deleted gate ──────────────────────────

    @Test
    fun `label on a declaration under alwaysStrict false fires TS1344`() {
        val diagnostics = diagnose("L: var x = 1;", directives = "// @alwaysStrict: false")
        val row = diagnostics.singleOrNull { it.code == 1344 }
        assert(row != null)
        assert(row.message == "A label is not allowed here.")
        assert(row.line == 1)
        assert(row.character == 1)
    }

    // ── TS1212 — the explicitNonStrict disjunct that used to switch a file non-strict ──

    /**
     * tsgo 7.0.2 (LSP, `alwaysStrict: false`, es2020 script): `script.ts(1,5): TS1212:
     * Identifier expected. 'let' is a reserved word in strict mode.` Before this round the
     * explicit false made the file EXPLICITLY non-strict here, which switched off the
     * target-derived strictness and the let/const shortcut — silence. (`strict: false`
     * ALONE still silences it in this compiler where tsgo reports; that disjunct is a
     * separate tsc-6 residue gated by the corpus's `@strict: false` baselines, recorded
     * in the (P18.103) note — deliberately NOT pinned here.)
     */
    @Test
    fun `var let under alwaysStrict false fires the strict-mode TS1212`() {
        val diagnostics = diagnose(
            "var let = 1;",
            directives = "// @alwaysStrict: false\n// @target: es2020",
        )
        val row = diagnostics.singleOrNull { it.code == 1212 }
        assert(row != null)
        assert(row.message == "Identifier expected. 'let' is a reserved word in strict mode.")
        assert(row.line == 1)
        assert(row.character == 5)
        assert(row.length == 3)
    }

    // ── EMIT — the `"use strict"` prologue is unconditional for non-ESM output ──

    private fun js(directives: String, source: String): String =
        TypeScriptCompiler().compile(directives + "\n" + source.trimIndent(), "t.ts").javascript
            ?: error("no js")

    // `CompilationResult.javascript` carries no trailing newline (the neighbouring emit
    // pins compare against a `trimIndent()`ed block for the same reason).
    private val scriptJs = "\"use strict\";\nvar x = 1;"

    private val commonJsModuleJs = "\"use strict\";\n" +
        "Object.defineProperty(exports, \"__esModule\", { value: true });\n" +
        "exports.y = void 0;\n" +
        "exports.y = 1;"

    @Test
    fun `a script under alwaysStrict false is emitted with use strict`() {
        val out = js("// @alwaysStrict: false\n// @target: es2020", "var x = 1;")
        assert(out == scriptJs)
    }

    @Test
    fun `a script under alwaysStrict false and strict false is emitted with use strict`() {
        val out = js("// @alwaysStrict: false\n// @strict: false\n// @target: es2020", "var x = 1;")
        assert(out == scriptJs)
    }

    @Test
    fun `a commonjs module under alwaysStrict false is emitted with use strict`() {
        val out = js(
            "// @alwaysStrict: false\n// @module: commonjs\n// @target: es2020",
            "export const y = 1;",
        )
        assert(out == commonJsModuleJs)
    }

    @Test
    fun `control - a script under strict false is emitted with use strict`() {
        val out = js("// @strict: false\n// @target: es2020", "var x = 1;")
        assert(out == scriptJs)
    }

    @Test
    fun `control - a commonjs module with nothing set is emitted with use strict`() {
        val out = js("// @module: commonjs\n// @target: es2020", "export const y = 1;")
        assert(out == commonJsModuleJs)
    }

    // ── The removed-option report — the ONE thing the explicit false still does ──

    /**
     * (P18.133) At the shipped default the report is tsgo's removed-option line, exactly as for
     * its two siblings `esModuleInterop=false` / `allowSyntheticDefaultImports=false` (tsgo
     * emits all three from `createRemovedOptionDiagnostic`, `program.go:858-870`). Measured:
     *
     *     tsconfig.json(1,40): error TS5108: Option 'alwaysStrict=false' has been removed. Please remove it from your configuration.
     */
    @Test
    fun `alwaysStrict false is tsgo's removed-option row at the shipped default`() {
        val diagnostics = diagnose("var x = 1;", directives = "// @alwaysStrict: false")
        val row = diagnostics.singleOrNull { it.code == 5108 }
        assert(row != null)
        assert(
            row.message == "Option 'alwaysStrict=false' has been removed. " +
                "Please remove it from your configuration.",
        )
        diagnostics should { have(none { it.code == 5107 }) }
    }

    /**
     * Control: an EXPLICIT `@typeScriptVersion` below `7.0` still selects the TypeScript 6
     * deprecation ladder (tsc 6.0.3 lists all three siblings in one
     * `checkDeprecations("6.0", "7.0", …)` block), where `ignoreDeprecations` silences.
     */
    @Test
    fun `control - an explicit typeScriptVersion 6 0 keeps the deprecated-option line`() {
        val diagnostics = diagnose(
            "var x = 1;",
            directives = "// @typeScriptVersion: 6.0\n// @alwaysStrict: false",
        )
        val row = diagnostics.singleOrNull { it.code == 5107 }
        assert(row != null)
        assert(
            row.message == "Option 'alwaysStrict=false' is deprecated and will stop " +
                "functioning in TypeScript 7.0. Specify compilerOption " +
                "'\"ignoreDeprecations\": \"6.0\"' to silence this error.",
        )
        diagnostics should { have(none { it.code == 5108 }) }
        val silenced = diagnose(
            "var x = 1;",
            directives = "// @typeScriptVersion: 6.0\n// @alwaysStrict: false\n// @ignoreDeprecations: 6.0",
        )
        silenced should { have(none { it.code == 5107 || it.code == 5108 }) }
    }

    /**
     * Under TypeScript 7 it is tsgo's `createRemovedOptionDiagnostic("alwaysStrict",
     * "false", "")` — TS5108 at the option VALUE (measured `tsconfig.json(1,96)` on a
     * one-line tsconfig whose `false` starts at column 96).
     */
    @Test
    fun `alwaysStrict false is a removed option under typeScriptVersion 7 0`() {
        val diagnostics = diagnose(
            """
            // @Filename: /foo/tsconfig.json
            {
                "compilerOptions": {
                    "alwaysStrict": false
                }
            }

            // @filename: /foo/a.ts
            const a = 1;
            """,
            directives = "// @typeScriptVersion: 7.0",
        )
        val row = diagnostics.singleOrNull { it.code == 5108 }
        assert(row != null)
        assert(
            row.message == "Option 'alwaysStrict=false' has been removed. " +
                "Please remove it from your configuration.",
        )
        assert(row.fileName == "/foo/tsconfig.json")
        assert(row.line == 3)
        assert(row.character == 25)
        assert(row.length == 5)
        diagnostics should { have(none { it.code == 5107 }) }
    }

    @Test
    fun `control - alwaysStrict true draws no removed-option report`() {
        val diagnostics = diagnose(withScript, directives = "// @alwaysStrict: true")
        diagnostics should { have(none { it.code == 5107 || it.code == 5108 }) }
        assertWithReported(diagnostics)
    }
}
