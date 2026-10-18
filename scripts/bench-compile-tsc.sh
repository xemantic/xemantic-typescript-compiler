#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
# xemantic-typescript-compiler - a conformant TypeScript compiler and type
# checker that runs on JVM, native, and WebAssembly
# Copyright (C) 2026 Kazimierz Pogoda / Xemantic
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as
# published by the Free Software Foundation, version 3 of the License.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public
# License along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
# As a special exception, this file contains Helper Code covered by the
# xemantic-typescript-compiler Output Exception; additional permissions
# are granted as described in the file LICENSE-EXCEPTION.
#

# THE BENCH PROFILES' MATERIALIZER: since (TSGO.8-e) only `--materialize-only` runs (the timing arm
# below measured `-core`, which was deleted; scripts/bench-3way.sh times the port). The rest of this
# header describes the profiles, which are unchanged.
#
# Performance benchmark: compile the TypeScript compiler with THIS compiler.
#
# Compiles subprojects of TypeScript's own source tree — taken from the
# `typescript-repo` clone, which is PINNED to the exact commit the test corpus
# tracks (`typeScriptCommit` in build.gradle.kts) — using the xtsc
# whole-project CLI (`MainKt`) on the JVM.
#
# Available --project profiles (each is the named src/ dir plus the transitive
# closure of its tsconfig `references`, compiled as ONE program — we do not
# model project references, so the reference graph is flattened):
#
#   compiler              src/compiler                          (~77 files, default)
#   tsc                   + src/tsc                             (~79 files)
#   jsTyping              + src/jsTyping                        (~83 files)
#   deprecatedCompat      + src/deprecatedCompat                (~80 files)
#   typingsInstallerCore  + jsTyping + typingsInstallerCore     (~87 files)
#   services              + jsTyping + services                 (~251 files)
#   server                + jsTyping + tic + services + dc + server    (~273 files)
#   harness               + all of the above + harness          (~311 files)
#   all                   run every profile above in sequence
#
# Each run reports and logs:
#   - self-reported compile time (ProjectCompiler.build, excludes JVM startup)
#   - wall-clock time of the java process (cold-CLI experience, includes JIT)
#   - peak RSS (when GNU time is available)
#   - diagnostic counts, total + by-code breakdown (compliance drift signal:
#     real tsc reports 0 errors on its own source, so every error we emit is
#     a false positive or a modeling gap — the count should only go down)
#
# Results are appended per project — bench/self-compile-tsc.tsv for the
# `compiler` profile (historical name), bench/self-compile-<project>.tsv for
# the rest (one row per invocation, keyed by the xtsc git revision), so
# performance and compliance can be compared across compiler changes:
#
#   scripts/bench-compile-tsc.sh --label "before checker change"
#   ... hack hack hack ...
#   scripts/bench-compile-tsc.sh --label "after checker change"
#
# NOTE: this measures the cold single-shot CLI (fresh JVM per run — JIT warm-up
# is part of the measurement, matching what a CLI user experiences). For warm
# in-process rebuild benchmarking use src/commonTest/kotlin/BenchMain.kt.
#
# Usage: scripts/bench-compile-tsc.sh [options]
#   --project NAME   which tsc subproject to compile (see profiles above;
#                    comma-separated list or "all" accepted; default compiler)
#   --iterations N   measured runs, median reported (default 1; ~4-5 min each
#                    for the compiler profile as of 2026-07)
#   --warmup N       unmeasured warm-up runs before measuring (default 0)
#   --heap SIZE      JVM -Xmx for the compiler process (default 4g)
#   --no-emit        type-check only (skip JS emit)
#   --node-stub      materialize a minimal @types/node stub (all `any`) and set
#                    tsconfig `types: ["node"]` — see the note above; the row's
#                    label is tagged "+node-stub" so TSV history stays comparable
#   --fresh          re-extract + regenerate the benchmark project even if a
#                    cached copy exists
#   --label TEXT     free-text note stored in the results log
#   --no-log         do not append to the bench/ results TSV
#   --materialize-only  write the profiles' projects under build/bench and exit:
#                    no compiler build, no run (the -tsgo native CI job checks
#                    them with tsgo and the port's image, not with -core)
#   --help           show this help
#
# Each benchmark project is materialized under build/bench/tsc-<name>-<commit8>/
# (`tsc-project-<commit8>` for the compiler profile — historical name; cheap to
# rebuild — safe to lose on `rm -rf build`):
#   - `git archive` of the profile's src/ dirs from typescript-repo's object
#     store (works with the sparse checkout; the object DB has the full tree)
#   - src/compiler/diagnosticInformationMap.generated.ts generated by an inline
#     Python port of TypeScript's scripts/processDiagnosticMessages.mjs (it is
#     a build artifact, not checked in — tsc's source does not parse without it)
#   - a merged tsconfig.json (their tsconfig-base minus composite /
#     emitDeclarationOnly / declaration / sourceMap, plus outDir=dist).
#     `types` is emptied by default: no real node_modules/@types is available
#     offline, so node/mocha/chai-typed code (server, harness) reports env-legit
#     diagnostics. Pass --node-stub to materialize a minimal any-typed
#     node_modules/@types/node stub + `types: ["node"]` (M1.3): it suppresses
#     the env-legit family (TS2591 require/process/Buffer + NodeJS.*) without
#     being able to add typed diagnostics. The no-stub run stays the honest
#     dashboard default until network provides the real @types/node.
#
# Prerequisite: typescript-repo cloned (the script runs
# `./gradlew cloneTypeScriptRepo` automatically when it is missing).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# The Gradle module holding the compiler. Every task invocation here must be
# QUALIFIED with it: an unqualified task name now runs in all three projects.
XTSC_MODULE="xemantic-typescript-compiler-tsgo"
cd "$REPO_ROOT"

