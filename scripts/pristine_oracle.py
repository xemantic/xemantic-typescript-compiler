#!/usr/bin/env python3
r"""Read PRISTINE tsc's answer for a shape, offline, out of the corpus itself.

WHY THIS EXISTS
---------------
`tools/tsgo-7.0.2/lib/tsc` is the only reference compiler that RUNS on this box, and it
is TypeScript 7 / tsgo -- which diverges from the pristine mainline tsc this project
diffs against (CLAUDE.md, round 938: TS2300 at both declarations vs the second only;
TS2300 for a late-bound duplicate where pristine emits TS2717 alone).  So a row measured
against tsgo alone is PROVISIONAL.

The pristine oracle that IS available offline is the corpus itself: `typescript-repo`
is pinned to the pristine mainline commit, its `tests/cases/**` are the inputs and its
`tests/baselines/reference/*.errors.txt` are the diagnostics PRISTINE tsc produced for
them.  An ABSENT `.errors.txt` beside a present case is evidence too -- it means pristine
emitted nothing.

USAGE
-----
  # what does pristine emit for this diagnostic code, and where?
  python3 scripts/pristine_oracle.py --code 2717
  python3 scripts/pristine_oracle.py --code 2717 --show 5

  # which cases contain this syntax, and what did pristine say about each?
  python3 scripts/pristine_oracle.py --pattern 'interface \w+ \{[^}]*\[\w+\]' --show 3

  # everything pristine says about one fixture (diagnostics + the annotated source)
  python3 scripts/pristine_oracle.py --fixture dynamicNamesErrors --full

  # combine: cases matching a pattern whose baseline carries a code
  python3 scripts/pristine_oracle.py --pattern '\[Symbol\.' --code 2300

Every result is labelled with whether that baseline is GENERATED-AND-ACTIVE in our suite
(i.e. whether the corpus is already gating it) -- read from the generated test sources
under build/generated/typescript-tests when they are present.

EXIT CODE: 0 if at least one result, 1 if nothing matched (so it composes in a shell).
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CASES = REPO / "typescript-repo" / "tests" / "cases"
BASELINES = REPO / "typescript-repo" / "tests" / "baselines" / "reference"
GENERATED = REPO / "build" / "generated" / "typescript-tests"

# A baseline header is either `file.ts(line,col): error TSxxxx: msg` or, for a global
# (file-less) diagnostic, a bare `error TSxxxx: msg`.
HEADER_RE = re.compile(
    r"^(?:(?P<file>[^(]+)\((?P<line>\d+),(?P<col>\d+)\): )?error TS(?P<code>\d+): (?P<msg>.*)$"
)


def die(msg: str) -> None:
    print(f"pristine_oracle: {msg}", file=sys.stderr)
    sys.exit(2)


# ---------------------------------------------------------------- generated index

def load_generated_index() -> tuple[set[str], set[str], str]:
    """(baselines referenced by an errors subtest, baselines referenced at all, note)."""
    errors_subtests: set[str] = set()
    any_subtests: set[str] = set()
    if not GENERATED.is_dir():
        return errors_subtests, any_subtests, (
            "NOT BUILT (run ./gradlew generateTypeScriptTests) -- 'active' is UNKNOWN below"
        )
    ref = re.compile(r"\$typeScriptBaselineDir/([^\"]+)")
    for kt in GENERATED.rglob("*.kt"):
        text = kt.read_text(errors="replace")
        for m in ref.finditer(text):
            name = m.group(1)
            any_subtests.add(name)
            if name.endswith(".errors.txt"):
                errors_subtests.add(name)
    note = f"{len(any_subtests)} baselines referenced by generated tests ({len(errors_subtests)} error baselines)"
    return errors_subtests, any_subtests, note


# ---------------------------------------------------------------- baseline access

def baselines_for(stem: str) -> list[Path]:
    """Every errors baseline belonging to a case stem, including `(target=es2015)` variations."""
    out = []
    for p in sorted(BASELINES.glob(f"{stem}*.errors.txt")):
        rest = p.name[len(stem):-len(".errors.txt")]
        if rest == "" or rest.startswith("("):
            out.append(p)
    return out


def diagnostics_of(path: Path) -> list[tuple[str, str]]:
    """(code, header line) for every top-of-file diagnostic header in an errors baseline."""
    out = []
    for line in path.read_text(errors="replace").splitlines():
        if line.startswith("===="):
            break
        m = HEADER_RE.match(line)
        if m:
            out.append((m.group("code"), line))
    return out


def annotated_source(path: Path) -> str:
    text = path.read_text(errors="replace")
    idx = text.find("\n====")
    return text[idx + 1:] if idx >= 0 else text


# ---------------------------------------------------------------- case access

def iter_cases():
    for root, _dirs, files in os.walk(CASES):
        for f in files:
            if f.endswith((".ts", ".tsx", ".js", ".jsx", ".mts", ".cts")):
                yield Path(root) / f


# The `tests/cases` tree in this clone is INCOMPLETE (6,537 files against 9,055 error
# baselines), so a pattern search over it alone silently misses cases -- notably every
# conformance case whose directory was not fetched.  The universal source index is the
# BASELINES themselves: a `.js` baseline opens with `//// [tests/cases/.../x.ts] ////`
# followed by the whole input, and an `.errors.txt` embeds the input under its
# `==== file (n errors) ====` banners.  Together they cover every case pristine tsc ran.

def baseline_source_text(stem: str) -> str:
    """Pristine's own copy of a case's source, from whichever baseline carries it."""
    parts = []
    for suffix in (".js", ".errors.txt"):
        for b in sorted(BASELINES.glob(f"{stem}*{suffix}")):
            rest = b.name[len(stem):-len(suffix)]
            if rest != "" and not rest.startswith("("):
                continue
            text = b.read_text(errors="replace")
            if suffix == ".errors.txt":
                idx = text.find("\n====")
                text = text[idx + 1:] if idx >= 0 else ""
            parts.append(text)
    return "\n".join(parts)


def grep_stems(pattern: str, ignore_case: bool) -> list[str]:
    """Case stems whose PRISTINE source matches `pattern` (ERE), via grep for speed.

    Searched: the `tests/cases` tree AND the `.js` / `.errors.txt` baselines, because the
    cases tree in this clone is INCOMPLETE (6,537 files against 9,055 error baselines) and
    a search over it alone silently misses whole conformance directories.
    """
    stems: set[str] = set()
    cmd_base = ["grep", "-rlE"] + (["-i"] if ignore_case else []) + [pattern]
    for target, includes in ((CASES, []), (BASELINES, ["--include=*.js", "--include=*.errors.txt"])):
        if not target.is_dir():
            continue
        res = subprocess.run(cmd_base + includes + [str(target)],
                             capture_output=True, text=True)
        for line in res.stdout.splitlines():
            name = Path(line).name
            for suffix in (".errors.txt", ".js"):
                if name.endswith(suffix):
                    name = name[: -len(suffix)]
                    break
            else:
                name = stem_of(Path(line))
            stems.add(name.split("(", 1)[0])
    return sorted(stems)


def iter_stems():
    """Every case stem pristine has an artifact for, with its source text lazily readable."""
    stems: dict[str, Path | None] = {}
    for c in iter_cases():
        stems.setdefault(stem_of(c), c)
    for b in BASELINES.iterdir():
        n = b.name
        for suffix in (".js", ".errors.txt"):
            if n.endswith(suffix):
                stem = n[: -len(suffix)].split("(", 1)[0]
                stems.setdefault(stem, None)
                break
    return stems


_CASE_INDEX: dict[str, Path] | None = None


def case_index() -> dict[str, Path]:
    global _CASE_INDEX
    if _CASE_INDEX is None:
        idx: dict[str, Path] = {}
        for c in iter_cases():
            idx.setdefault(stem_of(c), c)
        _CASE_INDEX = idx
    return _CASE_INDEX


def stem_of(case: Path) -> str:
    name = case.name
    for suf in (".d.ts", ".tsx", ".jsx", ".mts", ".cts", ".ts", ".js"):
        if name.endswith(suf):
            return name[: -len(suf)]
    return case.stem



# ---------------------------------------------------------------- extraction


_DIRECTIVE_LINE = re.compile(r"^[ \t]*//[ \t]*@[A-Za-z]+[ \t]*:")


def _baseline_sources_exist(stem: str) -> bool:
    """Can a baseline supply this case's source? (`.js` echo, or an errors annotation.)"""
    for b in BASELINES.glob(f"{stem}*.js"):
        rest = b.name[len(stem):-len(".js")]
        if rest == "" or rest.startswith("("):
            return True
    return bool(baselines_for(stem))


