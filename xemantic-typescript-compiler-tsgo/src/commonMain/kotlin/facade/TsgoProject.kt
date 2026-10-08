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

@file:OptIn(ExperimentalAtomicApi::class)

package com.xemantic.typescript.tsgo

import com.xemantic.typescript.tsgo.api.CheckerSignatureParams
import com.xemantic.typescript.tsgo.api.CheckerTypeParams
import com.xemantic.typescript.tsgo.api.DocumentIdentifier
import com.xemantic.typescript.tsgo.api.GetContextualTypeParams
import com.xemantic.typescript.tsgo.api.GetDiagnosticsParams
import com.xemantic.typescript.tsgo.api.GetPropertyOfTypeParams
import com.xemantic.typescript.tsgo.api.GetResolvedSignatureParams
import com.xemantic.typescript.tsgo.api.GetSignaturePropertyParams
import com.xemantic.typescript.tsgo.api.GetSignaturesOfTypeParams
import com.xemantic.typescript.tsgo.api.GetSourceFileNamesParams
import com.xemantic.typescript.tsgo.api.GetSymbolAtLocationParams
import com.xemantic.typescript.tsgo.api.GetSymbolAtPositionParams
import com.xemantic.typescript.tsgo.api.GetTypeAtLocationParams
import com.xemantic.typescript.tsgo.api.GetTypeAtPositionParams
import com.xemantic.typescript.tsgo.api.GetTypeOfSymbolParams
import com.xemantic.typescript.tsgo.api.GetTypePropertyParams
import com.xemantic.typescript.tsgo.api.IsTypeAssignableToParams
import com.xemantic.typescript.tsgo.api.NodeHandle
import com.xemantic.typescript.tsgo.api.ProjectID
import com.xemantic.typescript.tsgo.api.Session
import com.xemantic.typescript.tsgo.api.SignatureResponse
import com.xemantic.typescript.tsgo.api.SnapshotID
import com.xemantic.typescript.tsgo.api.SymbolResponse
import com.xemantic.typescript.tsgo.api.TypeResponse
import com.xemantic.typescript.tsgo.api.TypeToTypeNodeParams
import com.xemantic.typescript.tsgo.api.handleGetApparentType
import com.xemantic.typescript.tsgo.api.handleGetBaseTypes
import com.xemantic.typescript.tsgo.api.handleGetContextualType
import com.xemantic.typescript.tsgo.api.handleGetDeclaredTypeOfSymbol
import com.xemantic.typescript.tsgo.api.handleGetParametersOfSignature
import com.xemantic.typescript.tsgo.api.handleGetPropertiesOfType
import com.xemantic.typescript.tsgo.api.handleGetPropertyOfType
import com.xemantic.typescript.tsgo.api.handleGetResolvedSignature
import com.xemantic.typescript.tsgo.api.handleGetReturnTypeOfSignature
import com.xemantic.typescript.tsgo.api.handleGetSemanticDiagnostics
import com.xemantic.typescript.tsgo.api.handleGetSignaturesOfType
import com.xemantic.typescript.tsgo.api.handleGetSourceFileNames
import com.xemantic.typescript.tsgo.api.handleGetSymbolAtLocation
import com.xemantic.typescript.tsgo.api.handleGetSymbolAtPosition
import com.xemantic.typescript.tsgo.api.handleGetTypeArguments
import com.xemantic.typescript.tsgo.api.handleGetTypeAtLocation
import com.xemantic.typescript.tsgo.api.handleGetTypeAtPosition
import com.xemantic.typescript.tsgo.api.handleGetTypeOfSymbol
import com.xemantic.typescript.tsgo.api.handleGetTypesOfType
import com.xemantic.typescript.tsgo.api.handleIsTypeAssignableTo
import com.xemantic.typescript.tsgo.api.handleRequest
import com.xemantic.typescript.tsgo.api.handleTypeToString
import com.xemantic.typescript.tsgo.ast.localize
import com.xemantic.typescript.tsgo.api.encoder.getIndex
import com.xemantic.typescript.tsgo.ast.getPositionMap
import com.xemantic.typescript.tsgo.ast.utf16ToUTF8
import com.xemantic.typescript.tsgo.compiler.Program
import com.xemantic.typescript.tsgo.go.sync.goSpawn
import com.xemantic.typescript.tsgo.go.sync.park
import com.xemantic.typescript.tsgo.go.sync.parkToken
import com.xemantic.typescript.tsgo.go.sync.unpark
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.goBytesToString
import com.xemantic.typescript.tsgo.runtime.goStringToBytes
import com.xemantic.typescript.tsgo.tspath.Path
import com.xemantic.typescript.tsgo.vfs.FS
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

