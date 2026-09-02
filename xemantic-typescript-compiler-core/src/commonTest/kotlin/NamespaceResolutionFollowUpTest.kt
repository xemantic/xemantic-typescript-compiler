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
 * (CHK.80) The (CHK.79) follow-ups, each reproduced on its own scratch project
 * against tsgo 7.0.2 (2026-09-02) before it was fixed:
 *
 * (a) ANNOTATIONS through a namespace-import / `require` alias inside an ambient
 *     block — `x: net.Socket`, `Array<net.Socket>`, a method's return, a type alias
 *     — typed `any` (14 of 15 consumer probes silent; tsgo reports all 15, the one
 *     that reported here was the class heritage (CHK.79) landed). The same surface
 *     walk now ends [Checker.resolveQualifiedName].
 * (b) `class X extends net.Nope` / `extends NS.Nope`: tsgo reports TS2339 at the
 *     MEMBER (`Property 'Nope' does not exist on type 'typeof import("node:net")'`,
 *     `'typeof NS'`, the innermost segment for a nested head); this checker reported
 *     nothing. Emitted only where the head resolved to a namespace or module.
 * (c) NAMED-import heritage heads inside ambient blocks (`import { EventEmitter } from
 *     "node:events"; class C extends EventEmitter`) — the top-level import index does
 *     not hold a block's specifier, so the alias never resolved — and the bare names
 *     of a `declare global { namespace NodeJS { … } }` block declared by ANOTHER file's
 *     `NodeJS` (`interface ProcessEnv extends Dict<string>`), which the per-file
 *     namespace symbol lacks: false TS2339 on every inherited member.
 * (d) A module file's `import * as net from "./mynet"` merged BY NAME into the ambient
 *     carrier `globals["net"]` (a script-file local, so a SHARED name), after which
 *     `resolveAlias` on the carrier followed the foreign import: a false TS2339 and a
 *     false TS2833 in an UNRELATED file's `import net2 = require("net")`, and its
 *     own probe lost. An import alias is file-local in tsc whatever its name.
 *
 * Every consumer pin reads the type OUT OF A MESSAGE (an unresolved name is `any`,
 * which is silent everywhere); the lens pins read declaration IDENTITY through the
 * channels the externals generator asks (`typeReferenceSymbol`, `heritageBaseSymbol`).
 */
class NamespaceResolutionFollowUpTest {

    private fun messages(diagnostics: List<Diagnostic>, code: Int): List<String> =
        diagnostics.filter { it.code == code }.map { it.message }

    private val directives = "// @strict: true\n// @module: commonjs"

    // The (CHK.79) `@types/node`-shaped modules.
    private val streamDts = """
        declare module "stream" {
            class Stream { zzStream: number; }
            namespace Stream {
                class Duplex extends Stream { zzDuplex: string; }
                interface DuplexOptions { zzDuplexOpt: boolean; }
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
            import * as stream from "node:stream";
            class Socket extends stream.Duplex { zzSock: number; }
            class Server { zzServ: string; }
            interface ServerOpts { zzOpts: boolean; }
        }
        declare module "node:net" {
            export * from "net";
        }
    """

    // --- (a) annotations through a namespace-import alias inside an ambient block ----

    private val annDts = """
        declare module "ann" {
            import * as net from "node:net";
            import stream = require("stream");
            interface Holder {
                s: net.Socket;
                arr: Array<net.Socket>;
                opts: net.ServerOpts;
                d: stream.Duplex;
                u: net.Socket | undefined;
            }
            class Wrap { inner: net.Socket; m(): net.Server; take(o: net.ServerOpts): void; }
            type Alias = net.Socket;
            class Sub extends net.Socket { own: net.ServerOpts; }
            interface Missing { x: net.Nope; }
        }
    """

    private fun annProgram(consumer: String): List<Diagnostic> = diagnose(
        """
        // @Filename: stream.d.ts
        $streamDts
        // @Filename: net.d.ts
        $netDts
        // @Filename: ann.d.ts
        $annDts
        // @Filename: main.ts
        $consumer
        """,
        directives = directives,
    )

