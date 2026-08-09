#!/usr/bin/env bash
# (WARM.22) round 875 — the single-mistake ablation for ReachCensusTest.
#
# Round 807: a COMBINED ablation cannot attribute — six mistakes injected
# together failed six pins and read as full coverage, and one of them turned out
# to be a redundant guard. So ONE arm per invocation, each reverted before the
# next, on a COMMITTED tree (round 789: `git checkout --` also destroys every
# uncommitted change in the file, and a probe lives in the file it measures).
#
# Rounds 855/856: a driver must PROVE IT DISPATCHED. `ARMS=("$@")` with an array
# default, every arm dry-run for a real `git diff --shortstat` and a clean
# revert before its build, and an arm that produces no diff is a FAILURE, not a
# silent pass.
#
# The instrument under test is a CENSUS, so its mistakes are the ones a
# counter-only change actually has: a counter never incremented, a counter on
# the wrong path, an id table that renumbers one classifier onto another, an
# amplifier whose loop does not run r times, and a census that perturbs the
# compile it measures.
#
# Usage: round875-ablate.sh [A1 A2 ...]
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round875/ablate
mkdir -p "$OUT"

CHECKER=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
SPINE=xemantic-typescript-compiler-core/src/commonMain/kotlin/SpineDispatch.kt

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "REFUSED: tree is dirty — commit first (round 789)" >&2
  exit 1
fi

apply() {
  case "$1" in
    # A1 — the `calls` counter never fires for one classifier. The non-empty and
    # the reproducibility pins are what can see it.
    A1) python3 - <<'EOF'
import re
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s=open(p,errors='replace').read()
old='        if (ReachCensus.on) ReachCensus.calls[ReachCensus.CE]++\n'
assert s.count(old)==1
open(p,'w').write(s.replace(old,'',1))
EOF
       ;;
    # A2 — the fold counter goes back on the WRONG PATH for the ascend-with-edge
    # shape: `chain.size` instead of one per edge evaluation. This is the defect
    # the pins actually caught during the round, re-injected.
    A2) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s=open(p,errors='replace').read()
old='            if (ReachCensus.on) ReachCensus.folds[ReachCensus.IANY]++\n'
assert s.count(old)==1
s=s.replace(old,'',1)
old2='        if (ReachCensus.on) ReachCensus.misses[ReachCensus.IANY]++\n'
assert s.count(old2)==1
s=s.replace(old2,'        if (ReachCensus.on) { ReachCensus.misses[ReachCensus.IANY]++; ReachCensus.folds[ReachCensus.IANY] += chain.size.toLong() }\n',1)
open(p,'w').write(s)
EOF
       ;;
    # A3 — the id table is renumbered without the name table, so one
    # classifier's consultations are charged to another. Nothing in the compile
    # changes; only the index-alignment pin can see it.
    A3) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/SpineDispatch.kt'
s=open(p,errors='replace').read()
old='    const val CE = 9\n'
assert s.count(old)==1
open(p,'w').write(s.replace(old,'    const val CE = 10\n',1))
EOF
       ;;
    # A4 — the amplifier's loop runs ONCE however large r is, i.e. the slope it
    # reports is a boundary. Only the arithmetic identity says so.
    A4) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s=open(p,errors='replace').read()
old='''        val r = ReachCensus.amp
        var s = 0L
        var q = 0
        val t0 = PassTiming.nowNanos()
        while (q < r) {
            if (spineCeEdge(parent, child)) s++
            q++
        }'''
assert s.count(old)==1
new='''        val r = ReachCensus.amp
        var s = 0L
        var q = 0
        val t0 = PassTiming.nowNanos()
        while (q < 1) {
            if (spineCeEdge(parent, child)) s++
            q++
        }'''
open(p,'w').write(s.replace(old,new,1))
EOF
       ;;
    # A5 — the census PERTURBS the compile it measures: with it armed, one
    # classifier answers UNREACHED. Only the equivalence pins can see it, and it
    # is the mistake this whole family's hazard is made of (a reach classifier
    # decides whether a diagnostic is considered at all).
    A5) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s=open(p,errors='replace').read()
old='        if (ReachCensus.on) ReachCensus.calls[ReachCensus.CE]++\n'
assert s.count(old)==1
new=old+'        if (ReachCensus.on) return CE_NONE\n'
open(p,'w').write(s.replace(old,new,1))
EOF
       ;;
    *) echo "unknown arm: $1" >&2; return 9 ;;
  esac
}

ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3 A4 A5)

for arm in "${ARMS[@]}"; do
  echo "== $arm dry-run $(date)" | tee -a "$OUT/log"
  apply "$arm" || { echo "$arm: apply FAILED" | tee -a "$OUT/log"; git checkout -- "$CHECKER" "$SPINE"; continue; }
  DIFF=$(git diff --shortstat)
  if [[ -z "$DIFF" ]]; then
    echo "$arm: NO DIFF — the arm is dead (rounds 855/856)" | tee -a "$OUT/log"
    git checkout -- "$CHECKER" "$SPINE"
    continue
  fi
  echo "$arm: diff $DIFF" | tee -a "$OUT/log"
  ./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/$arm-build.log" 2>&1
  if ! grep -q 'BUILD SUCCESSFUL' "$OUT/$arm-build.log"; then
    echo "$arm: BUILD FAILED (round 808: a daemon OOM looks exactly like a clean ablation)" | tee -a "$OUT/log"
    git checkout -- "$CHECKER" "$SPINE"
    continue
  fi
  rm -rf xemantic-typescript-compiler-core/build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*ReachCensusTest*' \
      > "$OUT/$arm-test.log" 2>&1
  python3 - "$arm" "$OUT" <<'EOF' | tee -a "$OUT/log"
import glob, sys, xml.etree.ElementTree as ET
arm, out = sys.argv[1], sys.argv[2]
red = []
for f in glob.glob('*/build/test-results/jvmTest/*ReachCensus*.xml'):
    for tc in ET.parse(f).getroot().iter('testcase'):
        if any(True for _ in tc.iter('failure')):
            red.append(tc.get('name').split('[')[0])
print(f"{arm}: {len(red)} pins reddened")
for r in sorted(red):
    print(f"   - {r}")
open(f"{out}/{arm}.red", "w").write("\n".join(sorted(red)))
EOF
  git checkout -- "$CHECKER" "$SPINE"
  echo "$arm: reverted, tree clean=$(git diff --quiet && echo yes || echo NO)" | tee -a "$OUT/log"
done

./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/restore-build.log" 2>&1
echo "ablation complete; tree restored $(date)" | tee -a "$OUT/log"
