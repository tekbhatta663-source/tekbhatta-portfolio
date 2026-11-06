/**
 * App
 * ---
 * The only stateful React component. It owns one LiveSession instance and
 * renders nothing but the orb, a status line and a transient activity strip —
 * because Zoya has no text chat by design.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import ZoyaOrb from "./components/ZoyaOrb";
import ActivityFeed from "./components/ActivityFeed";
import FirstRun, { hasSeenIntro, markIntroSeen } from "./components/FirstRun";
import { LiveSession, type ActivityItem, type LiveEvent, type ZoyaState } from "./lib/LiveSession";
import { loadFacts } from "./lib/tools";

type Phase = "intro" | "live";

export default function App() {
  const [phase, setPhase] = useState<Phase>(() => (hasSeenIntro() ? "live" : "intro"));
  const [state, setState] = useState<ZoyaState>("disconnected");
  const [inputLevel, setInputLevel] = useState(0);
  const [outputLevel, setOutputLevel] = useState(0);
  const [items, setItems] = useState<ActivityItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [configured, setConfigured] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);

  const sessionRef = useRef<LiveSession | null>(null);
  const spectrumRef = useRef<Float32Array | null>(null);
  const voiceRef = useRef<string>("Aoede");
  const nameRef = useRef<string>("");

  // Cheap health probe so the UI can say "no key yet" instead of failing late.
  useEffect(() => {
    let cancelled = false;
    fetch("/api/health")
      .then((r) => r.json() as Promise<{ configured?: boolean }>)
      .then((d) => !cancelled && setConfigured(Boolean(d.configured)))
      .catch(() => !cancelled && setConfigured(null));
    return () => {
      cancelled = true;
    };
  }, []);

  const handleEvent = useCallback((e: LiveEvent) => {
    switch (e.type) {
      case "state":
        setState(e.state);
        if (e.state === "listening") setBusy(false);
        break;
      case "error":
        setError(e.message);
        setBusy(false);
        break;
      case "activity":
        setItems((prev) => {
          const next = [...prev, e.item];
          return next.length > 12 ? next.slice(-12) : next;
        });
        break;
      case "level":
        setInputLevel(e.input);
        setOutputLevel(e.output);
        spectrumRef.current = e.spectrum;
        break;
      case "ready":
        setError(null);
        break;
      case "turnEnd":
        break;
    }
  }, []);

  const stop = useCallback(async () => {
    const session = sessionRef.current;
    sessionRef.current = null;
    await session?.stop().catch(() => {});
    setState("disconnected");
    setBusy(false);
  }, []);

  const start = useCallback(
    async (voice: string, name: string) => {
      setError(null);
      setBusy(true);
      voiceRef.current = voice;
      nameRef.current = name;

      const facts = loadFacts();
      const factsLine = facts.length
        ? `Things you already remember about the user:\n${facts
            .map((f) => `- ${f.key}: ${f.value}`)
            .join("\n")}`
        : "You don't know anything about this user yet. Learn as you go.";

      const contextNote = [
        name ? `The user's name is ${name}.` : "",
        `Local time for the user: ${new Date().toLocaleString()}.`,
        `Their device reports the timezone ${Intl.DateTimeFormat().resolvedOptions().timeZone}.`,
        factsLine,
      ]
        .filter(Boolean)
        .join("\n");

      const session = new LiveSession({ voice, contextNote, onEvent: handleEvent });
      sessionRef.current = session;
      try {
        await session.start();
        markIntroSeen();
        setPhase("live");
      } catch {
        /* the error event already surfaced it */
        sessionRef.current = null;
      } finally {
        setBusy(false);
      }
    },
    [handleEvent],
  );

  const onOrbTap = useCallback(() => {
    const active = sessionRef.current;
    if (active) {
      // Any tap while she's connected means "shut up" -> hang up.
      void stop();
      return;
    }
    void start(voiceRef.current, nameRef.current);
  }, [start, stop]);

  // Kill the mic when the tab is hidden; leave the socket alone (it idles).
  useEffect(() => {
    const onVisibility = () => {
      if (document.hidden) {
        sessionRef.current?.setVolume(1);
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => document.removeEventListener("visibilitychange", onVisibility);
  }, []);

  useEffect(() => {
    return () => {
      void sessionRef.current?.stop();
    };
  }, []);

  const label = useMemo(() => {
    switch (state) {
      case "connecting":
        return "waking up";
      case "listening":
        return "listening";
      case "thinking":
        return "on it";
      case "speaking":
        return "speaking";
      case "error":
        return "hiccup";
      default:
        return "asleep";
    }
  }, [state]);

  const statusLine = useMemo(() => {
    if (state === "listening") {
      return inputLevel > 0.06 ? "I hear you" : "Say something";
    }
    if (state === "speaking") return "Zoya's talking";
    if (state === "thinking") return "Working on it…";
    if (state === "connecting") return "Connecting to Gemini Live…";
    if (state === "error") return "That didn't work";
    return "Tap the orb to wake her";
  }, [state, inputLevel]);

  return (
    <main className="grain relative flex h-[100dvh] w-full flex-col overflow-hidden bg-void">
      {/* ambient backdrop */}
      <div aria-hidden className="pointer-events-none absolute inset-0">
        <div className="absolute -top-1/3 left-1/2 h-[80vh] w-[80vh] -translate-x-1/2 rounded-full bg-[radial-gradient(circle,rgba(255,126,168,0.16),transparent_62%)] blur-2xl" />
        <div className="absolute -bottom-1/4 -left-1/4 h-[62vh] w-[62vh] rounded-full bg-[radial-gradient(circle,rgba(164,91,255,0.17),transparent_65%)] blur-3xl" />
        <div className="absolute -right-1/4 top-1/3 h-[52vh] w-[52vh] rounded-full bg-[radial-gradient(circle,rgba(103,232,249,0.09),transparent_65%)] blur-3xl" />
        <div className="absolute inset-0 bg-[linear-gradient(to_bottom,rgba(5,6,10,0.2),rgba(5,6,10,0.86))]" />
      </div>

      {phase === "intro" ? (
        <FirstRun
          configured={configured}
          onStart={({ voice, name }) => void start(voice, name)}
        />
      ) : (
        <>
          <ActivityFeed items={items} />

          <div className="relative z-10 flex flex-1 flex-col items-center justify-center gap-7">
            <ZoyaOrb
              state={state}
              input={inputLevel}
              output={outputLevel}
              spectrum={spectrumRef.current}
              busy={busy}
              label={label}
              onTap={onOrbTap}
            />

            <div className="flex flex-col items-center gap-1.5 px-8 text-center">
              <p
                key={statusLine}
                className="text-[13.5px] tracking-[0.01em] text-white/62"
                style={{ animation: "zoya-rise .3s ease both" }}
              >
                {statusLine}
              </p>
              {error ? (
                <p className="max-w-[34ch] text-[12px] leading-relaxed text-rose-soft/85">
                  {error}
                </p>
              ) : (
                <p className="text-[11px] tracking-[0.3em] text-white/22 uppercase">
                  {state === "disconnected" ? "no text · just voice" : "live"}
                </p>
              )}
            </div>
          </div>

          <footer className="relative z-10 flex items-center justify-between px-6 pb-[max(1.25rem,env(safe-area-inset-bottom))] text-[10.5px] tracking-[0.22em] text-white/22 uppercase">
            <span>gemini 3.1 flash live</span>
            <button
              type="button"
              onClick={() => {
                void stop();
                setPhase("intro");
              }}
              className="no-tap text-white/30 transition-colors hover:text-white/60"
            >
              settings
            </button>
          </footer>
        </>
      )}
    </main>
  );
}
