// Client-side Turnkey integration for delegated-access sends.
//
// The browser is the *only* party that can authorize a send: it holds the
// user's passkey (the sub-org's sole root user). The server never sees a key.
// This module:
//   - registers a passkey at sign-up (-> attestation for CREATE_SUB_ORGANIZATION),
//   - sets up a read-write session for silent denomination-*swap* stamping —
//     its key is pre-authorized at sign-up (no extra tap) and only re-minted
//     with a passkey tap once it expires,
//   - submits the Turnkey SPARK_PREPARE_TRANSFER activity for each package the
//     server hands back — stamped by the session for a swap, by the passkey for
//     the actual send — and maps the result into the shape the server publishes.
//
// The Turnkey JS SDK has no typed Spark method, so the activity is submitted
// via a stamper + fetch (the stable primitive), mirroring the server's own
// Turnkey transport.

import { Turnkey, WebauthnStamper, SessionType, getStorageValue, StorageKeys } from "@turnkey/sdk-browser";
import { secp256k1 } from "@noble/curves/secp256k1";
import type { SignedTransferDto, TransferDto } from "./api";

const API_BASE_URL =
  process.env.NEXT_PUBLIC_TURNKEY_API_BASE_URL ?? "https://api.turnkey.com";
// Parent org id — only needed to host passkey registration before the user's
// sub-org exists.
const PARENT_ORG_ID = process.env.NEXT_PUBLIC_TURNKEY_ORG_ID ?? "";
// WebAuthn relying-party id (the site's registrable domain, e.g. "localhost").
const RP_ID = process.env.NEXT_PUBLIC_TURNKEY_RP_ID ?? "localhost";

/** Whether this deployment signs sends client-side (turnkey) at all. */
export const turnkeyEnabled = PARENT_ORG_ID.length > 0;

function turnkey(organizationId: string): Turnkey {
  return new Turnkey({ apiBaseUrl: API_BASE_URL, defaultOrganizationId: organizationId, rpId: RP_ID });
}

// --- sign-up: register a passkey -------------------------------------------

export type RegisteredPasskey = {
  challenge: string;
  attestation: {
    credentialId: string;
    clientDataJson: string;
    attestationObject: string;
    transports: string[];
  };
};

/**
 * Registers a new WebAuthn passkey (one biometric prompt) and returns the
 * attestation to embed as the sub-org's owner root user.
 */
export async function registerPasskey(userName: string): Promise<RegisteredPasskey> {
  const tk = turnkey(PARENT_ORG_ID);
  const passkey = await tk.passkeyClient().createUserPasskey({
    publicKey: { user: { name: userName, displayName: userName } },
  });
  return {
    challenge: passkey.encodedChallenge,
    attestation: {
      credentialId: passkey.attestation.credentialId,
      clientDataJson: passkey.attestation.clientDataJson,
      attestationObject: passkey.attestation.attestationObject,
      transports: passkey.attestation.transports,
    },
  };
}

// --- session: silent stamping for swaps ------------------------------------

/**
 * Generates the browser-held session keypair (non-extractable, in IndexedDB) and
 * returns its public key, for the server to pre-authorize as a short-lived API
 * key on the new wallet at sign-up. That lets swaps be stamped silently
 * afterward without a second passkey tap. Generation is local — no prompt. The
 * key is generated under the parent org (the sub-org doesn't exist yet); it
 * lives in IndexedDB and is read back by the sub-org client when signing.
 */
export async function createSessionPublicKey(): Promise<string> {
  const tk = turnkey(PARENT_ORG_ID);
  const idb = await tk.indexedDbClient();
  await idb.resetKeyPair();
  const publicKey = await idb.getPublicKey();
  if (!publicKey) throw new Error("failed to generate a session key");
  return publicKey;
}

/**
 * Ensures a read-write session exists for `subOrgId`, prompting the passkey
 * once if not. The session keypair lives (non-extractable) in IndexedDB and
 * stamps swap activities without further prompts. The actual send is always
 * stamped by the passkey, so an expired/absent session only ever costs an
 * extra tap for swaps, never weakens send approval.
 *
 * No tap is needed while the session key pre-authorized at sign-up (see
 * [createSessionPublicKey]) is still valid; only once it expires does this fall
 * back to minting a fresh session with one (pinned) passkey tap.
 */
