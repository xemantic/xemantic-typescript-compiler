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
import com.xemantic.typescript.goport.ir.ints
import com.xemantic.typescript.goport.ir.k
import com.xemantic.typescript.goport.ir.list
import com.xemantic.typescript.goport.ir.obj
import com.xemantic.typescript.goport.ir.str
import com.xemantic.typescript.goport.naming.Naming
import com.xemantic.typescript.goport.naming.RenameTable
import com.xemantic.typescript.goport.types.TypeTable

/** Java.lang.Object members a generated member must not accidentally declare. */
/** The largest Go body (lines) lowered `inline` ([Program.computeInlineFuncs]). */
const val INLINE_MAX_LINES = 15

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

    /** A struct whose fields do not fit a JVM constructor's 255 argument slots (Decls.structClass). */
    fun bigStruct(st: com.xemantic.typescript.goport.types.StructType): Boolean = st.fields.size > 120

    /**
     * Pointer-receiver methods of named basic types (`func (t *Tristate) UnmarshalJSON`): extensions
     * on `GoPtr<V>?` (qnames and object keys); the type gets a `<V>_Ptr` box implementing the
     * interfaces those methods satisfy (Decls.valuePtrBox).
     */
    val valuePtrMethods = HashSet<String>()

    /** Value classes that got a `<V>_Ptr` box (filled while emitting; see [hasValuePtrBox]). */
    val valuePtrBoxes = HashSet<String>()

    /** Whether value class [key] has pointer methods (so a `<V>_Ptr` box is emitted for it). */
    fun hasValuePtrBox(key: String): Boolean = methodsByType[key]?.any { it.second.str("qname") in valuePtrMethods } == true

    /** Pointer-receiver methods whose body never reads the receiver (qnames and object keys). */
    val recvUnusedMethods = HashSet<String>()

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

    /** A named, non-generic method-set interface declared in the run: its method identities (name + signature key). */
    class IfaceInfo(val key: String, val pkg: String, val name: String, val methods: Set<String>, val embeds: Set<String>)

    /** Whether named interface [a] embeds [b], directly or transitively. */
    private fun embedsTransitively(a: IfaceInfo, b: String, seen: HashSet<String> = HashSet()): Boolean {
        if (!seen.add(a.key)) return false
        if (b in a.embeds) return true
        return a.embeds.any { e -> namedIfaceByKey[e]?.let { embedsTransitively(it, b, seen) } ?: false }
    }

    val namedIfaces = ArrayList<IfaceInfo>()

    /** Exported method-set interfaces of NON-ported packages that have a shim (`json.UnmarshalerFrom`): candidates for generic types. */
    val externalIfaces = LinkedHashMap<String, IfaceInfo>()
    val namedIfaceByKey = HashMap<String, IfaceInfo>()

    /**
     * Go assigns an interface value to any interface whose method set is a SUBSET of its own
     * (structurally, without a conversion the IR could record). The Kotlin interface therefore
     * extends every named interface of the run with a smaller method set (equal sets: the one
     * with the smaller key is the super, so no cycle). docs/goport-lowering.md § 3.
     */
    fun structuralIfaceSupers(key: String): List<IfaceInfo> {
        val a = namedIfaceByKey[key] ?: return emptyList()
        return namedIfaces.filter { b ->
            b.key != a.key && a.methods.containsAll(b.methods) && !embedsTransitively(b, a.key) &&
                (b.methods.size < a.methods.size || b.key < a.key || embedsTransitively(a, b.key))
        }
    }

    /** Generic origin types: method identity → Go method name, from the pointer method set (see [genericImplements]). */
    private val genericMsets = HashMap<String, Map<String, String>>()

    /**
     * Interfaces a GENERIC named type satisfies for every instantiation (the IR skips generic types,
     * `implementsSkipped`): those of [namedIfaces] whose methods all appear in the type's method set
     * with an identical signature key — a signature mentioning a type parameter never matches.
     */
    val genericImplements = HashMap<String, List<IfaceInfo>>()

    /**
     * Named slice/map/func types that implement interfaces (`type group []*Glob` with `String()`):
     * a typealias cannot implement one, so a value converted to an interface is wrapped in a
     * generated `<Name>_Box` class (docs/goport-lowering.md § 3).
     */
    val boxedNamed = HashSet<String>()

    /**
     * Reflection by codegen (docs/goport-lowering.md § 3): named struct types that reach `reflect`
     * (a `reflect.ValueOf`/`TypeFor` operand) or `json` (a field with a `json:` tag), closed over
     * their embedded structs. Each becomes a `GoReflectStruct` (and a `GoJsonStruct`).
     */
    val reflectStructs = HashSet<String>()
    private val reflectEmbeds = HashMap<String, MutableSet<String>>()
    private val reflectParentEmbeds = HashMap<String, MutableSet<String>>()

    /**
     * `//go:embed <file>` string variables, per package path: (Kotlin function name, pattern relative
     * to the package directory). Main writes the file contents as generated Kotlin (`EmbedData*.kt`).
     */
    val embeds = HashMap<String, MutableList<Pair<String, String>>>()

    /**
     * Function-local named types (`type MemberInfo struct{…}` inside a function), hoisted to the
     * package top level as `<Name>_<function>`: type key → synthetic qname, and qname → Kotlin name.
     */
    val localTypeQnames = HashMap<String, String>()
    val localTypeNames = HashMap<String, String>()

    /** The local TypeSpecs of one top-level declaration, by its qname (with their synthetic qnames). */
    val localTypeSpecs = HashMap<String, MutableList<Pair<String, Node>>>()

    /**
     * Automatic case-collision renames (docs/goport-lowering.md § 3): Go's exported `Foo` and
     * unexported `foo` on one receiver type (or at one package's top level) map to one Kotlin name.
     * The UNEXPORTED name gets the suffix `Impl`, package-wide (`"<pkg>\u0000<goName>"`), so every
     * method and interface method of that unexported name in the package agrees — unexported names
     * are package-scoped in Go, so no other package can name it. `renames.txt` still wins.
     */
    val autoMethodRenames = HashSet<String>()
    val autoFunRenames = HashSet<String>()

    init {
        for (p in packages) index(p)
        reflectThroughParameters()
        // Close the reflect/json structs over their embedded structs (json flattens them).
        // ... and a struct EMBEDDING a json struct is one too (`packagejson.Fields` embeds the tagged
        // HeaderFields/PathFields/DependencyFields and is what `json.Unmarshal` receives).
        do {
            var grew = false
            for ((parent, embeds) in reflectParentEmbeds) if (parent !in reflectStructs && embeds.any { it in reflectStructs }) grew = reflectStructs.add(parent) || grew
        } while (grew)
        val work = ArrayDeque(reflectStructs)
        while (work.isNotEmpty()) for (e in reflectEmbeds[work.removeFirst()] ?: emptySet()) if (reflectStructs.add(e)) work += e
        for ((key, ms) in genericMsets) {
            val found = (namedIfaces + externalIfaces.values).filter { b -> ms.keys.containsAll(b.methods) }
            if (found.isEmpty()) continue
            genericImplements[key] = found
            ifaceMethodNames.getOrPut(key) { HashSet() } += found.flatMap { b -> b.methods.map { ms.getValue(it) } }
        }
        for ((_, ms) in methodsByType) {
            val byKotlin = ms.groupBy { Naming.lowerCamel(it.second.str("name")!!) }
            for ((_, group) in byKotlin) {
                if (group.size < 2) continue
                for ((pk, d) in group) {
                    val n = d.str("name")!!
                    if (n.isNotEmpty() && n[0].isLowerCase()) autoMethodRenames += pk.path + "\u0000" + n
                }
            }
        }
        for (p in packages) {
            val names = ArrayList<String>()
            for (f in p.files) for (d in f.list("decls")) {
                if (d.k != "FuncDecl" || d.obj("recv") != null) continue
                val n = d.str("name") ?: continue
                if (n != "_" && n != "init") names += n
            }
            val byKotlin = names.distinct().groupBy { Naming.lowerCamel(it) }
            for ((_, group) in byKotlin) if (group.size > 1) {
                for (n in group) if (n[0].isLowerCase()) autoFunRenames += p.path + "\u0000" + n
            }
        }
        extensionMethods += nilSafeMethods
        extensionMethods += valuePtrMethods
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
        // A method declared on an ALIAS receiver (`func (n *ImportAttributesNode) …`, `= Node`) has the
        // qname of the alias but the object key of the aliased type: call sites look up by key.
        for ((_, ms) in methodsByType) for ((p, d) in ms) {
            if (d.str("qname") !in extensionMethods) continue
            d.int("obj")?.let { p.obj(it).str("key") }?.let { extensionMethods += it }
        }
    }

    /**
     * A method's identity for structural interface satisfaction: its name (package-qualified when
     * unexported) and its signature WITHOUT parameter names or receiver — type keys only.
     */
    fun methodIdentity(tt: TypeTable, pkg: String, name: String, sig: Int): String {
        val s = tt.unalias(sig) as com.xemantic.typescript.goport.types.SignatureType
        return (if (name[0].isUpperCase()) name else "$pkg.$name") + "|" + s.params.joinToString(",") { canonKey(tt, it.t) } +
            (if (s.variadic) "..." else "") + "|" + s.results.joinToString(",") { canonKey(tt, it.t) }
    }

    /** A type key with every alias resolved (the IR's keys spell `*alias:…json.Encoder`). */
    fun canonKey(tt: TypeTable, id: Int): String = when (val t = tt.unalias(id)) {
        is com.xemantic.typescript.goport.types.PointerType -> "*" + canonKey(tt, t.elem)
        is com.xemantic.typescript.goport.types.SliceType -> "[]" + canonKey(tt, t.elem)
        is com.xemantic.typescript.goport.types.ArrayType -> "[${t.len}]" + canonKey(tt, t.elem)
        is com.xemantic.typescript.goport.types.MapType -> "map[" + canonKey(tt, t.keyType) + "]" + canonKey(tt, t.elem)
        is com.xemantic.typescript.goport.types.SignatureType ->
            "func(" + t.params.joinToString(",") { canonKey(tt, it.t) } + (if (t.variadic) "..." else "") + ")(" + t.results.joinToString(",") { canonKey(tt, it.t) } + ")"
        else -> t.key
    }

    private fun referencesObj(body: Node, id: Int): Boolean {
        var found = false
        walk(body) { n ->
            if (n.str("k") == "Ident" && n.int("obj") == id) found = true
            !found
        }
        return found
    }

    /**
     * A struct reaches `reflect` THROUGH A PARAMETER ((TSGO.4-a)): a ported function that hands its
     * parameter to `reflect.ValueOf` (`lsproto.marshalUnion(v any, …)`, `countNonNil`, `unmarshalStruct`)
     * makes the struct type of every argument passed there a reflect struct, as a direct `reflect.ValueOf(s)` does.
     */
    private fun reflectThroughParameters() {
        val reflecting = HashMap<String, MutableSet<Int>>() // function key -> parameter indexes
        for (p in packages) for (f in p.files) for (d in f.list("decls")) {
            if (d.k != "FuncDecl" || d.obj("recv") != null) continue
            val key = d.int("obj")?.let { p.obj(it).str("key") } ?: continue
            val params = ArrayList<Int?>()
            for (fld in d.obj("type")?.obj("params")?.list("list") ?: emptyList()) {
                val names = fld.list("names")
                if (names.isEmpty()) params += null else for (nm in names) params += nm.int("obj")
            }
            walk(d.obj("body")) { n ->
                if (n.str("k") == "CallExpr" && n.str("call") == "func") {
                    val fe = n.obj("fun")
                    val sel = if (fe?.str("k") == "SelectorExpr") fe.obj("sel") else fe
                    if (sel?.int("obj")?.let { p.obj(it).str("key") } == "reflect.ValueOf") {
                        val a = n.list("args").firstOrNull()
                        val id = if (a?.str("k") == "Ident") a.int("obj") else null
                        val i = if (id != null) params.indexOf(id) else -1
                        if (i >= 0) reflecting.getOrPut(key) { HashSet() } += i
                    }
                }
                true
            }
        }
        if (reflecting.isEmpty()) return
        for (p in packages) {
            val tt = TypeTable(p)
            for (f in p.files) walk(f.list("decls")) { n ->
                if (n.str("k") == "CallExpr" && n.str("call") == "func") {
                    val fe = n.obj("fun")
                    val sel = if (fe?.str("k") == "SelectorExpr") fe.obj("sel") else fe
                    val idx = sel?.int("obj")?.let { p.obj(it).str("key") }?.let { reflecting[it] }
                    if (idx != null) for (i in idx) {
                        var t = n.list("args").getOrNull(i)?.int("t")?.let { tt.unalias(it) } ?: continue
                        if (t is com.xemantic.typescript.goport.types.PointerType) t = tt.unalias(t.elem)
                        val nt = t as? com.xemantic.typescript.goport.types.NamedType ?: continue
                        val o = nt.origin?.let { tt.unalias(it) as com.xemantic.typescript.goport.types.NamedType } ?: nt
                        if (o.tparams.isEmpty() && (o.pkg == p.path || o.pkg in byPath) && tt.under(o.id) is com.xemantic.typescript.goport.types.StructType) reflectStructs += o.key
                    }
                }
                true
            }
        }
    }

    private fun index(p: IrPackage) {
        val tt = TypeTable(p)
        fun structKeyOf(id: Int): String? {
            var t = tt.unalias(id)
            if (t is com.xemantic.typescript.goport.types.PointerType) t = tt.unalias(t.elem)
            val n = t as? com.xemantic.typescript.goport.types.NamedType ?: return null
            val o = n.origin?.let { tt.unalias(it) as com.xemantic.typescript.goport.types.NamedType } ?: n
            return if (o.pkg == p.path || o.pkg in byPath) (if (tt.under(o.id) is com.xemantic.typescript.goport.types.StructType) o.key else null) else null
        }
        for (i in 0 until tt.size) {
            val n = tt[i] as? com.xemantic.typescript.goport.types.NamedType ?: continue
            if (n.pkg != p.path || n.origin != null || n.localAt != null) continue
            val st = tt.under(n.id) as? com.xemantic.typescript.goport.types.StructType ?: continue
            if (st.fields.any { "json:" in it.tag } && n.tparams.isEmpty()) reflectStructs += n.key
            // Embedded structs (json flattens them) and struct-VALUE fields (reflect.DeepEqual / IsZero walk them).
            if (n.tparams.isEmpty()) for (f in st.fields) if (f.embedded) structKeyOf(f.t)?.let { reflectParentEmbeds.getOrPut(n.key) { HashSet() } += it }
            for (f in st.fields) if (f.embedded || tt.unalias(f.t) !is com.xemantic.typescript.goport.types.PointerType) {
                structKeyOf(f.t)?.let { k -> if ((tt.unalias(f.t) as? com.xemantic.typescript.goport.types.NamedType)?.let { it.tparams.isEmpty() && it.origin == null } != false) reflectEmbeds.getOrPut(n.key) { HashSet() } += k }
            }
        }
        for (f in p.files) walk(f.list("decls")) { n ->
            if (n.str("k") == "CallExpr" && n.str("call") == "func") {
                val fe = n.obj("fun")
                val sel = if (fe?.str("k") == "SelectorExpr") fe.obj("sel") else fe
                val key = sel?.int("obj")?.let { p.obj(it).str("key") }
                when (key) {
                    "reflect.ValueOf", "reflect.TypeOf" -> n.list("args").firstOrNull()?.int("t")?.let { structKeyOf(it) }?.let { reflectStructs += it }
                    "reflect.TypeFor" -> sel.obj("inst")?.ints("targs")?.forEach { a -> structKeyOf(a)?.let { reflectStructs += it } }
                }
            }
            true
        }
        for (f in p.files) for (d in f.list("decls")) {
            if (d.k != "FuncDecl") continue
            val q = d.str("qname") ?: continue
            walk(d.obj("body")) { n ->
                if (n.str("k") == "DeclStmt" && n.obj("decl")?.str("tok") == "type") {
                    for (spec in n.obj("decl")!!.list("specs")) {
                        val goName = spec.str("name") ?: continue
                        if (spec.bool("alias")) continue
                        val t = spec.obj("nameNode")?.int("obj")?.let { p.obj(it).int("t") } ?: continue
                        val key = tt[t].key
                        // A local type over the enclosing function's type parameters cannot be hoisted.
                        var tpRef = false
                        walkTypes(tt, (tt[t] as? com.xemantic.typescript.goport.types.NamedType)?.underlying ?: t) {
                            tpRef = tpRef || it is com.xemantic.typescript.goport.types.TypeParamType
                        }
                        if (tpRef) continue
                        val encl = q.substringAfterLast('/').substringAfter('.').replace('.', '_').replace('#', '_')
                        var kname = "${goName}_$encl"
                        while (kname in localTypeNames.values) kname += "_"
                        val sq = "${p.path}.$kname"
                        localTypeQnames[key] = sq
                        localTypeNames[sq] = kname
                        localTypeSpecs.getOrPut(q) { ArrayList() } += sq to spec
                    }
                }
                true
            }
        }
        for (i in 0 until tt.size) {
            val n = tt[i] as? com.xemantic.typescript.goport.types.NamedType ?: continue
            if (n.pkg != p.path || !n.isGenericOrigin || n.localAt != null) continue
            if (tt.under(n.id) is com.xemantic.typescript.goport.types.InterfaceType) continue
            val ms = tt.msetPtr(n).ifEmpty { tt.msetT(n) }
            genericMsets[n.key] = ms.associate { m -> methodIdentity(tt, p.path, m.name, m.sig) to m.name }
        }
        for (i in 0 until tt.size) {
            val n = tt[i] as? com.xemantic.typescript.goport.types.NamedType
            if (n != null && n.pkg != null && n.pkg !in byPath && n.origin == null && n.tparams.isEmpty() && n.key !in externalIfaces &&
                shims.hasTop(Naming.kotlinPackage(n.pkg), n.name)
            ) {
                val it = tt.under(n.id) as? com.xemantic.typescript.goport.types.InterfaceType
                val ms = it?.allMethods?.ifEmpty { it.methods } ?: emptyList()
                if (it != null && it.isMethodSet && ms.isNotEmpty() && ms.all { m -> m.name[0].isUpperCase() }) {
                    externalIfaces[n.key] = IfaceInfo(n.key, n.pkg, n.name, ms.map { m -> methodIdentity(tt, n.pkg, m.name, m.sig) }.toSet(), emptySet())
                }
            }
            if (n != null && n.pkg == p.path && n.origin == null && n.tparams.isEmpty() && n.localAt == null) {
                val it = tt.under(n.id) as? com.xemantic.typescript.goport.types.InterfaceType
                if (it != null && it.isMethodSet) {
                    val ms = it.allMethods.ifEmpty { it.methods }
                    if (ms.isNotEmpty()) {
                        val ids = ms.map { m -> methodIdentity(tt, p.path, m.name, m.sig) }.toSet()
                        val embeds = it.embedded.mapNotNull { e -> (tt.unalias(e) as? com.xemantic.typescript.goport.types.NamedType)?.key }.toSet()
                        val info = IfaceInfo(n.key, p.path, n.name, ids, embeds)
                        namedIfaces += info
                        namedIfaceByKey[n.key] = info
                    }
                }
            }
            if (n != null && n.pkg == p.path && n.origin == null) {
                val u = tt.under(n.id)
                if (n.node.list("implements").isNotEmpty() && n.localAt == null &&
                    (u is com.xemantic.typescript.goport.types.SliceType || u is com.xemantic.typescript.goport.types.MapType || u is com.xemantic.typescript.goport.types.SignatureType)
                ) boxedNamed += n.key
                for (im in n.node.list("implements")) {
                    val iface = tt.under(im.int("iface")!!) as? com.xemantic.typescript.goport.types.InterfaceType ?: continue
                    ifaceMethodNames.getOrPut(n.key) { HashSet() } += iface.allMethods.ifEmpty { iface.methods }.map { it.name }
                }
            }
        }
        // A struct VALUE type argument of a generic (`slices.Contains(stack, identity)` with
        // `RecursionId`, `collections.Set[K]`): generic code compares and hashes it with Go's `==`, so it
        // gets the structural equals/hashCode too (pointer instantiations keep identity).
        fun comparableTP(tp: Int): Boolean {
            val c = (tt.unalias(tp) as? com.xemantic.typescript.goport.types.TypeParamType)?.constraint ?: return false
            val u = tt.unalias(c)
            if (u is com.xemantic.typescript.goport.types.NamedType && u.pkg == null) return u.name == "comparable"
            val i = tt.under(c) as? com.xemantic.typescript.goport.types.InterfaceType ?: return false
            return i.comparable && i.allMethods.isEmpty()
        }
        for (f in p.files) walk(f.list("decls")) { n ->
            val inst = n.obj("inst")
            val tps: List<Int> = inst?.let { _ ->
                val gt = n.int("obj")?.let { p.obj(it).int("t") }?.let { tt.unalias(it) } ?: return@let emptyList()
                when (gt) {
                    is com.xemantic.typescript.goport.types.SignatureType -> gt.tparams
                    is com.xemantic.typescript.goport.types.NamedType -> (gt.origin?.let { tt.unalias(it) as com.xemantic.typescript.goport.types.NamedType } ?: gt).tparams
                    else -> emptyList()
                }
            } ?: emptyList()
            inst?.ints("targs")?.forEachIndexed { ai, a ->
                // Only where the type parameter is `comparable` (Go's `==` on it), never e.g. an arena's element.
                if (!tps.getOrNull(ai).let { it != null && comparableTP(it) }) return@forEachIndexed
                val k = tt.unalias(a) as? com.xemantic.typescript.goport.types.NamedType ?: return@forEachIndexed
                if (tt.under(k.id) !is com.xemantic.typescript.goport.types.StructType || k.localAt != null) return@forEachIndexed
                val origin = k.origin?.let { tt.unalias(it) as com.xemantic.typescript.goport.types.NamedType } ?: k
                if (origin.pkg == p.path || origin.pkg in byPath) structKeys += origin.key
            }
            true
        }
        // A struct VALUE as a context key (`context.WithValue(ctx, clientCapabilitiesKey{}, caps)`,
        // `ctx.Value(clientCapabilitiesKey{})`, (TSGO.4-a)): the context compares keys with Go's `==` on
        // interfaces, i.e. by value — two `clientCapabilitiesKey{}` are the same key.
        for (f in p.files) walk(f.list("decls")) { n ->
            if (n.str("k") == "CallExpr") {
                val fe = n.obj("fun")
                val key = when (fe?.str("k")) {
                    "SelectorExpr" -> {
                        val sel = fe.obj("sel")
                        val objKey = sel?.int("obj")?.let { p.obj(it).str("key") }
                        if (objKey == "context.WithValue") n.list("args").getOrNull(1)
                        else if (sel?.str("name") == "Value" && fe.obj("x")?.int("t")?.let { (tt.unalias(it) as? com.xemantic.typescript.goport.types.NamedType)?.key } == "context.Context") n.list("args").firstOrNull()
                        else null
                    }
                    else -> null
                }
                val k = key?.int("t")?.let { tt.unalias(it) } as? com.xemantic.typescript.goport.types.NamedType
                if (k != null && tt.under(k.id) is com.xemantic.typescript.goport.types.StructType && k.localAt == null) {
                    val origin = k.origin?.let { tt.unalias(it) as com.xemantic.typescript.goport.types.NamedType } ?: k
                    if (origin.pkg == p.path || origin.pkg in byPath) structKeys += origin.key
                }
            }
            true
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
                    if (ptr) rf.obj("type")?.obj("x")?.int("t")?.let { t ->
                        // A pointer method of a named BASIC type (a value class): an extension on GoPtr<V>.
                        if (tt.under(t) is com.xemantic.typescript.goport.types.BasicType) {
                            valuePtrMethods += q
                            d.int("obj")?.let { p.obj(it).str("key") }?.let { valuePtrMethods += it }
                        }
                    }
                    // A pointer method that never reads its receiver: `(*Expected[T])(nil).ExpectedJSONType()`
                    // may call it on any instance (ExprLowering.methodTarget).
                    if (ptr && d.obj("body") != null && (recvObj == null || !referencesObj(d.obj("body")!!, recvObj))) {
                        recvUnusedMethods += q
                        d.int("obj")?.let { p.obj(it).str("key") }?.let { recvUnusedMethods += it }
                    }
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

    // ---- inline functions (docs/goport-lowering.md § 3, "Inline func-typed parameters") ----

    /**
     * Ported functions lowered as Kotlin `inline`, by qualified name → the indices of their
     * func-typed parameters (declared NON-null, so the inlined lambda is never a boxed `Function1`).
     * Filled by [computeInlineFuncs]; empty until then.
     */
    val inlineFuncs = HashMap<String, Set<Int>>()

    /**
     * A function is lowered `inline` when ALL of these hold (each one is a Kotlin restriction or a
     * soundness condition, not a heuristic):
     * - it has a body of at most [INLINE_MAX_LINES] Go lines (the body is copied into every caller,
     *   which the 8,000-bytecode JIT limit counts — `huge_methods.py` is the gate);
     * - it is a top-level function or a method lowered as an EXTENSION (an interface member cannot
     *   be `inline`), not `init`, not overridden by hand ([exclude]);
     * - it has at least one func-typed parameter, and every such parameter is only ever CALLED
     *   (never compared to nil, stored, returned, reassigned, address-taken or passed on);
     * - its body has no function literal (a closure capturing an inline parameter is illegal, and
     *   Kotlin refuses local functions in an inline body), no `defer` (lowered as a lambda), no
     *   `go`, and no range over a function iterator (also a lambda);
     * - it does not call itself (an inline function cannot be recursive);
     * - no call site anywhere in the run passes `nil` for a func-typed parameter (the parameter is
     *   non-null; Go panics only when it calls nil, which these bodies always do).
     * A body that hoists private helpers is lowered without `inline` at emit time (a public inline
     * function cannot reference a private one) but keeps its non-null parameters.
     */
    fun computeInlineFuncs(exclude: Set<String>) {
        inlineFuncs.clear()
        for (p in packages) {
            val tt = TypeTable(p)
            for (f in p.files) for (d in f.list("decls")) {
                if (d.k != "FuncDecl") continue
                val q = d.str("qname") ?: continue
                val body = d.obj("body") ?: continue
                if (d.str("name") == "init" || q in exclude) continue
                if ((d.int("lines") ?: Int.MAX_VALUE) > INLINE_MAX_LINES) continue
                if (d.obj("recv") != null && q !in extensionMethods) continue
                val self = d.int("obj")
                val fnParams = HashMap<Int, Int>() // obj → index
                var i = 0
                var ok = true
                for (fld in d.obj("type")?.obj("params")?.list("list") ?: emptyList()) {
                    val names = fld.list("names")
                    val tid = fld.obj("type")?.int("t")
                    val isFn = tid != null && tt.under(tid) is com.xemantic.typescript.goport.types.SignatureType
                    if (names.isEmpty()) { if (isFn) ok = false; i++; continue }
                    for (n in names) {
                        if (isFn) {
                            val id = n.int("obj")
                            if (id == null || n.str("name") == "_" || p.obj(id).bool("mut") || p.obj(id).bool("addr")) ok = false
                            else fnParams[id] = i
                        }
                        i++
                    }
                }
                if (!ok || fnParams.isEmpty()) continue
                val stack = ArrayList<Pair<Node, String>>()
                fun scan(el: Any?, key: String) {
                    if (!ok) return
                    when (el) {
                        is kotlinx.serialization.json.JsonObject -> {
                            when (el.str("k")) {
                                "FuncLit", "DeferStmt", "GoStmt" -> { ok = false; return }
                                "RangeStmt" -> el.obj("x")?.int("t")?.let { if (tt.under(it) is com.xemantic.typescript.goport.types.SignatureType) { ok = false; return } }
                                "Ident" -> {
                                    val id = el.int("obj")
                                    if (id != null && id in fnParams && !el.bool("def")) {
                                        val (par, pk) = stack.lastOrNull() ?: (el to "")
                                        if (!(par.str("k") == "CallExpr" && pk == "fun" && par.str("call") == "dynamic")) { ok = false; return }
                                    }
                                    if (self != null && id == self) { ok = false; return }
                                }
                            }
                            for ((k2, v) in el) {
                                stack += el to k2
                                scan(v, k2)
                                stack.removeAt(stack.size - 1)
                            }
                        }
                        is List<*> -> for (v in el) scan(v, key)
                        else -> {}
                    }
                }
                scan(body, "")
                if (ok) inlineFuncs[q] = fnParams.values.toSet()
            }
        }
        if (inlineFuncs.isEmpty()) return
        // No call site may pass nil for an inline parameter.
        val dropped = HashSet<String>()
        for (p in packages) for (f in p.files) walk(f) { n ->
            if (n.str("k") == "CallExpr") {
                val fe = n.obj("fun")
                val ident = when (fe?.str("k")) {
                    "Ident" -> fe
                    "SelectorExpr" -> fe.obj("sel")
                    "IndexExpr", "IndexListExpr", "ParenExpr" -> fe.obj("x")?.let { if (it.str("k") == "SelectorExpr") it.obj("sel") else it }
                    else -> null
                }
                val key = ident?.int("obj")?.let { p.obj(it).str("key") }
                val idx = key?.let { inlineFuncs[it] }
                if (idx != null) {
                    val shift = if (n.str("call") == "methodexpr") 1 else 0
                    val args = n.list("args")
                    if (n.bool("tupleArg") || idx.any { args.getOrNull(it + shift)?.str("m") == "nil" }) dropped += key
                }
            }
            true
        }
        inlineFuncs.keys.removeAll(dropped)
    }

    // ---- window parameters (docs/goport-lowering.md § 3, "Window parameters") ----

    /**
     * Ported functions that get a WINDOW overload `<name>Win`, by qualified name → the index of
     * the string parameter it takes as `(base, offset, length)`. Filled by [computeWindowFuncs].
     */
    val windowFuncs = HashMap<String, Int>()

    /** The Kotlin name of [qname]'s window overload. */
    fun windowName(qname: String, goName: String): String = funName(qname, goName).trim('`') + "Win"

    /**
     * A top-level, non-generic, non-variadic function gets a window overload for its string
     * parameter `p` when `p` is used ONLY as a view (`len(p)`, `p[k]`, a sub-slice feeding a fused
     * `strings` call or `==` — [CallLowering.viewUseViolations]), is never reassigned or
     * address-taken, it is the ONLY such string parameter, and some call site in the run passes a
     * string slice for it (`isJSDocLikeText(p.sourceText[start:])`: a suffix copy of the source per
     * JSDoc comment). Such a call then passes the window and copies nothing.
     */
    fun computeWindowFuncs(exclude: Set<String>) {
        windowFuncs.clear()
        val cand = HashMap<String, Int>()
        for (p in packages) {
            val tt = TypeTable(p)
            for (f in p.files) for (d in f.list("decls")) {
                if (d.k != "FuncDecl" || d.obj("recv") != null) continue
                val q = d.str("qname") ?: continue
                val body = d.obj("body") ?: continue
                if (d.str("name") == "init" || q in exclude || q in inlineFuncs) continue
                val sig = d.int("obj")?.let { p.obj(it).int("t") }?.let { tt.unalias(it) } as? com.xemantic.typescript.goport.types.SignatureType ?: continue
                if (sig.tparams.isNotEmpty() || sig.variadic) continue
                val strParams = HashMap<Int, Int>()
                var i = 0
                for (fld in d.obj("type")?.obj("params")?.list("list") ?: emptyList()) {
                    val names = fld.list("names")
                    val tid = fld.obj("type")?.int("t")
                    val isStr = tid != null && (tt.under(tid) as? com.xemantic.typescript.goport.types.BasicType)?.name == "string"
                    if (names.isEmpty()) { i++; continue }
                    for (n in names) {
                        val id = n.int("obj")
                        if (isStr && id != null && n.str("name") != "_" && !p.obj(id).bool("mut") && !p.obj(id).bool("addr")) strParams[id] = i
                        i++
                    }
                }
                if (strParams.isEmpty()) continue
                val bad = CallLowering.viewUseViolations(body, strParams.keys, emptySet()) { call ->
                    val fe = call.obj("fun")
                    val ident = if (fe?.str("k") == "SelectorExpr") fe.obj("sel") else fe
                    ident?.int("obj")?.let { p.obj(it).str("key") }
                }
                val ok = strParams.keys - bad
                if (ok.size != 1) continue
                val name = windowName(q, d.str("name")!!)
                if (name in (topValueNames[p.path] ?: emptySet<String>())) continue
                cand[q] = strParams[ok.first()]!!
            }
        }
        if (cand.isEmpty()) return
        // Only where some call passes a string slice for the parameter.
        for (p in packages) for (f in p.files) walk(f) { n ->
            if (n.str("k") == "CallExpr" && n.str("call") == "func") {
                val fe = n.obj("fun")
                val ident = if (fe?.str("k") == "SelectorExpr") fe.obj("sel") else fe
                val key = ident?.int("obj")?.let { p.obj(it).str("key") }
                val idx = key?.let { cand[it] }
                val a = idx?.let { n.list("args").getOrNull(it) }
                if (a != null && a.str("k") == "SliceExpr" && a.str("sk") == "string" && !a.bool("slice3") &&
                    (a.obj("low") != null || a.obj("high") != null) && !n.bool("tupleArg")
                ) windowFuncs[key] = idx
            }
            true
        }
    }

    // ---- names of package-level declarations (keyed by the Go qualified name / object key) ----

    fun funName(qname: String, goName: String): String {
        renames.lookup(qname)?.let { return it }
        if (goName == "init") return "init" + (qname.substringAfter('#', "").toIntOrNull()?.let { "_${it - 1}" } ?: "")
        if (qname.substringBeforeLast('.') + "\u0000" + goName in autoFunRenames) return goName + "Impl"
        return Naming.escape(Naming.lowerCamel(goName))
    }
    fun varName(qname: String, goName: String): String = renames.lookup(qname) ?: Naming.escape(Naming.lowerCamel(goName))
    fun constName(qname: String, goName: String): String = renames.lookup(qname) ?: Naming.escape(goName)
    fun typeName(qname: String, goName: String): String = renames.lookup(qname) ?: localTypeNames[qname] ?: Naming.escape(goName)

    fun methodName(key: String, goName: String): String {
        renames.lookup(key)?.let { return it }
        if (key.substringBeforeLast('.').substringBeforeLast('.') + "\u0000" + goName in autoMethodRenames) return goName + "Impl"
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
    /** Whether a generated class delegates a member to a nil-safe extension (`goNullable`, see Decls). */
    var needNullable = false

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
