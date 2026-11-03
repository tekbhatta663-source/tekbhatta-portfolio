"use client";

import { useCallback, useEffect, useState } from "react";
import { getSupabase } from "@/lib/supabase";

type Msg = {
  id: string;
  name: string;
  email: string;
  subject: string | null;
  message: string;
  handled: boolean;
  created_at: string;
};

export default function Page() {
  const [rows, setRows] = useState<Msg[]>([]);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    const sb = getSupabase();
    if (!sb) return;
    const { data, error } = await sb.from("contact_messages").select("*").order("created_at", { ascending: false });
    if (error) setErr(error.message);
    else setRows((data as Msg[]) ?? []);
  }, []);

  useEffect(() => { load(); }, [load]);

  async function toggle(row: Msg) {
    await getSupabase()?.from("contact_messages").update({ handled: !row.handled }).eq("id", row.id);
    load();
  }

  async function remove(id: string) {
    if (!confirm("Delete this message?")) return;
    await getSupabase()?.from("contact_messages").delete().eq("id", id);
    load();
  }

  return (
    <div className="admin-panel">
      <h2 className="admin-h2">Contact messages ({rows.length})</h2>
      {err && <p className="form-msg is-err">{err}</p>}
      {rows.length === 0 ? (
        <p className="muted">No messages yet. They appear here once the contact form is wired to Supabase.</p>
      ) : (
        <ul className="admin-list">
          {rows.map((r) => (
            <li key={r.id} style={{ alignItems: "flex-start" }}>
              <div>
                <strong>{r.name}</strong> <span className="muted tiny">{r.email} · {new Date(r.created_at).toLocaleString()}</span>
                {r.subject ? <p className="tiny" style={{ margin: "4px 0 0" }}><strong>{r.subject}</strong></p> : null}
                <p className="muted tiny" style={{ margin: "4px 0 0" }}>{r.message}</p>
                <p className="tiny" style={{ margin: "4px 0 0", color: r.handled ? "var(--accent)" : "var(--muted)" }}>
                  {r.handled ? "handled" : "new"}
                </p>
              </div>
              <div className="admin-row-actions">
                <button className="btn btn-ghost btn-sm" type="button" onClick={() => toggle(r)}>
                  {r.handled ? "Reopen" : "Mark handled"}
                </button>
                <button className="btn btn-ghost btn-sm" type="button" onClick={() => remove(r.id)}>Delete</button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
