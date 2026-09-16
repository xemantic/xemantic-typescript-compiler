package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (LEGACY.0b) step 21 — two tsgo 7.0.2 mechanisms, both of which replace a TypeScript 6
 * answer with a DIFFERENT CODE AT A DIFFERENT ANCHOR rather than adding a new check.
 *
 * M3, TS8026. In a JS file an `@augments`/`@extends <Base>` JSDoc tag SUPPLIES the heritage
 * type arguments. tsgo counts them and, when the count is out of range, reports at the
 * `extends` EXPRESSION — never at the tag. TypeScript 6 reported TS2314 at the tag instead.
 * Measured against `tools/tsgo-7.0.2/lib/tsc`: all three classes of `jsExtendsImplicitAny`
 * (no tag / a bare tag / a tag with three arguments where one is wanted) answer TS8026 on
 * the heritage name, and a tag supplying the RIGHT count is silent and genuinely BINDS —
 * `@augments A<number>` makes the base's `T` the type `number`.
 *
 * M1, TS2671. A module augmentation of a target whose `export =` resolves to a non-module
 * entity is REFUSED, not merged (`checker.go:1447`). A JS `module.exports = <expr>` is that
 * `export =`, and an object literal / class / function all lack tsgo's Namespace flag; a file
 * exporting through `exports.a = …` is a real ValueModule and stays silent. Because the
 * augmentation is refused, the second declaration it used to contribute never exists, which
 * is what removes the TS2300 pair `jsExportMemberMergedWithModuleAugmentation2` used to carry.
 *
 * Every non-control pin here was proven RED against the pre-change binary.
 */
class TsgoStep21Test {

    // ---------------------------------------------------------------- M3, TS8026

    private val jsExtendsPrelude = """
        // @Filename: /a.d.ts
        declare class A<T> { x: T; }

        // @Filename: /b.js
    """.trimIndent()

    private fun jsExtends(body: String) = diagnose(
        jsExtendsPrelude + "\n" + body.trimIndent(),
        directives = "// @allowJs: true\n// @checkJs: true\n// @noImplicitAny: true",
    )

    @Test
    fun `a bare augments tag leaves TS8026 on the extends name`() {
        val d = jsExtends(
            """
            /** @augments A */
            class C extends A { }
            """
        )
        val rows = d.filter { it.fileName == "/b.js" }
        assert(rows.size == 1)
        val r = rows[0]
        assert(r.code == 8026)
        assert(r.message == "Expected A<T> type arguments; provide these with an '@extends' tag.")
        // tsgo: /b.js(5,17) and (9,17) — the JSDoc line is 1 here, the class line 2,
        // and the column is the `extends` name. 1-based, exactly as tsgo prints it.
        assert(r.line == 2)
        assert(r.character == 17)
        assert(r.length == 1)
    }

    @Test
    fun `a tag with too many type arguments leaves TS8026 on the extends name`() {
        val d = jsExtends(
            """
            /** @augments A<number, number, number> */
            class D extends A {}
            """
        )
        val rows = d.filter { it.fileName == "/b.js" }
        assert(rows.size == 1)
        val r = rows[0]
        assert(r.code == 8026)
        assert(r.message == "Expected A<T> type arguments; provide these with an '@extends' tag.")
        // tsgo: /b.js(5,17) and (9,17) — the JSDoc line is 1 here, the class line 2,
        // and the column is the `extends` name. 1-based, exactly as tsgo prints it.
        assert(r.line == 2)
        assert(r.character == 17)
        assert(r.length == 1)
    }

    @Test
    fun `no TS2314 is reported at the tag for either shape`() {
        val bare = jsExtends(
            """
            /** @augments A */
            class C extends A { }
            """
        )
        val wide = jsExtends(
            """
            /** @augments A<number, number, number> */
            class D extends A {}
            """
        )
        assert(bare.none { it.code == 2314 })
        assert(wide.none { it.code == 2314 })
    }

