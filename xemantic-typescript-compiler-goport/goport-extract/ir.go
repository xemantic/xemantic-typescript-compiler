package main

// The AST walk: every file, declaration, statement and expression of a
// package, annotated with what go/types knows about it.

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"go/ast"
	"go/token"
	"go/types"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"golang.org/x/tools/go/packages"
)

// px is the per-package extraction state.
type px struct {
	pkg     *packages.Package
	fset    *token.FileSet
	info    *types.Info
	closure map[string]bool // package paths being extracted in this run
	ifaces  []ifaceCand     // run-wide interface candidates (implements.go)
	module  string          // tsgo module path

	keyMemo     map[types.Type]string
	typeIds     map[string]int
	typeEntries []*O
	typeFull    map[int]bool

	objIds   map[types.Object]int
	objList  []types.Object
	objFn    map[types.Object]string
	facts    map[types.Object]*objFacts
	captures map[*ast.FuncLit][]types.Object

	scopeIds     map[*types.Scope]int
	scopeEntries []*O
	scopeNode    map[*types.Scope]ast.Node

	fieldOwner map[*types.Var]int
	fieldNamed map[*types.Var]string

	declRanges []declRange
	qnameSeen  map[string]int

	curDecl  string
	ff       *funcFacts
	sigStack []*types.Signature
	src      []byte

	holes      map[string][]string
	nilPending map[*O]string // nil literals not yet given an "impl" target
	st         *stats
}

type declRange struct {
	pos, end token.Pos
	qname    string
}

func newPx(pkg *packages.Package, closure map[string]bool, module string, st *stats, ifaces []ifaceCand) *px {
	p := &px{ifaces: ifaces,
		pkg: pkg, fset: pkg.Fset, info: pkg.TypesInfo, closure: closure, module: module,
		keyMemo: map[types.Type]string{}, typeIds: map[string]int{}, typeFull: map[int]bool{},
		objIds: map[types.Object]int{}, objFn: map[types.Object]string{},
		facts: map[types.Object]*objFacts{}, captures: map[*ast.FuncLit][]types.Object{},
		scopeIds: map[*types.Scope]int{}, scopeNode: map[*types.Scope]ast.Node{},
		fieldOwner: map[*types.Var]int{}, fieldNamed: map[*types.Var]string{},
		qnameSeen: map[string]int{}, holes: map[string][]string{}, st: st, nilPending: map[*O]string{},
	}
	for n, s := range p.info.Scopes {
		p.scopeNode[s] = n
	}
	return p
}

func (p *px) hole(cat, where string) {
	p.holes[cat] = append(p.holes[cat], where)
}

func (p *px) off(pos token.Pos) int {
	return p.fset.File(pos).Offset(pos)
}

func (p *px) line(pos token.Pos) int {
	return p.fset.File(pos).Line(pos)
}

// declAt returns the qualified name of the top-level declaration containing pos.
func (p *px) declAt(pos token.Pos) string {
	i := sort.Search(len(p.declRanges), func(i int) bool { return p.declRanges[i].end > pos })
	if i < len(p.declRanges) && p.declRanges[i].pos <= pos {
		return p.declRanges[i].qname
	}
	return ""
}

func (p *px) uniqueQname(q string) string {
	n := p.qnameSeen[q]
	p.qnameSeen[q] = n + 1
	if n == 0 {
		return q
	}
	return q + "#" + strconv.Itoa(n+1)
}

func (p *px) hashOf(from, to token.Pos) string {
	s := sha256.Sum256(p.src[p.off(from):p.off(to)])
	return hex.EncodeToString(s[:])
}

// ---------------------------------------------------------------- per-function facts

type funcFacts struct {
	Defer, Recover, Panic, Goto, Go, ChanOps, Select, Unsafe, Reflect, Labels int
	Closures, Fallthrough, Locals, AddrLocals, CapturedLocals, StructCopies   int
	TypeParams, Instantiates, MultiResult, NamedResults                       bool
	BareReturn                                                                int
}

func (f *funcFacts) encode() *O {
	o := obj()
	o.Opt("defer", f.Defer).Opt("recover", f.Recover).Opt("panic", f.Panic).Opt("goto", f.Goto)
	o.Opt("go", f.Go).Opt("chanOps", f.ChanOps).Opt("select", f.Select).Opt("unsafe", f.Unsafe)
	o.Opt("reflect", f.Reflect).Opt("labels", f.Labels).Opt("closures", f.Closures)
	o.Opt("fallthrough", f.Fallthrough).Opt("locals", f.Locals).Opt("addrLocals", f.AddrLocals)
	o.Opt("capturedLocals", f.CapturedLocals).Opt("structCopies", f.StructCopies)
	o.Opt("typeParams", f.TypeParams).Opt("instantiates", f.Instantiates)
	o.Opt("multiResult", f.MultiResult).Opt("namedResults", f.NamedResults).Opt("bareReturn", f.BareReturn)
	return o
}

// ---------------------------------------------------------------- package / file

func (p *px) packageIR() (*O, []fileInfo) {
	// Files in a deterministic order: by base name.
	type pf struct {
		name string
		f    *ast.File
	}
	var files []pf
	for _, f := range p.pkg.Syntax {
		files = append(files, pf{p.fset.Position(f.Pos()).Filename, f})
	}
	sort.Slice(files, func(i, j int) bool { return filepath.Base(files[i].name) < filepath.Base(files[j].name) })

	var asts []*ast.File
	for _, f := range files {
		asts = append(asts, f.f)
		for _, d := range f.f.Decls {
			p.declRanges = append(p.declRanges, declRange{pos: d.Pos(), end: d.End()})
		}
	}
	sort.Slice(p.declRanges, func(i, j int) bool { return p.declRanges[i].pos < p.declRanges[j].pos })
	p.computeFacts(asts)

	var fileNodes Lines
	var infos []fileInfo
	for _, f := range files {
		src, err := readFile(f.name)
		if err != nil {
			panic(err)
		}
		p.src = src
		fn, fi := p.file(f.f, f.name)
		fileNodes = append(fileNodes, fn)
		infos = append(infos, fi)
	}
	var nils []string
	for _, where := range p.nilPending {
		nils = append(nils, where)
	}
	sort.Strings(nils)
	for _, where := range nils {
		p.hole("nil-without-target", where)
	}
	p.addImplements()
	inits := p.initOrder()
	objs := p.finishObjects()
	var scopes Lines
	for _, s := range p.scopeEntries {
		scopes = append(scopes, s)
	}
	var tys Lines
	for _, t := range p.typeEntries {
		tys = append(tys, t)
	}
	out := obj().S("schema", irSchemaVersion).S("path", p.pkg.PkgPath).S("name", p.pkg.Name)
	out.S("files", fileNodes)
	out.S("initOrder", inits)
	out.S("types", tys).S("objects", objs).S("scopes", scopes)
	return out, infos
}

