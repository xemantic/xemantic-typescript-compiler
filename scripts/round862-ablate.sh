#!/usr/bin/env bash
# round-862 scratch: ONE deliberate mistake at a time (round 807 — a combined
# ablation credits pins with discrimination they do not have), against the four
# pin classes this round touches.
#
# Round 789/851: the harness is committed BEFORE this runs, because the revert
# that undoes an injected fault (`git checkout --`) also destroys every
# uncommitted change in the file the fault is in.
#
# Round 855/856: the arm list is an ARRAY, so a no-argument run cannot expand
# its default as one word, hit `unknown arm` and still report success.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT=build/bench/round862/ablate
mkdir -p "$OUT"

SRC=xemantic-typescript-compiler-core/src/commonMain/kotlin
TSC="$SRC/TypeScriptCompiler.kt"
SCAN="$SRC/DeclareRequireScan.kt"

ARMS=("$@"); [[ ${#ARMS[@]} -eq 0 ]] && ARMS=(M1 M2 M3 M4)

apply() {
  case "$1" in
    # M1 — the scanner drops `require\b`, so `requires` is accepted.
    M1) python3 - "$SCAN" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="        if (from < text.length && isDeclareRequireWordChar(text[from])) continue\n"
new="        if (false) continue\n"
assert old in s, "M1 anchor missing"
open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
        ;;
    # M2 — the check-only gate is inert (the census runs anyway).
    M2) python3 - "$TSC" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="val requireOnlyOrphans = if (options.skipEmitOutputs) emptySet() else cpcRequireOnlyOrphans("
new="val requireOnlyOrphans = if (false) emptySet() else cpcRequireOnlyOrphans("
assert old in s, "M2 anchor missing"
open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
        ;;
    # M3 — the gate is "simplified" to the `@noEmit` DIRECTIVE (round 738's
    # mistake, the one 440 corpus baselines depend on not being made).
    M3) python3 - "$TSC" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="if (options.skipEmitOutputs) emptySet() else cpcRequireOnlyOrphans("
new="if (options.noEmit || options.skipEmitOutputs) emptySet() else cpcRequireOnlyOrphans("
assert old in s, "M3 anchor missing"
open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
        ;;
    # M4 — pass 2 is skipped ALWAYS, so `staticallyReferenced` never learns
    # about a `typeof import('./x')` and a live file is dropped as an orphan.
    M4) python3 - "$TSC" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="""                    for (fileName in tsFileNames) {
                        val sf = parsedSourceFiles[fileName] ?: continue
                        val feOrphT0 = FrontEnd.t()
                        for (m in importTypeRegex.findAll(sf.text)) {"""
new="""                    for (fileName in emptyList<String>()) {
                        val sf = parsedSourceFiles[fileName] ?: continue
                        val feOrphT0 = FrontEnd.t()
                        for (m in importTypeRegex.findAll(sf.text)) {"""
assert old in s, "M4 anchor missing"
open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
        ;;
    *) echo "unknown arm $1" >&2; exit 1 ;;
  esac
}

for ARM in "${ARMS[@]}"; do
  echo "=== $ARM"
  apply "$ARM" || { echo "$ARM: apply FAILED"; continue; }
  # Round 856: prove the edit is a real diff before believing the arm.
  git diff --shortstat | sed 's/^/    diff: /'
  ./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/$ARM-build.log" 2>&1
  if ! grep -qa 'BUILD SUCCESSFUL' "$OUT/$ARM-build.log"; then
    echo "    BUILD FAILED (round 808: a daemon OOM looks exactly like a clean ablation)"
    git checkout -- "$TSC" "$SCAN"; continue
  fi
  rm -rf */build/test-results/jvmTest
  ./gradlew jvmTest --tests '*DeclareRequireScanTest*' --tests '*RequireOnlyOrphanTest*' \
      --tests '*PostCheckerPartitionTest*' --tests '*SkipEmitOutputsTest*' \
      > "$OUT/$ARM-test.log" 2>&1
  python3 - "$ARM" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
ran=0; fails=[]
for p in glob.glob('*/build/test-results/jvmTest/*.xml'):
    r=ET.parse(p).getroot()
    for tc in r.iter('testcase'):
        ran+=1
        if tc.find('failure') is not None or tc.find('error') is not None:
            fails.append(tc.get('classname').split('.')[-1]+" :: "+tc.get('name'))
print(f"    {sys.argv[1]}: {ran} pins ran, {len(fails)} RED")
for f in sorted(fails): print("      RED  "+f)
PY
  git checkout -- "$TSC" "$SCAN"
done
echo "=== tree restored:"; git status --porcelain
