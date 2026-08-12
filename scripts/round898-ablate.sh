#!/usr/bin/env bash
# (WARM.25) round 898 — the copy-census ablation. One mistake per arm.
#
# Protocol, and each line is a CLAUDE.md entry paid for by a lost round:
#  * one mistake at a time (round 807 — a combined ablation cannot attribute);
#  * a DRY RUN first, asserting each arm makes a real diff and reverts clean
#    (rounds 855/856 — a driver that dispatched no arm printed a clean sweep);
#  * an ARRAY default, never `"${@:-A1 A2}"`, which expands as ONE word;
#  * on a COMMITTED tree, because the revert is `git checkout --` and that
#    destroys every uncommitted change in the ablated file (rounds 789/851);
#  * `BUILD SUCCESSFUL` grepped before a zero is recorded (round 808 — a
#    daemon OOM reports 0 pins run, indistinguishable from "changed nothing").
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round898/ablate
mkdir -p "$OUT"

ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3 A4 A5 A6 A7 A8)

CHECKER=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
SPINE=xemantic-typescript-compiler-core/src/commonMain/kotlin/SpineDispatch.kt
BENCH=xemantic-typescript-compiler-core/src/commonTest/kotlin/BenchMain.kt

if [[ -n "$(git status --porcelain -- "$CHECKER" "$SPINE" "$BENCH")" ]]; then
  echo "REFUSED: the ablated files are dirty — commit first (rounds 789/851)"; exit 1
fi

if [[ "${1:-}" == "--dry" ]]; then
  for a in A1 A2 A3 A4 A5 A6 A7 A8; do
    python3 scripts/round898_ablate_apply.py "$a" >/dev/null || { echo "$a APPLY FAILED"; continue; }
    echo "$a  $(git diff --shortstat -- "$CHECKER" "$SPINE" "$BENCH" | tr -d '\n')"
    git checkout -- "$CHECKER" "$SPINE" "$BENCH"
  done
  exit 0
fi

for arm in "${ARMS[@]}"; do
  python3 scripts/round898_ablate_apply.py "$arm" || { echo "$arm: APPLY FAILED"; continue; }
  rm -rf */build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*CopyCensusTest*' \
      > "$OUT/$arm.log" 2>&1
  git checkout -- "$CHECKER" "$SPINE" "$BENCH"
  if ! grep -qa 'BUILD SUCCESSFUL\|tests completed' "$OUT/$arm.log"; then
    if ! grep -qa 'FAILED' "$OUT/$arm.log"; then
      echo "$arm: BUILD DID NOT COMPLETE — zero is not a result"; continue
    fi
  fi
  python3 - "$arm" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
arm = sys.argv[1]
red, ran = [], 0
for p in glob.glob('*/build/test-results/jvmTest/TEST-*CopyCensus*.xml'):
    r = ET.parse(p).getroot()
    for tc in r.iter('testcase'):
        ran += 1
        if tc.find('failure') is not None or tc.find('error') is not None:
            red.append(tc.get('name'))
print(f"{arm}: ran {ran}, red {len(red)}: " + "; ".join(sorted(red)))
PY
done
echo "ablation complete; tree restored"
git status --porcelain -- "$CHECKER" "$SPINE" "$BENCH"
