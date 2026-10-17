# Java ↔ Go HTTP contract (MVP)

Concise contract for the 23 Oct MVP. Payloads follow `schema/program.schema.json` (v1.1). JSON field names are **camelCase**. Base URL for the Go service is configured on the Java side (`altda.go.base-url`).

Status names in `program.status` are those of the schema (not informal aliases):

| Schema status         | Informal alias (task brief) |
|-----------------------|-----------------------------|
| `intake`               | draft                       |
| `clarifying`          | clarifying                  |
| `skeleton_proposed`   | skeleton_ready              |
| `skeleton_approved`   | skeleton_approved           |
| `content_generating`  | generating                   |
| `content_ready`       | (content done, pre-validate)|
| `validated`           | ready                       |
| `exported`            | exported                    |
| `failed` *(Java only)*| Go timeout / pipeline error |

`failed` is an **orchestration** status on the Java API (`GET /programs/{id}`). It is **not** written into `program.status` (schema enum). The response includes `error` with the failure message so a Go timeout never leaves a заявка stuck mid-async.

Long-running work is started by Java; the UI polls Java `GET /programs/{id}`. Mutations while `busy=true` or `status=content_generating` return **HTTP 409**. Each record has a monotonic `version` (optimistic locking). The Go service may be synchronous per call for MVP (Java owns async orchestration).

### Go client read timeouts (Java config)

Configured under `altda.go.timeouts` in `java-service/src/main/resources/application.yml`:

| Call | Default |
|------|---------|
| `/v1/skeleton/generate`, `/v1/skeleton/regenerate` | 180 000 ms (~3 min) |
| `/v1/content/generate` | 600 000 ms (~10 min) |
| `/v1/validate`, `/v1/allocate` (if present) | 10 000 ms |
| `/v1/parse` | 60 000 ms |

Select implementation with `altda.go.client=mock` (default) or `http`.

---

## Common conventions

- **Content-Type:** `application/json; charset=utf-8`
- **Errors:** `{ "error": "short_code", "message": "human-readable" }` with HTTP 4xx/5xx
- **Program body:** a full or partial `program` object matching the schema for the current stage
- **IDs:** stable node ids (`C1`, `T2.3`, `L2.3`, …). Nodes may carry `_meta.locked` and `_meta.dependsOn`
- **No auth** in MVP

---

## 1. Parse / clarify request

Used when a заявка is created or clarifications are submitted.

```
POST /v1/parse
```

**Request**

```json
{
  "rawText": "string",
  "attachments": [],
  "clarifications": [
    { "param": "entryLevel", "question": "...", "answer": "..." }
  ]
}
```

`clarifications` may be empty on first call.

**Response 200**

```json
{
  "params": { },
  "missing": ["entryLevel"],
  "clarifications": [
    { "param": "entryLevel", "question": "...", "answer": null }
  ],
  "gapsReport": []
}
```

Shapes match `request.params`, `request.missing`, `request.clarifications`, `request.gapsReport` in the schema. When `missing` is empty, Java proceeds to skeleton generation.

---

## 2. Generate skeleton (3 variants)

```
POST /v1/skeleton/generate
```

**Request**

```json
{
  "program": { },
  "selectedVariantKey": null
}
```

`program` must include at least `request` with filled `params` / answered clarifications. Optional `selectedVariantKey` is unused on first generation.

**Response 200** — `$defs/skeletonResponse`:

```json
{
  "variants": [
    {
      "key": "A",
      "rationale": "...",
      "pros": ["..."],
      "cons": ["..."],
      "goal": "...",
      "competencies": [],
      "modules": [],
      "finalAttestation": { }
    }
  ]
}
```

Up to **3** variants. Hours are **not** set by the LLM; the Go side (hours allocator) fills integer/0.5-step hours when materializing a chosen variant into a program skeleton (or returns weights only and documents that Java must not invent hours). For MVP, Go returns a response that Java can turn into `skeleton_proposed` program fields after the methodologist picks a variant.

**Agreement needed:** whether `generate` returns pure `skeletonResponse` (weights only) and a separate `POST /v1/skeleton/materialize` applies hours, or Go returns an allocated draft program per variant. Preferred for MVP: response = `skeletonResponse`; on approve, Java calls content/materialize with the chosen variant and Go returns header/goal/competencies/curriculum/finalAttestation with hours filled.

---

## 3. Regenerate parts (locked / dependsOn)

```
POST /v1/skeleton/regenerate
```

**Request**

```json
{
  "program": { },
  "comment": "Упрости модуль 2",
  "nodeIds": ["M2", "T2.1"],
  "respectLocks": true
}
```