type fileInfo struct {
	name      string
	generated bool
	lines     int
	decls     int
}

func (p *px) file(f *ast.File, name string) (*O, fileInfo) {
	tf := p.fset.File(f.Pos())
	gen := ast.IsGenerated(f)
	n := obj().S("name", filepath.Base(name)).S("generated", gen).S("package", f.Name.Name)
	// Language version in effect (Go >= 1.22: per-iteration loop variables).
	n.Opt("goVersion", p.info.FileVersions[f])
	var imps []any
	for _, is := range f.Imports {
		path, _ := strconv.Unquote(is.Path.Value)
		ie := obj().S("path", path)
		if is.Name != nil {
			ie.S("name", is.Name.Name)
		}
		var pn types.Object
		if is.Name != nil {
			pn = p.info.Defs[is.Name]
		} else {
			pn = p.info.Implicits[is]
		}
		if pn != nil {
			ie.S("obj", p.objID(pn))
		}
		imps = append(imps, ie)
	}
	if imps == nil {
		imps = []any{}
	}
	n.S("imports", imps)
	n.S("lines", tf.LineCount()).S("size", tf.Size())
	n.S("lineOffsets", tf.Lines())
	var decls Lines
	for _, d := range f.Decls {
		decls = append(decls, p.decl(d, gen))
	}
	if decls == nil {
		decls = Lines{}
	}
	n.S("decls", decls)
	p.st.addFile(p.pkg.PkgPath, gen, tf.LineCount())
	return n, fileInfo{filepath.Base(name), gen, tf.LineCount(), len(f.Decls)}
}

// ---------------------------------------------------------------- declarations

func recvBaseName(e ast.Expr) string {
	for {
		switch x := e.(type) {
		case *ast.StarExpr:
			e = x.X
		case *ast.ParenExpr:
			e = x.X
		case *ast.IndexExpr:
			e = x.X
		case *ast.IndexListExpr:
			e = x.X
		case *ast.Ident:
			return x.Name
		default:
			return "?"
		}
	}
}

func (p *px) setDeclRange(d ast.Decl, q string) {
	i := sort.Search(len(p.declRanges), func(i int) bool { return p.declRanges[i].pos >= d.Pos() })
	if i < len(p.declRanges) && p.declRanges[i].pos == d.Pos() {
		p.declRanges[i].qname = q
	}
}

func (p *px) decl(d ast.Decl, gen bool) *O {
	switch d := d.(type) {
	case *ast.FuncDecl:
		q := p.pkg.PkgPath + "."
		kind := "func"
		if d.Recv != nil && len(d.Recv.List) > 0 {
			q += recvBaseName(d.Recv.List[0].Type) + "."
			kind = "method"
		}
		q = p.uniqueQname(q + d.Name.Name)
		p.setDeclRange(d, q)
		p.curDecl = q
		p.ff = &funcFacts{}
		n := obj().S("k", "FuncDecl").S("qname", q).S("name", d.Name.Name)
		n.S("line", p.line(d.Pos())).S("lines", p.line(d.End())-p.line(d.Pos())+1)
		n.S("hash", p.hashOf(d.Pos(), d.End()))
		if d.Doc != nil {
			n.S("doc", d.Doc.Text())
		}
		n.S("o", p.off(d.Pos())).S("e", p.off(d.End()))
		fobj := p.info.Defs[d.Name]
		if fobj != nil {
			n.S("obj", p.objID(fobj))
		} else {
			p.hole("funcdecl-without-object", q)
		}
		if d.Recv != nil {
			n.S("recv", p.fieldList(d.Recv))
		}
		var sig *types.Signature
		if fobj != nil {
			sig = fobj.Type().(*types.Signature)
			if sig.TypeParams().Len() > 0 || sig.RecvTypeParams().Len() > 0 {
				p.ff.TypeParams = true
			}
			if sig.Results().Len() > 1 {
				p.ff.MultiResult = true
			}
			if sig.Results().Len() > 0 && sig.Results().At(0).Name() != "" {
				p.ff.NamedResults = true
			}
		}
		// go/types records no TypeAndValue for a declaration's FuncType; the
		// signature is the function object's type.
		ft := p.exprNoTV(d.Type)
		if sig != nil {
			ft.S("t", p.typ(sig, true)).S("m", "type")
		}
		n.S("type", ft)
		p.sigStack = append(p.sigStack, sig)
		if d.Body != nil {
			n.S("body", p.stmt(d.Body))
		} else {
			n.S("external", true) // assembly / linkname: no Go body
		}
		p.sigStack = p.sigStack[:len(p.sigStack)-1]
		n.S("facts", p.ff.encode())
		p.st.addFunc(p.pkg.PkgPath, kind, q, p.ff, gen)
		p.curDecl, p.ff = "", nil
		return n
	case *ast.GenDecl:
		q := p.pkg.PkgPath + "." + strings.ToLower(d.Tok.String()) + "@" + strconv.Itoa(p.line(d.Pos()))
		p.setDeclRange(d, q)
		p.curDecl = q
		p.ff = &funcFacts{}
		n := p.genDecl(d, true)
		n.S("qname", q)
		n.S("facts", p.ff.encode())
		p.st.addGen(p.pkg.PkgPath, q, d, p.ff, gen)
		p.curDecl, p.ff = "", nil
		return n
	case *ast.BadDecl:
		p.hole("bad-decl", p.posLC(d.Pos()))
		return obj().S("k", "BadDecl")
	}
	panic(fmt.Sprintf("decl %T", d))
}

