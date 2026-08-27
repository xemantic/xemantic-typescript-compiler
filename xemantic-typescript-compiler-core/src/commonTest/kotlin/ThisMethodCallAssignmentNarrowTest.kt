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
 * (CHK.62b) AN ASSIGNMENT WHOSE RIGHT-HAND SIDE IS A `this`-METHOD CALL DID NOT NARROW THE
 * ASSIGNED REFERENCE.
 *
 * The flow walk classifies an assignment's post-state through
 * [Checker.rhsIsDefinitelyNonNullish], whose CALL arm reads the callee's return ANNOTATION
 * and therefore has to resolve the callee's declaration. That resolution goes through
 * [Checker.resolvePropertyMethodDecl], which TYPES THE RECEIVER — and
 * `getTypeOfExpression` answers `any` for `Identifier("this")` (the (CHK.61)(a) defect), so
 * the resolver bailed at its `recvType === anyType` guard and every `this.m()` right-hand
 * side was unclassifiable. The reference kept its declared `T | undefined` and the read
 * became a false TS2322; the identical assignment with a FREE-function right-hand side has
 * always narrowed, which is the control below and is what makes this a RECEIVER-shaped
 * defect rather than a narrowing-shaped one.
 *
 * The carrier is [Checker.currentClassForThis], the enclosing class the checking ambient
 * already threads. It is installed ONLY in the flow resolver: both of its callers are
 * narrowing resolvers, where a resolution can only ever SUPPRESS a diagnostic, so this is
 * separable from (CHK.61)(a)'s general `computeRawTypeOfPropertyAccess` change.
 *
 * tsc 7.0.2 is silent on every positive here, and reports the two controls.
 *
 * RESIDUE, deliberately not pinned (round 765): a PROPERTY-access right-hand side
 * (`p ??= this.zzzFld`) still does not narrow — but neither does `p ??= zzzObj.zzzFld`, so
 * that gap is not `this`-shaped and is priced separately. And the elaboration SHAPE of the
 * surviving rows differs from tsc's: tsc reports `Type 'ZzzProj | undefined' is not
 * assignable to type 'ZzzProj'` AT the member, where we report the whole object literal
 * against the whole target.
 */
class ThisMethodCallAssignmentNarrowTest {

    private val prelude = """
        interface ZzzProj { zzzId: number }
        interface ZzzRes { zzzProj: ZzzProj }
        interface ZzzWrong { zzzProj: string }
        declare function zzzFindFree(): ZzzProj | undefined;
        declare function zzzCreateFree(): ZzzProj;
    """.trimIndent() + "\n"

    private fun inClass(body: String) = prelude +
        "class ZzzSvc {\n" +
        "  zzzCreate(): ZzzProj { return { zzzId: 1 } }\n" +
        "  zzzMaybe(): ZzzProj | undefined { return undefined }\n" +
        body + "\n}"

    @Test
    fun `a nullish-coalescing assignment from a this-method call narrows the reference`() {
        diagnose(
            inClass(
                "  zzzC1(): ZzzRes | undefined {\n" +
                    "    let zzzProj = zzzFindFree();\n" +
                    "    zzzProj ??= this.zzzCreate();\n" +
                    "    return { zzzProj };\n" +
                    "  }",
            ),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a plain assignment from a this-method call inside a falsy guard narrows the reference`() {
        diagnose(
            inClass(
                "  zzzC2(): ZzzRes | undefined {\n" +
                    "    let zzzProj = zzzFindFree();\n" +
                    "    if (!zzzProj) { zzzProj = this.zzzCreate(); }\n" +
                    "    return { zzzProj };\n" +
                    "  }",
            ),
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `the narrowed member TYPE is the method's return type and not the declared union`() {
        // The VALUE pin: a deliberately wrong target makes the checker NAME the member
        // type it built. Before the fix this message read `{ zzzProj: ZzzProj | undefined; }`;
        // tsc 7.0.2 agrees the member is `ZzzProj` (it says
        // `Type 'ZzzProj' is not assignable to type 'string'`).
        val rows = diagnose(
            inClass(
                "  zzzC6(): ZzzWrong {\n" +
                    "    let zzzProj = zzzFindFree();\n" +
                    "    zzzProj ??= this.zzzCreate();\n" +
                    "    return { zzzProj };\n" +
                    "  }",
            ),
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type '{ zzzProj: ZzzProj; }' is not assignable to type 'ZzzWrong'.")
    }

    @Test
    fun `control - a this-method call whose return annotation ADMITS undefined must not narrow`() {
        // Discriminates an over-broad fix that would treat any resolved `this.m()` as
        // non-nullish. tsc 7.0.2 reports here too. No ablation arm reddens this - it is a
        // CONTROL, not coverage.
        val rows = diagnose(
            inClass(
                "  zzzC5(): ZzzRes | undefined {\n" +
                    "    let zzzProj = zzzFindFree();\n" +
                    "    zzzProj ??= this.zzzMaybe();\n" +
                    "    return { zzzProj };\n" +
                    "  }",
            ),
        ).filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].message == "Type '{ zzzProj: ZzzProj | undefined; }' is not assignable to type 'ZzzRes | undefined'.")
    }

    @Test
    fun `control - the FREE-function right-hand side already narrowed and is unmoved`() {
        // CONTROL, not coverage: silent before and after the fix.
        diagnose(
            inClass(
                "  zzzC4(): ZzzRes | undefined {\n" +
                    "    let zzzProj = zzzFindFree();\n" +
                    "    zzzProj ??= zzzCreateFree();\n" +
                    "    return { zzzProj };\n" +
                    "  }",
            ),
        ) should {
            have(none { it.code == 2322 })
        }
    }
}
