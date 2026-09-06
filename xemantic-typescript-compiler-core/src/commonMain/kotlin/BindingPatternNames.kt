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
 */


package com.xemantic.typescript.compiler

/**
 * (CHK.99) Every name a variable-declaration `name` node BINDS — the checker-side
 * mirror of [Binder.bindVariableDeclarationName] (Binder.kt:398-419).
 *
 * **Why it exists.** `export const { p } = obj` / `export const [t0] = tup` exports
 * `p` / `t0` exactly as `export const p = 1` exports `p` — tsc's binder walks the
 * pattern and gives every leaf the container's export flags (binder.ts:3648 routes a
 * `VariableDeclaration` through `bindBindingElementFlow`/`declareSymbolAndAddToSymbolTable`
 * per element, and :887-888 is where an exported declaration's leaves land in the
 * module's `exports`). Five AST-derived export sets in [Checker] instead wrote
 * `(decl.name as? Identifier)`, so a pattern's leaves were in NO export set: a named
 * import of one was TS2305 (directly and through an `export *` barrel), and a
 * namespace-scoped one was TS2339 on `typeof NS`. The TYPE always resolved, because
 * the binder DOES declare every leaf — only the ABSENCE checks were wrong, which is
 * why the class is a pure false-positive family.
 *
 * **What it returns.** An `Identifier` answers itself, so a call site is a drop-in for
 * the `as? Identifier` it replaces. A pattern answers its leaves in source order:
 *
 * - `{ p }` -> `p`; `{ q: renamed }` -> `renamed` (the LOCAL name, never `propertyName`);
 * - `{ q = "z" }` / `{ q: r = "z" }` -> the bound name, the default is not a name;
 * - `{ ...rest }` / `[...tail]` -> `rest` / `tail` (a rest element binds like any other);
 * - `{ n: { d } }` / `[[a]]` -> the leaves of the nested pattern, recursively;
 * - `[, second]` -> `second` only (a hole is an `OmittedExpression`, which binds nothing);
 * - `{ [k]: v }` -> `v` (a computed KEY is not a name; the bound local still is).
 *
 * Anything else — an assignment-target pattern's arbitrary expression element, a
 * `ComputedPropertyName` reached as a `name` — binds nothing and is skipped, exactly
 * as the binder's `else -> { }` skips it. Duplicates are not deduped here; every call
 * site collects into a `Set`.
 */
internal fun bindingPatternNames(name: Expression): List<String> {
    if (name is Identifier) return listOf(name.text)
    if (name !is ObjectBindingPattern && name !is ArrayBindingPattern) return emptyList()
    val out = ArrayList<String>()
    collectBindingPatternNames(name, out)
    return out
}

private fun collectBindingPatternNames(name: Expression, out: MutableList<String>) {
    when (name) {
        is Identifier -> out.add(name.text)
        is ObjectBindingPattern -> for (element in name.elements) collectBindingPatternNames(element.name, out)
        is ArrayBindingPattern -> for (element in name.elements) {
            if (element is BindingElement) collectBindingPatternNames(element.name, out)
        }
        else -> { /* computed property names, omitted array holes, … — bind nothing */ }
    }
}
