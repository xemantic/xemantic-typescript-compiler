#!/usr/bin/env bash
# (WARM.12) round 865 — the ablation, ONE MISTAKE AT A TIME (round 807).
#
# A combined ablation cannot attribute: six faults injected together fail six
# pins and read as full coverage, while alone one of them may leave every pin
# green because a LATER guard makes the same decision. So each arm injects one
# fault, builds, runs the pin battery, records the failing set, and reverts.
#
# Round 855/856: the arm list is an ARRAY default, never `"${@:-M1 M2}"` (which
# expands as ONE word, dispatches nothing, and still prints a clean sweep), and
# every arm's edit is checked with `git diff --shortstat` before the build — a
# no-op edit is a dead arm, and an all-green dead sweep is indistinguishable
# from "every hook is redundant".
#
# Round 789/851: the tree must be COMMITTED before this runs, because the revert
# is `git checkout --` and it destroys any uncommitted change in the same file.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FLOW=xemantic-typescript-compiler-core/src/commonMain/kotlin/Flow.kt
CHECKER=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
SPINE=xemantic-typescript-compiler-core/src/commonMain/kotlin/SpineDispatch.kt
OUT=build/bench/r865-ablate
mkdir -p "$OUT"

PINS=(
  '*FlowNodeCensusTest*' '*FlowIndexEquivalenceTest*' '*FlowScanEquivalenceTest*'
  '*NarrowableRootsPreTestTest*' '*Inv2FlowLookupTest*' '*ClosureIndexEquivalenceTest*'
  '*CliModeRestoreTest*' '*FlowAssignmentNarrowingTest*'
)

if [[ -n "$(git status --porcelain)" ]]; then
  echo "REFUSED: the tree is dirty — commit before ablating (round 789)" >&2; exit 1
fi

apply() {
  case "$1" in
    M1) # a mint site loses its registration — the denominator silently shrinks
      python3 - "$FLOW" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="    private fun newUnreachable(): FlowUnreachable = noteMint(FlowUnreachable(nextId++))"
new="    private fun newUnreachable(): FlowUnreachable = FlowUnreachable(nextId++)"
assert s.count(old)==1
open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
      ;;
    M2) # the inventory is opened AFTER the first mint — one file's nodes land in another's
      python3 - "$FLOW" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="""        FlowCensus.beginFile(sourceFile.fileName, sourceFile.fileName.endsWith(".d.ts"))
        currentFlow = newStart(sourceFile)"""
new="""        currentFlow = newStart(sourceFile)
        FlowCensus.beginFile(sourceFile.fileName, sourceFile.fileName.endsWith(".d.ts"))"""
assert s.count(old)==1
open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
      ;;
    M3) # the main narrowing walk stops reporting what it looked at
      python3 - "$CHECKER" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="            if (FlowCensus.on) FlowCensus.touch(flowNode, FlowCensus.CH_NARROW)"
new="            if (false) FlowCensus.touch(flowNode, FlowCensus.CH_NARROW)"
assert s.count(old)==1
open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
      ;;
    M4) # the walk-volume axis is container-blind — every visit charged to file level
      python3 - "$FLOW" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="        FlowCensus.visit(functionLikeStack.lastOrNull()?.pos ?: -1)"
new="        FlowCensus.visit(-1)"
assert s.count(old)==1
open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
      ;;
    M5) # the probe gate is inert — the census records with the flag off
      python3 - "$SPINE" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
for old,new in [
 ("""    fun visit(containerPos: Int) {
        if (!on) return""","""    fun visit(containerPos: Int) {
        if (false) return"""),
 ("""    fun mint(node: FlowNode, containerPos: Int) {
        if (!on) return""","""    fun mint(node: FlowNode, containerPos: Int) {
        if (false) return"""),
 ("""    fun touch(node: FlowNode, ch: Int) {
        if (!on) return""","""    fun touch(node: FlowNode, ch: Int) {
        if (false) return"""),
]:
    assert s.count(old)==1, old[:40]
    s=s.replace(old,new)
open(p,'w',encoding='utf-8').write(s)
PY
      ;;
    *) echo "unknown arm $1" >&2; return 1 ;;
  esac
}

ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(M1 M2 M3 M4 M5)

for ARM in "${ARMS[@]}"; do
  echo "=== $ARM ==="
  apply "$ARM" || { echo "$ARM: DISPATCH FAILED"; continue; }
  STAT=$(git diff --shortstat)
  if [[ -z "$STAT" ]]; then
    echo "$ARM: REFUSED — the edit is a no-op, this arm tests nothing"
    git checkout -- "$FLOW" "$CHECKER" "$SPINE"
    continue
  fi
  echo "$ARM edit: $STAT"
  rm -rf build/test-results/jvmTest ./*/build/test-results/jvmTest
  ARGS=()
  for P in "${PINS[@]}"; do ARGS+=(--tests "$P"); done
  ./gradlew :xemantic-typescript-compiler-core:jvmTest "${ARGS[@]}" \
      > "$OUT/$ARM.log" 2>&1
  if ! grep -qa 'BUILD SUCCESSFUL\|BUILD FAILED' "$OUT/$ARM.log"; then
    echo "$ARM: BUILD DID NOT COMPLETE — see $OUT/$ARM.log"
  fi
  python3 - "$ARM" "$OUT" <<'PY'
import sys, glob, xml.etree.ElementTree as ET
arm, out = sys.argv[1], sys.argv[2]
n=f=0; fails=[]
for p in glob.glob('*/build/test-results/jvmTest/*.xml'):
    r=ET.parse(p).getroot(); n+=int(r.get('tests')); f+=int(r.get('failures'))
    for tc in r.iter('testcase'):
        for _ in tc.iter('failure'):
            fails.append(f"{tc.get('classname')}.{tc.get('name')}")
print(f"{arm}: {n} pins ran, {f} RED")
for x in sorted(fails): print("   RED", x)
open(f"{out}/{arm}.red","w").write("\n".join(sorted(fails))+"\n")
PY
  git checkout -- "$FLOW" "$CHECKER" "$SPINE"
done
echo "complete; tree restored: $(git status --porcelain | wc -l) modified files"
