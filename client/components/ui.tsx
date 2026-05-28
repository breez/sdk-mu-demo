"use client";

import React, { ReactNode } from "react";
import { QRCodeSVG } from "qrcode.react";
import {
  CloseIcon,
  BackIcon,
  ChevronDownIcon,
  CopyFilledIcon,
  ShareIcon,
  ExternalLinkIcon,
  InfoIcon,
  WarningIcon,
  CheckCircleIcon,
  ErrorIcon,
  SpinnerIcon,
} from "./Icons";

// ============================================
// DIALOG
// ============================================

export const DialogHeader: React.FC<{
  title: string;
  onClose: () => void;
  onBack?: () => void;
  icon?: ReactNode;
}> = ({ title, onClose, onBack, icon }) => (
  <div className="flex justify-center items-center mb-5 relative px-8">
    {onBack && (
      <button
        onClick={onBack}
        aria-label="Back"
        className="absolute left-0 top-1/2 -translate-y-1/2 py-2 pl-6 pr-2 -ml-6 text-spark-text-muted hover:text-spark-text-primary transition-colors rounded-lg hover:bg-white/5"
      >
        <BackIcon />
      </button>
    )}
    <div className="flex items-center gap-2 min-w-0 max-w-full">
      {icon && <span className="text-spark-primary shrink-0">{icon}</span>}
      <h2 className="font-display text-lg font-bold text-spark-text-primary truncate">{title}</h2>
      {icon && <span className="w-5 h-5 shrink-0" aria-hidden="true" />}
    </div>
    <button
      onClick={onClose}
      aria-label="Close"
      className="absolute right-0 top-1/2 -translate-y-1/2 py-2 pl-2 pr-6 -mr-6 text-spark-text-muted hover:text-spark-error transition-colors rounded-lg hover:bg-white/5"
    >
      <CloseIcon />
    </button>
  </div>
);

// ============================================
// PAYMENT INFO
// ============================================

export const PaymentInfoCard: React.FC<{ children: ReactNode; className?: string }> = ({
  children,
  className = "",
}) => (
  <div className={`bg-spark-dark/50 border border-spark-border rounded-2xl p-5 space-y-1 ${className}`}>{children}</div>
);

export const PaymentInfoRow: React.FC<{
  label: string;
  value: ReactNode;
  isBold?: boolean;
  valueColor?: string;
}> = ({ label, value, isBold = false, valueColor = "text-spark-text-primary" }) => (
  <div className="flex items-center justify-between py-2 gap-3">
    <span className="text-spark-text-secondary text-sm shrink-0">{label}</span>
    <span className={`font-mono text-sm text-right break-all ${isBold ? "font-bold" : "font-medium"} ${valueColor}`}>
      {value}
    </span>
  </div>
);

export const CollapsibleSection: React.FC<{
  label: string;
  isVisible: boolean;
  onToggle: () => void;
  children: ReactNode;
}> = ({ label, isVisible, onToggle, children }) => (
  <div className="py-2">
    <button onClick={onToggle} className="flex justify-between items-center w-full text-left">
      <span className="text-spark-text-secondary text-sm">{label}</span>
      <span className="text-spark-primary hover:text-spark-primary-light flex items-center transition-colors p-1">
        <ChevronDownIcon size="md" className={`transition-transform ${isVisible ? "rotate-180" : ""}`} />
      </span>
    </button>
    {isVisible && <div className="mt-2 bg-spark-dark border border-spark-border rounded-xl p-3">{children}</div>}
  </div>
);