PROJECT_ARG=compiler
ITERATIONS=1
WARMUP=0
HEAP=4g
NO_EMIT=0
NODE_STUB=0
FRESH=0
LABEL=""
DO_LOG=1
MATERIALIZE_ONLY=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --project)    PROJECT_ARG="$2"; shift 2 ;;
        --iterations) ITERATIONS="$2"; shift 2 ;;
        --warmup)     WARMUP="$2"; shift 2 ;;
        --heap)       HEAP="$2"; shift 2 ;;
        --no-emit)    NO_EMIT=1; shift ;;
        --node-stub)  NODE_STUB=1; shift ;;
        --fresh)      FRESH=1; shift ;;
        --label)      LABEL="$2"; shift 2 ;;
        --no-log)     DO_LOG=0; shift ;;
        --materialize-only) MATERIALIZE_ONLY=1; shift ;;
        --help|-h)    sed -n '/^# Performance benchmark/,/^set -euo/p' "$0" | sed '$d;s/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
done

# Keep TSV history self-describing: stub rows are tagged so a stub run is never
# mistaken for a no-stub compliance improvement.
[[ $NODE_STUB -eq 1 ]] && LABEL="${LABEL:+$LABEL }+node-stub"

command -v java >/dev/null || { echo "error: java not found on PATH" >&2; exit 1; }
command -v python3 >/dev/null || { echo "error: python3 not found on PATH" >&2; exit 1; }

BENCH_BUILD_DIR="$REPO_ROOT/build/bench"
RESULTS_DIR="$REPO_ROOT/bench"
mkdir -p "$BENCH_BUILD_DIR"

# ---------------------------------------------------------------------------
# Project profiles: src/ dirs per profile = the named dir plus the transitive
# closure of its tsconfig `references` (verified against the pinned commit's
# src/*/tsconfig.json). Compiled as one flat program.
# ---------------------------------------------------------------------------

ALL_PROJECTS=(compiler tsc jsTyping deprecatedCompat typingsInstallerCore services server harness)

project_dirs() {
    case "$1" in
        compiler)             echo "compiler" ;;
        tsc)                  echo "compiler tsc" ;;
        jsTyping)             echo "compiler jsTyping" ;;
        deprecatedCompat)     echo "compiler deprecatedCompat" ;;
        typingsInstallerCore) echo "compiler jsTyping typingsInstallerCore" ;;
        services)             echo "compiler jsTyping services" ;;
        server)               echo "compiler jsTyping typingsInstallerCore services deprecatedCompat server" ;;
        harness)              echo "compiler jsTyping typingsInstallerCore services deprecatedCompat server harness" ;;
        *) return 1 ;;
    esac
}

