# Status

**Phase 4 — Checker buildout.** 8,773 / 10,078 tests passing (~87.1%).

**B44.1 (2026-05-17, +1 — flips `inferTypePredicates_ts` JS-emit)** —
Preserve same-line `// line comment` between (a) `=` and a multi-line initializer
or (b) an expression and the dot of a chained property access on the next line.
Source shapes:
```
const x = // should error
   [1, 2, 3]
const y = list.map((arr) => arr // should error
   .filter(...));
```
Previously both comments were dropped. Two-piece fix: (a) new optional
`initializerLeadingTrailingComments` field on `VariableDeclaration` — populated
in `parseVariableDeclaration` from `scanner.getTrailingComments()` right after
consuming `=`, when `scanner.hasPrecedingLineBreak()` is true (initializer
starts on next line). Emitted by `emitVariableDeclaration` as `= <comment>\n
<value>`. (b) new optional `expressionTrailingLineComments` field on
`PropertyAccessExpression` — populated in the `Dot` branch of
`parseCallAndAccess` when `newLineBefore=true` AND `result.trailingComments`
is empty (CallExpression already captures these via `callTrailing` when
chained — re-capturing would double-emit, see B44.1 fix). Emitted by
`emitPropertyAccess` after the expression's regular trailing comments,
BEFORE the newline+indent+dot. Both gates: `text.startsWith("//")` AND
`hasTrailingNewLine` AND `!hasPrecedingNewLine` (same-line line comment
that terminates the line). Full-suite 10078/1302/3 (was 10078/1303/3, +1
net). Zero regressions.



**B43.3 (2026-05-17, +1 — flips `referenceSatisfiesExpression_ts` errors-baseline)** —
Three-part definite-assignment fix for `(b satisfies T) = ...`, `[(c satisfies T)] = [...]`
and friends: (a) `isValidAssignmentTarget` now accepts `AsExpression`, `TypeAssertionExpression`,
and `SatisfiesExpression` (removes FP TS2364). (b) New `unwrapTypeOnlyWrapper` helper +
ParenthesizedExpression branch in the Equals assignment path of `findUninitializedRefs`:
when LHS is `(x satisfies T)` / `(x as T)` / `(<T>x)`, treat the wrapped identifier as a
read (emits TS2454 if uninitialized) and THEN mark it as assigned. (c) New
`emitReadsForTypeWrappedDestructuring` walker handles `[(c satisfies T)] = [10]` and
`({d: (e satisfies T)} = ...)` shapes — walks the LHS destructuring pattern, finds
type-wrapped identifiers, emits TS2454 reads at those positions. Companion: extended
`collectDestructuringTargets` to unwrap ParenthesizedExpression/AsExpression/SatisfiesExpression/
TypeAssertionExpression so the underlying identifier still gets marked assigned.
`findUninitializedRefs` also gets a new `AsExpression` branch to mirror the existing
`SatisfiesExpression` one. Full-suite 10078/1303/3 (was 10078/1304/3, +1 net).
Zero regressions.

**B43.2 (2026-05-17, +1 — flips `anyMappedTypesError_ts` errors-baseline)** —
Parser now emits TS7039 "Mapped object type implicitly has an 'any' template type." when
a mapped type `{[P in K]}` lacks a value type (`: T`) AND `noImplicitAny` (or `strict`)
is enabled. Threaded a new `noImplicitAny: Boolean` parameter through Parser (default
false). All three Parser construction sites in `TypeScriptCompiler.kt` now pass
`options.noImplicitAny || options.strict`. Squiggle covers the entire mapped type
expression INCLUDING the outer `{...}` braces — scans backward from the bracketed
position to find the enclosing `{` and forward from end of `]` to the closing `}`.
Suppression for `@strict: false` tests (`mappedTypeNoTypeNoCrash_ts` still fires
TS2304 only, as expected). Full-suite 10078/1304/3 (was 10078/1305/3, +1 net).
Zero regressions.


Only the most recent ~5 B-entries are kept here. Older session notes live in
`STATUS-HISTORY.md` (and in `git log`, where every B-entry has a matching commit).