- If `nodeIds` is empty/null, Go regenerates the affected subgraph implied by `comment` (or the whole skeleton).
- Nodes with `_meta.locked: true` must **not** be overwritten when `respectLocks` is true.
- Cascades follow `_meta.dependsOn` (and the documented chain outcome → topic → lecture / seminar / questions).

**Response 200**

```json
{
  "program": { },
  "changedPaths": ["/curriculum/modules/1", "/goal"],
  "variants": null
}
```

Either a patched `program` (single working skeleton) or a new `variants` list when the methodologist rejected the whole proposal. Exactly one of `program` / `variants` should be non-null.

---

## 4. Generate content

```
POST /v1/content/generate
```

**Request**

```json
{
  "program": { },
  "nodeIds": null
}
```

`program.status` is expected to be `skeleton_approved` (or `content_generating`). If `nodeIds` is set, only those nodes (plus dependents, minus locked) are filled; otherwise the full content package for the stage (`calendar`, `lectures`, `assessment`, `sources`, topic `activities`, …).

**Response 200**

```json
{
  "program": { },
  "changedPaths": ["/lectures", "/assessment", "/calendar"]
}
```

Returned `program` should be valid for status `content_ready` (schema `allOf`).

---

## 5. Validate

```
POST /v1/validate
```

**Request**

```json
{
  "program": { }
}
```

**Response 200**

```json
{
  "validationReport": {
    "generatedAt": "2026-10-17T00:00:00Z",
    "iterations": 0,
    "summary": { "errors": 0, "warnings": 1, "passed": 12, "unverifiedSources": 1 },
    "checks": [
      {
        "ruleId": "HOURS_EQ_REQUESTED",
        "category": "hours",
        "severity": "error",
        "passed": true,
        "message": "...",
        "path": "/header/totalHours",
        "relatedIds": []
      }
    ]
  },
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

`validationReport` matches the schema. `issues` is the failed checks (errors + warnings) as a flat list for the UI — same information as `checks` where `passed: false`.

Rules mirror `tools/validate_reference.py` (Go ports them 1:1).

---

## Java public REST (orchestration, not Go)

Implemented by the Spring Boot app on port **8000**. Static files are served from `classpath:/static/` (vanilla-JS SPA in a later phase).

| Method | Path | Purpose |
|--------|------|---------|
| `GET`  | `/health` | Liveness: `{ "status": "ok", "goClient": "mock\|http" }` |
| `POST` | `/api/generate` | **Test mode:** sync full pipeline with auto-approval |
| `POST` | `/programs` | Create заявка from free-text (`{ "rawText": "..." }`) → 202 |
| `GET`  | `/programs` | List programs (id, status, title, version, busy, …) |
| `GET`  | `/programs/{id}` | Full snapshot for polling (includes `version`, `error`, `history`) |
| `POST` | `/programs/{id}/clarifications` | Submit clarification answers |
| `POST` | `/programs/{id}/skeleton/approve` | Approve a variant (`{ "variantKey": "A" }`) |
| `POST` | `/programs/{id}/skeleton/return` | Return skeleton for corrections (`{ "comment": "..." }`) |
| `POST` | `/programs/{id}/content/approve` | Approve content after validation |
| `POST` | `/programs/{id}/content/return` | Return content for corrections (`{ "comment": "..." }`) |

### Test mode: `POST /api/generate`

One-shot endpoint for hackathon automated tests (works with `altda.go.client=mock`):

```json
{ "request": "ДПП ПК Анализ данных на Python, 72 ч", "answers": { "entryLevel": "Базовый" } }
```

Runs parse → skeleton → auto-approve first variant → content → validate **synchronously**, skipping human согласование. Returns `{ id, status, program, issues, validationReport }`.

Async interactive steps: parse → skeleton → content → validate. While busy, `GET /programs/{id}` returns `"busy": true`. Revision history is stored on the Java side and mirrored into `program.approvals[]`.

---

## Open points for Go agreement

1. **Hours allocator ownership:** task split puts the allocator on Go; the README originally said Java. MVP assumes Go fills hours before returning a materializable skeleton/content (`/v1/allocate` reserved, 10 s timeout).
2. **Materialize-on-approve:** confirm whether approve triggers a dedicated Go call or Java merges the chosen `skeletonDraft` locally and only calls content/generate. Current Java MVP merges locally, then calls content/generate.
3. **Repair loop:** validate → repair (max 2) can stay inside Go’s `/v1/validate` or be a separate `/v1/repair`. MVP: Java calls `/v1/validate` once after content generation; repair can wait.
4. **SSE:** out of scope for this phase; polling only.
5. **`failed` status:** Java-only; Go should not emit it in `program.status`.
