export const meta = {
  name: 'triage-none-pool',
  description: 'Deep-trace triage of bounded NONE-pool failures: per-test code-path analysis + FP-safe recipe',
  phases: [
    { title: 'Triage' },
  ],
}

// args: array of { name, src } — one candidate failing test each.
const candidates = typeof args === 'string' ? JSON.parse(args) : args

const SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    test: { type: 'string' },
    verdict: {
      type: 'string',
      enum: ['FLIP_NOW', 'ATTEMPT', 'ARCHITECTURAL', 'MISSING_SOURCE'],
      description: 'FLIP_NOW: you READ the exact existing code path and a small additive change fires the missing diagnostic with a tight FP firewall. ATTEMPT: plausible bounded fix but FP surface or reachability uncertain. ARCHITECTURAL: needs a named Blocker (type-engine/cross-file-scope/lib-content/JSDoc-parser/decl-emit-nameability). MISSING_SOURCE: source .ts absent.',
    },
    ts_codes: { type: 'string', description: 'The TS diagnostic code(s) the baseline expects that we emit none of, e.g. "TS2345" or "TS2307+TS2593".' },
    gap_location: { type: 'string', description: 'EXACT file:line (e.g. Checker.kt:75683) of the function/branch where the additive change goes, OR "new check / new walker" if none exists. Required to justify FLIP_NOW.' },
    recipe: { type: 'string', description: 'Concrete 1-4 sentence implementation: which function, what gate/branch to add, what shape it matches, why it is FP-safe. For ARCHITECTURAL, name the blocker and the missing infra.' },
    fp_risk: { type: 'string', enum: ['LOW', 'MED', 'HIGH'] },
    confidence: { type: 'number', description: '0..1 confidence the verdict is correct AND (if FLIP_NOW) the recipe lands cleanly.' },
    blocker: { type: 'string', description: 'Named blocker if ARCHITECTURAL, else "none".' },
  },
  required: ['test', 'verdict', 'ts_codes', 'gap_location', 'recipe', 'fp_risk', 'confidence', 'blocker'],
}

function prompt(c) {
  return `You are triaging ONE failing test in a Kotlin port of the TypeScript compiler. The repo root is the cwd. The test currently emits NO diagnostics but the baseline expects some (a "NONE-PRODUCED" failure).

TEST: ${c.name}
SOURCE: ${c.src}
BASELINE: typescript-repo/tests/baselines/reference/${c.name}.errors.txt

Your job: decide whether this is a CLEAN bounded surgical win (FLIP_NOW), a plausible bounded attempt (ATTEMPT), or genuinely architectural (ARCHITECTURAL), and produce a precise FP-safe recipe.

STEPS (hard budget: <=14 tool calls — END by calling StructuredOutput, do NOT exhaust your turn first):
1. Read the source .ts and the .errors.txt baseline. Note which @directives are set (target/module/strict/etc) and the EXACT diagnostic code(s) + position + message text expected.
2. Identify the SINGLE root reason we emit nothing. Then GREP the Kotlin source (Checker.kt is the big one ~85k lines; also Parser.kt, Scanner.kt, Binder.kt, Transformer.kt, Emitter.kt, CompilerOptions.kt, TypeScriptCompiler.kt under src/commonMain/kotlin/) for the function that DOES (or should) emit this code. Find the precise branch/gate that bails or is missing.
3. Classify.

CRITICAL ANTI-OVER-CLASSIFICATION RULE (this fleet historically over-calls FLIP_NOW ~5x): you may ONLY return FLIP_NOW if you have actually READ the existing code path and can name the EXACT file:line where a SMALL ADDITIVE change (a new gated branch / a new narrow walker) fires the diagnostic, AND you can state a tight FP firewall (a gate so specific that no passing test matches it). If the missing diagnostic depends on a type the engine cannot currently produce (mapped-type/conditional-type materialization, generic argument inference through calls, cross-file per-file scope, contextual typing, lib-content the embedded lib lacks, a JSDoc type parser we do not have, decl-emit type-nameability), it is ARCHITECTURAL — name the blocker. When in doubt between FLIP_NOW and ATTEMPT, choose ATTEMPT. When in doubt between ATTEMPT and ARCHITECTURAL, prefer ARCHITECTURAL if it needs new infra.

Your StructuredOutput.recipe must be concrete enough that a senior engineer could implement it WITHOUT re-reading the test. For FLIP_NOW/ATTEMPT, gap_location MUST be a real file:line you found.`
}

phase('Triage')
const results = await parallel(
  candidates.map((c) => () =>
    agent(prompt(c), { label: `triage:${c.name}`, phase: 'Triage', schema: SCHEMA, agentType: 'Explore' })
      .catch(() => null)
  )
)

const ok = results.filter(Boolean)
const order = { FLIP_NOW: 0, ATTEMPT: 1, ARCHITECTURAL: 2, MISSING_SOURCE: 3 }
ok.sort((a, b) => (order[a.verdict] - order[b.verdict]) || (b.confidence - a.confidence))

const flip = ok.filter((r) => r.verdict === 'FLIP_NOW')
const attempt = ok.filter((r) => r.verdict === 'ATTEMPT')
log(`Triage done: ${ok.length}/${candidates.length} returned. FLIP_NOW=${flip.length} ATTEMPT=${attempt.length} ARCH=${ok.filter(r=>r.verdict==='ARCHITECTURAL').length}`)
for (const r of flip) log(`  FLIP_NOW [${r.confidence}] ${r.test} (${r.ts_codes}) @ ${r.gap_location} fp=${r.fp_risk}`)
for (const r of attempt.slice(0, 12)) log(`  ATTEMPT  [${r.confidence}] ${r.test} (${r.ts_codes}) fp=${r.fp_risk}`)

return ok
