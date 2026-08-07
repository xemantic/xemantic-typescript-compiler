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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A deliberately kind-dense TypeScript source exercising as many AST node classes
 * as the parser can produce in one file. Shared with the jvmTest reflection oracle
 * (`ForEachChildOracleTest`), which diffs [forEachChild] against the data-class
 * properties on every node this parses into. Parse ERRORS in here are fine — error
 * recovery still builds nodes — but the bulk is valid so the tree stays deep.
 */
internal val INV2_RICH_FIXTURE: String = """
    /// <reference path="ref.d.ts" />
    import def, { a as b, type tOnly } from "./mod";
    import * as ns from "./ns";
    import eq = require("./req");
    export { b as c2 };
    export * as nsx from "./deep";
    export * from "./star";

    @dec1 @dec2(arg)
    export abstract class Cls<T extends object = {}> extends Base implements IFace<T> {
      static s: number = 1;
      #priv = 2;
      readonly [Symbol.iterator]: string;
      declare f?: string;
      constructor(public p: T, ...rest: number[]) { super(); }
      get acc(): number { return 1; }
      set acc(v: number) { }
      static { Cls.s = 3; }
      ;
      async *gen(a = 1, { d1, d2 = 5 }: { d1: number; d2?: number }, [e1, , ...e2]: number[]): AsyncGenerator<number> {
        yield a; yield* [1, 2]; await Promise.resolve(0);
      }
      [k: string]: unknown;
    }

    class Holder { m = class Inner extends Cls<{}> { }; }

    enum En { A = 1, B = "b", ["C"] = 2 }
    const enum CE { X }

    namespace NS.Sub {
      export interface IFace<T> extends Other.Base<T> {
        m?(x: T): void;
        new (x: number): IFace<T>;
        (call: string): number;
        readonly r: T;
      }
    }

    declare module "amb" { export const z: number; }
    declare global { interface G { } }

    type Alias<T, U = T> = T | U;
    type Cond<T> = T extends Array<infer E> ? E : never;
    type Mapped<T> = { -readonly [K in keyof T as K]?: T[K] };
    type Tup = [number, string?, ...boolean[]];
    type Fn = <V>(a: V, b?: string) => V;
    type Ctor = abstract new (x: number) => Cls<{}>;
    type Q = typeof ns.thing;
    type Imp = import("./mod").Thing<string>;
    type Tmpl = `a${'$'}{number}b${'$'}{string}`;
    type Idx = Alias<string>["length"];
    type Paren = (string | number) & { x: 1 };
    type Pred = (x: unknown) => x is string;
    type Assert = (x: unknown) => asserts x is string;
    type Lit = "s" | 42 | true | null | undefined;
    type KeyOf = keyof Cls<{}>;
    type WithThis = { m(): this };

    async function fn<T>(a: T, b = 2, ...rest: string[]): Promise<T | undefined> {
      label: for (let i = 0, j = 1; i < 10; i++) {
        for (const k in { a: 1 }) { continue label; }
        for await (const v of source()) { break label; }
        do { i++; } while (i < 2);
        while (false) { }
        switch (i) { case 1: break; default: return undefined; }
        try { throw new Error("x"); } catch (e) { } finally { }
        with (Math) { }
        if (i) { } else { }
        debugger;
      }
      const o = { p: 1, "q": 2, 3: 3, [key()]: 4, short, ...spread, m() { }, get g() { return 1; }, set g(v) { } };
      const arr = [1, , "two", ...rest];
      const t = `t${'$'}{a}u${'$'}{b}v`;
      const tagged = tag`x${'$'}{a}y`;
      const re = /ab+c/gi;
      const un = void 0, du = delete o.p, to = typeof o;
      const cmp = 1 < 2 ? b : 3;
      const neg = -1, pp = ++x2, mm = x2--, nn = !true;
      const as1 = o as { p: number }, sat = o satisfies object, nnl = o!, ta = <any>o;
      const inst = new Cls<{}>({}, 1);
      function inner() { return new.target; }
      const lam = (q: number): number => q * 2;
      const lam2 = async (q) => q;
      x2 ??= 5; x2 ||= 6; x2 &&= 7;
      o?.p; o?.["q"]; lam?.(1);
      return a;
    }
    let x2 = 1;
    var { da, db: { dc = 9 } = {}, ...drest } = obj;
    export const [ea, , eb = 1, ...erest] = arr2;
    export async function af(): Promise<void> { }
    function* g2() { yield 1; }
    export type { Alias };
    export import ei = NS.Sub;
    throw new Error("top");
""".trimIndent()

/** JSX sibling of [INV2_RICH_FIXTURE] — parsed as `t.tsx` to reach the Jsx* node classes. */
internal val INV2_JSX_FIXTURE: String = """
    const el = <div attr="s" num={1} {...spread} flag><span>text{expr}</span><Self.Closing a={2} /></div>;
    const frag = <>{x}<br /></>;
    const empty = <p>{}</p>;
""".trimIndent()

