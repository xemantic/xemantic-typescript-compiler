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
 * (API.6) [Project.signatureHelpAt] — a caret offset in, the callee's signatures
 * out, through a real build of a real (in-memory) project.
 *
 * THE DISCRIMINATING DEVICE IS AN OVERLOADED CALLEE. Every plausible shortcut —
 * resolving the callee's type and rendering it, taking the one signature overload
 * resolution would pick, matching the callee by NAME against a declaration —
 * produces ONE signature and passes every other pin in this file. Only asking the
 * callee's type for its whole SIGNATURE LIST produces three, so the discriminator is
 * an EXACT list of three labels: a one-element answer, a superset and a reordering
 * all fail it.
 *
 * Every needle below names a CALL SITE and not just the callee's spelling — a bare
 * `pick(` matches the DECLARATION first, and a test written that way measures the
 * wrong offset while looking entirely correct (round 917's caret-marker trap, one
 * mechanism over).
 */
class ProjectSignatureHelpTest {

    /**
     * `module` is an ES kind ON PURPOSE and the program has TWO files — the trap
     * `docs/language-service.md` § 11 records: below two program files, or with
     * `module` unset, every import-related assertion is vacuous.
     */
    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val mainFile = "/proj/src/a.ts"
    private val otherFile = "/proj/src/b.ts"

    private val main = """
        import { imported, ns } from "./b";
        declare function pick(a: string): string;
        declare function pick(a: number, b: number): number;
        declare function pick(a: boolean, b: boolean, c: boolean): boolean;
        declare function plain(alpha: number, beta?: string): void;
        declare function collect(first: string, ...rest: number[]): void;
        declare function pickFrom<T>(xs: T[], index: number): T;
        declare function destructured({ a, b }: { a: number; b: number }, tail: string): void;
        declare function makeFn(): (inner: number) => void;
        function withDefault(a: number, b: number = 2): void {}
        declare const obj: { method(a: number, b: string): boolean };
        class Point { constructor(x: number, y: number) {} }
        class Base { constructor(seed: number) {} }
        class Sub extends Base { constructor() { super(1); } }
        declare const notCallable: number;
        declare function dec(tag: string): (t: unknown) => void;
        @dec("x")
        class Decorated {}
        export const useOverloadEmpty = pick();
        export const useOverloadOne = pick(1, 2);
        export const useOverloadBool = pick(true, false, false);
        export const usePlain = plain(1, "x");
        export const usePlainThird = plain(1, "x", );
        export const useCollect = collect("a", 1, 2, 3);
        export const usePickFrom = pickFrom([1], 0);
        export const useDestructured = destructured({ a: 1, b: 2 }, "t");
        export const useWithDefault = withDefault(1, 2);
        export const useMakeFn = makeFn()(1);
        export const useMethod = obj.method(1, "x");
        export const usePoint = new Point(1, 2);
        export const useImported = imported(1, "x");
        export const useNs = ns.nested(1);
        export const useNowhere = nowhere(1);
        export const notACall = notCallable;
    """.trimIndent() + "\n"

    private val other = """
        export declare function imported(first: number, second: string): void;
        export namespace ns { export function nested(only: number): void {} }
    """.trimIndent() + "\n"

