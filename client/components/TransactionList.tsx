"use client";

import React, { useMemo } from "react";
import type { Deposit, Payment } from "../lib/api";
import { formatWithCommas, formatTimeAgo } from "../lib/format";
import { ArrowDownIcon, ArrowUpIcon, LightningBoltIcon, WalletIcon } from "./Icons";

const ReceiveIcon = <ArrowDownIcon size="sm" />;
const SendIcon = <ArrowUpIcon size="sm" />;
const LightningIcon = <LightningBoltIcon size="xs" />;

function isLightning(method: string): boolean {
  return method === "bolt11" || method === "lightning";
}

function paymentDescription(p: Payment): string {
  if (p.description) return p.description;
  if (isLightning(p.method)) return p.type === "receive" ? "Lightning payment" : "Lightning payment";
  if (p.method === "onchain" || p.method === "deposit") return p.type === "receive" ? "On-chain deposit" : "On-chain withdrawal";
  return p.type === "receive" ? "Received" : "Sent";
}

const SkeletonRow: React.FC<{ index: number }> = ({ index }) => (
  <li className="flex items-center gap-3 px-3 py-3 rounded-xl animate-skeleton-item" style={{ animationDelay: `${index * 100}ms` }}>
    <div className="w-10 h-10 rounded-xl bg-spark-surface animate-pulse shrink-0" />
    <div className="flex-1 min-w-0 space-y-2">
      <div className="h-4 w-32 rounded-sm bg-spark-surface animate-pulse" />
      <div className="h-3 w-20 rounded-sm bg-spark-surface animate-pulse" />
    </div>
    <div className="h-4 w-16 rounded-sm bg-spark-surface animate-pulse shrink-0" />
  </li>
);

const SectionHeader: React.FC<{ title: string }> = ({ title }) => (
  <div className="flex items-center gap-2 mb-3">
    <h2 className="text-sm font-semibold text-spark-text-muted tracking-wide uppercase">{title}</h2>
    <div className="flex-1 h-px bg-linear-to-r from-spark-border to-transparent" />
  </div>
);

interface TransactionListProps {
  payments: Payment[];
  deposits: Deposit[];
  onPaymentSelected: (payment: Payment) => void;
  onDepositSelected: (deposit: Deposit) => void;
  isSyncing?: boolean;
}