    @Test
    fun `an interface member annotated through a namespace import inside an ambient block types through the module surface`() {
        val d = annProgram(
            """
            import ann = require("ann");
            declare const h: ann.Holder;
            const a1: boolean = h.s.zzSock;
            const a2: boolean = h.arr[0].zzSock;
            const a3: string = h.opts.zzOpts;
            const a4: number = h.d.zzDuplex;
            const a5: boolean = h.u!.zzSock;
            """
        )
        assert(messages(d, 2322) == listOf(
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'boolean' is not assignable to type 'string'.",
            "Type 'string' is not assignable to type 'number'.",
            "Type 'number' is not assignable to type 'boolean'.",
        ))
        assert(messages(d, 2339).isEmpty())
    }

    @Test
    fun `a class member a method return a parameter and a type alias written through the alias resolve alike`() {
        val d = annProgram(
            """
            import ann = require("ann");
            declare const w: ann.Wrap;
            const b1: boolean = w.inner.zzSock;
            const b2: boolean = w.m().zzServ;
            declare const al: ann.Alias;
            const b3: boolean = al.zzSock;
            declare const sub: ann.Sub;
            const b4: string = sub.own.zzOpts;
            w.take({ zzOpts: 1 });
            """
        )
        // The last row is the object-literal ARGUMENT `{ zzOpts: 1 }` against the
        // parameter `net.ServerOpts`, reported per property as tsgo reports it.
        assert(messages(d, 2322) == listOf(
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'string' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'boolean' is not assignable to type 'string'.",
            "Type 'number' is not assignable to type 'boolean'.",
        ))
        assert(messages(d, 2339).isEmpty())
    }

    @Test
    fun `negative control - a member the module surface lacks stays any in an annotation`() {
        val d = annProgram(
            """
            import ann = require("ann");
            declare const m: ann.Missing;
            const c1: boolean = m.x;
            const c2: boolean = m.x.zzAnything;
            """
        )
        // `net.Nope` resolves to nothing (tsgo: TS2694 at the annotation, a diagnostic
        // this checker does not emit there — pre-existing); the member is `any`.
        assert(messages(d, 2322).isEmpty())
        assert(messages(d, 2339).isEmpty())
    }

    // --- (b) TS2339 at a class base whose namespace or module head lacks the member ----

    private val negDts = """
        declare module "neg" {
            import * as net from "node:net";
            import tlsm = require("net");
            namespace Inner { class IB { zzIB: number; } }
            class Bad extends net.Nope { zzBad: number; }
            const notNs: number;
            class Bad2 extends notNs.Foo { zzBad2: number; }
            class Bad3 extends tlsm.Nope { zzBad3: number; }
            class Bad4 extends net.Socket.Nope { zzBad4: number; }
            class Bad5 extends Unknown.Foo { zzBad5: number; }
            class Bad6 extends Inner.Nope { zzBad6: number; }
            class Ok1 extends Inner.IB { zzOk1: number; }
            class Ok2 extends net.Socket { zzOk2: number; }
            interface IBad extends net.Nope { zzIBad: number; }
        }
    """

    private fun negProgram(consumer: String = ""): List<Diagnostic> = diagnose(
        """
        // @Filename: stream.d.ts
        $streamDts
        // @Filename: net.d.ts
        $netDts
        // @Filename: neg.d.ts
        $negDts
        // @Filename: main.ts
        export {};
        $consumer
        """,
        directives = directives,
    )

    @Test
    fun `a class extending a missing member of a namespace import head reports TS2339 with the written specifier`() {
        val d = negProgram()
        val rows = d.filter { it.code == 2339 }
        assert(rows.map { it.message } == listOf(
            "Property 'Nope' does not exist on type 'typeof import(\"node:net\")'.",
            "Property 'Nope' does not exist on type 'typeof import(\"net\")'.",
            "Property 'Nope' does not exist on type 'typeof Inner'.",
        ))
        // At the MEMBER name, as tsgo anchors it.
        assert(rows.all { it.length == "Nope".length && it.fileName == "neg.d.ts" })
    }

