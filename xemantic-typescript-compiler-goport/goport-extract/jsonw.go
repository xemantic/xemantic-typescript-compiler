package main

// A tiny ordered-JSON builder and writer.
//
// The IR must be byte-deterministic across runs, so we never marshal Go maps
// (whose iteration order is random) and never rely on reflection-based field
// ordering. Every JSON object is an *O: an ordered list of key/value pairs,
// written in insertion order.

import (
	"bufio"
	"strconv"
	"unicode/utf8"
)

// O is an ordered JSON object.
type O struct {
	keys []string
	vals []any
}

func obj() *O { return &O{} }

// S appends a key/value pair and returns the receiver for chaining.
func (o *O) S(k string, v any) *O {
	o.keys = append(o.keys, k)
	o.vals = append(o.vals, v)
	return o
}

// Opt appends the pair only when v is not a zero/empty value.
func (o *O) Opt(k string, v any) *O {
	switch x := v.(type) {
	case nil:
		return o
	case bool:
		if !x {
			return o
		}
	case string:
		if x == "" {
			return o
		}
	case int:
		if x == 0 {
			return o
		}
	case []any:
		if len(x) == 0 {
			return o
		}
	case []int:
		if len(x) == 0 {
			return o
		}
	case Lines:
		if len(x) == 0 {
			return o
		}
	case *O:
		if x == nil {
			return o
		}
	}
	return o.S(k, v)
}

// Lines is a JSON array written with one element per line (for diffable
// top-level tables); otherwise identical to []any.
type Lines []any

type jw struct {
	w *bufio.Writer
}

func (j *jw) val(v any) {
	w := j.w
	switch x := v.(type) {
	case nil:
		w.WriteString("null")
	case bool:
		if x {
			w.WriteString("true")
		} else {
			w.WriteString("false")
		}
	case int:
		w.WriteString(strconv.Itoa(x))
	case int64:
		w.WriteString(strconv.FormatInt(x, 10))
	case string:
		j.str(x)
	case *O:
		if x == nil {
			w.WriteString("null")
			return
		}
		w.WriteByte('{')
		for i, k := range x.keys {
			if i > 0 {
				w.WriteByte(',')
			}
			j.str(k)
			w.WriteByte(':')
			j.val(x.vals[i])
		}
		w.WriteByte('}')
	case []any:
		w.WriteByte('[')
		for i, e := range x {
			if i > 0 {
				w.WriteByte(',')
			}
			j.val(e)
		}
		w.WriteByte(']')
	case Lines:
		w.WriteByte('[')
		for i, e := range x {
			if i > 0 {
				w.WriteByte(',')
			}
			w.WriteByte('\n')
			j.val(e)
		}
		if len(x) > 0 {
			w.WriteByte('\n')
		}
		w.WriteByte(']')
	case []int:
		w.WriteByte('[')
		for i, e := range x {
			if i > 0 {
				w.WriteByte(',')
			}
			w.WriteString(strconv.Itoa(e))
		}
		w.WriteByte(']')
	case []string:
		w.WriteByte('[')
		for i, e := range x {
			if i > 0 {
				w.WriteByte(',')
			}
			j.str(e)
		}
		w.WriteByte(']')
	default:
		panic("jsonw: unsupported value type")
	}
}

const hexDigits = "0123456789abcdef"

// str writes a JSON string. Invalid UTF-8 is replaced by U+FFFD; the IR only
// passes TEXT through here (names, doc comments, type keys). Byte-exact data
// (string constants, struct tags) is base64-encoded by the caller instead.
func (j *jw) str(s string) {
	w := j.w
	w.WriteByte('"')
	for i := 0; i < len(s); {
		c := s[i]
		if c < utf8.RuneSelf {
			switch {
			case c == '"':
				w.WriteString(`\"`)
			case c == '\\':
				w.WriteString(`\\`)
			case c == '\n':
				w.WriteString(`\n`)
			case c == '\r':
				w.WriteString(`\r`)
			case c == '\t':
				w.WriteString(`\t`)
			case c < 0x20 || c == 0x7f:
				w.WriteString(`\u00`)
				w.WriteByte(hexDigits[c>>4])
				w.WriteByte(hexDigits[c&0xF])
			default:
				w.WriteByte(c)
			}
			i++
			continue
		}
		r, size := utf8.DecodeRuneInString(s[i:])
		if r == utf8.RuneError && size == 1 {
			w.WriteString(`�`)
		} else if r == ' ' || r == ' ' {
			w.WriteString(`\u202`)
			w.WriteByte(hexDigits[r&0xF])
		} else {
			w.WriteString(s[i : i+size])
		}
		i += size
	}
	w.WriteByte('"')
}
