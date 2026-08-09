#!/usr/bin/env bash
# ROUND 871 — (WARM.19): attribute a REAL `--serve` DAEMON REQUEST.
#
# WHY THIS EXISTS. Every warm measurement rounds 843-870 took profiles
# `BenchMain`, an IN-PROCESS repeated whole-project rebuild. The artifact that
# ships is the `--serve` daemon. Round 843 compared the two TOTALS (7.10-7.45 s
# vs 7.14/6.92 s) and concluded they agree — but a comparison of totals is not an
# attribution, and it was taken before rounds 860-870 removed ~15% of the
# rebuild.
#
# WHAT IT MEASURES, per request, with THREE nested brackets that are all already
# printed by the shipping binary — nothing new is instrumented here:
#
#   client wall   (this script, `date +%s%N` around `scripts/xtsc --daemon`)
#     > server elapsed  (`CompileResponse.elapsedMs`, brackets `runCli`,
#                        echoed by the server on its stderr)
#       > compiler time (the `time:` line inside the response body, brackets
#                        `ProjectCompiler.build` — the SAME call `BenchMain`
#                        brackets, so this is the in-process rebuild figure)
#
# so `client - server` is the socket + JSON + client JVM, and `server - compiler`
# is everything `runCli` does around the build (argument parse, the round-848
# mode ledger, diagnostic formatting, stdout capture, response encode).
#
# With `--front` each request additionally carries `--frontEnd`, which dumps the
# per-phase front-end census (config / crawl / read / preparse / parse / imports
# / bind / check) FROM INSIDE THAT REQUEST — i.e. the per-request counter table
# this round is about, with no new probe.
#
# TRAPS OBEYED
#  * round 857: the dev launcher's classpath is the STAGED `install/lib` dir, so
#    `./gradlew assemble` is a prerequisite; this script refuses without it and
#    checks the staged compiler jar carries a class the round is about.
#  * round 842: any build invalidates the AOT cache, so `XTSC_AOT=off`
#    throughout — this round measures the daemon, not the cache.
#  * round 800/851: run this AFTER `./gradlew --stop`, never beside a build.
#  * round 831: no `nohup … &` inside a backgrounded tool call; the daemon is
#    started here, in the foreground script, and killed on exit.
#
# USE:
#   scripts/round871-serve-ladder.sh <outdir> <n> [--front] [--proj DIR]
#                                    [--edit FILE] [--tag NAME]
#
#   --edit FILE   before every request from the 2nd on, append one comment line
#                 to FILE (a path relative to the project dir), so the ladder
#                 models an EDITOR workload: 77 of 78 files byte-identical to
#                 the previous request. The file is restored on exit.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?usage: round871-serve-ladder.sh <outdir> <n> [--front] [--proj DIR] [--edit FILE] [--tag NAME]}"
N="${2:?usage: round871-serve-ladder.sh <outdir> <n> ...}"
shift 2

FRONT=0
PROJ="$ROOT/build/bench/tsc-project-637d5746"
EDIT=""
TAG="ladder"
AMPSEQ=""
CACHESEQ=""
while [ $# -gt 0 ]; do
  case "$1" in
    --front) FRONT=1; shift ;;
    --proj)  PROJ="$2"; shift 2 ;;
    --edit)  EDIT="$2"; shift 2 ;;
    --tag)   TAG="$2"; shift 2 ;;
    # (WARM.19) the amplification ladder: one comma-separated `--parseAmp` value
    # per request, IN ONE DAEMON PROCESS, so a rotation (0,1,2,3,3,2,1,0) puts
    # every arm on both sides of any drift. `n` is ignored when this is given.
    --ampseq) AMPSEQ="$2"; FRONT=1; shift 2 ;;
    # (WARM.19) the CONTROLLED row for the cross-request parse cache: one
    # `on`/`off` per request, in ONE daemon process, so an ABBA rotation puts
    # both arms at the same warmth and on both sides of any drift. `off` sends
    # `--parseCacheOff`, whose ledger entry is restored after the request, so
    # the arms differ in nothing but whether the crawl consults the cache.
    --cacheseq) CACHESEQ="$2"; FRONT=1; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
if [ -n "$AMPSEQ" ]; then
  IFS=',' read -r -a AMPS <<< "$AMPSEQ"
  N="${#AMPS[@]}"
fi
if [ -n "$CACHESEQ" ]; then
  IFS=',' read -r -a CACHES <<< "$CACHESEQ"
  N="${#CACHES[@]}"
fi

mkdir -p "$OUT"

LIB="$ROOT/xemantic-typescript-compiler-daemon/build/install/lib"
[ -d "$LIB" ] || { echo "error: $LIB missing — run ./gradlew assemble first (round 857)" >&2; exit 1; }
JAR="$LIB/xemantic-typescript-compiler-jvm-0.1.0-SNAPSHOT.jar"
[ -f "$JAR" ] || { echo "error: staged compiler jar missing" >&2; exit 1; }
# POSITIVE CONTROL on the artifact under test (round 853): the staged jar must
# carry a class this arc added, or the ladder is measuring a pre-arc binary.
python3 - "$JAR" <<'EOF' || exit 1
import sys, zipfile
names = set(zipfile.ZipFile(sys.argv[1]).namelist())
want = "com/xemantic/typescript/compiler/ModuleSymbolScanIndexKt.class"
if want not in names:
    sys.stderr.write("error: staged jar has no %s — pre-round-870 artifact\n" % want)
    sys.exit(1)
