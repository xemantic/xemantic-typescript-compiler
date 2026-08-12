#!/usr/bin/env bash
# (WARM.23) round 896 — do FlowMapKeyTest's pins discriminate?
#
# ONE deliberate mistake at a time (round 807: a combined ablation credits a pin
# with discrimination it does not have). The tree is COMMITTED before this runs
# (round 789/851: the revert below destroys uncommitted work in the ablated
# file), and each arm is dry-run first — a `git diff --shortstat` per arm is the
# only thing separating a clean ablation from a dead one (rounds 855/856, where
# a driver's default expanded as one word and every arm silently hit `unknown`).
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
TYPES=xemantic-typescript-compiler-core/src/commonMain/kotlin/Types.kt
OUT=build/bench/round896-ablate
mkdir -p "$OUT"

ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3)

apply() {
  case "$1" in
    # The shift removed: nodeKey(0,0) IS the LongKeyMap sentinel again.
    A1) python3 - <<'PY'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Types.kt'
s=open(p,encoding='utf-8').read()
a='fun flowKey(pos: Int, end: Int): Long = nodeKey(pos + 1, end + 1)'
b='fun flowKey(pos: Int, end: Int): Long = nodeKey(pos, end)'
assert s.count(a)==1
open(p,'w',encoding='utf-8').write(s.replace(a,b))
PY
    ;;
    # Injectivity broken: two extents at one position collapse onto one key.
    A2) python3 - <<'PY'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Types.kt'
s=open(p,encoding='utf-8').read()
a='fun flowKey(pos: Int, end: Int): Long = nodeKey(pos + 1, end + 1)'
b='fun flowKey(pos: Int, end: Int): Long = nodeKey(pos + 1, pos + 1)'
assert s.count(a)==1
open(p,'w',encoding='utf-8').write(s.replace(a,b))
PY
    ;;
    # Only ONE coordinate shifted: still injective, still sentinel-free for
    # pos >= 0, but the synthetic (-1,-1) node no longer lands on the sentinel.
    A3) python3 - <<'PY'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Types.kt'
s=open(p,encoding='utf-8').read()
a='fun flowKey(pos: Int, end: Int): Long = nodeKey(pos + 1, end + 1)'
b='fun flowKey(pos: Int, end: Int): Long = nodeKey(pos + 1, end)'
assert s.count(a)==1
open(p,'w',encoding='utf-8').write(s.replace(a,b))
PY
    ;;
    *) echo "unknown arm $1"; return 1 ;;
  esac
}

for arm in "${ARMS[@]}"; do
  echo "=== $arm ==="
  apply "$arm" || { echo "$arm: apply FAILED"; continue; }
  changed=$(git diff --shortstat -- "$TYPES")
  if [[ -z "$changed" ]]; then
    echo "$arm: REFUSED — the edit produced no diff (a dead arm)"
    git checkout -- "$TYPES"
    continue
  fi
  echo "$arm diff: $changed"
  ./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*FlowMapKeyTest*' \
    --tests '*Inv2FlowLookup*' --tests '*FlowIndexEquivalence*' \
    > "$OUT/$arm.log" 2>&1
  if grep -qa "BUILD SUCCESSFUL" "$OUT/$arm.log"; then
    echo "$arm: ALL GREEN — the pins do NOT discriminate this mistake"
  else
    grep -a "tests completed" "$OUT/$arm.log" | tail -1
    python3 - "$arm" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
names=[]
for p in glob.glob('*/build/test-results/jvmTest/*.xml'):
    r=ET.parse(p).getroot()
    for tc in r.iter('testcase'):
        if any(f.tag in ('failure','error') for f in tc):
            names.append(tc.get('classname').split('.')[-1]+" :: "+tc.get('name'))
for n in sorted(names): print("   RED:", n)
PY
  fi
  git checkout -- "$TYPES"
done
echo "ablation complete; tree restored"
git status --porcelain -- "$TYPES"
