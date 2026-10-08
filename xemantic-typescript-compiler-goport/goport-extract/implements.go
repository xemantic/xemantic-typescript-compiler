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

// genericIfaces: the uninstantiated generic method-set interfaces of the run, by key ((TSGO.4-a):
// a generic struct satisfies one over its own type parameters, `*dirty.Box[T]` a `dirty.Value[T]`).
var genericIfaces = map[string]*types.Named{}

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
				// uninstantiated generic: a candidate for GENERIC types only (genericImplementsOf)
				if it, ok := n.Underlying().(*types.Interface); ok && it.NumMethods() > 0 && it.IsMethodSet() {
					genericIfaces[k.typeKey(n)] = n
				}
				return
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
		// (TSGO.4-a) An instantiation satisfies its type parameters' constraints, and a constraint that
		// is a generic interface over those parameters (`V Cloneable[V]`, project/dirty) names an
		// instantiated interface (`Cloneable[*directory]`) that appears in no expression: the type
		// argument must implement it nominally in Kotlin, so it is a candidate too.
		for id, inst := range p.TypesInfo.Instances {
			if !inKeptDecl(k, p, id.Pos()) {
				continue
			}
			var tparams *types.TypeParamList
			switch t := inst.Type.(type) {
			case *types.Named:
				tparams = t.Origin().TypeParams()
			case *types.Signature:
				if o, ok := p.TypesInfo.Uses[id]; ok {
					if f, ok := o.(*types.Func); ok {
						tparams = f.Origin().Signature().TypeParams()
					}
				}
			}
			if tparams == nil || tparams.Len() != inst.TypeArgs.Len() {
				continue
			}
			for i := 0; i < tparams.Len(); i++ {
				c, ok := types.Unalias(tparams.At(i).Constraint()).(*types.Named)
				if !ok || c.TypeArgs().Len() == 0 {
					continue
				}
				args := make([]types.Type, c.TypeArgs().Len())
				mapped := true
				for j := 0; j < c.TypeArgs().Len(); j++ {
					tp, ok := c.TypeArgs().At(j).(*types.TypeParam)
					if !ok || tp.Index() >= inst.TypeArgs.Len() || tparams.At(tp.Index()) != tp {
						mapped = false
						break
					}
					args[j] = inst.TypeArgs.At(tp.Index())
					if _, open := types.Unalias(args[j]).(*types.TypeParam); open {
						mapped = false // an instantiation inside generic code: no concrete type to decorate
						break
					}
				}
				if !mapped {
					continue
				}
				if ct, err := types.Instantiate(nil, c.Origin(), args, false); err == nil {
					consider(ct)
				}
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
			if impl := p.genericImplementsOf(n); len(impl) > 0 {
				e.S("implements", impl)
			} else {
				e.S("implementsSkipped", "generic")
			}
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

// genericImplementsOf returns the "implements" list of a GENERIC named type: each generic interface of the
// run with as many type parameters, instantiated with the type's OWN type parameters (positionally), that
// the type instantiated with those same parameters (or a pointer to it) satisfies — `*Box[T]` implements
// `Value[T]`. The entry's iface type names the type's own type parameters.
func (p *px) genericImplementsOf(n *types.Named) []any {
	out := []any{}
	tps := make([]types.Type, n.TypeParams().Len())
	for i := range tps {
		tps[i] = n.TypeParams().At(i)
	}
	self, err := types.Instantiate(nil, n, tps, false)
	if err != nil {
		return out
	}
	keys := make([]string, 0, len(genericIfaces))
	for key := range genericIfaces {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		gi := genericIfaces[key]
		m := gi.TypeParams().Len()
		if m > len(tps) || m > 3 {
			continue
		}
		// every m-tuple of the type's own parameters (`MapEntry[K, V]` implements `Value[V]`)
		idx := make([]int, m)
		for {
			args := make([]types.Type, m)
			for i, j := range idx {
				args[i] = tps[j]
			}
			if inst, err := types.Instantiate(nil, gi, args, true); err == nil {
				if it, ok := inst.Underlying().(*types.Interface); ok {
					via := ""
					if types.Implements(self, it) {
						via = "value"
					} else if types.Implements(types.NewPointer(self), it) {
						via = "pointer"
					}
					if via != "" {
						out = append(out, obj().S("iface", p.typ(inst, false)).S("key", p.typeKey(inst)).S("via", via))
					}
				}
			}
			k := m - 1
			for k >= 0 {
				idx[k]++
				if idx[k] < len(tps) {
					break
				}
				idx[k] = 0
				k--
			}
			if k < 0 {
				break
			}
		}
	}
	return out
}
