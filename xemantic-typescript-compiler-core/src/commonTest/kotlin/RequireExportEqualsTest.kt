/*
 * Copyright 2025-2026 Kazimierz Pogoda / Xemantic
 *
 * Licensed under the GNU Affero General Public License, Version 3 (AGPL-3.0-only)
 * WITH LicenseRef-xtsc-output-exception, see LICENSE.md.
 *
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.81) `import X = require("m")` of a FILELESS ambient block whose surface is
 * `export = <value>` names that value — tsc's `resolveExternalModuleSymbol` — where
 * B113 answered the block's CARRIER for every block. Each shape was reproduced on
 * its own scratch project against tsgo 7.0.2 (2026-09-02/03) before it was fixed:
 *
 * - `class Worker extends EventEmitter` under `import EventEmitter =
 *   require("node:events")` inside a `declare module` block inherited nothing
 *   (`@types/node`'s five `extends EventEmitter` bases), and so did `extends Stream`
 *   written inside `namespace Stream`; a file-level `import EE = require(…)` used as
 *   an annotation, a constructor or a base typed `any`; `EE.Abortable` was a false
 *   TS2694.
 * - `import net2 = require("net")` at a module file's top level was a false TS2833
 *   (`Did you mean 'net'?`) beside a correctly resolved type.
 * - TS2694's namespace display: the carrier is quoted (`"net"`, `"node:net"`), an
 *   `export =` target bare (`EventEmitter`, `NS`, `EventEmitter.Deep`), a carrier's
 *   other member through the quoted carrier (`"net".Sub`) — tsgo, measured; this
 *   checker printed `"main".events.EventEmitter`.
 * - Inside a `.d.ts` / `declare module` body the unresolved-name family is silent
 *   about qualified names; an interface's `extends net.Missing` and an annotation
 *   `net.Missing2` through such an alias are TS2694 in tsgo and were nothing here.
 * - `import { Stream } from "node:stream"` where the surface's namespace holds
 *   `Readable` but not `Stream` itself is TS2305 in tsgo (TS2616 for the block that
 *   spells `export = Stream`); the (CHK.79) surface walk made the import RESOLVE
 *   and nothing reported it.
 * - `@types/node`'s `namespace EventEmitter { export { internal as EventEmitter } }`:
 *   `class W extends EE.EventEmitter` is the class, through the local clause.
 *
 * Every consumer pin reads the type OUT OF A MESSAGE (an unresolved name is `any`,
 * silent everywhere); the lens pins read declaration IDENTITY through
 * `heritageBaseSymbol`, the channel the externals generator asks.
 */
class RequireExportEqualsTest {

    private fun messages(diagnostics: List<Diagnostic>, code: Int): List<String> =
        diagnostics.filter { it.code == code }.map { it.message }

    private val directives = "// @strict: true\n// @module: commonjs"

    private val eventsDts = """
        declare module "events" {
            class EventEmitter<T = any> { zzEmit: number; }
            namespace EventEmitter {
                export interface Abortable { zzAbort: string; }
                namespace Deep { interface Y { zzY: number; } }
            }
            export = EventEmitter;
        }
        declare module "node:events" {
            import events = require("events");
            export = events;
        }
    """

    private val streamDts = """
        declare module "stream" {
            import EventEmitter = require("node:events");
            class Stream extends EventEmitter { zzStream: number; }
            namespace Stream {
                class Readable extends Stream { zzReadable: string; }
                class Writable extends Stream { zzWritable: boolean; }
                interface Duplex extends Readable, Writable {}
            }
            export = Stream;
        }
        declare module "node:stream" {
            import stream = require("stream");
            export = stream;
        }
    """

    private val netDts = """
        declare module "net" {
            interface Socket { zzS: number; }
            namespace Sub { interface X { zzX: number; } }
        }
        declare module "node:net" { export * from "net"; }
        declare module "nsmod" {
            namespace NS { interface Foo { zzF: number; } }
            export = NS;
        }
        declare module "fnmod" {
            function fn(): number;
            export = fn;
        }
        declare module "cmod" {
            class Plain { zzP: number; }
            export = Plain;
        }
    """

