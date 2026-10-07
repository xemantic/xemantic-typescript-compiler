package main

// The object-facts pre-pass: which variables are CAPTURED by a closure, which
// have their ADDRESS TAKEN (so the lowering must box them for their whole
// life), and which are REASSIGNED after declaration (a Kotlin `var` rather
// than a `val`). It runs over the whole package before the IR walk so the
// facts are complete when an object entry is written.

import (
	"go/ast"
	"go/token"
	"go/types"
)

type objFacts struct {
	captured bool // read or written inside a func literal declared after it
	addr     bool // &x, x.ptrMethod(), x[:] on an array, or through a field/array path
	mut      bool // assigned (=, op=, ++/--, range =) after its declaration
}

func (p *px) fact(o types.Object) *objFacts {
	f := p.facts[o]
	if f == nil {
		f = &objFacts{}
		p.facts[o] = f
	}
	return f
}

// isVarLike reports whether o is a variable that can live in a function frame.
func isVarLike(o types.Object) bool {
	v, ok := o.(*types.Var)
	if !ok {
		return false
	}
	switch v.Kind() {
	case types.LocalVar, types.ParamVar, types.ResultVar, types.RecvVar, types.PackageVar:
		return true
	}
	return false
}

// rootVar follows an addressable expression down to the variable whose
// storage it denotes, marking every FIELD crossed on the way as address-taken
// too. It stops (returning nil) at any pointer indirection: &p.f where p is a
// pointer takes the address of heap storage, not of the variable p.
func (p *px) rootVar(e ast.Expr) types.Object {
	for {
		switch x := e.(type) {
		case *ast.ParenExpr:
			e = x.X
		case *ast.Ident:
			o := p.info.Uses[x]
			if o == nil {
				o = p.info.Defs[x]
			}
			if o != nil && isVarLike(o) {
				return o
			}
			return nil
		case *ast.SelectorExpr:
			sel := p.info.Selections[x]
			if sel == nil {
				// qualified identifier pkg.Var
				o := p.info.Uses[x.Sel]
				if o != nil && isVarLike(o) {
					return o
				}
				return nil
			}
			if sel.Kind() != types.FieldVal || sel.Indirect() {
				if sel.Kind() == types.FieldVal {
					p.fact(sel.Obj()).addr = true
				}
				return nil
			}
			p.fact(sel.Obj()).addr = true
			e = x.X
		case *ast.IndexExpr:
			tv := p.info.Types[x.X]
			if tv.Type == nil {
				return nil
			}
			if _, isArr := tv.Type.Underlying().(*types.Array); !isArr {
				return nil // slice/map element: heap storage
			}
			e = x.X
		default:
			return nil
		}
	}
}

func (p *px) markAddr(e ast.Expr) {
	if o := p.rootVar(e); o != nil {
		p.fact(o).addr = true
	}
}

func (p *px) markMut(e ast.Expr) {
	for {
		switch x := e.(type) {
		case *ast.ParenExpr:
			e = x.X
			continue
		case *ast.Ident:
			if o := p.info.Uses[x]; o != nil && isVarLike(o) {
				p.fact(o).mut = true
			}
		}
		return
	}
}

// computeFacts walks every file once.
func (p *px) computeFacts(files []*ast.File) {
	var stack []*ast.FuncLit
	var visit func(n ast.Node) bool
	visit = func(n ast.Node) bool {
		switch x := n.(type) {
		case *ast.FuncLit:
			stack = append(stack, x)
			ast.Inspect(x.Type, visit)
			ast.Inspect(x.Body, visit)
			stack = stack[:len(stack)-1]
			return false
		case *ast.Ident:
			o := p.info.Uses[x]
			if o == nil || !isVarLike(o) || !isLocal(o) {
				return true
			}
			for i := len(stack) - 1; i >= 0; i-- {
				fl := stack[i]
				if o.Pos() >= fl.Pos() && o.Pos() < fl.End() {
					break // declared inside this literal: not a capture here or further out
				}
				p.fact(o).captured = true
				p.addCapture(fl, o)
			}
		case *ast.UnaryExpr:
			if x.Op == token.AND {
				p.markAddr(x.X)
			}
		case *ast.SelectorExpr:
			if sel := p.info.Selections[x]; sel != nil && (sel.Kind() == types.MethodVal) {
				f := sel.Obj().(*types.Func)
				if r := f.Type().(*types.Signature).Recv(); r != nil {
					_, ptrRecv := r.Type().(*types.Pointer)
					tv := p.info.Types[x.X]
					if ptrRecv && tv.Type != nil {
						if _, recvIsPtr := tv.Type.Underlying().(*types.Pointer); !recvIsPtr && !types.IsInterface(tv.Type) && len(sel.Index()) == 1 {
							p.markAddr(x.X)
						} else if !recvIsPtr && len(sel.Index()) > 1 && !sel.Indirect() {
							// promoted through embedded VALUE fields: &x.embedded...
							p.markAddr(x.X)
						}
					}
				}
			}
		case *ast.SliceExpr:
			if tv := p.info.Types[x.X]; tv.Type != nil {
				if _, isArr := tv.Type.Underlying().(*types.Array); isArr {
					p.markAddr(x.X)
				}
			}
		case *ast.AssignStmt:
			if x.Tok != token.DEFINE {
				for _, l := range x.Lhs {
					p.markMut(l)
				}
			} else {
				for _, l := range x.Lhs { // := redeclaring an existing variable assigns it
					if id, ok := l.(*ast.Ident); ok && p.info.Defs[id] == nil {
						p.markMut(l)
					}
				}
			}
		case *ast.IncDecStmt:
			p.markMut(x.X)
		case *ast.RangeStmt:
			if x.Tok == token.ASSIGN {
				if x.Key != nil {
					p.markMut(x.Key)
				}
				if x.Value != nil {
					p.markMut(x.Value)
				}
			}
		}
		return true
	}
	for _, f := range files {
		ast.Inspect(f, visit)
	}
}

func (p *px) addCapture(fl *ast.FuncLit, o types.Object) {
	for _, c := range p.captures[fl] {
		if c == o {
			return
		}
	}
	p.captures[fl] = append(p.captures[fl], o)
}
