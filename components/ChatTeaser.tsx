"use client";

export default function ChatTeaser() {
  return (
    <button
      type="button"
      className="chat-teaser"
      onClick={() => window.dispatchEvent(new Event("tekai:open"))}
      aria-label="Open the Tek AI assistant"
    >
      <span className="robot" aria-hidden="true">◉</span>
      <span>
        <span className="teaser-title" style={{ display: "block" }}>Chat with Tek AI</span>
        <span className="teaser-sub">Ask in English · हिन्दी · नेपाली</span>
      </span>
    </button>
  );
}
