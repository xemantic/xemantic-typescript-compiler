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
import com.xemantic.typescript.compiler.ArrayBindingPattern
import com.xemantic.typescript.compiler.BindingElement
import com.xemantic.typescript.compiler.ObjectBindingPattern
import com.xemantic.typescript.compiler.AsExpression
import com.xemantic.typescript.compiler.BigIntLiteralNode
import com.xemantic.typescript.compiler.BinaryExpression
import com.xemantic.typescript.compiler.Block
import com.xemantic.typescript.compiler.BreakStatement
import com.xemantic.typescript.compiler.CallExpression
import com.xemantic.typescript.compiler.ClassDeclaration
import com.xemantic.typescript.compiler.ConditionalExpression
import com.xemantic.typescript.compiler.Constructor
import com.xemantic.typescript.compiler.ContinueStatement
import com.xemantic.typescript.compiler.ElementAccessExpression
import com.xemantic.typescript.compiler.EnumDeclaration
import com.xemantic.typescript.compiler.EnumMember
import com.xemantic.typescript.compiler.ExportDeclaration
import com.xemantic.typescript.compiler.EmptyStatement
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.ExpressionStatement
import com.xemantic.typescript.compiler.ForStatement
import com.xemantic.typescript.compiler.FunctionExpression
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.GetAccessor
import com.xemantic.typescript.compiler.SetAccessor
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.IfStatement
import com.xemantic.typescript.compiler.ImportDeclaration
import com.xemantic.typescript.compiler.ImportEqualsDeclaration
import com.xemantic.typescript.compiler.InterfaceDeclaration
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.ModifierFlag
import com.xemantic.typescript.compiler.NewExpression
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NonNullExpression
import com.xemantic.typescript.compiler.NoSubstitutionTemplateLiteralNode
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.NotEmittedStatement
import com.xemantic.typescript.compiler.NumericLiteralNode
import com.xemantic.typescript.compiler.ObjectLiteralExpression
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.ParenthesizedExpression
import com.xemantic.typescript.compiler.PostfixUnaryExpression
import com.xemantic.typescript.compiler.PrefixUnaryExpression
import com.xemantic.typescript.compiler.PropertyAccessExpression
import com.xemantic.typescript.compiler.PropertyAssignment
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.RegularExpressionLiteralNode
import com.xemantic.typescript.compiler.ReturnStatement
import com.xemantic.typescript.compiler.ShorthandPropertyAssignment
import com.xemantic.typescript.compiler.SpreadElement
import com.xemantic.typescript.compiler.Statement
import com.xemantic.typescript.compiler.StringLiteralNode
import com.xemantic.typescript.compiler.SyntaxKind
import com.xemantic.typescript.compiler.TemplateExpression
import com.xemantic.typescript.compiler.Type
import com.xemantic.typescript.compiler.TypeAliasDeclaration
import com.xemantic.typescript.compiler.TypeAssertionExpression
import com.xemantic.typescript.compiler.TypeFlags
import com.xemantic.typescript.compiler.TypeLiteral
import com.xemantic.typescript.compiler.TypeOfExpression
import com.xemantic.typescript.compiler.VariableStatement
import com.xemantic.typescript.compiler.CaseClause
import com.xemantic.typescript.compiler.DefaultClause
import com.xemantic.typescript.compiler.DoStatement
import com.xemantic.typescript.compiler.ForInStatement
import com.xemantic.typescript.compiler.ForOfStatement
import com.xemantic.typescript.compiler.SwitchStatement
import com.xemantic.typescript.compiler.ThrowStatement
import com.xemantic.typescript.compiler.TryStatement
import com.xemantic.typescript.compiler.VariableDeclaration
import com.xemantic.typescript.compiler.VariableDeclarationList
import com.xemantic.typescript.compiler.VoidExpression
import com.xemantic.typescript.compiler.WhileStatement
import com.xemantic.typescript.compiler.forEachChild
import com.xemantic.typescript.compiler.kir.emit.IrProgramBuilder
import com.xemantic.typescript.compiler.kir.emit.irDouble
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.kir.front.CheckedFacts
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
import org.jetbrains.kotlin.ir.builders.declarations.buildField
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
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irReturnUnit
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.builders.irIfThen
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
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBreakImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDelegatingConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrContinueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDoWhileLoopImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhileLoopImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
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
    private val facts: CheckedFacts,
    private val tsFile: SourceFile,
    private val tables: KirProgramTables,
    packageName: String,
    kotlinFileName: String,
) {

    private val irBuiltIns = builder.irBuiltIns
    private val irFile: IrFile = builder.file(packageName, kotlinFileName)
    private val intrinsics = KirIntrinsics(builder, irFile)

    // ---- the declare pass's tables, shared with every OTHER file -----------
    // A cross-file call resolves to a declaration in another tree, so these
    // cannot be per-file — see [KirProgramTables].

    private val functions get() = tables.functions
    /** One generated class per distinct object-literal name LIST — [shapeClassFor]. */
    private val shapeClasses = LinkedHashMap<List<String>, ShapeClass>()

    /** What makes a generated shape class's name unique across FILES. */
    private val shapeFilePrefix: String =
        kotlinName(tsFile.fileName.substringAfterLast('/').substringBeforeLast('.'))

    private val classes get() = tables.classes
    private val methods get() = tables.methods
    private val constructorsByDeclaration get() = tables.constructorsByDeclaration
    private val fields get() = tables.fields
    private val optionalParameters get() = tables.optionalParameters
    private val restParameters get() = tables.restParameters

    private val types = ErasedTypes(
        irBuiltIns,
        classForDeclaration = { declaration ->
            (declaration as? ClassDeclaration)?.let { classes[it] }
        },
        jsArrayType = { intrinsics.jsArrayType },
        jsObjectType = { intrinsics.jsObjectType },
        libraryType = { name -> intrinsics.libraryClass(name)?.owner?.defaultType },
        isOwnStructuralDeclaration = ::isOwnStructuralDeclaration,
        enumErasure = ::enumErasure,
        bigIntegerType = { intrinsics.bigIntegerType },
    )

    /**
     * The JVM type an enum's VALUES have, for the enum itself or for a member.
     *
     * A member's declaration is an `EnumMember` whose parent is the enum, so
     * both arrive here and both answer the same thing — which is right: with no
     * runtime object, `Type` and `Type.DOTTED` are the same JVM type.
     */
    private fun enumErasure(declaration: Node): IrType? {
        val enum = declaration as? EnumDeclaration
            ?: ((declaration as? EnumMember)?.parent as? EnumDeclaration)
            ?: return null
        val values = tables.enumMembers[enum]?.values ?: return null
        if (values.isEmpty()) return null
        return when {
            values.all { it is Double } -> types.double
            values.all { it is String } -> types.string
            else -> null
        }
    }

    /**
     * Is this the declaration of a structural type THIS PROGRAM wrote?
     *
     * An `interface`, a `type` alias and a type literal of the program's own
     * are property bags; the identically-shaped declarations in a lib `.d.ts`
     * are not, and telling them apart is what stops `Map` from erasing to an
     * empty bag whose every member reads `undefined`. The population is the
     * whole program rather than this one file, because an interface imported
     * from a sibling module is as much ours as one declared here.
     */
    private fun isOwnStructuralDeclaration(declaration: Node): Boolean {
        val structural = declaration is InterfaceDeclaration ||
            declaration is TypeAliasDeclaration ||
            declaration is TypeLiteral ||
            declaration is ObjectLiteralExpression
        return structural && tables.isProgramNode(declaration)
    }

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
        /** The enclosing frame, so a closure can see an outer function's `var`. */
        val parent: FunctionFrame?,
    ) {

        /**
         * This function's `var` bindings, by name — the FUNCTION scope.
         *
         * `var` is scoped to the function and not to the block, so its binding
         * cannot live in [scopes], which is pushed and popped per block: a
         * `var` declared inside an `if` is still readable after it. It is
         * consulted by [lookup] only after the block chain has missed, which
         * is what lets an inner `let` of the same name shadow it.
         */
        val hoisted: MutableMap<String, IrVariable> = HashMap()

        /**
         * The same variables as DECLARATIONS, to be emitted at the top of the
         * body — which is where JavaScript hoists them, and why reading one
         * before its assignment yields `undefined` rather than failing.
         */
        val hoistedDeclarations: MutableList<IrStatement> = mutableListOf()

    }

    private val frame: FunctionFrame
        get() = current ?: error("no function frame — the lowering is out of order")

    private val scope: IrBuilderWithScope get() = frame.scope

    /**
     * Pass 1 for this file. Every file's declare pass runs before ANY file's
     * define pass, which is what makes a cross-file reference resolvable in
     * either direction — module dependency order is then irrelevant, exactly as
     * declaration order within a file is.
     */
    /**
     * Pass 0: the class SHELLS, program-wide, before any member is declared.
     *
     * `class B extends A` needs `A`'s `IrClass` to exist when `B`'s supertype is
     * set, and TypeScript puts no ordering requirement on the two — nor on which
     * FILE each lives in. Creating every shell first makes the order irrelevant,
     * which is the same reason the declare/define split exists one level up.
     */
    fun declareShells() {
        tsFile.statements.forEach { statement ->
            if (statement is ClassDeclaration) declareClassShell(statement)
        }
    }

    fun declareAll() {
        tsFile.statements.forEach { declare(it) }
    }

    /**
     * Pass 1b: wire each method to the one it OVERRIDES.
     *
     * Its own phase because a base class may be declared LATER than the class
     * extending it — in this file or in another — so the base's methods do not
     * exist while the derived class's are being declared. An override that goes
     * unwired is not a compile error: the JVM simply sees two unrelated methods,
     * and a base-typed receiver keeps calling the base's.
     */
    fun linkOverrides() {
        tsFile.statements.filterIsInstance<ClassDeclaration>().forEach { declaration ->
            declaration.members.filterIsInstance<MethodDeclaration>().forEach { member ->
                if (ModifierFlag.Static in member.modifiers) return@forEach
                val name = (member.name as? Identifier)?.text ?: return@forEach
                val function = methods[member] ?: return@forEach
                overriddenIn(declaration, name, member.parameters.size)?.let {
                    function.overriddenSymbols = listOf(it.symbol)
                }
            }
        }
    }

    /** Pass 2 for this file: fills the bodies declared by pass 1. */
    fun defineAll() {
        tsFile.statements.forEach { define(it) }
    }

    /**
     * This file's MODULE INIT: its top-level statements, in source order.
     *
     * One per file, called by the program's `main` in dependency order — which
     * is what JavaScript does with a module body, and what makes
     * `export const X = compute()` mean anything. Null for a file with nothing
     * to run, so a program of pure declarations costs no call.
     */
    fun buildModuleInit(): IrSimpleFunction? {
        val statements = executableStatements()
        if (statements.isEmpty()) return null
        val init = builder.irFactory.buildFun {
            name = Name.identifier("moduleInit")
            returnType = irBuiltIns.unitType
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            origin = builder.generatedOrigin
        }
        init.parent = irFile
        irFile.declarations.add(init)
        builder.pluginContext.metadataDeclarationRegistrar
            .registerFunctionAsMetadataVisible(init)
        inFunction(init, irBuiltIns.unitType, null, null) {
            init.body = blockBody(init, emptyList(), statements)
        }
        return init
    }

    /**
     * The program's entry point: a `main` that runs every module's init.
     *
     * The order is the caller's ([KirProgramLowering] sorts it topologically);
     * `main` only calls them, so the entry file is not special beyond being
     * last.
     */
    fun buildEntryPoint(moduleInits: List<IrSimpleFunction>) {
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
            main.body = blockBodyOf(moduleInits.map { scope.irCall(it.symbol) })
        }
    }

    /**
     * Statements this file executes when it is loaded.
     *
     * Everything a pass has already emitted is excluded — the declarations —
     * and so is every module statement, which is ERASED: the checker has
     * already turned each imported name into the declaration it names, so an
     * `import` has nothing left to contribute at runtime.
     */
    fun executableStatements(): List<Statement> = tsFile.statements.filter { statement ->
        statement !is FunctionDeclaration && statement !is ClassDeclaration &&
            statement !is EnumDeclaration &&
            statement !is InterfaceDeclaration && statement !is TypeAliasDeclaration &&
            statement !is ImportDeclaration && statement !is ExportDeclaration &&
            statement !is ImportEqualsDeclaration
    }

    // =======================================================================
    // Pass 1 — declare
    // =======================================================================

    private fun declare(statement: Statement) {
        when (statement) {
            is EnumDeclaration -> declareEnum(statement)
            is VariableStatement -> declareModuleVariables(statement)
            is FunctionDeclaration -> declareFunction(statement)
            is ClassDeclaration -> declareClass(statement)
            // A type-only declaration contributes no runtime shape, so it is
            // erased rather than refused — exactly as tsc erases it.
            is InterfaceDeclaration, is TypeAliasDeclaration -> {}
            else -> {}
        }
    }

    /**
     * A MODULE-level `const`/`let`, as a static field on this file's facade.
     *
     * Static because a module's variables outlive the statement that assigned
     * them and are read from other files — `export const FOLD_QUOTED =
     * 'quoted'` is the shape every library is made of. The INITIALIZER is not
     * emitted here: it runs in this file's module init ([buildModuleInit]), in
     * source order among the other top-level statements, because that is when
     * JavaScript runs it and because it may call a function declared below it.
     */
    private fun declareModuleVariables(statement: VariableStatement) {
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
                ?: refuse(
                    tsFile, declaration,
                    "destructuring declarations are out of the spike subset"
                )
            val type = erase(
                declaration,
                variableType(declaration.name, declaration.initializer)
            )
            val field = builder.irFactory.buildField {
                this.name = Name.identifier(kotlinName(name.text))
                this.type = type
                visibility = DescriptorVisibilities.PUBLIC
                isStatic = true
                // NOT final, whatever TypeScript said. A JVM `static final`
                // field may only be assigned in `<clinit>`, and a module's
                // variables are assigned by its module INIT — so a `const`
                // marked final is an `IllegalAccessError` at run time, in a
                // program that compiled. TypeScript's `const` is a rule the
                // CHECKER has already enforced.
                isFinal = false
                origin = builder.generatedOrigin
            }
            field.parent = irFile
            irFile.declarations.add(field)
            val getter = moduleAccessor("${kotlinName(name.text)}\$get", type) { function ->
                function.body = builder.pluginContext.irFactory.createBlockBody(
                    UNDEFINED,
                    UNDEFINED,
                ).apply {
                    statements.add(
                        DeclarationIrBuilder(builder.pluginContext, function.symbol).irReturn(
                            IrGetFieldImpl(
                                UNDEFINED, UNDEFINED, field.symbol, type, null, null, null
                            )
                        )
                    )
                }
            }
            val setter = if (!mutable) null else moduleAccessor(
                "${kotlinName(name.text)}\$set",
                irBuiltIns.unitType,
                parameter = "value" to type,
            ) { function ->
                val parameter = function.parameters.first { it.kind == IrParameterKind.Regular }
                function.body = builder.pluginContext.irFactory.createBlockBody(
                    UNDEFINED,
                    UNDEFINED,
                ).apply {
                    statements.add(
                        IrSetFieldImpl(
                            UNDEFINED,
                            UNDEFINED,
                            field.symbol,
                            null,
                            DeclarationIrBuilder(builder.pluginContext, function.symbol)
                                .irGet(parameter),
                            irBuiltIns.unitType,
                            null,
                            null,
                        )
                    )
                }
            }
            tables.moduleVariables[declaration] =
                KirProgramTables.ModuleVariable(field, getter, setter, tsFile)
        }
    }

    /**
     * A top-level accessor function for a module variable.
     *
     * It exists because a top-level FIELD belongs to its file and Kotlin's IR
     * verifier refuses a read of one from another file — which is precisely
     * what an imported constant is. Within the declaring file the field is used
     * directly, so the accessor costs nothing there.
     */
    private fun moduleAccessor(
        name: String,
        returnType: IrType,
        parameter: Pair<String, IrType>? = null,
        body: (IrSimpleFunction) -> Unit
    ): IrSimpleFunction {
        val function = builder.irFactory.buildFun {
            this.name = Name.identifier(name)
            this.returnType = returnType
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            origin = builder.generatedOrigin
        }
        function.parent = irFile
        parameter?.let { (parameterName, parameterType) ->
            function.addValueParameter(parameterName, parameterType, builder.generatedOrigin)
        }
        body(function)
        irFile.declarations.add(function)
        builder.pluginContext.metadataDeclarationRegistrar
            .registerFunctionAsMetadataVisible(function)
        return function
    }

    /**
     * An `enum`, as its members' CONSTANTS and nothing else.
     *
     * There is no runtime object: a member access is replaced by its value,
     * which is exactly what a `const enum` is and what a plain one degrades to
     * for every use but reflecting over the enum itself. The values are read
     * off the AST rather than from the checker, because the rule is small and
     * syntactic — auto-increment from the last numeric value, or a literal
     * initializer — and a computed one is refused rather than guessed.
     */
    private fun declareEnum(declaration: EnumDeclaration) {
        val values = LinkedHashMap<String, Any>()
        var next = 0.0
        for (member in declaration.members) {
            val name = (member.name as? Identifier)?.text
                ?: (member.name as? StringLiteralNode)?.text
                ?: refuse(tsFile, member, "cannot lower a computed enum member name")
            when (val initializer = member.initializer) {
                null -> {
                    values[name] = next
                    next += 1.0
                }
                is NumericLiteralNode -> {
                    val value = numericValue(initializer)
                    values[name] = value
                    next = value + 1.0
                }
                is StringLiteralNode -> values[name] = initializer.text
                is PrefixUnaryExpression ->
                    if (initializer.operator == SyntaxKind.Minus &&
                        initializer.operand is NumericLiteralNode
                    ) {
                        val value = -numericValue(initializer.operand as NumericLiteralNode)
                        values[name] = value
                        next = value + 1.0
                    } else {
                        refuse(tsFile, member, "cannot lower this enum member initializer")
                    }
                else -> refuse(tsFile, member, "cannot lower this enum member initializer")
            }
        }
        tables.enumMembers[declaration] = values
    }

    /**
     * OVERLOAD SIGNATURES seen so far in this file, by name.
     *
     * A bodyless `function f(a: string): void` is one of several SIGNATURES of a
     * single implementation, and overload resolution may well pick it — so the
     * call site's declaration is a node with no body to lower. Each is mapped to
     * the implementation that follows, and the whole family then shares one IR
     * function, which is what TypeScript means by an overload.
     */
    private val pendingOverloads = HashMap<String, MutableList<FunctionDeclaration>>()

    /** The same, for a class's METHOD overloads. */
    private val pendingMethodOverloads = HashMap<String, MutableList<MethodDeclaration>>()

    private fun declareFunction(declaration: FunctionDeclaration) {
        val name = declaration.name
            ?: refuse(tsFile, declaration, "cannot lower an anonymous top-level function")
        if (declaration.body == null) {
            if (ModifierFlag.Declare in declaration.modifiers) {
                refuse(
                    tsFile, declaration,
                    "an ambient `declare function` has no implementation to lower"
                )
            }
            pendingOverloads.getOrPut(name.text) { mutableListOf() }.add(declaration)
            return
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
        // Every signature of this function names the same implementation.
        pendingOverloads.remove(name.text)?.forEach { functions[it] = function }
    }

    private fun declareClassShell(declaration: ClassDeclaration) {
        val name = declaration.name
            ?: refuse(tsFile, declaration, "cannot lower an anonymous class")
        val irClass = builder.irFactory.buildClass {
            this.name = Name.identifier(kotlinName(name.text))
            visibility = DescriptorVisibilities.PUBLIC
            // OPEN unconditionally: a class this program extends must be, and
            // deciding per class would mean knowing the whole program's
            // heritage before any shell exists. `final` is an optimization, and
            // the JVM's own is a devirtualizing JIT.
            modality = Modality.OPEN
            origin = builder.generatedOrigin
        }
        irClass.parent = irFile
        // Without a `this` receiver the class has no way to express a member's
        // dispatch receiver, and every method body would be unbuildable.
        irClass.createThisReceiverParameter()
        irFile.declarations.add(irClass)
        classes[declaration] = irClass
    }

    private fun declareClass(declaration: ClassDeclaration) {
        val irClass = classes.getValue(declaration)
        val base = superclassOf(declaration)
        irClass.superTypes = listOf(
            base?.let { classes.getValue(it).defaultType }
                ?: tables.runtimeSuperclasses[declaration]?.owner?.defaultType
                ?: irBuiltIns.anyType
        )
        base?.let { tables.superclasses[declaration] = it }

        for (member in declaration.members) {
            when (member) {
                is PropertyDeclaration -> declareField(declaration, irClass, member)
                is MethodDeclaration -> declareMethod(declaration, irClass, member)
                is Constructor -> declareConstructor(declaration, irClass, member)
                is GetAccessor -> declareAccessor(declaration, irClass, member)
                is SetAccessor -> declareAccessor(declaration, irClass, member)
                else -> refuse(tsFile, member, "cannot lower this class member")
            }
        }
        if (declaration.members.none { it is Constructor }) {
            defaultConstructor(declaration, irClass)
        }
    }

    /**
     * The class a heritage clause EXTENDS, or null for a class with no base.
     *
     * `implements` is erased — a generated class is not asked to satisfy a JVM
     * interface, because an interface type erases to the runtime's property bag
     * and nothing dispatches through it — while an `extends` of anything this
     * backend did not generate is refused rather than dropped: silently losing
     * a base class loses its members with it.
     */
    private fun superclassOf(declaration: ClassDeclaration): ClassDeclaration? {
        val clauses = declaration.heritageClauses ?: return null
        var base: ClassDeclaration? = null
        for (clause in clauses) {
            if (clause.token != SyntaxKind.ExtendsKeyword) continue
            val type = clause.types.singleOrNull()
                ?: refuse(tsFile, declaration, "a class extends exactly one class")
            val generated = classDeclarationOf(type.expression)
            if (generated != null) {
                base = generated
                continue
            }
            // A RUNTIME base — `class TomlDate extends Date`. The runtime class
            // is open for exactly this, and what the lowering needs from it is a
            // JVM symbol rather than a TypeScript tree.
            val runtime = runtimeBaseOf(type.expression)
                ?: refuse(
                    tsFile, declaration,
                    "`extends` is lowered for a class this backend generated or provides"
                )
            tables.runtimeSuperclasses[declaration] = runtime
        }
        return base
    }

    /**
     * The runtime class a heritage expression names — `Date`, today.
     *
     * By NAME, and checked against the program's own classes rather than
     * against the checker's resolution: a heritage clause's expression is a
     * type position the sink does not always visit as an expression, so
     * `nameAt` is frequently null here. The name is safe to use because a
     * program class of the same name would have been found by
     * [classDeclarationOf] one line up.
     */
    private fun runtimeBaseOf(expression: Expression): IrClassSymbol? {
        val name = (expression as? Identifier)?.text ?: return null
        facts.nameAt(expression)?.let { symbol ->
            val declaration = symbol.valueDeclaration ?: symbol.declarations.firstOrNull()
            if (declaration != null && tables.isProgramNode(declaration)) return null
        }
        if (classes.keys.any { it.name?.text == name }) return null
        return intrinsics.libraryClass(name)
    }

    /**
     * A `get`/`set` accessor, as an ordinary method.
     *
     * Named `get$x` / `set$x` so it cannot collide with a TypeScript member
     * called `x`, and reached only through a property ACCESS — see
     * [accessorFor]. `$` is legal in both languages' identifiers, and this is
     * the one place the backend invents a name.
     */
    private fun declareAccessor(
        owner: ClassDeclaration,
        irClass: IrClass,
        member: Node
    ) {
        val (name, parameters, body, isGetter) = when (member) {
            is GetAccessor -> AccessorShape(member.name, emptyList(), member.body, true)
            is SetAccessor -> AccessorShape(member.name, member.parameters, member.body, false)
            else -> refuse(tsFile, member, "cannot lower this accessor")
        }
        val memberName = (name as? Identifier)?.text
            ?: refuse(tsFile, member, "cannot lower a computed accessor name")
        if (body == null) refuse(tsFile, member, "cannot lower an accessor with no body")
        if (ModifierFlag.Static in accessorModifiers(member)) {
            refuse(tsFile, member, "a static accessor is out of the spike subset")
        }
        val function = irClass.addFunction {
            this.name = Name.identifier((if (isGetter) "get$" else "set$") + kotlinName(memberName))
            returnType = if (isGetter) accessorType(member, memberName) else irBuiltIns.unitType
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.OPEN
            origin = builder.generatedOrigin
        }
        function.parameters = listOf(dispatchReceiver(function, irClass))
        addParameters(function, parameters)
        val table = if (isGetter) tables.getters else tables.setters
        table.getOrPut(owner) { mutableMapOf() }[memberName] = function
    }

    private data class AccessorShape(
        val name: Node,
        val parameters: List<Parameter>,
        val body: Block?,
        val isGetter: Boolean,
    )

    private fun accessorModifiers(member: Node): Set<ModifierFlag> = when (member) {
        is GetAccessor -> member.modifiers
        is SetAccessor -> member.modifiers
        else -> emptySet()
    }

    /** A getter's result type, taken on the CLASS's own type as a member. */
    private fun accessorType(member: Node, name: String): IrType {
        val declared = facts.memberTypeOf(member)
            ?: refuse(tsFile, member, "the checker gave no type for accessor '$name'")
        return erase(member, declared)
    }

    private fun declareField(
        owner: ClassDeclaration,
        irClass: IrClass,
        member: PropertyDeclaration
    ) {
        val name = member.name as? Identifier
            ?: refuse(tsFile, member, "cannot lower a computed property name")
        // The property's type comes from the CLASS's type, not from the access
        // expression: `this` types as `any` here, so `this.x` does too.
        val declaredType = facts.memberTypeOf(member)
            ?: refuse(tsFile, member, "the checker gave no type for property '${name.text}'")
        val field = irClass.addField {
            this.name = Name.identifier(kotlinName(name.text))
            type = erase(member, declaredType)
            // PUBLIC, not private: a subclass's method reads its base's field
            // directly (there is no accessor to go through), and a JVM private
            // field is invisible one class down.
            visibility = DescriptorVisibilities.PUBLIC
            isStatic = ModifierFlag.Static in member.modifiers
            isFinal = false
            origin = builder.generatedOrigin
        }
        fields[member] = field
        if (field.isStatic) {
            tables.staticFields.getOrPut(owner) { mutableMapOf() }[name.text] = field
        }
    }

    private fun declareMethod(
        owner: ClassDeclaration,
        irClass: IrClass,
        member: MethodDeclaration
    ) {
        val name = member.name as? Identifier
            ?: refuse(tsFile, member, "cannot lower a computed method name")
        if (member.body == null) {
            // An overload SIGNATURE — the implementation follows in the same
            // class body and every signature shares its IR function.
            pendingMethodOverloads.getOrPut(name.text) { mutableListOf() }.add(member)
            return
        }
        val static = ModifierFlag.Static in member.modifiers
        val function = irClass.addFunction {
            this.name = Name.identifier(kotlinName(name.text))
            returnType = declaredReturnType(member)
            visibility = DescriptorVisibilities.PUBLIC
            // OPEN so a subclass may override it — see [declareClassShell].
            modality = if (static) Modality.FINAL else Modality.OPEN
            origin = builder.generatedOrigin
        }
        // Before the value parameters: `IrFunction.parameters` is one flat list
        // discriminated by kind, and the dispatch receiver has to come first.
        if (!static) function.parameters = listOf(dispatchReceiver(function, irClass))
        addParameters(function, member.parameters)
        methods[member] = function
        pendingMethodOverloads.remove(name.text)?.forEach { methods[it] = function }
        if (static) {
            tables.staticMethods.getOrPut(owner) { mutableMapOf() }[name.text] = function
        }
    }

    /**
     * The method a subclass member OVERRIDES, searched up the base chain.
     *
     * Matched by name and by parameter COUNT, which is what TypeScript's own
     * override relation reduces to once every parameter has been erased to
     * `Any?`. A base method of a different arity is a different JVM method and
     * the two coexist, exactly as they would in Kotlin.
     */
    private fun overriddenIn(
        owner: ClassDeclaration,
        name: String,
        parameterCount: Int
    ): IrSimpleFunction? = tables.superclasses[owner]
        ?.let { methodInChain(it, name, parameterCount) }

    /**
     * The RUNTIME class at the top of this class's chain, if it extends one.
     *
     * `class TomlDate extends Date` puts a `JsDate` above the whole chain, and
     * a method call on `this` or `super` may land there — so the search that
     * misses every generated class asks it before refusing.
     */
    private fun runtimeBaseAbove(owner: ClassDeclaration): IrClassSymbol? {
        tables.classChain(owner).forEach { current ->
            tables.runtimeSuperclasses[current]?.let { return it }
        }
        return null
    }

    /** The method [name]/[parameterCount] names on [from] or above it. */
    private fun methodInChain(
        from: ClassDeclaration,
        name: String,
        parameterCount: Int
    ): IrSimpleFunction? {
        tables.classChain(from).forEach { current ->
            current.members.filterIsInstance<MethodDeclaration>().firstOrNull { candidate ->
                (candidate.name as? Identifier)?.text == name &&
                    candidate.parameters.size == parameterCount &&
                    ModifierFlag.Static !in candidate.modifiers
            }?.let { return methods[it] }
        }
        return null
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

    /**
     * Declares the IR parameters, remembering which ones are OPTIONAL.
     *
     * An optional parameter's erased type is forced NULLABLE, whatever the
     * checker made of it — measured, an `all?: EventHandlerMap<Events>` types
     * as the `Map` alone, so trusting the erasure would produce a non-null slot
     * that a call site omitting the argument must then fill with null. The
     * omission itself is legal and is filled at the call site ([bindArguments]),
     * which is why the set is remembered rather than re-derived: nothing about
     * the IR parameter says it may be left out.
     */
    private fun addParameters(function: IrFunction, parameters: List<Parameter>) {
        parameters.forEachIndexed { index, parameter ->
            val rest = parameter.dotDotDotToken
            if (rest && index != parameters.lastIndex) {
                refuse(tsFile, parameter, "a rest parameter must be the last one")
            }
            // A DESTRUCTURING parameter has no name of its own — the pattern is
            // its own binding list — so the slot gets a synthetic one and the
            // pattern is bound from it in the body's prologue ([bindParameters]).
            val name = parameter.name as? Identifier ?: Identifier(
                text = "\$destructured$index",
                pos = parameter.pos,
                end = parameter.pos,
            )
            val optional = !rest && (parameter.questionToken || parameter.initializer != null)
            // A REST slot is the runtime array the call site BUILDS, and its
            // type is taken from the runtime rather than from the checker: the
            // declared `...args: T[]` erases to the same `JsArray` anyway, and
            // reading it here keeps the slot right even where the checker gave
            // the parameter no type at all (a `...args` with no annotation).
            val erased = if (rest) {
                intrinsics.jsArrayType
            } else {
                val type = facts.typeOf(parameter) ?: refuse(
                    tsFile, parameter, "the checker gave no type for parameter '${name.text}'"
                )
                erase(parameter, type).let { if (optional) it.makeNullable() else it }
            }
            val irParameter = function.addValueParameter(
                kotlinName(name.text),
                erased,
                builder.generatedOrigin
            )
            if (optional) optionalParameters.add(irParameter)
            if (rest) restParameters.add(irParameter)
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
            function.body = blockBody(function, declaration.parameters, body.statements, body)
        }
    }

    private fun defineClass(declaration: ClassDeclaration) {
        val irClass = classes.getValue(declaration)
        defineFieldInitializers(declaration, irClass)
        for (member in declaration.members) {
            when (member) {
                is MethodDeclaration -> {
                    val function = methods.getValue(member)
                    val body = member.body ?: continue
                    val static = ModifierFlag.Static in member.modifiers
                    inFunction(
                        function,
                        function.returnType,
                        if (static) null else function.parameters.first(),
                        if (static) null else declaration
                    ) {
                        function.body = blockBody(function, member.parameters, body.statements, body)
                    }
                }
                is GetAccessor -> defineAccessor(
                    declaration, tables.getters, member.name, emptyList(), member.body
                )
                is SetAccessor -> defineAccessor(
                    declaration, tables.setters, member.name, member.parameters, member.body
                )
                else -> {}
            }
        }
        defineConstructor(declaration, irClass)
    }

    private fun defineAccessor(
        declaration: ClassDeclaration,
        table: java.util.IdentityHashMap<ClassDeclaration, MutableMap<String, IrSimpleFunction>>,
        name: Node,
        parameters: List<Parameter>,
        body: Block?
    ) {
        val memberName = (name as? Identifier)?.text ?: return
        val function = table[declaration]?.get(memberName) ?: return
        val statements = body?.statements ?: return
        inFunction(function, function.returnType, function.parameters.first(), declaration) {
            function.body = blockBody(function, parameters, statements, body)
        }
    }

    /**
     * `class C { value = 1 }` — the initializer a field DECLARES.
     *
     * Set on the field rather than prepended to the constructor, because that
     * is where the JVM backend expects it: `IrInstanceInitializerCall` in the
     * constructor is the point it moves every one of these to, in declaration
     * order, before the constructor's own statements — which is TypeScript's
     * order too.
     *
     * Built under the CLASS's own `this` receiver: an initializer may read
     * another field (`b = this.a + 1`), and the constructor's receiver does not
     * exist yet at the point the backend places these.
     */
    private fun defineFieldInitializers(declaration: ClassDeclaration, irClass: IrClass) {
        val constructor = constructorsByDeclaration.getValue(declaration)
        for (member in declaration.members) {
            if (member !is PropertyDeclaration) continue
            val initializer = member.initializer ?: continue
            val field = fields.getValue(member)
            inFunction(constructor, field.type, irClass.thisReceiver, declaration) {
                scopes.addLast(HashMap())
                field.initializer = builder.irFactory.createExpressionBody(
                    UNDEFINED,
                    UNDEFINED,
                    coerce(initializer, lowerExpression(initializer), field.type)
                )
                scopes.removeLast()
            }
        }
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
            // The frame [inFunction] just installed is replaced, not nested, so
            // it inherits that frame's PARENT rather than becoming its child.
            current = FunctionFrame(
                constructor,
                DeclarationIrBuilder(builder.pluginContext, constructor.symbol),
                irBuiltIns.unitType,
                irClass.thisReceiver,
                declaration,
                current?.parent
            )
            val statements = mutableListOf<IrStatement>()
            scopes.addLast(HashMap())
            tsConstructor?.let { bindParameters(constructor, it.parameters, it.body, statements) }
            // The DELEGATION comes first, and where there is a base class its
            // arguments are the ones the TypeScript body passes to `super(…)` —
            // which TypeScript requires to be the first statement, and which is
            // therefore consumed here rather than lowered as a call.
            val base = tables.superclasses[declaration]
            val bodyStatements = tsConstructor?.body?.statements ?: emptyList()
            // `super(…)` need not be the FIRST statement — TypeScript only
            // requires it before `this` is touched, and a constructor that
            // massages its arguments first (the `smol-toml` date parser does)
            // is ordinary. Everything before it is lowered before the
            // delegation, which the JVM allows for as long as it does not use
            // `this` — and nothing that runs before `super(…)` can.
            val superIndex = bodyStatements.indexOfFirst { statement ->
                ((statement as? ExpressionStatement)?.expression as? CallExpression)
                    ?.let { (it.expression as? Identifier)?.text == "super" } == true
            }
            val superCall = bodyStatements.getOrNull(superIndex)
                ?.let { (it as? ExpressionStatement)?.expression as? CallExpression }
            bodyStatements.take(maxOf(superIndex, 0)).forEach { statement ->
                lowerStatement(statement, statements)
            }
            val runtimeBase = tables.runtimeSuperclasses[declaration]
            if (base == null && runtimeBase != null) {
                val arguments = superCall?.arguments ?: emptyList()
                val baseConstructor =
                    intrinsics.runtimeConstructorOfArity(runtimeBase, arguments.size)
                        ?: refuse(
                            tsFile,
                            superCall ?: declaration,
                            "no `${runtimeBase.owner.name.asString()}` constructor takes " +
                                "${arguments.size} argument(s)"
                        )
                val regular = baseConstructor.owner.parameters.filter {
                    it.kind == IrParameterKind.Regular
                }
                statements.add(
                    IrDelegatingConstructorCallImpl(
                        UNDEFINED,
                        UNDEFINED,
                        irBuiltIns.unitType,
                        baseConstructor,
                        typeArgumentsCount = 0,
                    ).apply {
                        arguments.forEachIndexed { index, argument ->
                            this.arguments[index] =
                                coerce(argument, lowerExpression(argument), regular[index].type)
                        }
                    }
                )
            } else if (base != null) {
                val baseConstructor = constructorsByDeclaration.getValue(base)
                statements.add(
                    IrDelegatingConstructorCallImpl(
                        UNDEFINED,
                        UNDEFINED,
                        irBuiltIns.unitType,
                        baseConstructor.symbol,
                        typeArgumentsCount = 0,
                    ).apply {
                        val arguments = superCall?.arguments ?: emptyList()
                        val regular = baseConstructor.parameters.filter {
                            it.kind == IrParameterKind.Regular
                        }
                        regular.forEachIndexed { index, parameter ->
                            val argument = arguments.getOrNull(index)
                            this.arguments[index] = if (argument != null) {
                                coerce(argument, lowerExpression(argument), parameter.type)
                            } else if (parameter in optionalParameters) {
                                scope.irNull()
                            } else {
                                refuse(
                                    tsFile,
                                    superCall ?: declaration,
                                    "`super(…)` passes ${arguments.size} argument(s) where the " +
                                        "base constructor takes ${regular.size}"
                                )
                            }
                        }
                    }
                )
            } else {
                statements.add(scope.irDelegatingConstructorCall(anyConstructor))
            }
            statements.add(
                IrInstanceInitializerCallImpl(
                    UNDEFINED, UNDEFINED, irClass.symbol, irBuiltIns.unitType
                )
            )
            bodyStatements.drop(maxOf(superIndex, 0) + if (superCall != null) 1 else 0)
                .forEach { statement -> lowerStatement(statement, statements) }
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
            ownerClass,
            saved
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
        statements: List<Statement>,
        body: Node? = null
    ): org.jetbrains.kotlin.ir.expressions.IrBlockBody {
        val lowered = mutableListOf<IrStatement>()
        scopes.addLast(HashMap())
        bindParameters(function, parameters, body, lowered)
        statements.forEach { lowerStatement(it, lowered) }
        scopes.removeLast()
        return blockBodyOf(lowered)
    }

    /**
     * `IrFactory.createBlockBody` takes no statements; they are added after.
     *
     * Every function body in this lowering is built here, which is what makes
     * it the place `var` HOISTING happens: the declarations collected while the
     * body was lowered are emitted before its first statement, and the frame's
     * list is cleared so a second body cannot inherit them.
     */
    private fun blockBodyOf(
        statements: List<IrStatement>
    ): org.jetbrains.kotlin.ir.expressions.IrBlockBody {
        val hoisted = current?.hoistedDeclarations
        val all = if (hoisted.isNullOrEmpty()) statements else hoisted + statements
        hoisted?.clear()
        return builder.irFactory.createBlockBody(UNDEFINED, UNDEFINED).apply {
            this.statements.addAll(all)
        }
    }

    /**
     * Binds each parameter's name, aliasing the ASSIGNED ones to a local.
     *
     * TypeScript lets a function assign to its own parameter — `all = all ||
     * new Map()` is the first line of mitt — and a JVM parameter is not a
     * variable an `IrSetValue` may target. So a parameter the body writes to
     * gets a mutable local initialized from it, and the name binds to that; one
     * the body only reads binds to the parameter itself and costs nothing.
     *
     * The scan is deliberately conservative: a same-named assignment in a
     * nested shadowing scope makes an alias nothing writes to, which is one
     * dead local, where missing an assignment would be invalid IR.
     */
    private fun bindParameters(
        function: IrFunction,
        parameters: List<Parameter>,
        body: Node?,
        out: MutableList<IrStatement>
    ) {
        val valueParameters = function.parameters.filter { it.kind == IrParameterKind.Regular }
        parameters.forEachIndexed { index, parameter ->
            val irParameter = valueParameters[index]
            val pattern = parameter.name
            if (pattern is ObjectBindingPattern || pattern is ArrayBindingPattern) {
                // The DEFAULT applies first, and it is not optional here: a
                // destructuring parameter with one (`{ maxDepth = 1000 } = {}`)
                // is called with nothing at all, so the pattern would otherwise
                // be read off a null — which is a NullPointerException at run
                // time in a program that compiled, and is exactly what the
                // `smol-toml` parser did on its first run.
                val subject = parameter.initializer?.let { fallback ->
                    scope.irWhen(
                        irParameter.type,
                        listOf(
                            scope.irBranch(
                                scope.irEqualsNull(scope.irGet(irParameter)),
                                coerce(fallback, lowerExpression(fallback), irParameter.type)
                            ),
                            scope.irElseBranch(scope.irGet(irParameter))
                        )
                    )
                } ?: scope.irGet(irParameter)
                bindPattern(pattern, subject, out)
                return@forEachIndexed
            }
            val name = pattern as? Identifier ?: return@forEachIndexed
            val default = parameter.initializer
            if (default != null) {
                // `function f(x = 5)`: the default is the body's first act, not
                // the caller's — an omitted argument arrives as null and is
                // replaced here, which is where JavaScript replaces it too.
                val local = buildVariable(
                    frame.irFunction as IrDeclarationParent,
                    UNDEFINED,
                    UNDEFINED,
                    builder.generatedOrigin,
                    Name.identifier(kotlinName(name.text)),
                    irParameter.type,
                    isVar = true,
                )
                local.initializer = scope.irWhen(
                    irParameter.type,
                    listOf(
                        scope.irBranch(
                            scope.irEqualsNull(scope.irGet(irParameter)),
                            coerce(default, lowerExpression(default), irParameter.type)
                        ),
                        scope.irElseBranch(scope.irGet(irParameter))
                    )
                )
                out.add(local)
                scopes.last()[name.text] = local
                return@forEachIndexed
            }
            if (body == null || !assignsTo(body, name.text)) {
                scopes.last()[name.text] = irParameter
                return@forEachIndexed
            }
            val local = buildVariable(
                frame.irFunction as IrDeclarationParent,
                UNDEFINED,
                UNDEFINED,
                builder.generatedOrigin,
                Name.identifier(kotlinName(name.text)),
                irParameter.type,
                isVar = true,
            )
            local.initializer = scope.irGet(irParameter)
            out.add(local)
            scopes.last()[name.text] = local
        }
    }

    /** Does [node]'s subtree assign to the free name [name]? */
    private fun assignsTo(node: Node, name: String): Boolean {
        var found = false
        fun visit(current: Node) {
            if (found) return
            if (current is BinaryExpression &&
                current.operator == SyntaxKind.Equals &&
                (current.left as? Identifier)?.text == name
            ) {
                found = true
                return
            }
            forEachChild(current) { child -> visit(child) }
        }
        visit(node)
        return found
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
            is ForOfStatement -> out.add(lowerForOf(statement))
            is ForInStatement -> out.add(lowerForIn(statement))
            is DoStatement -> out.add(lowerDoWhile(statement))
            is SwitchStatement -> out.add(lowerSwitch(statement))
            is ThrowStatement -> out.add(lowerThrow(statement))
            is TryStatement -> out.add(lowerTry(statement))
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
            // A `NotEmittedStatement` carries comments the parser preserved and
            // nothing else — it is the shape a stray `;` or a comment-only line
            // takes, and it emits nothing here for the same reason the ordinary
            // emitter emits nothing for it.
            is EmptyStatement, is NotEmittedStatement -> {}
            is FunctionDeclaration, is ClassDeclaration, is EnumDeclaration,
            is InterfaceDeclaration, is TypeAliasDeclaration,
            is ImportDeclaration, is ExportDeclaration, is ImportEqualsDeclaration -> {}
            else -> refuse(
                tsFile, statement,
                "cannot lower this ${statement::class.simpleName}"
            )
        }
    }

    private fun lowerVariables(statement: VariableStatement, out: MutableList<IrStatement>) {
        val list = statement.declarationList
        // A MODULE-level declaration was already given a static field by the
        // declare pass; here it is only ASSIGNED, in source order among the
        // other top-level statements — which is when JavaScript evaluates it.
        if (list.declarations.any { it in tables.moduleVariables }) {
            for (declaration in list.declarations) {
                val field = tables.moduleVariables[declaration]?.field ?: continue
                val initializer = declaration.initializer
                val value = if (initializer != null) {
                    coerce(initializer, lowerExpression(initializer), field.type)
                } else if (field.type == types.nothingNullable || field.type == irBuiltIns.anyNType) {
                    scope.irNull()
                } else {
                    refuse(
                        tsFile, declaration,
                        "cannot lower a module variable declared without an initializer"
                    )
                }
                out.add(
                    IrSetFieldImpl(
                        UNDEFINED, UNDEFINED, field.symbol, null, value,
                        irBuiltIns.unitType, null, null
                    )
                )
            }
            return
        }
        // `var` is FUNCTION-scoped and hoisted: the binding belongs to the
        // enclosing function however deeply the statement is nested, and it
        // exists — holding `undefined` — from the function's first instruction.
        // So the declaration moves to the top of the body ([blockBodyOf]) and
        // only the ASSIGNMENT stays here, which is exactly what JavaScript does.
        val hoist = list.flags == SyntaxKind.VarKeyword
        val mutable = list.flags != SyntaxKind.ConstKeyword
        for (declaration in list.declarations) {
            val pattern = declaration.name
            if (pattern is ObjectBindingPattern || pattern is ArrayBindingPattern) {
                if (hoist) {
                    refuse(
                        tsFile, declaration,
                        "a destructuring `var` is out of the spike subset"
                    )
                }
                val initializer = declaration.initializer
                    ?: refuse(tsFile, declaration, "a destructuring declaration needs a value")
                bindPattern(pattern, lowerExpression(initializer), out)
                continue
            }
            val name = pattern as? Identifier
                ?: refuse(tsFile, declaration, "cannot lower this declaration name")
            if (hoist) {
                val variable = hoistedVariable(
                    name,
                    erase(declaration, variableType(declaration.name, declaration.initializer))
                )
                declaration.initializer?.let { initializer ->
                    out.add(
                        scope.irSet(
                            variable,
                            coerce(initializer, lowerExpression(initializer), variable.type)
                        )
                    )
                }
                continue
            }
            // `let k: string` with no initializer holds `undefined` until it is
            // assigned — which is `null` here — so the SLOT is nullable whatever
            // the annotation said. TypeScript's definite-assignment analysis is
            // what makes reading it before the assignment an error, and that is
            // the checker's job, not the slot's.
            val declaredType =
                erase(declaration, variableType(declaration.name, declaration.initializer))
            val type = if (declaration.initializer == null) {
                declaredType.makeNullable()
            } else declaredType
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
                ?: scope.irNull()
            out.add(variable)
            scopes.last()[name.text] = variable
        }
    }

    /**
     * The FUNCTION-scoped slot for a `var`, created once per name.
     *
     * Always nullable and always mutable: a hoisted binding holds `undefined`
     * until its assignment runs, and `var` may be re-declared — `var x = 1; var
     * x = 2` is two assignments to one slot, not two slots, so a second
     * declaration of the same name answers the first one's variable.
     *
     * Re-declaration is also why the type is the FIRST declaration's: the two
     * may erase differently, and a slot that changed shape half way through a
     * function would be a different variable.
     */
    private fun hoistedVariable(name: Identifier, declaredType: IrType): IrVariable {
        frame.hoisted[name.text]?.let { return it }
        val variable = buildVariable(
            frame.irFunction as IrDeclarationParent,
            UNDEFINED,
            UNDEFINED,
            builder.generatedOrigin,
            Name.identifier(kotlinName(name.text)),
            declaredType.makeNullable(),
            isVar = true,
        )
        variable.initializer = scope.irNull()
        frame.hoisted[name.text] = variable
        frame.hoistedDeclarations.add(variable)
        return variable
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
        val initializerList = when (val initializer = statement.initializer) {
            null -> null
            is VariableStatement -> initializer.declarationList
            is com.xemantic.typescript.compiler.VariableDeclarationList -> initializer
            is Expression -> {
                outer.add(lowerExpression(initializer))
                null
            }
            else -> refuse(tsFile, initializer, "cannot lower this `for` initializer")
        }
        initializerList?.let { lowerVariables(VariableStatement(it), outer) }
        // A `let` loop variable is a FRESH binding per iteration, and a `var`
        // one is a single binding shared by the whole loop. The difference is
        // observable only through a closure — `for (let i…) fns.push(() => i)`
        // answers 0,1,2 where the `var` spelling answers 3,3,3 — so the copy is
        // made only where the body builds one, and the loop is otherwise
        // emitted exactly as before.
        val perIteration = if (
            initializerList != null &&
            initializerList.flags != SyntaxKind.VarKeyword &&
            buildsAClosure(statement.statement)
        ) {
            initializerList.declarations
                .mapNotNull { (it.name as? Identifier)?.text }
                .mapNotNull { name -> lookup(name)?.let { name to it } }
        } else {
            emptyList()
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
        val inner = mutableListOf<IrStatement>()
        // The fresh copies are declared, the body is lowered against THEM, and
        // the values are written back before the incrementor runs — which is
        // what makes an assignment inside the body still reach the next
        // iteration. `continue` lands after the trampoline, so it reaches the
        // write-back too.
        scopes.addLast(HashMap())
        val copies = perIteration.map { (name, carrier) ->
            val copy = buildVariable(
                frame.irFunction as IrDeclarationParent,
                UNDEFINED,
                UNDEFINED,
                builder.generatedOrigin,
                Name.identifier(kotlinName(name)),
                carrier.type,
                isVar = true,
            )
            copy.initializer = scope.irGet(carrier)
            inner.add(copy)
            scopes.last()[name] = copy
            carrier to copy
        }
        loops.addLast(LoopFrame(loop, trampoline ?: loop))
        val body = statementExpression(statement.statement)
        loops.removeLast()
        if (trampoline != null) {
            trampoline.body = body
            inner.add(trampoline)
        } else {
            inner.add(body)
        }
        copies.forEach { (carrier, copy) ->
            inner.add(scope.irSet(carrier, scope.irGet(copy)))
        }
        scopes.removeLast()
        // The incrementor is lowered with the COPIES out of scope, so it moves
        // the carrier — the next iteration then copies the moved value.
        statement.incrementor?.let { inner.add(lowerExpression(it)) }
        loop.body = IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, inner)
        outer.add(loop)
        scopes.removeLast()
        return IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, outer)
    }

    /**
     * Does [node] build a function value — the only way a loop variable's
     * per-iteration identity becomes observable?
     *
     * Without one, every read of the variable happens during its own iteration
     * and a shared binding is indistinguishable from a fresh one, which is why
     * the copy is not made unconditionally.
     */
    private fun buildsAClosure(node: Node): Boolean {
        var found = false
        fun visit(current: Node) {
            if (found) return
            when (current) {
                is ArrowFunction, is FunctionExpression, is FunctionDeclaration -> found = true
                else -> forEachChild(current) { visit(it) }
            }
        }
        visit(node)
        return found
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

    /**
     * `for (const x of xs) body` over an ARRAY, as an index walk.
     *
     * Not an iterator: `JsArray` has none, and the semantics wanted here are
     * JavaScript's own — the loop reads `length` on every iteration, so a body
     * that pushes extends the walk and one that pops shortens it, which an
     * iterator would forbid by throwing. Only an array-erased subject is
     * accepted; a `Map`, a `Set` and a string need their own iteration and are
     * refused rather than guessed at.
     */
    private fun lowerForOf(statement: ForOfStatement): IrExpression {
        if (statement.awaitModifier) {
            refuse(tsFile, statement, "`for await` is out of the spike subset")
        }
        val subject = statement.expression
        val owner = runtimeClassOf(subject)
        if (owner == null || owner != intrinsics.jsArrayClass) {
            refuse(
                tsFile, statement,
                "`for…of` is lowered for an ARRAY subject only; this one is " +
                    (facts.typeOf(subject)?.let { facts.render(it) } ?: "untyped")
            )
        }
        val list = statement.initializer as? VariableDeclarationList
            ?: refuse(tsFile, statement, "`for…of` needs a `const`/`let` binding")
        return indexedWalk(statement, statement.statement, list, lowerExpression(subject), null)
    }

    /**
     * `for (k in subject) body` — an indexed walk over the subject's KEYS.
     *
     * The keys are computed ONCE, before the loop, which is where this differs
     * from `for…of`'s live `length` read: JavaScript leaves the effect of adding
     * a property DURING a `for…in` unspecified, and a snapshot is the only
     * answer that is the same on every run.
     */
    private fun lowerForIn(statement: ForInStatement): IrExpression {
        val list = statement.initializer as? VariableDeclarationList
            ?: refuse(tsFile, statement, "`for…in` needs a `var`/`const`/`let` binding")
        val keys = scope.irCall(intrinsics.jsForInKeys).apply {
            arguments[0] = coerce(
                statement.expression,
                lowerExpression(statement.expression),
                types.anyNullable
            )
        }
        // The binding is a STRING whatever the checker made of it: `for…in`
        // enumerates property NAMES, so an array's indices arrive as `"0"`,
        // `"1"`, … rather than as numbers.
        return indexedWalk(statement, statement.statement, list, keys, types.string)
    }

    /**
     * The walk `for…of` and `for…in` share: a temporary array, an index, and a
     * body binding one element per iteration.
     */
    private fun indexedWalk(
        statement: Statement,
        bodyStatement: Statement,
        list: VariableDeclarationList,
        arrayValue: IrExpression,
        elementTypeOverride: IrType?
    ): IrExpression {
        val declaration = list.declarations.singleOrNull()
            ?: refuse(tsFile, statement, "this loop needs exactly one binding")
        val pattern = declaration.name
        val name = pattern as? Identifier

        val outer = mutableListOf<IrStatement>()
        scopes.addLast(HashMap())
        val array = temporary("array", intrinsics.jsArrayType, arrayValue)
        val index = temporary("index", types.double, scope.irDouble(0.0), mutable = true)
        outer.add(array)
        outer.add(index)
        val length = intrinsics.runtimePropertyGetter(intrinsics.jsArrayClass, "length")
            ?: refuse(tsFile, statement, "JsArray.length is missing")
        val get = intrinsics.runtimeMember(intrinsics.jsArrayClass, "get", 1)
            ?: refuse(tsFile, statement, "JsArray.get is missing")
        val loop = IrWhileLoopImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null)
        loop.condition = scope.irCall(
            irBuiltIns.lessFunByOperandType[irBuiltIns.doubleClass]
                ?: refuse(tsFile, statement, "no `Double` comparison intrinsic")
        ).apply {
            arguments[0] = scope.irGet(index)
            arguments[1] = scope.irCall(length).apply { arguments[0] = scope.irGet(array) }
        }
        // §4's trampoline, for the same reason `for(;;)` needs it and with the
        // same cost when it is not needed: the INCREMENT runs after the body, so
        // a `continue` that jumped to the loop head would skip it and spin
        // forever. Inside a one-iteration `do { … } while (false)`, `continue`
        // is a BREAK of that inner loop, which lands exactly on the increment.
        val trampoline = if (hasOwnContinue(bodyStatement)) {
            IrDoWhileLoopImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null).also {
                it.condition = scope.irBoolean(false)
            }
        } else null
        loops.addLast(LoopFrame(loop, trampoline ?: loop))
        val body = mutableListOf<IrStatement>()
        scopes.addLast(HashMap())
        val elementType = elementTypeOverride
            ?: facts.typeOf(declaration.name)?.let { erase(declaration, it) }
            ?: types.anyNullable
        val read = {
            scope.irCall(get).apply {
                arguments[0] = scope.irGet(array)
                arguments[1] = scope.irGet(index)
            }
        }
        // `for (const [k, v] of entries)` — the element is destructured by the
        // same binder a `const [k, v] = e` declaration uses, so the two cannot
        // disagree about what a pattern means.
        // A `var` in the loop HEAD is the function's binding, not a fresh one
        // per iteration — `for (var k in t) {}; return k` answers the LAST key,
        // and `var seen = "none"` above such a loop is the same slot the loop
        // writes. So the element is ASSIGNED there rather than declared here.
        val hoistTarget = if (name != null && list.flags == SyntaxKind.VarKeyword) {
            hoistedVariable(name, elementType)
        } else {
            null
        }
        when {
            hoistTarget != null -> body.add(
                scope.irSet(hoistTarget, coerce(declaration, read(), hoistTarget.type))
            )
            name != null -> {
                val element = temporary(
                    kotlinName(name.text),
                    elementType,
                    coerce(declaration, read(), elementType)
                )
                body.add(element)
                scopes.last()[name.text] = element
            }
            // `for (const [k, v] of entries)` — bound by the same binder a
            // `const [k, v] = e` declaration uses, so the two cannot disagree
            // about what a pattern means.
            pattern is ObjectBindingPattern || pattern is ArrayBindingPattern -> {
                if (list.flags == SyntaxKind.VarKeyword) {
                    refuse(
                        tsFile, declaration,
                        "a destructuring `var` in a loop head is out of the spike subset"
                    )
                }
                // An ARRAY pattern destructures by INDEX, which `readElementOfValue`
                // only does for a value statically typed as the runtime array —
                // and the checker gives a binding PATTERN no type of its own, so
                // `elementType` here is `Any?`. The subject is what is being
                // destructured, so its shape is known from the pattern.
                val subjectType = if (pattern is ArrayBindingPattern) {
                    intrinsics.jsArrayType
                } else {
                    elementType
                }
                bindPattern(pattern, coerce(declaration, read(), subjectType), body)
            }
            else -> refuse(tsFile, declaration, "cannot lower this loop binding")
        }
        lowerStatement(bodyStatement, body)
        body.add(
            scope.irSet(
                index,
                arithmeticValues("plus", scope.irGet(index), scope.irDouble(1.0))
            )
        )
        scopes.removeLast()
        loops.removeLast()
        loop.body = if (trampoline == null) {
            IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, body)
        } else {
            val increment = body.removeAt(body.size - 1)
            trampoline.body = IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, body)
            IrBlockImpl(
                UNDEFINED,
                UNDEFINED,
                irBuiltIns.unitType,
                null,
                listOf(trampoline, increment)
            )
        }
        outer.add(loop)
        scopes.removeLast()
        return IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, outer)
    }

    private fun lowerDoWhile(statement: DoStatement): IrExpression {
        val loop = IrDoWhileLoopImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null)
        loops.addLast(LoopFrame(loop, loop))
        loop.body = statementExpression(statement.statement)
        loops.removeLast()
        loop.condition = condition(statement.expression)
        return loop
    }

    /**
     * `switch`, with FALL-THROUGH, and without a `goto` to build it from.
     *
     * The encoding is a one-iteration `do { … } while (false)` — so `break`
     * inside a clause is an ordinary loop break and needs no special case — plus
     * a `matched` flag: a clause runs when the subject matched IT or when an
     * earlier clause already matched, which is exactly what falling through
     * means. Comparison is `===`, as the specification says.
     *
     * `default` is accepted only as the LAST clause. In the middle its meaning
     * is "run if nothing else matched, then fall through from here", which this
     * encoding cannot express without a second pass, and guessing is worse than
     * refusing.
     */
    private fun lowerSwitch(statement: SwitchStatement): IrExpression {
        val clauses = statement.caseBlock
        clauses.forEachIndexed { index, clause ->
            if (clause is DefaultClause && index != clauses.lastIndex) {
                refuse(
                    tsFile, clause,
                    "a `default` clause before the last one is out of the spike subset"
                )
            }
            if (clause !is CaseClause && clause !is DefaultClause) {
                refuse(tsFile, clause, "cannot lower this switch clause")
            }
        }
        val outer = mutableListOf<IrStatement>()
        scopes.addLast(HashMap())
        val subject = temporary(
            "subject",
            types.anyNullable,
            coerce(statement.expression, lowerExpression(statement.expression), types.anyNullable)
        )
        val matched = temporary("matched", types.boolean, scope.irBoolean(false), mutable = true)
        outer.add(subject)
        outer.add(matched)
        val loop = IrDoWhileLoopImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null)
        loops.addLast(LoopFrame(loop, loop))
        val body = mutableListOf<IrStatement>()
        clauses.forEach { clause ->
            val statements = mutableListOf<IrStatement>()
            scopes.addLast(HashMap())
            when (clause) {
                is CaseClause -> {
                    body.add(
                        scope.irIfThen(
                            irBuiltIns.unitType,
                            strictEqualsValues(
                                clause.expression,
                                scope.irGet(subject),
                                lowerExpression(clause.expression)
                            ),
                            scope.irSet(matched, scope.irBoolean(true))
                        )
                    )
                    clause.statements.forEach { lowerStatement(it, statements) }
                }
                // The last clause: it runs when nothing matched, and anything
                // that fell through to here runs it too.
                is DefaultClause -> {
                    body.add(scope.irSet(matched, scope.irBoolean(true)))
                    clause.statements.forEach { lowerStatement(it, statements) }
                }
                // Refused above, before anything was built.
                else -> refuse(tsFile, clause, "cannot lower this switch clause")
            }
            scopes.removeLast()
            body.add(
                scope.irIfThen(
                    irBuiltIns.unitType,
                    scope.irGet(matched),
                    IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, statements)
                )
            )
        }
        loops.removeLast()
        loop.body = IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, body)
        loop.condition = scope.irBoolean(false)
        outer.add(loop)
        scopes.removeLast()
        return IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, outer)
    }

    /**
     * `throw e`, where `e` is any VALUE — which the JVM cannot throw.
     *
     * The runtime wraps a non-`Throwable` in a carrier, and [lowerTry] unwraps
     * it on the way out, so a program that throws a string and catches it sees
     * the string. Throwing an `Error` subclass would be more idiomatic on the
     * JVM and would lose exactly that.
     */
    private fun lowerThrow(statement: ThrowStatement): IrExpression {
        val thrown = statement.expression
            ?: refuse(tsFile, statement, "`throw` with no operand")
        return scope.irCall(intrinsics.jsThrow, irBuiltIns.nothingType).apply {
            arguments[0] = coerce(thrown, lowerExpression(thrown), types.anyNullable)
        }
    }

    private fun lowerTry(statement: TryStatement): IrExpression {
        val tryBody = statementExpression(statement.tryBlock)
        val catches = mutableListOf<org.jetbrains.kotlin.ir.expressions.IrCatch>()
        statement.catchClause?.let { clause ->
            val caught = buildVariable(
                frame.irFunction as IrDeclarationParent,
                UNDEFINED,
                UNDEFINED,
                builder.generatedOrigin,
                Name.identifier("tmp\$thrown"),
                irBuiltIns.throwableType,
                isVar = false,
            )
            val statements = mutableListOf<IrStatement>()
            scopes.addLast(HashMap())
            (clause.variableDeclaration?.name as? Identifier)?.let { name ->
                // The VALUE thrown, not the JVM exception carrying it.
                val value = temporary(
                    kotlinName(name.text),
                    types.anyNullable,
                    scope.irCall(intrinsics.jsCaught, types.anyNullable).apply {
                        arguments[0] = scope.irGet(caught)
                    }
                )
                statements.add(value)
                scopes.last()[name.text] = value
            }
            clause.block.statements.forEach { lowerStatement(it, statements) }
            scopes.removeLast()
            catches.add(
                org.jetbrains.kotlin.ir.expressions.impl.IrCatchImpl(
                    UNDEFINED,
                    UNDEFINED,
                    caught,
                    IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, statements)
                )
            )
        }
        val finallyBody = statement.finallyBlock?.let { statementExpression(it) }
        return org.jetbrains.kotlin.ir.expressions.impl.IrTryImpl(
            UNDEFINED,
            UNDEFINED,
            irBuiltIns.unitType,
            tryBody,
            catches,
            finallyBody
        )
    }

    /** A local the lowering introduces for itself, bound to nothing in TypeScript. */
    private fun temporary(
        name: String,
        type: IrType,
        initializer: IrExpression,
        mutable: Boolean = false
    ) = buildVariable(
        frame.irFunction as IrDeclarationParent,
        UNDEFINED,
        UNDEFINED,
        builder.generatedOrigin,
        Name.identifier(name),
        type,
        isVar = mutable,
    ).apply { this.initializer = initializer }

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
        is PostfixUnaryExpression -> when (node.operator) {
            SyntaxKind.PlusPlus -> lowerIncrement(node, node.operand, 1.0, prefix = false)
            SyntaxKind.MinusMinus -> lowerIncrement(node, node.operand, -1.0, prefix = false)
            else -> refuse(tsFile, node, "cannot lower this postfix operator")
        }
        is ConditionalExpression -> lowerConditional(node)
        is CallExpression -> lowerCall(node)
        is NewExpression -> lowerNew(node)
        is PropertyAccessExpression -> lowerPropertyRead(node)
        is ArrayLiteralExpression -> lowerArrayLiteral(node)
        is ObjectLiteralExpression -> lowerObjectLiteral(node)
        is NoSubstitutionTemplateLiteralNode -> scope.irString(node.text)
        is RegularExpressionLiteralNode -> lowerRegularExpression(node)
        is BigIntLiteralNode -> scope.irCall(intrinsics.jsBigInt, intrinsics.bigIntegerType).apply {
            arguments[0] = scope.irString(node.text.removeSuffix("n"))
        }
        // `void e` evaluates `e` and yields `undefined` — `void 0` is how a
        // program spells `undefined` where the name might be shadowed.
        is VoidExpression -> IrBlockImpl(
            UNDEFINED,
            UNDEFINED,
            types.nothingNullable,
            null,
            listOf(lowerExpression(node.expression), scope.irNull())
        )
        is TemplateExpression -> lowerTemplate(node)
        is ArrowFunction -> lowerFunctionValue(node, node.parameters, node.body)
        is FunctionExpression -> lowerFunctionValue(node, node.parameters, node.body)
        is ElementAccessExpression -> lowerElementRead(node)
        // `x!` and `x as T` are ERASURES: both leave the value alone and change
        // only what the checker believes about it, and the checker has already
        // believed it — so the coercion to this node's OWN recorded type is the
        // whole of their runtime meaning.
        is NonNullExpression -> coerceToRecordedType(node, lowerExpression(node.expression))
        is AsExpression -> coerceToRecordedType(node, lowerExpression(node.expression))
        // `<T>expr` is `expr as T` in the older syntax and the SAME operation —
        // the checker records one type for both, so both read it back.
        is TypeAssertionExpression ->
            coerceToRecordedType(node, lowerExpression(node.expression))
        // `typeof x` on its own: the runtime answers the STRING, which is what
        // the operator is. The `typeof x === "…"` pattern below is an
        // optimization of this, not a substitute for it.
        is TypeOfExpression -> scope.irCall(intrinsics.jsTypeOf, types.string).apply {
            arguments[0] = coerce(
                node.expression, lowerExpression(node.expression), types.anyNullable
            )
        }
        else -> refuse(tsFile, node, "cannot lower this ${node::class.simpleName}")
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
        // The numeric globals are CONSTANTS, and a program that spells one is
        // asking for the value rather than for a lookup.
        "Infinity" -> scope.irDouble(Double.POSITIVE_INFINITY)
        "NaN" -> scope.irDouble(Double.NaN)
        "this" -> frame.thisReceiver?.let { scope.irGet(it) }
            ?: refuse(tsFile, node, "`this` outside a class member")
        else -> lookup(node.text)?.let { scope.irGet(it) }
            ?: moduleFieldFor(node)?.let { variable ->
                if (variable.owner === tsFile) {
                    IrGetFieldImpl(
                        UNDEFINED, UNDEFINED, variable.field.symbol, variable.field.type,
                        null, null, null
                    )
                } else {
                    scope.irCall(variable.getter.symbol, variable.field.type)
                }
            }
            ?: refuse(tsFile, node, "cannot lower the reference '${node.text}'")
    }

    /**
     * The MODULE-level field a free name refers to, or null.
     *
     * Asked only after the lexical scopes have missed, and answered through the
     * checker's own resolution of the name — so an IMPORTED constant reaches
     * the field its declaring file created, with the import contributing
     * nothing at runtime, exactly as a cross-file call does.
     */
    private fun moduleFieldFor(node: Identifier): KirProgramTables.ModuleVariable? {
        val symbol = facts.nameAt(node) ?: return null
        val declaration = symbol.valueDeclaration ?: symbol.declarations.firstOrNull()
        return (declaration as? VariableDeclaration)?.let { tables.moduleVariables[it] }
    }

    private fun lookup(name: String): IrValueDeclaration? {
        for (index in scopes.indices.reversed()) {
            scopes[index][name]?.let { return it }
        }
        // AFTER the block chain, deliberately: a `let` in an enclosing block
        // shadows a function-scoped `var` of the same name, and asking the
        // blocks first is what gives it precedence. Walking OUT through the
        // enclosing frames is how a closure reaches an outer function's `var`.
        var frame = current
        while (frame != null) {
            frame.hoisted[name]?.let { return it }
            frame = frame.parent
        }
        return null
    }

    private fun lowerPrefix(node: PrefixUnaryExpression): IrExpression = when (node.operator) {
        SyntaxKind.Minus -> {
            val operand = coerce(node.operand, lowerExpression(node.operand), types.double)
            scope.irCall(intrinsics.doubleUnaryOperator("unaryMinus")).apply {
                arguments[0] = operand
            }
        }
        SyntaxKind.Plus -> scope.irCall(intrinsics.jsToNumber).apply {
            arguments[0] = coerce(node.operand, lowerExpression(node.operand), types.anyNullable)
        }
        SyntaxKind.Exclamation -> not(condition(node.operand))
        SyntaxKind.Tilde -> scope.irCall(intrinsics.bitwise("jsBitNot"), types.double).apply {
            arguments[0] = coerce(node.operand, lowerExpression(node.operand), types.anyNullable)
        }
        SyntaxKind.PlusPlus -> lowerIncrement(node, node.operand, 1.0, prefix = true)
        SyntaxKind.MinusMinus -> lowerIncrement(node, node.operand, -1.0, prefix = true)
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
            // `x instanceof C` is a JVM type test, and it is only that because
            // a generated class IS a JVM class — the nominal half of the design's
            // hybrid, arriving where it always had to.
            SyntaxKind.InstanceOfKeyword -> {
                val target = classDeclarationOf(node.right)
                    ?: refuse(
                        tsFile, node,
                        "`instanceof` is lowered for a class this backend generated only"
                    )
                scope.irIs(
                    coerce(node.left, lowerExpression(node.left), types.anyNullable),
                    classes.getValue(target).defaultType
                )
            }
            SyntaxKind.EqualsEqualsEquals -> strictEquals(node, negated = false)
            SyntaxKind.ExclamationEqualsEquals -> strictEquals(node, negated = true)
            SyntaxKind.AmpersandAmpersand, SyntaxKind.BarBar,
            SyntaxKind.QuestionQuestion -> shortCircuit(node)
            SyntaxKind.GreaterThanGreaterThanGreaterThan ->
                scope.irCall(intrinsics.jsUnsignedShiftRight, types.double).apply {
                    arguments[0] = coerce(
                        node.left, lowerExpression(node.left), types.anyNullable
                    )
                    arguments[1] = coerce(
                        node.right, lowerExpression(node.right), types.anyNullable
                    )
                }
            // `==` is ECMAScript ABSTRACT equality and the runtime owns all of
            // it: `1 == "1"` is true and `null == 0` is false.
            // The COMMA operator: evaluate both, yield the right — `j++, p++`
            // in a `for` incrementor is what it is for.
            SyntaxKind.Comma -> {
                val left = lowerExpression(node.left)
                val right = lowerExpression(node.right)
                IrBlockImpl(UNDEFINED, UNDEFINED, right.type, null, listOf(left, right))
            }
            SyntaxKind.EqualsEquals -> looseEquals(node, negated = false)
            SyntaxKind.ExclamationEquals -> looseEquals(node, negated = true)
            SyntaxKind.Ampersand -> bitwise(node, "jsBitAnd")
            SyntaxKind.Bar -> bitwise(node, "jsBitOr")
            SyntaxKind.Caret -> bitwise(node, "jsBitXor")
            SyntaxKind.LessThanLessThan -> bitwise(node, "jsShiftLeft")
            SyntaxKind.GreaterThanGreaterThan -> bitwise(node, "jsShiftRight")
            SyntaxKind.PlusEquals, SyntaxKind.MinusEquals, SyntaxKind.AsteriskEquals,
            SyntaxKind.SlashEquals, SyntaxKind.PercentEquals,
            SyntaxKind.AmpersandEquals, SyntaxKind.BarEquals, SyntaxKind.CaretEquals,
            SyntaxKind.LessThanLessThanEquals, SyntaxKind.GreaterThanGreaterThanEquals,
            SyntaxKind.GreaterThanGreaterThanGreaterThanEquals ->
                lowerCompoundAssignment(node)
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
        if (isNumericSum(node)) {
            return arithmeticValues(
                "plus",
                coerce(node.left, left, types.double),
                coerce(node.right, right, types.double)
            )
        }
        return addValues(node.left, left, node.right, right)
    }

    /**
     * Does the CHECKER call this whole `+` a `number`?
     *
     * The question the erased operand types cannot answer any more, because a
     * property read out of a property bag erases to `Any?` however precisely
     * the checker typed it. `ctx.p + 1` — a scanner advancing its own cursor —
     * therefore reached `jsAdd` with BOTH sides boxed and an `instanceof` chain
     * to sort out, for an addition the checker had already called numeric.
     *
     * Asking about the SUM rather than about the operands is what makes this
     * exact: `+` yields `number` only when both operands are numeric, so the
     * one answer decides both coercions. Every other arithmetic operator has
     * coerced its operands to `Double` from the beginning — `x - 1` casts an
     * `any` today — so this is that same rule reaching the one operator that
     * could not have it, and not a new licence.
     */
    private fun isNumericSum(node: Node): Boolean {
        val type = facts.typeOf(node as? Expression ?: return false) ?: return false
        return types.map(type) == types.double
    }

    /** `+` over operands that are already lowered — see [lowerAddition]. */
    private fun addValues(
        leftNode: Expression,
        left: IrExpression,
        rightNode: Expression,
        right: IrExpression
    ): IrExpression {
        return when {
            left.type == types.double && right.type == types.double ->
                scope.irCall(intrinsics.doubleOperator("plus", types.double)).apply {
                    arguments[0] = left
                    arguments[1] = right
                }
            left.type == types.string || right.type == types.string ->
                scope.irCall(intrinsics.stringPlus).apply {
                    arguments[0] = asString(leftNode, left)
                    arguments[1] = asString(rightNode, right)
                }
            else -> scope.irCall(intrinsics.jsAdd).apply {
                arguments[0] = coerce(leftNode, left, types.anyNullable)
                arguments[1] = coerce(rightNode, right, types.anyNullable)
            }
        }
    }

    /**
     * A value in a string-concatenation position.
     *
     * Kotlin's `String.plus(Any?)` would call `toString()`, and `Double.toString`
     * is not `Number::toString` — it prints `1.0` where JavaScript prints `1`.
     * So everything that is not already a `String` goes through the runtime.
     *
     * It is also where the `undefined`/`null` collapse of §3.1 has to be undone:
     * see [nullRendersAsUndefined].
     */
    private fun asString(node: Expression, value: IrExpression): IrExpression = when {
        value.type == types.string -> value
        // The arm `jsToString` would have taken, without the box: `is Double ->
        // jsNumberToString(value)` is that function's own second line.
        value.type == types.double ->
            scope.irCall(intrinsics.jsNumberToString, types.string).apply { arguments[0] = value }
        else -> scope.irCall(
            if (nullRendersAsUndefined(node)) intrinsics.jsToStringNullAsUndefined
            else intrinsics.jsToString
        ).apply {
            arguments[0] = coerce(node, value, types.anyNullable)
        }
    }

    /**
     * Does a JVM `null` in this expression's slot mean JavaScript's `undefined`?
     *
     * §3.1 puts `undefined` and `null` on one JVM value, so `string | undefined`
     * and `string | null` both erase to `String?` and the RUNTIME cannot tell a
     * value of one from a value of the other — it answered `"null"` for both,
     * where JavaScript prints `undefined` for the first. The lowering can tell,
     * because it still holds the TypeScript type, and this is that question:
     * the type admits a nullish member, and every nullish member it admits is
     * `undefined` (or `void`).
     *
     * A type admitting BOTH keeps `"null"`. There is genuinely nothing left to
     * decide it by once the two values have been collapsed, and `any` is the
     * same case — so this narrows a wrong answer to the shapes that cannot be
     * separated at all, rather than swapping which one is wrong. The mirror
     * choice for `typeof`, made for the same reason, is in `jsTypeOf`.
     */
    private fun nullRendersAsUndefined(node: Expression): Boolean {
        val type = facts.typeOf(node) ?: return false
        val members = (type as? Type.Union)?.types ?: listOf(type)
        var undefined = false
        for (member in members) {
            if (member.flags.hasAny(TypeFlags.Null)) return false
            if (member.flags.hasAny(TypeFlags.Undefined or TypeFlags.Void)) undefined = true
        }
        return undefined
    }

    private fun arithmetic(node: BinaryExpression, operator: String): IrExpression =
        arithmeticValues(
            operator,
            coerce(node.left, lowerExpression(node.left), types.double),
            coerce(node.right, lowerExpression(node.right), types.double)
        )

    /** `-` `*` `/` `%` over operands already lowered AND already `Double`. */
    private fun arithmeticValues(
        operator: String,
        left: IrExpression,
        right: IrExpression
    ): IrExpression = scope.irCall(intrinsics.doubleOperator(operator, types.double)).apply {
        arguments[0] = left
        arguments[1] = right
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
        // Lowered BEFORE the specialization is chosen, and in source order: the
        // decision reads the operands' erased types, and `===` evaluates its
        // left operand first whichever runtime entry point it reaches.
        val left = lowerExpression(node.left)
        val right = lowerExpression(node.right)
        val call = strictEqualsValues(node, left, right)
        return if (negated) not(call) else call
    }

    /**
     * `===` over operands already lowered, decided by their ERASED types.
     *
     * The same shape [addValues] uses for `+`, and for the same reason: the
     * general entry point takes `Any?`, so a comparison the lowering has
     * already PROVEN to be between two numbers would box both sides and then
     * re-discover their types with an `instanceof` chain. `str.charCodeAt(p)
     * === 0x20` is that shape, and it is what a hand-written scanner's inner
     * loop is made of.
     *
     * Both half-specialized directions exist rather than one canonical order,
     * because reaching a single entry point would mean swapping two operands
     * that may both have effects.
     *
     * The fallback is unchanged and is what every mixed case still reaches —
     * an erasure this does not name is a correctness question for
     * [jsStrictEquals], never for the caller.
     */
    private fun strictEqualsValues(
        at: Node,
        left: IrExpression,
        right: IrExpression
    ): IrExpression {
        val l = left.type
        val r = right.type
        uniformStrictEquals(l, r)?.let { symbol ->
            return scope.irCall(symbol, types.boolean).apply {
                arguments[0] = left
                arguments[1] = right
            }
        }
        halfStrictEquals(r)?.let { symbol ->
            return scope.irCall(symbol.first, types.boolean).apply {
                arguments[0] = coerce(at, left, types.anyNullable)
                arguments[1] = right
            }
        }
        halfStrictEquals(l)?.let { symbol ->
            return scope.irCall(symbol.second, types.boolean).apply {
                arguments[0] = left
                arguments[1] = coerce(at, right, types.anyNullable)
            }
        }
        return scope.irCall(intrinsics.jsStrictEquals, types.boolean).apply {
            arguments[0] = coerce(at, left, types.anyNullable)
            arguments[1] = coerce(at, right, types.anyNullable)
        }
    }

    /** The entry point for two operands that erase to the SAME primitive. */
    private fun uniformStrictEquals(left: IrType, right: IrType): IrSimpleFunctionSymbol? =
        if (left != right) null else when (left) {
            types.double -> intrinsics.jsStrictEqualsNumbers
            types.string -> intrinsics.jsStrictEqualsStrings
            types.boolean -> intrinsics.jsStrictEqualsBooleans
            else -> null
        }

    /**
     * The (any-on-the-left, any-on-the-right) entry points for ONE primitive
     * operand, or null where this type is not one the runtime specializes.
     */
    private fun halfStrictEquals(
        type: IrType
    ): Pair<IrSimpleFunctionSymbol, IrSimpleFunctionSymbol>? = when (type) {
        types.double -> intrinsics.jsStrictEqualsAnyNumber to intrinsics.jsStrictEqualsNumberAny
        types.string -> intrinsics.jsStrictEqualsAnyString to intrinsics.jsStrictEqualsStringAny
        else -> null
    }

    /**
     * `&&` and `||` short-circuit, and their JavaScript result is an OPERAND
     * rather than a boolean — so the branches keep their own values and the
     * result is typed at the erasure of the whole expression.
     */
    /**
     * `a && b` / `a || b`, whose result is an OPERAND and not a boolean.
     *
     * The left operand is evaluated ONCE into a temporary, because it is both
     * the test and a possible result. Reusing one lowered expression node in
     * two places is invalid IR — "Duplicate IR node" — which nothing catches
     * without `-Xverify-ir`, so the temporary is not a style choice.
     *
     * The temporary keeps the LEFT's own erased type rather than the result's,
     * and the coercion to the result type happens INSIDE the branch that
     * returns it. `all || new Map()` is why: `all` is `JsMap?` and the result
     * is `JsMap`, so coercing before the test would cast a null to a non-null
     * type and throw exactly where JavaScript takes the other branch.
     */
    private fun shortCircuit(node: BinaryExpression): IrExpression {
        val declared = erase(node, facts.typeOf(node)
            ?: refuse(tsFile, node, "the checker gave no type for this expression"))
        val leftValue = lowerExpression(node.left)
        // The result of `a && b` is an OPERAND, so its type is what the two
        // arms have in common — and the checker's answer for the whole
        // expression is not always a type BOTH can become: `hasTime && (s =
        // t)` types as `string` while its kept arm is a `boolean`. Where an arm
        // cannot reach the declared type, the block is `Any?` and the
        // information is recovered by the cast at the use site, as everywhere
        // else union erasure loses one.
        val rightType = facts.typeOf(node.right)?.let { types.map(it) }
        val armsFit = coercionFor(leftValue.type, declared, irBuiltIns) != Coercion.IMPOSSIBLE &&
            (rightType == null || coercionFor(rightType, declared, irBuiltIns) != Coercion.IMPOSSIBLE)
        val type = if (armsFit) declared else types.anyNullable
        val temporary = buildVariable(
            frame.irFunction as IrDeclarationParent,
            UNDEFINED,
            UNDEFINED,
            builder.generatedOrigin,
            Name.identifier("tmp\$operand"),
            leftValue.type,
            isVar = false,
        )
        temporary.initializer = leftValue
        val kept = { coerce(node.left, scope.irGet(temporary), type) }
        val right = coerce(node.right, lowerExpression(node.right), type)
        // `??` differs from `||` in its TEST and in nothing else: it asks
        // whether the left operand is NULLISH rather than whether it is falsy,
        // which is the whole reason the operator exists — `"" ?? f` keeps the
        // empty string where `"" || f` replaces it. `undefined` and `null` are
        // one JVM value here (design §3.1), so one null test covers both, which
        // is exactly the pair JavaScript's own `??` tests for.
        val nullish = node.operator == SyntaxKind.QuestionQuestion
        val test = if (nullish) {
            scope.irEqualsNull(scope.irGet(temporary))
        } else {
            truthy(scope.irGet(temporary))
        }
        val branches = if (node.operator == SyntaxKind.AmpersandAmpersand || nullish) {
            listOf(scope.irBranch(test, right), scope.irElseBranch(kept()))
        } else {
            listOf(scope.irBranch(test, kept()), scope.irElseBranch(right))
        }
        return IrBlockImpl(
            UNDEFINED,
            UNDEFINED,
            type,
            null,
            listOf(temporary, scope.irWhen(type, branches))
        )
    }

    /** `==` / `!=` — the runtime's abstract equality, negated in place. */
    private fun looseEquals(node: BinaryExpression, negated: Boolean): IrExpression {
        val left = lowerExpression(node.left)
        val right = lowerExpression(node.right)
        // ABSTRACT equality coincides with strict equality exactly when both
        // operands are the same primitive; every MIXED case must stay on the
        // runtime, because `1 == true` and `null == undefined` are true there.
        val test = uniformStrictEquals(left.type, right.type)?.let { symbol ->
            scope.irCall(symbol, types.boolean).apply {
                arguments[0] = left
                arguments[1] = right
            }
        } ?: scope.irCall(intrinsics.jsLooseEquals, types.boolean).apply {
            arguments[0] = coerce(node.left, left, types.anyNullable)
            arguments[1] = coerce(node.right, right, types.anyNullable)
        }
        return if (negated) not(test) else test
    }

    private fun bitwise(node: BinaryExpression, name: String): IrExpression =
        scope.irCall(intrinsics.bitwise(name), types.double).apply {
            arguments[0] = coerce(node.left, lowerExpression(node.left), types.anyNullable)
            arguments[1] = coerce(node.right, lowerExpression(node.right), types.anyNullable)
        }

    /**
     * `a += b` and its family: READ the target, combine, store back.
     *
     * The target is read by lowering it a SECOND time, which is only sound
     * while re-reading it has no side effects of its own — so a target whose
     * receiver or index is anything but a name, a `this`, or a literal is
     * refused ([isRepeatableTarget]) rather than evaluated twice. `a[i++] += 1`
     * is the shape that costs, and it is rare enough to refuse loudly and
     * common enough to be a genuine bug if it were not.
     */
    private fun lowerCompoundAssignment(node: BinaryExpression): IrExpression {
        val target = node.left
        if (!isRepeatableTarget(target)) {
            refuse(
                tsFile, node,
                "a compound assignment needs a target that can be read twice — " +
                    "this one would evaluate its receiver or index a second time"
            )
        }
        return assignTo(target) { type ->
            val current = lowerExpression(target)
            val right = lowerExpression(node.right)
            val combined = when (node.operator) {
                SyntaxKind.PlusEquals ->
                    if (isNumericSum(node)) arithmeticValues(
                        "plus",
                        coerce(target, current, types.double),
                        coerce(node.right, right, types.double)
                    )
                    else addValues(target, current, node.right, right)
                SyntaxKind.MinusEquals -> numericCombine(node, "minus", current, right)
                SyntaxKind.AsteriskEquals -> numericCombine(node, "times", current, right)
                SyntaxKind.SlashEquals -> numericCombine(node, "div", current, right)
                SyntaxKind.PercentEquals -> numericCombine(node, "rem", current, right)
                SyntaxKind.AmpersandEquals -> bitwiseCombine(node, "jsBitAnd", current, right)
                SyntaxKind.BarEquals -> bitwiseCombine(node, "jsBitOr", current, right)
                SyntaxKind.CaretEquals -> bitwiseCombine(node, "jsBitXor", current, right)
                SyntaxKind.LessThanLessThanEquals ->
                    bitwiseCombine(node, "jsShiftLeft", current, right)
                SyntaxKind.GreaterThanGreaterThanEquals ->
                    bitwiseCombine(node, "jsShiftRight", current, right)
                SyntaxKind.GreaterThanGreaterThanGreaterThanEquals ->
                    bitwiseCombine(node, "jsUnsignedShiftRight", current, right)
                else -> refuse(tsFile, node, "cannot lower this compound assignment")
            }
            coerce(node, combined, type)
        }
    }

    private fun numericCombine(
        node: BinaryExpression,
        operator: String,
        left: IrExpression,
        right: IrExpression
    ): IrExpression = arithmeticValues(
        operator,
        coerce(node.left, left, types.double),
        coerce(node.right, right, types.double)
    )

    private fun bitwiseCombine(
        node: BinaryExpression,
        name: String,
        left: IrExpression,
        right: IrExpression
    ): IrExpression = scope.irCall(intrinsics.bitwise(name), types.double).apply {
        arguments[0] = coerce(node.left, left, types.anyNullable)
        arguments[1] = coerce(node.right, right, types.anyNullable)
    }

    /**
     * May this target be lowered twice — once as a read, once as a store?
     *
     * A name, a `this`, a chain of those, and an index that is itself one of
     * them or a literal. Anything else is refused, because "evaluate it twice"
     * and "evaluate it once" differ exactly where a program would notice.
     */
    private fun isRepeatableTarget(target: Expression): Boolean = when (target) {
        is Identifier -> true
        is PropertyAccessExpression -> isRepeatableTarget(target.expression)
        is ElementAccessExpression -> isRepeatableTarget(target.expression) &&
            (target.argumentExpression is Identifier ||
                target.argumentExpression is NumericLiteralNode ||
                target.argumentExpression is StringLiteralNode)
        else -> false
    }

    /**
     * `++x` / `x++` / `--x` / `x--`, as a BLOCK that yields the right value.
     *
     * A block rather than an assignment because the two forms differ in what
     * they EVALUATE TO — the new value and the old one — and because an
     * `IrSetValue` yields `Unit`, so neither form could be used as an
     * expression without one. In statement position the block costs a
     * temporary the JVM's own optimizer removes.
     */
    private fun lowerIncrement(
        node: Expression,
        target: Expression,
        increment: Double,
        prefix: Boolean
    ): IrExpression {
        if (!isRepeatableTarget(target)) {
            refuse(tsFile, node, "`++`/`--` needs a target that can be read twice")
        }
        val old = buildVariable(
            frame.irFunction as IrDeclarationParent,
            UNDEFINED,
            UNDEFINED,
            builder.generatedOrigin,
            Name.identifier("tmp\$operand"),
            types.double,
            isVar = false,
        )
        old.initializer = coerce(target, lowerExpression(target), types.double)
        val updated = buildVariable(
            frame.irFunction as IrDeclarationParent,
            UNDEFINED,
            UNDEFINED,
            builder.generatedOrigin,
            Name.identifier("tmp\$updated"),
            types.double,
            isVar = false,
        )
        updated.initializer = arithmeticValues(
            "plus",
            scope.irGet(old),
            scope.irDouble(increment)
        )
        val store = assignTo(target) { type -> coerce(node, scope.irGet(updated), type) }
        return IrBlockImpl(
            UNDEFINED,
            UNDEFINED,
            types.double,
            null,
            listOf(old, updated, store, scope.irGet(if (prefix) updated else old))
        )
    }

    private fun lowerAssignment(node: BinaryExpression): IrExpression =
        assignTo(node.left) { type -> coerce(node.right, lowerExpression(node.right), type) }

    /**
     * Stores into an assignment TARGET, whatever shape it has.
     *
     * The value is a function of the target's erased type rather than an
     * expression, because that type is only known once the target has been
     * classified — a field wants the field's type, a property bag and an array
     * element want `Any?`, a local wants its own — and coercing afterwards would
     * mean lowering the value before knowing what it must become.
     */
    /**
     * An assignment EXPRESSION, which yields the value it assigned.
     *
     * JavaScript's `=` is an expression — `a && (b = c)` is ordinary code, and
     * the `smol-toml` parser is written that way — while an `IrSetValue` or an
     * `IrSetField` yields `Unit`. So the assignment becomes a block: compute
     * once into a temporary, store it, yield it. In statement position the
     * block's value is discarded and the JVM's own optimizer removes the
     * temporary.
     */
    private fun assignTo(target: Expression, value: (IrType) -> IrExpression): IrExpression {
        val statements = mutableListOf<IrStatement>()
        var assigned: IrVariable? = null
        val store = assignToTarget(target) { type ->
            val temporary = temporary("assigned", type, value(type))
            statements.add(temporary)
            assigned = temporary
            scope.irGet(temporary)
        }
        val slot = assigned ?: return store
        statements.add(store)
        statements.add(scope.irGet(slot))
        return IrBlockImpl(UNDEFINED, UNDEFINED, slot.type, null, statements)
    }

    private fun assignToTarget(target: Expression, value: (IrType) -> IrExpression): IrExpression =
        when (target) {
            is Identifier -> {
                val variable = lookup(target.text)
                if (variable != null) {
                    scope.irSet(variable, value(variable.type))
                } else {
                    val variable = moduleFieldFor(target)
                        ?: refuse(tsFile, target, "cannot assign to '${target.text}'")
                    if (variable.owner === tsFile) {
                        IrSetFieldImpl(
                            UNDEFINED, UNDEFINED, variable.field.symbol, null,
                            value(variable.field.type), irBuiltIns.unitType, null, null
                        )
                    } else {
                        val setter = variable.setter
                            ?: refuse(
                                tsFile, target,
                                "cannot assign to the imported constant '${target.text}'"
                            )
                        scope.irCall(setter.symbol).apply {
                            arguments[0] = value(variable.field.type)
                        }
                    }
                }
            }
            is PropertyAccessExpression -> if (staticOwnerOf(target) != null) {
                val owner = staticOwnerOf(target)!!
                val field = tables.classChain(owner)
                    .firstNotNullOfOrNull { tables.staticFields[it]?.get(target.name.text) }
                    ?: refuse(
                        tsFile, target,
                        "class '${owner.name?.text}' declares no static '${target.name.text}'"
                    )
                IrSetFieldImpl(
                    UNDEFINED, UNDEFINED, field.symbol, null, value(field.type),
                    irBuiltIns.unitType, null, null
                )
            } else if (
                instanceOwnerOf(target)?.let { accessorFor(it, target.name.text, write = true) }
                != null
            ) {
                val setter = accessorFor(
                    instanceOwnerOf(target)!!, target.name.text, write = true
                )!!
                scope.irCall(setter).apply {
                    arguments[0] = receiverOf(target)
                    arguments[1] = value(
                        setter.parameters.first { it.kind == IrParameterKind.Regular }.type
                    )
                }
            } else if (isPropertyBag(target.expression)) {
                scope.irCall(intrinsics.jsObjectSet).apply {
                    arguments[0] = lowerExpression(target.expression)
                    arguments[1] = scope.irString(target.name.text)
                    arguments[2] = value(types.anyNullable)
                }
            } else if (isDynamicReceiver(target.expression)) {
                scope.irCall(intrinsics.jsSet).apply {
                    arguments[0] = coerce(
                        target.expression, lowerExpression(target.expression), types.anyNullable
                    )
                    arguments[1] = scope.irString(target.name.text)
                    arguments[2] = value(types.anyNullable)
                }
            } else {
                val field = resolveField(target)
                IrSetFieldImpl(
                    UNDEFINED,
                    UNDEFINED,
                    field.symbol,
                    receiverOf(target),
                    value(field.type),
                    irBuiltIns.unitType,
                    null,
                    null
                )
            }
            is ElementAccessExpression -> if (
                isDynamicReceiver(target.expression) || isPropertyBag(target.expression)
            ) {
                scope.irCall(intrinsics.jsIndexSet).apply {
                    arguments[0] = coerce(
                        target.expression, lowerExpression(target.expression), types.anyNullable
                    )
                    arguments[1] = coerce(
                        target.argumentExpression,
                        lowerExpression(target.argumentExpression),
                        types.anyNullable
                    )
                    arguments[2] = value(types.anyNullable)
                }
            } else {
                val owner = runtimeClassOf(target.expression)
                    ?: refuse(tsFile, target, elementAccessRefusal(target))
                val set = intrinsics.runtimeMember(owner, "set", 2)
                    ?: refuse(tsFile, target, "this backend cannot write an element of this receiver")
                scope.irCall(set).apply {
                    arguments[0] = lowerExpression(target.expression)
                    arguments[1] = elementIndex(target)
                    arguments[2] = value(types.anyNullable)
                }
            }
            else -> refuse(tsFile, target, "cannot lower this assignment target")
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
            // Everything else — "object", "function", "bigint", "symbol" — is
            // decided by the runtime's own `typeof`, compared as a string. The
            // arms above are the optimization: an `is` test where one is exact.
            else -> scope.irCall(intrinsics.jsStrictEquals, types.boolean).apply {
                arguments[0] = scope.irCall(intrinsics.jsTypeOf, types.string).apply {
                    arguments[0] = coerce(typeOf.expression, operand, types.anyNullable)
                }
                arguments[1] = scope.irString(literal.text)
            }
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
        // `this.m(…)` and `super.m(…)` are resolved from the enclosing class's
        // CHAIN by name, not from the checker's signature: measured, both
        // receivers type as `any` here, so the callee offers no signatures at
        // all and every such call would be refused for a reason that says
        // nothing about the program. (`this.x` FIELD reads have gone through
        // the owner class for the same reason since the first classes landed.)
        val selfReceiver = (callee as? PropertyAccessExpression)?.expression as? Identifier
        if (selfReceiver != null &&
            (selfReceiver.text == "this" || selfReceiver.text == "super")
        ) {
            val access = callee
            val viaSuper = selfReceiver.text == "super"
            val owner = frame.ownerClass
                ?: refuse(tsFile, node, "`${selfReceiver.text}` outside a class member")
            val from = if (viaSuper) tables.superclasses[owner] ?: owner else owner
            val generated = methodInChain(from, access.name.text, node.arguments.size)
            // The base may be one of this backend's RUNTIME classes — `class
            // TomlDate extends Date` calls `super.toISOString()` — in which case
            // the member is a JVM method of that class rather than a lowered
            // TypeScript one.
            val runtimeBase = runtimeBaseAbove(from)
            val target = generated?.symbol
                ?: runtimeBase?.let {
                    intrinsics.runtimeMember(it, access.name.text, node.arguments.size)
                }
                ?: refuse(
                    tsFile, node,
                    "no method '${access.name.text}' with ${node.arguments.size} argument(s) " +
                        "on '${from.name?.text}' or above it"
                )
            return scope.irCall(target).apply {
                // `super` is non-virtual, or an override calling its base
                // through it recurses into itself forever. `this` is virtual,
                // which is what an override is FOR.
                if (viaSuper) {
                    superQualifierSymbol = if (generated != null) {
                        classes.getValue(from).symbol
                    } else {
                        runtimeBase ?: refuse(tsFile, node, "`super` in a class with no base")
                    }
                }
                arguments[0] = receiverOf(access)
                val regular = target.owner.parameters.filter { it.kind == IrParameterKind.Regular }
                node.arguments.forEachIndexed { index, argument ->
                    arguments[index + 1] =
                        coerce(argument, lowerExpression(argument), regular[index].type)
                }
            }
        }
        methods[declaration]?.let { target ->
            // A STATIC method has no receiver at all — it is a JVM static.
            if (target.parameters.none { it.kind == IrParameterKind.DispatchReceiver }) {
                return scope.irCall(target.symbol).apply {
                    bindArguments(node, target.parameters, offset = 0)
                }
            }
            val receiver = callee as? PropertyAccessExpression
                ?: refuse(tsFile, node, "a method call needs a receiver")
            val viaSuper = (receiver.expression as? Identifier)?.text == "super"
            return scope.irCall(target.symbol).apply {
                // `super.m()` must NOT dispatch virtually, or an override calling
                // its base through `super` recurses into itself forever.
                if (viaSuper) {
                    superQualifierSymbol = frame.ownerClass
                        ?.let { tables.superclasses[it] }
                        ?.let { classes.getValue(it).symbol }
                        ?: refuse(tsFile, node, "`super` outside a class with a base")
                }
                arguments[0] = receiverOf(receiver)
                bindArguments(node, target.parameters, offset = 1)
            }
        }
        if (callee is PropertyAccessExpression && isPropertyBag(callee.expression)) {
            // A method of a property bag is a PROPERTY whose value is a
            // function — read it, then call it, exactly as JavaScript does.
            //
            // Through `jsCall` rather than a direct `invoke`, because the
            // property's value has whatever arity its DECLARATION had while the
            // call site supplies whatever TypeScript's optional parameters
            // allow: mitt's `off(type, handler?)` is a `Function2` called with
            // one argument. JavaScript pads the missing one with `undefined`,
            // and doing so here is the difference between running that library
            // and refusing it.
            return adaptingCall(node) { coerce(callee, lowerBagRead(callee), types.anyNullable) }
        }
        // A NUMBER's members, for the same reason a string's are: Kotlin's
        // `Double.toString()` prints `6.0` where JavaScript prints `6`.
        if (callee is PropertyAccessExpression && isNumberReceiver(callee.expression)) {
            val target = intrinsics.numberMember(callee.name.text, node.arguments.size)
                ?: refuse(
                    tsFile, node,
                    "'Number.${callee.name.text}' with ${node.arguments.size} argument(s) is " +
                        "not a member this backend gives a runtime function"
                )
            return scope.irCall(target).apply {
                arguments[0] = coerce(
                    callee.expression, lowerExpression(callee.expression), types.double
                )
                val regular = target.owner.parameters.filter { it.kind == IrParameterKind.Regular }
                node.arguments.forEachIndexed { index, argument ->
                    arguments[index + 1] =
                        coerce(argument, lowerExpression(argument), regular[index + 1].type)
                }
            }
        }
        if (callee is PropertyAccessExpression && isStringReceiver(callee.expression)) {
            val firstIsRegExp = node.arguments.firstOrNull()
                ?.let { runtimeClassOf(it) } == intrinsics.jsRegExpClass
            // `replace(re, fn)` is a different runtime function from
            // `replace(re, "s")`, and the argument's own erased type is what
            // says which — a function value is a `FunctionN`, or the variadic
            // carrier where its arity is not static.
            val lastIsFunction = node.arguments.lastOrNull()?.let { isFunctionValued(it) } == true
            val target = intrinsics.stringMember(
                callee.name.text, node.arguments.size, firstIsRegExp, lastIsFunction
            )
                ?: refuse(
                    tsFile, node,
                    "'String.${callee.name.text}' with ${node.arguments.size} argument(s) is " +
                        "not a member this backend gives a runtime function"
                )
            return scope.irCall(target).apply {
                // COERCED, exactly as the `Number` branch above coerces to
                // `Double`. `isStringReceiver` asks the CHECKER, which answers
                // yes for a receiver NARROWED to `string` out of a union — and
                // such a receiver's erasure is `Any`, so lowering it raw hands a
                // `String` parameter an `Any` and produces IR that is simply not
                // well typed. The JVM and Native backends accept it; the Wasm
                // one validates and rejects the module.
                arguments[0] = coerce(
                    callee.expression, lowerExpression(callee.expression), types.string
                )
                val regular = target.owner.parameters.filter { it.kind == IrParameterKind.Regular }
                node.arguments.forEachIndexed { index, argument ->
                    arguments[index + 1] =
                        coerce(argument, lowerExpression(argument), regular[index + 1].type)
                }
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
        // A GLOBAL free function — `isNaN(x)`, `parseInt(s, 10)`, `String(x)` —
        // reached only once the call has resolved to a declaration this backend
        // did not generate, so a program's own `function parseInt` is untouched.
        if (callee is Identifier && declaration != null && !tables.isProgramNode(declaration)) {
            intrinsics.globalFunction(callee.text, node.arguments.size)?.let { target ->
                return scope.irCall(target).apply {
                    val regular = target.owner.parameters.filter {
                        it.kind == IrParameterKind.Regular
                    }
                    node.arguments.forEachIndexed { index, argument ->
                        arguments[index] =
                            coerce(argument, lowerExpression(argument), regular[index].type)
                    }
                }
            }
        }
        // `a?.b(…)` — the receiver evaluated once, `null` where it is nullish,
        // and the member reached dynamically because a nullish-typed receiver
        // has told the backend nothing else.
        if (callee is PropertyAccessExpression && callee.questionDotToken) {
            val receiver = temporary(
                "optional",
                types.anyNullable,
                coerce(callee.expression, lowerExpression(callee.expression), types.anyNullable)
            )
            val invoke = scope.irCall(intrinsics.jsInvoke, types.anyNullable).apply {
                arguments[0] = scope.irGet(receiver)
                arguments[1] = scope.irString(callee.name.text)
                arguments[2] = scope.irVararg(
                    irBuiltIns.anyNType,
                    node.arguments.map { coerce(it, lowerExpression(it), types.anyNullable) }
                )
            }
            return IrBlockImpl(
                UNDEFINED,
                UNDEFINED,
                types.anyNullable,
                null,
                listOf(
                    receiver,
                    scope.irWhen(
                        types.anyNullable,
                        listOf(
                            scope.irBranch(
                                scope.irEqualsNull(scope.irGet(receiver)),
                                scope.irNull()
                            ),
                            scope.irElseBranch(invoke)
                        )
                    )
                )
            )
        }
        // A member call on a receiver the checker typed `any` — the largest
        // dynamic population in real TypeScript (`kir-structural-typing.md` §7)
        // and the one place this backend dispatches on what a value IS rather
        // than on what its type said. Refusing it would refuse most real code
        // reached through an untyped boundary.
        if (callee is PropertyAccessExpression && isDynamicReceiver(callee.expression)) {
            return scope.irCall(intrinsics.jsInvoke, types.anyNullable).apply {
                arguments[0] = coerce(
                    callee.expression, lowerExpression(callee.expression), types.anyNullable
                )
                arguments[1] = scope.irString(callee.name.text)
                arguments[2] = scope.irVararg(
                    irBuiltIns.anyNType,
                    node.arguments.map { coerce(it, lowerExpression(it), types.anyNullable) }
                )
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
            // A callee whose erasure did not say its arity, but which the
            // checker says IS callable: a union of function types, whose two
            // sides are a `Function1` and a `Function2` and have no single
            // erasure. JavaScript calls either with whatever the site supplies,
            // so the runtime performs that adaptation rather than the lowering
            // refusing the shape at the centre of most callback code.
            facts.typeOf(callee)?.takeIf { isCallableType(it) }?.let {
                return lowerDynamicCall(node)
            }
        }
        refuse(
            tsFile, node,
            "cannot lower this call — " + when {
                fact.signatureCount == 0 -> "the checker found nothing callable here"
                declaration == null -> "the ${fact.signatureCount} signature(s) on offer " +
                    "carry no declaration (a library or synthesized signature)"
                else -> "its declaration is a ${declaration::class.simpleName} this " +
                    "backend did not generate"
            } + (fact.receiverTypeText?.let { " ($it.${fact.memberName})" } ?: "")
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
        // `new Map()` — a library type this backend gives a runtime class.
        facts.typeOf(node)?.let { types.map(it) }?.let { intrinsics.runtimeClassOf(it) }
            ?.let { owner ->
                val given = node.arguments ?: emptyList()
                val constructor = intrinsics.runtimeConstructorOfArity(owner, given.size)
                    ?: refuse(
                        tsFile, node,
                        "no `${owner.owner.name.asString()}` constructor takes " +
                            "${given.size} argument(s) in this backend"
                    )
                val regular = constructor.owner.parameters.filter {
                    it.kind == IrParameterKind.Regular
                }
                return IrConstructorCallImpl(
                    UNDEFINED,
                    UNDEFINED,
                    owner.owner.defaultType,
                    constructor,
                    typeArgumentsCount = 0,
                    constructorTypeArgumentsCount = 0,
                ).apply {
                    given.forEachIndexed { index, argument ->
                        arguments[index] =
                            coerce(argument, lowerExpression(argument), regular[index].type)
                    }
                }
            }
        val signature = facts.constructionAt(node)
        val owner = (signature?.declaration as? Constructor)?.parent as? ClassDeclaration
            ?: (signature?.declaration as? ClassDeclaration)
            // A class that declares NO constructor offers no construct signature
            // here, so the callee's own symbol is asked instead: the implicit
            // constructor is a property of the class, not of the signature list.
            ?: classDeclarationOf(node.expression)
            ?: refuse(
                tsFile, node,
                if (signature == null) "the checker resolved no constructor for this `new`"
                else "cannot lower `new` on a non-class"
            )
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

    /**
     * `a?.b` — the receiver evaluated ONCE, and `null` where it is nullish.
     *
     * The whole point of `?.` is that the receiver is not evaluated twice, so
     * it goes into a temporary; the member is then read off the VALUE rather
     * than off the syntax, since the receiver node has already been consumed.
     */
    private fun lowerOptionalRead(node: PropertyAccessExpression): IrExpression {
        val receiver = temporary(
            "optional",
            types.anyNullable,
            coerce(node.expression, lowerExpression(node.expression), types.anyNullable)
        )
        val read = coerce(
            node,
            readMemberOfValue(node, scope.irGet(receiver), node.name.text),
            types.anyNullable
        )
        val branches = listOf(
            scope.irBranch(scope.irEqualsNull(scope.irGet(receiver)), scope.irNull()),
            scope.irElseBranch(read)
        )
        return IrBlockImpl(
            UNDEFINED,
            UNDEFINED,
            types.anyNullable,
            null,
            listOf(receiver, scope.irWhen(types.anyNullable, branches))
        )
    }

    /**
     * The CONSTANT an enum member access names — `Type.DOTTED` — or null.
     *
     * The receiver has to be an identifier naming an enum this program declared:
     * the enum has no runtime object, so this is the only shape of it that can
     * be lowered at all, and anything else about it (`Type[x]`, passing `Type`
     * as a value) refuses where it stands.
     */
    private fun enumMemberValue(node: PropertyAccessExpression): IrExpression? {
        val receiver = node.expression as? Identifier ?: return null
        val symbol = facts.nameAt(receiver) ?: return null
        val declaration = symbol.valueDeclaration ?: symbol.declarations.firstOrNull()
        val enum = declaration as? EnumDeclaration ?: return null
        val value = tables.enumMembers[enum]?.get(node.name.text) ?: return null
        return when (value) {
            is Double -> scope.irDouble(value)
            is String -> scope.irString(value)
            else -> null
        }
    }

    /** The generated class a callee expression NAMES, or null. */
    private fun classDeclarationOf(callee: Expression): ClassDeclaration? {
        val name = callee as? Identifier ?: return null
        val symbol = facts.nameAt(name) ?: return null
        val declaration = symbol.valueDeclaration ?: symbol.declarations.firstOrNull()
        return (declaration as? ClassDeclaration)?.takeIf { it in classes }
    }

    private fun lowerPropertyRead(node: PropertyAccessExpression): IrExpression {
        if (node.questionDotToken) return lowerOptionalRead(node)
        staticOwnerOf(node)?.let { owner ->
            tables.classChain(owner).forEach { current ->
                tables.staticFields[current]?.get(node.name.text)?.let { field ->
                    return IrGetFieldImpl(
                        UNDEFINED, UNDEFINED, field.symbol, field.type, null, null, null
                    )
                }
            }
            refuse(
                tsFile, node,
                "class '${owner.name?.text}' declares no static '${node.name.text}'"
            )
        }
        enumMemberValue(node)?.let { return it }
        if (isStringReceiver(node.expression)) {
            val target = intrinsics.stringMember(node.name.text, 0)
                ?: refuse(
                    tsFile, node,
                    "'String.${node.name.text}' is not a property this backend gives a " +
                        "runtime function"
                )
            return scope.irCall(target).apply {
                arguments[0] = coerce(
                    node.expression, lowerExpression(node.expression), types.string
                )
            }
        }
        if (isPropertyBag(node.expression)) return lowerBagRead(node)
        instanceOwnerOf(node)?.let { owner ->
            accessorFor(owner, node.name.text, write = false)?.let { getter ->
                return scope.irCall(getter).apply { arguments[0] = receiverOf(node) }
            }
        }
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
        if (isDynamicReceiver(node.expression)) {
            return scope.irCall(intrinsics.jsGet, types.anyNullable).apply {
                arguments[0] = coerce(
                    node.expression, lowerExpression(node.expression), types.anyNullable
                )
                arguments[1] = scope.irString(node.name.text)
            }
        }
        val field = resolveField(node)
        return IrGetFieldImpl(
            UNDEFINED, UNDEFINED, field.symbol, field.type, receiverOf(node), null, null
        )
    }

    /**
     * Is this a type the checker would let a program CALL?
     *
     * A union counts only when every member is callable — anything less is a
     * call the checker itself rejects, so the question never arises. `any` and
     * `unknown` count too, and that is a deliberate widening of §8's "refuse
     * rather than pretend": a call on `any` is dynamic in TypeScript as well,
     * and `jsCall` reproduces what a JS engine does with it, including throwing
     * a TypeError when the value turns out not to be a function. Refusing here
     * would refuse what `docs/kir-structural-typing.md` §7 measured as the
     * LARGEST dynamic population in real TypeScript.
     */
    private fun isCallableType(type: Type): Boolean = when {
        type.flags.hasAny(TypeFlags.Any or TypeFlags.Unknown) -> true
        type is Type.Union -> type.types.isNotEmpty() && type.types.all { isCallableType(it) }
        type is Type.Object -> type.callSignatures?.isNotEmpty() == true
        else -> false
    }

    /** `jsCall(callee, …)` — the arity-adapting call JavaScript performs. */
    private fun lowerDynamicCall(node: CallExpression): IrExpression =
        adaptingCall(node) {
            coerce(node.expression, lowerExpression(node.expression), types.anyNullable)
        }

    /**
     * `jsCall(callee, …)` — the arity-ADAPTING call, specialized where it can be.
     *
     * One shape for both dynamic-call sites, so the specialization decision is
     * made once. Up to `MAX_SPECIALIZED_CALL_ARITY` arguments it emits the
     * fixed-arity entry point, which passes them positionally and so allocates
     * no `vararg` array; above that it emits the general form. The two are
     * semantically identical — the callee's arity is still adapted to at run
     * time, which `mitt` depends on — so this is a cost change and not a
     * behaviour change, and `KirDynamicCallArityTest` is what says so.
     */
    private fun adaptingCall(
        node: CallExpression,
        callee: () -> IrExpression,
    ): IrExpression {
        // The CALLEE is lowered first, and that is not cosmetic: lowering an
        // expression may emit statements (a temporary, a compound assignment),
        // so building the arguments first would reorder those statements with
        // respect to the callee's — a difference no argument POSITION restores.
        val loweredCallee = callee()
        val lowered = node.arguments.map { coerce(it, lowerExpression(it), types.anyNullable) }
        val specialized = intrinsics.jsCallSpecialized(lowered.size)
        if (specialized != null) {
            return scope.irCall(specialized, types.anyNullable).apply {
                arguments[0] = loweredCallee
                lowered.forEachIndexed { index, argument -> arguments[index + 1] = argument }
            }
        }
        return scope.irCall(intrinsics.jsCall, types.anyNullable).apply {
            arguments[0] = loweredCallee
            arguments[1] = scope.irVararg(irBuiltIns.anyNType, lowered)
        }
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
        node: Node,
        parameters: List<Parameter>,
        body: Node,
        inheritThis: Boolean = true
    ): IrExpression {
        parameters.forEach { parameter ->
            if (parameter.name !is Identifier) {
                refuse(tsFile, parameter, "a destructuring parameter is out of the spike subset")
            }
        }
        val restIndex = parameters.indexOfFirst { it.dotDotDotToken }
        if (restIndex >= 0) {
            if (restIndex != parameters.lastIndex) {
                refuse(tsFile, parameters[restIndex], "a rest parameter must be the last one")
            }
            return lowerVariadicFunctionValue(parameters, body, inheritThis, restIndex)
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
        val thisReceiver = if (inheritThis) frame.thisReceiver else null
        val ownerClass = if (inheritThis) frame.ownerClass else null
        inFunction(lambda, types.anyNullable, thisReceiver, ownerClass) {
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
     * A function value with a rest parameter — the one whose arity is not static.
     *
     * It cannot be a `FunctionN`, because `N` is the CALLER's choice: the
     * replacer `cronstrue` hands to `String.replace` is called with as many
     * arguments as the match produced, and picking an arity from the
     * declaration would drop the surplus silently. So the body is compiled to
     * take every actual argument as ONE array, and the declared names are read
     * back out of it in the prologue — the fixed ones by index, the rest one as
     * the remainder. The carrier is the runtime's `JsVarargFunction`, which
     * `jsCall` unpacks where the actual count is known.
     *
     * The parameters become LOCALS rather than IR parameters, which also makes
     * assignment to one of them ordinary (a JavaScript parameter is mutable,
     * and [bindParameters] copies to a local for exactly that reason).
     */
    private fun lowerVariadicFunctionValue(
        parameters: List<Parameter>,
        body: Node,
        inheritThis: Boolean,
        fixed: Int
    ): IrExpression {
        parameters.forEach { parameter ->
            if (parameter.initializer != null) {
                refuse(
                    tsFile, parameter,
                    "a default value on a parameter of a variadic function value is out of " +
                        "the spike subset"
                )
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
        val packed = lambda.addValueParameter(
            Name.identifier("\$arguments"),
            intrinsics.jsArrayType,
            builder.generatedOrigin
        )
        val thisReceiver = if (inheritThis) frame.thisReceiver else null
        val ownerClass = if (inheritThis) frame.ownerClass else null
        inFunction(lambda, types.anyNullable, thisReceiver, ownerClass) {
            lambda.body = variadicLambdaBody(parameters, body, packed, fixed)
        }
        val implType = irBuiltIns.functionN(1).symbol
            .typeWith(intrinsics.jsArrayType, types.anyNullable)
        return scope.irCall(intrinsics.jsVarargFunction).apply {
            arguments[0] = scope.irInt(fixed)
            arguments[1] = IrFunctionExpressionImpl(
                UNDEFINED,
                UNDEFINED,
                implType,
                lambda,
                IrStatementOrigin.LAMBDA
            )
        }
    }

    /** [lowerVariadicFunctionValue]'s body: the prologue that unpacks, then the statements. */
    private fun variadicLambdaBody(
        parameters: List<Parameter>,
        body: Node,
        packed: IrValueParameter,
        fixed: Int
    ): org.jetbrains.kotlin.ir.expressions.IrBlockBody {
        scopes.addLast(HashMap())
        val lowered = mutableListOf<IrStatement>()
        parameters.forEachIndexed { index, parameter ->
            val text = (parameter.name as Identifier).text
            val rest = index >= fixed
            val local = buildVariable(
                frame.irFunction as IrDeclarationParent,
                UNDEFINED,
                UNDEFINED,
                builder.generatedOrigin,
                Name.identifier(kotlinName(text)),
                if (rest) intrinsics.jsArrayType else types.anyNullable,
                isVar = true,
            )
            local.initializer = if (rest) {
                scope.irCall(intrinsics.jsVarargRest).apply {
                    arguments[0] = scope.irGet(packed)
                    arguments[1] = scope.irInt(fixed)
                }
            } else {
                scope.irCall(intrinsics.jsVarargFixed).apply {
                    arguments[0] = scope.irGet(packed)
                    arguments[1] = scope.irInt(index)
                }
            }
            lowered.add(local)
            scopes.last()[text] = local
        }
        if (body is Block) {
            body.statements.forEach { lowerStatement(it, lowered) }
            if (body.statements.lastOrNull() !is ReturnStatement) {
                lowered.add(scope.irReturn(scope.irNull()))
            }
        } else {
            val expression = body as? Expression
                ?: refuse(tsFile, body, "cannot lower this function body")
            lowered.add(
                scope.irReturn(
                    coerce(expression, lowerExpression(expression), types.anyNullable)
                )
            )
        }
        scopes.removeLast()
        return blockBodyOf(lowered)
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
            bindParameters(lambda, parameters, body, lowered)
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
        val prologue = mutableListOf<IrStatement>()
        bindParameters(lambda, parameters, expression, prologue)
        val value = coerce(expression, lowerExpression(expression), types.anyNullable)
        scopes.removeLast()
        return blockBodyOf(prologue + scope.irReturn(value))
    }

    /**
     * A call of a function VALUE — `handler(event)`, `callbacks[0](x)`.
     *
     * Reached only after the call resolved to no generated declaration, which
     * is the honest order: a call of a declared function is a direct call and
     * must stay one.
     *
     * **THE CALL IS ARITY-ADAPTING AND MAY NOT BE A DIRECT `FunctionN.invoke`
     * (2026-08-25, (CHK.39)).** TypeScript's function assignability accepts a
     * function of FEWER parameters wherever one of more is declared, so a value
     * whose declared type says `(a, b) => void` is at run time perfectly likely
     * to be a `Function1` — `mitt`'s own driver registers a one-parameter
     * wildcard handler against a two-parameter `WildcardHandler`, and `emit`
     * calls it with two arguments. A `coerce(…, types.function(arity))` there is
     * a hard cast that throws `ClassCastException` at run time, and NOTHING in
     * the type system is wrong when it does. The repo already knew this for a
     * BAG member (the comment above [lowerBagRead]'s call site); it is the same
     * fact about a function value, and it was invisible only because such a
     * callee used to be `any` — the checker did not type a contextually-typed
     * parameter until (CHK.39), so this branch was never reached for one.
     *
     * The declared arity survives as the ARGUMENT-COUNT check alone, which is a
     * statement about the call SITE (something the checker also rejects) rather
     * than about the value that arrives.
     */
    private fun lowerFunctionValueCall(node: CallExpression, arity: Int): IrExpression {
        if (node.arguments.size != arity) {
            refuse(
                tsFile, node,
                "a function value of arity $arity called with ${node.arguments.size} argument(s)"
            )
        }
        return lowerDynamicCall(node)
    }

    // ---- object literals and property bags ---------------------------------

    /**
     * `{ a: 1, m() { … } }` — a `jsObjectOf` call, name and value flat.
     *
     * A method in an object literal is a PROPERTY whose value is a function,
     * which is what it is in JavaScript too — so it lowers through the same
     * path as an arrow, with `this` withheld: JavaScript binds a method's
     * `this` at the CALL, and inheriting the enclosing frame's receiver would
     * silently bind the wrong object rather than refuse.
     */
    private fun lowerObjectLiteral(node: ObjectLiteralExpression): IrExpression {
        val entries = mutableListOf<IrExpression>()
        val names = mutableListOf<String>()
        node.properties.forEach { property ->
            when (property) {
                is PropertyAssignment -> {
                    names.add(propertyKey(property.name, property))
                    entries.add(scope.irString(propertyKey(property.name, property)))
                    entries.add(
                        coerce(
                            property.initializer,
                            lowerExpression(property.initializer),
                            types.anyNullable
                        )
                    )
                }
                is ShorthandPropertyAssignment -> {
                    val name = property.name.text
                    names.add(name)
                    entries.add(scope.irString(name))
                    entries.add(
                        coerce(property.name, lowerExpression(property.name), types.anyNullable)
                    )
                }
                is MethodDeclaration -> {
                    names.add(propertyKey(property.name, property))
                    entries.add(scope.irString(propertyKey(property.name, property)))
                    val body = property.body
                        ?: refuse(tsFile, property, "an object-literal method with no body")
                    entries.add(
                        lowerFunctionValue(property, property.parameters, body, inheritThis = false)
                    )
                }
                else -> refuse(
                    tsFile, property,
                    "this object-literal member is out of the spike subset"
                )
            }
        }
        // The NOMINAL half: a literal whose names are all statically known gets
        // a generated class with one real field per name — see [shapeClassFor].
        // Everything else keeps the bag, which is what makes this additive.
        val shape = if (names.size * 2 == entries.size) shapeClassFor(names) else null
        if (shape != null) {
            return IrConstructorCallImpl(
                UNDEFINED, UNDEFINED,
                shape.irClass.defaultType, shape.constructor.symbol,
                typeArgumentsCount = 0, constructorTypeArgumentsCount = 0,
            ).apply {
                entries.filterIndexed { index, _ -> index % 2 == 1 }
                    .forEachIndexed { index, value -> arguments[index] = value }
            }
        }
        return scope.irCall(intrinsics.jsObjectOf).apply {
            arguments[0] = scope.irVararg(irBuiltIns.anyNType, entries)
        }
    }


    // ---- the NOMINAL half: a generated class per object-literal shape -------

    /**
     * The generated class for an object literal whose names are [names].
     *
     * §3.3's hybrid, with the nominal half finally on: a JVM class holding one
     * real field per declared property, extending the runtime's bag so that
     * NOTHING about assignability changes — a shape instance is a `JsObject`,
     * passes wherever one is expected, and answers a dynamic reader through the
     * same virtual `get` as any other bag. That is what makes this affordable
     * where changing what an object type ERASES to is not: TypeScript's
     * assignability is structural and a generated class is not, so the erasure
     * stays exactly as it was and only the RUNTIME class of the value changes.
     *
     * One class per distinct name LIST per file, keyed on the names in order —
     * so `{ pos: 0, line: 1 }` and `{ pos: 2, line: 3 }` share one, and a
     * different order is a different shape because `Object.keys` reports it.
     *
     * The prize is the census in `docs/perf/kir-backend-levers.md` §2a: 2,555
     * property reads per benchmark parse, 93.6% of them on a bag of exactly
     * three keys, at ~4.9 ns each where a `getfield` is under one.
     */
    private fun shapeClassFor(names: List<String>): ShapeClass? {
        // A literal with more slots than this keeps the bag: the generated
        // `get` is a chain, so a wide shape would trade a hash probe for a long
        // walk. The censused population's largest LITERAL is far below it.
        if (names.isEmpty() || names.size > 12) return null
        if (names.toSet().size != names.size) return null
        return shapeClasses.getOrPut(names) { buildShapeClass(names) }
    }

    /** A generated shape: its class, its constructor and its fields, in order. */
    private class ShapeClass(
        val irClass: IrClass,
        val constructor: IrConstructor,
        val fields: List<IrField>,
    )

    private fun buildShapeClass(names: List<String>): ShapeClass {
        val irClass = builder.irFactory.buildClass {
            // The name carries the FILE, because a shape class is a real class
            // in one shared package while `shapeClasses` is per file: without
            // it, two files both mint `JsShape0`, the second wins the package,
            // and the first file's call site links to a constructor of the
            // wrong arity — `NoSuchMethodError` at run time, with everything
            // compiling. Same shape as the native backend's per-file
            // `moduleInit` prefixes (CLAUDE.md, 2026-08-21).
            this.name = Name.identifier("JsShape_${shapeFilePrefix}_${shapeClasses.size}")
            visibility = DescriptorVisibilities.PUBLIC
            // FINAL, unlike a lowered TypeScript class: nothing extends a shape,
            // and a final override is what lets the JIT devirtualize `get` at a
            // monomorphic call site — which is the entire point of the class.
            modality = Modality.FINAL
            origin = builder.generatedOrigin
        }
        irClass.parent = irFile
        irClass.createThisReceiverParameter()
        irClass.superTypes = listOf(intrinsics.jsObjectType)
        irFile.declarations.add(irClass)

        val fields = names.mapIndexed { index, _ ->
            irClass.addField {
                this.name = Name.identifier("slot$index")
                type = types.anyNullable
                visibility = DescriptorVisibilities.PUBLIC
                isFinal = false
                origin = builder.generatedOrigin
            }
        }
        val constructor = buildShapeConstructor(irClass, names, fields)
        buildShapeGet(irClass, names, fields)
        buildShapeSet(irClass, names, fields)
        buildShapeHas(irClass, names)
        buildShapeDelete(irClass)
        buildShapeKeys(irClass)
        buildShapeSpill(irClass, names, fields)
        return ShapeClass(irClass, constructor, fields)
    }

    /** `JsShape(v0, …, vn) : JsObject()` — delegate, initialize, assign. */
    private fun buildShapeConstructor(
        irClass: IrClass,
        names: List<String>,
        fields: List<IrField>,
    ): IrConstructor {
        val constructor = irClass.addConstructor {
            isPrimary = true
            returnType = irClass.defaultType
            visibility = DescriptorVisibilities.PUBLIC
            origin = builder.generatedOrigin
        }
        constructor.parameters = names.mapIndexed { index, _ ->
            builder.irFactory.createValueParameter(
                startOffset = UNDEFINED,
                endOffset = UNDEFINED,
                origin = builder.generatedOrigin,
                kind = IrParameterKind.Regular,
                name = Name.identifier("v$index"),
                type = types.anyNullable,
                isAssignable = false,
                symbol = org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl(),
                varargElementType = null,
                isCrossinline = false,
                isNoinline = false,
                isHidden = false,
            ).also { it.parent = constructor }
        }
        val statements = mutableListOf<IrStatement>()
        statements.add(
            IrDelegatingConstructorCallImpl(
                UNDEFINED, UNDEFINED, irBuiltIns.unitType,
                intrinsics.jsObjectConstructor, typeArgumentsCount = 0,
            )
        )
        statements.add(
            IrInstanceInitializerCallImpl(
                UNDEFINED, UNDEFINED, irClass.symbol, irBuiltIns.unitType
            )
        )
        val self = irClass.thisReceiver!!
        fields.forEachIndexed { index, field ->
            statements.add(
                IrSetFieldImpl(
                    UNDEFINED, UNDEFINED, field.symbol,
                    org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl(UNDEFINED, UNDEFINED, self.type, self.symbol),
                    org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl(
                        UNDEFINED, UNDEFINED,
                        constructor.parameters[index].type,
                        constructor.parameters[index].symbol
                    ),
                    irBuiltIns.unitType,
                )
            )
        }
        constructor.body = blockBodyOf(statements)
        return constructor
    }

    /**
     * `override fun get(name) = if (shapeActive()) { if (name == "a") return slot0; … }; bagGet(name)`
     *
     * The comparison is EQUALS and not identity, which is not a detail: a name
     * that came from DATA rather than from an emitted literal — `o[key]` where
     * the key was parsed — is equal without being the same reference, and an
     * identity chain would fall through to the bag and answer `undefined` for a
     * property the object plainly has. `String.equals` opens with the identity
     * check anyway, so the constant-name case pays nothing for the correctness.
     */
    private fun buildShapeGet(irClass: IrClass, names: List<String>, fields: List<IrField>) {
        val function = shapeOverride(irClass, "get", types.anyNullable, listOf("name" to types.string))
        val self = function.parameters[0]
        val name = function.parameters[1]
        val guarded = names.mapIndexed { index, declared ->
            shapeIf(function, name, declared, shapeReturn(function, fieldRead(fields[index], self)))
        }
        function.body = blockBodyOf(
            listOf(shapeActiveGuard(function, self, guarded)) +
                shapeReturn(function, shapeBagCall(function, "bagGet", self, listOf(name)))
        )
    }

    /** `override fun set(name, value)` — the same chain, writing instead. */
    private fun buildShapeSet(irClass: IrClass, names: List<String>, fields: List<IrField>) {
        val function = shapeOverride(
            irClass, "set", irBuiltIns.unitType,
            listOf("name" to types.string, "value" to types.anyNullable)
        )
        val self = function.parameters[0]
        val name = function.parameters[1]
        val value = function.parameters[2]
        val guarded = names.mapIndexed { index, declared ->
            shapeIf(
                function, name, declared,
                IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, listOf(
                    IrSetFieldImpl(
                        UNDEFINED, UNDEFINED, fields[index].symbol,
                        org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl(UNDEFINED, UNDEFINED, self.type, self.symbol),
                        org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl(UNDEFINED, UNDEFINED, value.type, value.symbol),
                        irBuiltIns.unitType,
                    ),
                    shapeReturn(function, unitValue()),
                ))
            )
        }
        function.body = blockBodyOf(
            listOf(shapeActiveGuard(function, self, guarded)) +
                shapeReturn(function, shapeBagCall(function, "bagSet", self, listOf(name, value)))
        )
    }

    /** `override fun has(name)` — true for a declared slot, else the bag's answer. */
    private fun buildShapeHas(irClass: IrClass, names: List<String>) {
        val function = shapeOverride(
            irClass, "has", irBuiltIns.booleanType, listOf("name" to types.string)
        )
        val self = function.parameters[0]
        val name = function.parameters[1]
        val guarded = names.map { declared ->
            shapeIf(
                function, name, declared,
                shapeReturn(function, org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl.boolean(UNDEFINED, UNDEFINED, irBuiltIns.booleanType, true))
            )
        }
        function.body = blockBodyOf(
            listOf(shapeActiveGuard(function, self, guarded)) +
                shapeReturn(function, shapeBagCall(function, "bagHas", self, listOf(name)))
        )
    }

    /**
     * `override fun delete(name) { spillNow(); return bagDelete(name) }`
     *
     * SPILL FIRST, which is why no slot needs a presence bit: after a delete the
     * object is an ordinary bag and the deletion is an ordinary removal. The
     * censused population deletes nothing at all, so the cold path is allowed to
     * be the simple one.
     */
    private fun buildShapeDelete(irClass: IrClass) {
        val function = shapeOverride(
            irClass, "delete", irBuiltIns.booleanType, listOf("name" to types.string)
        )
        val self = function.parameters[0]
        val name = function.parameters[1]
        function.body = blockBodyOf(listOf(
            shapeBagCall(function, "spillNow", self, emptyList()),
            shapeReturn(function, shapeBagCall(function, "bagDelete", self, listOf(name))),
        ))
    }

    /** `override fun keys() { spillNow(); return bagKeys() }` — order via the spill. */
    private fun buildShapeKeys(irClass: IrClass) {
        val function = shapeOverride(irClass, "keys", intrinsics.jsArrayType, emptyList())
        val self = function.parameters[0]
        function.body = blockBodyOf(listOf(
            shapeBagCall(function, "spillNow", self, emptyList()),
            shapeReturn(function, shapeBagCall(function, "bagKeys", self, emptyList())),
        ))
    }

    /** `override fun spill()` — one `spillSlot` per field, in declaration ORDER. */
    private fun buildShapeSpill(irClass: IrClass, names: List<String>, fields: List<IrField>) {
        val function = shapeOverride(irClass, "spill", irBuiltIns.unitType, emptyList())
        val self = function.parameters[0]
        function.body = blockBodyOf(
            names.mapIndexed { index, declared ->
                shapeBagCall(
                    function, "spillSlot", self,
                    emptyList(),
                    listOf(
                        org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl.string(UNDEFINED, UNDEFINED, types.string, declared),
                        fieldRead(fields[index], self),
                    )
                )
            }
        )
    }

    /** An override of a `JsObject` member, with its dispatch receiver first. */
    private fun shapeOverride(
        irClass: IrClass,
        name: String,
        returnType: IrType,
        valueParameters: List<Pair<String, IrType>>,
    ): IrSimpleFunction {
        val base = intrinsics.runtimeMember(intrinsics.jsObjectClass, name, valueParameters.size)
            ?: error("JsObject.$name/${valueParameters.size} is missing")
        val function = irClass.addFunction {
            this.name = Name.identifier(name)
            this.returnType = returnType
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.FINAL
            origin = builder.generatedOrigin
        }
        function.parameters = listOf(dispatchReceiver(function, irClass)) +
            valueParameters.map { (parameterName, parameterType) ->
                builder.irFactory.createValueParameter(
                    startOffset = UNDEFINED,
                    endOffset = UNDEFINED,
                    origin = builder.generatedOrigin,
                    kind = IrParameterKind.Regular,
                    name = Name.identifier(parameterName),
                    type = parameterType,
                    isAssignable = false,
                    symbol = org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl(),
                    varargElementType = null,
                    isCrossinline = false,
                    isNoinline = false,
                    isHidden = false,
                ).also { it.parent = function }
            }
        function.overriddenSymbols = listOf(base)
        return function
    }

    /** `if (shapeActive()) { <branches> }` — the one test the fast path pays. */
    private fun shapeActiveGuard(
        function: IrSimpleFunction,
        self: IrValueParameter,
        branches: List<IrStatement>,
    ): IrStatement = org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType).apply {
        this.branches.add(
            org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl(
                UNDEFINED, UNDEFINED,
                shapeBagCall(function, "shapeActive", self, emptyList()),
                IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, branches),
            )
        )
    }

    /** `if (name == "<declared>") <then>`, by EQUALS — see [buildShapeGet]. */
    private fun shapeIf(
        function: IrSimpleFunction,
        name: IrValueParameter,
        declared: String,
        then: IrStatement,
    ): IrStatement = org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType).apply {
        branches.add(
            org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl(
                UNDEFINED, UNDEFINED,
                org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl(
                    UNDEFINED, UNDEFINED, irBuiltIns.booleanType,
                    intrinsics.jsStrictEqualsStrings, typeArgumentsCount = 0,
                ).apply {
                    arguments[0] = org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl(
                        UNDEFINED, UNDEFINED, name.type, name.symbol)
                    arguments[1] = org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl.string(
                        UNDEFINED, UNDEFINED, types.string, declared)
                },
                IrBlockImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null, listOf(then)),
            )
        )
    }

    private fun fieldRead(field: IrField, self: IrValueParameter): IrExpression =
        IrGetFieldImpl(
            UNDEFINED, UNDEFINED, field.symbol, field.type,
            org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl(UNDEFINED, UNDEFINED, self.type, self.symbol),
        )

    private fun shapeReturn(function: IrSimpleFunction, value: IrExpression): IrStatement =
        org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl(UNDEFINED, UNDEFINED, irBuiltIns.nothingType, function.symbol, value)

    private fun unitValue(): IrExpression =
        org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, irBuiltIns.unitClass)

    /** A call to one of `JsObject`'s final helpers on `this`. */
    private fun shapeBagCall(
        function: IrSimpleFunction,
        name: String,
        self: IrValueParameter,
        forwarded: List<IrValueParameter>,
        literals: List<IrExpression> = emptyList(),
    ): IrExpression {
        val arity = forwarded.size + literals.size
        val callee = intrinsics.runtimeMember(intrinsics.jsObjectClass, name, arity)
            ?: error("JsObject.$name/$arity is missing")
        return org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl(
            UNDEFINED, UNDEFINED, callee.owner.returnType, callee,
            typeArgumentsCount = 0,
        ).apply {
            arguments[0] = org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl(UNDEFINED, UNDEFINED, self.type, self.symbol)
            forwarded.forEachIndexed { index, parameter ->
                arguments[index + 1] =
                    org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl(UNDEFINED, UNDEFINED, parameter.type, parameter.symbol)
            }
            literals.forEachIndexed { index, literal ->
                arguments[forwarded.size + index + 1] = literal
            }
        }
    }

    private fun propertyKey(name: Node, owner: Node): String = when (name) {
        is Identifier -> name.text
        is StringLiteralNode -> name.text
        is NumericLiteralNode -> numericKey(numericValue(name))
        else -> refuse(tsFile, owner, "a computed property name is out of the spike subset")
    }

    /**
     * A numeric property name, spelled as JavaScript spells it.
     *
     * `{ 1: "a" }` has the property `"1"`, not `"1.0"` — every JavaScript key
     * is a string, and a number reaches that form through `ToString`.
     */
    private fun numericKey(value: Double): String =
        if (value == kotlin.math.floor(value) && !value.isInfinite()) {
            value.toLong().toString()
        } else value.toString()

    /**
     * `` `a${b}c` `` — a left-to-right fold of `String.plus`.
     *
     * Each substitution goes through the runtime's `ToString` for the same
     * reason `+` does: `Double.toString` prints `1.0` where JavaScript prints
     * `1`, and a template is the one place a program's output is MADE of that
     * conversion.
     */
    private fun lowerTemplate(node: TemplateExpression): IrExpression {
        var result: IrExpression = scope.irString(node.head.text)
        for (span in node.templateSpans) {
            result = scope.irCall(intrinsics.stringPlus).apply {
                arguments[0] = result
                arguments[1] = asString(span.expression, lowerExpression(span.expression))
            }
            val literal = (span.literal as? StringLiteralNode)?.text
                ?: refuse(tsFile, span, "cannot lower this template span")
            if (literal.isNotEmpty()) {
                result = scope.irCall(intrinsics.stringPlus).apply {
                    arguments[0] = result
                    arguments[1] = scope.irString(literal)
                }
            }
        }
        return result
    }

    /**
     * `/pattern/flags` — a `JsRegExp` built from the literal's own text.
     *
     * The parser hands the whole lexeme through, delimiters and all, and the
     * FLAGS are what follow the last `/`. Splitting on the last one rather than
     * scanning for an unescaped delimiter is exact here because the parser has
     * already decided where the literal ends — the ambiguity a regex literal has
     * is a LEXING one, and it was resolved before this node existed.
     */
    private fun lowerRegularExpression(node: RegularExpressionLiteralNode): IrExpression {
        val text = node.text
        val closing = text.lastIndexOf('/')
        if (!text.startsWith("/") || closing <= 0) {
            refuse(tsFile, node, "cannot lower this regular-expression literal")
        }
        return scope.irCall(intrinsics.jsRegExp, intrinsics.jsRegExpType).apply {
            arguments[0] = scope.irString(text.substring(1, closing))
            arguments[1] = scope.irString(text.substring(closing + 1))
        }
    }

    /**
     * Did the checker decline to say what this receiver is?
     *
     * `any` and `unknown` erase to `Any?`, and so does a union whose members
     * disagree — in all three the checker has told the backend nothing it can
     * dispatch on, which is exactly when the runtime must dispatch instead.
     */
    private fun isDynamicReceiver(node: Expression): Boolean {
        val type = facts.typeOf(node) ?: return false
        val erased = types.map(type) ?: return false
        return erased.isErasedAny(irBuiltIns)
    }

    /** Does this expression's checked type erase to a `Double`? */
    private fun isNumberReceiver(node: Expression): Boolean {
        val type = facts.typeOf(node) ?: return false
        return types.map(type) == types.double
    }

    /** Does this expression's checked type erase to a `String`? */
    private fun isStringReceiver(node: Expression): Boolean {
        val type = facts.typeOf(node) ?: return false
        return types.map(type) == types.string
    }

    /** Does this expression's checked type erase to the runtime's property bag? */
    private fun isPropertyBag(node: Expression): Boolean {
        val type = facts.typeOf(node) ?: return false
        val erased = types.map(type) ?: return false
        return erased.classifierOrNull == intrinsics.jsObjectClass
    }

    private fun lowerBagRead(node: PropertyAccessExpression): IrExpression =
        scope.irCall(intrinsics.jsObjectGet, types.anyNullable).apply {
            arguments[0] = lowerExpression(node.expression)
            arguments[1] = scope.irString(node.name.text)
        }

    // ---- destructuring ------------------------------------------------------

    /**
     * Binds every name a destructuring PATTERN introduces, from one value.
     *
     * `const { a, b: c } = value` and `const [x, y] = value` are both a
     * sequence of ordinary local declarations reading a member of a temporary —
     * which is what they are in JavaScript too. The temporary is what keeps the
     * value evaluated ONCE, and the nested case falls out for free: an element
     * whose own name is a pattern recurses with the member as its value.
     */
    private fun bindPattern(pattern: Node, value: IrExpression, out: MutableList<IrStatement>) {
        val subject = temporary("destructured", value.type, value)
        out.add(subject)
        when (pattern) {
            is ObjectBindingPattern -> pattern.elements.forEachIndexed { _, element ->
                if (element.dotDotDotToken) {
                    refuse(tsFile, element, "a rest element in a pattern is out of the subset")
                }
                val propertyName = (element.propertyName as? Identifier)?.text
                    ?: (element.name as? Identifier)?.text
                    ?: refuse(tsFile, element, "cannot lower this binding element")
                bindPatternElement(
                    element,
                    readMemberOfValue(element, scope.irGet(subject), propertyName),
                    out
                )
            }
            is ArrayBindingPattern -> pattern.elements.forEachIndexed { index, element ->
                if (element !is BindingElement) return@forEachIndexed
                if (element.dotDotDotToken) {
                    refuse(tsFile, element, "a rest element in a pattern is out of the subset")
                }
                bindPatternElement(
                    element,
                    readElementOfValue(element, scope.irGet(subject), index.toDouble()),
                    out
                )
            }
            else -> refuse(tsFile, pattern, "cannot lower this binding pattern")
        }
    }

    private fun bindPatternElement(
        element: BindingElement,
        value: IrExpression,
        out: MutableList<IrStatement>
    ) {
        val nested = element.name
        if (nested is ObjectBindingPattern || nested is ArrayBindingPattern) {
            bindPattern(nested, value, out)
            return
        }
        val name = nested as? Identifier
            ?: refuse(tsFile, element, "cannot lower this binding element")
        val declaredType = facts.typeOf(nested)?.let { erase(element, it) } ?: value.type
        // A per-element DEFAULT — `{ maxDepth = 1000 }` — applies where the
        // member is absent, which in this runtime is where it reads null. Its
        // omission is not a diagnostic anywhere: the program simply runs with a
        // null where it expected a number, and fails at the first arithmetic.
        val withDefault = element.initializer?.let { fallback ->
            val slot = temporary("element", types.anyNullable, coerce(element, value, types.anyNullable))
            IrBlockImpl(
                UNDEFINED,
                UNDEFINED,
                types.anyNullable,
                null,
                listOf(
                    slot,
                    scope.irWhen(
                        types.anyNullable,
                        listOf(
                            scope.irBranch(
                                scope.irEqualsNull(scope.irGet(slot)),
                                coerce(fallback, lowerExpression(fallback), types.anyNullable)
                            ),
                            scope.irElseBranch(scope.irGet(slot))
                        )
                    )
                )
            )
        } ?: value
        val local = temporary(
            kotlinName(name.text),
            declaredType,
            coerce(element, withDefault, declaredType)
        )
        out.add(local)
        scopes.last()[name.text] = local
    }

    /**
     * One named member of an already-lowered VALUE.
     *
     * The AST-based reads all start from a `PropertyAccessExpression`; a
     * destructuring pattern has no such node, so the mechanism is selected by
     * the value's ERASED type instead — a bag, or a class this backend
     * generated. Anything else refuses, which is the same answer the AST path
     * would give.
     */
    private fun readMemberOfValue(at: Node, value: IrExpression, name: String): IrExpression {
        if (value.type.isErasedAny(irBuiltIns)) {
            return scope.irCall(intrinsics.jsGet, types.anyNullable).apply {
                arguments[0] = value
                arguments[1] = scope.irString(name)
            }
        }
        if (value.type.classifierOrNull == types.string.classifierOrNull) {
            val member = intrinsics.stringMember(name, 0)
                ?: refuse(at, "'String.$name' is not a property this backend provides")
            return scope.irCall(member).apply { arguments[0] = value }
        }
        intrinsics.runtimeClassOf(value.type)?.let { owner ->
            val getter = intrinsics.runtimePropertyGetter(owner, name)
                ?: refuse(
                    at,
                    "'$name' is not a property this backend gives a " +
                        "'${owner.owner.name.asString()}'"
                )
            return scope.irCall(getter).apply { arguments[0] = value }
        }
        if (value.type.classifierOrNull == intrinsics.jsObjectClass) {
            return scope.irCall(intrinsics.jsObjectGet, types.anyNullable).apply {
                arguments[0] = value
                arguments[1] = scope.irString(name)
            }
        }
        generatedClassOf(value.type)?.let { owner ->
            tables.classChain(owner).forEach { current ->
                current.members.filterIsInstance<PropertyDeclaration>()
                    .firstOrNull { (it.name as? Identifier)?.text == name }
                    ?.let { property ->
                        val field = fields.getValue(property)
                        return IrGetFieldImpl(
                            UNDEFINED, UNDEFINED, field.symbol, field.type, value, null, null
                        )
                    }
            }
        }
        refuse(at, "cannot destructure '$name' out of this value")
    }

    private fun readElementOfValue(at: Node, value: IrExpression, index: Double): IrExpression {
        if (value.type.classifierOrNull != intrinsics.jsArrayClass) {
            refuse(at, "cannot destructure an element out of this value")
        }
        val get = intrinsics.runtimeMember(intrinsics.jsArrayClass, "get", 1)
            ?: refuse(at, "JsArray.get is missing")
        return scope.irCall(get).apply {
            arguments[0] = value
            arguments[1] = scope.irDouble(index)
        }
    }

    private fun refuse(at: Node, message: String): Nothing = refuse(tsFile, at, message)

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
        // A STRING indexes to a one-character string — `date[10] === ' '` is the
        // idiom — and out of range is `undefined`, which is why it goes through
        // the runtime rather than through `String.get`.
        if (isStringReceiver(node.expression)) {
            val charAt = intrinsics.stringMember("charAt", 1)
                ?: refuse(tsFile, node, "String.charAt is missing from the runtime")
            return scope.irCall(charAt).apply {
                arguments[0] = coerce(
                    node.expression, lowerExpression(node.expression), types.string
                )
                arguments[1] = elementIndex(node)
            }
        }
        // A dynamic receiver indexes through the runtime, which decides on what
        // the value IS — `a[0]` and `o["k"]` are one syntax over two containers.
        if (isDynamicReceiver(node.expression) || isPropertyBag(node.expression)) {
            return scope.irCall(intrinsics.jsIndexGet, types.anyNullable).apply {
                arguments[0] = coerce(
                    node.expression, lowerExpression(node.expression), types.anyNullable
                )
                arguments[1] = coerce(
                    node.argumentExpression,
                    lowerExpression(node.argumentExpression),
                    types.anyNullable
                )
            }
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
    /**
     * Is [expression] a function VALUE — of static arity, or the variadic carrier?
     *
     * The erasure alone cannot answer it: a VARIADIC signature erases to `Any?`
     * BECAUSE it has no arity to erase to ([ErasedTypes.mapCallable]), so the
     * shape that most needs recognising is the one the erased type hides. The
     * declaration is therefore asked directly where the argument is written as
     * a function, and the type's own call signature otherwise.
     */
    private fun isFunctionValued(expression: Expression): Boolean {
        if (expression is ArrowFunction || expression is FunctionExpression) return true
        val type = facts.typeOf(expression) ?: return false
        val erased = types.map(type) ?: return false
        if (types.functionArity(erased) != null) return true
        if (erased.classifierOrNull == intrinsics.jsVarargFunctionClass) return true
        return (type as? Type.Object)?.callSignatures?.firstOrNull()
            ?.let { isVariadic(it.declaration) } == true
    }

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
        // Up the BASE CHAIN: a subclass's method reads a field its base
        // declares, and looking only at the receiver's own class would report a
        // property the program plainly has as absent.
        tables.classChain(owner).forEach { current ->
            current.members.filterIsInstance<PropertyDeclaration>()
                .firstOrNull { (it.name as? Identifier)?.text == node.name.text }
                ?.let { return fields.getValue(it) }
        }
        refuse(
            tsFile, node,
            "class '${owner.name?.text}' declares no property '${node.name.text}'"
        )
    }

    /** The getter or setter [name] resolves to on [owner]'s chain, or null. */
    private fun accessorFor(
        owner: ClassDeclaration,
        name: String,
        write: Boolean
    ): IrSimpleFunction? {
        val table = if (write) tables.setters else tables.getters
        tables.classChain(owner).forEach { current ->
            table[current]?.get(name)?.let { return it }
        }
        return null
    }

    /** The class a STATIC member access names — `Counter.total` — or null. */
    private fun staticOwnerOf(node: PropertyAccessExpression): ClassDeclaration? =
        classDeclarationOf(node.expression)

    /** [ownerClassOf], but answering null instead of refusing. */
    private fun instanceOwnerOf(node: PropertyAccessExpression): ClassDeclaration? {
        val receiver = node.expression
        if (receiver is Identifier && (receiver.text == "this" || receiver.text == "super")) {
            return frame.ownerClass
        }
        val type = facts.typeOf(receiver) ?: return null
        val symbol = (type as? Type.Object)?.symbol
        val declaration = symbol?.valueDeclaration ?: symbol?.declarations?.firstOrNull()
        return (declaration as? ClassDeclaration)?.takeIf { it in classes }
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
        // `super.x` reads THIS object; what `super` changes is which member is
        // selected, and that is the call's `superQualifierSymbol`, not the value.
        if (receiver is Identifier && (receiver.text == "this" || receiver.text == "super")) {
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
        val regular = parameters.filter { it.kind == IrParameterKind.Regular }
        // A REST slot absorbs every argument from its own position on, so the
        // callee's arity stops being an upper bound and the surplus is not an
        // error — it is the array. Built HERE rather than in the callee because
        // this is the only place that knows how many arguments there are.
        val restIndex = regular.indexOfFirst { it in restParameters }
        if (restIndex >= 0) {
            if (node.arguments.size < restIndex) {
                refuse(
                    tsFile, node,
                    "expected at least $restIndex argument(s) but found ${node.arguments.size}"
                )
            }
            val collected = node.arguments.drop(restIndex).map { argument ->
                coerce(argument, lowerExpression(argument), types.anyNullable)
            }
            val restArray = scope.irCall(intrinsics.jsArrayOf).apply {
                arguments[0] = scope.irVararg(irBuiltIns.anyNType, collected)
            }
            regular.take(restIndex).forEachIndexed { index, parameter ->
                val argument = node.arguments.getOrNull(index)
                arguments[offset + index] = if (argument != null) {
                    coerce(argument, lowerExpression(argument), parameter.type)
                } else if (parameter in optionalParameters) {
                    scope.irNull()
                } else {
                    refuse(
                        tsFile, node,
                        "expected at least $restIndex argument(s) but found " +
                            "${node.arguments.size}"
                    )
                }
            }
            arguments[offset + restIndex] = restArray
            return
        }
        if (node.arguments.size > regular.size) {
            refuse(
                tsFile, node,
                "expected ${regular.size} argument(s) but found ${node.arguments.size}"
            )
        }
        regular.forEachIndexed { index, parameter ->
            val argument = node.arguments.getOrNull(index)
            arguments[offset + index] = if (argument != null) {
                coerce(argument, lowerExpression(argument), parameter.type)
            } else if (parameter in optionalParameters) {
                // An omitted optional argument is `undefined`, which §3.1 maps
                // to `null` — and the parameter's type was made nullable to
                // hold one.
                scope.irNull()
            } else {
                refuse(
                    tsFile, node,
                    "expected ${regular.size} argument(s) but found ${node.arguments.size}"
                )
            }
        }
    }

    // ---- coercion, truthiness, names ---------------------------------------

    /**
     * Puts [value] into the shape [target] wants, and refuses when it cannot.
     *
     * The one place the decision is taken (§6). Everything else in this class
     * calls it; nothing else inserts a cast.
     */
    private fun coerce(node: Node, value: IrExpression, target: IrType): IrExpression {
        // A generated class's own hierarchy, which `coercionFor` cannot see: it
        // decides on classifiers and builtins alone, so `Square` reaching a
        // `Shape` slot read as two unrelated types. Widening is free; narrowing
        // is the cast the checker's own narrowing already justified.
        when (generatedRelation(value.type, target)) {
            ClassRelation.WIDENS -> return value
            ClassRelation.NARROWS -> return scope.irAs(value, target)
            ClassRelation.UNRELATED -> {}
        }
        // A generated SHAPE widening to the bag it extends, which is the whole
        // reason the nominal half is expressible without touching the erasure:
        // every object type still erases to `JsObject`, and a shape instance IS
        // one. `coercionFor` decides on classifiers alone and cannot see it.
        if (isShapeType(value.type) && target.classifierOrNull == intrinsics.jsObjectClass) {
            return value
        }
        return coerceErased(node, value, target)
    }

    private enum class ClassRelation { WIDENS, NARROWS, UNRELATED }

    /** How two GENERATED class types relate, if they are both generated. */
    private fun generatedRelation(from: IrType, target: IrType): ClassRelation {
        val fromDeclaration = generatedClassOf(from) ?: return ClassRelation.UNRELATED
        val targetDeclaration = generatedClassOf(target) ?: return ClassRelation.UNRELATED
        if (fromDeclaration === targetDeclaration) return ClassRelation.UNRELATED
        if (tables.classChain(fromDeclaration).any { it === targetDeclaration }) {
            return ClassRelation.WIDENS
        }
        if (tables.classChain(targetDeclaration).any { it === fromDeclaration }) {
            return ClassRelation.NARROWS
        }
        return ClassRelation.UNRELATED
    }

    /** Is [type] one of this file's generated shape classes — see [shapeClassFor]? */
    private fun isShapeType(type: IrType): Boolean {
        val classifier = type.classifierOrNull ?: return false
        return shapeClasses.values.any { it.irClass.symbol == classifier }
    }

    private fun generatedClassOf(type: IrType): ClassDeclaration? {
        val classifier = type.classifierOrNull ?: return null
        return classes.entries.firstOrNull { it.value.symbol == classifier }?.key
    }

    private fun coerceErased(node: Node, value: IrExpression, target: IrType): IrExpression {
        // TypeScript lets a callback of ANY arity sit in a slot declared for
        // another — `cronstrue` hands `(s) => s` to a `(t, form?) => string`
        // parameter — and the JVM does not: `Function1` is not a `Function2`.
        // The call side has adapted since mitt (`jsCall` pads and drops); this
        // is the same adaptation where the value is STORED, and it is applied
        // only when the two arities actually differ.
        val wanted = types.functionArity(target)
        if (wanted != null && types.functionArity(value.type).let { it != null && it != wanted }) {
            val adapter = intrinsics.functionAdapter(wanted)
                ?: refuse(
                    tsFile, node,
                    "cannot reshape a function value to $wanted parameter(s) — this backend " +
                        "adapts up to 5"
                )
            return scope.irCall(adapter, target).apply {
                arguments[0] = value
            }
        }
        return when (coercionOf(value, target, irBuiltIns)) {
            Coercion.NONE -> value
            Coercion.CAST -> scope.irAs(value, target)
            Coercion.IMPOSSIBLE -> refuse(
                tsFile, node,
                "cannot coerce ${value.type.render()} to ${target.render()}"
            )
        }
    }

    /**
     * A value in a condition position.
     *
     * `jsTruthy` UNLESS the static type is already `boolean` — the one
     * optimization §6 asks for immediately, because conditions are everywhere
     * and the checker gives the answer for free.
     */
    private fun condition(node: Expression): IrExpression = truthy(lowerExpression(node))

    private fun truthy(value: IrExpression): IrExpression = when (value.type) {
        // A `boolean` IS the condition; a `number` and a `string` each have one
        // arm of `jsTruthy` and reach it without the box. `if (!state)` in a
        // hand-written scanner is the numeric case, once per character.
        types.boolean -> value
        types.double -> scope.irCall(intrinsics.jsTruthyNumber, types.boolean).apply {
            arguments[0] = value
        }
        types.string -> scope.irCall(intrinsics.jsTruthyString, types.boolean).apply {
            arguments[0] = value
        }
        // An OPTIONAL primitive is the shape an optional parameter has, and it
        // is asked once per character in a scanner: `!banNewLines`.
        types.double.makeNullable() ->
            scope.irCall(intrinsics.jsTruthyNumberOrNull, types.boolean).apply {
                arguments[0] = value
            }
        types.string.makeNullable() ->
            scope.irCall(intrinsics.jsTruthyStringOrNull, types.boolean).apply {
                arguments[0] = value
            }
        types.boolean.makeNullable() ->
            scope.irCall(intrinsics.jsTruthyBooleanOrNull, types.boolean).apply {
                arguments[0] = value
            }
        else -> scope.irCall(intrinsics.jsTruthy).apply {
            arguments[0] = if (value.type == types.anyNullable) value
            else scope.irAs(value, types.anyNullable)
        }
    }

    private fun not(value: IrExpression): IrExpression =
        scope.irCall(irBuiltIns.booleanNotSymbol).apply { arguments[0] = value }

    private fun erase(node: Node, type: Type): IrType = types.map(type)
        ?: refuse(
            tsFile, node,
            "cannot map the type '${facts.render(type)}' (${type::class.simpleName}" +
                ((type as? Type.Object)?.symbol?.let { symbol ->
                    ", symbol ${symbol.name}, declared by " +
                        (symbol.valueDeclaration ?: symbol.declarations.firstOrNull())
                            ?.let { it::class.simpleName }
                } ?: "") + ")"
        )

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
