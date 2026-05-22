"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, loadCreds, type Creds, type Prepare } from "../../lib/api";

export default function Send() {
  const router = useRouter();
  const [creds, setCreds] = useState<Creds | null>(null);
  const [paymentRequest, setPaymentRequest] = useState("");
  const [amount, setAmount] = useState("");
  const [prepared, setPrepared] = useState<Prepare | null>(null);
  const [result, setResult] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const c = loadCreds();
    if (!c) router.replace("/signup");
    else setCreds(c);
  }, [router]);

  if (!creds) return null;

  const doPrepare = async () => {
    setBusy(true);
    setErr(null);
    setResult(null);
    try {
      const amt = amount ? Number(amount) : undefined;
      const p = await api.prepareSend(creds.user_id, creds.api_key, {
        payment_request: paymentRequest.trim(),
        amount_sats: amt,
      });
      setPrepared(p);
    } catch (e: any) {
      setErr(e.message ?? "prepare failed");
    } finally {
      setBusy(false);
    }
  };

  const doSend = async () => {
    if (!prepared) return;
    setBusy(true);
    setErr(null);
    try {
      const r = await api.send(creds.user_id, creds.api_key, prepared.prepare_id);
      setResult(`sent — payment_id: ${r.payment_id} (${r.status})`);
      setPrepared(null);
      setPaymentRequest("");
      setAmount("");
    } catch (e: any) {
      setErr(e.message ?? "send failed");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h1>send</h1>
      {err && <div className="err">{err}</div>}
      {result && <div className="card" style={{ background: "#efe" }}>{result}</div>}

      {!prepared && (
        <>
          <label>payment request (bolt11 invoice or bitcoin address)</label>
          <textarea
            rows={3}
            value={paymentRequest}
            onChange={(e) => setPaymentRequest(e.target.value)}
            placeholder="lnbc… or bc1…"
          />
          <label style={{ marginTop: 12 }}>
            amount (sats — required for on-chain or amountless invoices)
          </label>
          <input
            type="number"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            placeholder="0"
          />
          <button style={{ marginTop: 12 }} onClick={doPrepare} disabled={busy || !paymentRequest}>
            {busy ? "preparing…" : "prepare"}
          </button>
        </>
      )}

      {prepared && (
        <div className="card">
          <h3 style={{ marginTop: 0 }}>review</h3>
          <div className="row"><span>method</span><span>{prepared.method}</span></div>
          <div className="row"><span>amount</span><span>{prepared.amount_sats.toLocaleString()} sats</span></div>
          <div className="row"><span>fee</span><span>{prepared.fee_sats.toLocaleString()} sats</span></div>
          <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
            <button onClick={doSend} disabled={busy}>{busy ? "sending…" : "confirm send"}</button>
            <button onClick={() => setPrepared(null)} disabled={busy}>cancel</button>
          </div>
        </div>
      )}
    </>
  );
}
