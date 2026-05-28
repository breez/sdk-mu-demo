import React from "react";

// Centralized icon components, ported from the Glow design system.

export interface IconProps {
  className?: string;
  size?: "xs" | "sm" | "md" | "lg" | "xl";
}

const sizeClasses = {
  xs: "w-3 h-3",
  sm: "w-4 h-4",
  md: "w-5 h-5",
  lg: "w-6 h-6",
  xl: "w-8 h-8",
};

export const CloseIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
  </svg>
);

export const BackIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
  </svg>
);

export const ChevronDownIcon: React.FC<IconProps> = ({ className = "", size = "sm" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
  </svg>
);

export const CheckIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
  </svg>
);

export const CheckCircleIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="currentColor" viewBox="0 0 20 20">
    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
  </svg>
);

export const ErrorIcon: React.FC<IconProps> = ({ className = "", size = "sm" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="currentColor" viewBox="0 0 20 20">
    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clipRule="evenodd" />
  </svg>
);

export const InfoIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="currentColor" viewBox="0 0 20 20">
    <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clipRule="evenodd" />
  </svg>
);

export const WarningIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} viewBox="0 0 20 20" fill="currentColor">
    <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.72-1.36 3.485 0l6.518 11.6c.75 1.336-.213 3.001-1.742 3.001H3.48c-1.53 0-2.492-1.665-1.742-3.001l6.52-11.6zM11 13a1 1 0 10-2 0 1 1 0 002 0zm-1-2a1 1 0 01-1-1V7a1 1 0 112 0v3a1 1 0 01-1 1z" clipRule="evenodd" />
  </svg>
);

export const AlertTriangleIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
  </svg>
);

export const SpinnerIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <span className={`${sizeClasses[size]} animate-spin ${className}`}>
    <svg className="w-full h-full" fill="none" viewBox="0 0 24 24">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
    </svg>
  </span>
);

export const MenuIcon: React.FC<IconProps> = ({ className = "", size = "lg" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
  </svg>
);

export const ArrowUpIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M7 11l5-5m0 0l5 5m-5-5v12" />
  </svg>
);

export const ArrowDownIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M17 13l-5 5m0 0l-5-5m5 5V6" />
  </svg>
);

export const ExternalLinkIcon: React.FC<IconProps> = ({ className = "", size = "xs" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
  </svg>
);

export const LogoutIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
  </svg>
);

export const ShareIcon: React.FC<IconProps> = ({ className = "", size = "sm" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="currentColor" viewBox="0 0 20 20">
    <path d="M15 8a3 3 0 10-2.977-2.63l-4.94 2.47a3 3 0 100 4.319l4.94 2.47a3 3 0 10.895-1.789l-4.94-2.47a3.027 3.027 0 000-.74l4.94-2.47C13.456 7.68 14.19 8 15 8z" />
  </svg>
);

export const LightningBoltIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} viewBox="0 0 24 24" fill="currentColor">
    <path d="M13 3L4 14h7l-2 7 9-11h-7l2-7z" />
  </svg>
);

export const WalletIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
  </svg>
);

export const QrCodeIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} viewBox="0 0 24 24" fill="currentColor">
    <path d="M3 11h8V3H3v8zm2-6h4v4H5V5zM3 21h8v-8H3v8zm2-6h4v4H5v-4zM13 3v8h8V3h-8zm6 6h-4V5h4v4zM19 13h2v2h-2zM13 13h2v2h-2zM15 15h2v2h-2zM13 17h2v2h-2zM15 19h2v2h-2zM17 17h2v2h-2zM17 13h2v2h-2zM19 15h2v2h-2zM19 19h2v2h-2z" />
  </svg>
);

export const CameraFlipIcon: React.FC<IconProps> = ({ className = "", size = "lg" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
  </svg>
);

export const ImageIcon: React.FC<IconProps> = ({ className = "", size = "lg" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
  </svg>
);

export const ExclamationIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01" />
  </svg>
);

export const InfoCircleIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
  </svg>
);

export const ClipboardIcon: React.FC<IconProps> = ({ className = "", size = "md" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
    <path strokeLinecap="round" strokeLinejoin="round" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
  </svg>
);

export const CopyFilledIcon: React.FC<IconProps> = ({ className = "", size = "sm" }) => (
  <svg className={`${sizeClasses[size]} ${className}`} fill="currentColor" viewBox="0 0 20 20">
    <path d="M8 2a2 2 0 00-2 2v1H5a2 2 0 00-2 2v7a2 2 0 002 2h6a2 2 0 002-2v-1h1a2 2 0 002-2V6l-4-4H8zm6 6h-2a2 2 0 01-2-2V4H8v1h3a1 1 0 011 1v2h2v2z" />
  </svg>
);
