package main

// The three per-package tables of the IR: TYPES, OBJECTS and SCOPES.
//
// Ids are dense integers assigned in FIRST-ENCOUNTER order of a deterministic
// traversal (files sorted by name, AST in source order), so two runs produce
// identical ids. Ids are LOCAL to one package's IR file; the cross-package
// join key is the "key" field (types) or the "key" field (package-level
// objects, methods, fields of named structs).

import (
	"encoding/base64"
	"fmt"
	"go/constant"
	"go/token"
	"go/types"
	"path/filepath"
	"strconv"
	"strings"
)

// ---------------------------------------------------------------- type keys

// typeKey returns a canonical string identifying t. Two types get the same
// key iff go/types considers them identical (modulo parameter NAMES, which are
// kept in the key so a signature entry can carry them) — with two refinements
// that make the key unique where a TypeString is not: type parameters and
// function-local named types carry their declaring position.
func (p *px) typeKey(t types.Type) string {
	if k, ok := p.keyMemo[t]; ok {
		return k
	}
	var b strings.Builder
	p.writeKey(&b, t)
	k := b.String()
	p.keyMemo[t] = k
	return k
}

func (p *px) writeKey(b *strings.Builder, t types.Type) {
	switch t := t.(type) {
	case nil:
		b.WriteString("<nil>")
	case *types.Basic:
		b.WriteString(t.Name())
	case *types.Named:
		b.WriteString(p.typeNameKey(t.Obj()))
		if ta := t.TypeArgs(); ta != nil && ta.Len() > 0 {
			b.WriteByte('[')
			for i := 0; i < ta.Len(); i++ {
				if i > 0 {
					b.WriteByte(',')
				}
				b.WriteString(p.typeKey(ta.At(i)))
			}
			b.WriteByte(']')
		}
	case *types.Alias:
		b.WriteString("alias:")
		b.WriteString(p.typeNameKey(t.Obj()))
		if ta := t.TypeArgs(); ta != nil && ta.Len() > 0 {
			b.WriteByte('[')
			for i := 0; i < ta.Len(); i++ {
				if i > 0 {
					b.WriteByte(',')
				}
				b.WriteString(p.typeKey(ta.At(i)))
			}
			b.WriteByte(']')
		}
	case *types.Pointer:
		b.WriteByte('*')
		b.WriteString(p.typeKey(t.Elem()))
	case *types.Slice:
		b.WriteString("[]")
		b.WriteString(p.typeKey(t.Elem()))
	case *types.Array:
		b.WriteString("[" + strconv.FormatInt(t.Len(), 10) + "]")
		b.WriteString(p.typeKey(t.Elem()))
	case *types.Map:
		b.WriteString("map[" + p.typeKey(t.Key()) + "]" + p.typeKey(t.Elem()))
	case *types.Chan:
		switch t.Dir() {
		case types.SendRecv:
			b.WriteString("chan(")
		case types.SendOnly:
			b.WriteString("chan<-(")
		case types.RecvOnly:
			b.WriteString("<-chan(")
		}
		b.WriteString(p.typeKey(t.Elem()) + ")")
	case *types.Signature:
		b.WriteString("func")
		if tp := t.TypeParams(); tp != nil && tp.Len() > 0 {
			b.WriteByte('[')
			for i := 0; i < tp.Len(); i++ {
				if i > 0 {
					b.WriteByte(',')
				}
				b.WriteString(p.typeKey(tp.At(i)))
			}
			b.WriteByte(']')
		}
		if r := t.Recv(); r != nil {
			b.WriteString("(recv " + r.Name() + " " + p.typeKey(r.Type()) + ")")
		}
		b.WriteByte('(')
		for i := 0; i < t.Params().Len(); i++ {
			if i > 0 {
				b.WriteByte(',')
			}
			v := t.Params().At(i)
			b.WriteString(v.Name() + " ")
			if t.Variadic() && i == t.Params().Len()-1 {
				b.WriteString("...")
			}
			b.WriteString(p.typeKey(v.Type()))
		}
		b.WriteString(")(")
		for i := 0; i < t.Results().Len(); i++ {
			if i > 0 {
				b.WriteByte(',')
			}
			v := t.Results().At(i)
			b.WriteString(v.Name() + " " + p.typeKey(v.Type()))
		}
		b.WriteByte(')')
	case *types.Struct:
		b.WriteString("struct{")
		for i := 0; i < t.NumFields(); i++ {
			if i > 0 {
				b.WriteByte(';')
			}
			f := t.Field(i)
			if f.Embedded() {
				b.WriteString("embedded ")
			}
			if !f.Exported() && f.Pkg() != nil {
				b.WriteString(f.Pkg().Path() + ".")
			}
			b.WriteString(f.Name() + " " + p.typeKey(f.Type()))
			if tag := t.Tag(i); tag != "" {
				b.WriteString(" " + strconv.Quote(tag))
			}
		}
		b.WriteByte('}')
	case *types.Interface:
		b.WriteString("interface{")
		n := 0
		for i := 0; i < t.NumExplicitMethods(); i++ {
			if n > 0 {
				b.WriteByte(';')
			}
			n++
			m := t.ExplicitMethod(i)
			b.WriteString(m.Name() + " " + p.typeKey(sigNoRecv(m.Type().(*types.Signature))))
		}
		for i := 0; i < t.NumEmbeddeds(); i++ {
			if n > 0 {
				b.WriteByte(';')
			}
			n++
			b.WriteString("embed " + p.typeKey(t.EmbeddedType(i)))
		}
		b.WriteByte('}')
	case *types.TypeParam:
		b.WriteString("typeparam:" + t.Obj().Name() + "@" + p.posKey(t.Obj().Pos()))
	case *types.Tuple:
		b.WriteByte('(')
		for i := 0; i < t.Len(); i++ {
			if i > 0 {
				b.WriteByte(',')
			}
			b.WriteString(t.At(i).Name() + " " + p.typeKey(t.At(i).Type()))
		}
		b.WriteByte(')')
	case *types.Union:
		b.WriteString("union(")
		for i := 0; i < t.Len(); i++ {
			if i > 0 {
				b.WriteByte('|')
			}
			if t.Term(i).Tilde() {
				b.WriteByte('~')
			}
			b.WriteString(p.typeKey(t.Term(i).Type()))
		}
		b.WriteByte(')')
	default:
		panic(fmt.Sprintf("typeKey: unhandled %T", t))
	}
}

