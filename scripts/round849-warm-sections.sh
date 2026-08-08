#!/usr/bin/env bash
# (WARM.3) + (WARM.4)(b) round 849 — the lib-type re-derivation PRIZE, and the
# WARM intra-handler attribution.
#
# Round 847 established that a handler's warm SHARE is not its cold share (the
# top two spine handlers swap between regimes) and that a probe boundary is
# ~1.85x more expensive cold than warm. Every intra-handler section table on
# record (`CtaSections`, `CpaSections`, `ArgSections`) was taken in a COLD
# one-shot JVM, so none can be read as a warm attribution without being re-taken.
# This script re-takes them warm, each with its COARSE twin so the boundary is
# priced DIFFERENTIALLY (round 734 — never by an empty-span loop), inside ONE
# warm process so nothing else varies.
#
# TWO PHASES, because phase 1 alone answers the queue item and phase 2 is the
# expensive part: a reader can act on `phase1.done` without waiting for the rest.
#
# The daemon stop lives INSIDE the script, between the build and the first
# sample: round 800's trap is that a batch which builds and then immediately
# measures reads every row ~270x too large, and no internal consistency check
# can see it. Nothing here runs concurrently with anything else — one xtsc run
# already occupies ~4.17 of this box's 8 cores.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round849
PHASE=${1:-all}
mkdir -p "$OUT"
date > "$OUT/started-$PHASE"

./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/build.log" 2>&1
if ! grep -q 'BUILD SUCCESSFUL' "$OUT/build.log"; then
  echo "BUILD FAILED" >> "$OUT/started-$PHASE"; date > "$OUT/done-$PHASE"; exit 1
fi

# The COST.1 and (JIT.1)(f) gates, BEFORE the daemons are stopped and long
# before any timing sample — `cost_gate.py` runs a compile of its own and must
# never overlap a measurement.
python3 scripts/cost_gate.py > "$OUT/cost-gate.log" 2>&1
echo "cost_gate exit=$?" >> "$OUT/started-$PHASE"
python3 scripts/huge_methods.py --fail-over 0 > "$OUT/huge-methods.log" 2>&1
echo "huge_methods exit=$?" >> "$OUT/started-$PHASE"

./gradlew --stop >> "$OUT/build.log" 2>&1
pkill -f 'KotlinCompile[D]aemon'
sleep 10
free -m > "$OUT/free.txt"

PROJ=$(ls -d build/bench/tsc-project-* | head -1)
# ROUND 858: this COLD arm used to read build/bench/cp.txt, a hand-frozen Jul-8
# dependency tail (kotlin-stdlib 2.4.0 / kotlinx-io 0.9.0 / serialization 1.9.0)
# while the WARM arm below read the CURRENT one - so every cold/warm ratio this
# script produced compared two different dependency tails. Now both arms resolve
# through the validating shared resolver.
. scripts/lib/dep-classpath.sh
DEPS="$(xtsc_dep_classpath build/bench/cp-warm.txt)" || exit 1
CP=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS
CPW=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test:$DEPS
echo "PROJ=$PROJ" >> "$OUT/started-$PHASE"

# ── PHASE 1 — (WARM.3). Two independent processes, two draws each: round 846
#    measured the PROBE's own code being cold on its first instrumented rebuild,
#    so only the second draw is quoted. The COLD arm is the same binary, which is
#    what makes the cold/warm ratio a property of the regime and not of a build.
if [ "$PHASE" = "all" ] || [ "$PHASE" = "1" ]; then
  for i in 1 2; do
    java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
         "$PROJ" 3 8 libtypes,libtypes > "$OUT/warm-libtypes$i.log" 2>&1
  done
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
       --noEmit --libTypeCensus "$PROJ" > "$OUT/cold-libtypes.log" 2>&1
  date > "$OUT/phase1.done"
fi

# ── PHASE 2 — (WARM.4)(b), the warm intra-handler tables.
if [ "$PHASE" = "all" ] || [ "$PHASE" = "2" ]; then
  for i in 1 2; do
    java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
         "$PROJ" 3 8 cta,ctacoarse,cta,ctacoarse > "$OUT/warm-cta$i.log" 2>&1
  done
  for i in 1 2; do
    java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
         "$PROJ" 3 8 cpa,cpacoarse,arg,argcoarse,cpa,cpacoarse,arg,argcoarse \
         > "$OUT/warm-cpaarg$i.log" 2>&1
  done
  date > "$OUT/phase2.done"
fi

date > "$OUT/done-$PHASE"
