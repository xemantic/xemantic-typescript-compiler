// PORT OVERLAY FILE (docs/goport-diag-oracle.md § 4, the preferred route): ADDED to
// internal/testrunner by goport-extract (go/packages Overlay) so the port carries the runner's own
// case preparation; it is never written into typescript-go-repo. The oracle's overlay
// (oracle-go/overlay/testrunner/xtsc_export.go) is extracted beside it, so the port and the oracle
// run the SAME copies of newCompilerTest's prepare block.
package testrunner

import (
	"testing"

	"github.com/microsoft/typescript-go/internal/testutil/harnessutil"
)

// XtscCaseConfigurations is getCompilerFileBasedTest (compiler_runner.go, tag typescript/v7.0.2)
// without its disk read: the configurations runTest enumerates for a case's text.
func XtscCaseConfigurations(t *testing.T, content string) []*harnessutil.NamedTestConfiguration {
	settings := extractCompilerSettings(content)
	return harnessutil.GetFileBasedTestConfigurations(t, settings, compilerVaryBy)
}