    @Test
    fun `negative control - a value head a class head an unresolved head and an interface base stay silent`() {
        // `notNs.Foo` (tsgo: `typeof number`), `net.Socket.Nope` (`typeof Socket`),
        // `Unknown.Foo` (TS2304) and `interface IBad extends net.Nope` (TS2694) are
        // other diagnostics' shapes; only the three namespace/module heads report.
        val d = negProgram()
        val rows = d.filter { it.code == 2339 }
        assert(rows.size == 3)
        assert(rows.none { it.message.contains("Foo") || it.message.contains("typeof Socket") })
        assert(d.none { it.code == 2339 && it.message.contains("IBad") })
    }

    @Test
    fun `a nested namespace head displays its innermost segment and a script namespace head is reported once`() {
        val d = diagnose(
            """
            namespace Outer { export namespace Inner2 { export class X { zzX: number; } } }
            namespace A.B { export class Y { zzY: number; } }
            class C4 extends Outer.Inner2.Nope { zzC4 = 1; }
            class C5 extends A.B.Nope { zzC5 = 1; }
            namespace M { class C {} class D extends M.C {} }
            class C6 extends Outer.Inner2.X { zzC6 = 1; }
            """
        )
        // `M.C` is declared but not exported (pristine `classExtendingQualifiedName`):
        // the spine's namespace-receiver check reports it and this pass does not
        // report it a second time.
        val rows = messages(d, 2339).sorted()
        assert(rows == listOf(
            "Property 'C' does not exist on type 'typeof M'.",
            "Property 'Nope' does not exist on type 'typeof B'.",
            "Property 'Nope' does not exist on type 'typeof Inner2'.",
        ))
    }

    @Test
    fun `a module file's own namespace import head displays the specifier it was written with`() {
        val d = diagnose(
            """
            // @Filename: net.d.ts
            $netDts
            // @Filename: stream.d.ts
            $streamDts
            // @Filename: mod.ts
            import * as net from "node:net";
            import netr = require("net");
            export class D1 extends net.Nope { zzD1 = 1; }
            export class D2 extends netr.Nope { zzD2 = 1; }
            export class D3 extends net.Socket { zzD3 = 1; }
            """,
            directives = directives,
        )
        assert(messages(d, 2339) == listOf(
            "Property 'Nope' does not exist on type 'typeof import(\"node:net\")'.",
            "Property 'Nope' does not exist on type 'typeof import(\"net\")'.",
        ))
    }

    // --- (c) named-import heritage heads and `declare global` namespaces -------------

    private val asyncHooksDts = """
        declare module "async_hooks" {
            class AsyncResource { zzAr: string; }
            interface AsyncResourceOptions { zzAro: boolean; }
        }
        declare module "node:async_hooks" {
            export * from "async_hooks";
        }
    """

    private val eventsDts = """
        declare module "events" {
            import { AsyncResource, AsyncResourceOptions } from "node:async_hooks";
            class EventEmitter<T = any> { zzEmit: number; }
            namespace EventEmitter {
                export interface Abortable { zzAbort: string; }
                export interface EEAR extends AsyncResource { zzEear: number; }
                export interface EEAO extends AsyncResourceOptions { zzEeao: number; }
            }
            export = EventEmitter;
        }
        declare module "node:events" {
            import events = require("events");
            export = events;
        }
    """

    private val cStreamDts = """
        declare module "stream" {
            import { Abortable, EventEmitter } from "node:events";
            class Stream extends EventEmitter { zzStream: number; }
            namespace Stream {
                interface StreamOptions extends Abortable { zzSo: number; }
                class Readable extends Stream { zzReadable: string; }
                class Writable extends Stream { zzWritable: boolean; }
            }
            export = Stream;
        }
        declare module "node:stream" {
            import stream = require("stream");
            export = stream;
        }
    """

    private val childProcessDts = """
        declare module "child_process" {
            import { EventEmitter, Nope } from "node:events";
            import { Readable, Writable } from "node:stream";
            class ChildProcess extends EventEmitter { zzCp: string; }
            interface Control extends EventEmitter { zzCtl: number; }
            class Sub extends Readable { zzSub: number; }
            class BadBase extends Nope { zzBadBase: number; }
            interface Opts { r: Readable; w: Writable; }
        }
        declare module "node:child_process" {
            export * from "child_process";
        }
    """

