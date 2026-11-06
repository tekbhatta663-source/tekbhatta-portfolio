/**
 * Zoya's tools.
 * -------------
 * One declarative list -> Gemini function declarations, a client executor and
 * a human-readable activity feed. Adding a capability means adding one entry
 * here (plus a case in `executeTool`).
 *
 * Everything in this file runs in the browser. The only tool that needs the
 * server is `openWebsite`, because a popup blocker will eat window.open()
 * calls that are not in a user gesture.
 */
/**
 * OpenAPI type names as bare strings. Declared locally (instead of importing
 * the SDK's `Type` enum) so this module has no runtime dependency on
 * @google/genai — the SDK is then free to load lazily on the first orb tap.
 */
const Type = {
  STRING: "STRING",
  NUMBER: "NUMBER",
  INTEGER: "INTEGER",
  BOOLEAN: "BOOLEAN",
  ARRAY: "ARRAY",
  OBJECT: "OBJECT",
} as const;

/** Just the slice of the SDK's FunctionDeclaration shape we actually build. */
export type FunctionDeclaration = {
  name: string;
  description: string;
  parameters?: {
    type: (typeof Type)[keyof typeof Type];
    properties?: Record<string, unknown>;
    required?: string[];
  };
};

export type ToolName =
  | "openWebsite"
  | "searchWeb"
  | "openMaps"
  | "rememberFact"
  | "recallFacts"
  | "getCurrentTime"
  | "setTimer"
  | "copyToClipboard"
  | "shareText"
  | "vibrate";

export type Fact = { key: string; value: string; at: number };

/* ------------------------------------------------------------------ memory -- */

const MEMORY_KEY = "zoya.facts.v1";

export function loadFacts(): Fact[] {
  try {
    const raw = localStorage.getItem(MEMORY_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as Fact[];
    return Array.isArray(parsed) ? parsed.slice(-60) : [];
  } catch {
    return [];
  }
}

export function saveFacts(facts: Fact[]): void {
  try {
    localStorage.setItem(MEMORY_KEY, JSON.stringify(facts.slice(-60)));
  } catch {
    /* private mode / quota */
  }
}

/* -------------------------------------------------------------- timers -- */

export type TimerHandle = { id: number; label: string; endsAt: number };

const timers = new Map<number, TimerHandle & { timeout: number }>();
let timerSeq = 0;

export function onTimerFired(cb: (t: TimerHandle) => void): void {
  window.addEventListener("zoya:timer", ((e: Event) => {
    cb((e as CustomEvent<TimerHandle>).detail);
  }) as EventListener);
}

/* ---------------------------------------------------------- declarations -- */

export const ZOYA_TOOLS: FunctionDeclaration[] = [
  {
    name: "openWebsite",
    description:
      "Open a website in a new browser tab. Use for 'open YouTube', 'search X on Google', 'go to my portfolio'. Accepts a bare domain ('youtube.com'), a full URL, or a search query.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        url: {
          type: Type.STRING,
          description: "Full URL, bare domain, or plain search query.",
        },
        label: {
          type: Type.STRING,
          description: "What to tell the user this is, e.g. 'YouTube'.",
        },
      },
      required: ["url"],
    },
  },
  {
    name: "searchWeb",
    description:
      "Search the web for a query and open the results. Use when the user asks you to look something up. You cannot read the results back — be honest about that and let them read the page.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        query: { type: Type.STRING, description: "The search query." },
        engine: {
          type: Type.STRING,
          description: "Search engine: google, youtube, wikipedia or github.",
        },
      },
      required: ["query"],
    },
  },
  {
    name: "openMaps",
    description: "Open a place, address or directions in Google Maps.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        query: {
          type: Type.STRING,
          description: "Place name, address, or 'from A to B' style directions query.",
        },
      },
      required: ["query"],
    },
  },
  {
    name: "rememberFact",
    description:
      "Store one durable fact about the user (name, city, preference, deadline, a person in their life). Use it silently when they volunteer something worth keeping.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        key: { type: Type.STRING, description: "Short snake_case key, e.g. 'user_name'." },
        value: { type: Type.STRING, description: "The fact, in one short sentence." },
      },
      required: ["key", "value"],
    },
  },
  {
    name: "recallFacts",
    description:
      "Read back everything you remember about the user. Use when they ask what you know about them or when you need context.",
    parameters: { type: Type.OBJECT, properties: {} },
  },
  {
    name: "getCurrentTime",
    description:
      "Get the user's current local date, time and timezone. Always check this instead of guessing the time or the day.",
    parameters: { type: Type.OBJECT, properties: {} },
  },
  {
    name: "setTimer",
    description:
      "Start an on-device countdown or reminder. When it fires you will be told, and you should deliver the line in character.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        seconds: { type: Type.NUMBER, description: "Duration in seconds. Max 86400." },
        label: {
          type: Type.STRING,
          description: "What it is for, e.g. 'pasta' or 'stretch'.",
        },
      },
      required: ["seconds"],
    },
  },
  {
    name: "copyToClipboard",
    description: "Copy a piece of text to the user's clipboard.",
    parameters: {
      type: Type.OBJECT,
      properties: { text: { type: Type.STRING, description: "The text to copy." } },
      required: ["text"],
    },
  },
  {
    name: "shareText",
    description:
      "Share text using the device share sheet (WhatsApp, SMS, Gmail, anything installed).",
    parameters: {
      type: Type.OBJECT,
      properties: {
        text: { type: Type.STRING, description: "Body of the message." },
        title: { type: Type.STRING, description: "Optional title or subject." },
      },
      required: ["text"],
    },
  },
  {
    name: "vibrate",
    description: "Buzz the phone. Use sparingly, for emphasis.",
    parameters: {
      type: Type.OBJECT,
      properties: {
        pattern: {
          type: Type.STRING,
          description: "short, double or long",
        },
      },
    },
  },
];