// sigNoRecv drops the receiver of an interface method signature: the receiver
// of an interface method is the interface itself, which would make the key of
// an anonymous interface recursive.
func sigNoRecv(s *types.Signature) *types.Signature {
	if s.Recv() == nil {
		return s
	}
	return types.NewSignatureType(nil, nil, nil, s.Params(), s.Results(), s.Variadic())
}

// typeNameKey is the stable name of a type-name object: "pkgpath.Name", or
// "pkgpath.Name@file:offset" for a function-local type.
func (p *px) typeNameKey(o *types.TypeName) string {
	if o.Pkg() == nil {
		return o.Name() // universe: error, comparable, any
	}
	k := o.Pkg().Path() + "." + o.Name()
	if o.Parent() != nil && o.Parent() != o.Pkg().Scope() {
		k += "@" + p.posKey(o.Pos())
	}
	return k
}

// posKey renders a position as "file.go:offset" with the file's BASE name, so
// keys do not depend on where the repository is checked out.
func (p *px) posKey(pos token.Pos) string {
	if !pos.IsValid() {
		return "-"
	}
	f := p.fset.File(pos)
	if f == nil {
		return "-"
	}
	return filepath.Base(f.Name()) + ":" + strconv.Itoa(f.Offset(pos))
}

// posLC renders "file.go:line:col".
func (p *px) posLC(pos token.Pos) string {
	if !pos.IsValid() {
		return ""
	}
	ps := p.fset.Position(pos)
	return filepath.Base(ps.Filename) + ":" + strconv.Itoa(ps.Line) + ":" + strconv.Itoa(ps.Column)
}

// ---------------------------------------------------------------- type table

