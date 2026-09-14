/*
 * Copyright 2024-2026 Kazimierz Pogoda / Xemantic
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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * (LEGACY.0b) step 12, round (P18.97): five JS-emit mechanisms where tsgo 7.0.2's output
 * differs from the pre-change emit. Every expected string below was READ OUT OF
 * `tools/tsgo-7.0.2/lib/tsc --target es2015 --module es2015` over the same fixture, never
 * hand-written (CLAUDE.md: ground truth for an emit answer is obtainable).
 *
 * The mechanisms and their tsgo homes:
 *
 *  * **M1** — the enum/namespace `var` hoist is decided by tsgo's PER-SCOPE first-declaration
 *    map (`runtimesyntax.go` `pushScope` / `recordDeclarationInScope` /
 *    `isFirstDeclarationInScope` / `addVarForDeclaration`): a scope is a SourceFile, Block,
 *    ModuleBlock or CaseBlock; functions, classes and variable-statement declarators
 *    (binding-pattern leaves included) are recorded in source order at their visit, and the
 *    enum/namespace records itself; the hoist is emitted only when the enum/namespace IS the
 *    first recorded declaration of that name in the CURRENT scope.
 *  * **M2** — `declare import a = b;` is an ambient statement and `typeeraser.go` drops it
 *    whole, ImportEquals included; the pre-change transformer exempted ImportEquals from the
 *    ambient elision (a tsc-6 transcription).
 *  * **M3** — an entity-name `import I = M;` inside a plain Block (a top-level block or a
 *    function body) goes through `visitImportEqualsDeclaration` and prints `var I = M;`;
 *    only a Block WITHIN A NAMESPACE elides it (`runtimesyntax.go` visit, the
 *    `KindImportEqualsDeclaration` arms).
 *  * **M4** — a setter's return-type annotation is never printed.
 *  * **M5** — `emitBOM` prepends U+FEFF to the emitted JavaScript, and the corpus harness
 *    renders that output as a plain content section (tsgo's baseline), not as the pristine
 *    harness's Latin-1 mojibake error baseline.
 *  * **M6** — a body-less `global` (the parser's recovery of `global x` in a class body)
 *    emits nothing: tsgo's `shouldEmitModuleDeclaration` refuses a namespace with no body,
 *    where tsc 6 printed an empty `global` IIFE.
 *  * **M7** — a source-written empty `export {}` is kept where it is written in a
 *    JAVASCRIPT file (tsgo's import elision answers `shouldEmitAliasDeclaration` true for
 *    anything `IsInJSFile`), and the emitter adds no second marker; in a TypeScript file
 *    it is still elided and the marker lands LAST — measured on 14 green baselines.
 */
class TsgoJsEmitResidueTest {

    private fun js(@Language("typescript") src: String, directives: String = "// @target: es2015\n"): String =
        TypeScriptCompiler().compile(directives + src.trimIndent(), "t.ts").javascript ?: error("no js")

    // ── M1: the per-scope first-declaration map ─────────────────────────────

    @Test
    fun `M1 - a var before an enum or namespace suppresses the hoist`() {
        val out = js(
            """
            var x5 = 1;
            enum x5 { One }
            var c;
            namespace c { export var x = 2; }
            """
        )
        val expected = """
            "use strict";
            var x5 = 1;
            (function (x5) {
                x5[x5["One"] = 0] = "One";
            })(x5 || (x5 = {}));
            var c;
            (function (c) {
                c.x = 2;
            })(c || (c = {}));
        """.trimIndent()
        assert(out == expected)
    }

    /**
     * The `nameCollisions` shapes, all in ONE namespace body (a ModuleBlock scope): a
     * var before the namespace drops the `let`; a namespace before the var KEEPS it
     * (`namespace z { var t } var z;`); a namespace before a class keeps it too.
     */
    @Test
    fun `M1 - namespace-body vars suppress and order decides`() {
        val out = js(
            """
            namespace T {
                var x = 2;
                namespace x { export class Bar {} }
                namespace z { var t; }
                var z;
                namespace y { var b; }
                class y {}
            }
            """
        )
        val expected = """
            "use strict";
            var T;
            (function (T) {
                var x = 2;
                (function (x) {
                    class Bar {
                    }
                    x.Bar = Bar;
                })(x || (x = {}));
                let z;
                (function (z) {
                    var t;
                })(z || (z = {}));
                var z;
                let y;
                (function (y) {
                    var b;
                })(y || (y = {}));
                class y {
                }
            })(T || (T = {}));
        """.trimIndent()
        assert(out == expected)
    }

    @Test
    fun `M1 - an outer-scope var does not suppress an inner scope's hoist and a body var does`() {
        val out = js(
            """
            var E = 1;
            function f() {
                enum E { A }
                var G;
                enum G { B }
            }
            """
        )
        val expected = """
            "use strict";
            var E = 1;
            function f() {
                let E;
                (function (E) {
                    E[E["A"] = 0] = "A";
                })(E || (E = {}));
                var G;
                (function (G) {
                    G[G["B"] = 0] = "B";
                })(G || (G = {}));
            }
        """.trimIndent()
        assert(out == expected)
    }

