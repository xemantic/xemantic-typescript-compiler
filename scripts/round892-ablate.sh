#!/usr/bin/env bash
# (WARM.18b) round 892 — SINGLE-MISTAKE ablation of the cta LOCAL family.
#
# Round 807's law: a COMBINED ablation cannot attribute. One arm at a time, each
# reverted before the next, on a COMMITTED tree (round 789 — the revert is
# `git checkout --`, which also destroys every uncommitted edit in the file, and
# round 851's corollary: commit before EVERY batch, not once per round).
#
# Two source files are ablated. The MECHANISM arms (A1-A8) edit ScopeStack.kt and
# are seen by ScopeStackTest; the WIRING arms (A9-A12) edit Checker.kt — which
# frame opens which scope, and where the pop is — and can only be seen through a
# COMPILE, i.e. by CtaLocalScopePinTest. Both classes run for every arm, because
# which one discriminates is exactly what is being measured (round 809: which
# seam pins discriminate cannot be predicted from reading the code).
#
# Printed because their absence has produced a false green in this repo:
#   * `git diff --shortstat` per arm — rounds 855/856;
#   * the build verdict and the test COUNT — round 808.
#
# Usage: round892-ablate.sh [A1 A2 ...]    (default: every arm)
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
STACK=xemantic-typescript-compiler-core/src/commonMain/kotlin/ScopeStack.kt
CHECKER=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
OUT=build/bench/r892-ablate
mkdir -p "$OUT"

# Round 855/856: an ARRAY default. `"${@:-A1 A2}"` expands the default as ONE
# word, so a no-argument run hits the `unknown arm` branch and still reports
# success — an all-green sweep from a driver that ran no arm at all.
ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3 A4 A5 A6 A7 A8 A9 A10 A11 A12)

[[ -z "$(git status --porcelain -- "$STACK" "$CHECKER")" ]] || {
  echo "REFUSED: sources are dirty — ablate on a committed tree" >&2; exit 1; }

for ARM in "${ARMS[@]}"; do
  git checkout -- "$STACK" "$CHECKER"
  python3 scripts/round892_ablate_apply.py "$ARM" || { echo "$ARM: APPLY FAILED"; continue; }
  SHORT="$(git diff --shortstat -- "$STACK" "$CHECKER")"
  if [[ -z "$SHORT" ]]; then
    echo "$ARM: NO DIFF — the edit did not apply, arm not run (rounds 855/856)"
    continue
  fi
  rm -rf ./*/build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-core:jvmTest \
      --tests '*ScopeStackTest*' --tests '*CtaLocalScopePinTest*' \
      > "$OUT/$ARM.log" 2>&1
  if ! grep -qa 'BUILD SUCCESSFUL\|BUILD FAILED' "$OUT/$ARM.log"; then
    echo "$ARM: BUILD DID NOT RUN — arm invalid"; git checkout -- "$STACK" "$CHECKER"; continue
  fi
  if grep -qa '^e: ' "$OUT/$ARM.log"; then
    echo "$ARM: COMPILE ERROR — arm invalid, re-cut it as a COMPILING mistake (round 808)"
    git checkout -- "$STACK" "$CHECKER"; continue
  fi
  python3 - "$ARM" "$SHORT" <<'EOF'
import sys, glob, xml.etree.ElementTree as ET
arm, short = sys.argv[1], sys.argv[2]
ran = failed = 0
names = []
for p in glob.glob('*/build/test-results/jvmTest/*.xml'):
    r = ET.parse(p).getroot()
    if 'ScopeStackTest' not in r.get('name', '') and 'CtaLocalScopePinTest' not in r.get('name', ''):
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
  git checkout -- "$STACK" "$CHECKER"
done
git checkout -- "$STACK" "$CHECKER"
echo "ablation complete; tree restored: $(git status --porcelain -- "$STACK" "$CHECKER" | wc -l) dirty files"