    private val workerDts = """
        declare module "worker" {
            import EventEmitter = require("node:events");
            class Worker extends EventEmitter { zzW: string; }
            interface Wi extends EventEmitter { zzWi: string; }
            type Ab = EventEmitter.Abortable;
        }
    """

    private fun program(vararg files: Pair<String, String>): List<Diagnostic> = diagnose(
        files.joinToString("\n") { (name, text) -> "// @Filename: $name\n$text" },
        directives = directives,
    )

    // --- the require alias names the `export =` class --------------------------------

    @Test
    fun `a class extending a require alias of an export-equals class inside an ambient block inherits its members`() {
        val d = program(
            "events.d.ts" to eventsDts,
            "worker.d.ts" to workerDts,
            "main.ts" to """
                import w = require("worker");
                declare const x: w.Worker;
                const p1: boolean = x.zzEmit;
                declare const y: w.Wi;
                const p2: boolean = y.zzEmit;
                declare const a: w.Ab;
                const p3: boolean = a.zzAbort;
            """,
        )
        assert(messages(d, 2322) == listOf(
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'string' is not assignable to type 'boolean'.",
        ))
        assert(messages(d, 2694).isEmpty())
        assert(messages(d, 2339).isEmpty())
    }

    @Test
    fun `a class extending Stream written inside namespace Stream inherits the class and its base`() {
        val d = program(
            "events.d.ts" to eventsDts,
            "stream.d.ts" to streamDts,
            "main.ts" to """
                import s = require("node:stream");
                declare const r: s.Readable;
                const q1: boolean = r.zzStream;
                const q2: boolean = r.zzEmit;
                const q3: boolean = r.zzReadable;
                declare const d: s.Duplex;
                const q4: boolean = d.zzStream;
                declare const st: s;
                const q5: boolean = st.zzStream;
                const q6: boolean = st.zzEmit;
            """,
        )
        assert(messages(d, 2322) == listOf(
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'string' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
        ))
        assert(messages(d, 2694).isEmpty())
    }

    @Test
    fun `a file-level require alias of an export-equals class is a type a constructor and a base`() {
        val d = program(
            "events.d.ts" to eventsDts,
            "main.ts" to """
                import EE = require("node:events");
                declare const e: EE;
                const p1: boolean = e.zzEmit;
                const p2: boolean = new EE().zzEmit;
                class Local extends EE { }
                const p3: boolean = new Local().zzEmit;
                declare const ab: EE.Abortable;
                const p4: boolean = ab.zzAbort;
            """,
        )
        assert(messages(d, 2322) == listOf(
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'string' is not assignable to type 'boolean'.",
        ))
        assert(messages(d, 2694).isEmpty())
        assert(messages(d, 2833).isEmpty())
    }

    @Test
    fun `negative control - a block without export-equals and a block exporting a namespace keep the carrier answer`() {
        val d = program(
            "net.d.ts" to netDts,
            "main.ts" to """
                import net2 = require("net");
                declare const s: net2.Socket;
                const z1: boolean = s.zzS;
                declare const sx: net2.Sub.X;
                const z2: boolean = sx.zzX;
                import ns = require("nsmod");
                declare const h: ns.Foo;
                const z3: boolean = h.zzF;
                import fn = require("fnmod");
                declare const g: fn.Nope;
            """,
        )
        assert(messages(d, 2322) == listOf(
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
        ))
        assert(messages(d, 2503) == listOf("Cannot find namespace 'fn'."))
        assert(messages(d, 2833).isEmpty())
        assert(messages(d, 2694).isEmpty())
    }

    // --- TS2833 / TS2694 through a require alias at a module file's top level ----------

