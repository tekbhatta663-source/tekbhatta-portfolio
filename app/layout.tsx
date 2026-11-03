import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  metadataBase: new URL("https://tekbhatta.vercel.app"),
  title: "TEKBHATTA — AI Learner & Aspiring Developer",
  description:
    "Portfolio of Tek Bhatta, a BBS student in Dhangadhi, Nepal, learning AI, LLMs, prompt engineering and web and app development.",
  keywords: ["Tek Bhatta", "portfolio", "Nepal", "AI learner", "prompt engineering", "BBS", "Dhangadhi"],
  authors: [{ name: "Tek Bhatta" }],
  openGraph: {
    title: "TEKBHATTA — AI Learner & Aspiring Developer",
    description:
      "An honest record of learning AI, LLMs, prompt engineering and software development from Dhangadhi, Nepal.",
    type: "website",
    locale: "en_US",
  },
  twitter: {
    card: "summary_large_image",
    title: "TEKBHATTA — AI Learner & Aspiring Developer",
    description: "An honest record of learning AI and software development from Dhangadhi, Nepal.",
  },
  robots: { index: true, follow: true },
};

export const viewport: Viewport = {
  themeColor: "#07080d",
  width: "device-width",
  initialScale: 1,
};

const themeScript = `(function(){try{var t=localStorage.getItem("tek-theme")||"dark";document.documentElement.setAttribute("data-theme",t);}catch(e){document.documentElement.setAttribute("data-theme","dark");}})();`;

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" data-theme="dark" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
        <link rel="icon" href="/favicon.svg" type="image/svg+xml" />
      </head>
      <body>
        <div className="aurora" aria-hidden="true" />
        {children}
      </body>
    </html>
  );
}
