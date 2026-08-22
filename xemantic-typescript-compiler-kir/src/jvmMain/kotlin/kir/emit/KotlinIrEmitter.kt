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

package com.xemantic.typescript.compiler.kir.emit

import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.GroupingMessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.pipeline.ArgumentsPipelineArtifact
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmBackendPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmConfigurationPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFir2IrPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmFrontendPipelinePhase
import org.jetbrains.kotlin.cli.pipeline.jvm.JvmWriteOutputsPhase
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.util.CompilerType
import org.jetbrains.kotlin.util.PerformanceManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension

/**
 * Compiles synthesized Kotlin IR to JVM `.class` files.
 *
 * This is the seam between xtsc and kotlinc. A caller hands it a lambda that
 * builds `IrFile`s; it returns the bytecode. Nothing about Kotlin's compilation
 * pipeline — phases, artifacts, plugin registrars, message collectors — is
 * visible in that contract, and deliberately so: the lowering from the checked
 * TypeScript AST has quite enough to think about without also owning kotlinc's
 * driver.
 *
 * How it works, since the mechanism is not obvious and was expensive to find.
 * The Kotlin JVM pipeline is five phases — configuration, frontend, FIR-to-IR,
 * backend, write-outputs — and only the FIR-to-IR one accepts
 * `IrGenerationExtension`s, as a plain argument. So the emitter drives the
 * phases itself and passes the caller's builder in at that point. There is no
 * plugin jar, no `META-INF/services` file and no `Project` involved:
 * `IrGenerationExtension.registerExtension(project, …)` no longer exists in
 * Kotlin 2.4, and `KotlinToJVMBytecodeCompiler` is K1-only.
 *
 * A one-declaration **seed** source file is compiled purely so that the
 * frontend produces a wired `IrBuiltIns`, `SymbolTable` and `IrPluginContext`;
 * the generated files are then appended to that module fragment. The seed
 * itself contributes no bytecode.
 *
 * IR validation is on ([K2JVMCompilerArguments.verifyIr] `= "error"`). It is
 * `none` by default on JVM and it is the only thing standing between a
 * malformed tree and an opaque `ClassCastException` deep inside codegen.
 *
 * @param outputDirectory where the `.class` files are written; created if absent.
 * @param classpath what the generated program is compiled against — by default
 *   the Kotlin standard library plus this module's runtime.
 * @param moduleName the Kotlin module name, which shows up in `@Metadata`.
 * @param jvmTarget the bytecode level of the generated classes.
 */