    @Test
    fun `a require alias of an ambient module is a namespace and not a misspelling of one`() {
        val d = program(
            "net.d.ts" to netDts,
            "main.ts" to """
                import net2 = require("net");
                declare const s: net2.Socket;
                const z1: boolean = s.zzS;
                import net3 = require("node:net");
                declare const s3: net3.Socket;
                const z2: boolean = s3.zzS;
            """,
        )
        assert(messages(d, 2833).isEmpty())
        assert(messages(d, 2694).isEmpty())
        assert(messages(d, 2322).size == 2)
    }

    @Test
    fun `TS2694 names the carrier quoted an export-equals target bare and a nested member through either`() {
        val d = program(
            "events.d.ts" to eventsDts,
            "net.d.ts" to netDts,
            "main.ts" to """
                import net2 = require("net");
                declare const a: net2.Nope;
                import net3 = require("node:net");
                declare const b: net3.Nope;
                import EE = require("node:events");
                declare const c: EE.Nope;
                import ev = require("events");
                declare const d: ev.Nope;
                import ns = require("nsmod");
                declare const e: ns.Nope;
                import * as nn from "node:net";
                declare const f: nn.Nope;
                declare const g: net2.Sub.Nope;
                declare const h: EE.Deep.Nope;
            """,
        )
        assert(messages(d, 2694) == listOf(
            "Namespace '\"net\"' has no exported member 'Nope'.",
            "Namespace '\"node:net\"' has no exported member 'Nope'.",
            "Namespace 'EventEmitter' has no exported member 'Nope'.",
            "Namespace 'EventEmitter' has no exported member 'Nope'.",
            "Namespace 'NS' has no exported member 'Nope'.",
            "Namespace '\"node:net\"' has no exported member 'Nope'.",
            "Namespace '\"net\".Sub' has no exported member 'Nope'.",
            "Namespace 'EventEmitter.Deep' has no exported member 'Nope'.",
        ))
        assert(messages(d, 2833).isEmpty())
    }

    @Test
    fun `a require alias of an export-equals class without a namespace is a type used as a namespace`() {
        val d = program(
            "net.d.ts" to netDts,
            "main.ts" to """
                import Plain = require("cmod");
                declare const d: Plain.Nope;
                declare const d2: Plain;
                const p1: boolean = d2.zzP;
            """,
        )
        assert(messages(d, 2702) == listOf("'Plain' only refers to a type, but is being used as a namespace here."))
        assert(messages(d, 2322) == listOf("Type 'number' is not assignable to type 'boolean'."))
    }

    // --- TS2694 inside ambient bodies, where the unresolved-name family is silent ------

    @Test
    fun `an interface heritage and an annotation through an alias inside an ambient block report the missing member`() {
        val d = program(
            "events.d.ts" to eventsDts,
            "net.d.ts" to netDts,
            "mod.d.ts" to """
                declare module "mod" {
                    import * as net from "node:net";
                    import net2 = require("net");
                    import EE = require("node:events");
                    import ns = require("nsmod");
                    interface A1 extends net.Missing { }
                    interface A2 extends EE.Nope { }
                    interface A3 extends ns.Nope { }
                    const b1: net.Missing2;
                    const b2: net2.Nope;
                    const b3: EE.Deep.Nope;
                    interface Fine extends net.Socket { c: net2.Sub.X; d: EE.Abortable; e: ns.Foo; }
                    class C1 extends EE.Nope { }
                }
            """,
            "main.ts" to """
                import m = require("mod");
                declare const f: m.Fine;
                const z: boolean = f.zzS;
            """,
        )
        assert(messages(d, 2694) == listOf(
            "Namespace '\"node:net\"' has no exported member 'Missing'.",
            "Namespace 'EventEmitter' has no exported member 'Nope'.",
            "Namespace 'NS' has no exported member 'Nope'.",
            "Namespace '\"node:net\"' has no exported member 'Missing2'.",
            "Namespace '\"net\"' has no exported member 'Nope'.",
            "Namespace 'EventEmitter.Deep' has no exported member 'Nope'.",
        ))
        assert(messages(d, 2339) == listOf("Property 'Nope' does not exist on type 'typeof EventEmitter'."))
        assert(messages(d, 2322) == listOf("Type 'number' is not assignable to type 'boolean'."))
    }

