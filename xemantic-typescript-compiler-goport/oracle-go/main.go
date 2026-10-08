// tsgo-oracle: a dev tool for the (TSGO.1) encoded-AST gate (docs/goport-oracle.md).
//
// It imports typescript-go's internal/ packages, which Go only allows from inside the
// typescript-go module. It is therefore NOT built as its own module: build.sh compiles it
// as the virtual package cmd/xtsc-oracle of typescript-go-repo through `go build -overlay`,
// so nothing is written into typescript-go-repo.
//
// Commands:
//
//	encode [-parse-only] [-jsx] [-force] [-like <oracle.bin>] [-o out.bin] <file>
//	    parse + bind + encoder.EncodeSourceFile in-process, exactly as tsgo's project
//	    system does for getSourceFile. -parse-only skips the binder.
//	crosscheck [-parse-only] [-limit N] <manifest.json>
//	    re-encode every ok manifest entry in-process (parse options taken from the
//	    oracle file's own header) and compare with the oracle bytes.
//	dump [-indices] [-no-strings] <file.bin>
//	    a line-per-node rendering of an encoded AST, stable for `diff`.
//	materialize / diags
//	    the (TSGO.2) diagnostics oracle: see diags.go and docs/goport-diag-oracle.md.
//	api -tsc <tsc> -out <file.jsonl> <tsconfig.json>
//	    the (TSGO.3-b) type-oracle recording: see api.go and docs/goport-api.md.
package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"github.com/microsoft/typescript-go/internal/api/encoder"
	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/binder"
	"github.com/microsoft/typescript-go/internal/core"
	"github.com/microsoft/typescript-go/internal/parser"
	"github.com/microsoft/typescript-go/internal/tspath"
	"github.com/microsoft/typescript-go/internal/vfs/osvfs"
	"github.com/zeebo/xxh3"
)

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	var err error
	switch os.Args[1] {
	case "encode":
		err = cmdEncode(os.Args[2:])
	case "crosscheck":
		err = cmdCrosscheck(os.Args[2:])
	case "dump":
		err = cmdDump(os.Args[2:])
	case "materialize":
		err = cmdMaterialize(os.Args[2:])
	case "diags":
		err = cmdDiags(os.Args[2:])
	case "emit":
		err = cmdEmit(os.Args[2:])
	case "api":
		err = cmdAPI(os.Args[2:])
	default:
		usage()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: tsgo-oracle encode|crosscheck|dump|materialize|diags|emit|api ... (see docs/goport-oracle.md, docs/goport-diag-oracle.md, docs/goport-emit-oracle.md, docs/goport-api.md)")
	os.Exit(2)
}

// encodeFile reproduces compiler/fileloader.go parseSourceFile + compiler/host.go GetSourceFile
// + project.go BindSourceFiles + api/session.go handleGetSourceFile.
//
// recordAs, when non-empty, is the absolute fileName recorded in the AST instead of the file's own
// path (and the source of its ScriptKind): committed pin fixtures are encoded under a stable
// virtual name so their expected bytes do not depend on where the checkout lives.
func encodeFile(fileName string, opts ast.ExternalModuleIndicatorOptions, parseOnly bool, recordAs string) ([]byte, error) {
	fs := osvfs.FS()
	abs, err := filepath.Abs(fileName)
	if err != nil {
		return nil, err
	}
	normalized := tspath.NormalizePath(abs)
	text, ok := fs.ReadFile(normalized) // same BOM/UTF-16 decoding as the binary
	if !ok {
		return nil, fmt.Errorf("cannot read %s", normalized)
	}
	if recordAs != "" {
		normalized = tspath.NormalizePath(recordAs)
	}
	scriptKind := core.GetScriptKindFromFileName(normalized)
	if scriptKind == core.ScriptKindUnknown {
		return nil, fmt.Errorf("unknown script kind for %s", normalized)
	}
	sf := parser.ParseSourceFile(ast.SourceFileParseOptions{
		FileName:                       normalized,
		Path:                           tspath.ToPath(normalized, "", fs.UseCaseSensitiveFileNames()),
		ExternalModuleIndicatorOptions: opts,
	}, text, scriptKind)
	sf.Hash = xxh3.HashString128(text) // project/parsecache.go: file.Hash = fh.Hash()
	if !parseOnly {
		binder.BindSourceFile(sf)
	}
	data, _, err := encoder.EncodeSourceFile(sf)
	return data, err
}

