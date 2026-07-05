# PLAN-PHASE-5 session-note history

**Round 392 (2026-07-04) — M2.2 burn-down #2: the TS2728 lib-file-attribution
cluster. Real-lib A/B recount 38 → 34 corpus failures (libMembers + externModule +
errorMessageOnObjectLiteralType fixed, initializedDestructuringAssignmentTypes also
cleared), zero corpus regressions, suite 8,971 / 0 / 3 (+3 local).** Sampled a fresh
12-test slice of the 38; three (externModule, errorMessageOnObjectLiteralType, plus
last session's libMembers) shared ONE root cause: under `useRealLibs` the default
library is SPLIT across many files (`lib.es5.d.ts`, `lib.es2015.core.d.ts`, …), each
parsed independently so positions OVERLAP (every file's nodes start at 0). The TS2728
"declared here" related-info builders resolved the declaring file by POSITION
(`resolveDeclarationSourceFile`), which under multi-file libs cannot disambiguate AND
false-matches a large USER file whose text happens to span the lib position (a lib
`sub` decl's position landed on `subby` in libMembers.ts → `libMembers.ts:15:19748`
instead of `lib.es2015.core.d.ts:--:--`). Fix: NODE-first attribution — `bindRealLibs`
populates `realLibDeclFile: Map<Node, String>` (every lib statement / interface-class
member / inner var-decl node → its DIST fileName), and the three TS2728 builders
(`findDeclarationRelatedInfo`, the property-suggestion site, `createPropertyDeclaredHereRelatedInfo`)
consult `libFileOfDecl(decl)` BEFORE the position path. Real-libs-scoped by
construction: the map is EMPTY under the embedded lib (single file) → embedded path
byte-identical (guaranteed, verified by the full embedded gate). The existing
`DEPRECATED_STRING_HTML_HELPERS` override (sub/sup/… → `lib.es2015.core.d.ts`) still
fires on top — it only needs `isLib` true, which the map now guarantees. 3 local tests
(RealLibsTs2728FileTest): non-es5 member → its real lib file (not es5), es5 member →
`lib.es5.d.ts`, and a USER member control (still the user file with a real position —
proving the map is lib-only). No self-compile dashboard delta (corpus-A/B fix; default
stays off). **Round-392 triage of the other 9 sampled failures (for the burn-down):**
correctOrderOfPromiseMethod/narrowingPastLastAssignment/keyRemappingKeyofResult = extra
TS2322 from richer lib generics (Promise.all const-tuple, evolving-array concat, mapped
key-remap → M3 engine); omitTypeHelperModifiers01 = SWAP TS2540↔TS2322 (Omit modifier +
readonly); mergedClassNamespaceRecordCast/interfaceAssignmentCompat/divergentAccessorsTypes6
= MISSING (Record-cast overlap + documented walkers under-fire); builtinIterator =
duplicate TS2515 (short vs full `Iterator<…>` display); doYouNeedToChange… = `Promise<T>`
vs `Promise<unknown>` display; keywordExpressionInternalComments = we emit NOTHING under
real libs (investigate — possible exception, unusual).

**Round 391 (2026-07-04) — M2.2 first burn-down: the real-lib A/B failing set
drops 40 → 38 (arguments + unaryOperatorsInStrictMode), zero corpus regressions,
suite 8,968 / 0 / 3 (+3 local).** Method: temp-flipped the `useRealLibs` default
to true, ran a diverse 8-test slice of the 40, extracted the actual diffs from the
result XMLs, and triaged them into distinct failure modes (recorded in the M2.2
item below for the next burn-down session). The cleanest first fix — a genuine
correctness bug the richer lib EXPOSED, not a compensating hardcode to gate: under
`useRealLibs` the real lib's `interface IArguments` (type-only, no `declare var`
companion) leaked into the VALUE-position spelling-suggestion candidate pool, so an
unresolved value-position `arguments` drew "Did you mean 'IArguments'?" (TS2552)
where tsc emits a plain TS2304. The embedded lib had no `IArguments` at all, which
is exactly why only the real-lib A/B surfaced it. Fix (Checker `getSpellingSuggestion`):
classify type-only symbols (Type flag, no Value/Module) from `perFileScope[fileName]`
— lib globals + cross-file script locals, the SAME source the value-position pool
draws from — into `typeOnlyNames`, not just the current file's binder locals. The
type-position branch never consults `typeOnlyNames`, so the fix is structurally
value-position-only; noted a symmetric latent FN (the type-position pool won't
suggest a type-only LIB global for a mistyped TYPE — no corpus test exercises it).
Lib-agnostic (embedded stays green; the fix only removes wrong suggestions that
depended on the richer lib being present). 3 local tests (SpellingSuggestionTypeOnlyTest)
+ two controls (`Object` still suggested in value position; a user interface still
suggested in type position). No self-compile dashboard delta (a corpus-A/B fix;
default stays off). **Triage of the other 7 sampled failures (for the next
session):** redefineArray = construct-sig TS2322 double-emitting alongside TS2739
(tsc reports only missing-props; our B112 pre-gate fires because real ArrayConstructor
HAS a construct sig — embedded didn't); libMembers = TS2728 "declared here" related
points at the wrong file/pos (`libMembers.ts:15:…` vs `lib.es2015.core.d.ts:--:--`)
because `resolveDeclarationSourceFile`/`isLibFileName` only know the FIRST real-lib
file (M2.1d's acknowledged multi-lib-file position ambiguity); isArray = `Array.isArray`
type-guard narrowing not applied under real libs → extra TS2339 (M3.4);
dissallowSymbolAsWeakType = extra TS2345 `null` ≁ `T` (generic inference, walker+engine
interaction); truthinessCallExpressionCoercion2 = one MISSING TS2774; implementArrayInterface
= extra TS2420 (9 missing es2015 Array methods) + TS2416-some (B537 semantics, implements-vs-array).

**Round 390 (2026-07-04) — M2.1(a) landed: the real TypeScript lib sources ship
as generated Kotlin (`RealLibFiles.kt`, 100 non-DOM lib files / 565,732 bytes,
keyed by bare lib name, byte-faithful incl. CRLF).** `generateRealLibSources`
(build.gradle.kts) reads the pinned commit's object DB directly (`git ls-tree`
+ `git show` — offline; the sparse working tree never materializes `src/lib`)
and emits ≤ 60,000-modified-UTF-8-byte `sb.append` chunks per string literal
(the 64 KB class-file constant cap; es5.d.ts = 4 chunks), wired as a commonMain
srcDir with every Kotlin compile task depending on it (first compile in a fresh
clone now needs typescript-repo, same as tests always did). 3 local tests
(RealLibFilesTest) pin multi-chunk reassembly (es5 > 65,535 chars with
first/middle/last-chunk anchors) and the `/// <reference lib>` directives
M2.1(b)'s DAG resolver will consume. No dashboard delta (no runtime behavior
change — the checker doesn't read RealLibFiles yet). **Debugging saga worth the
note: the first cut's KDoc contained the path glob `src/lib/*.d.ts` — the `/*`
in it opened a NESTED Kotlin block comment that the NEXT declaration's KDoc
`*/` re-balanced, so build.gradle.kts COMPILED with a silently-dead region:
tasks registered after the comment were "not found", top-level probe statements
never executed, and even an appended `this is a syntax error!!!` line "BUILT
SUCCESSFULLY" (it sat inside the swallowed region).** The tell that cracked it:
a deliberate EOF syntax error still building → the content can't be what's
compiling → comment-depth scan found the imbalance. (A raw NUL byte from a
tool-input NUL-char literal was a red herring fixed first.) CLAUDE.md's
block-comments-NEST gotcha gained the silent-dead-region variant.
**Same round, M2.1(b): `RealLibResolver` landed** — tsc's `libMap` (110 entries,
aliases + back-compat fallbacks), `targetToLibMap` defaults, the reference-lib
closure, and the priority ORDER (`getDefaultLibFilePriority` = libEntries index,
not DFS — es5 pulls in decorators, which still sorts last). Unknown names and
unshipped DOM references surface via `Resolution.unknownNames`/`.unavailable`.
One expectation fixed mid-test: `esnext.bigint` alone expands to THREE libs
(es2020.bigint's own directives pull es2020.intl → es2018.intl). 6 local tests
against the real headers. Suite 8,957 / 0 / 3.
**And M2.1(c): `RealLibSnapshots`** — parse-once shared ASTs (dist file
names), fresh binds per consumer (mergeSymbolTable mutates merged-in symbols
→ shared bound tables would cross-pollute programs), `useRealLibs` flag
(default off). The real es5.d.ts parses + binds cleanly on the first try.
4 local tests. Suite 8,961 / 0 / 3.
**And M2.1(d): checker wiring + the corpus A/B.** `bindRealLibs()` behind
`useRealLibs` (+ directive); cross-lib-file interface merging proven by
`[1,2,3].includes(2)` under `@lib: es2016`. A/B with the default temporarily
flipped: **40 / 8,961 failures, all error-baseline, zero js-emit — the M2.2
burn-down list is seeded in the queue item.** Wall time +70% under real libs
(fresh per-program binds of ~240KB+ of lib source) — noted as an M2.2
pre-flip task. Suite (default off) 8,965 / 0 / 3. M2.1 is COMPLETE.

Archived Phase-17 session notes trimmed from PLAN-PHASE-5.md (most recent first). See PLAN-PHASE-5.md for the live queue + the ~10 most-recent notes.

**Round 389 (2026-07-03) — M1.11 landed: self-compile 2,794 → 2,726 (−68;
TS2554 45 → 0, TS2345 424 → 411, TS2769 77 → 67, zero new codes) — M1 is
COMPLETE.** The "nested-function shadowing" item decomposed into five shapes
once each of the 45 TS2554 sites was traced to its declaration (the item's
own three samples were all different shapes): parameter shadowing (identifier
+ destructured + fn-typed params), body-local variable shadowing, the
namespace-flattening leak (`collectFuncDecls` recursed into ModuleDeclaration
bodies, making parser.ts's namespace-local 0-param `isExternalModuleReference`
hijack the file-level call site — now a body-scoped overlay collected at the
walker's ModuleDeclaration branch, with the inherited-ctor fixpoint extracted
and re-run per namespace), constructor-overload arity (only the FIRST ctor
signature was recorded — semver.ts's `Version(text)`/`Version(major,…)` pair
now records an isOverloaded RANGE), and spread-argument too-few unsoundness
(a spread counts as 1 arg but expands to ≥0, so `argCount < min` is unprovable
— too-many stands since spreads only add). Arity fixes are all
removal/bail-shaped (`minusParamShadowedNames` at every fn-body descent +
the `argCountFnDepth`-gated list-level var removal — the depth gate keeps
top-level B64.2 var-arrow entries checked). Type path: two mechanisms —
`populateParameterLocalTypes` now registers an UN-annotated param whose
DEFAULT is an arrow/fn-expr with the initializer's inferred type (emitter.ts
passes such params straight through as args: 5 FP TS2345 showing the outer
5-param signature vs `() => string`), and `shadowNestedFunctionNames`
(call-types walker, after the fn-body map copy) registers an anyType BAIL for
each body-nested fn whose name collides with an outer binding — B83.5 leaves
them unbound, so `getCalleeType`/`getTypeOfIdentifier` fell through to the
merged globals and found the utilities `writeFile` import (the
'undefined' ≁ 'string' FP at emitter.ts:1331). The −10 TS2769 were unbudgeted:
declarations.ts's nested fns colliding with overloaded imports fed the
overload path the same wrong signatures. 13 local tests
(NestedFnShadowingTest), every suppression paired with a negative control
proving the unshadowed check still fires. Residue intel: the last
TS2345-'undefined' (debug.ts:599) is NOT this family — it's assignment
narrowing through an `as`-cast RHS (`nodeArrayProto = Object.create(…) as
NodeArray<Node>` inside `if (!nodeArrayProto)`) → M3.4/narrowByAssignmentRhs
territory. Next by family: TS2339×836 (M3.4), TS2322×777 (M3.1 top shape),
TS2345×411, TS7006×301 (M1.6(c) call-arg contexts — callee doesn't resolve).**

**Round 388 (2026-07-03) — M1.9 + M1.6(a)+(b) + M1.8 + M1.10 all landed:
self-compile 4,376 → 2,794 (−1,582, −36.2%), five code commits, zero corpus
regressions (suite 8,935 / 0 / 3, +39 local tests). M1 is COMPLETE except the
newly-filed M1.11.** Fifth item, M1.10 (fe65a3cc, −64 exactly — TS2540 GONE):
the parser consumed `-readonly` in a mapped type without recording the sign,
and homomorphic mapped members carry their SOURCE declaration, so writes
through tsc's `Mutable<T>` idiom FP'd; `MappedType.readonlyMinus` +
`mappedMutableMemberIds` (inverse side-channel, checked first) + the
symmetric plain-`readonly`-token registration. Residue intel from the fresh
listAll: TS7006×301 = call-arg contexts whose callee doesn't resolve
(`makeFunctionTypeMapper(t => …)` — M1.6(c) territory); TS2322's top shape is
`Type 'T[]'` ×174 (generic call-site inference, M3.1); TS2554×45 is
nested-function shadowing → filed as M1.11. Per-item deltas (each bench-isolated):
**M1.9 (b4c15a22, −133)** — the "undefined lost against union targets" item
over-delivered because the union member was never lost in the RELATION; five
distinct emitters were at fault (return-path string fallback running after the
engine CONFIRMED assignability — B325's early return had never reached the
return path; enum-member union aliases resolving to anyType → the syntactic
`aliasUnionContainsNullishKeyword` skip; assignment TARGETS checked against the
guard-NARROWED type instead of the declared one → `narrowedDeclaredTypes` at
both dispatcher arms; the main arg path missing M1.7a's undefined-to-optional
rule for primitive/namespace-nested params; 17.20 firing on the sig's OWN
inferable bare TPs). TS2345-undefined 100 → 2, TS2322-undefined 70 → 0.
**M1.6(b) (0e38be5a, −446; TS7006 1554 → 1111)** — `contextualCallableArity`:
a plain callable contextual slot suppresses TS7006 up to its arity (rest =
unbounded; beyond-arity keeps firing per B224) in the arrow/fn-expr/
object-literal-METHOD branches + return-annotation threading
(`returnCtxAnnotation`, lazy resolution at the ReturnStatement). The real
checker.ts factory is a VAR-DECL annotation (`const checker: TypeChecker =
{...}`), not the return shape the map predicted — the plumbing existed, only
union-with-primitive slots suppressed. The suite gate caught the ONE corpus
pin: members reached through a union-with-non-object literal context must NOT
suppress (`ctxViaUnionWithPrimitive`;
contextualOverloadListFromUnionWithPrimitiveNoImplicitAny). **M1.8 (d31be6be)
+ M1.6(a) (4e048750), combined row −939 (TS7006 1111 → 301 = exactly
visitorPublic's ×810; TS7030 122 → 0; TS2366 57 → 50; TS7019 7 → 4)** — M1.8
aligned `checkBodyForImplicitReturn` with tsc's
checkAllCodePathsInNonVoidFunctionReturnOrThrow read from the offline sources
(TS7030 = noImplicitReturns-ONLY; TS2366 gated on resolved
undefined-assignability via `returnAnnotationAcceptsUndefined`; per-empty-return
TS7030 additionally `!strictNullChecks` — under strict an empty `return;` is
the TS2322 return-expression path); the queue's "audit which corpus baselines
pin the current disjunct" came back EMPTY (first-try green). M1.6(a):
`mappedAnnotationValueFnArity` derives the computed-enum-key mapped-table
members' contextual arity from the AST (annotation → alias → MappedType →
value alias → FunctionType), threaded as `ctxAnnotation` — no mapped-type
engine work needed. Two process notes: (1) **same-position masking** — M1.9's
TS2322 removal at empty `return;` sites SURFACED 8 pre-existing TS7030 FPs at
identical positions (a +N in an unrelated code after an FP fix: check position
overlap before calling it a regression); it became M1.8's repro. (2) The
M1.8+M1.6a bench row is marked +dirty from uncommitted DOCS edits only — the
compiled code is exactly 4e048750. New top families: TS2339×836 (the M3.4
union-receiver narrowing bucket), TS2322×777, TS2345×424, TS7006×301 (residue:
call-arg/uncontextualized shapes), TS2769×77, TS2540×64.**

**Round 387 (2026-07-03) — M1.3 landed: tsconfig `types`/`typeRoots`/@types acquisition
+ bench `--node-stub`. Self-compile: no-stub control EXACTLY 4,456 (acquisition inert
under `types: []`); stub run 4,456 → 4,411 (−45 env-legit, zero new codes). Suite
8,888 / 0 / 3 (+9 local).** Mechanics: `ProjectCompiler.collectTypeRootEntries` +
`effectiveTypeRoots` (explicit roots REPLACE the walk-up `node_modules/@types`
default) + `ModuleResolver.resolveTypeRootPackage` (package.json `types`/`typings`,
else `index.d.ts` — deliberately narrower than directory resolution: no `main`, no
runtime `index.*`); entries seed the graph walk so their imports and
`/// <reference types>` directives follow; TS2688 for explicitly-requested-but-missing
names only. TypesAcquisitionTest pins the sharp both-ways invariant with
ambient-global-only packages (only acquisition can reach them → inclusion = global
resolves + entry in programFiles; exclusion = TS2304). Two findings worth keeping:
(1) the first stub cut declared `Buffer` value-only and sys.ts's `let buffer: Buffer;`
drew TS2749 — a node global that doubles as a type needs the lib wrapper-type shape
(generic-tolerant `interface Buffer<T = any>` MERGED with the var via canMerge
Variable+Interface; harness even writes `Buffer<ArrayBuffer>`, hence the defaulted
type param). (2) Resolving the 46 env-legit names FREED the global 10-lookup TS2552
suggestion budget: the 5 `SetIterator`/`MapIterator` sites (es2024 collection-iterator
types missing from the embedded lib — an M2 lib-gap marker; 4×TS2552 + 1×TS2304 in the
control) all became TS2552 — diagnostics SHAPES can shift when unrelated names start
resolving, so compare by-code diffs against the freed-budget effect before calling a
+1 a regression. Ops: two mid-session cwd drifts (a `cd` into the bench dir made
relative-path XML reads report 0 files — a false "no tests ran" scare; absolute paths
resolved it). **Same session, M1.4 (re-measure + strategic map): fresh no-stub rows at
2254d13c — services 7,173 → 7,145 err / 563 → 393 s (−30%); server 7,634 → 7,606 /
627 → 383 s (−39%); harness 8,164 → 8,135 / 593 → 392 s (−34%, RSS 1,920 → 1,192 MB).
The ~−28 error deltas are round 386's narrowing work; the time/RSS drop is the
depth-2000 memo effect; each profile also gains M1.2b's +2 completed-narrowing TS2345
(same shape as utilities.ts:11604/11859). Family map from the 4,411-site compiler
`--listAll`: TS7006×1554 is 52% ONE FILE (visitorPublic.ts ×810 — the
`VisitEachChildTable` computed-enum-key mapped-type table) plus the factory pattern
(checker.ts ×318 `return { isUndefinedSymbol: symbol => … }` ← return-annotation
member fn types; program.ts ×94; tsbuildPublic.ts ×63) → M1.6. TS2345×65 + TS2322×~35
are ONE relation bug (`undefined` ≁ union CONTAINING undefined:
`PunctuationToken<any> | undefined`, `VisitResult<Node | undefined>`) and TS2339×44 is
`new Map<K, V>()` typing the local as MapConstructor → M1.7. TS7030×114 are
`T | undefined`-returning functions drawing our strict-only TS7030 where tsc requires
noImplicitReturns → M1.8. TS2339's dominant bucket (461 union receivers + the named
`Type`/tuple sites — `isTypeParameterDeclaration(node) ? node.name…`,
`isGenericTupleType(type) && type.target…`) is user-type-guard narrowing on the big
merged AST unions → absorbed into M3.4's item text. `SetIterator`/`MapIterator`
(es2024) and String.replace's RegExp-arg overload (TS2345 `'RegExp'`→`'string'` ×19)
→ M2 markers. On services/server/harness, TS2339 (1,741–1,904) overtakes TS7006 as
the #1 family — the M3.4 narrowing bucket dominates the bigger profiles.**
**Third arc, same round — M1.7 landed: self-compile 4,456 → 4,376 (−80, zero new
codes; TS2339 −50, TS2345 −25, TS2322 −5). (a) Explicit `undefined` is legal for an
OPTIONAL parameter on the single-signature arg path (B176's overload rule applied to
the 17.11c Reference branch + the 17.40 anonymous-fn sibling; `null` stays checked,
required params still reject). The ` | undefined` in the FP display was our OWN
B51.7 optional-display append — reading it as "union containing undefined" was the
wrong first hypothesis; the bench isolated the real split: only the `?:`-style
factory params were this bug, the `: X | undefined`-annotated style is a genuinely
lost union member → re-scoped into M1.9 (~75 sites with the TS2322 sibling).
(b) `getReturnTypeOfNewExpression` re-instantiates a constructor-interface's
construct-sig return target with the explicit type args (`new Map<string, number>()`
→ `Map<string, number>`, was MapConstructor → 44 TS2339 + 6 knock-ons). 8 local
tests (OptionalParamAndCtorInterfaceTest) with negative controls (null rejected,
required-param undefined rejected, no-type-args path intact). Suite 8,896 / 0 / 3.**

**Round 386 (2026-07-03) — M1.2 closed: narrowing depth horizon 50→2000 (zero corpus
churn), TS2563 half folded into M3.4 with measurement; M1.5 asserts predicates ACTIVE
end-to-end; M1.5b falsified-and-pinned; assignment-effect narrowing landed.
Self-compile: 4,464 → 4,456 errors, 185.8 → 75.8 s (−59%), RSS 1,166 → 853 MB.**
M1.2b: the flagged blocker ("corpus depends on depth-50 truncation") was measured
EMPTY — one-constant experiment, full suite 8,861/0/3 at depth 2000 — and the cap
itself turned out to be the round-385 perf problem's other half: truncated subtrees
are never memo-stored (clean-only rule), so the 50-cap forced deep-CFG walks to
recompute everything; lifting it alone took the compiler profile 185.8 → 68.3 s and
RSS 1,166 → 841 MB at byte-identical diagnostics EXCEPT +2 TS2345
(utilities.ts:11604/11859 — deeper walks now COMPLETE two narrowings whose results an
arg-check consumer turns into FPs; the depth-50 truncation had been hiding them;
tracked under M1.4). TS2563-emission folded into M3.4 after reading
largeControlFlowGraph: it is 10k sequential `data[0] = 0` statements — tsc's TS2563
fires because USE-SITE reference typing walks the evolving array's flow at every
mutation check (flow-based identifier typing = M3.4's exact capability); none of our
four narrowing consumers ever walks that file deep, so a faithful walk-exhaustion
emitter is unreachable until then and B399's per-file heuristic (+ its 27 self-compile
TS2563 FPs) stays. **M1.5 (eaa27a90)**: parser builds `TypePredicate(assertsModifier=
true)`; asserts returns are void (return-less bodied assert fns draw no TS2355/2366/
7030); `narrowByAssertCall` live — `is T` targets, `is NonNullable<T>` as nullish
exclusion, bare `asserts cond` by CONDITION via `applyConditionNarrowing` (the
`Debug.assert(x !== undefined)` shape); the round-385 pre-check widened to
path-containment (`argMentionsReferencePath` — iterative, bails open; the firewall
stays); `resolveFlowCalleeDecl` gains namespace-member callee resolution
(`resolveNamespaceMemberFnDecl`); `callHasTypeGuardArg` gates `!assertsModifier`.
8 local tests with negative controls (AssertsPredicateActivationTest); suite
8,869/0/3. **Self-compile M1.5 delta is only TS2344 −2 + TS2355 −1 — the assert
NARROWING moved nothing on tsc sources** (TS2339/TS18048 unchanged): the imported
`Debug` alias apparently doesn't resolve to debug.ts's namespace through the
`_namespaces/ts.ts` export-star barrel in the flow-callee path → new M1.5b queue item.
Also landed: `--listAll` CLI flag (full diagnostic lists for run-to-run FP diffing —
used to isolate every delta above). **Same session, the M1.5b pivot + assignment-effect
narrowing (482e9ad1 + 8f246dcf): self-compile 4,463 → 4,456 (TS18048 41 → 34).**
M1.5b's hypothesis was falsified BY TEST before building anything — a ProjectCompiler
repro of tsc's exact barrel topology narrows correctly (3 pinning tests,
AssertsBarrelResolutionTest) — and sampling the real TS18048 FPs showed ASSIGNMENT
shapes instead (`context.pragmas = new Map() as PragmaMap` then use;
`result.extendedSourceFiles ??= new Set()`). Landed `narrowByAssignmentRhs` (shared by
both walker mirrors): structurally-non-nullish-RHS exclusion for `=`/`??=`/`||=` on
identifier + property-path targets (`&&=` excluded and pinned), cheap pre-gates before
path building; Flow.kt binds FlowAssignment for COMPOUND assigns on property LHS (the
only real binder gap — plain `=` property targets already had nodes). Cost: compile
68.6 → 75.8 s (+10%, the per-FlowAssignment matcher; still −59% vs the session's
185.8 s start — revisit in M5). Suite 8,879 / 0 / 3 (+10 local this arc). **Process
lessons, armored in comments/memory: (1) a STALE walker comment ("no FlowAssignment
for property paths") led to a duplicate `when` arm in bindAssignmentTarget that
SHADOWED the real arm (Kotlin takes the first match), dropped the LHS read-records,
and regressed this-before-super + instanceof narrowing (narrowingOfDottedNames,
checkSuperCallBeforeThisAccessing2) — the commit initially landed on a FALSE-GREEN
garbled notification and was amended after a filesystem-verified rerun. (2)
Background-task/Monitor payloads were unreliable ALL session — fabricated bench
summaries citing nonexistent log dirs, two different "12-char" expansions of one sha,
a BUILD SUCCESSFUL while the worker was mid-run, `-s`-gated monitors firing on empty
files — every gate now reads test XMLs / TSVs / logs from disk only (memory:
background-task-verification.md). (3) Hit CLAUDE.md's №1 gotcha verbatim: an
`until ! pgrep -f GradleWorkerMain` poller matches ITSELF — the bench sat behind it
for 10 minutes.** Ops notes from earlier in the session, superseded by the above:
one bench overlapped a concurrently launched attribution JVM (contaminated row
deleted); never run a second JVM while a bench measures.

**Round 385 (2026-07-03) — P0 services hang FIXED (flow-walker memoization + budgets);
asserts-parse stub discovered; M0.2 baselines completed 8/8.**
The hang was the predicted exponential re-entry with a twist: `narrowByAssertCall`
resolved every visited FlowCall's callee (PropertyAccess receiver typing →
`getNarrowedTypeForReference` re-entry per call, no memoization anywhere) — and
because `parseType()`'s AssertsKeyword branch ERASES `asserts x is T` to bare `T`
(`TypePredicate.assertsModifier` is never constructed), ALL of that exponential work
was spent discovering "not a predicate" every time; assert narrowing has been inert
since round 43 built it. Fix (349dc97b) mirrors tsc checker.ts, four pieces:
(1) arg-path pre-check in `narrowByAssertCall`/`narrowByCallPredicate` — bail before
any callee resolution unless some argument's reference path IS the walked name;
(2) per-outermost-request callee-decl memo (`narrowWalkDeclCache`, tsc
`links.effectsSignature` — request-scoped for cross-pass safety); (3) per-invocation
flow-node memo (tsc `sharedFlowNodes`) with the depth-conditional serve rule
`depth <= cachedDepth` + clean-subtree-only stores that keep narrowing byte-identical
under the NARROW_MAX_DEPTH truncation; (4) live-depth (2000, tsc `flowDepth`) +
cumulative-visit budgets shared across re-entries via the `narrowLiveDepth` FIELD
(== 0 ⇔ outermost request = reset point). **Budget sizing lesson (40d33b58): near the
NARROW_MAX_DEPTH horizon subtree results are inherently entry-depth-dependent — no
identity-preserving memo can serve them — so depth-skewed diamond chains in giant tsc
functions legitimately need 6-figure visit counts; the first 50k budget truncated one
such walk and GREW the dashboard (TS18048 41→42 → compiler profile 4,485). The final
1M budget (probes: 50k → 4,485/101.8 s, 200k → 4,485/139.1 s, 1M → 4,484/187.1 s)
recovers exact pre-fix diagnostics.** Benches: compiler pre-fix 289.9 s → 187.1 s
(−35.5% at exact 4,484; the memo win is larger at looser compliance points — 101.8 s
at the 50k budget); services HUNG (killed after 30+ CPU-min frozen in one statement)
→ 563.4 s / 7,173 errors / 1,226 MB (7,174→7,173: the 1M budget recovers one narrowing
there too); server FIRST baseline 627.4 s / 7,634 errors / 1,139 MB (274 files);
harness FIRST baseline 592.7 s / 8,164 errors / 1,920 MB (312 files) — **M0.2 crash
gate 8/8 profiles green, zero crashes/OOMs anywhere.** Local
AssertNarrowingScalingTest pins the invariant: the exact services shape (N=120
property-chain assert-style calls whose args mention both walked paths, ≈2^120 walker
visits pre-fix) → 0.125 s, plus negative/positive controls proving `x is T` predicate
narrowing still applies through the memoized path. NEW: M1.5 queued (activate asserts
predicates end-to-end — parser + tsc condition-arg narrowing; the arg-path pre-check
must WIDEN to path-containment, never be deleted). M1.2 updated: its tsc-flowDepth
mechanism now exists as these budget fields; what remains is the TS2563
emission/suppression semantics. **Same session, M1.2a landed (3c4cb60b): B399 records
`cfaTooLargeFiles` and an end-of-init filter removes every TS2454 in them (tsc's
flowAnalysisDisabled emits TS2563 OR TS2454, never both; real tsc emits neither on
its own sources) — self-compile 4,484 → 4,464 (−20, exactly the predicted knock-ons),
compile time unchanged; CfaTooLargeBailTest pins both directions (small CFG: same
never-assigned-read shape MUST fire TS2454; too-large CFG: TS2563 present, TS2454
suppressed). The M1.2 remainder (TS2563 per-container semantics) is re-scoped in the
queue item — it is gated on NARROW_MAX_DEPTH removal (largeControlFlowGraph's
baseline REQUIRES TS2563, but faithful walk-exhaustion semantics need walks to reach
depth 2000, which the 50-cap prevents) and overlaps M3.4.** Full suite 8,861 / 0 / 3
(+5 local tests this round).