**B43.1 (2026-05-17, +1 — flips `decoratorMetadataNoLibIsolatedModulesTypes_ts` errors-baseline)** —
TS2583 "Cannot find name 'X'. Do you need to change your target library? Try changing the
'lib' compiler option to 'es2015' or later." now fires in type-position for forward-declarable
ES2015+ lib types (`Map`, `Set`, `WeakMap`, `WeakSet`, `Promise`, `Symbol`, `Iterable`,
`IterableIterator`, `Iterator`) when `@noLib: true` is set OR `@lib` is non-empty but contains
no `es2015`/`es6`/`esnext`/`es2.*` entries. Companion change: TS2564 ("Property has no
initializer...") is suppressed for properties whose type references such an unavailable name —
the type is effectively an error type at that point and TS2583 already flags the missing-lib
issue. New helper `isLibTypeUnavailableEs2015(name)` and `typeContainsUnavailableLibName(type)`
walks ArrayType/TupleType/UnionType/IntersectionType/TypeReference recursively.
`es6`/`es2015` aliased in the lib-check to avoid FP TS2583 emission for `@lib: es6` tests
(`asyncAwaitWithCapturedBlockScopeVar_ts`). Full-suite 10078/1305/3 (was 10078/1306/3, +1 net).
Zero regressions.

**B42.6 (2026-05-17, +1 — flips `destructionAssignmentError_ts` errors-baseline)** —
TS2809 "Declaration or statement expected. This '=' follows a block of statements, so if
you intended to write a destructuring assignment, you might need to wrap the whole
assignment in parentheses." now fires for `{a, b} = fn();` at the statement level (the
`=` after a closing `}` is a destructuring-without-parens shape). Previously emitted
generic TS1109 "Expression expected." Detection in `parsePrimaryExpression`'s else
branch: when current token is `Equals`, scan source text backward from
`scanner.getTokenPos()` skipping whitespace; if the immediately-preceding non-trivia
character is `}`, emit TS2809 instead. Full-suite 10078/1306/3 (was 10078/1307/3, +1
net). Zero regressions.

**B42.5 (2026-05-17, +1 — flips `errorOnInitializerInObjectTypeLiteralProperty_ts` errors-baseline)** —
Parser's `parseTypeMember` (shared by interface bodies AND type literals) now emits TS1247
"A type literal property cannot have an initializer" when parsing inside a type literal
`{ ... }` in type position, and TS1246 "An interface property cannot have an initializer."
when parsing inside an interface body. Distinguished via a new class-level flag
`inTypeLiteralForErrorWording` toggled by `parseTypeLiteralOrMappedType` with try/finally
restore. Checker.kt's TS1246 emission was already correctly scoped to InterfaceDeclaration.
Full-suite 10078/1307/3 (was 10078/1308/3, +1 net). Zero regressions.

**B42.4 (2026-05-17, +1 — flips `requireOfJsonFileNonRelativeWithoutExtensionResolvesToTs_ts` JS-emit)** —
`extractRelativeImports` now walks up from the current file's directory looking for
`node_modules/<specifier>.ts` / `.tsx` / `.d.ts` / `/index.{ts,tsx,d.ts}` when a bare
specifier didn't resolve via the standard candidate list. For multi-file test
fixtures that set up `@Filename: /src/node_modules/X.ts` and import via bare
specifier from a sibling, this adds the missing dep edge so `topologicalSort`
produces the correct emit order (`node_modules/X.js` before the importer).
Probe-dir walk: start at `dir`, try probes; on no match move up one segment
(`lastIndexOf('/')`) and retry; stop at empty string. Only fires for non-relative
specifiers AFTER standard candidates failed — bounded fallback. Full-suite
10078/1308/3 (was 10078/1309/3, +1 net). Zero regressions.

**B42.3 (2026-05-17, +1 — flips `isolatedModulesExportImportUninstantiatedNamespace_ts` errors-baseline)** —
New TS1269 emission: "Cannot use 'export import' on a type or type-only namespace
when 'isolatedModules' is enabled" fires for `export import X = Y` where Y resolves
to a type-only export from another file. Gate: `options.isolatedModules &&
!options.verbatimModuleSyntax`, ImportEqualsDeclaration with Export modifier,
non-ExternalModuleReference (skip `export import X = require(...)` cases), and the
root identifier resolves to a type-only import alias. Detection extends
`isExportedNameTypeOnly` to also recognize `export namespace` with
`ModuleInstanceState.NonInstantiated` — the existing helper missed namespaces.
Squiggle span: walks backward from `stmt.pos` (which is `import` keyword position)
to find the preceding `export` keyword, and ends at the trailing `;` (handles the
`node.end` overshoot gotcha). Full-suite 10078/1309/3 (was 10078/1310/3, +1 net).
Zero regressions.

