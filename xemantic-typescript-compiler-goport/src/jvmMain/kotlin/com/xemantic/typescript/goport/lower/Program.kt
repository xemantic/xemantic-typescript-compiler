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

import com.xemantic.typescript.goport.ir.IrPackage
import com.xemantic.typescript.goport.ir.Node
import com.xemantic.typescript.goport.ir.bool
import com.xemantic.typescript.goport.ir.int
import com.xemantic.typescript.goport.ir.k
import com.xemantic.typescript.goport.ir.list
import com.xemantic.typescript.goport.ir.obj
import com.xemantic.typescript.goport.ir.str
import com.xemantic.typescript.goport.naming.Naming
import com.xemantic.typescript.goport.naming.RenameTable
import com.xemantic.typescript.goport.types.TypeTable

/** Java.lang.Object members a generated member must not accidentally declare. */
val OBJECT_MEMBERS = setOf("toString", "hashCode", "equals", "getClass", "wait", "notify", "notifyAll", "finalize", "clone")

/**
 * Whole-run facts: every package of the run is loaded (the IR is ~75 MB), and the indices the
 * lowering of one package needs about another are computed here once.
 */
class Program(
    val packages: List<IrPackage>,
    val shims: ShimIndex,
    val renames: RenameTable,
) {
    val byPath: Map<String, IrPackage> = packages.associateBy { it.path }
    val ported: Set<String> = byPath.keys

    /** Pointer-receiver methods that compare their receiver to nil: lowered as extensions on `T?`. */
    val nilSafeMethods = HashSet<String>()

    /** Named types lowered as Kotlin typealiases: `type X Y` over a named struct, and named slice/map/func types. */
    val aliasTypes = HashSet<String>()

    /** Struct aliases (`type MutableNode Node`): a typealias to the class, never a copy. */
    val structAliases = HashSet<String>()

    /** For a struct alias declared in the run: the (Kotlin package, class) it aliases, when that is a SHIM class. */
    val structAliasTargets = HashMap<String, Pair<String, String>>()

    fun structAliasTarget(t: com.xemantic.typescript.goport.types.NamedType): Pair<String, String>? = structAliasTargets[t.key]

    /** Go method names of every interface a named type's `implements` lists (a superset of the overrides). */
    val ifaceMethodNames = HashMap<String, MutableSet<String>>()

    /** Named STRUCT types declared in the run (not aliases): keys. */
    val structTypes = HashSet<String>()

    fun structKind(key: String): Boolean = key in structTypes

    /** Methods lowered as EXTENSION functions: nil-safe ones and those of [aliasTypes]. */
    val extensionMethods = HashSet<String>()

    /** Value-receiver methods (struct receivers) that write their receiver: the call site must copy. */
    val receiverMutators = HashSet<String>()

    /** Methods declared on each named type key (`pkg.Type` → method FuncDecls). */
    val methodsByType = HashMap<String, MutableList<Pair<IrPackage, Node>>>()

    /** Kotlin names of top-level declarations per package path (value namespace and type namespace). */
    val topValueNames = HashMap<String, MutableSet<String>>()
    val topTypeNames = HashMap<String, MutableSet<String>>()

    val synth = SynthRegistry()

    /** Synthetic classes for anonymous struct types, per Go package path. */
    val anonStructs = HashMap<String, LinkedHashMap<String, String>>()

    /** Named struct types used as map KEYS by value: they get a structural equals/hashCode. */
    val structKeys = HashSet<String>()

    init {
        for (p in packages) index(p)
        extensionMethods += nilSafeMethods
        for ((typeKey, ms) in methodsByType) if (typeKey in aliasTypes) ms.forEach { extensionMethods += it.second.str("qname")!! }
        // Go calls a pointer-receiver method on a nil pointer and only panics where the body
        // dereferences it (`f.UpdateX(node, …)` with a nil factory returns `node` unchanged). An
        // extension on `T?` keeps that: the call never asserts, the body's `this!!.f` panics where
        // Go would. Methods an implemented interface requires stay members (overrides).
        for ((typeKey, ms) in methodsByType) {
            if (typeKey !in structTypes) continue
            val required = ifaceMethodNames[typeKey] ?: emptySet()
            for ((_, d) in ms) {
                val ptr = d.obj("recv")!!.list("list").first().obj("type")?.str("star") == "pointerType"
                if (ptr && d.str("name") !in required) extensionMethods += d.str("qname")!!
            }
        }
    }

    private fun index(p: IrPackage) {
        val tt = TypeTable(p)
        for (i in 0 until tt.size) {
            val n = tt[i] as? com.xemantic.typescript.goport.types.NamedType
            if (n != null && n.pkg == p.path && n.origin == null) {
                for (im in n.node.list("implements")) {
                    val iface = tt.under(im.int("iface")!!) as? com.xemantic.typescript.goport.types.InterfaceType ?: continue
                    ifaceMethodNames.getOrPut(n.key) { HashSet() } += iface.allMethods.ifEmpty { iface.methods }.map { it.name }
                }
            }
        }
        for (i in 0 until tt.size) {
            val m = tt[i] as? com.xemantic.typescript.goport.types.MapType ?: continue
            val k = tt.unalias(m.keyType) as? com.xemantic.typescript.goport.types.NamedType ?: continue
            if (tt.under(k.id) !is com.xemantic.typescript.goport.types.StructType) continue
            val origin = k.origin?.let { tt.unalias(it) as com.xemantic.typescript.goport.types.NamedType } ?: k
            structKeys += origin.key
        }
        val values = topValueNames.getOrPut(p.path) { HashSet() }
        val typesN = topTypeNames.getOrPut(p.path) { HashSet() }
        for (f in p.files) for (d in f.list("decls")) {
            when (d.k) {
                "FuncDecl" -> {
                    val recv = d.obj("recv")
                    if (recv == null) {
                        if (d.str("name") != "init") values += funName(d.str("qname")!!, d.str("name")!!)
                        continue
                    }
                    val q = d.str("qname")!!
                    val typeKey = q.substringBeforeLast('.')
                    methodsByType.getOrPut(typeKey) { ArrayList() } += p to d
                    val rf = recv.list("list").first()
                    val recvObj = rf.list("names").firstOrNull()?.int("obj")
                    val ptr = rf.obj("type")?.str("star") == "pointerType"
                    if (recvObj != null) {
                        val body = d.obj("body")
                        if (ptr && body != null && comparesToNil(body, recvObj)) nilSafeMethods += q
                        if (!ptr && body != null && writesReceiver(body, recvObj)) receiverMutators += q
                    }
                }
                "GenDecl" -> for (s in d.list("specs")) when (s.k) {
                    "TypeSpec" -> s.str("qname")?.let { q ->
                        typesN += typeName(q, s.str("name")!!)
                        val te = s.obj("type")!!
                        val tid = te.int("t")
                        if (!s.bool("alias") && tid != null) {
                            val u = tt.under(tid)
                            if (u is com.xemantic.typescript.goport.types.StructType && te.str("k") != "Ident" && te.str("k") != "SelectorExpr") structTypes += q
                            if (u is com.xemantic.typescript.goport.types.StructType && (te.str("k") == "Ident" || te.str("k") == "SelectorExpr")) {
                                structAliases += q
                                aliasTypes += q
                                val target = tt.unalias(tid) as? com.xemantic.typescript.goport.types.NamedType
                                if (target?.pkg != null && target.pkg !in packages.map { it.path }) {
                                    structAliasTargets[q] = Naming.kotlinPackage(target.pkg) to target.name
                                }
                            } else if (u !is com.xemantic.typescript.goport.types.StructType && u !is com.xemantic.typescript.goport.types.InterfaceType &&
                                u !is com.xemantic.typescript.goport.types.BasicType
                            ) aliasTypes += q
                        }
                    }
                    "ValueSpec" -> {
                        val isConst = d.str("tok") == "const"
                        for ((i, q) in s.list("names").withIndex()) {
                            val name = q.str("name")!!
                            if (name == "_") continue
                            val qn = (s["qnames"] as? kotlinx.serialization.json.JsonArray)?.getOrNull(i)?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                                ?: continue
                            values += if (isConst) constName(qn, name) else varName(qn, name)
                        }
                    }
                }
            }
        }
    }

    // ---- names of package-level declarations (keyed by the Go qualified name / object key) ----

    fun funName(qname: String, goName: String): String {
        renames.lookup(qname)?.let { return it }
        if (goName == "init") return "init" + (qname.substringAfter('#', "").toIntOrNull()?.let { "_${it - 1}" } ?: "")
        return Naming.escape(Naming.lowerCamel(goName))
    }
    fun varName(qname: String, goName: String): String = renames.lookup(qname) ?: Naming.escape(Naming.lowerCamel(goName))
    fun constName(qname: String, goName: String): String = renames.lookup(qname) ?: Naming.escape(goName)
    fun typeName(qname: String, goName: String): String = renames.lookup(qname) ?: Naming.escape(goName)

    fun methodName(key: String, goName: String): String {
        renames.lookup(key)?.let { return it }
        val n = Naming.lowerCamel(goName)
        return Naming.escape(n)
    }

    /** A struct field; Go's blank fields (`_ noCopy`) are numbered by their [index]. */
    fun fieldName(key: String?, goName: String, index: Int = -1): String {
        if (goName == "_") return "blank$index"
        if (key != null) renames.lookup(key)?.let { return it }
        return Naming.escape(Naming.lowerCamel(goName))
    }

    companion object {

        /** Pre-order walk over IR nodes (objects, JSON arrays and Kotlin lists of nodes); [visit] answers "descend". */
        fun walk(n: Any?, visit: (Node) -> Boolean) {
            when (n) {
                is kotlinx.serialization.json.JsonObject -> {
                    if (!visit(n)) return
                    for (v in n.values) walk(v, visit)
                }
                is List<*> -> for (v in n) walk(v, visit)
                else -> {}
            }
        }

        /** Visits [id] and every type reachable from it (cycle-safe). */
        fun walkTypes(types: TypeTable, id: Int, visit: (com.xemantic.typescript.goport.types.GoType) -> Unit) {
            val seen = HashSet<Int>()
            fun go(i: Int) {
                if (!seen.add(i)) return
                val t = types[i]
                visit(t)
                when (t) {
                    is com.xemantic.typescript.goport.types.PointerType -> go(t.elem)
                    is com.xemantic.typescript.goport.types.SliceType -> go(t.elem)
                    is com.xemantic.typescript.goport.types.ArrayType -> go(t.elem)
                    is com.xemantic.typescript.goport.types.MapType -> { go(t.keyType); go(t.elem) }
                    is com.xemantic.typescript.goport.types.SignatureType -> { t.params.forEach { go(it.t) }; t.results.forEach { go(it.t) } }
                    is com.xemantic.typescript.goport.types.StructType -> t.fields.forEach { go(it.t) }
                    is com.xemantic.typescript.goport.types.AliasType -> go(t.actual)
                    is com.xemantic.typescript.goport.types.NamedType -> t.targs.forEach { go(it) }
                    else -> {}
                }
            }
            go(id)
        }

        fun comparesToNil(body: Node, obj: Int): Boolean {
            var found = false
            walk(body) { x ->
                if (x.str("k") == "BinaryExpr" && (x.str("op") == "==" || x.str("op") == "!=")) {
                    val a = x.obj("x")!!
                    val b = x.obj("y")!!
                    if ((a.isIdentOf(obj) && b.str("m") == "nil") || (b.isIdentOf(obj) && a.str("m") == "nil")) found = true
                }
                !found
            }
            return found
        }

        private fun Node.isIdentOf(obj: Int) = str("k") == "Ident" && int("obj") == obj

        /** The root identifier object of an lvalue path (`r.a.b[i]` → `r`). */
        fun rootObj(e: Node): Int? = when (e.str("k")) {
            "Ident" -> e.int("obj")
            "SelectorExpr" -> if (e.bool("qual")) null else rootObj(e.obj("x")!!)
            "IndexExpr" -> if (e.str("ik") == "array") rootObj(e.obj("x")!!) else null
            "ParenExpr" -> rootObj(e.obj("x")!!)
            else -> null
        }

        fun writesReceiver(body: Node, obj: Int): Boolean {
            var found = false
            walk(body) { x ->
                when (x.str("k")) {
                    "AssignStmt" -> for (l in x.list("lhs")) if (l.str("k") != "Ident" && rootObj(l) == obj) found = true
                    "IncDecStmt" -> if (x.obj("x")!!.str("k") != "Ident" && rootObj(x.obj("x")!!) == obj) found = true
                    "UnaryExpr" -> if (x.str("op") == "&" && rootObj(x.obj("x")!!) == obj) found = true
                    "SelectorExpr" -> if (x.bool("autoAddr") && rootObj(x.obj("x")!!) == obj) found = true
                    "SliceExpr" -> if (x.str("sk") == "array" && rootObj(x.obj("x")!!) == obj) found = true
                }
                !found
            }
            return found
        }
    }
}

