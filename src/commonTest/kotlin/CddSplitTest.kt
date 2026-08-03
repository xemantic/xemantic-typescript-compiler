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
 * (JIT.1)(d) round 812 — `checkDuplicateDeclarations` was 12,935 bytecodes, so
 * HotSpot never JIT-compiled it; it is now an entry at 2,801 plus five helpers,
 * each one contiguous run of the original body:
 *
 *  * [Checker.cddCheckImportBindings] — TS2300 for duplicate import bindings and
 *    `import =` declarations, plus 17.127's default-import TS2395;
 *  * [Checker.cddCheckMergedEnums] — TS2432 and cross-declaration member TS2300;
 *  * [Checker.cddCheckMergedTypeParameters] — TS2428;
 *  * [Checker.cddCheckExportUniformity] — TS2395 and TS2434;
 *  * [Checker.cddCheckValueRedeclarations] — TS2393, TS2813/TS2814, TS2323 and
 *    the TS2451/TS2300 block-scoped cluster.
 *
 * **What stays in the entry is what every input pays**: the collection loop over
 * the statement list (every statement list pays it), the `groupBy`, and the
 * `if (group.size < 2) continue` guard — every moved region is behind that
 * guard, i.e. behind a name declared at least TWICE in one scope.
 *
 * `HugeMethodLimitTest` reads the compiled `Code` attribute lengths and guards
 * the SIZE. This class pins what a size check cannot see: that each helper still
 * runs and still says its own distinctive thing, and that the entry honours the
 * two values that cross a boundary — `cddCheckExportUniformity`'s `emitted2395`
 * and `cddCheckValueRedeclarations`' "the caller must `continue`" signal. Both
 * seam pins assert a diagnostic that must NOT appear, because the failure mode
 * of a dropped signal is a SUPERSEDED check running anyway.
 */
class CddSplitTest {

    // ── cddCheckImportBindings ──────────────────────────────────────────────

    @Test
    fun `import arm - two named import bindings of one name are TS2300 on both`() {
        val d = diagnose(
            """
            import { a } from "./m";
            import { a } from "./n";
            """
        )
        assert(d.count { it.code == 2300 } == 2)
        assert(d.first { it.code == 2300 }.message == "Duplicate identifier 'a'.")
    }

    @Test
    fun `import arm - two import-equals declarations of one name are TS2300 on both`() {
        val d = diagnose(
            """
            import q = require("./m");
            import q = require("./n");
            """
        )
        assert(d.count { it.code == 2300 } == 2)
        assert(d.first { it.code == 2300 }.message == "Duplicate identifier 'q'.")
    }

    // ── cddCheckMergedEnums ─────────────────────────────────────────────────

    @Test
    fun `enum arm - two merged declarations omitting the first initializer are TS2432`() {
        val d = diagnose(
            """
            enum E { A }
            enum E { B }
            """
        )
        assert(d.count { it.code == 2432 } == 1)
        assert(d.first { it.code == 2432 }.message ==
            "In an enum with multiple declarations, only one declaration can omit " +
            "an initializer for its first enum element.")
    }

    @Test
    fun `enum arm - a member name declared in two merged bodies is TS2300 on both`() {
        val d = diagnose(
            """
            enum F { A = 1 }
            enum F { A = 2 }
            """
        )
        assert(d.count { it.code == 2300 } == 2)
        assert(d.count { it.code == 2432 } == 0)
    }

    // ── cddCheckMergedTypeParameters ────────────────────────────────────────

    @Test
    fun `generics arm - merged interfaces with differing type parameters are TS2428`() {
        val d = diagnose(
            """
            interface I<T> { x: T }
            interface I<U> { y: U }
            """
        )
        assert(d.count { it.code == 2428 } == 2)
        assert(d.first { it.code == 2428 }.message ==
            "All declarations of 'I' must have identical type parameters.")
    }

    @Test
    fun `generics arm - negative control - identical type parameters are silent`() {
        val d = diagnose(
            """
            interface K<T> { x: T }
            interface K<T> { y: T }
            """
        )
        assert(d.count { it.code == 2428 } == 0)
    }

    // ── cddCheckExportUniformity ────────────────────────────────────────────

    @Test
    fun `export-uniformity arm - a class merged with an exported class is TS2395 on both`() {
        val d = diagnose(
            """
            export class C { m(): void {} }
            class C { n(): void {} }
            """
        )
        assert(d.count { it.code == 2395 } == 2)
        assert(d.first { it.code == 2395 }.message ==
            "Individual declarations in merged declaration 'C' must be all exported or all local.")
    }

    // ── cddCheckValueRedeclarations ─────────────────────────────────────────

