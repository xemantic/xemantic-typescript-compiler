# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **191,070** lines (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: three
checker reads, one table write, stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

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

**(P18.11) — PER-MODULE EXTERNALS GENERATION: 51 MODULES OF `@types/node` COMPILE TOGETHER, → 17,196 / 0 / 3 (2026-09-04).**
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
