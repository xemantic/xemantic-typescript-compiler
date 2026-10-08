#!/usr/bin/env python3
"""The (TSGO.5) command-line recording (docs/goport-cli.md).

Runs the SHIPPED tsgo binary (`tools/tsgo-7.0.2/lib/tsc`) as a user would — a fresh process per case,
stdout piped (so not a terminal), a fixed environment — and records what it prints, its exit status and,
for a case run in a scratch COPY of its project, every file it writes:

    build/goport/cli-oracle/cases.json         the cases (name, args, cwd, copy, env), read by CliParityTest
    build/goport/cli-oracle/<name>/stdout.txt  stdout, the copy's absolute path replaced by {WORK}
    build/goport/cli-oracle/<name>/result.json {"exit": N, "stderr": "...", "files": {relpath: sha256}}
    build/goport/cli-projects/<fixture>/       small generated projects (config errors, loose files, …)

The port's CliParityTest (TSGO_CLI=1) runs every case through `TsgoCli.run` in process — same arguments,
same working directory, same environment, the same default-library directory (the binary's own `lib`) —
and fails on any difference.

A case with "copy" runs in build/goport/cli-work/oracle/<name> (a copy of that directory, `.git`, `dist`,
and `node_modules` excluded — node_modules is a symbolic link to the original's), and its "files" are the
files the run created or changed there. The test copies into build/goport/cli-work/kotlin/<name>: the two
work directories have the same depth, so a path printed relative to the working directory — even one that
leaves the copy through the node_modules link — reads the same in both.

Projects: the 8 tsc profiles (build/bench/tsc-*; REFUSES fewer than 8), the (P18.265) census libraries,
cronstrue (`…/cronstrue/src`), marked, and a sample of the type-oracle projects (build/goport/api-projects),
plus command-line cases (flags, config errors, a missing project, --version/--help/--init/--showConfig).

Usage: scripts/tsgo-cli-oracle.py [--only NAME,...] [--list] [--api-sample N]
"""

import argparse
import concurrent.futures as cf
import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TSC = os.path.realpath(os.path.join(REPO, "tools/tsgo-7.0.2/lib/tsc"))
OUT = os.path.join(REPO, "build/goport/cli-oracle")
WORK = os.path.join(REPO, "build/goport/cli-work/oracle")
FIXTURES = os.path.join(REPO, "build/goport/cli-projects")


