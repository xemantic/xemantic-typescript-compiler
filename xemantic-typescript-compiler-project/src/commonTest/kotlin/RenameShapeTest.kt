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
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.ParserFlags
import kotlin.test.Test

/**
 * (API.8) The rename EDIT SHAPES, asserted with no checker and no build — the
 * cheapest pins in this round and the ones to run first.
 *
 * ## What these are built to fail against
 *
 * **A PLAIN OCCURRENCE REWRITE.** Replacing every occurrence with the new name is what
 * a rename looks like from a distance, it passes every "all occurrences were renamed"
 * assertion, and on `{ p }` it silently renames the object's KEY. The first two tests
 * here are that discriminator in its smallest form, asserted as exact replacement TEXT
 * rather than as a count.
 *
 * The second shortcut is a character-predicate validity check for the new name
 * (`isLetterOrDigit || '_' || '$'`), which passes `a digit may not start a name` and
 * fails on a keyword, on an escape and on a name with a space in it — three tests
 * below.
 *
 * Offsets are derived with `indexOf`; a hardcoded offset pins this file's own
 * arithmetic and would pass for an implementation that ignored its argument.
 */
class RenameShapeTest {

    private fun indexOf(text: String): SourceIndex = SourceIndex.of(
        text,
        "/t.ts",
        ParserFlags(
            forceJsx = false,
            topLevelAwait = false,
            needsJsxFlag = false,
            noImplicitAny = false,
        ),
    )

    /** The identifier at [offset] in [text] — asserted to BE one, so a miss is loud. */
    private fun identifierAt(text: String, offset: Int): Identifier {
        val node = indexOf(text).pathAt(offset).lastOrNull()
        assert(node is Identifier)
        return node
    }

    /** What replaces the identifier at the first occurrence of [needle] in [text]. */
    private fun rewriteAt(text: String, needle: String, old: String, new: String): String {
        val at = text.indexOf(needle)
        assert(at >= 0)
        return SyntaxRoles.renameRewrite(identifierAt(text, at), old, new).text
    }

    // --- THE DISCRIMINATOR --------------------------------------------------------

    /**
     * `{ p }` is a KEY and a REFERENCE spelled with one identifier. Replacing it
     * outright compiles and renames the key; the expansion moves only the reference.
     */
    @Test
    fun `an object literal shorthand expands rather than being replaced`() {
        assert(rewriteAt("const p = 1; const o = { p };", "p };", "p", "q") == "p: q")
    }

    /**
     * The same trap in the other direction: `{ q }` would destructure a property the
     * source object does not have.
     */
    @Test
    fun `a binding pattern shorthand expands rather than being replaced`() {
        assert(rewriteAt("declare const o: { p: number }; const { p } = o;", "p } = o", "p", "q") == "p: q")
    }

    /** The control the two above need: an ordinary occurrence is just replaced. */
    @Test
    fun `an ordinary occurrence is replaced by the new name`() {
        assert(rewriteAt("const p = 1; const u = p;", "p;", "p", "q") == "q")
    }

    /**
     * A binding element that ALREADY names its property is not a shorthand, so only
     * the local half moves — the property name is a different symbol and stays.
     */
    @Test
    fun `a binding element with an explicit property name replaces only the local`() {
        assert(
            rewriteAt("declare const o: { p: number }; const { p: local } = o;", "local }", "local", "q") == "q",
        )
    }

    /**
     * `export { p }` and `import { p }` are DELIBERATELY plain, where tsc expands both
     * to preserve the module's public name. Our identity crosses the alias hop, so the
     * export and the local are one symbol being renamed together — and expanding would
     * make `export { p }` behave differently from `export const p`, whose public name a
     * rename does change.
     */
    @Test
    fun `a bare export specifier is replaced plainly - stated divergence from tsc`() {
        assert(rewriteAt("const p = 1; export { p };", "p };", "p", "q") == "q")
    }

    @Test
    fun `a bare import specifier is replaced plainly`() {
        assert(rewriteAt("""import { p } from "./m"; const u = p;""", "p }", "p", "q") == "q")
    }

    /**
     * Where the NEW NAME lands inside the replacement, which the verification pass
     * re-asks the compiler about. An expansion moves it and a plain replacement does
     * not; getting this wrong makes the check ask about the OLD name and pass
     * vacuously.
     */
    @Test
    fun `the rewrite reports where the new name begins inside it`() {
        val text = "const p = 1; const o = { p };"
        assert(SyntaxRoles.renameRewrite(identifierAt(text, text.indexOf("p };")), "p", "q").nameOffset == 3)
        assert(SyntaxRoles.renameRewrite(identifierAt(text, text.indexOf("p = 1")), "p", "q").nameOffset == 0)
    }

