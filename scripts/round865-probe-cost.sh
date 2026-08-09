#!/usr/bin/env bash
# (WARM.12) round 865 — what the CENSUS ITSELF costs the row it measures.
#
# The instrument adds a static read plus a not-taken branch to `bindStatement`
# and `bindExpression` (490,565 calls on the compiler profile) and to the
# narrowing walk's arrival point (991,970). That is the standard probe-gated
# shape in this file, but it is being added to the very row the round is
# quoting, so "behaviour-free" is measured here rather than asserted.
#
# Two class dirs — the committed binary and the one before it — in a ROTATED
# interleave (before, after, after, before), two instrumented draws per process,
# because round 846 measured that the first instrumented rebuild in a process is
# systematically the slowest. Round-851 order: the daemon stop is here, after
# every gradle-invoking step and before the first sample.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
BEFORE="${1:?before class dir}"
AFTER="${2:?after class dir}"
OUT="${3:-build/bench/r865-probe-cost}"
mkdir -p "$OUT"
date > "$OUT/started"

DEPS="$(scripts/lib/dep-classpath.sh --print)" || exit 1
TEST=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test
[[ -f "$TEST/com/xemantic/typescript/compiler/bench/BenchMainKt.class" ]] || {
  echo "REFUSED: test class dir has no BenchMainKt" >&2; exit 1; }
javap -p -cp "$AFTER" com.xemantic.typescript.compiler.FlowCensus >/dev/null 2>&1 || {
  echo "REFUSED: the AFTER dir predates (WARM.12)" >&2; exit 1; }
if javap -p -cp "$BEFORE" com.xemantic.typescript.compiler.FlowCensus >/dev/null 2>&1; then
  echo "REFUSED: the BEFORE dir HAS FlowCensus — one build twice" >&2; exit 1
fi

./gradlew --stop >> "$OUT/started" 2>&1
pkill -f 'KotlinCompile[D]aemon'
sleep 10
free -m > "$OUT/free.txt"

PROJ=$(ls -d build/bench/tsc-project-* | head -1)
i=0
for ARM in before after after before; do
  i=$((i + 1))
  DIR="$BEFORE"; [[ $ARM == after ]] && DIR="$AFTER"
  java -Xmx4g -cp "$DIR:$TEST:$DEPS" com.xemantic.typescript.compiler.bench.BenchMainKt \
       "$PROJ" 3 8 frontend,frontend > "$OUT/$i-$ARM.log" 2>&1
  echo "run $i ($ARM) done" >> "$OUT/started"
done
date > "$OUT/done"
