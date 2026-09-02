# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **191,070** lines (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: three
checker reads, one table write, stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

**(P18.9) — THE RxJS CORE RUNG COMPILES, THEN ITS CENSUS HALVES, THEN A PARSER DEFECT, THEN ALL 250 rxjs FILES AND typescript.d.ts COMPILE, 16,867 → 17,150 / 0 / 3 (2026-09-02).** (EXT.11a):
`rxjs@7.8.2`'s 15 `internal/` declaration files generate with zero checker diagnostics and
the generated Kotlin metadata-compiles (`KotlinExternalsRxjsGateTest`, verbatim,
Apache-2.0). Three compile errors, two mechanisms: interface CALL SIGNATURES (rendered as a
nameless method) are now function-type aliases — `public typealias UnaryFunction<T, R> =
(T) -> R`, and an empty interface over one is an alias to it
(`OperatorFunction<T, R> = UnaryFunction<Observable<T>, Observable<R>>`, transitively),
nameable but never a supertype; `typeof Action` (rendered as the un-instantiated instance
type, CHK.73) now refuses with a marker naming the written query, plus an ARITY GUARD in
the type mapper. One silent defect fixed: a function type's `this:` parameter rendered
positionally — now a Kotlin RECEIVER (`SchedulerAction<T>.(T) -> Unit`). New instrument:
`ExternalsLibraryProbe` (env-gated; generated Kotlin, compile errors, diagnostics, a marker
census per mechanism). 10 exact pins, each red by stash-ablation; externals 94/0. Census
after: 97 markers / 0 errors — nullable unions, `any`, arrays and literals are the next
rung; a `val plain: Plain` class-value defect queued as (CHK.73b). **(EXT.11b) LANDED next,
16,881 → 16,889 / 0 / 3:** nullable unions → `X?` (syntactic and resolved, one rule, composing
inside function types), `any`/`unknown` → `Any?` unmarked (keyed on the intrinsic NAME —
`Record<…>` resolves to the bare `anyType` here, so a resolved `any` the source did not spell
stays marked), arrays → `Array<T>` on lib evidence (a program's own `Array` refused: the
checker resolves `Array<X>` by NAME), rest parameters → `vararg`, literals widen; RxJS census
97 → 62 markers, externals 102/0. **(PARSE.1) LANDED, 16,889 → 16,904 / 0 / 3:** the whole-library probe
(all 250 rxjs files) found `export { from } from './x'` reporting TS1005/TS1141/TS1434 — the
export-specifier loop read `from` as the clause keyword where the import loop already asked
tsc's list-element predicate; one line, 15 pins, emitted JS byte-identical to tsgo on 13
shapes, cost_gate +0.00%; no pristine baseline covers a `from` specifier. The same probe's
37 Kotlin compile errors (overload equivalence, value-vs-type name collisions, a narrowed
`var` override) were then closed by **(EXT.11c), 16,904 → 16,920 / 0 / 3: all 250 files compile (37 → 0)** —
Kotlin's overload-equivalence relation MEASURED against the metadata compiler over ~100 pairs
and pinned (a free own type parameter erases to `Any?`, a pinned one keeps identity up to
renaming; the override relation is a DIFFERENT key — positional identity, exact nullability —
so `KotlinSignatureKeys.kt` ships two), value-vs-type name collisions a loud skip, a narrowed
`var` override rendered as the inherited type with a marker, inheritance read through the
supertype's type arguments (a renamed TP silently lost every `override` before); a 21-file
extras gate; externals 118/0. **(CHK.73b), 16,920 → 16,924 / 0 / 3:** a class-, enum- or
namespace-valued export (`export const plain = Plain`, rendered as the INSTANCE type before) is
a loud skip through `heritageBaseSymbol` (`resolveName` cannot follow an import alias — measured);
externals 122/0. **(EXT.12), 16,924 → 16,928 / 0 / 3:** an overload equivalence class keeps its
LEAST-MARKED member (ties first, every member in its declared slot, the marker naming the
survivor); rxjs 250-file collapses 49 → 49 with six cleaner survivors (`<T> of(value: T)`);
externals 126/0. **(EXT.13), 16,928 → 16,947 / 0 / 3 — THE LADDER IS GREEN AT EVERY RUNG:**
`typescript.d.ts` (one `declare namespace ts`, `export = ts`, 11,448 lines) generates 9,792
lines of Kotlin compiling at 0 errors — the root ambient namespace flattens to the surface,
nested namespaces are `external object`s, references by shortest spelling, inheritance by
qualified path; 5,422 declarations, 1,659 markers; it exposed chained-`var`/diamond/`val`-
narrowing override mechanisms (closed) and checker name-resolution defects inside namespace
bodies (worked around syntactically, queued (CHK.76)); externals 145/0. **(CHK.75), 16,947 → 16,995 / 0 / 3:** the ambient-initializer rule is now
tsc's `checkAmbientInitializer` at both emitters (a `readonly` property or unannotated const
with a literal/enum initializer is legal; a non-literal one is TS1254 even on a property) —
73-row matrix byte-identical to tsgo, 48 pins, cost_gate +0.00%, `typescript.d.ts` now 0
diagnostics. **(CHK.76), 16,995 → 17,017 / 0 / 3:** names inside a `declare namespace` body now
resolve as tsc resolves them at every non-walk resolver (a nested namespace was invisible to the
ambient stack, so a sibling class typed `any` and a shadowed `Node` resolved to the root's);
`lookupInEnclosingNamespaces` at seven sites, 22 pins (8 red by ablation), 8-profile grid
unchanged, cost_gate within tolerance; two corpus regressions found by the suite and closed (a
namespace class value on the static-access emitter, a pin walker double-emitting). **(EXT.14), 17,017 → 17,021 / 0 / 3:** the generator's
per-file syntactic resolver is retired now the lens answers; a program-wide fallback survives
for qualified names and `declare module` bodies, `typescript.d.ts` byte-identical, four checker
residues queued as (CHK.77); externals 149/0. **(EXT.15), 17,021 → 17,031 / 0 / 3:** index
signatures as an `operator fun get`/`set` pair (measured against the metadata compiler) and
parameter properties as explicit members; `typescript.d.ts`'s 7 signatures render; externals
159/0. **(EXT.16), 17,031 → 17,051 / 0 / 3 — THE LADDER ITEM IS CHECKED OFF:** module
wiring (`ModuleWiring(name, entry)`; the public surface through the re-export graph;
`@file:JsModule`/`@JsNonModule`/`@JsName`, the `export =` object bound without a rename);
rxjs's 291 re-export markers → 0 with 101 honest "not exported by the package entry" markers;
externals 179/0. Residue: a Kotlin/JS compile gate for the real output ((EXT.17),
build-file route owner-gated), `@JsName` renaming for Kotlin-refused collisions ((EXT.18)), the
four checker namespace residues ((CHK.77)). **(EXT.17), 17,051 → 17,073 / 0 / 3:** the REAL
output compiled as Kotlin/JS for the first time (a local gate driving `K2JSCompiler` with a
fetched stdlib klib; the CI wiring is a build-file change, BLOCKED-PENDING-USER): Kotlin/JS
prohibits receiver function types in externals AND the receiver form was semantically wrong —
now receiver-less with a marker; a class implementing an interface's function-typed property with
a method (or fewer overloads) owes loud `override`s; externals 201/0 with the klib. **(CHK.77), 17,073 → 17,085 / 0 / 3:** the four
namespace-resolution residues closed at the resolver (a fileless `declare module` body, a
type-only namespace as a dotted heritage head + ambient inheritance, a qualified
`typeReferenceSymbol`, cross-file namespace MERGING through the `globals` root) — each matching
tsgo row for row, 12 pins, 8-profile grid unchanged, cost_gate exit 0; `@types/node` now resolves
what was `any` and exposes an externals spelling rule ((EXT.19)) and two augmentation
divergences ((CHK.78)). **(EXT.19), 17,085 → 17,091 / 0 / 3:** `@types/node` (66 files)
metadata-compiles at 0 errors (86 → 0; the queued mechanism was wrong — an arity cascade from
an un-filled generic default, not a spelling): defaulted type arguments filled, name ownership
under first-wins refused loudly, inherited texts respelled per scope, the generic-vs-plain
override lift, namespace imports inside ambient modules resolved; heritage skips 137 → 113;
externals 207/0; (CHK.79) queued. **(EXT.20), 17,091 → 17,103 / 0 / 3:** an `export =`
target never vanishes and declaration merging renders per tsgo's measured surface (class +
interface + namespace → one class with companion and nested types; `@types/node`'s
`EventEmitter`/`Stream`/`Module`/`Stats` are classes now, 0 metadata errors, 0 vanished
targets); externals 219/0; the one-generation-per-module design is (EXT.21). **(EXT.18), 17,103 → 17,113 / 0 / 3:**
collisions Kotlin refuses rename under wiring (`AjaxErrorValue`/`FooFn` bound by `@JsName`,
measured against Kotlin/JS): rxjs 9 → 0 skips, `@types/node` 48 → 0; the probe now JS-compiles
the real output and found 23 pre-existing superclass-call errors ((EXT.22)); externals 229/0. **(EXT.22), 17,113 → 17,117 / 0 / 3:**
the queued mechanism refuted by 70 measured rows — an external class may never spell a
superclass call; a class over a NESTED base with a required parameter renders a SECONDARY
constructor instead; `@types/node`'s real output is at 0 Kotlin/JS errors; externals 233/0;
the `= definedExternally` optionality rung is (EXT.23). **(EXT.23), 17,117 → 17,126 / 0 / 3:**
optional parameters render `= definedExternally` (90 measured rows; an override inherits the
default, a nested class with two direct declarers of one default is refused and handled);
rxjs/`@types/node`/`typescript.d.ts` at 0 errors in both compilers; externals 242/0.
**(CHK.79), 17,126 → 17,135 / 0 / 3:** a dotted heritage base whose head is a namespace import
inside an ambient block resolves through the target module's surface (`export =`/`export *`
chains, fileless carriers only); 9 pins, grid unchanged; the generator's syntactic route for
it retired (40 `@types/node` bases were carried by it); (EXT.21) BLOCKED-PENDING-USER on the package-naming scheme. **(CHK.80), 17,135 → 17,150 / 0 / 3:**
annotations through a block's namespace-import alias, TS2339 at a missing namespace member in
a heritage clause (tsgo's exact `typeof import("node:net")` wording), named-import and
`declare global` heritage heads, and the script-local carrier merge that let an unrelated
file's `import * as net` hijack `require("net")` — all matching tsgo; 19 pins, grid unchanged;
`@types/node` heritage refusals 95 → 82; (CHK.81) queued.

**(P18.8) — STAGE 2 OF THE INVERSION LANDS: THE POST-HOC TYPE ORACLE; THEN THE EXTERNALS ALIAS-REFERENCE RUNG, 16,838 → 16,867 / 0 / 3 (2026-09-02).**
**(INV.2)** (owner-approved this session): `TypeOracle` over the (INV.1) store + retained
graph + live checker — `typeAt` / `symbolAt` / `resolvedCallAt` / `contextualTypeAt` /
`typeOfSymbolAt` recorded during the walk, the bin-A rows forwarded at rest, `resolveName` /
`symbolsInScope` refused naming Stage 3, per-build handles, `close()` on edit; entries
`typeOracleOf(files)` and `ProjectCompiler.build(…, oracleHolder)`; the store grew
`symbols` / `calls` / `contextual`; per-row divergences in `docs/type-oracle.md`; 23 pins;
cost_gate +0.00 %. **Flag ON measured: compiler profile +21.5 % (1.90 µs per recorded
expression), many-small-2400-dom +6-7 % (0.95 µs)** — after the first arm read +57-64 % and
a per-channel attribution + JFR found the object-literal KEY leg re-typing its literal per
key (`getTypeOfExpression` has no per-node memo; O(keys²) on tsc's message tables), fixed by
reading the store. (INV.2b) queued: `Project` integration with the invalidation decided.
Design record: `docs/INVERSION-DESIGN.md` § 9b. **(EXT.10)**: references to a generated
alias render by NAME where the resolved body has no Kotlin spelling (`Handler<string>` →
`Handler<String>`; function-typed aliases now emitted and named) under identity evidence
through the new lens member `typeReferenceSymbol`; Dukat pin kept; 7 pins, externals 80/0.
**(INV.1b)** answered: a reconstruction-only arm (`nodeAnswers:reconstruction`) reads the
plain check (5,290 / 5,266 vs 5,270 ms) while types-only reads 6,158 / 6,121 — the whole
1.45 µs per expression is `getTypeOfExpression` re-typing what the walk already typed.

**(P18.7) — TWO OWNER DECISIONS LAND: THE POM LICENCE AND STAGE 1 OF THE INVERSION, 16,828 → 16,838 / 0 / 3 (2026-09-02).**
(LIC.2) the root POM's `licenses` block now declares `AGPL-3.0-only WITH
LicenseRef-xtsc-output-exception` plus a second entry for the Output Exception (was
Apache-2.0; verified on the generated core JVM POM). **(INV.1) the per-file node-answer
store** (`NodeAnswerStore`, `Type` slots by `nodeId`, filled at the capture/sink hook under
the reconstructed ambient, first-wins, refusal before resolution), OFF by default behind a
`Checker` parameter / `--nodeAnswers`; 10 pins incl. the round-911 positive control (body
local `number` recorded vs `string` post-hoc) and the production-mode computation count at
0; cost_gate +0.00%, huge_methods clean, warm A/B flag-off NOISE-DOMINATED (3 rotated
pairs, sd < 1%); **flag ON measured: +14.9 % warm on the compiler profile (1.34 µs per
recorded expression, 598,455 of them) and +10.3 % on many-small-2400-dom (1.49 µs,
232,106)** — per-node, attributed next by (INV.1b). (INV.2) Stage 2 queued
BLOCKED-PENDING-USER. Design record: `docs/INVERSION-DESIGN.md` § 9a.

**(P18.6) — SESSION CLOSE, FIVE LANDINGS, 16,803 → 16,828 / 0 / 3 (2026-09-02).** (EXT.7) the **smol-toml rung is GREEN**: the
externals generator goes MULTI-FILE (`generateKotlinExternals(List<SourceFileEntry>)`, one
Binder + one Checker, cross-file by-name rendering, cross-file type-name collisions a loud
skip), top-level overloads render (implementation signature omitted, duplicates collapsed),
`#private` omitted, heritage markers name the base, export wiring loud (`export {}` silent);
`KotlinExternalsSmolTomlGateTest` embeds the verbatim seven `smol-toml@1.7.1` files and
metadata-compiles the output with zero checker diagnostics (externals 64/0; full suite
16,815/0/3). (TEST.1) the "order-sensitive" `ProjectTrustedFilesystemTest` control was a
DATA RACE in the test's own `CountingVfs` under the crawl's 16 concurrent readers (old
wrapper: 12,880 of 16,000 threaded reads counted); atomics + a CAS-swapped per-path map,
`CountingVfsConcurrencyTest` reddens the old wrapper (full suite 16,816/0/3). (INV.0) step 3:
`TypeInstantiator` extracted (the instantiation seam, ~290 lines verbatim, `Checker.kt`
191,030 → 190,771; ledger row 3 with the first NON-none ambient surface; suite 16,819/0/3 byte-identical, cost_gate +0.00%, ab −0.81% NOISE-DOMINATED, JFR alloc unchanged, the 10 B hop `inline (hot)`). (EXT.8) heritage to GENERATED targets (supertypes, `override`/`open`,
inherited constructors, `open external class`, cross-file bases via the new lens member
`heritageBaseSymbol`; externals 70/0; full suite 16,825/0/3). (EXT.9) exported values (`val`/`var`, literal consts widened) and
accessor pairs as properties (externals 73/0; full suite 16,828/0/3).

**(P18.5) — DONE (2026-09-02).** Owner additions applied ((INV.0) merged with
receipt protocol, INVERSION-DESIGN § 10 cost-neutrality contract, approvals recorded,
shrinkage dashboard row); (LIC.3) CONTRIBUTING.md; (EXT.4) classes + enums landed
(externals 40/0 — `external class` with primary ctor + companion statics;
`sealed external interface` enums; `const enum` refused loudly; full suite 16,775/0/3);
(INV.0) STEP 1: `TypeInterner` extracted — first Stage-0 collaborator, ambient surface
NONE, suite 16,781/0/3 byte-identical, cost_gate +0.00%, wall NOISE-DOMINATED at +0.26%,
allocation profile unchanged, and the receipt protocol found the split IMPROVED hot
inlining (the 277 B monolith never hot-inlined; the 13 B hop + body both do);
(API.18) file-final token healed by an ownership descent (suite 16,791/0/3, the LSP
recorded-edge pin flipped to healed, punctuation-final files pinned conservative);
(EXT.5) generic aliases + generic methods + method overloads (externals 47/0);
(EXT.6) default exports + generic references to generated targets — **the mitt rung is
GREEN** (verbatim mitt@3.0.1 d.ts generates and metadata-compiles; externals 52/0);
(INV.0) step 2: `Relation`+`Ternary` relocated to `TypeRelationCache.kt` (suite 16,803/0/3).

