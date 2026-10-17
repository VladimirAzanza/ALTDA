package skeleton

import (
	"context"

	"github.com/VladimirAzanza/ALTDA/go-service/internal/program"
	"github.com/VladimirAzanza/ALTDA/go-service/internal/service/allocator"
	"github.com/VladimirAzanza/ALTDA/go-service/internal/skeleton"
)

// maxVariants is the most the contract allows in one response.
const maxVariants = 3

// Service proposes skeleton variants for a request: structure from a
// drafter, hours from the allocator.
type Service interface {
	Generate(ctx context.Context, p *program.Program) ([]Variant, error)
}

// Request is what a drafter needs to know, read from request.params.
type Request struct {
	Title                string
	Domain               string
	ProgramType          string
	Audience             string
	EntryLevel           string
	StudyForm            string
	FinalAttestationForm string
	TotalHours           float64
	IsInternship         bool
	MinPracticeShare     *float64
}

// Drafter proposes the structure of the program: modules, topics, outcomes
// and weights. It never decides hours. The template drafter below is a
// stand-in; an LLM-backed drafter implements the same interface.
type Drafter interface {
	Draft(ctx context.Context, req Request) ([]skeleton.Draft, error)
}

// Variant is a draft with its hours allocated, as the contract returns it.
type Variant struct {
	Key              string                  `json:"key"`
	Rationale        string                  `json:"rationale"`
	Pros             []string                `json:"pros"`
	Cons             []string                `json:"cons"`
	Goal             string                  `json:"goal"`
	Competencies     []skeleton.Competency   `json:"competencies"`
	Modules          []VariantModule         `json:"modules"`
	FinalAttestation VariantFinalAttestation `json:"finalAttestation"`
	Totals           allocator.Hours         `json:"totals"`
}

type VariantModule struct {
	ID      string          `json:"id"`
	Title   string          `json:"title"`
	IsIntro bool            `json:"isIntro"`
	Weight  float64         `json:"weight"`
	Hours   allocator.Hours `json:"hours"`
	Topics  []VariantTopic  `json:"topics"`
}

type VariantTopic struct {
	ID                string          `json:"id"`
	Title             string          `json:"title"`
	Weight            float64         `json:"weight"`
	Kinds             []string        `json:"kinds"`
	PracticeShareHint *float64        `json:"practiceShareHint,omitempty"`
	OutcomeIDs        []string        `json:"outcomeIds"`
	Prerequisites     []string        `json:"prerequisites"`
	Hours             allocator.Hours `json:"hours"`
}

type VariantFinalAttestation struct {
	Form        string          `json:"form"`
	Description string          `json:"description"`
	OutcomeIDs  []string        `json:"outcomeIds"`
	Hours       allocator.Hours `json:"hours"`
}
