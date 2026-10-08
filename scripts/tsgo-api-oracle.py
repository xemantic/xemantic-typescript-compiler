#!/usr/bin/env python3
"""The (TSGO.3-b) type-oracle recording (docs/goport-api.md § 4).

Materializes the request corpus's projects under build/goport/api-projects/ and records, for each, the
shipped tsgo binary's API answers (`tools/tsgo-7.0.2/lib/tsc --api`) to every request the recorder
generates (`build/goport/bin/tsgo-oracle api`, oracle-go/api.go):

    build/goport/api-oracle/<name>.jsonl.gz      one {"m","p","r"|"e"} line per request
    build/goport/api-oracle/manifest.tsv         name, tsconfig, records, sha256 of the .jsonl.gz

The port's ApiParityTest (TSGO_API=1) replays every recording against the in-process session.

Projects:
  - tsc: a COPY of tsc's 78 sources and tsconfig (--tsc-src, default the bench profile);
  - conf-NNNN: --conformance N single-file conformance cases (no `@filename` units), every k-th of the
    sorted list under --cases, each a copy in its own directory with a tsconfig listing just it and
    tsgo's DEFAULT options (the case's `// @` directives stay comments: the gate is the type oracle on
    a valid program, not the harness's configuration).

Usage: scripts/tsgo-api-oracle.py [--tsc-src DIR] [--cases DIR] [--conformance N] [--workers N]
Build the recorder first: xemantic-typescript-compiler-goport/oracle-go/build.sh
"""

import argparse
import concurrent.futures as cf
import glob
import gzip
import hashlib
import json
import os
import shutil
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TSC = os.path.join(REPO, "tools/tsgo-7.0.2/lib/tsc")
RECORDER = os.path.join(REPO, "build/goport/bin/tsgo-oracle")
PROJECTS = os.path.join(REPO, "build/goport/api-projects")
OUT = os.path.join(REPO, "build/goport/api-oracle")


def materialize(args):
    projects = []
    tsc_src = args.tsc_src or (sorted(glob.glob(os.path.join(REPO, "build/bench/tsc-project-*"))) or [None])[0]
    if tsc_src:
        dst = os.path.join(PROJECTS, "tsc")
        if not os.path.isfile(os.path.join(dst, "tsconfig.json")):
            shutil.rmtree(dst, ignore_errors=True)
            shutil.copytree(os.path.join(tsc_src, "src"), os.path.join(dst, "src"))
            shutil.copy(os.path.join(tsc_src, "tsconfig.json"), dst)
        projects.append(("tsc", os.path.join(dst, "tsconfig.json")))
    else:
        print("no tsc profile (--tsc-src)", file=sys.stderr)
    cases_root = args.cases or os.path.join(REPO, "build/goport/ts-submodule/tests/cases/conformance")
    if args.conformance and os.path.isdir(cases_root):
        cases = []
        for d, _, fs in os.walk(cases_root):
            for f in fs:
                if f.endswith(".ts") and not f.endswith(".d.ts"):
                    p = os.path.join(d, f)
                    with open(p, "rb") as h:
                        text = h.read()
                    if b"@filename" in text.lower():
                        continue  # a multi-unit case: not one file
                    cases.append(p)
        cases.sort()
        step = max(1, len(cases) // args.conformance)
        for i, p in enumerate(cases[::step][: args.conformance]):
            name = "conf-%04d" % i
            dst = os.path.join(PROJECTS, name)
            os.makedirs(dst, exist_ok=True)
            base = os.path.basename(p)
            shutil.copy(p, os.path.join(dst, base))
            with open(os.path.join(dst, "tsconfig.json"), "w") as h:
                json.dump({"files": [base], "compilerOptions": {}}, h)
            with open(os.path.join(dst, "source.txt"), "w") as h:
                h.write(os.path.relpath(p, cases_root) + "\n")
            projects.append((name, os.path.join(dst, "tsconfig.json")))
    return projects


def record(name, config):
    jsonl = os.path.join(OUT, name + ".jsonl")
    r = subprocess.run([RECORDER, "api", "-tsc", TSC, "-out", jsonl, config], capture_output=True, text=True)
    if r.returncode != 0:
        return name, config, None, r.stderr.strip()[-2000:]
    gz = jsonl + ".gz"
    n = 0
    with open(jsonl, "rb") as src, gzip.open(gz, "wb", compresslevel=6) as dst:
        for line in src:
            dst.write(line)
            n += 1
    os.remove(jsonl)
    with open(gz, "rb") as h:
        sha = hashlib.sha256(h.read()).hexdigest()
    return name, config, (n, sha), None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tsc-src")
    ap.add_argument("--cases")
    ap.add_argument("--conformance", type=int, default=200)
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()
    for f in (TSC, RECORDER):
        if not os.access(f, os.X_OK):
            sys.exit(f"missing {f}")
    os.makedirs(OUT, exist_ok=True)
    projects = materialize(args)
    rows, failed = [], []
    with cf.ThreadPoolExecutor(args.workers) as ex:
        for name, config, res, err in ex.map(lambda p: record(*p), projects):
            if err:
                failed.append((name, err))
                print(f"FAILED {name}: {err}", file=sys.stderr)
            else:
                rows.append((name, config, res[0], res[1]))
    rows.sort()
    with open(os.path.join(OUT, "manifest.tsv"), "w") as h:
        for name, config, n, sha in rows:
            h.write(f"{name}\t{config}\t{n}\t{sha}\n")
        if failed:
            h.write("#incomplete\n")
    total = sum(r[2] for r in rows)
    print(f"{len(rows)} projects, {total} requests recorded; {len(failed)} failed -> {OUT}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
