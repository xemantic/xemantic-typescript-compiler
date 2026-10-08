package main

// The (TSGO.3-b) type-oracle recorder (docs/goport-api.md): drives the SHIPPED tsgo binary's API
// (`tsc --api`, msgpack, synchronous — docs/goport-oracle.md § 1) over a project and records every
// request with its response, one JSON line each. The port's ApiParityTest replays the same requests
// against the in-process session (internal/api, ported) and compares the responses.
//
// The request stream is generated here, from the binary's own answers: for every non-library source
// file of the project, every node of the encoder's node index table (the table a NodeHandle indexes,
// built from this process's parse of the same text, so the handles agree with the server's) is visited:
//
//   - every Identifier/PrivateIdentifier: getSymbolsAtLocations + getTypeAtLocations (batched);
//   - every call-like node: getResolvedSignature;
//   - every argument of a call/new: getContextualType, getTypeAtLocation and, when both are types,
//     isTypeAssignableTo(argument, contextual);
//   - every distinct type any response names: typeToString; a type first met at a location also
//     getPropertiesOfType and getSignaturesOfType(call);
//   - every distinct signature: getReturnTypeOfSignature; every symbol met at a location:
//     getTypeOfSymbol.
//
// Usage: tsgo-oracle api -tsc <tsc binary> -out <file.jsonl> [-files N] <tsconfig.json>

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"

	"github.com/microsoft/typescript-go/internal/api"
	"github.com/microsoft/typescript-go/internal/api/encoder"
	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/vfs/osvfs"
)

type apiClient struct {
	cmd *exec.Cmd
	w   *bufio.Writer
	r   *bufio.Reader
}

func newAPIClient(tsc, cwd string) (*apiClient, error) {
	cmd := exec.Command(tsc, "--api", "-cwd", cwd)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	return &apiClient{cmd: cmd, w: bufio.NewWriterSize(stdin, 1<<16), r: bufio.NewReaderSize(stdout, 1<<16)}, nil
}

func writeBin(w *bufio.Writer, b []byte) {
	switch {
	case len(b) < 1<<8:
		w.WriteByte(0xc4)
		w.WriteByte(byte(len(b)))
	case len(b) < 1<<16:
		w.WriteByte(0xc5)
		binary.Write(w, binary.BigEndian, uint16(len(b)))
	default:
		w.WriteByte(0xc6)
		binary.Write(w, binary.BigEndian, uint32(len(b)))
	}
	w.Write(b)
}

func readBin(r *bufio.Reader) ([]byte, error) {
	t, err := r.ReadByte()
	if err != nil {
		return nil, err
	}
	var n uint32
	switch t {
	case 0xc4:
		b, err := r.ReadByte()
		if err != nil {
			return nil, err
		}
		n = uint32(b)
	case 0xc5:
		var v uint16
		if err := binary.Read(r, binary.BigEndian, &v); err != nil {
			return nil, err
		}
		n = uint32(v)
	case 0xc6:
		if err := binary.Read(r, binary.BigEndian, &n); err != nil {
			return nil, err
		}
	default:
		return nil, fmt.Errorf("expected msgpack bin, got 0x%02x", t)
	}
	b := make([]byte, n)
	_, err = io.ReadFull(r, b)
	return b, err
}

// call sends one request; it returns the JSON payload, or the error message (type 0x05).
func (c *apiClient) call(method string, params any) (json.RawMessage, string, error) {
	var p []byte
	if params != nil {
		var err error
		if p, err = json.Marshal(params); err != nil {
			return nil, "", err
		}
	}
	c.w.WriteByte(0x93)
	c.w.WriteByte(0x01)
	writeBin(c.w, []byte(method))
	writeBin(c.w, p)
	if err := c.w.Flush(); err != nil {
		return nil, "", err
	}
	if b, err := c.r.ReadByte(); err != nil || b != 0x93 {
		return nil, "", fmt.Errorf("bad response header 0x%02x: %v", b, err)
	}
	typ, err := c.r.ReadByte()
	if err != nil {
		return nil, "", err
	}
	if _, err := readBin(c.r); err != nil {
		return nil, "", err
	}
	payload, err := readBin(c.r)
	if err != nil {
		return nil, "", err
	}
	switch typ {
	case 0x04:
		return payload, "", nil
	case 0x05:
		return nil, string(payload), nil
	}
	return nil, "", fmt.Errorf("unexpected message type %d", typ)
}

func (c *apiClient) close() {
	c.cmd.Process.Kill()
	c.cmd.Wait()
}