// genDecl encodes import/const/type/var declarations, top-level or local.
func (p *px) genDecl(d *ast.GenDecl, top bool) *O {
	n := obj().S("k", "GenDecl").S("tok", d.Tok.String())
	n.S("line", p.line(d.Pos())).S("lines", p.line(d.End())-p.line(d.Pos())+1)
	n.S("o", p.off(d.Pos())).S("e", p.off(d.End()))
	if top {
		n.S("hash", p.hashOf(d.Pos(), d.End()))
	}
	if d.Doc != nil {
		n.S("doc", d.Doc.Text())
	}
	n.Opt("grouped", d.Lparen.IsValid())
	var specs []any
	for i, s := range d.Specs {
		switch s := s.(type) {
		case *ast.ImportSpec:
			path, _ := strconv.Unquote(s.Path.Value)
			sp := obj().S("k", "ImportSpec").S("path", path)
			if s.Name != nil {
				sp.S("name", s.Name.Name)
			}
			specs = append(specs, sp)
		case *ast.ValueSpec:
			sp := obj().S("k", "ValueSpec").S("line", p.line(s.Pos()))
			sp.S("o", p.off(s.Pos())).S("e", p.off(s.End()))
			if top {
				sp.S("hash", p.hashOf(s.Pos(), s.End()))
				var qs []string
				for _, nm := range s.Names {
					qs = append(qs, p.uniqueQname(p.pkg.PkgPath+"."+nm.Name))
				}
				sp.S("qnames", qs)
			}
			if d.Tok == token.CONST {
				sp.S("iota", i)
				if len(s.Values) == 0 {
					sp.S("implicitRepeat", true) // values/type repeat the previous spec; each const object carries its exact value
				}
			}
			if s.Doc != nil {
				sp.S("doc", s.Doc.Text())
			}
			var names []any
			var targets []types.Type
			for _, nm := range s.Names {
				names = append(names, p.expr(nm))
				if o := p.info.Defs[nm]; o != nil {
					targets = append(targets, o.Type())
					p.countLocalDef(o)
				} else {
					targets = append(targets, nil)
				}
			}
			sp.S("names", names)
			if s.Type != nil {
				sp.S("type", p.expr(s.Type))
			}
			var vals []any
			if len(s.Values) == 1 && len(s.Names) > 1 {
				vals = append(vals, p.tupleTarget(p.expr(s.Values[0]), s.Values[0], targets))
			} else {
				for j, v := range s.Values {
					var tgt types.Type
					if j < len(targets) {
						tgt = targets[j]
					}
					vals = append(vals, p.exprT(v, tgt))
				}
			}
			sp.Opt("values", vals)
			specs = append(specs, sp)
		case *ast.TypeSpec:
			sp := obj().S("k", "TypeSpec").S("line", p.line(s.Pos())).S("name", s.Name.Name)
			sp.S("o", p.off(s.Pos())).S("e", p.off(s.End()))
			if top {
				sp.S("qname", p.uniqueQname(p.pkg.PkgPath+"."+s.Name.Name))
				sp.S("hash", p.hashOf(s.Pos(), s.End()))
			}
			if s.Doc != nil {
				sp.S("doc", s.Doc.Text())
			}
			sp.S("nameNode", p.expr(s.Name))
			if s.TypeParams != nil {
				sp.S("tparams", p.fieldList(s.TypeParams))
			}
			sp.Opt("alias", s.Assign.IsValid())
			sp.S("type", p.expr(s.Type))
			if o := p.info.Defs[s.Name]; o != nil {
				p.typ(o.Type(), true) // declared types always get full entries (method sets)
			}
			specs = append(specs, sp)
		}
	}
	if specs == nil {
		specs = []any{}
	}
	n.S("specs", specs)
	return n
}

func (p *px) countLocalDef(o types.Object) {
	if p.ff == nil || !isLocal(o) {
		return
	}
	if v, ok := o.(*types.Var); ok && v.Kind() == types.LocalVar {
		p.ff.Locals++
		if f := p.facts[o]; f != nil {
			if f.addr {
				p.ff.AddrLocals++
			}
			if f.captured {
				p.ff.CapturedLocals++
			}
		}
	}
}

func (p *px) fieldList(fl *ast.FieldList) *O {
	n := obj().S("k", "FieldList")
	var fs []any
	for _, f := range fl.List {
		fe := obj().S("k", "Field").S("o", p.off(f.Pos())).S("e", p.off(f.End()))
		var names []any
		for _, nm := range f.Names {
			names = append(names, p.expr(nm))
		}
		fe.Opt("names", names)
		fe.S("type", p.expr(f.Type))
		if f.Tag != nil {
			// A struct tag is a string literal with no TypeAndValue; its value
			// is the unquoted bytes (base64, like every string constant).
			v, _ := strconv.Unquote(f.Tag.Value)
			fe.S("tag", obj().S("raw", f.Tag.Value).S("b64", base64.StdEncoding.EncodeToString([]byte(v))))
		}
		if f.Doc != nil {
			fe.S("doc", f.Doc.Text())
		}
		fs = append(fs, fe)
	}
	if fs == nil {
		fs = []any{}
	}
	n.S("list", fs)
	return n
}

// ---------------------------------------------------------------- statements

func (p *px) stmts(l []ast.Stmt) []any {
	out := []any{}
	for _, s := range l {
		out = append(out, p.stmt(s))
	}
	return out
}

func (p *px) curSig() *types.Signature {
	if len(p.sigStack) == 0 {
		return nil
	}
	return p.sigStack[len(p.sigStack)-1]
}

