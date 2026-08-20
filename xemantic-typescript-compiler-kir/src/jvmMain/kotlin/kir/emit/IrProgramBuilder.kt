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

// `IrSymbol.owner` and `IrDeclarationContainer.declarations` are both
// guarded by this opt-in because they are unsound WHILE the IR tree is being
// built by the frontend. Everything here runs strictly afterwards, inside an
// `IrGenerationExtension`, which is precisely when they are safe.
@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.xemantic.typescript.compiler.kir.emit

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Marks every declaration this module generates.
 *
 * The Kotlin backend asks each declaration where it came from; `GeneratedByPlugin`
 * with a key of our own is what tells it "not from source", which several
 * lowerings and the metadata writer both need.
 */
public object KirDeclarationKey : GeneratedDeclarationKey()

/**
 * The surface a caller of [KotlinIrEmitter.emit] builds a program through.
 *
 * It is a thin facade, not a wall: [pluginContext] and [moduleFragment] are
 * public, because the lowering will need IR shapes this class has no business
 * anticipating. What it does own is the handful of steps that are easy to get
 * subtly wrong — creating a synthetic file with a package fragment DESCRIPTOR
 * (without one, the first call to an inline function such as `println` dies
 * inside `IrInlineCodegen` with `IrFileImpl cannot be cast to IrDeclaration`),
 * setting `parent` on every declaration, and selecting one overload out of a
 * `referenceFunctions` result.
 */
public class IrProgramBuilder internal constructor(
    public val pluginContext: IrPluginContext,
    public val moduleFragment: IrModuleFragment,
) {

    public val irBuiltIns: IrBuiltIns get() = pluginContext.irBuiltIns

    public val irFactory: IrFactory get() = pluginContext.irFactory

    /** The origin stamped on everything generated here. */
    public val generatedOrigin: IrDeclarationOrigin =
        IrDeclarationOrigin.GeneratedByPlugin(KirDeclarationKey)

    /**
     * Adds an empty file to the module, in [packageName], named [fileName].
     *
     * The name is what appears in stack traces and in the `SourceFile`
     * attribute of the generated classes; it does not have to exist on disk.
     */
    public fun file(
        packageName: String,
        fileName: String
    ): IrFile {
        val file = IrFileImpl(
            NaiveSourceBasedFileEntryImpl(fileName, intArrayOf(0), 0, 0),
            EmptyPackageFragmentDescriptor(moduleFragment.descriptor, FqName(packageName))
        )
        file.module = moduleFragment
        moduleFragment.files.add(file)
        return file
    }

    /**
     * Adds a top-level function to [file].
     *
     * [body] receives the finished function so it can read back the parameters
     * it just declared — they are the only way to reference an argument, and
     * they exist only once the builder has created them.
     */
    public fun function(
        file: IrFile,
        name: String,
        returnType: IrType,
        vararg parameters: Pair<String, IrType>,
        body: IrBlockBodyBuilder.(IrSimpleFunction) -> Unit
    ): IrSimpleFunction {
        val function = irFactory.buildFun {
            this.name = Name.identifier(name)
            this.returnType = returnType
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            origin = generatedOrigin
        }
        function.parent = file
        parameters.forEach { (parameterName, parameterType) ->
            function.addValueParameter(parameterName, parameterType, generatedOrigin)
        }
        function.body = DeclarationIrBuilder(pluginContext, function.symbol).irBlockBody {
            body(function)
        }
        file.declarations.add(function)
        // Without this the function is in the bytecode but not in @Metadata,
        // i.e. invisible to any Kotlin module that later compiles against it.
        pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)
        return function
    }

    /**
     * Resolves one top-level function of [packageName] named [name], as seen
     * from [fromFile].
     *
     * The file is not decoration: resolution is scoped to the module and
     * dependencies a given file compiles against, which is what
     * `IrPluginContext.finderForSource` expresses and what the older
     * `referenceFunctions` — now deprecated — silently guessed at.
     *
     * [select] picks among overloads; leaving it out asserts there is only one.
     * Failing to match exactly one is an error naming the candidates, because
     * the alternative — a silently wrong overload — produces bytecode that
     * fails at link time in a program nobody can trace back to here.
     */
    public fun referenceFunction(
        fromFile: IrFile,
        packageName: String,
        name: String,
        select: (IrSimpleFunctionSymbol) -> Boolean = { true }
    ): IrSimpleFunctionSymbol = selectSingle(
        finder(fromFile).findFunctions(
            CallableId(FqName(packageName), Name.identifier(name))
        ),
        "$packageName.$name",
        select
    )

    /** Resolves one member function of the class [classFqName] named [name]. */
    public fun referenceMemberFunction(
        fromFile: IrFile,
        classFqName: String,
        name: String,
        select: (IrSimpleFunctionSymbol) -> Boolean = { true }
    ): IrSimpleFunctionSymbol = selectSingle(
        finder(fromFile).findFunctions(
            CallableId(ClassId.topLevel(FqName(classFqName)), Name.identifier(name))
        ),
        "$classFqName.$name",
        select
    )

    /** Resolves the class [classFqName], e.g. `kotlin.collections.ArrayList`. */
    public fun referenceClass(
        fromFile: IrFile,
        classFqName: String
    ): IrClassSymbol =
        finder(fromFile).findClass(ClassId.topLevel(FqName(classFqName)))
            ?: error("cannot resolve class '$classFqName' on the generated program's classpath")

    private fun finder(fromFile: IrFile): DeclarationFinder =
        pluginContext.finderForSource(fromFile)

    private fun selectSingle(
        candidates: Collection<IrSimpleFunctionSymbol>,
        description: String,
        select: (IrSimpleFunctionSymbol) -> Boolean
    ): IrSimpleFunctionSymbol {
        if (candidates.isEmpty()) {
            error("cannot resolve '$description' on the generated program's classpath")
        }
        val matching = candidates.filter(select)
        return when (matching.size) {
            1 -> matching.single()
            0 -> error(
                "no overload of '$description' matched; candidates: " +
                    candidates.joinToString { it.owner.renderParameterTypes() }
            )
            else -> error(
                "'$description' is ambiguous; matched: " +
                    matching.joinToString { it.owner.renderParameterTypes() }
            )
        }
    }

    private fun IrSimpleFunction.renderParameterTypes(): String =
        parameters.joinToString(prefix = "(", postfix = ")") { "${it.kind} ${it.type}" }

}

/**
 * A `Double` constant.
 *
 * The builders ship `irInt`, `irString` and the rest but no `irDouble`, and
 * `Double` is the type EVERY TypeScript number lowers to — so this is the most
 * frequently needed constant in the whole backend.
 */
public fun IrBuilderWithScope.irDouble(value: Double): IrConst =
    IrConstImpl.double(startOffset, endOffset, context.irBuiltIns.doubleType, value)
