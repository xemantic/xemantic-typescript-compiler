#!/usr/bin/env bash
# (WARM.30) round 903 — price `state.nodeTypes`' deep AST-VALUE key.
#
# `docs/perf/warm-hash-owner-census.md` ranks it at 57.1 ms, the largest single
# map owner in a warm rebuild, and round 898's admission test does NOT refute it:
# 354,131 deep hashes per rebuild is 161 ns each, possible only if the mean key
# subtree is large. Nothing had measured that, so this is the census — and the
# amplifier that separates the row's TWO OWNERS.
#
# THE STRUCTURAL POINT: `isPerFileDependentRefNode` is a recursive subtree walk
# over the SAME subtree the hash walks, running on EVERY call cacheable or not.
# A leaf-frame JFR profile charges it to the map and cannot tell them apart, so
# the amplifier brackets it as its own arm (arm C). Whatever the verdict on the
# hash, that separation is a correction to the attribution.
#
# WARM, not cold. Round 895 measured the JDK `String.indexOf` intrinsic warming
# 5.07x against a ~3.4x rebuild and `java.util.regex` warming ~1.5x, so a cold
# table ranks a text-shaped cost wrongly in EITHER direction; a `HashMap` probe
# has no reason to be exempt, and every number this round quotes is against a
# warm 5,429 ms denominator. `BenchMain`'s default warm-up of 6 is the measured
# floor for a between-process spread inside +-1% (2026-08-10).
#
# THE ROTATION IS THE PROTOCOL, TWICE. Round 869: the FIRST instrumented rebuild
# in a process is the slowest draw and is worth up to 15%, so an ascending ladder
# puts that bias wholly on the leading arm and FLATTENS the slope. Round 891: one
# rotation is not enough at two draws per arm — `r16,…,r16` and `r0,…,r0` over
# one binary read 53.6 and 14.1 ms/rep, a 4x disagreement — so the mirror
# rotation runs as a SECOND batch and the two are pooled.
#
# THE HOISTING FALSIFIER IS THE SLOPE, NOT THE SINK. A pure function of an
# immutable object may be hoisted out of the amplification loop, and `sink = r*h`
# passes either way. That is why arm A amplifies the MAP GET rather than
# `node.hashCode()` and arm C is a recursive (never inlined) call — and why a
# FLAT p(r) between the two `r` here means that arm was elided.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round903
mkdir -p "$OUT"
date > "$OUT/started"

CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
TEST_CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test
# Round 853: a gate reading a class DIRECTORY needs a positive control that the
# code under test is in it. Round 852: a cached classpath outlives the thing it
# describes.
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
  grep -aE '"summary"|WARM.30|amplified r=|sink mod r' "$OUT/$tag.txt" | tee -a "$OUT/started"
}

# 1. the census — counters and subtree walks only, no timestamp pair anywhere.
run census typenodekey

# 2. the amplifier, two `r`, ABBA inside each process, mirrored across the two.
run ampA tnkamp8,tnkamp24,tnkamp24,tnkamp8
run ampB tnkamp24,tnkamp8,tnkamp8,tnkamp24

date >> "$OUT/started"
touch "$OUT/done"