export async function ensureSession(subOrgId: string, credentialId?: string | null): Promise<void> {
  const tk = turnkey(subOrgId);
  const idb = await tk.indexedDbClient();
  await idb.init();
  const currentKey = await idb.getPublicKey();

  // Reuse the existing session only if it is for THIS sub-org and bound to the
  // current IndexedDB key. A session left over from another sub-org (e.g. after
  // re-provisioning or a network switch) is still "valid" by expiry but its key
  // isn't authorized here, so stamping would fail PUBLIC_KEY_NOT_FOUND — force a
  // fresh login in that case.
  //
  // NB: getSession() returns parseSession's shape, where the session public key
  // is in `token` (parseSession maps the JWT's public_key -> token); there is no
  // `publicKey` field. Comparing `.publicKey` here always failed (undefined),
  // re-logging in on every send and costing a needless second passkey tap.
  const existing = await tk.getSession();
  if (
    existing &&
    existing.organizationId === subOrgId &&
    existing.token === currentKey &&
    existing.expiry * 1000 > Date.now() + 5_000
  ) {
    return;
  }

  // No JWT session, but the current key may be the API key pre-authorized at
  // sign-up — there's no getSession() bundle for that, so confirm directly with
  // a whoami stamped by the key. If it's a valid (unexpired) credential this
  // succeeds and no tap is needed; if it's missing/expired it throws and we mint
  // a fresh session below.
  if (currentKey) {
    try {
      await idb.getWhoami({ organizationId: subOrgId });
      return;
    } catch {
      /* not an authorized credential — fall through to a passkey login */
    }
  }

  // Need a new session. Mint a FRESH session keypair first: re-registering the
  // persisted key fails Turnkey's "credential public keys must be unique" when
  // it's still on file from a prior/expired session.
  await idb.resetKeyPair();
  const publicKey = await idb.getPublicKey();
  // Passkey tap → a session registering the fresh key in this sub-org. Pin the
  // prompt to this sub-org's credential so a device with several "Glow" passkeys
  // doesn't show a picker (which, picked wrong, fails) — same pinning the send
  // path uses. Falls back to an unpinned prompt if we have no id.
  const passkeyClient = tk.passkeyClient(
    credentialId
      ? { rpId: RP_ID, allowCredentials: [{ type: "public-key", id: base64urlToBytes(credentialId) as BufferSource }] }
      : undefined,
  );
  await passkeyClient.loginWithPasskey({
    sessionType: SessionType.READ_WRITE,
    publicKey,
  });
}

/**
 * Clears the browser-side Turnkey state — the read-write session token and the
 * IndexedDB session keypair. Call this on logout: app creds live in
 * localStorage, but the Turnkey session/key live separately, so without this a
 * later sign-up inherits a stale session bound to the previous sub-org (its key
 * isn't authorized in the new one → PUBLIC_KEY_NOT_FOUND on the next sign).
 * Best-effort: a missing session/key is not an error.
 */
export async function clearTurnkeySession(subOrgId?: string): Promise<void> {
  if (!turnkeyEnabled) return;
  const tk = turnkey(subOrgId ?? PARENT_ORG_ID);
  try {
    await tk.logout();
  } catch {
    /* no active session */
  }
  try {
    await (await tk.indexedDbClient()).clear();
  } catch {
    /* no stored key */
  }
}

// --- login: re-authenticate an existing wallet -----------------------------

/**
 * Logs in with an existing passkey and returns the Turnkey session JWT for the
 * server to verify. The passkey tap happens against the parent org; Turnkey
 * resolves the credential to *its* sub-org and issues a session JWT naming that
 * sub-org. If several wallets exist on the device, the passkey the user picks
 * selects which wallet they log into. Also establishes the session sends reuse.
 *
 * Returns the credential id of the passkey too, so the next send can pin its
 * WebAuthn prompt to it (see [signSparkPrepareTransfer]). `loginWithPasskey`
 * itself returns void and doesn't surface which credential was used, so we ask
 * Turnkey: the fresh session belongs to the passkey root user, so whoami names
 * that user and its sole authenticator is the passkey.
 */
