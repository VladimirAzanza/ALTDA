# Java ↔ Go HTTP contract (final MVP)

Payloads follow `schema/program.schema.json` (**schemaVersion `1.1`**). JSON fields are **camelCase**. Every request and response body includes `"schemaVersion": "1.1"` (except bare health).

**All Go calls are synchronous.** Java owns async orchestration and polling for the UI; it waits on each Go HTTP call within the per-call read timeout.

Base URL: `altda.go.base-url`. Client: `altda.go.client=mock|http` (default `mock`).

---

## Status names

| `program.status` (schema) | Notes |
|---------------------------|--------|
| `intake` | |
| `clarifying` | |
| `skeleton_proposed` | |
| `skeleton_approved` | |
| `content_generating` | |
| `content_ready` | |
| `validated` | |
| `exported` | |
| ~~`failed`~~ | **Java orchestration only** — never written into `program.status`; appears on Java `GET /programs/{id}` with `error` |

---

## Errors (Go → Java)

HTTP 4xx/5xx body:

```json
{ "error": "bad_request", "message": "human-readable" }
```

Fixed `error` codes:

| Code | Typical HTTP | Meaning |
|------|--------------|---------|
| `bad_request` | 400 | Malformed / incomplete payload |
| `node_locked` | 409 | Regenerate targeted a locked node while `respectLocks=true` |
| `llm_timeout` | 504 / 408 | Model call timed out |
| `llm_error` | 502 | Model / upstream failure |
| `schema_invalid` | 422 | Response or input failed JSON Schema |

---

## Per-call read timeouts (Java)

`altda.go.timeouts` in `java-service/.../application.yml`:

| Call | Default |
|------|---------|
| `POST /v1/skeleton/generate`, `POST /v1/skeleton/regenerate` | 180 000 ms (~3 min) |
| `POST /v1/content` | 600 000 ms (~10 min) |
| `POST /v1/validate`, `POST /v1/allocate` | 10 000 ms |
| `POST /v1/parse` | 60 000 ms |
| `GET /v1/health` | connect timeout only |

---

## 0. Health

```
GET /v1/health
```

**Response 200**

```json
{ "status": "ok" }
```

---

## 1. Parse / clarify

```
POST /v1/parse
```

**Request**

```json
{
  "schemaVersion": "1.1",
  "rawText": "string",
  "attachments": [],
  "clarifications": [
    { "param": "entryLevel", "question": "...", "answer": "..." }
  ]
}
```

**Response 200**

```json
{
  "schemaVersion": "1.1",
  "params": { },
  "missing": ["entryLevel"],
  "clarifications": [
    { "param": "entryLevel", "question": "...", "answer": null }
  ],
  "gapsReport": []
}
```

---

## 2. Generate skeleton (hours already allocated)

```
POST /v1/skeleton/generate
```

**Request**

```json
{
  "schemaVersion": "1.1",
  "program": { },
  "selectedVariantKey": null
}
```

**Response 200**

```json
{
  "schemaVersion": "1.1",
  "variants": [
    {
      "key": "A",
      "rationale": "...",
      "pros": ["..."],
      "cons": ["..."],
      "goal": "...",
      "competencies": [],
      "modules": [
        {
          "id": "M1",
          "title": "...",
          "isIntro": false,
          "weight": 0.5,
          "hours": { "lecture": 2, "practice": 4, "lab": 0, "selfStudy": 0, "assessment": 0, "contact": 6, "total": 6 },
          "topics": [
            {
              "id": "T1.1",
              "title": "...",
              "weight": 1,
              "kinds": ["lecture", "practice"],
              "outcomeIds": ["C1.K1"],
              "hours": { "lecture": 2, "practice": 4, "lab": 0, "selfStudy": 0, "assessment": 0, "contact": 6, "total": 6 }
            }
          ]
        }
      ],
      "finalAttestation": {
        "form": "защита проекта",
        "description": "...",
        "outcomeIds": [],
        "hours": { "lecture": 0, "practice": 0, "lab": 0, "selfStudy": 0, "assessment": 2, "contact": 0, "total": 2 }
      },
      "totals": { "lecture": 2, "practice": 4, "lab": 0, "selfStudy": 0, "assessment": 2, "contact": 6, "total": 8 }
    }
  ]
}
```

