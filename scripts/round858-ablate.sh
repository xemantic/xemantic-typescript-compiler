#!/usr/bin/env bash
# ROUND 858 — one-mistake-at-a-time ablation of `scripts/lib/dep-classpath.sh`.
#
# A guard that cannot fail is not a guard, and the only instrument that settles
# it is a SINGLE deliberate mistake per run (round 807: six injected together
# read as full coverage and one of them was in fact undiscriminated).
#
# ROUND 789's TRAP AND WHY THIS IS SAFE: the revert here is `git checkout` of the
# very file the round's work lives in, which also destroys any UNCOMMITTED change
# to it. The harness is therefore COMMITTED BEFORE this script is ever run, so
# the revert is scoped to the injected fault and nothing else.
#
# The pins are not a Gradle task input (the .sh is invisible to the test task's
# up-to-date check — the `build/pass-lab.txt` trap), so every run passes
# `--rerun` or it would report the PREVIOUS run's verdict.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler

TARGET=scripts/lib/dep-classpath.sh
OUT=build/bench/round858-ablate
mkdir -p "$OUT"
XML=xemantic-typescript-compiler-core/build/test-results/jvmTest

[ -z "$(git status --porcelain "$TARGET")" ] || {
  echo "REFUSING: $TARGET has uncommitted changes — commit the harness first (round 789)." >&2
  exit 1
}

report() {  # report <tag>
  python3 - "$XML" "$1" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
xml, tag = sys.argv[1], sys.argv[2]
ran = failed = 0
names = []
for p in glob.glob(xml + "/*DepClasspathGuardTest*.xml"):
    r = ET.parse(p).getroot()
    for tc in r.iter("testcase"):
        ran += 1
        if tc.find("failure") is not None or tc.find("error") is not None:
            failed += 1
            names.append(tc.get("name"))
print("%-28s ran=%d failed=%d" % (tag, ran, failed))
for n in sorted(names):
    print("    FAILED: " + n)
if ran == 0:
    print("    !! ZERO PINS RAN — the build did not get that far; this is NOT a green.")
PY
}

run_pins() {  # run_pins <tag>
  rm -rf "$XML"
  ./gradlew :xemantic-typescript-compiler-core:jvmTest \
      --tests '*DepClasspathGuardTest*' --rerun > "$OUT/$1.log" 2>&1
  # A daemon death or a compile failure looks exactly like a clean ablation
  # (round 808): zero pins run, and the XML parser reports nothing.
  grep -q 'BUILD SUCCESSFUL\|BUILD FAILED' "$OUT/$1.log" \
    || echo "    !! neither BUILD SUCCESSFUL nor BUILD FAILED in $OUT/$1.log"
  report "$1"
}

ablate() {  # ablate <tag> <python-patch>
  python3 -c "$2" || { echo "patch $1 did not apply" >&2; git checkout "$TARGET"; exit 1; }
  git --no-pager diff --stat "$TARGET" | tail -1
  run_pins "$1"
  git checkout "$TARGET"
}

echo "=== BASELINE (committed, unablated) ==="
run_pins baseline

echo
echo "=== ABLATION A — drop libs.versions.toml from the watched inputs ==="
echo "    (the ROUND-858 bug: this is what ab-warm.sh's old guard was blind to)"
ablate ablationA '
import pathlib
p = pathlib.Path("scripts/lib/dep-classpath.sh"); t = p.read_text()
old = "        \"$root/gradle/libs.versions.toml\" \\\\\n"
assert old in t
p.write_text(t.replace(old, ""))'

echo
echo "=== ABLATION B — drop the entry-existence loop ==="
ablate ablationB '
import re, pathlib
p = pathlib.Path("scripts/lib/dep-classpath.sh"); t = p.read_text()
start = t.index("    # (3) EVERY NAMED JAR STILL EXISTS")
end = t.index("    return 0\n}", start)
p.write_text(t[:start] + t[end:])'

echo
echo "=== ABLATION C — drop the non-empty test ==="
ablate ablationC '
import pathlib
p = pathlib.Path("scripts/lib/dep-classpath.sh"); t = p.read_text()
start = t.index("    if [ ! -s \"$cache\" ]; then")
end = t.index("    fi\n", start) + len("    fi\n")
p.write_text(t[:start] + t[end:])'

echo
echo "=== restored ==="
git status --porcelain "$TARGET" || true
