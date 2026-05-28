"use client";

import React, { useState } from "react";
import dynamic from "next/dynamic";
import { api, type Creds, type Prepare } from "../../lib/api";
import { BottomSheetContainer } from "../../components/BottomSheet";
import {
  DialogHeader,
  PaymentInfoCard,
  PaymentInfoRow,
  PrimaryButton,
  SecondaryButton,
  ButtonSpinner,
  FormError,
  StepContainer,
} from "../../components/ui";
import { ArrowUpIcon, ClipboardIcon, LightningBoltIcon, CloseIcon, QrCodeIcon } from "../../components/Icons";
import GlowLogo from "../../components/GlowLogo";
import { formatWithThinSpaces } from "../../lib/format";

// Camera/QR-decoder code is client-only and a bit heavy; load it lazily.
const QrScannerDialog = dynamic(() => import("../../components/QrScannerDialog"), { ssr: false });

type Step = "input" | "review" | "processing" | "result";

export type AwaitPayment = (paymentId: string) => Promise<"completed" | "failed" | "timeout">;

interface SendDialogProps {
  isOpen: boolean;
  onClose: () => void;
  creds: Creds;
  onSent: () => void;
  awaitPayment: AwaitPayment;
  /** Bumped by parent to remount with fresh state on each open. */
  initialInput?: string;
}

const ProcessingView: React.FC = () => (
  <div className="flex flex-col items-center justify-center py-12">
    <div className="relative mb-8">
      <div className="relative w-24 h-24 flex items-center justify-center">
        <span className="absolute inset-0 w-full h-full animate-spin" style={{ animationDuration: "3s" }}>
          <svg className="w-full h-full" viewBox="0 0 100 100">
            <circle cx="50" cy="50" r="46" fill="none" stroke="url(#send-processing-gradient)" strokeWidth="3" strokeLinecap="round" strokeDasharray="80 200" />
            <defs>
              <linearGradient id="send-processing-gradient" x1="0%" y1="0%" x2="100%" y2="0%">
                <stop offset="0%" stopColor="#d4a574" />
                <stop offset="100%" stopColor="#d4a574" stopOpacity="0" />
              </linearGradient>
            </defs>
          </svg>
        </span>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/assets/Glow_Logo.svg" alt="Processing" className="w-14 h-14 object-contain animate-pulse drop-shadow-[0_0_15px_rgba(212,165,116,0.4)]" style={{ animationDuration: "2s" }} />
      </div>
    </div>
    <h3 className="font-display text-xl font-semibold text-spark-text-primary mb-2">Sending...</h3>
    <p className="text-spark-text-secondary text-sm text-center max-w-xs">Please wait while we process your transaction...</p>
    <div className="flex gap-1.5 mt-6">
      {[0, 1, 2].map((i) => (
        <div key={i} className="w-2 h-2 rounded-full bg-spark-primary" style={{ animation: "bounce 1s ease-in-out infinite", animationDelay: `${i * 0.15}s` }} />
      ))}
    </div>
  </div>
);

const ResultView: React.FC<{ success: boolean; message: string; onClose: () => void }> = ({ success, message, onClose }) => (
  <div className="flex flex-col items-center justify-center py-4">
    <div className="relative mb-6">
      {success ? (
        <>
          <div className="absolute -inset-3 rounded-full blur-xl" style={{ background: "rgba(212,165,116,0.20)" }} />
          <div className="relative w-20 h-20 flex items-center justify-center">
            <GlowLogo sizePx={64} starsAnimating imgClassName="drop-shadow-[0_0_20px_rgba(212,165,116,0.5)]" />
          </div>
        </>
      ) : (
        <>
          <div className="absolute inset-0 w-20 h-20 rounded-full blur-xl bg-spark-error/30" />
          <div className="relative w-20 h-20 rounded-full flex items-center justify-center bg-spark-error/20 border-2 border-spark-error">
            <CloseIcon className="w-10 h-10 text-spark-error" />
          </div>
        </>
      )}
    </div>
    <h3 className={`font-display text-2xl font-bold mb-2 ${success ? "text-spark-primary" : "text-spark-error"}`}>
      {success ? "Payment Sent!" : "Payment Failed"}
    </h3>
    <p className="text-spark-text-secondary text-center max-w-xs mb-8">{message}</p>
    <PrimaryButton onClick={onClose} className="min-w-[200px]">
      Done
    </PrimaryButton>
  </div>
);