// (TSGO.3-b) THE TYPE ORACLE: tsgo's API (`internal/api`, proto.go / session.go — ported, gen/api) in
// process, through a Kotlin facade. docs/goport-api.md.
//
// A [TsgoProject] is one tsconfig's program as tsgo's project system builds it (`api.XtscOpenProgram`)
// and an API session over it (`api.XtscNewSession`, the session's own handlers). Every query is a
// session HANDLER — the very code `tsc --api` runs for that request — so an answer here is tsgo's
// answer (the gate: ApiParityTest, `TSGO_API=1`). Handles keep tsgo's identity rules: a [TsgoType] or
// [TsgoSignature] is valid for its project, a [TsgoSymbol] for the session. Positions are UTF-16 offsets
// (Kotlin `String` indices) into the file's text; file names are absolute paths.
//
// [request] is the raw protocol: any proto.go method name and its JSON params (as `tsc --api` takes
// them) in, the response JSON out — the whole ~140-method surface, including what the typed facade
// does not wrap. The handlers that need tsgo's language service (completions, references, JSDoc) or
// its project system (updateSnapshot, release) are not ported: they throw (docs/goport-api.md § 3).

/**
 * A query the session refused (an unknown handle, a file not in the program, …): the handler's error.
 * [panicked]: the handler PANICKED — tsgo's connection recovers it into an error response
 * (`SyncConn.handleRequest`, conn_sync.go), and so does the facade; [message] is then `panic: <value>`.
 */
class TsgoApiException(message: String, val panicked: Boolean = false) : RuntimeException(message)

