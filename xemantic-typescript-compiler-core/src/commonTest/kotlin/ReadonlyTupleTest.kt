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
 * (CHK.93) STAGE 2 (g) — READONLY TUPLES and readonly arrays, in the DECLARED-TYPE
 * spelling: the `readonly [T, U]` type operator (a documented no-op before this round)
 * and `readonly T[]` / `ReadonlyArray<T>`. Every row was reproduced against pristine
 * `typescript@6.0.3` AND tsgo 7.0.2 before any code was written (`chk93s2/p5`); the two
 * agree on every row bar the ARGUMENT position, where tsgo prints a bare TS4104 and
 * pristine — the corpus's oracle, `readonlyTupleAndArrayElaboration` — a TS2345 carrying
 * the TS4104 line as its chain.
 *
 * The mechanism, shared with the const-context tuple of [ConstAssertionTest]:
 *  - `Type.Object.readonlyTuple`, set by `getTupleType(node, readonly = true)` under the
 *    operator and by the const-context array builder;
 *  - the relation refuses a READONLY array-like source against a MUTABLE array or tuple
 *    target outright (`readonlyToMutableArrayLike`, tsc's `structuredTypeRelatedToWorker`)
 *    and every assignability emitter prints TS4104 in place of a missing-members chain —
 *    the declaration, the assignment, the `return`, the class property, the argument;
 *  - a readonly tuple's members fall to `ReadonlyArray<T>` (`tupleInheritsArrayMember`),
 *    and an `Array<T>` mutator on a readonly array-like is TS2339 on the readonly display
 *    (`readonlyArrayLikeLacksArrayMember`), where the number-index bail used to swallow it;
 *  - a readonly tuple's numbered slots are read-only members (TS2540 `'0'`);
 *  - the display prefixes `readonly `.
 *
 * Two pre-existing false positives the round found in the same family and closed:
 * `const t: [1] = [1]` reported `Type 'number' is not assignable to type '1'` (B407's
 * per-element check typed the element by its base primitive; hidden on the corpus by the
 * pin walker wiping `readonlyTupleAndArrayElaboration`), and `delete ro.v` on a declared
 * `readonly` member reported TS2790 BESIDE TS2704 (tsc asks whether the operand is
 * optional only when it is not read-only).
 *
 * RECORDED residues (ours): `Readonly<{ a: number }>` displays its materialized body
 * `{ readonly a: number; }` where tsc keeps the alias name; `mt.push(3)` on a MUTABLE
 * tuple is silent (tuple method calls are not argument-checked, pre-existing); a
 * NON-IDENTIFIER receiver (`dn.x.push(3)` on `{ x: readonly [1, 2] }`) is silent — the
 * (CHK.46) receiver-shape gap, not this round's.
 */
class ReadonlyTupleTest {

    private val prelude = """
        declare const rt: readonly [1, 2]
        declare const ra: readonly number[]
        declare const mt: [1, 2]
        declare function probe(x: never): void
        export {}
    """.trimIndent() + "\n"

    /** 0-based line of the first line appended after [prelude] (line 0 is the directive). */
    private val rowLine = prelude.count { it == '\n' } + 1

    private fun messages(source: String): List<String> = diagnose(prelude + source).map { it.message }

    private fun col(source: String, needle: String): Int = source.indexOf(needle) + 1

    private fun colLast(source: String, needle: String): Int = source.lastIndexOf(needle) + 1

    private fun ts4104(source: String, target: String) =
        "The type '$source' is 'readonly' and cannot be assigned to the mutable type '$target'."

    private val pushMissingOnTuple = "Property 'push' does not exist on type 'readonly [1, 2]'."

    // ---------------------------------------------------------------------
    // members fall to ReadonlyArray
    // ---------------------------------------------------------------------

    @Test
    fun `push on a declared readonly tuple is TS2339 on the readonly display`() {
        val src = "rt.push(3)"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(pushMissingOnTuple))
        assert(d[0].code == 2339)
        assert(d[0].line == rowLine)
        assert(d[0].character == col(src, "push"))
        assert(d[0].length == 4)
    }

    @Test
    fun `push on a readonly array is TS2339 on the readonly display`() {
        val src = "ra.push(1)"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Property 'push' does not exist on type 'readonly number[]'."))
        assert(d[0].character == col(src, "push"))
    }

    @Test
    fun `a readonly tuple keeps every ReadonlyArray member`() {
        assert(messages("const zs = rt.slice(); const zl: number = rt.length; const zi = rt.indexOf(1); " +
            "const zm = rt.map(x => x); for (const q of rt) { const zq: number = q }").isEmpty())
    }

    @Test
    fun `a member on neither the tuple nor ReadonlyArray reports on the readonly display`() {
        assert(messages("rt.nope") == listOf("Property 'nope' does not exist on type 'readonly [1, 2]'."))
    }

    @Test
    fun `a member on neither a mutable tuple nor Array is TS2339 on the tuple`() {
        val src = "mt.nope"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Property 'nope' does not exist on type '[1, 2]'."))
        assert(d[0].character == col(src, "nope"))
    }

    @Test
    fun `negative control - a mutable tuple keeps push and a numeric member`() {
        // `mt.push(3)` is TS2345 in tsc (tuple method calls are not argument-checked
        // here, pre-existing) — the pin is that no MEMBER diagnostic appears.
        assert(diagnose(prelude + "mt.push(3); const ze: 1 = mt[0]; declare const ma: number[]; ma.push(1)").none { it.code == 2339 })
    }

    // ---------------------------------------------------------------------
    // TS4104 at every assignability position
    // ---------------------------------------------------------------------

    @Test
    fun `a readonly tuple against a mutable array is TS4104 at the declaration`() {
        val src = "const zm: number[] = rt"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(ts4104("readonly [1, 2]", "number[]")))
        assert(d[0].code == 4104)
        assert(d[0].line == rowLine)
        assert(d[0].character == col(src, "zm"))
        assert(d[0].length == 2)
        assert(d[0].messageChain.isEmpty())
    }

    @Test
    fun `a readonly tuple against a mutable tuple is TS4104`() {
        val src = "const zu: [1, 2] = rt"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(ts4104("readonly [1, 2]", "[1, 2]")))
        assert(d[0].character == col(src, "zu"))
    }

    @Test
    fun `a readonly array against a mutable array is TS4104 and no longer a missing-members chain`() {
        val src = "const zn: number[] = ra"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(ts4104("readonly number[]", "number[]")))
        assert(d[0].code == 4104)
        assert(d[0].character == col(src, "zn"))
    }

    @Test
    fun `a readonly any array against a mutable any array is TS4104`() {
        assert(messages("declare const zr: readonly any[]; const za: any[] = zr") ==
            listOf(ts4104("readonly any[]", "any[]")))
    }

    @Test
    fun `a readonly array against a mutable tuple is TS4104`() {
        assert(messages("const zt: [1] = ra") == listOf(ts4104("readonly number[]", "[1]")))
    }

    @Test
    fun `negative control - readonly targets accept readonly sources`() {
        assert(messages("const zr: readonly [1, 2] = rt; const zq: readonly number[] = rt; " +
            "const zc: ReadonlyArray<number> = ra; const zd: readonly number[] = mt; const ze: number[] = mt").isEmpty())
    }

    @Test
    fun `TS4104 at the return keyword`() {
        val src = "function zf(): number[] { return rt }"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(ts4104("readonly [1, 2]", "number[]")))
        assert(d[0].code == 4104)
        assert(d[0].character == col(src, "return"))
        assert(d[0].length == 6)
        assert(messages("function zg(): number[] { return ra }") == listOf(ts4104("readonly number[]", "number[]")))
    }

    @Test
    fun `TS4104 at the assignment target`() {
        val src = "let za: number[] = []; za = rt; za = ra"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(
            ts4104("readonly [1, 2]", "number[]"),
            ts4104("readonly number[]", "number[]"),
        ))
        assert(d.map { it.code } == listOf(4104, 4104))
        assert(d[0].character == col(src, "za = rt"))
        assert(d[1].character == col(src, "za = ra"))
        assert(d[0].length == 2)
    }

    @Test
    fun `TS4104 at the class property name`() {
        val src = "class ZC { p: number[] = rt; q: [1, 2] = rt; r: number[] = ra }"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(
            ts4104("readonly [1, 2]", "number[]"),
            ts4104("readonly [1, 2]", "[1, 2]"),
            ts4104("readonly number[]", "number[]"),
        ))
        assert(d[0].character == col(src, "p:"))
        assert(d[1].character == col(src, "q:"))
        assert(d[2].character == col(src, "r:"))
    }

    @Test
    fun `an argument carries the TS4104 line as the chain of TS2345`() {
        // pristine 6.0.3 (the corpus's oracle); tsgo 7.0.2 prints a bare TS4104 instead.
        val src = "declare function zg(x: number[]): void; zg(rt); zg(ra)"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(
            "Argument of type 'readonly [1, 2]' is not assignable to parameter of type 'number[]'.",
            "Argument of type 'readonly number[]' is not assignable to parameter of type 'number[]'.",
        ))
        assert(d.map { it.code } == listOf(2345, 2345))
        assert(d[0].messageChain == listOf("  " + ts4104("readonly [1, 2]", "number[]")))
        assert(d[1].messageChain == listOf("  " + ts4104("readonly number[]", "number[]")))
        assert(d[0].character == col(src, "rt)"))
        assert(d[0].length == 2)
        assert(d[1].character == col(src, "ra)"))
    }

    @Test
    fun `negative control - a readonly argument against a readonly or rest parameter is silent`() {
        assert(messages("declare function zg(x: readonly number[]): void; zg(rt); zg(ra); zg(mt); " +
            "declare function zh(...xs: number[]): void; zh(...ra); declare function zi(x: ReadonlyArray<number>): void; zi(rt)").isEmpty())
    }

    // ---------------------------------------------------------------------
    // read-only slots, display, delete
    // ---------------------------------------------------------------------

    @Test
    fun `a readonly tuple slot write is TS2540 on the slot`() {
        val src = "rt[0] = 1"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Cannot assign to '0' because it is a read-only property."))
        assert(d[0].code == 2540)
        assert(d[0].character == col(src, "0"))
        assert(d[0].length == 1)
        assert(messages("mt[0] = 1").isEmpty())
    }

    @Test
    fun `a readonly tuple displays with the readonly prefix`() {
        val src = "const zs: string = rt"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("Type 'readonly [1, 2]' is not assignable to type 'string'."))
        assert(messages("probe(rt); probe(mt)") == listOf(
            "Argument of type 'readonly [1, 2]' is not assignable to parameter of type 'never'.",
            "Argument of type '[1, 2]' is not assignable to parameter of type 'never'.",
        ))
    }

    @Test
    fun `a declared readonly member displays with the readonly prefix`() {
        assert(messages("declare const zd: { readonly a: string; b: number }; const zn: number = zd") ==
            listOf("Type '{ readonly a: string; b: number; }' is not assignable to type 'number'."))
    }

    @Test
    fun `residue - a Readonly utility displays its materialized body`() {
        // tsc keeps the alias: `Readonly<{ a: number; }>`. The materialized members now
        // carry the prefix they always had for TS2540; the alias name is the residue.
        assert(messages("declare const zd: Readonly<{ a: number }>; const zn: number = zd") ==
            listOf("Type '{ readonly a: number; }' is not assignable to type 'number'."))
    }

    @Test
    fun `delete of a declared readonly member is TS2704 alone`() {
        val src = "declare const zd: { readonly v: \"a\" }; delete zd.v"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf("The operand of a 'delete' operator cannot be a read-only property."))
        assert(d[0].code == 2704)
        assert(d[0].character == col(src, "zd.v"))
        assert(d[0].length == 4)
        assert(messages("class ZC { readonly a = 1 } const zc = new ZC(); delete zc.a").map { it } ==
            listOf("The operand of a 'delete' operator cannot be a read-only property."))
    }

    @Test
    fun `negative control - delete of a required writable member is still TS2790`() {
        assert(diagnose(prelude + "declare const zd: { v: \"a\" }; delete zd.v").map { it.code } == listOf(2790))
    }

    // ---------------------------------------------------------------------
    // the annotated array literal
    // ---------------------------------------------------------------------

    @Test
    fun `an array literal against a readonly tuple annotation keeps its literals`() {
        // Both were ours-only `Type 'number' is not assignable to type '1'` before this
        // round — the mutable one too (B407 typed the element by its base primitive).
        assert(messages("let zl: readonly [1, 2] = [1, 2]; const zc: readonly [1, 2] = [1, 2]; const zm: [1] = [1]; let zn: [1] = [1]").isEmpty())
    }

    @Test
    fun `an element that fails its slot reports the widened element`() {
        val src = "const zw: readonly [1, 2] = [1, \"a\"]; let zv: [1, 2] = [1, \"a\"]"
        val d = diagnose(prelude + src)
        assert(d.map { it.message } == listOf(
            "Type 'string' is not assignable to type '2'.",
            "Type 'string' is not assignable to type '2'.",
        ))
        assert(d[0].character == col(src, "\"a\""))
        assert(d[1].character == colLast(src, "\"a\""))
    }

    @Test
    fun `a let annotated readonly tuple is readonly`() {
        assert(messages("let zl: readonly [1, 2] = [1, 2]; zl.push(3)") == listOf(pushMissingOnTuple))
    }

    // ---------------------------------------------------------------------
    // shapes the grid exercises stay silent
    // ---------------------------------------------------------------------

    @Test
    fun `a user array guard keeps the declared mutable constituent at an argument`() {
        // tsc's own core.ts: `isArray(value): value is readonly unknown[]` on a
        // `T | T[]` subject keeps `T[]` (the declared constituent that relates), so a
        // mutating call inside the branch is legal. B378 installed the guard's own
        // `readonly unknown[]` — silent only while readonly related to mutable.
        assert(messages("declare function isArr(v: any): v is readonly unknown[]; declare function rem<T>(a: T[], i: number): void; " +
            "function z1(c: number | number[]) { if (isArr(c)) { rem(c, 0); const p1: string = c } } " +
            "function z2(m: Map<number, number | number[]>, h: number) { const c = m.get(h)!; if (isArr(c)) { rem(c, 0) } }") ==
            listOf("Type 'number[]' is not assignable to type 'string'."))
    }

    @Test
    fun `negative control - a bare object literal initializer is not const-asserted`() {
        // tsc's program.ts:1075: with a contextual type wrongly installed for every
        // object-literal initializer, a contextually-typed arrow member lost the
        // `| undefined` of its parameter and the whole literal reported TS2322.
        // tsc's program.ts:1071-1077, with its declarations reduced to what the shape needs.
        val src = """
            enum ModuleKind { None = 0, CommonJS = 1, ESNext = 99 }
            type ResolutionMode = ModuleKind.ESNext | ModuleKind.CommonJS | undefined
            interface FileReference { pos: number; fileName: string }
            interface SourceFile { fileName: string; impliedNodeFormat?: ResolutionMode; packageJsonScope?: object }
            interface CompilerOptions { module?: ModuleKind }
            declare function isString(text: unknown): text is string
            declare function getModeForFileReference(ref: FileReference | string, containingFileMode: ResolutionMode): ResolutionMode
            declare function getDefaultResolutionModeForFileWorker(sourceFile: Pick<SourceFile, "fileName" | "impliedNodeFormat" | "packageJsonScope">, options: CompilerOptions): ResolutionMode
            interface ResolutionNameAndModeGetter<Entry, SourceFile> {
                getName(entry: Entry): string
                getMode(entry: Entry, file: SourceFile, compilerOptions: CompilerOptions): ResolutionMode
            }
            function getTypeReferenceResolutionName<T extends FileReference | string>(entry: T) {
                return !isString(entry) ? entry.fileName : entry
            }
            const typeReferenceResolutionNameAndModeGetter: ResolutionNameAndModeGetter<FileReference | string, SourceFile | undefined> = {
                getName: getTypeReferenceResolutionName,
                getMode: (entry, file, compilerOptions) => getModeForFileReference(entry, file && getDefaultResolutionModeForFileWorker(file, compilerOptions)),
            }
        """.trimIndent()
        assert(messages(src).isEmpty())
    }

    @Test
    fun `negative control - array guards and readonly element pushes stay silent`() {
        assert(messages("declare function isArr(v: unknown): v is any[]; " +
            "function zg(v: readonly string[] | string): number { if (isArr(v)) { return v.length } return v.length } " +
            "const zarr: (readonly [string, number])[] = []; zarr.push([\"a\", 1] as const); " +
            "declare const zu: readonly string[] | undefined; const zl: number = zu ? zu.length : 0").isEmpty())
    }
}