// typ registers t and returns its id. full=true additionally computes the
// method sets of named types reached through STRUCTURAL composition (pointer,
// slice, array, map, chan elements, struct fields, named underlying types).
// Types reached only through a signature or a method-set entry are registered
// with full=false: their entry is complete except that a named type's method
// sets may be absent ("msets":false); the lowering then finds them by "key" in
// another entry or package. Named types DECLARED in the package being
// extracted always get their method sets.
func (p *px) typ(t types.Type, full bool) int {
	if t == nil {
		return -1
	}
	k := p.typeKey(t)
	if id, ok := p.typeIds[k]; ok {
		if st, isStruct := t.(*types.Struct); isStruct {
			// Identical struct types (e.g. the underlying types of two named
			// structs with the same fields) share one entry, but each has its
			// own field objects: map them all to the shared entry.
			for i := 0; i < st.NumFields(); i++ {
				if _, seen := p.fieldOwner[st.Field(i)]; !seen {
					p.fieldOwner[st.Field(i)] = id
				}
			}
		}
		if n, isNamed := t.(*types.Named); isNamed {
			// go/types does not canonicalize instances: an identical
			// instantiation may be a distinct *Named whose underlying struct
			// has its own field objects. Map those too.
			if _, isStruct := n.Underlying().(*types.Struct); isStruct {
				p.typ(n.Underlying(), false)
			}
		}
		if full && !p.typeFull[id] {
			p.typeFull[id] = true
			p.fillFull(id, t)
		}
		return id
	}
	id := len(p.typeEntries)
	p.typeIds[k] = id
	e := obj().S("id", id)
	p.typeEntries = append(p.typeEntries, e)
	p.typeFull[id] = full
	p.fillType(e, t, full)
	e.S("key", k)
	if full {
		p.fillFull(id, t)
	}
	return id
}

func basicKindName(k types.BasicKind) string {
	names := map[types.BasicKind]string{
		types.Invalid: "Invalid", types.Bool: "Bool", types.Int: "Int", types.Int8: "Int8",
		types.Int16: "Int16", types.Int32: "Int32", types.Int64: "Int64", types.Uint: "Uint",
		types.Uint8: "Uint8", types.Uint16: "Uint16", types.Uint32: "Uint32", types.Uint64: "Uint64",
		types.Uintptr: "Uintptr", types.Float32: "Float32", types.Float64: "Float64",
		types.Complex64: "Complex64", types.Complex128: "Complex128", types.String: "String",
		types.UnsafePointer: "UnsafePointer", types.UntypedBool: "UntypedBool",
		types.UntypedInt: "UntypedInt", types.UntypedRune: "UntypedRune",
		types.UntypedFloat: "UntypedFloat", types.UntypedComplex: "UntypedComplex",
		types.UntypedString: "UntypedString", types.UntypedNil: "UntypedNil",
	}
	if n, ok := names[k]; ok {
		return n
	}
	return "Kind" + strconv.Itoa(int(k))
}

func (p *px) typeList(tl *types.TypeList, full bool) []int {
	if tl == nil || tl.Len() == 0 {
		return nil
	}
	out := make([]int, tl.Len())
	for i := range out {
		out[i] = p.typ(tl.At(i), full)
	}
	return out
}

func (p *px) tparamList(tl *types.TypeParamList) []int {
	if tl == nil || tl.Len() == 0 {
		return nil
	}
	out := make([]int, tl.Len())
	for i := range out {
		out[i] = p.typ(tl.At(i), false)
	}
	return out
}

func (p *px) varList(t *types.Tuple, full bool) []any {
	var out []any
	for i := 0; i < t.Len(); i++ {
		v := t.At(i)
		out = append(out, obj().S("name", v.Name()).S("t", p.typ(v.Type(), full)))
	}
	if out == nil {
		out = []any{}
	}
	return out
}

