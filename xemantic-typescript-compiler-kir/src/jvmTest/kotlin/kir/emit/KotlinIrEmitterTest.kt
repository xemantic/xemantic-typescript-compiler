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

// See `IrProgramBuilder`: `IrSymbol.owner` is only unsafe DURING IR
// construction, and an `IrGenerationExtension` runs after it.
@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package com.xemantic.typescript.compiler.kir.emit

import com.xemantic.kotlin.test.assert
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * The decisive tests for the IR backend: they do not inspect a tree, they RUN
 * the bytecode it produced.
 *
 * A test that asserted over `IrFile`s would pass on IR that no backend accepts,
 * which is exactly the failure this module has to be protected from — the
 * distance between "a well-typed IR tree" and "a class file that runs" is where
 * every mechanical constraint in `docs/kir-design.md` was found.
 */
class KotlinIrEmitterTest {

    private val runtimePackage = "com.xemantic.typescript.compiler.kir.runtime"

    private fun outputDirectory(): Path = Files.createTempDirectory("xtsc-kir-test")

    @Test
    fun `should compile and run a generated program printing to stdout`() {
        val output = outputDirectory()
        val result = KotlinIrEmitter(output).emit {
            val file = file("app", "Main.kt")
            val printLine = printlnOfAny(file)
            function(file, "main", irBuiltIns.unitType) {
                +irCall(printLine).apply {
                    arguments[0] = irString("hello from generated Kotlin IR")
                }
            }
        }
        assert(result.successful)
        assert(result.classFiles.map { it.fileName.toString() } == listOf("MainKt.class"))
        val run = runGeneratedProgram(output, "app.MainKt")
        assert(run.exitCode == 0)
        assert(run.stdout == "hello from generated Kotlin IR\n")
    }

    @Test
    fun `should compile a generated program calling the kir runtime`() {
        val output = outputDirectory()
        val result = KotlinIrEmitter(output).emit {
            val file = file("app", "Main.kt")
            val consoleLog = referenceFunction(file, runtimePackage, "consoleLog")
            val jsNumberToString = referenceFunction(file, runtimePackage, "jsNumberToString")
            function(file, "main", irBuiltIns.unitType) {
                +irCall(consoleLog).apply {
                    arguments[0] = irVararg(
                        irBuiltIns.anyNType,
                        listOf(
                            irCall(jsNumberToString).apply {
                                arguments[0] = irDouble(1.0)
                            }
                        )
                    )
                }
            }
        }
        assert(result.successful)
        val run = runGeneratedProgram(output, "app.MainKt")
        assert(run.exitCode == 0)
        // The whole point of the runtime: a JS number prints as `1`, not `1.0`.
        assert(run.stdout == "1\n")
    }

    @Test
    fun `should compile a generated function with a parameter and call it`() {
        val output = outputDirectory()
        val result = KotlinIrEmitter(output).emit {
            val file = file("app", "Main.kt")
            val printLine = printlnOfAny(file)
            val jsNumberToString = referenceFunction(file, runtimePackage, "jsNumberToString")
            val doublePlus = referenceMemberFunction(file, "kotlin.Double", "plus") {
                it.owner.parameters.last().type == irBuiltIns.doubleType
            }
            val twice = function(
                file,
                "twice",
                irBuiltIns.doubleType,
                "x" to irBuiltIns.doubleType
            ) { function ->
                val x = function.parameters.single()
                +irReturn(
                    irCall(doublePlus).apply {
                        // Index 0 is the dispatch receiver: since Kotlin 2.2
                        // `arguments` is one flat list of every parameter kind.
                        arguments[0] = irGet(x)
                        arguments[1] = irGet(x)
                    }
                )
            }
            function(file, "main", irBuiltIns.unitType) {
                +irCall(printLine).apply {
                    arguments[0] = irCall(jsNumberToString).apply {
                        arguments[0] = irCall(twice.symbol).apply {
                            arguments[0] = irDouble(21.0)
                        }
                    }
                }
            }
        }
        assert(result.successful)
        val run = runGeneratedProgram(output, "app.MainKt")
        assert(run.exitCode == 0)
        assert(run.stdout == "42\n")
    }

    /**
     * The negative control.
     *
     * The malformation is a call carrying one argument more than its callee has
     * parameters, which `-Xverify-ir=error` rejects by name
     * (`IrCallValueArgumentCountChecker`). What that flag does NOT check is
     * everything semantic: a call whose argument has the wrong TYPE, an
     * unreachable declaration, a wrong `IMPLICIT_CAST` — those reach codegen and
     * surface, if at all, as a crash or as invalid bytecode. So this test pins
     * the reporting CHANNEL — a failed [EmitResult] carrying the diagnostic
     * rather than an exception escaping into the caller — not the coverage of
     * the verifier.
     */
    @Test
    fun `should report a malformed IR tree as a failed result`() {
        val output = outputDirectory()
        val result = KotlinIrEmitter(output).emit {
            val file = file("app", "Main.kt")
            val printLine = printlnOfAny(file)
            function(file, "main", irBuiltIns.unitType) {
                +irCall(printLine).apply {
                    arguments[0] = irString("one")
                    arguments.add(irString("one argument too many"))
                }
            }
        }
        assert(!result.successful)
        assert(result.errors.any { "argument(s)" in it })
        assert(result.classFiles.isEmpty())
    }

    /**
     * `kotlin.io.println` has an overload per primitive type; the `Any?` one is
     * the only one a generated program should ever reach, because every other
     * one would need the argument's static type to be known here.
     */
    private fun IrProgramBuilder.printlnOfAny(file: org.jetbrains.kotlin.ir.declarations.IrFile) =
        referenceFunction(file, "kotlin.io", "println") {
            it.owner.parameters.singleOrNull()?.type == irBuiltIns.anyNType
        }

}