**Round 384 (continued) — M0.2 findings + M1.1 landed: self-compile 13,245 → 4,484 (−66%).**
M0.2 (`--project all`): 5/8 profiles green in ~5 min each with tightly clustered
baselines (compiler 13,245; tsc-cli 13,247; jsTyping 13,301; deprecatedCompat 13,256;
typingsInstallerCore 13,348 — TS2305 dominating each at 8,752–8,837), zero
exceptions/OOMs; **services HUNG → the P0 now at the queue top** (30+ CPU-min frozen in
one `checkVarDeclAssignability`; stack: `narrowByAssertCall` → callee/arg type
resolution → `getNarrowedTypeForReference` re-entry per assert-call flow node, no
memoization — tsc's services code is `Debug.assert`-dense); server/harness deferred.
Also caught: the src/tsc profile's TSV name collided with the compiler profile's
historical file (fixed, `self-compile-tsc-cli.tsv`). **M1.1** (8a4ba245): export-star
barrel following — measured **13,245 → 4,484 (−8,761)**, TS2305 eliminated from the
top codes, compile −2.7%; remaining top families now TS7006×1554 (contextual-typing
gaps → M3.2), TS2339×886, TS2322×827, TS2345×543, TS7030×114, TS2769×77. **M1.2 recon**
(for the P0 + M1.2 implementer): tsc's mechanism confirmed at checker.ts:29037 —
`flowDepth === 2000` counts recursive `getTypeAtFlowNode` invocations per
`getFlowTypeOfReference` walk (linear single-antecedent steps are the iterative
`while(true)` loop and don't consume budget; `sharedFlowNodes` memoizes shared nodes
per walk), `flowAnalysisDisabled` is checker-global but save/restored around each
function-or-module block in `checkBlock` (= container-scoped), and
`reportFlowControlError` anchors at `findAncestor(reference, isFunctionOrModuleBlock)
.statements.pos` token span. Our B399 per-file node-count heuristic must be replaced by
that walk-budget + per-walk memoization — which is ALSO the P0 fix.

**Round 384 (2026-07-03) — M0.1 + M0.3 landed; M0.2 baseline running.**
M0.1 (9b5bcd78): `--project` profiles + per-project TSVs in the bench script (see QUEUE
entry). M0.3 (f85cc438): parse-based specifier extraction — the parser now records
`SourceFile.moduleSpecifiers` at the real parse sites (static/dynamic/require/import-type
plus a new bounded leading-trivia scan for `/// <reference>` that honors directives after
a block-comment header, which `checkTripleSlashSelfReference`'s corpus-pinned scan does
not); `ProjectCompiler.extractSpecifiers` parses instead of regex-scanning, so
string-literal/comment/regex-literal text can no longer fabricate unresolved imports or
pull junk files into the program. 6 local tests pin the invariant (garbage never
extracted; deep-nested dynamic imports found; string-literal mention neither reaches
`unresolved` nor joins the program). Suite 8,848 / 0 / 3 (+6 local). Session ops notes:
a leftover bench run from round 383 was still executing at session start (its TSV row
landed at 23:08 — labels tell them apart); my first services verification run was killed
as polluted (its gradle step compiled pre-M0.3 code, then the M0.3 recompile swapped
class files under the running JVM — don't recompile while a bench JVM is up). M0.2
`--project all` relaunched clean on f85cc438; expected effect on compiler profile:
errors stay exactly 13,245 (extraction doesn't affect checking), unresolved drops from
120 to just node-builtin bare specifiers (env-legit until M1.3).
