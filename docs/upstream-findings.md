# Upstream-reportable tsgo findings

A divergence between this compiler and `tsgo 7.0.2` that is in **neither**
`typescript-go-repo/testdata/submoduleTriaged.txt` (tsgo's known defects) **nor**
`submoduleAccepted.txt` (divergences tsgo has adopted) is a finding tsgo does not know
about. It belongs here as an **issue candidate** — a minimal `.ts` repro, tsgo's output,
the intended answer and why — per the owner directive of 2026-09-21 in CLAUDE.md
(§ "AI agent mission"). Bulk test PRs are refused by that directive; one issue per
finding, with a fixture attached only if upstream asks.

Check both manifests before adding a row. As of 2026-09-21, 24 of the 41 rows in
`docs/logical-parity.md` cite `submoduleTriaged` — i.e. every tsgo defect recorded so far
was already known upstream — so this file starts empty.

| Date | Shape (repro path) | tsgo answers | Intended answer | Status |
|------|--------------------|--------------|-----------------|--------|
