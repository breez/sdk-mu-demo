# sdk-mu-demo — design

Reference implementation of a multi-user server deployment of the
Breez Spark SDK. Goal: a senior dev can read this repo in an hour,
copy the patterns, and ship. Plus a deployable demo (server on Fly,
client on Vercel) so a team can actually try it.

## Goals

1. Canonical server-mode integration (`defaultServerConfig`, shared
   `SdkContext`, per-request `build → op → disconnect`, no lock).
2. Full client-facing API: send + receive over Bolt11 + on-chain,
   payment history, deposits. Enough to back a real wallet UI.
3. Deployable end-to-end on managed infra. `flyctl deploy` for the
   server (always-on), `vercel deploy` for the client, Supabase for
   Postgres. One repo.
4. Local dev: `docker compose up` brings up Postgres + regtest deps + app.
5. SDK source toggle: published artifact by default; local build
   from a side-by-side `spark-sdk/` via one env var.

## Non-goals (v1)

- Spark addresses, Spark invoices, LNURL, lightning addresses.
- External signer integration (Turnkey etc.) — postponed; seed is
  derived in-process from `MASTER_SECRET`. Refactor when the SDK's
  signer story stabilizes.
- HA, k8s, Prometheus dashboards, alerting, runbooks.
- KYC, billing, rate limiting (beyond trivial), abuse mitigation.
- User deletion / wallet sweep / disable flow.
- Token payments. Sats only.
- Backups / DR.

## Architecture

```
┌────────────────┐   HTTPS    ┌─────────────────────┐    JNI    ┌──────────┐
│ Next.js client │ ─────────► │  Ktor server (JVM)  │ ────────► │ Spark    │
│ (Vercel)       │            │  - SharedSdkContext │           │ SDK      │
└────────────────┘            │  - per-req SDK      │           │ (native) │
                              │  - JDBC pool        │           └──────────┘
                              │                     │                │
                              │                     │                ▼
                              │                     │           operators,
                              │                     │           SSP, chain
                              │                     │
                              │   ◄── webhooks ──── │           Spark SSP
                              └─────────┬───────────┘            (HMAC-SHA256)
                                        │ JDBC
                                        ▼
                                  ┌──────────┐
                                  │ Postgres │  (SDK + app schemas, same DB)
                                  └──────────┘
```

One process. One shared `SdkContext` built at boot, threaded into
every `SdkBuilder`. One JDBC pool, used by both the SDK and the app
schema. Stateless app layer; all durable state in Postgres.

## Stack

- Kotlin 2.1, JDK 17, Gradle KTS, single module.
- Ktor 2.x server (Netty engine).
- Postgres 16 via HikariCP + Flyway migrations.
- `kotlinx-serialization-json` for wire format.
- `logback-classic` for logs (JSON layout in prod, pretty in dev).
- SDK: `technology.breez.spark:breez-sdk-spark-kmp-jvm:<version>` from
  `mvn.breez.technology` (default) or `mavenLocal` (when `LOCAL_SDK=1`).
- Client: Next.js 14 (App Router, client components only).

## Server-mode integration (the core pattern)

```kotlin
// boot
val sharedCtx = newSharedSdkContext(SharedSdkContextParams(
    databaseUrl = env("DATABASE_URL"),
    // HTTP client, operator gRPC, Breez gRPC, Postgres pool all live here
))

// per request
suspend fun <T> withUser(userId: String, op: suspend (BreezSdk) -> T): T {
    val seed = hmacSha512(masterSecret, userId)  // 64 bytes entropy
    val sdk = SdkBuilder(defaultServerConfig(network), Seed.Entropy(seed))
        .withSharedContext(sharedCtx)
        .build()
    try { return op(sdk) } finally { sdk.disconnect() }
}
```

Rules — codified in code, called out in README:

- **No `ensureSynced=true` on reads.** Server mode rejects it. Reads
  see the local DB, which is kept fresh by webhook-driven syncs.
- **No defensive sync.** `syncWallet()` is called only by the webhook
  handler, on the user whose wallet the event names. Reads never sync.
- **No per-user lock.** Concurrent same-user requests are safe
  (operator-level single-spend via FROST; SDK retries on race; tree
  store writes idempotently). Verified by the upstream bench.
- **Always disconnect.** `try/finally`. Flushes outstanding writes.
- **One SDK per request, never pinned to a worker thread.**

## API surface

