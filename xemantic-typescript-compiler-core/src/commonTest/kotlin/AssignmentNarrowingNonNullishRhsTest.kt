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
 * (CHK.173) G2 — an assignment whose right-hand side the flow reader could not prove
 * non-nullish kept the declared `| undefined`, so every later read of the reference was a
 * false TS18048 (the element-access arm reports today; the identifier-receiver property arm,
 * Round A, is gated on this). Four independent mechanisms, one per section, each graded by
 * the live element-access observable `s["flags"]` AND a deliberate mis-assignment
 * (`const p: boolean = s`) that PRINTS the flow type. Every expectation is tsgo 7.0.2's row
 * list for the same file (`build/bench/p18204-agent/pins/`, one line lower there: the
 * harness does not count the directive line).
 *
 *  - G2a [Checker.calleeBodyReturnsNonNullishForFlow] did not consult the callee's own body
 *    locals, so a returned `symbol` resolved through the program-wide name map to some OTHER
 *    function's same-named local (tsc checker.ts `lateSymbol` x7 / `indexSymbol` x3).
 *  - G2b [Checker.resolveFlowCalleeDecl] answered null for a nested callee name nested in
 *    two functions (tsc: `createSymbol` in `createTypeChecker` and `createBinder`); it now
 *    resolves through the lexical scope at the call.
 *  - G2c `r = rs[0]` had no resolving arm in [Checker.narrowByAssignmentRhs], and a
 *    `this`-rooted right-hand side typed `any` under the property-access frame, which types
 *    no `this` (tsc fourslashImpl.ts `expectedRange` x4). `this` is installed for that one
 *    typing only. RESIDUE, deliberately not pinned: `const rs = this.getRanges(); if (!r)
 *    r = rs[0]` still reports under the element-access arm (tsgo silent) — the body local
 *    `rs` is typed only when that frame types `this`, and typing `this` there wakes two
 *    unrelated corpus emitters (see [Checker.typeOfExpressionWithFlowThis]).
 *  - G2d a const arrow / function-expression callee was never classified: since (CHK.31)
 *    the resolved callee is the function itself, which `callRhsHasNonNullishReturnAnnotation`
 *    had no arm for.
 *
 * The controls pin the reporting direction of each: a nullish callee body local, a nullish
 * lexical callee, a nullish or `any` element, and a nullish or generic arrow still report.
 */
class AssignmentNarrowingNonNullishRhsTest {

    @Test
    fun `G2a - a nested callee returning its own body local is proven non-nullish although another function declares a same-named local`() {
        val rows = diagnose(
            """
                interface Sym { flags: number }
                declare const Sym: new (f: number) => Sym;
                declare const m: { get(k: string): Sym | undefined; set(k: string, v: Sym): void };
                export function outer() {
                  function createSymbol(flags: number) {
                    const symbol = new Sym(flags);
                    return symbol;
                  }
                  function other(o: { p?: Sym }) { const symbol = o.p; return symbol; }
                  function viaSet(k: string) { let s = m.get(k); if (!s) m.set(k, s = createSymbol(1)); return s["flags"]; }
                  function viaIf(k: string) { let s = m.get(k); if (!s) s = createSymbol(1); return s["flags"]; }
                  function probe(k: string) { let s = m.get(k); if (!s) s = createSymbol(1); const p: boolean = s; }
                  return { viaSet, viaIf, probe, other };
                }
            """,
        ).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(rows == listOf("12:84 TS2322 Type 'Sym' is not assignable to type 'boolean'."))
    }

    @Test
    fun `control G2a - a callee body local that is nullish or shadowed by a nullish block local still reports`() {
        val rows = diagnose(
            """
                interface Sym { flags: number }
                declare const Sym: new (f: number) => Sym;
                declare function maybe(): Sym | undefined;
                declare const m: { get(k: string): Sym | undefined; set(k: string, v: Sym): void };
                export function outer() {
                  function mk1() { const symbol = maybe(); return symbol; }
                  function mk2() { let symbol: Sym | undefined = new Sym(1); if (m) symbol = maybe(); return symbol; }
                  function mk3() { const symbol = new Sym(1); if (m) { const symbol = maybe(); return symbol; } return symbol; }
                  function mk4() { let symbol = new Sym(1); return symbol; }
                  const symbol = 1;
                  function a(k: string) { let s = m.get(k); if (!s) s = mk1(); return s["flags"]; }
                  function b(k: string) { let s = m.get(k); if (!s) s = mk2(); return s["flags"]; }
                  function c(k: string) { let s = m.get(k); if (!s) s = mk3(); return s["flags"]; }
                  function d(k: string) { let s = m.get(k); if (!s) s = mk4(); return s["flags"]; }
                  return { a, b, c, d, symbol };
                }
            """,
        ).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(rows == listOf("11:71 TS18048 's' is possibly 'undefined'.", "12:71 TS18048 's' is possibly 'undefined'.", "13:71 TS18048 's' is possibly 'undefined'."))
    }

