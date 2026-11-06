/**
 * ActivityFeed
 * ------------
 * NOT a chat log. This is a transient "what she's doing" strip: tool calls
 * and system notes fade in above the orb and disappear. No message bubbles,
 * no transcripts, no text input anywhere — the conversation stays in the air.
 */
import { useEffect, useState } from "react";
import type { ActivityItem } from "../lib/LiveSession";

type Props = { items: ActivityItem[] };

const TTL_MS = 6500;
const MAX_VISIBLE = 3;

export default function ActivityFeed({ items }: Props) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (items.length === 0) return;
    const id = window.setInterval(() => setNow(Date.now()), 500);
    return () => window.clearInterval(id);
  }, [items.length]);

  const visible = items
    .filter((i) => now - i.at < TTL_MS)
    .slice(-MAX_VISIBLE)
    .reverse();

  if (visible.length === 0) return null;

  return (
    <div
      className="pointer-events-none absolute inset-x-0 top-[max(1rem,env(safe-area-inset-top))] z-20 flex flex-col items-center gap-2 px-5"
      aria-live="polite"
    >
      {visible.map((item, index) => (
        <div
          key={item.id}
          className="flex max-w-[92%] items-center gap-2 rounded-full border border-white/10 bg-white/[0.055] px-3.5 py-1.5 backdrop-blur-md"
          style={{
            opacity: 1 - index * 0.3,
            animation: "zoya-rise .32s cubic-bezier(.22,1,.36,1) both",
            transform: `scale(${1 - index * 0.03})`,
          }}
        >
          <span
            className={`h-1.5 w-1.5 shrink-0 rounded-full ${
              item.ok ? "bg-cyan-300/90" : "bg-rose/90"
            }`}
            style={{
              boxShadow: item.ok
                ? "0 0 10px rgba(103,232,249,.85)"
                : "0 0 10px rgba(255,126,168,.85)",
            }}
          />
          <span className="truncate text-[12.5px] leading-tight text-white/80">
            {item.label}
          </span>
        </div>
      ))}
    </div>
  );
}
