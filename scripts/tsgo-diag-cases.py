#!/usr/bin/env python3
"""Case materializer for the (TSGO.2) diagnostics oracle (docs/goport-diag-oracle.md).

Turns every case tsgo's compiler runner covers into one project directory per configuration. The
runner has FOUR suites (internal/testrunner/compiler_runner_test.go: TestLocal and TestSubmodule,
each over a compiler and a conformance runner):

    compiler/**, conformance/**              the TypeScript submodule's tests/cases (4d4f005c),
                                             extracted IN FULL from typescript-repo's git objects
                                             (the working tree is a sparse checkout)
    local/compiler/**, local/conformance/**  typescript-go-repo/testdata/tests/cases (tsgo's own)

all reached through one cases root, build/goport/diag-src (symlinks). Each case is split EXACTLY as
tsgo's compiler runner splits it (the splitting, the variation enumeration, the option resolution and tsgo's own skips run in-process in Go, through
`build/goport/bin/tsgo-oracle materialize`, which calls the runner's own code):

    build/goport/diag-cases/<case>/<variation>/case.json      inputs + derived harness state
    build/goport/diag-cases/<case>/<variation>/vfs/...        the virtual file system, `/` = vfs/
    build/goport/diag-cases/<case>/<variation>/tsconfig.json  for a CLI cross-check ONLY
    build/goport/diag-cases/manifest.json

<case> is the path relative to the cases root (e.g. compiler/foo.ts, local/compiler/bar.ts); <variation> is tsgo's
configuration name (e.g. `target=es2015,strict=true`) or `_` for a case without variations.

Skips, all recorded in the manifest with a reason:
  - tsgo `skippedTests` (compiler_runner.go), decided in Go;
  - tsgo `SkipUnsupportedCompilerOptions` on the RESOLVED options (embedded tsconfig + directives),
    decided in Go by calling the real function;
  - the corpus's own `tsconfigInTestUsesRemovedFeature` (build.gradle.kts, owner decision
    2026-09-17), a whole-file rule ported below; it is beyond tsgo, so a case it removes is
    recorded as `corpusSkip` and still materialized unless --corpus-skips is given.

Every case is materialized, including .tsx and conformance categories the Kotlin corpus has not
adopted; `inCorpus` marks the subset the generated Kotlin corpus runs (.ts only, compiler/ plus
build.gradle.kts `conformanceCategories`).

Usage: scripts/tsgo-diag-cases.py [--workers 4] [--batch 200] [--force] [--only PATTERN]
Exit status is non-zero (manifest "complete": false) on any fatal case or dead worker.
"""

import argparse
import collections
import concurrent.futures as cf
import fnmatch
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
CASES_ROOT = os.path.join(REPO, "build/goport/diag-src")
LOCAL_CASES = os.path.join(REPO, "typescript-go-repo/testdata/tests/cases")
OUT = os.path.join(REPO, "build/goport/diag-cases")
TOOL = os.path.join(REPO, "build/goport/bin/tsgo-oracle")
ORACLE_GO = os.path.join(REPO, "xemantic-typescript-compiler-goport/oracle-go")
TS_SUBMODULE = os.path.join(REPO, "build/goport/ts-submodule")
GRADLE_CORE = os.path.join(REPO, "xemantic-typescript-compiler-core/build.gradle.kts")
MATERIALIZE_VERSION = 1  # bump when the project-directory layout changes


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def die(msg, code=2):
    print(f"tsgo-diag-cases: REFUSED: {msg}", file=sys.stderr)
    sys.exit(code)


# ------------------------------------------------------------------ tool + environment


def ensure_tool():
    """(Re)build tsgo-oracle when any oracle-go source is newer than the binary."""
    srcs = []
    for dp, _, fns in os.walk(ORACLE_GO):
        srcs += [os.path.join(dp, f) for f in fns if f.endswith((".go", ".sh"))]
    newest = max(os.path.getmtime(s) for s in srcs)
    if not os.path.exists(TOOL) or os.path.getmtime(TOOL) < newest:
        print("tsgo-diag-cases: building tsgo-oracle", file=sys.stderr)
        subprocess.run(["bash", os.path.join(ORACLE_GO, "build.sh")], check=True)
    return sha256(open(TOOL, "rb").read())