    @Test
    fun `negative control - a head whose surface has a re-export clause from another module is not judged`() {
        val d = program(
            "net.d.ts" to netDts,
            "re.d.ts" to """
                declare module "re" {
                    export { Socket as Sock } from "net";
                }
                declare module "mod" {
                    import * as re from "re";
                    interface A extends re.Sock { }
                    const b: re.Sock;
                    const c: re.Nope;
                }
            """,
            "main.ts" to "export {};",
        )
        assert(messages(d, 2694).isEmpty())
    }

    @Test
    fun `a local re-export clause inside the export-equals namespace names the class for a base`() {
        val d = program(
            "events2.d.ts" to """
                declare module "events" {
                    class EventEmitter { zzEmit: number; }
                    import internal = require("node:events");
                    namespace EventEmitter {
                        export { internal as EventEmitter };
                        export interface Abortable { zzAbort: string; }
                    }
                    export = EventEmitter;
                }
                declare module "node:events" {
                    import events = require("events");
                    export = events;
                }
                declare module "w" {
                    import EE = require("node:events");
                    class W extends EE.EventEmitter { zzW: string; }
                    interface I extends EE.Abortable { zzI: number; }
                    const b: EE.Nope;
                    const c: EE.EventEmitter;
                }
            """,
            "main.ts" to """
                import w = require("w");
                declare const x: w.W;
                const p1: boolean = x.zzEmit;
                declare const i: w.I;
                const p2: boolean = i.zzAbort;
            """,
        )
        assert(messages(d, 2322) == listOf(
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'string' is not assignable to type 'boolean'.",
        ))
        assert(messages(d, 2339).isEmpty())
        assert(messages(d, 2694) == listOf("Namespace 'EventEmitter' has no exported member 'Nope'."))
    }

    // --- TS2305 / TS2616 for a named import of an export-equals value module -----------

    @Test
    fun `a named import absent from the export-equals target surface is TS2305 and the target's own name TS2616`() {
        val d = program(
            "events.d.ts" to eventsDts,
            "stream.d.ts" to streamDts,
            "aug.d.ts" to """
                declare module "node:stream" { interface Extra { zzE: number; } }
                declare module "s3x" {
                    import { Stream, Readable, Extra, Nope2 } from "node:stream";
                    interface Q { a: Stream; b: Readable; c: Extra; }
                }
            """,
            "main.ts" to """
                import { Stream, Readable, Extra, Nope } from "node:stream";
                declare const r: Readable;
                const z1: boolean = r.zzReadable;
                declare const e: Extra;
                const z2: boolean = e.zzE;
                import { Stream as S2 } from "stream";
                import type { Nope3 } from "stream";
                import { Abortable, Nope4 } from "node:events";
            """,
        )
        assert(messages(d, 2305) == listOf(
            "Module '\"node:stream\"' has no exported member 'Stream'.",
            "Module '\"node:stream\"' has no exported member 'Nope2'.",
            "Module '\"node:stream\"' has no exported member 'Stream'.",
            "Module '\"node:stream\"' has no exported member 'Nope'.",
            "Module '\"stream\"' has no exported member 'Nope3'.",
            "Module '\"node:events\"' has no exported member 'Nope4'.",
        ))
        assert(messages(d, 2616) == listOf(
            "'Stream' can only be imported by using 'import Stream = require(\"stream\")' or a default import.",
        ))
        assert(messages(d, 2322) == listOf(
            "Type 'string' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
        ))
    }