// apiRecord is one line of the recording: method, params (with the server's handles) and the
// response (r) or error message (e).
type apiRecord struct {
	M string          `json:"m"`
	P json.RawMessage `json:"p"`
	R json.RawMessage `json:"r,omitempty"`
	E string          `json:"e,omitempty"`
}

type apiRecorder struct {
	c        *apiClient
	out      *bufio.Writer
	snapshot uint64
	project  string
	n        int
	// handles first seen, in order
	types      map[uint64]bool
	signatures map[uint64]bool
	symbols    map[uint64]bool
	typeQueue  []uint64
	expandType map[uint64]bool
}

func (rc *apiRecorder) do(method string, params map[string]any) json.RawMessage {
	params["snapshot"] = rc.snapshot
	params["project"] = rc.project
	p, _ := json.Marshal(params)
	r, e, err := rc.c.call(method, json.RawMessage(p))
	if err != nil {
		panic(fmt.Sprintf("%s: %v", method, err))
	}
	// The response bytes verbatim: encoding/json would HTML-escape `<`/`>`/`&` inside the raw payload.
	enc := json.NewEncoder(rc.out)
	enc.SetEscapeHTML(false)
	enc.Encode(apiRecord{M: method, P: p, R: r, E: e})
	rc.n++
	if e != "" {
		return nil
	}
	return r
}

// noteType records a type handle named by a response; expand marks it as met at a location.
func (rc *apiRecorder) noteType(id uint64, expand bool) {
	if id == 0 {
		return
	}
	if !rc.types[id] {
		rc.types[id] = true
		rc.typeQueue = append(rc.typeQueue, id)
	}
	if expand {
		rc.expandType[id] = true
	}
}

func (rc *apiRecorder) noteTypeResponse(raw json.RawMessage, expand bool) {
	var t map[string]any
	if json.Unmarshal(raw, &t) != nil || t == nil {
		return
	}
	// Only a response's own id is registered (a type it merely names — target, typeParameters, … — is
	// not resolvable until some response returns it).
	if v, ok := t["id"].(float64); ok {
		rc.noteType(uint64(v), expand)
	}
}

// symbolTypes asks getTypeOfSymbol of every symbol of a symbol-list response not asked before.
func (rc *apiRecorder) symbolTypes(raw json.RawMessage) {
	var list []map[string]any
	json.Unmarshal(raw, &list)
	for _, s := range list {
		if s == nil {
			continue
		}
		if v, ok := s["id"].(float64); ok && !rc.symbols[uint64(v)] {
			rc.symbols[uint64(v)] = true
			rc.noteTypeResponse(rc.do("getTypeOfSymbol", map[string]any{"symbol": uint64(v)}), false)
		}
	}
}

func (rc *apiRecorder) noteSignatures(raw json.RawMessage) {
	var list []map[string]any
	if json.Unmarshal(raw, &list) != nil {
		var one map[string]any
		if json.Unmarshal(raw, &one) != nil || one == nil {
			return
		}
		list = []map[string]any{one}
	}
	for _, s := range list {
		if s == nil {
			continue
		}
		if v, ok := s["id"].(float64); ok && !rc.signatures[uint64(v)] {
			rc.signatures[uint64(v)] = true
			r := rc.do("getReturnTypeOfSignature", map[string]any{"signature": uint64(v)})
			rc.noteTypeResponse(r, false)
		}
	}
}

