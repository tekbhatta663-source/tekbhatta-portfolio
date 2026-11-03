"use client";

import { useEffect, useRef, useState } from "react";

type Msg = { role: "bot" | "me"; text: string };

const GREETING =
  "Hi! I'm Tek AI. I answer questions about Tek's portfolio in English, Hindi or Nepali. Ask me anything about his studies, skills or projects.";

export default function ChatWidget({ autoOpen = false }: { autoOpen?: boolean }) {
  const [open, setOpen] = useState(autoOpen);
  const [messages, setMessages] = useState<Msg[]>([{ role: "bot", text: GREETING }]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [lang, setLang] = useState("auto");
  const bodyRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const openHandler = () => setOpen(true);
    window.addEventListener("tekai:open", openHandler);
    return () => window.removeEventListener("tekai:open", openHandler);
  }, []);

  useEffect(() => {
    bodyRef.current?.scrollTo({ top: bodyRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, busy, open]);

  async function send(e: React.FormEvent) {
    e.preventDefault();
    const text = input.trim();
    if (!text || busy) return;
    setMessages((m) => [...m, { role: "me", text }]);
    setInput("");
    setBusy(true);
    try {
      const res = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: text, lang }),
      });
      const data = await res.json();
      setMessages((m) => [
        ...m,
        { role: "bot", text: data.reply || "I could not reach the assistant. Please try again." },
      ]);
    } catch {
      setMessages((m) => [
        ...m,
        { role: "bot", text: "The connection failed. You can email tekbhatta663@gmail.com instead." },
      ]);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button
        type="button"
        className="chat-fab"
        onClick={() => setOpen((v) => !v)}
        aria-label={open ? "Close Tek AI chat" : "Open Tek AI chat"}
        aria-expanded={open}
      >
        <span aria-hidden="true">{open ? "✕" : "◉"}</span>
      </button>

      {open && (
        <div className="chat-panel" role="dialog" aria-label="Tek AI assistant">
          <div className="chat-head">
            <span className="robot" aria-hidden="true">◉</span>
            <div style={{ flex: 1 }}>
              <div style={{ fontWeight: 700, fontSize: 14 }}>Tek AI</div>
              <div style={{ fontSize: 12, color: "var(--muted)", display: "flex", alignItems: "center", gap: 6 }}>
                <span className="dot" aria-hidden="true" /> Online · verified answers only
              </div>
            </div>
            <select
              aria-label="Answer language"
              value={lang}
              onChange={(e) => setLang(e.target.value)}
              style={{ width: "auto", minHeight: 36, padding: "6px 10px", fontSize: 13 }}
            >
              <option value="auto">Auto</option>
              <option value="en">English</option>
              <option value="hi">हिन्दी</option>
              <option value="ne">नेपाली</option>
            </select>
          </div>

          <div className="chat-body" ref={bodyRef}>
            {messages.map((m, i) => (
              <div key={i} className={`bubble ${m.role}`}>{m.text}</div>
            ))}
            {busy && (
              <div className="bubble bot" aria-live="polite">
                <span className="typing"><i /><i /><i /></span>
              </div>
            )}
          </div>

          <form className="chat-input" onSubmit={send}>
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="Ask me something..."
              aria-label="Your question"
              maxLength={500}
            />
            <button className="btn btn-primary" type="submit" disabled={busy || !input.trim()} style={{ padding: "0 18px" }}>
              ↑
            </button>
          </form>
        </div>
      )}
    </>
  );
}