/** A program and an API session over it: tsgo's type oracle for one tsconfig. */
class TsgoProject private constructor(
    /** The tsconfig's normalized absolute path (the API's project id). */
    val configFileName: String,
    /** The ported program (the escape hatch to everything the API does not expose). */
    val program: Program,
    /** tsconfig parse errors, `TSnnnn: message`. */
    val configDiagnostics: List<String>,
    private val session: Session,
    /** The snapshot handle the raw protocol's `snapshot` param names. */
    val snapshotId: ULong,
) {

    private val snapshot = SnapshotID(snapshotId)
    private val project = ProjectID(GoString.fromUtf16(configFileName))
    private val ctx = com.xemantic.typescript.tsgo.go.context.background()

    companion object {

        private val initialized = AtomicInt(0)

        /** Go's package initialization for what the session reaches (`parser` installs its JSDoc hook). */
        private fun init() {
            if (initialized.compareAndSet(0, 1)) com.xemantic.typescript.tsgo.parser.goInitPackage()
        }

        /** The host file system (rooted at `/`, case-sensitive): tsgo's `osvfs` minus symlink resolution. */
        fun diskFS(): FS = com.xemantic.typescript.tsgo.vfs.iovfs.from(com.xemantic.typescript.tsgo.go.os.dirFS("/"), true)!!

        /**
         * Opens [configFileName] (an absolute path to a tsconfig.json) over [fs]: the program is built and
         * bound as tsgo's project system builds a configured project's. The default libraries are the
         * bundled ones, or the lib files of [libDirectory] (an absolute path on [fs]) — what the shipped
         * `tsc` binary uses, its own `lib` directory. Throws when the config cannot be read.
         */
        fun open(configFileName: String, fs: FS = diskFS(), libDirectory: String? = null): TsgoProject = onGoStack {
            init()
            val name = com.xemantic.typescript.tsgo.tspath.normalizePath(GoString.fromUtf16(configFileName))
            val (program, pool, errs) = com.xemantic.typescript.tsgo.api.xtscOpenProgram(name, fs, libDirectory?.let { com.xemantic.typescript.tsgo.tspath.normalizePath(GoString.fromUtf16(it)) } ?: "")
            val diags = (0 until errs.len).map { i ->
                val d = errs[i]!!
                "TS${d.code}: " + GoString.toUtf16(d.localize(com.xemantic.typescript.tsgo.locale.Locale()))
            }
            if (program == null) throw TsgoApiException("$configFileName: ${diags.joinToString("; ")}")
            val id = 1uL
            val path = Path(name)
            val proj = com.xemantic.typescript.tsgo.project.Project(path, program, pool)
            val snapshot = com.xemantic.typescript.tsgo.project.Snapshot(id, com.xemantic.typescript.tsgo.project.ProjectCollection(mapOf(name to proj)))
            val projectSession = com.xemantic.typescript.tsgo.project.Session(fs, com.xemantic.typescript.tsgo.tspath.getDirectoryPath(name))
            val (session, handle) = com.xemantic.typescript.tsgo.api.xtscNewSession(projectSession, snapshot)
            TsgoProject(GoString.toUtf16(name), program, diags, session!!, handle.value)
        }
    }

    // ------------------------------------------------------------------ the raw protocol

    /**
     * One request of tsgo's API protocol: [method] (a proto.go `Method…` name, e.g. `"getTypeAtPosition"`)
     * with [paramsJson] (its params object, `snapshot`/`project` included — [snapshotId], [configFileName])
     * answered by the session's `HandleRequest`, exactly the JSON payload `tsc --api` responds with.
     */
    fun request(method: String, paramsJson: String): String = onGoStack { recovered {
        val params = goStringToBytes(GoString.fromUtf16(paramsJson))
        val (result, err) = session.handleRequest(ctx, GoString.fromUtf16(method), params)
        if (err != null) throw TsgoApiException(GoString.toUtf16(err.error()))
        val (bytes, merr) = com.xemantic.typescript.tsgo.api.xtscMarshal(result)
        if (merr != null) throw TsgoApiException(GoString.toUtf16(merr.error()))
        GoString.toUtf16(goBytesToString(bytes))
    } }

    // ------------------------------------------------------------------ files

    /** Every file of the program, libraries included (`getSourceFileNames`). */
    fun sourceFileNames(): List<String> = call { session.handleGetSourceFileNames(ctx, GetSourceFileNamesParams(snapshot, project)) }
        .let { s -> List(s.len) { GoString.toUtf16(s[it]) } }

    /** The semantic diagnostics of [fileName] (all files when null), `TSnnnn: message` (`getSemanticDiagnostics`). */
    fun semanticDiagnostics(fileName: String? = null): List<String> =
        call { session.handleGetSemanticDiagnostics(ctx, GetDiagnosticsParams(snapshot, project, fileName?.let { doc(it) })) }
            .let { s -> List(s.len) { i -> s[i]!!.let { "TS${it.code}: ${GoString.toUtf16(it.text)}" } } }

    // ------------------------------------------------------------------ positions and nodes

    /** The node a [TsgoNode] handle names at [position] of [fileName] (the touching property name, as the API resolves positions). */
    fun nodeAt(fileName: String, position: Int): TsgoNode? = onGoStack {
        val sf = program.getSourceFile(GoString.fromUtf16(fileName)) ?: throw TsgoApiException("source file not found: $fileName")
        val pos = sf.getPositionMap().utf16ToUTF8(position)
        val node = com.xemantic.typescript.tsgo.astnav.getTouchingPropertyName(sf, pos) ?: return@onGoStack null
        val idx = com.xemantic.typescript.tsgo.api.encoder.getNodeIndexTable(sf).getIndex(node)
        TsgoNode(this, "$idx.${node.kind.value}.${GoString.toUtf16(sf.path().value)}")
    }

    /** The type at [position] of [fileName] (`getTypeAtPosition`). */
    fun typeAtPosition(fileName: String, position: Int): TsgoType? =
        call { session.handleGetTypeAtPosition(ctx, GetTypeAtPositionParams(snapshot, project, doc(fileName), position.toUInt())) }?.let { TsgoType(this, it) }

    /** The symbol at [position] of [fileName] (`getSymbolAtPosition`). */
    fun symbolAtPosition(fileName: String, position: Int): TsgoSymbol? =
        call { session.handleGetSymbolAtPosition(ctx, GetSymbolAtPositionParams(snapshot, project, doc(fileName), position.toUInt())) }?.let { TsgoSymbol(this, it) }

    /** The type of [node] (`getTypeAtLocation`). */
    fun typeAtLocation(node: TsgoNode): TsgoType? =
        call { session.handleGetTypeAtLocation(ctx, GetTypeAtLocationParams(snapshot, project, node.h)) }?.let { TsgoType(this, it) }

    /** The symbol of [node] (`getSymbolAtLocation`). */
    fun symbolAtLocation(node: TsgoNode): TsgoSymbol? =
        call { session.handleGetSymbolAtLocation(ctx, GetSymbolAtLocationParams(snapshot, project, node.h)) }?.let { TsgoSymbol(this, it) }

    /** The contextual type of the expression [node] (`getContextualType`). */
    fun contextualType(node: TsgoNode): TsgoType? =
        call { session.handleGetContextualType(ctx, GetContextualTypeParams(snapshot, project, node.h)) }?.let { TsgoType(this, it) }

    /** The signature a call-like [node] resolves to (`getResolvedSignature`). */
    fun resolvedSignature(node: TsgoNode): TsgoSignature? =
        call { session.handleGetResolvedSignature(ctx, GetResolvedSignatureParams(snapshot, project, node.h)) }?.let { TsgoSignature(this, it) }

    // ------------------------------------------------------------------ types

    /** tsgo's display of [type] (`typeToString`; [enclosing] scopes the names, [flags] are `TypeFormatFlags`). */
    fun typeToString(type: TsgoType, enclosing: TsgoNode? = null, flags: Int = 0): String =
        GoString.toUtf16(call { session.handleTypeToString(ctx, TypeToTypeNodeParams(snapshot, project, type.r.id, enclosing?.h ?: NodeHandle(""), flags)) } as String)

    /** The properties of [type] (`getPropertiesOfType`). */
    fun propertiesOfType(type: TsgoType): List<TsgoSymbol> = symbols(call { session.handleGetPropertiesOfType(ctx, CheckerTypeParams(snapshot, project, type.r.id)) })

    /** The property [name] of [type] (`getPropertyOfType`). */
    fun propertyOfType(type: TsgoType, name: String): TsgoSymbol? =
        call { session.handleGetPropertyOfType(ctx, GetPropertyOfTypeParams(snapshot, project, type.r.id, GoString.fromUtf16(name))) }?.let { TsgoSymbol(this, it) }

    /** The call (or, with [construct], construct) signatures of [type] (`getSignaturesOfType`). */
    fun signaturesOfType(type: TsgoType, construct: Boolean = false): List<TsgoSignature> =
        call { session.handleGetSignaturesOfType(ctx, GetSignaturesOfTypeParams(snapshot, project, type.r.id, if (construct) 1 else 0)) }
            .let { s -> List(s.len) { TsgoSignature(this, s[it]!!) } }

    /** Whether [source] is assignable to [target] (`isTypeAssignableTo`). */
    fun isTypeAssignableTo(source: TsgoType, target: TsgoType): Boolean =
        call { session.handleIsTypeAssignableTo(ctx, IsTypeAssignableToParams(snapshot, project, source.r.id, target.r.id)) }

    /** The constituents of a union or intersection [type] (`getTypesOfType`). */
    fun typesOfType(type: TsgoType): List<TsgoType> = types(call { session.handleGetTypesOfType(ctx, GetTypePropertyParams(snapshot, project, type.r.id)) })

    /** The type arguments of a type reference [type] (`getTypeArguments`). */
    fun typeArguments(type: TsgoType): List<TsgoType> = types(call { session.handleGetTypeArguments(ctx, CheckerTypeParams(snapshot, project, type.r.id)) })

    /** The base types of a class or interface [type] (`getBaseTypes`). */
    fun baseTypes(type: TsgoType): List<TsgoType> = types(call { session.handleGetBaseTypes(ctx, CheckerTypeParams(snapshot, project, type.r.id)) })

    /** The apparent type of [type] (`getApparentType`). */
    fun apparentType(type: TsgoType): TsgoType? =
        call { session.handleGetApparentType(ctx, CheckerTypeParams(snapshot, project, type.r.id)) }?.let { TsgoType(this, it) }

    // ------------------------------------------------------------------ symbols and signatures

    /** The type of [symbol] (`getTypeOfSymbol`). */
    fun typeOfSymbol(symbol: TsgoSymbol): TsgoType? =
        call { session.handleGetTypeOfSymbol(ctx, GetTypeOfSymbolParams(snapshot, project, symbol.r.id)) }?.let { TsgoType(this, it) }

    /** The declared type of [symbol] (`getDeclaredTypeOfSymbol`). */
    fun declaredTypeOfSymbol(symbol: TsgoSymbol): TsgoType? =
        call { session.handleGetDeclaredTypeOfSymbol(ctx, GetTypeOfSymbolParams(snapshot, project, symbol.r.id)) }?.let { TsgoType(this, it) }

    /** The parameters of [signature] (`getParametersOfSignature`). */
    fun parametersOfSignature(signature: TsgoSignature): List<TsgoSymbol> =
        symbols(call { session.handleGetParametersOfSignature(ctx, GetSignaturePropertyParams(snapshot, project, signature.r.id)) })

    /** The return type of [signature] (`getReturnTypeOfSignature`). */
    fun returnTypeOfSignature(signature: TsgoSignature): TsgoType? =
        call { session.handleGetReturnTypeOfSignature(ctx, CheckerSignatureParams(snapshot, project, signature.r.id)) }?.let { TsgoType(this, it) }

    // ------------------------------------------------------------------ plumbing

    private fun doc(fileName: String) = DocumentIdentifier(fileName = GoString.fromUtf16(fileName))

    private fun symbols(s: GoSlice<SymbolResponse?>): List<TsgoSymbol> = List(s.len) { TsgoSymbol(this, s[it]!!) }

    private fun types(s: GoSlice<TypeResponse?>): List<TsgoType> = List(s.len) { TsgoType(this, s[it]!!) }

    private fun <R> call(handler: () -> Tuple2<R, GoError?>): R = onGoStack { recovered {
        val (r, err) = handler()
        if (err != null) throw TsgoApiException(GoString.toUtf16(err.error()))
        r
    } }

    /** A handler's panic as the error response tsgo's connection recovers it into. */
    private fun <R> recovered(block: () -> R): R = try {
        block()
    } catch (p: com.xemantic.typescript.tsgo.runtime.GoPanic) {
        val v = p.value
        val text = when (v) {
            is GoError -> GoString.toUtf16(v.error())
            is String -> GoString.toUtf16(v)
            else -> v.toString()
        }
        throw TsgoApiException("panic: $text", panicked = true)
    }
}

