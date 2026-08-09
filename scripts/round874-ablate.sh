#!/usr/bin/env bash
# (WARM.21) round 874 — the single-mistake ablation of the TAV candidate gate.
#
# ONE arm per invocation (round 807: a combined ablation cannot attribute), on a
# COMMITTED tree (round 789: the revert is `git checkout --`, which also destroys
# every uncommitted change in the file), and the arm is DRY-RUN first so a diff
# of zero lines is caught before a build is spent on it (rounds 855/856).
#
# Round 808: a Kotlin-daemon `GC overhead limit exceeded` looks exactly like a
# clean ablation — the build fails, the tests never run, and the parser reports
# `ran 0, failed 0`. So the build log is grepped for BUILD SUCCESSFUL and a
# missing one is an ERROR, never a zero.
#
#   usage: scripts/round874-ablate.sh [A1 A2 ...]     (default: all)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT=build/bench/r874-ablate
mkdir -p "$OUT"

# Round 855/856: an array default. `"${@:-A1 A2}"` expands the default as ONE
# word, so a no-argument run hits the `unknown arm` branch and still prints
# "complete" — an all-green sweep from a driver that ran no arm.
ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3 A4 A5 A6 A7 A8)

if [[ -n "$(git status --porcelain)" ]]; then
  echo "REFUSED: tree is dirty — commit first (round 789)" >&2; exit 1
fi

for ARM in "${ARMS[@]}"; do
  echo "=== $ARM ==="
  python3 scripts/round874_ablate_apply.py "$ARM" || { echo "  APPLY FAILED"; continue; }
  STAT="$(git diff --shortstat)"
  echo "  diff: $STAT"
  if [[ -z "$STAT" ]]; then
    echo "  REFUSED: the arm changed nothing"; git checkout -- .; continue
  fi
  rm -rf xemantic-typescript-compiler-core/build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-core:jvmTest \
      --tests '*TavCandidateGateTest*' > "$OUT/$ARM.log" 2>&1
  if ! grep -qa 'BUILD SUCCESSFUL\|tests completed' "$OUT/$ARM.log"; then
    echo "  ERROR: the build itself failed — see $OUT/$ARM.log"
    git checkout -- .; continue
  fi
  python3 - "$ARM" "$OUT" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
arm, out = sys.argv[1], sys.argv[2]
red, total = [], 0
for fn in glob.glob("xemantic-typescript-compiler-core/build/test-results/jvmTest/*TavCandidate*.xml"):
    r = ET.parse(fn).getroot()
    total += int(r.get("tests", 0))
    for tc in r.iter("testcase"):
        if tc.findall("failure") or tc.findall("error"):
            red.append(tc.get("name"))
print(f"  ran {total}, RED {len(red)}")
for n in sorted(red):
    print(f"    - {n}")
open(f"{out}/{arm}.red", "w").write("\n".join(sorted(red)))
PY
  git checkout -- .
done

echo "ablation complete; tree restored: $(git status --porcelain | wc -l) modified files"