    private fun vfs(mainText: String = main) = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            mainFile to mainText,
            otherFile to other,
        ),
    )

    private fun projectWith(mainText: String = main): Project = Project.open("/proj", vfs(mainText))

    /** The caret immediately after [callText], which must end at or inside a call. */
    private fun caretAfter(callText: String, text: String = main): Int {
        val at = text.indexOf(callText)
        assert(at >= 0)
        return at + callText.length
    }

    // --- THE DISCRIMINATOR --------------------------------------------------------

    /**
     * An overloaded callee: EVERY overload comes back, in declaration order, each
     * with its OWN parameter list. A mechanism that resolves the callee's type
     * without asking for its signature list answers one of these and passes every
     * other pin in this file.
     */
    @Test
    fun `an OVERLOADED callee answers with every overload in declaration order`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("useOverloadEmpty = pick("))
        assert(help != null)
        assert(
            help.signatures.map { it.label } == listOf(
                "pick(a: string): string",
                "pick(a: number, b: number): number",
                "pick(a: boolean, b: boolean, c: boolean): boolean",
            ),
        )
        assert(help.activeArgument == 0)
    }

    // --- which overload is ACTIVE --------------------------------------------------

    @Test
    fun `the active overload is the first with room for the caret's argument`() {
        val project = projectWith()
        // Argument 1: the one-parameter overload has no room for it, so the
        // two-parameter one is active. A rule that always answered 0 passes the
        // empty-call case above and fails here.
        val help = project.signatureHelpAt(mainFile, caretAfter("useOverloadOne = pick(1, "))
        assert(help != null)
        assert(help.activeArgument == 1)
        assert(help.activeSignature == 1)
    }

    @Test
    fun `the active overload also rejects one the finished arguments do not fit`() {
        val project = projectWith()
        // Argument 1 again, but the FIRST argument is a boolean: the two-parameter
        // overload wants a number there, so the three-parameter one is active. This
        // is the half of the rule a pure arity test cannot answer.
        val help = project.signatureHelpAt(mainFile, caretAfter("useOverloadBool = pick(true, "))
        assert(help != null)
        assert(help.activeArgument == 1)
        assert(help.activeSignature == 2)
    }

    @Test
    fun `the argument the caret is IN is not judged`() {
        val project = projectWith()
        // Inside argument 0 of `pick(true, …)`. Judging that argument would already
        // exclude the string overload; the rule looks only at arguments BEFORE the
        // caret, so the answer is still the first signature with room at index 0.
        val help = project.signatureHelpAt(mainFile, caretAfter("useOverloadBool = pick(") + 2)
        assert(help != null)
        assert(help.activeArgument == 0)
        assert(help.activeSignature == 0)
    }

    // --- what a rendered signature says --------------------------------------------

    @Test
    fun `a parameter's range indexes its own signature label`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("usePlain = plain("))
        assert(help != null)
        val signature = help.signatures.single()
        assert(signature.label == "plain(alpha: number, beta?: string): void")
        val alpha = signature.parameters[0]
        val beta = signature.parameters[1]
        // The ranges index the LABEL, and they are exact — which is the whole point
        // of recording them as the label is built rather than searching for them.
        assert(signature.label.substring(alpha.labelStart, alpha.labelEnd) == "alpha: number")
        assert(signature.label.substring(beta.labelStart, beta.labelEnd) == "beta?: string")
        assert(signature.returnTypeText == "void")
    }

    @Test
    fun `an OPTIONAL parameter is reported optional and a required one is not`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("usePlain = plain("))
        assert(help != null)
        val parameters = help.signatures.single().parameters
        assert(parameters.map { it.name } == listOf("alpha", "beta"))
        assert(!parameters[0].optional)
        assert(parameters[1].optional)
        assert(parameters.none { it.isRest })
    }

    @Test
    fun `a DEFAULTED parameter is optional too - a caller may omit it`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("useWithDefault = withDefault("))
        assert(help != null)
        val parameters = help.signatures.single().parameters
        assert(parameters.map { it.name } == listOf("a", "b"))
        assert(!parameters[0].optional)
        assert(parameters[1].optional)
    }

    @Test
    fun `the active parameter follows the active argument`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("usePlain = plain(1, "))
        assert(help != null)
        assert(help.activeArgument == 1)
        assert(help.signatures.single().activeParameter == 1)
    }

    // --- rest parameters -----------------------------------------------------------

    @Test
    fun `a REST parameter is reported, and the active parameter CLAMPS to it`() {
        val project = projectWith()
        // Argument 3 — two past the fixed parameter — must keep `...rest`
        // highlighted, because every further argument feeds it.
        val help = project.signatureHelpAt(mainFile, caretAfter("useCollect = collect(\"a\", 1, 2, "))
        assert(help != null)
        assert(help.activeArgument == 3)
        val signature = help.signatures.single()
        assert(signature.label == "collect(first: string, ...rest: number[]): void")
        assert(signature.parameters[1].isRest)
        // NOT optional: a rest parameter and an optional one mean different things.
        assert(!signature.parameters[1].optional)
        assert(signature.activeParameter == 1)
    }

    @Test
    fun `an argument past a signature with NO rest parameter has no active parameter`() {
        val project = projectWith()
        // `plain` takes two; the caret is on the third, which no parameter receives.
        // -1 rather than the last index: pointing at `beta` would say the user is
        // typing something that signature accepts.
        val help = project.signatureHelpAt(mainFile, caretAfter("usePlainThird = plain(1, \"x\", "))
        assert(help != null)
        assert(help.activeArgument == 2)
        assert(help.signatures.single().activeParameter == -1)
    }

    // --- the callee resolution is (API.3d)'s ---------------------------------------

    @Test
    fun `a METHOD through a receiver answers with the method's signature`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("useMethod = obj.method("))
        assert(help != null)
        assert(help.signatures.map { it.label } == listOf("method(a: number, b: string): boolean"))
    }

    @Test
    fun `a CONSTRUCTOR answers with a new-prefixed construct signature`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("usePoint = new Point(1, "))
        assert(help != null)
        val signature = help.signatures.single()
        assert(signature.label.startsWith("new Point(x: number, y: number)"))
        assert(signature.parameters.map { it.name } == listOf("x", "y"))
        assert(help.activeArgument == 1)
        assert(signature.activeParameter == 1)
    }

    @Test
    fun `an IMPORTED function answers about the declaration in the other file`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("useImported = imported("))
        assert(help != null)
        assert(
            help.signatures.map { it.label } ==
                listOf("imported(first: number, second: string): void"),
        )
    }

    @Test
    fun `a NAMESPACE member answers`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("useNs = ns.nested("))
        assert(help != null)
        assert(help.signatures.map { it.label } == listOf("nested(only: number): void"))
    }

    @Test
    fun `a callee that is itself a CALL answers about the returned function`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("useMakeFn = makeFn()("))
        assert(help != null)
        // No syntactic name — the callee is an expression, so the label carries none
        // rather than an invented one.
        assert(help.signatures.map { it.label } == listOf("(inner: number): void"))
    }

    // --- generics ------------------------------------------------------------------

    @Test
    fun `a GENERIC callee renders its declared type parameters, uninstantiated`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("usePickFrom = pickFrom("))
        assert(help != null)
        // `T`, not `number` — inferring it would mean inferring from arguments the
        // user has not finished typing.
        assert(help.signatures.map { it.label } == listOf("pickFrom<T>(xs: T[], index: number): T"))
    }

    // --- a destructured parameter --------------------------------------------------

    @Test
    fun `a DESTRUCTURED parameter is rendered rather than dropped`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("useDestructured = destructured("))
        assert(help != null)
        val signature = help.signatures.single()
        // The compiler drops binding-pattern parameters from a signature's symbols
        // and positionally zips what is left, so rendering from the symbols alone
        // would print `destructured(tail: { a: number; b: number })` — one parameter
        // short AND wearing its neighbour's type. The declaration is rendered instead.
        assert(signature.parameters.size == 2)
        assert(signature.parameters[0].name == "{ a, b }")
        assert(signature.parameters[1].name == "tail")
        assert(signature.parameters[1].typeText == "string")
    }

    // --- the negatives -------------------------------------------------------------

    @Test
    fun `an UNRESOLVABLE callee answers an empty signature list, not null`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("useNowhere = nowhere("))
        // The caret IS in an argument list — that fact is reported — and the callee
        // has no signatures. A host tells this from "not in a call" by the null.
        assert(help != null)
        assert(help.signatures.isEmpty())
        assert(help.activeSignature == 0)
        assert(help.activeArgument == 0)
    }

    @Test
    fun `a caret outside every argument list answers null`() {
        val project = projectWith()
        assert(project.signatureHelpAt(mainFile, main.indexOf("notACall")) == null)
        assert(project.signatureHelpAt(mainFile, 0) == null)
    }

    @Test
    fun `a caret in an unknown file answers null`() {
        val project = projectWith()
        assert(project.signatureHelpAt("/proj/src/nope.ts", 0) == null)
    }

    @Test
    fun `super is refused - it binds to nothing, so no constructor is offered`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("super("))
        // The anchor finds the call; `super` binds to nothing here, so the enclosing
        // class's base constructor is NOT offered. Stated as a refusal rather than
        // left to look like a resolution failure.
        assert(help != null)
        assert(help.signatures.isEmpty())
    }

    @Test
    fun `a DECORATOR factory is not refused - it is an ordinary call`() {
        val project = projectWith()
        val help = project.signatureHelpAt(mainFile, caretAfter("@dec("))
        assert(help != null)
        assert(help.signatures.map { it.label } == listOf("dec(tag: string): (t: unknown) => void"))
        assert(help.activeArgument == 0)
    }

    // --- the seams every other query in this module also has -----------------------

    @Test
    fun `a caret outside every argument list DOES NOT BUILD`() {
        val counting = CountingVfs(vfs())
        val project = Project.open("/proj", counting)
        // Warms the parse and the resolved options, neither of which is a build.
        project.nodeInfoAt(mainFile, 0)
        val before = counting.readsOf("/proj/tsconfig.json")
        assert(project.signatureHelpAt(mainFile, 0) == null)
        assert(counting.readsOf("/proj/tsconfig.json") == before)
    }

    @Test
    fun `an EDIT is seen without touching disk`() {
        val project = projectWith()
        assert(project.signatureHelpAt(mainFile, caretAfter("usePlain = plain(")) != null)
        val edited = main.replace(
            "declare function plain(alpha: number, beta?: string): void;",
            "declare function plain(renamed: boolean): void;",
        )
        project.updateFile(mainFile, edited)
        val help = project.signatureHelpAt(mainFile, caretAfter("usePlain = plain(", edited))
        assert(help != null)
        assert(help.signatures.map { it.label } == listOf("plain(renamed: boolean): void"))
    }

    @Test
    fun `a closed project refuses the query`() {
        val project = projectWith()
        project.close()
        var threw = false
        try {
            project.signatureHelpAt(mainFile, caretAfter("usePlain = plain("))
        } catch (_: IllegalStateException) {
            threw = true
        }
        assert(threw)
    }

    // --- the incomplete call, end to end -------------------------------------------

    @Test
    fun `an argument list left open still answers - the case an editor lives in`() {
        // The shape a user is in WHILE typing: no closing paren, nothing after it.
        // The call node's own real end lies before the caret, so a containment test
        // finds nothing and only the token-level anchor answers.
        val truncated = "declare function plain(alpha: number, beta?: string): void;\n" +
            "export const usePlain = plain(1, "
        val project = projectWith(truncated)
        val help = project.signatureHelpAt(mainFile, truncated.length)
        assert(help != null)
        assert(help.activeArgument == 1)
        assert(
            help.signatures.map { it.label } == listOf("plain(alpha: number, beta?: string): void"),
        )
    }
}
