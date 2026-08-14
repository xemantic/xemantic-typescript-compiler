#!/usr/bin/env bash
# (WARM.33) round 906 — the 43 per-file INV.4 reach MEMOS, and whether
# transposing them into one row per node is worth taking.
#
# THE TRAP THIS ROUND IS DESIGNED AROUND: this is a CACHE-LOCALITY question,
# and round 759's amplifier shape (repeat the SAME probe `r` times under one
# timestamp pair) CANNOT measure one — after the first repetition the line is
# L1-hot, so it prices an L1 hit, which is exactly the cost the transposition
# is supposed to remove. So the census comes first and is expected to carry the
# round: an access COUNT, a per-node classifier DISTRIBUTION, the real
# per-(file, classifier) memo FOOTPRINT, and the ascent's nodeId GAPS are all
# deterministic counters, and the box's cache geometry (L1d 32 KiB/core,
# L2 512 KiB/core, L3 16 MiB) turns them into a bound.
#
# Round 851's order: every gradle-invoking step BEFORE the daemon stop.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round906
mkdir -p "$OUT"
date > "$OUT/started"

CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
TEST_CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test
# Round 853: a gate reading a class DIRECTORY needs a positive control that the
# code under test is in it.
[[ -f "$CLASSES/com/xemantic/typescript/compiler/MainKt.class" ]] || {
  echo "ABORT — the core class dir has no MainKt: it is not the compiler" | tee -a "$OUT/started"; exit 1; }
[[ -f "$TEST_CLASSES/com/xemantic/typescript/compiler/bench/BenchMainKt.class" ]] || {
  echo "ABORT — no BenchMainKt in the test class dir" | tee -a "$OUT/started"; exit 1; }
[[ -f "$CLASSES/com/xemantic/typescript/compiler/ReachCensus.class" ]] || {
  echo "ABORT — no ReachCensus.class" | tee -a "$OUT/started"; exit 1; }
[[ "$CLASSES/com/xemantic/typescript/compiler/Checker.class" -nt \
   xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt ]] || {
  echo "ABORT — Checker.class is older than Checker.kt: stale build" | tee -a "$OUT/started"; exit 1; }

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
  grep -aE '"summary"|WARM.22|WARM.33|classifier|TOTAL|consults|probes|footprint|histogram|gap' \
    "$OUT/$tag.txt" | tee -a "$OUT/started"
}

case "${1:-all}" in
  reach|all)
    run reach1 "reach"
    run reach2 "reach"
    ;;&
  memo|all)
    run memo1 "reachmemo"
    run memo2 "reachmemo"
    ;;
esac
date >> "$OUT/started"
touch "$OUT/done"