    private val globalsDts = """
        declare namespace NodeJS {
            interface ReadableStream { zzRs: number; }
            interface WritableStream { zzWs: string; }
            interface ReadWriteStream extends ReadableStream, WritableStream {}
            interface RefCounted { zzRc: boolean; }
            interface Dict<T> { [key: string]: T | undefined; }
        }
    """

    private val processDts = """
        declare module "process" {
            import { EventEmitter } from "node:events";
            global {
                namespace NodeJS {
                    interface ProcessEnv extends Dict<string> {}
                    interface Socket extends ReadWriteStream { zzSock: boolean; }
                    interface Process extends EventEmitter { zzProc: string; }
                    interface Orphan extends NoSuchBase { zzOrphan: number; }
                }
            }
        }
        declare module "timers" {
            global {
                namespace NodeJS {
                    interface Timer extends RefCounted { zzTimer: number; }
                }
            }
        }
    """

    private fun cProgram(consumer: String): List<Diagnostic> = diagnose(
        """
        // @Filename: globals.d.ts
        $globalsDts
        // @Filename: async_hooks.d.ts
        $asyncHooksDts
        // @Filename: events.d.ts
        $eventsDts
        // @Filename: stream.d.ts
        $cStreamDts
        // @Filename: child_process.d.ts
        $childProcessDts
        // @Filename: process.d.ts
        $processDts
        // @Filename: main.ts
        $consumer
        """,
        directives = directives,
    )

    @Test
    fun `a class extending a named import of an export-equals chain inside an ambient block inherits it`() {
        val d = cProgram(
            """
            import cp = require("child_process");
            declare const c: cp.ChildProcess;
            const c1: boolean = c.zzEmit;
            declare const ctl: cp.Control;
            const c2: boolean = ctl.zzEmit;
            declare const sub: cp.Sub;
            const c3: boolean = sub.zzReadable;
            const c4: boolean = sub.zzStream;
            const c5: boolean = sub.zzEmit;
            declare const o: cp.Opts;
            const c6: boolean = o.r.zzReadable;
            const c7: boolean = o.w.zzWritable;
            """
        )
        assert(messages(d, 2322) == listOf(
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'string' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'string' is not assignable to type 'boolean'.",
        ))
        assert(messages(d, 2339).isEmpty())
    }

    @Test
    fun `a named import of an export-star chain inside an ambient block resolves as a base`() {
        val d = cProgram(
            """
            import ev = require("events");
            declare const ee: ev.EEAR;
            const e1: boolean = ee.zzAr;
            declare const eo: ev.EEAO;
            const e2: boolean = eo.zzAro;
            """
        )
        assert(messages(d, 2322) == listOf("Type 'string' is not assignable to type 'boolean'."))
        assert(messages(d, 2339).isEmpty())
    }

    @Test
    fun `a bare base inside a declare global namespace resolves through the merged global namespace`() {
        val d = cProgram(
            """
            export {};
            declare const env: NodeJS.ProcessEnv;
            const g1: boolean = env["x"];
            declare const ns: NodeJS.Socket;
            const g2: boolean = ns.zzRs;
            const g3: boolean = ns.zzWs;
            declare const pr: NodeJS.Process;
            const g4: boolean = pr.zzEmit;
            declare const tm: NodeJS.Timer;
            const g5: boolean = tm.zzRc;
            """
        )
        assert(messages(d, 2322) == listOf(
            "Type 'string | undefined' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'string' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
        ))
        assert(messages(d, 2339).isEmpty())
    }

    // --- (d) a module file's import alias never merges into a global carrier ---------

