// OVERLAY FILE (docs/goport-diag-oracle.md): compiled into internal/repo by
// oracle-go/build.sh through `go build -overlay`; it is never written into typescript-go-repo.
//
// The compiler test harness reads `tests/lib` (the `/.lib` test libraries, react.d.ts & co.) from
// the `_submodules/TypeScript` checkout, which is EMPTY in this clone. XTSC_TS_SUBMODULE points it
// at a directory the diag oracle extracts from typescript-repo (the same commit, 4d4f005c).
package repo

import (
	"os"
	"sync"
)

func init() {
	if dir := os.Getenv("XTSC_TS_SUBMODULE"); dir != "" {
		typeScriptSubmodulePath = sync.OnceValue(func() string { return dir })
	}
}
