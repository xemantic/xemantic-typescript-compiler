# (LEGACY.1) census — code owned by the TS7-removed features

Read-only census taken 2026-09-12, the day of the owner directive "TypeScript 7.0
compatibility is enough; legacy code supporting deprecated features can be removed".
Every claim below carries a grep receipt; line numbers are as of commit `282a1820`
(core module, `src/commonMain/kotlin` unless stated) and will drift — re-grep before
editing. The queue item (LEGACY.1) in `PLAN-PHASE-5.md` carries the decomposition.

## 0. The authoritative list (tsgo, `typescript-go-repo/internal/compiler/program.go:803-877`)

`createRemovedOptionDiagnostic(name, value, useInstead)` emits TS5102 (`Option '{0}' has
been removed. Please remove it from your configuration.`) when `value == ""` and TS5108
(`Option '{0}={1}' has been removed…`) otherwise, with a `Use '{0}' instead.` chain when
`useInstead` is non-empty. The `// Removed in TS7` block reports:

| option | value | chain |
|---|---|---|
| `baseUrl` | — | `"paths": {"*": ["./<rel-to-config>/*"]}` — a COMPUTED suggestion (`program.go:824-833`) |
| `outFile` | — | — |
| `target` | `ES5` | — (ES3 is not in `targetOptionMap` at all, `tsoptions/enummaps.go:154-168`) |
| `module` | `AMD` / `System` / `UMD` | — |
| `moduleResolution` | `Classic` / `node10` | — |
| `alwaysStrict` | `false` | — |
| `esModuleInterop` | `false` | — |
| `allowSyntheticDefaultImports` | `false` | — |
| `downlevelIteration` | any | — |

`strictBindCallApply: false` is NOT in the block. `export as namespace X` (the UMD
GLOBAL) is kept by tsgo (`checker/grammarchecks.go:611,2024`) and is not `module: UMD`.

## 1. What tsgo does AFTER reporting — the behaviour to mirror

