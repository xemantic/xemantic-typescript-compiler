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

@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.xemantic.typescript.compiler.kir.lower

import com.xemantic.typescript.compiler.kir.api.KotlinApiModule
import com.xemantic.typescript.compiler.kir.api.KotlinClass
import com.xemantic.typescript.compiler.kir.api.KotlinConstantObject
import com.xemantic.typescript.compiler.kir.api.KotlinDeclaration
import com.xemantic.typescript.compiler.kir.ast.ClassDeclaration
import com.xemantic.typescript.compiler.kir.ast.EnumDeclaration
import com.xemantic.typescript.compiler.kir.ast.FunctionDeclaration
import com.xemantic.typescript.compiler.kir.ast.VariableDeclaration
import com.xemantic.typescript.compiler.kir.emit.IrProgramBuilder
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import com.xemantic.typescript.compiler.kir.emit.irDouble
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.properties
import java.util.IdentityHashMap

/**
 * (KAPI.4) The JVM half of a library's exported API: KOTLINC DECLARES, KIR DEFINES.
 *
 * The exported API's Kotlin source — the same text the metadata klib is compiled
 * from — goes into the JVM pipeline beside the lowered program (`KotlinIrEmitter`'s
 * `sources`), so the frontend declares every exported name with the metadata and the
 * JVM names a Kotlin consumer resolves against. What this fills in is what only the
 * lowering knows, the BEHAVIOUR:
 *
 * - a top-level function's body calls the lowered implementation;
 * - a top-level property's accessors call the module variable's `x$get`/`x$set`;
 * - an `enum`'s `object` answers its members' constants, since the enum itself has
 *   no runtime object;
 * - a CLASS is the lowered class, not a wrapper around it — a wrapper would make the
 *   instance a consumer holds a different object from the one the library's own code
 *   holds. So the stub kotlinc declared lends the lowered class its METADATA (class,
 *   constructor, methods, and a property getter per exported field) and is dropped,
 *   and every type in the API file that named the stub names the lowered class.
 *
 * And the file's static initializer runs the module inits the API entry imports, so
 * the first call into the library evaluates its modules, as the first `import` of it
 * does in JavaScript.
 *
 * A declaration the lowering did not produce is an [IllegalStateException]: the API is
 * extracted from the same checked program, so a miss here is a defect in one of the
 * two walks and never a property of the input.
 */
