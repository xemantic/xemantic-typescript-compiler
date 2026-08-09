#!/usr/bin/env bash
# ROUND 872 — (WARM.20) part 2: what the client arm is worth on a REAL request,
# at both ends of the project-size range.
#
# The fixed client cost does not scale with the project, so a percentage of it is
# meaningless without saying which project: on the 78-file compiler profile
# (~7 s) it is a rounding error, and on the 3-file project an editor actually
# generates it is most of the wait. This runs the SAME warm daemon from two
# client arms over a given project, interleaved and rotated.
#
# Usage: round872-end-to-end.sh <outdir> <projectDir> [reps]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?usage: round872-end-to-end.sh <outdir> <projectDir> [reps]}"
PROJ="${2:?usage: round872-end-to-end.sh <outdir> <projectDir> [reps]}"
N="${3:-8}"
mkdir -p "$OUT"

KEXE="$ROOT/xemantic-typescript-compiler-client/build/bin/linuxX64/releaseExecutable/xemantic-typescript-compiler-client.kexe"
[ -x "$KEXE" ] || { echo "error: no native client at $KEXE" >&2; exit 1; }
SOCK="/tmp/xtsc-r872e-$$.sock"
SRVLOG="$OUT/e2e.server.log"
DAEMON_PID=""
cleanup() { [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" 2>/dev/null || true; rm -f "$SOCK"; }
trap cleanup EXIT

export XTSC_AOT=off
"$ROOT/scripts/xtsc" --serve --socket "$SOCK" > "$SRVLOG" 2>&1 &
DAEMON_PID=$!
for _ in $(seq 1 240); do grep -q "listening on" "$SRVLOG" 2>/dev/null && break; sleep 0.25; done
grep -q "listening on" "$SRVLOG" || { echo "error: daemon never bound" >&2; exit 1; }

run_arm() {
  case "$1" in
    jvm)    XTSC_AOT=off "$ROOT/scripts/xtsc" --daemon --socket "$SOCK" --noEmit --listAll "$PROJ" > "$2" 2>&1 || true ;;
    native) "$KEXE" --socket "$SOCK" --no-spawn --noEmit --listAll "$PROJ" > "$2" 2>&1 || true ;;
  esac
}

# Warm the DAEMON on this project first: the first request pays the crawl parse
# that round 871's CrawlParseCache exists to reuse, and it would otherwise land
# entirely on whichever arm happened to go first.
for w in 1 2; do run_arm jvm "$OUT/warm.$w.txt"; done

ARMS=(jvm native)
: > "$OUT/e2e.tsv"
for rep in $(seq 1 "$N"); do
  for k in 0 1; do
    a="${ARMS[$(( (k + rep) % 2 ))]}"
    t0=$EPOCHREALTIME
    run_arm "$a" "$OUT/last.$a.txt"
    t1=$EPOCHREALTIME
    ms=$(awk -v x="$t0" -v y="$t1" 'BEGIN{printf "%.1f", (y-x)*1000}')
    srv=$(grep -ao 'time: [0-9]*' "$OUT/last.$a.txt" | head -1 | tr -dc '0-9' || true)
    printf '%s\t%s\t%s\t%s\n' "$a" "$rep" "$ms" "${srv:-NA}" >> "$OUT/e2e.tsv"
  done
done

echo
echo "project: $PROJ"
echo "arm        n   median      min      max"
awk -F'\t' '{v[$1]=v[$1]" "$3} END{
  for (a in v) { n=split(v[a], x, " "); m=0
    for (i=1;i<=n;i++) if (x[i]!="") y[++m]=x[i]+0
    for (i=1;i<m;i++) for (j=i+1;j<=m;j++) if (y[j]<y[i]) {t=y[i];y[i]=y[j];y[j]=t}
    med=(m%2)?y[(m+1)/2]:(y[m/2]+y[m/2+1])/2
    printf "%-8s %3d %8.1f %8.1f %8.1f\n", a, m, med, y[1], y[m] }
}' "$OUT/e2e.tsv" | LC_ALL=C sort -k3 -n

echo
echo "EQUIVALENCE — both arms must report the same diagnostics:"
for a in jvm native; do
  n=$(grep -ac 'error TS' "$OUT/last.$a.txt" || true)
  d=$(grep -a 'error TS' "$OUT/last.$a.txt" | sed 's#.*/src/#src/#' | LC_ALL=C sort | md5sum | cut -c1-8)
  printf '  %-8s %s errors, digest %s\n' "$a" "$n" "$d"
done