| option | post-report behaviour | receipt |
|---|---|---|
| `target: ES5` | never honoured: `ScriptTargetES5` is marked `Deprecated: Do not use outside of options parsing and validation` (`core/compileroptions.go:511`); `estransforms/definitions.go:24-41` dispatches on the emit target with `default: transform maximally → NewES2016Transformer`. There is NO ES5 downlevel transformer (no class-to-function, arrow, for-of, spread, `__extends`/`__awaiter`/`__generator`). | `definitions.go:38-40` |
| `target: ES3` | not parseable | `enummaps.go:154-168` |
| `module: AMD/UMD/System` | never emitted: the consts are `Deprecated`, `compiler/emitter.go:81-101` has only `NewImpliedModuleTransformer` and `NewCommonJSModuleTransformer`; the three fall into `default: → CommonJS`. | `emitter.go:98-99` |
| `moduleResolution: Classic/node10` | folded into the Bundler/Node16 switch by `GetModuleResolutionKind()`; no classic or node10 algorithm exists. | `compileroptions.go:223-237` |
| `alwaysStrict: false` | ignored: 3 references (parse, field, diagnostic); `estransforms/usestrict.go:31-49` adds `"use strict"` unconditionally for every non-ESM-format file. | grep |
| `esModuleInterop: false` / `allowSyntheticDefaultImports: false` | ignored: 3 references each; interop helpers unconditional. | grep |
| `downlevelIteration` | ignored: 3 references, zero consumers (TS2802's message survives at `checker.go:6696,6699` but is selected by iteration-type failure). | grep |
| `baseUrl` | parsed, absolutised, reported; `modulespecifiers/specifiers.go:524`'s `relativeToBaseUrl` is an unrelated local. | grep |
| `outFile` | parsed; used only for `configDir` substitution and as a negative guard in output-path validation (`program.go:1052,1065,1073`); no bundling. | grep |

Two more receipts that decide the `target` family: `checker/types.go:1435-1457`
`LanguageFeatureMinimumTarget`'s lowest entry is `Exponentiation: ScriptTargetES2016` —
tsgo has no `target < ES2015` checker gate; `binder/binder.go:1379`
`getStrictModeBlockScopeFunctionDeclarationMessage` (TS1250/1251/1252) has zero callers,
and TS18028 has zero references outside the generated table. TS2550/TS2583 survive but
are lib-keyed (`checker.go:1677, 11536, 13842, 13861`) — out of scope.

## 2. Our sources, per family

### 2a. `target` ES5/ES3 (~730 lines of `Checker.kt` + ~90 of `CompilerOptions.kt`)

Option surface (`CompilerOptions.kt`): `:28-49` `enum class ScriptTarget { ES3, ES5, … }` +
`fromString` `:33-34`; `:98-99` `target = ES3`, `targetExplicitlySet`; `:274-287`
`effectiveTarget` with the ES5→ES2015 emit map at `:284`; `:288-348` `defaultedTarget`
(unset ⇒ ES2024); `:350-356` `effectiveModule` (its `else → CommonJS` arm dies with ES5);
`applyDirective` `:795-798`; tsconfig key list `:1243`. No CLI flag and no usage-text
entry exist for `target` (or any option in this arc), so `CliModeRestoreTest` is not a
hazard.

Emit side is already tsgo-shaped: `Transformer.kt` has 47 `effectiveTarget` sites and the
lowest comparison is `< ES2016` (`:7413, 7455, 7468`); no `< ES2015` gate exists in
`Transformer.kt` or `Emitter.kt`; `__extends`/`__read`/`__spreadArray` have zero hits;
the one `__values` (`Transformer.kt:17455`) is inside the `__asyncValues` ES2018
for-await helper, which tsgo keeps.

Checker side — the `defaultedTarget` gates:

| line | gate | owns |
|---|---|---|
| 9149 | `< ES2015` | pass gate for TS1250 |
| 9160 | `targetExplicitlySet && target <= ES5` | pass gate for TS18028 |
| 9861 | `< ES2015 && !downlevelIteration` | pass gate for TS2802 |
| 25445 | `< ES2015` | `spineAccessorModifierActive` (TS18045) |
| 25464 | `>= ES2015` | `spineIterableOperandActive` (TS2488) — inverted, not deleted |
| 25513 | `< ES2015 \|\|` | `spineAcRunActive` first disjunct (TS2396) |
| 27416 | `< ES2015` | `es5HoistBody` var-hoist into the unresolved spine scope |
| 38551 | `< ES2015` | `checkGlobalIterableRestOnlyBindingPattern` early return — inverted |
| 64685, 64809 | `< ES2015` | TS2461 vs TS2488 ternaries |
| 79083, 79119, 79123, 79127 | `< ES2015` | objlit-super TS2659 (pre-gate + 3 arms) |
| 80517 | `< ES2015 \|\| >= ES2022` | `checkWeakMapWeakSetCollision` (TS18027) — KEEP |
| 80736 | `> ES5 → return` | `checkBlockScopedFunctionDeclarations` guard |
| 89620 | `< ES2015` | `bodyVarRefs` ES5 body-var collection |
| 93271 | `<= ES5` | `needsExtendsHelper` (tslib TS2354 `__extends`) |
| 93502 | `<= ES5` | `isEs5Target`, threaded through 6 functions (17 refs) |
| 148248 | `<= ES5` | TS2340 `checkSuperPropertyAccessES5` vs TS2855 |
| 177479 | `>= ES2015` | inverted |
| 76716, 9865 | `< ES2018`, `< ES2020` | NOT this family — keep |
| 6653, 6655, 6664, 57189, 57222, 76639 | lib resolution | stay; their ES5 arm dies with the enum |

Function ranges: TS1250 family `80735-80875` (141 lines); TS18028 family `80496-80514`,
`80616-80733` (137 lines, `sourceIdentifierLength` single-caller); TS2802 family
`187638-187866` (229 lines, seven single-caller functions); `checkSuperPropertyAccessES5`
`147058-147164` (107 lines); `spineCheckAccessorModifier` `29307-29328` (~40 lines).

### 2b. `module: AMD/UMD/System`

The transforms and `outFile` bundling were deleted 2026-07-02 (`RemovedModuleKindsTest.kt`).
Residue: `Transformer.kt:5964-6070` `buildSystemDynamicImport`/`rewriteSystemDynExpr`/
`rewriteSystemDynStmt` (107 lines, DEAD — every occurrence of both names lies inside the
range); `Transformer.kt:16315-16461` `stripVarDeclsFromStatement`/`collectVarNamesFromStmts`/
`collectVarNamesFromStmt` (~147 lines, DEAD — all 31 occurrences inside `16323-16450`;
`collectBoundNames` at 16463 has other callers and stays); `Checker.kt` module-kind arms
`:49304, 49464, 50389, 80400-80401, 93224, 93268-93269, 93473, 93569, 187545, 188800-188802`
and System-only `:51357, 52216, 74575`; `TypeScriptCompiler.kt:513-515` (TS5107), `:750,
769, 933-934, 1121, 3032`; `CompilerOptions.kt:51-63`. `Checker.kt`'s 38 `UMD` hits are
overwhelmingly `export as namespace` — leave them; the externals module
(`KotlinExternalsWiring.kt:312-320,765`, `KotlinExternalsRenderer.kt:108,2019`,
`KotlinExternals.kt:364,529-530,688-691`) depends on that construct and must not be touched.

### 2c. `moduleResolution: classic/node10` (~40 lines)

`moduleResolution` is a bare `String?` (`CompilerOptions.kt:185`); `ModuleResolver.kt` has
NO classic or node10 arm. The family is `TypeScriptCompiler.kt:518-521` (TS5107/5108),
`:745-762` (TS5070 + the `None/AMD/UMD/System → "classic"` derivation), `Checker.kt:49304,
49308` `isClassicResolution` + consults at `49468, 49672, 49687, 49712, 49774` and
`:50389, 93224, 93228, 93237, 93473, 187545`, `CompilerOptions.kt:868`.

### 2d. `baseUrl` (~70 deletable lines + 10 guard simplifications)

`CompilerOptions.kt:183, 867`, `:1319-1350` TS5090; `TypeScriptCompiler.kt:437-439` TS5101,
`:2109, 2121, 3061`, `:3259-3270` the bare baseUrl lookup, `:3335-3360` the
`resolvePathsMapping(…, baseUrl, …)` anchoring (`paths` survives and keeps anchoring on the
tsconfig dir); `NameResolver.kt:573, 604-609`; `Checker.kt` guards of the shape
`&& options.baseUrl == null` at `49468, 49499, 49521, 49567, 49677, 49727, 49752, 49779,
49831, 187565` (simplify to true).

### 2e. `outFile` (~50 lines)

`TypeScriptCompiler.kt:459-460` TS5101, `:876-877` TS5074, `:1227` `jsName`, `:1601-1602`,
`:1615` `transformOrder` + `:3075-3076` (reference-directive ordering, "only used when
outFile is set"), `:1908-1923`, `:2079`, `:2468-2473`; `CompilerOptions.kt:205, 223,
911-912`. `out` is a 5.5 removal and a different arc (`ApplyDirectiveSplitTest.kt:181`).

### 2f. `alwaysStrict: false`, `esModuleInterop: false`, `allowSyntheticDefaultImports: false`

`alwaysStrict`: `Emitter.kt:229-231` (the `"use strict"` prologue suppression),
`Checker.kt:25470-25472` `spineWithStrictActive` (TS1101), `:29451`, `:25600-25605`
`explicitNonStrict`; the `== true` reads at `:53571, 71484, 25612, 25619, 25623` stay;
`CompilerOptions.kt:206, 913`. `esModuleInterop`: `CompilerOptions.kt:186-187, 869-871`,
`NameResolver.kt:441-446`, the no-interop `else` arms in `Transformer.kt:3082, 3106, 3137,
3179, 3220, 3224, 3261, 3265, 3376, 3397`, `Checker.kt:51438, 93268`.
`allowSyntheticDefaultImports`: `CompilerOptions.kt:188, 199, 903-904` (default `false`
today — the tsgo default is interop-derived), `Checker.kt:51353-51356, 51439, 52217`.
All three: `TypeScriptCompiler.kt:523-526`.

### 2g. `downlevelIteration`

`CompilerOptions.kt:196-197, 900`; `TypeScriptCompiler.kt:457-458`; the only behavioural
consumers are `Checker.kt:9861` and `:161374`, both `defaultedTarget < ES2015 &&
!options.downlevelIteration` — the family is entirely inside the ES5 family.

## 3. Hand-written pins

| file | tests | family / action |
|---|---|---|
| `DownlevelGateDefaultTargetTest.kt` | 10 (7 `an explicit es5 target still refuses …`) | target — delete/convert |
| `PrivateIdentifierTargetGateTest.kt` | 5 | TS18028 — delete |
| `LibAvailabilityDefaultTargetTest.kt` | 14 (6 explicit es5) | lib — convert; the unset-target half survives |
| `RealLibResolverTest.kt` (9 `ES5` refs), `RealLibSnapshotTest.kt` (5) | — | convert |
| `RemovedModuleKindsTest.kt` | 1 (`code == 5107`) | keep, add a 7.0 sibling asserting 5108 |
| `Inv4SpineBatch11Test.kt:131`, `Inv4SpineBatch9Test.kt:256` | 2 negative controls on `@alwaysStrict: false` | delete |
| `Inv4SpineAccessorModifier*Test`, `Inv4SpineBatch16Test`, `Inv4c2LexicalStateSwapTest`, `Inv4UnresolvedSpineScopeTest` | use `@target: es5` as a vehicle | convert to ES2015+ |
| `CpcSplitTest.kt` (5 baseUrl), `ApplyDirectiveSplitTest.kt` (3 outFile), `BaselineFormatterTest.kt`, `ReferenceDirectiveCrawlTest.kt`, `CompilerTestSupport.kt` | directive plumbing | keep (parsing survives); `ReferenceDirectiveCrawlTest` may be `transformOrder`'s only pin |

`@module: amd/umd/system`, `@downlevelIteration`, `@esModuleInterop: false`,
`@allowSyntheticDefaultImports: false`, `node10`, `__extends`, `__values`, `__read`,
`__spreadArray`: zero hand-written test files.

## 4. Corpus

`build.gradle.kts:892-909` `usesUnsupportedOption` drops `target es3/es5`, `module
amd/umd/system`, `moduleresolution node/node10/classic`, `outfile`, `baseurl`,
`esmoduleinterop=false`, `allowsyntheticdefaultimports=false`, `alwaysstrict=false`
(applied at `:1037` for the bare config and `:1064, 1104` per variation);
`:922-932` `tsconfigInTestUsesRemovedFeature` drops embedded-tsconfig `module:
amd|umd|system` and `outFile`; `:860-882` `tsgoSkippedTests` drops `moduleNone*`,
`noImplicitUseStrict_*`, `isolatedModulesOut`. Verified over the 8,837 generated tests:
active `target_es5`/`es3`, `module_amd/umd/system`, `esmoduleinterop_false`,
`alwaysstrict_false`, `@baseUrl`, `@outFile`: 0 each; active `moduleResolution` values
are only `bundler`/`node16`/`nodenext`.

**The one exception: `downlevelIteration` is NOT in `usesUnsupportedOption`.** Two active
cases (`blockScopedBindingsInDownlevelGenerator.ts`,
`sourceMapValidationVarInDownLevelGenerator.ts`, `target=es2015`) produce four ACTIVE
subtests whose `.errors.txt` pins the 6.0 wording `error TS5101: Option
'downlevelIteration' is deprecated and will stop functioning in TypeScript 7.0. …`.
`TypeScriptCompiler.kt:266-271` already version-gates this: `simulatedVersion` defaults
to `"6.0"` and `addDeprecation5101`/`addDeprecation` emit TS5102/TS5108 only when
`simulatedVersion >= "7.0"`. **The arc keeps that machinery as-is and deletes behaviour
only**; the tsgo wording is reachable with `// @typeScriptVersion: 7.0`, which is where a
tsgo-parity fixture belongs. Whether a real project build should default to 7.0 is an
owner decision.

## 5. Profiles and bench fixtures

All eight `build/bench/tsc-*-637d5746/tsconfig.json` are byte-identical: `target es2020`,
`module NodeNext`, `moduleResolution NodeNext`, `strict true`, `strictBindCallApply
false`, `alwaysStrict true`, no `baseUrl`/`outFile`/`esModuleInterop`/
`allowSyntheticDefaultImports`/`downlevelIteration`. The grid is immune to all nine
removals. Non-gating bench fixtures that DO use removed options: `build/bench/
inc50-scratch*/tsconfig.json` (`target: ES5`, from `scripts/inc50-stability-lib.sh:90`) and
`build/bench/many-small-2400-cjs*/tsconfig.json` (`moduleResolution: node`) — bump them
alongside steps (j) and (e).

## 6. Other modules

externals: only `export as namespace` (keep). KIR: `KirFileLowering.kt:390,1118` `define(`
is a local Kotlin function. LSP/daemon: every `System`/`ES5` hit is `java.lang.System`.
Zero dependence anywhere.

## 7. Out of scope (deprecated in 6.0 but NOT in tsgo's removed block)

`target=ES3` (unparseable in tsgo, TS5023-shaped — rides along with ES5 in step (j) but is
a different tsgo behaviour), `module=None`, `out` (5.5), `charset`, `keyofStringsOnly`,
`noImplicitUseStrict`, `noStrictGenericChecks`, `suppressExcessPropertyErrors`,
`suppressImplicitAnyIndexErrors` (5.0/5.5), `importsNotUsedAsValues`,
`preserveValueImports` (already TS5102), `strictBindCallApply: false`, `export as
namespace`.

## 8. Risks, ranked

1. The four live `downlevelIteration` corpus subtests — changing the default simulated
   version is the failure mode; keep `"6.0"`.
2. Steps (j2)/(j4): `Checker.kt` loses ~500 lines and `defaultedTarget`/`effectiveTarget`
   merge; the unset-target ⇒ ES2024 semantics must survive untouched.
3. Step (d)'s `allowSyntheticDefaultImports` default flip is a behaviour change at the
   default configuration.
4. Step (h)'s `transformOrder` may be the only consumer of the reference-directive
   dependency walk; `ReferenceDirectiveCrawlTest` is the single pin.
5. Bench fixtures `inc50-scratch*` and `many-small-2400-cjs*`.