    // --- the new name -------------------------------------------------------------

    @Test
    fun `an ordinary word is a valid new name`() {
        assert(SyntaxRoles.isIdentifierName("betterName"))
        assert(SyntaxRoles.isIdentifierName("_private"))
        assert(SyntaxRoles.isIdentifierName("\$dollar"))
        assert(SyntaxRoles.isIdentifierName("Ünïcödé"))
    }

    @Test
    fun `a name that is not one identifier token is refused`() {
        assert(!SyntaxRoles.isIdentifierName(""))
        assert(!SyntaxRoles.isIdentifierName("1bad"))
        assert(!SyntaxRoles.isIdentifierName("two words"))
        assert(!SyntaxRoles.isIdentifierName(" leading"))
        assert(!SyntaxRoles.isIdentifierName("trailing "))
        assert(!SyntaxRoles.isIdentifierName("has-dash"))
        assert(!SyntaxRoles.isIdentifierName("a.b"))
    }

    /**
     * A KEYWORD scans as its own token kind, so the lexer answers this too — which is
     * exactly why the reserved check has to run FIRST in `Project.renameAt`, or every
     * reserved word would be reported as "not an identifier".
     */
    @Test
    fun `a reserved word is not an identifier token and is also listed as reserved`() {
        assert(!SyntaxRoles.isIdentifierName("class"))
        assert("class" in SyntaxRoles.RESERVED_WORDS)
        assert("yield" in SyntaxRoles.RESERVED_WORDS)
        assert("await" in SyntaxRoles.RESERVED_WORDS)
    }

    /**
     * TypeScript's CONTEXTUAL keywords are legal binding names, so refusing them would
     * be inventing a rule the language does not have.
     */
    @Test
    fun `a contextual keyword is an allowed new name`() {
        assert("type" !in SyntaxRoles.RESERVED_WORDS)
        assert("any" !in SyntaxRoles.RESERVED_WORDS)
        assert("as" !in SyntaxRoles.RESERVED_WORDS)
        assert("of" !in SyntaxRoles.RESERVED_WORDS)
    }

    // --- the completeness net's two syntactic questions ---------------------------

    /**
     * The axis the completeness net splits on. Without it an `interface I { p }`
     * anywhere in the program would refuse renaming an unrelated local `p`, because a
     * member declaration name resolves to nothing.
     */
    @Test
    fun `a member position is told from a free one`() {
        val text = """
            interface I { p: string; }
            declare const o: I;
            const read = o.p;
            const p = 1;
            const use = p;
            namespace N { export const q = 1; }
            const viaNs = N.q;
        """.trimIndent()
        assert(SyntaxRoles.isMemberPosition(identifierAt(text, text.indexOf("p: string"))))
        assert(SyntaxRoles.isMemberPosition(identifierAt(text, text.indexOf("o.p") + 2)))
        assert(SyntaxRoles.isMemberPosition(identifierAt(text, text.indexOf("N.q") + 2)))
        assert(!SyntaxRoles.isMemberPosition(identifierAt(text, text.indexOf("const p = 1") + 6)))
        assert(!SyntaxRoles.isMemberPosition(identifierAt(text, text.indexOf("use = p") + 6)))
    }

    /** Both shorthand forms hide a property name; an explicit one does not. */
    @Test
    fun `a property-hiding shorthand is told from an explicit one`() {
        val text = """
            const p = 1;
            const o = { p };
            declare const src: { p: number; r: number };
            const { p: explicit } = src;
            const { r } = src;
        """.trimIndent()
        assert(SyntaxRoles.isPropertyHidingShorthand(identifierAt(text, text.indexOf("p };"))))
        assert(SyntaxRoles.isPropertyHidingShorthand(identifierAt(text, text.indexOf("r } = src"))))
        assert(!SyntaxRoles.isPropertyHidingShorthand(identifierAt(text, text.indexOf("p: explicit"))))
        assert(!SyntaxRoles.isPropertyHidingShorthand(identifierAt(text, text.indexOf("explicit"))))
    }

    /**
     * The obstacle no resolution can see: a member named by a string literal. The scan
     * must find it, and must not mistake a numeric or computed index for one.
     */
    @Test
    fun `element accesses named by a string literal are found and others are not`() {
        val text = """
            declare const o: { p: number };
            declare const arr: number[];
            const a = o["p"];
            const b = arr[0];
            const key = "p";
            const c = o[key];
        """.trimIndent()
        val found = SyntaxRoles.stringElementAccesses(indexOf(text).sourceFile)
        assert(found.map { it.second } == listOf("p"))
        assert(found.single().first.pos == text.indexOf("""o["p"]""") + 2)
    }
}
