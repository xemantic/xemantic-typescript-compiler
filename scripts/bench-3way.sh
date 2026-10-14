#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
# Three-way compile bench: compile the pinned TypeScript compiler sources with
# xtsc, the reference JS tsc, and the native tsgo, then write a per-run Markdown
# report under bench-history/runs/ and prepend rows to bench-history/README.md
# so improvements are observable across runs (see .github/workflows/bench.yml).
#
# xtsc IS THE tsgo PORT (`-tsgo`) since the V3 series (2026-10-14, owner): the
# compiler that ships and is retiring `-core` ((TSGO.7)/(TSGO.8)). Rows before
# that measured `-core`'s hand-written checker (46 diagnostics where tsc and tsgo
# report 65) and live below the V3 table as an archive.
#
# All compilers compile the SAME materialized project (the `compiler` profile
# that scripts/bench-compile-tsc.sh --materialize-only builds from
# typescript-repo @ typeScriptCommit). Every arm's diagnostic count is reported:
# the port must report what tsgo reports (all env-legit offline `@types/node`
# rows — no `node_modules/@types` here).
#
# TWO MODES, both measured, both like-for-like:
#
#   check-only  every compiler runs with --noEmit: nothing is transformed,
#               emitted or written. The mode the perf arc profiles.
#   emit        every compiler type-checks AND emits JavaScript to an outDir.
#
# xtsc ARMS:
#   warm      `CheckBenchMain` (-tsgo jvmTest): tsgo's default parallel checker,
#             rebuilt from scratch per iteration in one JVM — tsconfig, program,
#             every diagnostic (and in emit mode the emit, outputs discarded);
#             file contents read once (a warm host). The headline: the daemon /
#             language-server regime. Median of the MEASURED iterations.
#   cold JVM  `TsgoMain` (the ported `tsc` command line) in a fresh JVM, no AOT
#             cache: the one-shot CLI on a plain `java`.
#   native    OPTIONAL — the -tsgo GraalVM image (`:…-tsgo:nativeImage`), run
#             exactly as tsc/tsgo are.
#
# Usage: scripts/bench-3way.sh --tsc PATH --tsgo PATH [options]
#   --tsc PATH        reference JS `tsc` binary (required)
#   --tsgo PATH       native `tsgo` binary (required)
#   --xtsc-native P   OPTIONAL -tsgo native image (build/native/xtsc-tsgo),
#                     measured as a further compiler in every mode
#   --project NAME    tsc subproject profile (default compiler)
#   --xtsc-iters N    measured COLD JVM xtsc runs, median (default 1)
#   --ref-iters N     measured tsc/tsgo/native runs, median (default 3)
#   --xtsc-warmup N   warm-up rebuilds before the warm arm's measured window
#                     (default 6)
#   --xtsc-warm-iters N  measured warm rebuilds, median (default 8)
#   --no-warm         skip the warm arm
#   --warm-jvm L=P    label + java binary for the primary warm arm (default: `java`)
#   --warm-jvm2 L=P   OPTIONAL second warm arm on a DIFFERENT JVM, same bytecode,
#                     same run (the Graal JIT wins warm and loses cold against
#                     HotSpot; reporting both means never having to pick)
#   --modes LIST      comma-separated subset of check-only,emit (default both)
#   --out-dir DIR     bench-history root (default bench-history)
#   --label TEXT      free-text note stored in the report
#   --help            show this help

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TSC_BIN=""; TSGO_BIN=""; XTSC_NATIVE=""; PROJECT=compiler
XTSC_ITERS=1; REF_ITERS=3
XTSC_WARMUP=6; XTSC_WARM_ITERS=8; WARM=1
WARM_JVM1=""; WARM_LABEL1="jvm"; WARM_JVM2=""; WARM_LABEL2="jvm2"
MODES="check-only,emit"
OUT_DIR="$REPO_ROOT/bench-history"; LABEL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tsc)        TSC_BIN="$2"; shift 2 ;;
        --tsgo)       TSGO_BIN="$2"; shift 2 ;;
        --xtsc-native) XTSC_NATIVE="$2"; shift 2 ;;
        --project)    PROJECT="$2"; shift 2 ;;
        --xtsc-iters) XTSC_ITERS="$2"; shift 2 ;;
        --xtsc-warmup) XTSC_WARMUP="$2"; shift 2 ;;
        --xtsc-warm-iters) XTSC_WARM_ITERS="$2"; shift 2 ;;
        --no-warm)    WARM=0; shift ;;
        --warm-jvm)   WARM_LABEL1="${2%%=*}"; WARM_JVM1="${2#*=}"; shift 2 ;;
        --warm-jvm2)  WARM_LABEL2="${2%%=*}"; WARM_JVM2="${2#*=}"; shift 2 ;;
        --ref-iters)  REF_ITERS="$2"; shift 2 ;;
        --modes)      MODES="$2"; shift 2 ;;
        --out-dir)    OUT_DIR="$2"; shift 2 ;;
        --label)      LABEL="$2"; shift 2 ;;
        --help|-h)    sed -n '/^# Three-way/,/^set -euo/p' "$0" | sed '$d;s/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
