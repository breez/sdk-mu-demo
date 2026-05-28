"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, loadCreds, type Creds, type Payment, type Prepare } from "../../lib/api";
import { useEvents } from "../../lib/events";

export default function Send() {
  const router = useRouter();
  const [creds, setCreds] = useState<Creds | null>(null);
  const [paymentRequest, setPaymentRequest] = useState("");
  const [amount, setAmount] = useState("");
  const [prepared, setPrepared] = useState<Prepare | null>(null);
  const [result, setResult] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // payment_id we're awaiting a terminal WS event for. Cleared on
  // success/failure/timeout.
  const [waitingFor, setWaitingFor] = useState<string | null>(null);

  useEffect(() => {
    const c = loadCreds();
    if (!c) router.replace("/signup");
    else setCreds(c);
  }, [router]);

  // Resolve the waiting screen from a terminal payment. Returns whether it
  // was terminal (so callers know if anything is left to wait for).
  const resolvePending = useCallback((p: Payment): boolean => {
    if (p.status !== "completed" && p.status !== "failed") return false;
    setResult(`${p.status} — payment_id: ${p.id}`);
    setWaitingFor(null);
    setBusy(false);
    return true;
  }, []);

  // REST reconcile for the awaited payment — used on (re)connect and as the
  // timeout fallback, since the events stream is best-effort.
  const reconcile = useCallback(
    async (id: string): Promise<boolean> => {
      if (!creds) return false;
      try {
        const { payment } = await api.getPayment(creds.user_id, creds.api_key, id);
        return resolvePending(payment);
      } catch {
        return false;
      }
    },
    [creds, resolvePending]
  );

  useEvents(creds, {
    // A terminal event for the awaited payment resolves the screen.
    onEvent: (e) => {
      if (
        waitingFor &&
        (e.type === "payment_succeeded" || e.type === "payment_failed") &&
        e.payment.id === waitingFor
      ) {
        resolvePending(e.payment);
      }
    },
    // Events may have been dropped while disconnected — reconcile via REST.
    onConnect: () => {
      if (waitingFor) reconcile(waitingFor);
    },
  });

  // Safety net: if neither the stream nor an onConnect reconcile resolves the
  // payment within 60s, poll once more, then stop waiting so the UI never
  // hangs on a dropped event.
  useEffect(() => {
    if (!waitingFor) return;
    const id = waitingFor;
    const timer = setTimeout(async () => {
      if (!(await reconcile(id))) {
        setResult(`still pending — check payments history (${id})`);
        setWaitingFor(null);
        setBusy(false);
      }
    }, 60_000);
    return () => clearTimeout(timer);
  }, [waitingFor, reconcile]);

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
      setPrepared(null);
      setPaymentRequest("");
      setAmount("");
      if (r.status === "completed" || r.status === "failed") {
        setResult(`${r.status} — payment_id: ${r.payment_id}`);
        setBusy(false);
      } else {
        // Pending: wait for terminal status from the events stream.
        setWaitingFor(r.payment_id);
      }
    } catch (e: any) {
      setErr(e.message ?? "send failed");
      setBusy(false);
    }
  };

  return (
    <>
      <h1>send</h1>
      {err && <div className="err">{err}</div>}
      {result && <div className="card" style={{ background: "#efe" }}>{result}</div>}
      {waitingFor && (
        <div className="card" style={{ background: "#ffe" }}>
          waiting for confirmation… <span className="mono">{waitingFor}</span>
        </div>
      )}

      {!prepared && !waitingFor && (
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