/** A node of a project's file (an API `NodeHandle`: `index.kind.path`, the encoder's node index). */
class TsgoNode internal constructor(val project: TsgoProject, val handle: String) {
    internal val h = NodeHandle(GoString.fromUtf16(handle))

    /** The file's path. */
    val fileName: String get() = handle.substring(handle.indexOf('.', handle.indexOf('.') + 1) + 1)

    /** The `ast.Kind` number. */
    val kind: Int get() = handle.substring(handle.indexOf('.') + 1, handle.indexOf('.', handle.indexOf('.') + 1)).toInt()

    override fun equals(other: Any?): Boolean = other is TsgoNode && other.project === project && other.handle == handle
    override fun hashCode(): Int = handle.hashCode()
    override fun toString(): String = handle
}

/** A checker type of a project (an API `TypeResponse`; identity is the handle). */
class TsgoType internal constructor(val project: TsgoProject, internal val r: TypeResponse) {
    /** The type handle (the checker's type id). */
    val id: UInt get() = r.id.value
    /** `checker.TypeFlags`. */
    val flags: UInt get() = r.flags
    /** `checker.ObjectFlags` (object types). */
    val objectFlags: UInt get() = r.objectFlags
    /** An intrinsic type's name (`string`, `any`, …), else null. */
    val intrinsicName: String? get() = r.intrinsicName.takeIf { it.isNotEmpty() }?.let { GoString.toUtf16(it) }
    /** A literal type's value: a `String`, a `Double`, a `Boolean` or a bigint's decimal `String`. */
    val literalValue: Any? get() = r.value.let { if (it is String) GoString.toUtf16(it) else it }