def ensure_ts_submodule():
    """The harness mounts `/.lib` from <TypeScript submodule>/tests/lib, which is empty in this
    clone; extract it from typescript-repo (same commit as tsgo's submodule) into build/."""
    lib = os.path.join(TS_SUBMODULE, "tests/lib")
    names = subprocess.run(["git", "-C", os.path.join(REPO, "typescript-repo"), "ls-tree", "-r", "--name-only",
                            "HEAD", "tests/lib"], check=True, capture_output=True, text=True).stdout.split()
    if not names:
        die("typescript-repo has no tests/lib")
    for n in names:
        dst = os.path.join(TS_SUBMODULE, n)
        data = subprocess.run(["git", "-C", os.path.join(REPO, "typescript-repo"), "show", f"HEAD:{n}"],
                              check=True, capture_output=True).stdout
        if not os.path.exists(dst) or open(dst, "rb").read() != data:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            open(dst, "wb").write(data)
    pj = os.path.join(TS_SUBMODULE, "package.json")
    if not os.path.exists(pj):
        open(pj, "w").write('{"name": "typescript", "note": "stub: only tests/lib is extracted (docs/goport-diag-oracle.md)"}\n')
    return lib


def ensure_cases_root():
    """build/goport/diag-src: compiler/ and conformance/ -> the submodule's FULL tests/cases (extracted
    with `git archive` into build/goport/ts-submodule, re-extracted when the tree hash changes), and
    local/ -> tsgo's own testdata/tests/cases."""
    ts = os.path.join(REPO, "typescript-repo")
    tree = subprocess.run(["git", "-C", ts, "rev-parse", "HEAD:tests/cases"], check=True, capture_output=True,
                          text=True).stdout.strip()
    dst = os.path.join(TS_SUBMODULE, "tests/cases")
    stamp = os.path.join(TS_SUBMODULE, "tests/.cases-tree")
    if not (os.path.isdir(dst) and os.path.exists(stamp) and open(stamp).read().strip() == tree):
        print(f"tsgo-diag-cases: extracting the submodule's tests/cases ({tree[:12]})", file=sys.stderr)
        shutil.rmtree(dst, ignore_errors=True)
        os.makedirs(TS_SUBMODULE, exist_ok=True)
        arc = subprocess.run(["git", "-C", ts, "archive", "HEAD", "tests/cases"], check=True, capture_output=True,
                             env=dict(os.environ, GIT_NO_LAZY_FETCH="1")).stdout
        subprocess.run(["tar", "-x", "-C", TS_SUBMODULE], input=arc, check=True)
        open(stamp, "w").write(tree + "\n")
    if not os.path.isdir(LOCAL_CASES):
        die(f"no {LOCAL_CASES}")
    os.makedirs(CASES_ROOT, exist_ok=True)
    for name, target in (("compiler", os.path.join(dst, "compiler")), ("conformance", os.path.join(dst, "conformance")),
                         ("local", LOCAL_CASES)):
        link = os.path.join(CASES_ROOT, name)
        if os.path.islink(link) and os.readlink(link) == target:
            continue
        if os.path.lexists(link):
            os.remove(link)
        os.symlink(target, link)


def tool_env():
    env = dict(os.environ)
    env["XTSC_TS_SUBMODULE"] = TS_SUBMODULE
    return env


# ------------------------------------------------------------------ corpus


def conformance_categories():
    src = open(GRADLE_CORE).read()
    m = re.search(r"val conformanceCategories = listOf\((.*?)\n\)", src, re.S)
    if not m:
        die("cannot find conformanceCategories in build.gradle.kts")
    return re.findall(r'^\s*"([^"]+)"', m.group(1), re.M)


SUITES = ("compiler/", "conformance/", "local/compiler/", "local/conformance/")


def enumerate_cases():
    """Like tsgo's runner (harnessutil.EnumerateFiles, recursive, `\\.tsx?$`) over its four suites,
    submodule first. Also asserts the runner's own invariant: no base name twice within the submodule
    pair or within the local pair (compiler_runner_test.go `Duplicate test file`)."""
    out = []
    for suite in SUITES:
        top = os.path.join(CASES_ROOT, suite)
        for dp, dns, fns in os.walk(top, followlinks=True):
            dns.sort()
            for fn in sorted(fns):
                if re.search(r"\.tsx?$", fn):
                    out.append(suite + os.path.relpath(os.path.join(dp, fn), top))
    for pair in (("compiler/", "conformance/"), ("local/",)):
        names = [os.path.basename(c) for c in out if c.startswith(pair) and (pair[0] == "local/" or not c.startswith("local/"))]
        dup = {n for n, k in collections.Counter(names).items() if k > 1}
        if dup:
            die(f"duplicate test file name(s) within one runner pair: {sorted(dup)[:5]}")
    return out