func optsFromHeader(b []byte) (ast.ExternalModuleIndicatorOptions, error) {
	if len(b) < encoder.HeaderSize {
		return ast.ExternalModuleIndicatorOptions{}, fmt.Errorf("short header")
	}
	bits := binary.LittleEndian.Uint32(b[encoder.HeaderOffsetParseOptions:])
	return ast.ExternalModuleIndicatorOptions{JSX: bits&1 != 0, Force: bits&2 != 0}, nil
}

func cmdEncode(args []string) error {
	fl := flag.NewFlagSet("encode", flag.ExitOnError)
	parseOnly := fl.Bool("parse-only", false, "skip the binder (the binary always binds)")
	jsx := fl.Bool("jsx", false, "ExternalModuleIndicatorOptions.JSX")
	force := fl.Bool("force", false, "ExternalModuleIndicatorOptions.Force")
	like := fl.String("like", "", "take JSX/Force from this oracle file's header")
	out := fl.String("o", "", "output file (default stdout)")
	as := fl.String("as", "", "record this absolute fileName in the AST instead of FILE's path (pin fixtures)")
	fl.Parse(args)
	if fl.NArg() != 1 {
		return fmt.Errorf("encode needs exactly one file")
	}
	opts := ast.ExternalModuleIndicatorOptions{JSX: *jsx, Force: *force}
	if *like != "" {
		b, err := os.ReadFile(*like)
		if err != nil {
			return err
		}
		if opts, err = optsFromHeader(b); err != nil {
			return err
		}
	}
	data, err := encodeFile(fl.Arg(0), opts, *parseOnly, *as)
	if err != nil {
		return err
	}
	if *out == "" {
		_, err = os.Stdout.Write(data)
		return err
	}
	return os.WriteFile(*out, data, 0o644)
}

type manifestEntry struct {
	Input     string `json:"input"`
	FileName  string `json:"fileName"`
	Output    string `json:"output"`
	OutSha256 string `json:"outSha256"`
	Error     string `json:"error"`
	Source    string `json:"source"`
}

type manifest struct {
	OracleDir string          `json:"oracleDir"`
	Files     []manifestEntry `json:"files"`
}

func cmdCrosscheck(args []string) error {
	fl := flag.NewFlagSet("crosscheck", flag.ExitOnError)
	parseOnly := fl.Bool("parse-only", false, "compare parse-only bytes (expected to differ only in binder flags)")
	limit := fl.Int("limit", 0, "check at most N entries (spread evenly over the manifest); 0 = all")
	verbose := fl.Bool("v", false, "print every mismatch")
	fl.Parse(args)
	if fl.NArg() != 1 {
		return fmt.Errorf("crosscheck needs the manifest")
	}
	raw, err := os.ReadFile(fl.Arg(0))
	if err != nil {
		return err
	}
	var m manifest
	if err := json.Unmarshal(raw, &m); err != nil {
		return err
	}
	var entries []manifestEntry
	for _, e := range m.Files {
		if e.Error == "" && e.Output != "" {
			entries = append(entries, e)
		}
	}
	if *limit > 0 && *limit < len(entries) {
		step := float64(len(entries)) / float64(*limit)
		var picked []manifestEntry
		for i := 0; i < *limit; i++ {
			picked = append(picked, entries[int(float64(i)*step)])
		}
		entries = picked
	}
	equal, differ := 0, 0
	byExt := map[string][2]int{}
	flagOnly := 0
	flagBits := map[int]int{}
	for _, e := range entries {
		want, err := os.ReadFile(filepath.Join(m.OracleDir, e.Output))
		if err != nil {
			return err
		}
		opts, err := optsFromHeader(want)
		if err != nil {
			return fmt.Errorf("%s: %w", e.Output, err)
		}
		got, err := encodeFile(e.FileName, opts, *parseOnly, "")
		ext := extOf(e.FileName)
		c := byExt[ext]
		if err == nil && bytes.Equal(got, want) {
			equal++
			c[0]++
		} else {
			differ++
			c[1]++
			if err == nil && len(got) == len(want) && onlyFlagBytesDiffer(got, want) {
				flagOnly++
				off := int(binary.LittleEndian.Uint32(want[encoder.HeaderOffsetNodes:]))
				for i := off + encoder.NodeOffsetFlags; i+4 <= len(want); i += encoder.NodeSize {
					x := binary.LittleEndian.Uint32(got[i:]) ^ binary.LittleEndian.Uint32(want[i:])
					for bit := 0; bit < 32; bit++ {
						if x&(1<<bit) != 0 {
							flagBits[bit]++
						}
					}
				}
			}
			if *verbose {
				fmt.Printf("MISMATCH %s (err=%v, got %d bytes, want %d)\n", e.FileName, err, len(got), len(want))
			}
		}
		byExt[ext] = c
	}
	exts := make([]string, 0, len(byExt))
	for k := range byExt {
		exts = append(exts, k)
	}
	sort.Strings(exts)
	for _, k := range exts {
		fmt.Printf("  %-5s equal=%d differ=%d\n", k, byExt[k][0], byExt[k][1])
	}
	for bit := 0; bit < 32; bit++ {
		if flagBits[bit] > 0 {
			fmt.Printf("  differing node-flag bit %d (%s): %d nodes\n", bit, nodeFlagNames[bit], flagBits[bit])
		}
	}
	fmt.Printf("crosscheck parseOnly=%v: %d equal, %d differ (%d differ only in node-flag words)\n", *parseOnly, equal, differ, flagOnly)
	if differ > 0 && !*parseOnly {
		os.Exit(1)
	}
	return nil
}

