"use client";

import React, { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import type { Payment } from "../lib/api";
import GlowLogo from "./GlowLogo";

interface PaymentReceivedCelebrationProps {
  payment: Payment;
  onClose: () => void;
}

// Thin-space (U+2009) thousands separator — matches glow's celebration,
// which has no word-spacing CSS to tighten a regular space.
function formatSatsAmount(sats: number): string {
  return sats.toLocaleString("en-US").replace(/,/g, " ");
}

const PaymentReceivedCelebration: React.FC<PaymentReceivedCelebrationProps> = ({ payment, onClose }) => {
  const [isVisible, setIsVisible] = useState(false);
  const [starsAnimating, setStarsAnimating] = useState(false);

  useEffect(() => {
    requestAnimationFrame(() => setIsVisible(true));
    const starTimer = setTimeout(() => setStarsAnimating(true), 500);
    const closeTimer = setTimeout(() => {
      setIsVisible(false);
      setTimeout(onClose, 500);
    }, 4000);
    return () => {
      clearTimeout(starTimer);
      clearTimeout(closeTimer);
    };
  }, [onClose]);

  return createPortal(
    <div
      className={`fixed inset-0 z-[100] flex items-center justify-center transition-all duration-500 ${
        isVisible ? "opacity-100" : "opacity-0"
      }`}
      onClick={() => {
        setIsVisible(false);
        setTimeout(onClose, 500);
      }}
    >
      {/* Backdrop with blur */}
      <div className="absolute inset-0 bg-spark-void/90 backdrop-blur-md" />

      {/* Main content */}
      <div
        className={`relative z-10 flex flex-col items-center transform transition-all duration-700 ${
          isVisible ? "scale-100 translate-y-0" : "scale-50 translate-y-20"
        }`}
      >
        {/* Glow Logo */}
        <div className="relative mb-8">
          <div className="absolute -inset-4 rounded-full blur-2xl" style={{ background: "rgba(212,165,116,0.30)" }} />
          <div className="relative w-28 h-28 flex items-center justify-center">
            <GlowLogo sizePx={96} starsAnimating={starsAnimating} imgClassName="drop-shadow-[0_0_30px_rgba(212,165,116,0.6)]" />
          </div>
        </div>

        {/* Title */}
        <h2 className="text-2xl font-display font-bold text-spark-text-primary mb-6 animate-fade-in-up" style={{ animationDelay: "0.2s" }}>
          Payment Received
        </h2>

        {/* Amount with brand glow */}
        <div className="relative animate-fade-in-up text-center" style={{ animationDelay: "0.4s" }}>
          <div className="absolute inset-0 blur-2xl rounded-full" style={{ background: "rgba(212,165,116,0.35)" }} />
          <span className="relative inline-flex items-center gap-1 text-5xl font-mono font-bold text-spark-primary">
            <span className="text-3xl opacity-70">₿</span>
            {formatSatsAmount(payment.amount_sats)}
          </span>
        </div>

        {/* Tap to dismiss hint */}
        <p className="mt-10 text-spark-text-muted text-sm animate-fade-in-up" style={{ animationDelay: "0.6s" }}>
          Tap anywhere to dismiss
        </p>
      </div>
    </div>,
    document.body
  );
};

export default PaymentReceivedCelebration;
