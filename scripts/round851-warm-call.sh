#!/usr/bin/env bash
# (WARM.5) round 851 — the WARM intra-function attribution of
# `checkSingleCallExpressionTypes`, i.e. the ~60% of `ccetSpineLeave` that
# round 850's `arg` probe does not reach: callee resolution, overload
# selection, and the round-793 prologue.
#
# Same shape as `scripts/round849-warm-sections.sh` (whose build -> cost-gate ->
# JIT-gate -> daemon-stop -> measure path is validated), with one probe instead
# of three. Two independent processes, two ON draws and two COARSE draws each,
# so the boundary is priced by an ON-vs-COARSE DIFFERENTIAL inside one warm
# process (round 734: never an empty-span loop) and the probe's own code is warm
# by the second draw (round 846).
#
# The daemon stop lives INSIDE the script, between the build and the first
# sample: round 800's trap is that a batch which builds and then immediately
# measures reads every row ~270x too large, and no internal consistency check
# can see it.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round851
mkdir -p "$OUT"
date > "$OUT/started"

./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/build.log" 2>&1
if ! grep -q 'BUILD SUCCESSFUL' "$OUT/build.log"; then
  echo "BUILD FAILED" >> "$OUT/started"; date > "$OUT/done"; exit 1
fi

# The COST.1 and (JIT.1)(f) gates, BEFORE the daemons are stopped and long
# before any timing sample — `cost_gate.py` runs a compile of its own and must
# never overlap a measurement.
python3 scripts/cost_gate.py > "$OUT/cost-gate.log" 2>&1
echo "cost_gate exit=$?" >> "$OUT/started"
python3 scripts/huge_methods.py --fail-over 0 > "$OUT/huge-methods.log" 2>&1
echo "huge_methods exit=$?" >> "$OUT/started"

./gradlew --stop >> "$OUT/build.log" 2>&1
pkill -f 'KotlinCompile[D]aemon'
sleep 10
free -m > "$OUT/free.txt"

PROJ=$(ls -d build/bench/tsc-project-* | head -1)
CPW=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test:$(tr '\n' ':' < build/bench/cp-warm.txt)
echo "PROJ=$PROJ" >> "$OUT/started"

for i in 1 2; do
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
       "$PROJ" 3 8 call,callcoarse,call,callcoarse > "$OUT/warm-call$i.log" 2>&1
done

date > "$OUT/done"
