# PLAN

Living task list. Update as work progresses. Decisions go in
`DESIGN.md`; this file tracks **what's done, what's next, and the
context a fresh session needs to keep going**.

## Status

- Phase: **3 — deploy & client (code-complete + locally verified)**
- Done:
  - Phases 1–3 in code. Boots clean: `make up` → smoke.sh succeeds;
    /healthz, /readyz, /payments, /deposits/unclaimed, /payments/receive
    (bolt11 + onchain), unauth (401), cross-user (403), rate-limit all
    behave as designed against regtest.
  - Client (`client/`) builds clean on Next 16 + React 19.
  - Linux x86-64 `libbreez_sdk_spark_bindings.so` built via
    `rust:1-bookworm` Docker (3 min) and staged at `libs/`.
  - `docker build --platform linux/amd64` succeeds; the container
    boots in <2s, /readyz green, POST /users + /info round-trip
    works pointed at host Postgres.
  - Bumped Next.js + React to fix CVE-laden 14.2.5 pin.
  - Discovered + worked around: `breez-sdk-spark-kmp-jvm:0.1.0` is
    not actually on `mvn.breez.technology` — Docker needs a per-host
    `libs/m2/…` mirror staged from `~/.m2`. README updated.
  - **Live webhook flow verified end-to-end**: ngrok tunnel →
    SSP-delivered `POST /webhooks/sdk/{user_id}` with valid
    `X-Spark-Signature` → HMAC verify → `syncWallet()` → 200.
    Bolt11 receive paid from external regtest wallet, `/info`
    reflects new balance, `/payments` lists the receive as
    `status=completed`. Phase 2 "Done when" satisfied.
- Next: Supabase project + `flyctl deploy` + `vercel deploy`.
- Repo state: code-complete + verified locally. `libs/` is
  `.gitignore`d; provide its contents per README "Deploy the server"
  before `docker build`.
- Phase-2 integration tests (`one happy path each, no exhaustive
  matrix`) were not landed: they need live regtest infra; smoke.sh
  covers POST /users + GET /info. Track-as-followup.

Update the three lines above every time work crosses a phase
boundary or a meaningful chunk lands.

## Context for a fresh session

If you (Claude) are picking this up cold:

1. Read `DESIGN.md` first. It's the source of truth for every
   architectural decision.
2. The Breez Spark SDK source lives at
   `/Users/danielgranhao/Documents/breez/spark-sdk`. The
   **`origin/daniel-kotlin-harness-server-mode`** branch has the
   newest server-mode bench (`crates/breez-sdk/breez-bench/kotlin/`)
   — that's the canonical reference for `defaultServerConfig`,
   `newSharedSdkContext`, `withSharedContext`, and the no-lock
   per-request lifecycle. Read its `DESIGN.md` and
   `src/main/kotlin/Server.kt` before writing SDK-touching code.
3. Local SDK build pattern is in
   `crates/breez-sdk/bindings/examples/cli/langs/kotlin-multiplatform/Makefile`.
4. Webhook spec is in
   `crates/breez-sdk/docs/breez-sdk/src/guide/webhooks.md`.
5. User's auto-memory: see
   `/Users/danielgranhao/.claude/projects/-Users-danielgranhao-Documents-breez-sdk-mu-demo/memory/`.
6. CLAUDE.md preferences: extremely concise plans, no Co-Authored-By
   in commits.

## Phase 1 — server skeleton + happy path

**Goal:** `docker compose up` brings up Postgres + app; `curl` creates a
user and reads balance against regtest.

- [x] Initialize Gradle project (`build.gradle.kts`,
      `settings.gradle.kts`, `gradle/wrapper`, `gradlew`). Kotlin 2.1,
      JDK 17, single module. Mirror dependency set from spark-sdk
      bench `build.gradle.kts`.
- [x] `.gitignore` (Gradle, IDE, env, build artifacts, `out/`).
- [x] `.env.example` with every var from DESIGN.md "Config & secrets".
- [x] `Config.kt` — env loader, fail-fast on missing required vars.
- [x] `Makefile` with `setup`, `up`, `down`, `logs`, `build`, `run`,
      `LOCAL_SDK` + `SDK_PATH` plumbing.
- [x] `docker-compose.yml` — `postgres:16` service with healthcheck +
      named volume.
- [x] Flyway wired in; `V1__users.sql` migration creates the table per
      DESIGN.md.
- [x] `SharedContext.kt` — builds `newSharedSdkContext` at boot from
      config. Single instance held by the app.
- [x] `Sdk.kt` — `withUser(userId) { sdk -> … }` helper using
      `defaultServerConfig` + `Seed.Entropy(HMAC-SHA512(masterSecret, userId))`.
      Always disconnect in `finally`.
- [x] `Auth.kt` — bearer middleware. Parses header → SHA-256 →
      `users.api_key_hash` lookup → sets principal. 401/403 errors
      use the standard envelope.
- [x] `Main.kt` — Ktor server, `ContentNegotiation(json)`, routing
      block, install shutdown hook to close `SharedContext`.
- [x] `Users.kt` — `POST /users` mints api_key, inserts row.
      (Wired to register webhook in Phase 2.)
- [x] `routes/Info.kt` — `GET /users/{id}/info` → `getInfo(ensureSynced=false)`.
- [x] `Health.kt` — `/healthz` always-200; `/readyz` pings Postgres.
- [x] Smoke test: `./test/smoke.sh` curls `POST /users` then
      `GET /info` against `docker compose` + regtest.
- [x] README skeleton (just "how to run locally" for now) → rewritten
      as a tutorial in Phase 3.

