// Package program is the Go view of program.json (schema/program.schema.json).
//
// The schema is large and keeps growing, and other services own most of it.
// Each node therefore keeps a typed view of the few fields Go reads or writes
// (V) and carries every other field through untouched, so decoding and
// re-encoding a program never loses data it does not know about.
package program

import (
	"encoding/json"
	"maps"
)

// Node wraps one JSON object: V is the typed view, raw holds all of its
// original fields.
type Node[T any] struct {
	V   T
	raw map[string]json.RawMessage
}

func (n *Node[T]) UnmarshalJSON(data []byte) error {
	n.raw = nil
	if err := json.Unmarshal(data, &n.raw); err != nil {
		return err
	}
	return json.Unmarshal(data, &n.V)
}

// MarshalJSON writes the original fields, with those the typed view owns
// replaced by their current values.
func (n Node[T]) MarshalJSON() ([]byte, error) {
	typed, err := json.Marshal(n.V)
	if err != nil {
		return nil, err
	}
	var owned map[string]json.RawMessage
	if err := json.Unmarshal(typed, &owned); err != nil {
		return nil, err
	}

	out := maps.Clone(n.raw)
	if out == nil {
		out = make(map[string]json.RawMessage, len(owned))
	}
	maps.Copy(out, owned)
	return json.Marshal(out)
}