internal class KirApiDefinition(
    private val builder: IrProgramBuilder,
    private val tables: KirProgramTables,
    private val api: KotlinApiModule,
    private val apiFile: IrFile,
    /** An empty file of the same package, lending its FILE metadata — see [lend]. */
    private val metadataFile: IrFile,
    private val moduleInits: List<IrSimpleFunction>,
) {

    private val context = builder.pluginContext

    /** Each stub class kotlinc declared for an exported class, and the lowered class replacing it. */
    private val replacements = IdentityHashMap<IrClassSymbol, IrClass>()

    fun define() {
        val byName = api.declarations.associateBy { it.name.removeSurrounding("`") }
        apiFile.declarations.filterIsInstance<IrClass>().forEach { stub ->
            when (val declaration = byName[stub.name.asString()]) {
                is KotlinClass -> lend(stub, lowered(declaration.source as ClassDeclaration))
                is KotlinConstantObject -> defineConstants(stub, declaration)
                else -> {}
            }
        }
        apiFile.declarations.removeAll { it is IrClass && it.symbol in replacements }
        apiFile.declarations.toList().forEach { declaration ->
            when (declaration) {
                is IrSimpleFunction -> defineFunction(declaration, byName.declarationNamed(declaration))
                is IrProperty -> if (declaration.name.asString() == INIT_PROPERTY) {
                    defineInit(declaration)
                } else {
                    defineProperty(declaration, byName.declarationNamed(declaration))
                }
                else -> {}
            }
        }
    }

    private fun Map<String, KotlinDeclaration>.declarationNamed(declaration: IrDeclaration): KotlinDeclaration {
        val name = (declaration as? IrSimpleFunction)?.name ?: (declaration as IrProperty).name
        return get(name.asString()) ?: error("the exported API declares '$name' and the API model does not")
    }

    private fun lowered(declaration: ClassDeclaration): IrClass =
        tables.classes[declaration] ?: error("no lowered class for exported '${declaration.name?.text}'")

    // ---- classes ----------------------------------------------------------------------

    private fun lend(stub: IrClass, target: IrClass) {
        replacements[stub.symbol] = target
        target.metadata = stub.metadata
        // kotlinc serializes a class's metadata only inside a file that HAS file
        // metadata, and a lowered file has none. Not the API file's own: the lowered
        // file's facade would then list the API's functions a second time, and a
        // consumer would see every one of them twice. An empty file's lists nothing,
        // so the lowered facade stays invisible to Kotlin, as it was.
        target.file.metadata = metadataFile.metadata
        stub.constructors.singleOrNull()?.let { constructor ->
            val lowered = target.constructors.firstOrNull { it.regularCount() == constructor.regularCount() }
                ?: error("no lowered constructor of '${target.name}' takes ${constructor.regularCount()} argument(s)")
            lowered.metadata = constructor.metadata
        }
        stub.functions.filter { it.isFakeOverride.not() && it.metadata != null }.forEach { method ->
            val lowered = target.functions.firstOrNull {
                it.name == method.name && it.regularCount() == method.regularCount()
            } ?: error("no lowered method '${method.name}' on '${target.name}'")
            lowered.metadata = method.metadata
        }
        stub.properties.filter { !it.isFakeOverride }.forEach { property -> lendProperty(property, target) }
    }

    /**
     * An exported field or accessor, as the property kotlinc declared: its getter (and
     * setter) MOVE onto the lowered class and read the lowered member there.
     */
    private fun lendProperty(property: IrProperty, target: IrClass) {
        val name = property.name.asString()
        val field = target.declarations.filterIsInstance<IrField>()
            .firstOrNull { it.name.asString() == name && !it.isStatic }
        val getterMethod = target.functions.firstOrNull { it.name.asString() == "get$$name" }
        val setterMethod = target.functions.firstOrNull { it.name.asString() == "set$$name" }
        property.parent = target
        target.declarations.add(property)
        property.getter?.let { getter ->
            val receiver = rebindReceiver(getter, target)
            getter.body = DeclarationIrBuilder(context, getter.symbol).irBlockBody {
                val value = when {
                    getterMethod != null -> irCall(getterMethod.symbol).apply { arguments[0] = irGet(receiver) }
                    field != null -> irGetField(irGet(receiver), field)
                    else -> error("no lowered member '$name' on '${target.name}'")
                }
                +irReturn(coerce(value, getter.returnType))
            }
        }
        property.setter?.let { setter ->
            val receiver = rebindReceiver(setter, target)
            val value = setter.parameters.single { it.kind == IrParameterKind.Regular }
            setter.body = DeclarationIrBuilder(context, setter.symbol).irBlockBody {
                when {
                    setterMethod != null -> +irCall(setterMethod.symbol).apply {
                        arguments[0] = irGet(receiver)
                        arguments[1] = coerce(irGet(value), setterMethod.parameters[1].type)
                    }
                    field != null -> +irSetField(
                        irGet(receiver), field, coerce(irGet(value), field.type),
                    )
                    else -> error("no lowered member '$name' on '${target.name}'")
                }
            }
        }
    }

    /** The accessor's dispatch receiver, re-typed as the class it now belongs to. */
    private fun rebindReceiver(accessor: IrSimpleFunction, target: IrClass) =
        accessor.also { it.parent = target }.parameters.single { it.kind == IrParameterKind.DispatchReceiver }.also {
            it.type = target.defaultType
        }

    // ---- enums ------------------------------------------------------------------------

    private fun defineConstants(stub: IrClass, declaration: KotlinConstantObject) {
        val values = tables.enumMembers[declaration.source as EnumDeclaration]
            ?: error("no lowered enum '${declaration.name}'")
        stub.properties.forEach { property ->
            val getter = property.getter ?: return@forEach
            val value = values[property.name.asString()]
                ?: error("no value for enum member '${declaration.name}.${property.name}'")
            getter.body = DeclarationIrBuilder(context, getter.symbol).irBlockBody {
                +irReturn(if (value is Double) irDouble(value) else irString(value as String))
            }
        }
    }

    // ---- top level --------------------------------------------------------------------

    private fun defineFunction(function: IrSimpleFunction, declaration: KotlinDeclaration) {
        val implementation = tables.functions[declaration.source as FunctionDeclaration]
            ?: error("no lowered function for exported '${declaration.name}'")
        retype(function)
        function.body = DeclarationIrBuilder(context, function.symbol).irBlockBody {
            val call = irCall(implementation.symbol).apply {
                function.parameters.filter { it.kind == IrParameterKind.Regular }.forEachIndexed { index, parameter ->
                    arguments[index] = coerce(irGet(parameter), implementation.parameters[index].type)
                }
            }
            if (function.returnType.isUnit()) +call else +irReturn(coerce(call, function.returnType))
        }
    }

    private fun defineProperty(property: IrProperty, declaration: KotlinDeclaration) {
        val variable = tables.moduleVariables[declaration.source as VariableDeclaration]
            ?: error("no lowered module variable for exported '${declaration.name}'")
        property.getter?.let { getter ->
            retype(getter)
            getter.body = DeclarationIrBuilder(context, getter.symbol).irBlockBody {
                +irReturn(coerce(irCall(variable.getter.symbol), getter.returnType))
            }
        }
        property.setter?.let { setter ->
            val write = variable.setter ?: error("exported '${declaration.name}' is a `var` and its module variable is not")
            retype(setter)
            val value = setter.parameters.single { it.kind == IrParameterKind.Regular }
            setter.body = DeclarationIrBuilder(context, setter.symbol).irBlockBody {
                +irCall(write.symbol).apply { arguments[0] = coerce(irGet(value), write.parameters[0].type) }
            }
        }
    }

    /** The private property whose initializer is the file's `<clinit>`: run the module inits. */
    private fun defineInit(property: IrProperty) {
        val field = property.backingField ?: error("the API's init property has no backing field")
        val initializer = DeclarationIrBuilder(context, field.symbol)
        field.initializer = initializer.irExprBody(
            initializer.irBlock {
                moduleInits.forEach { +irCall(it.symbol) }
                +irUnit()
            }
        )
    }

    // ---- types ------------------------------------------------------------------------

    /** Re-points every type in [function]'s signature from a dropped stub class to the lowered class. */
    private fun retype(function: IrFunction) {
        function.returnType = replaced(function.returnType)
        function.parameters.forEach { it.type = replaced(it.type) }
    }

    private fun replaced(type: IrType): IrType {
        val target = type.classOrNull?.let { replacements[it] } ?: return type
        type as IrSimpleType
        return IrSimpleTypeImpl(target.symbol, type.isMarkedNullable(), emptyList(), type.annotations)
    }

    /**
     * [expression] as [type]: the API's erasure and the lowering's agree on what a value
     * IS, and may disagree on how precisely a signature says so (`Any?` against `JsMap?`).
     */
    private fun IrBuilderWithScope.coerce(expression: IrExpression, type: IrType): IrExpression =
        if (expression.type == type) expression else irAs(expression, type)

    private fun IrFunction.regularCount(): Int = parameters.count { it.kind == IrParameterKind.Regular }

    companion object {
        /**
         * The API source's one private declaration: a property whose initializer this
         * class replaces with the module inits, so loading the API facade evaluates the
         * library — rendered by `KotlinApiModule.renderForJvm` under a name no TypeScript
         * identifier can spell, so no export collides with it.
         */
        const val INIT_PROPERTY: String = "module-init"
    }

}
