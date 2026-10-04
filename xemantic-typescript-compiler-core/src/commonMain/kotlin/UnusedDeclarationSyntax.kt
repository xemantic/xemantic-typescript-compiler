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
 * (P18.271) True for a statement written with `declare`: tsgo's `reportUnused` drops every
 * unused-declaration row on a node carrying `NodeFlagsAmbient`, and every node under a
 * `declare` statement carries it — a module augmentation's interfaces, a `declare namespace`'s
 * members, a `declare class C<T>`'s type parameters (measured against tsgo 7.0.2).
 */
internal fun isAmbientUnusedRoot(stmt: Statement): Boolean {
    val modifiers = when (stmt) {
        is ModuleDeclaration -> stmt.modifiers
        is ClassDeclaration -> stmt.modifiers
        is InterfaceDeclaration -> stmt.modifiers
        is TypeAliasDeclaration -> stmt.modifiers
        is FunctionDeclaration -> stmt.modifiers
        is EnumDeclaration -> stmt.modifiers
        is VariableStatement -> stmt.modifiers
        else -> return false
    }
    return ModifierFlag.Declare in modifiers
}
