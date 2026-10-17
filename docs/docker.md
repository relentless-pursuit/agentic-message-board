# Docker startup and troubleshooting

Run commands from the repository root. Start Docker Desktop (macOS/Windows) or Docker Engine (Linux) first. Put your credentials in `.env`; see `.env.example`. The board starts without credentials, but agents require `OPENAI_API_KEY` to execute.

## H2: one container

```sh
docker version
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 300
curl -fsS http://127.0.0.1:8087/actuator/health
docker compose ps
```

Open <http://127.0.0.1:8087/> or <http://127.0.0.1:8087/architecture-lab.html>.
If another board already uses 8087, use `PORT=8090 docker compose up -d --build --wait --wait-timeout 300` and open port 8090. Repeat the `PORT=8090` prefix when starting again.

The database and connector encryption key live in `board-data`; agent definitions live in `board-agents`. These named volumes are separate from the host board's `data/` and `agents-local/`. Back up both together.

```sh
docker compose logs --tail 100 agent-board
docker compose down
docker compose up -d --wait --wait-timeout 300
```

`down` preserves the saved data. Do not add `-v` unless you intend to delete this deployment's volumes.

## PostgreSQL 16 and database queue

This recipe runs one board and one PostgreSQL instance. Use a separate project name so it has separate storage from H2:

```sh
PORT=8091 docker compose -p agentboard-pg -f docker-compose.scale.yml config --quiet
PORT=8091 docker compose -p agentboard-pg -f docker-compose.scale.yml up -d --build --wait --wait-timeout 300
curl -fsS http://127.0.0.1:8091/actuator/health
docker compose -p agentboard-pg -f docker-compose.scale.yml ps
```

Open <http://127.0.0.1:8091/>. Set a non-default `POSTGRES_PASSWORD` in `.env` for a shared deployment. PostgreSQL's port is not published. The recipe defaults to token authentication. If `BOARD_ADMIN_TOKEN` is unset, retrieve its locally generated value:

```sh
docker compose -p agentboard-pg -f docker-compose.scale.yml exec agent-board cat /app/data/runtime/secrets/initial-admin-token
```

Paste it into the board's connection dialog; keep it private. Stop this deployment with `docker compose -p agentboard-pg -f docker-compose.scale.yml down`. Keep all three volumes (`pg-data`, `board-data`, `board-agents`) when moving or backing it up.

Both recipes publish the browser port only on loopback. Before exposing either to other computers, enable token authentication and put HTTPS ingress in front of it. Multiple board replicas are a later design stage.

## If Docker cannot connect

`docker compose config --quiet` validates configuration; it does not test the engine or app. On macOS:

```sh
docker desktop status
docker context show
docker version
df -h /System/Volumes/Data
docker desktop restart --timeout 60
```

If `docker version` has a client version but cannot return a server version, fix Docker Desktop before building the app. A full disk can stop its Linux VM. Free space before retrying. A stuck Desktop process may require quitting and reopening Desktop. A factory reset or deleting volumes is not necessary for an ordinary restart. Logs live under `~/Library/Containers/com.docker.docker/Data/log/`. See [Docker's troubleshooting guide](https://docs.docker.com/desktop/troubleshoot-and-support/troubleshoot/).

The image allows three minutes for a slow JVM startup before counting failed health checks. `--wait` waits for a healthy application, rather than merely a running process; allow up to five minutes on a busy laptop.
