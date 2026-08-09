#!/usr/bin/env bash
# ROUND 872 — (WARM.20): the FIXED per-invocation latency of every client arm
# that exists, measured against ONE already-running daemon.
#
# Round 871 isolated the client side with a request the server refuses in
# constant time (`--watch` is answered by `CompileServer.respondTo` with
# `elapsedMs = 0` before any compile is attempted), and read 279 ms. That figure
# is the FAT dispatcher — `scripts/xtsc --daemon`, i.e. `XtscMainKt` in the
# daemon module, whose classpath carries the whole 5.6 MB compiler jar. This
# script measures that arm against the ones the module split already built and
# nothing had ever run:
#
#   fat        scripts/xtsc --daemon                  (the round-871 baseline)
#   fat-aot    …with the JDK 25 AOT cache             (must print `aot USE`)
#   thin       the -client module's own JVM main      (api + coroutines + ktor)
#   thin-aot   …with its own AOT cache
#   native     the -client module's Kotlin/Native binary
#   floor-exec /bin/true                              (fork+exec+ld.so+exit)
#   floor-sock python3 raw socket round trip          (protocol, no client)
#   floor-py   python3 -c pass                        (that arm's own startup)
#
# TIMING USES BASH'S `EPOCHREALTIME`, NEVER `date +%s%N`. The arms here span two
# orders of magnitude and the fast ones are single-digit ms: `date` is a fork and
# an exec per timestamp, which would be a large fraction of the very arm the
# round exists to measure. `floor-exec` prices what remains.
#
# Arms are INTERLEAVED (one rep = every arm once, rotated by rep) rather than
# blocked, because a fixed cost is exactly the thing a slow drift across a
# blocked run would attribute to whichever arm ran last.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?usage: round872-client-arms.sh <outdir> [reps]}"
N="${2:-12}"
mkdir -p "$OUT"

CLIENT_LIB="$ROOT/xemantic-typescript-compiler-client/build/install/lib"
KEXE="$ROOT/xemantic-typescript-compiler-client/build/bin/linuxX64/releaseExecutable/xemantic-typescript-compiler-client.kexe"
SOCK="/tmp/xtsc-r872-$$.sock"
SRVLOG="$OUT/arms.server.log"
DAEMON_PID=""
cleanup() { [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" 2>/dev/null || true; rm -f "$SOCK"; }
trap cleanup EXIT

# The staged client dir is produced by `:…-client:clientLib`, which — unlike the
# daemon's `xtscLib` — is NOT wired into `assemble`. Round 857's failure mode was
# exactly a launcher whose classpath dir nobody had created; refuse rather than
# silently measure something else.
[ -d "$CLIENT_LIB" ] || { echo "error: no $CLIENT_LIB — run ./gradlew :xemantic-typescript-compiler-client:clientLib" >&2; exit 1; }
CP="$(find "$CLIENT_LIB" -maxdepth 1 -name '*.jar' | LC_ALL=C sort | tr '\n' ':' | sed 's/:$//')"
[ -n "$CP" ] || { echo "error: $CLIENT_LIB holds no jars" >&2; exit 1; }
HAVE_KEXE=0; [ -x "$KEXE" ] && HAVE_KEXE=1

# ---------------------------------------------------------------------------
# the daemon
# ---------------------------------------------------------------------------
export XTSC_AOT=off          # for the SERVER; each client arm decides its own
"$ROOT/scripts/xtsc" --serve --socket "$SOCK" > "$SRVLOG" 2>&1 &
DAEMON_PID=$!
for _ in $(seq 1 240); do grep -q "listening on" "$SRVLOG" 2>/dev/null && break; sleep 0.25; done
grep -q "listening on" "$SRVLOG" || { echo "error: daemon never bound" >&2; exit 1; }
echo "daemon pid $DAEMON_PID on $SOCK"

# ---------------------------------------------------------------------------
# the raw-socket floor: 4-byte big-endian length + UTF-8 JSON, both ways
# (api/Framing.kt). Kept in python so it depends on nothing this repo builds.
# ---------------------------------------------------------------------------
cat > "$OUT/rawsock.py" <<'PY'
import json, socket, struct, sys
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect(sys.argv[1])
p = json.dumps({"args": ["--watch", "."], "protocolVersion": 1}).encode()
s.sendall(struct.pack(">i", len(p)) + p)
n = struct.unpack(">i", s.recv(4))[0]
b = b""
while len(b) < n:
    b += s.recv(n - len(b))
sys.stdout.write(json.loads(b)["output"])
PY

# ---------------------------------------------------------------------------
# arms
# ---------------------------------------------------------------------------
ARMS=(fat thin native floor-exec floor-sock floor-py)
[ -n "${XTSC_R872_FAT_AOT:-}" ] && ARMS+=(fat-aot)
[ -n "${XTSC_R872_THIN_AOT:-}" ] && ARMS+=(thin-aot)
[ "$HAVE_KEXE" -eq 1 ] || ARMS=("${ARMS[@]/native}")

run_arm() {  # $1 = arm name; output to $2
  case "$1" in
    fat)      XTSC_AOT=off "$ROOT/scripts/xtsc" --daemon --socket "$SOCK" --watch . > "$2" 2>&1 || true ;;
    fat-aot)  XTSC_AOT_VERBOSE=1 "$ROOT/scripts/xtsc" --daemon --socket "$SOCK" --watch . > "$2" 2>&1 || true ;;
    thin)     java -cp "$CP" com.xemantic.typescript.compiler.client.MainKt \
                  --socket "$SOCK" --no-spawn --watch . > "$2" 2>&1 || true ;;
    thin-aot) java -XX:AOTCache="${XTSC_R872_THIN_AOT}" -Xlog:aot*=off:stdout -Xlog:aot*=error:stderr \
                  -cp "$CP" com.xemantic.typescript.compiler.client.MainKt \
                  --socket "$SOCK" --no-spawn --watch . > "$2" 2>&1 || true ;;
    native)   "$KEXE" --socket "$SOCK" --no-spawn --watch . > "$2" 2>&1 || true ;;
    floor-exec) /bin/true > "$2" 2>&1 || true ;;
    floor-sock) python3 "$OUT/rawsock.py" "$SOCK" > "$2" 2>&1 || true ;;
    floor-py)   python3 -c pass > "$2" 2>&1 || true ;;
  esac
}

