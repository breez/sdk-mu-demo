"use client";

import React, { useEffect } from "react";
import GlowLogo from "./GlowLogo";
import { CopyFilledIcon, LogoutIcon } from "./Icons";
import { useToast } from "../contexts/ToastContext";

interface SideMenuProps {
  isOpen: boolean;
  onClose: () => void;
  onLogout: () => void;
  userId: string;
}

const SideMenu: React.FC<SideMenuProps> = ({ isOpen, onClose, onLogout, userId }) => {
  const { showToast } = useToast();

  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [isOpen, onClose]);

  const copyId = () => {
    navigator.clipboard.writeText(userId).then(() => showToast("success", "Wallet ID copied")).catch(() => {});
  };

  return (
    <div className="fixed inset-0 z-50" style={{ pointerEvents: isOpen ? "auto" : "none" }} aria-hidden={!isOpen}>
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/60 transition-opacity duration-200"
        style={{ opacity: isOpen ? 1 : 0 }}
        onClick={onClose}
      />

      {/* Drawer */}
      <div
        className="absolute top-0 left-0 h-full w-72 max-w-[80%] bg-spark-surface border-r border-spark-border shadow-glass-lg flex flex-col transition-[transform,opacity] duration-200 ease-out"
        style={{ transform: isOpen ? "translateX(0)" : "translateX(-100%)", opacity: isOpen ? 1 : 0 }}
      >
        {/* Header */}
        <div className="px-5 pt-8 pb-6 border-b border-spark-border" style={{ paddingTop: "max(env(safe-area-inset-top, 0px), 2rem)" }}>
          <div className="flex items-center gap-3">
            <div className="relative">
              <div className="absolute -inset-2 bg-gradient-radial from-spark-primary/25 to-transparent blur-xl" />
              <GlowLogo sizePx={40} imgClassName="drop-shadow-[0_0_12px_rgba(212,165,116,0.4)]" />
            </div>
            <div>
              <h2 className="font-display text-xl font-bold text-gradient-primary leading-none">Glow</h2>
              <p className="text-spark-text-muted text-xs mt-1">Powered by Breez SDK</p>
            </div>
          </div>
        </div>

        {/* Items */}
        <nav className="flex-1 overflow-y-auto p-3 space-y-1">
          <button
            onClick={copyId}
            className="w-full flex items-center gap-3 px-3 py-3 rounded-xl text-spark-text-secondary hover:text-spark-text-primary hover:bg-white/5 transition-colors text-left"
          >
            <CopyFilledIcon className="shrink-0" />
            <span className="flex-1 min-w-0">
              <span className="block text-sm font-medium">Wallet ID</span>
              <span className="block text-xs font-mono text-spark-text-muted truncate">{userId}</span>
            </span>
          </button>
        </nav>

        {/* Footer */}
        <div className="p-3 border-t border-spark-border" style={{ paddingBottom: "max(env(safe-area-inset-bottom, 0px), 0.75rem)" }}>
          <button
            onClick={() => {
              onClose();
              onLogout();
            }}
            className="w-full flex items-center gap-3 px-3 py-3 rounded-xl text-spark-error hover:bg-spark-error/10 transition-colors text-left"
          >
            <LogoutIcon className="shrink-0" />
            <span className="text-sm font-medium">Log out</span>
          </button>
        </div>
      </div>
    </div>
  );
};

export default SideMenu;