public class KotlinIrEmitter(
    private val outputDirectory: Path,
    private val classpath: List<Path> = GeneratedProgramClasspath.minimal(),
    private val moduleName: String = "kir",
    private val jvmTarget: String = "17",
) {

    /**
     * Runs the whole pipeline over the IR that [build] appends.
     *
     * [build] is invoked once, inside the FIR-to-IR phase, on a live
     * [IrProgramBuilder]. Anything it throws is caught and reported as a failed
     * [EmitResult], as is an IR validation error and a codegen failure — a
     * caller compiling somebody's TypeScript should get a diagnostic, not a
     * stack trace.
     */
    public fun emit(build: IrProgramBuilder.() -> Unit): EmitResult {
        val messages = RecordingMessageCollector()
        // The pipeline insists on a GroupingMessageCollector, which buffers
        // located diagnostics until `flush()` — so nothing may be read out of
        // `messages` before that call, which is why the result is assembled
        // after the `finally` rather than at each exit.
        val collector = GroupingMessageCollector(messages, false, false)
        val seedDirectory = Files.createTempDirectory("xtsc-kir-seed")
        val rootDisposable = Disposer.newDisposable("xtsc-kir-emitter")
        var builderFailure: Exception? = null
        var failureReason: String?
        try {
            val seedFile = seedDirectory.resolve("Seed.kt")
            Files.writeString(seedFile, "package $SEED_PACKAGE\n")
            Files.createDirectories(outputDirectory)
            val arguments = K2JVMCompilerArguments().apply {
                freeArgs = listOf(seedFile.toString())
                destination = outputDirectory.toString()
                classpath = this@KotlinIrEmitter.classpath.joinToString(File.pathSeparator)
                moduleName = this@KotlinIrEmitter.moduleName
                jvmTarget = this@KotlinIrEmitter.jvmTarget
                // The classpath is stated in full above; letting the compiler
                // add its own stdlib would put a second, possibly different
                // copy in front of it.
                noStdlib = true
                noReflect = true
                verifyIr = "error"
                // Kotlin's runtime null assertions are an invariant JAVASCRIPT
                // DOES NOT HAVE, so they are wrong here twice over. A JS
                // function handed `undefined` for a declared parameter does not
                // throw at entry — it throws, or does not, at the dereference —
                // and `Intrinsics.checkNotNullParameter` would have turned that
                // into a failure at the boundary, with a Kotlin message naming a
                // Kotlin type. They also cost: every generated function opens
                // with one per non-null reference parameter, on a call path a
                // recursive-descent parser crosses once per token.
                //
                // What is NOT affected is the runtime's own contract: `JsRuntime`
                // is compiled by this repo's build with its assertions intact, so
                // a lowering that hands `null` to `jsStrCharCodeAt` still fails
                // where it always did.
                noParamAssertions = true
                noCallAssertions = true
            }
            val extension = object : IrGenerationExtension {
                override fun generate(
                    moduleFragment: IrModuleFragment,
                    pluginContext: IrPluginContext
                ) {
                    try {
                        IrProgramBuilder(pluginContext, moduleFragment).build()
                    } catch (e: Exception) {
                        builderFailure = e
                        throw e
                    }
                }
            }
            val performanceManager = K2JVMCompiler().defaultPerformanceManager.apply {
                compilerType = CompilerType.K2
            }
            failureReason = runPipeline(
                arguments,
                collector,
                rootDisposable,
                performanceManager,
                extension
            )
        } catch (e: Exception) {
            // The builder's own exception, where there is one, names the real
            // fault; the pipeline wraps it in a content-free
            // `IrGenerationExtensionException` on the way out.
            val cause = builderFailure ?: e
            failureReason = "the pipeline aborted with ${cause::class.simpleName}" +
                (cause.message?.let { ": $it" } ?: "")
        } finally {
            collector.flush()
            Disposer.dispose(rootDisposable)
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            seedDirectory.deleteRecursively()
        }
        val reasons = listOfNotNull(failureReason)
        return EmitResult(
            successful = failureReason == null && !messages.hasErrors(),
            classFiles = writtenClassFiles(),
            messages = messages.all + reasons,
            errors = messages.errors + reasons
        )
    }

    /**
     * Runs the five JVM phases, returning null on success or the name of the
     * phase that failed.
     */
    private fun runPipeline(
        arguments: K2JVMCompilerArguments,
        collector: GroupingMessageCollector,
        rootDisposable: Disposable,
        performanceManager: PerformanceManager,
        extension: IrGenerationExtension
    ): String? {
        val configuration = JvmConfigurationPipelinePhase.executePhase(
            ArgumentsPipelineArtifact(
                arguments,
                Services.EMPTY,
                rootDisposable,
                collector,
                performanceManager
            )
        ) ?: return "the configuration phase failed"
        val frontend = JvmFrontendPipelinePhase.executePhase(configuration)
            ?: return "the frontend phase failed"
        // The one place the pipeline accepts IR generation extensions.
        val fir2ir = JvmFir2IrPipelinePhase.executePhase(frontend, listOf(extension))
            ?: return "the FIR-to-IR phase failed"
        // The last two phases are declared non-null: past FIR-to-IR the
        // pipeline signals failure by reporting to the collector, not by
        // returning nothing.
        val backend = JvmBackendPipelinePhase.executePhase(fir2ir)
        JvmWriteOutputsPhase.executePhase(backend)
        return null
    }

    private fun writtenClassFiles(): List<Path> =
        if (!Files.isDirectory(outputDirectory)) emptyList()
        else Files.walk(outputDirectory).use { paths ->
            paths.filter { it.extension == "class" }
                .map { it.toAbsolutePath() }
                .sorted()
                .toList()
        }

    private companion object {

        /**
         * The seed's package. It declares nothing, so it produces no class
         * file; its only job is to give the frontend something to parse.
         */
        const val SEED_PACKAGE = "com.xemantic.typescript.compiler.kir.seed"

    }

}

/**
 * What [KotlinIrEmitter.emit] produced.
 *
 * [messages] carries the compiler's own diagnostics rather than letting them
 * escape to stderr, because whoever asked for the compilation is the one who
 * needs to read them.
 */
public class EmitResult internal constructor(
    public val successful: Boolean,
    public val classFiles: List<Path>,
    public val messages: List<String>,
    public val errors: List<String>,
) {

    override fun toString(): String = buildString {
        append(if (successful) "emit succeeded" else "emit FAILED")
        append(", ").append(classFiles.size).append(" class file(s)")
        if (messages.isNotEmpty()) {
            append('\n')
            messages.joinTo(this, separator = "\n") { "  $it" }
        }
    }

}

/**
 * Collects the compiler's diagnostics instead of printing them.
 *
 * `PrintingMessageCollector` — the collector every kotlinc example uses — writes
 * to a stream, which is the one thing an embedded compiler must not do.
 *
 * `internal` rather than file-private because the METADATA compilation
 * (`…kir.api.compileMetadataKlib`) drives a different kotlinc entry point and
 * needs the same sink; two collectors would be two renderings of one thing.
 */
internal class RecordingMessageCollector : MessageCollector {

    private val recorded = mutableListOf<String>()
    private val recordedErrors = mutableListOf<String>()

    val all: List<String> get() = recorded.toList()
    val errors: List<String> get() = recordedErrors.toList()

    override fun clear() {
        recorded.clear()
        recordedErrors.clear()
    }

    override fun hasErrors(): Boolean = recordedErrors.isNotEmpty()

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?
    ) {
        // LOGGING is the compiler narrating itself — thousands of lines per
        // compilation, none of them about the caller's program.
        if (severity == CompilerMessageSeverity.LOGGING) return
        val rendered = buildString {
            append(severity.presentableName).append(": ")
            if (location != null) {
                append(location.path).append(':')
                append(location.line).append(':').append(location.column).append(' ')
            }
            append(message)
        }
        recorded += rendered
        if (severity.isError) recordedErrors += rendered
    }

}
