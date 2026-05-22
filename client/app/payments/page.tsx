"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, loadCreds, type Creds, type Payment } from "../../lib/api";

const PAGE = 20;

export default function Payments() {
  const router = useRouter();
  const [creds, setCreds] = useState<Creds | null>(null);
  const [offset, setOffset] = useState(0);
  const [items, setItems] = useState<Payment[]>([]);
  const [nextOffset, setNextOffset] = useState<number | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const c = loadCreds();
    if (!c) router.replace("/signup");
    else setCreds(c);
  }, [router]);

  useEffect(() => {
    if (!creds) return;
    setBusy(true);
    setErr(null);
    api
      .listPayments(creds.user_id, creds.api_key, { offset, limit: PAGE })
      .then((r) => {
        setItems(r.payments);
        setNextOffset(r.next_offset);
      })
      .catch((e) => setErr(e.message ?? "load failed"))
      .finally(() => setBusy(false));
  }, [creds, offset]);

  if (!creds) return null;

  return (
    <>
      <h1>payments</h1>
      {err && <div className="err">{err}</div>}
      {!busy && items.length === 0 && <p style={{ color: "#666" }}>no payments</p>}
      {items.map((p) => (
        <a key={p.id} href={`/payments/${p.id}`} style={{ textDecoration: "none", color: "inherit" }}>
          <div className="row">
            <div>
              <div>{p.type === "send" ? "→" : "←"} {p.amount_sats.toLocaleString()} sats</div>
              <div className="mono" style={{ color: "#888" }}>{p.method} · {p.status} · {p.id}</div>
            </div>
            <div style={{ color: "#888", fontSize: 12 }}>
              {new Date(p.timestamp * 1000).toLocaleString()}
            </div>
          </div>
        </a>
      ))}
      <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
        <button disabled={offset === 0 || busy} onClick={() => setOffset(Math.max(0, offset - PAGE))}>
          prev
        </button>
        <button disabled={nextOffset == null || busy} onClick={() => nextOffset != null && setOffset(nextOffset)}>
          next
        </button>
      </div>
    </>
  );
}
