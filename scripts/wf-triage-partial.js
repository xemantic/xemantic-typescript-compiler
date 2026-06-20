export const meta = {
  name: 'triage-partial-emit',
  description: 'Deep root-cause analysis of small-diff partial-emit failing tests to find clean surgical fixes',
  phases: [{ title: 'Analyze' }],
}

const CANDIDATES = args && args.length ? args : [
  'allowJscheckJsTypeParameterNoCrash',
  'mergeSymbolReexportInterface',
  'unicodeIdentifierName2',
  'extension',
  'dottedModuleName',
  'parseErrorIncorrectReturnToken',
  'interfaceNaming1',
  'keyofDoesntContainSymbols',
  'exhaustiveSwitchCheckCircularity',
  'reverseMappedPartiallyInferableTypes',
  'didYouMeanElaborationsForExpressionsWhichCouldBeCalled',
  'typeofInternalModules',
  'circularOptionalityRemoval',
  'indexedAccessWithVariableElement',
  'conflictingDeclarationsImportFromNamespace1',
  'quickIntersectionCheckCorrectlyCachesErrors',
  'defaultBestCommonTypesHaveDecls',
  'classPropertyErrorOnNameOnly',
]

const SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    test: { type: 'string' },
    category: { type: 'string', enum: ['scanner','parser','display-swap','checker-emission','cross-file-merge','deep-inference','transformer','tsgo-irrelevant','other'] },
    tractable: { type: 'string', enum: ['clean-single-piece','two-piece','multi-piece','architectural'] },
    root_cause: { type: 'string', description: 'Precise root cause: which code path produces the wrong/missing output and why.' },
    fix_location: { type: 'string', description: 'File + function name + approx line where the fix goes.' },
    fix_sketch: { type: 'string', description: 'Concrete description of the change needed, enough to implement from.' },
    fp_surface: { type: 'string', description: 'What other tests/shapes could regress; how to gate FP-safely.' },
    risk: { type: 'string', enum: ['low','med','high'] },
    confidence: { type: 'number', description: '0..1 confidence this is correctly characterized.' },
  },
  required: ['test','category','tractable','root_cause','fix_location','fix_sketch','fp_surface','risk','confidence'],
}

const PROMPT = (t) => `You are triaging a single failing test in a Kotlin port of the TypeScript compiler. Goal: determine whether it has a CLEAN single-piece surgical fix, and if so, give a precise, implementable fix recommendation. Do NOT build or run the full test suite (it takes 6 minutes and would conflict). You MAY: run \`python3 scripts/dump_diff.py ${t}\` (reads existing test XMLs, shows expected-vs-actual diff), read the test source \`typescript-repo/tests/cases/compiler/${t}.ts\` (or under tests/cases/conformance/ — use find), read the baseline \`typescript-repo/tests/baselines/reference/${t}.errors.txt\`, and grep/read the compiler source in \`src/commonMain/kotlin/com/xemantic/typescript/compiler/\` (Scanner.kt, Parser.kt, Binder.kt, Checker.kt, Transformer.kt, Emitter.kt).

Steps:
1. Run dump_diff to see exactly what we emit vs what tsc expects (the '-' lines are expected/tsc, '+' lines are our actual output).
2. Read the .ts source and the .errors.txt baseline to understand the intended diagnostics.
3. Classify the diff: is it (a) a SWAP — right position, wrong code or wrong display text; (b) MISSING — we don't emit an error tsc does; (c) EXTRA — we emit an error tsc doesn't; or a mix.
4. Trace the responsible code path. For a SWAP/display issue, find the exact emission site and what type/string is computed. For MISSING, find why the check doesn't fire. For a parser/scanner recovery diff, find the recovery branch.
5. Check CLAUDE.md and PLAN-PHASE-4-HISTORY.md (grep for "${t}") for any prior skip reason or gotcha — incorporate it but VERIFY it's still accurate against current code.
6. Decide tractability HONESTLY:
   - "clean-single-piece": one localized change (one emission site / one display branch / one position calc / one recovery branch), FP-safe by a clear gate, would flip the WHOLE test (all diff lines resolve together).
   - "two-piece": two independent localized changes both needed.
   - "multi-piece"/"architectural": needs type-engine infra (generic inference, mapped/conditional eval, cross-file scope, full flow narrowing), or 3+ coupled pieces.

Be skeptical and precise. A "clean-single-piece" verdict means I will implement exactly your fix_sketch — so it must be correct and complete (cover EVERY line in the diff). If even one diff line needs separate deep work, it is NOT clean-single-piece. Return the structured verdict.`

phase('Analyze')
const results = await parallel(CANDIDATES.map((t) => () =>
  agent(PROMPT(t), { label: `triage:${t}`, phase: 'Analyze', schema: SCHEMA })
))

const ok = results.filter(Boolean)
const clean = ok.filter(r => r.tractable === 'clean-single-piece').sort((a,b)=>b.confidence-a.confidence)
const two = ok.filter(r => r.tractable === 'two-piece').sort((a,b)=>b.confidence-a.confidence)
log(`CLEAN: ${clean.length}, TWO-PIECE: ${two.length}, harder: ${ok.length-clean.length-two.length}`)
return { clean, two_piece: two, all: ok }