EOF

[ -d "$PROJ" ] || { echo "error: project $PROJ missing" >&2; exit 1; }

SOCK="/tmp/xtsc-r871-$$.sock"
SRVLOG="$OUT/$TAG.server.log"
DAEMON_PID=""
EDIT_BAK=""

cleanup() {
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" 2>/dev/null || true
  rm -f "$SOCK"
  if [ -n "$EDIT_BAK" ] && [ -f "$EDIT_BAK" ]; then
    mv -f "$EDIT_BAK" "$PROJ/$EDIT"
  fi
}
trap cleanup EXIT

if [ -n "$EDIT" ]; then
  [ -f "$PROJ/$EDIT" ] || { echo "error: --edit $PROJ/$EDIT missing" >&2; exit 1; }
  EDIT_BAK="$OUT/$TAG.edit.bak"
  cp -p "$PROJ/$EDIT" "$EDIT_BAK"
fi

export XTSC_AOT=off
XTSC_AOT=off "$ROOT/scripts/xtsc" --serve --socket "$SOCK" > "$SRVLOG" 2>&1 &
DAEMON_PID=$!

# Wait for the bind line rather than sleeping a guessed interval.
for _ in $(seq 1 200); do
  grep -q "listening on" "$SRVLOG" 2>/dev/null && break
  sleep 0.25
done
grep -q "listening on" "$SRVLOG" || { echo "error: daemon never bound; see $SRVLOG" >&2; exit 1; }

ARGS=(--noEmit)
[ "$FRONT" -eq 1 ] && ARGS+=(--frontEnd)

TSV="$OUT/$TAG.tsv"
: > "$TSV"
printf 'req\tamp\tclient_ms\tserver_ms\tcompiler_ms\tcrawl_ms\terrs\tdigest\n' >> "$TSV"

for i in $(seq 1 "$N"); do
  if [ -n "$EDIT" ] && [ "$i" -gt 1 ]; then
    printf '\n// xtsc round871 edit %s\n' "$i" >> "$PROJ/$EDIT"
  fi
  REQARGS=("${ARGS[@]}")
  amp=0
  if [ -n "$AMPSEQ" ]; then
    amp="${AMPS[$((i - 1))]}"
    REQARGS+=(--parseAmp "$amp")
  fi
  if [ -n "$CACHESEQ" ]; then
    amp="${CACHES[$((i - 1))]}"
    [ "$amp" = "off" ] && REQARGS+=(--parseCacheOff)
  fi
  t0=$(date +%s%N)
  XTSC_AOT=off "$ROOT/scripts/xtsc" --daemon --socket "$SOCK" "${REQARGS[@]}" --listAll "$PROJ" \
      > "$OUT/$TAG.req$i.txt" 2>&1 || true
  t1=$(date +%s%N)
  client=$(( (t1 - t0) / 1000000 ))
  server=$(grep -a "request $i served in" "$SRVLOG" | tail -1 | sed 's/.*served in \([0-9]*\) ms.*/\1/')
  compiler=$(grep -a '^time:' "$OUT/$TAG.req$i.txt" | tail -1 | sed 's/[^0-9]*\([0-9]*\) ms.*/\1/')
  errs=$(grep -ac 'error TS' "$OUT/$TAG.req$i.txt" || true)
  digest=$(grep -a 'error TS' "$OUT/$TAG.req$i.txt" | sed "s#${PROJ}/##g" | sort | md5sum | cut -c1-8)
  crawl=$(grep -a '"import-graph crawl (WALL)"' "$OUT/$TAG.req$i.txt" | tail -1 | awk -F, '{printf "%.1f", $3/1000000}')
  cache=$(grep -a 'crawl parse cache:' "$OUT/$TAG.req$i.txt" | tail -1 | sed 's/crawl parse cache: //')
  mism=$(grep -ac 'MISMATCH' "$OUT/$TAG.req$i.txt" || true)
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$i" "$amp" "$client" "${server:-NA}" "${compiler:-NA}" "${crawl:-NA}" "$errs" "$digest" >> "$TSV"
  printf 'req %-3s amp %-2s client %6s ms  server %6s ms  compiler %6s ms  crawl %7s ms  errs %-4s %s%s\n' \
      "$i" "$amp" "$client" "${server:-NA}" "${compiler:-NA}" "${crawl:-NA}" "$errs" "$digest" \
      "$([ "$mism" != "0" ] && echo '  ** AMP MISMATCH **' || true)"
  [ -n "${cache:-}" ] && printf '        cache: %s\n' "$cache"
done

echo
echo "=== $TSV ==="
cat "$TSV"
