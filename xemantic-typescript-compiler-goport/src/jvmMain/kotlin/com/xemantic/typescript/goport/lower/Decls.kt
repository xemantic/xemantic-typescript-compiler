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

import com.xemantic.typescript.goport.emit.CodeWriter
import com.xemantic.typescript.goport.ir.Node
import com.xemantic.typescript.goport.ir.bool
import com.xemantic.typescript.goport.ir.int
import com.xemantic.typescript.goport.ir.ints
import com.xemantic.typescript.goport.ir.k
import com.xemantic.typescript.goport.ir.list
import com.xemantic.typescript.goport.ir.obj
import com.xemantic.typescript.goport.ir.reqInt
import com.xemantic.typescript.goport.ir.reqObj
import com.xemantic.typescript.goport.ir.str
import com.xemantic.typescript.goport.naming.Naming
import com.xemantic.typescript.goport.report.Report
import com.xemantic.typescript.goport.types.ArrayType
import com.xemantic.typescript.goport.types.BasicType
import com.xemantic.typescript.goport.types.InterfaceType
import com.xemantic.typescript.goport.types.NamedType
import com.xemantic.typescript.goport.types.PointerType
import com.xemantic.typescript.goport.types.SignatureType
import com.xemantic.typescript.goport.types.StructType
import com.xemantic.typescript.goport.types.TypeParamType

/** A hand port spliced in place of a lowered declaration (`-goport/overrides/<qname>.kt`). */
class Override(val qname: String, val hash8: String, val text: String, val file: String) {
    var used = false
}

/** One rendered top-level item of a Go file, with its accounting. */
class Item(val goLines: Int, val text: String, val isVar: Boolean, val initOrder: Int = -1)

/**
 * Lowers one Go package into Kotlin files: one per Go file (split when large), classes carrying
 * every method of their type, top-level functions, variables and constants.
 */
