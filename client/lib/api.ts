// Typed client for the sdk-mu-demo server. All calls require an api_key
// for `users/{userId}/…` endpoints; `POST /users` is open.

const BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export type ApiError = { error: { code: string; message: string } };

export class HttpError extends Error {
  constructor(public status: number, public body: unknown) {
    super(
      typeof body === "object" &&
      body !== null &&
      "error" in body &&
      typeof (body as ApiError).error?.message === "string"
        ? (body as ApiError).error.message
        : `HTTP ${status}`
    );
  }
}

// Default per-request timeout. Healthy calls finish in seconds (reads
// ~1s, send/receive a few seconds, provisioning under ~15s); this bounds
// the wait so a stalled upstream surfaces an error instead of an
// indefinite spinner.
const DEFAULT_TIMEOUT_MS = 45_000;

async function call<T>(
  path: string,
  init: RequestInit & { apiKey?: string; timeoutMs?: number } = {}
): Promise<T> {
  const { apiKey, headers, timeoutMs = DEFAULT_TIMEOUT_MS, ...rest } = init;
  const h = new Headers(headers);
  if (apiKey) h.set("Authorization", `Bearer ${apiKey}`);
  if (init.body && !h.has("Content-Type")) h.set("Content-Type", "application/json");

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  let res: Response;
  try {
    res = await fetch(`${BASE_URL}${path}`, { ...rest, headers: h, signal: controller.signal });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") {
      throw new HttpError(0, {
        error: { code: "timeout", message: "The server took too long to respond. Please try again." },
      });
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }

  let body: unknown = null;
  try {
    body = await res.json();
  } catch {
    // ignore — empty body
  }
  if (!res.ok) throw new HttpError(res.status, body);
  return body as T;
}

// --- shapes ----------------------------------------------------------------

export type CreateUser = {
  user_id: string;
  api_key: string;
  // Present only when the deployment runs SIGNER=turnkey (client-signed sends).
  turnkey_sub_org_id?: string | null;
};
export type Info = { balance_sats: number };

// Returned by POST /login: a freshly minted api_key for an existing wallet,
// re-authenticated via its Turnkey session JWT.
export type Login = {
  user_id: string;
  api_key: string;
  turnkey_sub_org_id: string;
};

// The WebAuthn passkey attestation the browser registers at sign-up, relayed
// into the user's Turnkey sub-org as its sole root user. Field names match the
// server's PasskeyAttestation / Turnkey's authenticator schema.
export type PasskeyAttestation = {
  authenticatorName?: string;
  challenge: string;
  attestation: {
    credentialId: string;
    clientDataJson: string;
    attestationObject: string;
    transports: string[];
  };
};

// The SPARK_PREPARE_TRANSFER material the server hands the client to sign.
export type TransferDto = {
  transfer_id: string;
  receiver_public_key: string; // hex
  threshold: number;
  leaves: { leaf_id: string; new_leaf_id: string }[];
  operator_recipients: { operator_id: string; encryption_public_key: string }[];
};

// The signed result the client returns to the server (publish).
export type SignedTransferDto = {
  operator_packages: { operator_id: string; encrypted_package: string }[];
  new_leaf_keys: { leaf_id: string; public_key: string }[];
  transfer_user_signature: string; // hex, 64-byte compact r||s
};

export type PublishResult = {
  swap_completed?: boolean;
  payment_id?: string;
  status?: string;
  fee_sats?: number;
};

export type Payment = {
  id: string;
  type: "send" | "receive";
  status: "completed" | "pending" | "failed";
  method: string;
  amount_sats: number;
  fee_sats: number;
  timestamp: number;
  invoice?: string | null;
  description?: string | null;
  tx_id?: string | null;
};

export type ListPayments = { payments: Payment[]; next_offset: number | null };

export type Prepare = {
  prepare_id: string;
  method: "bolt11" | "onchain";
  amount_sats: number;
  fee_sats: number;
  // Present only under SIGNER=turnkey (client-signed sends).
  kind?: "transfer" | "swap";
  sign_with?: string;
  transfer?: TransferDto;
  // The invoice/address the payment pays — shown at approval. Set for the send
  // leg and any preceding swap alike, so the review card always shows it.
  destination?: string;
};

export type SendResult = {
  payment_id: string;
  status: string;
  fee_sats: number;
};

export type Receive = { payment_request: string; fee_sats: number };

export type Deposit = {
  txid: string;
  vout: number;
  amount_sats: number;
  is_mature: boolean;
  refund_tx?: string | null;
  refund_tx_id?: string | null;
  claim_error?: string | null;
};

// --- methods --------------------------------------------------------------

