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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.133)(b) — the RELATION's `this` leg, tsc's `compareSignaturesRelated`: a source
 * signature's `this` type other than `void` must relate to the target's, in the
 * contravariant direction the parameters take (the TARGET's `this` assignable to the
 * SOURCE's) or, where tsc's `strictVariance` is off — a target declared as a METHOD
 * (`kind === MethodDeclaration || MethodSignature`, one AST node here), or a callback
 * parameter (`SignatureCheckMode.Callback`) — in either direction. The chain line is
 * TS2684's text *The 'this' types of each signature are incompatible.*, nested under the
 * ordinary TS2322 / TS2345 / TS2416 elaboration and followed by the elaboration of the
 * target's `this` against the source's.
 *
 * Every expected message and chain below is pristine `typescript@6.0.3`'s byte for byte
 * (chain nesting included), and tsgo 7.0.2 agrees on every one of them — the matrix had
 * ZERO REF-SPLIT rows. The ONE predicate ([Relater.signatureThisTypesRelated]) serves the
 * relation and both elaborations (`Checker.getFunctionMismatchElaborationWorker`,
 * `Checker.addSignatureElaboration`), so the chain line cannot disagree with the verdict.
 *
 * Reach: on all eight dashboard profiles the leg is entered 2,639-3,110 times per compile
 * and every entry is a signature related to ITSELF (the lib's `this: This` / `this: A`
 * type-parameter `this`), skipped as generic; zero on marked, cronstrue and the
 * 2,400-file project. So the 8-profile grid is a CONTROL for this leg and these pins are
 * the gate.
 *
 * Pre-existing families this round measured and did NOT touch, each pinned `residue -`:
 * `strictFunctionTypes: false` is not modelled by the parameter leg either (`CompilerOptions`
 * carries no such field; `(x: ZzzA & ZzzC) => void` against `(x: ZzzA) => void` reports
 * here and not in pristine under that flag); a UNION of function types as the SOURCE of a
 * declaration is refused by `canUseTypeEngine`'s object-carrying-union skip, so
 * `((this: ZzzA) => void) | ((this: ZzzC) => void)` against `(this: ZzzB) => void` is silent
 * where both references report; and an object-literal METHOD against a holder's member is
 * anchored at the declaration's NAME with the whole-object elaboration where tsc's
 * `elaborateObjectLiteral` anchors at the property with the member's own form — the
 * parameter-mismatch twin of that shape does exactly the same on the parent binary.
 */
class SignatureThisRelationTest {

    private val prelude = """
        interface ZzzA { a: number }
        interface ZzzB { b: string }
        interface ZzzC { c: boolean }
    """.trimIndent()

    private val thisLine = "  The 'this' types of each signature are incompatible."

    // ------------------------------------------------------ function types, contravariant

    @Test
    fun `unrelated this types on two function types are TS2322 with the this chain at a declaration`() {
        val d = diagnose(prelude + "\ndeclare const zzzSrc: (this: ZzzA, x: number) => void;\nconst zzzDst: (this: ZzzB, x: number) => void = zzzSrc;\nexport {};")
        d should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '(this: ZzzA, x: number) => void' is not assignable to type '(this: ZzzB, x: number) => void'." &&
                    it.messageChain == listOf(
                        thisLine,
                        "    Property 'a' is missing in type 'ZzzB' but required in type 'ZzzA'.",
                    )
            })
        }
        val row = d.first { it.code == 2322 }
        assert(row.line == 5)
        assert(row.character == 7)
        assert(row.length == "zzzDst".length)
        assert(d.size == 1)
    }

    @Test
    fun `negative control - a source this the target this extends is accepted contravariantly`() {
        val d = diagnose(prelude + "\ndeclare const zzzSrc: (this: ZzzA, x: number) => void;\nconst zzzDst: (this: ZzzA & ZzzC, x: number) => void = zzzSrc;\nexport {};")
        assert(d.isEmpty())
    }

    @Test
    fun `a source this narrower than the target this is TS2322 on function types with the intersection chain`() {
        val d = diagnose(prelude + "\ndeclare const zzzSrc: (this: ZzzA & ZzzC, x: number) => void;\nconst zzzDst: (this: ZzzA, x: number) => void = zzzSrc;\nexport {};")
        d should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '(this: ZzzA & ZzzC, x: number) => void' is not assignable to type '(this: ZzzA, x: number) => void'." &&
                    it.messageChain == listOf(
                        thisLine,
                        "    Type 'ZzzA' is not assignable to type 'ZzzA & ZzzC'.",
                        "      Property 'c' is missing in type 'ZzzA' but required in type 'ZzzC'.",
                    )
            })
        }
        assert(d.size == 1)
    }

    @Test
    fun `a source this narrower than the target this is TS2322 at an assignment too`() {
        val d = diagnose(prelude + "\ndeclare const zzzSrc: (this: ZzzA, x: number) => void;\nlet zzzDst: (this: ZzzB, x: number) => void;\nzzzDst = zzzSrc;\nexport {};")
        d should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '(this: ZzzA, x: number) => void' is not assignable to type '(this: ZzzB, x: number) => void'." &&
                    it.messageChain == listOf(
                        thisLine,
                        "    Property 'a' is missing in type 'ZzzB' but required in type 'ZzzA'.",
                    )
            })
        }
        val row = d.first { it.code == 2322 }
        assert(row.line == 6)
        assert(row.character == 1)
        assert(d.size == 1)
    }

    @Test
    fun `a this mismatch at a return statement is TS2322 with the this chain`() {
        val d = diagnose(prelude + "\ndeclare const zzzSrc: (this: ZzzA, x: number) => void;\nfunction zzzRet(): (this: ZzzB, x: number) => void { return zzzSrc; }\nexport {};")
        d should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '(this: ZzzA, x: number) => void' is not assignable to type '(this: ZzzB, x: number) => void'." &&
                    it.messageChain == listOf(
                        thisLine,
                        "    Property 'a' is missing in type 'ZzzB' but required in type 'ZzzA'.",
                    )
            })
        }
        val row = d.first { it.code == 2322 }
        assert(row.line == 5)
        assert(row.character == 54)
        assert(d.size == 1)
    }

    @Test
    fun `a this mismatch in argument position is TS2345 with the this chain`() {
        val d = diagnose(prelude + "\ndeclare function zzzTake(cb: (this: ZzzB, x: number) => void): void;\ndeclare const zzzSrc: (this: ZzzA, x: number) => void;\nzzzTake(zzzSrc);\nexport {};")
        d should {
            have(any {
                it.code == 2345 &&
                    it.message == "Argument of type '(this: ZzzA, x: number) => void' is not assignable to parameter of type '(this: ZzzB, x: number) => void'." &&
                    it.messageChain == listOf(
                        thisLine,
                        "    Property 'a' is missing in type 'ZzzB' but required in type 'ZzzA'.",
                    )
            })
        }
        val row = d.first { it.code == 2345 }
        assert(row.line == 6)
        assert(row.character == 9)
        assert(d.size == 1)
    }

    @Test
    fun `a this mismatch against an overloaded target names the target type and elaborates the first overload`() {
        val d = diagnose(prelude + "\ninterface ZzzOv { (this: ZzzB, x: number): void; (this: ZzzB, x: string): void }\ndeclare const zzzSrc: (this: ZzzA, x: number | string) => void;\nconst zzzDst: ZzzOv = zzzSrc;\ndeclare const zzzSrc2: (this: ZzzB, x: number | string) => void;\nconst zzzDst2: ZzzOv = zzzSrc2;\nexport {};")
        d should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '(this: ZzzA, x: string | number) => void' is not assignable to type 'ZzzOv'." &&
                    it.messageChain == listOf(
                        thisLine,
                        "    Property 'a' is missing in type 'ZzzB' but required in type 'ZzzA'.",
                    )
            })
        }
        assert(d.size == 1)
        assert(d.single().line == 6)
    }

    // ------------------------------------------------------------- the two exemptions

    @Test
    fun `negative control - a source this of void is never checked`() {
        val d = diagnose(prelude + "\ndeclare const zzzSrc: (this: void, x: number) => void;\nconst zzzDst: (this: ZzzB, x: number) => void = zzzSrc;\nexport {};")
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - a source without a this is never checked against a target with one`() {
        val d = diagnose(prelude + "\ndeclare const zzzSrc: (x: number) => void;\nconst zzzDst: (this: ZzzB, x: number) => void = zzzSrc;\nexport {};")
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - a target without a this is never checked against a source with one`() {
        val d = diagnose(prelude + "\ndeclare const zzzSrc: (this: ZzzA, x: number) => void;\nconst zzzDst: (x: number) => void = zzzSrc;\nexport {};")
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - identical this types relate at a declaration and through implements`() {
        val d = diagnose(prelude + "\ninterface ZzzI { m(this: ZzzA, x: number): void }\nclass ZzzK implements ZzzI { m(this: ZzzA, x: number): void {} }\ndeclare const zzzSrc: (this: ZzzA, x: number) => void;\nconst zzzDst: (this: ZzzA, x: number) => void = zzzSrc;\nexport {};")
        assert(d.isEmpty())
    }

    // --------------------------------------------------- method targets are bivariant

    @Test
    fun `negative control - a method signature target compares this bivariantly`() {
        val d = diagnose(prelude + "\ninterface ZzzSrcI { m(this: ZzzA & ZzzC, x: number): void }\ninterface ZzzDstI { m(this: ZzzA, x: number): void }\ndeclare const zzzSrc: ZzzSrcI;\nconst zzzDst: ZzzDstI = zzzSrc;\nexport {};")
        assert(d.isEmpty())
    }

    @Test
    fun `unrelated this types on two method signatures fail in both directions through the property chain`() {
        val d = diagnose(prelude + "\ninterface ZzzSrcI { m(this: ZzzA, x: number): void }\ninterface ZzzDstI { m(this: ZzzB, x: number): void }\ndeclare const zzzSrc: ZzzSrcI;\nconst zzzDst: ZzzDstI = zzzSrc;\nexport {};")
        d should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'ZzzSrcI' is not assignable to type 'ZzzDstI'." &&
                    it.messageChain == listOf(
                        "  Types of property 'm' are incompatible.",
                        "    Type '(this: ZzzA, x: number) => void' is not assignable to type '(this: ZzzB, x: number) => void'.",
                        "      The 'this' types of each signature are incompatible.",
                        "        Property 'a' is missing in type 'ZzzB' but required in type 'ZzzA'.",
                    )
            })
        }
        assert(d.size == 1)
    }

    @Test
    fun `negative control - a function type into a method signature member compares this bivariantly`() {
        val d = diagnose(prelude + "\ninterface ZzzMS { m(this: ZzzA, x: number): void }\ndeclare const zzzFn: (this: ZzzA & ZzzC, x: number) => void;\nconst zzzDst: ZzzMS = { m: zzzFn };\nexport {};")
        assert(d.isEmpty())
    }

    @Test
    fun `a class method source into a function type target is strict in both this directions`() {
        val d = diagnose(prelude + "\nclass ZzzK { m(this: ZzzA & ZzzC, x: number): void {} }\ndeclare const zzzK: ZzzK;\nconst zzzDst: (this: ZzzA, x: number) => void = zzzK.m;\nconst zzzDst2: (this: ZzzB, x: number) => void = zzzK.m;\nexport {};")
        d should {
            have(any {
                it.code == 2322 && it.line == 6 &&
                    it.message == "Type '(this: ZzzA & ZzzC, x: number) => void' is not assignable to type '(this: ZzzA, x: number) => void'." &&
                    it.messageChain == listOf(
                        thisLine,
                        "    Type 'ZzzA' is not assignable to type 'ZzzA & ZzzC'.",
                        "      Property 'c' is missing in type 'ZzzA' but required in type 'ZzzC'.",
                    )
            })
            have(any {
                it.code == 2322 && it.line == 7 &&
                    it.message == "Type '(this: ZzzA & ZzzC, x: number) => void' is not assignable to type '(this: ZzzB, x: number) => void'." &&
                    it.messageChain == listOf(
                        thisLine,
                        "    Type 'ZzzB' is not assignable to type 'ZzzA & ZzzC'.",
                        "      Property 'a' is missing in type 'ZzzB' but required in type 'ZzzA'.",
                    )
            })
        }
        assert(d.size == 2)
    }

    // ----------------------------------------------------------------- implements

    @Test
    fun `an implementing method with an unrelated this is TS2416 with the signature and this chain`() {
        val d = diagnose(prelude + "\ninterface ZzzI { m(this: ZzzA, x: number): void }\nclass ZzzK implements ZzzI { m(this: ZzzB, x: number): void {} }\nexport {};")
        d should {
            have(any {
                it.code == 2416 &&
                    it.message == "Property 'm' in type 'ZzzK' is not assignable to the same property in base type 'ZzzI'." &&
                    it.messageChain == listOf(
                        "  Type '(this: ZzzB, x: number) => void' is not assignable to type '(this: ZzzA, x: number) => void'.",
                        "    The 'this' types of each signature are incompatible.",
                        "      Property 'b' is missing in type 'ZzzA' but required in type 'ZzzB'.",
                    )
            })
        }
        val row = d.first { it.code == 2416 }
        assert(row.line == 5)
        assert(row.character == 30)
        assert(d.size == 1)
    }

    @Test
    fun `negative control - an implementing method this related in either direction is accepted`() {
        val d = diagnose(prelude + "\ninterface ZzzI { m(this: ZzzA & ZzzC, x: number): void }\nclass ZzzK implements ZzzI { m(this: ZzzA, x: number): void {} }\nclass ZzzK2 implements ZzzI { m(this: ZzzA & ZzzC & ZzzB, x: number): void {} }\nexport {};")
        assert(d.isEmpty())
    }

    // ---------------------------------------------------------- object-literal methods

    @Test
    fun `negative control - an object literal method into a method signature member compares this bivariantly`() {
        val d = diagnose(prelude + "\ninterface ZzzHolder2 { f(this: ZzzB, x: number): void }\nconst zzzH3: ZzzHolder2 = { f(this: ZzzB & ZzzC, x: number) {} };\nexport {};")
        assert(d.isEmpty())
    }

    @Test
    fun `residue - an object literal method into a function typed member reports the whole object at its name`() {
        // Both references anchor at the PROPERTY (column 27) and print the member's own
        // `Type '(this: ZzzA, x: number) => void' is not assignable to type '(this: ZzzB, x:
        // number) => void'.` — tsc's `elaborateObjectLiteral`; this compiler reports the
        // whole-object form at the declaration's name for the parameter-mismatch twin of
        // this shape too (pre-existing, measured on the parent binary). The `this` leg and
        // its chain are what this pin gates.
        val d = diagnose(prelude + "\ninterface ZzzHolder { f: (this: ZzzB, x: number) => void }\nconst zzzH: ZzzHolder = { f(this: ZzzA, x: number) {} };\nconst zzzH4: ZzzHolder = { f(this: ZzzB & ZzzC, x: number) {} };\nexport {};")
        d should {
            have(any {
                it.code == 2322 && it.line == 5 &&
                    it.messageChain == listOf(
                        "  Types of property 'f' are incompatible.",
                        "    Type '(this: ZzzA, x: number) => void' is not assignable to type '(this: ZzzB, x: number) => void'.",
                        "      The 'this' types of each signature are incompatible.",
                        "        Property 'a' is missing in type 'ZzzB' but required in type 'ZzzA'.",
                    )
            })
            have(any {
                it.code == 2322 && it.line == 6 &&
                    it.messageChain == listOf(
                        "  Types of property 'f' are incompatible.",
                        "    Type '(this: ZzzB & ZzzC, x: number) => void' is not assignable to type '(this: ZzzB, x: number) => void'.",
                        "      The 'this' types of each signature are incompatible.",
                        "        Type 'ZzzB' is not assignable to type 'ZzzB & ZzzC'.",
                        "          Property 'c' is missing in type 'ZzzB' but required in type 'ZzzC'.",
                    )
            })
        }
        assert(d.size == 2)
    }

    // ------------------------------------------------------------ callback parameters

    @Test
    fun `negative control - a callback parameter compares this bivariantly in both directions`() {
        val d = diagnose(prelude + "\ndeclare const zzzSrc: (cb: (this: ZzzA, x: number) => void) => void;\nconst zzzDst: (cb: (this: ZzzA & ZzzC, x: number) => void) => void = zzzSrc;\ndeclare const zzzSrc2: (cb: (this: ZzzA & ZzzC, x: number) => void) => void;\nconst zzzDst2: (cb: (this: ZzzA, x: number) => void) => void = zzzSrc2;\nexport {};")
        assert(d.isEmpty())
    }

    @Test
    fun `an unrelated callback this is TS2322 nested under the parameters line`() {
        val d = diagnose(prelude + "\ndeclare const zzzSrc3: (cb: (this: ZzzA, x: number) => void) => void;\nconst zzzDst3: (cb: (this: ZzzB, x: number) => void) => void = zzzSrc3;\nexport {};")
        d should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '(cb: (this: ZzzA, x: number) => void) => void' is not assignable to type '(cb: (this: ZzzB, x: number) => void) => void'." &&
                    it.messageChain == listOf(
                        "  Types of parameters 'cb' and 'cb' are incompatible.",
                        "    The 'this' types of each signature are incompatible.",
                        "      Property 'b' is missing in type 'ZzzA' but required in type 'ZzzB'.",
                    )
            })
        }
        assert(d.size == 1)
    }

    // ------------------------------------------------------------------ generic this

    @Test
    fun `a source generic this is instantiated from the parameter pins before it is compared`() {
        val d = diagnose(prelude + "\ninterface ZzzBox<T> { v: T }\ndeclare const zzzSrc: <T>(this: ZzzBox<T>, x: T) => void;\nconst zzzDst: (this: ZzzBox<number>, x: number) => void = zzzSrc;\nconst zzzDst2: (this: ZzzB, x: number) => void = zzzSrc;\nexport {};")
        d should {
            have(any {
                it.code == 2322 && it.line == 7 &&
                    it.message == "Type '<T>(this: ZzzBox<T>, x: T) => void' is not assignable to type '(this: ZzzB, x: number) => void'." &&
                    it.messageChain == listOf(
                        thisLine,
                        "    Property 'v' is missing in type 'ZzzB' but required in type 'ZzzBox<number>'.",
                    )
            })
        }
        assert(d.size == 1)
    }

    // ----------------------------------------------------------------- leaf shapes

    @Test
    fun `primitive this types print the bare not-assignable leaf`() {
        val d = diagnose("declare const zzzSrc: (this: string, x: number) => void;\nconst zzzDst: (this: number, x: number) => void = zzzSrc;\nexport {};")
        d should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '(this: string, x: number) => void' is not assignable to type '(this: number, x: number) => void'." &&
                    it.messageChain == listOf(thisLine, "    Type 'number' is not assignable to type 'string'.")
            })
        }
        assert(d.size == 1)
    }

    @Test
    fun `anonymous object this types print the missing-property leaf`() {
        val d = diagnose("declare const zzzSrc2: (this: { a: number }, x: number) => void;\nconst zzzDst2: (this: { b: string }, x: number) => void = zzzSrc2;\nexport {};")
        d should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '(this: { a: number; }, x: number) => void' is not assignable to type '(this: { b: string; }, x: number) => void'." &&
                    it.messageChain == listOf(thisLine, "    Property 'a' is missing in type '{ b: string; }' but required in type '{ a: number; }'.")
            })
        }
        assert(d.size == 1)
    }

    @Test
    fun `a nullable target this elaborates its failing constituent`() {
        val d = diagnose(prelude + "\ndeclare const zzzSrc3: (this: ZzzA, x: number) => void;\nconst zzzDst3: (this: ZzzA | undefined, x: number) => void = zzzSrc3;\nexport {};")
        d should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '(this: ZzzA, x: number) => void' is not assignable to type '(this: ZzzA | undefined, x: number) => void'." &&
                    it.messageChain == listOf(
                        thisLine,
                        "    Type 'ZzzA | undefined' is not assignable to type 'ZzzA'.",
                        "      Type 'undefined' is not assignable to type 'ZzzA'.",
                    )
            })
        }
        assert(d.size == 1)
    }

    @Test
    fun `when both this and a parameter fail the this line is reported first`() {
        val d = diagnose(prelude + "\ndeclare const zzzSrc4: (this: ZzzA, x: ZzzA) => void;\nconst zzzDst4: (this: { b: string }, x: { b: string }) => void = zzzSrc4;\nexport {};")
        d should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '(this: ZzzA, x: ZzzA) => void' is not assignable to type '(this: { b: string; }, x: { b: string; }) => void'." &&
                    it.messageChain == listOf(thisLine, "    Property 'a' is missing in type '{ b: string; }' but required in type 'ZzzA'.")
            })
        }
        assert(d.size == 1)
    }

    // ---------------------------------------------------------------------- residues

    @Test
    fun `residue - a union of function types as the source is not related at all`() {
        // Both references report TS2322 at line 5 (`Type '((this: ZzzA, x: number) => void) |
        // ((this: ZzzC, x: number) => void)' is not assignable to type '(this: ZzzB, x:
        // number) => void'.` with the first member's this chain); `canUseTypeEngine` refuses
        // an object-carrying union source against an object target ((PARITY.1)(c)), so the
        // leg is never asked. Pre-existing, not this item.
        val d = diagnose(prelude + "\ndeclare const zzzU: ((this: ZzzA, x: number) => void) | ((this: ZzzC, x: number) => void);\nconst zzzDst: (this: ZzzB, x: number) => void = zzzU;\nconst zzzDst2: (this: ZzzA & ZzzC, x: number) => void = zzzU;\nexport {};")
        assert(d.isEmpty())
    }

    @Test
    fun `residue - strictFunctionTypes false is not modelled so the narrower source this still reports`() {
        // Under `strictFunctionTypes: false` both references accept `(this: ZzzA & ZzzC) =>
        // void` against `(this: ZzzA) => void` (bivariant). `CompilerOptions` carries no
        // `strictFunctionTypes` field and the PARAMETER leg reports `(x: ZzzA & ZzzC) => void`
        // against `(x: ZzzA) => void` under that flag too (measured on the parent binary), so
        // the `this` leg inherits the family rather than adding a second one.
        val d = diagnose(
            prelude + "\ndeclare const zzzSrc: (this: ZzzA & ZzzC, x: number) => void;\nconst zzzDst: (this: ZzzA, x: number) => void = zzzSrc;\nexport {};",
            directives = "// @strict: true\n// @strictFunctionTypes: false",
        )
        assert(d.any { it.code == 2322 })
    }
}
