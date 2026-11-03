"use client";

import { useEffect, useState } from "react";

const ORDER = ["dark", "light", "neon"] as const;
type Theme = (typeof ORDER)[number];
const LABEL: Record<Theme, string> = { dark: "Dark", light: "Light", neon: "Neon AI" };

export default function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>("dark");
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    const saved = (localStorage.getItem("tek-theme") as Theme) || "dark";
    const next = ORDER.includes(saved) ? saved : "dark";
    setTheme(next);
    document.documentElement.setAttribute("data-theme", next);
    setMounted(true);
  }, []);

  function cycle() {
    const next = ORDER[(ORDER.indexOf(theme) + 1) % ORDER.length];
    setTheme(next);
    document.documentElement.setAttribute("data-theme", next);
    try { localStorage.setItem("tek-theme", next); } catch {}
  }

  return (
    <button
      type="button"
      className="icon-btn"
      onClick={cycle}
      aria-label={`Theme: ${LABEL[theme]}. Switch theme`}
      title={`Theme: ${LABEL[theme]}`}
    >
      <span aria-hidden="true" style={{ fontSize: 16 }}>
        {mounted && theme === "light" ? "☀" : mounted && theme === "neon" ? "✦" : "☾"}
      </span>
    </button>
  );
}
