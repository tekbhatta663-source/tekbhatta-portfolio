"use client";

import { useState } from "react";
import { getSupabase } from "@/lib/supabase";

type State = { kind: "idle" | "sending" | "ok" | "err"; message?: string };

export default function ContactForm() {
  const [form, setForm] = useState({ name: "", email: "", subject: "", message: "", honeypot: "" });
  const [state, setState] = useState<State>({ kind: "idle" });

  function update(key: keyof typeof form, value: string) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setState({ kind: "sending" });
    try {
      if (form.honeypot) {
        setState({ kind: "ok", message: "Message sent. I'll reply soon." });
        return;
      }

      const sb = getSupabase();
      if (sb) {
        const { error } = await sb.from("contact_messages").insert({
          name: form.name.trim(),
          email: form.email.trim(),
          subject: form.subject.trim(),
          message: form.message.trim(),
        });
        if (error) {
          setState({ kind: "err", message: "Could not save your message. Please email tekbhatta663@gmail.com." });
          return;
        }
        setState({ kind: "ok", message: "Message saved and waiting in the inbox. I'll reply by email." });
        setForm({ name: "", email: "", subject: "", message: "", honeypot: "" });
        return;
      }

      const endpoint = process.env.NEXT_PUBLIC_CONTACT_ENDPOINT;
      if (!endpoint) {
        setState({
          kind: "err",
          message: "Form delivery is not connected yet. Email tekbhatta663@gmail.com directly and it reaches me today.",
        });
        return;
      }
      const res = await fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(form),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        setState({ kind: "err", message: data.error || "Something went wrong. Please email tekbhatta663@gmail.com instead." });
        return;
      }
      setState({ kind: "ok", message: data.message || "Message sent. I'll reply soon." });
      setForm({ name: "", email: "", subject: "", message: "", honeypot: "" });
    } catch {
      setState({ kind: "err", message: "Network error. You can email tekbhatta663@gmail.com directly." });
    }
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="field">
        <label className="label" htmlFor="name">Name</label>
        <input id="name" value={form.name} onChange={(e) => update("name", e.target.value)} required autoComplete="name" />
      </div>
      <div className="field">
        <label className="label" htmlFor="email">Email</label>
        <input id="email" type="email" value={form.email} onChange={(e) => update("email", e.target.value)} required autoComplete="email" />
      </div>
      <div className="field">
        <label className="label" htmlFor="subject">Subject</label>
        <input id="subject" value={form.subject} onChange={(e) => update("subject", e.target.value)} required />
      </div>
      <div className="field">
        <label className="label" htmlFor="message">Message</label>
        <textarea id="message" value={form.message} onChange={(e) => update("message", e.target.value)} required maxLength={2000} />
        <span className="hint">{form.message.length}/2000</span>
      </div>

      <input
        tabIndex={-1}
        autoComplete="off"
        aria-hidden="true"
        value={form.honeypot}
        onChange={(e) => update("honeypot", e.target.value)}
        style={{ position: "absolute", left: "-9999px", width: 1, height: 1, opacity: 0 }}
      />

      <button className="btn btn-primary" type="submit" disabled={state.kind === "sending"} style={{ width: "100%" }}>
        {state.kind === "sending" ? "Sending..." : "Send Message"}
      </button>

      {state.kind === "ok" && <div className="status ok" role="status">{state.message}</div>}
      {state.kind === "err" && <div className="status err" role="alert">{state.message}</div>}
    </form>
  );
}
