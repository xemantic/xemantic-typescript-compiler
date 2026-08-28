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

package com.xemantic.typescript.compiler.kir.lower

import com.xemantic.typescript.compiler.ClassDeclaration
import com.xemantic.typescript.compiler.EnumDeclaration
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.VariableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import java.util.Collections
import java.util.IdentityHashMap

/**
 * What the declare pass records, shared by every file in the program.
 *
 * PROGRAM-wide and not per-file, which is the whole of what makes an import
 * work: a call in one file resolves to a `Signature` whose declaration is a
 * node in ANOTHER file's tree, and the IR function generated for it is found by
 * that node. Nothing about the import statement itself is consulted — it is
 * erased, exactly as tsc erases it — because the checker has already turned the
 * name into the declaration it names.
 *
 * Keyed by AST node IDENTITY throughout. Never by `nodeId`, which restarts at 0
 * in every `SourceFile` and would collide one node per file onto each id; and
 * never by a plain `HashMap`, since an AST node is a `data class` whose
 * `hashCode()` deep-recurses its whole subtree.
 */
internal class KirProgramTables(
    /** Every file being lowered — the population [isProgramNode] tests against. */
    val files: List<SourceFile>,
) {

    val functions = IdentityHashMap<FunctionDeclaration, IrSimpleFunction>()
    val classes = IdentityHashMap<ClassDeclaration, IrClass>()
    val methods = IdentityHashMap<MethodDeclaration, IrSimpleFunction>()
    val constructorsByDeclaration = IdentityHashMap<ClassDeclaration, IrConstructor>()
    val fields = IdentityHashMap<PropertyDeclaration, IrField>()

    /**
     * MODULE-level variables, as JVM statics on their file's facade.
     *
     * A library is made of these — `export const FOLD_QUOTED = 'quoted'` — and
     * a cross-file read of one must reach the same slot the declaring file
     * initialized, so the table is program-wide and keyed by the declaration.
     */
    val moduleVariables = IdentityHashMap<VariableDeclaration, ModuleVariable>()

    /**
     * One module-level variable: its slot, and the two functions that reach it.
     *
     * The accessors are not decoration. A top-level field belongs to its FILE,
     * and Kotlin's IR verifier refuses a read of one from another file
     * ("Access to a field declared in another file") — which is exactly what an
     * imported constant is. So a cross-file read calls [getter] and a cross-file
     * write calls [setter], while the declaring file uses the field directly.
     */
    class ModuleVariable(
        val field: IrField,
        val getter: IrSimpleFunction,
        val setter: IrSimpleFunction?,
        val owner: SourceFile,
    )

    /**
     * A class's ACCESSORS, by member name.
     *
     * A `get`/`set` pair is lowered as two ordinary methods rather than as an
     * `IrProperty`, because what consumes them is a property ACCESS in the
     * lowered TypeScript — never Kotlin's own property syntax — and a method
     * pair is the shape both sides of that already speak.
     */
    val getters = IdentityHashMap<ClassDeclaration, MutableMap<String, IrSimpleFunction>>()
    val setters = IdentityHashMap<ClassDeclaration, MutableMap<String, IrSimpleFunction>>()

    /**
     * ENUM member values, by declaration — the whole of an enum's lowering.
     *
     * An enum has no runtime object here: every member ACCESS is replaced by
     * its constant, which is what a `const enum` means and what a plain one
     * degrades to for every use except reflecting over the enum object itself
     * (`Type[x]`), which is refused. A member's value is a `Double` or a
     * `String`, mirroring TypeScript's two enum flavours.
     */
    val enumMembers = IdentityHashMap<EnumDeclaration, Map<String, Any>>()

    /** The class a `class X extends Y` names, where this backend generated it. */
    val superclasses = IdentityHashMap<ClassDeclaration, ClassDeclaration>()

    /**
     * The RUNTIME class a `class X extends Y` names — `class D extends Date`.
     *
     * Separate from [superclasses] because there is no `ClassDeclaration` to
     * point at: the base is one of this backend's own runtime classes, and what
     * the lowering needs from it is a JVM symbol rather than a TypeScript tree.
     */
    val runtimeSuperclasses = IdentityHashMap<ClassDeclaration, IrClassSymbol>()

    /** Static fields and methods, by owner and member name. */
    val staticFields = IdentityHashMap<ClassDeclaration, MutableMap<String, IrField>>()
    val staticMethods = IdentityHashMap<ClassDeclaration, MutableMap<String, IrSimpleFunction>>()

    /** [owner] and every class it inherits from, innermost first. */
    fun classChain(owner: ClassDeclaration): List<ClassDeclaration> {
        val chain = mutableListOf(owner)
        var current: ClassDeclaration? = superclasses[owner]
        while (current != null && chain.none { it === current }) {
            chain.add(current)
            current = superclasses[current]
        }
        return chain
    }

    /**
     * The IR parameters a call site may leave out.
     *
     * Nothing on an `IrValueParameter` records that, and the erased type does
     * not imply it either, so it is remembered at declaration and consulted at
     * every call.
     */
    val optionalParameters: MutableSet<IrValueParameter> =
        Collections.newSetFromMap(IdentityHashMap())

    /**
     * The REST parameter slots, remembered for the same reason [optionalParameters]
     * is: nothing on the `IrValueParameter` says it collects the caller's
     * trailing arguments rather than taking one of them.
     *
     * Its erased type is the runtime array — indistinguishable from a parameter
     * genuinely declared `T[]`, which is exactly the pair a call site must tell
     * apart: one takes an array the caller built, the other builds one.
     */
    val restParameters: MutableSet<IrValueParameter> =
        Collections.newSetFromMap(IdentityHashMap())

    /**
     * Is [node] part of THIS program, rather than of a lib `.d.ts`?
     *
     * Asked of a declaration reached through a type, and answered by walking
     * the parents `indexSourceFile` stamped (INV.2(a)) up to a root and
     * comparing it against the program's own files by identity. The check
     * decides whether a structural type is a property bag or a library type
     * this backend must refuse — see `ErasedTypes.isOwnStructuralDeclaration`.
     */
    fun isProgramNode(node: Node): Boolean {
        var current: Node? = node
        while (current != null) {
            if (current is SourceFile && files.any { it === current }) return true
            current = (current as? com.xemantic.typescript.compiler.NodeBase)?.parent
        }
        return false
    }

}
