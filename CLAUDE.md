# CLAUDE.md

## Build & Run

```sh
./gradlew build              # compile + test
./gradlew test               # tests only (JUnit 5 via kotlin.test)
./gradlew installDist        # build launcher scripts
./ask-repos <command>        # wrapper that calls installDist then runs the app
```

Run a single test class: `./gradlew test --tests "askrepo.SomeTest"`

Requires JDK 21. Gradle 8.10.2 wrapper is checked in.

## Project Layout

All source lives in `src/main/kotlin/askrepo/`, tests in `src/test/kotlin/askrepo/`.
Single package `askrepo`, no sub-packages.

Key files:
- `Main.kt` — CLI entry point, command routing (`ingest`, `ask`, `sync`, `list`, `serve`)
- `AdminServer.kt` — Ktor HTTP server: admin UI, webhook endpoint, SSE sync progress
- `SlackBot.kt` — Slack Bolt SDK socket-mode bot
- `Ingest.kt` / `Chunking.kt` — repo walking, chunking (code + markdown aware)
- `Anthropic.kt` / `Embeddings.kt` — Claude and embedding HTTP clients (Voyage AI + Ollama)
- `Retrieve.kt` — hybrid BM25 + cosine similarity retrieval
- `Store.kt` — on-disk index format (manifest.json, chunks.jsonl, vectors.bin)
- `Config.kt` — env var / `.env` parsing
- `GitProvider.kt` — Bitbucket/GitHub REST API file fetching
- `RepoManager.kt` — repos.json registry, sync orchestration

## Code Style

- 4-space indentation, no tabs
- Singleton `object` for stateless services (Answer, Store, Ingest, etc.)
- `@Serializable` data classes for JSON shapes (kotlinx-serialization)
- Java `HttpClient` for all HTTP (no OkHttp/Ktor client)
- `java.nio.file.Path` / `Files` for file I/O
- No comments unless the *why* is non-obvious

## Testing

- Integration-style tests preferred over mocks
- AdminServer tests use Ktor `testApplication` with `testConfig()` helper
- Tests create temp directories and clean up after themselves

## Environment

Required: `ANTHROPIC_API_KEY` (in env or `.env` file).
Embeddings default to Ollama (`nomic-embed-text`). Set `EMBEDDING_PROVIDER=voyage` and `VOYAGE_API_KEY` for Voyage.
See `.env.example` for all optional variables (`OLLAMA_BASE_URL`, `OLLAMA_MODEL`, etc.).

## Security Conventions

- Admin form inputs validated against `^[a-zA-Z0-9][a-zA-Z0-9._-]*$` (slugs) and `^[a-zA-Z0-9][a-zA-Z0-9._/-]*$` (branches)
- Webhook secret uses constant-time comparison (`MessageDigest.isEqual`)
- Webhook route not registered when `WEBHOOK_SECRET` is unset
- Admin actions audit-logged to stderr with timestamp and user
- Question length capped at 5k (Slack) / 10k (CLI)
- `.env` is gitignored and never committed
