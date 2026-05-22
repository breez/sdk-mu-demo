"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, loadCreds, type Creds, type Info, type Payment } from "../lib/api";

export default function Home() {
  const router = useRouter();
  const [creds, setCreds] = useState<Creds | null>(null);
  const [info, setInfo] = useState<Info | null>(null);
  const [recent, setRecent] = useState<Payment[]>([]);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    const c = loadCreds();
    if (!c) {
      router.replace("/signup");
      return;
    }
    setCreds(c);
    (async () => {
      try {
        const [i, p] = await Promise.all([
          api.info(c.user_id, c.api_key),
          api.listPayments(c.user_id, c.api_key, { limit: 20 }),
        ]);
        setInfo(i);
        setRecent(p.payments);
      } catch (e: any) {
        setErr(e.message ?? "failed to load");
      }
    })();
  }, [router]);

  if (!creds) return null;

  return (
    <>
      <h1 style={{ margin: "20px 0 8px" }}>balance</h1>
      {err && <div className="err">{err}</div>}
      <div className="card">
        <div style={{ fontSize: 32 }}>
          {info ? `${info.balance_sats.toLocaleString()} sats` : "…"}
        </div>
        <div className="mono" style={{ color: "#888", marginTop: 8 }}>
          {creds.user_id}
        </div>
      </div>

      <h2 style={{ margin: "20px 0 8px" }}>recent payments</h2>
      {recent.length === 0 && <p style={{ color: "#666" }}>no payments yet</p>}
      {recent.map((p) => (
        <a key={p.id} href={`/payments/${p.id}`} style={{ textDecoration: "none", color: "inherit" }}>
          <div className="row">
            <div>
              <div>{p.type === "send" ? "→" : "←"} {p.amount_sats.toLocaleString()} sats</div>
              <div className="mono" style={{ color: "#888" }}>{p.method} · {p.status}</div>
            </div>
            <div style={{ color: "#888", fontSize: 12 }}>
              {new Date(p.timestamp * 1000).toLocaleString()}
            </div>
          </div>
        </a>
      ))}
    </>
  );
}