func extOf(name string) string {
	for _, e := range []string{".d.ts", ".d.mts", ".d.cts", ".tsx", ".jsx", ".mts", ".cts", ".mjs", ".cjs", ".ts", ".js"} {
		if strings.HasSuffix(name, e) {
			return e
		}
	}
	return filepath.Ext(name)
}

// onlyFlagBytesDiffer: every differing byte lies in a node record's flags word.
func onlyFlagBytesDiffer(a, b []byte) bool {
	nodesOff := int(binary.LittleEndian.Uint32(a[encoder.HeaderOffsetNodes:]))
	if nodesOff != int(binary.LittleEndian.Uint32(b[encoder.HeaderOffsetNodes:])) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			if i < nodesOff || (i-nodesOff)%encoder.NodeSize < encoder.NodeOffsetFlags {
				return false
			}
		}
	}
	return true
}

// ---------------------------------------------------------------- dump

type decoded struct {
	b                                             []byte
	strOff, strData, extData, structData, nodeOff uint32
	nodeCount                                     int
}

func (d *decoded) u32(off uint32) uint32 {
	if int(off)+4 > len(d.b) {
		return 0xDEADBEEF
	}
	return binary.LittleEndian.Uint32(d.b[off:])
}

func (d *decoded) node(i int, field int) uint32 {
	return d.u32(d.nodeOff + uint32(i*encoder.NodeSize+field))
}

func (d *decoded) str(idx uint32) string {
	so := d.strOff + idx*4
	if so+8 > d.strData {
		return fmt.Sprintf("<bad string index %d>", idx)
	}
	s, e := d.u32(so), d.u32(so+4)
	if e < s || d.strData+e > d.extData {
		return fmt.Sprintf("<bad string range %d..%d>", s, e)
	}
	return string(d.b[d.strData+s : d.strData+e])
}

func q(s string, max int) string {
	if max > 0 && len(s) > max {
		return strconv.Quote(s[:max]) + fmt.Sprintf("...(%d bytes)", len(s))
	}
	return strconv.Quote(s)
}

var nodeFlagNames = []string{
	"Let", "Const", "Using", "Reparsed", "Synthesized", "OptionalChain", "ExportContext", "ContainsThis",
	"HasImplicitReturn", "HasExplicitReturn", "DisallowInContext", "YieldContext", "DecoratorContext", "AwaitContext",
	"DisallowConditionalTypesContext", "ThisNodeHasError", "JavaScriptFile", "ThisNodeOrAnySubNodesHasError",
	"HasAsyncFunctions", "PossiblyContainsDynamicImport", "PossiblyContainsImportMeta", "HasJSDoc", "JSDoc",
	"Ambient", "InWithStatement", "JsonFile", "PossiblyContainsDeprecatedTag", "Unreachable",
	"ReparserTransformedLiteral",
}

func flagString(f uint32) string {
	if f == 0 {
		return ""
	}
	var parts []string
	for i := 0; i < 32; i++ {
		if f&(1<<i) != 0 {
			if i < len(nodeFlagNames) {
				parts = append(parts, nodeFlagNames[i])
			} else {
				parts = append(parts, fmt.Sprintf("bit%d", i))
			}
		}
	}
	return " flags=" + strings.Join(parts, "|")
}