/**
 * Synthetic Kotlin interfaces standing for ANONYMOUS Go method-set interfaces
 * (`interface{ KindString() string }`): Kotlin has no structural interfaces, so every anonymous
 * interface the run mentions becomes one nominal interface, named after its methods, declared in
 * `gen/synth`, and implemented by every type the IR's `implements` lists for it.
 */
class SynthRegistry {
    /** Kotlin name by canonical signature text. */
    val names = LinkedHashMap<String, String>()
    val bodies = LinkedHashMap<String, String>()

    fun name(canonical: String, methodNames: List<String>): String = names.getOrPut(canonical) {
        val base = "Iface_" + methodNames.joinToString("_").take(60)
        val h = Integer.toHexString(canonical.hashCode()).padStart(8, '0')
        "${base}_$h"
    }
}

/** One package's lowering context. */
class PkgCtx(val prog: Program, val pkg: IrPackage) {
    val types = TypeTable(pkg)
    val kotlinPackage = Naming.kotlinPackage(pkg.path)
    val topValues: Set<String> = prog.topValueNames[pkg.path] ?: emptySet()
    val topTypes: Set<String> = prog.topTypeNames[pkg.path] ?: emptySet()

    fun obj(id: Int): Node = pkg.objects[id]

    /** Method FuncDecls by receiver base type key, declared in THIS package. */
    fun methodsOf(typeKey: String): List<Node> =
        prog.methodsByType[typeKey]?.filter { it.first === pkg }?.map { it.second } ?: emptyList()
}