done
IFS=',' read -r -a MODE_LIST <<<"$MODES"
for m in "${MODE_LIST[@]}"; do
    [[ "$m" == check-only || "$m" == emit ]] || {
        echo "error: unknown mode '$m' (known: check-only, emit)" >&2; exit 2; }
done
[[ -n "$TSC_BIN"  ]] || { echo "error: --tsc PATH is required" >&2; exit 2; }
[[ -n "$TSGO_BIN" ]] || { echo "error: --tsgo PATH is required" >&2; exit 2; }
command -v python3 >/dev/null || { echo "error: python3 not found" >&2; exit 1; }

# `tsc --version` prints "Version 6.0.3"; tsgo "Version 7.0.2".
TSC_VER="$("$TSC_BIN"  --version 2>/dev/null | awk '{print $NF}' || echo '?')"
TSGO_VER="$("$TSGO_BIN" --version 2>/dev/null | awk '{print $NF}' || echo '?')"
[[ -n "$TSC_VER"  ]] || TSC_VER='?'
[[ -n "$TSGO_VER" ]] || TSGO_VER='?'
strip_ansi() { sed -E $'s/\x1b\\[[0-9;]*m//g'; }

median() { printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END{ if(NR%2) print a[(NR+1)/2]; else print int((a[NR/2]+a[NR/2+1])/2) }'; }

# --------------------------------------------------------------------------
# 1. The project, and the port's JVM classpath (main + test classes: the warm
#    harness lives in jvmTest), both resolved once.
# --------------------------------------------------------------------------
PROJ_DIR="$("$REPO_ROOT/scripts/bench-compile-tsc.sh" --project "$PROJECT" --materialize-only | tail -1)"
[[ -f "$PROJ_DIR/tsconfig.json" ]] || { echo "error: no materialized project at '$PROJ_DIR'" >&2; exit 1; }
TS_COMMIT8="$(git -C "$REPO_ROOT/typescript-repo" rev-parse HEAD | cut -c1-8)"
FILES=$(find "$PROJ_DIR/src" -name '*.ts' | wc -l)
LOC=$(find "$PROJ_DIR/src" -name '*.ts' -print0 | xargs -0 cat | wc -l)

WORK="$(mktemp -d)"; trap 'rm -rf -- "$WORK"' EXIT
cat > "$WORK/print-cp.init.gradle.kts" <<'G'
rootProject {
    findProject(":xemantic-typescript-compiler-tsgo")?.let { p ->
        p.tasks.register("printBenchClasspath") {
            dependsOn("jvmTestClasses")
            val cp = p.configurations.getByName("jvmTestRuntimeClasspath")
            doLast {
                val dirs = listOf("classes/kotlin/jvm/main", "classes/kotlin/jvm/test", "processedResources/jvm/main", "processedResources/jvm/test")
                    .map { p.layout.buildDirectory.dir(it).get().asFile }
                println("BENCH_CP=" + (dirs + cp.files).joinToString(":"))
            }
        }
    }
}
G
echo "== resolving the -tsgo classpath (compiles jvmTest) =="
./gradlew -q --console=plain -I "$WORK/print-cp.init.gradle.kts" \
    :xemantic-typescript-compiler-tsgo:printBenchClasspath > "$WORK/cp.log" 2>&1 \
    || { cat "$WORK/cp.log" >&2; exit 1; }
[[ $(grep -ac '^BENCH_CP=' "$WORK/cp.log") == 1 ]] || { cat "$WORK/cp.log" >&2; exit 1; }
CP="$(grep -a '^BENCH_CP=' "$WORK/cp.log" | sed 's/^BENCH_CP=//')"
JAVA1="${WARM_JVM1:-java}"

# The cold JVM arm as an executable, so every process arm runs through run_proc.
cat > "$WORK/xtsc-jvm" <<EOF
#!/usr/bin/env bash
exec "$JAVA1" -Xss4m -Xmx6g -cp "$CP" com.xemantic.typescript.tsgo.cli.TsgoMainKt "\$@"
EOF
chmod +x "$WORK/xtsc-jvm"

# --------------------------------------------------------------------------
# 2. Process arms (tsc, tsgo, xtsc cold JVM, xtsc native): the same command
#    line, wall clock of the whole process, `error TS` lines counted.
# --------------------------------------------------------------------------
run_proc() { # $1=bin $2=mode $3=iters ; echoes "wall_ms err"
    local bin="$1" mode="$2" iters="$3" walls=() out i start end err=0
    for ((i=1;i<=iters;i++)); do
        out="$(mktemp)"
        start=$(date +%s%N)
        if [[ "$mode" == check-only ]]; then
            "$bin" -p "$PROJ_DIR/tsconfig.json" --noEmit >"$out" 2>&1 || true
        else
            "$bin" -p "$PROJ_DIR/tsconfig.json" --outDir "$(mktemp -d)" >"$out" 2>&1 || true
        fi
        end=$(date +%s%N)
        walls+=($(( (end-start)/1000000 )))
        err=$(strip_ansi <"$out" | grep -cE 'error TS[0-9]+' || true)
        rm -f "$out"
    done
    echo "$(median "${walls[@]}") ${err:-0}"
}

# --------------------------------------------------------------------------
# 3. The warm arm: CheckBenchMain prints one `iter N meas total_ms=… diags=…`
#    line per measured rebuild with its diagnostic digest and emitted-file count
#    (equal across iterations, and non-zero exactly in emit mode). A run with no measured line is a
#    failed arm, reported as 0 — never silently as a fast one.
# --------------------------------------------------------------------------
run_warm() { # $1=mode $2=java $3=label ; sets warm_ms warm_err
    warm_ms=0; warm_err=0
    [[ "$WARM" -eq 1 ]] || return 0
    local mode="$1" jb="$2" label="$3" out flags=() res
    [[ -x "$jb" || "$jb" == java ]] || {
        echo "::warning::warm arm '$label': no executable java at $jb" >&2; return 0; }
    [[ "$mode" == emit ]] && flags=(emit)
    echo "== xtsc-warm/$label ($mode, warmup $XTSC_WARMUP, iters $XTSC_WARM_ITERS) =="
    out="$WORK/warm-$label-$mode.log"
    "$jb" -Xms2g -Xmx6g -cp "$CP" com.xemantic.typescript.tsgo.CheckBenchMainKt \
        "$PROJ_DIR/tsconfig.json" "$XTSC_WARMUP" "$XTSC_WARM_ITERS" nolib parallel \
        ${flags[@]+"${flags[@]}"} >"$out" 2>&1 || true
    res="$(python3 - "$out" "$mode" <<'PY'
import re, statistics, sys
rows = [l for l in open(sys.argv[1], errors="replace") if l.startswith("iter ") and " meas " in l]
emitted = {int(re.search(r"emitted=(\d+)", r).group(1)) for r in rows}
ms = [float(re.search(r"total_ms=([\d.]+)", r).group(1)) for r in rows]
diags = {re.search(r"diags=(\d+)", r).group(1) for r in rows}
digests = {re.search(r"digest=(\S+)", r).group(1) for r in rows}
# an emit run that wrote nothing would read as a fast one
if not rows or len(diags) != 1 or len(digests) != 1 or len(emitted) != 1 or (sys.argv[2] == "emit") != (emitted.pop() > 0):
    print("0 0")
else:
    print(int(statistics.median(ms)), diags.pop())
PY
)"
    read -r warm_ms warm_err <<<"$res"
    [[ "$warm_ms" != 0 ]] || {
        echo "::warning::warm arm '$label' produced no consistent measured iterations" >&2; tail -20 "$out" >&2; }
}

