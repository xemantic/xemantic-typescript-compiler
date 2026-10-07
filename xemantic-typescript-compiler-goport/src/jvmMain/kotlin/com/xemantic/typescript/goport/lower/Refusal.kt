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

package com.xemantic.typescript.goport.lower

import java.io.File

/**
 * A lowering rule declined a construct. Thrown anywhere inside a declaration's lowering and
 * caught at the declaration: the porter then emits a `TODO("goport: refused …")` stub with the
 * same signature and counts [reason] in the report.
 */
class Refusal(val reason: String, val detail: String = "") : RuntimeException("$reason${if (detail.isEmpty()) "" else ": $detail"}")

fun refuse(reason: String, detail: String = ""): Nothing = throw Refusal(reason, detail)

/**
 * The hand-written shims' declared names (`-tsgo/src/commonMain/kotlin/{go,runtime}`), scanned
 * from source: a reference to a shim symbol that does not exist yet refuses the declaration
 * (`shim-missing`) instead of breaking the build, and the report lists what the shims lack.
 */
class ShimIndex(
    private val topLevel: Map<String, Set<String>>,
    private val members: Map<String, Set<String>>,
    /** Per package and class: member names. */
    private val classMembers: Map<String, Map<String, Set<String>>>,
    /** Classes whose primary constructor needs arguments (no zero value). */
    private val needsArgs: Map<String, Set<String>>,
    /** Top-level generic functions whose leading parameters are `GoElem<…>` dictionaries: name → count. */
    private val elemDicts: Map<String, Map<String, Int>> = emptyMap(),
) {

    /** How many leading `GoElem` dictionary parameters shim function [name] takes (0: none). */
    fun elemDictCount(kotlinPackage: String, name: String): Int = elemDicts[kotlinPackage]?.get(name) ?: 0

    fun hasTop(kotlinPackage: String, name: String): Boolean = topLevel[kotlinPackage]?.contains(name) ?: false

    fun hasMember(kotlinPackage: String, name: String): Boolean =
        members[kotlinPackage]?.contains(name) ?: false || hasTop(kotlinPackage, name)

    fun classHas(kotlinPackage: String, cls: String, member: String): Boolean =
        classMembers[kotlinPackage]?.get(cls)?.contains(member) ?: false

    fun needsArgs(kotlinPackage: String, cls: String): Boolean = needsArgs[kotlinPackage]?.contains(cls) ?: false

    companion object {
        private val decl = Regex(
            """^(\s*)(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|protected|override|open|abstract|sealed|data|inline|value|const|operator|infix|enum|annotation|external|tailrec|lateinit)\s+)*(class|interface|object|fun|val|var|typealias)\s+(?:<(?:[^<>]|<(?:[^<>]|<[^<>]*>)*>)*>\s*)?((?:[A-Za-z_][\w]*(?:<(?:[^<>]|<[^<>]*>)*>)?\??\.)*)`?([A-Za-z_]\w*)`?(.*)"""
        )

        fun scan(vararg roots: File): ShimIndex {
            val top = HashMap<String, MutableSet<String>>()
            val mem = HashMap<String, MutableSet<String>>()
            val cls = HashMap<String, MutableMap<String, MutableSet<String>>>()
            val args = HashMap<String, MutableSet<String>>()
            val dicts = HashMap<String, MutableMap<String, Int>>()
            for (root in roots) {
                if (!root.isDirectory) continue
                root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
                    val lines = f.readLines()
                    val pkg = lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim() ?: return@forEach
                    top.getOrPut(pkg) { HashSet() }
                    var current: String? = null
                    for (l in lines) {
                        if (l.isNotEmpty() && !l[0].isWhitespace() && !l.startsWith("}") && !l.startsWith("@") && !l.startsWith("//") && !l.startsWith("/*") && !l.startsWith(" *")) current = null
                        val m = decl.find(l) ?: continue
                        val indent = m.groupValues[1]
                        val kind = m.groupValues[2]
                        val receiver = m.groupValues[3]
                        val name = m.groupValues[4]
                        if (indent.isEmpty() && receiver.isEmpty()) {
                            top.getValue(pkg) += name
                            if (kind == "fun") {
                                // `fun <T> collect(elem: GoElem<T>, seq: Seq<T>)`: the caller passes the element kinds.
                                val ps = m.groupValues[5].substringAfter('(', "")
                                val n = Regex("""^(?:\s*\w+\s*:\s*(?:[\w.]*\.)?GoElem<[^,]*>\s*,?)+""").find(ps)?.value?.let { lead ->
                                    Regex("""GoElem<""").findAll(lead).count()
                                } ?: 0
                                if (n > 0) dicts.getOrPut(pkg) { HashMap() }[name] = n
                            }
                            if (kind == "class") {
                                current = name
                                cls.getOrPut(pkg) { HashMap() }.getOrPut(name) { HashSet() }
                                val rest = m.groupValues[5]
                                if (rest.startsWith("(") && !rest.startsWith("()")) {
                                    // A primary constructor: needs arguments when some parameter has no default.
                                    val ps = rest.substringAfter('(').substringBefore(')')
                                    if (ps.split(',').any { it.isNotBlank() && '=' !in it }) args.getOrPut(pkg) { HashSet() } += name
                                }
                            }
                        } else {
                            mem.getOrPut(pkg) { HashSet() } += name
                            if (indent.length == 4 && current != null) cls.getValue(pkg).getValue(current) += name
                            if (receiver.isNotEmpty()) {
                                val r = receiver.removeSuffix(".").substringBefore('<').removeSuffix("?")
                                cls.getOrPut(pkg) { HashMap() }.getOrPut(r) { HashSet() } += name
                            }
                        }
                    }
                }
            }
            return ShimIndex(top, mem, cls, args, dicts)
        }
    }
}
