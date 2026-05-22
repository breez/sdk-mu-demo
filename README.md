# sdk-mu-demo

Reference multi-user server deployment of the
[Breez Spark SDK](https://github.com/breez/rust-spark) — server-mode
integration, full wallet API, deployable end-to-end. One repo: Ktor
server on the JVM, Next.js client.

Read `DESIGN.md` for the why (architecture, server-mode rules, data
model, open questions). This README is the **how**.

```
┌────────────────┐   HTTPS    ┌─────────────────────┐    JNI    ┌──────────┐
│ Next.js client │ ─────────► │  Ktor server (JVM)  │ ────────► │ Spark    │
│ (Vercel)       │            │  SharedSdkContext   │           │ SDK      │
└────────────────┘            │  per-request SDK    │           │ (native) │
                              │  HikariCP + Flyway  │           └────┬─────┘
                              └─────────┬───────────┘                ▼
                                        │ JDBC          operators / SSP / chain
                                        ▼
                                  ┌──────────┐
                                  │ Postgres │
                                  └──────────┘
```

## Tutorial: clone → local → deploy → client

### 1. Local dev

Prereqs: JDK 17, Docker, GNU make.

```bash
git clone <this repo> && cd sdk-mu-demo
cp .env.example .env       # edit MASTER_SECRET, WEBHOOK_SECRET, BREEZ_API_KEY
make setup                 # downloads gradle, validates env
make postgres-up           # boots Postgres 16 in docker
make run                   # ./gradlew run — Ktor on :8080

# In another terminal:
./test/smoke.sh            # POST /users; GET /info
```

Defaults target the Breez Spark regtest network.

#### Iterating on the SDK (`LOCAL_SDK=1`)

To build the SDK from a side-by-side `spark-sdk/` checkout (e.g. while
hacking on the Rust side), set `LOCAL_SDK=1`:

```bash
make setup LOCAL_SDK=1                 # SDK_PATH defaults to ../spark-sdk
make setup LOCAL_SDK=1 SDK_PATH=/abs/path
make run    LOCAL_SDK=1                # gradle build resolves against ~/.m2
```

This builds the Rust dylib (`target/release/libbreez_sdk_spark_bindings.{dylib,so}`),
publishes the KMP bindings to `mavenLocal` under the SDK's in-tree
`libraryVersion` (currently `0.1.0`), and flips `SDK_VERSION` so the
Gradle build picks up that local artifact instead of the published one.
The Docker image is built against the published SDK only; iterate via
`make run`.

#### Webhooks locally

The webhook handler needs a public URL the SSP can reach. Tunnel with
ngrok or cloudflared:

```bash
cloudflared tunnel --url http://localhost:8080      # or `ngrok http 8080`
```

Set `PUBLIC_BASE_URL` in `.env` to the tunnel URL **before** running the
server — webhook registration happens at `POST /users` and the URL is
baked into the SSP-side record.

### 2. API

All `/users/{id}/…` endpoints require `Authorization: Bearer <api_key>`.
Errors use a stable envelope: `{ "error": { "code": "…", "message": "…" } }`.

```
POST   /users
       → { user_id, api_key }                              # api_key shown once
GET    /users/{id}/info                                    → { balance_sats }

POST   /users/{id}/payments/send/prepare
       { payment_request, amount_sats? }
       → { prepare_id, method, amount_sats, fee_sats }     # method: bolt11|onchain
POST   /users/{id}/payments/send
       { prepare_id }      headers: Idempotency-Key (opt)
       → { payment_id, status, fee_sats }

POST   /users/{id}/payments/receive
       { method: "bolt11"|"onchain", amount_sats?, description?, expiry_secs? }
       → { payment_request, fee_sats }                     # invoice or btc address

GET    /users/{id}/payments?offset=&limit=&type=&status=
       → { payments: [...], next_offset }
GET    /users/{id}/payments/{payment_id}                   → { payment }

GET    /users/{id}/deposits/unclaimed                       → { deposits: [...] }
POST   /users/{id}/deposits/{txid}:{vout}/claim             → { payment }
POST   /users/{id}/deposits/{txid}:{vout}/refund
       { destination_address, fee_rate_sat_vb }            → { tx_id, tx_hex }

POST   /webhooks/sdk/{user_id}                              # SSP-only; HMAC verified

GET    /healthz                                             # liveness
GET    /readyz                                              # readiness (db ping)
```

#### Curl walkthrough

```bash
BASE=http://localhost:8080

# 1) provision a wallet
read user_id api_key < <(curl -sS -X POST $BASE/users | python3 -c \
  "import sys,json; r=json.load(sys.stdin); print(r['user_id'],r['api_key'])")

H="Authorization: Bearer $api_key"

# 2) generate a bolt11 invoice for 10k sats
invoice=$(curl -sS -X POST -H "$H" -H 'content-type: application/json' \
  $BASE/users/$user_id/payments/receive \
  -d '{"method":"bolt11","amount_sats":10000,"description":"hello"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["payment_request"])')
echo "invoice: $invoice"

# 3) pay it from another wallet → webhook fires → /info shows 10000

curl -sS -H "$H" $BASE/users/$user_id/info
```

### 3. Deploy the server (Fly.io)

```bash
flyctl launch --no-deploy --copy-config --name <your-app>
flyctl secrets set \
  MASTER_SECRET="…"      WEBHOOK_SECRET="…" \
  DATABASE_URL="postgres://USER:PASS@HOST:5432/DB" \
  BREEZ_API_KEY="…"      PUBLIC_BASE_URL="https://<your-app>.fly.dev"
flyctl deploy
```

The published `breez-sdk-spark-kmp-jvm` JAR bundles native libs for
darwin + linux on both arches, so no per-host staging is needed — the
Dockerfile pulls the SDK from `mvn.breez.technology` and goes.

Database: Supabase (managed Postgres, free tier) — create a project,
copy the connection string from project settings, paste as `DATABASE_URL`.
Any reachable Postgres 14+ works (DO Managed DB, RDS, Neon, self-hosted).

Network: `NETWORK=mainnet` is the deploy default. Real sats. Use
`regtest` for the demo.

### 4. Deploy the client (Vercel)

```bash
cd client
npm install
vercel deploy
# Set NEXT_PUBLIC_API_BASE_URL → https://<your-fly-app>.fly.dev in project settings.
```

The client is plain App Router + a typed `lib/api.ts`. No server actions,
no API routes — the Ktor server is the only API surface. `api_key` lives
in `localStorage` (fine for a demo; a real app would use httpOnly cookies).

Pages:

```
/signup       POST /users, save api_key, redirect /
/             balance + recent 20 payments
/send         paste invoice/address → prepare → review → confirm
/receive      bolt11 | onchain → QR + paste
/payments     paginated list
/payments/:id detail view
```

## Out of scope (v1)

Spark addresses + Spark invoices, LNURL / lightning addresses, token
payments, external signer integration, HA / Prometheus / alerting, KYC
or billing, account deletion. See `DESIGN.md` "Non-goals" for the full
list.

## Project layout

```
.
├── DESIGN.md, PLAN.md, README.md
├── Makefile, docker-compose.yml, Dockerfile, fly.toml
├── .env.example, .dockerignore, .gitignore
├── build.gradle.kts, settings.gradle.kts, gradle.properties
├── gradle/, gradlew
├── src/main/kotlin/
│   ├── Main.kt           Config.kt   Errors.kt
│   ├── SharedContext.kt  Sdk.kt      Auth.kt
│   ├── Users.kt          Health.kt
│   └── routes/{Info,Send,Receive,Payments,Deposits,Webhooks,PaymentDto}.kt
├── src/main/resources/{logback.xml, db/migration/V1__users.sql}
├── test/smoke.sh
└── client/
    ├── package.json, tsconfig.json, vercel.json
    ├── app/{layout,page,signup,send,receive,payments}
    └── lib/api.ts
```
