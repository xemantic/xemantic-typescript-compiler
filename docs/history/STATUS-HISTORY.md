**(P18.11) — PER-MODULE EXTERNALS GENERATION: 51 MODULES OF `@types/node` COMPILE TOGETHER, → 17,196 / 0 / 3 (2026-09-04).**

**(P18.13) — THE AUGMENTATION RESIDUES: A PACKAGE AUGMENTATION STOPS INVENTING TWO ERRORS, 17,224 → 17,236 / 0 / 3 (2026-09-04; filtered gates only — the full suite was not run this round, so the headline count stands at 17,224 / 0 / 3 plus 12 new pins).**
(CHK.82) three of four residues, each measured against tsgo 7.0.2 on a scratch project.
**(3) is the one a real project meets:** `declare module "some-pkg"` over an installed package
was a false **TS2664** *plus* a false **TS2339 on the package's own member** — ONE cause, since
no resolver leg can name a bare specifier's target (a `package.json` `types` entry, not a string
transformation), so `targetFile` was null and the merge took the FILELESS-AMBIENT branch,
publishing the block's PARTIAL interface into `globals` as a round-510 stub while never merging
into the real target. `augmentationTargetFile` is the one home for the ladder; its bare leg takes
the crawl's answer from any file and requires them to AGREE. **(1)** a name the BLOCK declares
and the target does not export typed `any` — now resolved, narrowed to exactly that case, which
is what keeps (CHK.76)'s +43 rows away. **(2)** `import { Brand } from "./types.js"` beside an
augmentation declaring `Brand` was a false TS2305 while the TYPE always resolved — a pure
absence-check defect, fixed by reading the binder's own block `exports` through the SAME
export-modifier predicate the merge uses (extracted, one home, two consumers). A B86.4 display
defect fell out beside them: the namespace-qualification ascent treated a STRING-named module's
specifier as a name segment, so `@types/node` rendered `events.DefaultEventMap`,
`zlib.CompressCallback` and even `node:test.test.SuiteFn` — with a colon in it.
**(4) REFUSED with the measurement:** the enum display `import("…").E` is NOT an augmentation
residue (it reproduces with no `declare module` in the program, under tsgo AND pristine 6.0.3),
103 corpus baselines use that form and we satisfy them through hard-coded pins rather than a
mechanism — a logical-parity conversation. **(CHK.81)'s two remaining sub-items MEASURED and
REFUSED, both mis-stated in the queue:** the missing diagnostic is TS2503 (not TS2304) and the
axis is `declare` (not `declare module`), suppressed deliberately by **B367**; and the
literal-union display is neither an alias nor an ambient question — the rule is *collapse to the
base primitive exactly when the target holds no literal of that base*, systematic across TS2322
and TS2345, gated only by the full corpus.
12 pins, eight single-mistake ablation arms each with its own red set; filtered
`*Augment*`/`*Module*`/`*Import*`/`*Export*` 1,468/0/0; cost_gate exit 0 (errors 46 unchanged);
huge_methods 0 over; the 8-profile grid all eight `added=0 removed=0`; externals 290/0; and the
`@types/node` per-module receipt with its generated Kotlin CODE **byte-identical** and its marker
text strictly better.

(EXT.21b): a generation is scoped to one `declare module` block plus its re-export closure
(`export * from`, `export = <require alias>`); a global declaration renders in every generation
unless the module shadows it; a reference to another module's declaration is spelled into that
module's Kotlin package under the same identity evidence, with first-wins naming keyed per
module. 51 generations, 51 packages, compiled TOGETHER at 0 metadata and 0 Kotlin/JS errors:
`Socket` in both `node.dgram` and `node.net`, `Module` in both `node.module` and `node.vm`,
`declared again by another file` 57 → 2 (the residue is global-vs-global and a `ts5.6/`
duplicate). rxjs, `typescript.d.ts` and the flattened control are byte-identical. 13 pins, eight
ablation arms with distinct red sets; externals 275/0. Cross-module HERITAGE is refused loudly
and queued as (EXT.24): admitting it measured 184 `hides member of supertype` + 27 `inherits
conflicting members`, because `Inheritance` is built over one generation.

**(P18.12) — CROSS-MODULE HERITAGE: THE 179 REFUSED SUPERTYPES OF `@types/node` BECOME SUPERTYPES, 17,196 → 17,224 / 0 / 3 (2026-09-04).**
**(CHK.78) landed beside it:** three augmentation divergences, the first far broader than the
item stated — `resolveModuleSpecifier` is not directory-aware, so on a REAL project every
relative SIDE-EFFECT import read a false TS2882 (the corpus is blind: flat names; tsc's own
sources have none); the crawl's own answer now suppresses it. A bare name inside an augmentation
block typing `any` was a LIB-collision axis (the INV.3(c)(iv) leg sat below the per-file consult),
which also fixed a precedence divergence against tsgo; the lens no longer answers the block's
partial interface. One guard measured redundant and removed; 13 pins, `@types/node` byte-identical,
grid unchanged; four residues queued as (CHK.82).
(EXT.24): a per-module SET is generated in ONE call (`generateKotlinExternalsPerModule`) in two
passes — pass 1 collects each module's frozen tree and lifts it into that module's Kotlin package,
pass 2 re-runs each generation with the others' lifted models in hand — with the `open`
attribution computed once over the whole lifted set and restated per generation, because a member
a subclass in another package overrides must be `open` and the owning generation cannot see that
for itself. On `@types/node` 20.19.43: heritage refusals **179 → 0**, cross-package references
283 → 468, `Socket extends stream.Duplex` renders, and the 51-module set still compiles TOGETHER
at **0 metadata and 0 Kotlin/JS errors** (the 184 `hides member of supertype` + 27 `inherits
conflicting members` (EXT.21b) measured are gone). Exactly one new, honest heritage marker
(`https.Server` would need two class bases). rxjs and `typescript.d.ts` byte-identical — a
generation produced ALONE keeps the (EXT.21b) refusal by construction. 9 pins + 6 gate cases,
eight single-mistake arms; externals 275 → 290 / 0. The trap it cost an hour to find: a lifted
package is NOT an ordinary scope, and modelling it as one makes `node:console`'s
`node.node.console` shadow the head `node` and silently empty the whole cross-module attribution.

