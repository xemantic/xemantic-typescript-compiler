package main

// Structural interface satisfaction made explicit. Go interfaces are
// satisfied structurally; Kotlin interfaces nominally. Every named concrete
// type declared in an extracted package gets an "implements" list: each
// candidate interface that T (or only *T) satisfies, so the lowering can emit
// `class T : I`. Candidates are global to the run: every non-empty,
// method-only, non-generic interface type that ANY extracted package
// mentions (named or anonymous, closure-declared or external, plus `error`).

import (
	"go/types"
	"sort"

	"golang.org/x/tools/go/packages"
)

type ifaceCand struct {
	key string
	t   types.Type
}

func collectInterfaces(pkgs []*packages.Package, kept keptDecls) []ifaceCand {
	seen := map[string]types.Type{}
	if len(pkgs) == 0 {
		return nil
	}
	k := &px{fset: pkgs[0].Fset, keyMemo: map[types.Type]string{}}
	consider := func(t types.Type) {
		if t == nil {
			return
		}
		t = types.Unalias(t)
		if n, ok := t.(*types.Named); ok {
			if n.TypeParams().Len() > 0 && n.TypeArgs().Len() == 0 {
				return // uninstantiated generic
			}
		}
		it, ok := t.Underlying().(*types.Interface)
		if !ok || it.NumMethods() == 0 || !it.IsMethodSet() {
			return
		}
		key := k.typeKey(t)
		if _, dup := seen[key]; !dup {
			seen[key] = t
		}
	}
	consider(types.Universe.Lookup("error").Type())
	// Exported interfaces of every EXTERNAL package a closure package imports:
	// an external package may call a closure type's methods through them
	// (json.MarshalerTo, fmt.Stringer, ...) without the closure ever naming
	// the interface.
	inClosure := map[string]bool{}
	for _, p := range pkgs {
		inClosure[p.PkgPath] = true
	}
	for _, p := range pkgs {
		// A partial package contributes only the imports its kept declarations use.
		var usedImports map[string]bool
		if k := kept[p.PkgPath]; k != nil {
			usedImports = map[string]bool{}
			for id, o := range p.TypesInfo.Uses {
				if o.Pkg() != nil && inKeptDecl(k, p, id.Pos()) {
					usedImports[o.Pkg().Path()] = true
				}
			}
		}
		for _, imp := range p.Types.Imports() {
			if inClosure[imp.Path()] || usedImports != nil && !usedImports[imp.Path()] {
				continue
			}
			for _, name := range imp.Scope().Names() {
				if tn, ok := imp.Scope().Lookup(name).(*types.TypeName); ok && tn.Exported() {
					consider(tn.Type())
				}
			}
		}
	}
	for _, p := range pkgs {
		k := kept[p.PkgPath]
		for e, tv := range p.TypesInfo.Types {
			if inKeptDecl(k, p, e.Pos()) {
				consider(tv.Type)
			}
		}
		for id, o := range p.TypesInfo.Uses {
			if tn, ok := o.(*types.TypeName); ok && inKeptDecl(k, p, id.Pos()) {
				consider(tn.Type())
			}
		}
		for id, o := range p.TypesInfo.Defs {
			if tn, ok := o.(*types.TypeName); ok && inKeptDecl(k, p, id.Pos()) {
				consider(tn.Type())
			}
		}
	}
	// An anonymous interface identical to a named one's underlying type is the
	// named interface's own declaration (`type I interface{...}`), not a
	// separate candidate.
	var named []types.Type
	for _, t := range seen {
		if _, ok := t.(*types.Named); ok {
			named = append(named, t.Underlying())
		}
	}
	var out []ifaceCand
	for key, t := range seen {
		if _, isNamed := t.(*types.Named); !isNamed {
			dup := false
			for _, u := range named {
				if types.Identical(u, t) {
					dup = true
					break
				}
			}
			if dup {
				continue
			}
		}
		out = append(out, ifaceCand{key, t})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].key < out[j].key })
	return out
}

// implementsOf returns the "implements" list of a named concrete type.
func (p *px) implementsOf(n *types.Named) []any {
	out := []any{}
	ptr := types.NewPointer(n)
	for _, c := range p.ifaces {
		it := c.t.Underlying().(*types.Interface)
		via := ""
		if types.Implements(n, it) {
			via = "value"
		} else if types.Implements(ptr, it) {
			via = "pointer"
		}
		if via != "" {
			out = append(out, obj().S("iface", p.typ(c.t, false)).S("key", c.key).S("via", via))
			if in, ok := c.t.(*types.Named); ok && in.Obj().Pkg() != nil && !p.closure[in.Obj().Pkg().Path()] {
				p.st.extImpls = append(p.st.extImpls, [3]string{p.typeKey(n), c.key, via})
			}
		}
	}
	return out
}

// addImplements decorates the entries of the named types DECLARED in this
// package (sorted by name for determinism).
func (p *px) addImplements() {
	sc := p.pkg.Types.Scope()
	for _, name := range sc.Names() { // Names() is sorted
		tn, ok := sc.Lookup(name).(*types.TypeName)
		if !ok || tn.IsAlias() || !inKeptDecl(p.kept, p.pkg, tn.Pos()) {
			continue
		}
		n, ok := tn.Type().(*types.Named)
		if !ok || types.IsInterface(n) {
			continue
		}
		id := p.typ(n, true)
		e := p.typeEntries[id]
		if n.TypeParams().Len() > 0 {
			e.S("implementsSkipped", "generic")
			continue
		}
		e.S("implements", p.implementsOf(n))
	}
}

// initOrder records go/types' package-variable initialization order (Go
// initializes in DEPENDENCY order, not source order).
func (p *px) initOrder() Lines {
	out := Lines{}
	for _, in := range p.info.InitOrder {
		if !inKeptDecl(p.kept, p.pkg, in.Rhs.Pos()) {
			continue
		}
		var lhs []int
		for _, v := range in.Lhs {
			lhs = append(lhs, p.objID(v))
		}
		out = append(out, obj().S("lhs", lhs).S("file", fileBase(p, in.Rhs)).
			S("o", p.off(in.Rhs.Pos())).S("e", p.off(in.Rhs.End())))
	}
	return out
}
