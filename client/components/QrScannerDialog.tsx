"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import QrScanner from "qr-scanner";
import { FloatingIconButton } from "./ui";
import { useQrScanner } from "../hooks/useQrScanner";
import { CameraFlipIcon, ImageIcon, AlertTriangleIcon } from "./Icons";

interface QrScannerDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onScan: (data: string) => void;
}

const QrScannerDialog: React.FC<QrScannerDialogProps> = ({ isOpen, onClose, onScan }) => {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [galleryError, setGalleryError] = useState<string | null>(null);

  const handleScan = useCallback(
    (data: string) => {
      onScan(data);
      onClose();
    },
    [onScan, onClose]
  );

  const handleGalleryPick = useCallback(
    async (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (!file) return;
      setGalleryError(null);
      try {
        const result = await QrScanner.scanImage(file);
        onScan(result);
        onClose();
      } catch {
        setGalleryError("No QR code found in image");
        setTimeout(() => setGalleryError(null), 3000);
      }
      if (fileInputRef.current) fileInputRef.current.value = "";
    },
    [onScan, onClose]
  );

  const {
    videoRef,
    error,
    isScanning,
    isInitializing,
    facingMode,
    hasMultipleCameras,
    startScanning,
    stopScanning,
    toggleCamera,
    clearError,
  } = useQrScanner({ onScan: handleScan });

  // Keep latest start/stop in refs so the open/close effect doesn't
  // re-run (and re-init the camera) every time those callbacks change.
  const startScanningRef = useRef(startScanning);
  const stopScanningRef = useRef(stopScanning);
  startScanningRef.current = startScanning;
  stopScanningRef.current = stopScanning;

  useEffect(() => {
    const stop = stopScanningRef.current;
    if (isOpen) {
      // Wait for the slide-up transition (300ms) plus a buffer so the
      // <video> element is laid out before the scanner attaches.
      const timer = setTimeout(() => {
        if (videoRef.current) startScanningRef.current();
      }, 400);
      return () => {
        clearTimeout(timer);
        stop();
      };
    }
    stop();
  }, [isOpen, videoRef]);

  const handleClose = () => {
    stopScanning();
    clearError();
    onClose();
  };

  return (
    <div
      className="fixed inset-0 z-[70] flex flex-col justify-end"
      style={{ pointerEvents: isOpen ? "auto" : "none" }}
      aria-hidden={!isOpen}
    >
      <div
        className="absolute inset-0 bg-black/60 transition-opacity duration-300"
        style={{ opacity: isOpen ? 1 : 0 }}
        onClick={handleClose}
      />
      <div
        className="relative w-full h-full"
        style={{
          transform: isOpen ? "translateY(0)" : "translateY(100%)",
          transition: "transform 300ms cubic-bezier(0.05, 0.7, 0.1, 1)",
        }}
      >
        <div
          className="h-full w-full bg-spark-surface flex flex-col"
          style={{
            paddingTop: "env(safe-area-inset-top, 0px)",
            paddingBottom: "env(safe-area-inset-bottom, 0px)",
          }}
        >
          {/* Camera area — the feed is constrained to a square and centered. */}
          <div className="flex-1 relative flex items-center justify-center overflow-hidden">
            <div className="relative aspect-square w-full max-h-full">
              <video
                ref={videoRef}
                className="absolute inset-0 w-full h-full object-cover"
                playsInline
                muted
                autoPlay
                poster="data:image/svg+xml;utf8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1 1'%3E%3Crect width='1' height='1' fill='%23151520'/%3E%3C/svg%3E"
                style={{
                  backgroundColor: "#151520",
                  transform: facingMode === "user" ? "scaleX(-1)" : undefined,
                }}
              />

              {/* Scan overlay */}
              <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
                <div className="w-64 h-64 relative">
                  <div className="absolute top-0 left-0 w-8 h-8 border-t-4 border-l-4 border-spark-primary rounded-tl-lg" />
                  <div className="absolute top-0 right-0 w-8 h-8 border-t-4 border-r-4 border-spark-primary rounded-tr-lg" />
                  <div className="absolute bottom-0 left-0 w-8 h-8 border-b-4 border-l-4 border-spark-primary rounded-bl-lg" />
                  <div className="absolute bottom-0 right-0 w-8 h-8 border-b-4 border-r-4 border-spark-primary rounded-br-lg" />
                  {isScanning && <div className="absolute left-2 right-2 h-0.5 bg-spark-primary animate-scan-line" />}
                </div>
              </div>

              {isInitializing && (
                <div className="absolute inset-0 flex items-center justify-center bg-spark-surface/70">
                  <div className="text-center text-white p-4">
                    <div className="animate-spin rounded-full h-10 w-10 border-2 border-spark-primary border-t-transparent mx-auto mb-3" />
                    <p className="text-sm text-spark-text-secondary">Initializing camera...</p>
                  </div>
                </div>
              )}

              {!isScanning && !isInitializing && error && (
                <div className="absolute inset-0 flex items-center justify-center bg-spark-surface/80">
                  <div className="text-center text-white p-6 max-w-xs">
                    <div className="w-16 h-16 rounded-full bg-spark-error/20 flex items-center justify-center mx-auto mb-4">
                      <AlertTriangleIcon size="xl" className="text-spark-error" />
                    </div>
                    <p className="text-sm mb-2 font-medium">Camera not available</p>
                    <p className="text-xs text-spark-text-muted">{error}</p>
                  </div>
                </div>
              )}
            </div>

            {hasMultipleCameras && (
              <FloatingIconButton
                onClick={toggleCamera}
                className="absolute top-4 left-4 z-20"
                aria-label="Switch camera"
                icon={<CameraFlipIcon />}
              />
            )}

            <FloatingIconButton
              onClick={() => fileInputRef.current?.click()}
              className="absolute top-4 right-4 z-20"
              aria-label="Pick image from gallery"
              icon={<ImageIcon />}
            />
            <input ref={fileInputRef} type="file" accept="image/*" className="hidden" onChange={handleGalleryPick} />

            {galleryError && (
              <div className="absolute top-16 left-1/2 -translate-x-1/2 bg-spark-error/90 text-white text-sm px-4 py-2 rounded-lg backdrop-blur-sm z-30">
                {galleryError}
              </div>
            )}
          </div>

          {/* Bottom controls */}
          <div className="bg-spark-surface/80 backdrop-blur-md">
            <div className="p-6">
              <p className="text-spark-text-secondary text-sm text-center mb-4">Point camera at QR code</p>
              <button
                onClick={handleClose}
                className="w-full py-3 border border-spark-border text-spark-text-primary rounded-xl font-medium hover:bg-white/10 transition-colors"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default QrScannerDialog;
