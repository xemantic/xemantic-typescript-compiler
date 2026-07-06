# Phase 3 — Diagnostic-Driven Type Checker

## Summary

Phase 3 built the Binder, Checker, and diagnostic infrastructure on top of the
Phase 1/2 transpiler pipeline (Scanner → Parser → Transformer → Emitter).

**Result:** 7,632 / 10,077 tests passing (75.7%), up from 5,122 / 5,442 (94.1%)
at Phase 2 end. Added ~4,600 new tests (parameterized JS + error baselines),
implemented 80+ diagnostic codes, and brought the Checker from MVP const-enum
inlining to a scope-aware diagnostic engine with spelling suggestions, definite
assignment checking, unused detection, and basic type assignability.

**Detailed session history:** `PLAN-PHASE-3-done.md`

---

## Current state (2026-03-28)

- **10,077 tests**, 7,632 passing (75.7%), 2,446 failing
- **Checker.kt**: 21,827 lines — scope-aware name resolution, 80+ TS diagnostic codes
- **Remaining ceiling without full type checker: ~77% (7,770 tests)**

### Failure breakdown (2,446 remaining)

| Category | Count | Notes |
|----------|-------|-------|
| Error "none produced" | ~1,521 | Need structural type checker: TS2322, TS2345, TS2339 |
| Error diff (only missing) | ~443 | We produce correct subset, need more codes |
| Error diff (mixed FP+missing) | ~216 | Both false positives and missing diagnostics |
| Error diff (pure FP) | ~11 | Need type inference or control flow to suppress |
| Error diff (parser) | ~67 | Parser error recovery cascade |
| JS emit | ~257 | Multi-file ordering, CJS, private fields |

### What's blocking further progress

~62% of remaining failures need **full structural type checking** (TS2322/TS2339/TS2345):
- Structural type compatibility (assignability between object types)
- Generic instantiation and type parameter inference
- Type inference from initializers, function calls, property access
- Control flow analysis (narrowing, exhaustive switches)

The remaining ~150 non-type-checker tests come from:
- Multi-file JS output ordering (~50 tests)
- Parser error recovery (~50 tests, high regression risk)
- Contextual typing for TS7006 suppression (~27 tests, needs type checker anyway)
- Private fields WeakMap transform (~12 tests)
- Decorator static members (~8 tests)

---

## Deferred queue (from Phase 3)

Items investigated and deferred. Sorted by estimated test impact.

### Blocked on type checker infrastructure

| Item | Est. tests | Reason deferred |
|------|-----------|-----------------|
| TS2339 — property does not exist on type | ~100 | Needs type tracking for property access |
| TS2345 — argument type not assignable | ~79 | Needs function signature matching |
| TS1005/TS1109 parser error recovery | ~81 | High regression risk, per-test investigation |
| TS7006 contextual typing suppression | ~27 | Needs type inference through unions/generics |
| TS2304/TS2693 remaining FP patterns | ~40 | Conditional types, cross-file refs |
| TS2391 false positive reduction | ~14 | Abstract methods, JSDoc, interface signatures |
| TS2554 false positive reduction | ~13 | Overloaded functions, rest params, base ctors |
| TS2813/TS2814 merge diagnostics | ~12 | Classes merging with non-ambient classes |
| TS2305 — module has no exported member | ~11 | Cross-module resolution |
| TS2591 — suggest require() for Node globals | ~10 | Would break many tests using KNOWN_GLOBALS |
| TS1212 strict reserved word FP | ~9 | Need granular strict mode levels |
| TS2300 merge compatibility | ~5-10 | TS2813/TS2814/TS2451 needed |

### Non-type-checker deferred items

| Item | Est. tests | Reason deferred |
|------|-----------|-----------------|
| CJS multi-file transform | ~20 | Self-referencing exports, import alias, comment hoist |
| node_modules skip in multi-file | ~20 | Blanket skip causes 72 regressions |
| TS1128 declaration or statement expected | ~15 | Parser recovery contexts |
| TS2591 Node.js globals | ~10 | Needs conditional scope (module files only) |
| Nested const enum inlining | ~4 | Needs TS2651/TS2474 validation diagnostics |
| createRequire pattern for .mts node16 | ~4 | Module transform complexity |
| TS2802 iterators need ES2015+ | ~4 | Needs type inference for iterable detection |
| Import alias variable preservation | ~3 | Needs empty namespace IIFE emission |
| export * as ns downlevel transform | ~3 | Tests also need ES5 class/importHelpers |
| CJS first-statement comment hoisting | ~3 | Comment before Object.defineProperty |
| CJS import elision for export = X refs | ~3 | Circular self-reference + type-only |
| Namespace heritage clause qualification | ~2 | extends B.EventManager in IIFE |
| CJS alias qualification for namespace re-exports | ~2 | exports.bVal = exports.b |
| JSX import preservation | ~2 | CJS transform elides react require |
| TS2497 module has no default export | ~2 | Cross-module resolution |
| CJS self-referencing (0, exports.X) form | ~2 | Indirect call pattern |
| TS2564/TS2454 suppression for type-error vars | ~2 | Overlaps with type inference tests |
| ESM file module format detection | ~2 | Module/parser interaction |
| TS2451 block-scoped variable redecl | ~1 | Tests also need TS6203/TS6204 |
| var x; for unused type-only variables | ~1 | Parser error recovery in interfaces |

### Not planned for Phase 3

- `.symbols` baselines (14,015 tests) — full symbol resolution display
- `.types` baselines (14,015 tests) — full type inference and display
- `.sourcemap.txt` / `.js.map` baselines — source map generation
- `.trace.json` baselines — module resolution tracing
- `__generator` state machine — async-to-generator downlevel
- Private field WeakMap transform — ~4 tests
- Inline sourcemaps — ~4 tests

---

## Success criteria (achieved)

1. All TypeScript test cases with `.js` and `.errors.txt` baselines are `@Test` functions
2. `.errors.txt` tests serve as the primary scorecard
3. Parser diagnostics use correct TypeScript error codes and positions
4. Checker emits 80+ diagnostic codes (top-5 and beyond)
5. No regressions in JS emit tests
6. Clear per-session progress metric tracked