export XTSC_TSGO_LIB_DIR=""   # every xtsc arm on its bundled libs, as shipped
MODE_DATA=()
for MODE in "${MODE_LIST[@]}"; do
    run_warm "$MODE" "$JAVA1" "$WARM_LABEL1"; xw1="$warm_ms"; xwerr="$warm_err"
    xw2=0
    if [[ -n "$WARM_JVM2" ]]; then run_warm "$MODE" "$WARM_JVM2" "$WARM_LABEL2"; xw2="$warm_ms"; fi
    echo "== xtsc cold JVM ($MODE) =="; read -r xc xcerr < <(run_proc "$WORK/xtsc-jvm" "$MODE" "$XTSC_ITERS")
    echo "== tsc ($TSC_VER, $MODE) ==";   read -r tw terr < <(run_proc "$TSC_BIN"  "$MODE" "$REF_ITERS")
    echo "== tsgo ($TSGO_VER, $MODE) =="; read -r gw gerr < <(run_proc "$TSGO_BIN" "$MODE" "$REF_ITERS")
    nw=0; nerr=0
    if [[ -n "$XTSC_NATIVE" ]]; then
        echo "== xtsc native ($MODE) =="; read -r nw nerr < <(run_proc "$XTSC_NATIVE" "$MODE" "$REF_ITERS")
    fi
    MODE_DATA+=("$MODE $xw1 $xw2 $xwerr $xc $xcerr $tw $terr $gw $gerr $nw $nerr")