func (p *px) stmt(s ast.Stmt) *O {
	if s == nil {
		return nil
	}
	k := strings.TrimPrefix(fmt.Sprintf("%T", s), "*ast.")
	n := obj().S("k", k).S("o", p.off(s.Pos())).S("e", p.off(s.End())).S("line", p.line(s.Pos()))
	switch s := s.(type) {
	case *ast.BadStmt:
		p.hole("bad-stmt", p.posLC(s.Pos()))
	case *ast.DeclStmt:
		n.S("decl", p.genDecl(s.Decl.(*ast.GenDecl), false))
	case *ast.EmptyStmt:
		n.Opt("implicit", s.Implicit)
	case *ast.LabeledStmt:
		p.ff.Labels++
		n.S("label", p.expr(s.Label)).S("stmt", p.stmt(s.Stmt))
	case *ast.ExprStmt:
		n.S("x", p.expr(s.X))
	case *ast.SendStmt:
		p.ff.ChanOps++
		n.S("chan", p.expr(s.Chan))
		var elem types.Type
		if tv := p.info.Types[s.Chan]; tv.Type != nil {
			if ch, ok := tv.Type.Underlying().(*types.Chan); ok {
				elem = ch.Elem()
			}
		}
		n.S("value", p.exprT(s.Value, elem))
	case *ast.IncDecStmt:
		n.S("x", p.expr(s.X)).S("tok", s.Tok.String())
	case *ast.AssignStmt:
		n.S("tok", s.Tok.String())
		var lhs []any
		var targets []types.Type
		for _, l := range s.Lhs {
			lhs = append(lhs, p.expr(l))
			var t types.Type
			if id, ok := l.(*ast.Ident); ok {
				if o := p.info.Defs[id]; o != nil {
					t = o.Type()
					p.countLocalDef(o)
				} else if o := p.info.Uses[id]; o != nil {
					t = o.Type()
				}
			} else if tv := p.info.Types[l]; tv.Type != nil {
				t = tv.Type
			}
			targets = append(targets, t)
		}
		n.S("lhs", lhs)
		var rhs []any
		if s.Tok == token.ASSIGN || s.Tok == token.DEFINE {
			if len(s.Rhs) == 1 && len(s.Lhs) > 1 {
				rhs = append(rhs, p.tupleTarget(p.expr(s.Rhs[0]), s.Rhs[0], targets))
			} else {
				for i, r := range s.Rhs {
					rhs = append(rhs, p.exprT(r, targets[i]))
				}
			}
		} else {
			for _, r := range s.Rhs {
				rhs = append(rhs, p.expr(r))
			}
		}
		n.S("rhs", rhs)
	case *ast.GoStmt:
		p.ff.Go++
		n.S("call", p.expr(s.Call))
	case *ast.DeferStmt:
		p.ff.Defer++
		n.S("call", p.expr(s.Call))
	case *ast.ReturnStmt:
		sig := p.curSig()
		var targets []types.Type
		if sig != nil {
			for i := 0; i < sig.Results().Len(); i++ {
				targets = append(targets, sig.Results().At(i).Type())
			}
		}
		if len(s.Results) == 0 && len(targets) > 0 {
			n.S("bare", true) // returns the named results
			p.ff.BareReturn++
		}
		var res []any
		if len(s.Results) == 1 && len(targets) > 1 {
			res = append(res, p.tupleTarget(p.expr(s.Results[0]), s.Results[0], targets))
		} else {
			for i, r := range s.Results {
				var t types.Type
				if i < len(targets) {
					t = targets[i]
				}
				res = append(res, p.exprT(r, t))
			}
		}
		n.Opt("results", res)
	case *ast.BranchStmt:
		n.S("tok", s.Tok.String())
		switch s.Tok {
		case token.GOTO:
			p.ff.Goto++
		case token.FALLTHROUGH:
			p.ff.Fallthrough++
		}
		if s.Label != nil {
			n.S("label", p.expr(s.Label))
		}
	case *ast.BlockStmt:
		n.S("list", p.stmts(s.List))
	case *ast.IfStmt:
		if s.Init != nil {
			n.S("init", p.stmt(s.Init))
		}
		n.S("cond", p.expr(s.Cond)).S("body", p.stmt(s.Body))
		if s.Else != nil {
			n.S("else", p.stmt(s.Else))
		}
	case *ast.CaseClause:
		// only reached through switch encoders below
		panic("CaseClause outside switch")
	case *ast.SwitchStmt:
		if s.Init != nil {
			n.S("init", p.stmt(s.Init))
		}
		var tagT types.Type
		if s.Tag != nil {
			n.S("tag", p.expr(s.Tag))
			tagT = p.info.Types[s.Tag].Type
		}
		var cl []any
		for _, c := range s.Body.List {
			cc := c.(*ast.CaseClause)
			ce := obj().S("k", "CaseClause").S("o", p.off(cc.Pos())).S("e", p.off(cc.End())).S("line", p.line(cc.Pos()))
			if cc.List == nil {
				ce.S("default", true)
			}
			var vals []any
			for _, v := range cc.List {
				if tagT != nil {
					vals = append(vals, p.exprT(v, tagT))
				} else {
					vals = append(vals, p.expr(v))
				}
			}
			ce.Opt("list", vals)
			ce.S("body", p.stmts(cc.Body))
			cl = append(cl, ce)
		}
		if cl == nil {
			cl = []any{}
		}
		n.S("clauses", cl)
	case *ast.TypeSwitchStmt:
		if s.Init != nil {
			n.S("init", p.stmt(s.Init))
		}
		// Assign is `x := y.(type)` or `y.(type)`; the symbolic x has no object
		// of its own (Defs[x] == nil): each clause has an implicit one.
		var operand ast.Expr
		switch a := s.Assign.(type) {
		case *ast.AssignStmt:
			id := a.Lhs[0].(*ast.Ident)
			n.S("bind", id.Name)
			operand = a.Rhs[0].(*ast.TypeAssertExpr).X
		case *ast.ExprStmt:
			operand = a.X.(*ast.TypeAssertExpr).X
		}
		n.S("x", p.expr(operand))
		operandT := p.info.Types[operand].Type
		var cl []any
		for _, c := range s.Body.List {
			cc := c.(*ast.CaseClause)
			ce := obj().S("k", "TypeCaseClause").S("o", p.off(cc.Pos())).S("e", p.off(cc.End())).S("line", p.line(cc.Pos()))
			if cc.List == nil {
				ce.S("default", true)
			}
			var ts []any
			for _, t := range cc.List {
				if p.info.Types[t].IsNil() {
					ts = append(ts, p.exprT(t, operandT)) // `case nil:` compares with the interface's nil
				} else {
					ts = append(ts, p.expr(t))
				}
			}
			ce.Opt("types", ts)
			if o := p.info.Implicits[cc]; o != nil {
				ce.S("implicit", p.objID(o))
				p.countLocalDef(o)
			}
			ce.S("body", p.stmts(cc.Body))
			cl = append(cl, ce)
		}
		if cl == nil {
			cl = []any{}
		}
		n.S("clauses", cl)
	case *ast.SelectStmt:
		p.ff.Select++
		var cl []any
		for _, c := range s.Body.List {
			cc := c.(*ast.CommClause)
			ce := obj().S("k", "CommClause").S("o", p.off(cc.Pos())).S("e", p.off(cc.End())).S("line", p.line(cc.Pos()))
			if cc.Comm == nil {
				ce.S("default", true)
			} else {
				ce.S("comm", p.stmt(cc.Comm))
			}
			ce.S("body", p.stmts(cc.Body))
			cl = append(cl, ce)
		}
		n.S("clauses", cl)
	case *ast.ForStmt:
		if s.Init != nil {
			n.S("init", p.stmt(s.Init))
		}
		if s.Cond != nil {
			n.S("cond", p.expr(s.Cond))
		}
		if s.Post != nil {
			n.S("post", p.stmt(s.Post))
		}
		n.S("body", p.stmt(s.Body))
	case *ast.RangeStmt:
		n.S("tok", s.Tok.String())
		xt := p.info.Types[s.X].Type
		n.S("x", p.expr(s.X))
		rk, kt, vt := rangeKinds(xt)
		n.S("rk", rk)
		if rk == "?" || rk == "typeparam" {
			p.hole("range-kind-unknown", p.posLC(s.Pos()))
		}
		if rk == "chan" {
			p.ff.ChanOps++
		}
		if kt != nil {
			n.S("kt", p.typ(kt, true))
		}
		if vt != nil {
			n.S("vt", p.typ(vt, true))
		}
		if s.Key != nil {
			n.S("key", p.expr(s.Key))
			if id, ok := s.Key.(*ast.Ident); ok {
				if o := p.info.Defs[id]; o != nil {
					p.countLocalDef(o)
				}
			}
		}
		if s.Value != nil {
			n.S("value", p.expr(s.Value))
			if id, ok := s.Value.(*ast.Ident); ok {
				if o := p.info.Defs[id]; o != nil {
					p.countLocalDef(o)
				}
			}
			if vt != nil && isValueAggregate(vt) {
				n.S("valueCopy", true)
				p.ff.StructCopies++
			}
		}
		n.S("body", p.stmt(s.Body))
	default:
		panic(fmt.Sprintf("stmt %T", s))
	}
	return n
}

