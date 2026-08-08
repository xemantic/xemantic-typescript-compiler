#!/usr/bin/env bash
# (WARM.5) round 851, stage 2 — re-verify the two seams whose pins the first
# ablation showed BLIND, then take the warm measurement on the clean binary.
#
# Order matters: every source rewrite is reverted and the tree rebuilt BEFORE
# the first timing sample, and the daemons are stopped between the last build
# and that sample (round 800: a batch that builds and then immediately measures
# reads every row ~270x too large, and no internal consistency check sees it).
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round851
mkdir -p "$OUT"
date > "$OUT/started"

# ── stage 1: the clean binary, and the two repaired pins ────────────────────
./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/build.log" 2>&1
grep -c 'BUILD SUCCESSFUL' "$OUT/build.log" >> "$OUT/started"
rm -rf xemantic-typescript-compiler-core/build/test-results/jvmTest
./gradlew :xemantic-typescript-compiler-core:jvmTest \
    --tests '*CallSectionsWarmProbeTest*' --tests '*BenchTierReportTest*' \
    > "$OUT/pins-clean.log" 2>&1
echo "pins-clean exit=$?" >> "$OUT/started"

# ── stage 2: the two re-ablations, one mistake at a time ────────────────────
for F in coarse-anchor report-order; do
  python3 scripts/round851_ablate.py "$F" apply > "$OUT/$F.apply" 2>&1 || continue
  ./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/$F.build" 2>&1
  if grep -q 'BUILD SUCCESSFUL' "$OUT/$F.build"; then
    rm -rf xemantic-typescript-compiler-core/build/test-results/jvmTest
    ./gradlew :xemantic-typescript-compiler-core:jvmTest \
        --tests '*CallSectionsWarmProbeTest*' --tests '*BenchTierReportTest*' \
        > "$OUT/$F.test" 2>&1
    python3 - "$OUT/$F.failed" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
names = []
for p in glob.glob('xemantic-typescript-compiler-core/build/test-results/jvmTest/*.xml'):
    r = ET.parse(p).getroot()
    for tc in r.iter('testcase'):
        if list(tc.iter('failure')) or list(tc.iter('error')):
            names.append(tc.get('name'))
open(sys.argv[1], 'w').write("\n".join(sorted(names)) + "\n")
PY
  else
    echo "BUILD FAILED" > "$OUT/$F.failed"
  fi
  python3 scripts/round851_ablate.py "$F" revert >> "$OUT/$F.apply" 2>&1
done

# ── stage 3: back to the clean binary, the gates, then the measurement ──────
./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/build-clean.log" 2>&1
grep -c 'BUILD SUCCESSFUL' "$OUT/build-clean.log" >> "$OUT/started"
git status --porcelain > "$OUT/tree-before-measure.txt"

python3 scripts/cost_gate.py > "$OUT/cost-gate.log" 2>&1
echo "cost_gate exit=$?" >> "$OUT/started"
python3 scripts/huge_methods.py --fail-over 0 > "$OUT/huge-methods.log" 2>&1
echo "huge_methods exit=$?" >> "$OUT/started"

./gradlew --stop >> "$OUT/build-clean.log" 2>&1
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
