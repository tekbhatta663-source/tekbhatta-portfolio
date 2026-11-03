"use client";

import { useCallback, useEffect, useState } from "react";
import { getSupabase } from "@/lib/supabase";

type Item = { name: string; url: string; size: number };

export default function Page() {
  const [items, setItems] = useState<Item[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const sb = getSupabase();
    if (!sb) return;
    const { data, error } = await sb.storage.from("media").list("", { limit: 100, sortBy: { column: "created_at", order: "desc" } });
    if (error) return setErr(error.message);
    const mapped = (data ?? [])
      .filter((f) => f.name && !f.name.endsWith("/"))
      .map((f) => ({
        name: f.name,
        url: sb.storage.from("media").getPublicUrl(f.name).data.publicUrl,
        size: Number(f.metadata?.size ?? 0),
      }));
    setItems(mapped);
  }, []);

  useEffect(() => { load(); }, [load]);

  async function upload(file: File) {
    const sb = getSupabase();
    if (!sb) return;
    setBusy(true);
    setErr(null);
    const path = `${Date.now()}-${file.name.replace(/[^a-zA-Z0-9._-]/g, "_")}`;
    const { error } = await sb.storage.from("media").upload(path, file);
    setBusy(false);
    if (error) setErr(error.message);
    else load();
  }

  async function remove(name: string) {
    if (!confirm(`Delete ${name}?`)) return;
    const { error } = await getSupabase()?.storage.from("media").remove([name]) ?? { error: null };
    if (error) setErr(error.message);
    else load();
  }

  return (
    <div className="admin-panel">
      <h2 className="admin-h2">Media library</h2>
      <p className="muted tiny">Images, PDFs and videos go to the public media bucket. Maximum 50 MB per file.</p>
      <label className="field">
        <span>Upload a file</span>
        <input
          type="file"
          accept="image/*,application/pdf,video/*"
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) upload(f);
          }}
        />
      </label>
      {busy && <p className="muted tiny">Uploading…</p>}
      {err && <p className="form-msg is-err">{err}</p>}
      {items.length === 0 ? (
        <p className="muted">Nothing uploaded yet.</p>
      ) : (
        <ul className="admin-list">
          {items.map((i) => (
            <li key={i.name}>
              <div>
                <a href={i.url} target="_blank" rel="noreferrer" className="tiny">{i.name}</a>
                <span className="muted tiny"> · {(i.size / 1024).toFixed(0)} KB</span>
              </div>
              <div className="admin-row-actions">
                <button className="btn btn-ghost btn-sm" type="button" onClick={() => navigator.clipboard.writeText(i.url)}>Copy URL</button>
                <button className="btn btn-ghost btn-sm" type="button" onClick={() => remove(i.name)}>Delete</button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
