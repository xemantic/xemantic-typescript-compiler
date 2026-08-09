#!/usr/bin/env bash
# round-863 scratch: ONE deliberate mistake at a time (round 807 — a combined
# ablation credits pins with discrimination they do not have), against the pin
# classes (WARM.10) adds.
#
# Round 789/851: the harness and the fix are committed BEFORE this runs, because
# the revert that undoes an injected fault (`git checkout --`) also destroys
# every uncommitted change in the file the fault is in.
#
# Round 855/856: the arm list is an ARRAY, so a no-argument run cannot expand
# its default as one word, hit `unknown arm` and still report success.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT=build/bench/round863/ablate
mkdir -p "$OUT"

SRC=xemantic-typescript-compiler-core/src/commonMain/kotlin
SCAN="$SRC/JsxRuntimePragmaScan.kt"
TR="$SRC/Transformer.kt"
BENCH=xemantic-typescript-compiler-core/src/commonTest/kotlin/BenchMain.kt

ARMS=("$@"); [[ ${#ARMS[@]} -eq 0 ]] && ARMS=(M1 M2 M3 M4 M5)

apply() {
  case "$1" in
    # M1 — the scanner drops the non-overlap cursor, so a candidate whose
    # opening slash-star reuses the previous match's closing slash is accepted.
    # `findAll` never sees such a candidate.
    M1) python3 - "$SCAN" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="        if (start < lastEnd) continue                     // findAll resumes at the previous end\n"
new="        if (false) continue\n"
assert old in s, "M1 anchor missing"
open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
        ;;
    # M2 — the scanner uses Kotlin's WIDER whitespace notion instead of the
    # regex `\s` class, so it accepts NBSP-separated text the pattern rejects.
    M2) python3 - "$SCAN" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="    c == ' ' || c == '\\t' || c == '\\n' || c == '\\u000B' || c == '\\u000C' || c == '\\r'\n"
new="    c.isWhitespace()\n"
assert old in s, "M2 anchor missing"
open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
        ;;
    # M3 — the forward `\s+` after the tag becomes `\s*`, so `@jsxRuntimeclassic`
    # is accepted as a pragma.
    M3) python3 - "$SCAN" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="        if (j == from) continue                           // `\\s+` needs at least one\n"
new="        if (false) continue\n"
assert old in s, "M3 anchor missing"
open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
        ;;
    # M4 — the transformer takes the FIRST pragma instead of the LAST. Silently
    # WRONG rather than slow: it changes the emitted runtime of a file that
    # carries two pragmas, and no diagnostic anywhere says so.
    M4) python3 - "$TR" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="            for (keyword in pragmas) auto = keyword == \"automatic\"\n"
new="            if (pragmas.isNotEmpty()) auto = pragmas.first() == \"automatic\"\n"
assert old in s, "M4 anchor missing"
open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
        ;;
    # M5 — the harness's 5th argument silently defaults instead of failing, so a
    # typo measures the OTHER mode with nothing in the output to say so.
    M5) python3 - "$BENCH" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="    else -> error(\"usage: 5th argument must be `emit`, `noEmit`, or omitted — not '$flag'\")\n"
new="    else -> false\n"
assert old in s, "M5 anchor missing"
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
    git checkout -- "$SCAN" "$TR" "$BENCH"; continue
  fi
  rm -rf */build/test-results/jvmTest
  ./gradlew jvmTest --tests '*JsxRuntimePragmaScanTest*' --tests '*BenchEmitModeTest*' \
      --tests '*BenchFrontEndTierTest*' --tests '*BenchTierReportTest*' \
      -p xemantic-typescript-compiler-core > "$OUT/$ARM-test.log" 2>&1
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
  git checkout -- "$SCAN" "$TR" "$BENCH"
done
echo "=== tree restored:"; git status --porcelain
