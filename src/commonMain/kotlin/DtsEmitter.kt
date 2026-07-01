package com.xemantic.typescript.compiler

/**
 * Emits a single BUNDLED `.d.ts` for an `@outFile` + `@declaration`/`@emitDeclarationOnly`
 * compilation: each input module is wrapped in `declare module "<name>" { <decls-as-.d.ts> }`,
 * in dependency order. Module names are the file paths relative to the common source directory,
 * without extension; relative import/export specifiers are rewritten to those module names.
 *
 * Scope: the declaration shapes the outFile-bundle corpus needs (class, function, import,
 * `export * from`, interface, type-alias, enum, variable). Unknown shapes are skipped
 * (best-effort) so this never crashes on an unexpected input.
 */
internal class DtsBundleEmitter(
    /** (fileName, parsed) in emit (dependency) order. */
    private val orderedFiles: List<Pair<String, SourceFile>>,
    private val commonDir: String,
) {
    private val moduleNames: Map<String, String> =
        orderedFiles.associate { (fn, _) -> fn to moduleNameOf(fn) }
    private val moduleNameSet: Set<String> = moduleNames.values.toSet()

    fun emit(): String {
        val out = StringBuilder()
        for ((fn, sf) in orderedFiles) {
            out.append("declare module \"").append(moduleNames.getValue(fn)).append("\" {\n")
            for (stmt in sf.statements) emitStatement(stmt, fn, out)
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
        rel = rel.removePrefix("/")
        return stripTsExt(rel)
    }

    private fun stripTsExt(s: String): String = when {
        s.endsWith(".d.ts") -> s.removeSuffix(".d.ts")
        s.endsWith(".ts") -> s.removeSuffix(".ts")
        s.endsWith(".tsx") -> s.removeSuffix(".tsx")
        s.endsWith(".mts") -> s.removeSuffix(".mts")
        s.endsWith(".cts") -> s.removeSuffix(".cts")
        s.endsWith(".d.mts") -> s.removeSuffix(".d.mts")
        s.endsWith(".d.cts") -> s.removeSuffix(".d.cts")
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

    private fun normalizePath(p: String): String {
        val segs = mutableListOf<String>()
        for (s in p.split('/')) when (s) {
            "", "." -> {}
            ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.lastIndex)
            else -> segs.add(s)
        }
        return segs.joinToString("/")
    }

    // ── statement emit ──────────────────────────────────────────────────────

    private fun emitStatement(stmt: Node, importerFile: String, out: StringBuilder) {
        when (stmt) {
            is ClassDeclaration -> emitClass(stmt, "    ", out)
            is ImportDeclaration -> emitImport(stmt, importerFile, "    ", out)
            is ExportDeclaration -> emitExportDecl(stmt, importerFile, "    ", out)
            else -> {} // best-effort: skip shapes not yet supported
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
        out.append(sb).append("\n")
        // Members (empty for the current corpus targets) would render at "$indent    ".
        out.append(indent).append("}\n")
    }

    private fun emitImport(imp: ImportDeclaration, importerFile: String, indent: String, out: StringBuilder) {
        val spec = (imp.moduleSpecifier as? StringLiteralNode)?.text ?: return
        val rewritten = resolveSpec(spec, importerFile)
        val clause = imp.importClause
        if (clause == null) {
            out.append(indent).append("import \"").append(rewritten).append("\";\n")
            return
        }
        val parts = mutableListOf<String>()
        clause.name?.let { parts.add(it.text) }
        when (val nb = clause.namedBindings) {
            is NamespaceImport -> parts.add("* as ${nb.name.text}")
            is NamedImports -> parts.add(
                "{ " + nb.elements.joinToString(", ") { s ->
                    if (s.propertyName != null) "${s.propertyName.text} as ${s.name.text}" else s.name.text
                } + " }"
            )
            else -> {}
        }
        val typeOnly = if (clause.isTypeOnly) "type " else ""
        out.append(indent).append("import ").append(typeOnly)
            .append(parts.joinToString(", ")).append(" from \"").append(rewritten).append("\";\n")
    }

    private fun emitExportDecl(ex: ExportDeclaration, importerFile: String, indent: String, out: StringBuilder) {
        val specNode = ex.moduleSpecifier as? StringLiteralNode
        val clause = ex.exportClause
        when {
            // export * from "..."
            clause == null && specNode != null ->
                out.append(indent).append("export * from \"")
                    .append(resolveSpec(specNode.text, importerFile)).append("\";\n")
            // export { a, b as c } [from "..."]
            clause is NamedExports -> {
                val names = clause.elements.joinToString(", ") { s ->
                    if (s.propertyName != null) "${s.propertyName.text} as ${s.name.text}" else s.name.text
                }
                val from = if (specNode != null) " from \"${resolveSpec(specNode.text, importerFile)}\"" else ""
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