    private fun dProgram(modImport: String, consumer: String): List<Diagnostic> = diagnose(
        """
        // @Filename: net.d.ts
        declare module "net" {
            class Socket { zzSock: number; }
        }
        // @Filename: mynet.ts
        export class Socket { zzMine: string = ""; }
        // @Filename: mod.ts
        $modImport
        declare const s: net.Socket;
        export const a1: number = s.zzMine;
        export class D extends net.Socket { zzD = 1; }
        declare const dd: D;
        export const a2: number = dd.zzMine;
        // @Filename: consumer.ts
        $consumer
        """,
        directives = directives,
    )

    @Test
    fun `a module file's namespace import sharing an ambient module's name stays that file's own alias`() {
        val d = dProgram(
            modImport = """import * as net from "./mynet";""",
            consumer = """
            import net2 = require("net");
            declare const s: net2.Socket;
            const c1: boolean = s.zzSock;
            import * as net3 from "net";
            declare const t: net3.Socket;
            const c2: boolean = t.zzSock;
            """,
        )
        // mod.ts reads ITS `net` (`./mynet`); consumer.ts reads the ambient "net".
        assert(messages(d, 2322) == listOf(
            "Type 'string' is not assignable to type 'number'.",
            "Type 'string' is not assignable to type 'number'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
        ))
        assert(messages(d, 2339).isEmpty())
    }

