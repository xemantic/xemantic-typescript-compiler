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


// `IrSymbol.owner` and `IrDeclarationContainer.declarations` are guarded by
// `UnsafeDuringIrConstructionAPI` because they are unsound WHILE the frontend is
// building the tree. Everything here runs strictly afterwards, inside an
// `IrGenerationExtension` — the same reason `IrProgramBuilder` opts in.
@file:OptIn(
    org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)

package com.xemantic.typescript.compiler.kir.emit

import com.xemantic.typescript.compiler.kir.front.checkTypeScriptProject
import com.xemantic.typescript.compiler.kir.lower.KirProgramLowering
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * The KIR backend as a KOTLIN COMPILER PLUGIN, so that a checked TypeScript
 * program can be lowered inside `konanc` and come out as a native binary.
 *
 * The JVM path drives kotlinc's phases itself ([KotlinIrEmitter]); Kotlin/Native
 * has no equivalent phase API in 2.4, so the relationship is inverted — konanc
 * is the driver and the backend rides in as an `IrGenerationExtension`. What is
 * lowered is identical: the same [KirProgramLowering] over the same
 * [IrProgramBuilder].
 *
 * Configured by ENVIRONMENT, not by plugin options, for the reason CLAUDE.md
 * records about Gradle: an env var crosses a process boundary and a system
 * property does not always.
 */
public class KirNativeRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String get() = "xtsc.kir.native"

    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(KirNativeExtension())
    }

}

public class KirNativeExtension : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val projectPath = requireEnv("XTSC_KIR_PROJECT")
        val entryFileName = requireEnv("XTSC_KIR_ENTRY")
        val packageName = System.getenv("XTSC_KIR_PACKAGE") ?: "program"

        // The seed's own files, before the lowering appends the program's.
        val seedFiles = moduleFragment.files.toList()
        val started = System.nanoTime()
        val checked = checkTypeScriptProject(projectPath)
        if (checked.errors.isNotEmpty()) {
            error(
                "the TypeScript program does not type-check; the backend lowers only " +
                    "checked programs:\n" + checked.errors.joinToString("\n") {
                        "  TS${it.code} ${it.fileName}:${it.line}:${it.character} ${it.message}"
                    }
            )
        }
        val entry = checked.files.firstOrNull { it.fileName.endsWith(entryFileName) }
            ?: error(
                "no program file named '$entryFileName'; the program has " +
                    checked.files.joinToString { it.fileName }
            )
        val checkedAt = System.nanoTime()
        KirProgramLowering(
            IrProgramBuilder(pluginContext, moduleFragment),
            checked.facts,
            checked.files,
            entry,
            packageName,
            checked.importEdges,
        ).lower()
        // Kotlin/Native's IR validator requires every field to be private, where
        // the JVM one accepts a public static field. The generated module-level
        // variables are read and written through the accessors the lowering
        // already emits into the SAME file, so narrowing them is a change of
        // metadata only -- and it is done here rather than in the lowering so
        // that the JVM path keeps the shape its own backend validated.
        moduleFragment.files.forEach { privatizeFields(it) }
        // THE ENTRY POINT IS RESOLVED BY THE FRONTEND, which never saw the
        // generated `program.main` -- `-e program.main` answers "could not find"
        // however valid the IR is. So the SEED declares the `main` konanc will
        // find, and it is given a body here that calls the generated one. This
        // is the native counterpart of the JVM path's `mainClass`, which a
        // caller invokes reflectively for the same reason.
        // Found BEFORE the merge below, which puts a second `main` in this file.
        val seedMain = seedFiles
            .flatMap { it.declarations }
            .filterIsInstance<IrSimpleFunction>()
            .singleOrNull { it.name.asString() == "main" }
            ?: error("the seed source must declare a `main` for konanc to find")

        // KLIB SERIALIZATION ONLY SEES THE FRONTEND'S FILES. Kotlin/Native always
        // goes through a klib -- producing a program is "one-stage" compilation,
        // which writes an intermediate one -- and its serializer walks the files
        // the FIR module produced. A file this plugin ADDED to the module
        // fragment is dropped whole: the binary then links, and the call from
        // the seed's `main` dies at run time with an IrLinkageError naming a
        // symbol the validator had just accepted. So every generated declaration
        // is re-parented into the seed's own file, which the serializer does see.
        val generatedFiles = moduleFragment.files.filter { it !in seedFiles }
        val generatedMain = generatedFiles
            .flatMap { it.declarations }
            .filterIsInstance<IrSimpleFunction>()
            .singleOrNull { it.name.asString() == "main" }
            ?: error("the lowering produced no entry point")
        val seedFile = seedFiles.singleOrNull()
            ?: error("the seed must be exactly one file; it is ${seedFiles.size}")
        // One package now holds what were N files, so every top-level name must
        // be made unique or the serializer aborts on "different declarations with
        // the same signatures" -- each lowered TypeScript file contributes a
        // `moduleInit`, and eight of them collide. Nothing reads these names:
        // the IR is symbol-bound, and the JVM path keeps its per-file facade
        // classes, which is what makes the collision a Native-only problem.
        generatedFiles.forEachIndexed { index, file ->
            file.declarations.forEach { declaration ->
                if (declaration is IrDeclarationWithName) {
                    declaration.name = Name.identifier("f${index}_${declaration.name.asString()}")
                }
                declaration.parent = seedFile
                seedFile.declarations.add(declaration)
            }
        }
        moduleFragment.files.removeAll(generatedFiles)

        seedMain.body = DeclarationIrBuilder(pluginContext, seedMain.symbol).irBlockBody {
            +irCall(generatedMain.symbol)
        }
        System.err.println(
            "xtsc-kir-native: checked ${checked.files.size} file(s) in " +
                "${(checkedAt - started) / 1_000_000} ms, lowered in " +
                "${(System.nanoTime() - checkedAt) / 1_000_000} ms"
        )
    }

    /** Every field, at any nesting depth -- a class's members are fields too. */
    private fun privatizeFields(container: IrDeclarationContainer) {
        container.declarations.forEach { declaration: IrDeclaration ->
            when (declaration) {
                is IrField -> declaration.visibility = DescriptorVisibilities.PRIVATE
                is IrClass -> privatizeFields(declaration)
                else -> {}
            }
        }
    }

    private fun requireEnv(name: String): String =
        System.getenv(name) ?: error("$name is not set; the KIR native plugin needs it")

}