**B42.2 (2026-05-17, +1 — flips `isolatedModulesAmbientConstEnum_ts` errors-baseline)** —
TS2748 "Cannot access ambient const enums when 'isolatedModules' is enabled" now fires
for `E.X` where `E` is a `declare const enum E { ... }` in a non-.d.ts file under
`@isolatedModules: true` (without `@preserveConstEnums`). Per-file check in
`checkSinglePropertyAccess` (Checker.kt:50001): resolves the receiver identifier,
walks `declarations` for `EnumDeclaration` with both Const + Declare modifiers, and
emits TS2748 at the receiver position with squiggle length = identifier text length.
Skip when `preserveConstEnums` is set (TypeScript still allows the access — the const
enum is preserved at runtime as an object). Per-file scope: uses `binderResults` lookup
matching the file's `sourceFile.fileName`, not a global enum cache, so cross-file
const enums declared via `declare const enum` in OTHER files are still flagged.
Full-suite 10078/1310/3 (was 10078/1311/3, +1 net). Zero regressions.

**B42.1 (2026-05-17, +1 — flips `isolatedModulesExportDeclarationType_ts` JS-emit)** —
For multi-file `@isolatedModules` with `import { T } from "./type"` where T resolves to
a type-only export, `isValueExport` was returning true (treating T as runtime) because
the symbol's `flags` had been polluted by `mergeSymbolTable` — same-name symbols from
importing files merge their flags into the target file's locals (CLAUDE.md gotcha:
"ALL file locals merged into globals at Checker init"). The polluted T had
BlockScopedVariable|Alias|TypeAlias|ExportValue flags from cross-file merging.

`isValueExport` now scans the target file's source statements DIRECTLY to classify
declarations of `name` as value or type, avoiding the polluted symbol flags. For names
not found as direct declarations (ambient/aliased cases), falls back to the
flag-based logic. Companion change: `ExportAssignment` for `export default expr` now
captures and propagates `trailingComments` through `makeExportAssignment` so the
`// Ok` comment on `export default T;` survives erasure-vs-emission. Restricted the
parser change to `export default` only (NOT `export =`): under ES-module emission,
`export = X` is silently dropped by `Emitter.emitExportAssignment`, and
`emitTrailingCommentsBeforeNewline` would otherwise back up past the prior statement's
newline and attach the comment there (`es6ExportAssignment2_ts` regression). Full-suite
10078/1311/3 (was 10078/1312/3, +1 net). Zero regressions.

**B41.2 (2026-05-17, +1 — flips `numericLiteralsWithTrailingDecimalPoints01_ts` JS-emit)** —
Multi-line property access (`expr\n  /* comment */ .name`) now preserves the leading
comment between the expression and the dot. Previously, the comment was attached to
the dot token in the scanner but lost on the next `scanner.scan()` call (which resets
`leadingComments`). The parser now captures `leadingComments()` BEFORE calling
`nextToken()` to consume the dot (when `newLineBefore=true`), and merges them into
the property name's `leadingComments`. The emitter handles them specially when
`newLineBefore=true`: emit AFTER the indent, BEFORE the dot. Block comments are
followed by a space (`/* comment */ .toString()` form); line comments are followed
by newline + indent (`// comment\n    .toString()` form). Full-suite 10078/1312/3
(was 10078/1313/3, +1 net). Zero regressions; only the target test flips.