def suite_of(rel):
    return next(s for s in reversed(SUITES) if rel.startswith(s)).rstrip("/")


def in_corpus(rel, cats):
    if not rel.endswith(".ts") or rel.startswith("local/"):
        return False
    if rel.startswith("compiler/") and rel.count("/") == 1:
        return True
    return any(rel.startswith(f"conformance/{c}/") for c in cats)


DIRECTIVE = re.compile(r"^//\s*@(\w+)\s*:\s*(.+)", re.M)


def parse_directives(source):
    d = {}
    for m in DIRECTIVE.finditer(source):
        k = m.group(1).strip().lower()
        if k != "filename":
            d[k] = m.group(2).strip()
    return d


def tsconfig_in_test_uses_removed_feature(source, directives):
    """Verbatim port of build.gradle.kts `tsconfigInTestUsesRemovedFeature` (LEGACY.1)(g)."""
    sections = list(re.finditer(r"(?im)^\s*//\s*@filename:\s*(\S+)\s*$", source))

    def normalize(path):
        out = []
        for part in path.replace("\\", "/").split("/"):
            if part in ("", "."):
                continue
            if part == "..":
                if out:
                    out.pop()
            else:
                out.append(part)
        return "/".join(out)

    body_of, roots = {}, []
    for i, m in enumerate(sections):
        name = m.group(1)
        start = m.end()
        end = sections[i + 1].start() if i + 1 < len(sections) else len(source)
        key = normalize(name)
        body_of[key] = source[start:end]
        if name.rsplit("/", 1)[-1].lower() == "tsconfig.json":
            roots.append(key)

    def overridden(d):
        return bool(directives.get(d, "").strip())

    def uses_removed(body):
        if not overridden("module") and re.search(r'(?i)"module"\s*:\s*"(amd|umd|system)"', body):
            return True
        if not overridden("outfile") and re.search(r'(?i)"outFile"\s*:\s*"[^"]+"', body):
            return True
        if not overridden("baseurl") and re.search(r'(?i)"baseUrl"\s*:\s*"[^"]*"', body):
            return True
        if not overridden("moduleresolution") and re.search(r'(?i)"moduleResolution"\s*:\s*"(node|node10|classic)"', body):
            return True
        return False

    def extends_of(body):
        one = re.search(r'(?i)"extends"\s*:\s*"([^"]+)"', body)
        if one:
            return [one.group(1)]
        many = re.search(r'(?i)"extends"\s*:\s*\[([^\]]*)\]', body)
        return re.findall(r'"([^"]+)"', many.group(1)) if many else []

    for root in roots:
        seen, work = set(), [root]
        while work:
            cur = work.pop()
            if cur in seen:
                continue
            seen.add(cur)
            body = body_of.get(cur)
            if body is None:
                continue
            if uses_removed(body):
                return True
            d = cur.rsplit("/", 1)[0] if "/" in cur else ""
            for tgt in extends_of(body):
                res = normalize(tgt if not d else f"{d}/{tgt}")
                work.append(res)
                if not res.lower().endswith(".json"):
                    work.append(res + ".json")
    return False


# ------------------------------------------------------------------ main


