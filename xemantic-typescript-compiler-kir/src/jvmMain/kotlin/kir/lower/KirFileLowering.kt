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

// `IrSymbol.owner` and `IrDeclarationContainer.declarations` are unsound only
// WHILE the frontend is building the IR tree; every line here runs afterwards,
// inside an `IrGenerationExtension`.
@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.xemantic.typescript.compiler.kir.lower

import com.xemantic.typescript.compiler.ArrayLiteralExpression
import com.xemantic.typescript.compiler.ArrowFunction
import com.xemantic.typescript.compiler.AsExpression
import com.xemantic.typescript.compiler.BinaryExpression
import com.xemantic.typescript.compiler.Block
import com.xemantic.typescript.compiler.BreakStatement
import com.xemantic.typescript.compiler.CallExpression
import com.xemantic.typescript.compiler.ClassDeclaration
import com.xemantic.typescript.compiler.ConditionalExpression
import com.xemantic.typescript.compiler.Constructor
import com.xemantic.typescript.compiler.ContinueStatement
import com.xemantic.typescript.compiler.ElementAccessExpression
import com.xemantic.typescript.compiler.EmptyStatement
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.ExpressionStatement
import com.xemantic.typescript.compiler.ForStatement
import com.xemantic.typescript.compiler.FunctionExpression
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.IfStatement
import com.xemantic.typescript.compiler.InterfaceDeclaration
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.ModifierFlag
import com.xemantic.typescript.compiler.NewExpression
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NonNullExpression
import com.xemantic.typescript.compiler.NumericLiteralNode
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.ParenthesizedExpression
import com.xemantic.typescript.compiler.PrefixUnaryExpression
import com.xemantic.typescript.compiler.PropertyAccessExpression
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.ReturnStatement
import com.xemantic.typescript.compiler.SpreadElement
import com.xemantic.typescript.compiler.Statement
import com.xemantic.typescript.compiler.StringLiteralNode
import com.xemantic.typescript.compiler.SyntaxKind
import com.xemantic.typescript.compiler.Type
import com.xemantic.typescript.compiler.TypeAliasDeclaration
import com.xemantic.typescript.compiler.TypeFlags
import com.xemantic.typescript.compiler.TypeOfExpression
import com.xemantic.typescript.compiler.VariableStatement
import com.xemantic.typescript.compiler.WhileStatement
import com.xemantic.typescript.compiler.forEachChild
import com.xemantic.typescript.compiler.kir.emit.IrProgramBuilder
import com.xemantic.typescript.compiler.kir.emit.irDouble
import com.xemantic.typescript.compiler.kir.front.CheckedTypeScript
import com.xemantic.typescript.compiler.kir.refuse
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irEqualsNull
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irReturnUnit
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBreakImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrContinueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDoWhileLoopImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhileLoopImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import java.util.IdentityHashMap

/**
 * Lowers one checked TypeScript file to one synthetic Kotlin [IrFile].
 *
 * Two passes, as `docs/kir-lowering.md` §2 requires. **Declare** creates every
 * declaration EMPTY — symbol, name, parameters, return type, class shell — and
 * records `tsDeclaration → IrSymbol`; **define** fills the bodies, resolving
 * every reference through that table. Declaration order is irrelevant in
 * TypeScript, so a one-pass lowering would need a patch-up phase, which is
 * strictly worse.
 *
 * Top-level STATEMENTS are collected into a generated `main` in source order,
 * which is what makes a script-shaped `.ts` runnable at all.
 *
 * Nothing here degrades. Every path that cannot proceed calls [refuse], which
 * names the file, the position and the construct and aborts the emission.
 */
