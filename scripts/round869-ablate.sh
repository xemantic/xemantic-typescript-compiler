#!/usr/bin/env bash
# (WARM.16) round 869 — SINGLE-MISTAKE ablation of `AnnScopeStackTest`.
#
# Round 807's law: a COMBINED ablation cannot attribute. Six mistakes injected
# together failed six pins and read as full coverage; re-run alone, one of them
# left every pin green. So one arm at a time, each reverted before the next, on
# a COMMITTED tree (round 789 — the revert is `git checkout --`, which also
# destroys every uncommitted edit in the file, and round 851's corollary: commit
# before EVERY batch, not once per round).
#
# Two things this driver prints because their absence has produced a false green
# in this repo:
#   * `git diff --shortstat` per arm — rounds 855/856: an arm whose edit did not
#     apply is indistinguishable from a guard that is redundant;
#   * the build verdict and the test COUNT — round 808: a failed build reports
#     `ran 0, failed 0`, which reads exactly like "the mistake changed nothing".
#
# Usage: round869-ablate.sh [A1 A2 ...]    (default: every arm)
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
SRC=xemantic-typescript-compiler-core/src/commonMain/kotlin/AnnScopeStack.kt
OUT=build/bench/r869-ablate
mkdir -p "$OUT"

# Round 855/856: an ARRAY default. `"${@:-A1 A2}"` expands the default as ONE
# word, so a no-argument run hits the `unknown arm` branch and still reports
# success — an all-green sweep from a driver that ran no arm at all.
ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3 A4 A5 A6 A7)

[[ -z "$(git status --porcelain -- "$SRC")" ]] || {
  echo "REFUSED: $SRC is dirty — ablate on a committed tree" >&2; exit 1; }

apply() {
  python3 scripts/round869_ablate_apply.py "$1"
}

for ARM in "${ARMS[@]}"; do
  git checkout -- "$SRC"
  apply "$ARM" || { echo "$ARM: APPLY FAILED"; continue; }
  SHORT="$(git diff --shortstat -- "$SRC")"
  if [[ -z "$SHORT" ]]; then
    echo "$ARM: NO DIFF — the edit did not apply, arm not run (rounds 855/856)"
    continue
  fi
  rm -rf ./*/build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*AnnScopeStackTest*' \
      > "$OUT/$ARM.log" 2>&1
  RC=$?
  if ! grep -qa 'BUILD SUCCESSFUL\|BUILD FAILED' "$OUT/$ARM.log"; then
    echo "$ARM: BUILD DID NOT RUN — arm invalid"; git checkout -- "$SRC"; continue
  fi
  if grep -qa '^e: ' "$OUT/$ARM.log"; then
    echo "$ARM: COMPILE ERROR — arm invalid, re-cut it as a COMPILING mistake (round 808)"
    git checkout -- "$SRC"; continue
  fi
  python3 - "$ARM" "$SHORT" <<'EOF'
import sys, glob, xml.etree.ElementTree as ET
arm, short = sys.argv[1], sys.argv[2]
ran = failed = 0
names = []
for p in glob.glob('*/build/test-results/jvmTest/*.xml'):
    r = ET.parse(p).getroot()
    if 'AnnScopeStack' not in r.get('name', ''):
        continue
    for tc in r.iter('testcase'):
        ran += 1
        if tc.find('failure') is not None or tc.find('error') is not None:
            failed += 1
            names.append(tc.get('name'))
print(f"{arm}: ran {ran}, failed {failed} |{short.strip()}|")
for n in names:
    print(f"    RED: {n}")
EOF
  git checkout -- "$SRC"
done
git checkout -- "$SRC"
echo "ablation complete; tree restored: $(git status --porcelain -- "$SRC" | wc -l) dirty files"
