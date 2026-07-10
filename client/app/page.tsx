"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api, clearCreds, loadCreds, HttpError, type ApiError, type Creds, type Deposit, type Payment } from "../lib/api";
import { clearTurnkeySession } from "../lib/turnkey";
import { useEvents } from "../lib/events";
import CollapsingWalletHeader from "../components/CollapsingWalletHeader";
import TransactionList from "../components/TransactionList";
import SideMenu from "../components/SideMenu";
import PaymentDetailsDialog from "../components/PaymentDetailsDialog";
import DepositDetailsDialog from "../components/DepositDetailsDialog";
import SendDialog, { type AwaitPayment } from "../features/send/SendDialog";
import ReceiveDialog from "../features/receive/ReceiveDialog";
import PaymentReceivedCelebration from "../components/PaymentReceivedCelebration";
import { ArrowDownIcon, ArrowUpIcon } from "../components/Icons";

const PAGE = 50;

export default function WalletPage() {
  const router = useRouter();
  const [creds, setCreds] = useState<Creds | null>(null);
  const [balanceSats, setBalanceSats] = useState<number | null>(null);
  const [payments, setPayments] = useState<Payment[]>([]);
  const [nextOffset, setNextOffset] = useState<number | null>(null);
  const [deposits, setDeposits] = useState<Deposit[]>([]);
  const [isSyncing, setIsSyncing] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [scrollProgress, setScrollProgress] = useState(0);

  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const [isSendOpen, setIsSendOpen] = useState(false);
  const [isReceiveOpen, setIsReceiveOpen] = useState(false);
  const [sendSession, setSendSession] = useState(0);
  const [receiveSession, setReceiveSession] = useState(0);
  const [selectedPayment, setSelectedPayment] = useState<Payment | null>(null);
  const [selectedDeposit, setSelectedDeposit] = useState<Deposit | null>(null);
  const [celebrationPayment, setCelebrationPayment] = useState<Payment | null>(null);

  const scrollRef = useRef<HTMLDivElement>(null);
  // Resolvers awaiting a terminal status for a pending send.
  const pendingResolvers = useRef<Map<string, (s: "completed" | "failed") => void>>(new Map());
  // Dedupe incoming-payment celebrations: the stream is best-effort and a
  // payment_succeeded may arrive more than once across reconnects.
  const shownPaymentIds = useRef<Set<string>>(new Set());

  // Clear app creds + the Turnkey session/key and return to sign-up. Shared by
  // the menu's Logout and the auto-logout below.
  const logout = useCallback(
    async (c: Creds | null) => {
      await clearTurnkeySession(c?.turnkey_sub_org_id ?? undefined);
      clearCreds();
      router.replace("/signup");
    },
    [router]
  );

  const refetch = useCallback(
    async (c: Creds) => {
      try {
        const [info, list, dep] = await Promise.all([
          api.info(c.user_id, c.api_key),
          api.listPayments(c.user_id, c.api_key, { limit: PAGE }),
          api.listUnclaimedDeposits(c.user_id, c.api_key).catch(() => ({ deposits: [] })),
        ]);
        setBalanceSats(info.balance_sats);
        setPayments(list.payments);
        setNextOffset(list.next_offset);
        setDeposits(dep.deposits);
      } catch (e) {
        // A session from the other signer mode (e.g. a seed-mode wallet after the
        // deployment switched to turnkey) gets 409 signer_mismatch on every call
        // — these creds can never work here, so drop them and bounce to sign-up
        // for a wallet that matches this deployment.
        if (e instanceof HttpError && e.status === 409 && (e.body as ApiError)?.error?.code === "signer_mismatch") {
          await logout(c);
          return;
        }
        /* otherwise keep last known state */
      } finally {
        setIsSyncing(false);
      }
    },
    [logout]
  );

  useEffect(() => {
    const c = loadCreds();
    if (!c) {
      router.replace("/signup");
      return;
    }
    setCreds(c);
    refetch(c);
  }, [router, refetch]);

  useEvents(creds, {
    onConnect: () => {
      if (creds) refetch(creds);
    },
    onEvent: (e) => {
      if ((e.type === "payment_succeeded" || e.type === "payment_failed") && e.payment.id) {
        const resolve = pendingResolvers.current.get(e.payment.id);
        if (resolve) {
          pendingResolvers.current.delete(e.payment.id);
          resolve(e.type === "payment_succeeded" ? "completed" : "failed");
        }
      }
      // Celebrate incoming payments. Sends surface success in the Send
      // dialog's own result step, so only receives get the overlay.
      if (e.type === "payment_succeeded" && e.payment.type === "receive" && e.payment.id) {
        if (!shownPaymentIds.current.has(e.payment.id)) {
          const id = e.payment.id;
          shownPaymentIds.current.add(id);
          setTimeout(() => shownPaymentIds.current.delete(id), 30_000);
          setCelebrationPayment(e.payment);
        }
      }
      if (creds) refetch(creds);
    },
  });

  const awaitPayment = useCallback<AwaitPayment>(
    (paymentId) =>
      new Promise((resolve) => {
        const timer = setTimeout(async () => {
          pendingResolvers.current.delete(paymentId);
          if (!creds) return resolve("timeout");
          try {
            const { payment } = await api.getPayment(creds.user_id, creds.api_key, paymentId);
            if (payment.status === "completed") return resolve("completed");
            if (payment.status === "failed") return resolve("failed");
            return resolve("timeout");
          } catch {
            return resolve("timeout");
          }
        }, 60_000);
        pendingResolvers.current.set(paymentId, (status) => {
          clearTimeout(timer);
          pendingResolvers.current.delete(paymentId);
          resolve(status);
        });
      }),
    [creds]
  );

  const loadMore = async () => {
    if (!creds || nextOffset == null) return;
    setLoadingMore(true);
    try {
      const r = await api.listPayments(creds.user_id, creds.api_key, { offset: nextOffset, limit: PAGE });
      setPayments((prev) => [...prev, ...r.payments]);
      setNextOffset(r.next_offset);
    } catch {
      /* ignore */
    } finally {
      setLoadingMore(false);
    }
  };

  const handleScroll = useCallback(() => {
    if (scrollRef.current) {
      setScrollProgress(Math.min(1, scrollRef.current.scrollTop / 100));
    }
  }, []);

  const handleLogout = () => logout(creds);

  const refresh = useCallback(() => {
    if (creds) refetch(creds);
  }, [creds, refetch]);

  if (!creds) return null;

  return (
    <div className="flex flex-col h-[calc(100dvh)] relative overflow-hidden">
      {/* Atmospheric background */}
      <div className="absolute inset-0 pointer-events-none">
        <div className="absolute top-0 left-1/2 -translate-x-1/2 w-[500px] h-[300px] bg-gradient-radial from-spark-primary/15 via-spark-primary/5 to-transparent blur-3xl" />
        <div className="absolute bottom-1/4 right-0 w-[300px] h-[300px] bg-gradient-radial from-spark-primary/10 to-transparent blur-3xl" />
      </div>

      {/* Header */}
      <div className="sticky top-0 z-10">
        <CollapsingWalletHeader
          balanceSats={balanceSats}
          userId={creds.user_id}
          scrollProgress={scrollProgress}
          onOpenMenu={() => setIsMenuOpen(true)}
          isSyncing={isSyncing}
        />
      </div>

      {/* Scrollable transactions */}
      <div ref={scrollRef} className="grow overflow-y-auto relative z-0 scrollbar-hidden" onScroll={handleScroll}>
        <TransactionList
          payments={payments}
          deposits={deposits}
          isSyncing={isSyncing}
          onPaymentSelected={setSelectedPayment}
          onDepositSelected={setSelectedDeposit}
        />
        {nextOffset != null && (
          <div className="px-4 pb-4">
            <button
              onClick={loadMore}
              disabled={loadingMore}
              className="w-full py-3 rounded-xl border border-spark-border text-spark-text-secondary hover:text-spark-text-primary hover:border-spark-border-light transition-colors text-sm font-medium disabled:opacity-50"
            >
              {loadingMore ? "Loading…" : "Load more"}
            </button>
          </div>
        )}
      </div>

      {/* Bottom action bar */}
      <div className="bottom-bar flex items-center z-30">
        <button
          onClick={() => {
            setSendSession((s) => s + 1);
            setIsSendOpen(true);
          }}
          className="action-button action-button-send"
        >
          <ArrowUpIcon />
          <span>Send</span>
        </button>
        <button
          onClick={() => {
            setReceiveSession((s) => s + 1);
            setIsReceiveOpen(true);
          }}
          className="action-button action-button-receive"
        >
          <ArrowDownIcon />
          <span>Receive</span>
        </button>
      </div>

      {/* Dialogs */}
      <SendDialog
        key={`send-${sendSession}`}
        isOpen={isSendOpen}
        onClose={() => {
          setIsSendOpen(false);
          refresh();
        }}
        creds={creds}
        onSent={refresh}
        awaitPayment={awaitPayment}
      />

      <ReceiveDialog
        key={`receive-${receiveSession}`}
        isOpen={isReceiveOpen}
        onClose={() => {
          setIsReceiveOpen(false);
          refresh();
        }}
        creds={creds}
      />

      <PaymentDetailsDialog payment={selectedPayment} onClose={() => setSelectedPayment(null)} />

      <DepositDetailsDialog
        deposit={selectedDeposit}
        creds={creds}
        onClose={() => setSelectedDeposit(null)}
        onClaimed={() => {
          setSelectedDeposit(null);
          refresh();
        }}
      />

      <SideMenu isOpen={isMenuOpen} onClose={() => setIsMenuOpen(false)} onLogout={handleLogout} userId={creds.user_id} />

      {celebrationPayment && (
        <PaymentReceivedCelebration payment={celebrationPayment} onClose={() => setCelebrationPayment(null)} />
      )}
    </div>
  );
}
