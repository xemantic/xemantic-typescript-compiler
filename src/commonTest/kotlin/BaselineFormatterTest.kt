/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.sameAs
import kotlin.test.Test

class BaselineFormatterTest {

    // --- formatSourceOnlyBaseline ---

    @Test
    fun `formatSourceOnlyBaseline should format simple source`() {
        // given
        val fileName = "foo.ts"
        val cleanedSource = "const x = 1;\n"

        // when
        val result = formatSourceOnlyBaseline(fileName, cleanedSource)

        // then
        val expected = "//// [tests/cases/compiler/foo.ts] ////\r\n" +
                "\r\n" +
                "//// [foo.ts]\r\n" +
                "const x = 1;\n" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatSourceOnlyBaseline should strip directory from fileName`() {
        // given
        val fileName = "some/path/bar.ts"
        val cleanedSource = "x"

        // when
        val result = formatSourceOnlyBaseline(fileName, cleanedSource)

        // then
        val expected = "//// [tests/cases/compiler/bar.ts] ////\r\n" +
                "\r\n" +
                "//// [bar.ts]\r\n" +
                "x" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatSourceOnlyBaseline should strip CR from source`() {
        // given
        val fileName = "foo.ts"
        val cleanedSource = "a\r\nb\r\n"

        // when
        val result = formatSourceOnlyBaseline(fileName, cleanedSource)

        // then
        val expected = "//// [tests/cases/compiler/foo.ts] ////\r\n" +
                "\r\n" +
                "//// [foo.ts]\r\n" +
                "a\nb\n" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    // --- formatBaseline ---

    @Test
    fun `formatBaseline should format simple ts to js`() {
        // given
        val fileName = "hello.ts"
        val cleanedSource = "const x = 1;\n"
        val javascript = "var x = 1;\n"

        // when
        val result = formatBaseline(fileName, cleanedSource, javascript)

        // then
        val expected = "//// [tests/cases/compiler/hello.ts] ////\r\n" +
                "\r\n" +
                "//// [hello.ts]\r\n" +
                "const x = 1;\n" +
                "\r\n" +
                "\r\n" +
                "//// [hello.js]\r\n" +
                "var x = 1;\r\n" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should replace tsx with jsx when jsx is preserve`() {
        // given
        val fileName = "comp.tsx"
        val cleanedSource = "<div/>"
        val javascript = "<div/>"

        // when
        val result = formatBaseline(fileName, cleanedSource, javascript, jsx = "preserve")

        // then
        val expected = "//// [tests/cases/compiler/comp.tsx] ////\r\n" +
                "\r\n" +
                "//// [comp.tsx]\r\n" +
                "<div/>" +
                "\r\n" +
                "\r\n" +
                "//// [comp.jsx]\r\n" +
                "<div/>" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should replace tsx with js when jsx is not preserve`() {
        // given
        val fileName = "comp.tsx"
        val cleanedSource = "<div/>"
        val javascript = "React.createElement(\"div\")"

        // when
        val result = formatBaseline(fileName, cleanedSource, javascript, jsx = "react")

        // then
        val expected = "//// [tests/cases/compiler/comp.tsx] ////\r\n" +
                "\r\n" +
                "//// [comp.tsx]\r\n" +
                "<div/>" +
                "\r\n" +
                "\r\n" +
                "//// [comp.js]\r\n" +
                "React.createElement(\"div\")" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should replace mts with mjs`() {
        // given
        val fileName = "mod.mts"

        // when
        val result = formatBaseline(fileName, "export {}", "export {}")

        // then
        val expected = "//// [tests/cases/compiler/mod.mts] ////\r\n" +
                "\r\n" +
                "//// [mod.mts]\r\n" +
                "export {}" +
                "\r\n" +
                "\r\n" +
                "//// [mod.mjs]\r\n" +
                "export {}" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should replace cts with cjs`() {
        // given
        val fileName = "mod.cts"

        // when
        val result = formatBaseline(fileName, "export {}", "export {}")

        // then
        val expected = "//// [tests/cases/compiler/mod.cts] ////\r\n" +
                "\r\n" +
                "//// [mod.cts]\r\n" +
                "export {}" +
                "\r\n" +
                "\r\n" +
                "//// [mod.cjs]\r\n" +
                "export {}" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should use outFile name when provided`() {
        // given
        val fileName = "input.ts"
        val outFile = "dist/bundle.js"

        // when
        val result = formatBaseline(fileName, "const x = 1;", "var x = 1;", outFile = outFile)

        // then
        val expected = "//// [tests/cases/compiler/input.ts] ////\r\n" +
                "\r\n" +
                "//// [input.ts]\r\n" +
                "const x = 1;" +
                "\r\n" +
                "\r\n" +
                "//// [bundle.js]\r\n" +
                "var x = 1;" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should use LF for JS output when newLine is LF`() {
        // given
        val fileName = "test.ts"
        val javascript = "var x = 1;\nvar y = 2;\n"

        // when
        val result = formatBaseline(fileName, "const x = 1;", javascript, newLine = "LF")

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [test.ts]\r\n" +
                "const x = 1;" +
                "\r\n" +
                "\r\n" +
                "//// [test.js]\r\n" +
                "var x = 1;\nvar y = 2;\n" +
                "\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should use CRLF for JS output by default`() {
        // given
        val fileName = "test.ts"
        val javascript = "var x = 1;\nvar y = 2;\n"

        // when
        val result = formatBaseline(fileName, "const x = 1;", javascript)

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [test.ts]\r\n" +
                "const x = 1;" +
                "\r\n" +
                "\r\n" +
                "//// [test.js]\r\n" +
                "var x = 1;\r\nvar y = 2;\r\n" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should append sourceMap comment when sourceMap is true`() {
        // given
        val fileName = "test.ts"

        // when
        val result = formatBaseline(fileName, "const x = 1;", "var x = 1;", sourceMap = true)

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [test.ts]\r\n" +
                "const x = 1;" +
                "\r\n" +
                "\r\n" +
                "//// [test.js]\r\n" +
                "var x = 1;" +
                "\r\n" +
                "//# sourceMappingURL=test.js.map"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should include mapRoot in sourceMap URL`() {
        // given
        val fileName = "test.ts"
        val mapRoot = "http://example.com/maps/"

        // when
        val result = formatBaseline(
            fileName, "const x = 1;", "var x = 1;",
            sourceMap = true, mapRoot = mapRoot
        )

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [test.ts]\r\n" +
                "const x = 1;" +
                "\r\n" +
                "\r\n" +
                "//// [test.js]\r\n" +
                "var x = 1;" +
                "\r\n" +
                "//# sourceMappingURL=http://example.com/maps/test.js.map"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should trim trailing slash from mapRoot`() {
        // given
        val fileName = "test.ts"
        val mapRoot = "maps///"

        // when
        val result = formatBaseline(
            fileName, "const x = 1;", "var x = 1;",
            sourceMap = true, mapRoot = mapRoot
        )

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [test.ts]\r\n" +
                "const x = 1;" +
                "\r\n" +
                "\r\n" +
                "//// [test.js]\r\n" +
                "var x = 1;" +
                "\r\n" +
                "//# sourceMappingURL=maps/test.js.map"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should percent-encode special chars in sourceMap URL`() {
        // given
        val fileName = "test [1].ts"

        // when
        val result = formatBaseline(
            fileName, "const x = 1;", "var x = 1;",
            sourceMap = true
        )

        // then
        val expected = "//// [tests/cases/compiler/test [1].ts] ////\r\n" +
                "\r\n" +
                "//// [test [1].ts]\r\n" +
                "const x = 1;" +
                "\r\n" +
                "\r\n" +
                "//// [test [1].js]\r\n" +
                "var x = 1;" +
                "\r\n" +
                "//# sourceMappingURL=test%20%5B1%5D.js.map"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should preserve LF inside template literals in CRLF mode`() {
        // given
        val fileName = "test.ts"
        val javascript = "var s = `line1\nline2`;\nvar y = 1;\n"

        // when
        val result = formatBaseline(fileName, "const x = 1;", javascript)

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [test.ts]\r\n" +
                "const x = 1;" +
                "\r\n" +
                "\r\n" +
                "//// [test.js]\r\n" +
                "var s = `line1\nline2`;\r\nvar y = 1;\r\n" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    // --- formatMultiFileBaseline ---

    @Test
    fun `formatMultiFileBaseline should format multiple source files and JS outputs`() {
        // given
        val testFileName = "multi.ts"
        val sourceEchoes = listOf(
            "a.ts" to "const a = 1;\n",
            "b.ts" to "const b = 2;\n"
        )
        val jsOutputs = listOf(
            "a.js" to "var a = 1;\n",
            "b.js" to "var b = 2;\n"
        )

        // when
        val result = formatMultiFileBaseline(testFileName, sourceEchoes, jsOutputs)

        // then
        val expected = "//// [tests/cases/compiler/multi.ts] ////\r\n" +
                "\r\n" +
                "//// [a.ts]\r\n" +
                "const a = 1;\n" +
                "\r\n" +
                "//// [b.ts]\r\n" +
                "const b = 2;\n" +
                "\r\n" +
                "\r\n" +
                "//// [a.js]\r\n" +
                "var a = 1;\r\n" +
                "\r\n" +
                "//// [b.js]\r\n" +
                "var b = 2;\r\n" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatMultiFileBaseline should strip directory from source echo file names`() {
        // given
        val sourceEchoes = listOf("sub/dir/file.ts" to "x")
        val jsOutputs = listOf("file.js" to "x")

        // when
        val result = formatMultiFileBaseline("test.ts", sourceEchoes, jsOutputs)

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [file.ts]\r\n" +
                "x" +
                "\r\n" +
                "\r\n" +
                "//// [file.js]\r\n" +
                "x" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatMultiFileBaseline should not add trailing newline for empty JS output`() {
        // given
        val sourceEchoes = listOf("a.ts" to "export {}")
        val jsOutputs = listOf("a.js" to "")

        // when
        val result = formatMultiFileBaseline("test.ts", sourceEchoes, jsOutputs)

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [a.ts]\r\n" +
                "export {}" +
                "\r\n" +
                "\r\n" +
                "//// [a.js]\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatMultiFileBaseline should add sourceMap comments for JS files only`() {
        // given
        val sourceEchoes = listOf("a.ts" to "x")
        val jsOutputs = listOf(
            "a.js" to "var x;",
            "config.json" to "{}"
        )

        // when
        val result = formatMultiFileBaseline("test.ts", sourceEchoes, jsOutputs, sourceMap = true)

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [a.ts]\r\n" +
                "x" +
                "\r\n" +
                "\r\n" +
                "//// [a.js]\r\n" +
                "var x;" +
                "\r\n" +
                "//# sourceMappingURL=a.js.map" +
                "\r\n" +
                "//// [config.json]\r\n" +
                "{}" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatMultiFileBaseline should add sourceMap comments for mjs and cjs files`() {
        // given
        val sourceEchoes = listOf("a.mts" to "x", "b.cts" to "y")
        val jsOutputs = listOf(
            "a.mjs" to "var x;",
            "b.cjs" to "var y;"
        )

        // when
        val result = formatMultiFileBaseline("test.ts", sourceEchoes, jsOutputs, sourceMap = true)

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [a.mts]\r\n" +
                "x" +
                "\r\n" +
                "//// [b.cts]\r\n" +
                "y" +
                "\r\n" +
                "\r\n" +
                "//// [a.mjs]\r\n" +
                "var x;" +
                "\r\n" +
                "//# sourceMappingURL=a.mjs.map" +
                "\r\n" +
                "//// [b.cjs]\r\n" +
                "var y;" +
                "\r\n" +
                "//# sourceMappingURL=b.cjs.map"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    // --- toCRLF edge cases (tested through formatBaseline) ---

    @Test
    fun `formatBaseline should convert LF to CRLF inside block comments`() {
        // given
        val javascript = "/* comment\nline2 */\nvar x;\n"

        // when
        val result = formatBaseline("test.ts", "x", javascript)

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [test.ts]\r\n" +
                "x" +
                "\r\n" +
                "\r\n" +
                "//// [test.js]\r\n" +
                "/* comment\r\nline2 */\r\nvar x;\r\n" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should handle backticks inside line comments without toggling template mode`() {
        // given
        val javascript = "// comment with `backtick`\nvar x;\n"

        // when
        val result = formatBaseline("test.ts", "x", javascript)

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [test.ts]\r\n" +
                "x" +
                "\r\n" +
                "\r\n" +
                "//// [test.js]\r\n" +
                "// comment with `backtick`\r\nvar x;\r\n" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should handle backticks inside block comments without toggling template mode`() {
        // given
        val javascript = "/* `not a template` */\nvar x;\n"

        // when
        val result = formatBaseline("test.ts", "x", javascript)

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [test.ts]\r\n" +
                "x" +
                "\r\n" +
                "\r\n" +
                "//// [test.js]\r\n" +
                "/* `not a template` */\r\nvar x;\r\n" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }

    @Test
    fun `formatBaseline should handle escaped backticks inside template literals`() {
        // given
        val javascript = "var s = `escaped \\` backtick\nstill template`;\nvar y;\n"

        // when
        val result = formatBaseline("test.ts", "x", javascript)

        // then
        val expected = "//// [tests/cases/compiler/test.ts] ////\r\n" +
                "\r\n" +
                "//// [test.ts]\r\n" +
                "x" +
                "\r\n" +
                "\r\n" +
                "//// [test.js]\r\n" +
                "var s = `escaped \\` backtick\nstill template`;\r\nvar y;\r\n" +
                "\r\n"

        // we are asserting twice,
        // 1 - for the diff output
        // 2 - to assert exact strings even with trailing \r\n
        result sameAs expected
        assert(result == expected)
    }
}