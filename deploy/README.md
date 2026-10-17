# Deploy

Runs the Java service from `java-service/` (UI + REST on :8000).

```bash
cd deploy
docker compose up --build            # mock Go client
open http://localhost:8000/
../scripts/smoke_test.sh http://localhost:8000
```

Real Go service running on the host at :8081:

```bash
ALTDA_GO_CLIENT=http docker compose up --build
```

Data is in memory: restarting the container loses all programs.