// rangeKinds classifies a range expression's type and returns the key and
// value types a `for k, v := range x` binds.
func rangeKinds(t types.Type) (string, types.Type, types.Type) {
	if t == nil {
		return "?", nil, nil
	}
	u0 := t.Underlying()
	if _, isTP := t.(*types.TypeParam); isTP {
		if u0 = coreType(t); u0 == nil {
			return "typeparam", nil, nil
		}
	}
	switch u := u0.(type) {
	case *types.Basic:
		if u.Info()&types.IsString != 0 {
			return "string", types.Typ[types.Int], types.Typ[types.Rune]
		}
		if u.Info()&types.IsInteger != 0 {
			return "int", t, nil
		}
	case *types.Slice:
		return "slice", types.Typ[types.Int], u.Elem()
	case *types.Array:
		return "array", types.Typ[types.Int], u.Elem()
	case *types.Pointer:
		if a, ok := u.Elem().Underlying().(*types.Array); ok {
			return "ptrarray", types.Typ[types.Int], a.Elem()
		}
	case *types.Map:
		return "map", u.Key(), u.Elem()
	case *types.Chan:
		return "chan", u.Elem(), nil
	case *types.Signature:
		// range-over-func: func(yield func(K[, V]) bool)
		if u.Params().Len() == 1 {
			if y, ok := u.Params().At(0).Type().Underlying().(*types.Signature); ok {
				var k, v types.Type
				if y.Params().Len() > 0 {
					k = y.Params().At(0).Type()
				}
				if y.Params().Len() > 1 {
					v = y.Params().At(1).Type()
				}
				return "func", k, v
			}
		}
		return "func", nil, nil
	case *types.Interface:
		return "typeparam", nil, nil
	}
	return "?", nil, nil
}

// isValueAggregate: a value whose assignment COPIES in Go but aliases in Kotlin.
func isValueAggregate(t types.Type) bool {
	if t == nil {
		return false
	}
	switch u := t.Underlying().(type) {
	case *types.Struct:
		return u.NumFields() > 0 // struct{} has no state to copy
	case *types.Array:
		return u.Len() > 0
	}
	return false
}

// fresh reports whether e produces a new value nobody else references
// (composite literal or the result of a non-conversion call), so a copy is
// unnecessary.
func (p *px) fresh(e ast.Expr) bool {
	for {
		switch x := e.(type) {
		case *ast.ParenExpr:
			e = x.X
			continue
		case *ast.CompositeLit:
			return true
		case *ast.CallExpr:
			return !p.info.Types[x.Fun].IsType()
		}
		return false
	}
}

// ---------------------------------------------------------------- expressions

func modeOf(tv types.TypeAndValue) string {
	switch {
	case tv.IsVoid():
		return "void"
	case tv.IsType():
		return "type"
	case tv.IsBuiltin():
		return "builtin"
	case tv.IsNil():
		return "nil"
	case tv.Value != nil:
		return "const"
	case tv.HasOk() && tv.Addressable():
		return "commaok-var" // never produced by go/types; kept for completeness
	case tv.HasOk() && tv.Assignable():
		return "mapindex" // m[k]: assignable, not addressable, comma-ok capable
	case tv.HasOk():
		return "commaok"
	case tv.Addressable():
		return "variable"
	case tv.IsValue():
		return "value"
	}
	return "novalue"
}

// exprT encodes e in a position that assigns it to a location of type target,
// annotating the implicit conversions and copies the lowering must materialize.
func (p *px) exprT(e ast.Expr, target types.Type) *O {
	n := p.expr(e)
	p.annotateTarget(n, e, target)
	return n
}

func (p *px) implOf(e ast.Expr, src, target types.Type) *O {
	if target == nil {
		return nil
	}
	tv := p.info.Types[e]
	if e != nil && tv.IsNil() {
		return obj().S("k", "nil").S("to", p.typ(target, true))
	}
	if src == nil {
		return nil
	}
	if types.IsInterface(target) && !types.IsInterface(src) {
		if b, ok := src.(*types.Basic); ok && b.Kind() == types.UntypedNil {
			return obj().S("k", "nil").S("to", p.typ(target, true))
		}
		if _, isTP := src.(*types.TypeParam); isTP {
			return obj().S("k", "iface").S("from", p.typ(src, true)).S("to", p.typ(target, true)).S("fromTypeParam", true)
		}
		return obj().S("k", "iface").S("from", p.typ(src, true)).S("to", p.typ(target, true))
	}
	return nil
}

func (p *px) annotateTarget(n *O, e ast.Expr, target types.Type) {
	tv := p.info.Types[e]
	if im := p.implOf(e, tv.Type, target); im != nil {
		n.S("impl", im)
		delete(p.nilPending, n)
	}
	if isValueAggregate(tv.Type) && !p.fresh(e) && tv.Value == nil {
		n.S("copy", true)
		if p.ff != nil {
			p.ff.StructCopies++
		}
	}
}

// tupleTarget annotates a multi-value expression (call or comma-ok form)
// assigned element-wise to targets.
func (p *px) tupleTarget(n *O, e ast.Expr, targets []types.Type) *O {
	tv := p.info.Types[e]
	tup, ok := tv.Type.(*types.Tuple)
	if !ok {
		return n
	}
	if _, isCall := ast.Unparen(e).(*ast.CallExpr); !isCall {
		// v, ok := m[k] / x.(T) / <-ch: go/types re-records the expression's
		// type as the tuple (V, bool) for a comma-ok use.
		n.S("commaOk", true)
	}
	var ims []any
	anyImpl := false
	for i := 0; i < tup.Len() && i < len(targets); i++ {
		im := p.implOf(nil, tup.At(i).Type(), targets[i])
		if im != nil {
			anyImpl = true
			ims = append(ims, im)
		} else {
			ims = append(ims, nil)
		}
	}
	if anyImpl {
		n.S("implTuple", ims)
	}
	return n
}

