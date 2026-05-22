import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "sdk-mu-demo",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>
        <nav style={navStyle}>
          <a href="/" style={linkStyle}>balance</a>
          <a href="/send" style={linkStyle}>send</a>
          <a href="/receive" style={linkStyle}>receive</a>
          <a href="/payments" style={linkStyle}>payments</a>
        </nav>
        <main style={{ padding: 20, maxWidth: 720, margin: "0 auto" }}>{children}</main>
      </body>
    </html>
  );
}

const navStyle: React.CSSProperties = {
  display: "flex",
  gap: 16,
  padding: "12px 20px",
  borderBottom: "1px solid #eee",
  fontFamily: "system-ui, sans-serif",
};

const linkStyle: React.CSSProperties = {
  color: "#0070f3",
  textDecoration: "none",
};
