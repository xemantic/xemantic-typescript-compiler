package com.xemantic.typescript.compiler

/**
 * Emits a single BUNDLED `.d.ts` for an `@outFile` + `@declaration`/`@emitDeclarationOnly`
 * compilation: each input module is wrapped in `declare module "<name>" { <decls-as-.d.ts> }`,
 * in dependency order. Module names are the file paths relative to the common source directory,
 * without extension; relative import/export specifiers are rewritten to those module names.
 *
 * Supports: class (name/heritage/empty body), import (dropping imports unused in the emitted
 * signatures), `export * from` / `export { }`, and function declarations whose return type is
 * INFERRED from a single-`return` body (`return new X()` → `X`; `return fn()` → `fn`'s inferred
 * return). A type used in a signature but not accessible in the current module is written with a
 * "late export" import-type (`import("<spec>").Name`). Unknown shapes are skipped (best-effort).
 */
internal class DtsBundleEmitter(
    /** (fileName, parsed) in emit (dependency) order. */
    private val orderedFiles: List<Pair<String, SourceFile>>,
    private val commonDir: String,
) {
    private val filesByName: Map<String, SourceFile> = orderedFiles.toMap()
    private val moduleNames: Map<String, String> =
        orderedFiles.associate { (fn, _) -> fn to moduleNameOf(fn) }
    private val moduleNameSet: Set<String> = moduleNames.values.toSet()

    /** Top-level declared value/type names per file (class/function/interface/enum/type/var). */
    private val definedNames: Map<String, Set<String>> = orderedFiles.associate { (fn, sf) ->
        fn to buildSet {
            for (s in sf.statements) when (s) {
                is ClassDeclaration -> s.name?.let { add(it.text) }
                is FunctionDeclaration -> s.name?.let { add(it.text) }
                is InterfaceDeclaration -> add(s.name.text)
                is EnumDeclaration -> add(s.name.text)
                is TypeAliasDeclaration -> add(s.name.text)
                else -> {}
            }
        }
    }

    /** imported name -> source file, per file. */
    private val importMap: Map<String, Map<String, String>> = orderedFiles.associate { (fn, sf) ->
        fn to buildMap {
            for (s in sf.statements) if (s is ImportDeclaration) {
                val spec = (s.moduleSpecifier as? StringLiteralNode)?.text ?: continue
                val src = resolveSpecToFile(spec, fn) ?: continue
                (s.importClause?.namedBindings as? NamedImports)?.elements?.forEach { put(it.name.text, src) }
            }
        }
    }

    /** A resolved type reference: the name and the file that DEFINES it. */
    private data class TypeRef(val name: String, val definingFile: String?)

    fun emit(): String {
        val out = StringBuilder()
        for ((fn, sf) in orderedFiles) {
            out.append("declare module \"").append(moduleNames.getValue(fn)).append("\" {\n")
            // Which imported names are actually used as a type in an emitted signature.
            val usedTypeNames = collectUsedTypeNames(fn, sf)
            for (stmt in sf.statements) emitStatement(stmt, fn, usedTypeNames, out)
            out.append("}\n")
        }
        return out.toString().trimEnd('\n')
    }

    // ── module-name / specifier resolution ──────────────────────────────────

    private fun moduleNameOf(fileName: String): String {
        var rel = fileName
        if (commonDir.isNotEmpty()) {
            val prefix = if (commonDir.endsWith("/")) commonDir else "$commonDir/"
            if (rel.startsWith(prefix)) rel = rel.removePrefix(prefix)
        }
        return stripTsExt(rel.removePrefix("/"))
    }

    private fun stripTsExt(s: String): String = when {
        s.endsWith(".d.ts") -> s.removeSuffix(".d.ts")
        s.endsWith(".d.mts") -> s.removeSuffix(".d.mts")
        s.endsWith(".d.cts") -> s.removeSuffix(".d.cts")
        s.endsWith(".ts") -> s.removeSuffix(".ts")
        s.endsWith(".tsx") -> s.removeSuffix(".tsx")
        s.endsWith(".mts") -> s.removeSuffix(".mts")
        s.endsWith(".cts") -> s.removeSuffix(".cts")
        s.endsWith(".js") -> s.removeSuffix(".js")
        s.endsWith(".jsx") -> s.removeSuffix(".jsx")
        else -> s
    }

    /** Rewrite a relative specifier to the resolved module's bundled name; keep bare specifiers. */
    private fun resolveSpec(spec: String, importerFile: String): String {
        if (!spec.startsWith(".")) return spec
        val importerModDir = moduleNames.getValue(importerFile).substringBeforeLast('/', "")
        val joined = if (importerModDir.isEmpty()) spec else "$importerModDir/$spec"
        val norm = normalizePath(joined)
        return when {
            norm in moduleNameSet -> norm
            "$norm/index" in moduleNameSet -> "$norm/index"
            else -> norm
        }
    }

    private fun resolveSpecToFile(spec: String, importerFile: String): String? {
        if (!spec.startsWith(".")) return null
        val dir = importerFile.substringBeforeLast('/', "")
        val joined = if (dir.isEmpty()) spec else "$dir/$spec"
        val norm = normalizePath(joined)
        for (ext in listOf(".ts", ".tsx", ".d.ts", ".mts", ".cts", ".js", ".jsx"))
            if ("$norm$ext" in filesByName) return "$norm$ext"
        for (idx in listOf("/index.ts", "/index.tsx", "/index.d.ts", "/index.mts", "/index.cts"))
            if ("$norm$idx" in filesByName) return "$norm$idx"
        return null
    }

    private fun normalizePath(p: String): String {
        val segs = mutableListOf<String>()
        for (s in p.split('/')) when (s) {
            "", "." -> {}
            ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.lastIndex)
            else -> segs.add(s)
        }
        return segs.joinToString("/")
    }

    // ── return-type inference (bounded) ─────────────────────────────────────

    private fun functionInFile(file: String, name: String): FunctionDeclaration? =
        filesByName[file]?.statements?.filterIsInstance<FunctionDeclaration>()?.firstOrNull { it.name?.text == name }

    private fun inferReturnRef(file: String, fn: FunctionDeclaration): TypeRef? {
        fn.type?.let { return typeNodeRef(file, it) }
        val body = fn.body ?: return null
        val ret = body.statements.filterIsInstance<ReturnStatement>().singleOrNull()?.expression ?: return null
        return inferExprRef(file, ret, mutableSetOf())
    }

    private fun typeNodeRef(file: String, t: Node): TypeRef? {
        val tr = t as? TypeReference ?: return null
        val id = tr.typeName as? Identifier ?: return null
        return TypeRef(id.text, definingFileOf(file, id.text))
    }

    private fun inferExprRef(file: String, expr: Node, seen: MutableSet<String>): TypeRef? = when (expr) {
        is NewExpression -> (expr.expression as? Identifier)?.let { TypeRef(it.text, definingFileOf(file, it.text)) }
        is CallExpression -> {
            val callee = expr.expression as? Identifier
            if (callee != null) {
                val defFile = if (callee.text in (definedNames[file] ?: emptySet())) file
                    else importMap[file]?.get(callee.text)
                val fn = defFile?.let { functionInFile(it, callee.text) }
                val key = "$defFile:${callee.text}"
                if (fn != null && seen.add(key)) inferReturnRefSeen(defFile, fn, seen) else null
            } else null
        }
        else -> null
    }

    private fun inferReturnRefSeen(file: String, fn: FunctionDeclaration, seen: MutableSet<String>): TypeRef? {
        fn.type?.let { return typeNodeRef(file, it) }
        val body = fn.body ?: return null
        val ret = body.statements.filterIsInstance<ReturnStatement>().singleOrNull()?.expression ?: return null
        return inferExprRef(file, ret, seen)
    }

    /** The file that defines `name` as seen from `file` (local decl, else the import source). */
    private fun definingFileOf(file: String, name: String): String? = when {
        name in (definedNames[file] ?: emptySet()) -> file
        else -> importMap[file]?.get(name)
    }

    /**
     * Names used as a type in this module's emitted signatures — function return types (inferred)
     * and class heritage base names (`extends A`/`implements A`). An import kept iff its name is here.
     */
    private fun collectUsedTypeNames(file: String, sf: SourceFile): Set<String> = buildSet {
        for (s in sf.statements) when (s) {
            is FunctionDeclaration -> inferReturnRef(file, s)?.let { add(it.name) }
            is ClassDeclaration -> s.heritageClauses?.forEach { hc ->
                hc.types.forEach { (it.expression as? Identifier)?.let { id -> add(id.text) } }
            }
            else -> {}
        }
    }

    /** Does module (file) export `name` — directly or via `export * from`? */
    private fun moduleExports(file: String, name: String, seen: MutableSet<String> = mutableSetOf()): Boolean {
        if (!seen.add(file)) return false
        if (name in (definedNames[file] ?: emptySet())) return true
        val sf = filesByName[file] ?: return false
        for (s in sf.statements) if (s is ExportDeclaration) {
            val spec = (s.moduleSpecifier as? StringLiteralNode)?.text ?: continue
            val target = resolveSpecToFile(spec, file) ?: continue
            if (s.exportClause == null && moduleExports(target, name, seen)) return true
        }
        return false
    }

    /** How to reference `ref` from within `currentFile`: a bare name (accessible) or import-type. */
    private fun renderTypeRef(ref: TypeRef, currentFile: String): String {
        // Accessible directly: defined here or imported here.
        if (ref.definingFile == currentFile || importMap[currentFile]?.containsKey(ref.name) == true) return ref.name
        // Late export: prefer the current directory's index module if it re-exports the name.
        val dir = currentFile.substringBeforeLast('/', "")
        for (idxExt in listOf("/index.ts", "/index.tsx", "/index.d.ts", "/index.mts", "/index.cts")) {
            val idxFile = if (dir.isEmpty()) "index${idxExt.substringAfterLast('/').removePrefix("index")}"
                else "$dir$idxExt"
            if (idxFile != currentFile && idxFile in filesByName && moduleExports(idxFile, ref.name)) {
                val mod = moduleNames.getValue(idxFile)
                val spec = if (mod.endsWith("/index")) mod.removeSuffix("/index") else mod
                return "import(\"$spec\").${ref.name}"
            }
        }
        // Fallback: reference the defining module directly.
        val defMod = ref.definingFile?.let { moduleNames[it] }
        return if (defMod != null) "import(\"$defMod\").${ref.name}" else ref.name
    }

    // ── statement emit ──────────────────────────────────────────────────────

    private fun emitStatement(stmt: Node, file: String, usedTypeNames: Set<String>, out: StringBuilder) {
        when (stmt) {
            is ClassDeclaration -> emitClass(stmt, "    ", out)
            is FunctionDeclaration -> emitFunction(stmt, file, "    ", out)
            is ImportDeclaration -> emitImport(stmt, file, usedTypeNames, "    ", out)
            is ExportDeclaration -> emitExportDecl(stmt, file, "    ", out)
            else -> {}
        }
    }

    private fun emitClass(c: ClassDeclaration, indent: String, out: StringBuilder) {
        val sb = StringBuilder(indent)
        if (ModifierFlag.Export in c.modifiers) sb.append("export ")
        if (ModifierFlag.Default in c.modifiers) sb.append("default ")
        if (ModifierFlag.Abstract in c.modifiers) sb.append("abstract ")
        sb.append("class ").append(c.name?.text ?: "")
        c.heritageClauses?.forEach { hc ->
            val kw = if (hc.token == SyntaxKind.ImplementsKeyword) " implements " else " extends "
            sb.append(kw).append(hc.types.joinToString(", ") { renderExprName(it.expression) })
        }
        sb.append(" {")
        out.append(sb).append("\n").append(indent).append("}\n")
    }

    private fun emitFunction(f: FunctionDeclaration, file: String, indent: String, out: StringBuilder) {
        val sb = StringBuilder(indent)
        if (ModifierFlag.Export in f.modifiers) sb.append("export ")
        if (ModifierFlag.Default in f.modifiers) sb.append("default ")
        sb.append("function ").append(f.name?.text ?: "").append("(")
        // Parameters (corpus targets have none; render name+type when present, else empty).
        sb.append(f.parameters.joinToString(", ") { it.name.let { n -> (n as? Identifier)?.text ?: "" } })
        sb.append("): ")
        val ret = inferReturnRef(file, f)
        sb.append(if (ret != null) renderTypeRef(ret, file) else "void")
        sb.append(";")
        out.append(sb).append("\n")
    }

    private fun emitImport(imp: ImportDeclaration, file: String, usedTypeNames: Set<String>, indent: String, out: StringBuilder) {
        val spec = (imp.moduleSpecifier as? StringLiteralNode)?.text ?: return
        val clause = imp.importClause ?: run {
            out.append(indent).append("import \"").append(resolveSpec(spec, file)).append("\";\n"); return
        }
        // Keep only named imports that are used as a type in an emitted signature.
        val nb = clause.namedBindings
        if (nb is NamedImports) {
            val kept = nb.elements.filter { it.name.text in usedTypeNames }
            if (kept.isEmpty() && clause.name == null) return // fully unused → drop
            val names = kept.joinToString(", ") { s ->
                if (s.propertyName != null) "${s.propertyName.text} as ${s.name.text}" else s.name.text
            }
            val parts = mutableListOf<String>()
            clause.name?.let { parts.add(it.text) }
            if (kept.isNotEmpty()) parts.add("{ $names }")
            out.append(indent).append("import ").append(parts.joinToString(", "))
                .append(" from \"").append(resolveSpec(spec, file)).append("\";\n")
            return
        }
        // Namespace / default imports: emit verbatim (rewritten specifier).
        val parts = mutableListOf<String>()
        clause.name?.let { parts.add(it.text) }
        if (nb is NamespaceImport) parts.add("* as ${nb.name.text}")
        if (parts.isEmpty()) return
        out.append(indent).append("import ").append(parts.joinToString(", "))
            .append(" from \"").append(resolveSpec(spec, file)).append("\";\n")
    }

    private fun emitExportDecl(ex: ExportDeclaration, file: String, indent: String, out: StringBuilder) {
        val specNode = ex.moduleSpecifier as? StringLiteralNode
        val clause = ex.exportClause
        when {
            clause == null && specNode != null ->
                out.append(indent).append("export * from \"")
                    .append(resolveSpec(specNode.text, file)).append("\";\n")
            clause is NamedExports -> {
                val names = clause.elements.joinToString(", ") { s ->
                    if (s.propertyName != null) "${s.propertyName.text} as ${s.name.text}" else s.name.text
                }
                val from = if (specNode != null) " from \"${resolveSpec(specNode.text, file)}\"" else ""
                out.append(indent).append("export { ").append(names).append(" }").append(from).append(";\n")
            }
            else -> {}
        }
    }

    private fun renderExprName(e: Node): String = when (e) {
        is Identifier -> e.text
        is PropertyAccessExpression -> renderExprName(e.expression) + "." + e.name.text
        else -> ""
    }

    companion object {
        /** Longest common directory prefix (segment-wise) of the given file names. */
        fun computeCommonDir(fileNames: List<String>): String {
            if (fileNames.isEmpty()) return ""
            val dirs = fileNames.map { fn ->
                fn.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
            }
            var common = dirs.first()
            for (d in dirs.drop(1)) {
                var i = 0
                while (i < common.size && i < d.size && common[i] == d[i]) i++
                common = common.subList(0, i)
            }
            return common.joinToString("/")
        }
    }
}