results_tsv_for() { # historical name for the compiler profile, per-project otherwise
    case "$1" in
        compiler) echo "$RESULTS_DIR/self-compile-tsc.tsv" ;;
        # The src/tsc CLI profile must NOT collide with the compiler profile's
        # historical file name above.
        tsc)      echo "$RESULTS_DIR/self-compile-tsc-cli.tsv" ;;
        *)        echo "$RESULTS_DIR/self-compile-$1.tsv" ;;
    esac
}

proj_dir_for() {
    if [[ "$1" == compiler ]]; then echo "$BENCH_BUILD_DIR/tsc-project-$TS_COMMIT8"
    else echo "$BENCH_BUILD_DIR/tsc-$1-$TS_COMMIT8"; fi
}

if [[ "$PROJECT_ARG" == all ]]; then
    PROJECTS=("${ALL_PROJECTS[@]}")
else
    IFS=',' read -r -a PROJECTS <<<"$PROJECT_ARG"
fi
for P in "${PROJECTS[@]}"; do
    project_dirs "$P" >/dev/null || {
        echo "error: unknown project '$P' (known: ${ALL_PROJECTS[*]}, or 'all')" >&2; exit 2; }
done

# ---------------------------------------------------------------------------
# 1. The TypeScript sources — pinned version via typescript-repo.
# ---------------------------------------------------------------------------

if [[ ! -e "$REPO_ROOT/typescript-repo/.git" ]]; then
    echo "typescript-repo missing — cloning via ./gradlew cloneTypeScriptRepo ..."
    "$REPO_ROOT/gradlew" -q cloneTypeScriptRepo
fi

TS_COMMIT="$(git -C "$REPO_ROOT/typescript-repo" rev-parse HEAD)"
TS_COMMIT8="${TS_COMMIT:0:8}"

