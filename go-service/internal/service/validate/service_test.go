package validate

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/VladimirAzanza/ALTDA/go-service/internal/program"
	"github.com/VladimirAzanza/ALTDA/go-service/internal/service/allocate"
	"github.com/VladimirAzanza/ALTDA/go-service/internal/service/allocator"
)

const (
	examplePath  = "../../../../schema/example_program.json"
	skeletonPath = "../../../../schema/example_dpp_skeleton.json"
)

func load(t *testing.T, path string) *program.Program {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("schema example not found: %v", err)
	}
	return parse(t, data)
}

func parse(t *testing.T, data []byte) *program.Program {
	t.Helper()
	var p program.Program
	if err := json.Unmarshal(data, &p); err != nil {
		t.Fatal(err)
	}
	return &p
}

func validate(t *testing.T, p *program.Program) *Report {
	t.Helper()
	report, err := NewService().Validate(context.Background(), p)
	if err != nil {
		t.Fatal(err)
	}
	return report
}

func failed(report *Report, ruleID string) *Check {
	for _, c := range report.Checks {
		if c.RuleID == ruleID && !c.Passed {
			return &c
		}
	}
	return nil
}

func TestSchemaExamplesPassTheHoursRules(t *testing.T) {
	for _, path := range []string{examplePath, skeletonPath} {
		report := validate(t, load(t, path))

		if len(report.Checks) == 0 {
			t.Errorf("%s: no checks were run", path)
		}
		if issues := report.Issues(); len(issues) != 0 {
			t.Errorf("%s: unexpected issues: %+v", path, issues)
		}
	}
}

func TestEachHoursRuleCatchesItsOwnBreakage(t *testing.T) {
	tests := []struct {
		name     string
		ruleID   string
		path     string
		breakage func(*program.Root)
	}{
		{"contact does not match", "HOURS_CONTACT", "/curriculum/modules/0/topics/0/hours",
			func(r *program.Root) { r.Curriculum.V.Modules[0].V.Topics[0].V.Hours.Contact += 1 }},
		{"total does not match", "HOURS_ROW_SUM", "/curriculum/modules/0/topics/0/hours",
			func(r *program.Root) { r.Curriculum.V.Modules[0].V.Topics[0].V.Hours.Total += 1 }},
		{"topics do not add up to the module", "HOURS_TOPICS_EQ_MODULE", "/curriculum/modules/0/hours",
			func(r *program.Root) { r.Curriculum.V.Modules[0].V.Hours.Lecture += 1 }},
		{"declared totals are wrong", "HOURS_TOTALS_FIELD", "/curriculum/totals",
			func(r *program.Root) { r.Curriculum.V.Totals.Lecture += 1 }},
		{"plan differs from the requested hours", "HOURS_EQ_REQUESTED", "/header/totalHours",
			func(r *program.Root) { r.Header.V.TotalHours = 20 }},
		{"practice share too low", "HOURS_PRACTICE_SHARE", "/curriculum/totals",
			func(r *program.Root) {
				share := 0.99
				r.Request.V.Params.V.MinPracticeShare = &share
			}},
		{"activities do not match the topic", "HOURS_ACTIVITIES_EQ_TOPIC", "/curriculum/modules/0/topics/0/activities",
			func(r *program.Root) { r.Curriculum.V.Modules[0].V.Topics[0].V.Activities[0].V.Hours += 1 }},
	}

	for _, tt := range tests {
		t.Run(tt.ruleID, func(t *testing.T) {
			p := load(t, examplePath)
			tt.breakage(&p.V)

			issue := failed(validate(t, p), tt.ruleID)
			if issue == nil {
				t.Fatalf("%s did not fail", tt.ruleID)
			}
			if issue.Path != tt.path {
				t.Errorf("path = %q, want %q", issue.Path, tt.path)
			}
			if issue.Severity != SeverityError || issue.Category != CategoryHours || issue.Message == "" {
				t.Errorf("issue = %+v", issue)
			}
		})
	}
}

func TestModuleLevelAssessmentIsAllowed(t *testing.T) {
	// 1 h of assessment moves from the final attestation to the first module's
	// own row. Its topics do not carry it, and the plan still adds up.
	p := load(t, examplePath)
	module := &p.V.Curriculum.V.Modules[0].V
	module.Hours.Assessment += 1
	module.Hours.Total += 1
	p.V.FinalAttestation.V.Hours = program.Hours{}

	report := validate(t, p)

	if failed(report, "HOURS_TOPICS_EQ_MODULE") != nil {
		t.Error("an assessment held only by the module row must not fail HOURS_TOPICS_EQ_MODULE")
	}
	if issues := report.Issues(); len(issues) != 0 {
		t.Errorf("unexpected issues: %+v", issues)
	}
}

func TestNothingIsCheckedBeforeASkeletonExists(t *testing.T) {
	for _, status := range []string{"intake", "clarifying"} {
		p := parse(t, []byte(`{"id": "p", "status": "`+status+`", "request": {"rawText": "x"}}`))

		report := validate(t, p)
		if len(report.Checks) != 0 || report.Checks == nil {
			t.Errorf("%s: checks = %v, want an empty, non-nil list", status, report.Checks)
		}
		if report.Issues() == nil {
			t.Errorf("%s: Issues() must be an empty list, not nil", status)
		}
	}
}

