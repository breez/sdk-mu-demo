"use client";

import React, { createContext, useCallback, useContext, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import ToastNotification, { type ToastType, type ToastAction } from "../components/ToastNotification";

interface Toast {
  id: number;
  type: ToastType;
  message: string;
  detail?: string;
  action?: ToastAction;
}

interface ToastContextValue {
  showToast: (type: ToastType, message: string, detail?: string, action?: ToastAction) => void;
}

const ToastContext = createContext<ToastContextValue>({ showToast: () => {} });

export function useToast(): ToastContextValue {
  return useContext(ToastContext);
}

let toastIdCounter = 0;

export const ToastProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [mounted, setMounted] = useState(false);

  useEffect(() => setMounted(true), []);

  const showToast = useCallback(
    (type: ToastType, message: string, detail?: string, action?: ToastAction) => {
      const id = toastIdCounter++;
      setToasts((prev) => [...prev, { id, type, message, detail, action }]);
    },
    []
  );

  const removeToast = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      {mounted &&
        createPortal(
          <div className="toast-container">
            {toasts.map((toast) => (
              <ToastNotification
                key={toast.id}
                type={toast.type}
                message={toast.message}
                detail={toast.detail}
                action={toast.action}
                onClose={() => removeToast(toast.id)}
              />
            ))}
          </div>,
          document.body
        )}
    </ToastContext.Provider>
  );
};