export const CollapsibleCodeField: React.FC<{
  label: string;
  value: string;
  isVisible: boolean;
  onToggle: () => void;
  href?: string;
}> = ({ label, value, isVisible, onToggle, href }) => (
  <CollapsibleSection label={label} isVisible={isVisible} onToggle={onToggle}>
    <div className="overflow-x-auto">
      {href ? (
        <a href={href} target="_blank" rel="noopener noreferrer" className="font-mono text-xs break-all flex items-center gap-1 group">
          <span className="text-spark-text-secondary">{value}</span>
          <ExternalLinkIcon className="w-3.5 h-3.5 shrink-0 text-spark-primary opacity-70 group-hover:opacity-100 transition-opacity" />
        </a>
      ) : (
        <code className="text-spark-text-secondary font-mono text-xs break-all">{value}</code>
      )}
    </div>
  </CollapsibleSection>
);

// ============================================
// COPYABLE TEXT
// ============================================

export const CopyableText: React.FC<{
  text: string;
  truncate?: boolean;
  showShare?: boolean;
  onCopied?: () => void;
  onShareError?: () => void;
  label?: string;
  textColor?: string;
}> = ({ text, truncate = false, showShare = false, onCopied, onShareError, label = "Address", textColor = "text-spark-text-muted" }) => {
  const [copied, setCopied] = React.useState(false);
  const [canShare] = React.useState(() => typeof navigator !== "undefined" && !!navigator.share);

  const handleCopy = () => {
    navigator.clipboard
      .writeText(text)
      .then(() => {
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
        onCopied?.();
      })
      .catch(() => {});
  };

  const handleShare = async () => {
    try {
      await navigator.share({ title: label, text });
    } catch (err) {
      if ((err as Error).name !== "AbortError") onShareError?.();
    }
  };

  const displayText = truncate && text.length > 24 ? `${text.slice(0, 12)}...${text.slice(-12)}` : text;

  return (
    <div className="flex flex-col items-center gap-4 w-full">
      <button
        onClick={handleCopy}
        className={`text-center font-mono text-xs sm:text-sm break-all hover:opacity-80 transition-opacity ${textColor}`}
        title="Tap to copy"
      >
        {displayText}
      </button>
      <div className="flex gap-2">
        <button
          onClick={handleCopy}
          className={`flex items-center gap-2 px-4 py-2 rounded-xl font-medium text-sm transition-all ${
            copied ? "bg-spark-success/20 text-spark-success" : "bg-spark-primary text-white hover:bg-spark-primary-light"
          }`}
          title={`Copy ${label}`}
        >
          <CopyFilledIcon />
          {copied ? "Copied!" : "Copy"}
        </button>
        {showShare && canShare && (
          <button
            onClick={handleShare}
            className="flex items-center gap-2 px-4 py-2 border border-spark-border text-spark-text-secondary rounded-xl font-medium text-sm hover:text-spark-text-primary hover:border-spark-border-light transition-colors"
            title={`Share ${label}`}
          >
            <ShareIcon />
            Share
          </button>
        )}
      </div>
    </div>
  );
};

// ============================================
// ALERT
// ============================================

export const Alert: React.FC<{
  type: "info" | "warning" | "success" | "error";
  children: ReactNode;
  className?: string;
}> = ({ type, children, className = "" }) => {
  const styles = {
    info: "bg-spark-electric/10 border-spark-electric/30 text-spark-electric-light",
    warning: "bg-spark-warning/10 border-spark-warning/30 text-spark-warning",
    success: "bg-spark-success/10 border-spark-success/30 text-spark-success",
    error: "bg-spark-error/10 border-spark-error/30 text-spark-error",
  };
  const icons = {
    info: <InfoIcon className="shrink-0" />,
    warning: <WarningIcon className="shrink-0" />,
    success: <CheckCircleIcon className="shrink-0" />,
    error: <ErrorIcon className="shrink-0" size="md" />,
  };
  return (
    <div className={`flex items-start gap-3 p-4 rounded-xl border ${styles[type]} ${className}`}>
      {icons[type]}
      <div className="text-sm">{children}</div>
    </div>
  );
};