materialize_project() { # $1 = project name; sets PROJ_DIR
    local project="$1"
    PROJ_DIR="$(proj_dir_for "$project")"

    if [[ $FRESH -eq 1 ]]; then
        rm -rf "$PROJ_DIR"
    fi
    # The generated diagnostics map marks a completed extraction (it is the last
    # artifact the expensive phase writes). tsconfig.json and the optional
    # @types/node stub are rewritten on EVERY invocation below, so --node-stub
    # can be toggled without --fresh.
    if [[ -f "$PROJ_DIR/src/compiler/diagnosticInformationMap.generated.ts" ]]; then
        write_project_config
        return 0
    fi

    local dirs paths=()
    dirs="$(project_dirs "$project")"
    for d in $dirs; do paths+=("src/$d"); done

    echo "materializing benchmark project at $PROJ_DIR (tsc @ $TS_COMMIT8: ${dirs// /, }) ..."
    rm -rf "$PROJ_DIR"
    mkdir -p "$PROJ_DIR"

    # Extract the profile's dirs from the object store (sparse checkout has no
    # working-tree copy, but `git archive HEAD` reads the full tree).
    git -C "$REPO_ROOT/typescript-repo" archive HEAD "${paths[@]}" | tar -x -C "$PROJ_DIR"

    # Generate diagnosticInformationMap.generated.ts — a faithful port of
    # typescript-repo/scripts/processDiagnosticMessages.mjs (buildInfoFileOutput,
    # convertPropertyName, createKey), which tsc's own build runs with Node.
    # Every profile includes src/compiler, so this always applies.
    python3 - "$PROJ_DIR/src/compiler/diagnosticMessages.json" \
              "$PROJ_DIR/src/compiler/diagnosticInformationMap.generated.ts" <<'PYEOF'
import json, re, sys

src, out = sys.argv[1], sys.argv[2]
with open(src, encoding="utf-8") as f:
    table = json.load(f)  # dict preserves JSON insertion order

def convert_property_name(orig):
    result = "".join(
        "_Asterisk" if ch == "*"
        else "_Slash" if ch == "/"
        else "_Colon" if ch == ":"
        else (ch if re.match(r"[A-Za-z0-9_]", ch) else "_")  # JS \w is ASCII
        for ch in orig
    )
    result = re.sub(r"_+", "_", result)         # collapse multi-underscores
    result = re.sub(r"^_(\D)", r"\1", result)   # strip leading _ unless digit follows
    result = re.sub(r"_$", "", result)          # strip trailing _
    return result

def js(v):  # render a JSON truthy marker the way a JS template literal would
    return "true" if v is True else json.dumps(v, ensure_ascii=False)

lines = [
    "// <auto-generated />",
    "// generated from './diagnosticMessages.json' by scripts/bench-compile-tsc.sh",
    "// (inline port of typescript-repo/scripts/processDiagnosticMessages.mjs)",
    "",
    'import { DiagnosticCategory, DiagnosticMessage } from "./types.js";',
    "",
    "function diag(code: number, category: DiagnosticCategory, key: string, message: string, reportsUnnecessary?: {}, elidedInCompatabilityPyramid?: boolean, reportsDeprecated?: {}): DiagnosticMessage {",
    "    return { code, category, key, message, reportsUnnecessary, elidedInCompatabilityPyramid, reportsDeprecated };",
    "}",
    "",
    "/** @internal */",
    "export const Diagnostics = {",
]
for name, d in table.items():
    prop = convert_property_name(name)
    key = prop[:100] + "_" + str(d["code"])
    arg_ru = f", /*reportsUnnecessary*/ {js(d['reportsUnnecessary'])}" if d.get("reportsUnnecessary") else ""
    arg_el = (
        ("" if d.get("reportsUnnecessary") else ", /*reportsUnnecessary*/ undefined")
        + f", /*elidedInCompatabilityPyramid*/ {js(d['elidedInCompatabilityPyramid'])}"
    ) if d.get("elidedInCompatabilityPyramid") else ""
    arg_rd = (
        ("" if arg_el else ", /*reportsUnnecessary*/ undefined, /*elidedInCompatabilityPyramid*/ undefined")
        + f", /*reportsDeprecated*/ {js(d['reportsDeprecated'])}"
    ) if d.get("reportsDeprecated") else ""
    lines.append(
        f'    {prop}: diag({d["code"]}, DiagnosticCategory.{d["category"]}, "{key}",'
        f' {json.dumps(name, ensure_ascii=False)}{arg_ru}{arg_el}{arg_rd}) as DiagnosticMessage,'
    )
lines.append("};")
lines.append("")
with open(out, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(lines))
print(f"  generated diagnosticInformationMap.generated.ts ({len(table)} diagnostics)")
PYEOF

    write_project_config
}

