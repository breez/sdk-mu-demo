"use client";

import { useEffect, useRef } from "react";
import type { Creds, Deposit, Payment } from "./api";

const BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export type EventEnvelope =
  | { type: "payment_succeeded"; payment: Payment }
  | { type: "payment_pending"; payment: Payment }
  | { type: "payment_failed"; payment: Payment }
  | { type: "new_deposits"; deposits: Deposit[] }
  | { type: "claimed_deposits"; deposits: Deposit[] }
  | { type: "unclaimed_deposits"; deposits: Deposit[] };

type Handlers = {
  onEvent: (e: EventEnvelope) => void;
  // Fires after each successful (re)connect. Best place to refetch REST
  // state — the stream is best-effort and may have dropped events while
  // we were disconnected.
  onConnect?: () => void;
};

function wsUrl(creds: Creds): string {
  // http→ws, https→wss; same host/port as the REST base.
  const base = BASE_URL.replace(/^http/, "ws");
  return `${base}/users/${creds.user_id}/events?api_key=${encodeURIComponent(
    creds.api_key
  )}`;
}

/**
 * Subscribes to `/users/{id}/events`. Reconnects with exponential backoff
 * (1s → 30s cap). On (re)connect, calls `onConnect` so the caller can
 * reconcile via REST. Events are delivered best-effort; the bus drops
 * oldest on overflow and the server can close the WS with 1008 if the
 * outbox fills.
 */
export function useEvents(creds: Creds | null, handlers: Handlers) {
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  useEffect(() => {
    if (!creds) return;
    let ws: WebSocket | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let backoffMs = 1000;
    let stopped = false;

    const connect = () => {
      ws = new WebSocket(wsUrl(creds));
      ws.onopen = () => {
        backoffMs = 1000;
        handlersRef.current.onConnect?.();
      };
      ws.onmessage = (m) => {
        try {
          const e = JSON.parse(m.data) as EventEnvelope;
          handlersRef.current.onEvent(e);
        } catch {
          // malformed frame — ignore
        }
      };
      ws.onclose = () => {
        if (stopped) return;
        timer = setTimeout(connect, backoffMs);
        backoffMs = Math.min(backoffMs * 2, 30_000);
      };
      ws.onerror = () => {
        try {
          ws?.close();
        } catch {
          // close raised — onclose will still fire
        }
      };
    };

    connect();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      try {
        ws?.close();
      } catch {
        // ignore
      }
    };
  }, [creds]);
}
