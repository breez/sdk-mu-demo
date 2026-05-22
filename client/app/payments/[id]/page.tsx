"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { api, loadCreds, type Creds, type Payment } from "../../../lib/api";

export default function PaymentDetail() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const [creds, setCreds] = useState<Creds | null>(null);
  const [payment, setPayment] = useState<Payment | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    const c = loadCreds();
    if (!c) router.replace("/signup");
    else setCreds(c);
  }, [router]);

  useEffect(() => {
    if (!creds || !params.id) return;
    api
      .getPayment(creds.user_id, creds.api_key, params.id)
      .then((r) => setPayment(r.payment))
      .catch((e) => setErr(e.message ?? "load failed"));
  }, [creds, params.id]);

  if (!creds) return null;

  return (
    <>
      <h1>payment</h1>
      {err && <div className="err">{err}</div>}
      {!payment && !err && <p>loading…</p>}
      {payment && (
        <div className="card">
          <div className="row"><span>id</span><span className="mono">{payment.id}</span></div>
          <div className="row"><span>type</span><span>{payment.type}</span></div>
          <div className="row"><span>status</span><span>{payment.status}</span></div>
          <div className="row"><span>method</span><span>{payment.method}</span></div>
          <div className="row"><span>amount</span><span>{payment.amount_sats.toLocaleString()} sats</span></div>
          <div className="row"><span>fee</span><span>{payment.fee_sats.toLocaleString()} sats</span></div>
          <div className="row"><span>when</span><span>{new Date(payment.timestamp * 1000).toLocaleString()}</span></div>
          {payment.description && (
            <div className="row"><span>description</span><span>{payment.description}</span></div>
          )}
          {payment.invoice && (
            <div style={{ marginTop: 8 }}>
              <div style={{ color: "#888", fontSize: 13 }}>invoice</div>
              <div className="mono">{payment.invoice}</div>
            </div>
          )}
          {payment.tx_id && (
            <div style={{ marginTop: 8 }}>
              <div style={{ color: "#888", fontSize: 13 }}>tx id</div>
              <div className="mono">{payment.tx_id}</div>
            </div>
          )}
        </div>
      )}
    </>
  );
}
