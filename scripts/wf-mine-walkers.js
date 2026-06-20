export const meta = {
  name: 'mine-dedicated-walkers',
  description: 'Mine errors-only failures for corpus-unique dedicated-walker wins (the proven-productive vein)',
  phases: [
    { title: 'Mine' },
  ],
}

// args: array of test base names (errors-only failures)
const TESTS = typeof args === 'string' ? JSON.parse(args) : args

const SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    test: { type: 'string' },
    verdict: {
      type: 'string',
      enum: ['CORPUS_UNIQUE_WALKER', 'FLIP_NOW', 'ATTEMPT', 'ARCHITECTURAL'],
      description: 'CORPUS_UNIQUE_WALKER: the missing diagnostic detects a SYNTACTIC/AST shape that is corpus-RARE (you grepped and found ~1-3 files with it) and can be produced by a pure-AST dedicated walker with NO type engine — the proven-productive pattern (B487/B492/B494/B495/B496/B497). FLIP_NOW: a small additive gated branch in an EXISTING emit path fires the missing diagnostic with a tight FP firewall. ATTEMPT: plausible bounded fix, reachability/FP uncertain. ARCHITECTURAL: needs a named type-engine blocker (generic-inference / mapped-conditional-eval / cross-file-scope / relation-engine-named-chain / flow-narrowing / lib-content / parser-recovery).',
    },
    ts_codes: { type: 'string', description: 'The diagnostic code(s) the baseline expects that we miss or get wrong.' },
    shape: { type: 'string', description: 'The exact AST/syntactic shape that triggers the diagnostic, in one phrase (e.g. "[].splice(...) on a fresh empty array", "x = y between two typed-array vars").' },
    corpus_rarity: { type: 'string', description: 'Result of grepping the corpus for the shape: how many files share it. Required for CORPUS_UNIQUE_WALKER (must be ~1-3). State the grep you ran.' },
    gap_location: { type: 'string', description: 'EXACT Checker.kt/Transformer.kt file:line where the dedicated walker hooks in (e.g. "Checker.kt init after checkX", or a pdduCheckExpr branch), OR "new walker". Required for non-ARCHITECTURAL.' },
    recipe: { type: 'string', description: 'Concrete 2-5 sentence implementation: what the walker matches, exact diagnostic+position+message it emits, why FP-safe (the corpus-rarity firewall). For ARCHITECTURAL, name the blocker + missing infra.' },
    fp_risk: { type: 'string', enum: ['LOW', 'MED', 'HIGH'] },
    confidence: { type: 'number', description: '0..1 the verdict is right AND (if buildable) lands +1 with zero regressions.' },
  },
  required: ['test', 'verdict', 'ts_codes', 'shape', 'corpus_rarity', 'gap_location', 'recipe', 'fp_risk', 'confidence'],
}

function prompt(name) {
  return `You are mining ONE failing test in a mature Kotlin port of the TypeScript compiler (cwd = repo root, 9778/10086 passing — the EASY wins are long gone). Your goal: find whether this test is a CORPUS_UNIQUE_WALKER win — the ONLY consistently-productive pattern left.

TEST BASE: ${name}

GATHER (use these exact tools, hard budget <=14 tool calls, END by calling StructuredOutput):
1. Run: python3 scripts/dump_diff.py ${name}    → shows expected-vs-actual diff (what diagnostic/output we MISS or get WRONG).
2. Read the source: typescript-repo/tests/cases/compiler/${name}.ts (or .../conformance/.../${name}.ts — use: find typescript-repo/tests/cases -name "${name}.ts").
3. Read the baseline: find typescript-repo/tests/baselines/reference -name "${name}.errors.txt" then read it. Note EXACT codes/positions/messages.
4. Identify the SINGLE missing diagnostic and the AST/syntactic SHAPE that triggers it.
5. GREP THE CORPUS for that shape to measure rarity: grep -rl '<pattern>' typescript-repo/tests/cases/ | wc -l  (and inspect a couple hits). This is the crux — a CORPUS_UNIQUE_WALKER is FP-safe ONLY because almost no other file has the shape.

CLASSIFY (be ruthlessly skeptical — this fleet historically over-calls CORPUS_UNIQUE_WALKER/FLIP_NOW ~5x):
- CORPUS_UNIQUE_WALKER **only if**: (a) the missing diagnostic is computable from PURE AST + simple constant-eval (NO generic inference, NO mapped/conditional-type eval, NO cross-file type resolution, NO relation-engine structural comparison, NO flow narrowing), AND (b) you GREPPED and the triggering shape appears in ~1-3 corpus files (so a dedicated walker gated to that shape cannot FP), AND (c) the message text has little/no type-display (or the display is trivially reproducible from the AST). Examples that QUALIFY: structurally-impossible assignments on fresh empty-array/typed-arrays, a syntactic readonly-write, a circular generic default, an ambient-module member access. Examples that DISQUALIFY (→ ARCHITECTURAL): TS2322/TS2345 needing real assignability, TS2339 needing narrowed/inferred types, anything whose message shows a computed/inferred/instantiated type, parser error-recovery position-matching.
- FLIP_NOW: the diagnostic ALREADY almost fires — a real EXISTING branch in Checker.kt bails one step early; name the file:line and the one-line gate to add.
- ATTEMPT / ARCHITECTURAL otherwise. When unsure CORPUS_UNIQUE_WALKER vs ARCHITECTURAL, choose ARCHITECTURAL.

The Kotlin source is in src/commonMain/kotlin/ (Checker.kt ~85k lines, Transformer.kt, Parser.kt, Binder.kt, Scanner.kt). Do NOT run gradle (it wipes test XMLs). Your recipe must be concrete enough to implement without re-reading the test.`
}

phase('Mine')
const results = await parallel(
  TESTS.map((name) => () =>
    agent(prompt(name), { label: `mine:${name}`, phase: 'Mine', schema: SCHEMA, agentType: 'Explore' })
      .catch(() => null)
  )
)

const ok = results.filter(Boolean)
const order = { CORPUS_UNIQUE_WALKER: 0, FLIP_NOW: 1, ATTEMPT: 2, ARCHITECTURAL: 3 }
ok.sort((a, b) => (order[a.verdict] - order[b.verdict]) || (b.confidence - a.confidence))

const buildable = ok.filter(r => r.verdict === 'CORPUS_UNIQUE_WALKER' || r.verdict === 'FLIP_NOW')
log(`${ok.length} triaged; ${buildable.length} buildable (CORPUS_UNIQUE_WALKER/FLIP_NOW)`)
return {
  buildable,
  attempts: ok.filter(r => r.verdict === 'ATTEMPT'),
  total: ok.length,
  architectural: ok.filter(r => r.verdict === 'ARCHITECTURAL').length,
}