/* ------------------------------------------------------------- executor -- */

export type ToolContext = {
  /** Set by a user gesture so openWebsite may use window.open fallback. */
  userGestureWindow: () => Window | null;
};

export type ToolResult = {
  ok: boolean;
  /** One short line for the activity feed. */
  summary: string;
  /** What goes back to the model. Keep it terse. */
  output: Record<string, unknown>;
};

export async function executeTool(
  name: string,
  args: Record<string, unknown>,
  ctx: ToolContext,
): Promise<ToolResult> {
  const str = (k: string): string => {
    const v = args[k];
    return typeof v === "string" ? v.trim() : "";
  };
  const num = (k: string): number => {
    const v = args[k];
    return typeof v === "number" ? v : Number(v) || 0;
  };

  switch (name as ToolName) {
    case "openWebsite": {
      const url = normaliseUrl(str("url"));
      if (!url) return fail("I couldn't work out that address.");
      const label = str("label") || prettyHost(url);
      return openExternal(ctx, url, label);
    }

    case "searchWeb": {
      const q = str("query");
      if (!q) return fail("No search query given.");
      const engine = (str("engine") || "google").toLowerCase();
      const base =
        engine === "youtube"
          ? "https://www.youtube.com/results?search_query="
          : engine === "wikipedia"
            ? "https://en.wikipedia.org/w/index.php?search="
            : engine === "github"
              ? "https://github.com/search?q="
              : "https://www.google.com/search?q=";
      const url = base + encodeURIComponent(q);
      const res = await openExternal(ctx, url, `${engine} results for “${q}”`);
      return {
        ...res,
        output: {
          ...res.output,
          note: "The results are on the user's screen but you cannot read them. Ask what they see.",
        },
      };
    }

    case "openMaps": {
      const q = str("query");
      if (!q) return fail("No place given.");
      const url = `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(q)}`;
      return openExternal(ctx, url, `${q} on the map`);
    }

    case "rememberFact": {
      const key = str("key") || `fact_${Date.now()}`;
      const value = str("value");
      if (!value) return fail("Nothing to remember.");
      const facts = loadFacts().filter((f) => f.key !== key);
      facts.push({ key, value, at: Date.now() });
      saveFacts(facts);
      return ok(`Noted: ${value}`, { stored: true, key });
    }

    case "recallFacts": {
      const facts = loadFacts();
      return ok(
        facts.length ? `Recalled ${facts.length} remembered thing(s)` : "Nothing remembered yet",
        { facts: facts.map((f) => `${f.key}: ${f.value}`), count: facts.length },
      );
    }

    case "getCurrentTime": {
      const now = new Date();
      const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
      return ok("Checked the clock", {
        iso: now.toISOString(),
        local: now.toLocaleString(undefined, {
          weekday: "long",
          year: "numeric",
          month: "long",
          day: "numeric",
          hour: "2-digit",
          minute: "2-digit",
        }),
        timeZone: tz,
        utcOffsetMinutes: -now.getTimezoneOffset(),
      });
    }

    case "setTimer": {
      const seconds = Math.max(1, Math.min(86400, Math.round(num("seconds"))));
      const label = str("label") || "timer";
      const id = ++timerSeq;
      const endsAt = Date.now() + seconds * 1000;
      const timeout = window.setTimeout(() => {
        timers.delete(id);
        window.dispatchEvent(
          new CustomEvent<TimerHandle>("zoya:timer", { detail: { id, label, endsAt } }),
        );
      }, seconds * 1000);
      timers.set(id, { id, label, endsAt, timeout });
      const mins = Math.round(seconds / 6) / 10;
      return ok(
        `Timer set · ${mins >= 1 ? `${mins} min` : `${seconds}s`} (${label})`,
        { ok: true, seconds, label, endsAt: new Date(endsAt).toISOString() },
      );
    }

    case "copyToClipboard": {
      const text = str("text");
      if (!text) return fail("Nothing to copy.");
      try {
        await navigator.clipboard.writeText(text);
        return ok("Copied to clipboard", { copied: true });
      } catch {
        return fail("The browser refused clipboard access.");
      }
    }

    case "shareText": {
      const text = str("text");
      const title = str("title");
      if (!text) return fail("Nothing to share.");
      if (navigator.share) {
        try {
          await navigator.share({ text, title: title || undefined });
          return ok("Opened the share sheet", { shared: true });
        } catch {
          return ok("Share dismissed", { shared: false });
        }
      }
      await navigator.clipboard.writeText(text).catch(() => null);
      return ok("No share sheet here — copied instead", { shared: false, copied: true });
    }

    case "vibrate": {
      const pattern = str("pattern") || "short";
      const ms =
        pattern === "long" ? [400] : pattern === "double" ? [90, 70, 90] : [60];
      navigator.vibrate?.(ms);
      return ok("Buzzed", { vibrated: true });
    }

    default:
      return fail(`Unknown tool: ${name}`);
  }
}