// minimal msgpack reader for the structured-data section
func (d *decoded) msgpack(off uint32) (string, uint32) {
	b := d.b
	if int(off) >= len(b) {
		return "<eof>", off
	}
	t := b[off]
	switch {
	case t <= 0x7f:
		return strconv.Itoa(int(t)), off + 1
	case t&0xf0 == 0x90 || t == 0xdc || t == 0xdd:
		var n int
		switch t {
		case 0xdc:
			n = int(binary.BigEndian.Uint16(b[off+1:]))
			off += 3
		case 0xdd:
			n = int(binary.BigEndian.Uint32(b[off+1:]))
			off += 5
		default:
			n = int(t & 0x0f)
			off++
		}
		parts := make([]string, n)
		for i := range parts {
			parts[i], off = d.msgpack(off)
		}
		return "[" + strings.Join(parts, ",") + "]", off
	case t&0xe0 == 0xa0 || t == 0xd9 || t == 0xda || t == 0xdb:
		var n int
		switch t {
		case 0xd9:
			n = int(b[off+1])
			off += 2
		case 0xda:
			n = int(binary.BigEndian.Uint16(b[off+1:]))
			off += 3
		case 0xdb:
			n = int(binary.BigEndian.Uint32(b[off+1:]))
			off += 5
		default:
			n = int(t & 0x1f)
			off++
		}
		return strconv.Quote(string(b[off : off+uint32(n)])), off + uint32(n)
	case t == 0xcc:
		return strconv.Itoa(int(b[off+1])), off + 2
	case t == 0xcd:
		return strconv.Itoa(int(binary.BigEndian.Uint16(b[off+1:]))), off + 3
	case t == 0xce:
		return strconv.Itoa(int(binary.BigEndian.Uint32(b[off+1:]))), off + 5
	case t == 0xc2:
		return "false", off + 1
	case t == 0xc3:
		return "true", off + 1
	}
	return fmt.Sprintf("<msgpack 0x%02x>", t), off + 1
}

func (d *decoded) structured(off uint32) string {
	if off == 0xFFFFFFFF {
		return "-"
	}
	s, _ := d.msgpack(d.structData + off)
	return s
}

