# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **191,070** lines (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: three
checker reads, one table write, stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

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