Up to **3** variants. Go’s allocator fills all `hours.*` and `totals` (0.5-step). **Java never computes hours.** On approve, Java **merges the chosen variant locally** into the working program (no `/materialize` endpoint).

Transport may carry allocated `hours` / `totals` on variants beyond the weight-only `$defs/skeletonDraft` used for LLM output validation inside Go.

---

## 3. Regenerate (locked / dependsOn)

```
POST /v1/skeleton/regenerate
```

**Request**

```json
{
  "schemaVersion": "1.1",
  "program": { },
  "comment": "Упрости модуль 2",
  "nodeIds": ["M2", "T2.1"],
  "respectLocks": true
}
```

- Empty/null `nodeIds`: Go infers the subgraph from `comment` (or regenerates broadly).
- With `respectLocks: true`, nodes with `_meta.locked: true` are not overwritten.
- If any requested `nodeId` is locked and `respectLocks=true` → **409** `{ "error": "node_locked", "message": "..." }`.

**Response 200**

```json
{
  "schemaVersion": "1.1",
  "program": { },
  "changedPaths": ["/curriculum/modules/1"],
  "variants": null
}
```

Exactly one of `program` / `variants` is non-null. Regenerated fragments already have hours from Go.

---

## 4. Allocate (rebalance only)

```
POST /v1/allocate
```

Used **only after manual hour edits** in the UI (rebalance remaining weights/rows to match `header.totalHours`). Not used on the happy path after skeleton generate.

**Request**

```json
{
  "schemaVersion": "1.1",
  "program": { }
}
```

**Response 200**

```json
{
  "schemaVersion": "1.1",
  "program": { }
}
```

---

## 5. Generate content

```
POST /v1/content
```

**Request**

```json
{
  "schemaVersion": "1.1",
  "program": { },
  "nodeIds": null
}
```

**Response 200**

```json
{
  "schemaVersion": "1.1",
  "program": { },
  "issues": [
    {
      "ruleId": "SOURCE_UNVERIFIED",
      "severity": "warning",
      "message": "...",
      "path": "/sources/0"
    }
  ]
}
```

`issues[].path` is a **JSON Pointer**. `issues[].ruleId` values are identical to rule names in `tools/validate_reference.py` (e.g. `HOURS_EQ_REQUESTED`, `COVERAGE_OUTCOME_TAUGHT`, `SOURCE_UNVERIFIED`, `SECTION_MISSING`, …).

---

## 6. Validate

```
POST /v1/validate
```

**Request**

```json
{
  "schemaVersion": "1.1",
  "program": { }
}
```

**Response 200**

```json
{
  "schemaVersion": "1.1",
  "validationReport": { },
  "issues": [
    {
      "ruleId": "HOURS_EQ_REQUESTED",
      "severity": "error",
      "message": "...",
      "path": "/header/totalHours"
    }
  ]
}
```

`validationReport` matches the schema. `issues` = failed checks (`passed: false`), same `ruleId` / JSON Pointer conventions as above.

---

## Java public REST (orchestration)

Port **8000**. UI polls Java; Go is never called from the browser.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/health` | Java liveness (+ which Go client) |
| `POST` | `/api/generate` | Sync test pipeline, auto-approve |
| `POST` | `/programs` … | Interactive заявка lifecycle (see java-service README) |
| `GET` | `/programs/{id}/export.docx` | Phase 3 DOCX export (Apache POI from styled template) |

Mutating approve/return/lock/allocate/regenerate endpoints accept optimistic concurrency via `If-Match` (etag or bare version) and/or body `version`; mismatch → HTTP **409** `version_conflict`. `GET /programs/{id}` returns a deep-copied snapshot (`failedFrom` when status is Java-only `failed`).

Java maps Go `node_locked` → HTTP 409 for the UI. Go timeouts / LLM errors → Java `status=failed` + `error` + `failedFrom` (orchestration only).

---

## Locked decisions (no longer open)

1. **Hours:** Go allocates; Java never computes hours. Skeleton generate returns allocated variants; Java merges locally; **no `/materialize`**.
2. **`/v1/allocate`:** rebalance after manual edits only.
3. **`/v1/content`:** `{ program, issues[] }` (not changedPaths).
4. **`failed`:** Java-only orchestration status.
5. **Sync Go calls** + per-call timeouts as above.
6. **SSE:** not in this phase (Java polling).