func (p *px) fillType(e *O, t types.Type, full bool) {
	switch t := t.(type) {
	case *types.Basic:
		e.S("k", "basic").S("name", t.Name()).S("kind", basicKindName(t.Kind()))
		e.Opt("untyped", t.Info()&types.IsUntyped != 0)
		if t.Info()&types.IsUntyped != 0 && t.Kind() != types.UntypedNil {
			e.S("default", p.typ(types.Default(t), false))
		}
	case *types.Named:
		o := t.Obj()
		e.S("k", "named").S("name", o.Name())
		if o.Pkg() != nil {
			e.S("pkg", o.Pkg().Path())
		}
		if o.Parent() != nil && o.Pkg() != nil && o.Parent() != o.Pkg().Scope() {
			e.S("localAt", p.posLC(o.Pos()))
		}
		if orig := t.Origin(); orig != t {
			e.S("origin", p.typ(orig, false))
			e.S("targs", p.typeList(t.TypeArgs(), full))
		} else {
			e.Opt("tparams", p.tparamList(t.TypeParams()))
		}
		u := t.Underlying()
		if st, ok := u.(*types.Struct); ok {
			nk := p.typeNameKey(t.Origin().Obj())
			for i := 0; i < st.NumFields(); i++ {
				if _, seen := p.fieldNamed[st.Field(i).Origin()]; !seen {
					p.fieldNamed[st.Field(i).Origin()] = nk
				}
			}
		}
		e.S("u", p.typ(u, full))
		_, isStruct := u.(*types.Struct)
		_, isIface := u.(*types.Interface)
		e.S("isStruct", isStruct).S("isIface", isIface).S("comparable", types.Comparable(t))
		var ms []any
		for i := 0; i < t.NumMethods(); i++ {
			m := t.Method(i)
			sig := m.Type().(*types.Signature)
			ptr := false
			if sig.Recv() != nil {
				_, ptr = sig.Recv().Type().(*types.Pointer)
			}
			ms = append(ms, obj().S("name", m.Name()).S("fn", p.funcKey(m)).S("ptrRecv", ptr).S("sig", p.typ(sigNoRecv(sig), false)))
		}
		e.Opt("methods", ms)
	case *types.Alias:
		o := t.Obj()
		e.S("k", "alias").S("name", o.Name())
		if o.Pkg() != nil {
			e.S("pkg", o.Pkg().Path())
		}
		e.Opt("targs", p.typeList(t.TypeArgs(), full))
		e.Opt("tparams", p.tparamList(t.TypeParams()))
		e.S("rhs", p.typ(t.Rhs(), full))
		e.S("actual", p.typ(types.Unalias(t), full))
	case *types.Pointer:
		e.S("k", "pointer").S("elem", p.typ(t.Elem(), full))
	case *types.Slice:
		e.S("k", "slice").S("elem", p.typ(t.Elem(), full))
	case *types.Array:
		e.S("k", "array").S("len", int(t.Len())).S("elem", p.typ(t.Elem(), full))
	case *types.Map:
		e.S("k", "map").S("key", p.typ(t.Key(), full)).S("elem", p.typ(t.Elem(), full))
	case *types.Chan:
		dir := map[types.ChanDir]string{types.SendRecv: "both", types.SendOnly: "send", types.RecvOnly: "recv"}[t.Dir()]
		e.S("k", "chan").S("dir", dir).S("elem", p.typ(t.Elem(), full))
	case *types.Signature:
		e.S("k", "signature")
		e.S("params", p.varList(t.Params(), false)).S("results", p.varList(t.Results(), false))
		e.Opt("variadic", t.Variadic())
		if r := t.Recv(); r != nil {
			_, ptr := r.Type().(*types.Pointer)
			e.S("recv", obj().S("name", r.Name()).S("t", p.typ(r.Type(), false)).S("ptr", ptr))
		}
		e.Opt("tparams", p.tparamList(t.TypeParams()))
		e.Opt("recvTparams", p.tparamList(t.RecvTypeParams()))
	case *types.Struct:
		e.S("k", "struct")
		var fs []any
		for i := 0; i < t.NumFields(); i++ {
			f := t.Field(i)
			fe := obj().S("name", f.Name()).S("t", p.typ(f.Type(), full))
			fe.Opt("embedded", f.Embedded()).S("exported", f.Exported())
			if !f.Exported() && f.Pkg() != nil {
				fe.S("pkg", f.Pkg().Path())
			}
			fe.Opt("tag", t.Tag(i))
			fs = append(fs, fe)
		}
		if fs == nil {
			fs = []any{}
		}
		e.S("fields", fs)
		// The struct's own id is e's "id" (first key).
		id := e.vals[0].(int)
		for i := 0; i < t.NumFields(); i++ {
			p.fieldOwner[t.Field(i)] = id
		}
	case *types.Interface:
		e.S("k", "interface")
		var ms []any
		for i := 0; i < t.NumExplicitMethods(); i++ {
			m := t.ExplicitMethod(i)
			ms = append(ms, obj().S("name", m.Name()).S("sig", p.typ(sigNoRecv(m.Type().(*types.Signature)), false)))
		}
		if ms == nil {
			ms = []any{}
		}
		e.S("methods", ms)
		var em []int
		for i := 0; i < t.NumEmbeddeds(); i++ {
			em = append(em, p.typ(t.EmbeddedType(i), full))
		}
		e.Opt("embedded", em)
		// The complete method set (explicit + embedded), sorted by Id.
		var all []any
		for i := 0; i < t.NumMethods(); i++ {
			m := t.Method(i)
			all = append(all, obj().S("name", m.Name()).S("fn", p.funcKey(m)).S("sig", p.typ(sigNoRecv(m.Type().(*types.Signature)), false)))
		}
		e.Opt("allMethods", all)
		e.S("comparable", t.IsComparable()).Opt("isMethodSet", t.IsMethodSet()).Opt("implicit", t.IsImplicit())
	case *types.TypeParam:
		e.S("k", "typeparam").S("name", t.Obj().Name()).S("index", t.Index())
		e.S("at", p.posLC(t.Obj().Pos()))
		e.S("constraint", p.typ(t.Constraint(), false))
		if ct := coreType(t); ct != nil {
			e.S("core", p.typ(ct, false))
		}
	case *types.Tuple:
		e.S("k", "tuple").S("elems", p.varList(t, full))
	case *types.Union:
		e.S("k", "union")
		var ts []any
		for i := 0; i < t.Len(); i++ {
			ts = append(ts, obj().S("tilde", t.Term(i).Tilde()).S("t", p.typ(t.Term(i).Type(), false)))
		}
		e.S("terms", ts)
	default:
		panic(fmt.Sprintf("fillType: unhandled %T", t))
	}
}