/**
 * INV.2(a) invariants for [indexSourceFile] (invoked at the end of [Parser.parse]):
 * dense PREORDER nodeIds with SourceFile = 0 and nodeCount = max id + 1, parent
 * chains terminating at the SourceFile, data-class `copy()` yielding an UNINDEXED
 * node while staying structurally equal, and — the sharp signal for the iterative
 * indexer — a binary chain far beyond corpus depth indexing on a PLAIN thread
 * (the INV.1 crawl parses on Dispatchers.Default, off the 256 MB deep-stack
 * thread, so a recursive indexer would overflow exactly there).
 */
class Inv2NodeIndexTest {

    /** Preorder traversal mirroring [indexSourceFile]'s (iterative, children reversed onto a stack). */
    private fun preorder(root: Node): List<Node> {
        val out = ArrayList<Node>()
        val stack = ArrayList<Node>()
        stack.add(root)
        val buf = ArrayList<Node>()
        val collect: (Node) -> Unit = { buf.add(it) }
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            out.add(node)
            buf.clear()
            forEachChild(node, collect)
            for (i in buf.indices.reversed()) stack.add(buf[i])
        }
        return out
    }

    private fun assertDensePreorderAndParents(sourceFile: SourceFile, label: String) {
        val nodes = preorder(sourceFile)
        assertEquals(0, sourceFile.nodeId, "$label: SourceFile must have nodeId 0")
        assertEquals(nodes.size, sourceFile.nodeCount, "$label: nodeCount must equal the preorder visit count")
        nodes.forEachIndexed { index, node ->
            assertEquals(
                index, (node as NodeBase).nodeId,
                "$label: ${node::class.simpleName} at pos ${node.pos} has nodeId ${node.nodeId}, expected preorder index $index"
            )
        }
        val total = nodes.size
        for (node in nodes) {
            if (node === sourceFile) continue
            var cursor: Node = node
            var steps = 0
            while (cursor !== sourceFile) {
                val parent = assertNotNull(
                    (cursor as NodeBase).parent,
                    "$label: ${cursor::class.simpleName} at pos ${cursor.pos} has a null parent before reaching the SourceFile"
                )
                cursor = parent
                steps++
                assert(steps <= total)
            }
        }
    }

    @Test
    fun `parse stamps dense preorder nodeIds parents and nodeCount on the rich fixture`() {
        val sourceFile = Parser(INV2_RICH_FIXTURE, "rich.ts").parse()
        // Locals only inside have() conditions: power-assert renders every subexpression's
        // toString on failure, and a SourceFile receiver would dump/overflow the whole tree.
        val nodeCount = sourceFile.nodeCount
        assert(nodeCount > 400)
        assertDensePreorderAndParents(sourceFile, "rich.ts")
    }

    @Test
    fun `jsx tree indexes with parents linking through jsx nodes`() {
        val sourceFile = Parser(INV2_JSX_FIXTURE, "t.tsx").parse()
        val nodes = preorder(sourceFile)
        val jsxKindsPresent = nodes.any { it is JsxElement } && nodes.any { it is JsxSelfClosingElement } &&
            nodes.any { it is JsxFragment } && nodes.any { it is JsxText } &&
            nodes.any { it is JsxAttribute } && nodes.any { it is JsxSpreadAttribute }
        assert(jsxKindsPresent)
        assertDensePreorderAndParents(sourceFile, "t.tsx")
    }

    @Test
    fun `data-class copy yields an unindexed node while staying structurally equal`() {
        val sourceFile = Parser("const answer = 42;", "c.ts").parse()
        val identifier = preorder(sourceFile).filterIsInstance<Identifier>().first()
        val parsedId = identifier.nodeId
        val parsedParent = identifier.parent
        assert(parsedId > 0)
        assert(parsedParent != null)
        val copy = identifier.copy()
        assertEquals(-1, copy.nodeId, "copy() must yield an UNINDEXED node")
        assertEquals(null, copy.parent, "copy() must yield a parent-less node")
        assertEquals(identifier, copy, "base-class vars must stay excluded from data-class equality")
        assertEquals(identifier.hashCode(), copy.hashCode(), "hashCode must ignore base-class vars")
    }

    @Test
    fun `a 30k-term binary chain indexes on a plain thread without overflow`() {
        // Left-associative `+` chain: the PARSER is iterative on it (precedence loop), so
        // this isolates the INDEXER — a recursive indexSourceFile would overflow at this
        // depth on a default-stack test thread (no runWithDeepStack wrapper here).
        val terms = 30_000
        val source = "var a = 1;\nvar r = " + "a + ".repeat(terms - 1) + "a;\n"
        val sourceFile = Parser(source, "deep.ts").parse()
        // The chain alone is (terms − 1) BinaryExpressions + terms Identifiers = 2·terms − 1.
        val nodeCount = sourceFile.nodeCount
        assert(nodeCount >= 2 * terms - 1)
        // Deepest leaf's parent chain walks the whole left spine — iteratively.
        val nodes = preorder(sourceFile)
        nodes.forEachIndexed { index, node ->
            assertEquals(index, (node as NodeBase).nodeId, "deep chain: preorder id mismatch at index $index")
        }
    }

    @Test
    fun `negative control - a synthesized node is unindexed until a parse indexes it`() {
        val synthesized = Identifier(text = "synthetic")
        assert(synthesized.nodeId == -1)
        assert(synthesized.parent == null)
    }
}
