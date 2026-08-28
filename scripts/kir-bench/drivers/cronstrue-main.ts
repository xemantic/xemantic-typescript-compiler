/*
 * BENCHMARK DRIVER for cronstrue — ours, not the library's.
 *
 * NOT YET REGISTERED IN `kir-bench.sh`, deliberately. That harness REFUSES a run
 * whose arms it cannot all build, and arm 2 (xtsc -> JVM bytecode -> java) does
 * not exist for this library yet: it stops at the nominal/structural boundary,
 * (LIB.6). Registering it now would make the harness refuse every run. The
 * driver is committed because it is what the JS arms were measured with
 * (`docs/kir-library-readiness.md`, 2026-08-28) and because wiring it up when
 * (LIB.6) lands should be one line in the `for lib in …` loops rather than a
 * rewrite.
 *
 * The workload is one full description of each of twelve cron expressions,
 * repeated: the parser (field splitting, @-shorthand expansion, DOW/month name
 * normalisation, L/W/# specials) and the whole description pipeline (segment
 * descriptions, ranges, increments, 12-hour formatting, locale lookup and
 * `%s` substitution).
 *
 * `sink` accumulates the description LENGTHS so neither backend may treat the
 * work as dead, and so the two arms must agree before any timing is read.
 */
import cronstrue from './cronstrue'

const EXPRESSIONS: string[] = [
  '*/5 * * * *',
  '0 0 12 * * ?',
  '30 11 * * 1-5',
  '0 23 ? * MON-FRI',
  '23 12 * * SUN',
  '0 0 1 1 *',
  '*/5 * * * * *',
  '0 0/30 8-9 5,20 * ?',
  '@daily',
  '@hourly',
  '0 0 0 ? * 2#1',
  '0 15 10 L * ?',
]

function now(): number {
  return new Date().getTime()
}

function round(iterations: number): number {
  let acc = 0
  let i = 0
  while (i < iterations) {
    let e = 0
    while (e < EXPRESSIONS.length) {
      acc = acc + cronstrue.toString(EXPRESSIONS[e]).length
      e = e + 1
    }
    i = i + 1
  }
  return acc
}

const ITERATIONS = 2000
const WARMUP_ROUNDS = 6
const MEASURED_ROUNDS = 10

let sink = 0

let w = 0
while (w < WARMUP_ROUNDS) {
  sink = sink + round(ITERATIONS)
  w = w + 1
}

let best = 1000000000
let total = 0
let r = 0
while (r < MEASURED_ROUNDS) {
  const start = now()
  sink = sink + round(ITERATIONS)
  const elapsed = now() - start
  if (elapsed < best) {
    best = elapsed
  }
  total = total + elapsed
  r = r + 1
}

console.log(
  'cronstrue best_ms=' + best +
  ' mean_ms=' + total / MEASURED_ROUNDS +
  ' descriptions_per_round=' + ITERATIONS * EXPRESSIONS.length +
  ' us_per_description=' + (best * 1000) / (ITERATIONS * EXPRESSIONS.length) +
  ' sink=' + sink
)