done

# --------------------------------------------------------------------------
# 4. Render the per-run report + update the index.
# --------------------------------------------------------------------------
XTSC_REV="$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD)"
XTSC_REV_FULL="$(git -C "$REPO_ROOT" rev-parse HEAD)"
RUN_STAMP="${BENCH_RUN_STAMP:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUNNER="${BENCH_RUNNER:-$(uname -s)-$(uname -m)}"
REPO_SLUG="${GITHUB_REPOSITORY:-xemantic/xemantic-typescript-compiler}"

# All values are passed via the environment (NOT string-interpolated into the
# heredoc) — the report text contains backticks/`$` that an unquoted heredoc would
# run as command substitution. The delimiter is quoted so bash leaves the body verbatim.
export B_OUT_DIR="$OUT_DIR" B_STAMP="$RUN_STAMP" B_REV="$XTSC_REV" B_REV_FULL="$XTSC_REV_FULL"
export B_PROJECT="$PROJECT" B_RUNNER="$RUNNER" B_SLUG="$REPO_SLUG" B_LABEL="$LABEL"
export B_TS_COMMIT="$TS_COMMIT8" B_FILES="$FILES" B_LOC="$LOC"
export B_TSC_VER="$TSC_VER" B_TSGO_VER="$TSGO_VER"
export B_XITERS="$XTSC_ITERS" B_REFITERS="$REF_ITERS"
export B_WARMUP="$XTSC_WARMUP" B_WARMITERS="$XTSC_WARM_ITERS"
export B_WLABEL1="$WARM_LABEL1" B_WLABEL2="$WARM_LABEL2"
B_MODE_DATA="$(printf '%s\n' "${MODE_DATA[@]}")"; export B_MODE_DATA

python3 - <<'PYEOF'
import os
g = lambda k, d="": os.environ.get(k, d)
i = lambda k: int(g(k) or 0)

out_dir  = g("B_OUT_DIR")
runs_dir = os.path.join(out_dir, "runs")
os.makedirs(runs_dir, exist_ok=True)

stamp, rev, rev_full = g("B_STAMP"), g("B_REV"), g("B_REV_FULL")
project, runner, slug, label = g("B_PROJECT"), g("B_RUNNER"), g("B_SLUG"), g("B_LABEL")
ts_commit, files, loc = g("B_TS_COMMIT"), i("B_FILES"), i("B_LOC")
tsc_ver, tsgo_ver = g("B_TSC_VER"), g("B_TSGO_VER")
xiters, refiters = i("B_XITERS"), i("B_REFITERS")
warmup, warmiters = i("B_WARMUP"), i("B_WARMITERS")
wlabel1, wlabel2 = g("B_WLABEL1", "jvm"), g("B_WLABEL2", "jvm2")

