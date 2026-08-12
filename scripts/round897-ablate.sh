#!/usr/bin/env bash
# (WARM.24) round 897 — ablate the pins that hold this round's REFUSAL.
#
# A refusal rests on its instrument exactly as a fix does. Six single mistakes,
# ONE AT A TIME (round 807: a combined ablation cannot attribute), each dry-run
# for a real diff before it is believed (rounds 855/856), on a COMMITTED tree so
# the revert is scoped to the fault (rounds 789/851).
#
# The arms:
#   A1  the interned arm probes the RAW container    — the two arms stop agreeing
#   A2  `canon` enters PROBES before MEMBERS         — the modelling decision that
#                                                      makes the interned world an
#                                                      interned world at all
#   A3  the fold arm starts from an EMPTY table      — it stops modelling the probe
#                                                      `scanIdentifier` already pays
#   A4  `idToken` counts keywords as names           — the intern-table size, i.e.
#                                                      the hit rate, is inflated
#   A5  `publish` is last-wins                       — a snapshot taken after the
#                                                      set is repopulated
#   A6  `publish` accepts an EMPTY member set        — round 849's blind zero
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round897ablate
mkdir -p "$OUT"
SRC=xemantic-typescript-compiler-core/src/commonMain/kotlin/NameCensus.kt
GRADLE=./gradlew

ARMS=("$@"); [[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3 A4 A5 A6)

apply() {
  case "$1" in
    A1) python3 - "$SRC" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old="                setInternHits += probeSet(setInt, probesInt, raw = false)\n                mapRawHits += probeMap(mapRaw, probes, raw = true)"
new="                setInternHits += probeSet(setRaw, probesInt, raw = false)\n                mapRawHits += probeMap(mapRaw, probes, raw = true)"
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new))
PY
      ;;
    A2) python3 - "$SRC" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old="""        for (s in members) if (!canon.containsKey(s)) canon[s] = s
        for (s in globalNames) if (!canon.containsKey(s)) canon[s] = s
        for (s in probes) if (!canon.containsKey(s)) canon[s] = s"""
new="""        for (s in probes) if (!canon.containsKey(s)) canon[s] = s
        for (s in members) if (!canon.containsKey(s)) canon[s] = s
        for (s in globalNames) if (!canon.containsKey(s)) canon[s] = s"""
assert s.count(old)==1
open(p,'w').write(s.replace(old,new))
PY
      ;;
    A3) python3 - "$SRC" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old="        val table = HashMap<String, Any>(KEYWORDS)"
new="        val table = HashMap<String, Any>()"
assert s.count(old)==1
open(p,'w').write(s.replace(old,new))
PY
      ;;
    A4) python3 - "$SRC" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old="        if (keyword) { keywordTokens++; return }\n        distinctNames.add(word)"
new="        distinctNames.add(word)\n        if (keyword) { keywordTokens++; return }"
assert s.count(old)==1
open(p,'w').write(s.replace(old,new))
PY
      ;;
    A5) python3 - "$SRC" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old="        if (memberSnapshot != null) return\n        if (members.isEmpty()) return"
new="        if (members.isEmpty()) return"
assert s.count(old)==1
open(p,'w').write(s.replace(old,new))
PY
      ;;
    A6) python3 - "$SRC" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old="        if (memberSnapshot != null) return\n        if (members.isEmpty()) return"
new="        if (memberSnapshot != null) return"
assert s.count(old)==1
open(p,'w').write(s.replace(old,new))
PY
      ;;
    *) echo "unknown arm $1"; return 1 ;;
  esac
}

for arm in "${ARMS[@]}"; do
  git checkout -- "$SRC"
  apply "$arm" || { echo "$arm: APPLY FAILED"; continue; }
  # rounds 855/856: prove the edit is a real diff before reading its result.
  lines=$(git diff --shortstat -- "$SRC")
  echo "=== $arm  diff: $lines" | tee "$OUT/$arm.txt"
  if [[ -z "$lines" ]]; then echo "$arm: NO DIFF — arm did not dispatch" | tee -a "$OUT/$arm.txt"; continue; fi
  rm -rf xemantic-typescript-compiler-core/build/test-results/jvmTest
  $GRADLE :xemantic-typescript-compiler-core:jvmTest --tests '*NameCensusTest*' \
    > "$OUT/$arm.log" 2>&1
  grep -aq 'BUILD SUCCESSFUL\|tests completed\|FAILED' "$OUT/$arm.log" || \
    echo "$arm: build never got to the tests" | tee -a "$OUT/$arm.txt"
  python3 - "$arm" "$OUT" <<'PY'
import xml.etree.ElementTree as ET, glob, sys
arm, out = sys.argv[1], sys.argv[2]
red=[]; total=0
for f in glob.glob('xemantic-typescript-compiler-core/build/test-results/jvmTest/*.xml'):
    r=ET.parse(f).getroot()
    for tc in r.iter('testcase'):
        total+=1
        if tc.find('failure') is not None or tc.find('error') is not None:
            red.append(tc.get('name'))
line = f"{arm}: {len(red)} of {total} pins RED"
print(line)
for n in red: print("   -", n)
open(f"{out}/{arm}.txt","a").write(line+"\n"+"\n".join("   - "+n for n in red)+"\n")
PY
done
git checkout -- "$SRC"
echo "complete; tree restored"
git status --porcelain -- "$SRC"
touch "$OUT/done"
