#!/usr/bin/env bash
# (WARM.32) round 905 — the ITERATOR-ALLOCATION family.
#
# Kotlin's `Iterable.any`/`forEach` are `inline` but their bodies are
# `for (e in this)` on an `Iterable` receiver, so each asks the receiver for a
# HEAP ITERATOR and pays `hasNext`/`next` interface dispatch per element. Two
# populations: `forEachChild`'s 70 list child positions (once per node, three
# sweeps, #5 in the warm leaf table at 1.40%) and the INV.4 edge classifiers'
# 145 `.any { it === child }` identity tests (round 875's 3.32 M edge
# evaluations at 13.3 ns each = a 44 ms ceiling for that half).
#
# THE CAVEAT THAT DECIDES THE INSTRUMENT: the value is NOT the allocated bytes.
# Round 801 removed 367,189 `String` allocations for exactly 0 ms and round 893
# measured warm GC at ~1.7% of wall with the FASTER binary taking MORE pauses.
# So this is priced in TIME, by a two-arm amplifier, and there is no allocation
# counter anywhere in the instrument.
#
# Order matters: the CENSUS runs first and can refuse the candidate outright
# (round 904's method — population x premium against the floor, with the
# threshold population computed BEFORE any amplifier is built).
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round905
mkdir -p "$OUT"
date > "$OUT/started"

CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
TEST_CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test
# Round 853: a gate reading a class DIRECTORY needs a positive control that the
# code under test is in it. Round 852: a cached classpath outlives its subject.
[[ -f "$CLASSES/com/xemantic/typescript/compiler/MainKt.class" ]] || {
  echo "ABORT — the core class dir has no MainKt: it is not the compiler" | tee -a "$OUT/started"; exit 1; }
[[ -f "$TEST_CLASSES/com/xemantic/typescript/compiler/bench/BenchMainKt.class" ]] || {
  echo "ABORT — no BenchMainKt in the test class dir" | tee -a "$OUT/started"; exit 1; }
[[ -f "$CLASSES/com/xemantic/typescript/compiler/IterCensus.class" ]] || {
  echo "ABORT — no IterCensus.class: the round's own code is not in the dir" | tee -a "$OUT/started"; exit 1; }
[[ "$CLASSES/com/xemantic/typescript/compiler/IterCensus.class" -nt \
   xemantic-typescript-compiler-core/src/commonMain/kotlin/IterCensus.kt ]] || {
  echo "ABORT — IterCensus.class is older than IterCensus.kt: stale build" | tee -a "$OUT/started"; exit 1; }

. scripts/lib/dep-classpath.sh
DEPS="$(xtsc_dep_classpath build/bench/cp-warm.txt)" || exit 1
CP="$CLASSES:$TEST_CLASSES:$DEPS"
PROJ=$(ls -d build/bench/tsc-project-* | head -1)
echo "PROJ=$PROJ CP ok" >> "$OUT/started"

WARMUP=${WARMUP:-6}
ITERS=${ITERS:-3}

run() {
  local tag="$1" tiers="$2"
  echo "=== $tag  tiers=$tiers  warmup=$WARMUP iters=$ITERS ===" | tee -a "$OUT/started"
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.bench.BenchMainKt \
    "$PROJ" "$WARMUP" "$ITERS" "$tiers" > "$OUT/$tag.txt" 2>&1
  grep -aE '"summary"|WARM.32|calls=|histogram|EMPTY=|concrete List|ns per call|EQUIVALENCE|A - B' \
    "$OUT/$tag.txt" | tee -a "$OUT/started"
}

case "${1:-all}" in
  census|all)
    # Deterministic, so a disagreement between two processes is an instrument
    # fault and not a measurement.
    run census1 itercensus
    run census2 itercensus
    ;;&
  amp|all)
    # Two `r`, ABBA inside each process (the arms alternate which goes first)
    # and the rotation MIRRORED across the two processes — round 891: one
    # rotation is not enough at two draws per arm, because the leading draw's
    # ~15% lands wholly on whichever arm ran first.
    run ampA iteramp8,iteramp24,iteramp24,iteramp8
    run ampB iteramp24,iteramp8,iteramp8,iteramp24
    ;;
esac

date >> "$OUT/started"
touch "$OUT/done"
