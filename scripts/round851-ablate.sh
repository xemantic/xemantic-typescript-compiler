#!/usr/bin/env bash
# (WARM.5) round 851 — sequence the ONE-MISTAKE-AT-A-TIME ablation.
#
# Round 789: the harness is COMMITTED before this runs, because the revert here
# is `git checkout --` on the very file the probe lives in and would otherwise
# delete the round's own uncommitted work. Round 805: a script that rewrites a
# source file and restores it can be killed mid-flight, so it writes a marker
# per stage and `git status --porcelain` is the check afterwards.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round851-ablate
mkdir -p "$OUT"
date > "$OUT/started"

for F in coarse-anchor nested-on-only census-row census-on-only report-order emitted; do
  python3 scripts/round851_ablate.py "$F" apply > "$OUT/$F.apply" 2>&1 || { echo "APPLY FAILED $F"; continue; }
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
    echo "BUILD FAILED (a fault that does not compile is not an ablation)" > "$OUT/$F.failed"
  fi
  python3 scripts/round851_ablate.py "$F" revert >> "$OUT/$F.apply" 2>&1
  date > "$OUT/$F.done"
done

git status --porcelain > "$OUT/tree-after.txt"
date > "$OUT/done"