    @Test
    fun `an untagged JS heritage clause still reports TS8026 - control`() {
        val d = jsExtends("class B extends A {}")
        val rows = d.filter { it.fileName == "/b.js" }
        assert(rows.size == 1)
        assert(rows[0].code == 8026)
        assert(rows[0].line == 1)
        assert(rows[0].character == 17)
    }

    @Test
    fun `negative control - a tag supplying the right count is silent`() {
        val d = jsExtends(
            """
            /** @augments A<number> */
            class E extends A {}
            """
        )
        assert(d.none { it.fileName == "/b.js" })
    }

    @Test
    fun `negative control - an extends alias of the same tag is silent at the right count`() {
        val d = jsExtends(
            """
            /** @extends A<string> */
            class F extends A {}
            """
        )
        assert(d.none { it.fileName == "/b.js" })
    }

    @Test
    fun `negative control - a TypeScript file keeps TS2314 and never TS8026`() {
        val d = diagnose(
            """
            declare class A<T> { x: T; }
            class C extends A { }
            """,
            directives = "// @strict: true",
        )
        assert(d.any { it.code == 2314 })
        assert(d.none { it.code == 8026 })
    }

    // ---------------------------------------------------------------- M1, TS2671

    @Test
    fun `augmenting a CJS object-literal export is TS2671 on the module name`() {
        val d = diagnose(
            """
            // @Filename: /test.js
            module.exports = {
              a: "ok"
            };

            // @Filename: /index.ts
            import { a } from "./test";

            declare module "./test" {
              export const a: number;
            }
            """,
            directives = "// @allowJs: true\n// @checkJs: true",
        )
        val rows = d.filter { it.code == 2671 }
        assert(rows.size == 1)
        val r = rows[0]
        assert(r.message == "Cannot augment module './test' because it resolves to a non-module entity.")
        assert(r.fileName == "/index.ts")
        assert(r.line == 3)
        assert(r.character == 16)
        // the squiggle covers the quoted specifier, `"./test"` — 8 characters
        assert(r.length == 8)
    }

    @Test
    fun `the refused augmentation no longer manufactures a duplicate identifier pair`() {
        val d = diagnose(
            """
            // @Filename: /test.js
            module.exports = {
              a: "ok"
            };

            // @Filename: /index.ts
            import { a } from "./test";

            declare module "./test" {
              export const a: number;
            }

            a.toFixed();
            """,
            directives = "// @allowJs: true\n// @checkJs: true",
        )
        assert(d.none { it.code == 2300 })
        // the JS export still wins, so `a` is `string` and TS2551 is UNCHANGED
        assert(d.any { it.code == 2551 })
        assert(d.any { it.code == 2671 })
    }

    @Test
    fun `a CJS class export is also a non-module entity`() {
        val d = diagnose(
            """
            // @Filename: /test.js
            class Abcde { }
            module.exports = Abcde;

            // @Filename: /index.ts
            import { Abcde } from "./test";

            declare module "./test" {
              interface Zed { b: string }
            }
            """,
            directives = "// @allowJs: true\n// @checkJs: true",
        )
        assert(d.any { it.code == 2671 })
    }

    @Test
    fun `negative control - a JS file exporting through exports dot a is a real module`() {
        val d = diagnose(
            """
            // @Filename: /test.js
            exports.a = 1;

            // @Filename: /index.ts
            import { a } from "./test";

            declare module "./test" {
              interface Zed { b: string }
            }
            """,
            directives = "// @allowJs: true\n// @checkJs: true",
        )
        assert(d.none { it.code == 2671 })
    }

    @Test
    fun `negative control - augmenting a real TypeScript module still merges`() {
        val d = diagnose(
            """
            // @Filename: /test.ts
            export const a = 1;

            // @Filename: /index.ts
            import { a } from "./test";

            declare module "./test" {
              export interface Zed { b: string }
            }

            const z: Zed = { b: "" };
            """,
            directives = "// @strict: true",
        )
        assert(d.none { it.code == 2671 })
    }
}