    @Test
    fun `negative control - a static member a re-export clause and a namespace target are not TS2305`() {
        val d = program(
            "lib.d.ts" to """
                declare module "cm" {
                    class K { static made(): K; zzK: number; }
                    namespace K { interface Opt { zzO: number; } }
                    export = K;
                }
                declare module "node:cm" { import cm = require("cm"); export = cm; }
                declare module "ev" {
                    class E { zzE: number; }
                    import internal = require("node:ev");
                    namespace E { export { internal as E }; }
                    export = E;
                }
                declare module "node:ev" { import ev = require("ev"); export = ev; }
                declare module "star" {
                    class S { zzS: number; }
                    export = S;
                    export * from "cm";
                }
                declare module "nsm" { namespace NS { interface Foo { zzF: number; } } export = NS; }
            """,
            "main.ts" to """
                import { made, Opt, Nope } from "node:cm";
                import { E } from "node:ev";
                import { Anything } from "star";
                import { Foo, Nope2 } from "nsm";
                declare const o: Opt;
                const z: boolean = o.zzO;
            """,
        )
        assert(messages(d, 2305) == listOf("Module '\"node:cm\"' has no exported member 'Nope'."))
        assert(messages(d, 2616).isEmpty())
        assert(messages(d, 2322) == listOf("Type 'number' is not assignable to type 'boolean'."))
    }

    // --- the lens ----------------------------------------------------------------------

    private class Recorder : CheckedNodeSink {
        val heritageBaseDecl = HashMap<String, Node?>()
        val seen = HashSet<String>()
        override fun expression(node: Expression, lens: CheckedLens) {}
        override fun declaration(node: Node, lens: CheckedLens) {
            val (name, clauses) = when (node) {
                is ClassDeclaration -> (node.name?.text ?: return) to node.heritageClauses
                is InterfaceDeclaration -> node.name.text to node.heritageClauses
                else -> return
            }
            for (clause in clauses ?: return) {
                val base = clause.types.firstOrNull()?.expression ?: continue
                seen.add(name)
                heritageBaseDecl[name] = lens.heritageBaseSymbol(base)
                    ?.let { lens.aliasTarget(it) ?: it }
                    ?.declarations?.firstOrNull()
            }
        }
    }

    private fun runLens(vararg files: Pair<String, String>): Pair<Recorder, List<SourceFile>> {
        val options = CompilerOptions()
        val binder = Binder(options)
        val sourceFiles = files.map { (name, text) -> Parser(text.trimIndent(), name).parse() }
        val results = sourceFiles.map { binder.bind(it) }
        val recorder = Recorder()
        Checker(options, results, isMultiFileSource = true, checkedSink = recorder)
        return recorder to sourceFiles
    }

    private fun classNamed(file: SourceFile, name: String): Node {
        var found: Node? = null
        fun walk(node: Node) {
            if (node is ClassDeclaration && node.name?.text == name) found = node
            forEachChild(node) { walk(it) }
        }
        walk(file)
        return found!!
    }

    @Test
    fun `the lens answers a require alias base as the export-equals class and a namespace-internal base as the enclosing class`() {
        val (recorder, files) = runLens(
            "/proj/events.d.ts" to eventsDts,
            "/proj/stream.d.ts" to streamDts,
            "/proj/worker.d.ts" to workerDts,
        )
        val (events, stream, _) = files
        val workerExtendsEmitter = recorder.heritageBaseDecl["Worker"] === classNamed(events, "EventEmitter")
        val wiExtendsEmitter = recorder.heritageBaseDecl["Wi"] === classNamed(events, "EventEmitter")
        val streamExtendsEmitter = recorder.heritageBaseDecl["Stream"] === classNamed(events, "EventEmitter")
        val readableExtendsStream = recorder.heritageBaseDecl["Readable"] === classNamed(stream, "Stream")
        val writableExtendsStream = recorder.heritageBaseDecl["Writable"] === classNamed(stream, "Stream")
        val duplexExtendsReadable = recorder.heritageBaseDecl["Duplex"] === classNamed(stream, "Readable")
        val asked = recorder.seen.containsAll(listOf("Worker", "Wi", "Stream", "Readable", "Writable", "Duplex"))
        assert(asked)
        assert(workerExtendsEmitter)
        assert(wiExtendsEmitter)
        assert(streamExtendsEmitter)
        assert(readableExtendsStream)
        assert(writableExtendsStream)
        assert(duplexExtendsReadable)
    }
}