// fillFull adds the method sets of T and *T to a named type's entry.
func (p *px) fillFull(id int, t types.Type) {
	n, ok := t.(*types.Named)
	if !ok {
		return
	}
	e := p.typeEntries[id]
	for _, k := range e.keys {
		if k == "msetT" {
			return
		}
	}
	e.S("msetT", p.methodSet(types.NewMethodSet(n)))
	if _, isIface := n.Underlying().(*types.Interface); !isIface {
		e.S("msetPtr", p.methodSet(types.NewMethodSet(types.NewPointer(n))))
	}
}

func (p *px) methodSet(ms *types.MethodSet) []any {
	out := []any{}
	for i := 0; i < ms.Len(); i++ {
		s := ms.At(i)
		f := s.Obj().(*types.Func)
		sig := f.Type().(*types.Signature)
		ptr := false
		if sig.Recv() != nil {
			_, ptr = sig.Recv().Type().(*types.Pointer)
		}
		out = append(out, obj().S("name", f.Name()).S("fn", p.funcKey(f)).
			S("path", intsOf(s.Index())).S("indirect", s.Indirect()).S("ptrRecv", ptr).
			S("sig", p.typ(s.Type(), false)))
	}
	return out
}

func intsOf(xs []int) []int {
	if xs == nil {
		return []int{}
	}
	return append([]int{}, xs...)
}

// funcKey is the stable qualified name of a function or method:
// "pkgpath.Name" or "pkgpath.Recv.Name" (Recv = the base type name, without
// pointer or type arguments). Interface methods are keyed by their interface.
func (p *px) funcKey(f *types.Func) string {
	f = f.Origin()
	sig := f.Type().(*types.Signature)
	pkg := ""
	if f.Pkg() != nil {
		pkg = f.Pkg().Path()
	}
	if sig.Recv() == nil {
		if pkg == "" {
			return "builtin." + f.Name()
		}
		return pkg + "." + f.Name()
	}
	rt := sig.Recv().Type()
	if pt, ok := rt.(*types.Pointer); ok {
		rt = pt.Elem()
	}
	switch r := types.Unalias(rt).(type) {
	case *types.Named:
		return p.typeNameKey(r.Origin().Obj()) + "." + f.Name()
	case *types.Interface:
		// method of an anonymous interface
		if pkg == "" {
			return "builtin.?." + f.Name()
		}
		return pkg + ".?." + f.Name()
	}
	return pkg + ".?." + f.Name()
}

// ---------------------------------------------------------------- objects