    @Test
    fun `negative control - a module file's namespace import under a name no script declares is unchanged`() {
        val d = dProgram(
            modImport = """import * as net from "./mynet"; import * as other from "./mynet"; export const o: number = (null as any as other.Socket).zzMine;""",
            consumer = """export {};""",
        )
        assert(messages(d, 2322) == listOf(
            "Type 'string' is not assignable to type 'number'.",
            "Type 'string' is not assignable to type 'number'.",
            "Type 'string' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `negative control - a script global some module imports under the same name stays visible to a third file`() {
        val d = diagnose(
            """
            // @Filename: g.d.ts
            declare var Foo: string;
            // @Filename: x.ts
            export const Foo: number = 1;
            // @Filename: importer.ts
            import { Foo } from "./x";
            export const i1: string = Foo;
            // @Filename: third.ts
            export const t1: number = Foo;
            """,
            directives = directives,
        )
        assert(messages(d, 2322) == listOf(
            "Type 'number' is not assignable to type 'string'.",
            "Type 'string' is not assignable to type 'number'.",
        ))
    }

    // --- the lens ----------------------------------------------------------------------

    /** Records the lens's answers for every heritage base and every member annotation. */
    private class Recorder : CheckedNodeSink {
        val heritageBaseDecl = HashMap<String, Node?>()
        val annotationDecl = HashMap<String, Node?>()
        val seen = HashSet<String>()

        override fun expression(node: Expression, lens: CheckedLens) {}

        private fun recordHeritage(name: String, clauses: List<HeritageClause>?, lens: CheckedLens) {
            for (clause in clauses ?: return) {
                val base = clause.types.firstOrNull()?.expression ?: continue
                seen.add(name)
                heritageBaseDecl[name] = lens.heritageBaseSymbol(base)
                    ?.let { lens.aliasTarget(it) ?: it }
                    ?.declarations?.firstOrNull()
            }
        }

        private fun recordMembers(owner: String, members: List<ClassElement>, lens: CheckedLens) {
            for (m in members) {
                val (memberName, type) = when (m) {
                    is PropertyDeclaration -> ((m.name as? Identifier)?.text ?: continue) to m.type
                    is MethodDeclaration -> ((m.name as? Identifier)?.text ?: continue) to m.type
                    else -> continue
                }
                val ref = type as? TypeReference ?: continue
                val key = "$owner.$memberName"
                seen.add(key)
                annotationDecl[key] = lens.typeReferenceSymbol(ref)?.declarations?.firstOrNull()
            }
        }

        override fun declaration(node: Node, lens: CheckedLens) {
            when (node) {
                is InterfaceDeclaration -> {
                    recordHeritage(node.name.text, node.heritageClauses, lens)
                    recordMembers(node.name.text, node.members, lens)
                }
                is ClassDeclaration -> node.name?.text?.let {
                    recordHeritage(it, node.heritageClauses, lens)
                    recordMembers(it, node.members, lens)
                }
                else -> {}
            }
        }
    }

    /** One shared [Binder] for every file, as the production multi-file path binds. */
    private fun runLens(vararg files: Pair<String, String>): Pair<Recorder, List<SourceFile>> {
        val options = CompilerOptions()
        val binder = Binder(options)
        val sourceFiles = files.map { (name, text) -> Parser(text.trimIndent(), name).parse() }
        val results = sourceFiles.map { binder.bind(it) }
        val recorder = Recorder()
        Checker(options, results, isMultiFileSource = true, checkedSink = recorder)
        return recorder to sourceFiles
    }

    private fun declarationNamed(file: SourceFile, name: String): Node {
        var found: Node? = null
        fun walk(node: Node) {
            when (node) {
                is InterfaceDeclaration -> if (node.name.text == name) found = node
                is ClassDeclaration -> if (node.name?.text == name) found = node
                else -> {}
            }
            forEachChild(node) { walk(it) }
        }
        walk(file)
        return found!!
    }

    @Test
    fun `the lens answers an annotation through a namespace import inside an ambient block`() {
        val (recorder, files) = runLens(
            "/proj/stream.d.ts" to streamDts,
            "/proj/net.d.ts" to netDts,
            "/proj/ann.d.ts" to annDts,
        )
        val (stream, net, _) = files
        val holderS = recorder.annotationDecl["Holder.s"] === declarationNamed(net, "Socket")
        val holderOpts = recorder.annotationDecl["Holder.opts"] === declarationNamed(net, "ServerOpts")
        val holderD = recorder.annotationDecl["Holder.d"] === declarationNamed(stream, "Duplex")
        val wrapM = recorder.annotationDecl["Wrap.m"] === declarationNamed(net, "Server")
        assert(holderS)
        assert(holderOpts)
        assert(holderD)
        assert(wrapM)
        val asked = "Missing.x" in recorder.seen
        val missing = recorder.annotationDecl["Missing.x"]
        assert(asked)
        assert(missing == null)
    }

    @Test
    fun `the lens answers a named-import base inside an ambient block and a declare global bare base`() {
        val (recorder, files) = runLens(
            "/proj/globals.d.ts" to globalsDts,
            "/proj/async_hooks.d.ts" to asyncHooksDts,
            "/proj/events.d.ts" to eventsDts,
            "/proj/stream.d.ts" to cStreamDts,
            "/proj/child_process.d.ts" to childProcessDts,
            "/proj/process.d.ts" to processDts,
        )
        val globals = files[0]
        val asyncHooks = files[1]
        val events = files[2]
        val stream = files[3]
        val childProcessExtendsEmitter = recorder.heritageBaseDecl["ChildProcess"] === declarationNamed(events, "EventEmitter")
        val subExtendsReadable = recorder.heritageBaseDecl["Sub"] === declarationNamed(stream, "Readable")
        val eearExtendsAsyncResource = recorder.heritageBaseDecl["EEAR"] === declarationNamed(asyncHooks, "AsyncResource")
        val processEnvExtendsDict = recorder.heritageBaseDecl["ProcessEnv"] === declarationNamed(globals, "Dict")
        val timerExtendsRefCounted = recorder.heritageBaseDecl["Timer"] === declarationNamed(globals, "RefCounted")
        val processExtendsEmitter = recorder.heritageBaseDecl["Process"] === declarationNamed(events, "EventEmitter")
        assert(childProcessExtendsEmitter)
        assert(subExtendsReadable)
        assert(eearExtendsAsyncResource)
        assert(processEnvExtendsDict)
        assert(timerExtendsRefCounted)
        assert(processExtendsEmitter)
        // Negative controls: a named import of a name the chain does not declare
        // answers the ALIAS itself (its `aliasTarget` is null — the import binding
        // is all a consumer gets, as in tsc), and a bare base no file's global
        // namespace declares answers nothing.
        val asked = recorder.seen.containsAll(listOf("BadBase", "Orphan"))
        val badBase = recorder.heritageBaseDecl["BadBase"]
        val orphan = recorder.heritageBaseDecl["Orphan"]
        assert(asked)
        assert(badBase is ImportSpecifier)
        assert(orphan == null)
    }
}