func (p *px) exprs(l []ast.Expr) []any {
	out := []any{}
	for _, e := range l {
		out = append(out, p.expr(e))
	}
	return out
}

func (p *px) expr(e ast.Expr) *O { return p.expr0(e, true) }

// exprNoTV encodes an expression for which go/types legitimately records no
// TypeAndValue (a declaration's FuncType); the caller supplies "t".
func (p *px) exprNoTV(e ast.Expr) *O { return p.expr0(e, false) }

func (p *px) expr0(e ast.Expr, needTV bool) *O {
	if e == nil {
		return nil
	}
	k := strings.TrimPrefix(fmt.Sprintf("%T", e), "*ast.")
	n := obj().S("k", k).S("o", p.off(e.Pos())).S("e", p.off(e.End()))
	tv, hasTV := p.info.Types[e]
	if hasTV {
		if tv.Type != nil {
			n.S("t", p.typ(tv.Type, true))
		}
		n.S("m", modeOf(tv))
		if tv.IsNil() {
			p.nilPending[n] = p.posLC(e.Pos())
		}
		if tv.Value != nil {
			n.S("c", constVal(tv.Value))
		}
	}
	switch e := e.(type) {
	case *ast.Ident:
		n.S("name", e.Name)
		if o, ok := p.info.Defs[e]; ok {
			n.S("def", true)
			if o != nil {
				n.S("obj", p.objID(o))
			} else if e.Name == "_" {
				n.S("blank", true) // blank identifier in a definition position: no object
			} else {
				p.hole("def-without-object", e.Name+" "+p.posLC(e.Pos()))
			}
		} else if o := p.info.Uses[e]; o != nil {
			n.S("obj", p.objID(o))
			p.useObj(o)
		} else if e.Name == "_" {
			n.S("blank", true)
		} else {
			p.hole("ident-without-object", e.Name+" "+p.posLC(e.Pos()))
		}
		if inst, ok := p.info.Instances[e]; ok {
			n.S("inst", obj().S("targs", p.typeList(inst.TypeArgs, true)).S("t", p.typ(inst.Type, true)))
			if p.ff != nil {
				p.ff.Instantiates = true
			}
		}
		return n
	case *ast.BasicLit:
		n.S("kind", e.Kind.String()).S("raw", e.Value)
	case *ast.CompositeLit:
		if e.Type != nil {
			n.S("type", p.expr(e.Type))
		}
		n.S("elts", p.compositeElts(e, tv.Type))
	case *ast.FuncLit:
		p.ff.Closures++
		var sig *types.Signature
		if tv.Type != nil {
			sig, _ = tv.Type.(*types.Signature)
		}
		n.S("type", p.expr(e.Type))
		p.sigStack = append(p.sigStack, sig)
		n.S("body", p.stmt(e.Body))
		p.sigStack = p.sigStack[:len(p.sigStack)-1]
		var caps []int
		for _, o := range p.captures[e] {
			caps = append(caps, p.objID(o))
		}
		n.Opt("captures", caps)
	case *ast.ParenExpr:
		n.S("x", p.expr(e.X))
	case *ast.SelectorExpr:
		n.S("x", p.expr(e.X))
		n.S("sel", p.expr(e.Sel))
		if sel := p.info.Selections[e]; sel != nil {
			kind := map[types.SelectionKind]string{types.FieldVal: "field", types.MethodVal: "method", types.MethodExpr: "methodexpr"}[sel.Kind()]
			n.S("selk", kind).S("path", intsOf(sel.Index())).S("indirect", sel.Indirect())
			n.S("recv", p.typ(sel.Recv(), true))
			n.S("selt", p.typ(sel.Type(), true))
			if sel.Kind() != types.FieldVal {
				f := sel.Obj().(*types.Func)
				sig := f.Type().(*types.Signature)
				if r := sig.Recv(); r != nil {
					_, ptrRecv := r.Type().(*types.Pointer)
					n.S("ptrRecv", ptrRecv)
					if types.IsInterface(r.Type()) {
						n.S("ifaceMethod", true)
					}
					xt := p.info.Types[e.X].Type
					if sel.Kind() == types.MethodVal && xt != nil && !types.IsInterface(xt) {
						_, xIsPtr := xt.Underlying().(*types.Pointer)
						if ptrRecv && !xIsPtr && !sel.Indirect() {
							n.S("autoAddr", true) // x.M() means (&x).M()
						}
						if !ptrRecv && (xIsPtr || sel.Indirect()) {
							n.S("autoDeref", true) // p.M() means (*p).M() — a COPY of the receiver
						}
						if !ptrRecv && isValueAggregate(r.Type()) {
							n.S("recvCopy", true)
							if p.ff != nil {
								p.ff.StructCopies++
							}
						}
					}
				}
			}
		} else {
			n.S("qual", true) // pkg.Name
		}
	case *ast.IndexExpr:
		n.S("x", p.expr(e.X))
		xt := p.info.Types[e.X].Type
		if xt != nil {
			if m, ok := xt.Underlying().(*types.Map); ok {
				n.S("index", p.exprT(e.Index, m.Key()))
				n.S("ik", "map")
				break
			}
			ik := indexKind(xt, p.info.Types[e.X])
			if ik == "?" || ik == "typeparam" {
				p.hole("index-kind-unknown", p.posLC(e.Pos()))
			}
			n.S("ik", ik)
		} else if inst, ok := p.instanceOf(e.X); ok {
			_ = inst
			n.S("ik", "instantiate")
		}
		n.S("index", p.expr(e.Index))
	case *ast.IndexListExpr:
		n.S("x", p.expr(e.X)).S("indices", p.exprs(e.Indices)).S("ik", "instantiate")
	case *ast.SliceExpr:
		n.S("x", p.expr(e.X))
		if e.Low != nil {
			n.S("low", p.expr(e.Low))
		}
		if e.High != nil {
			n.S("high", p.expr(e.High))
		}
		if e.Max != nil {
			n.S("max", p.expr(e.Max))
		}
		n.Opt("slice3", e.Slice3)
		if xt := p.info.Types[e.X].Type; xt != nil {
			sk := indexKind(xt, p.info.Types[e.X])
			if sk == "?" || sk == "typeparam" {
				p.hole("slice-kind-unknown", p.posLC(e.Pos()))
			}
			n.S("sk", sk)
		}
	case *ast.TypeAssertExpr:
		n.S("x", p.expr(e.X))
		if e.Type != nil {
			n.S("type", p.expr(e.Type))
		}
	case *ast.CallExpr:
		p.call(n, e, tv)
	case *ast.StarExpr:
		n.S("x", p.expr(e.X))
		if tv.IsType() {
			n.S("star", "pointerType")
		} else {
			n.S("star", "deref")
		}
	case *ast.UnaryExpr:
		n.S("op", e.Op.String()).S("x", p.expr(e.X))
		if e.Op == token.ARROW {
			p.ff.ChanOps++
		}
	case *ast.BinaryExpr:
		n.S("op", e.Op.String())
		xt, yt := p.info.Types[e.X].Type, p.info.Types[e.Y].Type
		if (e.Op == token.EQL || e.Op == token.NEQ) && xt != nil && yt != nil {
			// comparing an interface with a concrete value converts the latter
			n.S("x", p.exprT(e.X, p.cmpTarget(e.X, xt, yt)))
			n.S("y", p.exprT(e.Y, p.cmpTarget(e.Y, yt, xt)))
			n.S("cmp", true)
		} else {
			n.S("x", p.expr(e.X)).S("y", p.expr(e.Y))
		}
	case *ast.KeyValueExpr:
		n.S("key", p.expr(e.Key)).S("value", p.expr(e.Value))
	case *ast.ArrayType:
		if _, isEll := e.Len.(*ast.Ellipsis); isEll {
			n.S("lenEllipsis", true) // [...]T: the length is in the array type "t"
		} else if e.Len != nil {
			n.S("len", p.expr(e.Len))
		}
		n.S("elt", p.expr(e.Elt))
	case *ast.StructType:
		n.S("fields", p.fieldList(e.Fields))
	case *ast.FuncType:
		if e.TypeParams != nil {
			n.S("tparams", p.fieldList(e.TypeParams))
		}
		n.S("params", p.fieldList(e.Params))
		if e.Results != nil {
			n.S("results", p.fieldList(e.Results))
		}
	case *ast.InterfaceType:
		n.S("methods", p.fieldList(e.Methods))
	case *ast.MapType:
		n.S("key", p.expr(e.Key)).S("value", p.expr(e.Value))
	case *ast.ChanType:
		p.ff.ChanOps++
		dir := "both"
		if e.Dir == ast.SEND {
			dir = "send"
		} else if e.Dir == ast.RECV {
			dir = "recv"
		}
		n.S("dir", dir).S("value", p.expr(e.Value))
	case *ast.Ellipsis:
		if e.Elt != nil {
			n.S("elt", p.expr(e.Elt))
		}
	case *ast.BadExpr:
		p.hole("bad-expr", p.posLC(e.Pos()))
	default:
		panic(fmt.Sprintf("expr %T", e))
	}
	if !hasTV && needTV {
		switch e.(type) {
		case *ast.KeyValueExpr:
		default:
			p.hole("expr-without-type:"+k, p.posLC(e.Pos()))
		}
	}
	return n
}