# One warm-up of every arm, discarded: the first invocation of each pays page
# cache and, for the JVM arms, a cold class-path scan that no later one repeats.
for a in "${ARMS[@]}"; do [ -n "$a" ] && run_arm "$a" "$OUT/warm.$a.txt"; done

: > "$OUT/samples.tsv"
for rep in $(seq 1 "$N"); do
  # rotate so no arm keeps a fixed position in the rep
  n=${#ARMS[@]}
  for k in $(seq 0 $((n - 1))); do
    a="${ARMS[$(( (k + rep) % n ))]}"
    [ -n "$a" ] || continue
    t0=$EPOCHREALTIME
    run_arm "$a" "$OUT/last.$a.txt"
    t1=$EPOCHREALTIME
    ms=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.1f", (b-a)*1000}')
    printf '%s\t%s\t%s\n' "$a" "$rep" "$ms" >> "$OUT/samples.tsv"
  done
done

echo
echo "arm                 n   median     min      max   p-spread"
awk -F'\t' '{v[$1]=v[$1]" "$3} END{
  for (a in v) {
    n=split(v[a], x, " "); m=0
    for (i=1;i<=n;i++) if (x[i]!="") y[++m]=x[i]+0
    for (i=1;i<m;i++) for (j=i+1;j<=m;j++) if (y[j]<y[i]) {t=y[i];y[i]=y[j];y[j]=t}
    med = (m%2) ? y[(m+1)/2] : (y[m/2]+y[m/2+1])/2
    printf "%-18s %3d %8.1f %7.1f %8.1f %8.1f%%\n", a, m, med, y[1], y[m], (y[m]-y[1])*100/med
  }
}' "$OUT/samples.tsv" | LC_ALL=C sort -k3 -n

echo
echo "every arm must have produced the SAME refusal — otherwise an arm is not"
echo "doing the round trip and its number is meaningless:"
for a in "${ARMS[@]}"; do
  [ -n "$a" ] || continue
  case "$a" in floor-exec|floor-py) printf '  %-12s (control, no request)\n' "$a" ;;
    *) printf '  %-12s %s\n' "$a" "$(grep -ao 'not supported over the compile server' "$OUT/last.$a.txt" | head -1 || echo 'MISSING!!')" ;;
  esac
done
