/**
 * FirstRun
 * --------
 * Pre-flight card. Two jobs: unlock audio with a real tap (browsers require a
 * gesture), and tell the truth about what Zoya will do with the mic. Shown
 * until the first successful session, then never again unless storage is off.
 */
import { useState } from "react";
import { ZOYA_VOICES, DEFAULT_VOICE } from "../lib/persona";

const STORAGE_KEY = "zoya.seen.v1";

export function hasSeenIntro(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

export function markIntroSeen(): void {
  try {
    localStorage.setItem(STORAGE_KEY, "1");
  } catch {
    /* private mode */
  }
}

type Props = {
  onStart: (opts: { voice: string; name: string }) => void;
  configured: boolean | null;
};

export default function FirstRun({ onStart, configured }: Props) {
  const [voice, setVoice] = useState<string>(() => {
    try {
      return localStorage.getItem("zoya.voice") ?? DEFAULT_VOICE;
    } catch {
      return DEFAULT_VOICE;
    }
  });
  const [name, setName] = useState<string>("");
  const [showVoice, setShowVoice] = useState(false);

  const begin = () => {
    try {
      localStorage.setItem("zoya.voice", voice);
    } catch {
      /* ignore */
    }
    onStart({ voice, name: name.trim() });
  };

  return (
    <div
      className="no-tap relative z-30 flex h-full w-full flex-col justify-between gap-6 overflow-y-auto px-6 pt-[max(2.5rem,env(safe-area-inset-top))] pb-[max(1.75rem,env(safe-area-inset-bottom))]"
      style={{ animation: "zoya-fade .5s ease both" }}
    >
      <header className="space-y-3">
        <p className="text-[11px] font-medium tracking-[0.42em] text-rose-soft/70 uppercase">
          voice · only
        </p>
        <h1 className="text-[clamp(2.6rem,13vw,3.6rem)] leading-[0.92] font-semibold tracking-[-0.035em]">
          Zoya
          <span className="mt-2 block text-[15px] font-normal tracking-[-0.01em] text-white/55">
            She talks. You talk. That's the whole interface.
          </span>
        </h1>
      </header>

      <div className="space-y-4">
        <ul className="space-y-2 text-[13.5px] leading-relaxed text-white/60">
          <li className="flex gap-2.5">
            <Dot /> Live audio both ways — nothing is typed, nothing is transcribed on screen.
          </li>
          <li className="flex gap-2.5">
            <Dot /> She can open sites, set timers, remember things and share text for you.
          </li>
          <li className="flex gap-2.5">
            <Dot /> Interrupt her any time. She'll stop mid-sentence.
          </li>
        </ul>

        {configured === false && (
          <p className="rounded-xl border border-rose/30 bg-rose/10 px-3.5 py-2.5 text-[12.5px] leading-relaxed text-rose-soft">
            No <code className="font-mono text-[11.5px]">GEMINI_API_KEY</code> found on the
            server. Add one to <code className="font-mono text-[11.5px]">.env.local</code> and
            restart — the UI works, but she can't connect yet.
          </p>
        )}

        <div className="space-y-2.5">
          <input
            value={name}
            onChange={(e) => setName(e.target.value.slice(0, 24))}
            placeholder="Your name (optional — she'll remember it)"
            autoComplete="given-name"
            className="w-full rounded-xl border border-white/10 bg-white/[0.04] px-3.5 py-2.5 text-[13.5px] text-white/85 outline-none placeholder:text-white/25 focus:border-rose/40"
          />

          <button
            type="button"
            onClick={() => setShowVoice((v) => !v)}
            className="no-tap flex w-full items-center justify-between rounded-xl border border-white/10 bg-white/[0.03] px-3.5 py-2.5 text-left text-[13px] text-white/60"
          >
            <span>
              Voice · <span className="text-white/85">{voice}</span>
            </span>
            <span className="text-[11px] tracking-widest text-white/35 uppercase">
              {showVoice ? "hide" : "change"}
            </span>
          </button>

          {showVoice && (
            <div className="grid grid-cols-2 gap-2" style={{ animation: "zoya-rise .25s ease both" }}>
              {ZOYA_VOICES.map((v) => (
                <button
                  key={v.id}
                  type="button"
                  onClick={() => setVoice(v.id)}
                  className={`no-tap rounded-lg border px-3 py-2 text-left transition-colors ${
                    voice === v.id
                      ? "border-rose/60 bg-rose/15 text-white"
                      : "border-white/10 bg-white/[0.02] text-white/60"
                  }`}
                >
                  <span className="block text-[12.5px] font-medium">{v.label}</span>
                  <span className="block text-[10.5px] text-white/40">{v.note}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      <button
        type="button"
        onClick={begin}
        className="no-tap group relative w-full overflow-hidden rounded-2xl bg-gradient-to-r from-rose via-[#d977ff] to-violet px-5 py-4 text-[15px] font-semibold text-[#0b0614] shadow-[0_18px_50px_-18px_rgba(255,126,168,.9)] active:scale-[0.99]"
      >
        Wake her up
        <span className="ml-2 inline-block transition-transform group-hover:translate-x-0.5">
          →
        </span>
      </button>

      <p className="text-center text-[11px] leading-relaxed text-white/28">
        Mic audio streams straight to Gemini. Nothing is stored on this server.
      </p>
    </div>
  );
}

function Dot() {
  return (
    <span className="mt-[7px] h-1 w-1 shrink-0 rounded-full bg-rose/70 shadow-[0_0_8px_rgba(255,126,168,.8)]" />
  );
}
