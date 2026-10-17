package program

// Program is the root of program.json.
type Program = Node[Root]

// Root lists the top-level blocks Go reads. Optional blocks are pointers:
// nil means the key is absent from the program.
type Root struct {
	Request          *Node[Request]          `json:"request,omitempty"`
	Header           *Node[Header]           `json:"header,omitempty"`
	Curriculum       *Node[Curriculum]       `json:"curriculum,omitempty"`
	FinalAttestation *Node[FinalAttestation] `json:"finalAttestation,omitempty"`
}

type Request struct {
	Params *Node[Params] `json:"params,omitempty"`
}

// Params is the part of request.params that affects hours.
type Params struct {
	MinPracticeShare *float64 `json:"minPracticeShare,omitempty"`
	IsInternship     *bool    `json:"isInternship,omitempty"`
}

type Header struct {
	TotalHours float64 `json:"totalHours"`
	StudyForm  string  `json:"studyForm"`
}

type Curriculum struct {
	Modules []Node[Module] `json:"modules"`
	Totals  Hours          `json:"totals"`
}

type Module struct {
	ID      string        `json:"id"`
	IsIntro *bool         `json:"isIntro,omitempty"`
	Weight  *float64      `json:"weight,omitempty"`
	Hours   Hours         `json:"hours"`
	Topics  []Node[Topic] `json:"topics"`
	Meta    *Node[Meta]   `json:"_meta,omitempty"`
}

type Topic struct {
	ID     string      `json:"id"`
	Weight *float64    `json:"weight,omitempty"`
	Hours  Hours       `json:"hours"`
	Meta   *Node[Meta] `json:"_meta,omitempty"`
}

type FinalAttestation struct {
	Hours Hours       `json:"hours"`
	Meta  *Node[Meta] `json:"_meta,omitempty"`
}

// Meta is the provenance block of a node ($defs/nodeMeta).
type Meta struct {
	Locked *bool `json:"locked,omitempty"`
}

// Hours follows $defs/hours: 0.5-hour steps,
// contact = lecture+practice+lab, total = contact+selfStudy+assessment.
type Hours struct {
	Lecture    float64 `json:"lecture"`
	Practice   float64 `json:"practice"`
	Lab        float64 `json:"lab"`
	SelfStudy  float64 `json:"selfStudy"`
	Assessment float64 `json:"assessment"`
	Contact    float64 `json:"contact"`
	Total      float64 `json:"total"`
}

// IsLocked reports whether a person locked the node (_meta.locked = true).
func (m *Module) IsLocked() bool { return isLocked(m.Meta) }
func (t *Topic) IsLocked() bool  { return isLocked(t.Meta) }

func (f *FinalAttestation) IsLocked() bool { return isLocked(f.Meta) }

func isLocked(meta *Node[Meta]) bool {
	return meta != nil && meta.V.Locked != nil && *meta.V.Locked
}