func cmdAPI(args []string) error {
	fl := flag.NewFlagSet("api", flag.ExitOnError)
	tsc := fl.String("tsc", "", "the tsgo binary (tools/tsgo-7.0.2/lib/tsc)")
	outFile := fl.String("out", "", "the recording (.jsonl)")
	maxFiles := fl.Int("files", 0, "at most this many source files (0: all)")
	batch := fl.Int("batch", 2000, "locations per batched request")
	fl.Parse(args)
	if fl.NArg() != 1 || *tsc == "" || *outFile == "" {
		return fmt.Errorf("usage: api -tsc <tsc> -out <file.jsonl> [-files N] <tsconfig.json>")
	}
	config := fl.Arg(0)
	// The handles index this process's parse of the same files: build the program as the session does.
	program, _, _ := api.XtscOpenProgram(config, osvfs.FS(), "")
	if program == nil {
		return fmt.Errorf("%s: no program", config)
	}
	c, err := newAPIClient(*tsc, "/")
	if err != nil {
		return err
	}
	defer c.close()
	if _, e, err := c.call("initialize", nil); err != nil || e != "" {
		return fmt.Errorf("initialize: %v %s", err, e)
	}
	r, e, err := c.call("updateSnapshot", map[string]any{"openProjects": []string{config}})
	if err != nil || e != "" {
		return fmt.Errorf("updateSnapshot: %v %s", err, e)
	}
	var snap struct {
		Snapshot uint64 `json:"snapshot"`
		Projects []struct {
			ID string `json:"id"`
		} `json:"projects"`
	}
	if err := json.Unmarshal(r, &snap); err != nil || len(snap.Projects) == 0 {
		return fmt.Errorf("updateSnapshot response %s: %v", r, err)
	}
	f, err := os.Create(*outFile)
	if err != nil {
		return err
	}
	defer f.Close()
	out := bufio.NewWriterSize(f, 1<<20)
	defer out.Flush()
	rc := &apiRecorder{
		c: c, out: out, snapshot: snap.Snapshot, project: snap.Projects[0].ID,
		types: map[uint64]bool{}, signatures: map[uint64]bool{}, symbols: map[uint64]bool{}, expandType: map[uint64]bool{},
	}
	// The project's own files, in the program's order (libraries and node_modules excluded).
	var files []*ast.SourceFile
	for _, sf := range program.GetSourceFiles() {
		if program.IsSourceFileDefaultLibrary(sf.Path()) || program.IsSourceFileFromExternalLibrary(sf) || strings.Contains(sf.FileName(), "/node_modules/") {
			continue
		}
		files = append(files, sf)
	}
	if *maxFiles > 0 && len(files) > *maxFiles {
		files = files[:*maxFiles]
	}
	for _, sf := range files {
		table := encoder.GetNodeIndexTable(sf)
		var idents, calls, argsList []string
		handle := func(i int, n *ast.Node) string { return fmt.Sprintf("%d.%d.%s", i, n.Kind, sf.Path()) }
		index := map[*ast.Node]int{}
		for i, n := range table.Nodes {
			if n != nil {
				index[n] = i
			}
		}
		for i, n := range table.Nodes {
			if n == nil {
				continue
			}
			switch n.Kind {
			case ast.KindIdentifier, ast.KindPrivateIdentifier:
				idents = append(idents, handle(i, n))
			case ast.KindCallExpression, ast.KindNewExpression, ast.KindTaggedTemplateExpression, ast.KindDecorator,
				ast.KindJsxOpeningElement, ast.KindJsxSelfClosingElement:
				calls = append(calls, handle(i, n))
			}
			if n.Kind == ast.KindCallExpression || n.Kind == ast.KindNewExpression {
				if a := n.Arguments(); a != nil {
					for _, arg := range a {
						if j, ok := index[arg]; ok {
							argsList = append(argsList, handle(j, arg))
						}
					}
				}
			}
		}
		for lo := 0; lo < len(idents); lo += *batch {
			hi := min(lo+*batch, len(idents))
			locs := idents[lo:hi]
			rc.symbolTypes(rc.do("getSymbolsAtLocations", map[string]any{"locations": locs}))
			types := rc.do("getTypeAtLocations", map[string]any{"locations": locs})
			var typeList []json.RawMessage
			json.Unmarshal(types, &typeList)
			for _, t := range typeList {
				rc.noteTypeResponse(t, true)
			}
		}
		for _, h := range calls {
			rc.noteSignatures(rc.do("getResolvedSignature", map[string]any{"location": h}))
		}
		for _, h := range argsList {
			ctx := rc.do("getContextualType", map[string]any{"location": h})
			rc.noteTypeResponse(ctx, false)
			at := rc.do("getTypeAtLocation", map[string]any{"location": h})
			rc.noteTypeResponse(at, false)
			var ct, tt map[string]any
			json.Unmarshal(ctx, &ct)
			json.Unmarshal(at, &tt)
			if ct != nil && tt != nil {
				rc.do("isTypeAssignableTo", map[string]any{"source": tt["id"], "target": ct["id"]})
			}
		}
		// Drain the types this file surfaced.
		for len(rc.typeQueue) > 0 {
			queue := rc.typeQueue
			rc.typeQueue = nil
			for _, id := range queue {
				rc.do("typeToString", map[string]any{"type": id})
				if rc.expandType[id] {
					rc.symbolTypes(rc.do("getPropertiesOfType", map[string]any{"type": id}))
					rc.noteSignatures(rc.do("getSignaturesOfType", map[string]any{"type": id, "kind": 0}))
				}
			}
		}
		fmt.Fprintf(os.Stderr, "%s: %d identifiers, %d calls, %d arguments; %d records\n", sf.FileName(), len(idents), len(calls), len(argsList), rc.n)
	}
	return nil
}