**B41.1 (2026-05-17, +2 — flips `functionsMissingReturnStatementsAndExpressions_ts` target_es5/target_es2015)** —
TS2355 ("function whose declared type is neither 'undefined', 'void', nor 'any' must
return a value") now fires for union-with-undefined return types like `undefined | number`
when the function has no explicit return statements. Previously, the "nullable"
classification suppressed TS2355 entirely; the early-return in `checkBodyForImplicitReturn`
matched union-with-undefined unconditionally. Per TypeScript's actual behavior, `undefined`
in a union does not satisfy the "must return a value" rule — only `void`/`any`/`never` (in
a union) or `undefined` (as a bare keyword, or as the single arg to `Promise<...>` for
async functions) suppress TS2355. The fix: replaced the bare early-return with a TS2355
emission for "nullable + !hasAnyReturn"; updated the "pure-undefined" check to also accept
`Promise<undefined>` (where the arg is a `KeywordTypeNode` for `undefined`) so async
`Promise<undefined>` return types still suppress TS2355. Both non-strict (`f23(): undefined
| number`) and strict (`f11(): undefined | number`, `f31(): Promise<undefined | number>`)
behavior covered. Full-suite 10078/1313/3 (was 10078/1315/3, +2 net). Zero regressions.

**B40.1 (2026-05-17, +1 — flips `declarationEmitResolveTypesIfNotReusable_ts` JS-emit)** —
Parser's `TypeOfKeyword` branch in `parseNonArrayType` now handles indexed-access
suffix `typeof X[K]` in addition to the existing array-suffix `typeof X[]` case.
Previously, `(o: typeof a['a']) => {}` would parse `typeof a` as the type and leave
`['a']` for the outer parser, which misinterpreted it as a destructured second
parameter (yielding `(o, []) => 'a';\n{ }`). The extended `while` loop now follows
the same pattern as the primary-type path immediately below — when the bracket is
not empty, consume `[`, parse an index type, expect `]`, wrap in `IndexedAccessType`.
ASI guard added (`!scanner.hasPrecedingLineBreak()`) to match the primary-type
loop's behavior. Full-suite 10078/1315/3 (was 10078/1316/3 post-B39.1, +1 net).
Zero regressions; only the target test flips.

**B39.1 (2026-05-17, +1 — flips `exportAssignmentImportMergeNoCrash_ts` JS-emit)** —
Preserve `const tempName = __importDefault(require(...))` for a default import whose
user-facing local binding name is SHADOWED by a same-name top-level
`VariableStatement`/`FunctionDeclaration`/`ClassDeclaration` declaration in the
original source AND the binding name is referenced in value positions. TypeScript
keeps the require's side-effect emit even when the temp const's identifier becomes
unused in the rewritten output because the shadowing local wins the rename map
(`Obj → exports.Obj` via Direct path) instead of `Obj → <temp>.default`. Example:
`import Obj from "./assignment"; export const Obj = void Obj;` previously elided
`const assignment_1 = __importDefault(require("./assignment"))` because
`assignment_1` appeared unused in the result — now kept. Gate is strictly limited
to default imports (not named — those may resolve to type-only targets via
`export type` re-resolution) AND shadowed cases only (not normal const-enum
imports whose references get inlined to `0 /* X.Foo */` and which must still
elide). 22-line addition in `Transformer.kt` `transformToCommonJS` Step 2
elision (~line 2486). Full-suite 10078/1316/3 (was 10078/1317/3, +1 net). Zero
regressions.

**B38.1 (2026-05-17, +1 — flips `privacyTopLevelInternalReferenceImportWithExport_ts` JS-emit)** —
Exported `import alias = X.Y` is now erased when `X` is a non-exported but
runtime-instantiated namespace AND `Y` is a type-only sub-member (interface,
type alias, or type-only sub-namespace). Previously, the `requireRootExported`
gate on `isQualifiedPathTypeOnly` was too narrow — it kept aliases that TypeScript
erases. The new gate `requireRuntimeOrExportedRoot` allows the root to be EITHER
exported OR runtime-instantiated. Example: `namespace m_private { export class
c_private {}; export interface i_private {}; export namespace mu_private { export
interface i {} } }` + `export import im_public_i_private = m_private.i_private;`
+ `export import im_public_mu_private = m_private.mu_private;` — both now erased
because `m_private` has runtime members (class/enum/var) and `i_private` /
`mu_private` are type-only. Non-runtime-non-exported roots (e.g. `namespace x {
interface c {} }` + `export import a = x.c`) still keep the alias with a
runtime-broken `exports.a = x.c` emit, matching TypeScript's behavior of emitting
syntactic value references even when they'd fail at runtime. Three call sites
updated (CJS pre-scan, AMD pre-scan, `transformImportEqualsDeclaration`); helper
renamed and gate condition extended. Verified zero regressions across 10078-test
suite — only the target test flips.

