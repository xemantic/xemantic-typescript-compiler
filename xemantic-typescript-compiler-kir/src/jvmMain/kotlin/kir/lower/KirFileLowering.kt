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
import com.xemantic.typescript.compiler.NumericLiteralNode
import com.xemantic.typescript.compiler.ObjectLiteralExpression
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.ParenthesizedExpression
import com.xemantic.typescript.compiler.PostfixUnaryExpression
import com.xemantic.typescript.compiler.PrefixUnaryExpression
import com.xemantic.typescript.compiler.PropertyAccessExpression
import com.xemantic.typescript.compiler.PropertyAssignment
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.ReturnStatement
import com.xemantic.typescript.compiler.ShorthandPropertyAssignment
import com.xemantic.typescript.compiler.SpreadElement
import com.xemantic.typescript.compiler.Statement
import com.xemantic.typescript.compiler.StringLiteralNode
import com.xemantic.typescript.compiler.SyntaxKind
import com.xemantic.typescript.compiler.TemplateExpression
import com.xemantic.typescript.compiler.Type
import com.xemantic.typescript.compiler.TypeAliasDeclaration
import com.xemantic.typescript.compiler.TypeFlags
import com.xemantic.typescript.compiler.TypeLiteral
import com.xemantic.typescript.compiler.TypeOfExpression
import com.xemantic.typescript.compiler.VariableStatement
import com.xemantic.typescript.compiler.CaseClause
import com.xemantic.typescript.compiler.DefaultClause
import com.xemantic.typescript.compiler.DoStatement
import com.xemantic.typescript.compiler.ForOfStatement
import com.xemantic.typescript.compiler.SwitchStatement
import com.xemantic.typescript.compiler.ThrowStatement
import com.xemantic.typescript.compiler.TryStatement
import com.xemantic.typescript.compiler.VariableDeclaration
import com.xemantic.typescript.compiler.VariableDeclarationList
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
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.makeNullable
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
    private val classes get() = tables.classes
    private val methods get() = tables.methods
    private val constructorsByDeclaration get() = tables.constructorsByDeclaration
    private val fields get() = tables.fields
    private val optionalParameters get() = tables.optionalParameters

    private val types = ErasedTypes(
        irBuiltIns,
        classForDeclaration = { declaration ->
            (declaration as? ClassDeclaration)?.let { classes[it] }
        },
        jsArrayType = { intrinsics.jsArrayType },
        jsObjectType = { intrinsics.jsObjectType },
        libraryType = { name -> intrinsics.libraryClass(name)?.owner?.defaultType },
        isOwnStructuralDeclaration = ::isOwnStructuralDeclaration,
    )

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
    )

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
            statement !is InterfaceDeclaration && statement !is TypeAliasDeclaration &&
            statement !is ImportDeclaration && statement !is ExportDeclaration &&
            statement !is ImportEqualsDeclaration
    }

    // =======================================================================
    // Pass 1 — declare
    // =======================================================================

    private fun declare(statement: Statement) {
        when (statement) {
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
            base?.let { classes.getValue(it).defaultType } ?: irBuiltIns.anyType
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
            base = classDeclarationOf(type.expression)
                ?: refuse(
                    tsFile, declaration,
                    "`extends` is lowered for a class this backend generated only"
                )
        }
        return base
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
            refuse(tsFile, member, "cannot lower a method with no body")
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
        for (parameter in parameters) {
            val name = parameter.name as? Identifier
                ?: refuse(tsFile, parameter, "destructuring parameters are out of the spike subset")
            if (parameter.dotDotDotToken) {
                refuse(tsFile, parameter, "rest parameters are out of the spike subset")
            }
            val type = facts.typeOf(parameter)
                ?: refuse(tsFile, parameter, "the checker gave no type for parameter '${name.text}'")
            val optional = parameter.questionToken || parameter.initializer != null
            val erased = erase(parameter, type).let { if (optional) it.makeNullable() else it }
            val irParameter = function.addValueParameter(
                kotlinName(name.text),
                erased,
                builder.generatedOrigin
            )
            if (optional) optionalParameters.add(irParameter)
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
            current = FunctionFrame(
                constructor,
                DeclarationIrBuilder(builder.pluginContext, constructor.symbol),
                irBuiltIns.unitType,
                irClass.thisReceiver,
                declaration
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
            val superCall = bodyStatements.firstOrNull()
                ?.let { (it as? ExpressionStatement)?.expression as? CallExpression }
                ?.takeIf { (it.expression as? Identifier)?.text == "super" }
            if (base != null) {
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
            bodyStatements.forEach { statement ->
                if (statement === superCall?.let { bodyStatements.first() }) return@forEach
                lowerStatement(statement, statements)
            }
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

    /** `IrFactory.createBlockBody` takes no statements; they are added after. */
    private fun blockBodyOf(
        statements: List<IrStatement>
    ): org.jetbrains.kotlin.ir.expressions.IrBlockBody =
        builder.irFactory.createBlockBody(UNDEFINED, UNDEFINED).apply {
            this.statements.addAll(statements)
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
            val name = parameter.name as? Identifier ?: return@forEachIndexed
            val irParameter = valueParameters[index]
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
            is EmptyStatement -> {}
            is FunctionDeclaration, is ClassDeclaration,
            is InterfaceDeclaration, is TypeAliasDeclaration,
            is ImportDeclaration, is ExportDeclaration, is ImportEqualsDeclaration -> {}
            else -> refuse(tsFile, statement, "cannot lower this statement")
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
        val declaration = list.declarations.singleOrNull()
            ?: refuse(tsFile, statement, "`for…of` needs exactly one binding")
        val name = declaration.name as? Identifier
            ?: refuse(tsFile, declaration, "destructuring in `for…of` is out of the spike subset")

        val outer = mutableListOf<IrStatement>()
        scopes.addLast(HashMap())
        val array = temporary("array", intrinsics.jsArrayType, lowerExpression(subject))
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
        val trampoline = if (hasOwnContinue(statement.statement)) {
            IrDoWhileLoopImpl(UNDEFINED, UNDEFINED, irBuiltIns.unitType, null).also {
                it.condition = scope.irBoolean(false)
            }
        } else null
        loops.addLast(LoopFrame(loop, trampoline ?: loop))
        val body = mutableListOf<IrStatement>()
        scopes.addLast(HashMap())
        val elementType = facts.typeOf(declaration.name)?.let { erase(declaration, it) }
            ?: types.anyNullable
        val element = temporary(
            kotlinName(name.text),
            elementType,
            coerce(
                declaration,
                scope.irCall(get).apply {
                    arguments[0] = scope.irGet(array)
                    arguments[1] = scope.irGet(index)
                },
                elementType
            )
        )
        body.add(element)
        scopes.last()[name.text] = element
        lowerStatement(statement.statement, body)
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
                            scope.irCall(intrinsics.jsStrictEquals, types.boolean).apply {
                                arguments[0] = scope.irGet(subject)
                                arguments[1] = coerce(
                                    clause.expression,
                                    lowerExpression(clause.expression),
                                    types.anyNullable
                                )
                            },
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
            SyntaxKind.AmpersandAmpersand, SyntaxKind.BarBar -> shortCircuit(node)
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
    private fun lowerAddition(node: BinaryExpression): IrExpression =
        addValues(node.left, lowerExpression(node.left), node.right, lowerExpression(node.right))

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
     */
    private fun asString(node: Expression, value: IrExpression): IrExpression =
        if (value.type == types.string) value
        else scope.irCall(intrinsics.jsToString).apply {
            arguments[0] = coerce(node, value, types.anyNullable)
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
        val type = erase(node, facts.typeOf(node)
            ?: refuse(tsFile, node, "the checker gave no type for this expression"))
        val leftValue = lowerExpression(node.left)
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
        val test = truthy(scope.irGet(temporary))
        val branches = if (node.operator == SyntaxKind.AmpersandAmpersand) {
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
        val test = scope.irCall(intrinsics.jsLooseEquals, types.boolean).apply {
            arguments[0] = coerce(node.left, lowerExpression(node.left), types.anyNullable)
            arguments[1] = coerce(node.right, lowerExpression(node.right), types.anyNullable)
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
                SyntaxKind.PlusEquals -> addValues(target, current, node.right, right)
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
    private fun assignTo(target: Expression, value: (IrType) -> IrExpression): IrExpression =
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
            is ElementAccessExpression -> {
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
            val from = if (viaSuper) {
                tables.superclasses[owner]
                    ?: refuse(tsFile, node, "`super` in a class with no base")
            } else owner
            val target = methodInChain(from, access.name.text, node.arguments.size)
                ?: refuse(
                    tsFile, node,
                    "no method '${access.name.text}' with ${node.arguments.size} argument(s) " +
                        "on '${from.name?.text}' or above it"
                )
            return scope.irCall(target.symbol).apply {
                // `super` is non-virtual, or an override calling its base
                // through it recurses into itself forever. `this` is virtual,
                // which is what an override is FOR.
                if (viaSuper) superQualifierSymbol = classes.getValue(from).symbol
                arguments[0] = receiverOf(access)
                bindArguments(node, target.parameters, offset = 1)
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
            return scope.irCall(intrinsics.jsCall, types.anyNullable).apply {
                arguments[0] = coerce(callee, lowerBagRead(callee), types.anyNullable)
                arguments[1] = scope.irVararg(
                    irBuiltIns.anyNType,
                    node.arguments.map { coerce(it, lowerExpression(it), types.anyNullable) }
                )
            }
        }
        if (callee is PropertyAccessExpression && isStringReceiver(callee.expression)) {
            val target = intrinsics.stringMember(callee.name.text, node.arguments.size)
                ?: refuse(
                    tsFile, node,
                    "'String.${callee.name.text}' with ${node.arguments.size} argument(s) is " +
                        "not a member this backend gives a runtime function"
                )
            return scope.irCall(target).apply {
                arguments[0] = lowerExpression(callee.expression)
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
                if (node.arguments?.isNotEmpty() == true) {
                    refuse(
                        tsFile, node,
                        "`new ${owner.owner.name.asString()}` takes no arguments in this backend"
                    )
                }
                val constructor = intrinsics.runtimeConstructor(owner)
                    ?: refuse(tsFile, node, "this runtime class has no no-argument constructor")
                return IrConstructorCallImpl(
                    UNDEFINED,
                    UNDEFINED,
                    owner.owner.defaultType,
                    constructor,
                    typeArgumentsCount = 0,
                    constructorTypeArgumentsCount = 0,
                )
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

    /** The generated class a callee expression NAMES, or null. */
    private fun classDeclarationOf(callee: Expression): ClassDeclaration? {
        val name = callee as? Identifier ?: return null
        val symbol = facts.nameAt(name) ?: return null
        val declaration = symbol.valueDeclaration ?: symbol.declarations.firstOrNull()
        return (declaration as? ClassDeclaration)?.takeIf { it in classes }
    }

    private fun lowerPropertyRead(node: PropertyAccessExpression): IrExpression {
        if (node.questionDotToken) {
            refuse(tsFile, node, "optional chaining `?.` is out of the spike subset")
        }
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
        if (isStringReceiver(node.expression)) {
            val target = intrinsics.stringMember(node.name.text, 0)
                ?: refuse(
                    tsFile, node,
                    "'String.${node.name.text}' is not a property this backend gives a " +
                        "runtime function"
                )
            return scope.irCall(target).apply { arguments[0] = lowerExpression(node.expression) }
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
        scope.irCall(intrinsics.jsCall, types.anyNullable).apply {
            arguments[0] = coerce(
                node.expression, lowerExpression(node.expression), types.anyNullable
            )
            arguments[1] = scope.irVararg(
                irBuiltIns.anyNType,
                node.arguments.map { coerce(it, lowerExpression(it), types.anyNullable) }
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
        node: Node,
        parameters: List<Parameter>,
        body: Node,
        inheritThis: Boolean = true
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
        node.properties.forEach { property ->
            when (property) {
                is PropertyAssignment -> {
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
                    entries.add(scope.irString(name))
                    entries.add(
                        coerce(property.name, lowerExpression(property.name), types.anyNullable)
                    )
                }
                is MethodDeclaration -> {
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
        return scope.irCall(intrinsics.jsObjectOf).apply {
            arguments[0] = scope.irVararg(irBuiltIns.anyNType, entries)
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

    private fun generatedClassOf(type: IrType): ClassDeclaration? {
        val classifier = type.classifierOrNull ?: return null
        return classes.entries.firstOrNull { it.value.symbol == classifier }?.key
    }

    private fun coerceErased(node: Node, value: IrExpression, target: IrType): IrExpression =
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
