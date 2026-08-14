#!/usr/bin/env bash
# (WARM.31) round 904 — the residual BOXED-PRIMITIVE map/set keys.
#
# `docs/perf/warm-leaf-profile.md` § 33.8 candidate (6) quotes `Integer.equals`
# at 29.4 ms of key-side leaf work and says, in as many words, that it is "a
# LOCATION, not yet a candidate": nothing had established WHICH maps hold it.
#
# THIS IS A COUNTER, NOT A FIX, and the reason it is only a counter is round
# 898's admission test transposed one family over. What a container swap returns
# is `population x per-operation premium`; the premium is ONE number shared by
# every site in the family (a boxed-`Int` `HashMap` probe is ~15-30 ns and round
# 903 measured a `LongKeyMap` probe at 2.11 ns, so the most generous credible
# net premium is ~10 ns). At 10 ns and an arc floor of ~17 ms, a site needs
# **~1.7 M operations per rebuild** to be worth a LOW-risk swap on its own —
# against a whole-spine node population of 856,962. So the only unknown per site
# is its population, and a population is a counter.
#
# The census is DETERMINISTIC, which is its own falsifier: two runs must agree
# to the last digit, and two of the sites are POSITIVE CONTROLS with an
# independently measured answer (round 900's `risgCalls` 259,739; round 896's
# `symAdds` 24,232). Round 902's law is why they are there — a zero, or a wrong
# number, from a mis-wired hook is indistinguishable from a real negative.
#
# WARM anyway, so the populations are the ones a warm rebuild really performs
# (`CrawlParseCache` serves the parse, round 897) and so the figure is directly
# comparable to this arc's other populations.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round904
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
[[ "$CLASSES/com/xemantic/typescript/compiler/MapCensus.class" -nt \
   xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt ]] || {
  echo "ABORT — MapCensus.class is older than MapCensus.kt: stale build" | tee -a "$OUT/started"; exit 1; }

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
  grep -aE '"summary"|WARM.31|FAMILY TOTAL|CONTROLS' "$OUT/$tag.txt" | tee -a "$OUT/started"
}

# Two independent processes. The census is deterministic, so a disagreement
# between them is an instrument fault, not a measurement.
run census1 boxedkey
run census2 boxedkey

date >> "$OUT/started"
touch "$OUT/done"