/**
 * One generated Kotlin file: collects the type imports its rendering asked for. A name is imported
 * only when it collides with nothing; otherwise it is written fully qualified.
 */
open class FileCtx(val pc: PkgCtx) {
    val imports = sortedMapOf<String, String>()

    open fun typeRef(kotlinPackage: String, name: String): String {
        if (kotlinPackage == pc.kotlinPackage) return name
        val fqn = "$kotlinPackage.$name"
        if (name in RESERVED_SHORT || name in pc.topTypes || name in pc.topValues) return fqn
        val cur = imports[name]
        if (cur != null) return if (cur == fqn) name else fqn
        imports[name] = fqn
        return name
    }

    /** Function imports (extensions are callable only through an import; same-named ones from several packages coexist). */
    val funImports = sortedSetOf<String>()

    /** Imports an extension function. */
    fun importFun(kotlinPackage: String, name: String) {
        if (kotlinPackage == pc.kotlinPackage) return
        funImports += "$kotlinPackage.${name.trim('`').let { if (name.startsWith("`")) "`$it`" else it }}"
    }

    companion object {
        val RESERVED_SHORT = setOf(
            "String", "Int", "Long", "Boolean", "Double", "Float", "UInt", "ULong", "Any", "Unit", "Nothing",
            "Array", "Comparable", "List", "Map", "Set", "Pair", "Triple", "Char", "Byte", "Short", "Number",
            "GoSlice", "GoMap", "GoElem", "GoPtr", "GoBox", "GoArray", "GoError", "GoPanic", "Tuple2", "Tuple3",
            "Tuple4", "Tuple5", "GoFieldPtr", "GoDeferFrame", "Exception", "Error", "Iterable", "Sequence",
            "Function", "JvmInline", "Suppress", "Iterator",
        )
    }
}
