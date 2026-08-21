/*
 * BENCHMARK DRIVER for mitt 3.0.1 — ours, not the library's.
 *
 * One source, compiled by two toolchains: tsgo (to JavaScript, run on Node) and
 * xtsc's Kotlin-IR backend (to JVM bytecode, run on java). Whatever it measures,
 * it measures the same way on both.
 *
 * The workload is `emit`, which is where a mitt program spends its time: a Map
 * lookup, a `.slice()` copy of the handler list, a `.map()` over it, and a call
 * per handler — twice, because '*' handlers are consulted on every emit.
 */
import mitt from './mitt'

type Events = {
	tick: number
	msg: string
}

const emitter = mitt<Events>()

let sink = 0

emitter.on('tick', (n) => {
	sink = sink + n
})
emitter.on('msg', (s) => {
	sink = sink + s.length
})
emitter.on('*', (type) => {
	sink = sink + 1
})

function round(iterations: number): void {
	let i = 0
	while (i < iterations) {
		emitter.emit('tick', 1)
		emitter.emit('msg', 'x')
		i = i + 1
	}
}

function now(): number {
	return new Date().getTime()
}

const ITERATIONS = 2000000
const WARMUP_ROUNDS = 6
const MEASURED_ROUNDS = 10

let w = 0
while (w < WARMUP_ROUNDS) {
	round(ITERATIONS)
	w = w + 1
}

let best = 1000000000
let total = 0
let r = 0
while (r < MEASURED_ROUNDS) {
	const start = now()
	round(ITERATIONS)
	const elapsed = now() - start
	if (elapsed < best) {
		best = elapsed
	}
	total = total + elapsed
	r = r + 1
}

const emits = ITERATIONS * 2
console.log(
	'mitt best_ms=' + best +
	' mean_ms=' + total / MEASURED_ROUNDS +
	' emits_per_round=' + emits +
	' ns_per_emit=' + (best * 1000000) / emits +
	' sink=' + sink
)
