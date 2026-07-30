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
# All three compile the SAME materialized project (the `compiler` profile that
# scripts/bench-compile-tsc.sh builds from typescript-repo @ typeScriptCommit),
# cold single process. Wall-clock is the comparable metric (xtsc also reports its
# self-time, which excludes JVM startup/JIT).
#
# TWO MODES, both measured, both like-for-like (round 739 — queue item (BENCH.1)):
#
#   check-only  every compiler runs with --noEmit / --no-emit: nothing is
#               transformed, emitted or written. THIS is the mode the whole perf
#               arc profiles (scripts/ab-interleaved.sh and scripts/cost_gate.py
#               both pass --noEmit), so it is the mode a perf claim must quote.
#   emit        every compiler type-checks AND emits JavaScript to an outDir.
#
# Until round 739 only the emit mode was measured, and until round 738 xtsc's
# --noEmit still ran the Transformer + Emitter and discarded the result — so a
# check-only ratio had never been measured on either side. Do not compare a row
# of one mode against a row of the other.
#
# Usage: scripts/bench-3way.sh --tsc PATH --tsgo PATH [options]
#   --tsc PATH        reference JS `tsc` binary (required)
#   --tsgo PATH       native `tsgo` binary (required)
#   --xtsc-native P   OPTIONAL ahead-of-time xtsc binary (./gradlew nativeImage ->
#                     build/native/xtsc). When given, it is measured as a FOURTH
#                     compiler in every mode. Omit it and the run is unchanged.
#                     Round 771: ~1.97x the JVM cold, output byte-identical on all
#                     8 profiles — see docs/perf/aot-native-image.md.
#   --project NAME    tsc subproject profile (default compiler)
#   --xtsc-iters N    measured xtsc runs, median (default 1; ~30s each in CI)
#   --ref-iters N     measured tsc/tsgo runs, median (default 3; cheap)
#   --modes LIST      comma-separated subset of check-only,emit (default both)
#   --out-dir DIR     bench-history root (default bench-history)
#   --label TEXT      free-text note stored in the report
#   --help            show this help

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TSC_BIN=""; TSGO_BIN=""; XTSC_NATIVE=""; PROJECT=compiler
XTSC_ITERS=1; REF_ITERS=3
MODES="check-only,emit"
OUT_DIR="$REPO_ROOT/bench-history"; LABEL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tsc)        TSC_BIN="$2"; shift 2 ;;
        --tsgo)       TSGO_BIN="$2"; shift 2 ;;
        --xtsc-native) XTSC_NATIVE="$2"; shift 2 ;;
        --project)    PROJECT="$2"; shift 2 ;;
        --xtsc-iters) XTSC_ITERS="$2"; shift 2 ;;
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

# `tsc --version` prints "Version 6.0.3"; tsgo prints "Version 7.0.0-dev.NNN".
TSC_VER="$("$TSC_BIN"  --version 2>/dev/null | awk '{print $NF}' || echo '?')"
TSGO_VER="$("$TSGO_BIN" --version 2>/dev/null | awk '{print $NF}' || echo '?')"
[[ -n "$TSC_VER"  ]] || TSC_VER='?'
[[ -n "$TSGO_VER" ]] || TSGO_VER='?'
strip_ansi() { sed -E $'s/\x1b\\[[0-9;]*m//g'; }

median() { printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END{ if(NR%2) print a[(NR+1)/2]; else print int((a[NR/2]+a[NR/2+1])/2) }'; }

# --------------------------------------------------------------------------
# 1. xtsc: reuse bench-compile-tsc.sh (materializes the project, builds the
#    compiler, resolves the classpath, runs MainKt). Parse its summary.
# --------------------------------------------------------------------------
TS_COMMIT8="$(git -C "$REPO_ROOT/typescript-repo" rev-parse HEAD | cut -c1-8)"
if [[ "$PROJECT" == compiler ]]; then PROJ_DIR="$REPO_ROOT/build/bench/tsc-project-$TS_COMMIT8"
else PROJ_DIR="$REPO_ROOT/build/bench/tsc-$PROJECT-$TS_COMMIT8"; fi

run_xtsc() { # $1=mode ; sets xtsc_wall xtsc_self xtsc_err xtsc_files LOC
    local mode="$1" xlog extra=()
    [[ "$mode" == check-only ]] && extra=(--no-emit)
    echo "== xtsc ($mode) =="
    xlog="$(mktemp)"
    "$REPO_ROOT/scripts/bench-compile-tsc.sh" --project "$PROJECT" --iterations "$XTSC_ITERS" \
        --no-log ${extra[@]+"${extra[@]}"} | tee "$xlog" >&2 || { echo "xtsc bench failed" >&2; exit 1; }

    # xtsc self/errors/files come from the MainKt RUN LOG (printed by the compiler
    # itself, so correct on any OS — the bench summary's own stats are 0 on macOS
    # BSD grep). wall and LOC come from the summary (date / wc, both fine).
    # LOC is read off the `input:` line specifically: a bare `[0-9]+ LOC` also
    # matches the `throughput: N LOC/s` line, and taking the LAST match silently
    # published THROUGHPUT as the LOC count in every report before round 739.
    xtsc_wall=$(sed -n 's/^compile:.*), *\([0-9]*\) ms wall$/\1/p' "$xlog" | head -1)
    LOC=$(sed -n 's/^input:.*, \([0-9]*\) LOC$/\1/p' "$xlog" | head -1)
    [[ -n "$xtsc_wall" ]] || xtsc_wall=0
    [[ -n "$LOC" ]] || LOC=0
    local runlog
    runlog=$(grep -oE 'full output: .*' "$xlog" | head -1 | sed 's/^full output: //' || true)
    if [[ -n "$runlog" && -f "$runlog" ]]; then
        xtsc_self=$(grep -oE '^time:\s+[0-9]+' "$runlog" | grep -oE '[0-9]+' | head -1 || echo 0)
        xtsc_err=$(grep -oE 'diagnostics:\s+[0-9]+ error' "$runlog" | grep -oE '[0-9]+' | head -1 || echo 0)
        xtsc_files=$(grep -oE '[0-9]+ in program' "$runlog" | grep -oE '[0-9]+' | head -1 || echo 0)
    else
        xtsc_self=0; xtsc_err=0; xtsc_files=0
    fi
    rm -f "$xlog"
}

# --------------------------------------------------------------------------
# 1b. The ahead-of-time xtsc binary (optional), on the SAME project and mode.
#
# Uses xtsc's OWN CLI rather than bench-compile-tsc.sh, which drives `java`.
# Emit mode needs no --outDir: xtsc writes to the outDir the materialized
# tsconfig already sets, exactly as the JVM arm does.
# --------------------------------------------------------------------------
run_native() { # $1=mode ; sets nat_wall nat_self nat_err
    local mode="$1" walls=() selfs=() out start end i
    nat_wall=0; nat_self=0; nat_err=0
    [[ -n "$XTSC_NATIVE" ]] || return 0
    echo "== xtsc-native ($mode) =="
    for ((i=1;i<=XTSC_ITERS;i++)); do
        out="$(mktemp)"
        start=$(date +%s%N)
        if [[ "$mode" == check-only ]]; then
            "$XTSC_NATIVE" --noEmit "$PROJ_DIR" >"$out" 2>&1 || true
        else
            "$XTSC_NATIVE" "$PROJ_DIR" >"$out" 2>&1 || true
        fi
        end=$(date +%s%N)
        walls+=($(( (end-start)/1000000 )))
        # Self time and errors come from the compiler's own output, so they are
        # correct on any OS (the round-730 macOS BSD-grep trap hits parsed stats,
        # not these).
        selfs+=($(grep -oE '^time:[[:space:]]+[0-9]+' "$out" | grep -oE '[0-9]+' | head -1 || echo 0))
        nat_err=$(grep -oE '[0-9]+ error\(s\)' "$out" | grep -oE '[0-9]+' | head -1 || echo 0)
        rm -f "$out"
    done
    nat_wall=$(median "${walls[@]}")
    nat_self=$(median "${selfs[@]}")
}

# --------------------------------------------------------------------------
# 2. tsc / tsgo on the SAME materialized project, in the SAME mode.
# --------------------------------------------------------------------------
run_ref() { # $1=bin  $2=mode ; echoes "wall_ms err"
    local bin="$1" mode="$2" walls=() out i start end err=0
    for ((i=1;i<=REF_ITERS;i++)); do
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

# Measure every requested mode; MODE_DATA rows are consumed by the reporter below.
MODE_DATA=()
for MODE in "${MODE_LIST[@]}"; do
    run_xtsc "$MODE"
    echo "== tsc ($TSC_VER, $MODE) ==";   read -r tsc_wall  tsc_err  < <(run_ref "$TSC_BIN"  "$MODE")
    echo "== tsgo ($TSGO_VER, $MODE) =="; read -r tsgo_wall tsgo_err < <(run_ref "$TSGO_BIN" "$MODE")
    run_native "$MODE"
    MODE_DATA+=("$MODE $xtsc_wall $xtsc_self $xtsc_err $tsc_wall $tsc_err $tsgo_wall $tsgo_err $nat_wall $nat_self $nat_err")
done

# --------------------------------------------------------------------------
# 3. Render the per-run report + update the index.
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
export B_TS_COMMIT="$TS_COMMIT8" B_FILES="$xtsc_files" B_LOC="$LOC"
export B_TSC_VER="$TSC_VER" B_TSGO_VER="$TSGO_VER"
export B_XITERS="$XTSC_ITERS" B_REFITERS="$REF_ITERS"
B_MODE_DATA="$(printf '%s\n' "${MODE_DATA[@]}")"; export B_MODE_DATA

python3 - <<'PYEOF'
import os, re
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

# mode xtsc_wall xtsc_self xtsc_err tsc_wall tsc_err tsgo_wall tsgo_err
#      nat_wall nat_self nat_err   (nat_* are 0 when --xtsc-native was not passed)
modes = []
for line in g("B_MODE_DATA").splitlines():
    p = line.split()
    if len(p) == 11:
        modes.append((p[0],) + tuple(int(x) for x in p[1:]))

def sec(ms): return f"{ms/1000:.2f}s" if ms else "n/a"
def tput(ms): return f"{loc*1000//ms:,}" if ms and loc else "n/a"
def ratio(a, b): return f"{a/b:.2f}×" if b else "n/a"

date_h = f"{stamp[0:4]}-{stamp[4:6]}-{stamp[6:8]}"
commit_url = f"https://github.com/{slug}/commit/{rev_full}"
MODE_DOC = {
    "check-only": "every compiler runs with `--noEmit`: type-check only, nothing "
                  "transformed, emitted or written. **This is the mode the perf arc "
                  "profiles** (`ab-interleaved.sh` / `cost_gate.py` both pass `--noEmit`).",
    "emit":       "every compiler type-checks AND emits JavaScript to an `outDir`.",
}

# ---- per-run report ----
lines = [
    f"# Bench run {stamp}",
    "",
    f"- **xtsc revision**: [`{rev}`]({commit_url})",
    f"- **TypeScript pin**: `{ts_commit}` (typeScriptCommit)",
    f"- **Profile**: `{project}` — {files} files, {loc:,} LOC",
    f"- **Runner**: {runner}",
    f"- **Method**: cold single process; wall-clock is the comparable metric. "
    f"xtsc = median of {xiters}, tsc/tsgo = median of {refiters}.",
]
if label:
    lines.append(f"- **Label**: {label}")

for mode, xw, xself, xerr, tw, terr, gw, gerr, nw, nself, nerr in modes:
    lines += [
        "",
        f"## {mode}",
        "",
        MODE_DOC.get(mode, ""),
        "",
        "| Compiler | Version | Wall | Throughput | Errors |",
        "|---|---|---:|---:|---:|",
        f"| xtsc | {rev} | {sec(xw)} | {tput(xw)} LOC/s | {xerr} |",
        f"| tsc | {tsc_ver} | {sec(tw)} | {tput(tw)} LOC/s | {terr} |",
        f"| tsgo | {tsgo_ver} | {sec(gw)} | {tput(gw)} LOC/s | {gerr} |",
    ]
    if nw:
        lines.append(f"| xtsc-native | {rev} (AOT) | {sec(nw)} | {tput(nw)} LOC/s | {nerr} |")
    lines += [
        "",
        f"Relative (wall): xtsc is **{ratio(xw,tw)}** tsc and **{ratio(xw,gw)}** tsgo; "
        f"tsc is **{ratio(tw,gw)}** tsgo. "
        f"xtsc self-reported time (excl. JVM startup/JIT): **{sec(xself)}**.",
    ]
    if nw:
        lines += [
            "",
            f"Ahead-of-time (GraalVM native-image): **{sec(nw)}**, "
            f"**{ratio(xw,nw)}** the JVM arm, **{ratio(nw,tw)}** tsc, **{ratio(nw,gw)}** tsgo. "
            f"Same compiler, same output — the JVM arm's extra time is warm-up a "
            f"one-shot CLI run never amortizes (docs/perf/aot-native-image.md).",
        ]
lines += [
    "",
    "> Compare a ratio only against a ratio of the SAME mode. A single xtsc run "
    "carries the full spread of one cold JVM (the per-run ratio has ranged 1.9–2.7 "
    "on an unchanged compiler), so read the median across runs, not one row.",
    "",
    "> Errors are all env-legit offline `@types/node` artifacts (no `node_modules/@types` "
    "in CI); real tsc reports 0 on its own source. Counts differ slightly by how each "
    "compiler models the missing ambient env, not by real diagnostics.",
    "",
]
run_name = f"{stamp}-{rev}.md"
with open(os.path.join(runs_dir, run_name), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(lines))

# ---- index (prepend newest rows, one per mode) ----
# Rows live between explicit markers so the pre-739 single-mode archive below
# them is never rewritten (its columns differ — see the README's archive note).
index = os.path.join(out_dir, "README.md")
START, END = "<!-- BENCH-ROWS-START -->", "<!-- BENCH-ROWS-END -->"
# The AOT columns are APPENDED rather than inserted: rows written before round
# 772 have 10 cells, and Markdown renders the missing trailing cells as empty,
# so every historical row stays under the headers it was written for. Inserting
# mid-table would silently shift ~341 archived rows by one column.
col = ("| Date | xtsc rev | Mode | xtsc | tsc | tsgo | xtsc/tsc | xtsc/tsgo | xtsc err | Run "
       "| xtsc-nat | nat/tsc | nat/tsgo |\n"
       "|---|---|---|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|\n")
new_rows = "".join(
    f"| {date_h} | [`{rev}`]({commit_url}) | {mode} | {sec(xw)} | {sec(tw)} | {sec(gw)} "
    f"| {ratio(xw,tw)} | {ratio(xw,gw)} | {xerr} | [report](runs/{run_name}) "
    f"| {sec(nw) if nw else '—'} | {ratio(nw,tw) if nw else '—'} | {ratio(nw,gw) if nw else '—'} |\n"
    for mode, xw, xself, xerr, tw, terr, gw, gerr, nw, nself, nerr in modes)

txt = open(index, encoding="utf-8").read() if os.path.exists(index) else ""
if START in txt and END in txt:
    head, rest = txt.split(START, 1)
    body, tail = rest.split(END, 1)
    body_rows = "".join(l + "\n" for l in body.splitlines()
                        if l.startswith("| 2"))          # data rows only
    txt = head + START + "\n\n" + col + new_rows + body_rows + "\n" + END + tail
else:  # bootstrap: keep whatever is already there as the archive
    txt = ("# Bench history\n\n"
           + START + "\n\n" + col + new_rows + "\n" + END + "\n\n" + txt)
with open(index, "w", encoding="utf-8", newline="\n") as f:
    f.write(txt)

print(f"wrote {os.path.join('runs', run_name)} and updated index")
PYEOF

echo
echo "=== 3-way bench ($PROJECT @ $TS_COMMIT8) ==="
for row in "${MODE_DATA[@]}"; do
    read -r m xw xs xe tw te gw ge nw ns ne <<<"$row"
    echo "  -- $m --"
    printf '    %-6s %8s ms wall  %6s errors\n' xtsc "$xw" "$xe"
    printf '    %-6s %8s ms wall  %6s errors  (%s)\n' tsc  "$tw" "$te" "$TSC_VER"
    printf '    %-6s %8s ms wall  %6s errors  (%s)\n' tsgo "$gw" "$ge" "$TSGO_VER"
    if [[ "$nw" != 0 ]]; then
        printf '    %-6s %8s ms wall  %6s errors  (AOT)\n' xtsc-n "$nw" "$ne"
    fi
done
