#!/usr/bin/env bash
# (P18.152) ablation — ONE injected mistake per arm (round 807), each reverted before the next.
#
# Every arm asserts against its OWN snapshot rather than `git diff --shortstat` (round 922: on a
# tree carrying the round's work that reads the whole round's diff, identically, for every arm),
# and every substitution asserts its anchor matches EXACTLY ONCE (a substitution helper without
# one is a silently dead arm). The tree is committed first (round 789), so the revert is scoped.
set -uo pipefail
cd "$(dirname "$0")/.."
CHK=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
SNAP=build/bench/p18-152-ablate; mkdir -p "$SNAP"
TESTS="--tests *ImportHelpersDefaultImportTest*"
patch() { python3 - "$1" "$2" <<'PY'
import io,sys
p="xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
s=io.open(p,encoding="utf-8").read()
old,new=sys.argv[1],sys.argv[2]
n=s.count(old)
if n!=1:
    print(f"ANCHOR FAILED: {n} occurrence(s)"); sys.exit(1)
io.open(p,"w",encoding="utf-8").write(s.replace(old,new)); print("patched")
PY
}
run_arm() {
  local name="$1"; shift
  cp "$CHK" "$SNAP/$name.pristine"
  "$@" || { echo "$name: PATCH FAILED"; cp "$SNAP/$name.pristine" "$CHK"; return 1; }
  cmp -s "$CHK" "$SNAP/$name.pristine" && { echo "$name: DEAD ARM — file unchanged"; return 1; }
  rm -rf build/test-results/jvmTest */build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-core:jvmTest $TESTS > "$SNAP/$name.log" 2>&1
  local red
  red=$(python3 -c "
import glob,xml.etree.ElementTree as ET
f=0
for p in glob.glob('*/build/test-results/jvmTest/*.xml'):
    r=ET.parse(p).getroot(); f+=int(r.get('failures',0))+int(r.get('errors',0))
print(f)")
  grep -qa 'BUILD SUCCESSFUL\|tests completed' "$SNAP/$name.log" || { echo "$name: BUILD DID NOT RUN TESTS"; }
  echo "$name: RED=$red"
  grep -aoE '^[A-Za-z0-9]+Test\[jvm\] > [^[]+' "$SNAP/$name.log" | sed 's/^/    /' | head -20
  cp "$SNAP/$name.pristine" "$CHK"
}

case "${1:-all}" in
a1|all)
  # a1 — the DEFAULT-CLAUSE arm off.
  run_arm a1 patch \
    'if (clause.name != null &&' \
    'if (false && clause.name != null &&' ;;&
a2|all)
  # a2 — the ORDERING guard off: the default-clause arm fires even when the
  # named-specifier arm already reported for this statement, so a mixed clause
  # grows a second row at column 1 beside tsgo's column 16.
  run_arm a2 patch \
    'diagnostics.count { it.code == 2354 && it.fileName == fileName } == beforeThisStmt' \
    'true' ;;
esac
echo "--- tree restored ---"; git status --porcelain -- "$CHK"
