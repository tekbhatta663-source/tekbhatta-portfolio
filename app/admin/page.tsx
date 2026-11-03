"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { getSupabase } from "@/lib/supabase";

export default function Page() {
  const [counts, setCounts] = useState<Record<string, number | string>>({});

  useEffect(() => {
    const sb = getSupabase();
    if (!sb) return;
    (async () => {
      const out: Record<string, number | string> = {};
      for (const t of ["projects", "skills", "certifications"]) {
        const { count } = await sb.from(t).select("*", { count: "exact", head: true });
        out[t] = count ?? 0;
      }
      const { count: unread } = await sb
        .from("contact_messages")
        .select("*", { count: "exact", head: true })
        .eq("handled", false);
      out.messages = unread ?? 0;
      setCounts(out);
    })();
  }, []);

  return (
    <div>
      <h1 className="admin-h1">Your portfolio, without touching code</h1>
      <p className="muted">Everything you change here appears on the public site within a minute.</p>
      <div className="admin-cards">
        <Link href="/admin/projects" className="admin-card"><strong>{counts.projects ?? "–"}</strong><span>Projects</span></Link>
        <Link href="/admin/skills" className="admin-card"><strong>{counts.skills ?? "–"}</strong><span>Skills</span></Link>
        <Link href="/admin/certifications" className="admin-card"><strong>{counts.certifications ?? "–"}</strong><span>Certificates</span></Link>
        <Link href="/admin/messages" className="admin-card"><strong>{counts.messages ?? "–"}</strong><span>New messages</span></Link>
      </div>
      <div className="admin-panel" style={{ marginTop: 18 }}>
        <h2 className="admin-h2">First-time checklist</h2>
        <ol className="tiny admin-steps">
          <li>Add your profile photo through Media, then paste its URL into Settings.</li>
          <li>Publish one real project, even this portfolio site.</li>
          <li>Only list skills you can actually show.</li>
          <li>Never upload a certificate you do not hold.</li>
        </ol>
      </div>
    </div>
  );
}
