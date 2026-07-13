# PLAN-PHASE-5 — Self-compile the TypeScript compiler, then performance

Owner directive (2026-07-03, re-scoping the 2026-07-02 *"fully compile any TypeScript
project"*): **fully compile the TypeScript compiler itself, then optimize
performance.** "Any TypeScript project" is the post-v1 horizon.

**v1 definition of done:** all 8 tsc-source profiles (compiler / tsc-cli / jsTyping /
deprecatedCompat / typingsInstallerCore / services / server / harness) at **zero false
positives**, all files emitted, zero crashes/hangs/OOMs — verifiable fully offline.
Byte-correct emit diffing against real tsc is the network-gated follow-up (needs
node + typescript installed). Then M5 (performance) completes the directive. Items
that do not block v1 (M2.4, M3.0, M3.5, all of M4) are parked in § "Post-v1 backlog"
near the bottom of this file — the top-to-bottom loop skips them until v1 lands.

This file is the **live queue** for Phase 17. `PLAN-PHASE-4.md` (Phase 16 and earlier)
is archived state — its "Known architectural blockers" section remains the reference
material for the M3 items below; do not work its queue.

## Phase 17 — Self-compile the TypeScript compiler (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

**Re-scope (2026-07-03, owner): the Phase 17 target narrowed from "any TypeScript
project" to the TypeScript compiler itself.** Rationale: "any project" is asymptotic,
while the tsc-source profiles are already the dashboard — v1 becomes a measurable
burn-down (compiler 2,726 / services ~7,145 / server ~7,606 / harness ~8,135 FPs,
same ~4 families ≈85% of every profile). Queue consequences: M2.4 (DOM — tsc sources
don't reference it), M3.0 (conformance adoption — optional extra regression net, not
needed for the burn-down), M3.5 (per-file scopes — revisit only if dashboard FPs trace
to cross-file conflation on tsc sources), and all of M4 (nodenext `exports` maps, decl
emitter, JSX, external sourcemaps, project references — none block self-compiling tsc)
moved to the new § "Post-v1 backlog". M3.1–M3.4 stay live but re-scoped from
completeness campaigns to dashboard-driven burn-down: the acceptance bar is the shapes
tsc's source uses, with the corpus suite as the regression net. M5 unchanged —
performance is the directive's second half and starts at v1 compliance.

*(Numbering note: rounds 432–434 below are the `perf/flow-import-resolution` branch's original
rounds 430–432, renumbered at merge — the branch ran in PARALLEL with main's own rounds 429c–431e,
which own those numbers. The perf rounds' FP baselines (1,148 / 1,665) are the branch's pre-merge
numbers; main's concurrent M3.1/M3.2 work independently took the compiler profile to 482.)*

**Re-scope (2026-07-13, owner — round 490): M5 becomes the staged ARCHITECTURE
INVERSION arc (INV.0–INV.7).** The owner reviewed the round-490 architecture
analysis ("question every decision; cross-check tsc/tsgo") and directed: follow the
recommendation, rescope towards the overall goal. Full analysis + phase design:
**`docs/ARCHITECTURE-RETHINK.md`** — read it before ANY M5/INV item. Headline: the
flat-profile micro-opt mode (rounds 482–489, 1–3%/round) is CLOSED; the measured
cost is a multiplier (~512 sequential full-program checker passes × uncached
`getTypeOfExpression` × context-bypassed `nodeTypes` × per-pass scope re-derivation
× non-interned unions), and the fix is the tsc-shaped inversion: bind-everything →
per-file scopes → single-pass demand-driven checking with per-node caches →
canonical type identity/mappers → share-nothing parallel checkers. Owner-approved
same directive: **kotlinx-coroutines-core as a commonMain dependency** (within the
kotlinx.* rule) for the Flow-based concurrent front-end (read+UTF-8→UTF-16 decode on
`Dispatchers.IO`, parse on `Dispatchers.Default`, bounded `flatMapMerge` = the
owner's measured microbench win) and later the parallel checker/emit phases —
streams live at the I/O boundaries; the checker core stays demand-driven
memoization, per the doc's § 4. Old M5.1–M5.7 are superseded/absorbed by the INV
items in the QUEUE below (M5.1 profiling → INV.0; M5.2/M5.3 → INV.5; M5.4 → INV.6;
M5.5/M5.6 → INV.7; M5.7 targets → doc § 6).**

**Round 503 (2026-07-13, same session as 500–502) — INV.3(c) DECOMPOSED from a
measured per-site attribution (probe-and-revert; no code landed).** The
round-502 lesson (per-PASS ≠ per-SITE) applied: tagging the GUESSED hot sites
(`getTypeFromTypeReference` fallback / `getTypeOfIdentifier` fallback /
`getCalleeType` ×3) left 148k of 157k conflated lookups untagged — the guess
list in the old (c) item was wrong (typeRef.fallback measured literally ZERO).
A second probe — 1:200 stack-sampling in the classifier's CONFLATED branch
(`Throwable.stackTraceToString()` is common-stdlib; ~790 samples), TEMP code
reverted after the run — settled it: **~82% of conflated traffic is ONE
family, the enum-discriminant/kind-domain narrowing machinery**
(`kindDomainKeysFromTypeNode` → `enumSwitchKeysFromTypeNode` /
`enumMemberKeysOfTypeNode` / `kindDomainTypeDeclSymbol` /
`resolveEnumSymbolForDiscriminant`, from `narrowByCallPredicate` via
`applyConditionNarrowing` + smaller `filterUnionByEnumDiscriminant` /
`resolveCallOverload` entries) — resolving type names read from FOREIGN AST
nodes (types.ts's union-member `.kind` annotations) while `currentFileLocals`
points at the CHECKING file, which is exactly why the top conflated names are
all types.ts node-interface names (JSDocFunctionType 21.5k / FunctionTypeNode
17.9k / ConstructorTypeNode 17.8k / MappedTypeNode / ConditionalTypeNode).
KEY DESIGN CONSEQUENCE: the per-file-correct key for that family is the
NODE'S OWNING FILE (tsc semantics — a types.ts annotation resolves in
types.ts's scope), now derivable from the INV.2(a) parent chains; a naive
`globalsForFile(currentCheckFileName, …)` flip would silently KILL the
narrowing wherever the checking file doesn't import the name (an FP
regression, not a cleanup). Remainder of the distribution: tagged counts
`identifier.fallback` 3,829 + `propAccess.objExpr` 3,005 +
`typeRef.resolve.ident` 1,942 + `mam.*` 63+63 + `propAccess.base` 13;
sampled small families `checkPrivateMemberAccess`, `getTypeOfIdentifier ←
isCalleeResolvable`, `resolveFlowCalleeDecl ← flowCalleeMayHaveAssertEffects`,
`computeRawTypeOfPropertyAccess ← getCalleeType`,
`typeNodeDefinitelyNonNullish`, `pmrCheckAccess`. The (c) item is rewritten
as four sub-items in dependency order: (i) the node-keyed primitive
(`owningSourceFile(node)` + `lookupPerFileForNode`), (ii) the kind-domain
family flip (~82%, resolution-PRESERVING, node-keyed), (iii) the
current-file-keyed value/callee sites (suppression-only), (iv) the
type-position tail + re-measure to unlock (d). No code change landed (probe
fully reverted, tree byte-equal to the round-502 commits; suite state
carries over: 10,273 / 0 / 3). NEXT: INV.3(c)(i).

**Round 502 (2026-07-13, same session as 500/501) — INV.3(b)(ii) LANDED: the
pilot consumer — INV.3(b) is COMPLETE.** The TS2315/TS2346 heritage-base
"not generic" gate — the `checkTypeArgumentConstraints` pass, the SMALLEST
nonzero pass in the (a) conflated-by-pass table that has DIRECT pass-local
consults (`checkImplicitThis`/`checkEvolvingEmptyArrayImplicitAny` have none;
`checkInterfaceMultiBaseConflicts`' mergedDecls consult is flow-changing, not
suppression-only) — now consults `globals` through the NEW
`globalsForFile(fileName, name)`, THE flip shape for the (c) migration:
return the merged-globals symbol INSTANCE (what keeps a flip byte-identical
for every legitimately visible name — substituting `lookupPerFile`'s return
directly would change symbol identity for lib/script names, since
`perFileScope` holds the first-occurrence script symbol while `globals`
holds the first-declarer merged instance) whenever the name has a per-file
meaning — a name outside `moduleOnlyGlobalNames` (lib / script-file /
augmentation-added global), or a module-only name the file declares or
imports, probed through `lookupPerFile` (an import alias probes non-null
whether or not its target resolves) — and null exactly where the legacy
consult LEAKED a foreign module file's local. Suppression-only at this site
by construction: a conflated hit could only ever EMIT a bogus TS2315/TS2346
(real tsc: an unresolvable heritage base is TS2304 territory, never TS2315).
Supporting infra now always-on: init 1b2 became `computePerFileVisibility`
(the INV.3(a) classifier's set construction hoisted out of the passTiming
gate; publishes `moduleOnlyGlobalNames` = module-file local names MINUS
lib/script/augmentation-visible; the classifier installs on top only when
instrumented; the pre-augmentation key-set snapshot is now unconditional —
one HashSet copy per program). Both mirrored consult sites flipped together
per their kept-in-sync contract (`checkConstraintsInExprWithTypeArgs`'s
TS2315 + `extendsClauseIsNonGeneric`'s TS2346 gate, `fileName` threaded).
**MEASUREMENT LESSON for (c): per-PASS attribution ≠ per-SITE.** The
post-flip instrumented run shows the pass STILL at 11 conflated with the
total lookup count EXACTLY unchanged (2,711,601) — the pass's conflated
traffic comes from DEEPER shared machinery (`checkConstraintsForTypeArgs` →
`getTypeFromTypeNode`), not the direct pass-local consults, which measured
ZERO conflated hits on the compiler profile (also why byte-identity holds
by measurement, with the leak-kill pinned by the local tests instead). A
hot-pass (c) flip must reason per-SITE about which consults inside the pass
carry the conflated traffic. Verified: suite green 10,266 → 10,273 (+7
Inv3GlobalsForFileTest — the two leak-kill tests FAIL on the pre-flip
checker, measured via stash: bogus TS2315/TS2346 from a foreign unimported
non-generic base; the five preservation controls pass on BOTH sides —
own-file / imported / script-file-global bases keep firing, imported-generic
+ same-file TS2346 controls); `--listAll` byte-identical on compiler AND
services (46/46 diagnostics each); bench row in band (26,265 ms self /
1,062 MB, +0.2% vs previous, same 46 env-legit diagnostics). CLAUDE.md
gotcha added (the globalsForFile-not-lookupPerFile flip rule +
mirrored-sites-flip-together); `lookupPerFile`'s KDoc re-pointed at
`globalsForFile` as the consumer-facing wrapper. NEXT: INV.3(c) — flip
resolution families onto the primitive in the (a) tables' order, per-site.

**Round 501 (2026-07-13, same session as 500) — INV.3(b) phase (i) LANDED:
the per-file resolution primitive, additive and unconsumed.**
`lookupPerFile(fileName, name)` (internal) — the lookup the (c) family flips
will substitute for conflated `globals[name]` consults: the file's
`perFileScope` table (own locals ▸ script-file globals ▸ lib), with an
ImportSpecifier-alias own-local resolved onward through the NEW
`resolveImportedSymbolGeneral` — the kind-AGNOSTIC generalization of the
flow-only resolver skeleton (ESM-`.js` strip via `resolveAliasJsModuleSpecifier`,
`export *` barrels via `resolveExportedSymbolThroughStars` — whose
NamedExports arm turns out to cover RENAMED re-exports too — plus
re-import/re-export alias hops, `visited`-guarded, memoized in
`importedSymbolGeneralCache`). ADDITIVE by design: the three kind-specific
legacy variants (fn/namespace/enum) stay untouched — their
per-declaration kind-filter-then-continue semantics differ subtly from
first-resolved-symbol, so delegation would not be behavior-preserving; they
become deletion candidates when their consumers migrate. Never wired into the
general `resolveAlias` (the round-409 TS2315-flood gotcha). **The trap that
cost the round its debugging time: `mergeSymbolTable` FLAG pollution.** The
first cut hopped on `sym.flags.hasAny(Alias)` — but a barrel-imported name's
TARGET symbol (types.ts's `interface Foo`) ACQUIRES the Alias bit when the
importing file's alias merges onto it in `globals` (`existing.flags |=`), so
the resolver hopped INTO the real declaration symbol, found no
ImportSpecifier, and returned null for every import. The fix is the
isValueExport gotcha applied to alias hopping: decide by DECLARATIONS
(`isImportBindingDecl` — ImportSpecifier / ImportDeclaration /
ImportEqualsDeclaration), never flags; a symbol with any real declaration IS
the resolution target. Contract documented in the KDoc: for an imported name
the primitive returns the SAME symbol instance the conflated globals lookup
returned (what makes (c) flips byte-identical); unresolvable/unsupported
alias kinds (default imports, `import * as ns`, `import =`) degrade to the
alias symbol itself; null strictly means "no per-file meaning" (the leak).
Pinned by Inv3PerFileLookupTest — the first local test to construct a
`Checker(options, binderResults)` DIRECTLY (internal visibility), asserting
symbol IDENTITY against the declaring file's binder locals over
direct-`.js` / plain-barrel / renamed-re-export / own-local / script-global /
lib shapes plus the foreign-module-local-is-null and alias-degradation
negative controls; fixture files must be PATH-shaped (`/proj/c.ts`) — flat
corpus-style names defeat directory-relative specifier resolution. Suite
green 10,260 → 10,266 (+6); the primitive is unconsumed by any checker path
(behavior provably unchanged; listAll spot-check clean). NEXT: INV.3(b)(ii)
— the pilot consumer at the (a)-measured lowest-risk site.

**Round 500 (2026-07-13) — INV.3 DECOMPOSED into (a)–(d) + INV.3(a) LANDED:
the conflation-dependency instrumentation and its first measurement.**
Decomposition facts verified in-code before writing the sub-items: the queue's
"buildPerFileScopes never consumed" was STALE — `perFileScope` is consumed at
4 sites (the 17.32b–e flips); the honest migration surface is the ~400 keyed
`globals` consults (373 `globals[` + 22 `in globals` + 8 explicit), and import
aliases free-ride on the conflation because the general `resolveAlias` cannot
follow ESM-`.js`/`export *` barrels/NamespaceImports (the flow-only resolvers
can; the general fallback measured a TS2315×466 flood at round 409 — (b) must
unify them into a primitive consumed only by per-file lookup). **The (a)
mechanism:** `globals` is constructed as `InstrumentedSymbolTable` (get/
containsKey report to an installable hook; LinkedHashMap backing preserved so
iteration order is byte-identical; interface delegation does not forward
equals/hashCode — identity semantics, documented) ONLY when `--passTiming` is
on at construction; `installGlobalsLookupClassifier` (init step 1b2, after the
globals membership settles) classifies each lookup against the per-file model:
TRUE_GLOBAL (no module file declares the name) / SHARED (module + lib/script/
augmentation — presence legit, symbol polluted: the chimera dimension) /
OWN_LOCAL (pure module name, current file's own local via `currentFileLocals`)
/ CONFLATED (pure module name, definitely foreign — the worklist proper) /
UNSCOPED (no file context at the site — also an INV.4 datum) / MISS, with
per-name + per-pass tables for conflated/unscoped in the dump.
**The measurement (compiler profile, 78 files): total 2,711,601 keyed lookups —
miss 1,937,514 (71%!), ownLocal 530,127, CONFLATED 157,060, unscoped 71,820,
trueGlobal 12,148, shared 2,932. Services profile (252 files): total 4,918,242
— miss 3,881,879 (79%), ownLocal 702,793, CONFLATED 216,888 (845 names),
unscoped 97,116, shared 3,989.** Reading: (1) the merged map is probed as a
maybe-fallback everywhere — 71–79% of consults find nothing; (2) conflated
traffic is REMARKABLY concentrated: 608/845 names — the top names are all
compiler/types.ts type names (JSDocFunctionType 21.5k, FunctionTypeNode 17.9k,
ConstructorTypeNode 17.8k, factory 12.1k, MappedTypeNode 7.6k …) reached
through `_namespaces/ts.ts` barrel imports, i.e. TYPE-space alias free-riding
— services adds VALUE-space leaks (`parent` 4.5k, `error` 4.1k — the round-442
`moduleFileLocalVarNames` family) — and 14–15 passes, of which
checkPropertyAccess (66.5k/92.0k) + checkCallExpressionTypes (55.0k/73.0k) +
checkTypeAssignability (28.9k/41.5k) carry 95–96% and are exactly INV.0's
top-3 wall-time passes (4.05 s / 1.99 s / 2.31 s of the 22.1 s init); (3)
ownLocal (the majority of legitimate hits) flips to per-file scope trivially;
(4) unscoped concentrates in checkUnresolvedNames (25.7k) + outside-dispatch
(13.4k); (5) SHARED is tiny even on services (4.0k) — the chimera ecology's
cost is per-lookup bail CHECKS on hot paths, not hit volume. Verification: suite green 10,255 → 10,260 (+5 Inv3GlobalsLookupTest —
on/off diagnostic parity over a module-leak + shared-name + script-global
probe, the class-accounting invariant total == Σclasses, leak-name presence in
the worklist tables, InstrumentedSymbolTable hook/delegation/order contracts,
dump gating); `--listAll` byte-identical BEFORE vs AFTER (off-mode, compiler
profile); bench row in band. Also fixed en route: the stale INV.3 queue claim,
and a grep trap recorded in CLAUDE.md (Checker.kt trips grep's binary
heuristic — source greps need `-a` too; it silently produced the stale claim).
NEXT: INV.3(b) — the per-file resolution primitive.

**Round 499 (2026-07-13, same session as 497/498) — INV.2(d) LANDED: the first
lexical-table CONSUMER — INV.2 "bind the world" is COMPLETE.** The canonical
B83.5 transient-symbol site (`checkPropertyAccessInStatement`'s
ClassDeclaration branch: a block-scoped class is invisible to the conventional
binder, so the `this.X` member check synthesized a fresh
`Symbol(SymbolFlags.Class, …)` per visit) now resolves the class through the
INV.2(c) tables: `currentLexicalScopes` (a per-file checker field set in
`checkPropertyAccess`, declared BEFORE `init` per the init-order gotcha) +
`lexicalScopeSymbol(node, name)` — a parent-chain walk to the nearest scope
binding the name, gated `flags.hasAny(Class)`, with the legacy synthesis kept
as the unindexed-tree fallback. This is the resolution primitive INV.4 will
generalize. **Fidelity + a real fix:** diagnostics are byte-identical on the
compiler AND services listAll A/Bs (46/46 each), the corpus suite is green,
AND a block-level `interface B { extra } class B { m() { this.extra } }`
merge no longer FPs TS2339 — the lexical symbol carries BOTH declarations
(canMerge Class+Interface), which the class-only transient never saw
(measured: the pre-pilot checker emitted the false TS2339; the new
Inv2LexicalConsumerTest pins both directions plus the TS2551
spelling-suggestion variant, proving the lexically-resolved type feeds the
full member machinery). Investigation notes: the OTHER two
`Symbol(SymbolFlags.Class, …)` syntheses are NOT convertible — the B511
clodule recovery's class is main-bound then OVERWRITTEN by last-wins (in
neither table), and the classExpressionAssignment display synthesis names an
anonymous ClassExpression (never a scope binding); a probe also recorded that
this pass's traversal does not descend `while` bodies (pre-existing, both
code paths — the tests use `if`/`for` shapes). Suite 10,251 → 10,255 (+4).
NEXT: INV.3 per-file scoping.

**Round 498 (2026-07-13, same session as 497) — INV.2(c) phase (ii) LANDED:
block-scope containers + class/interface/alias/enum scopes — INV.2(c) is
COMPLETE.** The lexical binder now covers tsc's `IsBlockScopedContainer` set
and the remaining containers: every `Block` that is NOT a function-like's
immediate body (the body shares the function scope — tsc `getContainerFlags`),
`for`/`for-in`/`for-of` headers (header `let`/`const` in the for scope, the
body block a child scope under it), `CatchClause` (binds the catch variable,
destructuring patterns included; the catch block chains under it), and
`SwitchStatement` standing in for tsc's CaseBlock — our AST has NO CaseBlock
node, so the switch statement owns the case-block scope and its EXPRESSION is
routed to the OUTER scope by hand (pushed last so sibling visit order stays
source order, which the first-wins merge semantics rely on). Class
declarations/expressions get scopes (type params; a named class EXPRESSION's
self-name binds inside only; class decorators walk under the OUTER scope),
interfaces/type aliases get type-param scopes, and enums get member-sibling
scopes (`enum E { A = 1, B = A }`): a main-bound enum ALIASES its merged
`exports`; a nested (B83.5-unbound) enum binds scope-space members ALSO
published onto the scope symbol's `exports` — gated `id ≤ −2` so a MAIN
symbol's exports are never touched. **The design dividend: phase (i)'s
`isDirectBodyChild` gates for block-scoped declarations DISSOLVE into a plain
`scope.existing == null` test — once every block-scope container owns a fresh
scope, the current scope IS the correct binding target everywhere (file/module
level stays skipped via the aliasing `existing`); `var` gains the real
`varHoistTarget` walk-up (nearest function-like/file/module boundary).**
Block-nested function declarations bind to the BLOCK (strict/module
semantics — the non-strict hoisting divergence is documented in the KDoc).
Verification: suite green 10,245 → 10,251 (+6; Inv2LexicalScopeTest now 20 —
the phase-(i) negative controls FLIPPED to positive location asserts:
if-block let/class/function in the block scope chained to the fn scope,
for-header `let` in its for scope while the sibling `var` header hoists,
catch destructuring, switch case-clause declarations, nested-bare-block
chains, fn-body-block/ModuleBlock negative controls, class/iface/alias
type-param scopes, main-vs-nested enum aliasing with the exports identity
check); `--listAll` byte-identical vs the round-497 binary; interleaved wall
B/A ×6 both orders NEUTRAL (medians 26,712 before / 26,526 after — after
faster on medians, slower on means via one outlier; noise). Tables remain
UNCONSUMED until INV.4/INV.2(d). NEXT: INV.2(d) — B83.5 dissolution pilots
(convert 1–2 checker transient-symbol sites to consume the new tables).

**Round 497 (2026-07-13) — INV.2(c) phase (i) LANDED: additive lexical binding
for function-like containers.** The Binder gained a second pass
(`bindLexicalScopes`, run after conventional binding) that walks the whole
tree ITERATIVELY (parallel explicit node/scope stacks — a 30k-term binary
chain binds on a plain thread) and builds `BinderResult.lexicalScopes`:
per-nodeId `LexicalScope` tables. Container design: the SourceFile root
ALIASES file locals and a ModuleDeclaration aliases its merged namespace
`exports` — one chained scope level per dotted segment, mirroring the
checker's B512 rule, with outer segments recovered via `symbol.parent` —
while the seven function-like kinds plus `ClassStaticBlockDeclaration` get
FRESH tables holding type params, params (binding patterns recursed; `this`
params excluded — tsc never binds them into locals), a named function
expression's self-name, body-top-level declarations (the function body block
is NOT a block-scope container, tsc `getContainerFlags`), and `var`s hoisted
from ANY block depth (also into file/module scopes for block-nested vars the
main binder's statement-only walk never saw). The function-like's own
decorators walk under the OUTER scope. **The reshuffle firewall: scope
symbols come from `Symbol.scopeSymbol` — a SEPARATE negative id space
(≤ −2, own counter) — so the global `nextId` sequence is untouched;
`declareLexical` mirrors `declareSymbol`'s merge semantics (canMerge reuse,
B505 Class+Class first-wins, param+var redeclaration merge) but never writes
`nodeToSymbol` or the aliased existing tables (a name the main binder
already bound is SKIPPED — attaching the extra declaration would mutate the
shared symbol).** Phase (ii) is deliberately unbound and PINNED by negative
controls: nested-block let/const/class/function, for-header let, catch
variables, case blocks, class scopes. Verification: suite green
10,231 → 10,245 (+14 `Inv2LexicalScopeTest` — flags per decl kind, hoisting,
root-aliasing identity, binding patterns, the zero-global-id-consumption
DELTA PROBE (two binds of identical top-level shape, one with rich bodies,
must consume equal global-id counts), namespace chain identity, plain-thread
deep chain, unindexed-tree guard, rich-fixture smoke); `--listAll`
byte-identical vs the stash-built BEFORE binary (46 diagnostics, compiler
profile); interleaved wall B/A ×6 with BOTH orders: a consistent
second-position-slower artifact appears in each order — position-balanced
means 26,328 vs 26,550 ms (+0.8%), inside the documented drift band. Tables
UNCONSUMED until INV.4/INV.2(d). NEXT: INV.2(c)(ii) block-scope containers +
class scopes, then INV.2(d) B83.5 dissolution pilots.

**Round 496 (2026-07-13, same session as 495) — INV.2(b) LANDED: the pilot
nodeId-array side table — `FlowGraph.flowAt`.** The first consumer of INV.2(a)'s
identity fields: `FlowGraph` carries `flowById`/`nodeById` arrays sized
`sourceFile.nodeCount`, PRE-COMPUTED at construction from the FINISHED
`nodeToFlow` map by a `forEachChild` walk (`array[nodeId] = map[nodeKey(node)]`),
and `flowAt(node)` serves in-tree lookups from the array behind an IDENTITY
ownership check (`nodeById[id] === node`), legacy-map fallback otherwise; all 5
checker read sites migrated. **The design discovery: a naive record-into-
array[nodeId] migration is NOT faithful** — the Long `nodeKey(pos,end)` ALIASES a
wrapper and a same-extent child onto one map entry (last-write-wins) and lookups
for EITHER hit it; pre-computing from the map reproduces the aliasing exactly,
and the identity check routes synthesized copies (nodeId −1 with real extents)
and foreign-file nodes (valid-looking ids) to the exact old path — behavior-
preserving BY CONSTRUCTION. (`nodeTypes` was REJECTED as the pilot: program-wide
`HashMap<TypeNode, Type>` STRUCTURAL keying with no file context at the lookup
sites, and the round-473 cross-file structural-collision ecology sits on top of
it — migrating it is INV.5's (node, mapper) keying, not a drop-in array.)
**Verification:** suite green 10,228 → 10,231 (+3 `Inv2FlowLookupTest`:
per-node fast≡legacy equivalence over the rich fixture incl. aliasing;
ghost-node fallback KEEPS the legacy map hit; foreign-file nodes take the map
path); `--listAll` byte-identical (interleaved). **Measurement (the (b)
deliverable):** interleaved wall B/A ×3 NEUTRAL (medians 25,999 vs 26,177 ms —
inside the noise band); bench row 25,800 ms self / 997 MB (RSS single-run band
840–997 across recent rows; the arrays' true cost ≈ +16 MB on ~1M nodes); JFR: `HashMap.getNode` = ~6.7% of ALL execution samples
but the nodeToFlow slice only ~6/139 of those (~0.3% of wall) — the ARRAY
MECHANISM is validated, and the mass-migration payoff is NOT in more cold
tables: it is in the hot maps the getNode samples actually sit in (walk-internal
memos, checker caches) and ultimately INV.4's per-node expression-type cache.
NEXT: INV.2(c) — full lexical binding, additive (function bodies first).

**Round 495 (2026-07-13) — INV.2(a) LANDED: AST identity foundations.** All 138
node data classes now extend `NodeBase` (`var nodeId = -1`, `var parent: Node? =
null`; deliberately NOT implementing `Node` — a non-sealed direct subtype would
break exhaustive `when` over `Node`); base-class vars sit outside data-class
`equals`/`hashCode`/`copy`, so structural node keys are byte-identical and a
Transformer `copy()` yields an UNINDEXED node; `SourceFile.nodeCount` body var.
New `NodeWalk.kt`: the canonical generic `forEachChild(node) {}` (every
node-typed primary-constructor property of all ~139 kinds; exhaustive sealed
`when`, so a new node CLASS fails compilation until added) + `indexSourceFile`
stamping dense PREORDER nodeIds (SourceFile = 0; a subtree = a contiguous id
range) + parents + nodeCount at the end of `Parser.parse()` — ITERATIVE
explicit-stack (crawl parses run on Dispatchers.Default OFF the deep-stack
thread; a recursive indexer would overflow exactly there). Fields are inert
until INV.2(b) consumes them. **Verification:** suite green 10,218 → 10,228
(+10 local: `Inv2NodeIndexTest` — dense preorder + parent chains + copy-
unindexed + a 30k-term chain indexed on a PLAIN thread (measured nodeCount
60,009 exact via jshell) + negative control; `ForEachChildOracleTest` — the
jvmTest REFLECTION oracle diffing forEachChild against data-class componentN
properties per node, over the kind-dense fixture + JSX fixture + directly-
constructed parser-unreachable kinds + ALL 78 real tsc compiler sources,
>100k nodes, identity-set AND multiset-size agreement); `--listAll`
byte-identical vs the stash-built BEFORE on the compiler profile (46
diagnostics; wall 25.67 → 25.73 s — the indexing walk is noise-level);
bench row 25,430 ms self / 840 MB RSS (−2.5%/−62 MB vs previous row = box
noise band; the per-node nodeId+parent fields cost ~16 MB on ~1M nodes,
invisible in RSS).
**Migration surprises (both now CLAUDE.md gotchas):** (1) the shared
superclass changed Kotlin LUB inference — `parsePropertyName`'s inferred
return type degraded to `Any` (14 downstream type errors; ONE explicit return
type fixed all; the silently-compiling `Any` variant is exactly what the
suite + listAll gates cover). (2) power-assert renders every captured
subexpression's toString on FAILURE — a failing `have(sourceFile.nodeCount >
…)` STACK-OVERFLOWED rendering the 30k-deep tree, and the oracle's `have`
OOM'd building a node-list diagram, masking the real messages; both tests
rewritten render-safe (int/boolean locals, plain `fail()`), after which the
initial sweep "failure" did not reproduce (deterministic green incl. the full
suite — the run-1 verdict is attributed to the assertion-machinery path, not
a forEachChild gap). NEXT: INV.2(b) — migrate ONE hot pos-keyed side table
(Flow's `nodeToFlow` or the checker's `nodeTypes`, per INV.0 evidence) to a
nodeId-indexed array; measure the `HashMap.getNode` JFR delta before mass
migration.

**Round 494 (2026-07-13) — INV.1(e) LANDED: the double parse is dead — the core
reuses the crawl's parses.** The crawl full-parsed every file for specifiers and
`compileParsed` parsed everything again; now ONE parse per file serves both.
Design (as scoped rounds 492/493): (1) `computeParserFlags(fileName, content,
options)` in TypeScriptCompiler.kt is the single source of truth for the
option-derived `Parser` flags (`forceJsx` / `topLevelAwait` incl. the
`fileLooksLikeModuleForAwait` content scan / `needsJsxFlag` / `noImplicitAny`) —
the core's single-file, emitDeclarationOnly-multi, and main multi-file sites all
route through it, and so does the crawl (`parseForCrawl`, which replaced
`extractSpecifiers`; specifiers now read off `preParsed.sourceFile.
moduleSpecifiers`). (2) `ParsedSource.preParsed: Map<String, PreParsedFile>`
carries `(content, flags, sourceFile, parser diagnostics)` from
`ProjectCompiler.build` (which hoists `emitOptions` above the crawl so crawl
flags are computed from the SAME options the core receives — verified
`effectiveModule` and every flag input is independent of the core's later
`packageJsonTypes` copy). (3) The core's multi-file parse site reuses an entry
ONLY on an exact content + flags match (`takeIf`), else parses fresh — reuse is
a pure optimization by construction; opt-in PassTiming counters
(`preParseReused`/`preParseFresh`, `--passTiming` dump line) make the match
observable. **Verification:** suite green 10,212 → 10,218 (+6 local
`Inv1PreParseReuseTest`: a deliberately-lying sentinel tree proves reuse FIRES
(the only externally visible identity signal), flags-mismatch and
content-mismatch negative controls re-parse, the real driver path reuses 2/2
under an option-driven flag (module es2022 makes `topLevelAwait` option-only —
a default-flag crawl would read 0), native top-level `await` flows through
check+emit off the reused tree, and the string/corpus path parses fresh);
`--listAll` byte-identical vs the stash-built BEFORE on compiler (46) AND
services (46, 252 files); reuse fires 78/78 on the bench compiler profile.
**Wall-clock: neutral within noise** on interleaved A/B (compiler BEFORE median
25,455 ms vs AFTER 25,364 ms; services 36,605 vs 36,466 with one adverse pair) —
the removed core-parse leg is small (~0.2–0.5 s hot) next to the 21 s checker,
and the win is architectural: ONE canonical tree per file is what INV.2 hangs
per-file `nodeId` side tables off. SESSION TRAP (memory updated): a fully GREEN
`./gradlew jvmTest` printed NO "tests completed" line, so the protocol's grep
pipeline exit-1'd and the task notification claimed failure — the XMLs (10,218/0)
were the truth; parse XMLs before reacting to a "failed" suite notification.
NEXT: INV.2 bind-the-world (full lexical binding, nodeIds, array-indexed side
tables — dissolves B83.5).

### QUEUE — work top-to-bottom; promote unblockers per protocol

(Restored 2026-07-12, round 481 — the queue/backlog/inventory sections had been
swept into PLAN-PHASE-5-HISTORY.md by an over-eager session-note trim; they are
LIVE structure, not history. v1's offline-verifiable legs LANDED at round 481, so
M5 is now the active arc per the owner directive; the Post-v1 backlog below is the
"any TypeScript project" horizon and stays parked until the owner re-scopes. The
M1–M3 campaign items still unchecked in the history file (M2.2/M2.3/M3.1–M3.4/M1.12)
hit their re-scoped v1 acceptance bar — "the shapes tsc's source uses" — when the
burn-down reached zero real FPs; reviving their full-completeness form is a
backlog-horizon decision, not queue debt.)

**EP — Emit parity (owner-authorized 2026-07-12: "output parity, including reported errors").**
The offline v1 DoD checked emit COMPLETENESS (all files emitted, exit 0) but not
emit-BYTE parity with tsc. The round-483 emit diff (`scripts/emit-diff-tsc.sh`, xtsc
vs npm `tsc@6.0.3` on the `compiler` profile) found 8/78 byte-identical, 70/78
differing — but **none are miscompiles**; xtsc's output is semantically correct and
runnable. Three systematic families explain nearly all changed lines (sequenced
cheap-first to shrink the diff before tackling the hard cross-file one):

- [x] **EP.3 Logical/nullish-assignment downleveling** (`||=`/`&&=`/`??=` below
  ES2021). DONE round 484 (2026-07-12): `Transformer.downlevelLogicalAssignment` —
  `a ||= b` → `a || (a = b)` etc., with side-effecting property/element receivers
  captured into temps (`(_a = obj())[_b = key()] || (_a[_b] = 6)`, tsc-faithful temp
  naming). ~284 sites in the compiler profile. Gated `effectiveTarget < ES2021`;
  corpus has ZERO files exercising these operators so it's pinned by
  `LogicalAssignmentDownlevelTest` only. KNOWN RESIDUAL: a `??=` target BELOW ES2020
  keeps a native `??` (not further downleveled — ES2020 is the tested/dashboard
  target); close when a sub-ES2020 `??=` case appears.
- [ ] **EP.2 Multi-line expression printer formatting.** Match tsc's operator/`:`
  placement (line-end vs line-start) and indentation when wrapping long
  `||`/`&&`/ternary chains. Mechanical Emitter work, no cross-file dependency, but
  HIGHER corpus-regression risk (touches the printer that the green corpus pins) —
  do it with the emit-diff gate in place and verify the full suite after each step.
- [ ] **EP.1 Cross-module const-enum inlining** (highest impact, ~93% of the changed
  lines in files like utilities.js). xtsc inlines SAME-FILE const enums but keeps
  `mod.Enum.Member` for const enums imported across modules; tsc inlines to
  `VALUE /* Enum.Member */` (numeric AND string-valued). Needs the checker to resolve
  imported const-enum values whole-program. Biggest/hardest (cross-file), collapses
  most of the diff. NOTE: xtsc's form still RUNS (preserveConstEnums keeps the enum
  objects) — this is byte-fidelity, not correctness.
- [ ] **EP.0 Wire the emit-diff gate into the dashboard.** `scripts/emit-diff-tsc.sh`
  exists (reports identical/differing + family signals). Ideal reference is a tsc
  BUILT AT THE PINNED COMMIT (npm tsc adds version noise to the small residual tail,
  esp. emitHelpers.js helper bodies); decide whether to build+cache the pinned tsc or
  accept the version-stable family signals. Re-run after EP.2/EP.1 to track the diff
  shrinking.

Session note (round 484) has the full family breakdown + methodology.

**INV — the M5 architecture-inversion arc (re-scoped 2026-07-13, owner; supersedes
M5.1–M5.7 — mapping and full design in `docs/ARCHITECTURE-RETHINK.md`, READ IT FIRST).**
Ground rules for every INV item: corpus suite green + 8-profile FP floors unchanged +
`--listAll` byte-diff empty for behavior-preserving steps + a bench TSV row per landed
item; decompose into the smallest standalone suite-gated commits; micro-opt rounds
against the flat profile are CLOSED (only an INV.0-evidenced ≥5% single lever may
interrupt the arc).

- [x] **INV.0 Instrument the multiplier.** DONE round 491 (2026-07-13):
  `PassTiming.kt` + non-inline `pass(name) {}` around all 514 init dispatch calls +
  the three counters (`getTypeOfExpression` calls/distinct with per-pass attribution,
  `nodeTypes` cacheable/bypassed/hit, depth-0 flow walks at `flowWalkWithTripCheck`),
  behind the `--passTiming` CLI flag; off-mode byte-identical (listAll A/B + wall
  parity) + suite green (+7 local). The table (round-491 session note): checker-init
  = 83% of wall; top-3 passes 38.6% (property-access / assignability / call-types,
  458k of 595k getTypeOfExpression calls, 84k flow walks — 68% from
  checkPropertyAccess); 474 sub-100 ms passes sum 36.5% = the multiplication tail.
  That note's cost-ordered worklist IS the INV.4 migration order.
- [x] **INV.1 Concurrent front-end — the owner's Flow beachhead (owner-approved
  kotlinx-coroutines-core dependency, 2026-07-13).** Sub-steps: (a) DONE round 492 —
  the dep was already in commonMain; landed the `runCompilerPipeline` expect/actual
  seam (JVM `runBlocking`) + the import-graph crawl as a cold sequential Flow
  (`crawlImportGraph`, ProjectCompiler) with the load-bearing emission-order
  contract documented at the seam (suite +3, listAll A/B byte-identical); (b) DONE
  round 493 — read+decode on `Dispatchers.IO` (`pipelineIoDispatcher`
  expect/actual), extraction parse on `Dispatchers.Default`, bounded
  `flatMapMerge(16)` per frontier (`readAndScanBatch`); resolution + emission stay
  sequential per frontier so emission stays first-discovery order (the binder stays
  sequential; parser audited — no shared mutable state); (c) DONE round 493 —
  corpus green (+6 local) + 3× `--listAll` byte-identical vs the (a) binary; (d)
  DONE round 493 — interleaved A/B −0.8 s (~3%) on the compiler profile + bench
  TSV row.
- [x] **INV.1(e) Kill the double parse — reuse the crawl's parses in the core.**
  DONE round 494 (2026-07-13): `computeParserFlags` (the shared single source of
  truth for the option-derived `Parser` flags, used by the core's parse sites AND
  the crawl), `ParsedSource.preParsed` carrying `PreParsedFile(content, flags,
  sourceFile, diagnostics)`, and the core's multi-file site reusing an entry ONLY
  on an exact content+flags match (else re-parse — reuse is a pure optimization).
  Verified: suite +6 (Inv1PreParseReuseTest — sentinel-tree reuse proof + both
  mismatch gates + driver-path counters), `--listAll` byte-identical on compiler
  AND services, reuse fires 78/78 (`--passTiming` counters), interleaved wall A/B
  neutral within noise on both profiles (the parse leg is small next to the
  checker; the point is one canonical tree per file — the INV.2 enabler).
  CLAUDE.md gotcha: a new option-derived Parser argument must extend
  `ParserFlags`, never a parse site inline, or the match reuses a wrong tree.
- [x] **INV.2 Bind the world** — COMPLETE round 499 (all four sub-items landed;
  the tables' mass consumption is INV.4's migration). Decomposed round 494
  (facts verified in-code:
  `Node` is a sealed interface + ~138 data classes with single-interface supertypes
  `) : Expression/Node/TypeNode/Statement/Declaration/ClassElement`; there is NO
  generic child-walk anywhere; nodes have no parent/id fields; `Symbol.id` is a
  GLOBAL companion `nextId++` (Types.kt:116–127, the ~350-test reshuffle anchor);
  `nodeKey` is the cross-file-colliding `(pos<<32)|end`). Work the sub-items in
  order, one commit each:
  - [x] **INV.2(a) AST identity foundations.** DONE round 495 (2026-07-13):
    `NodeBase` (nodeId/parent, NOT implementing Node — preserves sealed-`when`
    exhaustiveness) + 138 supertype edits + `SourceFile.nodeCount`; canonical
    `forEachChild` (exhaustive sealed `when`) + iterative preorder
    `indexSourceFile` hooked into `Parser.parse()`. Pinned by the jvmTest
    reflection oracle (`ForEachChildOracleTest` — componentN diff over fixtures +
    all 78 real tsc sources) + `Inv2NodeIndexTest` (dense preorder / parent
    chains / copy-unindexed / 30k-chain-on-plain-thread). Suite +10 (10,228),
    `--listAll` byte-identical, wall neutral. Gotchas: NodeBase LUB trap +
    power-assert node-toString trap.
  - [x] **INV.2(b) Pilot consumer.** DONE round 496 (2026-07-13):
    `FlowGraph.flowAt` — nodeId arrays pre-computed from the finished map
    (preserves the nodeKey extent-ALIASING) + identity ownership check
    (synthesized/foreign nodes take the legacy path); 5 checker sites migrated;
    suite +3, listAll byte-identical, wall neutral. JFR verdict: getNode ≈6.7%
    of samples but nodeToFlow only ~4% of that slice (~0.3% wall) — mechanism
    validated; the mass-migration targets are the HOT maps (walk memos, INV.4
    per-node type cache), not more cold tables. `nodeTypes` rejected as pilot
    (structural cross-file keying — INV.5 territory).
  - [x] **INV.2(c) Full lexical binding, additive.** Scope symbols from a SEPARATE
    id space (never the global `nextId` sequence — the reshuffle hazard); existing
    `locals`/`globals` byte-unchanged; new tables unconsumed until INV.4.
    - (i) DONE round 497 (2026-07-13): function-like containers —
      `bindLexicalScopes` (Binder.kt) walks the whole tree iteratively after
      conventional binding, building per-nodeId `LexicalScope`s
      (`BinderResult.lexicalScopes`): SourceFile root aliases file locals,
      ModuleDeclaration aliases the merged exports (chained per dotted segment,
      the B512 rule), the 7 function-like kinds + static blocks get fresh tables
      (type params, params minus `this`, fn-expr self-name, body-top-level
      decls, `var`s hoisted from any block depth). `Symbol.scopeSymbol` mints
      ids ≤ −2; a delta-probe test pins zero global-id consumption. Suite +14
      (Inv2LexicalScopeTest), listAll byte-identical, interleaved wall
      position-balanced +0.8% (noise band).
    - (ii) DONE round 498 (2026-07-13, same session): block-scope containers —
      every Block that is not a function-like's immediate body, for/for-in/for-of
      headers, CatchClause (binds the catch variable, destructuring included),
      SwitchStatement standing in for tsc's CaseBlock (our AST has none — the
      switch EXPRESSION routes to the OUTER scope by hand) — plus class scopes
      (type params; named class-expression self-name; class decorators outer),
      interface/type-alias scopes (type params), and enum scopes (aliasing
      main-bound exports; nested enums bind scope-space members also published
      on the scope symbol's exports, gated `id ≤ −2` so main symbols stay
      untouched). Design dividend: the phase-(i) `isDirectBodyChild` gates for
      block-scoped declarations DISSOLVE into `scope.existing == null` (every
      fresh scope IS the correct nearest block-scope container); `var` gains the
      real `varHoistTarget` walk-up. Block-nested function declarations use
      strict/module semantics (bind to the block). Suite +6 (20 total in
      Inv2LexicalScopeTest — the phase-(i) negative controls flipped to
      positive location asserts), listAll byte-identical, interleaved wall ×6
      both orders neutral.
  - [x] **INV.2(d) B83.5 dissolution pilots.** DONE round 499 (2026-07-13): the
    canonical site — `checkPropertyAccessInStatement`'s ClassDeclaration branch —
    now resolves a block-scoped class via `lexicalScopeSymbol` (parent-chain walk
    over `currentLexicalScopes`, set per file in `checkPropertyAccess`; legacy
    transient synthesis kept as the unindexed-tree fallback). Fidelity proven:
    suite green, listAll byte-identical on compiler AND services; and the pilot
    FIXES a real FP — a block-level `interface B` + `class B` merge now
    contributes interface members to `this` (the transient class-only symbol
    could not see them; measured: the pre-pilot checker emitted a false TS2339).
    Candidate analysis: the other two `Symbol(SymbolFlags.Class, …)` syntheses
    are NOT B83.5 scope-binding shapes and stay — the B511 clodule recovery
    (the class symbol is main-bound then OVERWRITTEN by last-wins, so it is in
    neither table) and the classExpressionAssignment display synthesis (a
    ClassExpression is never a scope binding). Mass consumption of the tables
    (the ~59 synthesis sites, `buildNestedFunctionMap`, the per-pass scope
    machinery) is INV.4's migration proper.
- [ ] **INV.3 Per-file scoping** — decomposed round 500 (facts verified in-code:
  `perFileScope` EXISTS and is already consumed at 4 sites — the 17.32b–e flips
  (TS2663-vs-TS2301, TS2552 candidate pool, resolveExpressionToSymbol, file-root
  TS2304) — so the earlier "never consumed" note was stale; the remaining
  migration surface is ~400 keyed `globals` consults; import aliases free-ride on
  the conflation because the general `resolveAlias` cannot follow ESM-`.js`
  specifiers / `export *` barrels / NamespaceImports — the FLOW-ONLY resolvers
  can, and the general-fallback variant measured a TS2315×466 flood at round
  409). End state: module files resolve own-locals + imports + true globals;
  the `mergeSymbolTable` conflation is retired for module files; the conflation
  ecology is deleted. Also lays the cross-file value-resolution groundwork EP.1
  needs. Work the sub-items in order, one commit each:
  - [x] **INV.3(a) Instrument the conflation dependency.** DONE round 500
    (2026-07-13): `globals` constructed as `InstrumentedSymbolTable` under
    `--passTiming` (plain map otherwise — zero added code on the hottest map);
    every keyed lookup classified against the per-file visibility model
    (TRUE_GLOBAL / SHARED / OWN_LOCAL / CONFLATED / UNSCOPED — see
    `GlobalsLookupClass`) by a classifier installed after init step 1b, with
    per-name + per-pass conflated/unscoped tables in the dump. Measured
    (compiler / services profiles): 2.71M / 4.92M keyed lookups — 71% / 79%
    MISSES (globals probed as a maybe-fallback everywhere), ownLocal
    530k/703k (flips to per-file trivially), CONFLATED 157k/217k concentrated
    in 608/845 names (almost all `types.ts` type names reached through barrel
    imports; services adds the round-442 value-space leaks `parent`/`error`)
    and 14–15 passes with the top 3 = 95–96% of conflated traffic = INV.0's
    top-3 wall passes (checkPropertyAccess / checkCallExpressionTypes /
    checkTypeAssignability), SHARED only 2.9k/4.0k (the chimera ecology's
    cost is per-lookup bail checks, not hit volume), unscoped 71.8k/97.1k
    (checkUnresolvedNames + outside-dispatch). Worklist: (b)'s primitive must
    resolve barrel-imported TYPE names; (c) starts at the three hot passes.
    Suite +5 (Inv3GlobalsLookupTest), `--listAll` byte-identical (off-mode),
    bench row in band.
  - [x] **INV.3(b) Per-file resolution primitive.** COMPLETE round 502:
    - (i) DONE round 501 (2026-07-13): `lookupPerFile(fileName, name)`
      (internal, unconsumed by checker paths) — perFileScope lookup with an
      ImportSpecifier-alias local resolved onward through
      `resolveImportedSymbolGeneral` (the kind-AGNOSTIC generalization of the
      flow-only resolver skeleton: ESM-`.js` strip + `export *` barrels +
      renamed re-exports via the star walk's NamedExports arm + re-import
      hops; memoized `importedSymbolGeneralCache`; ADDITIVE — the three
      kind-specific legacy variants stay untouched, their per-decl
      kind-filter-then-continue semantics differ; never wired into
      `resolveAlias` per the round-409 flood gotcha). KEY TRAP hit and
      pinned: mergeSymbolTable FLAG pollution means an Alias flag cannot
      identify an import alias — a barrel-imported name's TARGET symbol
      acquires the Alias bit from the importing file's merge, so the hop
      test must be declaration-based (`isImportBindingDecl` — the
      isValueExport gotcha applied to alias hopping). Degradations
      documented in the KDoc: unresolvable import / default-import /
      `import * as ns` / `import =` aliases return the alias symbol itself
      (callers keep their existing handling — extend when a (c) flip needs
      them); null strictly means "no per-file meaning" (the conflation
      leak). Pinned by Inv3PerFileLookupTest (direct
      `Checker(options, binderResults)` construction — a first for local
      tests — asserting symbol IDENTITY with the declaring file's binder
      locals across direct-`.js`/barrel/renamed-re-export/own-local/
      script-global/lib shapes + the foreign-module-local null and
      alias-degradation negative controls).
    - (ii) DONE round 502 (2026-07-13): pilot consumer — the TS2315/TS2346
      heritage-base "not generic" gate (`checkTypeArgumentConstraints`, the
      smallest nonzero pass in the (a) conflated-by-pass table with DIRECT
      pass-local consults) resolves through the NEW
      `globalsForFile(fileName, name)`, THE (c) flip shape: return the
      merged-globals INSTANCE whenever the name has a per-file meaning (a
      non-module-only name, or a module-only name the file declares/imports
      — probed via `lookupPerFile`; substituting the primitive's return
      directly would change symbol identity for lib/script names), null
      exactly where the legacy consult leaked a foreign module file's local
      (suppression-only at this site: real tsc never emits TS2315 for an
      unresolvable base). Supporting infra always-on: init 1b2 became
      `computePerFileVisibility` — publishes `moduleOnlyGlobalNames`
      (module-file local names minus lib/script/augmentation-visible), the
      INV.3(a) classifier installs on top of the same sets. Both mirrored
      consult sites flipped together (kept-in-sync contract); the conflated
      branch never touches `globals`, so the `--passTiming` conflated
      tables keep measuring only UN-migrated traffic. MEASUREMENT LESSON
      for (c): the post-flip instrumented run shows the pass STILL at 11
      conflated with the total lookup count EXACTLY unchanged (2,711,601)
      — the pass's conflated traffic comes from DEEPER shared machinery
      (`checkConstraintsForTypeArgs` → `getTypeFromTypeNode`), not the
      direct pass-local consults, which measured ZERO conflated hits on
      the compiler profile. Per-PASS attribution ≠ per-SITE: a hot-pass
      (c) flip needs per-site reasoning about which consults inside the
      pass actually carry the conflated traffic. Suite +7
      (Inv3GlobalsForFileTest — both leak-kill tests FAIL on the pre-flip
      checker, verified via stash; five preservation controls pass on
      both); `--listAll` byte-identical on compiler AND services.
  - [ ] **INV.3(c) Flip resolution families onto the primitive** — decomposed
    round 503 from a MEASURED per-site attribution (a temporary 1:200
    stack-sampling probe on the classifier's CONFLATED branch, ~790 samples,
    probe reverted — evidence in the round-503 session note). The guessed
    site list above was WRONG: `getTypeFromTypeReference`'s globals fallback
    measured ZERO conflated hits and `resolveTypeNameToSymbol`'s Identifier
    entry only ~1.2% — the actual distribution:
    **~82% is ONE family, the enum-discriminant/kind-domain narrowing
    machinery** (`kindDomainKeysFromTypeNode` → `enumSwitchKeysFromTypeNode` /
    `enumMemberKeysOfTypeNode` / `kindDomainTypeDeclSymbol` /
    `resolveEnumSymbolForDiscriminant`, reached from `narrowByCallPredicate`
    via `applyConditionNarrowing`, plus smaller entries from
    `filterUnionByEnumDiscriminant`/`resolveCallOverload`), which resolves
    type names read from FOREIGN AST nodes — types.ts's union-member `.kind`
    annotations — while `currentFileLocals` points at the CHECKING file
    (exactly the top conflated names: JSDocFunctionType / FunctionTypeNode /
    ConstructorTypeNode / MappedTypeNode / ConditionalTypeNode). The
    per-file-correct key there is the NODE'S OWNING FILE (tsc semantics: a
    types.ts annotation resolves in types.ts's scope), NOT
    `currentCheckFileName` — a naive `globalsForFile(currentCheckFileName,…)`
    flip would silently kill narrowing in files that don't import the name.
    The rest: `identifier.fallback` ~3.8k + `propAccess.objExpr` ~3k (tagged
    counts), `checkPrivateMemberAccess`, `getTypeOfIdentifier ←
    isCalleeResolvable`, `resolveFlowCalleeDecl ←
    flowCalleeMayHaveAssertEffects`, `computeRawTypeOfPropertyAccess ←
    getCalleeType`, `typeNodeDefinitelyNonNullish`, `pmrCheckAccess`,
    `mam.objectExpr`/`mam.recvSym` (~63 each). Sub-items, one commit each,
    every flip suite+listAll-gated on compiler AND services:
    - (i) **The node-keyed resolution primitive**: `owningSourceFile(node)`
      via the INV.2(a) parent chains (indexed trees only; unindexed →
      null → legacy) + `lookupPerFileForNode(node, name)` =
      `globalsForFile(owningFile, name)` — the resolution INV.3(d) needs
      for every foreign-node annotation read. Pin with a direct-construction
      test (Inv3PerFileLookupTest pattern).
    - (ii) **Flip the kind-domain/enum-discriminant family** (~82% of
      conflated traffic) onto the node-keyed primitive — the annotation
      node is in hand at every one of those sites; preserving resolution
      (not nulling) is the acceptance bar, listAll byte-identity the gate.
    - (iii) **Flip the current-file-keyed value/callee sites**
      (identifier.fallback, propAccess.objExpr/base/root, callee.ident,
      mam.*, pmrCheckAccess, resolveFlowCalleeDecl, isCalleeResolvable) —
      these read names from the CURRENT file's own AST, so
      `globalsForFile(currentCheckFileName/fileName, name)` is the right
      key; suppression-only where the name classifies conflated.
    - (iv) **Flip the type-position tail** (typeRef.resolve.ident ~1.9k,
      typeNodeDefinitelyNonNullish's globals fallback — the round-424
      barrel-alias fallback already restricted to interface/class/enum) and
      re-run the instrumented measurement; conflated-by-pass should
      approach zero, unlocking INV.3(d).
  - [ ] **INV.3(d) Retire the merge + delete the ecology.** Stop merging
    module-file locals into `globals`; delete `moduleFileLocalVarNames`,
    `conflatedTypeAliasFiles`, `conflatedInterfaceFiles`,
    `conflatedEnumFileSubsets`, the per-file interface views, and the chimera
    bails — walker-by-walker, each deletion suite- and listAll-gated (each
    removes hot-path work from `checkMemberAccessMissing`).
- [ ] **INV.4 Single-pass check spine.** `checkSourceFileOnce` per-node dispatch;
  migrate walker families in INV.0's cost order — every migration deletes a full-tree
  pass and its private scope machinery. Once ONE authoritative walk state exists, land
  the two things that are unsound today: a per-node expression-type cache, and flow
  narrowing folded into reference typing once (collapsing the rounds-408–479
  per-consumer wiring). The long middle — plan as many small items; corpus + listAll
  gate every family move.
- [ ] **INV.5 Canonical types + explicit instantiation** (absorbs M5.2/M5.3). Intern
  unions/intersections by sorted member-id key; literal interning; explicit mapper
  objects replace the ambient `currentTypeAliasArgs`/TP-scope contexts; instantiated
  members cached ON the `Type.Reference` (delete `resolveGenericPropertyType`
  fresh-minting + its depth-4 OOM cap); `nodeTypes` keyed (node, mapper) — always
  valid; then open `canUseTypeEngine`'s generic gate and DELETE superseded pin walkers.
- [ ] **INV.6 Parallelism** (absorbs M5.4). Share-nothing checker workers per
  `docs/parallel-caching.md` (trivially partitionable once INV.4 gives a per-file
  check entry); parallel emit on Default + IO write sink; deterministic partition +
  merge via the existing diagnostic sort. Structured concurrency from INV.1.
- [ ] **INV.7 Productization** (absorbs M5.5/M5.6). Native re-enable (the big-input
  GC inversion should largely dissolve post INV.4/5); watch mode driven by a
  file-event Flow; `.tsbuildinfo`-style incremental reuse.

Numeric targets (proposed, doc § 6): post INV.4/5 single-threaded compiler profile
≤ 10 s (≈ JS tsc) + harness RSS ≤ 1 GB; post INV.6 compiler ≤ 5 s on 4 cores;
INV.7 stretch: native cold ≤ 2× tsgo.

### Post-v1 backlog — the "any TypeScript project" horizon (parked 2026-07-03)

The top-to-bottom loop SKIPS this section until v1 (the 8 tsc-source profiles at zero
FPs) lands. None of these block self-compiling tsc. Each returns to the live queue
when v1 lands — or earlier if a live item genuinely needs one (promote per protocol,
with a session note saying why). Item IDs are stable; session notes reference them.

- [ ] **M2.4 DOM libs as an opt-in set** (dom.generated.d.ts is 1 MB+ — measure the
  parse/bind cost; ties into the shared-snapshot design). tsc's sources don't
  reference DOM — post-v1.
- [ ] **M3.0 Conformance generator extension.** Extend `generateTypeScriptTests` with
  a per-category allowlist for `tests/cases/conformance/` (5,907 files; keep all tsgo
  set-B filters). Start with the categories matching M3.1 (types/typeParameters,
  types/typeRelationships, expressions/functions). Each category lands only when its
  failures are triaged into queue items — never leave a category half-red without
  notes. Owner approval (2026-07-02) stands; optionally pull in early as an extra
  regression net if an M3 campaign wants the coverage.
- [ ] **M3.5 Per-file scopes** (Blocker #3: stop merging all file locals into
  `globals`; per-file scope construction with explicit import visibility). Revisit
  before v1 ONLY if dashboard FPs trace to cross-file scope conflation on tsc sources.
- [ ] **M4.1 Full nodenext resolution**: package.json `exports`/`imports` maps,
  symlink/realpath (pnpm layouts), `typesVersions`, package self-references. (The tsc
  repo itself uses relative imports + @types — unused for v1.)
- [ ] **M4.2 Real declaration emitter.** `.d.ts` output for arbitrary code (the corpus
  strips most `.d.ts` sections, so almost none exists today; `declaration: true` is
  table stakes for "any project"). Test bed: conformance decl baselines + self-compile
  d.ts diffing. Pull into v1 only if the owner defines "fully compile tsc" to include
  declaration output.
- [ ] **M4.3 JSX end-to-end** (`jsx: react-jsx`/`react`/`preserve` transforms on real
  React-shaped code).
- [ ] **M4.4 External sourcemaps** (`.js.map` files; inline maps exist).
- [ ] **M4.5 Decision point**: project references / composite / incremental scope
  (tsgo supports them; needed for large monorepos — decide build vs defer with owner).
- [ ] **M4.6 `package.json "type": "module"` module-format detection in
  `ProjectCompiler`** (found compiling zod, 2026-07-07): under `module: NodeNext`
  with a `"type": "module"` package.json, real tsc emits ESM but we emit CJS — the
  `collectPackageJsonTypes` machinery exists only for the multi-file TEST-source path
  and is not wired into the on-disk project pipeline. Repro: zod (see M4.7); the
  emitted CJS only runs in a `"type": "commonjs"` context. Unused for v1 (the
  tsc-source bench project has no package.json → CJS default is correct there).
- [ ] **M4.7 zod as a second dashboard profile** (validated 2026-07-07, round 432
  session note): shallow-clone `github.com/colinhacks/zod`, compile
  `packages/zod/src` (107 files, ~31k LOC) via a `tsconfig.xtsc.json` extending zod's
  real `.configs/tsconfig.base.json` (strict, exactOptionalPropertyTypes,
  noUnusedLocals, NodeNext), include `src/**/*.ts`, exclude tests/benchmarks — real
  tsc 6.0.3 reports 0 errors on it, so every xtsc diagnostic is an FP. Baseline
  2026-07-07: 1,665 FPs (top: TS7006×447 contextual params, TS2694×284 namespace
  members via `export *` barrels, TS7029×211 switch-fallthrough, TS2344×182), 0
  crashes, all 107 files emit, output passes a runtime smoke test. Complements the
  tsc-source profiles: stresses generic method chaining + noFallthroughCasesInSwitch,
  which tsc's own source doesn't.

### Offline asset inventory (verified 2026-07-02)

- `typescript-repo` object DB is complete (sparse checkout, full objects): any
  `src/**` path extractable via `git archive HEAD <path>`; `src/lib/` holds the 110
  real lib `.d.ts` files; `tests/cases/conformance/` holds 5,907 `.ts`/`.tsx` cases.
- Node/tsc/tsgo are NOT currently installed — differential testing (M0 optional) and
  real `@types/node` (M1.3) wait for network.
- The benchmark project cache lives under `build/bench/` (cheap to rebuild); results
  TSVs under `bench/` (gitignored, machine-specific).