def main_tree():
    """The repository holding build/bench and the scratch libraries (a worktree links them from the main tree)."""
    d = REPO
    while True:
        if glob.glob(os.path.join(d, "build/bench/tsc-*")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            return REPO
        d = parent


DATA = main_tree()
BENCH = os.path.join(DATA, "build/bench")
CENSUS = os.path.join(DATA, "build/scratch-p18265-census")
CRONSTRUE = os.path.join(DATA, "build/scratch-p18171/libs/cronstrue/src")
MARKED = os.path.join(DATA, "build/bench/inc50-scratch-marked")
API_PROJECTS = os.path.join(REPO, "build/goport/api-projects")
if not os.path.isdir(API_PROJECTS):
    API_PROJECTS = os.path.join(DATA, "build/goport/api-projects")

CENSUS_LIBS = [("mitt", "mitt"), ("superstruct", "superstruct"), ("immer", "immer"), ("ky", "ky"), ("hono", "hono"),
               ("datefns", "date-fns/pkgs/core"), ("zod", "zod/packages/zod"), ("typefest", "type-fest")]
# Libraries small enough to copy for an emit arm (node_modules is linked, never copied).
EMIT_LIBS = {"mitt", "superstruct", "ky", "zod"}

# GOMEMLIMIT is the Go runtime's soft heap limit: it changes when tsgo collects, never what it prints (type-fest
# otherwise peaks past what a shared box leaves free and is OOM-killed).
ENV = {"PATH": "/usr/bin:/bin", "HOME": "/nonexistent-home", "LANG": "C.UTF-8", "GOMEMLIMIT": "2500MiB"}

FIXTURE_FILES = {
    "loose/a.ts": "export const x: number = 'not a number';\nexport function f(a: string) { return a.length; }\n",
    "loose/b.ts": "import { f } from './a';\nconst n: string = f('x');\n",
    "bad-json/tsconfig.json": '{\n  "compilerOptions": {\n    "strict": true,\n  ,\n}\n',
    "bad-json/a.ts": "export const a = 1;\n",
    "bad-option/tsconfig.json": '{\n  "compilerOptions": {\n    "notAnOption": true,\n    "target": "es2020"\n  }\n}\n',
    "bad-option/a.ts": "export const a: string = 1;\n",
    "clean/tsconfig.json": '{\n  "compilerOptions": {\n    "strict": true,\n    "target": "es2022",\n    "module": "nodenext",\n'
                           '    "declaration": true,\n    "sourceMap": true,\n    "outDir": "out"\n  }\n}\n',
    "clean/src/index.ts": "import { greet } from './util.js';\nexport const message: string = greet('world');\n",
    "clean/src/util.ts": "/** Says hello. */\nexport function greet(name: string): string {\n    return `hello ${name}`;\n}\n",
    "nested/sub/dir/keep.txt": "",
    "nested/tsconfig.json": '{\n  "compilerOptions": { "strict": true, "noEmit": true }\n}\n',
    "nested/x.ts": "let v: number = 'x';\nv.toFixed();\nconst o = { a: 1 };\no.b;\n",
    "empty/.keep": "",
    "unicode/tsconfig.json": '{ "compilerOptions": { "strict": true } }\n',
    "unicode/été.ts": "const café: number = '☃';\n",
}


def write_fixtures():
    for rel, text in FIXTURE_FILES.items():
        p = os.path.join(FIXTURES, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8", newline="") as f:
            f.write(text)


def profiles():
    dirs = sorted(d for d in glob.glob(os.path.join(BENCH, "tsc-*")) if os.path.isfile(os.path.join(d, "tsconfig.json")))
    if len(dirs) < 8:
        sys.exit(f"REFUSED: found {len(dirs)} tsc profiles with a tsconfig.json under {BENCH}, need 8")
    return dirs


def build_cases(api_sample):
    cases = []

    def case(name, args, cwd=None, copy=None, env=None):
        cases.append({"name": name, "args": args, "cwd": cwd or ".", "copy": copy, "env": env or {}})

    for d in profiles():
        short = os.path.basename(d).split("-")[1]
        case(f"profile-{short}-noemit", ["--noEmit", "-p", d, "--pretty", "false"], cwd=d)
        case(f"profile-{short}-emit", ["-p", "."], copy=d)
    # the tsconfig's own `"pretty": true` (colours, code frames, the error summary), and a relative cwd
    p = [d for d in profiles() if os.path.basename(d).startswith("tsc-project-")][0]
    case("profile-compiler-pretty", ["--noEmit", "-p", p], cwd=p)
    case("profile-harness-pretty", ["--noEmit", "-p", [d for d in profiles() if "harness" in d][0]], cwd=REPO)
    for name, rel in CENSUS_LIBS:
        d = os.path.join(CENSUS, rel)
        # type-fest's whole-program check peaks past what a shared box has free when tsgo checks in
        # parallel (the binary is OOM-killed): single-threaded, as tsc's own output does not depend on it.
        extra = ["--singleThreaded"] if name == "typefest" else []
        case(f"lib-{name}-noemit", ["--noEmit", "-p", d, "--pretty", "false"] + extra, cwd=d)
        if name in EMIT_LIBS:
            case(f"lib-{name}-emit", ["-p", ".", "--pretty", "false"], copy=d)
    case("lib-cronstrue-noemit", ["--noEmit", "-p", CRONSTRUE, "--pretty", "false"], cwd=CRONSTRUE)
    case("lib-cronstrue-emit", ["-p", "."], copy=CRONSTRUE)
    case("lib-marked-noemit", ["--noEmit", "-p", MARKED, "--pretty", "false"], cwd=MARKED)
    case("lib-marked-emit", ["-p", "."], copy=MARKED)
    api = sorted(d for d in glob.glob(os.path.join(API_PROJECTS, "conf-*")) if os.path.isfile(os.path.join(d, "tsconfig.json")))
    step = max(1, len(api) // api_sample) if api_sample else len(api) + 1
    for d in api[::step][:api_sample]:
        n = os.path.basename(d)
        case(f"api-{n}-noemit", ["--noEmit", "-p", d], cwd=d)
        case(f"api-{n}-emit", ["-p", "."], copy=d)

    fx = lambda rel: os.path.join(FIXTURES, rel)
    # flags and command-line shapes
    case("cli-version", ["--version"], cwd=fx("empty"))
    case("cli-version-short", ["-v"], cwd=fx("empty"))
    case("cli-help", ["--help"], cwd=fx("empty"))
    case("cli-help-all", ["--all"], cwd=fx("empty"))
    case("cli-no-config", [], cwd=fx("empty"))
    case("cli-missing-project", ["-p", "/nonexistent/project"], cwd=fx("empty"))
    case("cli-missing-config-in-dir", ["-p", fx("empty")], cwd=fx("empty"))
    case("cli-unknown-flag", ["--notAFlag", "-p", fx("clean")], cwd=fx("empty"))
    case("cli-bad-flag-value", ["--target", "es1999", "-p", fx("clean")], cwd=fx("empty"))
    case("cli-project-and-files", ["-p", fx("clean"), "x.ts"], cwd=fx("empty"))
    case("cli-config-bad-json", ["-p", fx("bad-json")], cwd=fx("bad-json"))
    case("cli-config-bad-option", ["-p", fx("bad-option"), "--noEmit"], cwd=fx("bad-option"))
    case("cli-config-file-path", ["-p", fx("clean/tsconfig.json"), "--noEmit"], cwd=fx("clean"))
    case("cli-find-config-upwards", [], cwd=fx("nested/sub/dir"))
    case("cli-nested-pretty-forced", ["--pretty", "true"], cwd=fx("nested"))
    case("cli-nested-no-color", ["--pretty", "true"], cwd=fx("nested"), env={"NO_COLOR": "1"})
    case("cli-show-config", ["--showConfig", "-p", fx("clean")], cwd=fx("clean"))
    case("cli-list-files", ["--listFiles", "--noEmit", "-p", fx("clean")], cwd=fx("clean"))
    case("cli-list-files-only", ["--listFilesOnly", "-p", fx("clean")], cwd=fx("clean"))
    case("cli-explain-files", ["--explainFiles", "--noEmit", "-p", fx("clean")], cwd=fx("clean"))
    case("cli-loose-files-noemit", ["--noEmit", "a.ts", "b.ts"], cwd=fx("loose"))
    case("cli-loose-files-emit", ["--strict", "a.ts", "b.ts"], copy=fx("loose"))
    case("cli-loose-files-outdir", ["--outDir", "built", "--declaration", "a.ts", "b.ts"], copy=fx("loose"))
    case("cli-loose-ignore-config", ["--ignoreConfig", "--noEmit", "x.ts"], cwd=fx("nested"))
    case("cli-loose-with-config", ["x.ts"], cwd=fx("nested"))
    case("cli-clean-emit", ["-p", "."], copy=fx("clean"))
    case("cli-clean-emit-listed", ["-p", ".", "--listEmittedFiles"], copy=fx("clean"))
    case("cli-clean-incremental", ["-p", ".", "--incremental"], copy=fx("clean"))
    case("cli-clean-noemit-errors", ["-p", ".", "--noEmitOnError", "--strict"], copy=fx("loose"))
    case("cli-init", ["--init"], copy=fx("empty"))
    case("cli-locale", ["--locale", "de", "--noEmit"], cwd=fx("nested"))
    case("cli-unicode", ["--noEmit", "-p", "."], cwd=fx("unicode"))
    # `--build` and `--watch` are not ported yet (docs/goport-cli.md § 4): no case; TsgoCliTest pins the refusal.
    return cases


def snapshot(root):
    out = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if not os.path.islink(os.path.join(dirpath, d))]
        for f in filenames:
            p = os.path.join(dirpath, f)
            if os.path.islink(p):
                continue
            with open(p, "rb") as fh:
                out[os.path.relpath(p, root)] = hashlib.sha256(fh.read()).hexdigest()
    return out


def prepare_copy(src, dst):
    if os.path.exists(dst):
        shutil.rmtree(dst)
    shutil.copytree(src, dst, symlinks=True, ignore=shutil.ignore_patterns(".git", "dist", "node_modules"))
    if os.path.isdir(os.path.join(src, "node_modules")):
        os.symlink(os.path.join(src, "node_modules"), os.path.join(dst, "node_modules"))


def run_case(c):
    work = None
    if c["copy"]:
        work = os.path.join(WORK, c["name"])
        prepare_copy(c["copy"], work)
        cwd = os.path.join(work, c["cwd"])
        before = snapshot(work)
    else:
        cwd = c["cwd"]
    env = dict(ENV)
    env.update(c["env"])
    p = subprocess.run([TSC] + c["args"], cwd=cwd, env=env, capture_output=True, timeout=1800)
    stdout = p.stdout
    files = {}
    if work:
        stdout = stdout.replace(work.encode(), b"{WORK}")
        after = snapshot(work)
        files = {k: v for k, v in sorted(after.items()) if before.get(k) != v}
    d = os.path.join(OUT, c["name"])
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "stdout.txt"), "wb") as f:
        f.write(stdout)
    with open(os.path.join(d, "result.json"), "w") as f:
        json.dump({"exit": p.returncode, "stderr": p.stderr.decode("utf-8", "replace"), "files": files}, f, indent=1, sort_keys=True)
    return c["name"], p.returncode, len(stdout), len(files)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--api-sample", type=int, default=20)
    ap.add_argument("--workers", type=int, default=4)
    a = ap.parse_args()
    if not os.access(TSC, os.X_OK):
        sys.exit(f"REFUSED: no tsgo binary at {TSC}")
    write_fixtures()
    cases = build_cases(a.api_sample)
    names = [c["name"] for c in cases]
    assert len(names) == len(set(names)), "duplicate case names"
    if a.list:
        print("\n".join(names))
        return
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "cases.json"), "w") as f:
        json.dump({"tsc": TSC, "libDir": os.path.dirname(TSC), "work": "build/goport/cli-work/kotlin", "cases": cases}, f, indent=1)
    # The same cases for the Kotlin test, one per line: name, cwd, copy ("" = none), args and env (KEY=VALUE),
    # tab-separated; a list's items are separated by \x1f.
    with open(os.path.join(OUT, "cases.tsv"), "w", encoding="utf-8") as f:
        f.write(f"#libDir\t{os.path.dirname(TSC)}\n")
        for c in cases:
            f.write("\t".join([c["name"], c["cwd"], c["copy"] or "", "\x1f".join(c["args"]),
                               "\x1f".join(f"{k}={v}" for k, v in sorted(c["env"].items()))]) + "\n")
    only = set(filter(None, a.only.split(",")))
    todo = [c for c in cases if not only or c["name"] in only]
    killed = []
    with cf.ThreadPoolExecutor(a.workers) as ex:
        for name, code, n, files in ex.map(run_case, todo):
            print(f"{name:40s} exit={code} stdout={n}B files={files}")
            if code < 0:
                killed.append(name)
    print(f"recorded {len(todo)} of {len(cases)} cases -> {OUT}")
    if killed:
        sys.exit(f"FAILED: the binary was killed by a signal in {killed} (out of memory?) — re-record with fewer --workers")


if __name__ == "__main__":
    main()
