#!/usr/bin/env bash
# (WARM.11) round 864 — the WARM attribution of `FlowGraphBuilder.build`.
#
# Usage: scripts/round864-warm-flow.sh <out-subdir> [tiers] [processes]
#
# Round-851 order throughout: every gradle-invoking step BEFORE the daemon stop,
# then the samples. Round 853's positive control on the class dirs, sharpened per
# that round's own rule — the control names a member this round ADDS, so a stale
# class directory cannot satisfy it. Two processes x two draws per tier, because
# round 846 measured that the FIRST instrumented rebuild in a process is
# systematically the slowest draw.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT="build/bench/${1:-round864}"
TIERS="${2:-frontend,frontend}"
PROCS="${3:-2}"
mkdir -p "$OUT"
date > "$OUT/started"

./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/build.log" 2>&1
if ! grep -q 'BUILD SUCCESSFUL' "$OUT/build.log"; then
  echo "BUILD FAILED" >> "$OUT/started"; date > "$OUT/done"; exit 1
fi

python3 scripts/cost_gate.py > "$OUT/cost-gate.log" 2>&1
echo "cost_gate exit=$?" >> "$OUT/started"
python3 scripts/huge_methods.py --fail-over 0 > "$OUT/huge-methods.log" 2>&1
echo "huge_methods exit=$?" >> "$OUT/started"

DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED" >> "$OUT/started"; exit 1; }

./gradlew --stop >> "$OUT/build.log" 2>&1
pkill -f 'KotlinCompile[D]aemon'
sleep 10
free -m > "$OUT/free.txt"

MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
TEST=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test
[[ -f "$MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || {
  echo "REFUSED: main class dir has no MainKt" >> "$OUT/started"; exit 1; }
[[ -f "$TEST/com/xemantic/typescript/compiler/bench/BenchMainKt.class" ]] || {
  echo "REFUSED: test class dir has no BenchMainKt" >> "$OUT/started"; exit 1; }
# The round-853 control, in its sharp form: this member did not exist before this
# round, so a leftover class dir cannot answer it.
javap -p -cp "$MAIN" com.xemantic.typescript.compiler.FrontEnd 2>/dev/null \
  | grep -q 'addFlowIndexCensus' || {
  echo "REFUSED: main class dir predates (WARM.11)" >> "$OUT/started"; exit 1; }
echo "main classes: $(find "$MAIN" -name '*.class' | wc -l)" >> "$OUT/started"

CPW="$MAIN:$TEST:$DEPS"
PROJ=$(ls -d build/bench/tsc-project-* | head -1)
echo "PROJ=$PROJ TIERS=$TIERS PROCS=$PROCS" >> "$OUT/started"

for ((i = 1; i <= PROCS; i++)); do
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
       "$PROJ" 3 8 "$TIERS" > "$OUT/warm$i.log" 2>&1
done

date > "$OUT/done"