class PackageEmitter(
    val pc: PkgCtx,
    val overrides: Map<String, Override>,
    val report: Report,
    /** Pinned refusals (`-goport/refuse.txt`): qname → reason. */
    val pinned: Map<String, String> = emptyMap(),
) {
    private val prog get() = pc.prog
    private val types get() = pc.types

    /** Types whose declaration the lowering refuses: their methods are stubbed too. */
    private val refusedTypes: Set<String> by lazy {
        val out = HashSet<String>()
        val scratch = this
        for (f in pc.pkg.files) for (d in f.list("decls")) if (d.k == "GenDecl" && d.str("tok") == "type") {
            for (s in d.list("specs")) {
                val q = s.str("qname") ?: continue
                if (s.bool("alias")) continue
                if (scratch.typeSpecRefused(s)) out += q
            }
        }
        out
    }

    /** Whether the lowering refuses a type declaration itself (not one of its methods). */
    fun typeSpecRefused(s: Node): Boolean {
        val r = Report()
        PackageEmitter(pc, emptyMap(), r, emptyMap()).typeSpec(s, FileCtx(pc), probe = true)
        return s.str("qname") in r.refusedSet
    }

    private val initIndex: Map<Int, Int> = HashMap<Int, Int>().also { m ->
        pc.pkg.initOrder.forEachIndexed { i, e -> for (id in e.ints("lhs")) m[id] = i }
    }

    private var probeMode = false

    fun hash8(d: Node): String = (d.str("hash") ?: "").take(8)

    private fun traceLine(qname: String, hash: String?) = "// go: $qname ${(hash ?: "").take(8)}"

    /** The rendered items of one Go file. */
    fun lowerFile(file: Node, fc: FileCtx): List<Item> {
        val items = ArrayList<Item>()
        for (d in file.list("decls")) {
            when (d.k) {
                "FuncDecl" -> {
                    if (d.str("name") == "_") {
                        // `func _()` is a compile-time assertion; nothing to run.
                        report.lowered(pc.pkg, d.str("qname")!!, d.reqInt("lines"))
                        continue
                    }
                    if (d.obj("recv") != null) {
                        // A method lives in its type's class — unless nil-safe (an extension on T?).
                        val q = d.str("qname")!!
                        if (q in prog.extensionMethods) items += Item(d.reqInt("lines"), funcDecl(d, fc, extension = true), false)
                        continue
                    }
                    items += Item(d.reqInt("lines"), funcDecl(d, fc, extension = false), false)
                }
                "GenDecl" -> when (d.str("tok")) {
                    "import" -> {}
                    "type" -> for (s in d.list("specs")) {
                        if (s.bool("alias")) {
                            report.lowered(pc.pkg, s.str("qname") ?: "?", lines(s, d))
                            continue
                        }
                        items += Item(lines(s, d) + methodLines(s), typeSpec(s, fc), false)
                    }
                    "const" -> for (s in d.list("specs")) items += Item(lines(s, d), constSpec(s, fc), false)
                    "var" -> for (s in d.list("specs")) {
                        val order = s.list("names").mapNotNull { n -> n.int("obj")?.let { initIndex[it] } }.minOrNull() ?: -1
                        items += Item(lines(s, d), varSpec(s, fc), true, order)
                    }
                }
            }
        }
        // Kotlin initializes a file's properties in textual order and rejects a forward
        // reference; Go initializes package variables in dependency order (the IR's initOrder).
        return items.filter { !it.isVar } + items.filter { it.isVar }.sortedBy { it.initOrder }
    }

    private fun lines(s: Node, d: Node): Int {
        val specs = d.list("specs").size
        return if (specs <= 1) d.reqInt("lines") else maxOf(1, d.reqInt("lines") / specs)
    }

    private fun methodLines(s: Node): Int {
        val key = s.str("qname") ?: return 0
        return pc.methodsOf(key).filter { it.str("qname") !in prog.extensionMethods }.sumOf { it.reqInt("lines") }
    }

    // ------------------------------------------------------------------ functions and methods

    /** A top-level function, or a nil-safe method as an extension on its nullable receiver. */
    fun funcDecl(d: Node, fc: FileCtx, extension: Boolean, member: ClassScope? = null): String {
        val qname = d.str("qname")!!
        val o = pc.obj(d.reqInt("obj"))
        val sig = types.unalias(o.reqInt("t")) as SignatureType
        overrides[qname]?.let { ov -> return spliceOverride(ov, d) }
        val tpNames = HashMap<Int, String>()
        var recvTypeText = ""
        var classTparams = ""
        val tpElems = HashMap<Int, String>()
        val recvField = d.obj("recv")?.list("list")?.firstOrNull()
        if (recvField != null) {
            val named = recvNamed(recvField)
            val origin = named.origin?.let { types.unalias(it) as NamedType } ?: named
            // A generic receiver (`func (s *Set[T])`) declares its own type parameters: the
            // receiver type's type arguments. They are the class's parameters in Kotlin.
            val recvTparams = if (named.origin != null) named.targs else emptyList()
            recvTparams.forEachIndexed { i, id ->
                val classTp = types.unalias(origin.tparams[i]) as TypeParamType
                tpNames[id] = member?.tpNames?.get(origin.tparams[i]) ?: Naming.escape(classTp.name)
                if (prog.structKind(origin.key)) tpElems[id] = (if (extension) "this!!." else "") + "goElem_" + classTp.name
            }
            if (extension) {
                val tm0 = TypeMapper(fc, tpNames)
                classTparams = tm0.typeParamDecl(recvTparams)
                val kept = recvTparams.filter { !tm0.isSubstituted(it) }
                recvTypeText = tm0.namedRef(origin) + (if (kept.isEmpty()) "" else kept.joinToString(", ", "<", ">") { tm0.tpName(types.unalias(it) as TypeParamType) }) + "?."
            }
        }
        // A generic function takes the element kind of each type parameter (Go zeroes a `T`;
        // Kotlin erases it): `getSpellingSuggestion[string]` must answer "" for "no candidate", not null.
        val funDict = ArrayList<Int>()
        if (recvField == null) {
            val tmp = TypeMapper(fc)
            for (id in sig.tparams) if (!tmp.isSubstituted(id)) {
                funDict += id
                tpElems[id] = "goElem_" + (types.unalias(id) as TypeParamType).name
            }
        }
        val tm = TypeMapper(fc, tpNames + (member?.tpNames ?: emptyMap()), tpElems + (member?.tpElems ?: emptyMap()))
        val fn = FnCtx(fc, qname, tm)
        member?.let { fn.classMembers = it.memberNames }
        val low = Lowering(fn)
        val goName = d.str("name")!!
        val name = if (recvField != null) prog.methodName(qname, goName) else prog.funName(qname, goName)
        val out = CodeWriter()
        fn.w = out
        val lines = d.reqInt("lines")
        // Signature first: a refusal here leaves no stub (callers then fail visibly).
        val ft = d.reqObj("type")
        val params = ft.obj("params")?.list("list") ?: emptyList()
        val sigText: String
        try {
            if (recvField != null) recvField.list("names").firstOrNull()?.int("obj")?.let { r ->
                // A receiver the body assigns (`k -= 99` on a value receiver) is a local copy.
                if (!pc.obj(r).bool("mut")) fn.recvObj = r
            }
            fn.recvNullable = extension
            val decl = funDict.map { "goElem_${(types.unalias(it) as TypeParamType).name}: GoElem<${tm.kt(it)}>" } + low.paramDecls(params, sig)
            val tparams = if (recvField == null) tm.typeParamDecl(sig.tparams) else classTparams
            val result = tm.returns(sig.results.map { it.t })
            val ov = member?.overrides?.contains(name) == true
            val jvmName = if (member != null && name in OBJECT_MEMBERS && !ov) "@kotlin.jvm.JvmName(\"go${name.replaceFirstChar { it.uppercase() }}\")\n" else ""
            sigText = "$jvmName${if (ov) "override " else ""}fun ${if (tparams.isEmpty()) "" else "$tparams "}$recvTypeText$name(${decl.joinToString(", ")})$result"
        } catch (r: Refusal) {
            report.refused(pc.pkg, qname, lines, r, stub = false)
            return "${traceLine(qname, d.str("hash"))}\n// goport: refused ${r.reason}: $qname (no stub: signature)\n"
        }
        if (recvField != null && !probeMode) {
            val rn = recvNamed(recvField)
            val ro = rn.origin?.let { types.unalias(it) as NamedType } ?: rn
            if (ro.key in refusedTypes) {
                report.refused(pc.pkg, qname, lines, Refusal("receiver-type-refused"), stub = true)
                return "${traceLine(qname, d.str("hash"))}\n$sigText {\n    TODO(\"goport: refused receiver-type-refused: $qname\")\n}\n"
            }
        }
        pinned[qname]?.let { reason ->
            report.refused(pc.pkg, qname, lines, Refusal("pinned:$reason"), stub = true)
            return "${traceLine(qname, d.str("hash"))}\n$sigText {\n    TODO(\"goport: refused pinned:$reason: $qname\")\n}\n"
        }
        val body = d.obj("body") ?: return "${traceLine(qname, d.str("hash"))}\n$sigText {\n    TODO(\"goport: external body: $qname\")\n}\n"
        val bodyWriter = CodeWriter(1)
        fn.w = bodyWriter
        return try {
            fn.frames.addLast(Frame(sig.results.map { it.t }, low.namedResultObjs(ft)))
            recvField?.list("names")?.firstOrNull()?.int("obj")?.let { r ->
                if (fn.recvObj != r && pc.obj(r).str("name") != "_") low.declareLocal(r, if (extension) "this" else "this")
            }
            low.copyInParams(params)
            low.declareNamedResults()
            low.withDefersIfNeeded(body, sig.results.map { it.t }) { low.body(body.list("list")) }
            report.lowered(pc.pkg, qname, lines)
            "${traceLine(qname, d.str("hash"))}\n$sigText {\n$bodyWriter}\n" + fn.helpers.joinToString("") { "\n$it" }
        } catch (r: Refusal) {
            report.refused(pc.pkg, qname, lines, r, stub = true)
            "${traceLine(qname, d.str("hash"))}\n$sigText {\n    TODO(\"goport: refused ${r.reason}: $qname\")\n}\n"
        }
    }

    private fun recvNamed(recvField: Node): NamedType {
        var tId = recvField.reqObj("type").int("t")!!
        val p = types.unalias(tId)
        if (p is PointerType) tId = p.elem
        return types.unalias(tId) as? NamedType ?: refuse("receiver-not-named")
    }

    private fun spliceOverride(ov: Override, d: Node): String {
        ov.used = true
        val qname = d.str("qname")!!
        if (ov.hash8 != hash8(d)) report.stale(qname, ov.hash8, hash8(d), ov.file)
        report.overridden(pc.pkg, qname, d.reqInt("lines"))
        return "${traceLine(qname, d.str("hash"))}\n${ov.text.trimEnd()}\n"
    }

    // ------------------------------------------------------------------ types

    /** The class-level context of a struct/value class being rendered. */
    class ClassScope(
        val tpNames: Map<Int, String>,
        val memberNames: Set<String>,
        val overrides: Set<String>,
        val tpElems: Map<Int, String> = emptyMap(),
    )

    fun typeSpec(s: Node, fc: FileCtx, probe: Boolean = false): String {
        val qname = s.str("qname") ?: return "" // local type specs never reach here
        overrides[qname]?.let { ov ->
            ov.used = true
            report.overridden(pc.pkg, qname, s.int("lines") ?: 1)
            return "${traceLine(qname, s.str("hash"))}\n${ov.text.trimEnd()}\n"
        }
        val o = pc.obj(s.reqObj("nameNode").reqInt("obj"))
        val named = types.unalias(o.reqInt("t")) as NamedType
        val tm = TypeMapper(fc)
        probeMode = probe
        return try {
            val text = when (tm.namedKind(named)) {
                TypeMapper.NamedKind.STRUCT -> structClass(s, named, fc)
                TypeMapper.NamedKind.IFACE -> ifaceDecl(s, named, fc)
                TypeMapper.NamedKind.CONSTRAINT -> "${traceLine(qname, s.str("hash"))}\n// constraint-only interface ${named.name}: erased to its bound at each use\n"
                TypeMapper.NamedKind.VALUE -> valueClass(s, named, fc)
                TypeMapper.NamedKind.ALIAS -> {
                    val tpNames = tparamNames(named)
                    val tma = TypeMapper(fc, tpNames)
                    val target = if (qname in prog.structAliases) s.reqObj("type").int("t")!! else named.underlying
                    "${traceLine(qname, s.str("hash"))}\ntypealias ${prog.typeName(qname, named.name)}${tma.typeParamDecl(named.tparams).replace(Regex(" : [^,>]+"), "")} = ${tma.kt(target)}\n"
                }
                TypeMapper.NamedKind.ERROR -> ""
            }
            report.lowered(pc.pkg, qname, s.int("lines") ?: 1)
            text
        } catch (r: Refusal) {
            // A stub class keeps every signature that names the type compiling; each use is a stub too.
            report.refused(pc.pkg, qname, s.int("lines") ?: 1, r, stub = true)
            val tps = if (named.tparams.isEmpty()) "" else named.tparams.joinToString(", ", "<", ">") { (types.unalias(it) as TypeParamType).name }
            val n = prog.typeName(qname, named.name)
            "${traceLine(qname, s.str("hash"))}\n// goport: refused ${r.reason}: $qname (type)\nclass $n$tps {\n    fun goCopy(): $n$tps = TODO(\"goport: refused ${r.reason}: $qname\")\n}\n"
        }
    }

    private fun tparamNames(named: NamedType): Map<Int, String> =
        named.tparams.associateWith { Naming.escape((types.unalias(it) as TypeParamType).name) }

    /** Supertypes from the IR's `implements` list (structural satisfaction made nominal). */
    private fun supertypes(named: NamedType, tm: TypeMapper, methods: Set<String>): Pair<List<String>, Set<String>> {
        val out = LinkedHashSet<String>()
        val overrideNames = HashSet<String>()
        if (named.isGenericOrigin) return out.toList() to overrideNames
        for (im in named.node.list("implements")) {
            val ifId = im.reqInt("iface")
            val it = types.unalias(ifId)
            try {
                val (ref, iface) = when {
                    it is NamedType && it.pkg == null && it.name == "error" -> "GoError" to (types.under(ifId) as InterfaceType)
                    it is NamedType -> {
                        if (it.pkg !in prog.ported) {
                            val kp = Naming.kotlinPackage(it.pkg!!)
                            if (!prog.shims.hasTop(kp, it.name)) continue
                        }
                        if (it.tparams.isNotEmpty() || it.targs.isNotEmpty()) continue
                        tm.namedRef(it) to (types.under(ifId) as InterfaceType)
                    }
                    it is InterfaceType -> tm.synthIface(it) to it
                    else -> continue
                }
                val mnames = (iface.allMethods.ifEmpty { iface.methods }).map { m -> prog.methodName("", m.name) }
                // A method the type lacks (unexported interface method of another package) cannot be implemented.
                if (!mnames.all { m -> m in methods }) continue
                out += ref
                overrideNames += mnames
            } catch (_: Refusal) {
                continue
            }
        }
        if ("unwrap" in methods && prog.shims.hasTop(Naming.RUNTIME, "GoUnwrapper")) {
            out += "GoUnwrapper"
            overrideNames += "unwrap"
        }
        return out.toList() to overrideNames
    }

    /** Kotlin member names of every method in [named]'s pointer method set (declared or promoted). */
    private fun methodSetNames(named: NamedType): Map<String, com.xemantic.typescript.goport.types.MethodSetEntry> =
        (types.msetPtr(named).ifEmpty { types.msetT(named) }).associateBy { prog.methodName(it.fn, it.name) }

    private fun structClass(s: Node, named: NamedType, fc: FileCtx): String {
        val qname = s.str("qname")!!
        val tpNames = tparamNames(named)
        val tm0 = TypeMapper(fc, tpNames)
        // Generic struct classes carry a GoElem per type parameter: Go zeroes a `T` slot (a grown
        // slice of T, `var zero T`) and Kotlin erases T, so the element kind is passed in.
        val dict = named.tparams.filter { !tm0.isSubstituted(it) }.map { (types.unalias(it) as TypeParamType).name }
        val tpElems = named.tparams.filter { !tm0.isSubstituted(it) }.associateWith { "goElem_" + (types.unalias(it) as TypeParamType).name }
        val tm = TypeMapper(fc, tpNames, tpElems)
        val st = types.under(named.id) as StructType
        val name = prog.typeName(qname, named.name)
        val tpDecl = tm.typeParamDecl(named.tparams)
        val tpUse = if (named.tparams.isEmpty()) "" else named.tparams.joinToString(", ", "<", ">") { tpNames.getValue(it) }
        val self = "$name$tpUse"
        val fieldNames = st.fields.mapIndexed { i, f -> prog.fieldName("$qname.${f.name}", f.name, i) }
        checkCollisions(qname, fieldNames, "field")
        val declared = pc.methodsOf(qname).filter { it.str("qname") !in prog.extensionMethods }
        val mset = methodSetNames(named)
        val declaredNames = declared.map { prog.methodName(it.str("qname")!!, it.str("name")!!) }
        checkCollisions(qname, pc.methodsOf(qname).map { prog.methodName(it.str("qname")!!, it.str("name")!!) }, "method")
        val (supers, overrideNames) = supertypes(named, tm, mset.keys)
        val w = CodeWriter()
        w.line(traceLine(qname, s.str("hash")))
        w.line("class $name$tpDecl(")
        w.indent {
            for (tp in dict) w.line("@kotlin.jvm.JvmField val goElem_$tp: GoElem<${Naming.escape(tp)}>,")
            st.fields.forEachIndexed { i, f ->
                // @JvmField: no accessors (a Go `SetText` method would clash with `text`'s setter), and direct field access.
                w.line("${jvmField(fieldNames[i], f.t, tm)}var ${fieldNames[i]}: ${tm.kt(f.t)} = ${tm.zero(f.t)},")
            }
        }
        w.line(")${if (supers.isEmpty()) "" else " : " + supers.joinToString(", ")} {")
        w.indent {
            w.line()
            val copyArgs = dict.map { "goElem_$it = goElem_$it" } + st.fields.indices.map { "${fieldNames[it]} = ${copyField(fieldNames[it], st.fields[it].t, tm)}" }
            w.line("fun goCopy(): $self = $name(${copyArgs.joinToString(", ")})")
            w.line()
            w.block("fun goSet(o: $self)") {
                st.fields.forEachIndexed { i, f -> w.line("${fieldNames[i]} = ${copyField("o." + fieldNames[i], f.t, tm)}") }
            }
            if (named.comparable) {
                w.line()
                val eqs = st.fields.mapIndexed { i, f -> fieldEq(fieldNames[i], f.t, tm) }
                w.line("fun goEquals(o: $self): Boolean = ${if (eqs.isEmpty()) "true" else eqs.joinToString(" && ")}")
                w.line()
                val hs = st.fields.mapIndexed { i, f -> fieldHash(fieldNames[i], f.t, tm) }
                w.line("fun goHash(): Int = ${if (hs.isEmpty()) "0" else hs.joinToString(" + ") { "31 * $it" }}")
                if (qname in prog.structKeys) {
                    // A map keyed by this struct VALUE: Go compares keys structurally.
                    val star = if (named.tparams.isEmpty()) "" else named.tparams.joinToString(", ", "<", ">") { "*" }
                    w.line()
                    w.line("override fun equals(other: Any?): Boolean = other is $name$star && goEquals(other as $self)")
                    w.line()
                    w.line("override fun hashCode(): Int = goHash()")
                }
            }
            val scope = ClassScope(tpNames, (fieldNames + mset.keys).toSet(), overrideNames, tpElems)
            for (m in declared) {
                w.line()
                w.raw(funcDecl(m, fc, extension = false, member = scope))
            }
            // Promoted methods an implemented interface requires: delegate through the embedding path.
            for (mn in overrideNames.sorted()) {
                if (mn in declaredNames) continue
                val e = mset[mn] ?: continue
                if (e.path.size < 2) continue
                w.line()
                w.raw(promotedDelegate(named, e, mn, tm))
            }
            w.line()
            w.block("companion object") {
                if (named.tparams.isEmpty()) w.line("val ELEM: GoElem<$self> = GoElem({ $self() }, { it.goCopy() })")
                else {
                    val ps = dict.joinToString(", ") { "goElem_$it: GoElem<${Naming.escape(it)}>" }
                    val pa = dict.joinToString(", ") { "goElem_$it = goElem_$it" }
                    w.line("fun $tpDecl elem($ps): GoElem<$self> = GoElem({ $self($pa) }, { it.goCopy() })")
                }
            }
        }
        w.line("}")
        return w.toString()
    }

    /**
     * Fields and package variables carry no JVM accessors named after them: a Go `SetText`
     * method would otherwise clash with `text`'s setter. `@JvmField` where Kotlin allows it (also
     * direct field access); a value-class-typed one gets renamed accessors instead.
     */
    private fun jvmField(name: String, t: Int, tm: TypeMapper, mutable: Boolean = true): String {
        val n = name.trim('`')
        val rep = tm.repOf(t)
        val valueClass = tm.isValueClass(t) || rep == TypeMapper.Rep.UINT || rep == TypeMapper.Rep.ULONG
        if (!valueClass) return "@kotlin.jvm.JvmField "
        return "@get:kotlin.jvm.JvmName(\"goGet_$n\") " + if (mutable) "@set:kotlin.jvm.JvmName(\"goSet_$n\") " else ""
    }

    private fun copyField(code: String, t: Int, tm: TypeMapper): String = when {
        tm.isStructValue(t) && !tm.isEmptyStruct(t) && tm.hasGoCopy(t) -> "$code.goCopy()"
        types.under(t) is ArrayType -> "$code.goCopy()"
        else -> code
    }

    private fun generatedStruct(t: Int, tm: TypeMapper): Boolean {
        val n = types.unalias(t) as? NamedType ?: return false
        return tm.isPortedNamed(n) && tm.namedKind(n) == TypeMapper.NamedKind.STRUCT
    }

    private fun fieldHash(n: String, t: Int, tm: TypeMapper): String = when {
        tm.isStructValue(t) && !tm.isEmptyStruct(t) && generatedStruct(t, tm) -> "$n.goHash()"
        types.under(t) is ArrayType -> "$n.goHash()"
        tm.nullable(t) -> "$n.hashCode()"
        else -> "$n.hashCode()"
    }

    private fun fieldEq(n: String, t: Int, tm: TypeMapper): String = when {
        tm.isStructValue(t) && !tm.isEmptyStruct(t) && generatedStruct(t, tm) -> "$n.goEquals(o.$n)"
        types.under(t) is ArrayType -> "$n.goEquals(o.$n)"
        types.under(t) is PointerType && types.under((types.under(t) as PointerType).elem) is StructType -> "$n === o.$n"
        else -> "$n == o.$n"
    }

    private fun promotedDelegate(named: NamedType, e: com.xemantic.typescript.goport.types.MethodSetEntry, name: String, tm: TypeMapper): String {
        val sig = types.unalias(e.sig) as SignatureType
        val ps = sig.params.indices.map { "p$it" }
        val decl = sig.params.mapIndexed { i, p -> "${ps[i]}: ${tm.kt(p.t)}" }.joinToString(", ")
        var code = "this"
        var cur: Int = named.id
        for (idx in e.path.dropLast(1)) {
            var target = cur
            val p = types.under(cur)
            if (p is PointerType) {
                if (code != "this") code += "!!"
                target = p.elem
            }
            val st = types.under(target) as StructType
            val f = st.fields[idx]
            val owner = types.unalias(target) as NamedType
            val origin = owner.origin?.let { types.unalias(it) as NamedType } ?: owner
            code += "." + prog.fieldName(origin.key + "." + f.name, f.name, idx)
            cur = f.t
        }
        if (types.under(cur) is PointerType || types.under(cur) is InterfaceType) {
            if (e.fn !in prog.extensionMethods) code += "!!"
        }
        return "override fun $name($decl)${tm.returns(sig.results.map { it.t })} = $code.$name(${ps.joinToString(", ")})\n"
    }

    private fun checkCollisions(owner: String, names: List<String>, what: String) {
        val seen = HashSet<String>()
        for (n in names) if (!seen.add(n)) report.collision("$owner: two ${what}s map to Kotlin name '$n' — add a rename to renames.txt")
    }

    private fun ifaceDecl(s: Node, named: NamedType, fc: FileCtx): String {
        val qname = s.str("qname")!!
        val tpNames = tparamNames(named)
        val tm = TypeMapper(fc, tpNames)
        val it = types.under(named.id) as InterfaceType
        val name = prog.typeName(qname, named.name)
        val supers = it.embedded.mapNotNull { e ->
            val et = types.unalias(e)
            when {
                et is NamedType && et.pkg == null && et.name == "error" -> "GoError"
                et is NamedType -> tm.kt(e).removeSuffix("?")
                else -> refuse("iface-embeds", types[e].key)
            }
        }
        val w = CodeWriter()
        w.line(traceLine(qname, s.str("hash")))
        w.line("interface $name${tm.typeParamDecl(named.tparams)}${if (supers.isEmpty()) "" else " : " + supers.joinToString(", ")} {")
        w.indent {
            for (m in it.methods) {
                val sig = types.unalias(m.sig) as SignatureType
                val ps = sig.params.mapIndexed { i, p -> "p$i: ${tm.kt(p.t)}" }
                w.line("fun ${prog.methodName("$qname.${m.name}", m.name)}(${ps.joinToString(", ")})${tm.returns(sig.results.map { r -> r.t })}")
            }
        }
        w.line("}")
        return w.toString()
    }

    private fun valueClass(s: Node, named: NamedType, fc: FileCtx): String {
        val qname = s.str("qname")!!
        val tpNames = tparamNames(named)
        val tm = TypeMapper(fc, tpNames)
        val name = prog.typeName(qname, named.name)
        val tpUse = if (named.tparams.isEmpty()) "" else named.tparams.joinToString(", ", "<", ">") { tpNames.getValue(it) }
        val self = "$name$tpUse"
        val u = named.underlying
        val declared = pc.methodsOf(qname).filter { it.str("qname") !in prog.extensionMethods }
        val mset = methodSetNames(named)
        val (supers0, overrideNames) = supertypes(named, tm, mset.keys)
        val ordered = (types.under(u) as? BasicType)?.let { b -> tm.rep(b) != TypeMapper.Rep.BOOL } ?: false
        val supers = supers0 + if (ordered) listOf("Comparable<$self>") else emptyList()
        val w = CodeWriter()
        w.line(traceLine(qname, s.str("hash")))
        w.line("@kotlin.jvm.JvmInline")
        w.line("value class $name${tm.typeParamDecl(named.tparams)}(val value: ${tm.kt(u)})${if (supers.isEmpty()) "" else " : " + supers.joinToString(", ")} {")
        w.indent {
            if (ordered) {
                w.line()
                w.line("override fun compareTo(other: $self): Int = value.compareTo(other.value)")
            }
            val scope = ClassScope(tpNames, (mset.keys + "value").toSet(), overrideNames)
            for (m in declared) {
                w.line()
                val ptr = m.reqObj("recv").list("list").first().reqObj("type").str("star") == "pointerType"
                if (ptr) {
                    report.refused(pc.pkg, m.str("qname")!!, m.reqInt("lines"), Refusal("pointer-method-on-value-type"), stub = false)
                    w.line("// goport: refused pointer-method-on-value-type: ${m.str("qname")}")
                    continue
                }
                w.raw(funcDecl(m, fc, extension = false, member = scope))
            }
            w.line()
            w.block("companion object") {
                if (named.tparams.isEmpty()) w.line("val ELEM: GoElem<$self> = GoElem({ $self(${tm.zero(u)}) })")
                else w.line("fun ${tm.typeParamDecl(named.tparams)} elem(): GoElem<$self> = GoElem({ $self(${tm.zero(u)}) })")
            }
        }
        w.line("}")
        return w.toString()
    }

    // ------------------------------------------------------------------ constants and variables

    fun constSpec(s: Node, fc: FileCtx): String {
        val tm = TypeMapper(fc)
        val names = s.list("names")
        val w = CodeWriter()
        names.forEachIndexed { i, n ->
            val goName = n.str("name")!!
            if (goName == "_") return@forEachIndexed
            val qname = (s["qnames"] as? kotlinx.serialization.json.JsonArray)?.getOrNull(i)?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                ?: "${pc.pkg.path}.$goName"
            val o = pc.obj(n.reqInt("obj"))
            val t = o.reqInt("t")
            try {
                val c = o.obj("c") ?: refuse("const-without-value")
                val name = prog.constName(qname, goName)
                val b = types.basic(t) ?: refuse("const-non-basic")
                val raw = Literals.raw(c, tm.rep(b))
                w.line(traceLine(qname, s.str("hash")))
                if (tm.isValueClass(t)) w.line("val $name: ${tm.kt(t)} = ${tm.kt(t)}(${raw.code})")
                else w.line("const val $name: ${tm.basicKt(tm.rep(b))} = ${raw.code}")
                report.lowered(pc.pkg, qname, 1)
            } catch (r: Refusal) {
                report.refused(pc.pkg, qname, 1, r, stub = false)
                w.line("// goport: refused ${r.reason}: $qname (constant)")
            }
        }
        return w.toString()
    }

    fun varSpec(s: Node, fc: FileCtx): String {
        val names = s.list("names")
        val values = s.list("values")
        val w = CodeWriter()
        val qn = (s["qnames"] as? kotlinx.serialization.json.JsonArray)?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
            ?: names.map { "${pc.pkg.path}.${it.str("name")}" }
        overrides[qn.first()]?.let { ov ->
            ov.used = true
            report.overridden(pc.pkg, qn.first(), s.int("lines") ?: 1)
            return "${traceLine(qn.first(), s.str("hash"))}\n${ov.text.trimEnd()}\n"
        }
        if (names.all { it.str("name") == "_" }) {
            // `var _ I = (*T)(nil)`: a compile-time interface assertion — the IR's `implements` carries it.
            report.lowered(pc.pkg, qn.first(), s.int("lines") ?: 1)
            return ""
        }
        val tm = TypeMapper(fc)
        val fn = FnCtx(fc, qn.first(), tm)
        fn.w = CodeWriter()
        val low = Lowering(fn)
        try {
            pinned[qn.first()]?.let { refuse("pinned:$it") }
            if (values.size == 1 && names.size > 1) refuse("var-tuple-init")
            names.forEachIndexed { i, n ->
                val goName = n.str("name")!!
                val o = pc.obj(n.reqInt("obj"))
                val t = o.reqInt("t")
                val init = values.getOrNull(i)?.let { low.flow(it).code } ?: tm.zero(t)
                if (goName == "_") {
                    w.line(traceLine(qn[i], s.str("hash")))
                    w.line("private val ${fn.fresh("blank")}: Any? = $init")
                    return@forEachIndexed
                }
                if (o.bool("addr") && !tm.isStructValue(t)) refuse("addr-global")
                val name = prog.varName(qn[i], goName)
                val kw = if (o.bool("mut")) "var" else "val"
                w.line(traceLine(qn[i], s.str("hash")))
                w.line("${jvmField(name, t, tm, kw == "var")}$kw $name: ${tm.kt(t)} = $init")
            }
            if (fn.w.length > 0) refuse("var-init-statements")
            report.lowered(pc.pkg, qn.first(), s.int("lines") ?: 1)
            return w.toString() + fn.helpers.joinToString("") { "\n$it" }
        } catch (r: Refusal) {
            report.refused(pc.pkg, qn.first(), s.int("lines") ?: 1, r, stub = true)
            val sw = CodeWriter()
            names.forEachIndexed { i, n ->
                if (n.str("name") == "_") return@forEachIndexed
                val o = pc.obj(n.reqInt("obj"))
                sw.line(traceLine(qn[i], s.str("hash")))
                try {
                    val kt = tm.kt(o.reqInt("t"))
                    // A getter, not an initializer: a refused variable must not break its file's class init.
                    sw.line("val ${prog.varName(qn[i], n.str("name")!!)}: $kt get() = TODO(\"goport: refused ${r.reason}: ${qn[i]}\")")
                } catch (_: Refusal) {
                    sw.line("// goport: refused ${r.reason}: ${qn[i]} (no stub)")
                }
            }
            return sw.toString()
        }
    }

}
