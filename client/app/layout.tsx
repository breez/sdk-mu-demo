import type { Metadata, Viewport } from "next";
import "./globals.css";
import { ToastProvider } from "../contexts/ToastContext";

export const metadata: Metadata = {
  title: "Glow — sdk-mu-demo",
  description: "Lightning fast Bitcoin payments powered by Breez SDK",
  icons: {
    icon: "/assets/Glow_Logo.svg",
  },
};

export const viewport: Viewport = {
  themeColor: "#0a0a0f",
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
  viewportFit: "cover",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        <link
          href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@300;400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap"
          rel="stylesheet"
        />
      </head>
      <body>
        <div className="main-wrapper">
          <div id="content-root" className="max-w-4xl mx-auto">
            <div className="app-shell">
              <div className="page-layout">
                <ToastProvider>{children}</ToastProvider>
              </div>
            </div>
          </div>
        </div>
      </body>
    </html>
  );
}