func cmdDump(args []string) error {
	fl := flag.NewFlagSet("dump", flag.ExitOnError)
	indices := fl.Bool("indices", false, "print node indices, parent and next-sibling links (shift after an insertion)")
	noStrings := fl.Bool("no-strings", false, "omit the string-table listing")
	maxStr := fl.Int("max-string", 80, "truncate quoted strings to N bytes (0 = never)")
	fl.Parse(args)
	if fl.NArg() != 1 {
		return fmt.Errorf("dump needs one .bin file")
	}
	b, err := os.ReadFile(fl.Arg(0))
	if err != nil {
		return err
	}
	if len(b) < encoder.HeaderSize {
		return fmt.Errorf("file shorter than the %d-byte header", encoder.HeaderSize)
	}
	h := func(o int) uint32 { return binary.LittleEndian.Uint32(b[o:]) }
	d := &decoded{b: b, strOff: h(encoder.HeaderOffsetStringOffsets), strData: h(encoder.HeaderOffsetStringData),
		extData: h(encoder.HeaderOffsetExtendedData), structData: h(encoder.HeaderOffsetStructuredData), nodeOff: h(encoder.HeaderOffsetNodes)}
	if int(d.nodeOff) > len(b) || (len(b)-int(d.nodeOff))%encoder.NodeSize != 0 {
		fmt.Printf("WARNING: nodes section (%d..%d) is not a whole number of %d-byte records\n", d.nodeOff, len(b), encoder.NodeSize)
	}
	d.nodeCount = (len(b) - int(d.nodeOff)) / encoder.NodeSize
	fmt.Printf("header version=%d hash=%08x%08x%08x%08x parseOptions=%#x (jsx=%v force=%v)\n",
		b[3], h(16), h(12), h(8), h(4), h(20), h(20)&1 != 0, h(20)&2 != 0)
	fmt.Printf("sections stringOffsets=%d stringData=%d extended=%d structured=%d nodes=%d total=%d nodeCount=%d strings=%d\n",
		d.strOff, d.strData, d.extData, d.structData, d.nodeOff, len(b), d.nodeCount, (d.strData-d.strOff)/8)

	// children of each node, in record order
	children := make([][]int, d.nodeCount)
	depth := make([]int, d.nodeCount)
	for i := 1; i < d.nodeCount; i++ {
		p := int(d.node(i, encoder.NodeOffsetParent))
		if i > 1 && p < i && p >= 1 {
			depth[i] = depth[p] + 1
			children[p] = append(children[p], i)
		}
	}
	for i := 1; i < d.nodeCount; i++ {
		kind := d.node(i, encoder.NodeOffsetKind)
		pos, end := d.node(i, encoder.NodeOffsetPos), d.node(i, encoder.NodeOffsetEnd)
		data := d.node(i, encoder.NodeOffsetData)
		fl := d.node(i, encoder.NodeOffsetFlags)
		var sb strings.Builder
		sb.WriteString(strings.Repeat("  ", depth[i]))
		if *indices {
			fmt.Fprintf(&sb, "#%d p=%d n=%d ", i, d.node(i, encoder.NodeOffsetParent), d.node(i, encoder.NodeOffsetNext))
		}
		if kind == encoder.SyntaxKindNodeList {
			fmt.Fprintf(&sb, "[NodeList] %d..%d len=%d", pos, end, data)
			if fl != 0 {
				fmt.Fprintf(&sb, " flags=%#x", fl)
			}
		} else {
			fmt.Fprintf(&sb, "%s %d..%d", ast.Kind(kind).String(), pos, end)
			common := (data >> 24) & 0x3f
			switch data & encoder.NodeDataTypeMask {
			case encoder.NodeDataTypeChildren:
				if common != 0 {
					fmt.Fprintf(&sb, " common=%#x", common)
				}
				if m := data & 0xff; m != 0 {
					fmt.Fprintf(&sb, " mask=%08b", m)
				}
				if data&0x00ffff00 != 0 {
					fmt.Fprintf(&sb, " junk=%#x", data)
				}
			case encoder.NodeDataTypeString:
				if common != 0 {
					fmt.Fprintf(&sb, " common=%#x", common)
				}
				fmt.Fprintf(&sb, " text=%s", q(d.str(data&encoder.NodeDataStringIndexMask), *maxStr))
			case encoder.NodeDataTypeExtendedData:
				if common != 0 {
					fmt.Fprintf(&sb, " common=%#x", common)
				}
				eo := d.extData + data&encoder.NodeDataStringIndexMask
				switch ast.Kind(kind) {
				case ast.KindSourceFile:
					fmt.Fprintf(&sb, " fileName=%s path=%s variant=%d scriptKind=%d refs=%s typeRefs=%s libRefs=%s imports=%s augmentations=%s ambientModules=%s externalModuleIndicator=%d textRange=%s",
						q(d.str(d.u32(eo+4)), 0), q(d.str(d.u32(eo+8)), 0), d.u32(eo+12), d.u32(eo+16),
						d.structured(d.u32(eo+20)), d.structured(d.u32(eo+24)), d.structured(d.u32(eo+28)),
						d.structured(d.u32(eo+32)), d.structured(d.u32(eo+36)), d.structured(d.u32(eo+40)), d.u32(eo+44),
						fmt.Sprintf("%d..%d", d.u32(d.strOff+d.u32(eo)*4), d.u32(d.strOff+d.u32(eo)*4+4)))
				case ast.KindTemplateHead, ast.KindTemplateMiddle, ast.KindTemplateTail:
					fmt.Fprintf(&sb, " text=%s raw=%s templateFlags=%#x", q(d.str(d.u32(eo)), *maxStr), q(d.str(d.u32(eo+4)), *maxStr), d.u32(eo+8))
				default:
					fmt.Fprintf(&sb, " text=%s tokenFlags=%#x", q(d.str(d.u32(eo)), *maxStr), d.u32(eo+4))
				}
			default:
				fmt.Fprintf(&sb, " data=%#x(reserved type)", data)
			}
			sb.WriteString(flagString(fl))
		}
		fmt.Println(sb.String())
	}
	if !*noStrings {
		n := (d.strData - d.strOff) / 8
		fileTextEnd := uint32(0)
		if d.nodeCount > 1 && ast.Kind(d.node(1, encoder.NodeOffsetKind)) == ast.KindSourceFile {
			eo := d.extData + d.node(1, encoder.NodeOffsetData)&encoder.NodeDataStringIndexMask
			fileTextEnd = d.u32(d.strOff + d.u32(eo)*4 + 4)
		}
		fmt.Printf("strings appended after the file text (fileText=%d bytes, total string data=%d):\n", fileTextEnd, d.extData-d.strData)
		for i := uint32(0); i < n; i++ {
			s := d.u32(d.strOff + i*8)
			if s >= fileTextEnd && fileTextEnd > 0 {
				fmt.Printf("  str[%d] %s\n", i, q(d.str(i*2), *maxStr))
			}
		}
	}
	return nil
}
