#!/usr/bin/env bash
# (WARM.13b) round 888 — single-mistake ablation of the spine skip mask.
#
# Round 807: a COMBINED ablation cannot attribute — six mistakes injected
# together failed six pins and read as full coverage, and one of them turned out
# to be a redundant guard when re-run alone. So ONE arm per invocation, each
# dry-run for a real diff and reverted before the next.
#
# Round 855: the default arm list is an ARRAY, never `"${@:-A1 A2 A3}"` — that
# expands as ONE word, hits the `unknown arm` branch, and still prints
# "complete", which is indistinguishable from an all-green sweep.
#
# Round 789/851: the tree must be COMMITTED before this runs, because each arm
# ends in `git checkout --` on a file that would also carry uncommitted work.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round888-ablate
mkdir -p "$OUT"
DISP=xemantic-typescript-compiler-core/src/commonMain/kotlin/SpineDispatch.kt

if [[ -n "$(git status --porcelain -- "$DISP")" ]]; then
  echo "REFUSED: $DISP is dirty — commit before ablating (round 789)"; exit 1
fi

ARMS=("$@"); [[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3)

apply() {
  python3 - "$1" <<'PY'
import re, sys
arm = sys.argv[1]
p = "xemantic-typescript-compiler-core/src/commonMain/kotlin/SpineDispatch.kt"
s = open(p).read()
def entry(tag, fn):
    global s
    i = s.index(tag)
    j = s.index("/*", i + 4)
    seg = s[i:j]
    new = fn(seg)
    assert new != seg, f"arm made no change to {tag}"
    s = s[:i] + new + s[j:]
if arm == "A1":       # a CLOSED handler wrongly loses a kind it acts on
    entry("/* 42 spineCaEnterNode", lambda t: t.replace("NodeKind.BINARY_EXPRESSION,\n", "", 1))
elif arm == "A2":     # an OPEN handler wrongly given a closure
    entry("/*  0 ctaSpineEnter", lambda t: t.replace("null,", "intArrayOf(NodeKind.BLOCK),", 1))
elif arm == "A3":     # the statement anchor wrongly loses ExpressionStatement
    entry("/*  5 spineCtaM3StatementAnchr",
          lambda t: t.replace("NodeKind.EXPRESSION_STATEMENT,\n", "", 1))
else:
    sys.exit(f"unknown arm {arm}")
open(p, "w").write(s)
PY
}

for arm in "${ARMS[@]}"; do
  echo "=== $arm ==="
  apply "$arm" || { echo "$arm: APPLY FAILED"; git checkout -- "$DISP"; continue; }
  changed=$(git diff --shortstat -- "$DISP")
  if [[ -z "$changed" ]]; then
    echo "$arm: REFUSED — edit produced no diff"; git checkout -- "$DISP"; continue
  fi
  echo "$arm diff: $changed"
  ./gradlew :xemantic-typescript-compiler-core:jvmTest \
      --tests '*SpineMaskEquivalenceTest*' --tests '*CtaSectionProbeTest*' \
      --tests '*SpineDispatchProbeTest*' --tests '*SpineAmpProbeTest*' \
      > "$OUT/$arm.log" 2>&1
  if ! grep -qa 'BUILD SUCCESSFUL\|BUILD FAILED' "$OUT/$arm.log"; then
    echo "$arm: BUILD DID NOT COMPLETE — read $OUT/$arm.log"
  fi
  python3 - "$arm" "$OUT" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
arm, out = sys.argv[1], sys.argv[2]
red = []
for p in glob.glob("*/build/test-results/jvmTest/*.xml"):
    r = ET.parse(p).getroot()
    for tc in r.iter("testcase"):
        if any(c.tag in ("failure", "error") for c in tc):
            red.append(f"{r.get('name')}::{tc.get('name')}")
print(f"{arm}: {len(red)} red")
for t in sorted(red):
    print("   ", t)
open(f"{out}/{arm}.red", "w").write("\n".join(sorted(red)))
PY
  git checkout -- "$DISP"
  echo "$arm reverted; tree clean: $(git status --porcelain -- "$DISP" | wc -l) change(s)"
done
echo "ablation complete"
