# ALTDA Java service

Spring Boot 21 orchestration API (заявка lifecycle, Go client). Listens on **port 8000**.

## Run

```bash
./run.sh
# equivalent:
./mvnw spring-boot:run
```

Docker:

```bash
docker build -t altda-java .
docker run --rm -p 8000:8000 altda-java
```

Default profile uses **mock** Go client (`altda.go.client=mock`). To call a real Go service:

```bash
ALTDA_GO_CLIENT=http ./run.sh
# or
./mvnw spring-boot:run -Dspring-boot.run.profiles=http
```

## Quick test

```bash
curl -s http://localhost:8000/health
curl -s -X POST http://localhost:8000/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"request":"ДПП ПК Анализ данных на Python, 72 ч","answers":{"entryLevel":"Базовый"}}'
```

From repo root: `../scripts/smoke_test.sh`.

## Tests

```bash
./mvnw test
```

## Layout

- `POST /programs` — interactive заявка (async; poll `GET /programs/{id}`)
- `POST /api/generate` — sync pipeline with auto-approval (hackathon tests)
- Contract with Go: [`../docs/API.md`](../docs/API.md)
