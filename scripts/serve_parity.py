#!/usr/bin/env python3
"""(SERVE.2) round 873 — the ANSWER-PARITY MATRIX between the one-shot CLI and a
warm `--serve` daemon.

WHY THIS EXISTS.  Two divergences between the two paths have surfaced by
accident and neither by a test: round 848 found fifteen process-global mode
flags a one-shot CLI leaks harmlessly and a daemon leaks into every LATER
request, and round 872 found a daemon-served compile exiting 0 on a project
where the CLI exits 1 — a CI false-green.  Nothing had ever swept the boundary
deliberately.  This does, as a MATRIX rather than as a list of hunches:

    outcome x invocation-form x observable, plus a SEQUENCE axis a one-shot
    CLI structurally cannot have (repeat, A-B-A, mode-then-plain, fail-then-pass,
    refused-then-normal, EDIT-then-request).

WHAT IS COMPARED, per step: process EXIT CODE, stdout (normalized only for the
run's own tree root and the `time:` line), stderr, and the whole project TREE
(every file's sha256) so an emit difference cannot hide.

THE DRIVER'S OWN FAILURE MODES ARE LOUD, which is the property CLAUDE.md keeps
recording the absence of (an ablation driver that dispatched no arm and printed
`complete`; a suite-count snippet that prints `0 0 0`).  Three guards:

  * every daemon-arm step asserts the request was SERVED BY THE DAEMON — the
    server's own `request N served` line count must advance by exactly one, and
    the dispatcher's in-process fallback message must NOT appear on stderr.
    Without this the single most likely instrument failure — the daemon died, so
    `--daemon` compiled in-process and the two arms agreed trivially — would
    render as a clean sweep;
  * a cell that raises, or whose step count does not match, is ERROR, never PASS;
  * `--selftest` asserts the comparator itself reddens on a synthetic diff.

USE:
    scripts/serve_parity.py --out build/serve-parity          # the whole matrix
    scripts/serve_parity.py --out DIR --only no-path,emit     # named cells
    scripts/serve_parity.py --selftest                        # comparator check

Exit 0 iff every cell PASSes (a cell whose divergence is documented and
deliberate is declared KNOWN in the cell itself and prints as such).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LIB = REPO / "xemantic-typescript-compiler-daemon" / "build" / "install" / "lib"
MAIN = "com.xemantic.typescript.compiler.server.XtscMainKt"

# --------------------------------------------------------------------------
# fixtures — one project each, keyed by name
# --------------------------------------------------------------------------

TSCONFIG = json.dumps(
    {"compilerOptions": {"strict": True, "target": "es2020", "outDir": "out"},
     "include": ["src"]},
    indent=2,
) + "\n"

FIXTURES: dict[str, dict[str, str]] = {
    # a project with no errors
    "clean": {
        "tsconfig.json": TSCONFIG,
        "src/a.ts": "export const a: number = 1;\nexport function f(x: string): string { return x; }\n",
    },
    # a project with a type error
    "typeerr": {
        "tsconfig.json": TSCONFIG,
        "src/a.ts": "export const a: number = \"not a number\";\nexport const b: string = 2;\n",
    },
    # a project that does not parse
    "syntax": {
        "tsconfig.json": TSCONFIG,
        "src/a.ts": "export const a: number = ;\nfunction f( {\n",
    },
    # a project importing something that does not resolve
    "unresolved": {
        "tsconfig.json": TSCONFIG,
        "src/a.ts": "import { nope } from \"./missing\";\nexport const x = nope;\n",
    },
    # a config that includes nothing
    "empty": {
        "tsconfig.json": json.dumps(
            {"compilerOptions": {"strict": True, "outDir": "out"}, "include": ["src"]},
            indent=2,
        ) + "\n",
        "src/.keep": "",
    },
    # a second, DIFFERENT project for the A-B-A sequence
    "other": {
        "tsconfig.json": TSCONFIG,
        "src/z.ts": "export interface Z { n: number }\nexport const z: Z = { n: 1 };\nexport const bad: Z = { n: \"s\" };\n",
    },
    # the DECOY the daemon's own cwd holds — see `daemon_cwd` below
    "decoy": {
        "tsconfig.json": TSCONFIG,
        "src/decoy.ts": "export const decoyMarker: number = 12345;\n",
    },
}

# --------------------------------------------------------------------------
# cells
# --------------------------------------------------------------------------

@dataclass
class Step:
    """One invocation, run on both arms."""
    args: list[str]
    project: str | None = None
    # where the invocation runs: "parent" (the arm's tree root) or "project"
    cwd: str = "parent"
    # applied to the arm's own copy BEFORE the invocation: (project, relpath, text)
    edit: tuple[str, str, str] | None = None
    # "full" compares normalized stdout byte for byte; "salient" compares only
    # diagnostic and summary lines (for steps whose output is a timing table)
    compare: str = "full"
    label: str = ""


@dataclass
class Cell:
    name: str
    what: str
    steps: list[Step]
    projects: list[str] = field(default_factory=list)
    # A cell whose divergence is deliberate and documented states WHY here. It
    # is reported as KNOWN rather than DIFF, and the reason is printed with it,
    # so this can never be a silent escape hatch.
    known: str | None = None

    def fixtures(self) -> list[str]:
        names = list(self.projects)
        for s in self.steps:
            if s.project and s.project not in names:
                names.append(s.project)
            if s.edit and s.edit[0] not in names:
                names.append(s.edit[0])
        return names


def _abs(p: str) -> str:
    return "$P:" + p          # placeholder, resolved per arm


def cells() -> list[Cell]:
    out: list[Cell] = []

    # ---- OUTCOME axis, absolute project path, --noEmit -------------------
    for fx in ("clean", "typeerr", "syntax", "unresolved", "empty"):
        out.append(Cell(
            name=f"outcome-{fx}",
            what=f"{fx} project, absolute path, --noEmit",
            steps=[Step(args=["--noEmit", _abs(fx)], project=fx)],
        ))

    # ---- INVOCATION-FORM axis -------------------------------------------
    out.append(Cell(
        name="form-listall",
        what="--listAll (the full diagnostic set and its order)",
        steps=[Step(args=["--noEmit", "--listAll", _abs("typeerr")], project="typeerr")],
    ))
    out.append(Cell(
        name="form-project-flag",
        what="-p pointing straight at tsconfig.json",
        steps=[Step(args=["--noEmit", "-p", _abs("typeerr") + "/tsconfig.json"], project="typeerr")],
    ))
    out.append(Cell(
        name="form-relative-path",
        what="a RELATIVE project path from the tree root",
        steps=[Step(args=["--noEmit", "typeerr"], project="typeerr")],
    ))
    out.append(Cell(
        name="form-no-path",
        what="NO path argument at all, cwd = the project (CliArgs.project defaults to \".\")",
        steps=[Step(args=["--noEmit"], project="typeerr", cwd="project")],
    ))
    out.append(Cell(
        name="form-dot-path",
        what="an explicit \".\" from inside the project",
        steps=[Step(args=["--noEmit", "."], project="typeerr", cwd="project")],
    ))
    out.append(Cell(
        name="form-missing-project",
        what="an ABSOLUTE path that does not exist on either side",
        steps=[Step(args=["--noEmit", "/nonexistent-xtsc-parity-project"])],
    ))
    out.append(Cell(
        name="form-missing-relative",
        what="a RELATIVE path that names nothing (the one form that cannot be absolutized)",
        steps=[Step(args=["--noEmit", "nope-not-here"])],
    ))
    out.append(Cell(
        name="form-bare-source-file",
        what="a bare .ts file argument (tsc's single-file mode)",
        steps=[Step(args=["--noEmit", _abs("typeerr") + "/src/a.ts"], project="typeerr")],
    ))
    out.append(Cell(
        name="form-numeric-option-value",
        what="--workers 4 with NO path: the '4' must not be mistaken for a project",
        steps=[Step(args=["--noEmit", "--workers", "4"], project="typeerr", cwd="project",
                    compare="salient")],
    ))
    out.append(Cell(
        name="form-help",
        what="--help",
        steps=[Step(args=["--help"])],
    ))

    # ---- EMIT axis -------------------------------------------------------
    out.append(Cell(
        name="emit-default",
        what="a project that EMITS (outDir from the config) — the tree is diffed",
        steps=[Step(args=[_abs("clean")], project="clean")],
    ))
    out.append(Cell(
        name="emit-outdir-relative",
        what="--outDir with a relative, not-yet-existing directory",
        steps=[Step(args=[_abs("clean"), "--outDir", "emitted-here"], project="clean", cwd="project")],
    ))
    out.append(Cell(
        name="emit-outdir-absolute",
        what="--outDir with an absolute, not-yet-existing directory",
        steps=[Step(args=[_abs("clean"), "--outDir", _abs("clean") + "/abs-emitted"],
                    project="clean")],
    ))
    out.append(Cell(
        name="emit-project-flag-relative",
        what="-p on a relative tsconfig path from the tree root, emitting",
        steps=[Step(args=["-p", "clean/tsconfig.json"], project="clean")],
    ))
    out.append(Cell(
        name="emit-noemit-writes-nothing",
        what="--noEmit on an emitting project leaves the tree untouched",
        steps=[Step(args=["--noEmit", _abs("clean")], project="clean")],
    ))

    # ---- SEQUENCE axis ---------------------------------------------------
    out.append(Cell(
        name="seq-repeat",
        what="the SAME request three times",
        steps=[Step(args=["--noEmit", _abs("typeerr")], project="typeerr", label=f"rep{i}")
               for i in range(3)],
    ))
    out.append(Cell(
        name="seq-a-b-a",
        what="project A, then a DIFFERENT project B, then A again",
        steps=[
            Step(args=["--noEmit", _abs("typeerr")], project="typeerr", label="A1"),
            Step(args=["--noEmit", _abs("other")], project="other", label="B"),
            Step(args=["--noEmit", _abs("typeerr")], project="typeerr", label="A2"),
        ],
    ))
    out.append(Cell(
        name="seq-mode-then-plain",
        what="a request carrying a process-global MODE flag, then one that does not (round 848)",
        steps=[
            Step(args=["--noEmit", "--passTiming", _abs("clean")], project="clean",
                 compare="salient", label="passTiming"),
            Step(args=["--noEmit", _abs("typeerr")], project="typeerr", label="plain"),
        ],
    ))
    out.append(Cell(
        name="seq-mode-workers-then-plain",
        what="--workers 4 (a mode that selects a different code path), then a plain request",
        steps=[
            Step(args=["--noEmit", "--workers", "4", _abs("clean")], project="clean",
                 compare="salient", label="workers"),
            Step(args=["--noEmit", _abs("typeerr")], project="typeerr", label="plain"),
        ],
    ))
    out.append(Cell(
        name="seq-fail-then-pass",
        what="a failing compile, then a passing one",
        steps=[
            Step(args=["--noEmit", _abs("typeerr")], project="typeerr", label="fail"),
            Step(args=["--noEmit", _abs("clean")], project="clean", label="pass"),
        ],
    ))
    out.append(Cell(
        name="seq-syntax-then-normal",
        what="a project that does not parse, then a normal one",
        steps=[
            Step(args=["--noEmit", _abs("syntax")], project="syntax", label="syntax"),
            Step(args=["--noEmit", _abs("typeerr")], project="typeerr", label="normal"),
        ],
    ))
    out.append(Cell(
        name="seq-missing-then-normal",
        what="a request for a project that does not exist, then a normal one",
        steps=[
            Step(args=["--noEmit", "/nonexistent-xtsc-parity-project"], label="missing"),
            Step(args=["--noEmit", _abs("typeerr")], project="typeerr", label="normal"),
        ],
    ))
    out.append(Cell(
        name="seq-refused-then-normal",
        what="a REFUSED request (--watch) followed by a normal one",
        steps=[
            Step(args=["--watch", _abs("clean")], project="clean", label="refused"),
            Step(args=["--noEmit", _abs("typeerr")], project="typeerr", label="normal"),
        ],
        known="--watch is refused by the daemon in constant time and RUN by the CLI "
              "forever; the refused step is compared for the daemon's refusal only "
              "(the CLI arm is not run for it).",
    ))
    # THE cell round 871's cross-request parse cache put in the blast radius.
    out.append(Cell(
        name="seq-edit-between-requests",
        what="compile, EDIT a source file, compile again (round 871's CrawlParseCache)",
        steps=[
            Step(args=["--noEmit", _abs("clean")], project="clean", label="before"),
            Step(args=["--noEmit", _abs("clean")], project="clean", label="after-edit",
                 edit=("clean", "src/a.ts", "export const a: number = \"broken by the edit\";\n")),
            Step(args=["--noEmit", _abs("clean")], project="clean", label="after-revert",
                 edit=("clean", "src/a.ts", "export const a: number = 1;\n")),
        ],
    ))
    out.append(Cell(
        name="seq-edit-adds-file",
        what="compile, ADD a new source file, compile again",
        steps=[
            Step(args=["--noEmit", _abs("clean")], project="clean", label="before"),
            Step(args=["--noEmit", _abs("clean")], project="clean", label="after-add",
                 edit=("clean", "src/added.ts", "export const bad: number = \"added\";\n")),
        ],
    ))
    out.append(Cell(
        name="seq-emit-then-noemit",
        what="an emitting request, then --noEmit on the same project",
        steps=[
            Step(args=[_abs("clean")], project="clean", label="emit"),
            Step(args=["--noEmit", _abs("clean")], project="clean", label="noEmit"),
        ],
    ))

    return out


# --------------------------------------------------------------------------
# running one arm
# --------------------------------------------------------------------------

@dataclass
class RunResult:
    exit: int
    stdout: str
    stderr: str
    tree: dict[str, str]


TIME_LINE = re.compile(r"^time:\s+\d+ ms$", re.M)
MS_ANY = re.compile(r"\b\d+(\.\d+)?\s*ms\b")


def tree_hashes(root: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for p in sorted(root.rglob("*")):
        if p.is_file():
            out[str(p.relative_to(root))] = hashlib.sha256(p.read_bytes()).hexdigest()[:16]
    return out


def normalize(text: str, roots: list[Path]) -> str:
    for r in roots:
        text = text.replace(str(r), "<ROOT>")
    text = TIME_LINE.sub("time: <T>", text)
    return text


def salient(text: str) -> str:
    keep = []
    for line in text.splitlines():
        if "error TS" in line or line.startswith(("OK —", "FAILED —", "files:", "emitted:",
                                                  "unresolved imports:")):
            keep.append(line)
    return "\n".join(keep)


class Daemon:
    """The one warm daemon every daemon-arm step goes through."""

    def __init__(self, out: Path, cwd: Path, cp: str, java: str):
        self.log = out / "daemon.log"
        self.sock = str(out / "xtsc-parity.sock")
        self.cp = cp
        self.java = java
        self.cwd = cwd
        self.proc: subprocess.Popen | None = None

    def restart(self, why: str) -> None:
        """A wedged daemon must not poison every later cell.

        One pathological request holds the single compile thread for good — the
        server is sequential by design (invariant 1) — so without this a single
        ERROR cell turns the rest of the matrix into ERRORs that say nothing.
        """
        print(f"   (restarting the daemon: {why})")
        self.stop()
        self.log = self.log.with_name(f"daemon.{int(time.time())}.log")
        self.start()

    def start(self) -> None:
        fh = self.log.open("wb")
        self.proc = subprocess.Popen(
            [self.java, "-Xmx4g", "-cp", self.cp, MAIN, "--serve", "--socket", self.sock],
            cwd=str(self.cwd), stdout=fh, stderr=subprocess.STDOUT,
        )
        for _ in range(400):
            if self.log.exists() and "listening on" in self.log.read_text(errors="replace"):
                return
            if self.proc.poll() is not None:
                raise SystemExit(f"daemon died on start; see {self.log}")
            time.sleep(0.25)
        raise SystemExit(f"daemon never bound {self.sock}; see {self.log}")

    def served(self) -> int:
        """How many requests the SERVER says it has served — the reachability
        control.  A daemon-arm step that does not advance this ran in-process."""
        if not self.log.exists():
            return 0
        return len(re.findall(r"^xtsc: request \d+ served ", self.log.read_text(errors="replace"), re.M))

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        try:
            os.unlink(self.sock)
        except FileNotFoundError:
            pass


def run_step(java: str, cp: str, step: Step, root: Path, sock: str | None,
             timeout: int) -> RunResult:
    args = []
    for a in step.args:
        args.append(str(root / a[3:]) if a.startswith("$P:") else a)
    cwd = root if step.cwd == "parent" else root / (step.project or "")
    cmd = [java, "-Xmx4g", "-cp", cp, MAIN]
    if sock:
        cmd += ["--daemon", "--socket", sock]
    cmd += args
    p = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True, timeout=timeout)
    return RunResult(p.returncode, p.stdout, p.stderr, tree_hashes(root))


# --------------------------------------------------------------------------
# the matrix
# --------------------------------------------------------------------------

FALLBACK = "no compile server on"


def materialize(root: Path, names: list[str]) -> None:
    for n in names:
        for rel, text in FIXTURES[n].items():
            f = root / n / rel
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(text)


def compare_step(step: Step, cli: RunResult, dmn: RunResult,
                 cli_root: Path, dmn_root: Path) -> list[str]:
    diffs: list[str] = []
    if cli.exit != dmn.exit:
        diffs.append(f"exit: cli={cli.exit} daemon={dmn.exit}")
    a = normalize(cli.stdout, [cli_root])
    b = normalize(dmn.stdout, [dmn_root])
    if step.compare == "salient":
        a, b = salient(a), salient(b)
    if a != b:
        diffs.append("stdout:\n" + first_diff(a, b))
    ea = normalize(cli.stderr, [cli_root])
    eb = normalize(dmn.stderr, [dmn_root])
    if ea != eb:
        diffs.append("stderr:\n" + first_diff(ea, eb))
    if cli.tree != dmn.tree:
        only_cli = sorted(set(cli.tree) - set(dmn.tree))
        only_dmn = sorted(set(dmn.tree) - set(cli.tree))
        changed = sorted(k for k in set(cli.tree) & set(dmn.tree) if cli.tree[k] != dmn.tree[k])
        diffs.append(f"tree: only-cli={only_cli} only-daemon={only_dmn} differing={changed}")
    return diffs


def first_diff(a: str, b: str, context: int = 3) -> str:
    la, lb = a.splitlines(), b.splitlines()
    for i in range(max(len(la), len(lb))):
        x = la[i] if i < len(la) else "<absent>"
        y = lb[i] if i < len(lb) else "<absent>"
        if x != y:
            lo = max(0, i - context)
            head = "\n".join(f"      | {l}" for l in la[lo:i])
            return (f"{head}\n" if head else "") + f"    cli    | {x}\n    daemon | {y}"
    return "    (equal after normalization?)"


def run_cell(cell: Cell, out: Path, cp: str, java: str, daemon: Daemon,
             timeout: int) -> tuple[str, list[str]]:
    cli_root = out / "run" / cell.name / "cli"
    dmn_root = out / "run" / cell.name / "daemon"
    for r in (cli_root, dmn_root):
        if r.exists():
            shutil.rmtree(r)
        r.mkdir(parents=True)
    names = cell.fixtures()
    materialize(cli_root, names)
    materialize(dmn_root, names)

    problems: list[str] = []
    ran = 0
    for n, step in enumerate(cell.steps):
        tag = step.label or f"step{n}"
        refused_only = cell.name == "seq-refused-then-normal" and tag == "refused"
        if step.edit:
            proj, rel, text = step.edit
            for r in (cli_root, dmn_root):
                (r / proj / rel).parent.mkdir(parents=True, exist_ok=True)
                (r / proj / rel).write_text(text)
        # The CLI arm runs FIRST, so a daemon that wedges still leaves the
        # answer the daemon was supposed to match on record.
        cli: RunResult | None = None
        if not refused_only:
            try:
                cli = run_step(java, cp, step, cli_root, None, timeout)
            except subprocess.TimeoutExpired:
                problems.append(f"[{tag}] ERROR cli arm TIMED OUT after {timeout}s")
                return "ERROR", problems
        before = daemon.served()
        try:
            dmn = run_step(java, cp, step, dmn_root, daemon.sock, timeout)
        except subprocess.TimeoutExpired:
            problems.append(
                f"[{tag}] ERROR daemon arm TIMED OUT after {timeout}s — the daemon is "
                f"wedged (it serves requests sequentially, so nothing else can run)")
            daemon.restart("the previous request never returned")
            return "ERROR", problems
        after = daemon.served()
        # THE reachability control: the request must have been answered by the
        # daemon.  Both halves matter — the counter proves the server ran it,
        # the stderr check proves the dispatcher did not quietly fall back.
        if after != before + 1:
            problems.append(
                f"[{tag}] ERROR the daemon arm was NOT served by the daemon "
                f"(served {before} -> {after}); this cell tested nothing")
            return "ERROR", problems
        if FALLBACK in dmn.stderr:
            problems.append(f"[{tag}] ERROR the dispatcher fell back to an in-process compile")
            return "ERROR", problems
        ran += 1
        if refused_only:
            # The CLI would run --watch forever; what is asserted here is that
            # the daemon refuses in constant time and says so.
            if dmn.exit != 2 or "not supported over the compile server" not in dmn.stdout:
                problems.append(f"[{tag}] DIFF refused step: exit={dmn.exit} stdout={dmn.stdout!r}")
            continue
        assert cli is not None
        for d in compare_step(step, cli, dmn, cli_root, dmn_root):
            problems.append(f"[{tag}] DIFF {d}")

    if ran != len(cell.steps):
        return "ERROR", problems + [f"only {ran}/{len(cell.steps)} steps ran"]
    if not problems:
        return "PASS", []
    return ("KNOWN" if cell.known else "DIFF"), problems


# --------------------------------------------------------------------------
# self-test: the comparator must SEE a divergence
# --------------------------------------------------------------------------

def selftest() -> int:
    root = Path("/tmp/x")
    step = Step(args=[])
    same = RunResult(0, "OK — 0 errors\n", "", {"a": "1"})
    checks = [
        ("exit", RunResult(1, "OK — 0 errors\n", "", {"a": "1"}), "exit:"),
        ("stdout", RunResult(0, "FAILED — 1 error(s)\n", "", {"a": "1"}), "stdout:"),
        ("stderr", RunResult(0, "OK — 0 errors\n", "boom", {"a": "1"}), "stderr:"),
        ("tree", RunResult(0, "OK — 0 errors\n", "", {"a": "2"}), "tree:"),
        ("tree-extra", RunResult(0, "OK — 0 errors\n", "", {"a": "1", "b": "1"}), "tree:"),
    ]
    bad = 0
    for name, other, needle in checks:
        got = compare_step(step, same, other, root, root)
        ok = any(d.startswith(needle) for d in got)
        print(f"  selftest {name:10s} {'sees it' if ok else 'BLIND'}")
        if not ok:
            bad += 1
    got = compare_step(step, same, same, root, root)
    print(f"  selftest identical {'quiet' if not got else 'FALSE POSITIVE'}")
    if got:
        bad += 1
    return bad


# --------------------------------------------------------------------------

def resolve_cp() -> str:
    if not LIB.is_dir():
        raise SystemExit(f"no {LIB} — run ./gradlew assemble")
    jars = sorted(LIB.glob("*.jar"))
    if not jars:
        raise SystemExit(f"{LIB} holds no jars — run ./gradlew assemble")
    # positive control that the code under test is on the classpath at all
    # (round 853: a gate reading a class directory needs one).
    if not any("compiler-daemon" in j.name for j in jars):
        raise SystemExit(f"{LIB} has no daemon jar; this would measure something else")
    return ":".join(str(j) for j in jars)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/serve-parity")
    ap.add_argument("--only", default="")
    ap.add_argument("--timeout", type=int, default=300)
    ap.add_argument("--java", default=os.environ.get("XTSC_JAVA", "java"))
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        bad = selftest()
        print("selftest: OK" if not bad else f"selftest: {bad} BLIND comparator(s)")
        return 1 if bad else 0

    out = Path(args.out)
    if not out.is_absolute():
        out = REPO / out
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    print("comparator selftest (a green matrix from a blind comparator proves nothing):")
    if selftest():
        print("REFUSING to run: the comparator cannot see a divergence")
        return 2

    cp = resolve_cp()
    chosen = [c for c in cells() if not args.only or c.name in args.only.split(",")]
    if not chosen:
        raise SystemExit(f"--only matched no cell of {[c.name for c in cells()]}")

    # The daemon runs with its cwd inside a DECOY project — a realistic daemon
    # was started from some other directory, and a request that resolves a
    # relative path against the server's cwd must be VISIBLE, not accidentally
    # right because both processes happen to sit in the same place.
    decoy = out / "daemon-cwd"
    materialize(decoy, ["decoy"])
    daemon = Daemon(out, decoy / "decoy", cp, args.java)
    daemon.start()
    print(f"daemon up on {daemon.sock} (cwd = the decoy project)\n")

    results: list[tuple[str, Cell, list[str]]] = []
    try:
        for cell in chosen:
            verdict, problems = run_cell(cell, out, cp, args.java, daemon, args.timeout)
            results.append((verdict, cell, problems))
            print(f"{verdict:6s} {cell.name:28s} {cell.what}")
            for p in problems:
                print("   " + p.replace("\n", "\n   "))
            if verdict == "KNOWN":
                print(f"   KNOWN because: {cell.known}")
    finally:
        daemon.stop()

    steps = sum(len(c.steps) for _, c, _ in results)
    n_pass = sum(1 for v, _, _ in results if v == "PASS")
    n_known = sum(1 for v, _, _ in results if v == "KNOWN")
    bad = [(v, c) for v, c, _ in results if v in ("DIFF", "ERROR")]
    print(f"\n{len(results)} cells, {steps} invocation pairs: "
          f"{n_pass} PASS, {n_known} KNOWN, {len(bad)} DIFF/ERROR")
    for v, c in bad:
        print(f"  {v} {c.name}")
    (out / "report.json").write_text(json.dumps(
        [{"cell": c.name, "what": c.what, "verdict": v, "problems": p, "steps": len(c.steps)}
         for v, c, p in results], indent=2))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