All `/users/{id}/...` endpoints require `Authorization: Bearer <api_key>`.
Mismatched user vs. key → 403. Open `POST /users` (deployer is
expected to put their own auth in front for prod — README warns).

```
POST   /users
       → { user_id, api_key }                 # api_key shown once
GET    /users/{id}/info
       → { balance_sats }

POST   /users/{id}/payments/send/prepare
       { payment_request, amount_sats? }
       → { prepare_id, method, fee_sats, ... }
       # method inferred from payment_request:
       #   bolt11 invoice → "bolt11"
       #   btc address    → "onchain"
POST   /users/{id}/payments/send
       { prepare_id }
       headers: Idempotency-Key (optional, passed to SDK)
       → { payment_id, status, fee_sats }

POST   /users/{id}/payments/receive
       { method: "bolt11" | "onchain", amount_sats?, description?, expiry_secs? }
       → { payment_request, fee_sats }
       # bolt11: invoice string. onchain: btc address.

GET    /users/{id}/payments?offset=&limit=&type=&status=
       → { payments: [...], next_offset }
GET    /users/{id}/payments/{payment_id}
       → { payment }

GET    /users/{id}/deposits/unclaimed
       → { deposits: [...] }
POST   /users/{id}/deposits/{txid}:{vout}/claim
POST   /users/{id}/deposits/{txid}:{vout}/refund
       { destination_address, fee_rate_sat_vb }

POST   /webhooks/sdk/{user_id}              # called by Spark SSP
       headers: X-Spark-Signature
       body: see below

GET    /users/{id}/events                   # WebSocket upgrade; auth: ?api_key=
       → SdkEvent stream

GET    /healthz                             # liveness (always 200)
GET    /readyz                              # readiness (db ping + shared-ctx ok)
```

Errors: `{ error: { code, message } }`. Stable codes
(`unauthorized`, `forbidden`, `not_found`, `bad_request`,
`upstream_unavailable`, `internal`).

### prepare_id

Two-step send (prepare → confirm) lets clients show a fee quote.
Server holds the `PrepareSendPaymentResponse` in an in-memory
`ConcurrentHashMap<String, PrepareEntry>` keyed by a random
`prepare_id`, 60s TTL. Restart → client re-prepares. Acceptable
because prepare is cheap and idempotent.

### Idempotency

Send accepts `Idempotency-Key`. Passed to
`SendPaymentRequest.idempotencyKey`. SDK dedupes; safe retry semantics.

## Webhook handling

Spark SSP signs each delivery with HMAC-SHA256 over the raw body using
the secret given at registration. Header: `X-Spark-Signature`.

Registration happens once at user provisioning:

```kotlin
// POST /users
withUser(newUserId) { sdk ->
    sdk.registerWebhook(RegisterWebhookRequest(
        url = "${PUBLIC_BASE_URL}/webhooks/sdk/$newUserId",
        secret = WEBHOOK_SECRET,                          // shared across users in v1
        eventTypes = listOf(
            WebhookEventType.LightningReceiveFinished,
            WebhookEventType.LightningSendFinished,
            WebhookEventType.CoopExitFinished,
            WebhookEventType.StaticDepositFinished,
        ),
    ))
}
```

Handler:

```
POST /webhooks/sdk/{user_id}
  1. Verify HMAC-SHA256(raw_body, WEBHOOK_SECRET) == X-Spark-Signature
  2. Parse event
  3. withUser(user_id) { sdk -> sdk.syncWallet() }
  4. Respond 200 (ack)
```

Errors → 5xx so the SSP retries. Idempotent at the SDK layer (sync is
safe to repeat). We don't dedupe events at our boundary in v1.

**Shared vs per-user secret:** v1 uses one `WEBHOOK_SECRET` env var
across all users. Simpler. A real prod deployment might prefer a
per-user secret stored in the `users` table; called out in README.

## Client push (WebSocket)

