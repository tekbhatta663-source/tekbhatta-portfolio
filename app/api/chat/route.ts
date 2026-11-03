import { NextResponse } from "next/server";
import knowledge from "@/data/knowledge.json";

type Fact = { keys: string[]; answer: string };

const SYSTEM = `You are Tek AI, the assistant on Tek Bhatta's portfolio website.
Answer ONLY using the verified facts provided below. If the answer is not contained in them,
say you do not have verified information and point the visitor to tekbhatta663@gmail.com.
Never invent personal details, employers, achievements, dates, certificates or links.
Reply in the same language the visitor used (English, Hindi or Nepali). Keep answers under 90 words.
Verified facts:
${(knowledge.facts as Fact[]).map((f, i) => `${i + 1}. ${f.answer}`).join("\n")}`;

function normalize(s: string) {
  return s.toLowerCase().replace(/[^\p{L}\p{N}\s]/gu, " ");
}

function localAnswer(message: string): string {
  const q = normalize(message);
  const words = q.split(/\s+/).filter((w) => w.length > 2);
  let best: { score: number; answer: string } = { score: 0, answer: "" };

  for (const fact of knowledge.facts as Fact[]) {
    let score = 0;
    for (const key of fact.keys) {
      const k = normalize(key).trim();
      if (!k) continue;
      if (q.includes(k)) score += k.includes(" ") ? 3 : 2;
      else if (words.some((w) => k.startsWith(w) || w.startsWith(k))) score += 1;
    }
    if (score > best.score) best = { score, answer: fact.answer };
  }

  if (best.score >= 2) return best.answer;

  if (/^(hi|hello|hey|namaste|नमस्ते|नमस्कार)\b/i.test(message.trim())) {
    return knowledge.greeting;
  }
  return knowledge.unavailable;
}

export async function POST(req: Request) {
  let body: { message?: string; lang?: string };
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "Invalid JSON body" }, { status: 400 });
  }

  const message = (body.message || "").toString().trim().slice(0, 500);
  if (!message) return NextResponse.json({ error: "Message is required" }, { status: 400 });

  const key = process.env.OPENAI_API_KEY;
  const fallback = localAnswer(message);

  if (!key) {
    return NextResponse.json({
      reply: fallback,
      mode: "demo",
      note: "Demo mode: answers come from the local knowledge base. Set OPENAI_API_KEY to enable the LLM.",
    });
  }

  const langHint =
    body.lang === "hi" ? "Reply in Hindi." : body.lang === "ne" ? "Reply in Nepali." : "Reply in the visitor's language.";

  try {
    const res = await fetch("https://api.openai.com/v1/chat/completions", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${key}`,
      },
      body: JSON.stringify({
        model: process.env.OPENAI_MODEL || "gpt-4o-mini",
        temperature: 0.2,
        max_tokens: 260,
        messages: [
          { role: "system", content: SYSTEM + "\n" + langHint },
          { role: "user", content: message },
        ],
      }),
    });

    if (!res.ok) {
      return NextResponse.json({ reply: fallback, mode: "fallback" });
    }

    const data = await res.json();
    const reply = data?.choices?.[0]?.message?.content?.trim();
    return NextResponse.json({ reply: reply || fallback, mode: "llm" });
  } catch {
    return NextResponse.json({ reply: fallback, mode: "fallback" });
  }
}
