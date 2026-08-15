#!/usr/bin/env bash
# (WARM.34) round 907 — the ablation for the ascent census: ONE mistake at a time
# (round 807 — a combined ablation cannot attribute), each arm dry-run for a real
# diff (round 855) AND named with what shows the mistake was REACHED (round 902 —
# `git diff --shortstat` proves the edit LANDED, never that it DOES anything, and
# in a driver's output a dead arm and a blind pin are the same line).
#
# Round 856: an array default, never "${@:-A B C}", which expands as ONE word and
# hits the `unknown arm` branch while still printing "complete; tree restored".
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler

CHK=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
MC=xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt
OUT=build/bench/round907-ablate
mkdir -p "$OUT"

[[ -z "$(git status --porcelain)" ]] || { echo "ABORT — tree not clean"; exit 1; }

ARMS=("$@"); [[ ${#ARMS[@]} -eq 0 ]] && ARMS=(C1 C2 C3 C4 C5 C6)

inject() {
  case "$1" in
    # the recursion calls the PUBLIC entry, so every chain step opens an ascent
    C1) python3 - <<'PY'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s=open(p,encoding='utf-8').read()
old='            return parent?.hasFrom(name) == true'
assert s.count(old)==1
s=s.replace(old,'            return parent?.has(name) == true')
open(p,'w',encoding='utf-8').write(s)
PY
       ;;
    # the ascent is never closed on the next entry
    C2) python3 - <<'PY'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt'
s=open(p,encoding='utf-8').read()
old='''    fun lexAscentTop(family: Int, scopeId: Int, name: String) {
        closeAscent()'''
assert s.count(old)==1
s=s.replace(old,'''    fun lexAscentTop(family: Int, scopeId: Int, name: String) {''')
open(p,'w',encoding='utf-8').write(s)
PY
       ;;
    # the repeat key drops the SCOPE
    C3) python3 - <<'PY'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt'
s=open(p,encoding='utf-8').read()
old='        val key = scopeId.toLong() * AS_FAMILIES + family'
assert s.count(old)==1
s=s.replace(old,'        val key = 0L * AS_FAMILIES + family')
open(p,'w',encoding='utf-8').write(s)
PY
       ;;
    # the (level,name) pair census drops the LEVEL
    C4) python3 - <<'PY'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt'
s=open(p,encoding='utf-8').read()
old='        if (lexPairSeen.getOrPut(l) { HashSet() }.add(name)) lexPairDistinct++ else lexPairRepeat++'
assert s.count(old)==1
s=s.replace(old,'        if (lexAscentAllNames.add(name)) lexPairDistinct++ else lexPairRepeat++')
old2='    private val lexPairSeen = HashMap<LexicalScope, MutableSet<String>>()'
assert s.count(old2)==1
s=s.replace(old2, old2 + '\n\n    private val lexAscentAllNames = HashSet<String>()')
open(p,'w',encoding='utf-8').write(s)
PY
       ;;
    # the presence hit recorded from the flags VERDICT instead of the level's map
    C5) python3 - <<'PY'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s=open(p,encoding='utf-8').read()
old='''        if (MapCensus.on) MapCensus.lexAscentLevelHit()
        return sym.flags.hasAny(UNRESOLVED_TYPE_ELIGIBLE_FLAGS)'''
assert s.count(old)==1
s=s.replace(old,'''        val v = sym.flags.hasAny(UNRESOLVED_TYPE_ELIGIBLE_FLAGS)
        if (v && MapCensus.on) MapCensus.lexAscentLevelHit()
        return v''')
open(p,'w',encoding='utf-8').write(s)
PY
       ;;
    # a hook hoisted OUT of its MapCensus.on guard (INV.0)
    C6) python3 - <<'PY'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s=open(p,encoding='utf-8').read()
old='''        private fun hasFrom(name: String): Boolean {
            if (MapCensus.on) MapCensus.lexAscentStep()'''
assert s.count(old)==1
s=s.replace(old,'''        private fun hasFrom(name: String): Boolean {
            MapCensus.lexAscentStep()''')
open(p,'w',encoding='utf-8').write(s)
PY
       ;;
    *) echo "unknown arm $1"; return 1 ;;
  esac
}

for arm in "${ARMS[@]}"; do
  echo "=== $arm ===" | tee -a "$OUT/log"
  inject "$arm" || { echo "$arm: INJECTION FAILED"; git checkout -- "$CHK" "$MC"; continue; }
  # round 855: prove the edit landed and reverts clean, before believing anything
  git diff --shortstat | tee -a "$OUT/log"
  rm -rf build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*LexAscentCensusTest*' \
      > "$OUT/$arm.log" 2>&1
  command grep -a "BUILD SUCCESSFUL" "$OUT/$arm.log" > /dev/null \
    && echo "$arm: BUILD SUCCESSFUL — no pin reddened" | tee -a "$OUT/log"
  python3 - "$OUT/$arm.log" "$arm" <<'PY' | tee -a "$OUT/log"
import xml.etree.ElementTree as ET, glob, sys
red=[]
for f in glob.glob('*/build/test-results/jvmTest/*LexAscent*.xml'):
    r=ET.parse(f).getroot()
    for tc in r.iter('testcase'):
        if list(tc.iter('failure')): red.append(tc.get('name').replace('[jvm]',''))
# round 808: a Kotlin-daemon death looks exactly like a clean ablation
ok = 'BUILD' in open(sys.argv[1],errors='ignore').read()
print(f"{sys.argv[2]}: red={len(red)} {sorted(red)}  (build reached: {ok})")
PY
  git checkout -- "$CHK" "$MC"
done

echo "complete; tree restored: $(git status --porcelain | wc -l) modified" | tee -a "$OUT/log"
touch "$OUT/done"