// objID registers o (lazily; entries are built in finish()) and returns its id.
func (p *px) objID(o types.Object) int {
	if o == nil {
		return -1
	}
	if id, ok := p.objIds[o]; ok {
		return id
	}
	id := len(p.objList)
	p.objIds[o] = id
	p.objList = append(p.objList, o)
	if p.curDecl != "" && isLocal(o) {
		p.objFn[o] = p.curDecl
	}
	return id
}

// isLocal reports whether o is declared inside a function (not package level,
// not a field or method).
func isLocal(o types.Object) bool {
	if o.Pkg() == nil || o.Parent() == nil {
		return false
	}
	ps := o.Pkg().Scope()
	s := o.Parent()
	// package scope, universe, and FILE scopes (whose parent is the package
	// scope; they hold only imported package names) are not local.
	return s != ps && s != types.Universe && s.Parent() != ps
}

func objKind(o types.Object) string {
	switch o := o.(type) {
	case *types.Var:
		switch o.Kind() {
		case types.PackageVar, types.LocalVar:
			return "var"
		case types.RecvVar:
			return "recv"
		case types.ParamVar:
			return "param"
		case types.ResultVar:
			return "result"
		case types.FieldVar:
			return "field"
		}
		return "var"
	case *types.Const:
		return "const"
	case *types.TypeName:
		return "typename"
	case *types.Func:
		if o.Type().(*types.Signature).Recv() != nil {
			return "method"
		}
		return "func"
	case *types.PkgName:
		return "pkgname"
	case *types.Label:
		return "label"
	case *types.Builtin:
		return "builtin"
	case *types.Nil:
		return "nil"
	}
	return "?"
}

// objKey is the cross-package stable name of an object, or "" for locals.
func (p *px) objKey(o types.Object) string {
	switch o := o.(type) {
	case *types.Func:
		return p.funcKey(o)
	case *types.TypeName:
		if isLocal(o) {
			return ""
		}
		return p.typeNameKey(o)
	case *types.Builtin, *types.Nil:
		return "builtin." + o.Name()
	case *types.PkgName, *types.Label:
		return "" // file- / function-scoped; see "imported"
	case *types.Var:
		if o.Kind() == types.FieldVar {
			if n, ok := p.fieldNamed[o.Origin()]; ok {
				return n + "." + o.Name()
			}
			return ""
		}
	}
	if o.Pkg() == nil {
		return "builtin." + o.Name()
	}
	if isLocal(o) || o.Parent() == nil {
		return ""
	}
	return o.Pkg().Path() + "." + o.Name()
}

func (p *px) finishObjects() Lines {
	out := Lines{}
	for i := 0; i < len(p.objList); i++ { // the list may grow while we build
		o := p.objList[i]
		e := obj().S("id", i).S("k", objKind(o)).S("name", o.Name())
		if o.Pkg() != nil {
			e.S("pkg", o.Pkg().Path())
		}
		e.Opt("key", p.objKey(o))
		e.S("exported", o.Exported())
		switch o.(type) {
		case *types.Label, *types.PkgName, *types.Builtin:
		default:
			e.S("t", p.typ(o.Type(), o.Pkg() == p.pkg.Types || isLocal(o)))
		}
		if o.Pkg() != nil && p.closure[o.Pkg().Path()] {
			e.Opt("at", p.posLC(o.Pos()))
		}
		if isLocal(o) {
			e.S("local", true)
			e.S("scope", p.scopeID(o.Parent()))
			e.Opt("fn", p.objFn[o])
		}
		f := p.facts[o]
		if f != nil {
			e.Opt("captured", f.captured).Opt("addr", f.addr).Opt("mut", f.mut)
		}
		switch o := o.(type) {
		case *types.Var:
			if o.Kind() == types.FieldVar {
				if id, ok := p.fieldOwner[o]; ok {
					e.S("owner", id)
				} else {
					p.hole("field-without-owner", o.Name()+" "+p.posLC(o.Pos()))
				}
				e.Opt("embedded", o.Embedded())
			}
			if org := o.Origin(); org != o {
				e.S("origin", p.objID(org))
			}
		case *types.Func:
			sig := o.Type().(*types.Signature)
			if r := sig.Recv(); r != nil {
				_, ptr := r.Type().(*types.Pointer)
				e.S("recv", p.typ(r.Type(), false)).S("ptrRecv", ptr)
				if _, isIface := r.Type().Underlying().(*types.Interface); isIface {
					e.S("abstract", true)
				}
			}
			if org := o.Origin(); org != o {
				e.S("origin", p.objID(org))
			}
		case *types.Const:
			e.S("c", constVal(o.Val()))
		case *types.TypeName:
			e.Opt("alias", o.IsAlias())
		case *types.PkgName:
			e.S("imported", o.Imported().Path())
		}
		out = append(out, e)
	}
	return out
}