const SendDialog: React.FC<SendDialogProps> = ({ isOpen, onClose, creds, onSent, awaitPayment, initialInput = "" }) => {
  const [step, setStep] = useState<Step>("input");
  const [paymentRequest, setPaymentRequest] = useState(initialInput);
  const [isScannerOpen, setIsScannerOpen] = useState(false);
  const [amount, setAmount] = useState("");
  const [prepared, setPrepared] = useState<Prepare | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [resultSuccess, setResultSuccess] = useState(false);
  const [resultMessage, setResultMessage] = useState("");

  const doPrepare = async () => {
    setBusy(true);
    setErr(null);
    try {
      const amt = amount ? Number(amount) : undefined;
      const p = await api.prepareSend(creds.user_id, creds.api_key, {
        payment_request: paymentRequest.trim(),
        amount_sats: amt,
      });
      setPrepared(p);
      setStep("review");
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Prepare failed");
    } finally {
      setBusy(false);
    }
  };

  const doSend = async () => {
    if (!prepared) return;
    setStep("processing");
    setErr(null);
    try {
      const r = await api.send(creds.user_id, creds.api_key, prepared.prepare_id);
      let status: "completed" | "failed" | "timeout" = r.status as "completed" | "failed" | "timeout";
      if (r.status !== "completed" && r.status !== "failed") {
        status = await awaitPayment(r.payment_id);
      }
      onSent();
      if (status === "completed") {
        setResultSuccess(true);
        setResultMessage("Your payment has been successfully sent to the recipient.");
      } else if (status === "failed") {
        setResultSuccess(false);
        setResultMessage("There was an error processing your payment. Please try again.");
      } else {
        setResultSuccess(false);
        setResultMessage("Payment is still pending — check your payment history in a moment.");
      }
      setStep("result");
    } catch (e) {
      onSent();
      setResultSuccess(false);
      setResultMessage(e instanceof Error ? e.message : "Send failed");
      setStep("result");
    }
  };

  const handlePaste = async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (text?.trim()) setPaymentRequest(text.trim());
    } catch {
      /* clipboard unavailable */
    }
  };

  const total = prepared ? prepared.amount_sats + prepared.fee_sats : 0;

  return (
    <>
    <BottomSheetContainer isOpen={isOpen} onClose={onClose} showBackdrop>
      <DialogHeader title="Send" onClose={onClose} icon={<ArrowUpIcon />} />
      <StepContainer>
        {step === "input" && (
          <div className="flex flex-col gap-4 pt-2">
            <div>
              <label className="block text-sm font-medium text-spark-text-primary mb-2">Payment request</label>
              <textarea
                rows={3}
                value={paymentRequest}
                onChange={(e) => setPaymentRequest(e.target.value)}
                placeholder="lnbc… (invoice) or bc1… (bitcoin address)"
                spellCheck={false}
                autoCapitalize="none"
                autoCorrect="off"
                className="w-full font-mono text-sm resize-none"
              />
            </div>

            <div className="flex gap-2">
              <button
                onClick={handlePaste}
                className="flex-1 flex items-center justify-center gap-1.5 py-2.5 bg-spark-surface border border-spark-border rounded-xl text-spark-text-secondary hover:text-spark-text-primary hover:border-spark-border-light transition-colors"
              >
                <ClipboardIcon size="xs" />
                <span className="text-sm font-medium">Paste</span>
              </button>
              <button
                onClick={() => setIsScannerOpen(true)}
                className="flex-1 flex items-center justify-center gap-1.5 py-2.5 bg-spark-surface border border-spark-border rounded-xl text-spark-text-secondary hover:text-spark-text-primary hover:border-spark-border-light transition-colors"
              >
                <QrCodeIcon size="xs" />
                <span className="text-sm font-medium">Scan</span>
              </button>
            </div>

            <div>
              <label className="block text-sm font-medium text-spark-text-primary mb-2">
                Amount <span className="text-spark-text-muted font-normal">(sats — for on-chain or amountless invoices)</span>
              </label>
              <input
                type="number"
                inputMode="numeric"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                placeholder="0"
                className="font-mono"
              />
            </div>

            <FormError error={err} />

            <PrimaryButton onClick={doPrepare} disabled={busy || !paymentRequest.trim()} className="w-full">
              {busy ? <ButtonSpinner label="Preparing..." /> : "Continue"}
            </PrimaryButton>
          </div>
        )}

        {step === "review" && prepared && (
          <div className="space-y-6 pt-2">
            <div className="text-center py-4">
              <p className="text-spark-text-muted text-sm mb-2">You&apos;re sending</p>
              <div className="flex items-baseline justify-center gap-2">
                <span className="text-4xl font-mono font-bold text-spark-text-primary inline-flex items-center">
                  <span className="text-[0.8em] opacity-70 mr-px">₿</span>
                  {formatWithThinSpaces(total)}
                </span>
              </div>
            </div>

            <PaymentInfoCard>
              <PaymentInfoRow label="Method" value={prepared.method} />
              <PaymentInfoRow
                label="Amount"
                value={
                  <span className="inline-flex items-center">
                    <span className="text-[0.8em] opacity-70 mr-px">₿</span>
                    {formatWithThinSpaces(prepared.amount_sats)}
                  </span>
                }
              />
              <PaymentInfoRow
                label="Network fee"
                value={
                  <span className="inline-flex items-center">
                    <span className="text-[0.8em] opacity-70 mr-px">₿</span>
                    {formatWithThinSpaces(prepared.fee_sats)}
                  </span>
                }
              />
            </PaymentInfoCard>

            <FormError error={err} />

            <div className="flex gap-3">
              <SecondaryButton onClick={() => setStep("input")} className="flex-1">
                Back
              </SecondaryButton>
              <PrimaryButton onClick={doSend} className="flex-1">
                <span className="flex items-center justify-center gap-2">
                  <LightningBoltIcon size="sm" />
                  Send
                </span>
              </PrimaryButton>
            </div>
          </div>
        )}

        {step === "processing" && <ProcessingView />}

        {step === "result" && <ResultView success={resultSuccess} message={resultMessage} onClose={onClose} />}
      </StepContainer>
    </BottomSheetContainer>

      <QrScannerDialog
        isOpen={isScannerOpen}
        onClose={() => setIsScannerOpen(false)}
        onScan={(data) => {
          setPaymentRequest(data.trim());
          setErr(null);
        }}
      />
    </>
  );
};

export default SendDialog;