`GET /users/{id}/events` (WS upgrade, `?api_key=...` since browsers
can't header-auth WS). Inside every `withUser`, an `EventListener`
bridges `SdkEvent` to a per-user in-process `MutableSharedFlow`; WS
subscribers drain it. Payment + deposit events flow live.

Best-effort delivery. No replay. On (re)connect the client refetches
`/info` + `/payments`, then trusts the stream.

Single-process bus is enough for v1.1; multi-machine would swap in
Redis pubsub / Postgres LISTEN-NOTIFY (same coordination story as the
optimize queue).

## Background tasks

The always-on JVM hosts a single in-memory optimize queue alongside the
HTTP server. Webhook handler (after `syncWallet`) and send handler
(after `sendPayment`) both enqueue `OptimizeJob(userId)`. A worker
coroutine drains the queue and, per job, builds a per-user SDK via
`withUser` and calls `optimizeLeaves(OptimizeLeavesRequest(mode = FULL))`
— a `suspend` call (uniffi async, so it doesn't pin a thread) that
returns `OptimizeLeavesResponse` whose `outcome` is
`Completed { rounds_executed }` (`rounds_executed == 0` means the wallet
was already optimal) once the run finishes. `withTimeout(5min)` wraps the
call as a circuit-breaker: cancellation propagates into the SDK (uniffi
drops the Rust future), bounding a run whose future never completes from
holding a worker permit indefinitely.

- **Dedup.** If a job for `userId` is already queued or in-flight, drop
  the new one. A burst of payments shouldn't fire N optimizes.
- **Concurrency cap.** Small semaphore (2–4) so a fleet-wide spike
  doesn't pin the JVM.
- **Auto-optimize disabled.** `LeafOptimizationConfig.autoEnabled =
  false`. The SDK's built-in auto-optimizer relies on a long-lived
  connection that the per-request `withUser` pattern doesn't provide.

In-memory is enough for v1: a process restart drops queued jobs; the
next payment for that user re-enqueues. The worker uses the same
`SharedSdkContext` and `withUser` helper as the HTTP routes.

No reconciliation sync. The webhook delivery contract is the recovery
path: we 5xx on failure, the SSP retries.

## Data model

App-owned tables, kept separate from SDK tables (which live in the same
DB but are SDK-managed and we never touch them):

```sql
-- v1__users.sql
CREATE TABLE users (
    user_id        VARCHAR(64)  PRIMARY KEY,             -- ULID
    api_key_hash   CHAR(64)     NOT NULL UNIQUE,         -- SHA-256(api_key)
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    webhook_id     VARCHAR(64)                           -- from SDK, for unregister
);
CREATE INDEX idx_users_created_at ON users (created_at);
```

That's the entire app schema for v1. Everything else (payments,
deposits, balance) lives in the SDK's tables and is read through SDK
APIs.

Why not cache payments app-side? Because the SDK already maintains the
canonical record and our reads after a sync return fresh data. Caching
would duplicate state and invite drift.

## Auth

- `api_key` minted at `POST /users`: 32 random bytes, base32-no-pad,
  prefixed `mu_`. Shown once. Hashed (SHA-256) for storage.
- Bearer in `Authorization` header. Middleware looks up
  `api_key_hash`, sets `principal = user_id`, rejects on mismatch.
- Path `{id}` must match `principal`. Cross-user → 403.

## Config & secrets

All via env. `.env.example` committed. Production: Fly secrets.

| Var | Required | Default | Notes |
|---|---|---|---|
| `NETWORK` | yes | — | `mainnet` \| `regtest` |
| `DATABASE_URL` | yes | — | `postgres://user:pass@host:5432/db` |
| `MASTER_SECRET` | yes | — | Hex or ASCII; seed-derivation input |
| `WEBHOOK_SECRET` | yes | — | HMAC secret given to SSP at registration |
| `PUBLIC_BASE_URL` | yes | — | E.g. `https://sdk-mu-demo.fly.dev` |
| `BREEZ_API_KEY` | yes | — | Passed into `defaultServerConfig` |
| `PORT` | no | `8080` | |
| `CORS_ORIGINS` | no | empty | Comma-sep; client origin in deployed mode |
| `LOG_LEVEL` | no | `info` | |

## Build & local dev

```
make setup            # one-time per branch
make up               # docker compose up (postgres + app)
make logs             # tail
make down             # stop
```

`LOCAL_SDK` toggle (lifted from spark-sdk's bench Makefile):

```
make setup                              # uses published artifact
make setup LOCAL_SDK=1                  # SDK_PATH defaults to ../spark-sdk
make setup LOCAL_SDK=1 SDK_PATH=/path
```

When `LOCAL_SDK=1`: cd into `$SDK_PATH`, run its `build-release` +
`install-uniffi-bindgen-gobley`, generate KMP bindings, publish to
`mavenLocal`. The Makefile flips `SDK_VERSION` to match the local
artifact's `libraryVersion` (currently `0.1.0`); Gradle's repository
block lists `mavenLocal()` first, so the local artifact wins.

Native lib at runtime: the published KMP JAR bundles a `.so` per arch
(`darwin-aarch64`, `darwin-x86-64`, `linux-aarch64`, `linux-x86-64`)
and JNA loads it from the classpath. The `LOCAL_SDK=1` path emits only
host-arch native libs, so the `jna.library.path` runtime hint set on
the Gradle `run` task is what makes that path work.

## Deploy

### Server: Fly.io

- `fly.toml`: 1 shared-cpu-1x VM, 512MB, region near user.
- `Dockerfile`: multi-stage. Stage 1: `gradle:8-jdk17` builds the
  shadow jar. Stage 2: `eclipse-temurin:17-jre-jammy`, copies jar +
  native `.so` from the KMP artifact, sets `jna.library.path`. Runs
  `java -jar app.jar`.
- Secrets via `flyctl secrets set` for `MASTER_SECRET`,
  `WEBHOOK_SECRET`, `DATABASE_URL`, `BREEZ_API_KEY`.
- `min_machines_running = 1`, `auto_stop_machines = "off"`. The JVM
  hosts an in-process optimize queue worker that drains opportunistically
  after payments — needs a continuously running process.

### Database

- **Default (deployed):** Supabase (managed Postgres, free tier). Set
  `DATABASE_URL` to the connection string from the Supabase project
  settings. Any reachable Postgres 14+ works — DO Managed DB, RDS,
  Neon, self-hosted, etc.
- **Local:** `docker compose` brings up postgres:16.

### Client: Vercel

- `client/` is a Next.js App Router project. Vercel auto-detects.
- One env var: `NEXT_PUBLIC_API_BASE_URL` → server's Fly URL.
- Client-side only (no server actions, no API routes proxying) so the
  API surface is visibly the source of truth — easier to understand
  what the server actually exposes.

### Networks

- Default `NETWORK=mainnet`. Costs real sats to seed demo wallets.
- `NETWORK=regtest` for the Spark deployed regtest network (the de
  facto testnet). Local dev runs against regtest by default.
- Same binary, different env. README explains how to switch.

## Client pages

```
/signup       POST /users, store api_key in localStorage, redirect to /
/             GET /info (balance) + GET /payments (recent 20)
/send         input: payment_request, amount?  →  prepare → review → confirm
/receive      tabs: bolt11 | onchain  →  POST /receive  →  show QR + paste
/payments     paginated list, click row → detail
/payments/:id GET /payments/{id}
```

Plain CSS / Tailwind, no design system. Wallet UX, not product UX.

## Project layout

```
.
├── DESIGN.md                  (this file)
├── README.md                  (tutorial / how to run)
├── Makefile                   (setup, up, down, etc.)
├── docker-compose.yml         (postgres + app for local)
├── fly.toml
├── Dockerfile
├── .env.example
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/, gradlew
├── src/
│   ├── main/kotlin/
│   │   ├── Main.kt
│   │   ├── Config.kt
│   │   ├── Errors.kt          (envelope + stable codes)
│   │   ├── SharedContext.kt
│   │   ├── Sdk.kt             (withUser helper)
│   │   ├── Auth.kt            (api-key middleware)
│   │   ├── Users.kt           (POST /users, mint + register webhook)
│   │   ├── routes/
│   │   │   ├── Info.kt
│   │   │   ├── Send.kt        (prepare + confirm)
│   │   │   ├── Receive.kt
│   │   │   ├── Payments.kt    (list + get)
│   │   │   ├── PaymentDto.kt  (shared payment serialization)
│   │   │   ├── Deposits.kt
│   │   │   └── Webhooks.kt
│   │   └── Health.kt
│   ├── main/resources/
│   │   ├── logback.xml
│   │   └── db/migration/      (Flyway, V1__users.sql, …)
│   └── test/kotlin/           (per-route happy-path tests against regtest)
└── client/                    (Next.js App Router)
    ├── package.json
    ├── app/
    │   ├── layout.tsx
    │   ├── page.tsx
    │   ├── signup/page.tsx
    │   ├── send/page.tsx
    │   ├── receive/page.tsx
    │   └── payments/...
    ├── lib/api.ts             (typed client for the server)
    └── vercel.json
```
