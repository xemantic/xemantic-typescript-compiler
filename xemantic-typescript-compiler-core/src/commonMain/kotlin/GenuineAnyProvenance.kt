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
 * (CHK.228) which `any` (and `never`) a conditional type may EVALUATE. tsgo's `getConditionalType`
 * takes both branches for an `any` check type, the true one against an `any` / `unknown` extends
 * type, and nothing for a distributive check type of `never`, but this checker also answers `any`
 * for every type it cannot resolve (a free type parameter's indexed access, an unevaluated
 * conditional, …) and can answer `never` for a conditional it should have DEFERRED
 * (`[T] extends [never] ? X : never` with `T` free) — and evaluating such a WASH is a false
 * positive: measured, type-fest's ours-only rows went 301 -> 814 when every `any` was trusted, and
 * the corpus's `conditionalTypeAssignabilityWhenDeferred` gained a TS2322 when every `never` was.
 *
 * So only a GENUINE one is: the keyword, a naked alias argument the current [frame] records as bound
 * to one (an alias instantiated with a genuine argument — chained through nested aliases), or a
 * `NoInfer` / intersection (/ union, for `any`) over such a node (type-fest's
 * `IsAny<T> = 0 extends 1 & NoInfer<T> ? true : false`). The frame is valid only while the checker's
 * alias-argument map IS the one it was recorded with, so any other mapper install (a distribution, an
 * `infer` binding) reads every `any` / `never` as a wash — the conservative direction.
 */
internal class GenuineAnyProvenance(private val checker: Checker) {

    /** The alias-argument map and the names in it bound to a genuine `any` / `never`. */
    var frame: Pair<Map<String, Type>, Set<String>>? = null

    /** Whether [node] — already resolved to `any` — denotes a genuine `any`. */
    fun isGenuineAny(node: TypeNode, aliasArgs: Map<String, Type>?): Boolean = isGenuine(node, anyType, aliasArgs)

    /** Whether [node] — already resolved to `never` — denotes a genuine `never`. */
    fun isGenuineNever(node: TypeNode, aliasArgs: Map<String, Type>?): Boolean = isGenuine(node, neverType, aliasArgs)

    private fun isGenuine(node: TypeNode, t: Type, aliasArgs: Map<String, Type>?): Boolean =
        when (val n = checker.intersectionOps.unparenthesized(node)) {
            is KeywordTypeNode -> n.kind == (if (t === anyType) SyntaxKind.AnyKeyword else SyntaxKind.NeverKeyword)
            is TypeReference -> {
                val name = (n.typeName as? Identifier)?.text
                val args = n.typeArguments
                if (name == null) false
                else if (args.isNullOrEmpty())
                    frame.let { it != null && it.first === aliasArgs && name in it.second && aliasArgs[name] === t }
                else name == "NoInfer" && args.size == 1 && isGenuine(args[0], t, aliasArgs)
            }
            is IntersectionType -> n.types.any { isGenuine(it, t, aliasArgs) }
            is UnionType -> if (t === anyType) n.types.any { isGenuine(it, t, aliasArgs) }
                else n.types.all { isGenuine(it, t, aliasArgs) }
            else -> false
        }

    /** The parameters of an alias being instantiated that are bound to a genuine `any` / `never` —
     *  judged in the CALLER's frame, so a genuine argument passed down a chain of aliases stays
     *  genuine. (An outer frame's names are not inherited: an alias body cannot name another alias's
     *  parameter.) */
    fun argNames(
        typeArgs: List<TypeNode>, params: List<TypeParameter>, resolvedArgs: List<Type>,
        aliasArgs: Map<String, Type>?,
    ): Set<String> {
        var out: HashSet<String>? = null
        for (i in params.indices) {
            val r = resolvedArgs[i]
            if ((r === anyType || r === neverType) && isGenuine(typeArgs[i], r, aliasArgs))
                (out ?: HashSet<String>().also { out = it }).add(params[i].name.text)
        }
        return out ?: emptySet()
    }
}