    @Test
    fun `G2b - an ambiguous nested callee name resolves lexically to the function visible at the call`() {
        val rows = diagnose(
            """
                // @Filename: binder.ts
                export function createBinder() {
                  function createSymbol(flags: number, name: string): { flags: number; name: string } | undefined {
                    return flags ? { flags, name } : undefined;
                  }
                  return createSymbol;
                }

                // @Filename: main.ts
                interface Sym { flags: number }
                declare const Sym: new (f: number) => Sym;
                declare const m: { get(k: string): Sym | undefined; set(k: string, v: Sym): void };
                export function outer() {
                  function createSymbol(flags: number) {
                    const s = new Sym(flags);
                    return s;
                  }
                  function viaSet(k: string) { let s = m.get(k); if (!s) m.set(k, s = createSymbol(1)); return s["flags"]; }
                  function probe(k: string) { let s = m.get(k); if (!s) s = createSymbol(1); const p: boolean = s; }
                  return { viaSet, probe };
                }
            """,
        ).map { "${it.fileName}:${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(rows == listOf("main.ts:10:84 TS2322 Type 'Sym' is not assignable to type 'boolean'."))
    }

    @Test
    fun `control G2b - the lexical callee returning a nullish value still reports and an inner const arrow shadows it`() {
        val rows = diagnose(
            """
                // @Filename: binder.ts
                declare const Sym2: new (f: number) => { flags: number };
                export function createBinder() {
                  function createSymbol(flags: number) { const s = new Sym2(flags); return s; }
                  return createSymbol;
                }

                // @Filename: main.ts
                interface Sym { flags: number }
                declare function maybe(): Sym | undefined;
                declare const m: { get(k: string): Sym | undefined; set(k: string, v: Sym): void };
                export function outer() {
                  function createSymbol(flags: number) {
                    const s = maybe();
                    return s;
                  }
                  function viaIf(k: string) { let s = m.get(k); if (!s) s = createSymbol(1); return s["flags"]; }
                  function shadowed(k: string) {
                    const createSymbol = (f: number) => new Sym2(f);
                    let s = m.get(k); if (!s) s = createSymbol(1); return s["flags"];
                  }
                  return { viaIf, shadowed };
                }
                declare const Sym2: new (f: number) => Sym;
            """,
        ).map { "${it.fileName}:${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(rows == listOf("main.ts:9:85 TS18048 's' is possibly 'undefined'."))
    }

    @Test
    fun `G2c - an element-access right-hand side reduces the declared union to the element type`() {
        val rows = diagnose(
            """
                interface Range { pos: number; end: number }
                declare function ranges(): Range[];
                export function viaParam(r: Range | undefined, rs: Range[]) { if (!r) r = rs[0]; return r["pos"]; }
                export function viaCall(r: Range | undefined) { if (!r) r = ranges()[0]; return r["pos"]; }
                export function probe(r: Range | undefined, rs: Range[]) { if (!r) r = rs[0]; const p: boolean = r; }
            """,
        ).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(rows == listOf("5:85 TS2322 Type 'Range' is not assignable to type 'boolean'."))
    }

    @Test
    fun `control G2c - an element whose type is nullish or any keeps the declared union`() {
        val rows = diagnose(
            """
                interface Range { pos: number; end: number }
                declare function anys(): any[];
                declare const u: (Range | undefined)[];
                declare const rec: { [k: string]: Range | undefined };
                export function a(r: Range | undefined) { if (!r) r = u[0]; return r["pos"]; }
                export function b(r: Range | undefined) { if (!r) r = anys()[0]; return r["pos"]; }
                export function c(r: Range | undefined) { if (!r) r = rec["k"]; return r["pos"]; }
            """,
        ).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(rows == listOf("5:68 TS18048 'r' is possibly 'undefined'.", "6:73 TS18048 'r' is possibly 'undefined'.", "7:72 TS18048 'r' is possibly 'undefined'."))
    }

    @Test
    fun `G2c - a this-rooted element read is typed from the enclosing instance member class`() {
        val rows = diagnose(
            """
                interface Range { pos: number; end: number }
                export class State {
                  ranges: Range[] = [];
                  getRanges(): Range[] { return []; }
                  viaMethod(r: Range | undefined) { if (!r) r = this.getRanges()[0]; return r["pos"]; }
                  viaField(r: Range | undefined) { if (!r) r = this.ranges[0]; return r["pos"]; }
                  verify(expectedRange: Range | undefined) {
                    if (!expectedRange) {
                      expectedRange = this.getRanges()[0];
                    }
                    return expectedRange["pos"] + expectedRange["end"];
                  }
                  probe(r: Range | undefined) { if (!r) r = this.getRanges()[0]; const p: boolean = r; }
                }
            """,
        ).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(rows == listOf("13:72 TS2322 Type 'Range' is not assignable to type 'boolean'."))
    }

    @Test
    fun `G2d - a call through a const arrow or function expression is proven non-nullish`() {
        val rows = diagnose(
            """
                interface Sym { flags: number }
                declare const Sym: new (f: number) => Sym;
                declare const m: { get(k: string): Sym | undefined; set(k: string, v: Sym): void };
                const topAnn = (f: number): Sym => new Sym(f);
                const topBare = (f: number) => new Sym(f);
                const topFn = function (f: number) { const made = new Sym(f); return made; };
                export function a(k: string) { let s = m.get(k); if (!s) s = topAnn(1); return s["flags"]; }
                export function b(k: string) { let s = m.get(k); if (!s) s = topBare(1); return s["flags"]; }
                export function c(k: string) { let s = m.get(k); if (!s) s = topFn(1); return s["flags"]; }
                export function outer() {
                  const inBare = (f: number) => new Sym(f);
                  function d(k: string) { let s = m.get(k); if (!s) s = inBare(1); return s["flags"]; }
                  return { d };
                }
            """,
        ).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(rows.isEmpty())
    }

    @Test
    fun `control G2d - a const arrow returning a nullish or generic value still reports`() {
        val rows = diagnose(
            """
                interface Sym { flags: number }
                declare function maybe(): Sym | undefined;
                declare const m: { get(k: string): Sym | undefined; set(k: string, v: Sym): void };
                const annNullish = (f: number): Sym | undefined => maybe();
                const bareNullish = (f: number) => maybe();
                const blockNullish = (f: number) => { if (f) return maybe(); return maybe(); };
                const generic = <T>(v: T) => v;
                export function a(k: string) { let s = m.get(k); if (!s) s = annNullish(1); return s["flags"]; }
                export function b(k: string) { let s = m.get(k); if (!s) s = bareNullish(1); return s["flags"]; }
                export function c(k: string) { let s = m.get(k); if (!s) s = blockNullish(1); return s["flags"]; }
                export function d(k: string) { let s = m.get(k); if (!s) s = generic(m.get(k)); return s["flags"]; }
            """,
        ).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(rows == listOf("8:84 TS18048 's' is possibly 'undefined'.", "9:85 TS18048 's' is possibly 'undefined'.", "10:86 TS18048 's' is possibly 'undefined'.", "11:88 TS18048 's' is possibly 'undefined'."))
    }

    @Test
    fun `control G2b - a nested OVERLOAD SET is not resolved lexically so a nullable argument still reports`() {
        // Its first declaration answered alone let the overload proof pick the non-nullish
        // signature for a nullable argument. tsgo reports the read.
        val rows = diagnose(
            """
                interface Type { flags: number; restrictiveInstantiation?: Type; }
                interface TypeMapper { kind: number; }
                declare const restrictiveMapper: TypeMapper;
                function outer(maybe: Type | undefined) {
                    function getRestrictiveInstantiation(type: Type) {
                        if (type.restrictiveInstantiation) {
                            return type.restrictiveInstantiation;
                        }
                        type.restrictiveInstantiation = instantiateType(maybe, restrictiveMapper);
                        type.restrictiveInstantiation.restrictiveInstantiation = type.restrictiveInstantiation;
                        return type.restrictiveInstantiation;
                    }
                    function instantiateType(type: Type, mapper: TypeMapper | undefined): Type;
                    function instantiateType(type: Type | undefined, mapper: TypeMapper | undefined): Type | undefined;
                    function instantiateType(type: Type | undefined, mapper: TypeMapper | undefined): Type | undefined {
                        return type;
                    }
                    return getRestrictiveInstantiation;
                }
            """,
        ).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(rows == listOf("10:9 TS18048 'type.restrictiveInstantiation' is possibly 'undefined'."))
    }
}