// constVal encodes a constant exactly. Strings are BYTE strings in Go, so they
// are base64 (never JSON text, which would mangle invalid UTF-8).
func constVal(v constant.Value) *O {
	switch v.Kind() {
	case constant.Bool:
		return obj().S("kind", "bool").S("v", constant.BoolVal(v))
	case constant.String:
		return obj().S("kind", "string").S("b64", base64.StdEncoding.EncodeToString([]byte(constant.StringVal(v))))
	case constant.Int:
		return obj().S("kind", "int").S("v", v.ExactString())
	case constant.Float:
		f, _ := constant.Float64Val(v)
		return obj().S("kind", "float").S("v", v.ExactString()).S("f64", strconv.FormatFloat(f, 'g', -1, 64))
	case constant.Complex:
		return obj().S("kind", "complex").S("re", constant.Real(v).ExactString()).S("im", constant.Imag(v).ExactString())
	}
	return obj().S("kind", "unknown")
}

// ---------------------------------------------------------------- scopes

func (p *px) scopeID(s *types.Scope) int {
	if s == nil {
		return -1
	}
	if id, ok := p.scopeIds[s]; ok {
		return id
	}
	id := len(p.scopeEntries)
	p.scopeIds[s] = id
	e := obj().S("id", id)
	p.scopeEntries = append(p.scopeEntries, e)
	kind := "?"
	switch {
	case s == types.Universe:
		kind = "universe"
	case s == p.pkg.Types.Scope():
		kind = "package"
	default:
		if n, ok := p.scopeNode[s]; ok {
			kind = strings.TrimPrefix(fmt.Sprintf("%T", n), "*ast.")
		} else {
			// A function's label scope is not in Info.Scopes.
			allLabels := s.Len() > 0
			for _, name := range s.Names() {
				if _, isLabel := s.Lookup(name).(*types.Label); !isLabel {
					allLabels = false
				}
			}
			if allLabels {
				kind = "labels"
			} else {
				p.hole("scope-without-node", p.posLC(s.Pos()))
			}
		}
	}
	e.S("k", kind)
	if s.Parent() != nil {
		e.S("parent", p.scopeID(s.Parent()))
	}
	if s.Pos().IsValid() {
		e.S("file", filepath.Base(p.fset.Position(s.Pos()).Filename))
		e.S("o", p.off(s.Pos())).S("e", p.off(s.End()))
		if fn := p.declAt(s.Pos()); fn != "" {
			e.S("fn", fn)
		}
	}
	return id
}

// coreType returns the core type of a type parameter (the single underlying
// type shared by every term of its constraint's type set), or nil. go/types
// computes this internally but does not export it.
func coreType(t types.Type) types.Type {
	tp, ok := t.(*types.TypeParam)
	if !ok {
		return t.Underlying()
	}
	var terms []types.Type
	var collect func(it *types.Interface) bool
	collect = func(it *types.Interface) bool {
		for i := 0; i < it.NumEmbeddeds(); i++ {
			switch e := it.EmbeddedType(i).(type) {
			case *types.Union:
				for j := 0; j < e.Len(); j++ {
					terms = append(terms, e.Term(j).Type().Underlying())
				}
			default:
				if ei, ok := e.Underlying().(*types.Interface); ok {
					if !collect(ei) {
						return false
					}
				} else {
					terms = append(terms, e.Underlying())
				}
			}
		}
		return true
	}
	ci, ok := tp.Constraint().Underlying().(*types.Interface)
	if !ok || !collect(ci) || len(terms) == 0 {
		return nil
	}
	for _, x := range terms[1:] {
		if !types.Identical(x, terms[0]) {
			return nil
		}
	}
	return terms[0]
}