    @Test
    fun `value arm - two function implementations of one name are TS2393 on both`() {
        val d = diagnose(
            """
            function h(): void {}
            function h(): void {}
            """
        )
        assert(d.count { it.code == 2393 } == 2)
        assert(d.first { it.code == 2393 }.message == "Duplicate function implementation.")
    }

    @Test
    fun `value arm - two exported var declarations of one name are TS2323 on both`() {
        val d = diagnose(
            """
            export var v = 1;
            export var v = 2;
            """
        )
        assert(d.count { it.code == 2323 } == 2)
        assert(d.first { it.code == 2323 }.message ==
            "Cannot redeclare exported variable 'v'.")
    }

    @Test
    fun `value arm - let merged with const is TS2451 on both`() {
        val d = diagnose(
            """
            let z = 1;
            const z = 2;
            """
        )
        assert(d.count { it.code == 2451 } == 2)
        assert(d.first { it.code == 2451 }.message ==
            "Cannot redeclare block-scoped variable 'z'.")
    }

    @Test
    fun `value arm - a function body merged with two classes is TS2813 plus TS2814`() {
        val d = diagnose(
            """
            class D { m(): void {} }
            class D { n(): void {} }
            function D(): void {}
            """
        )
        assert(d.count { it.code == 2813 } == 2)
        assert(d.count { it.code == 2814 } == 1)
        assert(d.first { it.code == 2814 }.message ==
            "Function with bodies can only merge with classes that are ambient.")
    }

    // ── the two seams ───────────────────────────────────────────────────────

    /**
     * SEAM — `cddCheckExportUniformity` returns `emitted2395`, and the entry must
     * read it as `if (emitted2395) continue`: TS2395's "all exported or all local"
     * category SUPERSEDES the duplicate-identifier verdict. Two classes of one
     * name would otherwise reach the entry's `isDuplicate` tail
     * (`hasClass && classCount >= 2`) and add a TS2300 per declaration.
     */
    @Test
    fun `seam - TS2395 suppresses the duplicate-class TS2300 that follows it`() {
        val d = diagnose(
            """
            export class C { m(): void {} }
            class C { n(): void {} }
            """
        )
        assert(d.count { it.code == 2395 } == 2)
        assert(d.none { it.code == 2300 })
    }

    /**
     * SEAM — `cddCheckValueRedeclarations` returns "the caller must `continue`",
     * and the entry must replay it. Here the TS2813/TS2814 branch takes one of the
     * seven signals while `hasClass && classCount >= 2` holds, so an entry that
     * ignored the signal would fall into the `isDuplicate` tail and add TS2300 on
     * all three declarations.
     */
    @Test
    fun `seam - the value-redeclaration signal suppresses the duplicate-class TS2300`() {
        val d = diagnose(
            """
            class D { m(): void {} }
            class D { n(): void {} }
            function D(): void {}
            """
        )
        assert(d.count { it.code == 2813 } == 2)
        assert(d.none { it.code == 2300 })
    }

    /**
     * The block-scoped exit of `cddCheckValueRedeclarations` — `allBlockScoped &&
     * blockScopedDecls.size >= 2` — is a REDUNDANT guard on today's code, and this
     * pin is named as an arm pin for that reason: `allBlockScoped` requires
     * `!hasVar && !hasFunc && !hasClass && !hasEnum`, which is exactly what the
     * remaining blocks and the `isDuplicate` tail all need, so nothing below it
     * can fire whether or not the signal is returned. Round 812 ablated it and
     * measured 0 failing pins, as predicted from the guards.
     */
    @Test
    fun `value arm - a block-scoped redeclaration emits TS2451 and nothing else`() {
        val d = diagnose(
            """
            let z = 1;
            const z = 2;
            """
        )
        assert(d.count { it.code == 2451 } == 2)
        assert(d.none { it.code == 2300 })
        assert(d.none { it.code == 2323 })
    }

    // ── order ───────────────────────────────────────────────────────────────

    /**
     * The five call sites keep the monolith's ORDER, which a size check and a
     * per-arm count pin are both blind to: TS2428 (`cddCheckMergedTypeParameters`)
     * is emitted before TS2395 (`cddCheckExportUniformity`) for a merged generic
     * interface that is also unevenly exported.
     */
    @Test
    fun `order - the generics arm emits before the export-uniformity arm`() {
        val d = diagnose(
            """
            export interface J<T> { x: T }
            interface J<U> { y: U }
            """
        )
        val codes = d.map { it.code }
        assert(codes.count { it == 2428 } == 2)
        assert(codes.count { it == 2395 } == 2)
        assert(codes.indexOfFirst { it == 2428 } < codes.indexOfFirst { it == 2395 })
    }
}
