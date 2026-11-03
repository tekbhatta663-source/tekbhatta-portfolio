"use client";

import { useCallback, useEffect, useState } from "react";
import { getSupabase } from "@/lib/supabase";

export type Field = {
  key: string;
  label: string;
  type?: "text" | "textarea" | "number" | "tags" | "select" | "url" | "file";
  options?: string[];
  bucket?: string;
  placeholder?: string;
};

type Row = Record<string, unknown> & { id?: string };

export default function CrudEditor({
  table,
  fields,
  title,
  emptyHint,
}: {
  table: string;
  fields: Field[];
  title: string;
  emptyHint?: string;
}) {
  const [rows, setRows] = useState<Row[]>([]);
  const [draft, setDraft] = useState<Row>({});
  const [editing, setEditing] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    const sb = getSupabase();
    if (!sb) return;
    setLoading(true);
    const { data, error } = await sb.from(table).select("*").order("created_at", { ascending: false });
    setLoading(false);
    if (error) setMsg(error.message);
    else setRows((data as Row[]) ?? []);
  }, [table]);

  useEffect(() => {
    load();
  }, [load]);

  function set(key: string, value: unknown) {
    setDraft((d) => ({ ...d, [key]: value }));
  }

  function startEdit(row: Row) {
    setEditing(row.id ?? null);
    setDraft({ ...row });
    setMsg(null);
  }

  function reset() {
    setEditing(null);
    setDraft({});
    setMsg(null);
  }

  async function save(e: React.FormEvent) {
    e.preventDefault();
    const sb = getSupabase();
    if (!sb) return;
    setBusy(true);
    setMsg(null);
    const payload: Row = {};
    for (const f of fields) {
      const v = draft[f.key];
      payload[f.key] =
        f.type === "number" ? (v === "" || v === undefined ? null : Number(v))
        : f.type === "tags" ? String(v ?? "").split(",").map((s) => s.trim()).filter(Boolean)
        : v ?? null;
    }
    const res = editing
      ? await sb.from(table).update(payload).eq("id", editing)
      : await sb.from(table).insert(payload);
    setBusy(false);
    if (res.error) return setMsg(res.error.message);
    reset();
    load();
  }

  async function remove(id?: string) {
    if (!id) return;
    const sb = getSupabase();
    if (!sb) return;
    if (!confirm("Delete this item? This cannot be undone.")) return;
    const { error } = await sb.from(table).delete().eq("id", id);
    if (error) setMsg(error.message);
    else load();
  }

  async function upload(field: Field, file: File) {
    const sb = getSupabase();
    if (!sb) return;
    setBusy(true);
    const path = `${Date.now()}-${file.name.replace(/[^a-zA-Z0-9._-]/g, "_")}`;
    const { error } = await sb.storage.from(field.bucket ?? "media").upload(path, file, { upsert: false });
    if (error) {
      setBusy(false);
      return setMsg(error.message);
    }
    const { data } = sb.storage.from(field.bucket ?? "media").getPublicUrl(path);
    set(field.key, data.publicUrl);
    setBusy(false);
  }

  return (
    <div className="admin-grid">
      <form className="admin-panel" onSubmit={save}>
        <h2 className="admin-h2">{editing ? `Edit ${title}` : `Add ${title}`}</h2>
        {fields.map((f) => (
          <label className="field" key={f.key}>
            <span>{f.label}</span>
            {f.type === "textarea" ? (
              <textarea rows={4} value={String(draft[f.key] ?? "")} onChange={(e) => set(f.key, e.target.value)} />
            ) : f.type === "select" ? (
              <select value={String(draft[f.key] ?? "")} onChange={(e) => set(f.key, e.target.value)}>
                <option value="">Choose…</option>
                {(f.options ?? []).map((o) => (
                  <option key={o} value={o}>{o}</option>
                ))}
              </select>
            ) : f.type === "file" ? (
              <span className="file-row">
                <input
                  type="file"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) upload(f, file);
                  }}
                />
                {draft[f.key] ? <span className="muted tiny">uploaded</span> : null}
              </span>
            ) : (
              <input
                type={f.type === "number" ? "number" : "text"}
                placeholder={f.placeholder}
                value={String(draft[f.key] ?? "")}
                onChange={(e) => set(f.key, e.target.value)}
              />
            )}
          </label>
        ))}
        {msg && <p className="form-msg is-err">{msg}</p>}
        <div className="admin-actions">
          <button className="btn btn-primary" type="submit" disabled={busy}>
            {busy ? "Saving…" : editing ? "Save changes" : `Add ${title}`}
          </button>
          {editing && (
            <button type="button" className="btn btn-ghost" onClick={reset}>
              Cancel
            </button>
          )}
        </div>
      </form>

      <div className="admin-panel">
        <h2 className="admin-h2">{title} ({rows.length})</h2>
        {loading ? (
          <p className="muted">Loading…</p>
        ) : rows.length === 0 ? (
          <p className="muted">{emptyHint ?? "Nothing here yet."}</p>
        ) : (
          <ul className="admin-list">
            {rows.map((r) => (
              <li key={String(r.id)}>
                <div>
                  <strong>{String(r[fields[0].key] ?? "Untitled")}</strong>
                  <span className="muted tiny"> {String(r[fields[1]?.key] ?? "")}</span>
                </div>
                <div className="admin-row-actions">
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => startEdit(r)}>Edit</button>
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => remove(r.id)}>Delete</button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
