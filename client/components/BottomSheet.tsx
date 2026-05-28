"use client";

import React, { ReactNode, useCallback, useEffect, useRef, useState } from "react";

export interface BottomSheetContainerProps {
  isOpen: boolean;
  onClose?: () => void;
  children: ReactNode;
  showBackdrop?: boolean;
  /** Tailwind max-width class for the panel. */
  maxWidthClass?: string;
}

/**
 * Lightweight bottom sheet inspired by the Glow design system.
 * Always mounted; toggles open/closed via CSS transitions so re-opens
 * are instant. Supports drag-to-dismiss from the handle and tap-on-
 * backdrop / Escape to close.
 */
export const BottomSheetContainer: React.FC<BottomSheetContainerProps> = ({
  isOpen,
  onClose,
  children,
  showBackdrop = true,
  maxWidthClass = "max-w-lg",
}) => {
  const [dragY, setDragY] = useState(0);
  const [dragging, setDragging] = useState(false);
  const startY = useRef(0);

  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose?.();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [isOpen, onClose]);

  // Reset drag offset whenever the sheet opens.
  useEffect(() => {
    if (isOpen) setDragY(0);
  }, [isOpen]);

  const onHandleDown = useCallback((e: React.PointerEvent) => {
    if (e.button !== 0) return;
    setDragging(true);
    startY.current = e.clientY;
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
  }, []);

  const onHandleMove = useCallback(
    (e: React.PointerEvent) => {
      if (!dragging) return;
      setDragY(Math.max(0, e.clientY - startY.current));
    },
    [dragging]
  );

  const onHandleUp = useCallback(() => {
    if (!dragging) return;
    setDragging(false);
    if (dragY > 120) {
      onClose?.();
    } else {
      setDragY(0);
    }
  }, [dragging, dragY, onClose]);

  const panelStyle: React.CSSProperties = {
    transform: isOpen ? `translateY(${dragY}px)` : "translateY(100%)",
    // Fade out when closed so the card's upward box-shadow doesn't bleed
    // over the wallet's bottom action bar while the sheet sits parked
    // off-screen (it's always mounted for instant re-open).
    opacity: isOpen ? 1 : 0,
    transition: dragging
      ? "none"
      : "transform 250ms cubic-bezier(0.05, 0.7, 0.1, 1), opacity 250ms cubic-bezier(0.05, 0.7, 0.1, 1)",
  };

  return (
    <div
      className="fixed inset-0 z-50 flex flex-col justify-end"
      style={{ pointerEvents: isOpen ? "auto" : "none" }}
      aria-hidden={!isOpen}
    >
      {showBackdrop && (
        <div
          className="absolute inset-0 bg-black/60 transition-opacity duration-200"
          style={{ opacity: isOpen ? 1 : 0 }}
          onClick={onClose}
        />
      )}
      <div className={`relative mx-auto w-full ${maxWidthClass}`} style={panelStyle}>
        <BottomSheetCard onHandlePointerDown={onHandleDown} onHandlePointerMove={onHandleMove} onHandlePointerUp={onHandleUp}>
          {children}
        </BottomSheetCard>
      </div>
    </div>
  );
};

interface BottomSheetCardProps {
  children: ReactNode;
  onHandlePointerDown?: (e: React.PointerEvent) => void;
  onHandlePointerMove?: (e: React.PointerEvent) => void;
  onHandlePointerUp?: (e: React.PointerEvent) => void;
}

const BottomSheetCard: React.FC<BottomSheetCardProps> = ({
  children,
  onHandlePointerDown,
  onHandlePointerMove,
  onHandlePointerUp,
}) => (
  <div className="relative bottom-sheet-card bottom-sheet-card-bordered shadow-glass-lg overflow-hidden w-full max-h-[88dvh] flex flex-col">
    <div
      className="bottom-sheet-handle-zone shrink-0"
      style={{ touchAction: "none" }}
      onPointerDown={onHandlePointerDown}
      onPointerMove={onHandlePointerMove}
      onPointerUp={onHandlePointerUp}
      onPointerCancel={onHandlePointerUp}
    >
      <div className="bottom-sheet-handle" />
    </div>
    <div className="pt-3 flex-1 overflow-y-auto min-h-0 scrollbar-hidden">{children}</div>
  </div>
);

export default BottomSheetContainer;
