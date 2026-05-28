"use client";

import React, { useState } from "react";
import type { Payment } from "../lib/api";
import { BottomSheetContainer } from "./BottomSheet";
import { DialogHeader, PaymentInfoCard, PaymentInfoRow, CollapsibleCodeField } from "./ui";
import { formatWithSpaces } from "../lib/format";

interface PaymentDetailsDialogProps {
  payment: Payment | null;
  onClose: () => void;
}

function formatDateTime(timestampSecs: number): string {
  return new Date(timestampSecs * 1000).toLocaleString(undefined, {
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

const statusColor: Record<string, string> = {
  completed: "text-spark-success",
  pending: "text-spark-warning",
  failed: "text-spark-error",
};

const PaymentDetailsDialog: React.FC<PaymentDetailsDialogProps> = ({ payment, onClose }) => {
  const [showInvoice, setShowInvoice] = useState(false);
  const [showTxId, setShowTxId] = useState(false);

  const isOpen = payment != null;
  const sign = payment?.type === "receive" ? "+" : "-";
  const title = payment
    ? payment.description || (payment.type === "receive" ? "Received" : "Sent")
    : "";

  return (
    <BottomSheetContainer isOpen={isOpen} onClose={onClose}>
      {payment && (
        <>
          <DialogHeader title={title} onClose={onClose} />
          <div className="space-y-4 overflow-y-auto">
            <PaymentInfoCard>
              <PaymentInfoRow
                label="Amount"
                value={
                  <span className="inline-flex items-center">
                    {sign} <span className="text-[0.8em] opacity-70 mx-px">₿</span>
                    {formatWithSpaces(payment.amount_sats)}
                  </span>
                }
              />
              {payment.fee_sats > 0 && (
                <PaymentInfoRow
                  label="Fee"
                  value={
                    <span className="inline-flex items-center">
                      <span className="text-[0.8em] opacity-70 mr-px">₿</span>
                      {formatWithSpaces(payment.fee_sats)}
                    </span>
                  }
                />
              )}
              <PaymentInfoRow label="Status" value={payment.status} valueColor={statusColor[payment.status] ?? "text-spark-text-primary"} />
              <PaymentInfoRow label="Method" value={payment.method} />
              <PaymentInfoRow label="Date & Time" value={formatDateTime(payment.timestamp)} />
              {payment.description && <PaymentInfoRow label="Description" value={payment.description} />}
              {payment.invoice && (
                <CollapsibleCodeField label="Invoice" value={payment.invoice} isVisible={showInvoice} onToggle={() => setShowInvoice((v) => !v)} />
              )}
              {payment.tx_id && (
                <CollapsibleCodeField label="Transaction ID" value={payment.tx_id} isVisible={showTxId} onToggle={() => setShowTxId((v) => !v)} />
              )}
            </PaymentInfoCard>
          </div>
        </>
      )}
    </BottomSheetContainer>
  );
};

export default PaymentDetailsDialog;
