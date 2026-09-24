import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "SYSTEM Account",
  description: "Sign in and authorize ChatGPT to access your SYSTEM tasks.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