# Writes the merged tsconfig.json (+ the optional @types/node stub) into
# PROJ_DIR. Runs on every invocation — cheap, and it is what lets --node-stub
# toggle without re-extracting.
write_project_config() {
    local types_json='[]'
    if [[ $NODE_STUB -eq 1 ]]; then
        types_json='["node"]'
        mkdir -p "$PROJ_DIR/node_modules/@types/node"
        cat > "$PROJ_DIR/node_modules/@types/node/package.json" <<'PKGEOF'
{ "name": "@types/node", "version": "0.0.0-xtsc-stub", "types": "index.d.ts" }
PKGEOF
        # Minimal @types/node STUB — not the real package (offline environment):
        # just enough ambient surface for tsc's own sources, every type `any`, so
        # it can suppress the env-legit diagnostic family (TS2591 require/process/
        # Buffer/module + NodeJS.*) but never ADD a typed diagnostic. Body-less
        # `declare module "x";` makes each node builtin an UNTYPED runtime module
        # (imports type as `any`). Replace with the real @types/node when network
        # access is available.
        cat > "$PROJ_DIR/node_modules/@types/node/index.d.ts" <<'DTSEOF'
declare var process: any;
declare var global: any;
// Buffer is used in TYPE position too (sys.ts `let buffer: Buffer;`, harness
// `Buffer<ArrayBuffer>`), so it needs the lib wrapper-type shape — a var for the
// value side MERGED with a generic-tolerant interface for the type side.
interface Buffer<T = any> { [index: string]: any; }
declare var Buffer: any;
declare var require: any;
declare var module: any;
declare var exports: any;
declare var setImmediate: any;
declare var clearImmediate: any;
declare var __filename: any;
declare var __dirname: any;
declare namespace NodeJS {
    type ErrnoException = any;
    type Timeout = any;
    type Timer = any;
    type ReadableStream = any;
    type WritableStream = any;
    type ProcessEnv = any;
    type Process = any;
}
declare module "assert";
declare module "buffer";
declare module "child_process";
declare module "crypto";
declare module "events";
declare module "fs";
declare module "http";
declare module "https";
declare module "inspector";
declare module "module";
declare module "net";
declare module "os";
declare module "path";
declare module "perf_hooks";
declare module "readline";
declare module "stream";
declare module "string_decoder";
declare module "tty";
declare module "url";
declare module "util";
declare module "vm";
declare module "worker_threads";
declare module "zlib";
DTSEOF
    else
        rm -rf "$PROJ_DIR/node_modules/@types/node"
    fi

    # Merged tsconfig: typescript-repo/src/tsconfig-base.json + the subproject
    # tsconfig.json, minus the project-reference/declaration-only machinery
    # (composite, emitDeclarationOnly, declaration*, sourceMap,
    # isolatedDeclarations) so a plain JS emit is exercised; `types` is [] unless
    # --node-stub materialized the stub above (see header note).
    cat > "$PROJ_DIR/tsconfig.json" <<JSONEOF
{
    "compilerOptions": {
        "rootDir": "src",
        "outDir": "dist",

        "pretty": true,
        "lib": ["es2020"],
        "target": "es2020",
        "module": "NodeNext",
        "moduleResolution": "NodeNext",

        "strict": true,
        "strictBindCallApply": false,
        "useUnknownInCatchVariables": false,
        "noImplicitOverride": true,

        "skipLibCheck": true,

        "alwaysStrict": true,
        "preserveConstEnums": true,
        "newLine": "lf",

        "types": $types_json
    },
    "include": ["src/**/*"]
}
JSONEOF
}

# The Gradle init script scripts/lib/dep-classpath.sh resolves a runtime classpath through
# (kir-screen.sh / kir-profile.sh read kotlin-stdlib from it). Written on every run.
INIT_SCRIPT="$BENCH_BUILD_DIR/print-classpath.init.gradle.kts"
cat > "$INIT_SCRIPT" <<KTSEOF
// Generated by scripts/bench-compile-tsc.sh — do not edit.
// Registered ONLY in the compiler module. Under \`allprojects\` this would also
// hit the root (which has no jvmRuntimeClasspath, so the task throws) and
// -api (which has one, and would print a SECOND, wrong classpath whose
// ordering against the real one would silently decide the benchmark).
rootProject {
    // findProject, not project(): an init script applies to EVERY build in the
    // invocation, and \`build-logic\` has no such project — a hard lookup there
    // fails the whole run during plugin resolution.
    findProject(":$XTSC_MODULE")?.tasks?.register("xtscPrintJvmRuntimeClasspath") {
        doLast {
            val cp = project.configurations.getByName("jvmRuntimeClasspath")
                .resolve().joinToString(":") { it.absolutePath }
            println("XTSC_CLASSPATH=\$cp")
        }
    }
}
KTSEOF

if [[ $MATERIALIZE_ONLY -eq 1 ]]; then
    for P in "${PROJECTS[@]}"; do materialize_project "$P"; echo "$PROJ_DIR"; done
    exit 0
fi

# (TSGO.8-e) THE TIMING ARM IS GONE: it ran `-core`'s hand-written checker, which was deleted.
# The port is timed by scripts/bench-3way.sh (CI: bench.yml) and scripts/bench-jvms.sh, both of
# which materialize their projects through this script's --materialize-only.
echo "error: bench-compile-tsc.sh only materializes now (--materialize-only); time the port with scripts/bench-3way.sh" >&2
exit 2
