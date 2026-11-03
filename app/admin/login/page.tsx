"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { getSupabase, isConfigured } from "@/lib/supabase";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setMsg(null);
    const sb = getSupabase();
    if (!sb) {
      setMsg("Supabase is not configured yet. Add your project URL and anon key first.");
      return;
    }
    setBusy(true);
    const { error } = await sb.auth.signInWithPassword({ email, password });
    setBusy(false);
    if (error) setMsg(error.message);
    else router.replace("/admin");
  }

  return (
    <div className="admin-login">
      <form className="admin-panel" onSubmit={submit}>
        <h1 className="admin-h1">Admin sign in</h1>
        <p className="muted" style={{ marginTop: 0 }}>
          {isConfigured
            ? "Use the email and password of the Supabase user you created."
            : "Supabase is not configured on this deployment yet."}
        </p>
        <label className="field">
          <span>Email</span>
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" />
        </label>
        <label className="field">
          <span>Password</span>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required autoComplete="current-password" />
        </label>
        {msg && <p className="form-msg is-err">{msg}</p>}
        <button className="btn btn-primary" type="submit" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