/* --------------------------------------------------------------- helpers -- */

function ok(summary: string, output: Record<string, unknown>): ToolResult {
  return { ok: true, summary, output };
}

/**
 * Open a URL for the user.
 *
 * The window opened during the wake-up tap is a same-origin `about:blank`, so
 * we can (a) navigate it any time later without tripping a popup blocker and
 * (b) put a tiny "Zoya is taking you to…" card in it if navigation is refused.
 * A plain window.open() from a tool call would just be blocked.
 */
async function openExternal(
  ctx: ToolContext,
  url: string,
  label: string,
): Promise<ToolResult> {
  if (!/^https?:\/\//i.test(url)) return fail("Only web addresses can be opened.");

  const win = ctx.userGestureWindow();
  if (win) {
    try {
      win.location.replace(url);
      if (!win.closed) return ok(`Opened ${label}`, { opened: true, url });
    } catch {
      /* cross-origin navigation threw: fall through */
    }
  }

  // No reserved window (popup blocker, iOS standalone, user closed it).
  const fallback = window.open(url, "_blank", "noopener,noreferrer");
  if (fallback) return ok(`Opened ${label}`, { opened: true, url });

  return ok(`Tried to open ${label} — the browser blocked the tab`, {
    opened: false,
    url,
    hint: "Apologise in one line and tell them the link needs a real tap.",
  });
}

function fail(error: string): ToolResult {
  return { ok: false, summary: error, output: { error } };
}

export function normaliseUrl(input: string): string | null {
  const raw = input.trim();
  if (!raw) return null;
  if (/^https?:\/\//i.test(raw)) return raw;
  // Looks like a domain? Then it's a URL, otherwise treat it as a search.
  if (/^[\w-]+(\.[\w-]+)+(\/\S*)?$/i.test(raw)) return `https://${raw}`;
  if (/^localhost(:\d+)?/i.test(raw)) return `http://${raw}`;
  return `https://www.google.com/search?q=${encodeURIComponent(raw)}`;
}

function prettyHost(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return url;
  }
}