def _strip_harness_directives(text: str) -> str:
    """Drop the `// @key: value` harness directives, as tsc's own harness does.

    Empirically pinned against the baselines: the directive lines AND the blank run that
    follows them are absent from the `==== file ====` body, so pristine's line 1 is the
    first line of real source.  Directives elsewhere in the file are dropped in place.
    """
    lines = text.split("\n")
    out: list[str] = []
    started = False
    for line in lines:
        if _DIRECTIVE_LINE.match(line):
            continue
        if not started and line.strip() == "":
            continue
        started = True
        out.append(line)
    return "\n".join(out)


_JS_SECTION = re.compile(r"^//// \[([^\]]+)\]\s*$")
_ERR_SECTION = re.compile(r"^==== (.+?) \(\d+ errors?\) ====\s*$")


def extract_sources(stem: str) -> dict[str, str]:
    """Pristine's own INPUT files for a case, recovered from its baselines.

    Prefers the `.js` baseline (it echoes every input verbatim before the emitted
    output); falls back to the `==== file (n errors) ====` banners of `.errors.txt`,
    whose body is indented by exactly four spaces.
    """
    files: dict[str, str] = {}
    # A single-file case that emits nothing (declaration-only, or `@noEmit`) has neither a
    # `.js` nor an `.errors.txt` baseline; its source survives only in `tests/cases`.
    #
    # THE CASE FILE IS THE LAST RESORT, NOT THE FIRST CHOICE (round 941).  It still carries
    # the `// @target: …` harness directives that tsc's own harness STRIPS before compiling,
    # so its line numbers are the baseline's plus the directive count -- and a sweep that
    # differences (line, code) then reports EVERY row as a divergence in both directions.
    # The pre-941 guard only looked for an exact `<stem>.js`, so every multi-variation case
    # (`<stem>(target=es2015).js`) fell through to it: 27 of the sweep's 630 fixtures, and
    # the single largest source of phantom "ours-only" rows.
    case = case_index().get(stem)
    if case is not None and not _baseline_sources_exist(stem):
        text = case.read_text(errors="replace")
        if "@filename" not in text.lower():
            return {case.name: _strip_harness_directives(text)}
    for b in sorted(BASELINES.glob(f"{stem}*.js")):
        rest = b.name[len(stem):-len(".js")]
        if rest != "" and not rest.startswith("("):
            continue
        cur, buf, seen_output = None, [], set()
        for line in b.read_text(errors="replace").splitlines():
            m = _JS_SECTION.match(line)
            if m:
                if cur and cur not in seen_output:
                    files.setdefault(cur, "\n".join(buf))
                name = m.group(1)
                base = name.rsplit("/", 1)[-1]
                # the opening `//// [tests/cases/.../x.ts] ////` banner is a path, not a file
                cur = None if line.rstrip().endswith("////") else base
                if cur and (cur.endswith((".js", ".jsx", ".mjs", ".cjs", ".d.ts", ".map"))):
                    seen_output.add(cur)
                    cur = None
                buf = []
                continue
            if cur is not None:
                buf.append(line)
        if cur and cur not in seen_output:
            files.setdefault(cur, "\n".join(buf))
        if files:
            return files
    for b in baselines_for(stem):
        cur, buf = None, []
        for line in annotated_source(b).splitlines():
            m = _ERR_SECTION.match(line)
            if m:
                if cur:
                    files.setdefault(cur, "\n".join(buf))
                cur, buf = m.group(1).rsplit("/", 1)[-1], []
                continue
            if cur is None:
                continue
            if line.startswith("!!!") or line.strip(" \t~") == "" and set(line.strip()) == {"~"}:
                continue
            if line.lstrip().startswith("~") and line.strip("~ \t") == "":
                continue
            buf.append(line[4:] if line.startswith("    ") else line)
        if cur:
            files.setdefault(cur, "\n".join(buf))
        if files:
            return files
    return files