    /**
     * Binding-pattern leaves are recorded (`var { e } = o; enum e {}` drops the hoist), and
     * the CaseBlock quirk: tsgo's visitor early-returns on a subtree with no TypeScript
     * syntax BEFORE recording its children, so `case 1: var h;` records nothing and the
     * later `enum h` is first in the CaseBlock scope — `let h;` is emitted.
     */
    @Test
    fun `M1 - binding-pattern leaves are recorded and a case clause without TypeScript is not`() {
        val out = js(
            """
            declare const o: any;
            var { e } = o;
            enum e { A }
            var [f] = o;
            namespace f { export var v = 1; }
            switch (o) {
                case 1:
                    var h;
                case 2:
                    enum h { X }
            }
            """
        )
        val expected = """
            "use strict";
            var { e } = o;
            (function (e) {
                e[e["A"] = 0] = "A";
            })(e || (e = {}));
            var [f] = o;
            (function (f) {
                f.v = 1;
            })(f || (f = {}));
            switch (o) {
                case 1:
                    var h;
                case 2:
                    let h;
                    (function (h) {
                        h[h["X"] = 0] = "X";
                    })(h || (h = {}));
            }
        """.trimIndent()
        assert(out == expected)
    }

    /**
     * Mostly what the OLD sets already got right and must survive the rule swap —
     * class-then-namespace drops the hoist, enum-then-enum drops the second, a `declare var`
     * is erased before recording so the enum after it is first — plus ONE positive: a
     * top-level Block is its own scope whose `var R;` suppresses the enum's hoist (the
     * pre-change binary printed `var R;` twice there, so this pin is red on it).
     */
    @Test
    fun `M1 - class-then-namespace and enum-then-enum drop the hoist and a block var does too`() {
        val out = js(
            """
            class K {}
            namespace K { export var v = 1; }
            enum D { A }
            enum D { B = 1 }
            declare var Q: any;
            enum Q { A }
            {
                var R;
                enum R { A }
            }
            """
        )
        val expected = """
            "use strict";
            class K {
            }
            (function (K) {
                K.v = 1;
            })(K || (K = {}));
            var D;
            (function (D) {
                D[D["A"] = 0] = "A";
            })(D || (D = {}));
            (function (D) {
                D[D["B"] = 1] = "B";
            })(D || (D = {}));
            var Q;
            (function (Q) {
                Q[Q["A"] = 0] = "A";
            })(Q || (Q = {}));
            {
                var R;
                (function (R) {
                    R[R["A"] = 0] = "A";
                })(R || (R = {}));
            }
        """.trimIndent()
        assert(out == expected)
    }

    // ── M2: `declare import` is ambient ─────────────────────────────────────

    @Test
    fun `M2 - declare import emits nothing`() {
        val out = js("declare import a = b;")
        assert(out == "\"use strict\";")
    }

    /**
     * `declare export import` made the file a module; once it is erased the file has no
     * runtime export left, and the `export {};` marker lands AFTER the last statement.
     */
    @Test
    fun `M2 - declare export import emits nothing and the module marker lands last`() {
        val out = js(
            """
            namespace x {
                interface c {
                }
            }
            declare export import a = x.c;
            var b: a;
            """
        )
        assert(out == "var b;\nexport {};")
    }

    @Test
    fun `M2 - negative control - a non-declare export import and import alias still emit`() {
        val out = js(
            """
            namespace x { export var c = 1; }
            export import a = x.c;
            import d = x.c;
            var b = d;
            """
        )
        val expected = """
            var x;
            (function (x) {
                x.c = 1;
            })(x || (x = {}));
            export var a = x.c;
            var d = x.c;
            var b = d;
        """.trimIndent()
        assert(out == expected)
    }

    /**
     * The alias is REFERENCED (`export var v = a`) on purpose: an unreferenced internal
     * alias is elided by the namespace body's own unused-alias rule whatever `declare`
     * does, and the first version of this pin was blind to M2 for exactly that reason
     * (green under the arm that reverted M2).
     */
    @Test
    fun `M2 - a referenced declare import inside a namespace body emits nothing`() {
        val out = js(
            """
            namespace N {
                declare import a = b;
                export var v = a;
            }
            """
        )
        val expected = """
            "use strict";
            var N;
            (function (N) {
                N.v = a;
            })(N || (N = {}));
        """.trimIndent()
        assert(out == expected)
    }

    // ── M3: import-equals inside a plain block ──────────────────────────────

    @Test
    fun `M3 - an entity-name import equals in a top-level block emits var`() {
        val out = js(
            """
            namespace M { export var v = 1; }
            {
                import I = M;
                import I2 = require("foo");
            }
            """
        )
        val expected = """
            "use strict";
            var M;
            (function (M) {
                M.v = 1;
            })(M || (M = {}));
            {
                var I = M;
                import I2 = require("foo");
            }
        """.trimIndent()
        assert(out == expected)
    }

