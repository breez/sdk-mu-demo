"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, loadCreds, saveCreds } from "../../lib/api";

export default function Signup() {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (loadCreds()) router.replace("/");
  }, [router]);

  const create = async () => {
    setBusy(true);
    setErr(null);
    try {
      const c = await api.createUser();
      saveCreds({ user_id: c.user_id, api_key: c.api_key });
      router.replace("/");
    } catch (e: any) {
      setErr(e.message ?? "signup failed");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h1>new wallet</h1>
      <p style={{ color: "#666" }}>
        Creates a wallet on the server and stores the API key in this browser.
        Don&apos;t use this for real funds.
      </p>
      {err && <div className="err">{err}</div>}
      <button onClick={create} disabled={busy}>
        {busy ? "creating…" : "create wallet"}
      </button>
    </>
  );
}