const TransactionList: React.FC<TransactionListProps> = ({
  payments,
  deposits,
  onPaymentSelected,
  onDepositSelected,
  isSyncing,
}) => {
  const { confirming, pendingApproval } = useMemo(() => {
    const conf: Deposit[] = [];
    const pending: Deposit[] = [];
    for (const d of deposits) {
      if (d.is_mature) pending.push(d);
      else conf.push(d);
    }
    return { confirming: conf, pendingApproval: pending };
  }, [deposits]);

  const isEmpty = payments.length === 0 && deposits.length === 0;

  if (isEmpty) {
    if (isSyncing) {
      return (
        <div
          className="px-4 py-3 flex-1 overflow-hidden"
          style={{
            maskImage: "linear-gradient(to bottom, black 50%, transparent 100%)",
            WebkitMaskImage: "linear-gradient(to bottom, black 50%, transparent 100%)",
          }}
        >
          <SectionHeader title="Payments" />
          <ul className="space-y-2">
            {Array.from({ length: 12 }, (_, i) => (
              <SkeletonRow key={i} index={i} />
            ))}
          </ul>
        </div>
      );
    }
    return (
      <div className="flex flex-col items-center justify-center py-20 px-6">
        <div className="w-20 h-20 rounded-2xl bg-spark-surface border border-spark-border flex items-center justify-center mb-6">
          <WalletIcon className="w-10 h-10 text-spark-text-muted" />
        </div>
        <h3 className="text-lg font-semibold text-spark-text-primary mb-2">No payments yet</h3>
        <p className="text-spark-text-muted text-sm text-center max-w-xs">
          Your payment history will appear here once you send or receive your first payment.
        </p>
      </div>
    );
  }

  const renderPayment = (p: Payment, index: number) => {
    const isReceive = p.type === "receive";
    const isFailed = p.status === "failed";
    const isPending = !isFailed && p.status === "pending";
    return (
      <li
        key={p.id || `${p.timestamp}-${index}`}
        className="transaction-item flex items-center gap-3 px-3 py-3 rounded-xl cursor-pointer animate-list-item"
        style={{ animationDelay: `${Math.min(index * 30, 240)}ms` }}
        onClick={() => onPaymentSelected(p)}
      >
        <div
          className={`w-10 h-10 rounded-xl flex items-center justify-center shrink-0 ${
            isReceive ? "bg-spark-success/15 text-spark-success" : "bg-spark-electric/15 text-spark-electric"
          } ${isPending ? "animate-pulse" : ""}`}
        >
          {isReceive ? ReceiveIcon : SendIcon}
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-1.5">
            <p className="text-[15px] font-medium text-spark-text-primary truncate">{paymentDescription(p)}</p>
            {isLightning(p.method) && <span className="text-spark-text-muted shrink-0">{LightningIcon}</span>}
            {isPending && <span className="shrink-0 w-1.5 h-1.5 rounded-full bg-spark-warning animate-pulse" />}
            {isFailed && (
              <span className="shrink-0 px-1.5 py-0.5 rounded-sm bg-spark-error/15 text-spark-error text-[10px] font-medium uppercase">
                Failed
              </span>
            )}
          </div>
          <div className="flex items-center gap-1 text-xs text-spark-text-muted mt-0.5">
            <span>{formatTimeAgo(p.timestamp)}</span>
            {!isFailed && p.fee_sats > 0 && (
              <>
                <span>·</span>
                <span>fee {formatWithCommas(p.fee_sats)}</span>
              </>
            )}
          </div>
        </div>
        <span
          className={`font-mono font-semibold text-[15px] shrink-0 inline-flex items-center ${
            isFailed ? "text-spark-text-muted line-through" : isReceive ? "text-spark-success" : "text-spark-electric"
          }`}
        >
          {isReceive ? "+" : "-"}
          <span className="text-[0.8em] opacity-70">₿</span>
          {formatWithCommas(p.amount_sats)}
        </span>
      </li>
    );
  };

  const renderDeposit = (d: Deposit, index: number) => (
    <li
      key={`${d.txid}:${d.vout}`}
      className="transaction-item flex items-center gap-3 px-3 py-3 rounded-xl cursor-pointer animate-list-item"
      style={{ animationDelay: `${Math.min(index * 30, 240)}ms` }}
      onClick={() => onDepositSelected(d)}
    >
      <div className="w-10 h-10 rounded-xl flex items-center justify-center shrink-0 bg-spark-success/15 text-spark-success animate-pulse">
        {ReceiveIcon}
      </div>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-1.5">
          <p className="text-[15px] font-medium text-spark-text-primary truncate">On-chain deposit</p>
          <span className="shrink-0 w-1.5 h-1.5 rounded-full bg-spark-warning animate-pulse" />
        </div>
        <div className="text-xs text-spark-text-muted mt-0.5">{d.is_mature ? "Ready to claim" : "Waiting for confirmations"}</div>
      </div>
      <span className="font-mono font-semibold text-[15px] shrink-0 inline-flex items-center text-spark-success">
        +<span className="text-[0.8em] opacity-70">₿</span>
        {formatWithCommas(d.amount_sats)}
      </span>
    </li>
  );

  return (
    <div className="px-4 py-3">
      {confirming.length > 0 && (
        <>
          <SectionHeader title="Pending Confirmation" />
          <ul className="space-y-2 mb-6">{confirming.map((d, i) => renderDeposit(d, i))}</ul>
        </>
      )}
      {pendingApproval.length > 0 && (
        <>
          <SectionHeader title="Pending Approval" />
          <ul className="space-y-2 mb-6">{pendingApproval.map((d, i) => renderDeposit(d, i))}</ul>
        </>
      )}
      {payments.length > 0 && (
        <>
          <SectionHeader title="Payments" />
          <ul className="space-y-2">{payments.map((p, i) => renderPayment(p, i))}</ul>
        </>
      )}
    </div>
  );
};

export default TransactionList;
