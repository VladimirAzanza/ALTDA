# Deploy

Runs both services with Docker Compose:

- `go` — Go engine, port 8081 (contract in `docs/API.md`)
- `java` — Spring Boot UI + REST, port 8000

```bash
cd deploy
docker compose -p altda up --build                
ALTDA_GO_CLIENT=http docker compose -p altda up --build
docker compose down
```

Check the Go service directly:

```bash
curl -s localhost:8081/v1/health
curl -s -X POST localhost:8081/v1/parse -H 'Content-Type: application/json' \
  -d '{"schemaVersion":"1.1","rawText":"ДПП ПК Анализ данных на Python, 72 ч"}'
```

Java keeps programs in memory: restarting the container loses them.
