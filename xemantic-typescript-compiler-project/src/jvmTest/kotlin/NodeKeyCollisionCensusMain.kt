/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 */

package com.xemantic.typescript.compiler.project

import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.Parser
import com.xemantic.typescript.compiler.forEachChild
import java.io.File

/**
 * How often does one program's `(pos, end)` node key name a node in MORE THAN ONE
 * FILE?
 *
 * `Binder.nodeToSymbol` and `Binder.moduleInstanceStates` are shared by every
 * `BinderResult` from one `Binder` and keyed by `nodeKey(pos, end)`, so a key that
 * two files both produce is LAST-WINS in bind order. This counts the population
 * that hazard ranges over, on a real project.
 *
 * It also reports, for the target file's LAST statement, which trailing-newline
 * counts make its key collide — that statement's `end` is the only span in a file
 * that trailing whitespace moves.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [<fileSuffix> [maxNewlines]]" }
    val dir = args[0]
    val suffix = if (args.size > 1) args[1] else null
    val max = if (args.size > 2) args[2].toInt() else 400

    val project = Project.open(dir)
    val files = project.files
    project.close()
    println("programFiles=${files.size}")

    // key -> files that produce it, and one sample kind per file
    val owners = HashMap<Long, MutableMap<String, String>>(1 shl 20)
    var nodes = 0L

    fun key(pos: Int, end: Int): Long =
        ((pos.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)) * -0x61c8864680b583ebL

    for (file in files) {
        val text = try { File(file).readText() } catch (_: Exception) { continue }
        val root = try { Parser(text, file).parse() } catch (_: Exception) { continue }
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            nodes++
            owners.getOrPut(key(n.pos, n.end)) { HashMap(2) }
                .putIfAbsent(file, n::class.simpleName ?: "?")
            forEachChild(n) { c -> stack.addLast(c); }
        }
    }

    var shared = 0
    var sharedPairs = 0L
    for ((_, m) in owners) if (m.size > 1) { shared++; sharedPairs += m.size }
    println("nodes=$nodes distinctKeys=${owners.size} keysInMoreThanOneFile=$shared (avgFiles=${if (shared==0) 0.0 else sharedPairs.toDouble()/shared})")
    println("collisionRateOfKeys=${"%.4f".format(100.0 * shared / owners.size)}%")

    // The kinds `Binder.recordNodeSymbol` actually stores — a collision only matters
    // when a key is WRITTEN by one file and READ for another file's node of a kind
    // some read site passes.
    val declKinds = setOf(
        "ModuleDeclaration", "ClassDeclaration", "EnumDeclaration", "EnumMember",
        "InterfaceDeclaration", "FunctionDeclaration", "VariableDeclaration",
        "TypeAliasDeclaration", "ImportSpecifier", "ExportSpecifier",
        "ImportDeclaration", "ImportEqualsDeclaration", "ImportClause",
    )
    var declShared = 0
    val examples = ArrayList<String>()
    for ((_, m) in owners) {
        if (m.size < 2) continue
        val decls = m.entries.filter { it.value in declKinds }
        if (decls.size < 2) continue
        declShared++
        if (examples.size < 12) examples.add(decls.joinToString(" | ") { "${it.key.substringAfterLast('/')}:${it.value}" })
    }
    println("keysSharedByTwoDECLARATIONnodesInDifferentFiles=$declShared")
    for (e in examples) println("   $e")

    // Every file's LAST statement, swept over trailing-newline counts: its `end` is
    // the EOF offset, so it is the one span trailing whitespace moves.
    var filesWithReachableCollision = 0
    for (file in files) {
        val text = try { File(file).readText() } catch (_: Exception) { continue }
        val b = text.trimEnd('\n') + "\n"
        val r = try { Parser(b, file).parse() } catch (_: Exception) { continue }
        val last = r.statements.lastOrNull() ?: continue
        val hits = ArrayList<String>()
        for (n in 0..max) {
            val m = owners[key(last.pos, last.end + n)] ?: continue
            val others = m.filterKeys { it != file }
            if (others.isNotEmpty() && hits.size < 3)
                hits.add("n=$n->${others.entries.first().let { "${it.key.substringAfterLast('/')}:${it.value}" }}")
        }
        if (hits.isNotEmpty()) {
            filesWithReachableCollision++
            if (filesWithReachableCollision <= 15)
                println("  ${file.substringAfterLast('/')} lastStmt pos=${last.pos} end=${last.end} collides ${hits.joinToString(", ")}")
        }
    }
    println("filesWhoseLastStatementCanBeDrivenIntoACollision=$filesWithReachableCollision of ${files.size}")

    if (suffix != null) {
        val target = files.firstOrNull { it.endsWith(suffix) } ?: error("no file ends with $suffix")
        val text = File(target).readText()
        val base = text.trimEnd('\n') + "\n"
        val root = Parser(base, target).parse()
        val last = root.statements.lastOrNull() ?: error("no statements")
        println("target=$target baseLen=${base.length} lastStatement=${last::class.simpleName} pos=${last.pos} end=${last.end}")
        // `end` of the last statement is the EOF offset, so it grows by one per
        // appended newline. Which of those values already names a node elsewhere?
        var hits = 0
        for (n in 0..max) {
            val k = key(last.pos, last.end + n)
            val m = owners[k] ?: continue
            val others = m.filterKeys { it != target }
            if (others.isEmpty()) continue
            hits++
            println("  n=$n key collides with ${others.entries.take(4).joinToString { "${it.key.substringAfterLast('/')}:${it.value}" }}")
        }
        println("lastStatementCollisions=$hits of ${max + 1} trailing-newline counts")
    }
}