func TestInvalidPrograms(t *testing.T) {
	tests := []struct {
		name string
		json string
	}{
		{"unknown status", `{"id": "p", "status": "weird"}`},
		{"missing status", `{"id": "p"}`},
		{"no header", `{"status": "skeleton_proposed"}`},
		{"no curriculum", `{"status": "skeleton_proposed", "header": {"totalHours": 16}}`},
		{"no final attestation", `{"status": "skeleton_proposed", "header": {"totalHours": 16}, "curriculum": {"modules": [], "totals": {}}}`},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := NewService().Validate(context.Background(), parse(t, []byte(tt.json)))
			if !errors.Is(err, ErrInvalidProgram) {
				t.Errorf("err = %v, want ErrInvalidProgram", err)
			}
		})
	}
}

func TestSummaryCountsPassedAndFailed(t *testing.T) {
	p := load(t, examplePath)
	p.V.Header.V.TotalHours = 20 // breaks HOURS_EQ_REQUESTED only

	report := validate(t, p)

	if report.Summary.Errors != 1 {
		t.Errorf("errors = %d, want 1", report.Summary.Errors)
	}
	if report.Summary.Passed != len(report.Checks)-1 {
		t.Errorf("passed = %d, want %d", report.Summary.Passed, len(report.Checks)-1)
	}
	if len(report.Issues()) != 1 {
		t.Errorf("issues = %d, want 1", len(report.Issues()))
	}
}

func TestReportUsesTheClock(t *testing.T) {
	now := time.Date(2026, 10, 18, 9, 30, 0, 0, time.UTC)
	svc := &service{now: func() time.Time { return now }}

	report, err := svc.Validate(context.Background(), load(t, examplePath))
	if err != nil {
		t.Fatal(err)
	}
	if !report.GeneratedAt.Equal(now) {
		t.Errorf("generatedAt = %v, want %v", report.GeneratedAt, now)
	}
}

func withoutActivities(p *program.Program) *program.Program {
	for mi := range p.V.Curriculum.V.Modules {
		topics := p.V.Curriculum.V.Modules[mi].V.Topics
		for ti := range topics {
			topics[ti].V.Activities = nil
		}
	}
	return p
}

func lockTopic(topic *program.Node[program.Topic], hours program.Hours) {
	locked := true
	topic.V.Hours = hours
	topic.V.Meta = &program.Node[program.Meta]{V: program.Meta{Locked: &locked}}
}

// The allocator and the validator must agree: whatever allocate produces has
// to pass every hours rule. (Programs with activities are covered separately
// below: allocate does not rewrite them.)
func TestAllocatedProgramsPassValidation(t *testing.T) {
	allocateSvc := allocate.NewService(allocator.DefaultConfig())

	cases := map[string]func() *program.Program{
		"example, untouched": func() *program.Program { return withoutActivities(load(t, examplePath)) },
		"example with a locked topic": func() *program.Program {
			p := withoutActivities(load(t, examplePath))
			lockTopic(&p.V.Curriculum.V.Modules[0].V.Topics[0], program.Hours{Lecture: 3, Practice: 3, Contact: 6, Total: 6})
			return p
		},
		"empty skeleton": func() *program.Program {
			return parse(t, []byte(`{"id": "p", "status": "skeleton_proposed", "request": {"rawText": "x"},
				"header": {"totalHours": 72, "studyForm": "очная"},
				"curriculum": {"modules": [
					{"id": "M1", "weight": 0.6, "hours": {}, "topics": [
						{"id": "T1.1", "weight": 0.5, "hours": {}}, {"id": "T1.2", "weight": 0.5, "hours": {}}]},
					{"id": "M2", "weight": 0.4, "hours": {}, "topics": [{"id": "T2.1", "weight": 1, "hours": {}}]}],
					"totals": {}},
				"finalAttestation": {"form": "зачёт", "description": "x", "hours": {}}}`))
		},
	}

	for name, build := range cases {
		t.Run(name, func(t *testing.T) {
			p := build()
			if err := allocateSvc.Allocate(context.Background(), p); err != nil {
				t.Fatal(err)
			}

			if issues := validate(t, p).Issues(); len(issues) != 0 {
				t.Errorf("allocated program fails validation: %+v", issues)
			}
		})
	}
}

// When a locked topic makes the requested practice share impossible, the
// allocator warns and the validator reports exactly that rule, nothing else.
func TestUnreachablePracticeShareIsReportedByTheValidator(t *testing.T) {
	p := withoutActivities(load(t, examplePath))
	lockTopic(&p.V.Curriculum.V.Modules[0].V.Topics[0], program.Hours{Lecture: 8, Contact: 8, Total: 8})

	if err := allocate.NewService(allocator.DefaultConfig()).Allocate(context.Background(), p); err != nil {
		t.Fatal(err)
	}

	issues := validate(t, p).Issues()
	if len(issues) != 1 || issues[0].RuleID != "HOURS_PRACTICE_SHARE" {
		t.Errorf("issues = %+v, want only HOURS_PRACTICE_SHARE", issues)
	}
}

// allocate rewrites hours, not the content. Topics that already had activities
// are left with the old ones, and the validator flags them so the content for
// those topics gets regenerated.
func TestStaleActivitiesAreFlaggedAfterAllocate(t *testing.T) {
	p := load(t, examplePath)
	lockTopic(&p.V.Curriculum.V.Modules[0].V.Topics[0], program.Hours{Lecture: 3, Practice: 3, Contact: 6, Total: 6})

	if err := allocate.NewService(allocator.DefaultConfig()).Allocate(context.Background(), p); err != nil {
		t.Fatal(err)
	}

	stale := failed(validate(t, p), "HOURS_ACTIVITIES_EQ_TOPIC")
	if stale == nil {
		t.Fatal("changed topics must have their stale activities reported")
	}
}
