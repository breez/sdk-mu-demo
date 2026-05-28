"use client";

import React from "react";
import { formatWithSpaces } from "../lib/format";
import { MenuIcon } from "./Icons";

interface CollapsingWalletHeaderProps {
  balanceSats: number | null;
  userId: string;
  scrollProgress: number;
  onOpenMenu: () => void;
  isSyncing?: boolean;
}

const CollapsingWalletHeader: React.FC<CollapsingWalletHeaderProps> = ({
  balanceSats,
  userId,
  scrollProgress,
  onOpenMenu,
  isSyncing,
}) => {
  const balance = balanceSats ?? 0;
  const shortId = userId.length > 16 ? `${userId.slice(0, 6)}…${userId.slice(-6)}` : userId;

  return (
    <div className="relative overflow-hidden transition-all duration-200">
      {/* Glassmorphism background */}
      <div className="absolute inset-0 bg-spark-surface/80 backdrop-blur-xl border-b border-spark-border" />

      {/* Glow behind balance */}
      <div
        className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[350px] h-[200px] pointer-events-none transition-opacity duration-300"
        style={{ opacity: 1 - scrollProgress * 0.7 }}
      >
        <div className="absolute inset-0 bg-gradient-radial from-spark-primary/30 via-spark-primary/15 to-transparent blur-3xl" />
        <div className="absolute inset-4 bg-gradient-radial from-amber-400/20 to-transparent blur-2xl" />
      </div>

      <div className="relative z-10 px-4 pb-2 pt-2" style={{ paddingTop: "max(env(safe-area-inset-top, 0px), 0.5rem)" }}>
        {/* Top bar */}
        <div className="h-14 flex items-center justify-between mb-4">
          <button
            onClick={onOpenMenu}
            className="p-2 -ml-2 text-spark-text-secondary hover:text-spark-text-primary transition-colors rounded-xl hover:bg-white/5"
            aria-label="Open menu"
          >
            <MenuIcon size="lg" />
          </button>
        </div>

        {/* Balance */}
        <div className="text-center">
          <div className="relative h-4 mb-1 flex items-center justify-center">
            <span
              className={`absolute text-spark-text-muted text-xs font-display font-medium tracking-widest uppercase transition-opacity duration-300 inline-flex items-center gap-1.5 ${
                isSyncing ? "opacity-100" : "opacity-0"
              }`}
            >
              <span className="w-1.5 h-1.5 rounded-full bg-spark-primary animate-pulse" />
              Syncing
            </span>
            <span
              className={`absolute text-spark-text-muted text-xs font-display font-medium tracking-widest uppercase transition-opacity duration-300 inline-flex items-center ${
                isSyncing ? "opacity-0" : "opacity-100"
              }`}
            >
              Balance
              <span className="mx-1.5">·</span>
              <span className="px-1.5 py-0.5 rounded-full bg-white/5">sats</span>
            </span>
          </div>

          <div className="relative inline-block text-center">
            <span className="balance-display">{formatWithSpaces(balance)}</span>
            {balance > 0 && (
              <span className="absolute right-full top-1/2 -translate-y-1/2 mr-0.5 text-3xl text-spark-text-secondary opacity-70 font-mono">
                ₿
              </span>
            )}
          </div>

          {/* Secondary line — wallet id (demo has no fiat rate) */}
          <div className="mt-2 flex items-center justify-center gap-3">
            <span
              className="w-6 h-0.5 bg-spark-primary"
              style={{ maskImage: "linear-gradient(to right, transparent, black)", WebkitMaskImage: "linear-gradient(to right, transparent, black)" }}
            />
            <span className="text-spark-text-muted text-xs font-mono">{shortId}</span>
            <span
              className="w-6 h-0.5 bg-spark-primary"
              style={{ maskImage: "linear-gradient(to left, transparent, black)", WebkitMaskImage: "linear-gradient(to left, transparent, black)" }}
            />
          </div>
        </div>

        <div className="h-4" />
      </div>
    </div>
  );
};

export default CollapsingWalletHeader;
