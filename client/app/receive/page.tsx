"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { QRCodeSVG } from "qrcode.react";
import { api, loadCreds, type Creds } from "../../lib/api";

type Tab = "bolt11" | "onchain";

export default function Receive() {
  const router = useRouter();
  const [creds, setCreds] = useState<Creds | null>(null);
  const [tab, setTab] = useState<Tab>("bolt11");
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [paymentRequest, setPaymentRequest] = useState<string | null>(null);
  const [feeSats, setFeeSats] = useState<number | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const c = loadCreds();
    if (!c) router.replace("/signup");
    else setCreds(c);
  }, [router]);

  if (!creds) return null;

  const generate = async () => {
    setBusy(true);
    setErr(null);
    setPaymentRequest(null);
    try {
      const body: Parameters<typeof api.receive>[2] =
        tab === "bolt11"
          ? {
              method: "bolt11",
              amount_sats: amount ? Number(amount) : undefined,
              description: description || undefined,
              expiry_secs: 604_800,
            }
          : { method: "onchain" };
      const r = await api.receive(creds.user_id, creds.api_key, body);
      setPaymentRequest(r.payment_request);
      setFeeSats(r.fee_sats);
    } catch (e: any) {
      setErr(e.message ?? "receive failed");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h1>receive</h1>

      <div style={{ display: "flex", gap: 8, marginBottom: 12 }}>
        <button
          style={{ background: tab === "bolt11" ? "#0070f3" : "#fff", color: tab === "bolt11" ? "#fff" : "#111" }}
          onClick={() => { setTab("bolt11"); setPaymentRequest(null); }}
        >
          lightning (bolt11)
        </button>
        <button
          style={{ background: tab === "onchain" ? "#0070f3" : "#fff", color: tab === "onchain" ? "#fff" : "#111" }}
          onClick={() => { setTab("onchain"); setPaymentRequest(null); }}
        >
          on-chain
        </button>
      </div>

      {err && <div className="err">{err}</div>}

      {tab === "bolt11" && (
        <>
          <label>amount (sats — optional for amountless invoice)</label>
          <input type="number" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="0" />
          <label style={{ marginTop: 12 }}>description</label>
          <input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="invoice memo" />
        </>
      )}
      {tab === "onchain" && (
        <p style={{ color: "#666" }}>
          A deposit address will be generated. Send any amount; claim from the
          payments view once mature.
        </p>
      )}

      <button style={{ marginTop: 12 }} onClick={generate} disabled={busy}>
        {busy ? "generating…" : "generate"}
      </button>

      {paymentRequest && (
        <div className="card" style={{ marginTop: 16 }}>
          <div style={{ background: "#fff", padding: 16, display: "flex", justifyContent: "center" }}>
            <QRCodeSVG value={paymentRequest} size={220} />
          </div>
          <div className="mono" style={{ marginTop: 12 }}>{paymentRequest}</div>
          {feeSats != null && feeSats > 0 && (
            <div style={{ color: "#888", marginTop: 8 }}>
              fee: {feeSats.toLocaleString()} sats
            </div>
          )}
        </div>
      )}
    </>
  );
}