# mode warm1 warm2 warm_err cold cold_err tsc tsc_err tsgo tsgo_err nat nat_err
# (0 = arm not run or failed)
modes = []
for line in g("B_MODE_DATA").splitlines():
    p = line.split()
    if len(p) == 12:
        modes.append((p[0],) + tuple(int(x) for x in p[1:]))

def sec(ms): return f"{ms/1000:.2f}s" if ms else "—"
def tput(ms): return f"{loc*1000//ms:,}" if ms and loc else "—"
def ratio(a, b): return f"{a/b:.2f}×" if a and b else "—"

date_h = f"{stamp[0:4]}-{stamp[4:6]}-{stamp[6:8]}"
commit_url = f"https://github.com/{slug}/commit/{rev_full}"
MODE_DOC = {
    "check-only": "every compiler runs with `--noEmit`: type-check only, nothing transformed, emitted or written.",
    "emit":       "every compiler type-checks AND emits JavaScript to an `outDir` (the warm arm emits and discards).",
}

lines = [
    f"# Bench run {stamp}",
    "",
    f"- **xtsc revision**: [`{rev}`]({commit_url}) — the tsgo port (`-tsgo`)",
    f"- **TypeScript pin**: `{ts_commit}` (typeScriptCommit)",
    f"- **Profile**: `{project}` — {files} files, {loc:,} LOC",
    f"- **Runner**: {runner}",
    f"- **Method**: xtsc **warm** = `CheckBenchMain` (tsgo's parallel checker), median of {warmiters} "
    f"in-process rebuilds after {warmup} warm-up rebuilds; xtsc **cold JVM** = `TsgoMain`, median of "
    f"{xiters} process(es); tsc / tsgo / xtsc native = median of {refiters} processes.",
]
if label:
    lines.append(f"- **Label**: {label}")

for mode, xw1, xw2, xwerr, xc, xcerr, tw, terr, gw, gerr, nw, nerr in modes:
    lines += ["", f"## {mode}", "", MODE_DOC.get(mode, ""), "",
              "| Compiler | Version | Wall | Throughput | Errors |", "|---|---|---:|---:|---:|"]
    if xw1:
        lines.append(f"| **xtsc (warm, {wlabel1})** | {rev} | **{sec(xw1)}** | {tput(xw1)} LOC/s | {xwerr} |")
    if xw2:
        lines.append(f"| **xtsc (warm, {wlabel2})** | {rev} | **{sec(xw2)}** | {tput(xw2)} LOC/s | {xwerr} |")
    lines.append(f"| xtsc (cold JVM) | {rev} | {sec(xc)} | {tput(xc)} LOC/s | {xcerr} |")
    if nw:
        lines.append(f"| xtsc (native image) | {rev} | {sec(nw)} | {tput(nw)} LOC/s | {nerr} |")
    lines += [
        f"| tsgo | {tsgo_ver} | {sec(gw)} | {tput(gw)} LOC/s | {gerr} |",
        f"| tsc | {tsc_ver} | {sec(tw)} | {tput(tw)} LOC/s | {terr} |",
        "",
        f"Against tsgo (1.00× = parity): warm **{ratio(xw1, gw)}**"
        + (f" ({wlabel2} {ratio(xw2, gw)})" if xw2 else "")
        + f", cold JVM **{ratio(xc, gw)}**"
        + (f", native image **{ratio(nw, gw)}**" if nw else "")
        + f". Against tsc: warm {ratio(xw1, tw)}, cold JVM {ratio(xc, tw)}"
        + (f", native image {ratio(nw, tw)}" if nw else "") + ".",
    ]
    errs = {n for n in (xwerr if xw1 else None, xcerr if xc else None, nerr if nw else None) if n is not None}
    if errs and errs != {gerr}:
        lines += ["", f"**DIAGNOSTIC COUNT DIFFERS FROM tsgo** ({gerr}): xtsc reported {sorted(errs)}."]