**(P18.10) — THE CI HALF OF THE KOTLIN/JS GATE, THE README REPOSITIONING, AND THE EXTERNALS PACKAGE SCHEME MEASURED RATHER THAN PROPOSED, 17,150 → 17,169 / 0 / 3 (2026-09-03).**
Three owner decisions answered. **(EXT.17)**: the Kotlin/JS stdlib klib is now DECLARED by the
build (`dependencyScope` + `resolvable`, artifact-only `@klib` notation so no Kotlin/JS platform
attributes are needed and no wrong variant can be handed over) and passed to `jvmTest` as the
environment variable the gate reads — nothing enters a published artifact. Its ablation found a
second defect: with the path pointed at nothing the gate read **28 tests / 0 failures having
compiled nothing**, so `JsStdlib.locate` now splits an UNSET locator (a fact about the box —
skip) from a SET-but-missing one (a fact about the build — fail); ablated 28/0 green → **27 of
28 RED**. **(DOC.2)**: the approved README commit cherry-picked onto main unchanged, then a
second commit refreshed only what measurement changed (the ladder table at 0 Kotlin errors, the
`@types/node` flattening named as the open limit, 15,528 → 17,169, and the stale "the language
service is not incremental" bullet replaced by (LSP.3)'s numbers, said in tsgo's favour).
**(EXT.21a)**: the package scheme was MEASURED against both Kotlin compilers and **the queued
proposal was refuted** — a backtick rescues a hyphenated segment, a hard keyword and a
digit-first one and **nothing else** (`.`, `~`, `@`, `:`, `/` are illegal inside one), so `/`
`:` `.` are separators, npm's leading `@` is dropped, and any other character is refused loudly
with no `package` line rather than escaped into a file no compiler accepts;
`ModuleWiring.packageRoot` gives the kotlin-wrappers `node.fs` shape without hard-coding an
ecosystem. Every accepted package name is also QUALIFIED-referencable — the measurement that
makes per-module generation ((EXT.21b), queued) possible at all. Externals 242 → 261/0.

**(P18.9) — THE RxJS CORE RUNG COMPILES, THEN ITS CENSUS HALVES, THEN A PARSER DEFECT, THEN ALL 250 rxjs FILES AND typescript.d.ts COMPILE, 16,867 → 17,182 / 0 / 3 (2026-09-02).** (EXT.11a):
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
`@types/node` heritage refusals 95 → 82. **(CHK.81) PARTLY, → 17,182 / 0 / 3 combined with (P18.10)'s work:** a
`require` alias of an ambient block whose surface is `export = <value>` names that value, not
the carrier (five `@types/node` `extends EventEmitter` bases), with TS2694's carrier display,
the interface/annotation member reports, the false TS2833 gone and TS2305/TS2616 for a named
import absent from the surface; the implementing agent was rate-limited before any gate and the
verification found a lost diagnostic on real `@types/node` that only the probe's rendered types
could see, fixed in 11 lines; 13 pins all discriminating; three sub-items still open.


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

**(P18.4) — SESSION CLOSE: THE PHASE 18 FIRST ARC IS LANDED END-TO-END, 16,764 / 0 / 3
(2026-09-01).** In one session under the re-pointing directive: the directive persisted;
licence strings aligned ((LIC.1), with (LIC.2) POM drift flagged BLOCKED-PENDING-USER); the
tsgo comparison made honest ((DOC.1)) and then MEASURED against the right tsgo ((LSP.3));
README repositioned on `docs/reposition` ((DOC.2), awaiting owner review); the 142-method
census written ((INV.D): A=94 / B=15 / C=33, (INV.1) proposal BLOCKED-PENDING-USER); the
externals generator through THREE rungs ((EXT.1-3): interfaces, generics, references,
typealiases, functions, function types — 29 pins, zero-classpath metadata compile gate);
the LSP server feature-complete for a first release ((LSP.1-2): 58 pins, nativeImage
wired); (API.18) honestly refused twice with the mechanism recorded. `cost_gate.py`: every
counter unchanged all session — the INC-closure directive holding by construction. Next
top items: (EXT.4…n) ladder, (INV.0) split (Stage 0 of the inversion), (API.18)'s
sibling-bound descent.


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

**(P18.3) — THE LSP IS FEATURE-COMPLETE FOR A FIRST RELEASE, AND THE HONEST tsgo NUMBER IS
30-50x AGAINST US (2026-09-01).** (LSP.2): the full feature map onto `Project` — lifecycle,
navigation, completion, signatureHelp, rename-with-refusals-as-errors, pull diagnostics,
PROJECT-WIDE publishDiagnostics off the narrowed `diagnostics()` — 16 new pins (module 58/0,
warning-clean), nativeImage task wired (build needs a GraalVM host). (LSP.3), both servers
long-lived on tsc's 78 sources: tsgo `--lsp` answers a per-edit hover in **12-18 ms** where we
take **398-630** (their lazy NodeLinks answering vs our narrowed-build-per-question —
`docs/INVERSION-DESIGN.md`'s bin-B gap measured end-to-end); first open **255 ms vs 24.8 s**
(different work: we eagerly publish the whole 46-row project error list, which their LSP
cannot do at all — our wave: **524 ms, 5 files, exactly 46 rows**). Receipts caught the
46-vs-65 gap per-file. Published in `docs/perf/incremental-vs-tsgo.md` (LSP arm) + § 3b.
Suite unchanged **16,734 / 0 / 3** plus the 16 new LSP pins → next count on the full run.

**(P18.1/P18.2) — THE DOC ARC, THE 142-METHOD CENSUS, AND THE FIRST TWO PHASE-18 CONSUMERS
(2026-09-01).** (LIC.1)/(DOC.1)/(DOC.2 on `docs/reposition`)/(INV.D) landed in the main
context; then ONE two-agent worktree wave landed **(EXT.1)** — Kotlin externals from the
CHECKED program, alias-resolution pin `Species`->`String`, metadata-compile gate with a
negative control, 15 pins — and **(LSP.1)** — JSON-RPC/LSP over `Project`, initialize +
didOpen + hover, **LSP UTF-16 = Project offsets CONFIRMED identical modulo the 1-base** at
an astral-char pin, 42 pins. `docs/INVERSION-DESIGN.md` answers the WebStorm question:
of tsgo's 142 API methods, **A=94 answerable post-hoc today, B=15 walk-scoped (13 closable
by a record-during-walk NodeLinks store — (INV.1) proposal BLOCKED-PENDING-USER), C=33 not
checker questions**; on-demand flow is NOT required for the census. **The LSP's first
fixture found a `-project` defect ((API.18): a file-final token is unreachable without a
trailing newline)** — the mission thesis demonstrating itself: a new consumer finds what
the corpus structurally cannot. Also queued: (LIC.2) the root POM says Apache-2.0
(BLOCKED-PENDING-USER, build file). Suite on the
merged tree: **16,734 / 0 failures / 3 skipped** (+57: 15 externals + 42 lsp);
`cost_gate.py` exit 0, every counter +0.00% (the new modules move nothing — the control
passes); `huge_methods.py --fail-over 0` clean (core-only census: a CONTROL for the new
modules, per its own gotcha).

**(P18.0) — THE PROJECT IS RE-POINTED: TYPESCRIPT FOR THE JVM AND KOTLIN (owner directive
2026-09-01).** The WebStorm evaluation paused — their need was a post-hoc TYPE ORACLE (the
query shape of tsgo's `tsc/internal/api/proto.go`, 142 methods) and this checker's answers are
functions of walk-scoped state; tsgo is the free official default, so "a TypeScript compiler"
is not the mission. **The mission: no Node and no Go in the toolchain; an embeddable
whole-program checker (`Project`); a Kotlin externals generator with resolved types (the
Dukat/Karakum gap); the KIR JVM bytecode backend; an LSP anyone can try in five minutes.**
The directive is persisted in CLAUDE.md § "AI agent mission", the WORK ORDER at the top of the
PLAN-PHASE-5.md QUEUE (new items (LIC.1) (DOC.1) (DOC.2) (EXT.1…n) (LSP.1…n) (INV.D) (INV.0)),
and SESSION-PROMPT.md, so run-loop iterations cannot revert to the old mission. **The (INC.\*)
latency family is CLOSED** at a 94-110 ms incremental floor / 93-217 ms plugin query — further
INC rounds are REFUSED unless a plugin-facing query measures > 300 ms warm. Suite unchanged:
**16,677 / 0 failures / 3 skipped** (doc-only commit).

**(INC.91) — THE REOPENED CLOSURE, CENSUSED THE SAME DAY AND REFUSED ON SOUNDNESS
(2026-09-01).** (INC.90) reopened the reverse-dependency closure on a 12.7x measurement; this
census refuses the PROPOSAL without touching that number. Counts, two reproducing runs.
**THREE FRAMINGS REFUTED, INCLUDING TWO OF MY OWN.** The transitive importer closure of a
`layer00` module is **187 of 2,401 files (7.8%)**, not "most of the program" — fan-out is ~4
per hop. The offset-sensitivity worry (foreign types keyed by `(fileName, pos, end)`) is REAL
(`Checker.kt:57089`) and costs exactly **ONE extra hop**: 1 fingerprint moved for an
append-at-END edit, **3** for the identical edit at the TOP, **0 beyond hop 2**.
**THE BLOCKER WAS IN THE FINGERPRINT'S OWN KDoc THE WHOLE TIME** (`:57050`): (INC.47)'s
file-boundary cut gives up TRANSITIVITY, and `incrementalDiagnostics` is sound BECAUSE a moved
signature anywhere falls back. The proposal kept the signal and deleted the fallback.
**Refuting number: on a length-preserving three-file edit the only error is at HOP 2, hop 1 is
silent in every channel, and the walk reports 0 rows where the truth is 1** — a missing
diagnostic. tsgo answers 1/1 and its own hop-1 `.d.ts` is unchanged too, so the feature is
achievable but its soundness cannot come from a signature.
**WHAT SURVIVES IS MOST OF THE WIN:** narrow a signature edit to the transitive importer
CLOSURE (`Result.importEdges`, already computed), splice the rest, use the fingerprint for
nothing — **2,401 -> 187 files (12.8x)**, sound because a superset always is, degrading to
today's behaviour on barrels. The remaining 187 -> 4-8 needs a TRANSITIVE signature, a larger
item than the walk. **The method is the reusable part: one probe runner, no wall clock, and it
killed a design with a real 12.7x measurement behind it — a prize being real is not evidence
that a mechanism for collecting it is sound.**

**(INC.90) — THE tsgo INCREMENTAL COMPARISON RE-TAKEN ON A SECOND ARM THAT IS FINALLY
LIKE-FOR-LIKE, AND THE SIGNATURE CLIFF REOPENS (INC.35) (2026-09-01).** Every tsgo incremental
number this repo had published came from ONE arm — tsc's 78 huge barrel-exporting sources,
where we report **46** rows against tsgo's **65**. New arm: `many-small-2400-dom`, 2,401 files
in 48 layers, edited at `layer00` (deepest-dependency worst case), where **both compilers
report the identical single row**, so the equivalence gate this comparison always lacked
passes exactly.
**ARM A DID NOT MOVE** (ours 5,523 warm / 226 body / 5,578 signature against the recorded
5,352 / 232 / 5,694) — expected, since the ~25 (INC.\*) rounds since removed per-FILE costs
and that profile has 78 files.
**ARM B IS THE FINDING.** (INC.35) closed the reverse-dependency closure on tsc's sources, and
Arm A corroborates it FOR TSGO TOO (signature edit **1,695 ms against its own 1,667 cold** —
its pruning recovers nothing). On LAYERED code the same mechanism is worth almost everything:
tsgo **304 ms against its own 427 cold**, i.e. a signature edit costs it what a body edit costs
(297), where we rebuild at **3,850**. **12.7x wall, ~96x marginal — the largest gap ever
measured here, and the only one with a named mechanism on the other side.** Queued (INC.91).
**BOTH NUMBERS, BECAUSE ONE GETS IT WRONG:** on the wall we answer a body edit in **137 ms
against 297** and a no-op in **0 against 264**; but tsgo's floor is 89% of its own body cell,
so its MARGINAL body cost is ~33 ms against our ~137. We win the wall on the live-session
model; they win the compute on a real invalidation algorithm.
**THE PLUGIN'S OWN CALL IS IMMUNE TO THE CLIFF** — `incrementalDiagnostics()` is reached from
`diagnostics()` and nowhere else, while the plugin asks `diagnosticsOf` exclusively (narrows at
the SOURCE): **93-106 ms on Arm B, 187-217 on Arm A, independent of edit shape**, corroborating
(INC.86)'s 90 ms per-keystroke figure.
**THREE HARNESS DEFECTS, AND THE FIRST IS THE ONE TO REMEMBER:** the inherited fixture's
`orig.ts` was CRLF while both edit variants were LF, so every "one-line edit" was that line plus
a 3,916-line newline normalisation; the tsgo harness read its row count out of a subshell and
printed stale values; both harnesses were hardcoded to `binder.ts` and to scratchpad paths that
survived by luck. All three fixed, plus two receipts the old runner could not print (a per-cell
row count, and served-vs-fell-back from `Project.incrementalAnswers` — both arms read body 3/3,
signature 0/3).
**AND CLAUDE.md's ROUND-938 CLAIM IS FALSE:** pristine `typescript@6.0.3` IS runnable here and
agrees with tsgo on all 65 rows, so the gap is **19 genuine false negatives of ours, 0 tsgo
divergences** — but 18 of 19 are emission-side on work already done, so it is not a 29% work
gap. `docs/perf/tsgo-diagnostic-gap.md` (new), `docs/perf/incremental-vs-tsgo.md` (rewritten).
Suite **16,677 / 0 failures / 3 skipped**.

**(INC.89) — THREE INHERITED REFUSALS RE-DERIVED, ONE PLUGIN-FACING API MEMBER PINNED, ONE
SPLIT LANDED (2026-09-01).** (INC.88) left a standing instruction — "anything larger needs the
refusals re-derived rather than inherited" — and the first half of this round is that, on
reading alone. Fresh baseline, TWO processes ((INC.52)): WALL **102/106 ms**, init block
**40.0/48.2**, head `init:buildFileLocalTypeMaps` **21.4/15.3**, `init:buildPerFileScopes`
**5.7/5.5**, `init:computeAllEnumValues` **4.8/4.5**.
**THE THREE ANSWERS DIFFER FROM EACH OTHER, WHICH IS THE ARGUMENT FOR DOING IT.**
`buildFileLocalTypeMaps` is **CONFIRMED** — it is partition-scoped already, builds ONE file's
map (`eagerBuilds=1`), and the ms is that file's first real type-resolution cascade; do not
re-open it from its size. The two un-Boyer-Moore-able whole-program regexes in
`collectUmdGlobalsAndModuleFiles` are **already censused honestly** ("0 ms — 0 `.d.ts` files.
LATENT on a `@types` tree"). And `isModuleFile`, recomputed **≥5 times per build** across the
init setup block, is **REFUTED as a lever with no build at all**: it early-returns on the FIRST
import/export, which every file of a module-shaped project has. A repetition count is not a
cost — round 801's law one predicate over.
**(a) `Project.reloadFile` AND `OverlayVfs.revert` ARE NOW PINNED** — the API (INC.75) tells the
IntelliJ plugin to adopt, documented as a first-class third change kind and present in the suite
only as a step inside two `trustFilesystem` tests, with its implementation half unpinned
entirely. 17 pins, no production code changed. **TWO ablations, because one cannot grade both
halves**: emptying `reloadFile` reddens 7; emptying `revert` reddens 12 — all 7 revert pins plus
the 5 reload VALUE pins, cross-validating that reload's promises flow through `revert`. A doc
edge was pinned rather than waved through: for a file existing ONLY in the overlay, "what is on
disk is the truth" means **ABSENCE**, so reload removes it.
**(b) THE (INC.20) SPLIT FOR `checkCrossFileUseBeforeDeclaration`** — its emitter walked all
2,401 files to produce rows `getDiagnostics()` then dropped, because the diagnostic is anchored
at the USE file and the partition filter discards the rest. **The ordinal invariant is the whole
risk**: the verdict compares `decl.fileIdx > useFileIdx`, both ordinals of `binderResults`, so
re-heading on `checkedResults.withIndex()` renumbers `useFileIdx` to ~0 and flips the verdict
toward FALSE POSITIVES silently. The head therefore stays `binderResults.withIndex()` and the
partition is a `continue` AFTER the enumeration. Receipt is a COUNT, never a millisecond.
Ablation reddens **pin 6 only**, and the other six are recorded as structural rather than
claimed as coverage.
**(d) THE BIGGEST PLUGIN-FACING LATENCY ITEM ON THE PAGE IS NOT A DEFECT WORTH FIXING — IT IS
THE FLOOR.** `docs/language-service.md` § 13's "one open defect" was that
`completionsAt`/`signatureHelpAt` cannot reach a prepared check (207 ms after `prepare(6)`
against 194 cold). Both refusals standing in front of it were re-derived at HEAD and **both
hold; (INC.33) is FIRMER than when written** — break-even **1.40 -> 1.52** and **12.1 -> 12.9**,
*because* the floor arc cut the base while per-anchor capture did not; retention unchanged to
the digit (**54.4 M** records for one widened `checker.ts` entry). The queue's own named
successor, the PREPARE-AMORTISED case, is **REFUTED BY MECHANISM**: the typed `.` must reach
`updateFile` or the completion anchor is computed from stale text, and `updateFile` does
`captures.clear(); prepared = null`, so the dominant completion is invoked at a state nothing
can have prepared. **And the prize it assumes does not exist** — `member.caret` costs what
`base.noCapture` costs (224 vs 254 ms; 2,035 vs 2,189), so the ~200 ms **is one narrowed
build**: completion latency IS the incremental floor. § 13 now cites BY SYMBOL after its line
numbers rotted a third time in a day.
**GATES.** Suite **16,677 / 0 / 3** (+24, all this round's pins); `cost_gate.py` exit 0, every
counter +0.00%, `output.errors` 46; `huge_methods.py --fail-over 0` clean; **`partition-gate.sh
sensitivity` EQUIVALENT on all 76 files across 78 netting passes, 72 carrying rows** — the arm
that can see a starved partition. `cost_gate` and the corpus are CONTROLS here, not coverage.

**(INC.88) — THE ROOT-FILE GLOB IS REFUSED, AND THE SPLIT IS WHAT EARNS IT (2026-09-01).**
Re-decomposing after (INC.87)(a) put the glob SECOND at **9.95 ms of a 95-100 ms query**, behind
an init block that is 48.8 and largely refused. Closed in both available directions.
**DIRECTION 1, memoizing the glob across builds under `trustFilesystem`, is refused by a promise
this compiler already SHIPS:** that KDoc says "ADDED and REMOVED files are still discovered from
the backing store on every build. **Nothing about the file SET is taken on trust**", two pins
state it, `OverlayVfs` and `docs/language-service.md` repeat it, and (INC.65) refused the same
shape one layer down. (INC.60)'s policy — a no-promise fix outranks a promise-costing one — is
why the other half was measured first.
**DIRECTION 2 WAS A REAL HYPOTHESIS AND IS REFUTED.** (INC.77) priced this row's syscall half at
~1.8 us/entry and called the residue irreducible — measured over `SystemVfs` ALONE, where the
shipped path is `OverlayVfs` wrapping it plus a per-directory sort, and the row reads 3.4-3.8
us/entry. Two new sub-rows closing against the SAME open timestamp:
`listEntries + sort 8.431 ms` = **sort 0.483 (5.7%)** + **OverlayVfs merge 0.752 (8.9%)** +
**the BACKING STORE's listing 7.196 (85.4%)**. That 85% is `File.listFiles()` plus one `stat`
per entry, and Java exposes no `d_type`, so one syscall per entry is a floor. **(INC.77) is
CONFIRMED on the shipped path** and both wrappers together are 1.2 ms of 8.4.
**WHAT LANDS IS THE INSTRUMENT, NOT A FIX** — the rows are inline no-ops when the probe is off,
so the refusal is reproducible instead of a claim in a note, which matters because the refusal
they confirm had been quoted for three rounds without ever being checked on the path it
described.
**GATES.** Suite **16,653 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean.

**(INC.87)(a) — THE POST-CHECKER'S FILTER ROW IS 4.5 ms OF A KEYSTROKE AND 89% OF IT ANSWERS
NOTHING; SPLITTING IT REFUTED ITS OWN SHAPE (2026-09-01).** (INC.86)(a) named
`post-check diagnostic filters` — 4.22 ms of a 90 ms query and a row NO queue item had ever
named. Split into three abutting sub-rows first, per (INC.65): **POST_DIAGS 4.507 -> 0.508 ms**,
of which **TS2688+TS2209+isolatedDecls 3.296 -> 0.492**, the **`modulePreserve4` whole-program
text scan 1.184 -> ABSENT (`calls` 1 -> 0)**, and the parse-cascade `removeAll` chain 0.0022 ->
0.0017 as the untouched control. The three summed to 99.4% of the row.
**THE OBVIOUS CANDIDATE WAS THE SMALLER MEMBER.** Reading the region, the eye lands on one
unconditional whole-program TEXT scan sitting above the guard that is its only consumer — real,
and 26%. The other 73% was `checkMissingTypesReferenceExports`' package.json pass, rooted at an
alternation `(?:^|/)`, so `BnM.optimize` gives it no literal and it is attempted at EVERY
POSITION of every file NAME — on a fixture with no `node_modules` at all, i.e. wholly to answer
NO. Pre-gated on `endsWith("/package.json")`, EXACT because the pattern's own tail anchors
there, regex kept live as the decider (round 792). The text scan is deferred behind a `lazy`
with the cheap basename test moved in front of it — and it is paid TWICE per keystroke in the
shipped design, since the (INC.17) recheck re-runs that very lambda.
**NO WALL IS CLAIMED AND THE SAME RUN SAYS WHY:** WALL read 108 -> 88 ms while `initNanos` read
**51.5 -> 77.9** on untouched code. One `--passTiming` draw is not a measurement ((INC.52)) and
the query wall carries (INC.72)'s ±20 ms term. The receipt is a COUNT — the bracket lives INSIDE
the `lazy`, so `calls == 0` IS the statement that the scan never ran.
**BOTH PIN CLASSES WENT RED FIRST, BOTH INSTRUCTIVELY.** `ProjectCompiler` never puts a
`package.json` into the program at all, so the TS2688 pin had to move to the multi-file harness
— as an absence assertion it would have been green forever; and the count pin's fixture was
named `b.ts`/`c.ts`, two of the twelve `modulePreserve4` basenames, so the scan correctly ran.
That collision is now the POSITIVE control (round 790).
**REFUSED, not shipped:** `init:evolvingArrayUseSiteWalks` (1.835 ms, five throwaway
collections per file) — a rewrite was built and REVERTED, unpriceable and unpinnable locally.

**(INC.81) — A LIST PER KEY FOR 9,401 KEYS THAT NEVER GOT A SECOND ENTRY, AND A REFUTED
ROUND-471 HYPOTHESIS (2026-08-31).** `Checker.enclosingImportIndex` is **4.7 ms** of an 87 ms
per-keystroke query and NO queue item had ever named it — it surfaced only from re-taking the
ranking after (INC.78)/(INC.79)/(INC.80), which is what (INC.57)'s law asks for.
**CENSUSED BEFORE ANYTHING WAS DESIGNED:** the build inserts **9,401 specifiers under 9,401
DISTINCT keys**, so every `getOrPut` misses and every one allocated a `MutableList` and a
`Pair` — and **`multiFileKeys=0`**, i.e. not one key is reached from two files, so the
whole-program structural reach matches nothing on a real project.
**THE OBVIOUS HYPOTHESIS WAS MEASURED AND REFUTED.** The key is an AST *data class*, so
`hashCode` recurses both Identifiers and both comment lists (round 471). Priced in ONE
timestamp pair over a second pass: **76.5 ns each, 0.72 ms — 14% of the row**. The walk is
~1.0 ms and **~3.4 ms is the insert plus the two allocations**. So the key is left alone (its
structural semantics are load-bearing) and only the REPRESENTATION changed: the `Pair` itself
for the one-entry case, promoted to a list on a second claim, map presized.
**MEASURED** with two class dirs differing only in this, rotated across processes: **4.60 ->
3.17 ms**, after winning 3/3 batches in both directions with NON-OVERLAPPING ranges, and the
population census identical in both arms.
**THE PIN EXISTS BECAUSE THE CENSUS SAYS NOTHING REACHES THE PROMOTION** — `multiFileKeys=0`
is precisely that statement — so it takes a fixture, and two BYTE-IDENTICAL importers are one
(`ImportSpecifier`'s components include `pos`/`end`, so the same import at the same offsets in
two files IS one key). Two ablation arms, each reddening a DIFFERENT pin.
**GATES.** Suite **16,624 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean.
**RESIDUE REFUSED WITH REASONS:** the walk IS the index's definition, and the hash cannot move
without changing a key whose structural semantics the replaced scan fixes.

**(INC.80) — JOINING A PATH BY ARITHMETIC, AND THE TWO-DRAW READ THAT NEARLY REFUTED IT
(2026-08-31).** `PathUtil.join(base, part)` built `"$base/$part"` and normalized it — and for
a module specifier that is exactly the case `isNormalized` must refuse (a `..` segment), so
(INC.68)'s fast path could never help it and the general body allocates a `split` list, a
`String` per segment, an `ArrayDeque` and a `joinToString` builder: **3.4-4.1 ms over 4,701
calls** in the crawl's specifier resolution. Counting the leading `..`, dropping that many
segments off the base with `lastIndexOf` and concatenating is **131-136 ns** — priced as a
probe arm and checked against the general body on all 4,701 real pairs BEFORE it was built.
**THE MEASUREMENT IS THE PART WORTH READING.** Two draws of the row said NOTHING (6.26/7.22
before, 6.22/7.45 after) and the refutation was already being written. **Six draws per arm,
ROTATED ACROSS PROCESSES over two class dirs differing only in this file: 6.41 -> 4.95 ms at
the median, the after arm winning in ALL THREE batches and BOTH rotation directions.**
(INC.68)'s law bites in this direction too — an unrotated pair cannot see a 23% change in the
very row it measures.
**AND THE FIRST EXPLANATION WAS REFUTED RATHER THAN ASSUMED:** the natural story (the
allocating arm pays GC the build never pays, round 801) is wrong — with a 2 GB young gen the
allocating arm got SLOWER (873 -> 1,264 ns) and so did the arithmetic one (131 -> 257), 20
young pauses in the whole process.
**RECEIPTS:** `pathNormalizeCalls` **11,935 -> 9,577** and every remaining call takes the
already-normalized path — a floor build performs **ZERO allocating normalizations, down from
2,358**.
**PINS** are a DIFFERENTIAL against the general body over a 12-base x 25-part grid ((CFG.1): a
wrong join names a different FILE and nothing here notices). **It caught its own defect on the
first run** — joining at the ROOT spelled `//dep`, a base the 4,701-pair fixture population
does not contain and the adversarial grid does. Four ablation arms; the no-fast-path arm
reddens ONLY the regime pin.
**GATES.** Suite **16,622 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean.
**SUCCESSOR:** `dirname` + the memo key at **~1.5 ms over 4,701 calls** — the crawl loop knows
the importer's directory once per FILE and re-derives it per SPECIFIER.

**(INC.79) — THE CRAWL ASKED THE FILESYSTEM ABOUT FILES THE GLOB HAD ALREADY LISTED
(2026-08-31).** (INC.73)(a) refused this row's syscall half by arithmetic — "2,351 distinct
resolutions at exactly one `exists` each, so ~2.6 ms is irreducible". **That is true of the
resolver in isolation and false of the BUILD**: the root-file glob has already listed every
directory of the project and proved which files are there, off the same `Vfs`, ~20 ms earlier
in the same build. A per-component refusal can be right about its component and wrong about
the program, and what says so is asking who else already knows the answer.
**DECOMPOSED FIRST** (one binary, ABBA-rotated, population checked against the build's own
4,701 specifiers / 2,351 distinct): `resolve` **9.4-9.8 ms**, of which `existsOnly` **4.4-4.6**
(2,350 probes at ~1.9 us), `joinOnly` **3.7-3.9**, `dirnameOnly` 0.8, `keyOnly` 1.2,
`bookkeeping` 0.5-0.8 — so the syscalls are the largest piece and the path arithmetic the
next, neither of which the row itself could say.
`ModuleResolver` now memoizes `exists`/`isDirectory` for the build and is SEEDED from the
glob. **It adds no assumption**: (INC.65) already memoizes the whole ANSWER per
`(importerDir, specifier)`, strictly stronger, over the same one-build lifetime. **The seed
may only say YES** — a file can exist and be excluded from the program.
**MEASURED:** the row **10.2-12.0 -> 5.8-6.5 ms**, and the receipt is the count the build
prints — **2,351 questions, 0 reached the filesystem**.
**THE ABLATION FOUND THE PIN SET INCOMPLETE, WHICH IS WHAT IT IS FOR:** keying the memo by
BASENAME reddened only the COUNT pins, because every value pin happened to ask about names
existing on both sides — a wrong PROGRAM, silent per (CFG.1). The missing pin was added and
b2 then reddens it. Three arms, three distinct red sets (4 / 3 / 3).
**GATES.** Suite **16,618 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean.
**SUCCESSOR, measured and named:** `PathUtil.join`/`normalize` at ~810 ns x 4,701
(**3.7-3.9 ms**, a `normalize` that must process `..` segments, which (INC.68)'s fast path
cannot help) and `dirname` + the memo key at **~1.5 ms**, which the crawl loop could hoist
per FILE.

**(INC.56) — AN IntelliJ-CLASS HOST CAN SKIP THE RE-READ, AND THE ROW IT WAS AIMED AT WAS A
*LOCATION* (2026-08-31).** Two opt-in halves in the embedding API: `Project.trustFilesystem`
(the host promises the bytes of a file will not change without this project being told —
through `updateFile`, `deleteFile` or the new `reloadFile`) and `Vfs.readTextIfResident` /
`Vfs.retainRead` (the crawl skips its per-file THREAD HANDOFF for content already in memory).
Retention is written ONLY from the crawl's single-threaded fold — round 825, because the crawl
reads from N concurrent workers.
**MEASURED**, 8 instrumented draws per arm, one JVM per arm, arms rotated across processes,
both rotations agreeing, with the untouched sequential specifier-resolution row as the control:
crawl WALL **30.6/37.0 -> 21.7/19.4 ms** at 2,401 small files and **13.7/14.2 -> 9.5/7.8 ms**
on tsc's 78 huge ones; `read+decode` **132.6/176.1 -> 1.52/1.39** and **65.4/63.2 ->
0.076/0.057**.
**AND THE REFUTATION IS WORTH MORE THAN THE ROW: THE QUEUE PRICED THIS FROM `FrontEnd.READ`,
WHICH IS ELAPSED-WITH-SUSPENSION — A LOCATION, NOT A PRICE.** Retaining the content WITHOUT
skipping the hop served **33,350 reads from memory and moved the crawl's wall by NOTHING** on
the 2,401-file project, while halving it on tsc's 78 huge sources. **The read is a BYTE cost;
the row that made it look like a FILE cost was the hop's suspension** — so the fix that works
on both shapes removes the HANDOFF, not the read.
**THE PROMISE IS NARROWER THAN THE ENTRY FEARED, AND IT IS PINNED:** additions and deletions
are still discovered on every build (nothing caches the file SET), and `.json` is never
trusted. 18 pins including the documented LIMIT (an unreported content change IS missed) and a
REGIME pin that the crawl really takes the resident path; 4 of 5 ablation arms discriminate and
the fifth is recorded as a REDUNDANT GUARD rather than claimed.
**GATES.** Suite **16,586 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% — a CONTROL,
since `SystemVfs` resides nothing and the CLI path is provably unchanged; `huge_methods.py
--fail-over 0` clean.
**SUCCESSOR:** the crawl's remaining halves — sequential specifier resolution ~11-13 ms
(non-syscall remainder; its syscall half is refused by (INC.73)(a)) and a ~7-9 ms concurrent
residue that is the `flatMapMerge` machinery itself, i.e. (INC.64)'s question with the last hop
gone.

**(INC.78) — THE ROOT-FILE GLOB ASKED AN *ACCEPTING* REGEX PER CANDIDATE, AND NO REFUSAL
FILTER COULD HAVE HELPED IT (2026-08-31).** `collectRootFiles` ran
`excludeRegexes.none { } && includeRegexes.any { }` for every candidate of every build — i.e.
on every keystroke of a language-service host — at **4.66-8.08 ms, 1.9-3.4 us per candidate**
on a ~90-110 ms incremental floor at 2,401 files.
**THE ATTRIBUTION INVERTS THE OBVIOUS FIX.** (INC.77) proposed a cheap prefix/extension
pre-filter; measured standalone on one binary, the EXCLUDE half is **191 ns/candidate** (its
literal prefix fails on the first character) and the INCLUDE half is **2,239** — `src/**/*`
compiles to `^…/src/(?:[^/]+/)*[^/]*(?:\.ts|…)$`, which backtracks over every directory
segment and **runs to a MATCH for every file in the project**. A filter can only refuse, so
the lever is an EXACT shortcut and the proposal was aimed at the half that was already cheap.
`GlobMatcher` keeps the regex as its DEFINITION and answers the
`<literal>` + `**` segment + bare `*` leaf + literal tail shape — `src`, `src/**/*`,
`src/**/*.ts`, `dist`, `**/*.spec.ts`, i.e. what tsconfigs contain — from the head and the
tail. Two corrections came from EXTENDING the differential grid rather than reading it: an
EMPTY SEGMENT is the one remainder `(?:[^/]+/)*[^/]*` cannot match (a doubled separator now
falls back to the oracle), and that test is exact only because the head ends at a directory
boundary. The length guard is provably unreachable and is recorded as a REDUNDANT GUARD.
**THE WALL COULD NOT CARRY THE CLAIM: the same one-process ratio read 12x, then 5x, then 3x
over four processes of one binary** (round 867's arm instability). The receipt is
`FrontEnd.globRegexEvals` — decisions that reach the regex — **4,802 -> 0**, pinned at TWO
program sizes with a positive control that a constrained pattern still runs it once per
candidate. In-build `CFG_MATCH` **4.66 -> 0.61 ms**, root-file glob row **14.46 -> 9.16**.
Gate is a DIFFERENTIAL, not a green suite ((CFG.1): a wrong root-file set is silent here);
4 ablation arms, 4 distinct red sets, and the no-fast-path arm reddens ONLY the cost and
regime pins while every value pin stays green.
**GATES.** Suite **16,610 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% including
`output.programFiles` 78 -> 78; `huge_methods.py --fail-over 0` clean.

**(INC.76) — THE LANGUAGE SERVICE WAS PAYING (INC.60)'s DEFECT IN FULL, THROUGH A WRAPPER THAT
DID NOT OVERRIDE (2026-08-31).** `Vfs.listEntries`'s default body is
`list(path).map { VfsEntry(it, isDirectory(it)) }`, and (INC.60) added that member precisely
because asking the kind per entry is kotlinx-io's `metadataOrNull` — **up to FIVE `stat`s**.
`OverlayVfs` never overrode it, so **every `Project` build handed the whole saving back**,
silently, since the answers are identical either way.
**MEASURED STANDALONE over the build's own 50 directories / 2,451 entries: 6.34 ms taking the
kinds from the delegate's listing against 19.54 ms asking per entry — and 19.5 is what the
build's `vfs.listEntries + sort` row read.** That match turned a 3x probe-vs-row gap into a
diagnosis. **LANDED, and it costs NO promise, so both arms gain**: that row **20.70 -> 9.73
ms**, the whole root-file glob **28.14 -> 18.44**, the per-keystroke query **153/145 ->
123/125 ms** trusted and **156/162 -> 140/138** untrusted.
Pins are a DIFFERENTIAL against the default body — a wrong kind drops a file from the program
or adopts a directory as a root, and (CFG.1) says nothing here notices — including the one
asymmetry an obvious implementation gets wrong (an on-disk FILE the overlay has given children
is a DIRECTORY). The cost pin had to be restated as a COMPLEXITY claim at two program sizes:
`isDirectoryCalls == 0` is false and correctly so, because a build asks about specific PATHS.
`CountingVfs` had the same omission and is fixed with it; an audit found no third case.
**TRANSFERABLE: a defaulted interface member added for speed is a silent regression waiting
for the next wrapper**, and the instrument is a row measured STANDALONE against the same row
measured IN THE BUILD.

**(INC.73) — A 2.5 ms ROW, AND THE TWO REFUTATIONS THAT COST NOTHING TO FIND (2026-08-31).**
`init:moduleTypeNameIndex` — the largest single row left in the floor's per-pass table after
(INC.69)/(INC.70)/(INC.71) — is built on FIRST ASK; GO/NO-GO first, per (INC.16):
`moduleTypeNameIndexBuilds` **0 on a floor build, 1 on a full one**.
**ITS VALUE RECEIPT IS THE 8 PROFILES AND THE CORPUS IS A CONTROL — the ablation that never
builds it reddens ZERO of the ~13k baselines and 3 of the 8 profiles (+2 rows each: harness,
server, services)**, which is exactly where rounds 471 and 513 got their evidence. **A family
can have no corpus coverage at all and still be load-bearing; the way to find out is to ablate
and grid, not to reason about it.**
**AND THE HONEST PART: neither the floor wall (medians 117/124 before against 119/127 after —
no separation) nor a 2-process phase A/B can resolve 2.5 ms.** The receipts are the pass row
from the clean single-binary decomposition plus the deterministic count, and the round is
written up as the 2.5 ms landing it is.
**TWO REFUTATIONS FROM THE SAME RECON, BOTH WORTH MORE THAN THE ROW.**
**(a) `SystemVfs.exists` IS ONE SYSCALL** — 1130.7 ns/call against `java.io.File.exists`'s
1108.8, **1.02x**, ABBA inside one process over the fixture's own 2,401 paths. So (INC.60)'s
five-stat finding is specific to `metadataOrNull` and does NOT generalise; and the resolver
already probes exactly ONCE per resolution (**2,351 `exists` + 10 `isDirectory` for 2,351
distinct pairs**, because `.ts` is first in `allExtensions`), so there is no syscall lever in
the crawl's 11 ms resolution row.
**(b) `init:collectUmdGlobalsAndModuleFiles` (2.32) and `init:mergeFileLocalsIntoGlobals`
(2.06) ARE NOT DEFERRABLE**, and the reason is not their readers but their readers' SCHEDULE:
`umdGlobalNames` is read by the merge itself and `moduleFiles` by `collectModuleAugmentations`,
both LATER INIT PASSES that run unconditionally. Combined prize ~5 ms of a 94 ms floor —
refused on arithmetic.
**GATES.** Suite **16,568 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% (including
`typeNode.bypassed` 145,723, the direct receipt that `multiFileModuleTypeNames` answers
identically); `huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0`.
**SUCCESSOR:** the init dispatch has no non-walker row above ~1.4 ms left, so what remains
there is (INC.7)'s partition question one walker at a time; **the floor's largest row is the
CRAWL and its READ half is (INC.56)** — now the only row left with a double-digit prize, and
the one an IntelliJ-class host can simply hand us.

**(INC.72) — THE SURPLUS WAS THE CRAWL, AND BOTH OF THIS SESSION'S WALL FIGURES ARE RETRACTED
(2026-08-31).** (INC.70) and (INC.71) each reported an ABBA-rotated floor wall about **three
times** what their pass row explained, and that gap was queued as a mechanism to hunt. It was
not a mechanism. Running the SAME two binaries with the per-PHASE instrument — two processes
per arm, rotated, second instrumented draw — attributes the change and nothing else:
**init-block pass dispatch 39.87 -> 25.06 ms (-14.81)**, which is what the two pass rows said,
while the UNTOUCHED **import-graph crawl swung +18.01** in the same run, its
elapsed-with-suspension `read+decode` sum moving **147.8 -> 249.9 ms**. Every other phase is
flat to within 0.7 ms.
**So (INC.70)'s "160.0 -> 136.5 (-23.5)" and (INC.71)'s "142.5 -> 120.0 (-22.5)" are each one
batch's reading of a quantity carrying a ±20 ms concurrent term; the same binaries read
128.5 -> 116.5 in this round's batch. What ships is -14.81 ms of init-block dispatch,
phase-attributed, and that is the number to carry.**
**THE LESSON IS NOT "ROTATE MORE" — IT IS "PICK AN INSTRUMENT WHOSE VARIANCE DOES NOT CONTAIN
THE ANSWER".** (INC.68) showed a BLOCKED batch inventing a delta that rotation removed; this is
the next step out — a ROTATED batch of a COMPOSITE quantity still cannot separate two of its
terms, and 4 processes x 8 draws per arm did not help, because the noise is a real, large,
unrelated phase rather than run-to-run jitter. For a checker-side floor change the receipt is
now `FrontEnd`'s phase row plus the deterministic population count; the floor wall is a sanity
check. `FloorAbMain` grows an `fe` mode so that decomposition is a two-BINARY A/B.
**SESSION TOTAL, re-taken on the SAME INSTRUMENT rather than inferred from the A/B arms —
`scripts/floor-decomposition.sh`, same fixture, same warm-ups, same `PLAIN late` slot: the
2,401-file `dom` floor is 122 -> 94 ms (`PLAIN early` 144 -> 105).** Two runs of one recipe
have no arm-rotation problem to get wrong, which is (INC.72)'s lesson applied to the
REPORTING. **The ranking has changed and the next round must start from it: the CRAWL is now
the largest floor row (29 ms, 36%) for the first time in this arc — its READ half is (INC.56),
the one row costing a soundness promise and the one an IntelliJ-class host can hand us — with
the init-block dispatch at 22 (28%), config+glob 12, bind 8, post 5.** The pass table is
**22.43 ms over 418 rows**, headed by three whole-program INDEX builds
(`init:moduleTypeNameIndex` 2.52, `init:collectUmdGlobalsAndModuleFiles` 2.32,
`init:mergeFileLocalsIntoGlobals` 2.06) — none of them a per-file table, so the
(INC.70)/(INC.71) deferral shape does not transfer unchanged, and the GO/NO-GO for each is
(INC.16)'s counter: who forces the index, and is it anyone on a floor build?

**(INC.71) — THE PER-FILE VISIBILITY SETS, AND A FLOOR WALL THAT KEEPS OUTRUNNING THE PASS
TABLE (2026-08-31).** `init:computePerFileVisibility` walks every program file's `locals` to
publish `moduleOnlyGlobalNames` and `libValueShadowNames`, whose only three readers —
`globalsForFile`, `globalsForFileNode`, `libValueBehindTypeOnlyShadow` — are all NAME
RESOLUTION. So a build that checks nothing reads neither.
**THE POPULATION DECIDED IT BEFORE ANY IMPLEMENTATION, for the price of one temporary
counter: 0 asks on a floor build of the 2,401-file fixture against 335,881 on a full one.**
(INC.16)'s law used as a GO/NO-GO rather than as a post-hoc explanation.
**THE ORDERING CLAIM WAS CHECKED**: the pass compares `globals.keys` against
`init:snapshotPreAugGlobalKeys`' snapshot, and all three writers of `globals` run at earlier
init steps. **The one place it is deliberately NOT lazy is the probe** — the INV.3(a)
classifier is still installed at the pass's moment and FORCES the sets from inside its lambda,
so `globals.lookups` reads 783,383, **+0.00%**.
**MEASURED:** row **-> 0.002-0.003 ms** from 5.5-7.2; ABBA-rotated floor
**142.5 -> 120.0 ms (-15.8%)**.
**THE VALUE RECEIPT IS THE CORPUS, AND THAT IS NOW A RULE RATHER THAN AN ACCIDENT:** ablation
c2 (sets stay empty) reddens **492** core tests, while the hand-written `-project` value pin
stays GREEN — the second round running where a `-project` pin cannot discriminate the
mechanism and the corpus discriminates it in the hundreds. For the INV.3 visibility model the
`-project` pins gate the REGIME (which builds do the work) and the corpus gates the ANSWER.
**GATES.** Suite **16,565 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0`.
**SUCCESSOR IS A MEASUREMENT QUESTION, NOT A ROW ((INC.72)):** twice in a row the rotated
floor WALL moved about **three times** what the pass table explains (-23.5 against ~4 ms,
-22.5 against ~7). Both changes also removed thousands of RETAINED allocations per build,
which round 801 says is a plausible mechanism and not a measured one. Decompose BOTH arms with
`--frontEnd` before opening another init row: either the surplus is outside the init block, or
the `rows`-tier probe under-reports and every ranking taken from it needs re-reading.

**(INC.68) — 80% OF THE PATHS THIS COMPILER NORMALIZES WERE ALREADY NORMALIZED, AND THE
BLOCKED ARMS INVENTED A REGRESSION THAT ROTATION REMOVED (2026-08-31).** (INC.66) said
"before pricing any row, check it has a SPLIT"; the row it named for re-decomposition —
`config+glob`, the one floor row carrying no soundness promise — had a split already, and the
cost was under it in a function neither row names. `PathUtil.normalize` is called once per
directory entry by `systemListEntries` and once per candidate probe by `PathUtil.join`, and
allocates ~10 objects each time. **THE CENSUS IS THE WHOLE ARGUMENT AND IT COST ONE COUNTER:
11,935 calls per floor build, 9,584 (80.3%) returning the argument UNCHANGED** — not a
property of the fixture, but of the callers (a child path built from an already-normalized
parent; `"<normalized base>/<plain name>"`). So the fix is a one-pass allocation-free
predicate and an early return: no cache, nothing to invalidate. **PRICED BY POPULATION
BEFORE THE FLOOR WAS CONSULTED** ((INC.52)): 1.02-1.22 us/call against <=0.2, i.e. ~9 ms per
floor build, which is what the rows returned. **ABBA-rotated, 4 processes/arm, 32 floor draws
each:** `vfs.listEntries` 10.86 -> 7.76, specifier resolution 14.94 -> 10.66, crawl WALL
39.51 -> 32.18, config+glob 17.96 -> 13.44, **floor median 127 -> 121 ms**.
**THE LESSON OUTRANKS THE MILLISECONDS: the first, BLOCKED, paired run reported +2.70 ms on
`include/exclude regex match` — a region that calls no `normalize` — reproducibly over 12
draws per arm, and read config+glob as +3.39, i.e. it said the glob half was a net loss. Both
signs INVERTED under rotation.** A per-arm draw count does not substitute for rotation, and a
stable delta in a region with no causal path to the edit is the tell that the ORDER is the
variable.
**THE PINS ARE OVER THE ACCEPTANCES, because the directions are asymmetric**: a false
negative costs the old path, a false positive resolves to a DIFFERENT FILE with no diagnostic
anywhere ((CFG.1)). Value pins against a transcribed reference (a second implementation — a
differential whose arms are one function cannot see a fast path), plus idempotence, a
rewrite-count control and a quiescence-independent predicate pin. Ablations a1/a2/a3/a4 redden
5/5/4/3 of 6.
**GATES.** Suite **16,548 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% including
`output.programFiles` 78; `huge_methods.py --fail-over 0` clean; 8-profile grid
`added=0 removed=0` on all eight — **coverage here rather than a control**, since the corpus
materialises no directory and cannot reach the resolver's path arithmetic.

**(INC.70) — EVERY BUILD ALLOCATED A NAME-RESOLUTION TABLE FOR EVERY FILE, AND A FLOOR BUILD
READS NONE OF THEM (2026-08-31).** `init:buildPerFileScopes` allocated two maps per program
file, copied that file's own top-level locals into one and precomputed a
`LayeredSymbolTable`'s shadow list — for EVERY file, on EVERY build, whether or not a name was
ever resolved there. **THE POPULATION WAS MEASURED BEFORE ANY TIMING, per (INC.16):
`perFileScopeBuilds` is 2,401 -> 0 on a floor build of the 2,401-file fixture and 2,401 ->
2,401 on a full one.** Not "fewer" — none.
**WHAT MAKES THE DEFERRAL EXACT IS AN INIT-ORDER FACT NEITHER FUNCTION STATES**: the eager
loop SNAPSHOTTED `result.locals` precisely to survive a later mutation, and the checker's ONE
writer of a `BinderResult.locals` is `collectModuleAugmentations`, dispatched at an EARLIER
init step — so the two snapshots are the same table. A writer scheduled after this pass would
make the eager and lazy answers disagree silently.
**MEASURED:** row **4.625 -> 0.750 ms** (second instrumented draw), whole init block
39.34 -> 36.38; ABBA-rotated floor **median-of-medians 160.0 -> 136.5 ms (-14.7%)**, four
process medians DISJOINT. **The wall delta is larger than the row explains (~4 of ~23 ms) and
the surplus is recorded as UNATTRIBUTED, not claimed** — the eager form also retained ~4,800
maps per build, which is a plausible mechanism and not a measured one (round 801).
**THE VALUE HALF IS A MEASUREMENT, NOT AN ASSUMPTION:** ablation b2 (never build a scope)
reddens **503** core-suite tests.
**AND THE THIRD ARM IS RECORDED AS BLIND, which is the round's second finding:** b3 (never
STORE the built scope) reads 0 RED even after the fixture was strengthened, because
`perFileScopeOf`'s one-entry IDENTITY memo absorbs every repeated ask for the same file — so
the map's memoization is pinned by nothing here, and the reason is a second cache one layer
up. Likewise the value pins do not discriminate `perFileScope`'s presence at all: under b2 the
module-local leak is STILL TS2304, because `moduleOnlyGlobalNames` decides that upstream.
**GATES.** Suite **16,559 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0` — COVERAGE here, since
an absent scope makes `perFileScopeOf` answer null and every consumer falls back to the merged
`globals`, i.e. a name resolving to a FOREIGN module's local.
**HARNESS TRAP WORTH THE LINE:** a cross-binary A/B runner may read no census counter that
does not exist in BOTH arms — the older arm dies with `NoSuchMethodError` and the batch prints
one arm's medians as if they were both.

**(INC.69) — THE INIT-BLOCK DISPATCH IS NOT FLAT, AND A PLATEAU IS A SHARED PER-FILE COST
(2026-08-31).** (INC.66) recorded the ~400-pass table as FLAT, "so there is no row to make
cheaper"; a HISTOGRAM rather than a top-N list refutes it — on `many-small-2400-dom` the
floor table is **418 rows summing to 39.5 ms, 44 of them carrying 37.1 (94%) and 367 carrying
0.82** — and 21 of those 44 sit at an almost identical **0.39-0.55 ms**. A plateau of
near-identical prices across unrelated walkers is not a coincidence of what they do: all 21
are corpus PIN walkers whose whole body is a whole-program loop whose first act is
`fileName.substringAfterLast('/') != "<one literal>"`, i.e. 2,401 iterations and a `String`
allocation each to compare against a name no real project contains.
**ONE BASENAME INDEX, BUILT ON FIRST ASK**, and the 21 loop HEADERS re-pointed at it; the
redundant `!=` guard is kept VERBATIM so every loop body is byte-identical.
**MEASURED — the deterministic half first**: the 21 rows **10.079 -> 0.457 ms** (second
instrumented draw, round 846; 0.438 of the remainder is the FIRST asker paying the one build,
the other twenty are 0.000-0.002), cross-checked against four draws of the unmodified binary
in a separate process at 9.27-12.01. **ABBA-rotated wall, one JVM per arm, 4 processes/arm x
8 draws: floor median-of-medians 157 -> 144.5 ms (-8.0%)**, means 162.5 -> 145.5.
**THE SAME RUN RE-PROVED (INC.68)'s LAW ON ITSELF**: the two unrotated `rows` processes read
whole-table sums of 52.32 -> 54.27 ms — the after arm 4% "worse" — while the 21 rows it
changed fell 22-fold, because that process simply drew slow. An unrotated process compares
rows WITHIN itself, never totals.
**THE PINS ARE NESTED-PATH VALUE PINS BECAUSE THE CORPUS CANNOT REACH THEM**: the harness
materialises no directory, so its names are FLAT and all ~13k baselines exercise the
degenerate key — an index keyed by the full path passes every one and silently stops pinning
a real project's `src/dates/temporal.ts`, a MISSING diagnostic nothing here prints.
**GATES.** Suite **16,553 / 0 / 3** (+5, exactly the new pins); `cost_gate.py` exit 0, every
counter +0.00%; `huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0`,
labelled a CONTROL in its own header (no profile holds any of the 21 literals). Ablations
a1/a2/a3 redden 2/1/2 of 5; **a4 (widen the index to a suffix match) reddens NOTHING and is
recorded as a round-927 redundant-guard PAIR** — the index buys the speed, the kept guard
keeps the correctness — and only a5, which widens the index AND deletes the guard, reddens
the negative control.

**(INC.67) — READING THE PLUGIN FOUND A DEFECT NO PROFILE COULD, AND IT WAS ONE THIS
SESSION HAD WIDENED (2026-08-31).** The instrument was the CONSUMER'S SOURCE.
`xemantic/xtsc-intellij-plugin` — the first real host of the `Project` API — keeps one
`XtscSession` per `tsconfig.json`, **each owning its own single-thread executor**, so a
monorepo with N configs runs **N compiler threads in one JVM**. That is a shape no fixture,
profile or corpus baseline here produces, and the one every process-global cache implicitly
assumes away. `RealLibSnapshots.parseCache` was a plain `HashMap` mutated in place, and its
KDoc's stated mitigation (`prewarmParsedLibFiles`) covers `--workers` inside ONE compile and
says nothing about two independent sessions — and (INC.63)/(INC.65) had just added two more
such maps. All three now publish **copy-on-write behind `@Volatile`**.
**WHAT IT BUYS, PRECISELY:** a lost race still costs a RECOMPUTATION, and always did, since
`getOrPut` on a `HashMap` is not atomic either; what this removes is the CORRUPTION. **And
the duplicate is harmless for the mirror of round 471's reason** — the identity sets these
feed compare `Node`s STRUCTURALLY, so two parses of the same lib text are interchangeable to
every consumer. `ModuleResolver`'s (INC.65) memo needs none of it: per instance, per build.
**THE FIRST DRAFT OF THE PIN BROKE TWO OF CLAUDE.md's OWN RULES AND ONLY RUNNING IT SAID SO**
— it put a `Map<String, SourceFile>` inside `assert(...)`, so power-assert rendered the AST
and the failure arrived as an **`OutOfMemoryError` in the diagram builder** with the real
cause masked; and it compared two reads by IDENTITY, which assumes a quiescent process, so
it passed in isolation and failed in the full suite. **A pin's ENVIRONMENT is part of its
specification.**
**GATES.** Suite **16,542 / 0 / 3**; `cost_gate.py` exit 0; `huge_methods.py --fail-over 0`
clean; 8-profile grid `added=0 removed=0` on all eight; ablation e1 reddens exactly the
publication pin.
**WHAT ELSE THE PLUGIN REVIEW SHOWED:** it already does what this arc assumed a host would —
`updateFile` for unsaved buffers, `diagnosticsOf` for the file ON SCREEN ONLY, one thread per
project, and (INC.55)'s cancellation wired to `ProcessCanceledException`. Its `configPath`
argument is load-bearing and non-obvious: without it a malformed `tsconfig.json` shows a
clean editor over a program checked with default options. It is also the host that could make
(INC.56)'s promise — but it invalidates on `VFS_CHANGES` rather than owning the read, so the
promise is expressible and not yet made.

**(INC.65) — THE CRAWL RE-ASKED THE FILESYSTEM A QUESTION IT HAD ALREADY ANSWERED, AND THE
SESSION'S FLOOR IS 241 -> 151 / 256 -> 116 ms (2026-08-30).** The previous round named "a
PARTITION question and a HOST PROMISE" as all that was left; that was wrong within the hour,
because **`FrontEnd.CRAWL` had no split below its two elapsed-WITH-SUSPENSION CPU sums** — so
the residue between them and the WALL was unattributed, and on an application-shaped project
that residue is most of the row. Bracketing the crawl's SEQUENTIAL half
(`FrontEnd.CRAWL_RESOLVE`) read **20.6-28.6 ms of a 44-60 ms crawl wall**, ~15% of the whole
floor. **(INC.53)'s "ask what runs OUTSIDE a pass" has a sub-row-shaped twin, and this is the
third time this arc that ADDING an instrument, not reading one, is what found the cost.**
**THE FIX IS EXACT, AND READING THE FUNCTION IS WHAT SAYS SO**: `ModuleResolver.resolve`
reads `importerPath` once, to take its `dirname`, and never again, so `(importerDir,
specifier)` is not a heuristic key but THE key. Censused offline before building anything:
**4,701 resolutions over 2,351 distinct pairs — a duplication factor of exactly 2.0**, and a
codebase with shared barrels has more. Nothing to invalidate — a `ModuleResolver` is
constructed once per `build`, so the memo's lifetime IS one build; deliberately NOT
process-global, since a cross-build cache cannot see an ADDED file ((INC.48)). `null` is a
real answer and is memoized too, or the filesystem is re-probed for every unresolved
specifier — the population a project mid-edit has most of. **CRAWL_RESOLVE 24.0 -> 14.3 ms
mean; crawl wall 44-60 -> 34-44.**
**THE PIN THE DESIGN RESTS ON IS NOT A COUNT**: a memo keyed by the SPECIFIER ALONE passes
every count assertion and silently resolves `./dep` in one directory to another directory's
file — a wrong PROGRAM, which per (CFG.1) this repo has no diagnostic channel to notice.
Ablation d2 makes exactly that mistake and reddens exactly that pin.
**GATES.** Suite **16,539 / 0 / 3**; `cost_gate.py` exit 0 with **`output.programFiles` 78**
(the direct receipt that resolution still finds the same program); `huge_methods.py
--fail-over 0` clean; 8-profile `--noEmit` grid `added=0 removed=0` on all eight; `--outDir`
emit **byte-identical to the PRE-SESSION binary**, 78 files.
**THE SESSION, ONE FIXTURE AND ONE INSTRUMENT: `many-small-2400-dom` floor medians 241 -> 151
(early) and 256 -> 116 (late), -37% / -55%**, across (INC.63), (INC.64)(a)/(b) and (INC.65) —
and **UNDERSTATED**, because the box drifted ~10% slower over the session (`full` median
3,944 -> 4,335), so the floor's SHARE of a full build fell 6.1% -> 3.5%.
**AND THE NUMBER THE HOST ACTUALLY FEELS, measured through the `Project` API itself
(`scripts/incremental-cost.sh`, 2,401 files, 3 rotations):** a body edit re-answers in
**170-248 ms** (warm rotations 159-192), a comment-only edit 164-192, introducing an error
166-193 with the TS2322 correctly found, and a re-query with NO edit is **0 ms** — the memo
serves it. The narrowed build is **149-204 ms against a full build of 4,140-5,897**, i.e.
**~25-30x**, and the partition's answer agrees with the full build's row for row on every
rotation. The floor is the dominant term of that latency, which is what makes this arc the
right one for an editor host.

**SUCCESSOR (INC.66):** checker construct 38-70 (the init pass dispatch, flat — an (INC.7)
partition question), crawl WALL 34-44 (its READ half is (INC.56), the only row costing a
soundness promise), config+glob 13-29 (co-largest on some draws, NO promise attached, and
worth re-decomposing rather than assuming (INC.60) finished it). **And take the lesson
literally: before pricing any row, check it HAS a split.**

**(INC.64) — TWO ROWS PAID ON EVERY KEYSTROKE FOR WORK NOBODY READS, AND THE FLOOR IS
241 -> 146 ms OVER THE SESSION (2026-08-30).** Both found by (INC.62)'s instrument —
divide a row by its own population, refuse an impossible per-op cost.
**(a) THE CRAWL HANDED EVERY FILE TO ANOTHER THREAD TO SCHEDULE A MAP PROBE.**
`readAndScanBatch` read on `Dispatchers.IO` and then hopped to `Dispatchers.Default` for
EVERY file so a parse would never run on an IO thread — but on a warm build every parse is
a `CrawlParseCache` HIT, so the hop scheduled a ~1 us probe onto another thread, `files`
times. Reading all 2,401 files sequentially is **13-21 ms** and the flags over them
1.1-1.8, against a crawl WALL of **51-57**; priced with an ABBA-rotated synthetic arm,
**sequential 14.4 / `flatMapMerge(16)` alone 17.2 / one hop 18.5 / the shipped two hops
32.1 ms**. Only a MISS hops now; the cold crawl is untouched. `pre-parse (CPU sum)` falls
**69-81 ms -> 2.0-2.7**. **The wall could NOT resolve it** (ranges overlap, and that run's
`full` median was itself 9% slower), so the claim rests on the mechanism plus the synthetic
arm and the PIN IS A COUNT — dispatches at two program sizes: cold 5 -> 5 and 20 -> 20,
warm 0, and after one edit exactly ONE.
**(b) A `--noEmit` BUILD COMPUTED A DEPENDENCY ORDER FOR AN EMIT THAT NEVER HAPPENS —
15.0-22.6 ms, ~10% of the floor, AND IT WAS ON NO QUEUE.** `extractRelativeImports` runs
twice per file and every consumer of its product orders EMITTED output. **(INC.59)'s
finding one call deeper.** The obvious edit is wrong — a `continue` also skips
`tsFileNames.add`, which every later phase reads. **AND THE CORPUS IS A CONTROL HERE, NOT
THE GATE**: `skipEmitOutputs` is set only by `ProjectCompiler`, never by the `@noEmit`
corpus directive, so all ~13k baselines run with the branch TAKEN. The 8-profile `--noEmit`
grid (`added=0 removed=0` on all eight) and the new `-project` pins are what see it; the
EMITTING path is verified independently — an `--outDir` build of the compiler profile is
byte-identical across the two binaries, 78 files, `diff -r` clean.
**THE VALUE PIN WAS BLIND ON ITS FIRST FIXTURE AND ONLY THE ABLATION SAID SO**: named the
obvious way round (`dep` imported by `main`), dependency order and ALPHABETICAL order
coincide, so emptying the sort's edges left it green. Renamed `zdep`/`amain` so the two
orders are opposite — a pin over an ORDER needs a fixture whose expected order differs from
every order the system produces by accident.
**MEASURED (many-small-2400-dom, floor median): 241 -> 189 -> 197 -> 146 ms early and
256 -> 166 -> 152 -> 143 late** across this session's three landings, **-39% / -44%**.
**GATES.** Suite **16,535 / 0 / 3** (+7, exactly the new pins); `cost_gate.py` exit 0;
`huge_methods.py --fail-over 0` clean.
**SUCCESSOR (INC.65):** what is left is a PARTITION question (the init-block pass dispatch,
flat across ~400 passes) and a HOST PROMISE ((INC.56), the crawl's read half) — the era of
finding a stray quadratic in the front end may be over, which is itself worth recording.

**(INC.63) — EVERY KEYSTROKE RE-DERIVED THE WHOLE LIB, AND THE HALF THE STANDING REFUSAL
NAMED WAS 3% OF IT (2026-08-30).** (INC.62) asked for the floor on a `dom` fixture before
opening any row; taken, and the largest single addressable row is `parseBuiltinLib` at
**46-50 ms of a 241-256 ms floor**, stable to ~1% across draws where everything else swings
40%, and **O(1) in program size — so it is a BIGGER share the smaller the project**, i.e.
precisely what an IDE-hosted application pays per keystroke. It was invisible at
`"lib": ["es2020"]`, where the same row is 8-11 ms. **(INC.54)(c) had REFUSED it whole,
"BLOCKED on round 884's `mergedSymbols` clone-on-write"** — true of the BIND, which
measures **1.4 ms**. The other 97% is two pure functions of the SHARED parses:
`RealLibResolver.resolve`, whose `/// <reference lib=…/>` closure regexes ~3.7 MB of lib
text and which `bindRealLibs` called **TWICE** per construction (~32 ms), and the
B85.2/M2.2 decl-set walk, ~30k puts into containers keyed by **data-class AST nodes** —
round 471's deep `hashCode` at a scale the es2020 fixtures could not express (~15 ms).
**THE ARITHMETIC NAMED THE MECHANISM BEFORE ANY BUILD**: ~500 ns per `HashMap` put with a
`String` value is 20-40x impossible, which is (INC.62)'s own instrument and the fifth
defect it has found. The recorded split mis-attributed the resolve because it sits INSIDE
the `bindLibFiles` section — `bindLibFiles` **17.4 -> 1.4 ms** is that regex, not a bind.
**A REFUSAL THAT NAMES A BLOCKER MUST CHECK THE BLOCKED HALF IS WHERE THE COST IS.**
**MEASURED (many-small-2400-dom, both arms this session):** `parseBuiltinLib` 47.1 -> 1.65,
50.1 -> 1.69, 46.2 -> 1.46 ms; the decl-set walk 12.0-15.9 -> **0.01**; checker construct
99 -> 55 / 97 -> 44 / 84 -> 43; **PLAIN floor median 241 -> 189 (early) and 256 -> 166
(late)**, the early arm's ranges disjoint. **GATES.** Suite **16,528 / 0 / 3** (+5, exactly
the new pins); `cost_gate.py` exit 0 with all 20 counters +0.00% (the EXPECTED answer — a
CLI compile builds one checker, so a hoist within one construction is a no-op there);
`huge_methods.py --fail-over 0` clean; 8-profile before/after BINARY grid `added=0
removed=0` on all eight, run and LABELLED as a control (the index is a function of the lib
set alone and the eight profiles share one — the corpus, thousands of compiles in one JVM,
is what discriminates the sharing). Ablation: three arms, each reddening exactly the pin it
names, with the embedded-lib negative control green in all three.
**SUCCESSOR (INC.64):** the init-block pass dispatch (40-53 ms, FLAT — an (INC.7)-style
partition question, not a micro-optimisation) and the crawl WALL (51-57 ms, (INC.56), the
only row costing a soundness promise) are now co-largest.




**(INC.61) — THE WHOLE (INC.\*) ARC HAD BEEN MEASURING THE CHEAP `lib`, AND THE FLOOR'S
LARGEST PASS IS NOW 45x SMALLER (2026-08-30).** Re-reading the floor after (INC.60) —
(INC.59)'s own lesson, applied a second time — put **123 of the checker's 137 ms in the
init-block pass dispatch**, whose per-pass table no round had read on the many-small shape
since (INC.58) proved the tsc-profile ranking wrong by 600x. Its largest row was
`init:buildPerFileScopes`, which copies the SHARED half of a file's scope — lib globals,
script-file locals, global augmentations — into a fresh table **per file**, i.e.
`files x libGlobals` insertions. **THEN THE FIXTURE ITSELF TURNED OUT TO BE THE
UNDERSTATEMENT:** it pins `"lib": ["es2020"]` (~185 names) where an ordinary project's
unset `lib` means **`dom`** (~2,242). Copying the fixture and changing **that one line and
nothing else** takes the pass from **13.5 ms to 175.6 ms** on the same 2,401 files — 70%
of the whole floor pass table. So (INC.57)'s law that a profile's FILE SHAPE can make a
cost inexpressible holds equally for its **compilerOptions**, which CLAUDE.md had recorded
once for a library baseline ((CHK.49)) without the general conclusion being drawn.
**THE FIX IS AN OVERLAY, NOT A CACHE** — the base is the same object for every file, so it
is built once and `LayeredSymbolTable` answers `own[k] ?: base[k]`. **Its ORDER is the
load-bearing half**: three consumers iterate a per-file scope, and a `LinkedHashMap` keeps
a shadowed key's ORIGINAL position, so a shadowing local must appear there carrying the
OWN value rather than being appended — the one thing an implementation gets wrong, and
the only pin ablation c1 reddens (**the 16,523-test corpus would not have caught it
either**, since order reaches only cost counters and suggestion ordering). Mutators throw
rather than silently dropping a write. **MEASURED (dom arm, 2,401 files, both arms this
session): the pass 175.64 -> 3.90 ms (45x), init dispatch 334 -> 42, checker construct
393 -> 83, floor phase total 503 -> 200, and the PLAIN floor median 385 -> 202 ms** —
worth its own line, because (INC.60)'s 16 ms sat inside the ±40% single-draw band with the
WRONG SIGN and this one is far outside it, so here the wall corroborates the row instead
of contradicting it. **GATES.** Suite **16,523 / 0 / 3** (+4, exactly the new pins);
`cost_gate.py` exit 0 with every counter unchanged; `huge_methods.py --fail-over 0` clean;
8-profile grid `added=0 removed=0` on all eight, run deliberately because this is the
checker's name-resolution substrate. **SUCCESSOR (INC.62): re-take the floor on a `dom`
fixture before opening any of its rows, and treat that as the default shape from here.**

**(CFG.1) — A PROJECT THAT HAS EVER BEEN BUILT READ ITS OWN OUTPUT BACK IN, AND THE
CORPUS CANNOT CONTAIN A DIRECTORY (2026-08-30, found by (INC.60) on the way past).**
tsc's rule for an ABSENT `exclude` is `excludeSpecs = filter([outDir, declarationDir],
d => !!d)` (`commandLineParser.ts`); the package folders are not `exclude` entries there
at all but are pruned from every wildcard match by the matcher — which is what
`ProjectCompiler`'s own walk already does by basename. **We had the redundant half and
not the load-bearing one.** Measured against tsgo 7.0.2 on a two-file project with
`outDir: "dist"` and the artifacts a previous `--declaration` build leaves behind:
**tsgo's program is 1 file and ours was 2** — `dist` matches the default everything-include
and a `.d.ts` is a root extension — so such a project crawled, read, parsed, bound and
checked its own emitted tree **on every keystroke**, which is the incremental floor the
(INC.\*) arc has been paying down. After the fix the CLI answers `1 root, 1 in program`,
i.e. tsgo's own. An EXPLICIT `exclude` still REPLACES the default, as in tsc — pinned,
because that is the direction a "just add outDir to the defaults" implementation gets
wrong, and it is ablation arm b2. **THE DIAGNOSTIC HALF IS REAL IN tsc AND UNOBSERVABLE
HERE, WHICH IS ITSELF THE FINDING**: forced in, tsgo answers TS2451 twice for a duplicated
`declare const` and TS5011 for the moved common source directory, and **we report
neither** — so a defect that changed the PROGRAM ITSELF was invisible to every diagnostic
channel in this repo and the only observable left was a file COUNT. A value pin asserting
those codes stay absent **stayed green under the ablation that removes the whole fix** and
was deleted rather than kept (round 808). Both gaps filed as **(CHK.74)** and **(CFG.2)**.
**NOTHING HERE COULD SEE THE DEFECT EITHER**: the generated corpus materialises no
directory, and all eight dashboard profiles scope `include` to a `src` subtree under which
`dist` never matched — the grid is a CONTROL and reads `added=0 removed=0` on all eight,
as predicted before it ran. Only a `-project` fixture through `ProjectCompiler` and a
`Vfs` expresses it, the same instrument (CHK.29) needed and for the same reason.
**GATES.** Suite **16,519 / 0 / 3**; `cost_gate.py` exit 0 with every counter unchanged;
`huge_methods.py --fail-over 0` clean; 8-profile grid clean.

**(INC.60) — THE INCREMENTAL FLOOR'S THIRD ROW WAS A QUESTION ASKED TWICE PER ENTRY, AND
THE SECOND ASK COST FIVE SYSCALLS (2026-08-30).** `FrontEnd.CONFIG` — tsconfig load,
`@types` acquisition and the root-file glob — is what an editor pays on every keystroke,
and no round had separated its three pieces. Split five ways it is **~99% the glob, the
glob is ~99% its directory walk, and 60-70% of THAT is one call the walk did not need to
make**: for every entry the directory listing had just returned it went back to the
filesystem to ask "is this a directory?". tsconfig load is **0.43 ms** and `@types`
**0.01 ms** — neither was ever the row. **WHY THAT BOOLEAN COSTS 7.3-8.6 us IS IN THE
DEPENDENCY, NOT IN OUR SOURCE**: kotlinx-io 0.9.1 compiles `metadataOrNull` to
`File.exists()` + `isFile()` + `isDirectory()` + `isFile()` + `length()` — up to five
`stat` syscalls plus an allocation — on a `Path` rebuilt from the string the listing had
just produced; it is visible only by dividing the row by its population and refusing the
implied per-op cost (7.3 us is impossible for one `stat`). `Vfs.listEntries` answers the
kind WITH the listing; **its default body is literally the two calls it replaces**, so
every other `Vfs` is unchanged and correct without touching it, and `SystemVfs` overrides
it through a new `expect fun systemListEntries` (JVM: one `readdir` + one `stat` per
entry; native: the portable pair). **MEASURED, both arms this session with the same
runner: `CONFIG` 29.2-32.6 -> 11.5-16.3 ms at 2,401 files and 52.8/52.9 -> 20.7-27.1 at
4,801; per entry 9.3 -> 3.1-4.4 us, flat across both sizes** — a constant-factor win on a
linear row, with the population census (`50 dirs / 2451 entries / 2401 candidates / 2401
roots`) IDENTICAL across the change, which is the receipt that nothing was skipped to buy
it. **THE UNINSTRUMENTED FLOOR MEDIANS READ 216 BEFORE AND 222 AFTER**, i.e. the saving
sits inside the ±40% single-draw band and a wall-clock reading of this round would have
concluded the opposite of the truth — which is why the split was built before the fix.
Pinned at two layers and ablated separately: `RootGlobListingTest` (the CALL SHAPE; its
counting `Vfs` must OVERRIDE `listEntries`, or the default *is* the pre-fix sequence and
the pin is vacuous) and `SystemVfsListEntriesTest` (the JVM actual's EQUIVALENCE, whose
divergence would be silent — it includes a directory named `looks-like.ts`). a1 reddens
2 of 3 in the first and none in the second; a2 the reverse. **GATES.** Suite **16,514 /
0 / 3** (+6, exactly the new pins); `cost_gate.py` exit 0 with **every counter
unchanged**; `huge_methods.py --fail-over 0` clean. **SUCCESSOR (CFG.1), a DEFECT found
on the way**: tsc's `commandLineParser.ts:3131-3141` defaults `exclude` to
`[outDir, declarationDir]` when absent and **we implement none of it**, so a project that
has ever emitted pulls its own `dist/**/*.d.ts` back in as ROOT FILES.

**(BIND.1) — A DIAGNOSTIC THAT APPEARED AND DISAPPEARED WITH THE BYTE LENGTH OF AN
UNRELATED FILE (2026-08-30, reported from the IntelliJ plugin).** `nodeKey(pos, end)`
carries NO file identity and positions restart at 0 in every file, yet
`Binder.nodeToSymbol` and `moduleInstanceStates` were ONE map shared by every
`BinderResult` a binder produced — so two declarations at coincident offsets in DIFFERENT
files shared a slot, last-wins in bind order. **IT IS NOT A THEORETICAL HAZARD: tsc's OWN
78 SOURCES CARRY 271 KEYS WRITTEN BY TWO OR MORE *DECLARATION* NODES IN DIFFERENT FILES**
(`watchUtilities.ts`/`moduleNameResolver.ts` variable declarations, a dozen
import-specifier pairs), and an ordinary 223-file program (one source file plus `zod` and
`@types/node`) carries 109 of them plus 4,324 shared keys overall. **THE TRIGGER IS
WHITESPACE**, which is why it reads as random: `Node.end` is the end of the FOLLOWING
token, so for a file's LAST statement it is the EOF offset — the one span trailing
newlines move — and **106 of those 223 files have a last statement that appending
newlines ALONE can drive into a collision**. Reduced to four lines: two same-length files
each declaring a merged `namespace` made `buildNamespaceScope` build the scope of the
OTHER file's namespace, so the file's own exports went missing (a false TS2304) and the
foreign file's became visible (a missing one) — **both directions, against tsc 5.9.3** —
and adding ONE character to the sibling file made it vanish. The tables are now per
`bind()`; twelve checker reads holding only a `Node` go through `nodeSymbolOf` /
`moduleInstanceStateOf`, which ask the OWNING file (INV.2(a) parent chain) and treat an
owner that recorded nothing as **null** rather than scanning the others — that scan IS the
collision. **NOTHING HERE COULD HAVE SEEN IT**: it needs two files whose declarations land
on coincident offsets, which no hand-written fixture produces by accident, so
`NodeKeyCollisionTest` hands two exact texts to the pipeline and ASSERTS the collision
precondition; ablated, its two behavioural pins go red while the precondition and the
no-collision control stay green. **GATES.** Suite **16,500 / 0 / 3** (+4, exactly the new
pins); the compiler profile still reports **46 errors on 78 files**; `cost_gate.py` exit 0
with `output.errors` and `spine.nodes` UNCHANGED — `typeOfExpr.calls` +0.54% and
`narrow.memoServed` +1.55% are the 271 collisions on that profile now resolving to the
right file, re-baselined here; `huge_methods.py --fail-over 0` clean; warning-clean.

**(INC.57)+(INC.58)+(INC.59) — THE FRONT END WAS QUADRATIC IN FILE COUNT **THREE TIMES**,
AND NO PROFILE HERE COULD EXPRESS ANY OF THEM; THE PER-KEYSTROKE FLOOR OF A 2,401-FILE
PROJECT GOES **1,653 -> 279 ms (5.9x)** (2026-08-30).** Working (INC.56) — *"skip the re-read, THE LARGEST
REMAINING FRONT-END ROW"* — its own entry demanded the prize first be re-measured on "a
project with MANY SMALL files rather than tsc's 78 huge ones". That measurement **refuted
the premise and found two independent quadratics beside it**, in different subsystems.
**(INC.58), found by (INC.57)'s own successor instrument (divide the floor pass table by
file count at two sizes): `checkJsxImportResolutions` was **709.74 of a 774.65 ms floor
pass table — 92% — on a project containing NO JSX**, growing 14.6x for 4x the files.**
`resolveJsxTsxCandidate`'s path-suffix fallback walked every file of the program once per
import specifier per extension, and the pass is gated on `--jsx` being **UNSET** — maximum
work on precisely the projects that never use JSX, always answering null. Restricting it to
the `.jsx`/`.tsx` subset is EXACTLY equivalent (every non-null return is such a file; order
preserved, so the FIRST match is unchanged) and takes it to **0.30 ms, linear**.
**(INC.54)(a) had ranked that pass at 1.2 ms from the tsc profile — 600x, so a published
RANKING and not merely a price was invalidated.** A pin lesson from it: the first value pin
went RED on a WORKING binary because a relative specifier is served by an O(1) probe and
never reaches the scan — **an assertion about WHICH path served an answer is not implied by
the answer being right**; there are now two value pins, one per path. Suite **16,503 / 0 /
3**; both gates clean with every cost counter unchanged.
**AND (INC.59), THE THIRD — FOUND BY RE-READING THE FLOOR RATHER THAN TRUSTING THE
RANKING, WHICH IS THE REUSABLE HALF OF THE WHOLE SESSION.** After two rounds had
reordered it twice, `post-checker` had become the LARGEST row — 166-189 ms of a 366 ms
floor (~48%) — and **appeared in no queue item at all**. One expression:
`parsedSourceFiles.filter { it.key !in transformOrder.toSet() }`, with `.toSet()` INSIDE
the lambda, so an N-element set was rebuilt once per entry of an N-entry map — **in the
`--noEmit` path**, i.e. a build that emits nothing was spending 175 ms per keystroke
preparing an emit order it would never use. `POST_EMITPREP` **158.5-175.3 -> 1.8-2.8 ms
(~70x)**; floor 366 -> **279 ms**. Suite **16,504 / 0 / 3**, both gates clean, counters
unchanged. **VERIFIED AT MONOREPO SCALE:** a fourth size (4,801 files) reads a **428 ms
floor against 279 at 2,401 — **1.53x for 2x the files, i.e. SUB-linear**, the cleanest
evidence the quadratics are gone rather than reduced. The two rows still above 2.0x are
single-digit-to-teens ms and sit at or below the noise ((INC.52) read one floor row at
13.16 and 8.42 ms in two draws of ONE binary), so **this class is exhausted at these
sizes** — what made the three findable is that they were 14.6x / 21x / 4x-per-doubling,
one to two orders clear of that band. **SUCCESSOR (INC.60):** `config load + @types +
root glob`, 52.8 / 52.9 ms at 4,801 — two draws **0.2% apart**, the one floor row
measurable without fighting the noise, and it carries no soundness promise where
(INC.56) does.

**AND (INC.57), THE ONE THAT STARTED IT:** `extractRelativeImports` opened with
`allFiles.map { it.fileName }.toSet()` — a fresh list AND set of every program file name —
and the emit-order scan calls it **TWICE per file**: `2 x files^2` string hashes per build,
plus two sibling `parsed.files.any { … }` scans in the same loop. On generated
application-shaped projects (`scripts/gen-many-small-project.py`) the `FrontEnd.IMPORTS`
row grew **4x for 2x the files — 18.9 / 76.3 / 331.6 ms at 601 / 1201 / 2401** — which at
2,401 files is 11.5 M hashes and ~92 MB of garbage on every keystroke. **WHY ~950 ROUNDS
MISSED IT, and it is now a CLAUDE.md entry:** all eight dashboard profiles are ONE
codebase, tsc's own sources at **78 files averaging 128 KB**, where `2 x 78^2` vanishes —
**a cost that is per-FILE rather than per-BYTE is structurally inexpressible on that
shape**, and nothing here had ever been pointed at the opposite one ((INC.9)'s regime law
on a new axis: the SHAPE of the corpus). **The fix is a HOIST, not a cache** — `parsed.files`
is a `val List` on a data class, so the set is loop-invariant by construction and there is
no invalidation story; `.toSet()` is kept verbatim so the container and any iteration order
stay bit-for-bit what the per-call expression produced. IMPORTS -> **5.8 / 7.1 / 16.1 ms**,
per-file cost FLAT where it had been doubling; floor medians 165 -> 142, 409 -> 359,
**1653 -> 1035 ms**. **PINNED AS A COUNT** (`programNameSetBuilds == 1` at 10 files AND at
100) because the claim is about COMPLEXITY and only a count can state one, plus a VALUE pin
on dependency-first emit order. **ONE ABLATION ARM, TWO ANSWERS:** the count pins go RED
reading exactly **20** (`2 x files`) while the value pin stays GREEN; and all **20**
`cost_gate.py` counters are IDENTICAL between arm and HEAD, so this round is provably
counter-neutral and the gate's +0.54%/+1.55% is drift from the **60 commits** since the
baseline was recorded at (CHK.63) — deliberately NOT rebaselined, since folding sixty
commits of unattributed drift into this one would make it un-auditable. **(INC.56) is
re-ranked, not refuted as a saving: it is FOURTH** (crawl wall 25-38 ms of a 409 ms floor)
and the only one of the five costing a soundness promise. **SUCCESSOR (INC.58):** the
`Checker` init-block pass dispatch is itself super-linear — 73-91 / 204-217 / 756-810 ms at
601 / 1201 / 2401 files, ~73% of the floor. **GATES.** Suite **16,499 / 0 / 3** (+3, exactly
the new pins); `cost_gate.py` exit 0, `output.errors` 46, `spine.nodes` 856,962;
`huge_methods.py --fail-over 0` clean.

**(INC.55) — A HOST CAN NOW CANCEL A BUILD, WHICH IS THE CAPABILITY AN IntelliJ PLUGIN
NEEDS AND NO LATENCY WORK CAN REPLACE (2026-08-30).** Asked to judge the language service
as "the best support one could get inside an IntelliJ platform IDE" rather than as
"incremental", the top of the list changes: there was **ZERO cancellation** anywhere in
the compiler or the `Project` API. A build runs on the compiler's own deep-stack thread and
`Project` JOINS it, so the caller is blocked for its whole duration and cannot abandon it
from outside — while `DaemonCodeAnalyzer` restarts analysis on every write action. Without
this an editor must either block a pooled thread producing an answer it has already
discarded, delaying the next one behind it, or not run the analysis in a highlighting pass
at all. `Project.cancellation` takes a `CancellationSignal` (on the platform,
`{ indicator.isCanceled }`), polled at every `pass("…")` boundary AND every **1024 spine
nodes** — the second is what keeps a large buffer's walk (1.65 s on tsc's own `checker.ts`)
interruptible, and the hot loop's own comment refuses interleaved work, so the poll sits
behind a counter (837 volatile reads for 856,962 nodes). **IT IS AN `Error` DELIBERATELY**:
the checker, crawl and `Vfs` carry defensive `catch (Exception)` guards, and a cancellation
they could swallow would let the build continue with a missing file — silently wrong, worse
than not cancelling; `Error` is safe because the 2026-07-04 sweep left no `catch (Throwable)`
anywhere, which is pinned rather than trusted to a KDoc. **STATE SAFETY IS BY CONSTRUCTION**
— every cache assignment in `Project` happens after `build` returns, so a throw skips all of
them — pinned both at the first poll and MID-flight. **THE PIN THAT ALMOST DIDN'T
DISCRIMINATE**: the spine-poll test first compared a 3-file fixture with a 1-file one and
FAILED, because the `pass()` poll count is not constant across programs (405 vs 418) and
swamped the spine's ~12; holding file count and shape fixed and varying only SIZE reads ~417
against ~526. **GATES.** Suite **16,496 / 0 / 3** (+7, exactly the new pins); `cost_gate.py`
exit 0 with **`spine.nodes` UNCHANGED at 856,962** and `output.errors` flat at 46 — the poll
is inert when unarmed; `huge_methods.py --fail-over 0` clean. Also documented: the threading
rule is a CONFINEMENT rule (Symbol/Type ids are thread-local, so two threads on one
`Project` corrupt an id space with no diagnostic), and the GraalVM/AOT/CRaC artifact levers
do NOT apply to a plugin running in-process on the IDE's own JVM.


**(INC.53) — THE INCREMENTAL FLOOR'S LARGEST BLOCK WAS NEVER IN A PASS, AND ~950 ROUNDS OF
INSTRUMENTS COULD NOT SEE IT (2026-08-29).** The floor is what an editor pays per keystroke,
and 32-44 ms of its 63-72 ms is "checker construct + getDiagnostics". Split for the first
time: **`getDiagnostics()` is 2-3 MICROSECONDS**, so the whole phase is the CONSTRUCTOR — and
~20 ms of it is the class's **~494 property initializers**, a constant that reads the same on
a 63 ms floor build and a 5.2 s full one. That is 0.4% of a full compile, which is exactly why
no round noticed, and **~30% of every language-service query**. **A FIELD INITIALIZER IS NOT A
`pass("…")`**, so it contributes to no `--passTiming` row, no `cost_gate.py` counter and no
diagnostic: the whole pass-gating arc ((INC.7)/(INC.20)/(INC.21), 189 walkers) swept loop
headers and structurally could not reach it. **FOUR initializers are essentially all of it**
(the other ~490 are 0.2-1.2 ms between them — 494 allocations cannot be 20 ms, which is what
said a handful were doing whole-program work). Three were whole-program indices with exactly
ONE read site each and now build on FIRST ASK: `localTypeAliasIndex` becomes a per-FILE index
over that file's own frozen statements, in the same DFS order and first-wins per name, the
other two `lazy(NONE)`. **Floor field region, four draws each side: 18.6 / 25.4 / 29.6 / 30.2
ms -> 8.1 / 12.6 / 8.4 / 11.2**, with all three rows reading 0.00 ms and 0 files on a floor
build; even a FULL build needs only **69 of 78** files' alias index. Claimed as a WORK
REDUCTION, not a millisecond ((INC.52)'s law — the same binary reads 13.16 and 8.42 ms for one
row in two draws), so `EagerIndexCensus` counts the population. **THE FOURTH IS REFUSED WITH
ITS PRICE**: `parseBuiltinLib` splits three ways with no dominant part (binds 3.2-5.3 ms, decl
walk 1.9-2.8, resolution + 45 `mergeSymbolTable` 3.1-5.3) — and the round-471 hypothesis that
the data-class-keyed node sets dominate is MEASURED WRONG. Its two larger parts are
per-checker by requirement (the checker mutates lib symbols), so round 884's `mergedSymbols`
clone-on-write is the named unblocker. **GATES.** Suite **16,489 / 0 / 3** (+4, exactly the
new pins); `cost_gate.py` exit 0 with `output.errors` flat at 46; `huge_methods.py
--fail-over 0` clean, and `Checker.<init>` shrank **5,538 -> 5,464** bytecodes, buying back
(JIT.1)(d) headroom.


**(INC.52) — THE INCREMENTAL FLOOR'S DEAREST PASS STOPS WALKING EVERY FILE'S SYMBOL
TABLE, AND ITS PRICE IS BELOW WHAT THIS REPO CAN MEASURE (2026-08-29).** With project
diagnostics incremental ((INC.46)) and restart-proof ((INC.48)), what an editor pays per
keystroke is the FLOOR. Decomposed: **68 ms**, of which the checker is **42 ms (67%)** with
nothing to check, and the largest pass in both draws is `init:computeAllEnumValues` — whose
second loop visited EVERY file's `locals` and recursed through every namespace's `exports`
to find the program's enums. `BinderResult.bindsEnum` answers that from the bind that
already happened: an identity, not an approximation, since `bindEnumDeclaration` is the one
site minting a conventional enum symbol and `enumValues` is ID-keyed. **MEASURED AS A
POPULATION, from ONE binary with the verify arm as the "before": 12,871 top-level symbol
visits -> 8,676 (-32.6%)**, plus every namespace recursion beneath the **45 of 78** files
skipped, with `localsSkipViolations = 0` over a non-empty skipped set. **AND THE TIME IS NOT
RESOLVABLE, WHICH IS THE PART WORTH KEEPING**: the row that motivated the round read 13.16
ms in one draw and **8.42 ms in the next draw of the same binary**; after the change, 7.27
and 9.66; the floor wall reads 68 before and 74 after with draws spanning 57-86. So it is
landed as a WORK REDUCTION with a control and no millisecond is claimed — a single-draw
per-pass row on a 68 ms floor is not a measurement, and that is now a CLAUDE.md entry
because the next agent will read the same table and reach for the same row. **GATES.** Suite
**16,485 / 0 / 3** (+2, exactly the new pins); `cost_gate.py` exit 0; `huge_methods.py
--fail-over 0` clean; warning-clean.

**(INC.48) — THE INCREMENTAL STATE OUTLIVES THE PROCESS, AND A RESTART IS **60x**
(2026-08-29).** (INC.46) made project-wide diagnostics incremental within a process and
every bit of that state died with it: an IDE restart, a plugin reload or a daemon recycle
paid a whole-program build for a tree nobody had touched. `Project.saveState()` encodes
what has to survive — export signatures, escapes, the program's file list, that build's
diagnostics and a content hash per input — and `restoreState()` adopts it, so the next
process starts at the (INC.46) gate instead of at a rebuild. **MEASURED on tsc's own 78
sources, every arm asserted to agree ROW FOR ROW**: warm, **5,855 ms -> 94 ms (62x)**
clean and 259 ms (23x) with a file changed on disk; in a **COLD process — which is what a
restart actually is — 9,625-9,844 ms -> 155-175 ms (~60x)**, the snapshot being **47 KB**
for a 78-file project. The cold column is the one that matters and it is nearly as good as
the warm one, which was not obvious: an IDE restart pays the JIT ramp, and (INC.49)
attributed ~18 s of a 23 s first query to exactly that — but the ramp barely touches a path
that never checks the whole program. **IT WRITES NO FILE**: `encode`/`decode` answer and
take a string, so the host decides where its caches live; the CLI's `--incremental`
(`tsconfig.xtsbuildinfo`, INV.7(d3)) remains the convention for callers who want the other
one. **EVERY PART OF THE CLAIM IS CHECKED, because skipping any of it is a stale answer**:
the compiler build id (never a `.dirty`/`unknown` one — two dirty trees share an id without
sharing behaviour), the config path, a CONTENT hash per file (never mtime — round 871), and
the `.json` INPUTS as well as the sources, since a changed tsconfig or a `package.json`
whose `type` decides a module format makes every stored row suspect rather than one file's.
**AND THE STALENESS CASE NO HASH CAN SEE HAS ITS OWN MECHANISM**: a file ADDED while the
process was down is in no stored hash and no stored list, so a restored state is not
trusted until a build has re-crawled and found the same program — even a clean project runs
the gate once, with an EMPTY partition. Ablated, the naive "trust the snapshot" version
reddens exactly two pins and nothing else. **GATES.** Suite **16,483 / 0 / 3** (+13,
exactly the new pins); `cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean;
warning-clean.

**(INC.50)/(INC.51) — THE STABILITY RATE IS A PROPERTY OF THE CODEBASE, NOT OF LAYERING;
AND ONE LINE OF ORDINARY LIBRARY CODE ESCAPED THE WHOLE FILE (2026-08-29).** (INC.47) left
one question: is 67% a property of the mechanism or of tsc's own sources? Measured on three
corpora of 40 real commits each, whole trees per side: tsc `src/compiler` **67%**,
`cronstrue` **50%**, `marked` **72%** — the two libraries BRACKET tsc, so layered code is
**not materially above** it and (INC.50)'s per-hop closure is refused by its own stated
threshold. `cronstrue` is the CONTROL arm and was chosen as one: it is the only library
outside the corpus where this checker agrees with tsgo 7.0.2 exactly (0 errors both sides)
and has no dependencies, because a library we report errors on has types degraded to `any`
and a degraded type is artificially STABLE. The transferable statement is that the rate
tracks **what a codebase's commits touch** — cronstrue's edits are to the ~44 locale
classes that ARE its exported surface (its MOVED cases are real signature changes such as
`commaOnlyOnX0()` -> `commaOnlyOnX0(s?: string)`), where tsc's are inside function bodies.
AND **(INC.51)**: pointing the mechanism at real code found a defect in ONE run.
`marked.ts` escaped because of `export { useExtension as use }` — the walk collected the
name an IMPORTER sees and looked it up in `locals`, which the file keys by the name it
DECLARES, so every renaming export missed, read as "an exported name with no file-level
symbol", and escaped the WHOLE file: every edit to it rebuilt the whole program forever and
the export's type was never hashed. tsc's own 78 sources never use the shape, so all eight
dashboard profiles are structurally blind to it. Fixed, with three pins — one of which
records a DELIBERATE conservatism: renaming the LOCAL still moves the hash, because
dropping declaration names would make two structurally identical classes hash equal and a
class with a `private` member is nominally typed. **AND THE (INC.47) LAW REPEATED ON A
SECOND CORPUS: removing an escape buys NOTHING** — marked's escapes went 1 -> 0 with its
rate unchanged at 72%, exactly as `types.ts` left tsc's at 67%. On both, the file that
could not be summarised was also one whose surface genuinely moved. **GATES.** Suite
**16,470 / 0 / 3** (+4, exactly the (INC.51) pins); `cost_gate.py` exit 0;
`huge_methods.py --fail-over 0` clean; warning-clean.

**(INC.47) — THE EXPORT FINGERPRINT IS A CANONICAL SERIALIZATION, THE ESCAPE CLASS IS
EMPTY, AND THE 87.5% CEILING IT WAS AIMED AT DID NOT EXIST (2026-08-29).** The walk no
longer recurses: every type reachable from a file's exports is DISCOVERED once, in a
deterministic order, and named by its discovery INDEX, so a reference — forward, back or
self — costs one lookup and cycles need no special case. There is no strongly-connected
component left to hash, which is why this is simpler than the Tarjan machinery the queue
named and strictly stronger. **MEASURED whole-program on tsc's own 78 sources**:
`types.ts` **122.52 ms for ONE export and a node-budget STOP -> 6.21 ms for 871 exports**;
whole-program **131 -> 16 ms**; structural nodes **2,019,605 -> 38,502**; budget stops
1 -> **0**; escapes `[types.ts]` -> **[]**; exports hashed 2,137 -> **3,007**; both
controls held (identical-text stability **78/78**, narrowed-vs-whole agreement **24/24**).
**AND THE PRIZE IS REFUTED ON BOTH ARMS RATHER THAN ARGUED**: the 40-commit stability
corpus reads **27/40 = 67% before AND after, with every one of the 40 per-case verdicts
identical**. (INC.46)(2)'s ceiling came from its runner printing *"N moved only because a
touched file ESCAPES"* over the code `if (escaped)` — which counts every case that
TOUCHED an escaping file — while its own detail lines showed four other movers in the same
case; re-derived, exactly ONE of the 8 qualified, so the ceiling was **70%**, and after
this even that one moves, because `types.ts` is a file of exported declarations and an
edit to it really does move the surface. **IT LANDS ON SOUNDNESS, NOT ON THE RATE**: the
old walk bounded its recursion with a DEPTH CAP of 24 and hashed everything below it as
one constant — a MISSED invalidation, i.e. a stale diagnostic, live since (INC.46)(3)
began answering project-wide diagnostics from the previous build. Both new pins are RED
against the pre-(INC.47) binary and green after, one for the mechanism (pinned on the node
COUNTER, not a time) and one for the soundness half. **The escape class being empty is a
claim about OTHER codebases** — a single-file library with a large cyclic type graph is
ordinary in real TypeScript and would have forced a whole-program rebuild on every
keystroke forever. **GATES.** Suite **16,466 / 0 / 3** (+2, exactly the new pins);
`cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean; build warning-clean.
**SUCCESSOR: (INC.50)** — the 67% is not improvable on this corpus by any mechanism, so
the live question is the rate on ordinary LAYERED code (`knip`, `jsonrepair`, `cronstrue`).

**(INC.46)(3) — PROJECT-WIDE DIAGNOSTICS ARE INCREMENTAL, AND WITH (INC.44)/(INC.45)
NOTHING AN EDITOR ASKS IS WHOLE-PROGRAM BY DEFAULT ANY MORE (2026-08-29).**
`Project.diagnostics()` no longer rebuilds after every edit: when the edit moved no
exported signature it answers the previous build's rows with the edited files' rows
replaced, from ONE narrowed build — **108-113 ms against 4,864-5,096 ms, a factor of 45**
on a served edit. **GRADED AS A DIFFERENTIAL THAT NEEDS NO BASELINE**: over (INC.46)(2)'s
40 real tsc commits, edited THROUGH THE OVERLAY as an editor's unsaved buffers, the answer
must equal a project opened fresh on the edited text — **EQUIVALENT, 40 agreed of 40**,
with `served=27` as the control that keeps the agreement from being vacuous (a run whose
`served` is 0 is REFUSED — round 790: a verifier reads 0 both when the skip is sound and
when the instrument is dead). The 27 is exactly step (2)'s 67%, two instruments
corroborating. **Five preconditions, each CHECKED rather than argued** and each with its
own pin; the pin set is a PAIR by construction (a body-only edit must be served and a
signature edit must not — an implementation that always serves passes the first, one that
never serves passes the second), and each is pinned twice, on the ANSWER and on the
BUILD COUNT, because without the cost family every pin passes against the old
always-rebuild behaviour. **TWO DEFECTS THE PINS FOUND THAT REVIEW DID NOT**: the
incremental answer was NOT RETAINED (`cached` cannot hold it — that field is a
whole-program `Result` — so a second `diagnostics()` with no intervening edit rebuilt, and
an editor asks twice constantly); and **the build-counting unit every cost pin in this repo
uses is BLIND for an edited config** — an overlaid file is served from the overlay and
never reaches the backing `Vfs`, so the config's read count stops moving and "did this
rebuild" reads 0 for a build that certainly happened. Two pre-existing control pins
legitimately moved 1 -> 2 builds and were updated to state the new cost model rather than
papered over: adding an export IS a signature change, and the gate being wrong costs the
narrowed build plus the rebuild. **GATES.** Suite **16,464 / 0 / 3** (+11, exactly the new
pins); `cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean; build warning-clean.
**THE SUCCESSOR IS SCC-AWARE HASHING**: `types.ts` still escapes on an in-file
strongly-connected component that no budget closes (measured at 2 M and 12 M nodes) and it
accounts for 8 of the 13 fallbacks, so Tarjan-per-component is the one lever between the
measured **67%** floor and the **87.5%** ceiling.

**(INC.46)(2) — THE STABILITY RATE IS **67%** OVER 40 REAL tsc COMMITS, AND ONE TEXT SCAN
WAS WORTH 35 POINTS OF IT (2026-08-29).** Step (2) is the one the queue said could refuse the
whole mechanism ("under ~70% the 45x is diluted to nothing"). `scripts/inc46-stability.sh`
fetches its OWN blob-filtered depth-3000 clone of microsoft/TypeScript — never
`typescript-repo`, which is a depth-1 shallow clone AND a build-pinned input — and replays
**40 real no-merge commits** touching `src/compiler`, materialising the WHOLE tree at the
parent against the whole tree at the commit (a file from another era beside a tree from this
one resolves against symbols that may not exist). **27 of 40 stable = 67%**, and **8 of the
13 that moved did so ONLY because `types.ts` escapes** — so the band is a **67% floor and an
87.5% ceiling** with one named lever between them. **THE FIRST READING WAS 32% AND WAS AN
ARTIFACT OF MY OWN CODE**: `declaresGlobalSurface` scanned whole source for
`export as namespace` — a construct with NO AST NODE in this parser — and `checker.ts` says
those words **twice, both in `//` comments**; since it is the file tsc's history edits most,
that single false positive cost **35 percentage points** and presented as a plausible
refusal rather than as a defect. **No fixture would have found it** — nobody writes
`// export as namespace foo` into a hand-written test — and the edit corpus found it in one
run. **`types.ts`'s escape is STRUCTURAL and was measured rather than assumed**: it is a
node-budget stop at 2,000,000 nodes (129.6 ms) AND still a stop at **12,000,000 (741 ms,
whole budget burned)**, because the file-boundary cut cannot help INSIDE a file and
`types.ts` declares ~874 mutually recursive interfaces in one. The lever is **SCC-aware
hashing**, deliberately not attempted here; the budget stays bounded and the file is recorded
in `ExportSignatures.whole` — a full rebuild, never a stale diagnostic. **GATES.** Suite
**16,453 / 0 / 3** (+13 over 16,440: the 12 step-(1) pins plus the comment-mention pin this
defect earned); `cost_gate.py` exit 0; `huge_methods.py --fail-over 0` clean. Step (3),
wiring the invalidation into `Project.diagnostics()`, is now the only item left.

**(INC.46)(1) — THE EXPORTED-SIGNATURE FINGERPRINT IS BUILT AND MEASURED, AND ITS WALK
HAD TO BE FOUND BY MEASUREMENT THREE TIMES (2026-08-29).** The queue's step-(1) threshold
("single-digit ms on `types.ts`'s 874 exports, or stop") is met with room: **136 ms
whole-program** on a 5,215 ms rebuild, and **0 ms on 23 of 24 narrowed builds** — a
narrowed build fingerprints only its partition, so the per-EDIT cost of the gate is under
a millisecond against the 108-113 ms build it rides on. **The two controls that decide
feasibility are not cost figures**: two builds of identical text agree **78/78** (the
id-freedom claim — a hash carrying a `Type.id` passes every structural test and then
invalidates everything, always), and a narrowed build's fingerprint equals the
whole-program one **24/24** (the CONVERGENCE claim — the baseline comes from a
whole-program build and the edit's answer from a narrowed one, so a systematic
disagreement means every first edit falls back forever). **THE WALK'S SHAPE WAS THE REAL
QUESTION.** A path-only cycle guard is EXPONENTIAL in DAG width — 159 s inside one build,
found by an external `jcmd Thread.print` — and closed-subtree memoization is still not
enough, because tsc's resolved-type graph is one giant SCC (`Node.parent: Node` plus
hundreds of mutually recursive interfaces): **6 of 78 files unfinished inside a
2,000,000-node budget, among them `checker.ts`, `binder.ts` and `emitter.ts`**. What works
is CUTTING at the file boundary — a type declared elsewhere is unchanged by construction
while only this file is edited, so it is keyed by its declaration's `(fileName, pos, end)`
and not descended into. That took the arm from 719 ms / 6 escapes / **4-of-24** agreement
to **136 ms / 2 escapes / 24-of-24**. **AND THE QUEUE CENSUSED THE WRONG QUANTITY**: cost
tracks the transitive type CLOSURE, not the export COUNT, and the two are near-inversely
related — `utilities.ts`'s 692 exports are 1.6 ms where `types.ts`, which declares the
SCC, is 129.6 ms. Steps (2) (the stability RATE, which needs a deepened TypeScript clone)
and (3) (wiring the invalidation) are deliberately NOT in this commit — the order of work
is measure-first and (2) can still refuse the whole thing. **GATES.** Suite **16,452 / 0 /
3** (+12 over 16,440, exactly the new pins); `cost_gate.py` exit 0 with a largest move of
**+0.08%** (the profile's standing residual — the expected answer, since the walk is off
by default and a strict no-op then); `huge_methods.py --fail-over 0` **0 over limit**.

**(INC.46) QUEUED AND PRICED — AND MEASURING IT REFUTED THE QUEUE'S OWN EXPLANATION OF WHY
PROJECT-WIDE DIAGNOSTICS CANNOT BE INCREMENTAL (2026-08-29, owner's idea).** The standing
story, from round 772 and (INC.35), is that a dependency closure buys nothing on tsc because
its sources are `export *` barrels. **The barrels were never the cause.** A SYMBOL-level use
graph — which is free, since `capturedDefinitions` already records span -> declaration —
re-checks **100% of the program's characters at the median edit, the same as the file-level
graph** (94.9% of imported names placed, so not an under-count): those files genuinely use
symbols from most other files and the relation is transitive. **What collapses it is asking
whether an edit moved any EXPORTED SIGNATURE**, not which symbols a file uses: a body-only
edit moves none, so no dependent re-checks and the cost is one narrowed build — **108-113 ms
against 4,864-5,096 ms, a factor of 45**, already measured by (INC.31)/(INC.37). **91.6% of
the program's characters are inside brace bodies** (a proxy for edit POSITION, optimistic
because an inferred return type leaks, pessimistic because it counts `interface` bodies).
**This needs no corpus and no owner call** — a signature hash pays on DENSE code too, so
unlike (INC.35) it is gradable on the dashboard profile. **THE SHARP HAZARD IS RECORDED**:
`typeToString` is the wrong hash source in BOTH directions — `aliasDisplayMap` is a
first-wins global so it is not a pure function of the type (spurious invalidation), and B58.1
renders `errorType` as `"any"` so a degraded resolution hashes as a genuine `any` (a MISSED
invalidation, silently). The hash must be an id-free structural fingerprint; (INC.16) already
built one to copy. Cost input censused: **3,398 exported declarations, mean 44/file, max 874
in `types.ts`**; its runtime is the first thing to measure, with a stated refusal threshold.
**No code landed — the entry is the deliverable.**

**(INC.45) — `renameAt` IS NARROWED TOO, AND ITS ABLATION FOUND A BLIND PIN SET
(2026-08-29).** The rename sweep took (INC.44)'s spelling closure and hands the resulting
file set to the compiler as a check partition. Two things make it more than a copy.
**Both of a rename's builds must share ONE partition** — `verifyRename` compares
diagnostics as a `(file, code)` MULTISET, which a partition filters, so a narrowed
"before" against a whole-program "after" reports every unswept row as removed; the
soundness argument for narrowing it at all is that a rename edits only files the plan
names and an unedited file's meaning can change only through a name it imports, which it
must then SPELL. **And the population is the closure UNION every occurrence of the NEW
name**, because `verifyRename`'s third check — the only one that can see a rename which
compiles and means something else — scans for occurrences already spelling it and would
otherwise pass VACUOUSLY. **THE ABLATION'S FINDING**: arm b2 (the after-build forgets the
partition) reddened **NOTHING**, because every fixture was a CLEAN program and both bags
were empty whatever either build walked — one file carrying a diagnostic and spelling
neither name takes it to **2 RED**. Arm b3 (never narrow) is **UNDISCRIMINATED and
recorded as such**: the change is equivalence-preserving by construction, so what stands
in its place is one pin on the shipped DEFAULT with no mode install in it ((INC.16)'s
lesson). **MEASURED**: an ordinary rename is **~1.0-1.3 s against ~15 s (12-14.5x)** —
`emitFiles` 2 of 78 files at 1,304 ms, `transformNodes` 3 of 78 at 1,025,
`checkSourceElement` 1 of 78 (but that file is `checker.ts`) at 4,725.
**GATES.** Suite **16,440 / 0 / 3** (+18 over the session's 16,422 baseline, exactly the new pins); rename differential **EQUIVALENT** — 8 carets,
7 narrowed, 6 producing an APPLICABLE plan, 1,691 edits compared plan for plan, 0
diverged, 56.5 s against 114.2 s; three ablation arms b1 **1 RED** / b2 **2 RED** / b3
undiscriminated with a reason.

**(INC.44) — `referencesAt` IS NARROWED BY *SPELLING*, AND THE DOC CLAIM THAT IT "CANNOT
BE" CONFUSED THE CLAIM WITH THE EVIDENCE (2026-08-29).** `docs/language-service.md` said in
three places that find-references and rename "are NOT narrowed and will not be: their claim
is about every file, so there is nothing to narrow to". The claim is program-wide; the
EVIDENCE is not — an occurrence can only be an answer if it SPELLS a name the symbol is
reachable by. `referencesAt` now selects that population before typing it and `captureIn`'s
partition, which has always been DERIVED from the request's spans, narrows the check with
it: **no new mechanism**. On tsc's own 78 sources an ordinary name costs **510–553 ms
against 8.8–11.1 s (17–18x)**, `checker.ts`-only names 1,940 ms (4.8x), and the worst
realistic case (`SyntaxKind`, 9,827 hits in 49 files) still wins at 4,904 ms; a repeat is
free (119–150 ms) because the narrow path reaches a memo the whole-program one never did.
The closure over `import { p as q }` / `export { p as q }` terminates because both spellings
are tokens of the file DECLARING the alias; everything else — a default export, a default
import's local, `export =`, `import x = require(…)`, a namespace binding, the spelling
`default` — REFUSES and runs the old sweep. **The near-miss worth remembering**: the obvious
substring file filter is not exact, because `StringLiteralNode.text` is the COOKED value and
`\a` is an identity escape, so `o["pl\ain"]` names `plain` — a file may be skipped only if
it holds no backslash at all (29 of 78 do, carrying 78.2% of the characters). **The
ablation's honest half**: arm a3 reddens only the REFUSAL pins, so the escape guards are
CONSERVATISM — kept because tsc answers **6** references where we answer **2** on a
`export { renamed as default }` edge, which is now pinned so the day it closes is loud.
**GATES.** Suite **16,434 / 0 / 3** (+12 from a re-verified 16,422 baseline, exactly the new pins); reference differential **EQUIVALENT** — 60 carets drawn by stride over all 381,775 occurrences, **59 of them actually narrowed** (the control), **0 diverged**, 12,248 hits compared element for element; mean partition **17.5 of 78 files**, aggregate 182.0 s narrowed against 561.6 s whole-program (**3.09x** on a draw that lands proportional to occurrence count, i.e. on the hottest names);
four ablation arms, four DISTINCT red sets; `cost_gate.py` / `huge_methods.py` are CONTROLS here (no `-core` source
touched) and both are green: `cost_gate.py` exit 0 with `output.errors` **46** and a largest move of **+0.08%**
(`globals.lookups`/`globals.misses` — the profile is unchanged, this is its standing
run-to-run residual), `huge_methods.py --fail-over 0` clean.


**(INC.85) — A WAVE THAT CANNOT BLOCK IS DRAINED WITHOUT THE 16-WAY MERGE, AND THE GATE IS
DEFAULTED OFF (2026-09-01).** (INC.84) measured the crawl's `flatMapMerge` pipeline at
**0.58/0.60/0.60x effective parallelism** on the arm an IntelliJ-class host runs — 16 workers
producing LESS CPU than their own wall, because every read is served from memory and every
parse from the content cache, so there is nothing to overlap. The same pipeline runs at
**7.5-8.9x** for a host that does not promise the filesystem, which is the control that makes
this a statement about the WAVE rather than about concurrency.
`readAndScanBatch` now classifies per path on the caller's thread — resident content AND a
content-cache hit is built directly, anything else defers to the old pipeline moved verbatim —
with both halves feeding the UNCHANGED single-threaded fold, so `CrawlParseCache.store`,
`retainRead` and the counters still run once each and off the flow (round 825).
**`readAndScanBatch` WALL 8.48/9.82/12.28 -> 5.84/5.32/4.73 ms; pipeline 6.63/8.13/9.61 ->
0.81/0.80/0.67.** The receipt is DETERMINISTIC and no wall number is quoted: a warm trusted
keystroke reads **2400 resident / 1 piped** (the merge is entered for the edited file alone)
against **0 / 2401** cold and untrusting, while four rotated batches of the query wall gave
sign-flipping deltas on the untouched CONTROL arm too — (INC.72)'s +-20 ms concurrent term.
**THE ROUND WAS FIRST REPORTED AS A REFUSAL, AND WHAT CHANGED THE VERDICT WAS REMOVING A COST
RATHER THAN RE-MEASURING.** The first design made every host pay a per-path probe (~0.6-0.9 ms
per wave) to serve a regime only some are in. `Vfs.hasResidentContent()` — a whole-store
question **defaulted `false`**, asked ONCE per wave — means every `Vfs` that has not opted in,
`SystemVfs` and so the entire shipped CLI and daemon path, performs **not one probe**.
**AND THE GATE'S SHAPE IS STRUCTURAL, NOT A THRESHOLD:** `OverlayVfs` answers from `retained`
alone and deliberately NOT from overlaid buffers, because `contents` is O(open editors) while
a wave is O(program files) — that disjunct would spend O(program) probes to fast-drain a
handful and can never pay at any project size. Dropping it took the last non-winning regime
from 0.6-0.9 ms to **99-111 NANOseconds**.
**EIGHT ABLATION ARMS, AND a5 IS THE ONE WORTH READING:** it was **DEAD on its first pass**
because the fixture's edit dropped retention, so the shape that matters — an unsaved buffer,
resident with new bytes over a stale cached tree — did not exist. Rebuilt, it reddens exactly
the staleness pin. **Without it this change could have shipped serving the PREVIOUS
KEYSTROKE'S parse tree, with no counter, order pin or corpus baseline noticing.**
**GATES.** Suite **16,645 / 0 / 3**; `cost_gate.py` exit 0, every counter unchanged;
`huge_methods.py --fail-over 0` clean; compiler profile **46**; `--frontEnd` census lines
byte-identical before and after.

**(INC.82) — THE IMPORTER'S DIRECTORY WAS RE-DERIVED PER SPECIFIER, AND THE ISOLATED PROBE
OVER-READ ITS OWN PRIZE BY 3x (2026-08-31).** `ModuleResolver.resolve` read `importerPath`
for nothing but its `dirname` — the (INC.65) KDoc says so in as many words — then joined it
with the specifier into a fresh `String` and probed the memo with it TWICE. The crawl knows
that directory once per FILE and asked once per SPECIFIER: **4,701 asks over 2,401 files**.
**PRICED BEFORE BUILDING** with the probe that already decomposes the row: of 1,314 ns per
specifier, `dirnameOnly` 96 and `keyOnly` 174 — **1.27 ms of a 6.18 ms row**.
**LANDED:** `resolveFrom(specifier, importerDir)` is the entry point and `resolve` a wrapper,
which makes the contract structural rather than a comment; the memo is nested (`dir -> spec`)
so the outer probe hashes a cached-hash instance the caller already holds and the inner one
only the short specifier; a memoized `null` is an identity sentinel, so a served answer costs
one probe; and the crawl hoists both the `dirname` and the per-file resolution map, the map
staying LAZY so a file whose every import is unresolved still contributes no entry.
**AND THE PART WORTH READING IS THE OVER-READ.** In the BUILD, over two class dirs differing
only in these files and rotated across processes, `FERESOLVE` reads **4771/5143/4102 ->
4677/4707/3954 us** — after wins 3/3 batches in both directions, ranges overlapping, delta
**~0.15-0.44 ms, not 1.27**. `hits x mean-call-cost` one layer in from where it is usually
quoted: 96 and 174 ns are what those operations cost **in a tight loop over 4,701 reps**,
inputs in L1 and the branch perfectly predicted. **An isolated per-operation probe prices an
UPPER BOUND on a removal, never the removal.**
**SO THE RECEIPT IS THE COUNT, EXACT TO THE UNIT:** `path normalize: 9577 -> 7277`, i.e.
precisely `4,701 - 2,401`, with the glob, join and resolution-question censuses IDENTICAL
across the arms — the receipt that the same work is done. The floor wall moved 103 -> 93 ms
3/3 and is **not claimed**: (INC.72)'s +-20 ms concurrent term is ten times the effect.
**ABLATION:** three arms, three distinct red sets. a2's pin needed a whole build —
`moduleResolutions` is not on the `Result` and reaches the checker as (CHK.30)'s
bare-specifier answer, so a map written under the wrong importer is a LOST diagnostic.
**ALSO: `docs/language-service.md` § 14 now EXISTS.** (INC.75)(b) claimed it documented
`cancellation`; the § 0 table's rows for `cancellation`, `saveState()` and `restoreState()`
all pointed at a section that was never written. It now carries the signatures, the poll
points, the cancelled-build contract, the exact `null`/`false` conditions, the added-file
limit, and the JVM edge a host hits: the cancellation is an `Error` by design, so
`Future.get` wraps it in `ExecutionException` and a generic failure branch would log a
warning per cancelled keystroke.
**GATES.** Suite **16,629 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean; compiler profile **46** diagnostics, unchanged.

**(INC.86)(b) ANSWERED:** the init block is 418 rows, `rowsTo50pct=5`, tail of 363 rows worth
**1.03 ms between them** — no plateau left.
**GATES.** Suite **16,653 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%, `output.errors`
46; `huge_methods.py --fail-over 0` clean; **two-binary 8-profile grid added=0 removed=0 on all
eight**, its before-arm control verified non-blind.

**(P18.14) — A REAL FALSE NEGATIVE BEHIND A DISPLAY ITEM, 17,236 → 17,259 / 0 / 3 (2026-09-04).**
(PARITY.1) was queued as two FORM divergences and its most valuable half turned out to be
MEANING: `canUseTypeEngine` refused a `Type.Union` source against an OBJECT target wholesale,
so `const t: { x: number } = u` with `u: "a" | "b"` — and `string | number`, `string | undefined`,
against a named interface, an array, a function type, at declaration, assignment AND return —
reported NOTHING where tsgo 7.0.2 and pristine 6.0.3 both report TS2322 (argument and
object-literal-member positions already reported, so a probe written either way reads as
working). Fixed by lifting the two primitive-vs-object rules the gate already had to a
primitive-only UNION, with the suppression-only narrowing predicates given the matching
anonymous-object arm. The literal-union display collapse landed at declaration and assignment
(`getBaseTypeOfLiteralType` maps over a union where `getWidenedLiteralType` has no union arm,
plus tsc's `never` guard, which also fixed a pre-existing single-literal divergence); argument,
return and object-literal-member displays are separate emitters and stay open. **The qualified
`import("…").Enum` display is REFUSED with a measurement that corrects the item**: the rule is
enum-only, the claim that 103 baselines are served by hard-coded pins is false (they are served
by rounds 745-749's real same-string-retry mechanism), and only 10 of 2,881 active baselines
carry the rendering — while `typeToString` here is a pure `(Type) -> String` with 718 downstream
sites, so the node-builder context tsc uses is a redesign, not a fix. 23 pins, seven arms each
with its own red set (one first read 0 RED and was replaced by a pin probing what the guard
actually protects); **no baseline moved and no `LogicalParityDivergence` was needed**, which the
round predicted by enumerating the 4 literal-union-source and 18 `never`-target baselines. New
CLAUDE.md entry: the 8-profile grid is structurally BLIND to every type-display change (all its
rows are `Cannot find name …`).
