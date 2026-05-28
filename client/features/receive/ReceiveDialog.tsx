"use client";

import React, { useEffect, useState } from "react";
import { api, type Creds } from "../../lib/api";
import { BottomSheetContainer } from "../../components/BottomSheet";
import {
  DialogHeader,
  TabList,
  Tab,
  StepContainer,
  QRCodeContainer,
  CopyableText,
  Alert,
  PrimaryButton,
  ButtonSpinner,
  FormError,
} from "../../components/ui";
import LoadingSpinner from "../../components/LoadingSpinner";
import { ArrowDownIcon, LightningBoltIcon } from "../../components/Icons";
import { useToast } from "../../contexts/ToastContext";

type ReceiveTab = "lightning" | "bitcoin";

interface ReceiveDialogProps {
  isOpen: boolean;
  onClose: () => void;
  creds: Creds;
}

interface Generated {
  paymentRequest: string;
  feeSats: number;
}

const ReceiveDialog: React.FC<ReceiveDialogProps> = ({ isOpen, onClose, creds }) => {
  const { showToast } = useToast();
  const [tab, setTab] = useState<ReceiveTab>("lightning");

  // Lightning invoice flow
  const [lnStep, setLnStep] = useState<"input" | "loading" | "qr">("input");
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [lnResult, setLnResult] = useState<Generated | null>(null);
  const [lnError, setLnError] = useState<string | null>(null);

  // Bitcoin address flow
  const [btcAddress, setBtcAddress] = useState<string | null>(null);
  const [btcLoading, setBtcLoading] = useState(false);
  const [btcError, setBtcError] = useState<string | null>(null);

  const generateInvoice = async () => {
    setLnStep("loading");
    setLnError(null);
    try {
      const r = await api.receive(creds.user_id, creds.api_key, {
        method: "bolt11",
        amount_sats: amount ? Number(amount) : undefined,
        description: description || undefined,
        expiry_secs: 604_800,
      });
      setLnResult({ paymentRequest: r.payment_request, feeSats: r.fee_sats });
      setLnStep("qr");
    } catch (e) {
      setLnError(e instanceof Error ? e.message : "Failed to create invoice");
      setLnStep("input");
    }
  };

  const generateAddress = async () => {
    setBtcLoading(true);
    setBtcError(null);
    try {
      const r = await api.receive(creds.user_id, creds.api_key, { method: "onchain" });
      setBtcAddress(r.payment_request);
    } catch (e) {
      setBtcError(e instanceof Error ? e.message : "Failed to generate address");
    } finally {
      setBtcLoading(false);
    }
  };

  // Auto-generate a bitcoin address when switching to that tab.
  useEffect(() => {
    if (isOpen && tab === "bitcoin" && !btcAddress && !btcLoading && !btcError) {
      generateAddress();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, tab, btcAddress, btcLoading, btcError]);

  return (
    <BottomSheetContainer isOpen={isOpen} onClose={onClose} showBackdrop>
      <DialogHeader title="Receive" onClose={onClose} icon={<ArrowDownIcon />} />

      <TabList>
        <Tab isActive={tab === "lightning"} onClick={() => setTab("lightning")}>
          <LightningBoltIcon size="sm" />
          Lightning
        </Tab>
        <Tab isActive={tab === "bitcoin"} onClick={() => setTab("bitcoin")}>
          <span className="font-bold text-sm">₿</span>
          Bitcoin
        </Tab>
      </TabList>

      <StepContainer>
        {tab === "lightning" && lnStep === "input" && (
          <div className="pt-6 space-y-5">
            <div>
              <label className="block text-sm font-medium text-spark-text-primary mb-2">Amount (sats)</label>
              <input
                type="number"
                inputMode="numeric"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                placeholder="Optional — amountless invoice"
                className="font-mono"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-spark-text-primary mb-2">Description</label>
              <input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Invoice memo (optional)" />
            </div>
            <FormError error={lnError} />
            <PrimaryButton onClick={generateInvoice} className="w-full">
              Create invoice
            </PrimaryButton>
          </div>
        )}

        {tab === "lightning" && lnStep === "loading" && (
          <div className="flex flex-col items-center justify-center h-40">
            <LoadingSpinner text="Generating invoice..." />
          </div>
        )}

        {tab === "lightning" && lnStep === "qr" && lnResult && (
          <div className="pt-8 space-y-6 flex flex-col items-center">
            <div className="text-center">
              <h3 className="text-lg font-medium text-spark-text-primary mb-2">Lightning Invoice</h3>
              <p className="text-spark-text-secondary text-sm">Scan to pay this Lightning invoice</p>
            </div>
            <QRCodeContainer value={lnResult.paymentRequest} />
            <div className="w-full">
              <CopyableText
                text={lnResult.paymentRequest}
                truncate
                showShare
                label="Lightning Invoice"
                onCopied={() => showToast("success", "Copied!")}
                onShareError={() => showToast("error", "Failed to share")}
              />
              {lnResult.feeSats > 0 && (
                <Alert type="warning" className="mt-8">
                  <div className="text-center">A fee of ₿{lnResult.feeSats.toLocaleString()} is applied to this transaction.</div>
                </Alert>
              )}
            </div>
          </div>
        )}

        {tab === "bitcoin" && (
          <div className="pt-8">
            {btcLoading || (!btcAddress && !btcError) ? (
              <div className="text-center py-8">
                <LoadingSpinner text="Generating Bitcoin address..." />
              </div>
            ) : btcError ? (
              <div className="space-y-5">
                <FormError error={btcError} />
                <PrimaryButton onClick={generateAddress} className="w-full">
                  {btcLoading ? <ButtonSpinner label="Generating..." /> : "Try again"}
                </PrimaryButton>
              </div>
            ) : btcAddress ? (
              <div className="flex flex-col items-center gap-6">
                <div className="text-center">
                  <h3 className="text-lg font-medium text-spark-text-primary mb-2">Bitcoin Address</h3>
                  <p className="text-spark-text-secondary text-sm">Send Bitcoin here, then claim it once it confirms</p>
                </div>
                <QRCodeContainer value={btcAddress} />
                <div className="w-full flex flex-col items-center gap-4">
                  <CopyableText
                    text={btcAddress}
                    truncate
                    showShare
                    label="Bitcoin Address"
                    onCopied={() => showToast("success", "Copied!")}
                    onShareError={() => showToast("error", "Failed to share")}
                  />
                  <div className="mt-2 h-6" aria-hidden="true" />
                </div>
              </div>
            ) : null}
          </div>
        )}
      </StepContainer>
    </BottomSheetContainer>
  );
};

export default ReceiveDialog;