lines += [
    "",
    "> The warm arm is an in-process rebuild (no JVM start, files read once): the daemon / language-server "
    "regime. tsc and tsgo are whole processes. In CI (bench.yml) the native column is the plain "
    "`:…-tsgo:nativeImage` (G1, no PGO); the PGO image (`scripts/tsgo-native-image-pgo.sh`, native.yml) is faster. Compare a ratio only "
    "against a ratio of the SAME mode, and read the trend across rows, not one run on a shared runner.",
    "",
    "> Errors are env-legit offline `@types/node` artifacts (no `node_modules/@types` here); tsgo and the "
    "port report the same rows.",
    "",
]
run_name = f"{stamp}-{rev}.md"
with open(os.path.join(runs_dir, run_name), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(lines))

# ---- index (prepend newest rows, one per mode) ----
# V3 MARKERS, 2026-10-14: the series restarts on the tsgo port. Everything already in
# the file (the V2 `-core` table and the older archives) is kept below, never rewritten.
index = os.path.join(out_dir, "README.md")
START, END = "<!-- BENCH-ROWS-V3-START -->", "<!-- BENCH-ROWS-V3-END -->"
col = ("| Date | xtsc rev | Mode | xtsc warm | warm/tsgo | xtsc cold | cold/tsgo | xtsc native | native/tsgo "
       "| tsgo | tsc | err xtsc/tsgo | Run | warm2 |\n"
       "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|\n")
new_rows = "".join(
    f"| {date_h} | [`{rev}`]({commit_url}) | {mode} | {sec(xw1)} | {ratio(xw1, gw)} | {sec(xc)} | {ratio(xc, gw)} "
    f"| {sec(nw)} | {ratio(nw, gw)} | {sec(gw)} | {sec(tw)} | {xcerr}/{gerr} "
    f"| [report](runs/{run_name}) | {sec(xw2)} |\n"
    for mode, xw1, xw2, xwerr, xc, xcerr, tw, terr, gw, gerr, nw, nerr in modes)

txt = open(index, encoding="utf-8").read() if os.path.exists(index) else ""
if START in txt and END in txt:
    head, rest = txt.split(START, 1)
    body, tail = rest.split(END, 1)
    body_rows = "".join(l + "\n" for l in body.splitlines() if l.startswith("| 2"))  # data rows only
    txt = head + START + "\n\n" + col + new_rows + body_rows + "\n" + END + tail
else:
    banner = ("# Bench history\n\n> **Series restarted 2026-10-14 (V3): xtsc is the tsgo port (`-tsgo`).** "
              "Warm = `CheckBenchMain` (tsgo's parallel checker, in-process rebuild); cold = the ported `tsc` "
              "command line in a fresh JVM; native = the port's GraalVM image. Every row below the V3 markers "
              "measured `-core`'s hand-written checker (46 diagnostics, not tsgo's 65) and is NOT comparable.\n\n")
    old = txt[len("# Bench history"):].lstrip("\n") if txt.startswith("# Bench history") else txt
    txt = (banner + START + "\n\n" + col + new_rows + "\n" + END + "\n\n"
           + "## Archive — V2 series (`-core`, warm-primary)\n\n" + old)
with open(index, "w", encoding="utf-8", newline="\n") as f:
    f.write(txt)

print(f"wrote {os.path.join('runs', run_name)} and updated index")
PYEOF

echo
echo "=== 3-way bench ($PROJECT @ $TS_COMMIT8) ==="
for row in "${MODE_DATA[@]}"; do
    read -r m xw1 xw2 xwerr xc xcerr tw terr gw gerr nw nerr <<<"$row"
    echo "  -- $m --"
    printf '    %-12s %8s ms  %4s errors  <-- headline\n' "warm/$WARM_LABEL1" "$xw1" "$xwerr"
    if [[ "$xw2" != 0 ]]; then printf '    %-12s %8s ms\n' "warm/$WARM_LABEL2" "$xw2"; fi
    printf '    %-12s %8s ms  %4s errors\n' "xtsc cold" "$xc" "$xcerr"
    if [[ "$nw" != 0 ]]; then printf '    %-12s %8s ms  %4s errors\n' "xtsc native" "$nw" "$nerr"; fi
    printf '    %-12s %8s ms  %4s errors  (%s)\n' tsgo "$gw" "$gerr" "$TSGO_VER"
    printf '    %-12s %8s ms  %4s errors  (%s)\n' tsc "$tw" "$terr" "$TSC_VER"
done
