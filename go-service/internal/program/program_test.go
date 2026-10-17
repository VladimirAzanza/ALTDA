package program

import (
	"encoding/json"
	"os"
	"reflect"
	"testing"
)

func roundTrip(t *testing.T, in string) (Program, any, any) {
	t.Helper()

	var p Program
	if err := json.Unmarshal([]byte(in), &p); err != nil {
		t.Fatal(err)
	}
	out, err := json.Marshal(p)
	if err != nil {
		t.Fatal(err)
	}

	var want, got any
	if err := json.Unmarshal([]byte(in), &want); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(out, &got); err != nil {
		t.Fatal(err)
	}
	return p, want, got
}

func TestRoundTripKeepsUnknownFields(t *testing.T) {
	in := `{
		"schemaVersion": "1.1",
		"status": "skeleton_proposed",
		"competencies": [{"id": "C1", "title": "Анализ"}],
		"header": {"title": "Курс", "totalHours": 16, "studyForm": "очная", "authors": [], "approval": {"orderNumber": null}},
		"curriculum": {
			"modules": [{
				"id": "M1", "title": "Основы", "assessmentForm": "зачёт", "weight": 1,
				"hours": {"lecture": 1, "practice": 1, "lab": 0, "selfStudy": 0, "assessment": 0, "contact": 2, "total": 2},
				"topics": [{
					"id": "T1.1", "title": "Тема", "outcomeIds": ["C1.K1"], "doubts": ["версия"],
					"hours": {"lecture": 1, "practice": 1, "lab": 0, "selfStudy": 0, "assessment": 0, "contact": 2, "total": 2},
					"_meta": {"locked": true, "version": 3, "skill": "skeleton", "dependsOn": ["C1.K1"]}
				}]
			}],
			"totals": {"lecture": 1, "practice": 1, "lab": 0, "selfStudy": 0, "assessment": 0, "contact": 2, "total": 2}
		},
		"finalAttestation": {"form": "зачёт", "description": "x",
			"hours": {"lecture": 0, "practice": 0, "lab": 0, "selfStudy": 0, "assessment": 1, "contact": 0, "total": 1}},
		"lectures": [{"id": "L1.1"}]
	}`

	_, want, got := roundTrip(t, in)
	if !reflect.DeepEqual(want, got) {
		t.Errorf("round trip changed the program\nwant: %v\n got: %v", want, got)
	}
}

func TestRoundTripOfSchemaExamples(t *testing.T) {
	for _, path := range []string{
		"../../../schema/example_program.json",
		"../../../schema/example_dpp_skeleton.json",
	} {
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("schema example not found: %v", err)
		}

		_, want, got := roundTrip(t, string(data))
		if !reflect.DeepEqual(want, got) {
			t.Errorf("%s: round trip changed the program", path)
		}
	}
}

func TestEditsAreWrittenAndRestIsKept(t *testing.T) {
	in := `{"id": "p1", "header": {"title": "Курс", "totalHours": 16, "studyForm": "очная"},
		"curriculum": {"modules": [{"id": "M1", "title": "Основы", "topics": [
			{"id": "T1.1", "title": "Тема", "hours": {"lecture": 1, "practice": 0, "lab": 0, "selfStudy": 0, "assessment": 0, "contact": 1, "total": 1}}
		], "hours": {"lecture": 1, "practice": 0, "lab": 0, "selfStudy": 0, "assessment": 0, "contact": 1, "total": 1}}],
		"totals": {"lecture": 1, "practice": 0, "lab": 0, "selfStudy": 0, "assessment": 0, "contact": 1, "total": 1}}}`

	var p Program
	if err := json.Unmarshal([]byte(in), &p); err != nil {
		t.Fatal(err)
	}
	p.V.Curriculum.V.Modules[0].V.Topics[0].V.Hours.Lecture = 4
	p.V.Curriculum.V.Modules[0].V.Topics[0].V.Hours.Total = 4

	out, err := json.Marshal(p)
	if err != nil {
		t.Fatal(err)
	}

	var back struct {
		ID         string `json:"id"`
		Curriculum struct {
			Modules []struct {
				Title  string `json:"title"`
				Topics []struct {
					Title string `json:"title"`
					Hours Hours  `json:"hours"`
				} `json:"topics"`
			} `json:"modules"`
		} `json:"curriculum"`
	}
	if err := json.Unmarshal(out, &back); err != nil {
		t.Fatal(err)
	}

	topic := back.Curriculum.Modules[0].Topics[0]
	if topic.Hours.Lecture != 4 || topic.Hours.Total != 4 {
		t.Errorf("edit was not written: %+v", topic.Hours)
	}
	if back.ID != "p1" || back.Curriculum.Modules[0].Title != "Основы" || topic.Title != "Тема" {
		t.Errorf("untouched fields were lost: %+v", back)
	}
}

func TestAbsentOptionalBlocksStayAbsent(t *testing.T) {
	var p Program
	if err := json.Unmarshal([]byte(`{"id": "p1", "status": "intake"}`), &p); err != nil {
		t.Fatal(err)
	}

	out, err := json.Marshal(p)
	if err != nil {
		t.Fatal(err)
	}

	var got map[string]any
	if err := json.Unmarshal(out, &got); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"header", "curriculum", "finalAttestation", "request"} {
		if _, present := got[key]; present {
			t.Errorf("%q must not appear when it was absent", key)
		}
	}
}

func TestIsLocked(t *testing.T) {
	var p Program
	in := `{"curriculum": {"modules": [{"id": "M1", "topics": [
		{"id": "T1.1", "_meta": {"locked": true}},
		{"id": "T1.2", "_meta": {"locked": false}},
		{"id": "T1.3"}]}]}}`
	if err := json.Unmarshal([]byte(in), &p); err != nil {
		t.Fatal(err)
	}

	topics := p.V.Curriculum.V.Modules[0].V.Topics
	want := []bool{true, false, false}
	for i, topic := range topics {
		if got := topic.V.IsLocked(); got != want[i] {
			t.Errorf("%s: IsLocked = %v, want %v", topic.V.ID, got, want[i])
		}
	}
}

func TestRoundTripKeepsActivityFields(t *testing.T) {
	row := `{"lecture": 2, "practice": 0, "lab": 0, "selfStudy": 0, "assessment": 0, "contact": 2, "total": 2}`
	in := `{"id": "p", "status": "content_ready", "curriculum": {"modules": [{"id": "M1", "hours": ` + row + `, "topics": [
		{"id": "T1.1", "hours": ` + row + `, "activities": [
			{"kind": "lecture", "hours": 2, "content": "Введение", "delivery": "onsite"}]}]}], "totals": ` + row + `}}`

	_, want, got := roundTrip(t, in)
	if !reflect.DeepEqual(want, got) {
		t.Errorf("activity fields were lost\nwant: %v\n got: %v", want, got)
	}
}
