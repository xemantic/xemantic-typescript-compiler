#!/usr/bin/env bash
# (WARM.27) round 900 — the ablation. One deliberate mistake at a time (round
# 807: a combined ablation credits pins with discrimination they do not have),
# each dry-run for a real diff first (rounds 855/856), on a COMMITTED tree
# (rounds 789/851: the revert destroys uncommitted work in the ablated file).
#
#   A1  first-occurrence index      — `lastIndexOf` records the FIRST occurrence.
#                                     Only a duplicate straddling the cut sees it.
#   A2  off-by-one at the cut       — `>= lo` becomes `> lo`.
#   A3  the index is not shared     — every SuffixNameSet builds its own.
#   A4  the eager probe argument    — `addClosureCensus` back on `.size`, i.e.
#                                     round 900's defect restored verbatim.
#   A5  contains re-materialises    — the index is built AND the set is too.
#
# Usage: scripts/round900-ablate.sh [A1 A2 ...]   (default: all)
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler

# round 855/856: an array default, because "${@:-A1 A2}" expands as ONE word and
# a no-argument run then dispatches nothing while printing a clean sweep.
ARMS=("$@"); [[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3 A4 A5)

FLOW=xemantic-typescript-compiler-core/src/commonMain/kotlin/Flow.kt
SPINE=xemantic-typescript-compiler-core/src/commonMain/kotlin/SpineDispatch.kt
OUT=build/bench/round900-ablate
mkdir -p "$OUT"

[[ -z "$(git status --porcelain)" ]] || { echo "REFUSED: tree not clean"; exit 1; }

apply() {
  case "$1" in
    A1) python3 - "$FLOW" <<'EOF'
import io,sys
p=sys.argv[1]; s=io.open(p,encoding='utf-8').read()
o="            for (k in names.indices) m[names[k]] = k\n"
n="            for (k in names.indices.reversed()) m[names[k]] = k\n"
assert s.count(o)==1; io.open(p,'w',encoding='utf-8').write(s.replace(o,n))
EOF
    ;;
    A2) python3 - "$FLOW" <<'EOF'
import io,sys
p=sys.argv[1]; s=io.open(p,encoding='utf-8').read()
o="    override fun contains(element: String): Boolean = index.lastIndexOf(element) >= lo\n"
n="    override fun contains(element: String): Boolean = index.lastIndexOf(element) > lo\n"
assert s.count(o)==1; io.open(p,'w',encoding='utf-8').write(s.replace(o,n))
EOF
    ;;
    A3) python3 - "$FLOW" <<'EOF'
import io,sys
p=sys.argv[1]; s=io.open(p,encoding='utf-8').read()
o="                SuffixNameSet(scan.index, lo)"
n="                SuffixNameSet(scan.names, lo)"
assert s.count(o)==1; io.open(p,'w',encoding='utf-8').write(s.replace(o,n))
EOF
    ;;
    A4) python3 - "$FLOW" "$SPINE" <<'EOF'
import io,sys
p=sys.argv[1]; s=io.open(p,encoding='utf-8').read()
o="                FrontEnd.addClosureCensus(reassigned)"
n="                FrontEnd.addClosureCensus(reassignedEagerSize(reassigned))"
assert s.count(o)==1
s=s.replace(o,n)
# the eager-argument shape, restored as a helper so the type still matches
s=s.replace("internal class SuffixNameIndex(val names: Array<String>) {",
            "internal fun reassignedEagerSize(s: Set<String>): Set<String> { s.size; return s }\n\ninternal class SuffixNameIndex(val names: Array<String>) {",1)
io.open(p,'w',encoding='utf-8').write(s)
EOF
    ;;
    A5) python3 - "$FLOW" <<'EOF'
import io,sys
p=sys.argv[1]; s=io.open(p,encoding='utf-8').read()
o="    override fun contains(element: String): Boolean = index.lastIndexOf(element) >= lo\n"
n="    override fun contains(element: String): Boolean = materialize().contains(element)\n"
assert s.count(o)==1; io.open(p,'w',encoding='utf-8').write(s.replace(o,n))
EOF
    ;;
    *) echo "unknown arm $1"; return 1 ;;
  esac
}

for arm in "${ARMS[@]}"; do
  echo "=== $arm ==="
  apply "$arm" || { git checkout -- "$FLOW" "$SPINE"; continue; }
  # rounds 855/856: confirm the edit is a REAL diff before trusting its result.
  ST="$(git diff --shortstat)"
  echo "  diff: $ST"
  [[ -n "$ST" ]] || { echo "  REFUSED: arm made no diff"; git checkout -- "$FLOW" "$SPINE"; continue; }
  ./gradlew :xemantic-typescript-compiler-core:jvmTest \
      --tests '*SuffixNameIndex*' --tests '*FlowScanEquivalence*' \
      > "$OUT/$arm.log" 2>&1
  # round 808: a build that never ran is indistinguishable from "nothing changed".
  if ! grep -qa "BUILD SUCCESSFUL\|tests completed" "$OUT/$arm.log"; then
    if grep -qa "^e: " "$OUT/$arm.log"; then echo "  COMPILE ERROR (arm invalid)"
    else echo "  BUILD DID NOT RUN — result is not evidence"; fi
  fi
  python3 - "$OUT/$arm.log" <<'EOF'
import re,sys,glob,xml.etree.ElementTree as ET
failed=[]
for f in glob.glob("*/build/test-results/jvmTest/*.xml"):
    for tc in ET.parse(f).getroot().iter("testcase"):
        if tc.find("failure") is not None or tc.find("error") is not None:
            failed.append(tc.get("classname","").split(".")[-1]+"."+tc.get("name",""))
print("  failing pins: %d" % len(failed))
for n in sorted(failed): print("    -", n)
EOF
  git checkout -- "$FLOW" "$SPINE"
done
echo "complete; tree restored"
git status --porcelain