// cmpTarget: an operand of == / != is converted to the other operand's type
// when it is the untyped nil, or a concrete value compared with an interface.
func (p *px) cmpTarget(e ast.Expr, t, other types.Type) types.Type {
	if p.info.Types[e].IsNil() {
		return other
	}
	return ifaceOrNil(other, t)
}

// ifaceOrNil returns other when it is an interface and t is not (so t must be
// converted to compare), else nil.
func ifaceOrNil(other, t types.Type) types.Type {
	if types.IsInterface(other) && !types.IsInterface(t) {
		return other
	}
	return nil
}

func (p *px) instanceOf(e ast.Expr) (types.Instance, bool) {
	switch x := e.(type) {
	case *ast.Ident:
		i, ok := p.info.Instances[x]
		return i, ok
	case *ast.SelectorExpr:
		i, ok := p.info.Instances[x.Sel]
		return i, ok
	}
	return types.Instance{}, false
}

func indexKind(xt types.Type, tv types.TypeAndValue) string {
	if tv.IsType() {
		return "instantiate"
	}
	u0 := xt.Underlying()
	if _, isTP := xt.(*types.TypeParam); isTP {
		if u0 = coreType(xt); u0 == nil {
			return "typeparam"
		}
	}
	switch u := u0.(type) {
	case *types.Basic:
		if u.Info()&types.IsString != 0 {
			return "string"
		}
	case *types.Slice:
		return "slice"
	case *types.Array:
		return "array"
	case *types.Pointer:
		return "ptrarray"
	case *types.Map:
		return "map"
	case *types.Signature:
		return "instantiate"
	case *types.Interface:
		return "typeparam"
	}
	return "?"
}

func (p *px) compositeElts(e *ast.CompositeLit, t types.Type) []any {
	out := []any{}
	if t == nil {
		return append(out, p.exprs(e.Elts)...)
	}
	u := t.Underlying()
	if ptr, ok := u.(*types.Pointer); ok { // elided &T{} inside a literal
		u = ptr.Elem().Underlying()
	}
	for i, el := range e.Elts {
		switch u := u.(type) {
		case *types.Struct:
			if kv, ok := el.(*ast.KeyValueExpr); ok {
				var ft types.Type
				if id, ok := kv.Key.(*ast.Ident); ok {
					if f, ok := p.info.Uses[id].(*types.Var); ok {
						ft = f.Type()
					}
				}
				kn := obj().S("k", "KeyValueExpr").S("o", p.off(kv.Pos())).S("e", p.off(kv.End()))
				kn.S("key", p.expr(kv.Key)).S("value", p.exprT(kv.Value, ft)).S("field", true)
				out = append(out, kn)
			} else if i < u.NumFields() {
				n := p.exprT(el, u.Field(i).Type())
				n.S("fieldIndex", i)
				out = append(out, n)
			} else {
				out = append(out, p.expr(el))
			}
		case *types.Slice, *types.Array:
			var et types.Type
			if s, ok := u.(*types.Slice); ok {
				et = s.Elem()
			} else {
				et = u.(*types.Array).Elem()
			}
			if kv, ok := el.(*ast.KeyValueExpr); ok {
				kn := obj().S("k", "KeyValueExpr").S("o", p.off(kv.Pos())).S("e", p.off(kv.End()))
				kn.S("key", p.expr(kv.Key)).S("value", p.exprT(kv.Value, et)).S("index", true)
				out = append(out, kn)
			} else {
				out = append(out, p.exprT(el, et))
			}
		case *types.Map:
			if kv, ok := el.(*ast.KeyValueExpr); ok {
				kn := obj().S("k", "KeyValueExpr").S("o", p.off(kv.Pos())).S("e", p.off(kv.End()))
				kn.S("key", p.exprT(kv.Key, u.Key())).S("value", p.exprT(kv.Value, u.Elem()))
				out = append(out, kn)
			} else {
				out = append(out, p.expr(el))
			}
		default:
			out = append(out, p.expr(el))
		}
	}
	return out
}