export async function loginWithExistingPasskey(): Promise<{
  sessionJwt: string;
  credentialId: string | null;
}> {
  const tk = turnkey(PARENT_ORG_ID);
  const idb = await tk.indexedDbClient();
  // Always mint a FRESH session keypair: re-registering the persisted key fails
  // Turnkey's "credential public keys must be unique" when it's still on file
  // from an earlier session, and a fresh key also can't carry over a session
  // bound to a different wallet.
  await idb.resetKeyPair();
  const publicKey = await idb.getPublicKey();
  await tk.passkeyClient().loginWithPasskey({ sessionType: SessionType.READ_WRITE, publicKey });
  // The raw session JWT is the stored session value. NB: getSession().token is
  // the session *public key*, not the JWT (parseSession maps token -> publicKey),
  // so read the stored string directly.
  const rawJwt = await getStorageValue(StorageKeys.Session);
  const session = await tk.getSession();
  if (typeof rawJwt !== "string" || !session?.organizationId) {
    throw new Error("login did not produce a session token");
  }
  const subOrgId = session.organizationId;

  // Resolve the passkey's credential id from Turnkey, stamped by the fresh
  // session. Best-effort: if the lookup fails the send just falls back to an
  // unpinned WebAuthn prompt.
  let credentialId: string | null = null;
  try {
    await idb.init();
    const who = await idb.getWhoami({ organizationId: subOrgId });
    const auth = await idb.getAuthenticators({ organizationId: subOrgId, userId: who.userId });
    credentialId = auth.authenticators[0]?.credentialId ?? null;
  } catch {
    /* leave unpinned */
  }

  return { sessionJwt: rawJwt, credentialId };
}

// --- signing SPARK_PREPARE_TRANSFER ----------------------------------------

interface Stamper {
  stamp(payload: string): Promise<{ stampHeaderName: string; stampHeaderValue: string }>;
}

/**
 * Signs one SPARK_PREPARE_TRANSFER package. `kind === "transfer"` is the actual
 * send (passkey prompt); `kind === "swap"` is a denomination swap to the SSP
 * (silent, session-stamped).
 */
export async function signSparkPrepareTransfer(
  subOrgId: string,
  signWith: string,
  transfer: TransferDto,
  kind: "transfer" | "swap",
  credentialId?: string | null,
): Promise<SignedTransferDto> {
  const tk = turnkey(subOrgId);
  // Load the persisted session keypair into this client instance before using
  // its stamper. init() is required per instance (it hydrates the in-memory key
  // from IndexedDB); without it the stamper throws "Key not initialized". It
  // loads the existing key rather than regenerating, so the session created in
  // ensureSession stays valid. The session stamper signs swaps and always polls.
  const idb = await tk.indexedDbClient();
  await idb.init();
  const sessionStamper = idb.stamper as Stamper;
  // The send is passkey-stamped. Pin it to this sub-org's credential so the
  // browser uses the right passkey directly instead of offering a picker over
  // every "Glow" passkey on the device (which, picked wrong, fails
  // CREDENTIAL_NOT_FOUND). Falls back to an unpinned prompt if we have no id.
  const submitStamper: Stamper =
    kind === "transfer"
      ? new WebauthnStamper({
          rpId: RP_ID,
          ...(credentialId
            ? { allowCredentials: [{ type: "public-key", id: base64urlToBytes(credentialId) as BufferSource }] }
            : {}),
        })
      : sessionStamper;

  const parameters = {
    signWith,
    transfer: {
      transferId: transfer.transfer_id,
      leaves: transfer.leaves.map((l) => ({
        leafId: l.leaf_id,
        oldLeafDerivation: { signingLeaf: { leafId: l.leaf_id } },
        newLeafDerivation: { signingLeaf: { leafId: l.new_leaf_id } },
      })),
      threshold: transfer.threshold,
      operatorRecipients: transfer.operator_recipients.map((o) => ({
        operatorId: o.operator_id,
        encryptionPublicKey: o.encryption_public_key,
      })),
      receiverPublicKey: transfer.receiver_public_key,
    },
  };

  // Submit stamped by passkey (send) or session (swap). Poll, if needed, with
  // the session stamper so a slow send never costs a second passkey prompt.
  const activity = await submitActivity(
    "/public/v1/submit/spark_prepare_transfer",
    {
      type: "ACTIVITY_TYPE_SPARK_PREPARE_TRANSFER",
      timestampMs: Date.now().toString(),
      organizationId: subOrgId,
      parameters,
    },
    submitStamper,
    sessionStamper,
    subOrgId,
  );

  const result = activity?.result?.sparkPrepareTransferResult;
  if (!result) throw new Error("Turnkey SPARK_PREPARE_TRANSFER returned no result");

  return {
    operator_packages: (result.operatorPackages ?? []).map(
      (p: { operatorId: string; encryptedPackage: string }) => ({
        operator_id: p.operatorId,
        encrypted_package: p.encryptedPackage,
      }),
    ),
    new_leaf_keys: (result.newLeafPublicKeys ?? []).map(
      (k: { leafId: string; publicKey: string }) => ({ leaf_id: k.leafId, public_key: k.publicKey }),
    ),
    // Turnkey returns the user signature DER-encoded; the SDK wants 64-byte
    // compact r||s.
    transfer_user_signature: derToCompactHex(result.transferUserSignature),
  };
}

