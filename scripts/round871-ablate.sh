#!/usr/bin/env bash
# ROUND 871 — SINGLE-MISTAKE ablation of CrawlParseCacheTest's pins.
#
# One arm per invocation, each reverted before the next (round 807: a combined
# ablation cannot attribute, and six mistakes injected together read as full
# coverage while one of them was in fact covered by a later guard).
#
# THE DRIVER TRAP THIS OBEYS (rounds 855/856): `"${@:-A1 A2}"` expands its
# default as ONE word, so a no-argument run hits `unknown arm` for every arm and
# still prints a clean summary. The default is an ARRAY here, and every arm is
# dry-run for a real diff that reverts clean before anything is built.
#
# AND ROUND 789/851: the tree must be COMMITTED before this runs, because the
# revert is `git checkout --`, which also destroys any uncommitted edit in the
# ablated file.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="${OUT:-/tmp/r871-ablate}"
mkdir -p "$OUT"

SRC=xemantic-typescript-compiler-core/src/commonMain/kotlin/CrawlParseCache.kt
DRV=xemantic-typescript-compiler-core/src/commonMain/kotlin/ProjectCompiler.kt

if [ -n "$(git status --porcelain -- "$SRC" "$DRV")" ]; then
  echo "error: $SRC / $DRV are dirty — commit first (round 789)" >&2; exit 1
fi

apply() {
  case "$1" in
    # The hit condition drops the CONTENT compare: an edited file is served its
    # previous tree. THE failure this whole cache is guarded against.
    A1) python3 - "$SRC" <<'EOF'
import sys
p=sys.argv[1]; s=open(p).read()
old="        if (e.content != source) return null\n"
assert s.count(old)==1
open(p,'w').write(s.replace(old,""))
EOF
        ;;
    # The hit condition drops the FLAGS compare (INV.1(e)'s other half).
    A2) python3 - "$SRC" <<'EOF'
import sys
p=sys.argv[1]; s=open(p).read()
old="        if (e.flags != flags) return null\n"
assert s.count(old)==1
open(p,'w').write(s.replace(old,""))
EOF
        ;;
    # The content compare becomes a LENGTH compare — the mtime/size family of
    # mistakes, which passes every edit that changes the file's size.
    A3) python3 - "$SRC" <<'EOF'
import sys
p=sys.argv[1]; s=open(p).read()
old="        if (e.content != source) return null\n"
new="        if (e.content.length != source.length) return null\n"
assert s.count(old)==1
open(p,'w').write(s.replace(old,new))
EOF
        ;;
    # The PATH stops being part of the key.
    A4) python3 - "$SRC" <<'EOF'
import sys
p=sys.argv[1]; s=open(p).read()
old="        val e = entries[fileName] ?: return null\n"
new="        val e = entries.values.firstOrNull() ?: return null\n"
assert s.count(old)==1
open(p,'w').write(s.replace(old,new))
EOF
        ;;
    # The OFF arm stops being off on the READ side.
    A5) python3 - "$SRC" <<'EOF'
import sys
p=sys.argv[1]; s=open(p).read()
old="        if (!enabled) return null\n        val e = entries[fileName]"
new="        val e = entries[fileName]"
assert s.count(old)==1
open(p,'w').write(s.replace(old,new))
EOF
        ;;
    # …and on the WRITE side.
    A6) python3 - "$SRC" <<'EOF'
import sys
p=sys.argv[1]; s=open(p).read()
old="        if (!enabled) return\n        entries[fileName] = parsed\n"
new="        entries[fileName] = parsed\n"
assert s.count(old)==1
open(p,'w').write(s.replace(old,new))
EOF
        ;;
    # The DRIVER never stores, so nothing is ever reusable across builds.
    A7) python3 - "$DRV" <<'EOF'
import sys
p=sys.argv[1]; s=open(p).read()
old="            if (pp != null) CrawlParseCache.store(f.path, pp)\n"
assert s.count(old)==1
open(p,'w').write(s.replace(old,""))
EOF
        ;;
    *) echo "unknown arm: $1" >&2; return 2 ;;
  esac
}

revert() { git checkout -- "$SRC" "$DRV"; }

# Dry run FIRST: every named arm must make a real diff and revert clean.
dryrun() {
  local a rc=0
  for a in "$@"; do
    apply "$a" || { echo "$a: apply FAILED"; rc=1; revert; continue; }
    local n; n=$(git diff --shortstat -- "$SRC" "$DRV")
    revert
    if [ -z "$n" ]; then echo "$a: NO DIFF — dead arm"; rc=1; else echo "$a: diff$n; reverts clean"; fi
    [ -n "$(git status --porcelain -- "$SRC" "$DRV")" ] && { echo "$a: DID NOT REVERT"; rc=1; }
  done
  return $rc
}

ARMS=("$@")
if [ "${#ARMS[@]}" -eq 0 ]; then ARMS=(A1 A2 A3 A4 A5 A6 A7); fi

if [ "${ARMS[0]}" = "--dry" ]; then
  dryrun A1 A2 A3 A4 A5 A6 A7
  exit $?
fi

for arm in "${ARMS[@]}"; do
  echo "=== $arm ==="
  apply "$arm"
  rm -rf xemantic-typescript-compiler-core/build/test-results/jvmTest
  set +e
  timeout 1200 ./gradlew :xemantic-typescript-compiler-core:jvmTest \
      --tests '*CrawlParseCacheTest*' > "$OUT/$arm.log" 2>&1
  set -e
  if ! grep -qa "BUILD SUCCESSFUL\|tests completed" "$OUT/$arm.log"; then
    echo "  BUILD DID NOT RUN — see $OUT/$arm.log (round 808: a daemon OOM looks like a clean ablation)"
  fi
  python3 - "$arm" "$OUT" <<'EOF'
import sys, glob, xml.etree.ElementTree as ET
arm, out = sys.argv[1], sys.argv[2]
red = []
total = 0
for fn in glob.glob('*/build/test-results/jvmTest/*.xml'):
    for tc in ET.parse(fn).getroot().iter('testcase'):
        total += 1
        if any(c.tag in ('failure', 'error') for c in tc):
            red.append(tc.get('name'))
print("  ran %d pins, RED %d" % (total, len(red)))
for r in sorted(red):
    print("    - " + r)
open("%s/%s.red" % (out, arm), "w").write("\n".join(sorted(red)))
EOF
  revert
done
