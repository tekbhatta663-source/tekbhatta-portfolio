"use client";

import { useEffect, useState } from "react";
import { getSupabase } from "@/lib/supabase";

const FIELDS = [
  ["display_name", "Display name"],
  ["headline", "Headline"],
  ["tagline", "Tagline"],
  ["location", "Location"],
  ["email", "Public email"],
  ["linkedin_url", "LinkedIn URL"],
  ["github_url", "GitHub URL"],
] as const;

export default function Page() {
  const [row, setRow] = useState<Record<string, string>>({});
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const sb = getSupabase();
    if (!sb) return;
    sb.from("settings").select("*").eq("id", 1).maybeSingle().then(({ data }) => {
      if (data) setRow(data as Record<string, string>);
    });
  }, []);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    const sb = getSupabase();
    if (!sb) return;
    setBusy(true);
    const { error } = await sb.from("settings").upsert({ id: 1, ...row });
    setBusy(false);
    setMsg(error ? error.message : "Saved. The public site picks it up within a minute.");
  }

  return (
    <form className="admin-panel" onSubmit={save}>
      <h2 className="admin-h2">Profile and links</h2>
      {FIELDS.map(([key, label]) => (
        <label className="field" key={key}>
          <span>{label}</span>
          <input value={row[key] ?? ""} onChange={(e) => setRow({ ...row, [key]: e.target.value })} />
        </label>
      ))}
      <label className="field">
        <span>Biography</span>
        <textarea rows={6} value={row.bio ?? ""} onChange={(e) => setRow({ ...row, bio: e.target.value })} />
      </label>
      {msg && <p className={msg.startsWith("Saved") ? "form-msg is-ok" : "form-msg is-err"}>{msg}</p>}
      <button className="btn btn-primary" type="submit" disabled={busy}>{busy ? "Saving…" : "Save settings"}</button>
    </form>
  );
}
