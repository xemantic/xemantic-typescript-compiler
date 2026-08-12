#!/usr/bin/env bash
# (WARM.23) round 896 — do PerFileScopeMemoTest's pins discriminate?
#
# ONE mistake at a time (round 807). The tree is COMMITTED first (round 789/851).
# Each arm is dry-run checked for a real diff before its build, because an arm
# that silently edited nothing reads exactly like "the guard is redundant"
# (rounds 855/856).
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
CHECKER=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
OUT=build/bench/round896-ablate2a
mkdir -p "$OUT"

ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(B1 B2 B3)

apply() {
  case "$1" in
    # B1: the memo ignores its key — serves whatever it last saw.
    B1) python3 - <<'PY'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s=open(p,encoding='utf-8').read()
a='        if (fileName === perFileScopeMemoKey) {'
b='        if (perFileScopeMemoKey != null) {'
assert s.count(a)==1
open(p,'w',encoding='utf-8').write(s.replace(a,b))
PY
    ;;
    # B2: the memo becomes the ORACLE — a miss answers null instead of probing
    # the map. This is what keying the map by identity would do.
    B2) python3 - <<'PY'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s=open(p,encoding='utf-8').read()
a='        val scope = perFileScopeProbe(fileName)\n        perFileScopeMemoKey = fileName'
b='        val scope = if (perFileScopeMemoKey == null) perFileScopeProbe(fileName) else null\n        perFileScopeMemoKey = fileName'
assert s.count(a)==1
open(p,'w',encoding='utf-8').write(s.replace(a,b))
PY
    ;;
    # B3: globalsForFile falls through to the merged globals when the per-file
    # lookup answers null — the INV.3(d) leak the restructuring must not add.
    B3) python3 - <<'PY'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s=open(p,encoding='utf-8').read()
a='                return lookupInFileScope(scope, name)'
b='                lookupInFileScope(scope, name)?.let { return it }'
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
  changed=$(git diff --shortstat -- "$CHECKER")
  if [[ -z "$changed" ]]; then
    echo "$arm: REFUSED — the edit produced no diff (a dead arm)"
    git checkout -- "$CHECKER"; continue
  fi
  echo "$arm diff: $changed"
  ./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*PerFileScopeMemoTest*' \
    --tests '*Inv3PerFileLookup*' > "$OUT/$arm.log" 2>&1
  if grep -qa "BUILD SUCCESSFUL" "$OUT/$arm.log"; then
    echo "$arm: ALL GREEN — the pins do NOT discriminate this mistake"
  else
    grep -a "tests completed" "$OUT/$arm.log" | tail -1
    python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
names=[]
for p in glob.glob('*/build/test-results/jvmTest/*.xml'):
    r=ET.parse(p).getroot()
    for tc in r.iter('testcase'):
        if any(f.tag in ('failure','error') for f in tc):
            names.append(tc.get('classname').split('.')[-1]+" :: "+tc.get('name'))
for n in sorted(names): print("   RED:", n)
PY
  fi
  git checkout -- "$CHECKER"
done
echo "ablation complete; tree restored"
git status --porcelain -- "$CHECKER"
