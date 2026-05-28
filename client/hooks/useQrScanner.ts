"use client";

import { useCallback, useRef, useState } from "react";
import QrScanner from "qr-scanner";

export type FacingMode = "environment" | "user";

export interface UseQrScannerOptions {
  onScan: (data: string) => void;
  onError?: (error: string) => void;
}

export interface UseQrScannerReturn {
  videoRef: React.RefObject<HTMLVideoElement>;
  error: string | null;
  isScanning: boolean;
  isInitializing: boolean;
  facingMode: FacingMode;
  hasMultipleCameras: boolean;
  startScanning: () => Promise<void>;
  stopScanning: () => void;
  toggleCamera: () => void;
  clearError: () => void;
}

/**
 * Manages QR-code scanning via the `qr-scanner` library with camera
 * controls. Encapsulates all scanner state and lifecycle.
 */
export const useQrScanner = ({ onScan, onError }: UseQrScannerOptions): UseQrScannerReturn => {
  const videoRef = useRef<HTMLVideoElement>(null!);
  const qrScannerRef = useRef<QrScanner | null>(null);

  const [error, setError] = useState<string | null>(null);
  const [isScanning, setIsScanning] = useState(false);
  const [isInitializing, setIsInitializing] = useState(false);
  const [facingMode, setFacingMode] = useState<FacingMode>("environment");
  const [hasMultipleCameras, setHasMultipleCameras] = useState(false);

  const clearError = useCallback(() => setError(null), []);

  const stopScanning = useCallback(() => {
    if (qrScannerRef.current) {
      qrScannerRef.current.stop();
      qrScannerRef.current.destroy();
      qrScannerRef.current = null;
    }
    setIsScanning(false);
  }, []);

  const startScanning = useCallback(async () => {
    try {
      setError(null);
      setIsInitializing(true);
      setIsScanning(false);

      if (!videoRef.current) {
        const msg = "Video element not available";
        setError(msg);
        onError?.(msg);
        setIsInitializing(false);
        return;
      }

      const hasCamera = await QrScanner.hasCamera();
      if (!hasCamera) {
        const msg = "No camera found on this device";
        setError(msg);
        onError?.(msg);
        setIsInitializing(false);
        return;
      }

      qrScannerRef.current = new QrScanner(
        videoRef.current,
        (result) => {
          onScan(result.data);
          stopScanning();
        },
        {
          // Decode errors happen constantly while searching for a code; ignore.
          onDecodeError: () => {},
          // We draw our own corner brackets in QrScannerDialog; disabling the
          // library overlay avoids two overlapping squares.
          highlightScanRegion: false,
          highlightCodeOutline: false,
          preferredCamera: facingMode,
          maxScansPerSecond: 5,
        }
      );

      await qrScannerRef.current.start();
      setIsInitializing(false);
      setIsScanning(true);

      // Re-check cameras after permission is granted; the initial check may
      // return stale results before the user allowed access.
      try {
        const cameras = await QrScanner.listCameras(false);
        const uniqueIds = new Set(cameras.map((c) => c.id));
        setHasMultipleCameras(uniqueIds.size > 1);
      } catch {
        /* ignore */
      }
    } catch (err) {
      let msg = "Camera access denied or not available";
      if (err instanceof Error) {
        if (err.name === "NotAllowedError") msg = "Camera access denied. Please allow camera access and try again.";
        else if (err.name === "NotFoundError") msg = "No camera found on this device";
        else if (err.name === "NotReadableError") msg = "Camera is already in use by another application";
        else if (err.name === "OverconstrainedError") msg = "Camera constraints not supported";
      }
      setError(msg);
      onError?.(msg);
      setIsInitializing(false);
      setIsScanning(false);
    }
  }, [facingMode, onScan, onError, stopScanning]);

  const toggleCamera = useCallback(() => {
    const newMode = facingMode === "environment" ? "user" : "environment";
    setFacingMode(newMode);
    if (qrScannerRef.current) {
      qrScannerRef.current.setCamera(newMode).catch(() => {});
    }
  }, [facingMode]);

  return {
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
  };
};