func (p *px) call(n *O, e *ast.CallExpr, tv types.TypeAndValue) {
	fun := e.Fun
	for {
		if pe, ok := fun.(*ast.ParenExpr); ok {
			fun = pe.X
			continue
		}
		break
	}
	ftv := p.info.Types[e.Fun]
	fn := p.expr(e.Fun)
	fn.S("callee", true) // a method selector WITHOUT this flag is a method VALUE (bound)
	n.S("fun", fn)
	if e.Ellipsis.IsValid() {
		n.S("spread", true)
	}
	switch {
	case ftv.IsType():
		n.S("call", "conv").S("to", p.typ(ftv.Type, true))
		var args []any
		for _, a := range e.Args {
			args = append(args, p.exprT(a, ftv.Type))
		}
		n.S("args", args)
		return
	case ftv.IsBuiltin():
		name := "?"
		switch f := fun.(type) {
		case *ast.Ident:
			name = f.Name
		case *ast.SelectorExpr: // unsafe.Sizeof etc.
			name = f.X.(*ast.Ident).Name + "." + f.Sel.Name
		}
		n.S("call", "builtin").S("builtin", name)
		switch name {
		case "panic":
			p.ff.Panic++
		case "recover":
			p.ff.Recover++
		case "close":
			p.ff.ChanOps++
		case "make":
			if len(e.Args) > 0 {
				if t := p.info.Types[e.Args[0]].Type; t != nil {
					if _, ok := t.Underlying().(*types.Chan); ok {
						p.ff.ChanOps++
					}
				}
			}
		}
		if strings.HasPrefix(name, "unsafe.") {
			p.ff.Unsafe++
		}
		// The callee's instantiated signature is recorded by go/types on Fun.
		if sig, ok := ftv.Type.(*types.Signature); ok {
			n.S("sig", p.typ(sig, true))
		}
		var args []any
		if name == "append" && len(e.Args) > 0 {
			st := p.info.Types[e.Args[0]].Type
			var et types.Type
			if st != nil {
				if s, ok := st.Underlying().(*types.Slice); ok {
					et = s.Elem()
				}
			}
			args = append(args, p.expr(e.Args[0]))
			for _, a := range e.Args[1:] {
				if e.Ellipsis.IsValid() {
					args = append(args, p.expr(a))
				} else {
					args = append(args, p.exprT(a, et))
				}
			}
		} else {
			for _, a := range e.Args {
				args = append(args, p.expr(a))
			}
		}
		if args == nil {
			args = []any{}
		}
		n.S("args", args)
		return
	}
	sig, _ := types.Unalias(ftv.Type).Underlying().(*types.Signature)
	kind := "func"
	switch f := fun.(type) {
	case *ast.SelectorExpr:
		if sel := p.info.Selections[f]; sel != nil {
			switch sel.Kind() {
			case types.MethodVal:
				kind = "method"
			case types.MethodExpr:
				kind = "methodexpr"
			case types.FieldVal:
				kind = "dynamic" // calling a func-typed field
			}
		} else if _, ok := p.info.Uses[f.Sel].(*types.Func); !ok {
			kind = "dynamic"
		}
	case *ast.Ident:
		if _, ok := p.info.Uses[f].(*types.Func); !ok {
			kind = "dynamic"
		}
	case *ast.IndexExpr, *ast.IndexListExpr:
		// explicit instantiation f[T](...)
		var base ast.Expr
		if ix, ok := f.(*ast.IndexExpr); ok {
			base = ix.X
		} else {
			base = f.(*ast.IndexListExpr).X
		}
		if id, ok := base.(*ast.Ident); ok {
			if _, isF := p.info.Uses[id].(*types.Func); !isF {
				kind = "dynamic"
			}
		} else if s, ok := base.(*ast.SelectorExpr); ok {
			if sel := p.info.Selections[s]; sel != nil && sel.Kind() == types.MethodVal {
				kind = "method"
			}
		} else {
			kind = "dynamic"
		}
	default:
		kind = "dynamic"
	}
	n.S("call", kind)
	if sig != nil {
		n.S("sig", p.typ(sig, true))
	} else {
		p.hole("call-without-signature", p.posLC(e.Pos()))
	}
	var args []any
	if sig != nil {
		params := sig.Params()
		// f(g()) with a multi-value g spreads the tuple across parameters
		if len(e.Args) == 1 && params.Len() > 1 {
			if tup, ok := p.info.Types[e.Args[0]].Type.(*types.Tuple); ok {
				var targets []types.Type
				for i := 0; i < tup.Len(); i++ {
					targets = append(targets, paramType(sig, i, false))
				}
				n.S("tupleArg", true)
				n.S("args", []any{p.tupleTarget(p.expr(e.Args[0]), e.Args[0], targets)})
				return
			}
		}
		for i, a := range e.Args {
			args = append(args, p.exprT(a, paramType(sig, i, e.Ellipsis.IsValid())))
		}
	} else {
		args = p.exprs(e.Args)
	}
	if args == nil {
		args = []any{}
	}
	n.S("args", args)
	if sig != nil && sig.Variadic() && !e.Ellipsis.IsValid() {
		n.S("variadicFrom", sig.Params().Len()-1) // args at index >= this are packed into a slice
	}
}

// paramType is the type argument i is assigned to (the variadic element type
// for packed variadic arguments).
func paramType(sig *types.Signature, i int, spread bool) types.Type {
	np := sig.Params().Len()
	if sig.Variadic() && i >= np-1 {
		last := sig.Params().At(np - 1).Type()
		if spread {
			return last
		}
		if s, ok := last.Underlying().(*types.Slice); ok {
			return s.Elem()
		}
		return nil
	}
	if i < np {
		return sig.Params().At(i).Type()
	}
	return nil
}

// useObj records a use for the external-symbol census and the risky facts.
func (p *px) useObj(o types.Object) {
	if o.Pkg() != nil {
		switch o.Pkg().Path() {
		case "unsafe":
			if p.ff != nil {
				p.ff.Unsafe++
			}
		case "reflect":
			if p.ff != nil {
				p.ff.Reflect++
			}
		}
	}
	p.st.addUse(p, o)
}

func fileBase(p *px, n ast.Node) string {
	return filepath.Base(p.fset.Position(n.Pos()).Filename)
}