# ---------------------------------------------------------------- reporting

def report(stem: str, case: Path | None, errbases: list[Path], args, gen_err: set[str],
           gen_any: set[str], gen_known: bool) -> None:
    print(f"\n=== {stem}")
    if case is not None:
        print(f"    case:     {case.relative_to(REPO)}")
    if not errbases:
        print("    PRISTINE: SILENT (no .errors.txt baseline exists for this case)")
    for b in errbases:
        diags = diagnostics_of(b)
        if args.code and not any(c == args.code for c, _ in diags):
            continue
        if gen_known:
            if b.name in gen_err:
                active = "ACTIVE (this errors baseline is a generated, byte-exact gate)"
            elif any(n.startswith(stem) for n in gen_any):
                active = "case generated, but this ERRORS baseline is NOT a subtest"
            else:
                active = "NOT GENERATED (skipped by the harness -- the suite does not gate it)"
        else:
            active = "unknown (generated tests not built)"
        print(f"    baseline: {b.name}  [{active}]")
        for code, line in diags:
            mark = " <<<" if args.code and code == args.code else ""
            print(f"      {line}{mark}")
        if args.full:
            print("    --- annotated source ---")
            for line in annotated_source(b).splitlines():
                print(f"    {line}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--code", help="diagnostic code, with or without the TS prefix (e.g. 2717)")
    ap.add_argument("--pattern", help="regex searched against the CASE source text")
    ap.add_argument("--fixture", help="a case stem, e.g. dynamicNamesErrors")
    ap.add_argument("--show", type=int, default=0, help="cap the number of results printed (0 = all)")
    ap.add_argument("--full", action="store_true", help="also print the annotated source of each baseline")
    ap.add_argument("--ignore-case", action="store_true")
    ap.add_argument("--extract", metavar="DIR",
                    help="write the fixture's PRISTINE input files into DIR (with --fixture), "
                         "so our own compiler can be run over exactly what pristine tsc saw")
    args = ap.parse_args()

    if not BASELINES.is_dir():
        die(f"no pristine baselines at {BASELINES} -- clone typescript-repo first")
    if not (args.code or args.pattern or args.fixture):
        die("give at least one of --code / --pattern / --fixture")
    if args.code:
        args.code = args.code.upper().removeprefix("TS")

    gen_err, gen_any, note = load_generated_index()
    gen_known = bool(gen_any)
    print(f"pristine corpus: {BASELINES.relative_to(REPO)}")
    print(f"generated index: {note}")

    results = 0
    truncated = False

    if args.fixture:
        cases = [c for c in [case_index().get(args.fixture)] if c is not None]
        errbases = baselines_for(args.fixture)
        js = [b for b in BASELINES.glob(f"{args.fixture}*.js")
              if b.name[len(args.fixture):-3] == "" or b.name[len(args.fixture):-3].startswith("(")]
        if not cases and not errbases and not js:
            print(f"\nno case and no baseline named {args.fixture!r}")
            return 1
        report(args.fixture, cases[0] if cases else None, errbases, args, gen_err, gen_any, gen_known)
        if args.extract:
            out = Path(args.extract)
            out.mkdir(parents=True, exist_ok=True)
            files = extract_sources(args.fixture)
            if not files:
                die(f"could not recover any input file for {args.fixture!r}")
            for name, text in files.items():
                (out / name).parent.mkdir(parents=True, exist_ok=True)
                (out / name).write_text(text.rstrip() + "\n")
                print(f"    extracted: {out / name}")
        return 0

    if args.pattern:
        for stem in grep_stems(args.pattern, args.ignore_case):
            case = case_index().get(stem)
            errbases = baselines_for(stem)
            if args.code and not any(
                any(c == args.code for c, _ in diagnostics_of(b)) for b in errbases
            ):
                continue
            if args.show and results >= args.show:
                truncated = True
                results += 1
                continue
            report(stem, case, errbases, args, gen_err, gen_any, gen_known)
            results += 1
    else:  # --code alone: sweep the baselines
        needle = f"error TS{args.code}:"
        for b in sorted(BASELINES.glob("*.errors.txt")):
            head = b.read_text(errors="replace").split("\n====", 1)[0]
            if needle not in head:
                continue
            stem = b.name[: -len(".errors.txt")]
            stem = stem.split("(", 1)[0]
            if args.show and results >= args.show:
                truncated = True
                results += 1
                continue
            case = case_index().get(stem)
            report(stem, case, [b], args, gen_err, gen_any, gen_known)
            results += 1

    print(f"\n{results} result(s)" + (" (output capped by --show)" if truncated else ""))
    return 0 if results else 1


if __name__ == "__main__":
    sys.exit(main())