export const api = {
  // `passkey` is required when the deployment runs SIGNER=turnkey (it becomes
  // the sub-org's root user); `sessionPublicKey` is the browser-held session key
  // pre-authorized on the wallet so swaps need no extra tap. Both omitted for
  // SIGNER=seed.
  createUser: (passkey?: PasskeyAttestation, sessionPublicKey?: string) =>
    call<CreateUser>("/users", {
      method: "POST",
      body: JSON.stringify({
        ...(passkey ? { passkey } : {}),
        ...(sessionPublicKey ? { session_public_key: sessionPublicKey } : {}),
      }),
    }),

  // Turnkey deployments: re-authenticate an existing wallet with its passkey
  // (the session JWT) and get a fresh api_key.
  login: (sessionJwt: string) =>
    call<Login>("/login", { method: "POST", body: JSON.stringify({ session_jwt: sessionJwt }) }),

  info: (userId: string, apiKey: string) =>
    call<Info>(`/users/${userId}/info`, { apiKey }),

  listPayments: (
    userId: string,
    apiKey: string,
    opts: { offset?: number; limit?: number; type?: "send" | "receive"; status?: string } = {}
  ) => {
    const q = new URLSearchParams();
    if (opts.offset != null) q.set("offset", String(opts.offset));
    if (opts.limit != null) q.set("limit", String(opts.limit));
    if (opts.type) q.set("type", opts.type);
    if (opts.status) q.set("status", opts.status);
    const s = q.toString();
    return call<ListPayments>(
      `/users/${userId}/payments${s ? `?${s}` : ""}`,
      { apiKey }
    );
  },

  getPayment: (userId: string, apiKey: string, id: string) =>
    call<{ payment: Payment }>(`/users/${userId}/payments/${id}`, { apiKey }),

  prepareSend: (
    userId: string,
    apiKey: string,
    body: { payment_request: string; amount_sats?: number }
  ) =>
    call<Prepare>(`/users/${userId}/payments/send/prepare`, {
      method: "POST",
      body: JSON.stringify(body),
      apiKey,
    }),

  // Seed deployments: the server signs and sends.
  send: (
    userId: string,
    apiKey: string,
    prepareId: string,
    idempotencyKey?: string
  ) =>
    call<SendResult>(`/users/${userId}/payments/send`, {
      method: "POST",
      body: JSON.stringify({ prepare_id: prepareId }),
      apiKey,
      headers: idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {},
    }),

  // Turnkey deployments: the client signed SPARK_PREPARE_TRANSFER; the server
  // publishes it. May return { swap_completed: true }, meaning the client must
  // re-prepare and sign the next package (the swap loop).
  publishSend: (
    userId: string,
    apiKey: string,
    prepareId: string,
    signed: SignedTransferDto
  ) =>
    call<PublishResult>(`/users/${userId}/payments/send/publish`, {
      method: "POST",
      body: JSON.stringify({ prepare_id: prepareId, signed }),
      apiKey,
    }),

  receive: (
    userId: string,
    apiKey: string,
    body: {
      method: "bolt11" | "onchain";
      amount_sats?: number;
      description?: string;
      expiry_secs?: number;
    }
  ) =>
    call<Receive>(`/users/${userId}/payments/receive`, {
      method: "POST",
      body: JSON.stringify(body),
      apiKey,
    }),

  listUnclaimedDeposits: (userId: string, apiKey: string) =>
    call<{ deposits: Deposit[] }>(`/users/${userId}/deposits/unclaimed`, { apiKey }),

  claimDeposit: (userId: string, apiKey: string, outpoint: string) =>
    call<{ payment: Payment }>(
      `/users/${userId}/deposits/${outpoint}/claim`,
      { method: "POST", apiKey, body: "{}" }
    ),
};

// --- local creds (api key + user id) --------------------------------------
//
// Stored in localStorage. v1 — fine for a demo; a real app would use
// httpOnly cookies and never expose the key to JS.

const STORAGE_KEY = "sdk-mu-demo.creds";

export type Creds = {
  user_id: string;
  api_key: string;
  // Present only for turnkey deployments — the client signs sends against it.
  turnkey_sub_org_id?: string | null;
  // The passkey credential registered as this sub-org's root user. Pins the
  // send's WebAuthn prompt to the right passkey among any others on the device.
  turnkey_credential_id?: string | null;
};

export function loadCreds(): Creds | null {
  if (typeof window === "undefined") return null;
  const s = window.localStorage.getItem(STORAGE_KEY);
  return s ? (JSON.parse(s) as Creds) : null;
}

export function saveCreds(c: Creds) {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(c));
}

export function clearCreds() {
  window.localStorage.removeItem(STORAGE_KEY);
}