def run_batch(bi, cases, out_root):
    lst = os.path.join(out_root, ".work", f"batch{bi}.txt")
    log = os.path.join(out_root, ".work", f"batch{bi}.log")
    with open(lst, "w") as f:
        f.write("\n".join(cases) + "\n")
    with open(log, "w") as lf:
        p = subprocess.run([TOOL, "materialize", "-cases-root", CASES_ROOT, "-out", out_root, "-list", lst],
                           stdout=subprocess.PIPE, stderr=lf, env=tool_env())
    rows = [json.loads(l) for l in p.stdout.decode().splitlines() if l.startswith("{")]
    return bi, p.returncode, rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--batch", type=int, default=200)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--only", help="fnmatch pattern over case paths (debugging; writes a partial manifest)")
    ap.add_argument("--corpus-skips", action="store_true",
                    help="also do not materialize cases the corpus-only rule removes")
    args = ap.parse_args()

    tool_sha = ensure_tool()
    ensure_ts_submodule()
    ensure_cases_root()
    cats = conformance_categories()
    cases = enumerate_cases()
    if args.only:
        cases = [c for c in cases if fnmatch.fnmatch(c, args.only)]
    os.makedirs(os.path.join(OUT, ".work"), exist_ok=True)
    man_path = os.path.join(OUT, "manifest.json")
    key = sha256(json.dumps([MATERIALIZE_VERSION, tool_sha]).encode())
    old = {}
    if os.path.exists(man_path) and not args.force:
        try:
            m = json.load(open(man_path))
            if m.get("key") == key:
                old = m["cases"]
        except (ValueError, KeyError):
            old = {}

    entries, todo = {}, []
    for rel in cases:
        raw = open(os.path.join(CASES_ROOT, rel), "rb").read()
        src = raw.decode("utf-8", errors="replace")
        e = {"sha256": sha256(raw), "inCorpus": in_corpus(rel, cats)}
        if tsconfig_in_test_uses_removed_feature(src, parse_directives(src)):
            e["corpusSkip"] = "tsconfigInTestUsesRemovedFeature"
        prev = old.get(rel)
        if prev and prev["sha256"] == e["sha256"] and prev.get("configs") and all(
                c["status"] != "ok" or os.path.exists(os.path.join(OUT, c["dir"], "case.json")) for c in prev["configs"]):
            entries[rel] = prev
            continue
        entries[rel] = e
        if e.get("corpusSkip") and args.corpus_skips:
            e["configs"] = [{"variation": "*", "status": "skipped", "reason": "corpus " + e["corpusSkip"]}]
            continue
        todo.append(rel)

    print(f"tsgo-diag-cases: {len(cases)} cases, {len(cases) - len(todo)} cached, {len(todo)} to materialize",
          file=sys.stderr)
    t0 = time.time()
    fatal = []
    for rel in todo:
        shutil.rmtree(os.path.join(OUT, rel), ignore_errors=True)
    batches = [todo[i:i + args.batch] for i in range(0, len(todo), args.batch)]
    with cf.ThreadPoolExecutor(args.workers) as ex:
        for bi, rc, rows in ex.map(lambda ib: run_batch(ib[0], ib[1], OUT), enumerate(batches)):
            by = {}
            for r in rows:
                c = {"variation": r["variation"], "status": r["status"]}
                if r.get("reason"):
                    c["reason"] = r["reason"]
                if r.get("dir"):
                    c["dir"] = os.path.relpath(r["dir"], OUT)
                by.setdefault(r["case"], []).append(c)
            for rel in batches[bi]:
                entries[rel]["configs"] = by.get(rel) or [
                    {"variation": "*", "status": "fatal", "reason": f"no result (tool exit {rc}; see .work/batch{bi}.log)"}]
            print(f"  batch {bi}: {len(batches[bi])} cases, exit {rc}", file=sys.stderr, flush=True)
    secs = time.time() - t0
    for rel, e in entries.items():
        for c in e.get("configs", []):
            if c["status"] == "fatal":
                fatal.append(f"{rel} [{c['variation']}]: {c.get('reason')}")

    counts = {"cases": len(entries), "configs": 0, "ok": 0, "skipped": {}, "fatal": len(fatal),
              "okInCorpus": 0, "okCorpusSkip": 0, "suites": {}}
    for rel, e in entries.items():
        su = counts["suites"].setdefault(suite_of(rel), {"cases": 0, "ok": 0, "skipped": 0})
        su["cases"] += 1
        for c in e.get("configs", []):
            if c["variation"] != "*":
                counts["configs"] += 1
            if c["status"] in ("ok", "skipped"):
                su[c["status"]] += 1
            if c["status"] == "ok":
                counts["ok"] += 1
                counts["okInCorpus"] += e["inCorpus"]
                counts["okCorpusSkip"] += bool(e.get("corpusSkip"))
            elif c["status"] == "skipped":
                r = c.get("reason", "").split(":")[0]
                counts["skipped"][r] = counts["skipped"].get(r, 0) + 1
    complete = not fatal and not args.only
    manifest = {"complete": complete, "key": key, "materializeVersion": MATERIALIZE_VERSION, "toolSha256": tool_sha,
                "casesRoot": os.path.relpath(CASES_ROOT, REPO), "seconds": round(secs, 1), "counts": counts,
                "cases": dict(sorted(entries.items()))}
    tmp = man_path + ".tmp"
    json.dump(manifest, open(tmp, "w"), indent=1)
    os.replace(tmp, man_path)
    print(json.dumps(counts, indent=1))
    print(f"materialized {len(todo)} cases in {secs:.1f} s")
    if fatal:
        for f in fatal[:20]:
            print("  FATAL " + f, file=sys.stderr)
        die(f"{len(fatal)} configuration(s) failed; manifest marked incomplete", 1)
    print(f"tsgo-diag-cases: OK, manifest {os.path.relpath(man_path, REPO)}")


if __name__ == "__main__":
    main()