    @Test
    fun `M3 - an entity-name import equals in a function body emits var`() {
        val out = js(
            """
            namespace M { export var v = 1; }
            function f() {
                import I = M;
            }
            """
        )
        val expected = """
            "use strict";
            var M;
            (function (M) {
                M.v = 1;
            })(M || (M = {}));
            function f() {
                var I = M;
            }
        """.trimIndent()
        assert(out == expected)
    }

    /** tsgo's second arm: a Block WITHIN A NAMESPACE elides the internal alias. */
    @Test
    fun `M3 - negative control - inside a namespace a block or function body elides it`() {
        val out = js(
            """
            namespace M { export var v = 1; }
            namespace N {
                {
                    import I = M;
                }
                function g() {
                    import J = M;
                }
                export var q = 1;
            }
            """
        )
        val expected = """
            "use strict";
            var M;
            (function (M) {
                M.v = 1;
            })(M || (M = {}));
            var N;
            (function (N) {
                {
                }
                function g() {
                }
                N.q = 1;
            })(N || (N = {}));
        """.trimIndent()
        assert(out == expected)
    }

    // ── M4: a setter never prints a return type ─────────────────────────────

    @Test
    fun `M4 - a setter return-type annotation is not printed`() {
        val out = js(
            """
            class C {
                set Goo(v: string): string {}
                get Foo(): string { return "x"; }
                static set S(v: number): number { }
            }
            """
        )
        val expected = """
            "use strict";
            class C {
                set Goo(v) { }
                get Foo() { return "x"; }
                static set S(v) { }
            }
        """.trimIndent()
        assert(out == expected)
    }

    /**
     * Not the emitter: the object-literal accessor printers never printed a type, but the
     * PARSER did not accept a return type on an object-literal setter at all — the
     * pre-change binary de-synchronised `set p(v: number): number {}` into
     * `set p(v) { }, number ... get; q();`. The parse now mirrors the getter arm.
     */
    @Test
    fun `M4 - an object-literal setter return-type annotation parses and is not printed`() {
        val out = js(
            """
            const o = {
                set p(v: number): number {},
                get q(): number { return 1; },
            };
            """
        )
        val expected = """
            "use strict";
            const o = {
                set p(v) { },
                get q() { return 1; },
            };
        """.trimIndent()
        assert(out == expected)
    }

    // ── M5: emitBOM ─────────────────────────────────────────────────────────

    @Test
    fun `M5 - emitBOM prepends a byte order mark to the JavaScript`() {
        val out = js("var x;", directives = "// @target: es2015\n// @emitBOM: true\n")
        assert(out == "﻿\"use strict\";\nvar x;")
    }

    @Test
    fun `M5 - negative control - without emitBOM there is no byte order mark`() {
        val out = js("var x;")
        assert(out == "\"use strict\";\nvar x;")
    }

    /**
     * The harness half: tsgo's `emitBOM.js` baseline is a plain `//// [t.js]` content
     * section whose body starts with the real U+FEFF. The pristine harness's Latin-1
     * re-read (two TS1127s + an `==== t.js (2 errors) ====` echo) is gone.
     */
    // ── M6: a body-less `global` emits nothing ──────────────────────────────

    @Test
    fun `M6 - a body-less global recovered from a class body emits nothing and a real namespace global still does`() {
        val out = js(
            """
            class C {
                global x
            }
            namespace global { export var v = 1; }
            """
        )
        val expected = """
            "use strict";
            class C {
            }
            x;
            var global;
            (function (global) {
                global.v = 1;
            })(global || (global = {}));
        """.trimIndent()
        assert(out == expected)
    }

    // ── M7: a source-written `export {}` in a JavaScript file ───────────────

    @Test
    fun `M7 - a JavaScript file keeps its own export marker where it is written`() {
        // The corpus case's own shape (`thisInObjectJs`): a `.ts` container name carrying an
        // `@allowJs` + `@outDir` header and one `@filename: index.js` file.
        val result = TypeScriptCompiler().compile(
            "// @target: es2015\n// @allowJs: true\n// @outDir: out\n\n// @filename: index.js\nexport {}\nlet obj = { x: 10 };\n",
            "t.ts",
        )
        val outputs = result.jsOutputs.size
        val out = result.jsOutputs.firstOrNull()?.second
        assert(outputs == 1)
        assert(out == "export {};\nlet obj = { x: 10 };")
    }

    /** In a TypeScript file the written `export {}` is elided and the marker lands LAST. */
    @Test
    fun `M7 - negative control - a TypeScript file elides its written export marker and lands one last`() {
        val out = js(
            """
            export {}
            let obj = { x: 10 };
            export {}
            """
        )
        assert(out == "let obj = { x: 10 };\nexport {};")
    }

    @Test
    fun `M5 - the baseline formatter renders the emitBOM output as a plain content section`() {
        val baseline = TypeScriptCompiler()
            .compile("// @target: es2015\n// @emitBOM: true\nvar x;", "t.ts")
            .toBaseline()
        val hasContentSection = baseline.contains("//// [t.js]\r\n﻿\"use strict\";\r\nvar x;")
        val hasMojibakeErrors = baseline.contains("TS1127")
        assert(hasContentSection)
        assert(!hasMojibakeErrors)
    }
}
