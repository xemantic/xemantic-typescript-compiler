/*
 * BENCHMARK DRIVER for smol-toml — ours, not the library's.
 *
 * Same source for both toolchains (tsgo -> JavaScript -> Node, and xtsc's
 * Kotlin-IR backend -> JVM bytecode -> java). The workload is one full parse of
 * the document the acceptance test uses, repeated: scanner, value parsing,
 * dates, arrays, inline tables, table headers, array-of-tables.
 *
 * `sink` reads a field off the result so that neither backend may treat the
 * parse as dead code.
 */
import { parse } from './parse.ts'

const document = "title = \"TOML Example\"\nenabled = true\nratio = 0.5\nnegative = -17\nexponent = 1e3\nunderscored = 1_000_000\nhex = 0xDEADBEEF\noctal = 0o755\nbinary = 0b1010\nempty_string = \"\"\nescaped = \"line\\nbreak\\ttab \\\"quoted\\\" back\\\\slash\"\nliteral = 'C:\\Users\\nobody'\nmultiline = \"\"\"\nfirst\nsecond\"\"\"\n\n[owner]\nname = \"Tom Preston-Werner\"\ndob = 1979\n\n[database]\nports = [ 8000, 8001, 8002 ]\ndata = [ [\"delta\", \"phi\"], [3.14] ]\ntemp_targets = { cpu = 79.5, case = 72.0 }\n\n[servers.alpha]\nip = \"10.0.0.1\"\nrole = \"frontend\"\n\n[servers.beta]\nip = \"10.0.0.2\"\nrole = \"backend\"\n\n[[products]]\nname = \"Hammer\"\nsku = 738594937\n\n[[products]]\nname = \"Nail\"\nsku = 284758393\ncolor = \"gray\"\n\n[deep.nested.table]\nvalue = \"reached\"\n"

function now(): number {
	return new Date().getTime()
}

function round(iterations: number): number {
	let acc = 0
	let i = 0
	while (i < iterations) {
		const parsed = parse(document) as any
		acc = acc + (parsed.negative as number)
		i = i + 1
	}
	return acc
}

const ITERATIONS = 20000
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
	'toml best_ms=' + best +
	' mean_ms=' + total / MEASURED_ROUNDS +
	' parses_per_round=' + ITERATIONS +
	' us_per_parse=' + (best * 1000) / ITERATIONS +
	' sink=' + sink
)
