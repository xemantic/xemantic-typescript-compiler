// The (TSGO.3) EMIT oracle (docs/goport-emit-oracle.md).
//
//	emit -cases-root DIR [-baselines DIR] [-o OUT] PROJECT_DIR
//	emit -cases-root DIR [-baselines DIR] -batch LIST -status FILE
//	    compile a materialized configuration through the UNMODIFIED compiler runner
//	    (newCompilerTest: CompileFiles with emit) and render the three emit baselines its
//	    runSingleConfigTest writes — `output` (.js), `sourcemap` (.js.map) and `sourcemap record`
//	    (.sourcemap.txt) — into one framed file (emitFrame). With -baselines every rendered baseline
//	    is also compared byte for byte with tsgo's committed baseline.
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/microsoft/typescript-go/internal/testrunner"
	"github.com/microsoft/typescript-go/internal/testutil/harnessutil"
)

type emitStatus struct {
	Dir       string `json:"dir"`
	Out       string `json:"out"`
	Status    string `json:"status"` // ok | error | fatal | skipped
	Reason    string `json:"reason,omitempty"`
	OutSha256 string `json:"outSha256,omitempty"`
	// per artifact kind: the artifact status and, with -baselines, the committed-baseline verdict
	// (equal | differ | missing-expected | unexpected-present | n/a)
	Artifacts map[string]string `json:"artifacts,omitempty"`
	Baselines map[string]string `json:"baselines,omitempty"`
}

// emitFrame renders a configuration's artifacts as one byte-exact file, the format both the oracle
// and the port write (EmitParityTest):
//
//	== <kind>\t<status>\t<baseline>\t<byte length>\n<content bytes>\n
//
// A failed artifact's content (a panic message) is not part of the frame.
func emitFrame(arts []*testrunner.XtscEmitArtifact) string {
	var b strings.Builder
	for _, a := range arts {
		c := a.Content
		if a.Status != "ok" {
			c = ""
		}
		fmt.Fprintf(&b, "== %s\t%s\t%s\t%d\n", a.Kind, a.Status, a.Baseline, len(c))
		b.WriteString(c)
		b.WriteString("\n")
	}
	return b.String()
}

func emitHeader(rel string) string { return "tests/cases/" + strings.TrimPrefix(rel, localPrefix) }

func cmdEmit(args []string) error {
	fl := flag.NewFlagSet("emit", flag.ExitOnError)
	casesRoot := fl.String("cases-root", "", "build/goport/diag-src")
	baselines := fl.String("baselines", "", "typescript-go-repo/testdata/baselines/reference: compare against tsgo's committed emit baselines")
	out := fl.String("o", "", "output file (default stdout; single project)")
	batch := fl.String("batch", "", "file of PROJECT_DIR<TAB>OUT lines")
	status := fl.String("status", "", "status JSONL (with -batch)")
	fl.Parse(args)
	if *casesRoot == "" {
		return fmt.Errorf("emit needs -cases-root")
	}
	if abs, err := filepath.Abs(*casesRoot); err == nil {
		*casesRoot = abs
	}
	type item struct{ dir, out string }
	var items []item
	if *batch != "" {
		raw, err := os.ReadFile(*batch)
		if err != nil {
			return err
		}
		for _, l := range strings.Split(string(raw), "\n") {
			if l = strings.TrimRight(l, "\r"); l == "" {
				continue
			}
			parts := strings.SplitN(l, "\t", 2)
			if len(parts) != 2 {
				return fmt.Errorf("bad batch line %q", l)
			}
			items = append(items, item{parts[0], parts[1]})
		}
	} else {
		if fl.NArg() != 1 {
			return fmt.Errorf("emit needs one PROJECT_DIR (or -batch)")
		}
		items = append(items, item{fl.Arg(0), *out})
	}
	var statusW *bufio.Writer
	if *status != "" {
		f, err := os.Create(*status)
		if err != nil {
			return err
		}
		defer f.Close()
		statusW = bufio.NewWriter(f)
		defer statusW.Flush()
	}
	var firstErr error
	withT(func(t *testing.T) {
		for _, it := range items {
			st := emitOne(t, *casesRoot, it.dir, it.out, *baselines)
			b, _ := json.Marshal(st)
			if statusW != nil {
				statusW.Write(append(b, '\n'))
				statusW.Flush()
			} else {
				fmt.Fprintln(os.Stderr, string(b))
				if st.Status != "ok" {
					firstErr = fmt.Errorf("%s: %s %s", it.dir, st.Status, st.Reason)
				}
			}
		}
	})
	return firstErr
}

func emitOne(t *testing.T, casesRoot, dir, out, baselines string) emitStatus {
	st := emitStatus{Dir: dir, Out: out}
	cj, err := readCase(dir)
	if err != nil {
		st.Status, st.Reason = "error", err.Error()
		return st
	}
	filename := filepath.Join(casesRoot, cj.Case)
	var arts []*testrunner.XtscEmitArtifact
	var problem string
	r := runUnit(t, caseSubtestName(cj.Case, cj.Variation), func(t *testing.T) {
		content, configs := testrunner.XtscConfigurations(t, filename)
		if sha(content) != cj.CaseSha256 {
			problem = "case source changed since materialize (re-run the materializer)"
			return
		}
		var named *harnessutil.NamedTestConfiguration
		testName := ""
		found := false
		for _, c := range configs {
			if variationDir(c.Name) == cj.Variation {
				named, testName, found = c.Named, c.TestName, true
			}
		}
		if !found {
			problem = "variation not found: " + cj.Variation
			return
		}
		arts = testrunner.XtscEmitBaselines(t, testName, filename, content, named, emitHeader(cj.Case))
	})
	switch {
	case problem != "":
		st.Status, st.Reason = "error", problem
		return st
	case r.failed || arts == nil:
		st.Status, st.Reason = "fatal", "newCompilerTest failed or panicked (see log)"
		return st
	case r.skipped:
		st.Status, st.Reason = "skipped", "harness skip"
		return st
	}
	text := emitFrame(arts)
	if out == "" {
		os.Stdout.WriteString(text)
	} else {
		if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
			st.Status, st.Reason = "error", err.Error()
			return st
		}
		tmp := out + ".tmp"
		if err := os.WriteFile(tmp, []byte(text), 0o644); err != nil {
			st.Status, st.Reason = "error", err.Error()
			return st
		}
		if err := os.Rename(tmp, out); err != nil {
			st.Status, st.Reason = "error", err.Error()
			return st
		}
	}
	st.Status, st.OutSha256 = "ok", sha(text)
	st.Artifacts = map[string]string{}
	for _, a := range arts {
		st.Artifacts[a.Kind] = a.Status
	}
	if baselines != "" {
		st.Baselines = map[string]string{}
		sub := cj.Suite + "/"
		if !strings.HasPrefix(cj.Case, localPrefix) {
			sub = "submodule/" + sub
		}
		const noContent = "<no content>"
		for _, a := range arts {
			if a.Status != "ok" {
				st.Baselines[a.Kind] = "n/a"
				continue
			}
			b, err := os.ReadFile(filepath.Join(baselines, filepath.FromSlash(sub+a.Baseline)))
			switch {
			case err != nil && a.Content == noContent:
				st.Baselines[a.Kind] = "equal"
			case err != nil:
				st.Baselines[a.Kind] = "missing-expected"
			case a.Content == noContent:
				st.Baselines[a.Kind] = "unexpected-present"
			case string(b) == a.Content:
				st.Baselines[a.Kind] = "equal"
			default:
				st.Baselines[a.Kind] = "differ"
			}
		}
	}
	return st
}