**Done when:** `make up` + the smoke script both succeed on a clean
clone with `LOCAL_SDK=1`.

## Phase 2 — payments end-to-end

**Goal:** full wallet API works locally; payments visible after a
webhook fires.

- [x] `routes/Send.kt` — `POST /payments/send/prepare` +
      `POST /payments/send`. In-memory prepare cache
      (`ConcurrentHashMap`, 60s TTL, scheduled cleanup). Idempotency
      key passthrough.
- [x] `routes/Receive.kt` — `POST /payments/receive` with
      `method ∈ {bolt11, onchain}`. Branch into
      `ReceivePaymentMethod.Bolt11Invoice` or `BitcoinAddress`.
- [x] `routes/Payments.kt` — `list_payments` (with `offset/limit`,
      optional `type`/`status` server-side filter) + `get_payment`.
- [x] `routes/Deposits.kt` — `list_unclaimed_deposits`,
      `claim_deposit`, `refund_deposit`. `{txid}:{vout}` path param
      parsing.
- [x] `routes/Webhooks.kt` — `POST /webhooks/sdk/{user_id}`. Raw-body
      HMAC-SHA256 verify against `X-Spark-Signature` using
      `WEBHOOK_SECRET` (constant-time compare). Then
      `withUser(user_id) { syncWallet() }` and 200.
- [x] Wire webhook registration into `POST /users`:
      `registerWebhook(url, WEBHOOK_SECRET, [all four event types])`.
      Persist returned id in `users.webhook_id`. Rollback on
      reg failure so we don't leave orphan rows.
- [ ] Per-route integration tests against regtest (one happy path
      each, no exhaustive matrix). **DEFERRED:** requires live regtest
      infra; smoke.sh covers POST /users + GET /info. Track separately.
- [x] CORS middleware reading `CORS_ORIGINS`.
- [x] Logback JSON layout in prod profile (LOG_FORMAT=json).
- [x] README — full API table + curl walkthrough; merged into the
      Phase-3 tutorial-style README.

**Done when:** locally, with `ngrok`/`cloudflared` tunneling the
webhook URL, a bolt11 receive triggers a webhook that syncs and
makes the payment show up in `/payments`.

## Phase 3 — deploy & client

**Goal:** team uses a deployed URL, sends/receives over both methods,
history shows up.

- [x] `Dockerfile` — multi-stage (Gradle build → JRE runtime).
      Expects `libs/libbreez_sdk_spark_bindings.so` pre-staged in build
      context — the published `breez-sdk-spark-kmp-jvm:0.1.0` JAR only
      ships host-arch native libs (e.g. `darwin-aarch64/`). Sets
      `jna.library.path=/app/libs`. README documents staging.
- [x] `fly.toml` — 1 shared-cpu-1x, 512MB, region pinned, healthcheck
      on `/readyz`.
- [x] `flyctl secrets set` script (documented in README, not committed).
- [x] Rate-limit middleware on `POST /users` (Ktor RateLimit plugin,
      in-memory, 10/min/IP).
- [x] `client/` Next.js App Router project.
  - [x] `lib/api.ts` typed client using `NEXT_PUBLIC_API_BASE_URL`.
  - [x] `/signup` — POST /users, store api_key in localStorage.
  - [x] `/` — balance + recent 20 payments.
  - [x] `/send` — paste → prepare → review → confirm.
  - [x] `/receive` — bolt11 vs onchain tabs, QR display (qrcode.react).
  - [x] `/payments` + `/payments/[id]` — list + detail.
  - [x] `vercel.json`.
- [x] README rewritten as a tutorial: clone → local → deploy → client.
- [ ] Provision Supabase project (dashboard); record connection URL
      as `DATABASE_URL` for Fly secrets.
- [ ] `flyctl launch --no-deploy --copy-config` → `flyctl secrets set`
      (MASTER_SECRET, WEBHOOK_SECRET, DATABASE_URL, BREEZ_API_KEY,
      PUBLIC_BASE_URL) → `flyctl deploy` → `curl /readyz` green.
- [ ] `vercel deploy` from `./client/` with `NEXT_PUBLIC_API_BASE_URL`
      set to the Fly URL in project settings.
**Done when:** team accesses the Vercel URL, signs up, sends, receives,
and sees history — pointed at the Fly-hosted server on mainnet.

## Planned (v1.1)

In-process scheduled tasks on the always-on JVM. Each task is a
coroutine launched at boot, walks users with a concurrency cap and
jittered per-user offsets, persists `last_run_at` per user/task in the
app schema. Sketched in DESIGN.md "Background tasks".

- [ ] Reconciliation sync — hourly `syncWallet()` per user.
- [ ] Leaf optimization — periodic `optimize()` per user.

## Deferred (post-v1.1)

- Webhook handler does `syncWallet()` synchronously before acking. A
  no-op sync on regtest measured at ~2.2s; real deltas + mainnet RTT
  could push past the SSP's delivery deadline → retry storm or, worst
  case, dropped events. Cheapest hedge if needed: wrap `syncWallet`
  in `withTimeout(25s)` so we 5xx + let SSP retry instead of holding
  the handler open. Proper fix is ack-200-first + persistent event
  queue + background worker. **Trigger to revisit:** Fly logs show
  webhook handler p95 >10s, or SSP-side retries appear in delivery
  history.
- Per-user webhook secret.
- `DELETE /users/{id}` + wallet sweep.
- Spark addresses, Spark invoices, LNURL, lightning addresses.
- Token payments.
- External signer integration (Turnkey).
- HA, Prometheus, alerting, runbooks.