internal class KirFileLowering(
    private val builder: IrProgramBuilder,
    private val checked: CheckedTypeScript,
    packageName: String,
    kotlinFileName: String,
) {

    private val tsFile = checked.sourceFile
    private val facts = checked.facts
    private val irBuiltIns = builder.irBuiltIns
    private val irFile: IrFile = builder.file(packageName, kotlinFileName)
    private val intrinsics = KirIntrinsics(builder, irFile)

    // ---- the declare pass's tables, keyed by AST node IDENTITY -------------
    // Never by `nodeId` (it restarts at 0 in every SourceFile) and never by a
    // plain HashMap (an AST node is a data class whose hashCode deep-recurses
    // its whole subtree).

    private val functions = IdentityHashMap<FunctionDeclaration, IrSimpleFunction>()
    private val classes = IdentityHashMap<ClassDeclaration, IrClass>()
    private val methods = IdentityHashMap<MethodDeclaration, IrSimpleFunction>()
    private val constructorsByDeclaration = IdentityHashMap<ClassDeclaration, IrConstructor>()
    private val fields = IdentityHashMap<PropertyDeclaration, IrField>()

    private val types = ErasedTypes(
        irBuiltIns,
        classForDeclaration = { declaration ->
            (declaration as? ClassDeclaration)?.let { classes[it] }
        },
        jsArrayType = { intrinsics.jsArrayType },
    )

    // ---- the define pass's walk state --------------------------------------

    /** Lexical scopes, innermost last. TypeScript locals, not IR temporaries. */
    private val scopes = ArrayDeque<MutableMap<String, IrValueDeclaration>>()

    /** Enclosing loops, innermost last, for `break` and `continue`. */
    private val loops = ArrayDeque<LoopFrame>()

    private var current: FunctionFrame? = null

    /**
     * What `break` and `continue` inside one TypeScript loop jump to.
     *
     * They differ for a `for`: `continue` must still run the incrementor, so the
     * body sits inside a one-iteration inner loop whose `break` IS the continue
     * (§4). [continueTarget] is that inner loop where one exists.
     */
    private class LoopFrame(val breakTarget: IrLoop, val continueTarget: IrLoop)

    private class FunctionFrame(
        val irFunction: IrFunction,
        val scope: IrBuilderWithScope,
        val returnType: IrType,
        val thisReceiver: IrValueParameter?,
        val ownerClass: ClassDeclaration?,
    )

    private val frame: FunctionFrame
        get() = current ?: error("no function frame — the lowering is out of order")

    private val scope: IrBuilderWithScope get() = frame.scope

    /** Runs both passes and returns the file the program's entry point is in. */
    fun lower(): IrFile {
        tsFile.statements.forEach { declare(it) }
        tsFile.statements.forEach { define(it) }
        buildMain()
        return irFile
    }

    // =======================================================================
    // Pass 1 — declare
    // =======================================================================

    private fun declare(statement: Statement) {
        when (statement) {
            is FunctionDeclaration -> declareFunction(statement)
            is ClassDeclaration -> declareClass(statement)
            // A type-only declaration contributes no runtime shape, so it is
            // erased rather than refused — exactly as tsc erases it.
            is InterfaceDeclaration, is TypeAliasDeclaration -> {}
            else -> {}
        }
    }

    private fun declareFunction(declaration: FunctionDeclaration) {
        val name = declaration.name
            ?: refuse(tsFile, declaration, "cannot lower an anonymous top-level function")
        if (declaration.body == null) {
            refuse(tsFile, declaration, "cannot lower a function with no body (an overload or `declare`)")
        }
        if (declaration.asteriskToken || ModifierFlag.Async in declaration.modifiers) {
            refuse(tsFile, declaration, "generators and `async` are out of the spike subset")
        }
        val function = builder.irFactory.buildFun {
            this.name = Name.identifier(kotlinName(name.text))
            returnType = declaredReturnType(declaration)
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            origin = builder.generatedOrigin
        }
        function.parent = irFile
        addParameters(function, declaration.parameters)
        irFile.declarations.add(function)
        builder.pluginContext.metadataDeclarationRegistrar
            .registerFunctionAsMetadataVisible(function)
        functions[declaration] = function
    }

    private fun declareClass(declaration: ClassDeclaration) {
        val name = declaration.name
            ?: refuse(tsFile, declaration, "cannot lower an anonymous class")
        if (declaration.heritageClauses?.isNotEmpty() == true) {
            refuse(tsFile, declaration, "`extends`/`implements` is out of the spike subset")
        }
        val irClass = builder.irFactory.buildClass {
            this.name = Name.identifier(kotlinName(name.text))
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            origin = builder.generatedOrigin
        }
        irClass.parent = irFile
        irClass.superTypes = listOf(irBuiltIns.anyType)
        // Without a `this` receiver the class has no way to express a member's
        // dispatch receiver, and every method body would be unbuildable.
        irClass.createThisReceiverParameter()
        irFile.declarations.add(irClass)
        classes[declaration] = irClass

        for (member in declaration.members) {
            when (member) {
                is PropertyDeclaration -> declareField(declaration, irClass, member)
                is MethodDeclaration -> declareMethod(declaration, irClass, member)
                is Constructor -> declareConstructor(declaration, irClass, member)
                else -> refuse(tsFile, member, "cannot lower this class member")
            }
        }
        if (declaration.members.none { it is Constructor }) {
            defaultConstructor(declaration, irClass)
        }
    }

    private fun declareField(
        owner: ClassDeclaration,
        irClass: IrClass,
        member: PropertyDeclaration
    ) {
        val name = member.name as? Identifier
            ?: refuse(tsFile, member, "cannot lower a computed property name")
        if (ModifierFlag.Static in member.modifiers) {
            refuse(tsFile, member, "static members are out of the spike subset")
        }
        // The property's type comes from the CLASS's type, not from the access
        // expression: `this` types as `any` here, so `this.x` does too.
        val declaredType = facts.memberTypeOf(member)
            ?: refuse(tsFile, member, "the checker gave no type for property '${name.text}'")
        fields[member] = irClass.addField(
            kotlinName(name.text),
            erase(member, declaredType),
            DescriptorVisibilities.PRIVATE
        )
    }

    private fun declareMethod(
        owner: ClassDeclaration,
        irClass: IrClass,
        member: MethodDeclaration
    ) {
        val name = member.name as? Identifier
            ?: refuse(tsFile, member, "cannot lower a computed method name")
        if (ModifierFlag.Static in member.modifiers) {
            refuse(tsFile, member, "static members are out of the spike subset")
        }
        if (member.body == null) {
            refuse(tsFile, member, "cannot lower a method with no body")
        }
        val function = irClass.addFunction {
            this.name = Name.identifier(kotlinName(name.text))
            returnType = declaredReturnType(member)
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            origin = builder.generatedOrigin
        }
        // Before the value parameters: `IrFunction.parameters` is one flat list
        // discriminated by kind, and the dispatch receiver has to come first.
        function.parameters = listOf(dispatchReceiver(function, irClass))
        addParameters(function, member.parameters)
        methods[member] = function
    }

    private fun declareConstructor(
        owner: ClassDeclaration,
        irClass: IrClass,
        member: Constructor
    ) {
        if (constructorsByDeclaration.containsKey(owner)) {
            refuse(tsFile, member, "constructor overloads are out of the spike subset")
        }
        member.parameters.firstOrNull { it.modifiers.isNotEmpty() }?.let {
            refuse(tsFile, it, "parameter properties are out of the spike subset")
        }
        val constructor = irClass.addConstructor {
            isPrimary = true
            returnType = irClass.defaultType
            visibility = DescriptorVisibilities.PUBLIC
            origin = builder.generatedOrigin
        }
        addParameters(constructor, member.parameters)
        constructorsByDeclaration[owner] = constructor
    }

    private fun defaultConstructor(owner: ClassDeclaration, irClass: IrClass) {
        val constructor = irClass.addConstructor {
            isPrimary = true
            returnType = irClass.defaultType
            visibility = DescriptorVisibilities.PUBLIC
            origin = builder.generatedOrigin
        }
        constructorsByDeclaration[owner] = constructor
    }

    private fun dispatchReceiver(function: IrFunction, irClass: IrClass): IrValueParameter =
        builder.irFactory.createValueParameter(
            startOffset = UNDEFINED,
            endOffset = UNDEFINED,
            origin = builder.generatedOrigin,
            kind = org.jetbrains.kotlin.ir.declarations.IrParameterKind.DispatchReceiver,
            name = Name.identifier("<this>"),
            type = irClass.defaultType,
            isAssignable = false,
            symbol = org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl(),
            varargElementType = null,
            isCrossinline = false,
            isNoinline = false,
            isHidden = false,
        ).also { it.parent = function }

    private fun addParameters(function: IrFunction, parameters: List<Parameter>) {
        for (parameter in parameters) {
            val name = parameter.name as? Identifier
                ?: refuse(tsFile, parameter, "destructuring parameters are out of the spike subset")
            if (parameter.dotDotDotToken) {
                refuse(tsFile, parameter, "rest parameters are out of the spike subset")
            }
            if (parameter.initializer != null) {
                refuse(tsFile, parameter, "default parameter values are out of the spike subset")
            }
            val type = facts.typeOf(parameter)
                ?: refuse(tsFile, parameter, "the checker gave no type for parameter '${name.text}'")
            function.addValueParameter(
                kotlinName(name.text),
                erase(parameter, type),
                builder.generatedOrigin
            )
        }
    }

    /**
     * A declaration's RETURN type, from the signature the checker built for it.
     *
     * There is no other route: a `TypeNode` is syntax, and the lens exposes no
     * way to resolve one. A missing signature is an oracle miss and is refused
     * rather than defaulted to `Unit`, which would silently change what the
     * function means.
     */
    private fun declaredReturnType(declaration: Node): IrType {
        val signature = facts.signatureOf(declaration)
            ?: refuse(tsFile, declaration, "the checker gave no signature for this declaration")
        val returnType = signature.resolvedReturnType
            ?: refuse(tsFile, declaration, "the checker resolved no return type for this declaration")
        return erase(declaration, returnType)
    }

    // =======================================================================
    // Pass 2 — define
    // =======================================================================

    private fun define(statement: Statement) {
        when (statement) {
            is FunctionDeclaration -> defineFunction(statement)
            is ClassDeclaration -> defineClass(statement)
            else -> {}
        }
    }

    private fun defineFunction(declaration: FunctionDeclaration) {
        val function = functions.getValue(declaration)
        val body = declaration.body ?: return
        inFunction(function, function.returnType, null, null) {
            function.body = blockBody(function, declaration.parameters, body.statements)
        }
    }

    private fun defineClass(declaration: ClassDeclaration) {
        val irClass = classes.getValue(declaration)
        for (member in declaration.members) {
            when (member) {
                is MethodDeclaration -> {
                    val function = methods.getValue(member)
                    val body = member.body ?: continue
                    inFunction(
                        function,
                        function.returnType,
                        function.parameters.first(),
                        declaration
                    ) {
                        function.body = blockBody(function, member.parameters, body.statements)
                    }
                }
                else -> {}
            }
        }
        defineConstructor(declaration, irClass)
    }

    /**
     * The constructor body: the delegation and initializer prologue every JVM
     * constructor needs, then the TypeScript body's own statements.
     *
     * `IrInstanceInitializerCallImpl` is not optional even with no field
     * initializers — it is where the backend puts them, and omitting it makes a
     * later field initializer silently vanish rather than fail.
     */
    private fun defineConstructor(declaration: ClassDeclaration, irClass: IrClass) {
        val constructor = constructorsByDeclaration.getValue(declaration)
        val tsConstructor = declaration.members.filterIsInstance<Constructor>().firstOrNull()
        val anyConstructor = irBuiltIns.anyClass.owner.constructors.first()
        inFunction(constructor, irBuiltIns.unitType, constructor.parameters.firstOrNull(), declaration) {
            // A constructor has no dispatch receiver parameter; `this` inside it
            // is the class's own thisReceiver.
            current = FunctionFrame(
                constructor,
                DeclarationIrBuilder(builder.pluginContext, constructor.symbol),
                irBuiltIns.unitType,
                irClass.thisReceiver,
                declaration
            )
            val statements = mutableListOf<IrStatement>(
                scope.irDelegatingConstructorCall(anyConstructor),
                IrInstanceInitializerCallImpl(
                    UNDEFINED, UNDEFINED, irClass.symbol, irBuiltIns.unitType
                )
            )
            scopes.addLast(HashMap())
            tsConstructor?.let { bindParameters(constructor, it.parameters) }
            tsConstructor?.body?.statements?.forEach { lowerStatement(it, statements) }
            scopes.removeLast()
            constructor.body = blockBodyOf(statements)
        }
    }

    /**
     * The generated `main`: every top-level statement, in source order.
     *
     * Declarations are skipped because pass 1 already emitted them, and a `main`
     * is emitted even when there is nothing to run — an empty entry point is a
     * runnable program, an absent one is not.
     */
    private fun buildMain() {
        val statements = tsFile.statements.filter {
            it !is FunctionDeclaration && it !is ClassDeclaration &&
                it !is InterfaceDeclaration && it !is TypeAliasDeclaration
        }
        val main = builder.irFactory.buildFun {
            name = Name.identifier("main")
            returnType = irBuiltIns.unitType
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            origin = builder.generatedOrigin
        }
        main.parent = irFile
        irFile.declarations.add(main)
        builder.pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(main)
        inFunction(main, irBuiltIns.unitType, null, null) {
            main.body = blockBody(main, emptyList(), statements)
        }
    }

    private fun inFunction(
        function: IrFunction,
        returnType: IrType,
        thisReceiver: IrValueParameter?,
        ownerClass: ClassDeclaration?,
        block: () -> Unit
    ) {
        val saved = current
        current = FunctionFrame(
            function,
            DeclarationIrBuilder(builder.pluginContext, function.symbol),
            returnType,
            thisReceiver,
            ownerClass
        )
        try {
            block()
        } finally {
            current = saved
        }
    }

    private fun blockBody(
        function: IrFunction,
        parameters: List<Parameter>,
        statements: List<Statement>
    ): org.jetbrains.kotlin.ir.expressions.IrBlockBody {
        val lowered = mutableListOf<IrStatement>()
        scopes.addLast(HashMap())
        bindParameters(function, parameters)
        statements.forEach { lowerStatement(it, lowered) }
        scopes.removeLast()
        return blockBodyOf(lowered)
    }

    /** `IrFactory.createBlockBody` takes no statements; they are added after. */
    private fun blockBodyOf(
        statements: List<IrStatement>
    ): org.jetbrains.kotlin.ir.expressions.IrBlockBody =
        builder.irFactory.createBlockBody(UNDEFINED, UNDEFINED).apply {
            this.statements.addAll(statements)
        }

    private fun bindParameters(function: IrFunction, parameters: List<Parameter>) {
        val valueParameters = function.parameters.filter {
            it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular
        }
        parameters.forEachIndexed { index, parameter ->
            val name = parameter.name as? Identifier ?: return@forEachIndexed
            scopes.last()[name.text] = valueParameters[index]
        }
    }

    // ---- statements --------------------------------------------------------

    private fun lowerStatement(statement: Statement, out: MutableList<IrStatement>) {
        when (statement) {
            is VariableStatement -> lowerVariables(statement, out)
            is ExpressionStatement -> out.add(lowerExpression(statement.expression))
            is ReturnStatement -> out.add(lowerReturn(statement))
            is IfStatement -> out.add(lowerIf(statement))
            is WhileStatement -> out.add(lowerWhile(statement))
            is ForStatement -> out.add(lowerFor(statement))
            is Block -> {
                scopes.addLast(HashMap())
                val inner = mutableListOf<IrStatement>()
                statement.statements.forEach { lowerStatement(it, inner) }
                scopes.removeLast()
                out.add(IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, inner))
            }
            is BreakStatement -> {
                if (statement.label != null) {
                    refuse(tsFile, statement, "labelled `break` is out of the spike subset")
                }
                val target = loops.lastOrNull()
                    ?: refuse(tsFile, statement, "`break` outside a loop")
                out.add(IrBreakImpl(UNDEFINED, UNDEFINED, irBuiltIns.nothingType, target.breakTarget))
            }
            is ContinueStatement -> {
                if (statement.label != null) {
                    refuse(tsFile, statement, "labelled `continue` is out of the spike subset")
                }
                val target = loops.lastOrNull()
                    ?: refuse(tsFile, statement, "`continue` outside a loop")
                out.add(
                    if (target.continueTarget === target.breakTarget) {
                        IrContinueImpl(
                            UNDEFINED, UNDEFINED, irBuiltIns.nothingType, target.continueTarget
                        )
                    } else {
                        // In a `for`, `continue` must still run the incrementor,
                        // so it BREAKS the one-iteration inner loop the body sits
                        // in and falls through to the step (§4).
                        IrBreakImpl(
                            UNDEFINED, UNDEFINED, irBuiltIns.nothingType, target.continueTarget
                        )
                    }
                )
            }
            is EmptyStatement -> {}
            is FunctionDeclaration, is ClassDeclaration,
            is InterfaceDeclaration, is TypeAliasDeclaration -> {}
            else -> refuse(tsFile, statement, "cannot lower this statement")
        }
    }

    private fun lowerVariables(statement: VariableStatement, out: MutableList<IrStatement>) {
        val list = statement.declarationList
        if (list.flags == SyntaxKind.VarKeyword) {
            refuse(
                tsFile, statement,
                "`var` is out of the spike subset — its function scoping is not modelled"
            )
        }
        val mutable = list.flags != SyntaxKind.ConstKeyword
        for (declaration in list.declarations) {
            val name = declaration.name as? Identifier
                ?: refuse(tsFile, declaration, "destructuring declarations are out of the spike subset")
            val type = erase(declaration, variableType(declaration.name, declaration.initializer))
            val variable = buildVariable(
                frame.irFunction as IrDeclarationParent,
                UNDEFINED,
                UNDEFINED,
                builder.generatedOrigin,
                Name.identifier(kotlinName(name.text)),
                type,
                isVar = mutable,
            )
            variable.initializer = declaration.initializer
                ?.let { coerce(it, lowerExpression(it), type) }
                // `let x;` is `undefined`, which §3.1 maps to `null` — so the
                // variable must be able to hold one.
                ?: if (type == types.nothingNullable || type == irBuiltIns.anyNType) {
                    scope.irNull()
                } else {
                    refuse(
                        tsFile, declaration,
                        "cannot lower '${name.text}' declared without an initializer"
                    )
                }
            out.add(variable)
            scopes.last()[name.text] = variable
        }
    }

    /**
     * A local's type: its own, unless the checker had none for it.
     *
     * The fallback is not a widening but the opposite — a RECOVERY. Measured,
     * the checker answers `any` for a `let` declared in a `for` header, and
     * `any` is the one answer that carries no information; the initializer's own
     * type does. A genuinely `any`-typed local is thus typed as its initializer
     * and any later assignment of a different shape is REFUSED rather than
     * silently boxed, which is the honest failure for a construct design doc §5
     * puts out of scope.
     */
    private fun variableType(name: Expression, initializer: Expression?): Type {
        val declared = (name as? Identifier)?.let { facts.typeOf(it) }
        if (declared != null && !declared.flags.hasAny(TypeFlags.Any or TypeFlags.Unknown)) {
            return declared
        }
        val fromInitializer = initializer?.let { facts.typeOf(it) }
        return fromInitializer ?: declared
            ?: refuse(tsFile, name, "the checker gave no type for this declaration")
    }

    private fun lowerReturn(statement: ReturnStatement): IrExpression {
        val value = statement.expression
            ?: return scope.irReturnUnit()
        return scope.irReturn(coerce(value, lowerExpression(value), frame.returnType))
    }

    private fun lowerIf(statement: IfStatement): IrExpression {
        val condition = condition(statement.expression)
        val branches = mutableListOf<org.jetbrains.kotlin.ir.expressions.IrBranch>(
            scope.irBranch(condition, statementExpression(statement.thenStatement))
        )
        statement.elseStatement?.let {
            branches.add(scope.irElseBranch(statementExpression(it)))
        }
        return scope.irWhen(irBuiltIns.unitType, branches)
    }

    private fun lowerWhile(statement: WhileStatement): IrExpression {
        val loop = IrWhileLoopImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null)
        loop.condition = condition(statement.expression)
        loops.addLast(LoopFrame(loop, loop))
        loop.body = statementExpression(statement.statement)
        loops.removeLast()
        return loop
    }

    /**
     * `for (init; cond; step) body` → `{ init; while (cond) { body; step } }`.
     *
     * With one refinement §4 insists on: `continue` must still run `step`, and
     * IR has no `goto`. So when the body actually contains a `continue` bound to
     * this loop, the body goes inside a ONE-ITERATION `do { … } while (false)`,
     * whose `break` is exactly "skip the rest of the body, then run the step".
     * The trampoline is emitted only where it is needed, so an ordinary `for`
     * stays a plain `while`.
     */
    private fun lowerFor(statement: ForStatement): IrExpression {
        scopes.addLast(HashMap())
        val outer = mutableListOf<IrStatement>()
        when (val initializer = statement.initializer) {
            null -> {}
            is VariableStatement -> lowerVariables(initializer, outer)
            is com.xemantic.typescript.compiler.VariableDeclarationList ->
                lowerVariables(VariableStatement(initializer), outer)
            is Expression -> outer.add(lowerExpression(initializer))
            else -> refuse(tsFile, initializer, "cannot lower this `for` initializer")
        }
        val loop = IrWhileLoopImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null)
        loop.condition = statement.condition?.let { condition(it) } ?: scope.irBoolean(true)
        val trampoline = if (hasOwnContinue(statement.statement)) {
            IrDoWhileLoopImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null).apply {
                condition = scope.irBoolean(false)
            }
        } else {
            null
        }
        loops.addLast(LoopFrame(loop, trampoline ?: loop))
        val body = statementExpression(statement.statement)
        loops.removeLast()
        val inner = mutableListOf<IrStatement>()
        if (trampoline != null) {
            trampoline.body = body
            inner.add(trampoline)
        } else {
            inner.add(body)
        }
        statement.incrementor?.let { inner.add(lowerExpression(it)) }
        loop.body = IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, inner)
        outer.add(loop)
        scopes.removeLast()
        return IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, outer)
    }

    /** Does [node] contain a `continue` bound to the loop it is the body of? */
    private fun hasOwnContinue(node: Node): Boolean {
        var found = false
        fun visit(current: Node) {
            if (found) return
            when (current) {
                is ContinueStatement -> found = true
                // A nested loop captures its own `continue`s.
                is WhileStatement, is ForStatement,
                is com.xemantic.typescript.compiler.DoStatement,
                is com.xemantic.typescript.compiler.ForInStatement,
                is com.xemantic.typescript.compiler.ForOfStatement -> {}
                else -> forEachChild(current) { visit(it) }
            }
        }
        visit(node)
        return found
    }

    private fun statementExpression(statement: Statement): IrExpression {
        val inner = mutableListOf<IrStatement>()
        scopes.addLast(HashMap())
        lowerStatement(statement, inner)
        scopes.removeLast()
        return IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, inner)
    }

    // ---- expressions -------------------------------------------------------

    private fun lowerExpression(node: Expression): IrExpression = when (node) {
        is NumericLiteralNode -> scope.irDouble(numericValue(node))
        is StringLiteralNode -> scope.irString(node.text)
        is ParenthesizedExpression -> lowerExpression(node.expression)
        is Identifier -> lowerIdentifier(node)
        is BinaryExpression -> lowerBinary(node)
        is PrefixUnaryExpression -> lowerPrefix(node)
        is ConditionalExpression -> lowerConditional(node)
        is CallExpression -> lowerCall(node)
        is NewExpression -> lowerNew(node)
        is PropertyAccessExpression -> lowerPropertyRead(node)
        is ArrayLiteralExpression -> lowerArrayLiteral(node)
        is ArrowFunction -> lowerFunctionValue(node, node.parameters, node.body)
        is FunctionExpression -> lowerFunctionValue(
            node,
            node.parameters,
            node.body ?: refuse(tsFile, node, "a function expression with no body")
        )
        is ElementAccessExpression -> lowerElementRead(node)
        // `x!` and `x as T` are ERASURES: both leave the value alone and change
        // only what the checker believes about it, and the checker has already
        // believed it — so the coercion to this node's OWN recorded type is the
        // whole of their runtime meaning.
        is NonNullExpression -> coerceToRecordedType(node, lowerExpression(node.expression))
        is AsExpression -> coerceToRecordedType(node, lowerExpression(node.expression))
        is TypeOfExpression -> refuse(
            tsFile, node,
            "`typeof` is lowered only as part of a `typeof x === \"…\"` comparison"
        )
        else -> refuse(tsFile, node, "cannot lower this expression")
    }

    /**
     * `true`, `false`, `null`, `undefined` and `this` are all parsed as
     * [Identifier]s by this parser, so they are decided here rather than by node
     * kind — a fact that is easy to miss and produces "unresolved name `true`".
     */
    private fun lowerIdentifier(node: Identifier): IrExpression = when (node.text) {
        "true" -> scope.irBoolean(true)
        "false" -> scope.irBoolean(false)
        "null", "undefined" -> scope.irNull()
        "this" -> frame.thisReceiver?.let { scope.irGet(it) }
            ?: refuse(tsFile, node, "`this` outside a class member")
        else -> lookup(node.text)?.let { scope.irGet(it) }
            ?: refuse(tsFile, node, "cannot lower the reference '${node.text}'")
    }

    private fun lookup(name: String): IrValueDeclaration? {
        for (index in scopes.indices.reversed()) {
            scopes[index][name]?.let { return it }
        }
        return null
    }

    private fun lowerPrefix(node: PrefixUnaryExpression): IrExpression = when (node.operator) {
        SyntaxKind.Minus -> {
            val operand = coerce(node.operand, lowerExpression(node.operand), types.double)
            scope.irCall(intrinsics.doubleOperator("unaryMinus", types.double)).apply {
                arguments[0] = operand
            }
        }
        SyntaxKind.Plus -> scope.irCall(intrinsics.jsToNumber).apply {
            arguments[0] = coerce(node.operand, lowerExpression(node.operand), types.anyNullable)
        }
        SyntaxKind.Exclamation -> not(condition(node.operand))
        else -> refuse(tsFile, node, "cannot lower this unary operator")
    }

    private fun lowerConditional(node: ConditionalExpression): IrExpression {
        val type = erase(node, facts.typeOf(node)
            ?: refuse(tsFile, node, "the checker gave no type for this conditional"))
        return scope.irWhen(
            type,
            listOf(
                scope.irBranch(
                    condition(node.condition),
                    coerce(node.whenTrue, lowerExpression(node.whenTrue), type)
                ),
                scope.irElseBranch(
                    coerce(node.whenFalse, lowerExpression(node.whenFalse), type)
                )
            )
        )
    }

    // ---- binary ------------------------------------------------------------

    private fun lowerBinary(node: BinaryExpression): IrExpression {
        typeOfPattern(node)?.let { return it }
        return when (node.operator) {
            SyntaxKind.Equals -> lowerAssignment(node)
            SyntaxKind.Plus -> lowerAddition(node)
            SyntaxKind.Minus -> arithmetic(node, "minus")
            SyntaxKind.Asterisk -> arithmetic(node, "times")
            SyntaxKind.Slash -> arithmetic(node, "div")
            SyntaxKind.Percent -> arithmetic(node, "rem")
            SyntaxKind.LessThan -> comparison(node, irBuiltIns.lessFunByOperandType)
            SyntaxKind.LessThanEquals -> comparison(node, irBuiltIns.lessOrEqualFunByOperandType)
            SyntaxKind.GreaterThan -> comparison(node, irBuiltIns.greaterFunByOperandType)
            SyntaxKind.GreaterThanEquals ->
                comparison(node, irBuiltIns.greaterOrEqualFunByOperandType)
            SyntaxKind.EqualsEqualsEquals -> strictEquals(node, negated = false)
            SyntaxKind.ExclamationEqualsEquals -> strictEquals(node, negated = true)
            SyntaxKind.AmpersandAmpersand, SyntaxKind.BarBar -> shortCircuit(node)
            SyntaxKind.EqualsEquals, SyntaxKind.ExclamationEquals -> refuse(
                tsFile, node,
                "`==` / `!=` are out of the spike subset — their coercion is not modelled"
            )
            else -> refuse(tsFile, node, "cannot lower this binary operator")
        }
    }

    /**
     * `+`, decided by the ERASED operand types.
     *
     * §5 states the rule over the TypeScript types, and the erased ones are a
     * function of those — but they are also strictly more available: measured,
     * the checker answers `any` for `x + 1` where `x` is a narrowed union, and
     * for `this.value + by` where the receiver is `this`. Lowering the operands
     * first and reading their IR types back recovers the numeric case in both,
     * and where they genuinely disagree it falls to `jsAdd`, which is §5's own
     * third arm rather than a widening.
     */
    private fun lowerAddition(node: BinaryExpression): IrExpression {
        val left = lowerExpression(node.left)
        val right = lowerExpression(node.right)
        return when {
            left.type == types.double && right.type == types.double ->
                scope.irCall(intrinsics.doubleOperator("plus", types.double)).apply {
                    arguments[0] = left
                    arguments[1] = right
                }
            left.type == types.string || right.type == types.string ->
                scope.irCall(intrinsics.stringPlus).apply {
                    arguments[0] = asString(node.left, left)
                    arguments[1] = asString(node.right, right)
                }
            else -> scope.irCall(intrinsics.jsAdd).apply {
                arguments[0] = coerce(node.left, left, types.anyNullable)
                arguments[1] = coerce(node.right, right, types.anyNullable)
            }
        }
    }

    /**
     * A value in a string-concatenation position.
     *
     * Kotlin's `String.plus(Any?)` would call `toString()`, and `Double.toString`
     * is not `Number::toString` — it prints `1.0` where JavaScript prints `1`.
     * So everything that is not already a `String` goes through the runtime.
     */
    private fun asString(node: Expression, value: IrExpression): IrExpression =
        if (value.type == types.string) value
        else scope.irCall(intrinsics.jsToString).apply {
            arguments[0] = coerce(node, value, types.anyNullable)
        }

    private fun arithmetic(node: BinaryExpression, operator: String): IrExpression =
        scope.irCall(intrinsics.doubleOperator(operator, types.double)).apply {
            arguments[0] = coerce(node.left, lowerExpression(node.left), types.double)
            arguments[1] = coerce(node.right, lowerExpression(node.right), types.double)
        }

    private fun comparison(
        node: BinaryExpression,
        table: Map<org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol,
            org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol>
    ): IrExpression {
        val operator = table[irBuiltIns.doubleClass]
            ?: refuse(tsFile, node, "no `Double` comparison intrinsic on this Kotlin version")
        return scope.irCall(operator).apply {
            arguments[0] = coerce(node.left, lowerExpression(node.left), types.double)
            arguments[1] = coerce(node.right, lowerExpression(node.right), types.double)
        }
    }

    /**
     * `===`, which is NOT Kotlin's `===`: JavaScript compares strings and
     * numbers by VALUE and holds `NaN !== NaN`, so the runtime owns it.
     */
    private fun strictEquals(node: BinaryExpression, negated: Boolean): IrExpression {
        val call = scope.irCall(intrinsics.jsStrictEquals).apply {
            arguments[0] = coerce(node.left, lowerExpression(node.left), types.anyNullable)
            arguments[1] = coerce(node.right, lowerExpression(node.right), types.anyNullable)
        }
        return if (negated) not(call) else call
    }

    /**
     * `&&` and `||` short-circuit, and their JavaScript result is an OPERAND
     * rather than a boolean — so the branches keep their own values and the
     * result is typed at the erasure of the whole expression.
     */
    private fun shortCircuit(node: BinaryExpression): IrExpression {
        val type = erase(node, facts.typeOf(node)
            ?: refuse(tsFile, node, "the checker gave no type for this expression"))
        val left = lowerExpression(node.left)
        val right = coerce(node.right, lowerExpression(node.right), type)
        val leftValue = coerce(node.left, left, type)
        val test = truthy(leftValue)
        return if (node.operator == SyntaxKind.AmpersandAmpersand) {
            scope.irWhen(
                type,
                listOf(scope.irBranch(test, right), scope.irElseBranch(coerce(node.left, left, type)))
            )
        } else {
            scope.irWhen(
                type,
                listOf(scope.irBranch(test, coerce(node.left, left, type)), scope.irElseBranch(right))
            )
        }
    }

    private fun lowerAssignment(node: BinaryExpression): IrExpression {
        return when (val target = node.left) {
            is Identifier -> {
                val variable = lookup(target.text)
                    ?: refuse(tsFile, target, "cannot assign to '${target.text}'")
                scope.irSet(
                    variable,
                    coerce(node.right, lowerExpression(node.right), variable.type)
                )
            }
            is PropertyAccessExpression -> {
                val field = resolveField(target)
                IrSetFieldImpl(
                    UNDEFINED,
                    UNDEFINED,
                    field.symbol,
                    receiverOf(target),
                    coerce(node.right, lowerExpression(node.right), field.type),
                    irBuiltIns.unitType,
                    null,
                    null
                )
            }
            is ElementAccessExpression -> {
                val owner = runtimeClassOf(target.expression)
                    ?: refuse(tsFile, target, elementAccessRefusal(target))
                val set = intrinsics.runtimeMember(owner, "set", 2)
                    ?: refuse(tsFile, target, "this backend cannot write an element of this receiver")
                scope.irCall(set).apply {
                    arguments[0] = lowerExpression(target.expression)
                    arguments[1] = elementIndex(target)
                    arguments[2] = coerce(
                        node.right, lowerExpression(node.right), types.anyNullable
                    )
                }
            }
            else -> refuse(tsFile, node, "cannot lower this assignment target")
        }
    }

    /**
     * `typeof x === "…"`, recognized as a PATTERN on the whole comparison and
     * folded to a type test — before generic `===` lowering, because after it
     * there is no `typeof` left to see (design doc §3.2).
     */
    private fun typeOfPattern(node: BinaryExpression): IrExpression? {
        val negated = when (node.operator) {
            SyntaxKind.EqualsEqualsEquals -> false
            SyntaxKind.ExclamationEqualsEquals -> true
            else -> return null
        }
        val (typeOf, literal) = when {
            node.left is TypeOfExpression && node.right is StringLiteralNode ->
                node.left as TypeOfExpression to node.right as StringLiteralNode
            node.right is TypeOfExpression && node.left is StringLiteralNode ->
                node.right as TypeOfExpression to node.left as StringLiteralNode
            else -> return null
        }
        val operand = lowerExpression(typeOf.expression)
        val test = when (literal.text) {
            "string" -> scope.irIs(operand, types.string)
            "number" -> scope.irIs(operand, types.double)
            "boolean" -> scope.irIs(operand, types.boolean)
            "undefined" -> scope.irEqualsNull(coerce(typeOf.expression, operand, types.anyNullable))
            else -> refuse(
                tsFile, node,
                "`typeof x === \"${literal.text}\"` is out of the spike subset"
            )
        }
        return if (negated) not(test) else test
    }

    // ---- calls, construction, members --------------------------------------

    private fun lowerCall(node: CallExpression): IrExpression {
        val fact = facts.callAt(node)
            ?: refuse(tsFile, node, "the checker recorded no call at this position")
        val callee = node.expression
        val declaration = fact.signature?.declaration
        functions[declaration]?.let { target ->
            return scope.irCall(target.symbol).apply {
                bindArguments(node, target.parameters, offset = 0)
            }
        }
        methods[declaration]?.let { target ->
            val receiver = callee as? PropertyAccessExpression
                ?: refuse(tsFile, node, "a method call needs a receiver")
            return scope.irCall(target.symbol).apply {
                arguments[0] = receiverOf(receiver)
                bindArguments(node, target.parameters, offset = 1)
            }
        }
        if (callee is PropertyAccessExpression) {
            runtimeClassOf(callee.expression)?.let { owner ->
                return lowerRuntimeMemberCall(node, callee, owner)
            }
            intrinsics.libraryMember(fact.receiverTypeText, fact.memberName)?.let { intrinsic ->
                return lowerIntrinsicCall(node, intrinsic)
            }
        }
        // LAST, and never for a property access. A callee is a function VALUE
        // only once it has resolved to no declaration and to no member — and a
        // MEMBER is of function type too, so a `x.y(…)` reaching here is an
        // unknown library member (`Math.max`), which must keep saying so
        // rather than be lowered as a call of the member's own value.
        if (callee !is PropertyAccessExpression) {
            facts.typeOf(callee)?.let { types.map(it) }?.let { types.functionArity(it) }
                ?.let { arity -> return lowerFunctionValueCall(node, arity) }
        }
        refuse(
            tsFile, node,
            "cannot lower this call — it resolves to no generated declaration " +
                "and to no known library member" +
                (fact.receiverTypeText?.let { " ($it.${fact.memberName})" } ?: "")
        )
    }

    /**
     * A call to the runtime.
     *
     * The only shape in the spike subset is `console.log`, which is variadic —
     * so its arguments become one `vararg` of `Any?`, each coerced but not
     * boxed: the backend boxes given an honest IR type.
     */
    private fun lowerIntrinsicCall(
        node: CallExpression,
        intrinsic: org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
    ): IrExpression {
        val parameters = intrinsic.owner.parameters
        val variadic = parameters.singleOrNull()?.varargElementType != null
        return scope.irCall(intrinsic).apply {
            if (variadic) {
                arguments[0] = scope.irVararg(
                    irBuiltIns.anyNType,
                    node.arguments.map { coerce(it, lowerExpression(it), types.anyNullable) }
                )
            } else {
                bindArguments(node, parameters, offset = 0)
            }
        }
    }

    private fun lowerNew(node: NewExpression): IrExpression {
        val signature = facts.constructionAt(node)
            ?: refuse(tsFile, node, "the checker resolved no constructor for this `new`")
        val owner = (signature.declaration as? Constructor)?.parent as? ClassDeclaration
            ?: (signature.declaration as? ClassDeclaration)
            ?: refuse(tsFile, node, "cannot lower `new` on a non-class")
        val constructor = constructorsByDeclaration[owner]
            ?: refuse(tsFile, node, "cannot lower `new` on a class this backend did not generate")
        val irClass = classes.getValue(owner)
        return IrConstructorCallImpl(
            UNDEFINED,
            UNDEFINED,
            irClass.defaultType,
            constructor.symbol,
            typeArgumentsCount = 0,
            constructorTypeArgumentsCount = 0,
        ).apply {
            val arguments = node.arguments ?: emptyList()
            constructor.parameters.forEachIndexed { index, parameter ->
                val argument = arguments.getOrNull(index)
                    ?: refuse(tsFile, node, "too few arguments for this constructor")
                this.arguments[index] =
                    coerce(argument, lowerExpression(argument), parameter.type)
            }
        }
    }

    private fun lowerPropertyRead(node: PropertyAccessExpression): IrExpression {
        runtimeClassOf(node.expression)?.let { owner ->
            val getter = intrinsics.runtimePropertyGetter(owner, node.name.text)
                ?: refuse(
                    tsFile, node,
                    "'${node.name.text}' is not a property this backend gives a " +
                        "'${owner.owner.name.asString()}'"
                )
            return scope.irCall(getter).apply {
                arguments[0] = lowerExpression(node.expression)
            }
        }
        val field = resolveField(node)
        return IrGetFieldImpl(
            UNDEFINED, UNDEFINED, field.symbol, field.type, receiverOf(node), null, null
        )
    }

    // ---- function values ---------------------------------------------------

    /**
     * An arrow or a function expression: a Kotlin lambda of the same arity.
     *
     * Three things about it are decisions rather than mechanics.
     *
     * **The parameters and the result are `Any?`** (`ErasedTypes.function`), so
     * any function value fits any position of the same arity — which is what
     * TypeScript's bivariant function assignability needs and what the JVM's
     * variance would otherwise refuse.
     *
     * **`this` is inherited from the enclosing frame**, which IS arrow
     * semantics: an arrow does not bind its own `this`, and neither does the
     * lambda, because the frame it borrows is the enclosing method's.
     *
     * **Capture costs nothing to arrange**: the lowering's scope stack is not
     * cleared at a function boundary, so an outer local is simply in scope and
     * the reference to it is an ordinary `irGet` of the outer declaration.
     * Kotlin's own closure lowering then does the capturing.
     */
    private fun lowerFunctionValue(
        node: Expression,
        parameters: List<Parameter>,
        body: Node
    ): IrExpression {
        parameters.forEach { parameter ->
            if (parameter.name !is Identifier) {
                refuse(tsFile, parameter, "a destructuring parameter is out of the spike subset")
            }
            if (parameter.dotDotDotToken) {
                refuse(tsFile, parameter, "a rest parameter is out of the spike subset")
            }
        }
        val lambda = builder.irFactory.buildFun {
            name = SpecialNames.ANONYMOUS
            returnType = types.anyNullable
            visibility = DescriptorVisibilities.LOCAL
            modality = Modality.FINAL
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
        }
        lambda.parent = frame.irFunction
        parameters.forEach { parameter ->
            lambda.addValueParameter(
                Name.identifier(kotlinName((parameter.name as Identifier).text)),
                types.anyNullable,
                builder.generatedOrigin
            )
        }
        inFunction(lambda, types.anyNullable, frame.thisReceiver, frame.ownerClass) {
            lambda.body = lambdaBody(lambda, parameters, body)
        }
        return IrFunctionExpressionImpl(
            UNDEFINED,
            UNDEFINED,
            types.function(parameters.size),
            lambda,
            IrStatementOrigin.LAMBDA
        )
    }

    /**
     * A lambda's body, block-shaped or expression-shaped.
     *
     * A block body gets a trailing `return null` unless it already ends in a
     * `return`: the lambda's erased result type is `Any?`, and a JVM method
     * with a value result may not fall off its end — the failure would be
     * invalid bytecode rather than a wrong answer, i.e. far from here.
     */
    private fun lambdaBody(
        lambda: IrSimpleFunction,
        parameters: List<Parameter>,
        body: Node
    ): org.jetbrains.kotlin.ir.expressions.IrBlockBody {
        if (body is Block) {
            val lowered = mutableListOf<IrStatement>()
            scopes.addLast(HashMap())
            bindParameters(lambda, parameters)
            body.statements.forEach { lowerStatement(it, lowered) }
            scopes.removeLast()
            if (body.statements.lastOrNull() !is ReturnStatement) {
                lowered.add(scope.irReturn(scope.irNull()))
            }
            return blockBodyOf(lowered)
        }
        val expression = body as? Expression
            ?: refuse(tsFile, body, "cannot lower this function body")
        scopes.addLast(HashMap())
        bindParameters(lambda, parameters)
        val value = coerce(expression, lowerExpression(expression), types.anyNullable)
        scopes.removeLast()
        return blockBodyOf(listOf(scope.irReturn(value)))
    }

    /**
     * A call of a function VALUE — `handler(event)`, `callbacks[0](x)`.
     *
     * Reached only after the call resolved to no generated declaration, which
     * is the honest order: a call of a declared function is a direct call and
     * must stay one.
     */
    private fun lowerFunctionValueCall(node: CallExpression, arity: Int): IrExpression {
        if (node.arguments.size != arity) {
            refuse(
                tsFile, node,
                "a function value of arity $arity called with ${node.arguments.size} argument(s)"
            )
        }
        val invoke = intrinsics.invoke(arity)
        return scope.irCall(invoke, types.anyNullable).apply {
            arguments[0] = coerce(
                node.expression, lowerExpression(node.expression), types.function(arity)
            )
            node.arguments.forEachIndexed { index, argument ->
                arguments[index + 1] =
                    coerce(argument, lowerExpression(argument), types.anyNullable)
            }
        }
    }

    // ---- arrays and other runtime-backed receivers -------------------------

    /**
     * `[a, b, c]` — a call of the runtime's `jsArrayOf`.
     *
     * Every element is coerced to `Any?` and NOT to the array's element type:
     * the erasure keeps no element type (`ErasedTypes.isArrayLike`), so an
     * array of `number` and an array of `Cover` are one JVM shape, and the cast
     * back is paid where an element is READ — which is the same trade union
     * erasure makes, in the same place, for the same reason.
     */
    private fun lowerArrayLiteral(node: ArrayLiteralExpression): IrExpression {
        val elements = node.elements.map { element ->
            if (element is SpreadElement) {
                refuse(tsFile, element, "a spread element is out of the spike subset")
            }
            coerce(element, lowerExpression(element), types.anyNullable)
        }
        return scope.irCall(intrinsics.jsArrayOf).apply {
            arguments[0] = scope.irVararg(irBuiltIns.anyNType, elements)
        }
    }

    /** `a[i]` — `JsArray.get`, which answers `undefined` out of range. */
    private fun lowerElementRead(node: ElementAccessExpression): IrExpression {
        if (node.questionDotToken) {
            refuse(tsFile, node, "optional element access `?.[]` is out of the spike subset")
        }
        val owner = runtimeClassOf(node.expression)
            ?: refuse(tsFile, node, elementAccessRefusal(node))
        val get = intrinsics.runtimeMember(owner, "get", 1)
            ?: refuse(tsFile, node, "this backend cannot read an element of this receiver")
        return scope.irCall(get).apply {
            arguments[0] = lowerExpression(node.expression)
            arguments[1] = elementIndex(node)
        }
    }

    /**
     * An index, as the `Double` a JavaScript index IS.
     *
     * Not narrowed to `Int` here: `a[i]` with a fractional or huge `i` is legal
     * TypeScript that reads a hole, so the narrowing is the runtime's decision
     * and it answers `undefined` rather than throwing.
     */
    private fun elementIndex(node: ElementAccessExpression): IrExpression = coerce(
        node.argumentExpression,
        lowerExpression(node.argumentExpression),
        types.double
    )

    private fun elementAccessRefusal(node: ElementAccessExpression): String {
        val type = facts.typeOf(node.expression)
        return "element access on '${type?.let { facts.render(it) } ?: "an untyped receiver"}'" +
            " is out of the spike subset"
    }

    /**
     * A call whose receiver is a runtime-backed value: `a.push(x)`, `a.slice()`.
     *
     * Selected by the receiver's ERASED type, never by the checker's rendering
     * of its TypeScript one — that rendering carries the element type
     * (`string[]`, `Cover[]`), so a table keyed by it would need one row per
     * element type the program happens to contain.
     */
    private fun lowerRuntimeMemberCall(
        node: CallExpression,
        callee: PropertyAccessExpression,
        owner: IrClassSymbol,
    ): IrExpression {
        val target = intrinsics.runtimeMember(owner, callee.name.text, node.arguments.size)
            ?: refuse(
                tsFile, node,
                "'${callee.name.text}' with ${node.arguments.size} argument(s) is not a " +
                    "member this backend gives a '${owner.owner.name.asString()}'"
            )
        val regular = target.owner.parameters.filter { it.kind == IrParameterKind.Regular }
        return scope.irCall(target).apply {
            arguments[0] = lowerExpression(callee.expression)
            node.arguments.forEachIndexed { index, argument ->
                arguments[index + 1] =
                    coerce(argument, lowerExpression(argument), regular[index].type)
            }
        }
    }

    /** The runtime class this expression's checked type erases to, or null. */
    private fun runtimeClassOf(receiver: Expression): IrClassSymbol? {
        val type = facts.typeOf(receiver) ?: return null
        val erased = types.map(type) ?: return null
        return intrinsics.runtimeClassOf(erased)
    }

    /** The value with the type the checker recorded for THIS node — `!` and `as`. */
    private fun coerceToRecordedType(node: Expression, value: IrExpression): IrExpression {
        val type = facts.typeOf(node)
            ?: refuse(tsFile, node, "the checker gave no type for this assertion")
        return coerce(node, value, erase(node, type))
    }

    /**
     * Which generated field a property access names.
     *
     * Resolution goes through the OWNER CLASS rather than through the member
     * symbol at the access, because measured, `this` types as `any` here — so
     * `this.value` resolves to no member at all. The owner is `this`'s enclosing
     * class where the receiver is `this`, and the receiver's own erased type
     * otherwise; either way the field is then named, not guessed.
     */
    private fun resolveField(node: PropertyAccessExpression): IrField {
        val owner = ownerClassOf(node)
        val declaration = owner.members.filterIsInstance<PropertyDeclaration>()
            .firstOrNull { (it.name as? Identifier)?.text == node.name.text }
            ?: refuse(
                tsFile, node,
                "class '${owner.name?.text}' declares no property '${node.name.text}'"
            )
        return fields.getValue(declaration)
    }

    private fun ownerClassOf(node: PropertyAccessExpression): ClassDeclaration {
        val receiver = node.expression
        if (receiver is Identifier && receiver.text == "this") {
            return frame.ownerClass
                ?: refuse(tsFile, node, "`this` outside a class member")
        }
        val receiverType = facts.typeOf(receiver)
            ?: refuse(tsFile, node, "the checker gave no type for this receiver")
        val symbol = (receiverType as? Type.Object)?.symbol
        val declaration = symbol?.valueDeclaration ?: symbol?.declarations?.firstOrNull()
        return declaration as? ClassDeclaration
            ?: refuse(
                tsFile, node,
                "property access on '${facts.render(receiverType)}' is out of the spike subset"
            )
    }

    private fun receiverOf(node: PropertyAccessExpression): IrExpression {
        val receiver = node.expression
        if (receiver is Identifier && receiver.text == "this") {
            return frame.thisReceiver?.let { scope.irGet(it) }
                ?: refuse(tsFile, node, "`this` outside a class member")
        }
        return lowerExpression(receiver)
    }

    private fun org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression.bindArguments(
        node: CallExpression,
        parameters: List<IrValueParameter>,
        offset: Int
    ) {
        val regular = parameters.filter {
            it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular
        }
        if (node.arguments.size != regular.size) {
            refuse(
                tsFile, node,
                "expected ${regular.size} argument(s) but found ${node.arguments.size}"
            )
        }
        node.arguments.forEachIndexed { index, argument ->
            arguments[offset + index] =
                coerce(argument, lowerExpression(argument), regular[index].type)
        }
    }

    // ---- coercion, truthiness, names ---------------------------------------

    /**
     * Puts [value] into the shape [target] wants, and refuses when it cannot.
     *
     * The one place the decision is taken (§6). Everything else in this class
     * calls it; nothing else inserts a cast.
     */
    private fun coerce(node: Node, value: IrExpression, target: IrType): IrExpression =
        when (coercionOf(value, target, irBuiltIns)) {
            Coercion.NONE -> value
            Coercion.CAST -> scope.irAs(value, target)
            Coercion.IMPOSSIBLE -> refuse(
                tsFile, node,
                "cannot coerce ${value.type.render()} to ${target.render()}"
            )
        }

    /**
     * A value in a condition position.
     *
     * `jsTruthy` UNLESS the static type is already `boolean` — the one
     * optimization §6 asks for immediately, because conditions are everywhere
     * and the checker gives the answer for free.
     */
    private fun condition(node: Expression): IrExpression = truthy(lowerExpression(node))

    private fun truthy(value: IrExpression): IrExpression =
        if (value.type == types.boolean) value
        else scope.irCall(intrinsics.jsTruthy).apply {
            arguments[0] = if (value.type == types.anyNullable) value
            else scope.irAs(value, types.anyNullable)
        }

    private fun not(value: IrExpression): IrExpression =
        scope.irCall(irBuiltIns.booleanNotSymbol).apply { arguments[0] = value }

    private fun erase(node: Node, type: Type): IrType = types.map(type)
        ?: refuse(tsFile, node, "cannot map the type '${facts.render(type)}'")

    /**
     * A TypeScript name as a Kotlin one.
     *
     * A pure function applied at declaration AND reference sites through this
     * single helper: two copies of a name mangler is a defect waiting to happen.
     */
    private fun kotlinName(name: String): String =
        if (name in KOTLIN_HARD_KEYWORDS) "$name$" else name

    /** The value of a numeric literal, in every base TypeScript spells one in. */
    private fun numericValue(node: NumericLiteralNode): Double {
        val text = node.text.replace("_", "")
        return when {
            text.startsWith("0x") || text.startsWith("0X") ->
                text.substring(2).toLong(16).toDouble()
            text.startsWith("0o") || text.startsWith("0O") ->
                text.substring(2).toLong(8).toDouble()
            text.startsWith("0b") || text.startsWith("0B") ->
                text.substring(2).toLong(2).toDouble()
            else -> text.toDoubleOrNull()
                ?: refuse(tsFile, node, "cannot read the numeric literal '${node.text}'")
        }
    }


    private companion object {

        /** IR offsets this backend has no source position for. */
        const val UNDEFINED = -1

        val KOTLIN_HARD_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
            "in", "interface", "is", "null", "object", "package", "return", "super", "this",
            "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
        )

    }

}