// --- transport -------------------------------------------------------------

/* eslint-disable @typescript-eslint/no-explicit-any */
async function submitActivity(
  submitPath: string,
  body: Record<string, unknown>,
  submitStamper: Stamper,
  pollStamper: Stamper,
  organizationId: string,
): Promise<any> {
  const json = JSON.stringify(body);
  const { stampHeaderName, stampHeaderValue } = await submitStamper.stamp(json);
  const res = await fetch(`${API_BASE_URL}${submitPath}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", [stampHeaderName]: stampHeaderValue },
    body: json,
  });
  if (!res.ok) throw new Error(`Turnkey ${submitPath} failed: HTTP ${res.status} ${await res.text()}`);
  let activity = (await res.json())?.activity;
  if (!activity) throw new Error(`Turnkey ${submitPath}: response has no activity`);

  // Poll to a terminal status (Spark activities usually complete promptly).
  const deadline = Date.now() + 30_000;
  let delayMs = 250;
  while (activity.status !== "ACTIVITY_STATUS_COMPLETED") {
    if (
      activity.status !== "ACTIVITY_STATUS_CREATED" &&
      activity.status !== "ACTIVITY_STATUS_PENDING"
    ) {
      throw new Error(`Turnkey activity ${activity.id} ended ${activity.status}`);
    }
    if (Date.now() > deadline) throw new Error(`Turnkey activity ${activity.id} timed out`);
    await new Promise((r) => setTimeout(r, delayMs));
    delayMs = Math.min(delayMs * 2, 2_000);
    const q = JSON.stringify({ activityId: activity.id, organizationId });
    const stamp = await pollStamper.stamp(q);
    const pr = await fetch(`${API_BASE_URL}/public/v1/query/get_activity`, {
      method: "POST",
      headers: { "Content-Type": "application/json", [stamp.stampHeaderName]: stamp.stampHeaderValue },
      body: q,
    });
    if (!pr.ok) throw new Error(`Turnkey get_activity failed: HTTP ${pr.status}`);
    activity = (await pr.json())?.activity;
  }
  return activity;
}
/* eslint-enable @typescript-eslint/no-explicit-any */

// --- helpers ---------------------------------------------------------------

function derToCompactHex(derHex: string): string {
  // Normalize to low-S: Turnkey returns DER signatures with S in the upper half
  // of the curve order ~half the time, and Spark operators that enforce
  // canonical (BIP-62) low-S reject high-S, so an un-normalized signature would
  // make ~50% of client-signed sends fail intermittently.
  return secp256k1.Signature.fromDER(hexToBytes(derHex)).normalizeS().toCompactHex();
}

function base64urlToBytes(s: string): Uint8Array {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(s.length / 4) * 4, "=");
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function hexToBytes(hex: string): Uint8Array {
  const clean = hex.startsWith("0x") ? hex.slice(2) : hex;
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  return out;
}