    override fun equals(other: Any?): Boolean = other is TsgoType && other.project === project && other.r.id == r.id
    override fun hashCode(): Int = r.id.hashCode()
    /** [TsgoProject.typeToString]. */
    override fun toString(): String = project.typeToString(this)
}

/** A symbol (an API `SymbolResponse`; identity is the handle). */
class TsgoSymbol internal constructor(val project: TsgoProject, internal val r: SymbolResponse) {
    /** The symbol handle. */
    val id: ULong get() = r.id.value
    val name: String get() = GoString.toUtf16(r.name)
    /** `ast.SymbolFlags`. */
    val flags: UInt get() = r.flags
    /** `ast.CheckFlags`. */
    val checkFlags: UInt get() = r.checkFlags
    val declarations: List<TsgoNode> get() = List(r.declarations.len) { TsgoNode(project, GoString.toUtf16(r.declarations[it].value)) }
    val valueDeclaration: TsgoNode? get() = r.valueDeclaration.value.takeIf { it.isNotEmpty() }?.let { TsgoNode(project, GoString.toUtf16(it)) }

    override fun equals(other: Any?): Boolean = other is TsgoSymbol && other.project === project && other.r.id == r.id
    override fun hashCode(): Int = r.id.hashCode()
    override fun toString(): String = name
}

/** A signature (an API `SignatureResponse`; identity is the handle). */
class TsgoSignature internal constructor(val project: TsgoProject, internal val r: SignatureResponse) {
    /** The signature handle. */
    val id: ULong get() = r.id.value
    /** `checker.SignatureFlags`. */
    val flags: UInt get() = r.flags
    val declaration: TsgoNode? get() = r.declaration.value.takeIf { it.isNotEmpty() }?.let { TsgoNode(project, GoString.toUtf16(it)) }

    override fun equals(other: Any?): Boolean = other is TsgoSignature && other.project === project && other.r.id == r.id
    override fun hashCode(): Int = r.id.hashCode()
}

/**
 * Runs [block] on a goroutine thread and waits for it: tsgo's checker recurses as deep as a Go goroutine
 * stack allows (1 GB), which an ordinary caller thread does not have.
 */
internal fun <R> onGoStack(block: () -> R): R {
    val done = AtomicInt(0)
    var out: Result<R>? = null
    val token = parkToken()
    goSpawn {
        out = runCatching(block)
        done.store(1)
        unpark(token)
    }
    while (done.load() == 0) park(done)
    return out!!.getOrThrow()
}
