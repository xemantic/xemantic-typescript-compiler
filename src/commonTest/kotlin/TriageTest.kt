package com.xemantic.typescript.compiler
import kotlin.test.Test
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.io.buffered

class TriageTest {
    @Test fun t() {
        val sb = StringBuilder()
        for (n in listOf("noSymbolForMergeCrash","mergeSymbolRexportFunction","checkerInitializationCrash","mergeSymbolReexportedTypeAliasInstantiation","ensureNoCrashExportAssignmentDefineProperrtyPotentialMerge")) {
            try {
                val p = Path("typescript-repo/tests/cases/compiler/$n.ts")
                val src = SystemFileSystem.source(p).buffered().readString()
                val res = TypeScriptCompiler().compile(src, "$n.ts")
                sb.appendLine("=== $n : ${res.diagnostics.size} diagnostics ===")
                for (d in res.diagnostics) sb.appendLine("  TS${d.code} ${d.fileName}:${(d.line ?: -1)+1}:${(d.character ?: -1)+1} ${d.message}")
            } catch (e: Throwable) { sb.appendLine("=== $n CRASHED: ${e::class.simpleName}: ${e.message}") }
        }
        val out = SystemFileSystem.sink(Path("/tmp/triage_out.txt")).buffered()
        out.writeString(sb.toString()); out.close()
    }
}