export const FormError: React.FC<{ error: string | null }> = ({ error }) =>
  error ? (
    <div className="flex items-center gap-2 text-spark-error text-sm">
      <ErrorIcon size="sm" className="shrink-0" />
      <span>{error}</span>
    </div>
  ) : null;

// ============================================
// STEP + TABS
// ============================================

export const StepContainer: React.FC<{ children: ReactNode; className?: string }> = ({ children, className = "" }) => (
  <div className={`relative ${className}`} style={{ minHeight: "280px" }}>
    {children}
  </div>
);

export const TabList: React.FC<{ children: ReactNode; className?: string }> = ({ children, className = "" }) => (
  <div className={`flex bg-spark-dark/50 rounded-xl ${className}`}>{children}</div>
);

export const Tab: React.FC<{
  children: ReactNode;
  isActive: boolean;
  onClick: () => void;
}> = ({ children, isActive, onClick }) => (
  <button
    onClick={onClick}
    className={`flex-1 flex items-center justify-center gap-2 px-4 py-3 rounded-xl text-sm font-display font-semibold transition-all duration-200 ${
      isActive ? "bg-spark-primary text-black" : "text-spark-text-muted hover:text-spark-text-primary hover:bg-white/5"
    }`}
  >
    {children}
  </button>
);

// ============================================
// BUTTONS
// ============================================

export interface ButtonProps extends Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, "onClick"> {
  onClick?: () => void;
  children: ReactNode;
  className?: string;
}

export const PrimaryButton: React.FC<ButtonProps> = ({ onClick, disabled = false, children, className = "", ...props }) => (
  <button
    type="button"
    onClick={onClick}
    disabled={disabled}
    className={`button ${disabled ? "opacity-50 cursor-not-allowed" : ""} ${className}`}
    {...props}
  >
    {children}
  </button>
);

export const SecondaryButton: React.FC<ButtonProps> = ({ onClick, disabled = false, children, className = "", ...props }) => (
  <button
    type="button"
    onClick={onClick}
    disabled={disabled}
    className={`py-3 font-display font-semibold text-spark-text-secondary border border-spark-border rounded-xl hover:text-spark-text-primary hover:border-spark-border-light transition-colors disabled:opacity-50 ${className}`}
    {...props}
  >
    {children}
  </button>
);

export const ButtonSpinner: React.FC<{ label: string }> = ({ label }) => (
  <span className="flex items-center justify-center gap-2">
    <SpinnerIcon />
    {label}
  </span>
);

export interface FloatingIconButtonProps extends Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, "onClick"> {
  onClick?: () => void;
  icon: ReactNode;
  className?: string;
}

export const FloatingIconButton: React.FC<FloatingIconButtonProps> = ({ onClick, icon, className = "", ...props }) => (
  <button
    type="button"
    onClick={onClick}
    className={`p-3 rounded-full bg-black/50 hover:bg-black/70 text-white backdrop-blur-sm transition-colors border border-white/10 ${className}`}
    {...props}
  >
    {icon}
  </button>
);

// ============================================
// QR CODE
// ============================================

export const QRCodeContainer: React.FC<{ value: string; size?: number; className?: string }> = ({
  value,
  size = 200,
  className = "",
}) => (
  <div className={`relative ${className}`}>
    <div className="absolute -inset-3 pointer-events-none">
      <div className="absolute top-0 left-0 w-6 h-6 border-t-2 border-l-2 border-spark-primary/50 rounded-tl-lg" />
      <div className="absolute top-0 right-0 w-6 h-6 border-t-2 border-r-2 border-spark-primary/50 rounded-tr-lg" />
      <div className="absolute bottom-0 left-0 w-6 h-6 border-b-2 border-l-2 border-spark-primary/50 rounded-bl-lg" />
      <div className="absolute bottom-0 right-0 w-6 h-6 border-b-2 border-r-2 border-spark-primary/50 rounded-br-lg" />
    </div>
    <div className="qr-container">
      <QRCodeSVG value={value} size={size} />
    </div>
  </div>
);
