# ask-repos

A command-line tool and Slack bot that lets you ask natural-language
questions about git repositories and get answers grounded in the repo's
code and documentation.

## How it works

1. **Ingest** walks a repo, skips files git would ignore, chunks the
   remaining text (markdown-aware for docs, blank-line-block-aware for
   code, recursive-character for anything else), embeds each chunk with
   Voyage `voyage-code-3`, and writes an index.
2. **Ask** embeds your question, picks the top-12 most similar chunks by
   cosine similarity, hands them to Claude (`claude-haiku-4-5`) with a
   strict "cite sources or say you don't know" prompt, and returns the
   answer plus a list of files considered.
3. **Slack bot** listens for @mentions, routes questions to the right
   repo's index, and posts answers back to the channel.

## Prerequisites

- **JDK 21** — `java -version` must report 21.x.
- **API keys**
  - `ANTHROPIC_API_KEY` (https://console.anthropic.com/)
  - `VOYAGE_API_KEY` (https://www.voyageai.com/)
- **Slack tokens** (only for the bot)
  - `SLACK_BOT_TOKEN` (starts with `xoxb-`)
  - `SLACK_APP_TOKEN` (starts with `xapp-`)
- **Git provider tokens** (only for `sync` command)
  - `BITBUCKET_TOKEN` — Bitbucket Cloud app password (`username:app-password`) or Server HTTP access token
  - `GITHUB_TOKEN` — GitHub personal access token with `repo` scope

## Setup

```sh
git clone <repo-url>
cd ask-repos
cp .env.example .env
# fill in API keys (and optionally Slack tokens) in .env
./gradlew installDist
```

The `ask-repos` script in the repo root is a thin wrapper around the
`installDist` launcher. For convenience, add the repo root to your
`PATH` or copy the wrapper into `~/bin`.

## Usage

### Index a repo (local)

```sh
./ask-repos ingest --path /path/to/some/repo
```

Index is written to `/path/to/some/repo/.ask-repos/`. Consider adding
`.ask-repos/` to that repo's `.gitignore`.

### Index a repo (named / centralized)

```sh
./ask-repos ingest --path /path/to/some/repo --name my-project
```

Index is written to `~/.ask-repos/indexes/my-project/` (override with
`ASK_REPOS_INDEX_BASE`). Named indexes are what the Slack bot uses.

Re-running either form is incremental: unchanged files are skipped.

### Ask a question

```sh
# Using a repo-local index:
./ask-repos ask "how does X work?" --path /path/to/some/repo

# Using a named index:
./ask-repos ask "how does X work?" --name my-project
```

### Sync repos via API (no git clone)

```sh
# Sync all repos defined in repos.json:
./ask-repos sync

# Sync a specific repo:
./ask-repos sync --name my-project

# Run sync continuously every 30 minutes:
./ask-repos sync --interval 30
```

Files are fetched via the Bitbucket/GitHub REST API and never stored on
disk — only the vector index is persisted. This is ideal for private
repos where cloning onto the bot's host is a security concern.

#### repos.json

The sync command reads `~/.ask-repos/repos.json` to know which repos
to sync. Example:

```json
{
  "repos": [
    {
      "name": "backend-api",
      "provider": "bitbucket",
      "workspace": "acme-corp",
      "repo": "backend-api",
      "branch": "main",
      "channels": ["C06ABC123"]
    },
    {
      "name": "frontend-app",
      "provider": "github",
      "workspace": "acme-corp",
      "repo": "frontend-app",
      "branch": "main",
      "channels": ["C06DEF456", "C06GHI789"]
    }
  ]
}
```

Fields:
- `name` — index name (used in `--name` and in Slack `in <name>` syntax)
- `provider` — `bitbucket` or `github`
- `workspace` — Bitbucket workspace slug or GitHub org/user
- `repo` — repository slug
- `branch` — branch to index (default `main`)
- `channels` — Slack channel IDs that can query this repo. Empty = all channels.

### List named indexes

```sh
./ask-repos list
```

### Start the Slack bot

```sh
./ask-repos serve
```

Requires `SLACK_BOT_TOKEN` and `SLACK_APP_TOKEN` in `.env`.

In Slack, mention the bot with a question:
- `@ask-repos how does authentication work?` — if only one repo is
  indexed, it searches that one.
- `@ask-repos how does auth work? in backend-api` — specify a repo
  by name when multiple are indexed.
- `@ask-repos help` — lists available repos.

### Slack app setup

1. Go to https://api.slack.com/apps and create a new app.
2. Under **Socket Mode**, enable it and generate an app-level token with
   `connections:write` scope. This is your `SLACK_APP_TOKEN`.
3. Under **OAuth & Permissions**, add bot token scopes:
   `app_mentions:read`, `chat:write`.
4. Under **Event Subscriptions**, subscribe to the `app_mention` bot
   event.
5. Install the app to your workspace. The bot token is your
   `SLACK_BOT_TOKEN`.
6. Add both tokens to `.env` and run `./ask-repos serve`.

## Deployment

### What you need

- An EC2 instance (or any Linux host) with **Java 21** (JRE) installed.
- API keys: `ANTHROPIC_API_KEY`, `VOYAGE_API_KEY`.
- Git provider token(s): `GITHUB_TOKEN` and/or `BITBUCKET_TOKEN`.
- Slack app tokens: `SLACK_BOT_TOKEN` and `SLACK_APP_TOKEN` (see [Slack app setup](#slack-app-setup) below).

### Build and deploy

```sh
# 1. Build the distribution zip locally
./gradlew assembleDist

# 2. Copy to the server
scp build/distributions/ask-repos-0.1.0.zip app@<server>:~/

# 3. SSH in, extract, and set up
ssh app@<server>
rm -rf ask-repos-0.1.0
unzip ask-repos-0.1.0.zip
```

### Configure environment

Create a `.env` file on the server (e.g. `~/ask-repos.env`):

```sh
ANTHROPIC_API_KEY=...
VOYAGE_API_KEY=...
SLACK_BOT_TOKEN=xoxb-...
SLACK_APP_TOKEN=xapp-...
GITHUB_TOKEN=...
BITBUCKET_TOKEN=...
ADMIN_USER=admin
ADMIN_PASSWORD=<change-me>
SYNC_INTERVAL_MINUTES=30
WEBHOOK_SECRET=<random-string>
```

### Run with systemd

Create `~/.config/systemd/user/ask-repos.service`:

```ini
[Unit]
Description=ask-repos

[Service]
WorkingDirectory=%h
EnvironmentFile=%h/ask-repos.env
ExecStart=%h/ask-repos-0.1.0/bin/ask-repos serve
Restart=on-failure

[Install]
WantedBy=default.target
```

```sh
systemctl --user daemon-reload
systemctl --user enable --now ask-repos.service
```

The admin UI is now available at `http://<server-ip>:3000/admin`
(login with `ADMIN_USER` / `ADMIN_PASSWORD` from the env file).

### Configure repos and channels

1. Open `http://<server-ip>:3000/admin` in a browser.
2. Log in with the admin credentials.
3. Click **Add Repo** and fill in the provider, workspace, repo name, branch, and Slack channel IDs.
4. Click **Sync** on a repo to index it immediately.
5. The Slack bot is live — users can `@ask-repos` in the configured channels.

### Updating

```sh
# Build locally and deploy
./gradlew assembleDist
scp build/distributions/ask-repos-0.1.0.zip app@<server>:~/

# On the server
rm -rf ask-repos-0.1.0
unzip ask-repos-0.1.0.zip
systemctl --user restart ask-repos.service
```

### Webhook-triggered sync

Instead of polling with `SYNC_INTERVAL_MINUTES`, you can configure
GitHub or Bitbucket to POST to a webhook on push:

```
POST http://<server-ip>:3000/webhook/<repo-name>?secret=<WEBHOOK_SECRET>
```

The secret can also be sent as an `X-Webhook-Secret` header.

**GitHub:** In your repo settings, add a webhook with:
- Payload URL: `https://ask-repos.example.com/webhook/my-repo?secret=...`
- Content type: `application/json`
- Events: Just the push event

**Bitbucket:** In your repo settings, add a webhook with:
- URL: `https://ask-repos.example.com/webhook/my-repo?secret=...`
- Triggers: Repository push

The webhook returns immediately and runs the sync in the background.
Duplicate requests while a sync is running are ignored.

### HTTPS with a reverse proxy

The admin UI uses HTTP basic auth, so credentials are sent in plain text.
For production, put it behind a reverse proxy with TLS. Example with Caddy:

```
# /etc/caddy/Caddyfile
ask-repos.internal.example.com {
    reverse_proxy localhost:3000
}
```

Caddy auto-provisions TLS certificates. For nginx, add a `proxy_pass`
block and configure your own certificate (e.g. via Let's Encrypt / certbot).

### Exit codes

- `1` — usage error (unknown command, missing argument).
- `2` — missing API key or Slack token.
- `3` — no index found at the target path.

## Stack

- Kotlin 2.x on JDK 21, built with Gradle Kotlin DSL.
- `kotlinx.serialization-json` — Anthropic/Voyage request+response
  shapes.
- `com.slack.api:bolt-socket-mode` — Slack bot via Socket Mode (no public
  URL needed).
- HTTP via the JDK's `java.net.http.HttpClient`.
- Tests via `kotlin.test` (stdlib).

## On-disk index format

Index files live under `<repo>/.ask-repos/` (local mode) or
`~/.ask-repos/indexes/<name>/` (named mode):

- `manifest.json` — repo path, model, embedding model, dimension,
  timestamps.
- `files.json` — map of relative path to `{ contentHash, chunkIds }`.
  This is what makes re-ingest incremental.
- `chunks.jsonl` — one JSON chunk per line: `id`, `filePath`, `startLine`,
  `endLine`, `language`, `text`.
- `vectors.bin` — little-endian packed floats: `[int32 dim]` header,
  then `N * dim` float32s in the same order as `chunks.jsonl`.

The whole thing is rewritten on each ingest — no in-place edits.

---

## Design decisions

Non-obvious choices and their rationale.

- **Gradle 8.10.2 wrapper.** The Kotlin 2.x plugin ecosystem has the
  longest-standing track record on Gradle 8.x, and a reproducible wrapper
  matters more than the latest Gradle features.
- **`kotlin("test")` via JUnit 5 default.** Kotlin 2.x resolves
  `kotlin("test")` against `kotlin-test-junit5` when JUnit 5 is on the
  runtime classpath; `useJUnitPlatform()` makes that explicit.
- **Hand-rolled `.env` parser.** ~25 lines. Strips matching quotes,
  ignores comments and blanks, does not support variable expansion.
  Enough for this tool.
- **Gitignore matcher is deliberately partial.** It handles `*`, `**`,
  `?`, `/`-anchoring, trailing `/`, negation `!`, and comments. It does
  **not** handle character classes (`[abc]`), backslash escapes, or
  nested `.gitignore` files. The built-in ignores list
  (`.git`, `node_modules`, `target`, etc.) closes the biggest gaps.
- **Code chunking packs blank-line-separated blocks greedily up to ~1500
  chars / ~60 lines.** It never splits mid-block. Hard cap of 3000 chars
  is enforced by a final pass that splits anything bigger. Chunks under
  80 chars are dropped.
- **Markdown chunker splits on H1+H2, then on H3 if a section is too
  big.** Carries a `# Title > ## Section` prefix into sub-chunks so
  context is not lost.
- **Vectors stored as a single packed little-endian float blob**, not in
  per-chunk JSON. For the expected repo size (hundreds to low thousands
  of chunks) this is 4 * dim * N bytes — tens of MB at most — and loads
  instantly.
- **Hybrid BM25 + vector retrieval.** Cosine similarity alone misses
  exact keyword matches; BM25 alone misses semantic similarity. The
  hybrid scorer normalises both into [0,1] and combines them with a
  tunable alpha weight, giving better recall than either alone.
- **Voyage batching at 64 chunks per request, retry 1s / 2s / 4s on 429
  and 5xx.**
- **Anthropic client supports both streaming and non-streaming.** The
  CLI uses non-streaming (simpler, Haiku latency is acceptable). The
  Slack bot streams and updates the message in-place every ~1 second for
  faster perceived latency.
- **Slack Bolt SDK with Socket Mode.** Socket Mode connects via WebSocket
  so no public URL is needed — ideal for an internal company tool.
  Writing the Socket Mode protocol from scratch would be a significant
  undertaking, justifying the dependency.
- **Slack bot auto-detects the target repo.** When multiple repos are
  available in a channel, the bot embeds the question and compares
  against each repo's index to pick the best match. Users can still
  force a repo with the `in <repo-name>` suffix.
- **Named indexes live under `~/.ask-repos/indexes/<name>/`.** The
  same format as repo-local indexes. The `--name` flag controls which
  mode is used. This keeps the Slack bot decoupled from where repos are
  cloned.
- **API-based file reading instead of git clone.** For private repos,
  cloning to the bot host is a security concern. Instead, files are read
  via the Bitbucket/GitHub REST API, chunked and embedded in memory, and
  only the vector index is persisted. No source code is stored on disk.
- **Channel-based access control via repos.json.** Each repo entry has
  a `channels` list of Slack channel IDs. If empty, the repo is
  available everywhere. This lets teams scope repos per channel without
  any per-user config.
- **Webhook endpoint is only registered when `WEBHOOK_SECRET` is set.**
  If no secret is configured, the `/webhook/` route does not exist,
  eliminating the attack surface entirely.
- **Constant-time webhook secret comparison.** Uses
  `MessageDigest.isEqual()` to prevent timing-based secret enumeration.
- **Admin form input validation.** Repo names, workspaces, and repo
  slugs are validated against `^[a-zA-Z0-9][a-zA-Z0-9._-]*$`; branches
  against `^[a-zA-Z0-9][a-zA-Z0-9._/-]*$`; provider must be `github`
  or `bitbucket`. Prevents path traversal and injection via form fields.
- **Audit logging for admin actions.** All add/edit/delete/sync
  operations are logged with timestamp and authenticated user to stderr
  and an in-memory ring buffer (capped at 500 entries).
- **Question length limits.** The Slack bot rejects questions over 5,000
  characters; the CLI rejects over 10,000. Prevents abuse of embedding
  and LLM APIs.
- **Thread history TTL.** Slack conversation context expires after 24
  hours and is hard-capped at 200 threads, preventing unbounded memory
  growth.

## Known limitations and next steps

### Known rough edges

- **Gitignore matcher** does not support character classes, backslash
  escaping, or nested `.gitignore` files.
- **Entire vector store is loaded into memory.** Fine at thousands of
  chunks; not at millions.
- **No concurrent embedding requests.** First-time ingest of large repos
  is bottlenecked by embedding latency.
- **The `ask` command always embeds the question fresh**, even for
  repeated questions.
- **Slack bot loads the index on every question.** For a small number of
  repos this is fine (loads in <1s). At scale, keep indexes in memory.
- **Admin UI uses HTTP basic auth.** No session management, MFA, or
  per-user roles. Intended for internal use behind a reverse proxy with
  TLS.
- **No rate limiting** on admin UI, webhook, or Slack bot endpoints.

### Natural next steps

1. **A proper vector store** — Qdrant, pgvector, or DuckDB — once the
   total size crosses hundreds of MB.
2. **Rate limiting** on webhook and admin endpoints to prevent abuse.
3. **Encryption at rest** for vector indexes (source code chunks are
   currently stored as plaintext in `chunks.jsonl`).
