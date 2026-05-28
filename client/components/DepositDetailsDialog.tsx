"use client";

import React, { useState } from "react";
import { api, type Creds, type Deposit } from "../lib/api";
import { BottomSheetContainer } from "./BottomSheet";
import { DialogHeader, PaymentInfoCard, PaymentInfoRow, CollapsibleCodeField, Alert, PrimaryButton, ButtonSpinner } from "./ui";
import { ArrowDownIcon } from "./Icons";
import { formatWithSpaces } from "../lib/format";
import { useToast } from "../contexts/ToastContext";

interface DepositDetailsDialogProps {
  deposit: Deposit | null;
  creds: Creds;
  onClose: () => void;
  onClaimed: () => void;
}

const DepositDetailsDialog: React.FC<DepositDetailsDialogProps> = ({ deposit, creds, onClose, onClaimed }) => {
  const [showTxId, setShowTxId] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const { showToast } = useToast();

  const isOpen = deposit != null;

  const claim = async () => {
    if (!deposit) return;
    setBusy(true);
    setErr(null);
    try {
      await api.claimDeposit(creds.user_id, creds.api_key, `${deposit.txid}:${deposit.vout}`);
      showToast("success", "Deposit claimed");
      onClaimed();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Claim failed");
    } finally {
      setBusy(false);
    }
  };

  return (
    <BottomSheetContainer isOpen={isOpen} onClose={onClose}>
      {deposit && (
        <>
          <DialogHeader title="On-chain deposit" onClose={onClose} icon={<ArrowDownIcon />} />
          <div className="space-y-4 overflow-y-auto">
            <PaymentInfoCard>
              <PaymentInfoRow
                label="Amount"
                value={
                  <span className="inline-flex items-center">
                    +<span className="text-[0.8em] opacity-70 mx-px">₿</span>
                    {formatWithSpaces(deposit.amount_sats)}
                  </span>
                }
              />
              <PaymentInfoRow
                label="Status"
                value={deposit.is_mature ? "Ready to claim" : "Confirming"}
                valueColor={deposit.is_mature ? "text-spark-success" : "text-spark-warning"}
              />
              <CollapsibleCodeField
                label="Transaction ID"
                value={`${deposit.txid}:${deposit.vout}`}
                isVisible={showTxId}
                onToggle={() => setShowTxId((v) => !v)}
              />
            </PaymentInfoCard>

            {deposit.claim_error && <Alert type="error">{deposit.claim_error}</Alert>}
            {err && <Alert type="error">{err}</Alert>}

            {!deposit.is_mature && (
              <Alert type="info">This deposit is waiting for on-chain confirmations. You can claim it once it matures.</Alert>
            )}

            <PrimaryButton onClick={claim} disabled={busy || !deposit.is_mature} className="w-full">
              {busy ? <ButtonSpinner label="Claiming..." /> : "Claim deposit"}
            </PrimaryButton>
          </div>
        </>
      )}
    </BottomSheetContainer>
  );
};

export default DepositDetailsDialog;
