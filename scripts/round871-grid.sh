#!/usr/bin/env bash
# (WARM.19) round 871 — the 8 dashboard profiles, each compiled TWICE THROUGH
# ONE `--serve` DAEMON, and the two answers diffed.
#
# WHY NOT THE USUAL TWO-CLASS-DIR GRID. The usual grid answers "did the binary's
# output change". For this round that question is trivially "no": a one-shot CLI
# performs exactly ONE `ProjectCompiler.build`, so the cross-request parse cache
# can never register a hit in it — every profile's own census says `0 hit / N
# miss`. A grid of two CLI binaries therefore cannot exercise the thing that
# landed, and a green one would be evidence of nothing.
#
# What CAN go wrong is a SERVED tree being wrong, and that needs a second
# request in the same process. So this grid is: request 1 (every file parsed,
# nothing cached) against request 2 (every file served from the cache), on all
# eight profiles, diffed in both directions. Request 1 is, by construction, the
# pre-change behaviour.
#
# Round 811's refusal (a capture containing "... and N more error(s)") and round
# 804's (an empty capture) are both enforced. Round 857: the daemon runs from
# the STAGED lib dir, so `./gradlew assemble` is a prerequisite.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="${1:-build/bench/r871-grid}"
mkdir -p "$OUT"

LIB="$ROOT/xemantic-typescript-compiler-daemon/build/install/lib"
python3 - "$LIB/xemantic-typescript-compiler-jvm-0.1.0-SNAPSHOT.jar" <<'EOF' || exit 1
import sys, zipfile
names = set(zipfile.ZipFile(sys.argv[1]).namelist())
want = "com/xemantic/typescript/compiler/CrawlParseCache.class"
if want not in names:
    sys.stderr.write("REFUSED: the staged jar has no %s — it predates (WARM.19)\n" % want)
    sys.exit(1)
EOF

declare -A DIRS=(
  [compiler]="$ROOT/build/bench/tsc-project-637d5746"
  [tsc-cli]="$ROOT/build/bench/tsc-tsc-637d5746"
  [jsTyping]="$ROOT/build/bench/tsc-jsTyping-637d5746"
  [deprecatedCompat]="$ROOT/build/bench/tsc-deprecatedCompat-637d5746"
  [typingsInstallerCore]="$ROOT/build/bench/tsc-typingsInstallerCore-637d5746"
  [services]="$ROOT/build/bench/tsc-services-637d5746"
  [server]="$ROOT/build/bench/tsc-server-637d5746"
  [harness]="$ROOT/build/bench/tsc-harness-637d5746"
)
PROFILES="compiler tsc-cli jsTyping deprecatedCompat typingsInstallerCore services server harness"

SOCK="/tmp/xtsc-r871g-$$.sock"
SRVLOG="$OUT/server.log"
DAEMON_PID=""
cleanup() { [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" 2>/dev/null; rm -f "$SOCK"; }
trap cleanup EXIT

export XTSC_AOT=off
"$ROOT/scripts/xtsc" --serve --socket "$SOCK" > "$SRVLOG" 2>&1 &
DAEMON_PID=$!
for _ in $(seq 1 200); do grep -q "listening on" "$SRVLOG" 2>/dev/null && break; sleep 0.25; done
grep -q "listening on" "$SRVLOG" || { echo "REFUSED: daemon never bound" >&2; exit 1; }

FAIL=0
for P in $PROFILES; do
  D="${DIRS[$P]}"
  [ -d "$D" ] || { echo "$P: MISSING profile dir — skipped"; continue; }
  for PASS in 1 2; do
    "$ROOT/scripts/xtsc" --daemon --socket "$SOCK" --noEmit --listAll --frontEnd "$D" \
        > "$OUT/$P.$PASS.raw" 2>&1
    grep -a 'error TS' "$OUT/$P.$PASS.raw" | sed "s#${D}/##g" | sort > "$OUT/$P.$PASS.txt"
    if grep -qa 'more error(s)' "$OUT/$P.$PASS.raw"; then
      echo "$P pass $PASS: REFUSED — truncated capture"; FAIL=1; continue
    fi
  done
  n1=$(wc -l < "$OUT/$P.1.txt"); n2=$(wc -l < "$OUT/$P.2.txt")
  add=$(comm -13 "$OUT/$P.1.txt" "$OUT/$P.2.txt" | wc -l)
  rem=$(comm -23 "$OUT/$P.1.txt" "$OUT/$P.2.txt" | wc -l)
  c1=$(grep -a 'crawl parse cache:' "$OUT/$P.1.raw" | tail -1 | sed 's/crawl parse cache: //')
  c2=$(grep -a 'crawl parse cache:' "$OUT/$P.2.raw" | tail -1 | sed 's/crawl parse cache: //')
  cr1=$(grep -a '"import-graph crawl (WALL)"' "$OUT/$P.1.raw" | tail -1 | awk -F, '{printf "%.0f", $3/1000000}')
  cr2=$(grep -a '"import-graph crawl (WALL)"' "$OUT/$P.2.raw" | tail -1 | awk -F, '{printf "%.0f", $3/1000000}')
  printf '%-22s errs %5s/%-5s added=%s removed=%s   crawl %5s -> %-5s ms\n' \
      "$P" "$n1" "$n2" "$add" "$rem" "${cr1:-NA}" "${cr2:-NA}"
  printf '%-22s   pass1 %s\n%-22s   pass2 %s\n' "" "$c1" "" "$c2"
  [ "$add" = "0" ] && [ "$rem" = "0" ] || FAIL=1
  [ "$n1" != "0" ] || { echo "$P: REFUSED — empty capture"; FAIL=1; }
done
echo
[ "$FAIL" = "0" ] && echo "ALL EIGHT: added=0 removed=0 between an all-MISS and an all-HIT request" \
                  || echo "FAILURES PRESENT"
exit "$FAIL"
