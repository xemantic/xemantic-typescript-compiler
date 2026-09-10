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

/**
 * (INV.0) step 9 — the INV.2(c) SCOPE-SPACE ascent, with one home.
 *
 * B83.5 is the invariant that a declaration the main binder never bound is
 * invisible to `globals` / `result.locals` / `perFileScope`. **It is wider than the
 * name suggests, and this KDoc is where that was finally written down**: `Binder`
 * recurses into statements from exactly two places — a `SourceFile`'s own list and a
 * `ModuleBlock`'s — so it is not "declarations nested in a `Block`" that go unbound
 * but *everything that is not a direct statement of one of those two*. A `class` at
 * the very top of a function body, with no block nesting at all, is equally unbound.
 *
 * INV.2(c) answers that population separately: `Binder.bindLexicalScopes` is a full
 * `forEachChild` walk that declares every one of those declarations into a
 * [LexicalScope], in a DISJOINT (negative) id space, and — the property everything
 * here rests on — `declareLexical` REFUSES any name the main binder already bound in
 * that container. So a hit in [LexicalScope.symbols] can only ever be a declaration
 * the conventional tables do not have, which is what makes consulting it unable to
 * change how any bound name resolves, and what gives the shadowing rule for free.
 *
 * ## Why this class exists
 *
 * The ascent had been hand-copied FIVE times, and the copies had drifted on four
 * axes: which table they read (the owning file's, or the walk-scoped ambient
 * `currentLexicalScopes`), whether they start at the node or at its parent, which
 * `SymbolFlags` they accept, and whether the walk is hop-capped. Those differences
 * are all deliberate — see each caller — so this class takes them as PARAMETERS
 * rather than unifying them. Nothing about any caller's behaviour changes; the
 * duplication does.
 *
 * **`LexicalScope.existing` is never read here and must not be**: it ALIASES the main
 * binder's table, so reading it would put every INV.3 name back in play and destroy
 * the soundness argument above (round 748; CLAUDE.md's "read `scope.symbols` ONLY").
 *
 * **And these rules are properties of THIS ascent, not of the tables** — round 918
 * measured that `lexLevelHasName`'s untrusted-level skip and its `symbols`-only rule
 * do not transplant onto `spineScopeLookup`'s chain. A new consumer gets a new
 * method here, with its axes stated, rather than a widened existing one.
 */
internal class LexicalScopeResolver(
    /**
     * `Checker.fileResults` — the per-file binder results, handed in as the OBJECT (a
     * `val` of `Checker` declared above the constructor boundary, so it is a legal
     * constructor input rather than an ambient read). This class reads nothing else:
     * the ascent is a function of the tables and the parent chain, which is why its
     * ambient row is NONE.
     */
    private val fileResults: Map<String, BinderResult>,
) {

    /**
     * The scope-space symbol named [name] visible at [node], innermost first, or null.
     *
     * @param scopes the table to walk. Callers pass either the OWNING file's
     *   ([scopesOfOwningFile]) or the walk-scoped ambient, and the two are not
     *   interchangeable: (CHK.85)(b) measured a function-body `const k3 = K.A` read as
     *   non-fresh through the ambient alone, because the object-literal typing walk
     *   does not install it.
     * @param startAtParent begin the ascent at `node.parent` rather than at [node].
     *   A caller holding the DECLARATION's own name node must not match a scope the
     *   node itself opens.
     * @param flags when non-null, only a symbol carrying one of these is accepted, and
     *   a non-matching hit does NOT stop the ascent.
     * @param stopFlags when non-null, a hit carrying one of these ENDS the ascent — it
     *   is answered if it also matches [flags] and refused otherwise. This is the axis a
     *   VALUE-space consult needs and a TYPE-space one does not: the two spaces are
     *   disjoint, so a wrong-KIND hit in type space is genuinely not a binding of the
     *   name, while in value space a `const` and a nested `function` compete for the
     *   same name and the INNER one wins whichever kind it is. Without it an ascent
     *   filtered to declarations would walk PAST an inner variable and answer an outer
     *   function — a wrong answer, not a miss.
     * @param hopCap a fail-safe on a malformed parent chain; 0 means uncapped, which is
     *   what the two callers that predate the cap pass.
     */
    fun symbolAt(
        node: Node,
        name: String,
        scopes: Map<Int, LexicalScope>,
        startAtParent: Boolean = false,
        flags: SymbolFlags? = null,
        stopFlags: SymbolFlags? = null,
        hopCap: Int = 4096,
    ): Symbol? {
        if (scopes.isEmpty()) return null
        var cur: Node? = if (startAtParent) (node as NodeBase).parent else node
        var hops = 0
        while (cur != null && (hopCap == 0 || hops++ < hopCap)) {
            val id = (cur as NodeBase).nodeId
            if (id >= 0) {
                val sym = scopes[id]?.symbols?.get(name)
                if (sym != null) {
                    if (flags == null || sym.flags.hasAny(flags)) return sym
                    if (stopFlags != null && sym.flags.hasAny(stopFlags)) return null
                }
            }
            if (cur is SourceFile) break
            cur = cur.parent
        }
        return null
    }

    /**
     * The INV.2(c) table of the file that OWNS [node], or null when it has none — an
     * unindexed hand-built tree, or a node whose owning file is not in the program.
     *
     * **Reading it BUILDS it** ((INC.16): `BinderResult.lexicalScopes` is `lazy`), so a
     * consult whose name gate is not near-empty must ask [resultOfOwningFile] first and
     * consult that file's own PROJECTION before touching this.
     */
    fun scopesOfOwningFile(node: Node): Map<Int, LexicalScope>? {
        val owner = owningSourceFile(node) ?: return null
        return fileResults[owner.fileName]?.lexicalScopes
    }

    /**
     * The `BinderResult` of the file that OWNS [node], or null when it is not in the
     * program. Unlike [scopesOfOwningFile] this touches NO lazy member, which is what
     * lets a caller test the file's own name projection before forcing its tables.
     */
    fun resultOfOwningFile(node: Node): BinderResult? {
        val owner = owningSourceFile(node) ?: return null
        return fileResults[owner.fileName]
    }
}
