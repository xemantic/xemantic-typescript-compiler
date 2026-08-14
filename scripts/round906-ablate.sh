#!/usr/bin/env bash
# (WARM.33) round 906 — the single-mistake ablation of the memo-access census.
#
# Round 807: a COMBINED ablation cannot attribute, so one arm per invocation.
# Round 855/856: an array default, and every arm dry-run for a real diff before
# its result is read. Round 902: `git diff --shortstat` proves the edit LANDED,
# never that it DOES anything — so each arm below names the counter or
# population that shows its mistake was REACHED, and the pins assert those
# populations are non-empty (round 849).
#
# The tree must be COMMITTED: the revert is `git checkout --`, which also
# destroys uncommitted work in the same file (round 789).
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
SRC=xemantic-typescript-compiler-core/src/commonMain/kotlin/ReachMemoCensus.kt
OUT=build/bench/round906/ablate
mkdir -p "$OUT"

[[ -z "$(git status --porcelain)" ]] || { echo "ABORT — uncommitted work; the revert would destroy it"; exit 1; }

ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3)

apply() {
  case "$1" in
    # the consultation is no longer recorded against its node: the per-node
    # histogram's whole population disappears. REACHED because `probes > 0` is
    # asserted by a different pin in the same run.
    A1) python3 - <<'PY'
import sys
p="xemantic-typescript-compiler-core/src/commonMain/kotlin/ReachMemoCensus.kt"
s=open(p).read()
old="        if (id >= 0 && id < nodeCount) perNode[id]++\n"
assert old in s
open(p,"w").write(s.replace(old,"",1))
PY
      ;;
    # the two interleaved classifiers stop separating their consultation from
    # their ancestor probes. REACHED because the gap pin asserts the
    # interleaved ascent population is non-empty.
    A2) python3 - <<'PY'
p="xemantic-typescript-compiler-core/src/commonMain/kotlin/ReachMemoCensus.kt"
s=open(p).read()
old="        if (hops == 0) { p(c, id); return }\n"
assert old in s
open(p,"w").write(s.replace(old,"        p(c, id); return\n",1))
PY
      ;;
    # layout C stops being modelled at all. REACHED because the report prints
    # its rows either way, so the mistake is invisible to a row COUNT and only
    # the per-level sum can see it.
    A3) python3 - <<'PY'
p="xemantic-typescript-compiler-core/src/commonMain/kotlin/ReachMemoCensus.kt"
s=open(p).read()
old="        val cAddr = baseC + id.toLong() * 64 + c\n        for (h in simC) h.touch(cAddr)\n"
assert old in s
open(p,"w").write(s.replace(old,"",1))
PY
      ;;
    *) echo "unknown arm $1"; return 1 ;;
  esac
}

for arm in "${ARMS[@]}"; do
  echo "=== $arm ==="
  apply "$arm" || { git checkout -- "$SRC"; continue; }
  git diff --shortstat -- "$SRC" | tee "$OUT/$arm.diff"
  ./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*ReachMemoCensusTest*' \
    > "$OUT/$arm.log" 2>&1
  echo "gradle exit=$?" | tee -a "$OUT/$arm.diff"
  grep -aE "^e: |BUILD" "$OUT/$arm.log" | tail -3
  python3 - "$arm" "$OUT" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
arm, out = sys.argv[1], sys.argv[2]
red = []
for p in glob.glob("*/build/test-results/jvmTest/*.xml"):
    r = ET.parse(p).getroot()
    for tc in r.iter("testcase"):
        if tc.find("failure") is not None or tc.find("error") is not None:
            red.append(tc.get("name"))
open(f"{out}/{arm}.red", "w").write("\n".join(sorted(red)))
print(f"{arm}: {len(red)} pins reddened")
for n in sorted(red):
    print("   ", n)
PY
  git checkout -- "$SRC"
done
echo "complete; tree restored:"
git status --porcelain
