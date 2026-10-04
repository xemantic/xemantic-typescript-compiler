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
 * (P18.293) the argument list of a reference to a generic type alias, with every omitted
 * trailing argument filled from its parameter's DEFAULT — tsgo's `fillMissingTypeArguments`.
 *
 * Before this the alias-substitution arm of `getTypeFromTypeReference` required exactly one
 * argument per parameter, so `type A<T, Op = {}> = IsNever<T>` referenced as `A<[]>` fell to
 * the alias's DECLARED (parametric) type: its `T` stayed the unbound parameter and the body
 * answered for that instead of for `[]` — `IsNever<T>` read `true`, and type-fest's
 * `IsTuple<[]>` (whose `Options` parameter is defaulted) read `false` (32 rows). It looked
 * like an evaluation-ORDER defect only because the census compared an import path that
 * happened to reach the reference through a fully-applied alias.
 *
 * A default is resolved with the EARLIER parameters bound to their arguments (a default may
 * name them: `K extends keyof T = keyof T`); a later parameter cannot appear in an earlier
 * default (TS2744), so the left-to-right fold is exact. A list that still lacks a required
 * argument is NOT filled ([argumentNodes] answers null) and keeps the old fall-through.
 */
internal object AliasDefaultTypeArgs {

    /** The node standing for each parameter's argument — explicit, else the default — or null when
     *  the reference cannot be completed (too many arguments, or a required one omitted). */
    fun argumentNodes(typeArgs: List<TypeNode>, params: List<TypeParameter>): List<TypeNode>? {
        if (typeArgs.size > params.size) return null
        if (typeArgs.size == params.size) return typeArgs
        val out = ArrayList<TypeNode>(params.size)
        out.addAll(typeArgs)
        for (i in typeArgs.size until params.size) out.add(params[i].default ?: return null)
        return out
    }

    /** One resolved type per parameter; [resolve] types an explicit argument at the reference site,
     *  [resolveWithBindings] types a default with the earlier parameters bound. */
    fun resolve(
        typeArgs: List<TypeNode>, params: List<TypeParameter>,
        resolve: (TypeNode) -> Type, resolveWithBindings: (TypeNode, Map<String, Type>) -> Type,
    ): List<Type> {
        val out = ArrayList<Type>(params.size)
        for (a in typeArgs) out.add(resolve(a))
        if (typeArgs.size == params.size) return out
        val bindings = HashMap<String, Type>()
        for (i in out.indices) bindings[params[i].name.text] = out[i]
        for (i in typeArgs.size until params.size) {
            val t = resolveWithBindings(params[i].default!!, HashMap(bindings))
            out.add(t)
            bindings[params[i].name.text] = t
        }
        return out
    }
}
