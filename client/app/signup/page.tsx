"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, loadCreds, saveCreds } from "../../lib/api";
import { turnkeyEnabled, registerPasskey, loginWithExistingPasskey, createSessionPublicKey } from "../../lib/turnkey";
import GlowLogo from "../../components/GlowLogo";
import { FormError } from "../../components/ui";

export default function Signup() {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [starsAnimating, setStarsAnimating] = useState(false);

  useEffect(() => {
    if (loadCreds()) router.replace("/");
  }, [router]);

  useEffect(() => {
    const t = setTimeout(() => setStarsAnimating(true), 300);
    return () => clearTimeout(t);
  }, []);

  const create = async () => {
    setBusy(true);
    setErr(null);
    try {
      // Turnkey deployments: register a passkey first — it becomes the sub-org's
      // sole root user and the only credential that can authorize a send. Name it
      // distinguishably so repeated sign-ups don't all show as identical
      // "glow-wallet" entries in the OS passkey picker.
      const passkey = turnkeyEnabled
        ? await registerPasskey(`Glow ${new Date().toISOString().slice(0, 16).replace("T", " ")}`)
        : undefined;
      // Generate the browser-held session key (no prompt) and send its public key
      // with sign-up. The server pre-authorizes it on the new wallet, so swaps
      // are stamped silently afterward without a second passkey tap — the passkey
      // prompt above (registering the passkey) is the only one. The session only
      // stamps swaps; the actual send always prompts the passkey. Once the
      // session key expires, ensureSession mints a fresh one on the next send.
      const sessionPublicKey = turnkeyEnabled ? await createSessionPublicKey() : undefined;
      const c = await api.createUser(passkey, sessionPublicKey);
      saveCreds({
        user_id: c.user_id,
        api_key: c.api_key,
        turnkey_sub_org_id: c.turnkey_sub_org_id ?? null,
        turnkey_credential_id: passkey?.attestation.credentialId ?? null,
      });
      router.replace("/");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Signup failed");
    } finally {
      setBusy(false);
    }
  };

  // Turnkey deployments: re-authenticate an existing wallet with its passkey and
  // mint a fresh api_key. Lets a returning user (cleared storage / new device)
  // get back into their wallet instead of being forced to create a new one.
  const logIn = async () => {
    setBusy(true);
    setErr(null);
    try {
      const { sessionJwt, credentialId } = await loginWithExistingPasskey();
      const c = await api.login(sessionJwt);
      saveCreds({
        user_id: c.user_id,
        api_key: c.api_key,
        turnkey_sub_org_id: c.turnkey_sub_org_id,
        // Pin future sends to the passkey this login used, so a device holding
        // several "Glow" passkeys doesn't show an unpinned picker on the next send.
        turnkey_credential_id: credentialId,
      });
      router.replace("/");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Login failed");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="h-full w-full flex flex-col bg-spark-dark relative overflow-hidden">
      {/* Animated background */}
      <div className="absolute inset-0 pointer-events-none overflow-hidden">
        <div className="absolute top-1/3 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px]">
          <div className="absolute inset-0 bg-gradient-radial from-spark-primary/25 via-spark-primary/8 to-transparent blur-3xl animate-glow-pulse" />
        </div>
        <div className="absolute top-20 right-10 w-32 h-32 bg-gradient-radial from-spark-primary/15 to-transparent blur-2xl" />
        <div className="absolute bottom-40 left-10 w-24 h-24 bg-gradient-radial from-spark-electric/10 to-transparent blur-2xl" />
        <div
          className="absolute inset-0 opacity-[0.015]"
          style={{ backgroundImage: "radial-gradient(circle at 1px 1px, rgba(255,255,255,0.4) 1px, transparent 0)", backgroundSize: "48px 48px" }}
        />
      </div>

      {/* Content */}
      <div className="flex-1 flex flex-col items-center justify-center px-6 relative z-10">
        <div className="mb-10 relative">
          <div className="absolute -inset-8 bg-gradient-radial from-spark-primary/20 via-spark-primary/5 to-transparent blur-2xl animate-glow-pulse" />
          <GlowLogo sizePx={144} starsAnimating={starsAnimating} imgClassName="drop-shadow-[0_0_24px_rgba(212,165,116,0.45)]" />
        </div>

        <h1 className="font-display text-5xl md:text-6xl font-bold text-center mb-2 tracking-tight">
          <span className="text-gradient-primary">Glow</span>
        </h1>
        <p className="text-spark-text-muted text-sm font-display text-center mb-12">Powered by Breez SDK</p>

        <div className="w-full max-w-xs space-y-4">
          <button onClick={create} disabled={busy} className="button w-full py-4 text-base tracking-wider">
            {busy ? "Creating…" : "Get Started"}
          </button>
          {turnkeyEnabled && (
            <button
              onClick={logIn}
              disabled={busy}
              className="w-full py-3 text-sm tracking-wider text-spark-text-secondary hover:text-spark-text-primary transition-colors"
            >
              Already have a wallet? Log in
            </button>
          )}
          <FormError error={err} />
          <p className="text-spark-text-muted text-xs text-center leading-relaxed">
            Creates a wallet on the server and stores the API key in this browser. Don&apos;t use this for real funds.
          </p>
        </div>
      </div>
    </div>
  );
}
