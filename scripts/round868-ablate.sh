#!/usr/bin/env bash
# (WARM.15) round 868 ablation — one deliberate mistake at a time (round 807),
# each reverted before the next. The tree is committed (round 789), so the
# revert is `git checkout --` on exactly the ablated file.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
SRC=xemantic-typescript-compiler-core/src/commonMain/kotlin/StarExportIndex.kt
OUT=build/bench/round868/ablate
mkdir -p "$OUT"
ARMS=("$@"); [[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3 A4 A5 A6 A7)
for A in "${ARMS[@]}"; do
  git checkout -- "$SRC"
  case "$A" in
    A1) # the export gate dropped from the function index
        perl -0pi -e 's/if \(n != null && ModifierFlag\.Export in stmt\.modifiers\) \{/if (n != null) {/' "$SRC" ;;
    A2) # variables become LAST-wins instead of first-wins
        perl -0pi -e 's/if \(n !in varDecls\) varDecls\[n\] = d/varDecls[n] = d/' "$SRC" ;;
    A3) # `export * as ns from` admitted as a descendable edge (still compiles:
        # the clause is downcast instead of the statement being skipped)
        python3 - "$SRC" <<'PY2'
import sys
p=sys.argv[1]; s=open(p).read()
s=s.replace("                if (clause != null && clause !is NamedExports) continue\n","")
s=s.replace("reExports.add(ReExport(clause, target))","reExports.add(ReExport(clause as? NamedExports, target))")
open(p,'w').write(s)
PY2
        ;;
    A4) # the edge list reversed
        perl -0pi -e 's/return StarExportIndex\(fnDecls, varDecls, interfaceNames, reExports\)/return StarExportIndex(fnDecls, varDecls, interfaceNames, reExports.reversed())/' "$SRC" ;;
    A5) # the export gate dropped from the interface set
        perl -0pi -e 's/if \(ModifierFlag\.Export in stmt\.modifiers\) interfaceNames\.add\(stmt\.name\.text\)/interfaceNames.add(stmt.name.text)/' "$SRC" ;;
    A6) # only the LAST overload of a name kept (the grouping collapsed)
        perl -0pi -e 's/fnDecls\.getOrPut\(n\) \{ ArrayList\(\) \}\.add\(stmt\)/fnDecls[n] = arrayListOf(stmt)/' "$SRC" ;;
    A7) # a binding-pattern declaration admitted under its first bound name
        perl -0pi -e 's/val n = \(d\.name as\? Identifier\)\?\.text \?: continue/val n = (d.name as? Identifier)?.text ?: "p"/' "$SRC" ;;
    *) echo "unknown arm: $A" >&2; continue ;;
  esac
  D=$(git diff --shortstat -- "$SRC")
  echo "== $A diff: $D"
  if [[ -z "$D" ]]; then echo "   REFUSED: arm made no diff" ; continue; fi
  rm -rf build/test-results/jvmTest */build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*StarExportIndexTest*' > "$OUT/$A.log" 2>&1
  echo "   gradle exit=$?"
  grep -q 'BUILD SUCCESSFUL\|tests completed\|FAILED' "$OUT/$A.log" || echo "   WARNING: no test outcome in log"
  python3 - "$A" <<'PY'
import glob,sys,xml.etree.ElementTree as ET
f=[]
n=0
for p in glob.glob('*/build/test-results/jvmTest/*.xml'):
    for tc in ET.parse(p).getroot().iter('testcase'):
        n+=1
        if tc.find('failure') is not None or tc.find('error') is not None: f.append(tc.get('name'))
print(f"   {sys.argv[1]}: ran {n}, failed {len(f)}")
for x in sorted(f): print("     RED:", x)
PY
done
git checkout -- "$SRC"
echo "tree restored: $(git status --porcelain -- "$SRC" | wc -l) modified"
